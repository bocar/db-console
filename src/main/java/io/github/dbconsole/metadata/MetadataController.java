package io.github.dbconsole.metadata;

import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.jdbc.UnknownDataSourceException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 对象树与表结构接口（F-03/F-06）。 */
@RestController
@RequestMapping("/api/data-sources")
public class MetadataController {

  private final TableStructureService service;
  private final DataSourceRegistry registry;

  public MetadataController(TableStructureService service, DataSourceRegistry registry) {
    this.service = service;
    this.registry = registry;
  }

  @GetMapping("/{id}/tables")
  public List<TableStructure> listTables(@PathVariable String id) {
    return service.listTables(registry.resolve(id));
  }

  @GetMapping("/{id}/tables/{table}/structure")
  public TableStructure tableStructure(@PathVariable String id, @PathVariable String table) {
    TableStructure structure = service.tableStructure(registry.resolve(id), table);
    if (structure == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "表 " + table + " 不存在或无权访问");
    }
    return structure;
  }
}
