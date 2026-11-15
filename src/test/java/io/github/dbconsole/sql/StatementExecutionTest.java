package io.github.dbconsole.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.dialect.SqlStatement;
import io.github.dbconsole.dialect.SqlStatementAnalyzer;
import io.github.dbconsole.history.HistoryService;
import io.github.dbconsole.jdbc.ConnectionFactory;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.transaction.TransactionManager;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 语句执行 module 的不变式测试：连接归属、并发槽位释放时机、取消意图先于驱动调用、
 * SQLException 终态分类（取消 / 超时 / 执行结果未知 / 执行失败）。
 * 经真实 RunningQueryRegistry + mock ConnectionFactory 直击 interface，不 mock 分类逻辑。
 */
@ExtendWith(OutputCaptureExtension.class)
class StatementExecutionTest {

  private static final String SESSION_TOKEN = "session-token";
  private static final String SOURCE_ID = "mysql-work-note";

  private final ConnectionFactory connections = mock(ConnectionFactory.class);
  private final TransactionManager transactions = mock(TransactionManager.class);
  private final HistoryService history = mock(HistoryService.class);
  private final RunningQueryRegistry runningQueries = new RunningQueryRegistry(1);
  private final Connection connection = mock(Connection.class);
  private final Connection transactionConnection = mock(Connection.class);
  private final Statement jdbcStatement = mock(Statement.class);

  private DbConsoleProperties properties;
  private ResolvedDataSource source;
  private StatementExecution execution;
  private ExecutorService executor;

  @BeforeEach
  void setUp() throws Exception {
    properties = properties();
    source = new DataSourceRegistry(properties, ref -> "db-secret").resolve(SOURCE_ID);
    when(connections.open(any(ResolvedDataSource.class))).thenReturn(connection);
    when(connection.createStatement()).thenReturn(jdbcStatement);
    when(transactionConnection.createStatement()).thenReturn(jdbcStatement);
    execution =
        new StatementExecution(connections, transactions, runningQueries, history, properties);
    executor = Executors.newSingleThreadExecutor();
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  @Test
  @DisplayName("手动事务路径：事务连接被使用且不被关闭，事务时钟被 touch")
  void transactionConnectionIsUsedAndNeverClosed() throws Exception {
    String sql = "UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1";
    when(jdbcStatement.execute(sql)).thenReturn(false);
    when(jdbcStatement.getUpdateCount()).thenReturn(1);

    SqlResponses response = execution.execute(transactionRequest(sql));

    assertThat(response).isInstanceOf(SqlResponses.UpdateResult.class);
    verify(connections, never()).open(any(ResolvedDataSource.class));
    verify(transactionConnection, never()).close();
    verify(transactions).touch(SESSION_TOKEN);
    verify(history)
        .record(eq(SESSION_TOKEN), eq(SOURCE_ID), eq(sql), anyLong(), eq(true));
  }

  @Test
  @DisplayName("自动提交路径：连接打开并关闭，事务时钟不 touch")
  void autoCommitPathOpensAndClosesConnection() throws Exception {
    String sql = "UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1";
    when(jdbcStatement.execute(sql)).thenReturn(false);
    when(jdbcStatement.getUpdateCount()).thenReturn(1);

    execution.execute(request(sql, null, true));

    verify(connections).open(source);
    verify(connection).close();
    verify(transactions, never()).touch(anyString());
  }

  @Test
  @DisplayName("连接打开失败也释放并发槽位（F-10）：随后登记不再被拒")
  void openFailureReleasesConcurrencySlot() throws Exception {
    when(connections.open(any(ResolvedDataSource.class)))
        .thenThrow(new SQLException("connection refused", "08006"));

    assertThatThrownBy(() -> execution.execute(request("UPDATE t SET x = 1 WHERE id = 1", null, true)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("连接打开失败");

    RunningQuery again = runningQueries.register(SESSION_TOKEN, SOURCE_ID);
    runningQueries.complete(again);
  }

  @Test
  @DisplayName("执行完成释放并发槽位：随后登记不再被拒")
  void completionReleasesConcurrencySlot() throws Exception {
    when(jdbcStatement.execute(anyString())).thenReturn(true);
    stubEmptyResultRows();

    execution.execute(request("SELECT 1", new StatementExecution.PageRequest(1, 2), false));

    RunningQuery again = runningQueries.register(SESSION_TOKEN, SOURCE_ID);
    runningQueries.complete(again);
  }

  @Test
  @DisplayName("查询被本会话取消：终态为 Cancelled（F-14）")
  void cancelOnQueryReturnsCancelled() throws Exception {
    String sql = "SELECT id FROM t";
    RunningQuery running = runningQueries.register(SESSION_TOKEN, SOURCE_ID);
    running.requestCancel();
    when(jdbcStatement.execute(anyString())).thenThrow(new SQLException("cancelled by user"));

    SqlResponses response =
        execution.execute(request(sql, running, new StatementExecution.PageRequest(1, 2), true));

    assertThat(response).isInstanceOf(SqlResponses.Cancelled.class);
    assertThat(((SqlResponses.Cancelled) response).elapsedMs()).isGreaterThanOrEqualTo(0);
  }

  @Test
  @DisplayName("写入被取消：提交状态无法确认，终态为执行结果未知（F-10/F-14）")
  void cancelOnWriteReturnsUnknownOutcome() throws Exception {
    String sql = "UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1";
    RunningQuery running = runningQueries.register(SESSION_TOKEN, SOURCE_ID);
    running.requestCancel();
    when(jdbcStatement.execute(sql)).thenThrow(new SQLException("cancelled by user"));

    SqlResponses response = execution.execute(request(sql, running, null, true));

    assertThat(response)
        .isInstanceOf(SqlResponses.UnknownOutcome.class)
        .extracting(r -> ((SqlResponses.UnknownOutcome) r).message())
        .asString()
        .contains("执行已被取消");
  }

  @Test
  @DisplayName("查询超时：明确抛出超时反馈")
  void timeoutOnQueryThrowsQueryTimeout() throws Exception {
    when(jdbcStatement.execute(anyString())).thenThrow(new SQLTimeoutException("timeout"));

    assertThatThrownBy(
            () -> execution.execute(request("SELECT id FROM t", new StatementExecution.PageRequest(1, 2), true)))
        .isInstanceOf(QueryTimeoutException.class);
  }

  @Test
  @DisplayName("写入超时：提交状态无法确认，终态为执行结果未知")
  void timeoutOnWriteReturnsUnknownOutcome() throws Exception {
    String sql = "UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1";
    when(jdbcStatement.execute(sql)).thenThrow(new SQLTimeoutException("timeout"));

    SqlResponses response = execution.execute(request(sql, null, true));

    assertThat(response).isInstanceOf(SqlResponses.UnknownOutcome.class);
  }

  @Test
  @DisplayName("写入遇连接类失败：终态为执行结果未知")
  void unavailableOnWriteReturnsUnknownOutcome() throws Exception {
    String sql = "UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1";
    when(jdbcStatement.execute(sql)).thenThrow(new SQLException("broken pipe", "08006"));

    SqlResponses response = execution.execute(request(sql, null, true));

    assertThat(response).isInstanceOf(SqlResponses.UnknownOutcome.class);
  }

  @Test
  @DisplayName("查询遇连接类失败：不误判为结果未知，按执行失败处理")
  void unavailableOnQueryThrowsDatabaseExecutionException() throws Exception {
    when(jdbcStatement.execute(anyString()))
        .thenThrow(new SQLException("broken pipe", "08006"));

    assertThatThrownBy(
            () -> execution.execute(request("SELECT id FROM t", new StatementExecution.PageRequest(1, 2), true)))
        .isInstanceOf(DatabaseExecutionException.class);
  }

  @Test
  @DisplayName("分页路径：+1 探测行、页身份字段与 COUNT 总数包装")
  void pagedPathProbesWithPageSizePlusOne() throws Exception {
    String sql = "SELECT id FROM t";
    when(jdbcStatement.execute(anyString())).thenReturn(true, false);
    stubEmptyResultRows();

    SqlResponses response = execution.execute(request(sql, new StatementExecution.PageRequest(1, 2), false));

    assertThat(response).isInstanceOf(SqlResponses.QueryResult.class);
    SqlResponses.QueryResult result = (SqlResponses.QueryResult) response;
    assertThat(result.page()).isEqualTo(1);
    assertThat(result.pageSize()).isEqualTo(2);
    assertThat(result.hasNextPage()).isFalse();
    assertThat(result.totalRows()).isNull();
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcStatement, times(2)).execute(sqlCaptor.capture());
    assertThat(sqlCaptor.getAllValues().get(0)).isEqualTo("SELECT id FROM t\nLIMIT 3 OFFSET 0");
    assertThat(sqlCaptor.getAllValues().get(1))
        .isEqualTo("SELECT COUNT(*) FROM (SELECT id FROM t\n) dbconsole_count");
    verify(history, never()).record(anyString(), anyString(), anyString(), anyLong(), anyBoolean());
  }

  @Test
  @DisplayName("分页路径按 recordHistory 标志记录历史")
  void pagedPathRecordsHistoryOnlyWhenRequested() throws Exception {
    when(jdbcStatement.execute(anyString())).thenReturn(true, false);
    stubSingleRowResult();

    execution.execute(request("SELECT id FROM t", new StatementExecution.PageRequest(1, 2), true));

    verify(history, times(1))
        .record(eq(SESSION_TOKEN), eq(SOURCE_ID), eq("SELECT id FROM t"), anyLong(), eq(false));
  }

  @Test
  @DisplayName("语句关闭失败不改变已成功的执行结果")
  void statementCloseFailureReturnsResult() throws Exception {
    String sql = "UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1";
    when(jdbcStatement.execute(sql)).thenReturn(false);
    when(jdbcStatement.getUpdateCount()).thenReturn(1);
    doThrow(new SQLException("close failed")).when(jdbcStatement).close();

    SqlResponses response = execution.execute(request(sql, null, true));

    assertThat(response).isInstanceOf(SqlResponses.UpdateResult.class);
  }

  @Test
  @DisplayName("数据库失败时驱动错误消息回显的 SQL 与凭据不落应用日志")
  void databaseFailureKeepsPlainTextSqlAndCredentialsOutOfLogs(CapturedOutput output)
      throws Exception {
    String marker = "log-sanitize-marker-42";
    String sql = "SELECT * FROM t WHERE token = '" + marker + "'";
    when(jdbcStatement.execute(anyString())).thenThrow(new SQLException("error near '" + sql + "'", "42000"));

    assertThatThrownBy(() -> execution.execute(request(sql, new StatementExecution.PageRequest(1, 2), true)))
        .isInstanceOf(DatabaseExecutionException.class);

    assertThat(output).doesNotContain(marker).doesNotContain("db-secret");
  }

  @Test
  @DisplayName("应用日志不记录完整 SQL、数据库凭据或结果数据")
  void applicationLogsExcludeSensitiveData(CapturedOutput output) throws Exception {
    String sql = "SELECT name FROM t WHERE name = 'sql-secret'";
    when(jdbcStatement.execute(anyString())).thenReturn(true);
    stubSingleRowResult();

    SqlResponses response = execution.execute(request(sql, new StatementExecution.PageRequest(1, 2), true));

    assertThat(response).isInstanceOf(SqlResponses.QueryResult.class);
    assertThat(output).doesNotContain(sql, "sql-secret", "db-secret");
  }

  @Test
  @DisplayName("同一会话并发第二查询被拒（F-14 的「指定」语义）")
  void concurrentQueryWithinSessionIsRejected() throws Exception {
    CountDownLatch queryRunning = new CountDownLatch(1);
    CountDownLatch releaseQuery = new CountDownLatch(1);
    when(jdbcStatement.execute(anyString()))
        .thenAnswer(
            invocation -> {
              queryRunning.countDown();
              return releaseQuery.await(5, TimeUnit.SECONDS);
            });
    stubEmptyResultRows();
    Future<SqlResponses> running =
        executor.submit(
            () ->
                execution.execute(
                    request(
                        "SELECT 1",
                        runningQueries.register(SESSION_TOKEN, SOURCE_ID),
                        new StatementExecution.PageRequest(1, 2),
                        true)));
    assertThat(queryRunning.await(2, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> runningQueries.register(SESSION_TOKEN, SOURCE_ID))
        .isInstanceOfSatisfying(
            SqlRejectException.class,
            rejected -> assertThat(rejected.code()).isEqualTo("SESSION_QUERY_RUNNING"));

    releaseQuery.countDown();
    assertThat(running.get(5, TimeUnit.SECONDS)).isInstanceOf(SqlResponses.QueryResult.class);
  }

  private void stubEmptyResultRows() throws SQLException {
    ResultSet resultSet = mock(ResultSet.class);
    ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    when(jdbcStatement.getResultSet()).thenReturn(resultSet);
    when(resultSet.getMetaData()).thenReturn(metadata);
    when(metadata.getColumnCount()).thenReturn(1);
    when(metadata.getColumnLabel(1)).thenReturn("id");
    when(metadata.getColumnTypeName(1)).thenReturn("INT");
    when(resultSet.next()).thenReturn(false);
  }

  private void stubSingleRowResult() throws SQLException {
    ResultSet resultSet = mock(ResultSet.class);
    ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    when(jdbcStatement.getResultSet()).thenReturn(resultSet);
    when(resultSet.getMetaData()).thenReturn(metadata);
    when(metadata.getColumnCount()).thenReturn(1);
    when(metadata.getColumnLabel(1)).thenReturn("name");
    when(metadata.getColumnTypeName(1)).thenReturn("VARCHAR");
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getObject(1)).thenReturn("value");
  }

  /** 自动注册路径：module 外不接触 RunningQuery 的场景。 */
  private StatementExecution.ExecutionRequest request(
      String sql, StatementExecution.PageRequest page, boolean recordHistory) {
    return request(
        sql, runningQueries.register(SESSION_TOKEN, SOURCE_ID), page, recordHistory);
  }

  private StatementExecution.ExecutionRequest request(
      String sql, RunningQuery running, StatementExecution.PageRequest page, boolean recordHistory) {
    return new StatementExecution.ExecutionRequest(
        source, SESSION_TOKEN, null, running, statement(sql), sql, page, recordHistory);
  }

  private StatementExecution.ExecutionRequest transactionRequest(String sql) {
    return new StatementExecution.ExecutionRequest(
        source, SESSION_TOKEN, transactionConnection,
        runningQueries.register(SESSION_TOKEN, SOURCE_ID), statement(sql), sql, null, true);
  }

  private static SqlStatement statement(String sql) {
    return (SqlStatement)
        SqlStatementAnalyzer.forDialect(
                DbConsoleProperties.DatabaseType.MYSQL,
                DbConsoleProperties.CompatibilityMode.NATIVE)
            .analyze(sql);
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
        new DbConsoleProperties.LoginProperties("admin", "test-secret"),
        new DbConsoleProperties.SessionProperties(Duration.ofHours(8)),
        new DbConsoleProperties.ExecutionProperties(
            Duration.ofSeconds(30), 20_000, 1, 100, 1_000, 65_536, 10_485_760),
        new DbConsoleProperties.TransactionProperties(Duration.ofMinutes(10)),
        new DbConsoleProperties.AuditProperties("./target/test-audit", Duration.ofDays(30)),
        List.of(source));
  }
}
