package io.github.dbconsole.dialect;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dbconsole.config.DbConsoleProperties.CompatibilityMode;
import io.github.dbconsole.config.DbConsoleProperties.DatabaseType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 可编辑结果资格检测（F-17）：简单单表 SELECT 的识别与来源表提取。 */
class SingleTableSelectAnalyzerTest {

  private static final SingleTableSelectAnalyzer MYSQL =
      SingleTableSelectAnalyzer.forDialect(DatabaseType.MYSQL, CompatibilityMode.NATIVE);
  private static final SingleTableSelectAnalyzer DM8 =
      SingleTableSelectAnalyzer.forDialect(DatabaseType.DM8, CompatibilityMode.NATIVE);

  @Nested
  @DisplayName("合格的单表 SELECT")
  class Eligible {

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM orders",
        "select id, name from orders",
        "SELECT id FROM orders;",
        "SELECT * FROM db.orders",
        "SELECT * FROM `my db`.`orders`",
        "SELECT * FROM \"ODD NAME\"",
        "SELECT o.id, o.name FROM orders AS o WHERE o.id > 1 ORDER BY o.id LIMIT 5",
        "SELECT t.* FROM orders t",
        "SELECT id, name FROM orders WHERE remark = '; JOIN ('",
        "SELECT ALL id FROM orders",
        "SELECT * FROM orders OFFSET 10",
        "SELECT * FROM sch.db.orders",
    })
    @DisplayName("识别来源表并返回去引号的限定名部分")
    void detectsSimpleSingleTableSelect(String sql) {
      Optional<List<String>> parts = MYSQL.detect(sql);
      assertThat(parts).as(sql).isPresent();
    }

    @Test
    @DisplayName("表引用按点号拆分为去引号部分，保留原始大小写")
    void splitsQualifiedReferenceIntoParts() {
      assertThat(MYSQL.detect("SELECT * FROM `my db`.Orders"))
          .contains(List.of("my db", "Orders"));
      assertThat(MYSQL.detect("SELECT id FROM orders"))
          .contains(List.of("orders"));
    }

    @Test
    @DisplayName("DM8 方言同样识别（双引号标识符）")
    void detectsForDm8() {
      assertThat(DM8.detect("SELECT \"ID\" FROM \"NAVIC\".\"ORDERS\" WHERE 1 = 1"))
          .contains(List.of("NAVIC", "ORDERS"));
    }
  }

  @Nested
  @DisplayName("不具备编辑资格的 SELECT")
  class Ineligible {

    @ParameterizedTest
    @ValueSource(strings = {
        // 集合与去重
        "SELECT DISTINCT id FROM orders",
        "SELECT id FROM orders UNION SELECT id FROM bills",
        "SELECT id FROM orders INTERSECT SELECT 1 FROM bills",
        "SELECT id FROM orders EXCEPT SELECT 1 FROM bills",
        // 分组与聚合（行折叠，无法定位到表行）
        "SELECT COUNT(*) FROM orders",
        "SELECT id, MAX(amount) FROM orders",
        "SELECT SUM(amount), MIN(id) FROM orders",
        "SELECT id FROM orders GROUP BY name",
        "SELECT id FROM orders HAVING COUNT(*) > 1",
        "SELECT LISTAGG(name) FROM orders",
        // 窗口函数
        "SELECT ROW_NUMBER() OVER (ORDER BY id) FROM orders",
        "SELECT id, SUM(amount) OVER () FROM orders",
        // 多表
        "SELECT * FROM orders, bills",
        "SELECT * FROM orders JOIN bills ON orders.id = bills.id",
        "SELECT * FROM orders LEFT OUTER JOIN bills ON 1 = 1",
        "SELECT * FROM orders NATURAL JOIN bills",
        "SELECT * FROM orders STRAIGHT_JOIN bills ON 1 = 1",
        "SELECT * FROM orders AS o INNER JOIN bills AS b ON 1 = 1",
        // 派生表
        "SELECT * FROM (SELECT 1 AS x) derived",
        "SELECT * FROM (SELECT id FROM orders) x WHERE x.id > 1",
        // 锁定子句
        "SELECT * FROM orders FOR UPDATE",
        // WITH 前缀（CTE 来源不是单一基表）
        "WITH x AS (SELECT 1) SELECT * FROM x",
        "WITH x AS (SELECT 1) SELECT * FROM orders",
        // 非 SELECT 动词
        "UPDATE orders SET name = 'x'",
        "DELETE FROM orders",
    })
    @DisplayName("返回空：整体只读降级")
    void returnsEmptyForIneligible(String sql) {
      assertThat(MYSQL.detect(sql)).as(sql).isEmpty();
    }

    @Test
    @DisplayName("选择列表中的标量子查询是表达式列，不取消资格")
    void scalarSubqueryInSelectListStaysEligible() {
      // 子查询内的 FROM/MAX 都在括号深度内，不参与最外层判定
      assertThat(MYSQL.detect("SELECT id, (SELECT MAX(x) FROM bills) FROM orders"))
          .contains(List.of("orders"));
    }

    @Test
    @DisplayName("字符串与注释中的关键字不参与判定")
    void keywordsInsideStringsAndCommentsIgnored() {
      assertThat(MYSQL.detect("SELECT id FROM orders -- JOIN nothing\nWHERE 1 = 1"))
          .contains(List.of("orders"));
      assertThat(DM8.detect("SELECT id FROM orders WHERE note = '/* GROUP BY */'"))
          .contains(List.of("orders"));
    }
  }
}
