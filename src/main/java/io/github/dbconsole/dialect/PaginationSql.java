package io.github.dbconsole.dialect;

/**
 * 分页 SQL 生成（方言层，F-11/ADR-0002/ADR-0004）：分页约定的唯一收口点——
 * 派生表包装、+1 探测行、OFFSET 换算与总数取整只存在于本类，
 * 调用方（语句执行层）不学习这些约定；新方言接入只须适配本类。
 * 总行数用 COUNT 包装、能安全取得时才由调用方提供；总数未知降级由执行方处理。
 */
public final class PaginationSql {

  private PaginationSql() {}

  /**
   * 分页取数 SQL：原查询已有顶层分页或锁定子句时先做派生表包装保留原子句边界，
   * 再追加「每页行数 + 1」的探测子句（多取一行用于判定 hasNextPage）。
   */
  public static String pagedSql(
      SqlStatementAnalyzer analyzer, String statementSql, int page, int pageSize) {
    String baseSql =
        analyzer.requiresDerivedTableForPagination(statementSql)
            ? derivedTableWrap(statementSql)
            : statementSql;
    return appendPageClause(baseSql, pageSize + 1, (long) (page - 1) * pageSize);
  }

  /** 总行数统计包装：重名列等使包装不可执行时由调用方降级为总数未知。 */
  public static String countSql(String innerSql) {
    return "SELECT COUNT(*) FROM (" + innerSql + "\n) dbconsole_count";
  }

  /** 总页数换算：向上取整（38 行 × 每页 5 → 8 页）。 */
  public static int totalPages(long totalRows, int pageSize) {
    return (int) (totalRows / pageSize + (totalRows % pageSize == 0 ? 0 : 1));
  }

  /** 取数子句：前置换行，避免原查询的尾随行注释吞掉子句。 */
  private static String appendPageClause(String baseSql, int fetchLimit, long offset) {
    return baseSql + "\nLIMIT " + fetchLimit + " OFFSET " + offset;
  }

  /** 派生表包装：原查询已有顶层分页或锁定子句时保留原子句边界。 */
  private static String derivedTableWrap(String innerSql) {
    return "SELECT * FROM (" + innerSql + "\n) dbconsole_page";
  }
}
