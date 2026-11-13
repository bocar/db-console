package io.github.dbconsole.sql;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** SQL 执行请求：浏览器只传数据源标识与语句文本，确认凭据可选。空白 SQL 由分析器判定。 */
public record SqlRequest(
    @NotBlank String sourceId, @NotNull String sql, String confirmationToken) {}
