package io.github.dbconsole.auth;

import io.github.dbconsole.web.ApiError;
import io.github.dbconsole.web.SessionAuthInterceptor;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录会话的 HTTP 接口：登录、登出与会话探针。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final SessionService sessions;

  public AuthController(SessionService sessions) {
    this.sessions = sessions;
  }

  @PostMapping("/login")
  public ResponseEntity<?> login(
      @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    Optional<String> token = sessions.login(request.username(), request.password());
    if (token.isEmpty()) {
      return ResponseEntity.status(401)
          .body(new ApiError("BAD_CREDENTIALS", "用户名或密码不正确"));
    }
    response.addHeader(HttpHeaders.SET_COOKIE, SessionCookies.sessionCookie(token.get()));
    return ResponseEntity.ok(new SessionInfo(request.username()));
  }

  @PostMapping("/logout")
  public void logout(HttpServletRequest request, HttpServletResponse response) {
    if (request.getCookies() != null) {
      for (Cookie cookie : request.getCookies()) {
        if (SessionCookies.SESSION_COOKIE.equals(cookie.getName())) {
          sessions.logout(cookie.getValue());
        }
      }
    }
    response.addHeader(HttpHeaders.SET_COOKIE, SessionCookies.clearedCookie());
    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  @GetMapping("/session")
  public SessionInfo session(
      @RequestAttribute(SessionAuthInterceptor.USERNAME_ATTRIBUTE) String username) {
    return new SessionInfo(username);
  }
}
