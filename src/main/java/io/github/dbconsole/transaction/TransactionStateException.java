package io.github.dbconsole.transaction;

/** 手动事务状态冲突：无事务、已有未结束事务或跨数据源操作。 */
public class TransactionStateException extends RuntimeException {

  private final String code;

  public TransactionStateException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
