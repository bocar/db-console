package io.github.dbconsole.sql;

import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.dialect.RejectedSql;
import io.github.dbconsole.dialect.SqlStatement;
import io.github.dbconsole.dialect.SqlStatementAnalyzer;
import io.github.dbconsole.dialect.StatementKind;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL 执行编排（F-04/F-05）：方言分析 → 范围与确认分派 → JDBC 单语句执行。
 * 手动事务（F-16）由事务层接入前的语句均在自动提交连接上执行；
 * 需确认的操作必须携带与「会话 + 数据源 + SQL」绑定的确认凭据。
 */
public class SqlExecutionService {

  private static final Logger log = LoggerFactory.getLogger(SqlExecutionService.class);

  private final DataSourceRegistry registry;
  private final ConfirmationService confirmations;
  private final DbConsoleProperties properties;

  public SqlExecutionService(
      DataSourceRegistry registry,
      ConfirmationService confirmations,
      DbConsoleProperties properties) {
    this.registry = registry;
    this.confirmations = confirmations;
    this.properties = properties;
  }

  public Object execute(String username, SqlRequest request) {
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

  private Object run(ResolvedDataSource source, String sql, SqlStatement statement) {
    long startedAt = System.nanoTime();
    Properties props = new Properties();
    props.setProperty("user", source.username());
    props.setProperty("password", source.password());
    props.setProperty("connectTimeout", "5000");
    props.setProperty("socketTimeout", String.valueOf(
        properties.execution().timeout().toMillis() + 5000));
    try (Connection connection = DriverManager.getConnection(source.jdbcUrl(), props)) {
      connection.setAutoCommit(true);
      try (Statement jdbcStatement = connection.createStatement()) {
        jdbcStatement.setQueryTimeout(Math.max(1, (int) properties.execution().timeout().toSeconds()));
        boolean isResultSet = jdbcStatement.execute(sql);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info(
            "SQL 执行完成 sourceId={} kind={} elapsedMs={}",
            source.id(),
            statement.kind(),
            elapsedMs);
        if (isResultSet) {
          try (ResultSet resultSet = jdbcStatement.getResultSet()) {
            return QueryResultMapper.map(resultSet, properties.execution().maxPageSize(), elapsedMs);
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
      throw new DatabaseExecutionException(safeMessage(exception));
    }
  }

  /** 驱动消息面向用户可定位；凭据不会出现在驱动错误消息中，仍做长度截断防护。 */
  private static String safeMessage(SQLException exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return "数据库执行失败，原因未知";
    }
    return message.length() > 500 ? message.substring(0, 500) + "…" : message;
  }
}
