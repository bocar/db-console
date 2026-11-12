package io.github.dbconsole.auth;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/** 会话 Cookie 的统一构造：HttpOnly、SameSite=Strict。 */
public final class SessionCookies {

  public static final String SESSION_COOKIE = "DB_CONSOLE_SESSION";

  private SessionCookies() {}

  public static String sessionCookie(String token, Duration timeout) {
    return ResponseCookie.from(SESSION_COOKIE, token)
        .httpOnly(true)
        .sameSite("Strict")
        .path("/")
        .maxAge(timeout)
        .build()
        .toString();
  }

  public static String clearedCookie() {
    return ResponseCookie.from(SESSION_COOKIE, "")
        .httpOnly(true)
        .sameSite("Strict")
        .path("/")
        .maxAge(0)
        .build()
        .toString();
  }
}
