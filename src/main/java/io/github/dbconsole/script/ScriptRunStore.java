package io.github.dbconsole.script;

import io.github.dbconsole.auth.SessionService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;

/**
 * 脚本运行暂存（F-13）：预校验通过时按登录会话暂存文件名与全文，汇总历史端点据
 * 运行标识取回，浏览器不再二次上传整份脚本。同一会话只保留最近一次预校验的脚本
 * （重新上传即取代，暂存随之有界；前端同一时刻也只持有一个有效运行标识），
 * 条目在记录后即消费删除，同一标识不可重复入史。仅存内存、随会话存亡，
 * 是分析响应的附属状态——不做运行完整性裁决（F-13 的完整性契约仍由
 * 「完整执行结束才调用汇总端点」承担，审计逐语句不受影响）。
 */
public class ScriptRunStore {

  private final Map<String, StoredScript> runBySession = new ConcurrentHashMap<>();

  /** 暂存的脚本：运行标识、数据源、文件名与完整内容。 */
  public record StoredScript(String scriptId, String sourceId, String filename, String content) {}

  /** 暂存一份通过预校验的脚本（取代该会话此前的暂存），返回本轮运行的唯一标识。 */
  public String store(String sessionToken, String sourceId, String filename, String content) {
    String scriptId = UUID.randomUUID().toString();
    runBySession.put(sessionToken, new StoredScript(scriptId, sourceId, filename, content));
    return scriptId;
  }

  /** 取出并删除该会话当前运行标识对应的脚本；标识不匹配（未预校验、已取代、已消费或会话已结束）返回 null。 */
  public StoredScript consume(String sessionToken, String scriptId) {
    StoredScript stored = runBySession.get(sessionToken);
    if (stored == null || !stored.scriptId().equals(scriptId)) {
      return null;
    }
    runBySession.remove(sessionToken);
    return stored;
  }

  /** 会话结束（登出/失效）：清除该会话的全部暂存。 */
  @EventListener(SessionService.SessionEnded.class)
  public void onSessionEnd(SessionService.SessionEnded event) {
    runBySession.remove(event.token());
  }
}
