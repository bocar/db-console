package io.github.dbconsole.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.dbconsole.audit.AuditService;
import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.metadata.TableStructure;
import io.github.dbconsole.metadata.TableStructureService;
import io.github.dbconsole.transaction.TransactionManager;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SQL 执行编排测试：前置校验、确认分派、DESC 特例与审计包裹。
 * 语句执行的不变式（连接归属、槽位释放、终态分类）见 {@link StatementExecutionTest}；
 * 外部契约（HTTP 语义）见 SqlApiTest / SqlCancelApiTest / SqlPaginationApiTest。
 */
class SqlExecutionServiceTest {

  private static final String SESSION_TOKEN = "session-token";
  private static final String USERNAME = "admin";
  private static final String SOURCE_ID = "mysql-work-note";

  private final TransactionManager transactions = mock(TransactionManager.class);
  private final TableStructureService tableStructures = mock(TableStructureService.class);
  private final AuditService audit = mock(AuditService.class);
  private final StatementExecution statementExecution = mock(StatementExecution.class);

  private SqlExecutionService executionService;

  @BeforeEach
  void setUp() {
    DbConsoleProperties properties = properties();
    DataSourceRegistry registry = new DataSourceRegistry(properties, ref -> "db-secret");
    executionService =
        new SqlExecutionService(
            registry,
            new ConfirmationService(),
            new RunningQueryRegistry(properties.execution().maxConcurrency()),
            transactions,
            tableStructures,
            audit,
            properties,
            statementExecution);
  }

  @Test
  @DisplayName("写入执行成功记录操作者、SQL 指纹来源与 SUCCEEDED 状态")
  void successfulWriteRecordsSucceededAudit() {
    String sql = "UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1";
    when(statementExecution.execute(any()))
        .thenReturn(new SqlResponses.UpdateResult(1, 5L, false, false));

    SqlResponses response = executionService.execute(SESSION_TOKEN, USERNAME, request(sql));

    assertThat(response).isInstanceOf(SqlResponses.UpdateResult.class);
    verify(audit)
        .recordExecution(eq(USERNAME), eq(SOURCE_ID), eq(sql), anyLong(), eq("SUCCEEDED"));
    verifyNoMoreInteractions(audit);
  }

  @Test
  @DisplayName("DESC 执行成功也记录 SUCCEEDED 审计")
  void describeSuccessRecordsSucceededAudit() {
    String sql = "DESC dbconsole_test_t";
    when(tableStructures.tableStructure(SOURCE_ID, "dbconsole_test_t"))
        .thenReturn(new TableStructure("dbconsole_test_t", null, List.of(), List.of(), List.of()));

    SqlResponses response = executionService.execute(SESSION_TOKEN, USERNAME, request(sql));

    assertThat(response).isInstanceOf(SqlResponses.QueryResult.class);
    verify(audit)
        .recordExecution(eq(USERNAME), eq(SOURCE_ID), eq(sql), anyLong(), eq("SUCCEEDED"));
    verifyNoMoreInteractions(audit);
  }

  @Test
  @DisplayName("DESC 结构读取失败记录 FAILED 审计")
  void describeFailureRecordsFailedAudit() {
    String sql = "DESC dbconsole_test_t";
    when(tableStructures.tableStructure(SOURCE_ID, "dbconsole_test_t"))
        .thenThrow(new IllegalArgumentException("table not found"));

    assertThatThrownBy(() -> executionService.execute(SESSION_TOKEN, USERNAME, request(sql)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("table not found");

    verify(audit).recordExecution(eq(USERNAME), eq(SOURCE_ID), eq(sql), anyLong(), eq("FAILED"));
    verifyNoMoreInteractions(audit);
  }

  @Test
  @DisplayName("连接打开失败记录 FAILED 审计且不标为结果未知")
  void connectionOpenFailureRecordsFailedAudit() {
    String sql = "SELECT 1";
    when(statementExecution.execute(any()))
        .thenThrow(new IllegalStateException("连接打开失败：refused"));

    assertThatThrownBy(() -> executionService.execute(SESSION_TOKEN, USERNAME, request(sql)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("连接打开失败");

    verify(audit).recordExecution(eq(USERNAME), eq(SOURCE_ID), eq(sql), anyLong(), eq("FAILED"));
    verifyNoMoreInteractions(audit);
  }

  @Test
  @DisplayName("执行失败只记录一次 FAILED 审计")
  void executionFailureRecordsSingleFailedAudit() {
    String sql = "SELECT * FROM dbconsole_test_t";
    when(statementExecution.execute(any()))
        .thenThrow(new DatabaseExecutionException("read failed", null));

    assertThatThrownBy(() -> executionService.execute(SESSION_TOKEN, USERNAME, request(sql)))
        .isInstanceOf(DatabaseExecutionException.class)
        .hasMessageContaining("read failed");

    verify(audit).recordExecution(eq(USERNAME), eq(SOURCE_ID), eq(sql), anyLong(), eq("FAILED"));
    verifyNoMoreInteractions(audit);
  }

  @Test
  @DisplayName("写入超时且结果无法确认时记录 UNKNOWN 审计")
  void writeTimeoutRecordsUnknownAudit() {
    String sql = "UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1";
    when(statementExecution.execute(any())).thenReturn(new SqlResponses.UnknownOutcome("结果未知"));

    SqlResponses response = executionService.execute(SESSION_TOKEN, USERNAME, request(sql));

    assertThat(response).isInstanceOf(SqlResponses.UnknownOutcome.class);
    verify(audit).recordExecution(eq(USERNAME), eq(SOURCE_ID), eq(sql), anyLong(), eq("UNKNOWN"));
    verifyNoMoreInteractions(audit);
  }

  @Test
  @DisplayName("查询执行超时时明确反馈超时并记录 FAILED 审计")
  void queryTimeoutIsReportedExplicitly() {
    String sql = "SELECT * FROM dbconsole_test_t";
    when(statementExecution.execute(any())).thenThrow(new QueryTimeoutException());

    assertThatThrownBy(() -> executionService.execute(SESSION_TOKEN, USERNAME, request(sql)))
        .isInstanceOf(QueryTimeoutException.class)
        .hasMessageContaining("超时");

    verify(audit).recordExecution(eq(USERNAME), eq(SOURCE_ID), eq(sql), anyLong(), eq("FAILED"));
    verifyNoMoreInteractions(audit);
  }

  @Test
  @DisplayName("同一数据源执行并发超过上限时明确拒绝（F-10）")
  void concurrencyBeyondLimitIsRejectedExplicitly() {
    when(statementExecution.execute(any()))
        .thenReturn(new SqlResponses.QueryResult(List.of(), List.of(), 0, false));

    executionService.execute(SESSION_TOKEN, USERNAME, request("SELECT 1"));

    assertThatThrownBy(
            () -> executionService.execute("session-token-2", USERNAME, request("SELECT 2")))
        .isInstanceOfSatisfying(
            SqlRejectException.class,
            rejected -> assertThat(rejected.code()).isEqualTo("CONCURRENCY_EXCEEDED"))
        .hasMessageContaining("并发");
  }

  @Test
  @DisplayName("有未提交事务时隐式提交 DDL 先于确认流程被拦截，且不落审计（F-16）")
  void implicitCommitDdlWithOpenTransactionIsRejectedBeforeConfirmation() {
    String sql = "DROP TABLE dbconsole_test_t";
    when(transactions.connectionFor(SESSION_TOKEN, SOURCE_ID))
        .thenReturn(mock(java.sql.Connection.class));

    assertThatThrownBy(() -> executionService.execute(SESSION_TOKEN, USERNAME, request(sql)))
        .isInstanceOf(io.github.dbconsole.transaction.TransactionStateException.class)
        .hasMessageContaining("请先提交或回滚当前事务");

    verifyNoInteractions(audit);
  }

  @Test
  @DisplayName("脚本单语句执行传 recordHistory=false 且走不分页路径（F-13 汇总历史由专用端点记录）")
  void scriptStatementExecutesWithoutHistoryAndWithoutPaging() {
    String sql = "UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1";
    when(statementExecution.execute(any()))
        .thenReturn(new SqlResponses.UpdateResult(1, 5L, false, false));

    executionService.executeScriptStatement(SESSION_TOKEN, USERNAME, request(sql));

    ArgumentCaptor<StatementExecution.ExecutionRequest> captor =
        ArgumentCaptor.forClass(StatementExecution.ExecutionRequest.class);
    verify(statementExecution).execute(captor.capture());
    StatementExecution.ExecutionRequest captured = captor.getValue();
    assertThat(captured.page()).isNull();
    assertThat(captured.recordHistory()).isFalse();
    assertThat(captured.transactionConnection()).isNull();
  }

  @Test
  @DisplayName("脚本单语句 SELECT 同样走不分页路径")
  void scriptSelectExecutesNonPaged() {
    String sql = "SELECT * FROM dbconsole_test_t";
    when(statementExecution.execute(any()))
        .thenReturn(new SqlResponses.QueryResult(List.of(), List.of(), 0, false));

    executionService.executeScriptStatement(SESSION_TOKEN, USERNAME, request(sql));

    ArgumentCaptor<StatementExecution.ExecutionRequest> captor =
        ArgumentCaptor.forClass(StatementExecution.ExecutionRequest.class);
    verify(statementExecution).execute(captor.capture());
    assertThat(captor.getValue().page()).isNull();
    assertThat(captor.getValue().recordHistory()).isFalse();
  }

  @Test
  @DisplayName("存在未提交手动事务时脚本单语句执行被拒绝，不进入执行与审计（F-16 互斥）")
  void scriptStatementRejectsOpenTransaction() {
    String sql = "UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1";
    doThrow(
            new io.github.dbconsole.transaction.TransactionStateException(
                TransactionManager.CODE_TRANSACTION_OPEN,
                "脚本执行使用自动提交连接，请先提交或回滚当前事务"))
        .when(transactions)
        .requireScriptAutoCommit(SESSION_TOKEN, SOURCE_ID);

    assertThatThrownBy(
            () -> executionService.executeScriptStatement(SESSION_TOKEN, USERNAME, request(sql)))
        .isInstanceOf(io.github.dbconsole.transaction.TransactionStateException.class)
        .hasMessageContaining("请先提交或回滚当前事务");

    verifyNoInteractions(audit);
    verifyNoInteractions(statementExecution);
  }

  private static SqlRequest request(String sql) {
    return new SqlRequest(SOURCE_ID, sql, null);
  }

  private static DbConsoleProperties properties() {
    DbConsoleProperties.DataSourceProperties source =
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
            Duration.ofSeconds(30), 20_000, 1, 100, 1_000, 65_536, 10_485_760),
        new DbConsoleProperties.TransactionProperties(Duration.ofMinutes(10)),
        new DbConsoleProperties.AuditProperties("./target/test-audit", Duration.ofDays(30)),
        null,
        null,
        List.of(source));
  }
}
