package io.github.dbconsole.sql;

import io.github.dbconsole.config.DbConsoleProperties.DataSourceEnvironment;
import io.github.dbconsole.dialect.SqlStatement;
import io.github.dbconsole.dialect.StatementKind;

/** 确认规则分派（F-09/F-16）：环境 × 操作类别 × 最外层 WHERE。 */
public final class ConfirmationDecision {

  public enum Requirement {
    NOT_REQUIRED,
    PRODUCTION_WRITE,
    MISSING_WHERE,
    IMPLICIT_COMMIT_DDL
  }

  private ConfirmationDecision() {}

  public static Requirement evaluate(DataSourceEnvironment environment, SqlStatement statement) {
    if (statement.kind().isImplicitCommitDdl()) {
      return Requirement.IMPLICIT_COMMIT_DDL;
    }
    if (statement.kind().isWrite()) {
      if (environment == DataSourceEnvironment.PRODUCTION) {
        return Requirement.PRODUCTION_WRITE;
      }
      // 无 WHERE 确认仅适用于 UPDATE/DELETE；INSERT 无 WHERE 语义
      boolean updateOrDeleteWithoutWhere =
          (statement.kind() == StatementKind.UPDATE
                  || statement.kind() == StatementKind.DELETE)
              && !statement.hasOuterWhere();
      if (updateOrDeleteWithoutWhere) {
        return Requirement.MISSING_WHERE;
      }
    }
    return Requirement.NOT_REQUIRED;
  }
}
