package io.github.dbconsole.metadata;

/** 目标表不存在或无权访问（F-06）。 */
public class TableNotFoundException extends RuntimeException {

  public TableNotFoundException(String tableName) {
    super("表 " + tableName + " 不存在或无权访问");
  }
}
