package io.github.dbconsole.metadata;

import io.github.dbconsole.dialect.CommentStatementGenerator;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 表列注释语句生成接口（F-07）。 */
@RestController
@RequestMapping("/api/data-sources")
public class CommentStatementController {

  private final TableStructureService structures;
  private final DataSourceRegistry registry;

  public CommentStatementController(TableStructureService structures, DataSourceRegistry registry) {
    this.structures = structures;
    this.registry = registry;
  }

  public record CommentRequest(
      @NotBlank String tableName,
      @NotBlank String columnName,
      @NotBlank String comment) {}

  public record CommentResponse(String sql) {}

  @PostMapping("/{id}/comment-statement")
  public CommentResponse generate(
      @PathVariable String id, @Valid @RequestBody CommentRequest request) {
    ResolvedDataSource source = registry.resolve(id);
    TableStructure structure = structures.tableStructure(id, request.tableName());
    TableStructure.ColumnDefinition column =
        structure.columns().stream()
            .filter(c -> c.name().equals(request.columnName()))
            .findFirst()
            .orElseThrow(
                () -> new TableNotFoundException(
                    request.tableName() + "." + request.columnName()));
    String sql =
        CommentStatementGenerator.columnComment(
            source.databaseType(), source.compatibilityMode(),
            request.tableName(), column, request.comment());
    return new CommentResponse(sql);
  }
}
