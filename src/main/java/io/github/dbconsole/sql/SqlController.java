package io.github.dbconsole.sql;

import io.github.dbconsole.web.SessionAuthInterceptor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SQL 执行接口（F-04/F-05）与查询取消接口（F-14）。 */
@RestController
@RequestMapping("/api/sql")
public class SqlController {

  private final SqlExecutionService executionService;

  public SqlController(SqlExecutionService executionService) {
    this.executionService = executionService;
  }

  @PostMapping("/execute")
  public SqlResponses execute(
      @RequestAttribute(SessionAuthInterceptor.SESSION_TOKEN_ATTRIBUTE) String sessionToken,
      @RequestAttribute(SessionAuthInterceptor.USERNAME_ATTRIBUTE) String username,
      @Valid @RequestBody SqlRequest request) {
    return executionService.execute(sessionToken, username, request);
  }

  @PostMapping("/page")
  public SqlResponses page(
      @RequestAttribute(SessionAuthInterceptor.SESSION_TOKEN_ATTRIBUTE) String sessionToken,
      @RequestAttribute(SessionAuthInterceptor.USERNAME_ATTRIBUTE) String username,
      @Valid @RequestBody SqlPageRequest request) {
    return executionService.page(sessionToken, username, request);
  }

  /** 取消当前登录会话在本数据源运行中的查询（F-14）。 */
  @PostMapping("/{sourceId}/cancel")
  public CancelResponse cancel(
      @RequestAttribute(SessionAuthInterceptor.SESSION_TOKEN_ATTRIBUTE) String sessionToken,
      @PathVariable String sourceId) {
    return executionService.cancelRunningQuery(sessionToken, sourceId);
  }
}
