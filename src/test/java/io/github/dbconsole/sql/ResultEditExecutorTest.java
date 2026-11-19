package io.github.dbconsole.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dbconsole.config.DbConsoleProperties.CompatibilityMode;
import io.github.dbconsole.config.DbConsoleProperties.DatabaseType;
import io.github.dbconsole.config.DbConsoleProperties.DataSourceEnvironment;
import io.github.dbconsole.jdbc.ConnectionFactory;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.dialect.ResultEditSql;
import io.github.dbconsole.dialect.StatementKind;
import io.github.dbconsole.resultedit.ResultEditResponses;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 结果编辑批次执行器测试（F-17）：单一短事务、0 行回滚、失败回滚与终态分类。 */
class ResultEditExecutorTest {

  private final ConnectionFactory connections = mock(ConnectionFactory.class);
  private final Connection connection = mock(Connection.class);
  private final PreparedStatement prepared = mock(PreparedStatement.class);
  private final RunningQuery running = mock(RunningQuery.class);

  private final ResolvedDataSource source = new ResolvedDataSource(
      "mysql-work-note", "MySQL 测试库", DataSourceEnvironment.DEVELOPMENT,
      DatabaseType.MYSQL, CompatibilityMode.NATIVE,
      "jdbc:mysql://127.0.0.1:1/never", "db-user", "db-secret");

  private ResultEditExecutor executor;

  @BeforeEach
  void setUp() throws SQLException {
    executor = new ResultEditExecutor(connections);
    when(connections.open(source)).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(prepared);
    when(running.cancelRequested()).thenReturn(false);
  }

  private static ResultEditExecutor.PlannedStatement delete(int id) {
    return new ResultEditExecutor.PlannedStatement(
        0,
        ResultEditSql.delete(
            DatabaseType.MYSQL, CompatibilityMode.NATIVE,
            List.of("orders"), List.of("id"), List.of(id)));
  }

  private static ResultEditExecutor.PlannedStatement update(int id, String value) {
    return new ResultEditExecutor.PlannedStatement(
        0,
        ResultEditSql.update(
            DatabaseType.MYSQL, CompatibilityMode.NATIVE,
            List.of("orders"), List.of("id"), List.of(id),
            List.of("name"), List.of(value)));
  }

  @Nested
  @DisplayName("单一短事务")
  class TransactionSemantics {

    @Test
    @DisplayName("全部成功：自动提交关闭、逐条执行、提交、关闭连接")
    void commitsAfterAllStatements() throws SQLException {
      when(prepared.executeUpdate())
          .thenReturn(1, 1);

      ResultEditResponses response =
          executor.execute(source, List.of(delete(5), update(6, "x")), running, 30);

      assertThat(response).isInstanceOf(ResultEditResponses.Applied.class);
      verify(connection).setAutoCommit(false);
      verify(connection).commit();
      verify(connection, never()).rollback();
      verify(connection).close();
    }

    @Test
    @DisplayName("UPDATE 影响 0 行：整体回滚并定位到该变更")
    void zeroAffectedRollsBackWholeBatch() throws SQLException {
      when(prepared.executeUpdate()).thenReturn(1, 0);

      ResultEditResponses response =
          executor.execute(source, List.of(delete(5), update(6, "x")), running, 30);

      assertThat(response).isInstanceOf(ResultEditResponses.Failed.class);
      ResultEditResponses.Failed failed = (ResultEditResponses.Failed) response;
      assertThat(failed.section()).isEqualTo("UPDATE");
      assertThat(failed.changeIndex()).isEqualTo(0);
      assertThat(failed.message()).contains("行不存在");
      verify(connection).rollback();
      verify(connection, never()).commit();
    }
  }

  @Nested
  @DisplayName("终态分类（F-10）")
  class OutcomeClassification {

    @Test
    @DisplayName("约束冲突等普通失败：回滚并定位失败变更")
    void constraintViolationFailsLocated() throws SQLException {
      when(prepared.executeUpdate()).thenThrow(new SQLException("约束冲突", "23000", 1));

      ResultEditResponses response = executor.execute(source, List.of(delete(5)), running, 30);

      assertThat(response).isInstanceOf(ResultEditResponses.Failed.class);
      assertThat(((ResultEditResponses.Failed) response).message()).contains("约束冲突");
      verify(connection).rollback();
    }

    @Test
    @DisplayName("断连类失败归为执行结果未知")
    void connectionFailureIsUnknownOutcome() throws SQLException {
      when(prepared.executeUpdate()).thenThrow(new SQLException("connection reset", "08S01"));

      ResultEditResponses response = executor.execute(source, List.of(delete(5)), running, 30);

      assertThat(response).isInstanceOf(ResultEditResponses.Unknown.class);
      assertThat(response.auditStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("语句间检测到取消意图：停止后续语句并整体回滚为结果未知")
    void cancelBetweenStatementsStopsBatch() throws SQLException {
      when(prepared.executeUpdate()).thenReturn(1);
      when(running.cancelRequested()).thenReturn(false, true);

      ResultEditResponses response =
          executor.execute(source, List.of(delete(5), delete(6)), running, 30);

      assertThat(response).isInstanceOf(ResultEditResponses.Unknown.class);
      assertThat(((ResultEditResponses.Unknown) response).message()).contains("取消");
      verify(connection).rollback();
      verify(connection, never()).commit();
      // 只执行了第一条，第二条未prepare
      verify(connection).prepareStatement(anyString());
    }
  }

  @Test
  @DisplayName("参数按序绑定，null 以 setNull 绑定")
  void bindsParamsInOrder() throws Exception {
    when(prepared.executeUpdate()).thenReturn(1);
    ResultEditExecutor.PlannedStatement nullableUpdate =
        new ResultEditExecutor.PlannedStatement(
            0,
            ResultEditSql.update(
                DatabaseType.MYSQL, CompatibilityMode.NATIVE,
                List.of("orders"), List.of("id"), List.of(9),
                List.of("note"), java.util.Arrays.asList((Object) null)));

    executor.execute(source, List.of(nullableUpdate), running, 30);

    verify(prepared).setNull(1, java.sql.Types.VARCHAR);
    verify(prepared).setObject(2, 9);
    verify(prepared).setQueryTimeout(30);
  }
}
