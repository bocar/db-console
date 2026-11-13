package io.github.dbconsole.dialect;

/** 方言分析结果：合法单条语句，或拒绝原因。 */
public sealed interface SqlAnalysisResult permits SqlStatement, RejectedSql {

  enum RejectionReason {
    EMPTY_OR_COMMENT_ONLY,
    MULTIPLE_STATEMENTS
  }
}
