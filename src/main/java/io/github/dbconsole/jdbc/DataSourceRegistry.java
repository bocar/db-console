package io.github.dbconsole.jdbc;

import io.github.dbconsole.config.DbConsoleProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源注册表：把不可变配置解析为可用连接信息。
 * 启动时校验凭据引用可解析（配置无效启动失败，REQUIREMENTS §3）；
 * 连通性与认证在连接测试或首次使用时检查。
 */
public final class DataSourceRegistry {

  private final Map<String, ResolvedDataSource> dataSourcesById;

  public DataSourceRegistry(DbConsoleProperties properties, CredentialSource credentials) {
    Map<String, ResolvedDataSource> resolved = new LinkedHashMap<>();
    for (DbConsoleProperties.DataSourceProperties source : properties.dataSources()) {
      resolved.put(source.id(), resolve(source, credentials));
    }
    this.dataSourcesById = Collections.unmodifiableMap(resolved);
  }

  /** 浏览器可见的公开信息列表，保持配置顺序。 */
  public List<DataSourceSummary> summaries() {
    return dataSourcesById.values().stream()
        .map(source ->
            new DataSourceSummary(
                source.id(),
                source.displayName(),
                source.environment(),
                source.databaseType(),
                source.compatibilityMode()))
        .toList();
  }

  /** 按标识解析；未知标识拒绝。 */
  public ResolvedDataSource resolve(String id) {
    ResolvedDataSource resolved = dataSourcesById.get(id);
    if (resolved == null) {
      throw new UnknownDataSourceException(id);
    }
    return resolved;
  }

  private static ResolvedDataSource resolve(
      DbConsoleProperties.DataSourceProperties source, CredentialSource credentials) {
    String password = credentials.resolve(source.credentialRef());
    if (password == null || password.isBlank()) {
      throw new IllegalStateException(
          "数据源 %s 的凭据环境变量未配置: %s".formatted(source.id(), source.credentialRef()));
    }
    return new ResolvedDataSource(
        source.id(),
        source.displayName(),
        source.environment(),
        source.databaseType(),
        source.compatibilityMode(),
        source.jdbcUrl(),
        source.username(),
        password);
  }
}
