package io.github.dbconsole.dialect;

/**
 * 语句操作类别（粗分类，ADR-0001）。
 * 确认与提交规则按类别分派（F-09/F-16）；范围之外的动词归 OTHER，由执行层拒绝。
 */
public enum StatementKind {
  SELECT(true, false, false),
  INSERT(false, true, false),
  UPDATE(false, true, false),
  DELETE(false, true, false),
  DESCRIBE(true, false, false),
  CREATE_TABLE(false, false, true),
  ALTER_TABLE(false, false, true),
  DROP_TABLE(false, false, true),
  /** DM8/Oracle 系的 COMMENT ON 属 DDL，执行即隐式提交（F-07/F-16）。 */
  COMMENT_ON(false, false, true),
  OTHER(false, false, false);

  private final boolean query;
  private final boolean write;
  private final boolean implicitCommitDdl;

  StatementKind(boolean query, boolean write, boolean implicitCommitDdl) {
    this.query = query;
    this.write = write;
    this.implicitCommitDdl = implicitCommitDdl;
  }

  public boolean isQuery() {
    return query;
  }

  public boolean isWrite() {
    return write;
  }

  /** MySQL/DM8/Oracle 的常规建表、改表、删表会触发隐式提交（F-16）。 */
  public boolean isImplicitCommitDdl() {
    return implicitCommitDdl;
  }
}
