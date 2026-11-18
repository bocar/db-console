package io.github.dbconsole.dialect;

import io.github.dbconsole.config.DbConsoleProperties.CompatibilityMode;
import io.github.dbconsole.config.DbConsoleProperties.DatabaseType;

/**
 * 方言族判定的单一收口：MySQL 系（含 TiDB 与 MySQL 兼容模式）与其余（DM8/Oracle/SQL Server 系）。
 * 分词器旗标、语句分析器选型、标识符引用符与结构限定位映射共用本判定。
 */
public final class DialectFamily {

  private DialectFamily() {}

  public static boolean isMysqlFamily(DatabaseType type, CompatibilityMode mode) {
    return type == DatabaseType.MYSQL
        || type == DatabaseType.TIDB
        || mode == CompatibilityMode.MYSQL;
  }
}
