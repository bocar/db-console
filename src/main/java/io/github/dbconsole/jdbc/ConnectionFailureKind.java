package io.github.dbconsole.jdbc;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Locale;

/** 连接测试失败分类：区分认证失败、超时与不可达（F-02）。 */
public enum ConnectionFailureKind {
  NONE,
  AUTHENTICATION,
  TIMEOUT,
  UNAVAILABLE,
  UNKNOWN;

  /** 写入或提交超时/断连且状态无法确认（F-10「执行结果未知」）。 */
  public boolean isOutcomeUnknown() {
    return this == TIMEOUT || this == UNAVAILABLE;
  }

  /** 按驱动异常特征分类；只依赖 SQLState 与消息文本，不回传原始细节。 */
  public static ConnectionFailureKind classify(SQLException exception) {
    if (exception instanceof SQLTimeoutException) {
      return TIMEOUT;
    }
    String sqlState = exception.getSQLState();
    if (sqlState != null && sqlState.startsWith("28")) {
      return AUTHENTICATION;
    }
    String rawMessage = exception.getMessage();
    String message =
        (rawMessage == null ? "" : rawMessage).toLowerCase(Locale.ROOT);
    if (message.contains("access denied")
        || message.contains("用户名或密码")
        || message.contains("登录失败")
        || message.contains("login failed")
        || message.contains("invalid username/password")) {
      return AUTHENTICATION;
    }
    if (message.contains("timed out") || message.contains("timeout")) {
      return TIMEOUT;
    }
    if (message.contains("connection refused") || message.contains("communications link failure")) {
      return UNAVAILABLE;
    }
    return message.isEmpty() ? UNAVAILABLE : UNKNOWN;
  }
}
