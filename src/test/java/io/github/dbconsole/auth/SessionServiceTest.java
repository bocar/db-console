package io.github.dbconsole.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dbconsole.config.DbConsoleProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionServiceTest {

  private static final Duration TIMEOUT = Duration.ofHours(8);

  private final FakeClock clock = new FakeClock(Instant.parse("2026-11-13T08:00:00Z"));

  private SessionService service() {
    return new SessionService(
        new DbConsoleProperties.LoginProperties("admin", "test-secret"), TIMEOUT, clock);
  }

  @Test
  @DisplayName("正确账号密码换得会话令牌")
  void loginWithMatchingCredentialsReturnsToken() {
    Optional<String> token = service().login("admin", "test-secret");

    assertThat(token).isPresent();
  }

  @Test
  @DisplayName("错误密码或错误用户名都换不到令牌")
  void loginWithWrongCredentialsReturnsEmpty() {
    assertThat(service().login("admin", "wrong")).isEmpty();
    assertThat(service().login("root", "test-secret")).isEmpty();
  }

  @Test
  @DisplayName("令牌通过认证并返回登录账号")
  void authenticateReturnsUsernameForValidToken() {
    SessionService sessions = service();
    String token = sessions.login("admin", "test-secret").orElseThrow();

    assertThat(sessions.authenticate(token)).contains("admin");
  }

  @Test
  @DisplayName("未知令牌认证失败")
  void authenticateRejectsUnknownToken() {
    assertThat(service().authenticate("no-such-token")).isEmpty();
  }

  @Test
  @DisplayName("空闲超过会话有效期后令牌失效")
  void sessionExpiresAfterIdleTimeout() {
    SessionService sessions = service();
    String token = sessions.login("admin", "test-secret").orElseThrow();

    clock.advance(TIMEOUT.plusSeconds(1));

    assertThat(sessions.authenticate(token)).isEmpty();
  }

  @Test
  @DisplayName("每次访问滑动续期：连续访问不过期，停止访问后过期")
  void slidingRenewalKeepsActiveSessionsAlive() {
    SessionService sessions = service();
    String token = sessions.login("admin", "test-secret").orElseThrow();

    clock.advance(TIMEOUT.minusSeconds(1));
    assertThat(sessions.authenticate(token)).contains("admin");

    clock.advance(TIMEOUT.minusSeconds(1));
    assertThat(sessions.authenticate(token)).contains("admin");

    clock.advance(TIMEOUT.plusSeconds(1));
    assertThat(sessions.authenticate(token)).isEmpty();
  }

  @Test
  @DisplayName("登出后令牌立即失效")
  void logoutInvalidatesToken() {
    SessionService sessions = service();
    String token = sessions.login("admin", "test-secret").orElseThrow();

    sessions.logout(token);

    assertThat(sessions.authenticate(token)).isEmpty();
  }

  private static final class FakeClock extends Clock {

    private Instant now;

    private FakeClock(Instant now) {
      this.now = now;
    }

    private void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
