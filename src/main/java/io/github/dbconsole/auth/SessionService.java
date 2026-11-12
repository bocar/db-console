package io.github.dbconsole.auth;

import io.github.dbconsole.config.DbConsoleProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存登录会话：令牌签发、滑动续期与过期清理。单实例部署，无持久化。 */
public final class SessionService {

  private final DbConsoleProperties.LoginProperties login;
  private final Duration timeout;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, LoginSession> sessionsByToken = new ConcurrentHashMap<>();

  public SessionService(
      DbConsoleProperties.LoginProperties login, Duration timeout, Clock clock) {
    this.login = login;
    this.timeout = timeout;
    this.clock = clock;
  }

  /** 校验登录凭据，成功时签发新会话令牌。 */
  public Optional<String> login(String username, String password) {
    boolean userMatches = constantEquals(login.username(), username);
    boolean passwordMatches = constantEquals(login.password(), password);
    if (!userMatches || !passwordMatches) {
      return Optional.empty();
    }
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = HexFormat.of().formatHex(bytes);
    sessionsByToken.put(token, new LoginSession(username, clock.instant()));
    return Optional.of(token);
  }

  /** 认证令牌并滑动续期，返回登录账号；未知或过期令牌返回空。 */
  public Optional<String> authenticate(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    sessionsByToken
        .values()
        .removeIf(session -> Duration.between(session.lastAccess(), now).compareTo(timeout) > 0);
    LoginSession session = sessionsByToken.get(token);
    if (session == null) {
      return Optional.empty();
    }
    sessionsByToken.put(token, new LoginSession(session.username(), now));
    return Optional.of(session.username());
  }

  /** 登出并令牌立即失效。 */
  public void logout(String token) {
    if (token != null) {
      sessionsByToken.remove(token);
    }
  }

  private boolean constantEquals(String expected, String actual) {
    if (expected == null || actual == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }

  private record LoginSession(String username, Instant lastAccess) {}
}
