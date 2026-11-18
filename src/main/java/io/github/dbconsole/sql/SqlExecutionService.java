package io.github.dbconsole.sql;

import io.github.dbconsole.audit.AuditService;
import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.dialect.RejectedSql;
import io.github.dbconsole.dialect.SqlAnalysisResult;
import io.github.dbconsole.dialect.SqlStatement;
import io.github.dbconsole.dialect.SqlStatementAnalyzer;
import io.github.dbconsole.dialect.StatementKind;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.metadata.TableStructure;
import io.github.dbconsole.metadata.TableStructureService;
import io.github.dbconsole.transaction.TransactionManager;
import io.github.dbconsole.transaction.TransactionStateException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * SQL 执行编排（F-04/F-05）：方言分析 → 范围与确认分派 → 交由语句执行 module 运行。
 * 连接归属、并发登记、终态分类与分页细节收口在 {@link StatementExecution}；
 * 本类只保留前置校验、事务内隐式提交 DDL 拦截（F-16）、执行确认分派（F-09）、
 * DESC 特例（F-06）与审计包裹（F-10）。
 */
public class SqlExecutionService {

  private final DataSourceRegistry registry;
  private final ConfirmationService confirmations;
  private final RunningQueryRegistry runningQueries;
  private final TransactionManager transactions;
  private final TableStructureService tableStructures;
  private final AuditService audit;
  private final DbConsoleProperties properties;
  private final StatementExecution statementExecution;

  public SqlExecutionService(
      DataSourceRegistry registry,
      ConfirmationService confirmations,
      RunningQueryRegistry runningQueries,
      TransactionManager transactions,
      TableStructureService tableStructures,
      AuditService audit,
      DbConsoleProperties properties,
      StatementExecution statementExecution) {
    this.registry = registry;
    this.confirmations = confirmations;
    this.runningQueries = runningQueries;
    this.transactions = transactions;
    this.tableStructures = tableStructures;
    this.audit = audit;
    this.properties = properties;
    this.statementExecution = statementExecution;
  }

  public SqlResponses execute(String sessionToken, String username, SqlRequest request) {
    return executeStatement(sessionToken, username, request, false);
  }

  /**
   * 脚本单语句执行（F-13）：整份脚本已预校验通过，这里按既有单语句机制逐条运行。
   * 确认凭据逐语句照常校验（F-09 绑定不变式），审计逐语句落盘；会话历史不在此写，
   * 汇总由专用端点（ScriptService.recordRun）记录。
   */
  public SqlResponses executeScriptStatement(String sessionToken, String username, SqlRequest request) {
    return executeStatement(sessionToken, username, request, true);
  }

  /**
   * 单语句执行的统一编排：工作台与脚本两条入口的差异收敛为一个 script 标志——
   * 脚本恒自动提交（存在未提交事务即拒绝，与预校验共用 TransactionManager 的互斥守卫，
   * F-16）、SELECT 不分页（分页不属于脚本结果语义）且不写会话历史（汇总由专用端点记录）。
   * 确认分派、DESC 特例与审计包裹两条入口完全一致。
   */
  private SqlResponses executeStatement(
      String sessionToken, String username, SqlRequest request, boolean script) {
    StatementGates.GateViolation lengthViolation =
        StatementGates.lengthViolation(request.sql(), properties.execution().maxSqlLength());
    if (lengthViolation != null) {
      throw new SqlRejectException(lengthViolation.code(), lengthViolation.message());
    }
    Precheck precheck = precheck(request.sourceId(), request.sql());
    ResolvedDataSource source = precheck.source();
    SqlStatement statement = precheck.statement();
    StatementGates.GateViolation scopeViolation = StatementGates.scopeViolation(statement);
    if (scopeViolation != null) {
      throw new SqlRejectException(scopeViolation.code(), scopeViolation.message());
    }
    // 脚本恒自动提交：存在未提交事务即拒绝（F-16 互斥，与预校验同一守卫）
    if (script) {
      transactions.requireScriptAutoCommit(sessionToken, source.id());
    }
    Connection transactionConnection = transactions.connectionFor(sessionToken, source.id());
    // 有未提交事务时隐式提交 DDL 一律先拦截（F-16），不进入确认流程
    if (transactionConnection != null && statement.kind().isImplicitCommitDdl()) {
      throw new TransactionStateException(
          TransactionManager.CODE_TRANSACTION_OPEN,
          "请先提交或回滚当前事务，再执行隐式提交 DDL");
    }
    // DESC 走对象树同一结构查询（F-06），不执行原生 SQL，不占执行并发额度
    if (statement.kind() == StatementKind.DESCRIBE) {
      return auditExecution(source, request.sql(), username,
          () -> describeAsQuery(source, request.sql()));
    }
    if (!script && statement.kind() == StatementKind.SELECT) {
      RunningQuery running = runningQueries.register(sessionToken, source.id());
      return auditExecution(source, request.sql(), username,
          () -> statementExecution.execute(
              new StatementExecution.ExecutionRequest(
                  source, sessionToken, transactionConnection, running, statement,
                  request.sql(),
                  new StatementExecution.PageRequest(1, properties.execution().defaultPageSize()),
                  !script,
                  true)));
    }
    if (confirmations.check(
            sessionToken, source.id(), source.environment(), statement,
            request.sql(), request.confirmationToken())
        instanceof ConfirmationService.Required required) {
      return new SqlResponses.ConfirmationRequired(
          required.reason(), required.implicitCommitDdl(), required.freshToken());
    }
    RunningQuery running = runningQueries.register(sessionToken, source.id());
    return auditExecution(source, request.sql(), username, () -> statementExecution.execute(
        new StatementExecution.ExecutionRequest(
            source, sessionToken, transactionConnection, running, statement,
            request.sql(), null, !script, false)));
  }

  /** 取消当前登录会话在本数据源运行中的查询（F-14）；其他会话的查询不可见。 */
  public CancelResponse cancelRunningQuery(String sessionToken, String sourceId) {
    registry.resolve(sourceId);
    return runningQueries.cancel(sessionToken, sourceId);
  }

  public SqlResponses page(String sessionToken, String username, SqlPageRequest request) {
    StatementGates.GateViolation lengthViolation =
        StatementGates.lengthViolation(request.sql(), properties.execution().maxSqlLength());
    if (lengthViolation != null) {
      throw new SqlRejectException(lengthViolation.code(), lengthViolation.message());
    }
    if (request.pageSize() > properties.execution().maxPageSize()) {
      throw new SqlRejectException("INVALID_PAGE_SIZE", "每页行数超过上限");
    }
    Precheck precheck = precheck(request.sourceId(), request.sql());
    ResolvedDataSource source = precheck.source();
    SqlStatement statement = precheck.statement();
    if (statement.kind() != StatementKind.SELECT) {
      throw new SqlRejectException("PAGINATION_NOT_ALLOWED", "只有只读查询支持分页");
    }
    Connection transactionConnection = transactions.connectionFor(sessionToken, source.id());
    RunningQuery running = runningQueries.register(sessionToken, source.id());
    // 分页不重复标记（F-11 翻页复用事务上下文、不新开连接；前端沿用首次执行的可编辑信息）
    return auditExecution(source, request.sql(), username,
        () -> statementExecution.execute(
            new StatementExecution.ExecutionRequest(
                source, sessionToken, transactionConnection, running, statement,
                request.sql(),
                new StatementExecution.PageRequest(request.page(), request.pageSize()),
                false,
                false)));
  }

  /**
   * execute/page 共享的前置分析（解析数据源 → 构造方言分析器 → fail-closed 分析）。
   * SQL 长度检查留在各入口：两入口的错误优先级原序不同（page 的 pageSize 检查居中）。
   * SqlAnalysisResult 的 RejectedSql 变体在此坍缩为 INVALID_SQL 拒绝，
   * 不向调用方暴露 sealed 层次；方言层保持对 sql 包零依赖。
   */
  private record Precheck(ResolvedDataSource source, SqlStatement statement) {}

  private Precheck precheck(String sourceId, String sql) {
    ResolvedDataSource source = registry.resolve(sourceId);
    SqlStatementAnalyzer analyzer =
        SqlStatementAnalyzer.forDialect(source.databaseType(), source.compatibilityMode());
    SqlAnalysisResult analysis = analyzer.analyze(sql);
    if (analysis instanceof RejectedSql rejected) {
      throw new SqlRejectException("INVALID_SQL", rejected.message());
    }
    return new Precheck(source, (SqlStatement) analysis);
  }

  /** 审计终态由响应变体穷尽定义（auditStatus），调用方不拼装状态串。 */
  private SqlResponses auditExecution(
      ResolvedDataSource source, String sql, String username,
      Supplier<SqlResponses> execution) {
    long startedAt = System.nanoTime();
    SqlResponses result;
    try {
      result = execution.get();
    } catch (RuntimeException exception) {
      audit.recordExecution(username, source.id(), sql, elapsedMillisSince(startedAt), "FAILED");
      throw exception;
    }
    audit.recordExecution(username, source.id(), sql, elapsedMillisSince(startedAt),
        result.auditStatus());
    return result;
  }

  private static long elapsedMillisSince(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
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
}
