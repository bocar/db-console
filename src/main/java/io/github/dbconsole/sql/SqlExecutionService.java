package io.github.dbconsole.sql;

import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.dialect.RejectedSql;
import io.github.dbconsole.dialect.SqlAnalysisResult;
import io.github.dbconsole.dialect.SqlStatement;
import io.github.dbconsole.dialect.SqlStatementAnalyzer;
import io.github.dbconsole.dialect.StatementKind;
import io.github.dbconsole.jdbc.ConnectionFactory;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.transaction.TransactionManager;
import io.github.dbconsole.transaction.TransactionStateException;
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
  private final TransactionManager transactions;
  private final DbConsoleProperties properties;

  public SqlExecutionService(
      DataSourceRegistry registry,
      ConfirmationService confirmations,
      ConnectionFactory connections,
      TransactionManager transactions,
      DbConsoleProperties properties) {
    this.registry = registry;
    this.confirmations = confirmations;
    this.connections = connections;
    this.transactions = transactions;
    this.properties = properties;
  }

  public SqlResponses execute(String sessionToken, SqlRequest request) {
    if (request.sql().length() > properties.execution().maxSqlLength()) {
      throw new SqlRejectException("SQL_TOO_LONG", "SQL 长度超过上限");
    }
    ResolvedDataSource source = registry.resolve(request.sourceId());
    SqlStatementAnalyzer analyzer =
        SqlStatementAnalyzer.forDialect(source.databaseType(), source.compatibilityMode());
    SqlAnalysisResult analysis = analyzer.analyze(request.sql());
    if (analysis instanceof RejectedSql rejected) {
      throw new SqlRejectException("INVALID_SQL", rejected.message());
    }
    SqlStatement statement = (SqlStatement) analysis;
    if (statement.kind() == StatementKind.OTHER) {
      throw new SqlRejectException("OUT_OF_SCOPE", "该语句超出允许的操作范围");
    }
    Connection transactionConnection = transactions.connectionFor(sessionToken, source.id());
    // 有未提交事务时隐式提交 DDL 一律先拦截（F-16），不进入确认流程
    if (transactionConnection != null && statement.kind().isImplicitCommitDdl()) {
      throw new TransactionStateException(
          TransactionManager.CODE_TRANSACTION_OPEN,
          "请先提交或回滚当前事务，再执行隐式提交 DDL");
    }
    ConfirmationDecision.Requirement requirement =
        ConfirmationDecision.evaluate(source.environment(), statement);
    if (requirement != ConfirmationDecision.Requirement.NOT_REQUIRED) {
      boolean verified =
          confirmations.verify(
              sessionToken, source.id(), request.sql(), request.confirmationToken());
      if (!verified) {
        return new SqlResponses.ConfirmationRequired(
            requirement.name(),
            statement.kind().isImplicitCommitDdl(),
            confirmations.issue(sessionToken, source.id(), request.sql()));
      }
    }
    return run(source, request.sql(), statement, sessionToken, transactionConnection);
  }

  private SqlResponses run(
      ResolvedDataSource source,
      String sql,
      SqlStatement statement,
      String sessionToken,
      Connection transactionConnection) {
    long startedAt = System.nanoTime();
    boolean inTransaction = transactionConnection != null;
    // 事务连接由事务层持有：执行后归还（不 close、不改自动提交），并刷新空闲时钟
    try (Connection scope =
        inTransaction ? null : connections.open(source)) {
      Connection connection = inTransaction ? transactionConnection : scope;
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
            if (inTransaction) {
              transactions.touch(sessionToken);
            }
            return QueryResultMapper.map(resultSet, maxRows, elapsedMs);
          }
        }
        Integer affectedRows =
            statement.kind().isImplicitCommitDdl() ? null : jdbcStatement.getUpdateCount();
        if (inTransaction) {
          transactions.touch(sessionToken);
        }
        return new SqlResponses.UpdateResult(affectedRows, elapsedMs, inTransaction);
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
