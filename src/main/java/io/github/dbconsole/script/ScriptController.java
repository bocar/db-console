package io.github.dbconsole.script;

import io.github.dbconsole.sql.ExecutionForm;
import io.github.dbconsole.sql.ExecutionOrchestrator;
import io.github.dbconsole.sql.SqlRequest;
import io.github.dbconsole.sql.SqlResponses;
import io.github.dbconsole.web.SessionAuthInterceptor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 脚本执行接口（F-13）：整份预校验、逐条执行与运行汇总历史；编排逻辑在服务层。 */
@RestController
@RequestMapping("/api/sql/script")
public class ScriptController {

  private final ScriptAnalyzer scriptAnalyzer;
  private final ScriptService scriptService;
  private final ExecutionOrchestrator orchestrator;

  public ScriptController(
      ScriptAnalyzer scriptAnalyzer,
      ScriptService scriptService,
      ExecutionOrchestrator orchestrator) {
    this.scriptAnalyzer = scriptAnalyzer;
    this.scriptService = scriptService;
    this.orchestrator = orchestrator;
  }

  /** 预校验：整份脚本任一条未通过即整体无效，逐条报告原因。 */
  @PostMapping("/analyze")
  public ScriptAnalyzeResponse analyze(
      @RequestAttribute(SessionAuthInterceptor.SESSION_TOKEN_ATTRIBUTE) String sessionToken,
      @Valid @RequestBody ScriptUploadRequest request) {
    return scriptAnalyzer.analyze(sessionToken, request);
  }

  /** 逐条执行：自动提交连接、不写会话历史；确认凭据逐语句校验。 */
  @PostMapping("/execute")
  public SqlResponses execute(
      @RequestAttribute(SessionAuthInterceptor.SESSION_TOKEN_ATTRIBUTE) String sessionToken,
      @RequestAttribute(SessionAuthInterceptor.USERNAME_ATTRIBUTE) String username,
      @Valid @RequestBody SqlRequest request) {
    return orchestrator.run(
        new ExecutionForm.ScriptStatementForm(
            sessionToken, username, request.sourceId(), request.sql(),
            request.confirmationToken()));
  }

  /** 汇总历史：完整执行结束的运行记录一条汇总（文件名 + 完整脚本 + 状态）。 */
  @PostMapping("/history")
  public void recordRun(
      @RequestAttribute(SessionAuthInterceptor.SESSION_TOKEN_ATTRIBUTE) String sessionToken,
      @Valid @RequestBody ScriptHistoryRequest request) {
    scriptService.recordRun(sessionToken, request);
  }
}
