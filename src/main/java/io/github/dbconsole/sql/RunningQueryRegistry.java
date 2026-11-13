package io.github.dbconsole.sql;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 运行中查询登记表（F-14/F-10）：按登录会话与数据源跟踪在执行的 JDBC 语句。
 * 提供两类能力：执行前的并发上限校验（F-10），以及仅限本会话的查询取消（F-14）。
 * 取消本身不写审计（F-10 只审计执行/提交/回滚）；被取消查询的终态由执行路径落审计。
 */
public class RunningQueryRegistry {

  private final int maxConcurrency;
  private final Map<String, RunningQuery> running = new ConcurrentHashMap<>();

  public RunningQueryRegistry(int maxConcurrency) {
    this.maxConcurrency = maxConcurrency;
  }

  /** 登记一条执行；该数据源在执行数达到上限时明确拒绝（F-10）。 */
  public RunningQuery register(String sessionToken, String sourceId) {
    if (countRunningOn(sourceId) >= maxConcurrency) {
      throw new SqlRejectException(
          "CONCURRENCY_EXCEEDED", "该数据源执行并发已达上限，请等待运行中的语句结束");
    }
    RunningQuery query = new RunningQuery(sessionToken, sourceId);
    running.put(query.id(), query);
    return query;
  }

  /** 挂载实际 JDBC 语句，使取消请求可直达驱动。 */
  public void bindStatement(RunningQuery query, Statement jdbcStatement) {
    query.attach(jdbcStatement);
  }

  /** 查询是否已被本会话的取消请求标记。 */
  public boolean isCancelRequested(RunningQuery query) {
    return query.isCancelRequested();
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
    try {
      jdbcStatement.cancel();
      // 驱动已接受取消才打标记：取消失败时后续错误不得误报为「已取消」
      target.requestCancel();
      return CancelResponse.cancelled();
    } catch (SQLException exception) {
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

  /** 取消失败原因面向用户，截断防护且不含 SQL 文本或凭据。 */
  private static String cancelFailureMessage(SQLException exception) {
    String reason = exception.getMessage();
    if (reason == null || reason.isBlank()) {
      return "驱动未返回原因";
    }
    return reason.length() > 200 ? reason.substring(0, 200) + "…" : reason;
  }
}
