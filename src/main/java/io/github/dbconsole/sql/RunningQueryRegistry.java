package io.github.dbconsole.sql;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 运行中查询登记表（F-14/F-10）：按登录会话与数据源跟踪在执行的 JDBC 语句。
 * 提供两类能力：执行前的并发上限校验（F-10），以及仅限本会话的查询取消（F-14）。
 * 同一会话在同一数据源上同时只允许一条运行中查询——「指定的运行中查询」由此无歧义，
 * 取消目标即该查询。取消本身不写审计（F-10 只审计执行/提交/回滚）；被取消查询的终态由执行路径落审计。
 */
public class RunningQueryRegistry {

  private final int maxConcurrency;
  private final Map<String, RunningQuery> running = new ConcurrentHashMap<>();

  public RunningQueryRegistry(int maxConcurrency) {
    this.maxConcurrency = maxConcurrency;
  }

  /**
   * 登记一条执行（F-10）：同一会话在该数据源已有运行中查询时拒绝（F-14 的「指定」语义），
   * 该数据源执行数达到上限时拒绝。检查与登记在锁内原子完成，并发注册不会超额。
   */
  public synchronized RunningQuery register(String sessionToken, String sourceId) {
    if (findBySessionAndSource(sessionToken, sourceId).findFirst().isPresent()) {
      throw new SqlRejectException(
          "SESSION_QUERY_RUNNING", "本会话在该数据源已有运行中的查询，请等待完成或先取消");
    }
    if (countRunningOn(sourceId) >= maxConcurrency) {
      throw new SqlRejectException(
          "CONCURRENCY_EXCEEDED", "该数据源执行并发已达上限，请等待运行中的语句结束");
    }
    RunningQuery query = new RunningQuery(sessionToken, sourceId);
    running.put(query.id(), query);
    return query;
  }

  /** 执行结束（成功/失败/取消）时释放登记。 */
  public void complete(RunningQuery query) {
    running.remove(query.id());
  }

  /**
   * 取消本会话在本数据源运行中的查询（F-14）：其他会话的运行中查询不可见，
   * 也不会被触碰；无运行中查询（含查询已先完成）时报告「查询先完成」。
   */
  public CancelResponse cancel(String sessionToken, String sourceId) {
    RunningQuery target =
        findBySessionAndSource(sessionToken, sourceId).findFirst().orElse(null);
    if (target == null) {
      return CancelResponse.completed();
    }
    Statement jdbcStatement = target.statement();
    if (jdbcStatement == null) {
      // 语句尚未挂载（创建窗口内）：标记已生效，执行路径收尾时按取消终态处理
      target.requestCancel();
      return CancelResponse.cancelled();
    }
    // 意图先于驱动调用置位：驱动中断可能先于 cancel() 返回到达执行线程
    target.requestCancel();
    try {
      jdbcStatement.cancel();
      return CancelResponse.cancelled();
    } catch (SQLException exception) {
      target.clearCancelRequest();
      return CancelResponse.failed(cancelFailureMessage(exception));
    }
  }

  private Stream<RunningQuery> findBySessionAndSource(String sessionToken, String sourceId) {
    return running.values().stream()
        .filter(query -> query.sessionToken().equals(sessionToken))
        .filter(query -> query.sourceId().equals(sourceId));
  }

  private long countRunningOn(String sourceId) {
    return running.values().stream().filter(query -> query.sourceId().equals(sourceId)).count();
  }

  /** 取消失败原因面向用户并截断防护；驱动消息可能含语句片段，仅进入本次响应，不落日志或审计。 */
  private static String cancelFailureMessage(SQLException exception) {
    String reason = exception.getMessage();
    if (reason == null || reason.isBlank()) {
      return "驱动未返回原因";
    }
    return reason.length() > 200 ? reason.substring(0, 200) + "…" : reason;
  }
}
