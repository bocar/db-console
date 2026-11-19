package io.github.dbconsole.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.dbconsole.audit.AuditService;
import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.dialect.ResultEditSql;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.metadata.TableStructure;
import io.github.dbconsole.metadata.TableStructureService;
import io.github.dbconsole.resultedit.ResultEditResponses;
import io.github.dbconsole.transaction.TransactionManager;
import io.github.dbconsole.transaction.TransactionStateException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 语句执行编舞（interface = {@link ExecutionOrchestrator#run}）：
 * 四条不变式（审计只在真实执行落盘、槽位失败也释放、确认门拦截零副作用、互斥先于一切）
 * 与单语句编舞语义（DESC 特例、事务归属分派、终态分类、并发上限、脚本互斥）。
 */
class ExecutionOrchestratorTest {

  private static final String SESSION_TOKEN = "session-token";
  private static final String USERNAME = "admin";
  private static final String SOURCE_ID = "mysql-work-note";

  private final TransactionManager transactions = mock(TransactionManager.class);
  private final TableStructureService tableStructures = mock(TableStructureService.class);
  private final AuditService audit = mock(AuditService.class);
  private final StatementExecution statementExecution = mock(StatementExecution.class);
  private final ResultEditExecutor batchExecutor = mock(ResultEditExecutor.class);

  private DbConsoleProperties properties;
  private ConfirmationService confirmations;
  private DataSourceRegistry registry;
  private ResolvedDataSource source;

  @BeforeEach
  void setUp() {
    properties = properties(8);
    confirmations = new ConfirmationService();
    registry = new DataSourceRegistry(properties, ref -> "db-secret");
    source = registry.resolve(SOURCE_ID);
  }

  private ExecutionOrchestrator orchestrator() {
    return orchestrator(new RunningQueryRegistry(properties.execution().maxConcurrency()));
  }

  private ExecutionOrchestrator orchestrator(RunningQueryRegistry runningQueries) {
    return new ExecutionOrchestrator(
        registry, confirmations, runningQueries, transactions, tableStructures, audit,
        properties, statementExecution, batchExecutor);
  }

  private SqlResponses workbench(String sql, String token) {
    return orchestrator()
        .run(new ExecutionForm.WorkbenchQueryForm(SESSION_TOKEN, USERNAME, SOURCE_ID, sql, token));
  }

  private SqlResponses script(String sql, String token) {
    return orchestrator()
        .run(new ExecutionForm.ScriptStatementForm(SESSION_TOKEN, USERNAME, SOURCE_ID, sql, token));
  }

  // ------------------------------------------------------------------ 编舞不变式

  @Nested
  @DisplayName("编舞不变式（批次形态）")
  class BatchInvariants {

    private ExecutionForm.EditBatchForm batchForm(
        List<ResultEditExecutor.PlannedStatement> planned, List<String> tokens) {
      return new ExecutionForm.EditBatchForm(
          source, SESSION_TOKEN, USERNAME, planned, tokens);
    }

    private ResultEditExecutor.PlannedStatement delete(int id) {
      return new ResultEditExecutor.PlannedStatement(
          0,
          ResultEditSql.delete(
              new io.github.dbconsole.dialect.Dialect(DbConsoleProperties.DatabaseType.MYSQL, DbConsoleProperties.CompatibilityMode.NATIVE),
              List.of("orders"), List.of("id"), List.of(id)));
    }

    @Test
    @DisplayName("确认门未通过：返回预览与凭据，不登记、不执行、不落审计")
    void confirmationGateBlocksBeforeAnyExecutionSideEffect() {
      ResultEditResponses response = orchestrator().run(batchForm(List.of(delete(1)), null));

      assertThat(response).isInstanceOf(ResultEditResponses.ConfirmationRequired.class);
      ResultEditResponses.ConfirmationRequired required =
          (ResultEditResponses.ConfirmationRequired) response;
      assertThat(required.statements()).hasSize(1);
      assertThat(required.statements().get(0).confirmationToken()).isNotBlank();
      verifyNoBatchExecution();
      verifyNoAudit();
      // 槽位未被占用：同会话同源可立即再登记
      RunningQueryRegistry runningQueries =
          new RunningQueryRegistry(properties.execution().maxConcurrency());
      orchestrator(runningQueries).run(batchForm(List.of(delete(1)), null));
      RunningQuery probe = runningQueries.register(SESSION_TOKEN, source.id());
      runningQueries.complete(probe);
    }

    @Test
    @DisplayName("凭据校验通过才执行：确认后放行并落 SUCCEEDED 审计（主语为段计数汇总）")
    void verifiedTokensExecuteAndAuditOnce() {
      ExecutionOrchestrator orchestrator = orchestrator();
      ResultEditResponses.ConfirmationRequired preview =
          (ResultEditResponses.ConfirmationRequired)
              orchestrator.run(batchForm(List.of(delete(1)), null));
      List<String> tokens =
          preview.statements().stream()
              .map(ResultEditResponses.StatementPreview::confirmationToken)
              .toList();
      when(batchExecutor.execute(any(), anyList(), any(), anyInt()))
          .thenReturn(new ResultEditResponses.Applied(List.of(), 3));

      ResultEditResponses applied =
          orchestrator.run(batchForm(List.of(delete(1)), tokens));

      assertThat(applied).isInstanceOf(ResultEditResponses.Applied.class);
      verify(batchExecutor).execute(any(), anyList(), any(), anyInt());
      verify(audit)
          .recordExecution(eq(USERNAME), eq(SOURCE_ID), contains("DELETE 1"),
              anyLong(), eq("SUCCEEDED"));
    }

    @Test
    @DisplayName("执行 arm 抛运行时异常：记 FAILED 审计、异常上抛、槽位仍释放")
    void runtimeFailureAuditsFailedAndReleasesSlot() {
      RunningQueryRegistry runningQueries =
          new RunningQueryRegistry(properties.execution().maxConcurrency());
      ExecutionOrchestrator orchestrator = orchestrator(runningQueries);
      ResultEditResponses.ConfirmationRequired preview =
          (ResultEditResponses.ConfirmationRequired)
              orchestrator.run(batchForm(List.of(delete(1)), null));
      List<String> tokens =
          preview.statements().stream()
              .map(ResultEditResponses.StatementPreview::confirmationToken)
              .toList();
      when(batchExecutor.execute(any(), anyList(), any(), anyInt()))
          .thenThrow(new IllegalStateException("连接打开失败：refused"));

      assertThatThrownBy(() -> orchestrator.run(batchForm(List.of(delete(1)), tokens)))
          .isInstanceOf(IllegalStateException.class);

      verify(audit)
          .recordExecution(eq(USERNAME), eq(SOURCE_ID), any(), anyLong(), eq("FAILED"));
      RunningQuery probe = runningQueries.register(SESSION_TOKEN, source.id());
      runningQueries.complete(probe);
    }

    @Test
    @DisplayName("互斥守卫先于一切执行副作用：守卫拒绝时不登记、不执行、不落审计")
    void mutexGuardRejectsBeforeAnySideEffect() {
      doThrow(
              new TransactionStateException(
                  TransactionManager.CODE_TRANSACTION_OPEN, "结果编辑保存使用独立短事务"))
          .when(transactions)
          .requireNoOpenTransaction(any(), any(), any());

      assertThatThrownBy(() -> orchestrator().run(batchForm(List.of(delete(1)), null)))
          .isInstanceOf(TransactionStateException.class);

      verifyNoBatchExecution();
      verifyNoAudit();
    }

    private void verifyNoBatchExecution() {
      verify(batchExecutor, never()).execute(any(), anyList(), any(), anyInt());
    }
  }

  // ------------------------------------------------------------------ 单语句编舞（原 SqlExecutionServiceTest 迁入）

  @Nested
  @DisplayName("单语句编舞（工作台 / 脚本 / 翻页）")
  class SingleStatement {

    @Test
    @DisplayName("写入执行成功记录 SUCCEEDED 审计且只记一次")
    void successfulWriteRecordsSucceededAudit() {
      String sql = "UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1";
      when(statementExecution.execute(any()))
          .thenReturn(new SqlResponses.UpdateResult(1, 5L, false, false));

      SqlResponses response = workbench(sql, null);

      assertThat(response).isInstanceOf(SqlResponses.UpdateResult.class);
      verify(audit).recordExecution(eq(USERNAME), eq(SOURCE_ID), eq(sql), anyLong(), eq("SUCCEEDED"));
      verifyNoMoreInteractions(audit);
    }

    @Test
    @DisplayName("DESC 执行成功也记录 SUCCEEDED 审计")
    void describeSuccessRecordsSucceededAudit() {
      when(tableStructures.tableStructure(SOURCE_ID, "dbconsole_test_t"))
          .thenReturn(new TableStructure("dbconsole_test_t", null, List.of(), List.of(), List.of()));

      SqlResponses response = workbench("DESC dbconsole_test_t", null);

      assertThat(response).isInstanceOf(SqlResponses.QueryResult.class);
      verify(audit).recordExecution(eq(USERNAME), eq(SOURCE_ID), any(), anyLong(), eq("SUCCEEDED"));
      verifyNoMoreInteractions(audit);
    }

    @Test
    @DisplayName("DESC 结构读取失败记录 FAILED 审计")
    void describeFailureRecordsFailedAudit() {
      when(tableStructures.tableStructure(SOURCE_ID, "dbconsole_test_t"))
          .thenThrow(new IllegalArgumentException("table not found"));

      assertThatThrownBy(() -> workbench("DESC dbconsole_test_t", null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("table not found");

      verify(audit).recordExecution(eq(USERNAME), eq(SOURCE_ID), any(), anyLong(), eq("FAILED"));
      verifyNoMoreInteractions(audit);
    }

    @Test
    @DisplayName("执行 arm 抛运行时异常记录 FAILED 审计")
    void executionArmFailureRecordsFailedAudit() {
      when(statementExecution.execute(any()))
          .thenThrow(new IllegalStateException("连接打开失败：refused"));

      assertThatThrownBy(
              () -> workbench("UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1", null))
          .isInstanceOf(IllegalStateException.class);

      verify(audit).recordExecution(eq(USERNAME), eq(SOURCE_ID), any(), anyLong(), eq("FAILED"));
      verifyNoMoreInteractions(audit);
    }

    @Test
    @DisplayName("写入超时且结果无法确认时记录 UNKNOWN 审计")
    void writeTimeoutRecordsUnknownAudit() {
      when(statementExecution.execute(any()))
          .thenReturn(new SqlResponses.UnknownOutcome("结果未知"));

      SqlResponses response =
          workbench("UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1", null);

      assertThat(response).isInstanceOf(SqlResponses.UnknownOutcome.class);
      verify(audit).recordExecution(eq(USERNAME), eq(SOURCE_ID), any(), anyLong(), eq("UNKNOWN"));
      verifyNoMoreInteractions(audit);
    }

    @Test
    @DisplayName("同一数据源执行并发超过上限时明确拒绝（F-10）")
    void concurrencyBeyondLimitIsRejectedExplicitly() {
      properties = properties(1);
      registry = new DataSourceRegistry(properties, ref -> "db-secret");
      source = registry.resolve(SOURCE_ID);
      RunningQueryRegistry runningQueries =
          new RunningQueryRegistry(properties.execution().maxConcurrency());
      ExecutionOrchestrator orchestrator = orchestrator(runningQueries);
      when(statementExecution.execute(any()))
          .thenReturn(new SqlResponses.QueryResult(List.of(), List.of(), 0, false));

      orchestrator.run(
          new ExecutionForm.WorkbenchQueryForm(SESSION_TOKEN, USERNAME, SOURCE_ID, "SELECT 1", null));

      assertThatThrownBy(
              () -> orchestrator.run(
                  new ExecutionForm.WorkbenchQueryForm(
                      "session-token-2", USERNAME, SOURCE_ID, "SELECT 2", null)))
          .isInstanceOfSatisfying(
              SqlRejectException.class,
              rejected -> assertThat(rejected.code()).isEqualTo("CONCURRENCY_EXCEEDED"))
          .hasMessageContaining("并发");
    }

    @Test
    @DisplayName("有未提交事务时隐式提交 DDL 先于确认流程被拦截，且不落审计（F-16）")
    void implicitCommitDdlWithOpenTransactionIsRejectedBeforeConfirmation() {
      when(transactions.connectionFor(SESSION_TOKEN, SOURCE_ID))
          .thenReturn(mock(java.sql.Connection.class));

      assertThatThrownBy(() -> workbench("DROP TABLE dbconsole_test_t", null))
          .isInstanceOf(TransactionStateException.class)
          .hasMessageContaining("请先提交或回滚当前事务");

      verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("脚本单语句执行 recordHistory=false 且走不分页路径（汇总历史由专用端点记录）")
    void scriptStatementExecutesWithoutHistoryAndWithoutPaging() {
      String sql = "UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1";
      when(statementExecution.execute(any()))
          .thenReturn(new SqlResponses.UpdateResult(1, 5L, false, false));

      script(sql, null);

      ArgumentCaptor<StatementExecution.ExecutionRequest> captor =
          ArgumentCaptor.forClass(StatementExecution.ExecutionRequest.class);
      verify(statementExecution).execute(captor.capture());
      StatementExecution.ExecutionRequest captured = captor.getValue();
      assertThat(captured.page()).isNull();
      assertThat(captured.recordHistory()).isFalse();
      assertThat(captured.transactionConnection()).isNull();
      assertThat(captured.markEditable()).isFalse();
    }

    @Test
    @DisplayName("脚本单语句 SELECT 同样走不分页路径")
    void scriptSelectExecutesNonPaged() {
      when(statementExecution.execute(any()))
          .thenReturn(new SqlResponses.QueryResult(List.of(), List.of(), 0, false));

      script("SELECT * FROM dbconsole_test_t", null);

      ArgumentCaptor<StatementExecution.ExecutionRequest> captor =
          ArgumentCaptor.forClass(StatementExecution.ExecutionRequest.class);
      verify(statementExecution).execute(captor.capture());
      assertThat(captor.getValue().page()).isNull();
      assertThat(captor.getValue().recordHistory()).isFalse();
    }

    @Test
    @DisplayName("存在未提交手动事务时脚本单语句执行被拒绝，不进入执行与审计（F-16 互斥）")
    void scriptStatementRejectsOpenTransaction() {
      doThrow(
              new TransactionStateException(
                  TransactionManager.CODE_TRANSACTION_OPEN,
                  "脚本执行使用自动提交连接，请先提交或回滚当前事务"))
          .when(transactions)
          .requireScriptAutoCommit(SESSION_TOKEN, SOURCE_ID);

      assertThatThrownBy(
              () -> script("UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1", null))
          .isInstanceOf(TransactionStateException.class)
          .hasMessageContaining("请先提交或回滚当前事务");

      verifyNoInteractions(audit);
      verifyNoInteractions(statementExecution);
    }

    @Test
    @DisplayName("翻页形态：SELECT 走分页 arm、不写历史不标记；非 SELECT 拒绝")
    void pageFormPagingWithoutHistoryOrMarking() {
      when(statementExecution.execute(any()))
          .thenReturn(
              new SqlResponses.QueryResult(List.of(), List.of(), 0, false)
                  .withPaging(2, 20));

      SqlResponses response =
          orchestrator()
              .run(new ExecutionForm.PageForm(SESSION_TOKEN, USERNAME, SOURCE_ID,
                  "SELECT * FROM dbconsole_test_t", 2, 20));

      assertThat(response).isInstanceOf(SqlResponses.QueryResult.class);
      ArgumentCaptor<StatementExecution.ExecutionRequest> captor =
          ArgumentCaptor.forClass(StatementExecution.ExecutionRequest.class);
      verify(statementExecution).execute(captor.capture());
      assertThat(captor.getValue().page()).isEqualTo(new StatementExecution.PageRequest(2, 20));
      assertThat(captor.getValue().recordHistory()).isFalse();
      assertThat(captor.getValue().markEditable()).isFalse();
    }
  }

  private void verifyNoAudit() {
    verify(audit, never()).recordExecution(any(), any(), any(), anyLong(), any());
  }

  private static DbConsoleProperties properties(int maxConcurrency) {
    DbConsoleProperties.DataSourceProperties dev =
        new DbConsoleProperties.DataSourceProperties(
            SOURCE_ID,
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
            Duration.ofSeconds(30), 20_000, maxConcurrency, 100, 1_000, 65_536, 10_485_760),
        new DbConsoleProperties.TransactionProperties(Duration.ofMinutes(10)),
        new DbConsoleProperties.AuditProperties("./target/test-audit", Duration.ofDays(30)),
        null,
        null,
        List.of(dev));
  }
}
