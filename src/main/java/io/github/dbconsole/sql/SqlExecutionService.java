package io.github.dbconsole.sql;

import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.dialect.RejectedSql;
import io.github.dbconsole.dialect.SqlAnalysisResult;
import io.github.dbconsole.dialect.SqlStatement;
import io.github.dbconsole.dialect.SqlStatementAnalyzer;
import io.github.dbconsole.dialect.StatementKind;
import io.github.dbconsole.jdbc.ConnectionFactory;
import io.github.dbconsole.jdbc.ConnectionFailureKind;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.metadata.TableStructure;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;import io.github.dbconsole.metadata.TableStructureService;
import io.github.dbconsole.history.HistoryService;
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
  private final TableStructureService tableStructures;
  private final HistoryService history;
  private final DbConsoleProperties properties;

  public SqlExecutionService(
      DataSourceRegistry registry,
      ConfirmationService confirmations,
      ConnectionFactory connections,
      TransactionManager transactions,
      TableStructureService tableStructures,
      HistoryService history,
      DbConsoleProperties properties) {
    this.registry = registry;
    this.confirmations = confirmations;
    this.connections = connections;
    this.transactions = transactions;
    this.tableStructures = tableStructures;
    this.history = history;
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
    // DESC 走对象树同一结构查询（F-06），不执行原生 SQL
    if (statement.kind() == StatementKind.DESCRIBE) {
      return describeAsQuery(source, request.sql());
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

  /** DESC 语句转为结构查询结果（F-06）：与对象树共用 TableStructureService。 */
  private SqlResponses describeAsQuery(ResolvedDataSource source, String sql) {
    String tableName =
        sql.trim()
            .replaceFirst("(?i)^(DESC|DESCRIBE)\\s+", "")
            .replaceAll(";$", "")
            .trim();
    if (tableName.isEmpty()) {
      throw new SqlRejectException("INVALID_SQL", "DESC 需要指定表名");
    }
    TableStructure structure = tableStructures.tableStructure(source.id(), tableName);
    List<SqlResponses.QueryColumn> columns =
        List.of(
            new SqlResponses.QueryColumn("COLUMN_NAME", "VARCHAR"),
            new SqlResponses.QueryColumn("TYPE", "VARCHAR"),
            new SqlResponses.QueryColumn("NULLABLE", "VARCHAR"),
            new SqlResponses.QueryColumn("DEFAULT_VALUE", "VARCHAR"),
            new SqlResponses.QueryColumn("KEY", "VARCHAR"),
            new SqlResponses.QueryColumn("COMMENT", "VARCHAR"));
    List<List<Object>> rows = new ArrayList<>();
    for (TableStructure.ColumnDefinition column : structure.columns()) {
      rows.add(
          Arrays.asList(
              column.name(),
              column.type(),
              column.nullable() ? "YES" : "NO",
              column.defaultValue(),
              column.primaryKey() ? "PRI" : "",
              column.comment()));
    }
    return new SqlResponses.QueryResult(columns, rows, 0, false);
  }

  private SqlResponses run(
      ResolvedDataSource source,
      String sql,
      SqlStatement statement,
      String sessionToken,
      Connection transactionConnection) {
    long startedAt = System.nanoTime();
    boolean inTransaction = transactionConnection != null;
    Connection connection = inTransaction ? transactionConnection : openAutoCommit(source);
    try {
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
        history.record(sessionToken, source.id(), sql, elapsedMs, true, inTransaction);
        if (isResultSet) {
          try (ResultSet resultSet = jdbcStatement.getResultSet()) {
            return QueryResultMapper.map(resultSet, properties.execution(), elapsedMs);
          }
        }
        int rawCount = jdbcStatement.getUpdateCount();
        // DDL 与驱动未提供行数（-1）统一为 null，前端显示「驱动未提供行数」
        Integer affectedRows =
            statement.kind().isImplicitCommitDdl() || rawCount < 0 ? null : rawCount;
        history.record(sessionToken, source.id(), sql, elapsedMs, true, inTransaction);
        return new SqlResponses.UpdateResult(affectedRows, elapsedMs, inTransaction,
            statement.kind().isImplicitCommitDdl());
      }
    } catch (SQLException exception) {
      long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
      boolean writeStatement = statement.kind().isWrite() || statement.kind().isImplicitCommitDdl();
      ConnectionFailureKind failureKind = ConnectionFailureKind.classify(exception);
      if (writeStatement && failureKind.isOutcomeUnknown()) {
        log.warn(
            "执行结果未知 sourceId={} kind={} sqlState={} elapsedMs={}",
            source.id(), statement.kind(), exception.getSQLState(), elapsedMs);
        return new SqlResponses.UnknownOutcome(safeMessage(exception));
      }
      log.warn(
          "SQL 执行失败 sourceId={} kind={} sqlState={} elapsedMs={}",
          source.id(), statement.kind(), exception.getSQLState(), elapsedMs);
      throw new DatabaseExecutionException(safeMessage(exception), exception);
    } finally {
      if (inTransaction) {
        transactions.touch(sessionToken);
      } else {
        try {
          connection.close();
        } catch (SQLException closeFailure) {
          // 关闭失败不影响执行结果
        }
      }
    }
  }

  private Connection openAutoCommit(ResolvedDataSource source) {
    try {
      Connection connection = connections.open(source);
      connection.setAutoCommit(true);
      return connection;
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "连接打开失败：" + exception.getMessage(), exception);
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
