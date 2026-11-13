package io.github.dbconsole.dialect;

/**
 * 语句操作类别（粗分类，ADR-0001）。
 * 确认与提交规则按类别分派（F-09/F-16）；范围之外的动词归 OTHER，由执行层拒绝。
 */
public enum StatementKind {
  SELECT(false, false),
  INSERT(true, false),
  UPDATE(true, false),
  DELETE(true, false),
  DESCRIBE(false, false),
  CREATE_TABLE(false, true),
  ALTER_TABLE(false, true),
  DROP_TABLE(false, true),
  /** DM8/Oracle 系的 COMMENT ON 属 DDL，执行即隐式提交（F-07/F-16）。 */
  COMMENT_ON(false, true),
  OTHER(false, false);

  private final boolean write;
  private final boolean implicitCommitDdl;

  StatementKind(boolean write, boolean implicitCommitDdl) {
    this.write = write;
    this.implicitCommitDdl = implicitCommitDdl;
  }

  public boolean isWrite() {
    return write;
  }

  /** MySQL/DM8/Oracle 的常规建表、改表、删表会触发隐式提交（F-16）。 */
  public boolean isImplicitCommitDdl() {
    return implicitCommitDdl;
  }
}
