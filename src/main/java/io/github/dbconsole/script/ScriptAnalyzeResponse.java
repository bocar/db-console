package io.github.dbconsole.script;

import java.util.List;

/**
 * 脚本预校验响应（F-13）：valid=false 时 errors 逐条给出原因，整份脚本不执行且 scriptId
 * 为 null；valid=true 时 statements 按执行顺序携带类型与确认要求，scriptId 用于汇总历史
 * 端点取回文件名与全文（浏览器免二次上传）。预校验失败的语句不进入 statements。
 */
public record ScriptAnalyzeResponse(
    String sourceId,
    String filename,
    String scriptId,
    boolean valid,
    List<ScriptStatementPlan> statements,
    List<ScriptStatementError> errors) {}
