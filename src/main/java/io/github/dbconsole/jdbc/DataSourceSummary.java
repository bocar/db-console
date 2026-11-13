package io.github.dbconsole.jdbc;

import io.github.dbconsole.config.DbConsoleProperties.CompatibilityMode;
import io.github.dbconsole.config.DbConsoleProperties.DataSourceEnvironment;
import io.github.dbconsole.config.DbConsoleProperties.DatabaseType;

/** 浏览器可见的数据源公开信息：不含连接信息与凭据（F-02）。 */
public record DataSourceSummary(
    String id,
    String displayName,
    DataSourceEnvironment environment,
    DatabaseType databaseType,
    CompatibilityMode compatibilityMode) {}
