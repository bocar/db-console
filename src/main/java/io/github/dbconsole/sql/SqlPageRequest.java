package io.github.dbconsole.sql;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 翻页请求：携带原只读查询与目标页，不持有跨请求游标。 */
public record SqlPageRequest(
    @NotBlank String sourceId,
    @NotNull String sql,
    @Positive int page,
    @Positive int pageSize) {}
