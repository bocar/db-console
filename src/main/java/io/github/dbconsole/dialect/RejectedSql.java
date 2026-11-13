package io.github.dbconsole.dialect;

/** 被拒绝的输入（空白/纯注释/多语句）与面向用户的原因说明。 */
public record RejectedSql(String message) implements SqlAnalysisResult {}
