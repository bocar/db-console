package io.github.dbconsole.dialect;

/** 合法单条语句的分析结果：操作类别与最外层 WHERE 有无。 */
public record SqlStatement(StatementKind kind, boolean hasOuterWhere)
    implements SqlAnalysisResult {}
