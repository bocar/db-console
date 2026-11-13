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

/**
 * 内存登录会话：令牌签发、滑动续期与过期清理。单实例部署，无持久化。
 *
 * <p>过期判定按单个会话 O(1) 进行；已过期条目的回收以每 {@link #SWEEP_INTERVAL} 次认证
 * 一次的频率摊销执行，避免每个请求全表扫描。
 */
public final class SessionService {

  private static final int SWEEP_INTERVAL = 64;

  private final DbConsoleProperties.LoginProperties login;
  private final Duration timeout;
  private final Clock clock;
  private final org.springframework.context.ApplicationEventPublisher eventPublisher;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, LoginSession> sessionsByToken = new ConcurrentHashMap<>();
  private long authenticationCount;

  public SessionService(
      DbConsoleProperties.LoginProperties login,
      Duration timeout,
      Clock clock,
      org.springframework.context.ApplicationEventPublisher eventPublisher) {
    this.login = login;
    this.timeout = timeout;
    this.clock = clock;
    this.eventPublisher = eventPublisher;
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
    maybeSweepExpired();
    Instant now = clock.instant();
    LoginSession session = sessionsByToken.get(token);
    if (session == null || Duration.between(session.lastAccess(), now).compareTo(timeout) > 0) {
      sessionsByToken.remove(token);
      return Optional.empty();
    }
    session.slideAccess(now);
    return Optional.of(session.username());
  }

  /** 登出并令牌立即失效；发布会话结束事件供事务层等清理。 */
  public void logout(String token) {
    if (token != null && sessionsByToken.remove(token) != null) {
      eventPublisher.publishEvent(new SessionEnded(token));
    }
  }

  /** 会话结束事件（登出）。 */
  public record SessionEnded(String token) {}

  private synchronized void maybeSweepExpired() {
    if (++authenticationCount % SWEEP_INTERVAL != 0) {
      return;
    }
    Instant now = clock.instant();
    sessionsByToken
        .values()
        .removeIf(session -> Duration.between(session.lastAccess(), now).compareTo(timeout) > 0);
  }

  private boolean constantEquals(String expected, String actual) {
    if (expected == null || actual == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }

  /** 可变会话条目：滑动续期原地更新，避免每请求重建记录。 */
  private static final class LoginSession {

    private final String username;
    private volatile Instant lastAccess;

    private LoginSession(String username, Instant lastAccess) {
      this.username = username;
      this.lastAccess = lastAccess;
    }

    private String username() {
      return username;
    }

    private Instant lastAccess() {
      return lastAccess;
    }

    private void slideAccess(Instant now) {
      this.lastAccess = now;
    }
  }
}
