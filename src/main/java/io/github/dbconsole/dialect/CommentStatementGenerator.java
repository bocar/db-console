package io.github.dbconsole.dialect;

import io.github.dbconsole.config.DbConsoleProperties.CompatibilityMode;
import io.github.dbconsole.config.DbConsoleProperties.DatabaseType;
import io.github.dbconsole.metadata.TableStructure.ColumnDefinition;

/**
 * 表列注释语句生成（F-07）：按方言产出新增或修改注释的 SQL。
 * MySQL 系使用 ALTER TABLE ... COMMENT 子句（保留原类型/默认值/约束）；
 * DM8/Oracle 系使用 COMMENT ON COLUMN。
 */
public final class CommentStatementGenerator {

  private CommentStatementGenerator() {}

  /**
   * 生成修改列注释的语句（保留原有类型、默认值与约束）。
   *
   * @param type 数据库类型与兼容模式
   * @param mode 兼容模式
   * @param tableName 表名（不含引用包裹）
   * @param column 列定义（含原类型/默认值/可空性）
   * @param comment 新注释文本
   * @return 可直接进入编辑器的 SQL 语句
   */
  public static String columnComment(
      DatabaseType type, CompatibilityMode mode,
      String tableName, ColumnDefinition column, String comment) {
    boolean mysqlFamily =
        type == DatabaseType.MYSQL || type == DatabaseType.TIDB || mode == CompatibilityMode.MYSQL;
    if (mysqlFamily) {
      return mysqlColumnComment(tableName, column, comment);
    }
    return oracleColumnComment(tableName, column, comment);
  }

  /**
   * MySQL：ALTER TABLE ... MODIFY COLUMN，保留原类型/默认值/NOT NULL/注释。
   * 类型与默认值来自 JDBC 元数据（ColumnDefinition），确保不改变结构。
   */
  private static String mysqlColumnComment(
      String tableName, ColumnDefinition column, String comment) {
    StringBuilder sql = new StringBuilder();
    sql.append("ALTER TABLE `").append(tableName).append("`\n");
    sql.append("  MODIFY COLUMN `").append(column.name()).append("` ");
    sql.append(column.type());
    if (!column.nullable()) {
      sql.append(" NOT NULL");
    }
    if (column.defaultValue() != null && !column.defaultValue().isEmpty()) {
      sql.append(" DEFAULT ").append(column.defaultValue());
    }
    sql.append(" COMMENT '").append(escape(comment)).append("'");
    return sql.toString();
  }

  /** DM8/Oracle：COMMENT ON COLUMN，独立语句不触碰列结构。 */
  private static String oracleColumnComment(
      String tableName, ColumnDefinition column, String comment) {
    return String.format(
        "COMMENT ON COLUMN %s.%s IS '%s'", tableName, column.name(), escape(comment));
  }

  private static String escape(String text) {
    return text == null ? "" : text.replace("'", "''");
  }
}
