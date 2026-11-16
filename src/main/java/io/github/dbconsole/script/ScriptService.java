package io.github.dbconsole.script;

import io.github.dbconsole.history.HistoryService;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.sql.SqlRejectException;

/**
 * 脚本运行服务（F-13）：汇总历史的落库收口。调用契约是「仅完整执行结束的运行」
 * （F-13，前端在全部语句成功后调用）；文件名与全文取自预校验暂存（consume 即消费，
 * 同一运行不可重复入史），历史是会话级内存 UI 设施，运行完整性由调用契约保证，
 * 审计侧逐语句落盘不受此影响。Controller 只转交请求，不编排。
 */
public class ScriptService {

  public static final String CODE_SCRIPT_RUN_NOT_FOUND = "SCRIPT_RUN_NOT_FOUND";

  private final DataSourceRegistry registry;
  private final ScriptRunStore runStore;
  private final HistoryService history;

  public ScriptService(DataSourceRegistry registry, ScriptRunStore runStore, HistoryService history) {
    this.registry = registry;
    this.runStore = runStore;
    this.history = history;
  }

  /** 记录一次完整脚本运行的汇总历史（文件名 + 完整脚本 + 汇总状态，取自暂存）。 */
  public void recordRun(String sessionToken, ScriptHistoryRequest request) {
    registry.resolve(request.sourceId());
    ScriptRunStore.StoredScript stored = runStore.consume(sessionToken, request.scriptId());
    if (stored == null || !stored.sourceId().equals(request.sourceId())) {
      throw new SqlRejectException(
          CODE_SCRIPT_RUN_NOT_FOUND, "脚本运行不存在或已失效，请重新上传后再执行");
    }
    history.recordScriptRun(
        sessionToken,
        request.sourceId(),
        stored.filename(),
        stored.content(),
        request.durationMs());
  }
}
