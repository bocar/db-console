package io.github.dbconsole.dialect;

/**
 * 分页 SQL 生成（方言层，F-11/ADR-0002）：包装原查询加 LIMIT/OFFSET 重执行，
 * 总行数用 COUNT 包装、能安全取得时才由调用方提供。
 * MySQL 与 DM8 首批目标语法一致；厂商差异在此收口，服务层不拼方言 SQL。
 */
public final class PaginationSql {

  private PaginationSql() {}

  /** 取数子句：前置换行，避免原查询的尾随行注释吞掉子句。 */
  public static String appendPageClause(String baseSql, int fetchLimit, long offset) {
    return baseSql + "\nLIMIT " + fetchLimit + " OFFSET " + offset;
  }

  /** 派生表包装：原查询已有顶层分页或锁定子句时保留原子句边界。 */
  public static String derivedTableWrap(String innerSql) {
    return "SELECT * FROM (" + innerSql + "\n) dbconsole_page";
  }

  /** 总行数统计包装：重名列等使包装不可执行时由调用方降级为总数未知。 */
  public static String countSql(String innerSql) {
    return "SELECT COUNT(*) FROM (" + innerSql + "\n) dbconsole_count";
  }
}
