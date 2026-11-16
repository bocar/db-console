package io.github.dbconsole.script;

import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.history.HistoryService;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.sql.ConfirmationService;
import io.github.dbconsole.transaction.TransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 脚本执行层装配（F-13）。 */
@Configuration
public class ScriptConfig {

  @Bean
  public ScriptRunStore scriptRunStore() {
    return new ScriptRunStore();
  }

  @Bean
  public ScriptAnalyzer scriptAnalyzer(
      DataSourceRegistry registry,
      ConfirmationService confirmations,
      TransactionManager transactions,
      ScriptRunStore runStore,
      DbConsoleProperties properties) {
    return new ScriptAnalyzer(registry, confirmations, transactions, runStore, properties);
  }

  @Bean
  public ScriptService scriptService(
      DataSourceRegistry registry, ScriptRunStore runStore, HistoryService history) {
    return new ScriptService(registry, runStore, history);
  }
}
