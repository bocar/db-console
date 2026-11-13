package io.github.dbconsole.transaction;

import java.sql.Connection;
import java.time.Instant;

/** 进行中的手动事务：绑定会话、数据源与单一 JDBC 连接（自动提交已关闭）。 */
final class ActiveTransaction {

  private final String sourceId;
  private final String username;
  private final Connection connection;
  private volatile Instant lastActivity;

  ActiveTransaction(String sourceId, String username, Connection connection, Instant startedAt) {
    this.sourceId = sourceId;
    this.username = username;
    this.connection = connection;
    this.lastActivity = startedAt;
  }

  String sourceId() {
    return sourceId;
  }

  String username() {
    return username;
  }

  Connection connection() {
    return connection;
  }

  Instant lastActivity() {
    return lastActivity;
  }

  void touch(Instant now) {
    this.lastActivity = now;
  }
}
