package io.github.dbconsole.script;

/** 预校验未通过的单条语句（F-13）：序号定位原文，错误码与面向用户的原因逐条报告。 */
public record ScriptStatementError(int index, String sql, String code, String message) {}
