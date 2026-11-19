package io.github.dbconsole.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dbconsole.audit.AuditService;
import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.dialect.ResultEditSql;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.resultedit.ResultEditResponses;
import io.github.dbconsole.transaction.TransactionManager;
import io.github.dbconsole.transaction.TransactionStateException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 语句执行编舞不变式（F-17 深化第一笔）：经 {@link ExecutionOrchestrator#run} 这唯一
 * interface 断言四条不变式——审计只在真实执行落盘、槽位在失败时也释放、
 * 确认门不为未通过的批次放行、互斥守卫先于一切执行副作用。
 * 第二笔迁入其余形态后，本类按形态扩展为表驱动。
 */
class ExecutionOrchestratorTest {

  private static final String SESSION_TOKEN = "session-token";
  private static final String USERNAME = "admin";

  private final AuditService audit = mock(AuditService.class);
  private final TransactionManager transactions = mock(TransactionManager.class);
  private final ResultEditExecutor batchExecutor = mock(ResultEditExecutor.class);

  private DbConsoleProperties properties;
  private ConfirmationService confirmations;
  private RunningQueryRegistry runningQueries;
  private ResolvedDataSourceFactory sources;
  private ExecutionOrchestrator orchestrator;

  /** 真实 DataSourceRegistry + 属性的本地小工厂（测试内复用 properties 的双源定义）。 */
  private static final class ResolvedDataSourceFactory {
    private final DataSourceRegistry registry;

    private ResolvedDataSourceFactory(DbConsoleProperties properties) {
      this.registry = new DataSourceRegistry(properties, ref -> "db-secret");
    }

    io.github.dbconsole.jdbc.ResolvedDataSource dev() {
      return registry.resolve("mysql-work-note");
    }
  }

  @BeforeEach
  void setUp() {
    properties = properties();
    confirmations = new ConfirmationService();
    runningQueries = new RunningQueryRegistry(properties.execution().maxConcurrency());
    sources = new ResolvedDataSourceFactory(properties);
    orchestrator =
        new ExecutionOrchestrator(
            confirmations, runningQueries, audit, transactions, properties, batchExecutor);
  }

  private ExecutionForm.EditBatchForm batchForm(
      List<ResultEditExecutor.PlannedStatement> planned, List<String> tokens) {
    return new ExecutionForm.EditBatchForm(
        sources.dev(), SESSION_TOKEN, USERNAME, "结果编辑保存使用独立短事务，请先提交或回滚当前事务",
        planned, tokens);
  }

  private static ResultEditExecutor.PlannedStatement delete(int id) {
    return new ResultEditExecutor.PlannedStatement(
        0,
        ResultEditSql.delete(
            DbConsoleProperties.DatabaseType.MYSQL, DbConsoleProperties.CompatibilityMode.NATIVE,
            List.of("orders"), List.of("id"), List.of(id)));
  }

  @Test
  @DisplayName("确认门未通过：返回预览与凭据，不登记、不执行、不落审计")
  void confirmationGateBlocksBeforeAnyExecutionSideEffect() {
    ResultEditResponses response = orchestrator.run(batchForm(List.of(delete(1)), null));

    assertThat(response).isInstanceOf(ResultEditResponses.ConfirmationRequired.class);
    ResultEditResponses.ConfirmationRequired required = (ResultEditResponses.ConfirmationRequired) response;
    assertThat(required.statements()).hasSize(1);
    assertThat(required.statements().get(0).confirmationToken()).isNotBlank();
    verifyNoInteractions(batchExecutor);
    verifyNoAudit();
    // 槽位未被占用：同会话同源可立即再登记
    RunningQuery probe = runningQueries.register(SESSION_TOKEN, sources.dev().id());
    runningQueries.complete(probe);
  }

  @Test
  @DisplayName("凭据校验通过才执行：确认后放行并落 SUCCEEDED 审计（主语为段计数汇总）")
  void verifiedTokensExecuteAndAuditOnce() {
    ResultEditResponses.ConfirmationRequired preview =
        (ResultEditResponses.ConfirmationRequired)
            orchestrator.run(batchForm(List.of(delete(1)), null));
    List<String> tokens =
        preview.statements().stream()
            .map(ResultEditResponses.StatementPreview::confirmationToken)
            .toList();
    when(batchExecutor.execute(any(), anyList(), any(), anyInt()))
        .thenReturn(new ResultEditResponses.Applied(List.of(), 3));

    ResultEditResponses applied = orchestrator.run(batchForm(List.of(delete(1)), tokens));

    assertThat(applied).isInstanceOf(ResultEditResponses.Applied.class);
    verify(batchExecutor).execute(any(), anyList(), any(), anyInt());
    verify(audit)
        .recordExecution(eq(USERNAME), eq(sources.dev().id()), contains("DELETE 1"),
            anyLong(), eq("SUCCEEDED"));
  }

  @Test
  @DisplayName("执行 arm 抛运行时异常：记 FAILED 审计、异常上抛、槽位仍释放")
  void runtimeFailureAuditsFailedAndReleasesSlot() {
    when(batchExecutor.execute(any(), anyList(), any(), anyInt()))
        .thenThrow(new IllegalStateException("连接打开失败：refused"));

    ExecutionForm.EditBatchForm form =
        batchForm(
            List.of(delete(1)),
            // 先取有效凭据再触发失败路径
            tokensFor(List.of(delete(1))));
    assertThatThrownBy(() -> orchestrator.run(form))
        .isInstanceOf(IllegalStateException.class);

    verify(audit)
        .recordExecution(eq(USERNAME), eq(sources.dev().id()), any(), anyLong(), eq("FAILED"));
    // 槽位已释放：登记不再冲突
    RunningQuery probe = runningQueries.register(SESSION_TOKEN, sources.dev().id());
    runningQueries.complete(probe);
  }

  @Test
  @DisplayName("互斥守卫先于一切执行副作用：守卫拒绝时不登记、不执行、不落审计")
  void mutexGuardRejectsBeforeAnySideEffect() {
    org.mockito.Mockito.doThrow(
            new TransactionStateException(
                TransactionManager.CODE_TRANSACTION_OPEN, "结果编辑保存使用独立短事务"))
        .when(transactions)
        .requireNoOpenTransaction(any(), any(), any());

    assertThatThrownBy(() -> orchestrator.run(batchForm(List.of(delete(1)), null)))
        .isInstanceOf(TransactionStateException.class);

    verifyNoInteractions(batchExecutor);
    verifyNoAudit();
  }

  private List<String> tokensFor(List<ResultEditExecutor.PlannedStatement> planned) {
    ResultEditResponses.ConfirmationRequired preview =
        (ResultEditResponses.ConfirmationRequired) orchestrator.run(batchForm(planned, null));
    return preview.statements().stream()
        .map(ResultEditResponses.StatementPreview::confirmationToken)
        .toList();
  }

  private void verifyNoInteractions(ResultEditExecutor executor) {
    verify(executor, never()).execute(any(), anyList(), any(), anyInt());
  }

  private void verifyNoAudit() {
    verify(audit, never()).recordExecution(any(), any(), any(), anyLong(), any());
  }

  private static DbConsoleProperties properties() {
    DbConsoleProperties.DataSourceProperties dev =
        new DbConsoleProperties.DataSourceProperties(
            "mysql-work-note",
            "MySQL 测试库",
            DbConsoleProperties.DataSourceEnvironment.DEVELOPMENT,
            DbConsoleProperties.DatabaseType.MYSQL,
            DbConsoleProperties.CompatibilityMode.NATIVE,
            "jdbc:mysql://127.0.0.1:1/never",
            "db-user",
            "DB_CREDENTIAL");
    return new DbConsoleProperties(
        new DbConsoleProperties.LoginProperties(USERNAME, "test-secret"),
        new DbConsoleProperties.SessionProperties(Duration.ofHours(8)),
        new DbConsoleProperties.ExecutionProperties(
            Duration.ofSeconds(30), 20_000, 8, 100, 1_000, 65_536, 10_485_760),
        new DbConsoleProperties.TransactionProperties(Duration.ofMinutes(10)),
        new DbConsoleProperties.AuditProperties("./target/test-audit", Duration.ofDays(30)),
        null,
        null,
        List.of(dev));
  }
}
