package io.github.dbconsole.jdbc;

import io.github.dbconsole.config.DbConsoleProperties;
import java.util.ArrayList;
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
  private final List<DataSourceSummary> summaries;

  public DataSourceRegistry(DbConsoleProperties properties, CredentialSource credentials) {
    Map<String, ResolvedDataSource> resolved = new LinkedHashMap<>();
    List<DataSourceSummary> publicSummaries = new ArrayList<>();
    for (DbConsoleProperties.DataSourceProperties source : properties.dataSources()) {
      ResolvedDataSource item = resolve(source, credentials);
      resolved.put(source.id(), item);
      publicSummaries.add(
          new DataSourceSummary(
              source.id(),
              source.displayName(),
              source.environment(),
              source.databaseType(),
              source.compatibilityMode()));
    }
    this.dataSourcesById = Collections.unmodifiableMap(resolved);
    this.summaries = List.copyOf(publicSummaries);
  }

  /** 浏览器可见的公开信息列表，保持配置顺序（启动时预计算）。 */
  public List<DataSourceSummary> summaries() {
    return summaries;
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
