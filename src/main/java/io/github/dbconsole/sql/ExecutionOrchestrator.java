package io.github.dbconsole.sql;

import io.github.dbconsole.audit.AuditService;
import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.dialect.RejectedSql;
import io.github.dbconsole.dialect.ResultEditSql;
import io.github.dbconsole.dialect.SqlAnalysisResult;
import io.github.dbconsole.dialect.SqlStatement;
import io.github.dbconsole.dialect.StatementKind;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.metadata.TableStructure;
import io.github.dbconsole.metadata.TableStructureService;
import io.github.dbconsole.resultedit.ResultEditResponses;
import io.github.dbconsole.transaction.TransactionManager;
import io.github.dbconsole.transaction.TransactionStateException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * 语句执行编舞（GLOSSARY：语句执行——连接归属、并发登记与终态在此确定）：
 * 「把语句跑到终态」的唯一漏斗。不变式在此一次成型并随全部执行形态继承——
 * 确认门（凭据签发/校验，被拒输入不签发）、并发登记与槽位释放（连接打开失败也释放）、
 * 审计包裹（确认要求与预校验拒绝不落盘，只在真实执行落盘）、F-10 终态分类、
 * 事务归属分派（工作台事务连接 / 脚本恒自动提交 / 批次独立短事务）。
 * 入口瘦成策略：构造 {@link ExecutionForm} 后经 {@link #run} 进入；形态变体即策略。
 * 单语句/批次两个执行 arm（{@link StatementExecution}、{@link ResultEditExecutor}）
 * 是包私有内部 seam，不进入本 interface。
 */
public class ExecutionOrchestrator {

  /** 批次形态与手动事务互斥的守卫文案（策略随形态，归编舞）。 */
  private static final String BATCH_MUTEX_MESSAGE =
      "结果编辑保存使用独立短事务，请先提交或回滚当前事务";

  private final DataSourceRegistry registry;
  private final ConfirmationService confirmations;
  private final RunningQueryRegistry runningQueries;
  private final TransactionManager transactions;
  private final TableStructureService tableStructures;
  private final AuditService audit;
  private final DbConsoleProperties properties;
  private final StatementExecution statementExecution;
  private final ResultEditExecutor batchExecutor;

  public ExecutionOrchestrator(
      DataSourceRegistry registry,
      ConfirmationService confirmations,
      RunningQueryRegistry runningQueries,
      TransactionManager transactions,
      TableStructureService tableStructures,
      AuditService audit,
      DbConsoleProperties properties,
      StatementExecution statementExecution,
      ResultEditExecutor batchExecutor) {
    this.registry = registry;
    this.confirmations = confirmations;
    this.runningQueries = runningQueries;
    this.transactions = transactions;
    this.tableStructures = tableStructures;
    this.audit = audit;
    this.properties = properties;
    this.statementExecution = statementExecution;
    this.batchExecutor = batchExecutor;
  }

  /**
   * 唯一入口：按形态分派到编舞；新增形态 = 新增 sealed 变体 + 一个分派臂
   * （Java 17 无 switch 模式，instanceof 链等价且同样受 sealed 约束穷尽）。
   */
  @SuppressWarnings("unchecked")
  public <R extends TerminalResponse> R run(ExecutionForm<R> form) {
    if (form instanceof ExecutionForm.WorkbenchQueryForm workbench) {
      return (R) runWorkbenchQuery(workbench);
    }
    if (form instanceof ExecutionForm.ScriptStatementForm script) {
      return (R) runScriptStatement(script);
    }
    if (form instanceof ExecutionForm.PageForm page) {
      return (R) runPage(page);
    }
    if (form instanceof ExecutionForm.EditBatchForm batch) {
      return (R) runBatch(batch);
    }
    throw new IllegalArgumentException("未知执行形态: " + form.getClass().getSimpleName());
  }

  /** 取消当前登录会话在本数据源运行中的查询（F-14）；其他会话的查询不可见。 */
  public CancelResponse cancelRunningQuery(String sessionToken, String sourceId) {
    registry.resolve(sourceId);
    return runningQueries.cancel(sessionToken, sourceId);
  }

  // ---------------------------------------------------------------- 工作台首次执行

  /** 工作台（F-04/F-05）：SELECT 首页分页 + 可编辑标记 + 历史；写语句先过确认门。 */
  private SqlResponses runWorkbenchQuery(ExecutionForm.WorkbenchQueryForm form) {
    Precheck precheck = precheck(form.sourceId(), form.sql());
    rejectIfOutOfScope(precheck.statement());
    // 有未提交事务时隐式提交 DDL 一律先拦截（F-16），不进入确认流程
    Connection transactionConnection =
        transactions.connectionFor(form.sessionToken(), precheck.source().id());
    if (transactionConnection != null && precheck.statement().kind().isImplicitCommitDdl()) {
      throw new TransactionStateException(
          TransactionManager.CODE_TRANSACTION_OPEN,
          "请先提交或回滚当前事务，再执行隐式提交 DDL");
    }
    return executeSingleStatement(
        form.sessionToken(), form.username(), form.sql(), form.confirmationToken(),
        precheck, transactionConnection, true,
        precheck.statement().kind() == StatementKind.SELECT);
  }

  // ---------------------------------------------------------------- 脚本单语句

  /** 脚本（F-13）：恒自动提交（互斥守卫），不分页、不写会话历史。 */
  private SqlResponses runScriptStatement(ExecutionForm.ScriptStatementForm form) {
    Precheck precheck = precheck(form.sourceId(), form.sql());
    rejectIfOutOfScope(precheck.statement());
    transactions.requireScriptAutoCommit(form.sessionToken(), precheck.source().id());
    return executeSingleStatement(
        form.sessionToken(), form.username(), form.sql(), form.confirmationToken(),
        precheck, null, false, false);
  }

  // ---------------------------------------------------------------- 翻页

  /** 翻页（F-11）：仅只读查询；复用事务上下文、不新开连接、不重复标记。 */
  private SqlResponses runPage(ExecutionForm.PageForm form) {
    rejectIfTooLong(form.sql());
    if (form.pageSize() > properties.execution().maxPageSize()) {
      throw new SqlRejectException("INVALID_PAGE_SIZE", "每页行数超过上限");
    }
    Precheck precheck = precheck(form.sourceId(), form.sql());
    if (precheck.statement().kind() != StatementKind.SELECT) {
      throw new SqlRejectException("PAGINATION_NOT_ALLOWED", "只有只读查询支持分页");
    }
    Connection transactionConnection =
        transactions.connectionFor(form.sessionToken(), precheck.source().id());
    RunningQuery running = runningQueries.register(form.sessionToken(), precheck.source().id());
    return auditExecution(
        precheck.source(), form.username(), form.sql(),
        () -> executeArm(
            precheck, form.sessionToken(), transactionConnection, running,
            new StatementExecution.PageRequest(form.page(), form.pageSize()), false, false));
  }

  // ---------------------------------------------------------------- 单语句编舞共享段

  /**
   * 单语句共享编舞：DESC 特例（F-06，不占并发额度）→ 分页取数或确认门 →
   * 登记 → 审计包裹的执行。pagedFirstPage 仅工作台 SELECT：首页分页 + 可编辑标记。
   */
  private SqlResponses executeSingleStatement(
      String sessionToken, String username, String sql, String confirmationToken,
      Precheck precheck, Connection transactionConnection,
      boolean recordHistory, boolean pagedFirstPage) {
    ResolvedDataSource source = precheck.source();
    // DESC 走对象树同一结构查询（F-06），不执行原生 SQL
    if (precheck.statement().kind() == StatementKind.DESCRIBE) {
      return auditExecution(source, username, sql, () -> describeAsQuery(source, sql));
    }
    if (pagedFirstPage) {
      RunningQuery running = runningQueries.register(sessionToken, source.id());
      return auditExecution(
          source, username, sql,
          () -> executeArm(
              precheck, sessionToken, transactionConnection, running,
              new StatementExecution.PageRequest(1, properties.execution().defaultPageSize()),
              recordHistory, true));
    }
    if (confirmations.check(
            sessionToken, source.id(), source.environment(), precheck.statement(),
            sql, confirmationToken)
        instanceof ConfirmationService.Required required) {
      return new SqlResponses.ConfirmationRequired(
          required.reason(), required.implicitCommitDdl(), required.freshToken());
    }
    RunningQuery running = runningQueries.register(sessionToken, source.id());
    return auditExecution(
        source, username, sql,
        () -> executeArm(
            precheck, sessionToken, transactionConnection, running, null, recordHistory, false));
  }

  /** 单语句执行 arm 的唯一构造点：历史/标记旗标由形态推导，不再散于调用方。 */
  private SqlResponses executeArm(
      Precheck precheck, String sessionToken, Connection transactionConnection,
      RunningQuery running, StatementExecution.PageRequest page,
      boolean recordHistory, boolean markEditable) {
    return statementExecution.execute(
        new StatementExecution.ExecutionRequest(
            precheck.source(), sessionToken, transactionConnection, running,
            precheck.statement(), precheck.sql(), page, recordHistory, markEditable));
  }

  // ---------------------------------------------------------------- 批次（F-17）

  /**
   * 暂存更改批次（F-17）：互斥守卫 → 无条件汇总确认门（逐条凭据）→ 登记 →
   * 审计包裹的批次执行 → finally 释放槽位。删除/更新/新增单短事务语义在执行 arm 内。
   */
  private TerminalResponse runBatch(ExecutionForm.EditBatchForm form) {
    ResolvedDataSource source = form.source();
    transactions.requireNoOpenTransaction(form.sessionToken(), source.id(), BATCH_MUTEX_MESSAGE);
    ResultEditResponses gate = batchConfirmationGate(form);
    if (gate != null) {
      return gate;
    }
    RunningQuery running = runningQueries.register(form.sessionToken(), source.id());
    try {
      return auditExecution(
          source, form.username(), batchAuditSubject(form.planned()),
          () -> batchExecutor.execute(source, form.planned(), running, timeoutSeconds()));
    } finally {
      runningQueries.complete(running);
    }
  }

  /**
   * 批次确认门（F-09 + F-17 裁决）：每次保存都先经一次汇总确认——逐条签发
   * 「会话 + 数据源 + 语句 + 参数渲染」绑定凭据，任一未校验通过即返回完整预览并
   * 停止执行；风险标注按 F-09 逐条判定（生产写等），无风险语句不标注但仍参与绑定。
   */
  private ResultEditResponses batchConfirmationGate(ExecutionForm.EditBatchForm form) {
    ResolvedDataSource source = form.source();
    List<ResultEditResponses.StatementPreview> previews =
        new ArrayList<>(form.planned().size());
    boolean anyPending = false;
    for (int index = 0; index < form.planned().size(); index++) {
      ResultEditExecutor.PlannedStatement statement = form.planned().get(index);
      ResultEditSql.Generated generated = statement.generated();
      ConfirmationDecision.Requirement requirement =
          ConfirmationDecision.evaluate(
              source.environment(), new SqlStatement(generated.kind(), true));
      String reason =
          requirement == ConfirmationDecision.Requirement.NOT_REQUIRED
              ? null
              : requirement.name();
      String token = null;
      String bound = ResultEditSql.boundText(generated.sql(), generated.params());
      String presented = tokenAt(form.confirmationTokens(), index);
      if (presented == null
          || !confirmations.verify(form.sessionToken(), source.id(), bound, presented)) {
        token = confirmations.issue(form.sessionToken(), source.id(), bound);
        anyPending = true;
      }
      previews.add(
          new ResultEditResponses.StatementPreview(
              index, statement.section(), statement.changeIndex(),
              generated.sql(), generated.params(), reason, token));
    }
    return anyPending ? new ResultEditResponses.ConfirmationRequired(previews) : null;
  }

  // ---------------------------------------------------------------- 共享件

  /** 前置分析（工作台/脚本/翻页共用）：长度门 → 解析数据源 → 方言分析（fail-closed）。 */
  private Precheck precheck(String sourceId, String sql) {
    rejectIfTooLong(sql);
    ResolvedDataSource source = registry.resolve(sourceId);
    SqlAnalysisResult analysis = source.dialect().analyzer().analyze(sql);
    if (analysis instanceof RejectedSql rejected) {
      throw new SqlRejectException("INVALID_SQL", rejected.message());
    }
    return new Precheck(source, sql, (SqlStatement) analysis);
  }

  /** 范围门（F-05）：工作台与脚本共用；翻页只做 SELECT-only 检查，错误优先级不同。 */
  private void rejectIfOutOfScope(SqlStatement statement) {
    StatementGates.GateViolation scopeViolation = StatementGates.scopeViolation(statement);
    if (scopeViolation != null) {
      throw new SqlRejectException(scopeViolation.code(), scopeViolation.message());
    }
  }

  private void rejectIfTooLong(String sql) {
    StatementGates.GateViolation lengthViolation =
        StatementGates.lengthViolation(sql, properties.execution().maxSqlLength());
    if (lengthViolation != null) {
      throw new SqlRejectException(lengthViolation.code(), lengthViolation.message());
    }
  }

  /**
   * 审计包裹（单语句与批次共用）：只在真实执行落盘；终态由响应变体穷尽定义
   * （auditStatus），调用方不拼装状态串。
   */
  private <R extends TerminalResponse> R auditExecution(
      ResolvedDataSource source, String username, String subject, Supplier<R> execution) {
    long startedAt = System.nanoTime();
    R result;
    try {
      result = execution.get();
    } catch (RuntimeException exception) {
      audit.recordExecution(username, source.id(), subject, elapsedMillisSince(startedAt), "FAILED");
      throw exception;
    }
    audit.recordExecution(username, source.id(), subject, elapsedMillisSince(startedAt),
        result.auditStatus());
    return result;
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

  private static String tokenAt(List<String> tokens, int index) {
    if (tokens == null || index < 0 || index >= tokens.size()) {
      return null;
    }
    return tokens.get(index);
  }

  /** 审计主语（F-10 不含完整 SQL 与数据）：段计数指纹即可定位一次保存的规模。 */
  private static String batchAuditSubject(List<ResultEditExecutor.PlannedStatement> planned) {
    long deletes = planned.stream().filter(s -> "DELETE".equals(s.section())).count();
    long updates = planned.stream().filter(s -> "UPDATE".equals(s.section())).count();
    long inserts = planned.stream().filter(s -> "INSERT".equals(s.section())).count();
    return "结果编辑保存 DELETE " + deletes + " / UPDATE " + updates + " / INSERT " + inserts;
  }

  private int timeoutSeconds() {
    return Math.max(1, (int) properties.execution().timeout().toSeconds());
  }

  private static long elapsedMillisSince(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }

  private record Precheck(ResolvedDataSource source, String sql, SqlStatement statement) {}
}
