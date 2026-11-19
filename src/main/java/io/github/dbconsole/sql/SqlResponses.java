package io.github.dbconsole.sql;

import java.util.List;

/** 执行响应的密封层次（wire 契约与前端 types.ts 对齐）。 */
public sealed interface SqlResponses extends TerminalResponse {

  /** 审计终态（F-10）：由响应变体穷尽定义，调用方不得自行拼装状态串。 */
  @Override
  String auditStatus();

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
      Integer totalPages,
      EditableInfo editable)
      implements SqlResponses {

    @Override
    public String auditStatus() {
      return "SUCCEEDED";
    }

    public QueryResult(List<QueryColumn> columns, List<List<Object>> rows, long elapsedMs,
        boolean truncated) {
      this("QUERY", columns, rows, elapsedMs, truncated, null, null, null, null, null, null);
    }

    /** 行集合级结果（mapper 层）：仅携带分页探测标记，页身份与总数由执行层以 wither 补充。 */
    public QueryResult(List<QueryColumn> columns, List<List<Object>> rows, long elapsedMs,
        boolean truncated, boolean hasNextPage) {
      this("QUERY", columns, rows, elapsedMs, truncated, null, null, hasNextPage, null, null, null);
    }

    /** 页身份补全（执行层）：同值副本追加页码与每页行数。 */
    public QueryResult withPaging(int page, int pageSize) {
      return new QueryResult(kind, columns, rows, elapsedMs, truncated, page, pageSize,
          hasNextPage, totalRows, totalPages, editable);
    }

    /** 总数补全（执行层）：同值副本追加总行数与总页数。 */
    public QueryResult withTotals(long totalRows, int totalPages) {
      return new QueryResult(kind, columns, rows, elapsedMs, truncated, page, pageSize,
          hasNextPage, totalRows, totalPages, editable);
    }

    /** 可编辑结果标记（F-17）返回带 editable 信息的同值副本。 */
    public QueryResult withEditable(EditableInfo editable) {
      return new QueryResult(kind, columns, rows, elapsedMs, truncated, page, pageSize,
          hasNextPage, totalRows, totalPages, editable);
    }
  }

  /**
   * 可编辑结果信息（F-17 / GLOSSARY：可编辑结果）：来源表限定名部分（去引号）、
   * 主键列名组，以及与查询结果列按位置一一对应的表列定义（null = 表达式或重名列，只读）。
   * 缺失（null）表示该查询结果整体只读。
   */
  record EditableInfo(
      List<String> tableParts, List<String> primaryKeys, List<EditableColumn> columns) {}

  /** 可编辑结果中单个结果列对应的表列定义。 */
  record EditableColumn(
      String name, String type, boolean nullable, String defaultValue,
      boolean autoIncrement, boolean primaryKey) {}

  /** 变更结果：受影响行数（null=驱动未提供）；implicitCommitDdl 标识 DDL 语句。 */
  record UpdateResult(
      String kind, Integer affectedRows, long elapsedMs, boolean uncommitted,
      boolean implicitCommitDdl) implements SqlResponses {

    @Override
    public String auditStatus() {
      return "SUCCEEDED";
    }

    public UpdateResult(Integer affectedRows, long elapsedMs, boolean uncommitted,
        boolean implicitCommitDdl) {
      this("UPDATE", affectedRows, elapsedMs, uncommitted, implicitCommitDdl);
    }
  }

  /** 确认要求：携带与「会话 + 数据源 + SQL」绑定的确认凭据。 */
  record ConfirmationRequired(
      String kind, String reason, boolean implicitCommitDdl, String confirmationToken)
      implements SqlResponses {

    @Override
    public String auditStatus() {
      return "SUCCEEDED";
    }

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

    @Override
    public String auditStatus() {
      return "UNKNOWN";
    }
  }

  /** 查询已取消（F-14）：本会话取消成功后的查询终态，结果不保留。 */
  record Cancelled(String kind, long elapsedMs) implements SqlResponses {

    public Cancelled(long elapsedMs) {
      this("CANCELLED", elapsedMs);
    }

    @Override
    public String auditStatus() {
      return "CANCELLED";
    }
  }

  record QueryColumn(String name, String type) {}
}
