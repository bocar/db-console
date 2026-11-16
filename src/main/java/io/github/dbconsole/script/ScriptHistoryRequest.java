package io.github.dbconsole.script;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 脚本运行汇总历史请求（F-13）：仅完整执行结束的运行由前端调用；文件名与全文
 * 由服务端按 scriptId 从预校验暂存取回，客户端不重复上传、也不可断言内容。
 */
public record ScriptHistoryRequest(
    @NotBlank String sourceId,
    @NotBlank String scriptId,
    @NotNull @PositiveOrZero Long durationMs) {}
