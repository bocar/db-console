package io.github.dbconsole.web;

import io.github.dbconsole.jdbc.ConnectionTestOutcome;
import io.github.dbconsole.jdbc.ConnectionTester;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.jdbc.DataSourceSummary;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 数据源 HTTP 接口：公开信息列表与连接测试（F-02）。 */
@RestController
@RequestMapping("/api/data-sources")
public class DataSourceController {

  private final DataSourceRegistry registry;
  private final ConnectionTester tester;

  public DataSourceController(DataSourceRegistry registry, ConnectionTester tester) {
    this.registry = registry;
    this.tester = tester;
  }

  @GetMapping
  public List<DataSourceSummary> list() {
    return registry.summaries();
  }

  @PostMapping("/{id}/connection-test")
  public ConnectionTestOutcome testConnection(@PathVariable String id) {
    return tester.test(id);
  }
}
