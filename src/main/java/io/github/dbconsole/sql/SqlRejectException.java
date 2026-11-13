package io.github.dbconsole.sql;

/** 请求在执行前被拒绝：边界、范围或长度不合规则。 */
public class SqlRejectException extends RuntimeException {

  private final String code;

  public SqlRejectException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
