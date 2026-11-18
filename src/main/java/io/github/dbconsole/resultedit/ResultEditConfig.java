package io.github.dbconsole.resultedit;

import io.github.dbconsole.audit.AuditService;
import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.jdbc.ConnectionFactory;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.metadata.TableStructureService;
import io.github.dbconsole.sql.ConfirmationService;
import io.github.dbconsole.sql.RunningQueryRegistry;
import io.github.dbconsole.transaction.TransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 结果编辑保存层装配（F-17）。 */
@Configuration
public class ResultEditConfig {

  @Bean
  public ResultEditService resultEditService(
      DataSourceRegistry registry,
      ConfirmationService confirmations,
      TransactionManager transactions,
      TableStructureService tableStructures,
      RunningQueryRegistry runningQueries,
      AuditService audit,
      DbConsoleProperties properties,
      ConnectionFactory connections) {
    return new ResultEditService(
        registry, confirmations, transactions, tableStructures, runningQueries, audit,
        properties, new ResultEditExecutor(connections));
  }
}
