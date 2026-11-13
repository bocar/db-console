package io.github.dbconsole.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dbconsole.auth.SessionService;
import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.jdbc.ConnectionFactory;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionManagerTest {

  private static final Duration IDLE = Duration.ofSeconds(2);

  private final FakeClock clock = new FakeClock(Instant.parse("2026-11-13T10:00:00Z"));
  private final Connection connection = mock(Connection.class);
  private final SessionService sessions =
      new SessionService(
          new DbConsoleProperties.LoginProperties("admin", "secret"),
          Duration.ofHours(8),
          clock,
          event -> {});
  private final TransactionManager manager = new TransactionManager(factory(), sessions, IDLE, clock);

  private ConnectionFactory factory() {
    ConnectionFactory factory = mock(ConnectionFactory.class);
    try {
      when(factory.open(source("mysql-work-note"))).thenReturn(connection);
      when(factory.open(source("dm8-navic"))).thenReturn(mock(Connection.class));
    } catch (SQLException exception) {
      throw new IllegalStateException(exception);
    }
    return factory;
  }

  private static ResolvedDataSource source(String id) {
    return new ResolvedDataSource(
        id,
        "显示名",
        io.github.dbconsole.config.DbConsoleProperties.DataSourceEnvironment.DEVELOPMENT,
        io.github.dbconsole.config.DbConsoleProperties.DatabaseType.MYSQL,
        io.github.dbconsole.config.DbConsoleProperties.CompatibilityMode.NATIVE,
        "jdbc:mysql://127.0.0.1:1/never",
        "root",
        "secret");
  }

  private String loginSession() {
    return sessions.login("admin", "secret").orElseThrow();
  }

  @Test
  @DisplayName("开启事务：连接关闭自动提交并按会话持有")
  void beginOpensConnectionWithAutoCommitOff() throws Exception {
    String token = loginSession();
    manager.begin(token, source("mysql-work-note"));

    verify(connection).setAutoCommit(false);
    assertThat(manager.hasActive(token)).isTrue();
    assertThat(manager.activeSource(token)).contains("mysql-work-note");
  }

  @Test
  @DisplayName("同一会话重复开启事务被拒绝")
  void beginRejectsWhenTransactionAlreadyOpen() throws Exception {
    String token = loginSession();
    manager.begin(token, source("mysql-work-note"));

    assertThatThrownBy(() -> manager.begin(token, source("mysql-work-note")))
        .isInstanceOf(TransactionStateException.class)
        .hasMessageContaining("提交或回滚");
  }

  @Test
  @DisplayName("提交作用于整个事务并释放连接")
  void commitClosesTransaction() throws Exception {
    String token = loginSession();
    manager.begin(token, source("mysql-work-note"));

    manager.commit(token, "mysql-work-note");

    verify(connection).commit();
    verify(connection).close();
    assertThat(manager.hasActive(token)).isFalse();
  }

  @Test
  @DisplayName("回滚撤销未提交变更并释放连接")
  void rollbackClosesTransaction() throws Exception {
    String token = loginSession();
    manager.begin(token, source("mysql-work-note"));

    manager.rollback(token, "mysql-work-note");

    verify(connection).rollback();
    verify(connection).close();
    assertThat(manager.hasActive(token)).isFalse();
  }

  @Test
  @DisplayName("无事务时提交或回滚被拒绝")
  void commitWithoutTransactionRejected() {
    String token = loginSession();

    assertThatThrownBy(() -> manager.commit(token, "mysql-work-note"))
        .isInstanceOf(TransactionStateException.class)
        .hasMessageContaining("没有未提交事务");
    assertThatThrownBy(() -> manager.rollback(token, "mysql-work-note"))
        .isInstanceOf(TransactionStateException.class);
  }

  @Test
  @DisplayName("事务连接按会话与数据源绑定：同源返回连接，跨源拒绝")
  void connectionForBindsSessionAndSource() throws Exception {
    String token = loginSession();
    manager.begin(token, source("mysql-work-note"));

    assertThat(manager.connectionFor(token, "mysql-work-note")).isSameAs(connection);
    assertThatThrownBy(() -> manager.connectionFor(token, "dm8-navic"))
        .isInstanceOf(TransactionStateException.class)
        .hasMessageContaining("先结束");
  }

  @Test
  @DisplayName("无事务会话取连接返回空，由调用方走自动提交")
  void connectionForWithoutTransactionIsNull() {
    assertThat(manager.connectionFor(loginSession(), "mysql-work-note")).isNull();
  }

  @Test
  @DisplayName("其他登录会话取不到该事务的连接（返回空，走独立连接）")
  void connectionForRejectsOtherSession() throws Exception {
    String owner = loginSession();
    String other = loginSession();
    manager.begin(owner, source("mysql-work-note"));

    assertThat(manager.connectionFor(other, "mysql-work-note")).isNull();
  }

  @Test
  @DisplayName("空闲超过时限后清理：回滚并释放")
  void cleanupRollsBackIdleTransactions() throws Exception {
    String token = loginSession();
    manager.begin(token, source("mysql-work-note"));

    clock.advance(IDLE.plusSeconds(1));
    manager.cleanupExpired();

    verify(connection).rollback();
    verify(connection).close();
    assertThat(manager.hasActive(token)).isFalse();
  }

  @Test
  @DisplayName("活动中的事务不会被清理，执行会刷新空闲时钟")
  void cleanupKeepsActiveTransactions() throws Exception {
    String token = loginSession();
    manager.begin(token, source("mysql-work-note"));

    clock.advance(IDLE.minusSeconds(1));
    manager.touch(token);
    clock.advance(IDLE.minusSeconds(1));
    manager.cleanupExpired();

    verify(connection, never()).rollback();
    assertThat(manager.hasActive(token)).isTrue();
  }

  @Test
  @DisplayName("会话结束（登出/失效）即时回滚其事务")
  void sessionEndRollsBackImmediately() throws Exception {
    String token = loginSession();
    manager.begin(token, source("mysql-work-note"));

    manager.onSessionEnd(token);

    verify(connection).rollback();
    verify(connection).close();
  }

  @Test
  @DisplayName("清理时发现会话已失效同样回滚")
  void cleanupRollsBackWhenSessionNoLongerValid() throws Exception {
    String token = loginSession();
    manager.begin(token, source("mysql-work-note"));
    sessions.logout(token);

    manager.cleanupExpired();

    verify(connection).rollback();
    assertThat(manager.hasActive(token)).isFalse();
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
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
