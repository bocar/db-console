package io.github.dbconsole.jdbc;

/** 未知数据源标识：浏览器伪造的连接目标一律拒绝。 */
public class UnknownDataSourceException extends RuntimeException {

  public UnknownDataSourceException(String id) {
    super("未知数据源标识: " + id);
  }
}
