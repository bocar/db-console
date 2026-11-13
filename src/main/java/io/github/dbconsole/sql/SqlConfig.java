package io.github.dbconsole.sql;

import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.jdbc.ConnectionFactory;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** SQL 执行层装配。 */
@Configuration
public class SqlConfig {

  @Bean
  public ConfirmationService confirmationService() {
    return new ConfirmationService();
  }

  @Bean
  public SqlExecutionService sqlExecutionService(
      DataSourceRegistry registry,
      ConfirmationService confirmations,
      ConnectionFactory connections,
      DbConsoleProperties properties) {
    return new SqlExecutionService(registry, confirmations, connections, properties);
  }
}
