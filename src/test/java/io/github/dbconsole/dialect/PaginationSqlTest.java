package io.github.dbconsole.dialect;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dbconsole.config.DbConsoleProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 分页 SQL 生成的直测（ADR-0002/0004）：+1 探测、派生表包装、COUNT 包装与总数取整。 */
class PaginationSqlTest {

  private final SqlStatementAnalyzer analyzer =
      SqlStatementAnalyzer.forDialect(
          DbConsoleProperties.DatabaseType.MYSQL,
          DbConsoleProperties.CompatibilityMode.NATIVE);

  @Test
  @DisplayName("pagedSql：追加「每页行数 + 1」探测子句与偏移量")
  void pagedSqlAppendsProbeLimitAndOffset() {
    assertThat(PaginationSql.pagedSql(analyzer, "SELECT id FROM t", 1, 2))
        .isEqualTo("SELECT id FROM t\nLIMIT 3 OFFSET 0");
    assertThat(PaginationSql.pagedSql(analyzer, "SELECT id FROM t", 3, 10))
        .isEqualTo("SELECT id FROM t\nLIMIT 11 OFFSET 20");
  }

  @Test
  @DisplayName("原查询已有顶层 LIMIT 时先做派生表包装保留原子句边界")
  void pagedSqlWrapsQueriesWithTopLevelPagination() {
    assertThat(PaginationSql.pagedSql(analyzer, "SELECT id FROM t LIMIT 10", 1, 2))
        .isEqualTo("SELECT * FROM (SELECT id FROM t LIMIT 10\n) dbconsole_page\nLIMIT 3 OFFSET 0");
    assertThat(PaginationSql.pagedSql(analyzer, "SELECT id FROM t FOR UPDATE", 1, 2))
        .isEqualTo(
            "SELECT * FROM (SELECT id FROM t FOR UPDATE\n) dbconsole_page\nLIMIT 3 OFFSET 0");
  }

  @Test
  @DisplayName("countSql：COUNT 包装保留原子句边界")
  void countSqlWrapsInnerQuery() {
    assertThat(PaginationSql.countSql("SELECT id FROM t"))
        .isEqualTo("SELECT COUNT(*) FROM (SELECT id FROM t\n) dbconsole_count");
  }

  @Test
  @DisplayName("totalPages：向上取整")
  void totalPagesRoundsUp() {
    assertThat(PaginationSql.totalPages(38, 5)).isEqualTo(8);
    assertThat(PaginationSql.totalPages(10, 5)).isEqualTo(2);
    assertThat(PaginationSql.totalPages(0, 5)).isEqualTo(0);
    assertThat(PaginationSql.totalPages(1, 5)).isEqualTo(1);
  }
}
