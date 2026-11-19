package io.github.dbconsole.dialect;

import io.github.dbconsole.config.DbConsoleProperties.CompatibilityMode;
import io.github.dbconsole.config.DbConsoleProperties.DatabaseType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

/**
 * 结果编辑语句生成（F-17，方言层）：按方言产出参数化（占位符）的 DELETE / UPDATE / INSERT，
 * 值一律走参数绑定，不拼接字面量（注入面收口在 JDBC）。引用符按方言族选择：MySQL 系反引号、
 * 其余双引号，内嵌引号双写转义——与 {@link CommentStatementGenerator} 同一方言族判定。
 * {@link #boundText} 提供确认凭据绑定文本：语句 + 参数的规范渲染，值变即文本变（F-09 绑定不变式）。
 */
public final class ResultEditSql {

  private ResultEditSql() {}

  /** 生成的单条语句：SQL 文本（含 ? 占位符）、按序参数与操作类别（确认判定用）。 */
  public record Generated(String sql, List<Object> params, StatementKind kind) {}

  /** DELETE：主键列按序并列定位。 */
  public static Generated delete(
      Dialect dialect,
      List<String> tableParts, List<String> keyColumns, List<Object> keyValues) {
    String sql =
        "DELETE FROM " + dialect.quoteQualified(tableParts) + whereByKey(dialect, keyColumns);
    return new Generated(sql, copyNullable(keyValues), StatementKind.DELETE);
  }

  /** UPDATE：仅变更列进 SET，主键定位在 WHERE；参数序 = SET 值 + 主键值。 */
  public static Generated update(
      Dialect dialect,
      List<String> tableParts,
      List<String> keyColumns, List<Object> keyValues,
      List<String> setColumns, List<Object> setValues) {
    StringJoiner assignments = new StringJoiner(", ");
    for (String column : setColumns) {
      assignments.add(dialect.quoteIdentifier(column) + " = ?");
    }
    String sql =
        "UPDATE " + dialect.quoteQualified(tableParts)
            + " SET " + assignments + whereByKey(dialect, keyColumns);
    List<Object> params = new ArrayList<>(setValues.size() + keyValues.size());
    params.addAll(setValues);
    params.addAll(keyValues);
    return new Generated(sql, copyNullable(params), StatementKind.UPDATE);
  }

  /** INSERT：仅携带提供值的列，其余走库端默认。 */
  public static Generated insert(
      Dialect dialect,
      List<String> tableParts, List<String> columns, List<Object> values) {
    StringJoiner quotedColumns = new StringJoiner(", ");
    StringJoiner placeholders = new StringJoiner(", ");
    for (String column : columns) {
      quotedColumns.add(dialect.quoteIdentifier(column));
      placeholders.add("?");
    }
    String sql =
        "INSERT INTO " + dialect.quoteQualified(tableParts)
            + " (" + quotedColumns + ") VALUES (" + placeholders + ")";
    return new Generated(sql, copyNullable(values), StatementKind.INSERT);
  }

  /** 参数可含 null（SET NULL / NULL 值插入），List.copyOf 不接受 null 元素。 */
  private static List<Object> copyNullable(List<Object> values) {
    return Collections.unmodifiableList(new ArrayList<>(values));
  }

  /**
   * 确认凭据绑定文本（F-09 绑定不变式在参数化语句上的延伸）：语句文本 + 参数的规范渲染。
   * 占位符语句只约束形状，值变必须使凭据失效——渲染保证 null / 字符串 / 数字 / 布尔可区分。
   */
  public static String boundText(String sql, List<Object> params) {
    StringJoiner rendered = new StringJoiner(", ");
    for (Object param : params) {
      rendered.add(renderParam(param));
    }
    return sql + "\n-- 参数: [" + rendered + "]";
  }

  private static String renderParam(Object param) {
    if (param == null) {
      return "null";
    }
    if (param instanceof String text) {
      return "\"" + escapeJson(text) + "\"";
    }
    return param.toString();
  }

  private static String escapeJson(String text) {
    StringBuilder escaped = new StringBuilder(text.length() + 8);
    for (int index = 0; index < text.length(); index++) {
      char current = text.charAt(index);
      switch (current) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (current < 0x20) {
            escaped.append(String.format("\\u%04x", (int) current));
          } else {
            escaped.append(current);
          }
        }
      }
    }
    return escaped.toString();
  }

  private static String whereByKey(Dialect dialect, List<String> keyColumns) {
    StringJoiner predicates = new StringJoiner(" AND ");
    for (String column : keyColumns) {
      predicates.add(dialect.quoteIdentifier(column) + " = ?");
    }
    return " WHERE " + predicates;
  }

  private static String qualified(
      DatabaseType type, CompatibilityMode mode, List<String> tableParts) {
    StringJoiner qualified = new StringJoiner(".");
    for (String part : tableParts) {
      qualified.add(quote(type, mode, part));
    }
    return qualified.toString();
  }

  private static String quote(DatabaseType type, CompatibilityMode mode, String identifier) {
    char quote = DialectFamily.isMysqlFamily(type, mode) ? '`' : '"';
    String doubled = String.valueOf(quote) + quote;
    return quote + identifier.replace(String.valueOf(quote), doubled) + quote;
  }
}
