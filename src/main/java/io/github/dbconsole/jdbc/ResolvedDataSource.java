package io.github.dbconsole.jdbc;

import io.github.dbconsole.config.DbConsoleProperties.CompatibilityMode;
import io.github.dbconsole.config.DbConsoleProperties.DataSourceEnvironment;
import io.github.dbconsole.config.DbConsoleProperties.DatabaseType;
import io.github.dbconsole.dialect.Dialect;

/**
 * 解析后的数据源：含连接信息与凭据，只在服务端使用。
 * toString 不输出凭据与 URL，防止日志泄漏。
 */
public record ResolvedDataSource(
    String id,
    String displayName,
    DataSourceEnvironment environment,
    DatabaseType databaseType,
    CompatibilityMode compatibilityMode,
    String jdbcUrl,
    String username,
    String password) {

  /** 方言值对象（F-17 深化）：方言知识（族判定/引用/分析器）的取用入口。 */
  public Dialect dialect() {
    return Dialect.of(databaseType(), compatibilityMode());
  }

  @Override
  public String toString() {
    return "ResolvedDataSource[%s]".formatted(id);
  }
}
