package io.github.dbconsole.dialect;

/** 被拒绝的输入与原因（空白/纯注释/多语句）。 */
public record RejectedSql(SqlAnalysisResult.RejectionReason reason, String message)
    implements SqlAnalysisResult {}
