package io.github.dbconsole.sql;

import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.dialect.PaginationSql;
import io.github.dbconsole.dialect.SqlStatement;
import io.github.dbconsole.dialect.SqlStatementAnalyzer;
import io.github.dbconsole.dialect.StatementKind;
import io.github.dbconsole.history.HistoryService;
import io.github.dbconsole.jdbc.ConnectionFactory;
import io.github.dbconsole.jdbc.ConnectionFailureKind;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.transaction.TransactionManager;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 语句执行（GLOSSARY：语句执行）：在登录会话内对单个数据源运行一条经方言分析的语句。
 * 连接归属（事务连接不关闭 / 自动提交连接必关）、并发槽位释放时机、取消意图先于驱动调用、
 * SQLException 终态分类（取消 / 超时 / 执行结果未知 / 执行失败）在此收口；
 * 服务端分页的派生表包装、+1 探测行与总数统计同样内化，调用方不学习这些约定。
 * SqlExecutionService 只做前置校验、执行确认分派、DESC 特例与审计包裹。
 */
class StatementExecution {

  private static final Logger log = LoggerFactory.getLogger(StatementExecution.class);
  private static final int DRIVER_MESSAGE_LIMIT = 500;

  private final ConnectionFactory connections;
  private final TransactionManager transactions;
  private final RunningQueryRegistry runningQueries;
  private final HistoryService history;
  private final DbConsoleProperties properties;

  StatementExecution(
      ConnectionFactory connections,
      TransactionManager transactions,
      RunningQueryRegistry runningQueries,
      HistoryService history,
      DbConsoleProperties properties) {
    this.connections = connections;
    this.transactions = transactions;
    this.runningQueries = runningQueries;
    this.history = history;
    this.properties = properties;
  }

  /**
   * 一次语句执行的请求。分页查询传 {@link PageRequest}（执行方走 +1 探测与总数统计），
   * 写语句传 null；recordHistory 仅作用于分页路径（写语句路径恒记录，page 入口的重执行不记录）。
   */
  record ExecutionRequest(
      ResolvedDataSource source,
      String sessionToken,
      Connection transactionConnection,
      RunningQuery running,
      SqlStatement statement,
      String sql,
      PageRequest page,
      boolean recordHistory) {}

  record PageRequest(int page, int pageSize) {}

  SqlResponses execute(ExecutionRequest request) {
    long startedAt = System.nanoTime();
    ResolvedDataSource source = request.source();
    String sql = request.sql();
    boolean inTransaction = request.transactionConnection() != null;
    // 打开连接在 try 之内：打开失败也必须经 finally 释放并发槽位（F-10）
    Connection connection = null;
    try {
      connection = inTransaction ? request.transactionConnection() : openAutoCommit(source);
      return request.page() != null
          ? executePage(request, connection, startedAt, inTransaction)
          : executeStatement(request, connection, startedAt, inTransaction);
    } catch (SQLException exception) {
      return classifyFailure(request, exception, elapsedMillisSince(startedAt));
    } finally {
      runningQueries.complete(request.running());
      if (inTransaction) {
        transactions.touch(request.sessionToken());
      } else if (connection != null) {
        try {
          connection.close();
        } catch (SQLException closeFailure) {
          // 关闭失败不影响执行结果
        }
      }
    }
  }

  /** 分页查询路径（F-11）：派生表包装、+1 探测行、页身份字段与 COUNT 总数统计。 */
  private SqlResponses executePage(
      ExecutionRequest request, Connection connection, long startedAt, boolean inTransaction)
      throws SQLException {
    ResolvedDataSource source = request.source();
    String sql = request.sql();
    PageRequest page = request.page();
    SqlStatementAnalyzer analyzer =
        SqlStatementAnalyzer.forDialect(source.databaseType(), source.compatibilityMode());
    String statementSql = analyzer.removeTerminator(sql);
    String baseSql =
        analyzer.requiresDerivedTableForPagination(statementSql)
            ? PaginationSql.derivedTableWrap(statementSql)
            : statementSql;
    String pagedSql = PaginationSql.appendPageClause(baseSql, page.pageSize() + 1, offset(page));
    SqlResponses.QueryResult result = null;
    try (Statement jdbcStatement = connection.createStatement()) {
      request.running().attach(jdbcStatement);
      jdbcStatement.setQueryTimeout(timeoutSeconds());
      if (!jdbcStatement.execute(pagedSql)) {
        throw new SQLException("查询未返回结果集");
      }
      long elapsedMs = elapsedMillisSince(startedAt);
      log.info("SQL 执行完成 sourceId={} kind={} elapsedMs={}",
          source.id(), StatementKind.SELECT, elapsedMs);
      if (request.recordHistory()) {
        history.record(request.sessionToken(), source.id(), sql, elapsedMs, inTransaction);
      }
      SqlResponses.QueryResult pageResult;
      try (ResultSet resultSet = jdbcStatement.getResultSet()) {
        pageResult =
            QueryResultMapper.map(resultSet, properties.execution(), elapsedMs, page.pageSize());
      }
      result =
          new SqlResponses.QueryResult(pageResult.columns(), pageResult.rows(), elapsedMs,
              pageResult.truncated(), page.page(), page.pageSize(), pageResult.hasNextPage(),
              null, null);
    } catch (SQLException exception) {
      if (result != null) {
        // 页结果已成形，仅语句收尾失败：返回已得数据，不误报失败
        log.warn("JDBC 语句关闭失败 sourceId={} kind={} sqlState={}",
            source.id(), StatementKind.SELECT, exception.getSQLState());
        return result;
      }
      throw exception;
    }
    Long totalRows = countTotalRows(connection, source.id(), statementSql);
    if (totalRows != null) {
      int totalPages =
          (int) (totalRows / page.pageSize() + (totalRows % page.pageSize() == 0 ? 0 : 1));
      result =
          new SqlResponses.QueryResult(result.columns(), result.rows(), result.elapsedMs(),
              result.truncated(), result.page(), result.pageSize(), result.hasNextPage(),
              totalRows, totalPages);
    }
    return result;
  }

  /** 写语句与不分页查询路径（F-04/F-05）：终态按语句类别分类。 */
  private SqlResponses executeStatement(
      ExecutionRequest request, Connection connection, long startedAt, boolean inTransaction)
      throws SQLException {
    ResolvedDataSource source = request.source();
    SqlStatement statement = request.statement();
    String sql = request.sql();
    SqlResponses result = null;
    try (Statement jdbcStatement = connection.createStatement()) {
      request.running().attach(jdbcStatement);
      jdbcStatement.setQueryTimeout(timeoutSeconds());
      boolean isResultSet = jdbcStatement.execute(sql);
      long elapsedMs = elapsedMillisSince(startedAt);
      log.info(
          "SQL 执行完成 sourceId={} kind={} elapsedMs={}",
          source.id(), statement.kind(), elapsedMs);
      history.record(request.sessionToken(), source.id(), sql, elapsedMs, inTransaction);
      if (isResultSet) {
        try (ResultSet resultSet = jdbcStatement.getResultSet()) {
          result = QueryResultMapper.map(resultSet, properties.execution(), elapsedMs);
        }
      } else {
        int rawCount = jdbcStatement.getUpdateCount();
        // DDL 与驱动未提供行数（-1）统一为 null，前端显示「驱动未提供行数」
        Integer affectedRows =
            statement.kind().isImplicitCommitDdl() || rawCount < 0 ? null : rawCount;
        result =
            new SqlResponses.UpdateResult(affectedRows, elapsedMs, inTransaction,
                statement.kind().isImplicitCommitDdl());
      }
    } catch (SQLException exception) {
      if (result != null) {
        // 结果已成形，仅语句收尾失败：返回已得数据，不误报失败
        log.warn("JDBC 语句关闭失败 sourceId={} kind={} sqlState={}",
            source.id(), statement.kind(), exception.getSQLState());
        return result;
      }
      throw exception;
    }
    return result;
  }

  /**
   * SQLException 终态分类树：取消意图优先（查询 → CANCELLED，写入 → 执行结果未知），
   * 查询超时明确反馈，写入超时/断连归为执行结果未知（F-10，不自动重试），其余按执行失败。
   */
  private SqlResponses classifyFailure(
      ExecutionRequest request, SQLException exception, long elapsedMs) {
    boolean paged = request.page() != null;
    ResolvedDataSource source = request.source();
    StatementKind kind = request.statement().kind();
    if (request.running().isCancelRequested()) {
      if (paged) {
        log.info("查询已取消 sourceId={} elapsedMs={}", source.id(), elapsedMs);
        return new SqlResponses.Cancelled(elapsedMs);
      }
      log.warn("写入执行被取消，结果未知 sourceId={} kind={} elapsedMs={}",
          source.id(), kind, elapsedMs);
      return new SqlResponses.UnknownOutcome("执行已被取消，写入结果未知，请核实数据库状态");
    }
    if (paged) {
      if (exception instanceof SQLTimeoutException) {
        log.warn("查询执行超时 sourceId={} sqlState={} elapsedMs={}",
            source.id(), exception.getSQLState(), elapsedMs);
        throw new QueryTimeoutException();
      }
      log.warn("SQL 执行失败 sourceId={} kind={} sqlState={} elapsedMs={}",
          source.id(), kind, exception.getSQLState(), elapsedMs);
      throw new DatabaseExecutionException(safeMessage(exception), exception);
    }
    boolean writeStatement = kind.isWrite() || kind.isImplicitCommitDdl();
    ConnectionFailureKind failureKind = ConnectionFailureKind.classify(exception);
    if (writeStatement && failureKind.isOutcomeUnknown()) {
      log.warn("执行结果未知 sourceId={} kind={} sqlState={} elapsedMs={}",
          source.id(), kind, exception.getSQLState(), elapsedMs);
      return new SqlResponses.UnknownOutcome(safeMessage(exception));
    }
    log.warn("SQL 执行失败 sourceId={} kind={} sqlState={} elapsedMs={}",
        source.id(), kind, exception.getSQLState(), elapsedMs);
    throw new DatabaseExecutionException(safeMessage(exception), exception);
  }

  /** 总行数统计（F-11）：COUNT 包装无法安全执行时降级为总数未知。 */
  private Long countTotalRows(Connection connection, String sourceId, String sql) {
    try (Statement countStatement = connection.createStatement()) {
      countStatement.setQueryTimeout(timeoutSeconds());
      if (!countStatement.execute(PaginationSql.countSql(sql))) {
        return null;
      }
      try (ResultSet countResult = countStatement.getResultSet()) {
        return countResult.next() ? countResult.getLong(1) : null;
      }
    } catch (SQLException exception) {
      log.info("总数统计不可用 sourceId={} sqlState={}", sourceId, exception.getSQLState());
      return null;
    }
  }

  private static long offset(PageRequest page) {
    return (long) (page.page() - 1) * page.pageSize();
  }

  /** 查询超时秒数：配置不足 1 秒按 1 秒兜底。 */
  private int timeoutSeconds() {
    return Math.max(1, (int) properties.execution().timeout().toSeconds());
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

  private static long elapsedMillisSince(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }
}
