package io.github.dbconsole.sql;

/** 数据库执行失败：message 面向用户可定位，不含堆栈与凭据。 */
public class DatabaseExecutionException extends RuntimeException {

  public DatabaseExecutionException(String message) {
    super(message);
  }
}
