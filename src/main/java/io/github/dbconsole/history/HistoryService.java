package io.github.dbconsole.history;

import io.github.dbconsole.auth.SessionService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 会话内 SQL 历史（F-12）：按登录会话+数据源隔离，仅存内存，退出/失效即清除。
 * 上限每数据源 100 条，超出淘汰最旧。
 */
@Service
public class HistoryService {

  private static final int MAX_ENTRIES_PER_SOURCE = 100;

  private final Map<String, Map<String, Deque<HistoryEntry>>> historyBySession = new ConcurrentHashMap<>();
  private final Clock clock;
  private final AtomicLong idGenerator = new AtomicLong();

  public HistoryService(Clock clock) {
    this.clock = clock;
  }

  /** 条目类别（wire）：SQL 为普通单语句执行，SCRIPT 为脚本运行汇总。 */
  public static final String KIND_SQL = "SQL";
  public static final String KIND_SCRIPT = "SCRIPT";

  /** 记录一条已执行的 SQL（失败路径在记录前已抛出，故仅存在成功终态）。 */
  public void record(String sessionToken, String sourceId, String sql, long durationMs,
      boolean uncommitted) {
    recordEntry(sessionToken, sourceId, sql, durationMs, uncommitted, null, KIND_SQL);
  }

  /**
   * 记录一次脚本运行的汇总历史（F-13）：完整执行结束的运行由专用端点调用，文件名单独标识。
   * 全文入史是 F-13 明文要求；条目随会话存亡，最坏驻留为每数据源 100×maxFileBytes（约 500MB），
   * 会话结束即整体释放——不引入按字节裁剪，避免削弱「找回完整脚本」的历史职责。
   */
  public void recordScriptRun(
      String sessionToken, String sourceId, String filename, String script, long durationMs) {
    recordEntry(sessionToken, sourceId, script, durationMs, false, filename, KIND_SCRIPT);
  }

  private void recordEntry(
      String sessionToken, String sourceId, String sql, long durationMs,
      boolean uncommitted, String filename, String kind) {
    historyBySession
        .computeIfAbsent(sessionToken, k -> new ConcurrentHashMap<>())
        .computeIfAbsent(sourceId, k -> new LinkedList<>())
        .addFirst(new HistoryEntry(idGenerator.incrementAndGet(), sourceId, sql,
            clock.instant().toString(), durationMs, "SUCCEEDED", uncommitted, filename, kind));
    trim(sessionToken, sourceId);
  }

  /** 查询指定数据源的近期历史。 */
  public List<HistoryEntry> list(String sessionToken, String sourceId) {
    Deque<HistoryEntry> deque = dequeOf(sessionToken, sourceId);
    if (deque == null) return List.of();
    synchronized (deque) {
      return new ArrayList<>(deque);
    }
  }

  /** 清空指定数据源的历史。 */
  public void clear(String sessionToken, String sourceId) {
    Map<String, Deque<HistoryEntry>> perSource = historyBySession.get(sessionToken);
    if (perSource != null) perSource.remove(sourceId);
  }

  /** 会话结束（登出/失效）：清除该会话全部历史。 */
  @EventListener(SessionService.SessionEnded.class)
  public void onSessionEnd(SessionService.SessionEnded event) {
    historyBySession.remove(event.token());
  }

  private Deque<HistoryEntry> dequeOf(String sessionToken, String sourceId) {
    Map<String, Deque<HistoryEntry>> perSource = historyBySession.get(sessionToken);
    return perSource == null ? null : perSource.get(sourceId);
  }

  private void trim(String sessionToken, String sourceId) {
    Deque<HistoryEntry> deque = dequeOf(sessionToken, sourceId);
    if (deque == null) return;
    while (deque.size() > MAX_ENTRIES_PER_SOURCE) {
      deque.removeLast();
    }
  }

  /** 历史条目（wire）：kind 区分普通语句与脚本汇总；filename 仅脚本汇总条目携带。 */
  public record HistoryEntry(
      long id, String sourceId, String sql, String executedAt,
      long durationMs, String status, boolean uncommitted, String filename, String kind) {}
}
