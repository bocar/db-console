package io.github.dbconsole.dialect;

/** 方言分析结果：合法单条语句，或被拒绝的输入。 */
public sealed interface SqlAnalysisResult permits SqlStatement, RejectedSql {}
