package io.github.dbconsole.sql;

import java.util.List;

/** 执行响应的三种形态（wire 契约与前端 types.ts 对齐）。 */
public final class SqlResponses {

  private SqlResponses() {}

  public record QueryColumn(String name, String type) {}

  /** 查询结果：列名 + 按列位置的行（零行保留列名），大整数与高精度小数以字符串保精度。 */
  public record QueryResult(
      String kind, List<QueryColumn> columns, List<List<Object>> rows, long elapsedMs, boolean truncated) {

    public QueryResult(List<QueryColumn> columns, List<List<Object>> rows, long elapsedMs, boolean truncated) {
      this("QUERY", columns, rows, elapsedMs, truncated);
    }
  }

  /** 变更结果：受影响行数；驱动不提供（如 DDL）时为 null。 */
  public record UpdateResult(String kind, Integer affectedRows, long elapsedMs, boolean uncommitted) {

    public UpdateResult(Integer affectedRows, long elapsedMs, boolean uncommitted) {
      this("UPDATE", affectedRows, elapsedMs, uncommitted);
    }
  }

  /** 确认要求：携带与「会话 + 数据源 + SQL」绑定的确认凭据。 */
  public record ConfirmationRequired(
      String kind, String reason, boolean implicitCommitDdl, String confirmationToken) {

    public ConfirmationRequired(String reason, boolean implicitCommitDdl, String confirmationToken) {
      this("CONFIRMATION_REQUIRED", reason, implicitCommitDdl, confirmationToken);
    }
  }
}
