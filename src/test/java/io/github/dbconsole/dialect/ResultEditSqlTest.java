package io.github.dbconsole.dialect;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dbconsole.config.DbConsoleProperties.CompatibilityMode;
import io.github.dbconsole.config.DbConsoleProperties.DatabaseType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 结果编辑语句生成（F-17）：参数化 DML 与确认凭据绑定文本。 */
class ResultEditSqlTest {

  private static final Dialect MYSQL = Dialect.of(DatabaseType.MYSQL, CompatibilityMode.NATIVE);
  private static final Dialect DM8 = Dialect.of(DatabaseType.DM8, CompatibilityMode.NATIVE);

  @Test
  @DisplayName("MySQL 系生成反引号引用的参数化 DELETE/UPDATE/INSERT")
  void generatesMysqlStatements() {
    ResultEditSql.Generated delete =
        ResultEditSql.delete(
            MYSQL, List.of("orders"), List.of("id"), List.of(5));
    assertThat(delete.sql()).isEqualTo("DELETE FROM `orders` WHERE `id` = ?");
    assertThat(delete.params()).containsExactly(5);
    assertThat(delete.kind()).isEqualTo(StatementKind.DELETE);

    ResultEditSql.Generated update =
        ResultEditSql.update(
            MYSQL, List.of("db", "orders"),
            List.of("id"), List.of(5), List.of("name", "qty"),
            java.util.Arrays.asList("x", null));
    assertThat(update.sql())
        .isEqualTo("UPDATE `db`.`orders` SET `name` = ?, `qty` = ? WHERE `id` = ?");
    assertThat(update.params()).containsExactly("x", null, 5);
    assertThat(update.kind()).isEqualTo(StatementKind.UPDATE);

    ResultEditSql.Generated insert =
        ResultEditSql.insert(
            MYSQL, List.of("orders"), List.of("name", "qty"), List.of("x", 3));
    assertThat(insert.sql()).isEqualTo("INSERT INTO `orders` (`name`, `qty`) VALUES (?, ?)");
    assertThat(insert.params()).containsExactly("x", 3);
    assertThat(insert.kind()).isEqualTo(StatementKind.INSERT);
  }

  @Test
  @DisplayName("DM8 系生成双引号引用，复合主键按序并列")
  void generatesDm8StatementsWithCompositeKey() {
    assertThat(
            ResultEditSql.delete(
            DM8, List.of("NAVIC", "ORDERS"),
                    List.of("ID", "NAME"), List.of(5, "a"))
                .sql())
        .isEqualTo("DELETE FROM \"NAVIC\".\"ORDERS\" WHERE \"ID\" = ? AND \"NAME\" = ?");

    assertThat(
            ResultEditSql.update(
            DM8, List.of("ORDERS"),
                    List.of("ID", "NAME"), List.of(5, "a"), List.of("QTY"), List.of(1))
                .sql())
        .isEqualTo("UPDATE \"ORDERS\" SET \"QTY\" = ? WHERE \"ID\" = ? AND \"NAME\" = ?");
  }

  @Test
  @DisplayName("标识符内嵌引号按方言双写转义")
  void escapesEmbeddedQuotes() {
    assertThat(
            ResultEditSql.delete(
            MYSQL, List.of("or`ders"), List.of("i`d"), List.of(1))
                .sql())
        .isEqualTo("DELETE FROM `or``ders` WHERE `i``d` = ?");
    assertThat(
            ResultEditSql.delete(
            DM8, List.of("or\"ders"), List.of("id"), List.of(1))
                .sql())
        .isEqualTo("DELETE FROM \"or\"\"ders\" WHERE \"id\" = ?");
  }

  @Test
  @DisplayName("确认凭据绑定文本 = 语句 + 参数的规范渲染（null/字符串/数字/布尔可区分）")
  void bindsDeterministicConfirmationText() {
    String a = ResultEditSql.boundText("UPDATE `t` SET `n` = ? WHERE `id` = ?", List.of("x", 5));
    String b = ResultEditSql.boundText("UPDATE `t` SET `n` = ? WHERE `id` = ?", List.of("x", 5));
    String differentValue =
        ResultEditSql.boundText("UPDATE `t` SET `n` = ? WHERE `id` = ?", List.of("y", 5));
    String nullValue =
        ResultEditSql.boundText(
            "UPDATE `t` SET `n` = ? WHERE `id` = ?", java.util.Arrays.asList(null, 5));

    assertThat(a).isEqualTo(b);
    assertThat(a).isNotEqualTo(differentValue);
    assertThat(a).isNotEqualTo(nullValue);
    assertThat(a).contains("-- 参数: [\"x\", 5]");
    assertThat(nullValue).contains("-- 参数: [null, 5]");
    assertThat(
            ResultEditSql.boundText("DELETE FROM t WHERE i = ?", List.of(true, "引\"号\\n")))
        .contains("-- 参数: [true, \"引\\\"号\\\\n\"]");
  }
}
