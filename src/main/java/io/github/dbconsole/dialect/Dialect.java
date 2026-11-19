package io.github.dbconsole.dialect;

import io.github.dbconsole.config.DbConsoleProperties.CompatibilityMode;
import io.github.dbconsole.config.DbConsoleProperties.DatabaseType;
import java.util.List;
import java.util.StringJoiner;

/**
 * 方言值对象（F-17 深化）：目标库方言知识的 locality 收口——族判定、标识符引用、
 * 分析器选型都问它，(DatabaseType, CompatibilityMode) 二元组不再穿行于签名之间。
 * 由 {@code ResolvedDataSource.dialect()} 派生，随数据源走。
 */
public record Dialect(DatabaseType type, CompatibilityMode mode) {

  /** MySQL 系（含 TiDB 与 MySQL 兼容模式）与其余（DM8/Oracle/SQL Server 系）之分。 */
  public boolean mysqlFamily() {
    return DialectFamily.isMysqlFamily(type, mode);
  }

  /** 标识符引用：MySQL 系反引号、其余双引号；内嵌引号双写转义。 */
  public String quoteIdentifier(String identifier) {
    char quote = mysqlFamily() ? '`' : '"';
    String doubled = String.valueOf(quote) + quote;
    return quote + identifier.replace(String.valueOf(quote), doubled) + quote;
  }

  /** 限定名引用：逐段引用后以点连接。 */
  public String quoteQualified(List<String> parts) {
    StringJoiner qualified = new StringJoiner(".");
    for (String part : parts) {
      qualified.add(quoteIdentifier(part));
    }
    return qualified.toString();
  }

  /** 单语句边界与操作分类分析器（ADR-0001）。 */
  public SqlStatementAnalyzer analyzer() {
    return SqlStatementAnalyzer.forDialect(type, mode);
  }

  /** 可编辑结果资格检测器（F-17）。 */
  public SingleTableSelectAnalyzer singleTableAnalyzer() {
    return SingleTableSelectAnalyzer.forDialect(type, mode);
  }
}
