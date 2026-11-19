package io.github.dbconsole.sql;

import io.github.dbconsole.audit.AuditService;
import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.history.HistoryService;
import io.github.dbconsole.jdbc.ConnectionFactory;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.metadata.TableStructureService;
import io.github.dbconsole.transaction.TransactionManager;
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
  public RunningQueryRegistry runningQueryRegistry(DbConsoleProperties properties) {
    return new RunningQueryRegistry(properties.execution().maxConcurrency());
  }

  @Bean
  public StatementExecution statementExecution(
      ConnectionFactory connections,
      TransactionManager transactions,
      RunningQueryRegistry runningQueries,
      HistoryService history,
      DbConsoleProperties properties,
      TableStructureService tableStructures) {
    return new StatementExecution(
        connections, transactions, runningQueries, history, properties,
        new EditableResultMarker(tableStructures));
  }

  /** 语句执行编舞：确认门、登记、审计、终态分类的唯一漏斗（全部执行形态经此）。 */
  @Bean
  public ExecutionOrchestrator executionOrchestrator(
      DataSourceRegistry registry,
      ConfirmationService confirmations,
      RunningQueryRegistry runningQueries,
      TransactionManager transactions,
      TableStructureService tableStructures,
      AuditService audit,
      DbConsoleProperties properties,
      StatementExecution statementExecution,
      ConnectionFactory connections) {
    return new ExecutionOrchestrator(
        registry, confirmations, runningQueries, transactions, tableStructures, audit, properties,
        statementExecution, new ResultEditExecutor(connections));
  }
}
