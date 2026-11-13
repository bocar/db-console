package io.github.dbconsole.sql;

/** 数据库执行失败：message 面向用户可定位，cause 保留原始异常供日志（不外发堆栈）。 */
public class DatabaseExecutionException extends RuntimeException {

  public DatabaseExecutionException(String message, Throwable cause) {
    super(message, cause);
  }
}
