package io.github.dbconsole.resultedit;

import io.github.dbconsole.web.SessionAuthInterceptor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 结果编辑保存接口（F-17）。 */
@RestController
@RequestMapping("/api/sql/result-edit")
public class ResultEditController {

  private final ResultEditService resultEditService;

  public ResultEditController(ResultEditService resultEditService) {
    this.resultEditService = resultEditService;
  }

  /** 保存暂存更改：未确认时返回完整语句预览与确认凭据，确认后单一短事务执行。 */
  @PostMapping("/save")
  public ResultEditResponses save(
      @RequestAttribute(SessionAuthInterceptor.SESSION_TOKEN_ATTRIBUTE) String sessionToken,
      @RequestAttribute(SessionAuthInterceptor.USERNAME_ATTRIBUTE) String username,
      @Valid @RequestBody ResultEditRequest request) {
    return resultEditService.save(sessionToken, username, request);
  }
}
