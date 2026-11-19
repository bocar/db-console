package io.github.dbconsole.sql;

import io.github.dbconsole.web.SessionAuthInterceptor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SQL 执行接口（F-04/F-05）与查询取消接口（F-14）：构造执行形态交编舞。 */
@RestController
@RequestMapping("/api/sql")
public class SqlController {

  private final ExecutionOrchestrator orchestrator;

  public SqlController(ExecutionOrchestrator orchestrator) {
    this.orchestrator = orchestrator;
  }

  @PostMapping("/execute")
  public SqlResponses execute(
      @RequestAttribute(SessionAuthInterceptor.SESSION_TOKEN_ATTRIBUTE) String sessionToken,
      @RequestAttribute(SessionAuthInterceptor.USERNAME_ATTRIBUTE) String username,
      @Valid @RequestBody SqlRequest request) {
    return orchestrator.run(
        new ExecutionForm.WorkbenchQueryForm(
            sessionToken, username, request.sourceId(), request.sql(),
            request.confirmationToken()));
  }

  @PostMapping("/page")
  public SqlResponses page(
      @RequestAttribute(SessionAuthInterceptor.SESSION_TOKEN_ATTRIBUTE) String sessionToken,
      @RequestAttribute(SessionAuthInterceptor.USERNAME_ATTRIBUTE) String username,
      @Valid @RequestBody SqlPageRequest request) {
    return orchestrator.run(
        new ExecutionForm.PageForm(
            sessionToken, username, request.sourceId(), request.sql(),
            request.page(), request.pageSize()));
  }

  /** 取消当前登录会话在本数据源运行中的查询（F-14）。 */
  @PostMapping("/{sourceId}/cancel")
  public CancelResponse cancel(
      @RequestAttribute(SessionAuthInterceptor.SESSION_TOKEN_ATTRIBUTE) String sessionToken,
      @PathVariable String sourceId) {
    return orchestrator.cancelRunningQuery(sessionToken, sourceId);
  }
}
