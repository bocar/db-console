package io.github.dbconsole.jdbc;

import io.github.dbconsole.config.DbConsoleProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** JDBC 层装配：数据源注册表与连接测试。 */
@Configuration
public class JdbcConfig {

  @Bean
  public CredentialSource credentialSource() {
    return CredentialSource.fromEnvironment();
  }

  @Bean
  public DataSourceRegistry dataSourceRegistry(
      DbConsoleProperties properties, CredentialSource credentials) {
    return new DataSourceRegistry(properties, credentials);
  }

  @Bean
  public ConnectionTester connectionTester(DataSourceRegistry registry) {
    return new ConnectionTester(registry);
  }
}
