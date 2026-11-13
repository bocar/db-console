package io.github.dbconsole.auth;

import io.github.dbconsole.config.DbConsoleProperties;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 登录会话装配。 */
@Configuration
public class AuthConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public SessionService sessionService(
      DbConsoleProperties properties,
      Clock clock,
      ApplicationEventPublisher eventPublisher) {
    return new SessionService(
        properties.login(), properties.session().timeout(), clock, eventPublisher);
  }
}
