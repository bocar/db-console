package io.github.dbconsole.sql;

import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.dialect.RejectedSql;
import io.github.dbconsole.dialect.SqlStatement;
import io.github.dbconsole.dialect.SqlStatementAnalyzer;
import io.github.dbconsole.dialect.StatementKind;
import io.github.dbconsole.jdbc.ConnectionFactory;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL 执行编排（F-04/F-05）：方言分析 → 范围与确认分派 → JDBC 单语句执行。
 * 手动事务（F-16）由事务层接入前的语句均在自动提交连接上执行；
 * 需确认的操作必须携带与「会话 + 数据源 + SQL」绑定的确认凭据。
 */
public class SqlExecutionService {

  private static final Logger log = LoggerFactory.getLogger(SqlExecutionService.class);
  private static final int DRIVER_MESSAGE_LIMIT = 500;

  private final DataSourceRegistry registry;
  private final ConfirmationService confirmations;
  private final ConnectionFactory connections;
  private final DbConsoleProperties properties;

  public SqlExecutionService(
      DataSourceRegistry registry,
      ConfirmationService confirmations,
      ConnectionFactory connections,
      DbConsoleProperties properties) {
    this.registry = registry;
    this.confirmations = confirmations;
    this.connections = connections;
    this.properties = properties;
  }

  public SqlResponses execute(String username, SqlRequest request) {
    if (request.sql().length() > properties.execution().maxSqlLength()) {
      throw new SqlRejectException("SQL_TOO_LONG", "SQL 长度超过上限");
    }
    ResolvedDataSource source = registry.resolve(request.sourceId());
    SqlStatementAnalyzer analyzer =
        SqlStatementAnalyzer.forDialect(source.databaseType(), source.compatibilityMode());
    Object analysis = analyzer.analyze(request.sql());
    if (analysis instanceof RejectedSql rejected) {
      throw new SqlRejectException("INVALID_SQL", rejected.message());
    }
    SqlStatement statement = (SqlStatement) analysis;
    if (statement.kind() == StatementKind.OTHER) {
      throw new SqlRejectException("OUT_OF_SCOPE", "该语句超出允许的操作范围");
    }
    ConfirmationDecision.Requirement requirement =
        ConfirmationDecision.evaluate(source.environment(), statement);
    if (requirement != ConfirmationDecision.Requirement.NOT_REQUIRED) {
      boolean verified =
          confirmations.verify(username, source.id(), request.sql(), request.confirmationToken());
      if (!verified) {
        return new SqlResponses.ConfirmationRequired(
            requirement.name(),
            statement.kind().isImplicitCommitDdl(),
            confirmations.issue(username, source.id(), request.sql()));
      }
    }
    return run(source, request.sql(), statement);
  }

  private SqlResponses run(ResolvedDataSource source, String sql, SqlStatement statement) {
    long startedAt = System.nanoTime();
    try (Connection connection = connections.open(source)) {
      connection.setAutoCommit(true);
      try (Statement jdbcStatement = connection.createStatement()) {
        int timeoutSeconds =
            Math.max(1, (int) properties.execution().timeout().toSeconds());
        jdbcStatement.setQueryTimeout(timeoutSeconds);
        boolean isResultSet = jdbcStatement.execute(sql);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info(
            "SQL 执行完成 sourceId={} kind={} elapsedMs={}",
            source.id(),
            statement.kind(),
            elapsedMs);
        if (isResultSet) {
          try (ResultSet resultSet = jdbcStatement.getResultSet()) {
            int maxRows = properties.execution().maxPageSize();
            return QueryResultMapper.map(resultSet, maxRows, elapsedMs);
          }
        }
        Integer affectedRows =
            statement.kind().isImplicitCommitDdl() ? null : jdbcStatement.getUpdateCount();
        return new SqlResponses.UpdateResult(affectedRows, elapsedMs, false);
      }
    } catch (SQLException exception) {
      long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
      log.warn(
          "SQL 执行失败 sourceId={} kind={} sqlState={} elapsedMs={}",
          source.id(),
          statement.kind(),
          exception.getSQLState(),
          elapsedMs);
      throw new DatabaseExecutionException(safeMessage(exception), exception);
    }
  }

  /** 驱动消息面向用户可定位并截断防护；凭据不出现在驱动错误消息中。 */
  private static String safeMessage(SQLException exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return "数据库执行失败，原因未知";
    }
    return message.length() > DRIVER_MESSAGE_LIMIT
        ? message.substring(0, DRIVER_MESSAGE_LIMIT) + "…"
        : message;
  }
}
