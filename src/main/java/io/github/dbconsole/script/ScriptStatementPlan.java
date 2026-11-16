package io.github.dbconsole.script;

/**
 * 预校验通过的单条语句计划（F-13）：携带语句全文、类型与确认要求；
 * 需确认语句的凭据按「会话 + 数据源 + 语句文本」逐条签发，脚本内容任何改动即全部失效。
 */
public record ScriptStatementPlan(
    int index,
    String sql,
    String kind,
    String confirmationReason,
    boolean implicitCommitDdl,
    String confirmationToken) {}
