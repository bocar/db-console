package io.github.dbconsole.sql;

import java.util.List;

/** 执行响应的密封层次（wire 契约与前端 types.ts 对齐）。 */
public sealed interface SqlResponses {

  /** 查询结果：列名 + 按列位置的行（零行保留列名），大整数与高精度小数以字符串保精度。 */
  record QueryResult(
      String kind,
      List<QueryColumn> columns,
      List<List<Object>> rows,
      long elapsedMs,
      boolean truncated,
      Integer page,
      Integer pageSize,
      Boolean hasNextPage,
      Long totalRows,
      Integer totalPages)
      implements SqlResponses {

    public QueryResult(List<QueryColumn> columns, List<List<Object>> rows, long elapsedMs,
        boolean truncated) {
      this("QUERY", columns, rows, elapsedMs, truncated, null, null, null, null, null);
    }

    public QueryResult(List<QueryColumn> columns, List<List<Object>> rows, long elapsedMs,
        boolean truncated, int page, int pageSize, boolean hasNextPage,
        Long totalRows, Integer totalPages) {
      this("QUERY", columns, rows, elapsedMs, truncated, page, pageSize, hasNextPage,
          totalRows, totalPages);
    }
  }

  /** 变更结果：受影响行数（null=驱动未提供）；implicitCommitDdl 标识 DDL 语句。 */
  record UpdateResult(
      String kind, Integer affectedRows, long elapsedMs, boolean uncommitted,
      boolean implicitCommitDdl) implements SqlResponses {

    public UpdateResult(Integer affectedRows, long elapsedMs, boolean uncommitted,
        boolean implicitCommitDdl) {
      this("UPDATE", affectedRows, elapsedMs, uncommitted, implicitCommitDdl);
    }
  }

  /** 确认要求：携带与「会话 + 数据源 + SQL」绑定的确认凭据。 */
  record ConfirmationRequired(
      String kind, String reason, boolean implicitCommitDdl, String confirmationToken)
      implements SqlResponses {

    public ConfirmationRequired(String reason, boolean implicitCommitDdl,
        String confirmationToken) {
      this("CONFIRMATION_REQUIRED", reason, implicitCommitDdl, confirmationToken);
    }
  }

  /** 执行结果未知（F-10）：写入或提交超时/断连且状态无法确认，不自动重试。 */
  record UnknownOutcome(String kind, String message) implements SqlResponses {

    public UnknownOutcome(String message) {
      this("UNKNOWN_OUTCOME", message);
    }
  }

  record QueryColumn(String name, String type) {}
}
