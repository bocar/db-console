package io.github.dbconsole.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dbconsole.auth.SessionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Web 层装配：登录会话拦截器。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final SessionService sessions;
  private final ObjectMapper objectMapper;

  public WebConfig(SessionService sessions, ObjectMapper objectMapper) {
    this.sessions = sessions;
    this.objectMapper = objectMapper;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(new SessionAuthInterceptor(sessions, objectMapper))
        .addPathPatterns("/api/**")
        .excludePathPatterns("/api/auth/login", "/api/auth/logout");
  }
}
