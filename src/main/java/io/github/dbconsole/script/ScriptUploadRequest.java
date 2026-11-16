package io.github.dbconsole.script;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 脚本上传请求（F-13）：浏览器只传数据源标识、文件名与 UTF-8 脚本文本，不含连接信息。 */
public record ScriptUploadRequest(
    @NotBlank String sourceId, @NotBlank String filename, @NotNull String content) {}
