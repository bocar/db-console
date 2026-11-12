package io.github.dbconsole.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dbconsole.auth.AuthController;
import io.github.dbconsole.auth.SessionCookies;
import io.github.dbconsole.auth.SessionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

/** 受保护接口的登录会话校验：无有效会话一律 401。 */
public final class SessionAuthInterceptor implements HandlerInterceptor {

  private final SessionService sessions;
  private final ObjectMapper objectMapper;

  public SessionAuthInterceptor(SessionService sessions, ObjectMapper objectMapper) {
    this.sessions = sessions;
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler)
      throws IOException {
    Optional<String> username = sessions.authenticate(sessionToken(request));
    if (username.isPresent()) {
      request.setAttribute(AuthController.USERNAME_ATTRIBUTE, username.get());
      return true;
    }
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            objectMapper.writeValueAsString(
                new ApiError("UNAUTHENTICATED", "未登录或会话已失效，请重新登录")));
    return false;
  }

  private String sessionToken(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (SessionCookies.SESSION_COOKIE.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }
}
