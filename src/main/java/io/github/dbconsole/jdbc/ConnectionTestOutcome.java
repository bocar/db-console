package io.github.dbconsole.jdbc;

/** 连接测试结果：面向用户的诊断，message 不含凭据与连接细节。 */
public record ConnectionTestOutcome(boolean ok, ConnectionFailureKind kind, String message) {

  public static ConnectionTestOutcome success() {
    return new ConnectionTestOutcome(true, ConnectionFailureKind.NONE, "连接正常");
  }

  public static ConnectionTestOutcome failure(ConnectionFailureKind kind) {
    String message =
        switch (kind) {
          case AUTHENTICATION -> "认证失败：数据库账号或密码不正确";
          case TIMEOUT -> "连接超时：目标数据库未在时限内响应";
          case UNAVAILABLE -> "无法连接到数据库服务器";
          case UNKNOWN, NONE -> "连接失败，原因未知";
        };
    return new ConnectionTestOutcome(false, kind, message);
  }
}
