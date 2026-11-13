package io.github.dbconsole.history;

import io.github.dbconsole.web.SessionAuthInterceptor;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SQL 历史接口（F-12）：会话内按数据源查看与清空。 */
@RestController
@RequestMapping("/api/history")
public class HistoryController {

  private final HistoryService history;

  public HistoryController(HistoryService history) {
    this.history = history;
  }

  @GetMapping("/{sourceId}")
  public List<HistoryService.HistoryEntry> list(
      @RequestAttribute(SessionAuthInterceptor.SESSION_TOKEN_ATTRIBUTE) String sessionToken,
      @PathVariable String sourceId) {
    return history.list(sessionToken, sourceId);
  }

  @DeleteMapping("/{sourceId}")
  public void clear(
      @RequestAttribute(SessionAuthInterceptor.SESSION_TOKEN_ATTRIBUTE) String sessionToken,
      @PathVariable String sourceId) {
    history.clear(sessionToken, sourceId);
  }
}
