package io.github.dbconsole.metadata;

/** 元数据读取失败（JDBC DatabaseMetaData 异常包装）。 */
public class MetadataException extends RuntimeException {

  public MetadataException(String message, Throwable cause) {
    super(message, cause);
  }
}
