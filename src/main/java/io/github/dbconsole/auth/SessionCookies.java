package io.github.dbconsole.auth;

import org.springframework.http.ResponseCookie;

/**
 * 会话 Cookie 的统一构造：HttpOnly、SameSite=Strict。
 *
 * <p>会话 Cookie 不设 maxAge：浏览器会话存续期间始终携带，闲置超时由服务端滑动续期兜底
 * （否则活跃用户会在固定时限点被浏览器丢弃 Cookie，尽管服务端会话仍有效）。
 */
public final class SessionCookies {

  public static final String SESSION_COOKIE = "DB_CONSOLE_SESSION";

  private SessionCookies() {}

  public static String sessionCookie(String token) {
    return base(token).build().toString();
  }

  public static String clearedCookie() {
    return base("").maxAge(0).build().toString();
  }

  private static ResponseCookie.ResponseCookieBuilder base(String token) {
    return ResponseCookie.from(SESSION_COOKIE, token)
        .httpOnly(true)
        .sameSite("Strict")
        .path("/");
  }
}
