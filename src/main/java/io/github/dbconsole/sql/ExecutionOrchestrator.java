package io.github.dbconsole.sql;

import io.github.dbconsole.audit.AuditService;
import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.dialect.ResultEditSql;
import io.github.dbconsole.dialect.SqlStatement;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.resultedit.ResultEditResponses;
import io.github.dbconsole.transaction.TransactionManager;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 语句执行编舞（GLOSSARY：语句执行——连接归属、并发登记与终态在此确定）：
 * 「把语句跑到终态」的唯一漏斗。不变式在此一次成型并随全部执行形态继承——
 * 确认门（凭据签发/校验，被拒输入不签发）、并发登记与槽位释放（连接打开失败也释放）、
 * 审计包裹（确认要求与预校验拒绝不落盘，只在真实执行落盘）、F-10 终态分类。
 * 入口服务瘦成策略：构造 {@link ExecutionForm} 后经 {@link #run} 进入；形态变体即策略。
 * 单语句/批次两个执行 arm（{@link StatementExecution}、{@link ResultEditExecutor}）
 * 是包私有内部 seam，不进入本 interface。
 */
public class ExecutionOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(ExecutionOrchestrator.class);

  private final ConfirmationService confirmations;
  private final RunningQueryRegistry runningQueries;
  private final AuditService audit;
  private final TransactionManager transactions;
  private final DbConsoleProperties properties;
  private final ResultEditExecutor batchExecutor;

  public ExecutionOrchestrator(
      ConfirmationService confirmations,
      RunningQueryRegistry runningQueries,
      AuditService audit,
      TransactionManager transactions,
      DbConsoleProperties properties,
      ResultEditExecutor batchExecutor) {
    this.confirmations = confirmations;
    this.runningQueries = runningQueries;
    this.audit = audit;
    this.transactions = transactions;
    this.properties = properties;
    this.batchExecutor = batchExecutor;
  }

  /**
   * 唯一入口：按形态分派到编舞；新增形态 = 新增 sealed 变体 + 一个分派臂
   * （Java 17 无 switch 模式，instanceof 链等价且同样受 sealed 约束穷尽）。
   */
  @SuppressWarnings("unchecked")
  public <R extends TerminalResponse> R run(ExecutionForm<R> form) {
    if (form instanceof ExecutionForm.EditBatchForm batch) {
      return (R) runBatch(batch);
    }
    // 绞杀第二笔迁入：WorkbenchQueryForm / ScriptStatementForm / PageForm
    throw new IllegalArgumentException("未知执行形态: " + form.getClass().getSimpleName());
  }

  /**
   * 暂存更改批次（F-17）：互斥守卫 → 无条件汇总确认门（逐条凭据）→ 登记 →
   * 审计包裹的批次执行 → finally 释放槽位。删除/更新/新增单短事务语义在执行 arm 内。
   */
  private TerminalResponse runBatch(ExecutionForm.EditBatchForm form) {
    ResolvedDataSource source = form.source();
    transactions.requireNoOpenTransaction(form.sessionToken(), source.id(), form.mutexMessage());
    ResultEditResponses gate = batchConfirmationGate(form);
    if (gate != null) {
      return gate;
    }
    RunningQuery running = runningQueries.register(form.sessionToken(), source.id());
    long startedAt = System.nanoTime();
    try {
      ResultEditResponses response;
      try {
        response = batchExecutor.execute(source, form.planned(), running, timeoutSeconds());
      } catch (RuntimeException exception) {
        audit.recordExecution(
            form.username(), source.id(), batchAuditSubject(form.planned()),
            elapsedMillisSince(startedAt), "FAILED");
        throw exception;
      }
      audit.recordExecution(
          form.username(), source.id(), batchAuditSubject(form.planned()),
          elapsedMillisSince(startedAt), response.auditStatus());
      return response;
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
}
