package io.github.dbconsole.transaction;

import io.github.dbconsole.audit.AuditService;
import io.github.dbconsole.auth.SessionService;
import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.jdbc.ConnectionFactory;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 事务层装配：手动事务管理器与空闲清理调度。 */
@Configuration
@EnableScheduling
public class TransactionConfig {

  @Bean
  public TransactionManager transactionManager(
      ConnectionFactory connections,
      SessionService sessions,
      AuditService audit,
      DbConsoleProperties properties,
      Clock clock) {
    return new TransactionManager(
        connections, sessions, audit, properties.transaction().idleTimeout(), clock);
  }
}
