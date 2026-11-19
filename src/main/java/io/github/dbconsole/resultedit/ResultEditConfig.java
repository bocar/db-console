package io.github.dbconsole.resultedit;

import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.metadata.TableStructureService;
import io.github.dbconsole.sql.ExecutionOrchestrator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 结果编辑保存层装配（F-17）。 */
@Configuration
public class ResultEditConfig {

  @Bean
  public ResultEditService resultEditService(
      DataSourceRegistry registry,
      TableStructureService tableStructures,
      DbConsoleProperties properties,
      ExecutionOrchestrator orchestrator) {
    return new ResultEditService(registry, tableStructures, properties, orchestrator);
  }
}
