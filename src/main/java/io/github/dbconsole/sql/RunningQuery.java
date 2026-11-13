package io.github.dbconsole.sql;

import java.sql.Statement;
import java.util.UUID;

/**
 * 一条在执行的查询登记（F-14）：绑定登录会话与数据源，
 * 后续可挂载 JDBC 语句以支持取消；取消标记只对本会话的取消请求可见。
 */
public final class RunningQuery {

  private final String id = UUID.randomUUID().toString();
  private final String sessionToken;
  private final String sourceId;
  private volatile Statement statement;
  private volatile boolean cancelRequested;

  RunningQuery(String sessionToken, String sourceId) {
    this.sessionToken = sessionToken;
    this.sourceId = sourceId;
  }

  String id() {
    return id;
  }

  String sessionToken() {
    return sessionToken;
  }

  String sourceId() {
    return sourceId;
  }

  void attach(Statement jdbcStatement) {
    this.statement = jdbcStatement;
  }

  void requestCancel() {
    this.cancelRequested = true;
  }

  boolean isCancelRequested() {
    return cancelRequested;
  }

  Statement statement() {
    return statement;
  }
}
