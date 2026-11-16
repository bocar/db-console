package io.github.dbconsole.script;

import io.github.dbconsole.auth.SessionService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;

/**
 * 脚本运行暂存（F-13）：预校验通过时按「登录会话 + 脚本运行标识」暂存文件名与全文，
 * 汇总历史端点据标识取回，浏览器不再二次上传整份脚本；条目在记录后即消费删除，
 * 同一标识不可重复入史。仅存内存、随会话存亡，是分析响应的附属状态——不做运行
 * 完整性裁决（F-13 的完整性契约仍由「完整执行结束才调用汇总端点」承担，审计逐语句不受影响）。
 */
public class ScriptRunStore {

  private final Map<String, Map<String, StoredScript>> runsBySession = new ConcurrentHashMap<>();

  /** 暂存的脚本：数据源、文件名与完整内容。 */
  public record StoredScript(String sourceId, String filename, String content) {}

  /** 暂存一份通过预校验的脚本，返回本轮运行的唯一标识。 */
  public String store(String sessionToken, String sourceId, String filename, String content) {
    String scriptId = UUID.randomUUID().toString();
    runsBySession
        .computeIfAbsent(sessionToken, key -> new ConcurrentHashMap<>())
        .put(scriptId, new StoredScript(sourceId, filename, content));
    return scriptId;
  }

  /** 取出并删除该运行标识对应的脚本；不存在（未预校验、已消费或会话已结束）返回 null。 */
  public StoredScript consume(String sessionToken, String scriptId) {
    Map<String, StoredScript> runs = runsBySession.get(sessionToken);
    if (runs == null) {
      return null;
    }
    StoredScript script = runs.remove(scriptId);
    if (script != null && runs.isEmpty()) {
      runsBySession.remove(sessionToken, runs);
    }
    return script;
  }

  /** 会话结束（登出/失效）：清除该会话的全部暂存。 */
  @EventListener(SessionService.SessionEnded.class)
  public void onSessionEnd(SessionService.SessionEnded event) {
    runsBySession.remove(event.token());
  }
}
