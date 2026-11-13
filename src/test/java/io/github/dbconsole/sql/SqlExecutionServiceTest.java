package io.github.dbconsole.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.dbconsole.audit.AuditService;
import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.history.HistoryService;
import io.github.dbconsole.jdbc.ConnectionFactory;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.metadata.TableStructure;
import io.github.dbconsole.metadata.TableStructureService;
import io.github.dbconsole.transaction.TransactionManager;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SqlExecutionServiceTest {

  private static final String SESSION_TOKEN = "session-token";
  private static final String USERNAME = "admin";
  private static final String SOURCE_ID = "mysql-work-note";

  private final ConnectionFactory connections = mock(ConnectionFactory.class);
  private final TransactionManager transactions = mock(TransactionManager.class);
  private final TableStructureService tableStructures = mock(TableStructureService.class);
  private final HistoryService history = mock(HistoryService.class);
  private final AuditService audit = mock(AuditService.class);
  private final Connection connection = mock(Connection.class);
  private final Statement jdbcStatement = mock(Statement.class);

  private SqlExecutionService executionService;

  @BeforeEach
  void setUp() throws Exception {
    DbConsoleProperties properties = properties();
    DataSourceRegistry registry = new DataSourceRegistry(properties, ref -> "db-secret");
    when(connections.open(any(ResolvedDataSource.class))).thenReturn(connection);
    when(connection.createStatement()).thenReturn(jdbcStatement);
    executionService =
        new SqlExecutionService(
            registry,
            new ConfirmationService(),
            connections,
            transactions,
            tableStructures,
            history,
            audit,
            properties);
  }

  @Test
  @DisplayName("写入执行成功记录操作者、SQL 指纹来源与 SUCCEEDED 状态")
  void successfulWriteRecordsSucceededAudit() throws Exception {
    String sql = "UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1";
    when(jdbcStatement.execute(sql)).thenReturn(false);
    when(jdbcStatement.getUpdateCount()).thenReturn(1);

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
  @DisplayName("自动提交连接打开失败记录 FAILED 审计且不标为结果未知")
  void connectionOpenFailureRecordsFailedAudit() throws Exception {
    String sql = "SELECT 1";
    when(connections.open(any(ResolvedDataSource.class)))
        .thenThrow(new SQLException("connection refused", "08006"));

    assertThatThrownBy(() -> executionService.execute(SESSION_TOKEN, USERNAME, request(sql)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("连接打开失败");

    verify(audit).recordExecution(eq(USERNAME), eq(SOURCE_ID), eq(sql), anyLong(), eq("FAILED"));
    verifyNoMoreInteractions(audit);
  }

  @Test
  @DisplayName("查询结果读取失败只记录一次 FAILED 审计")
  void queryReadFailureRecordsSingleFailedAudit() throws Exception {
    String sql = "SELECT * FROM dbconsole_test_t";
    ResultSet resultSet = mock(ResultSet.class);
    when(jdbcStatement.execute(sql)).thenReturn(true);
    when(jdbcStatement.getResultSet()).thenReturn(resultSet);
    when(resultSet.getMetaData()).thenThrow(new SQLException("read failed", "42000"));

    assertThatThrownBy(() -> executionService.execute(SESSION_TOKEN, USERNAME, request(sql)))
        .isInstanceOf(DatabaseExecutionException.class)
        .hasMessageContaining("read failed");

    verify(audit).recordExecution(eq(USERNAME), eq(SOURCE_ID), eq(sql), anyLong(), eq("FAILED"));
    verifyNoMoreInteractions(audit);
  }

  @Test
  @DisplayName("应用日志不记录完整 SQL、数据库凭据或结果数据")
  void applicationLogsExcludeSensitiveData(CapturedOutput output) throws Exception {
    String sql = "SELECT name FROM dbconsole_test_t WHERE name = 'sql-secret'";
    ResultSet resultSet = mock(ResultSet.class);
    ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    when(jdbcStatement.execute(sql)).thenReturn(true);
    when(jdbcStatement.getResultSet()).thenReturn(resultSet);
    when(resultSet.getMetaData()).thenReturn(metadata);
    when(metadata.getColumnCount()).thenReturn(1);
    when(metadata.getColumnLabel(1)).thenReturn("name");
    when(metadata.getColumnTypeName(1)).thenReturn("VARCHAR");
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getObject(1)).thenReturn("result-secret");

    SqlResponses response = executionService.execute(SESSION_TOKEN, USERNAME, request(sql));

    assertThat(response).isInstanceOf(SqlResponses.QueryResult.class);
    assertThat(output).doesNotContain(sql, "sql-secret", "db-secret", "result-secret");
    verify(audit)
        .recordExecution(eq(USERNAME), eq(SOURCE_ID), eq(sql), anyLong(), eq("SUCCEEDED"));
  }

  @Test
  @DisplayName("语句关闭失败不改变已成功执行的审计状态或产生重复事件")
  void statementCloseFailureKeepsSingleSucceededAudit() throws Exception {
    String sql = "UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1";
    when(jdbcStatement.execute(sql)).thenReturn(false);
    when(jdbcStatement.getUpdateCount()).thenReturn(1);
    doThrow(new SQLException("close failed")).when(jdbcStatement).close();

    SqlResponses response = executionService.execute(SESSION_TOKEN, USERNAME, request(sql));

    assertThat(response).isInstanceOf(SqlResponses.UpdateResult.class);
    verify(audit)
        .recordExecution(eq(USERNAME), eq(SOURCE_ID), eq(sql), anyLong(), eq("SUCCEEDED"));
    verifyNoMoreInteractions(audit);
  }

  @Test
  @DisplayName("写入超时且结果无法确认时记录 UNKNOWN 审计")
  void writeTimeoutRecordsUnknownAudit() throws Exception {
    String sql = "UPDATE dbconsole_test_t SET name = 'new' WHERE id = 1";
    when(jdbcStatement.execute(sql)).thenThrow(new SQLTimeoutException("timeout"));

    SqlResponses response = executionService.execute(SESSION_TOKEN, USERNAME, request(sql));

    assertThat(response).isInstanceOf(SqlResponses.UnknownOutcome.class);
    verify(audit).recordExecution(eq(USERNAME), eq(SOURCE_ID), eq(sql), anyLong(), eq("UNKNOWN"));
    verifyNoMoreInteractions(audit);
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
            Duration.ofSeconds(30), 20_000, 4, 100, 1_000, 65_536, 10_485_760),
        new DbConsoleProperties.TransactionProperties(Duration.ofMinutes(10)),
        new DbConsoleProperties.AuditProperties("./target/test-audit", Duration.ofDays(30)),
        List.of(source));
  }
}
