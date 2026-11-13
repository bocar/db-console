package io.github.dbconsole.sql;

/** 查询执行超时（F-10）：语句在配置时限内未完成，明确反馈而非笼统数据库错误。 */
public class QueryTimeoutException extends RuntimeException {

  public QueryTimeoutException() {
    super("查询执行超时：语句在配置时限内未完成，请优化语句或调整执行超时配置");
  }
}
