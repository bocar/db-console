package io.github.dbconsole.sql;

import io.github.dbconsole.web.SessionAuthInterceptor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SQL 执行接口（F-04/F-05）。 */
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
}
