package io.github.dbconsole.resultedit;

import io.github.dbconsole.audit.AuditService;
import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.dialect.ResultEditSql;
import io.github.dbconsole.dialect.SqlStatement;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.metadata.TableStructure;
import io.github.dbconsole.metadata.TableStructureService;
import io.github.dbconsole.sql.ConfirmationDecision;
import io.github.dbconsole.sql.ConfirmationService;
import io.github.dbconsole.sql.RunningQuery;
import io.github.dbconsole.sql.RunningQueryRegistry;
import io.github.dbconsole.sql.SqlRejectException;
import io.github.dbconsole.transaction.TransactionManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.charset.StandardCharsets;

/**
 * 结果编辑保存编排（F-17 / ADR-0006）：暂存更改 → 服务端重校验（数量上限、列与主键
 * 对照最新表结构）→ 按方言生成参数化语句（删除 → 更新 → 新增）→ 逐语句确认判定与
 * 凭据签发/校验（绑定文本 = 语句 + 参数渲染，值变即失效）→ 批次短事务执行 → 审计。
 * 无状态：两次调用（确认前/后）由同一请求确定性重建语句，凭据校验即等价校验重建结果。
 */
public class ResultEditService {

  private static final String MUTEX_MESSAGE =
      "结果编辑保存使用独立短事务，请先提交或回滚当前事务";

  private final DataSourceRegistry registry;
  private final ConfirmationService confirmations;
  private final TransactionManager transactions;
  private final TableStructureService tableStructures;
  private final RunningQueryRegistry runningQueries;
  private final AuditService audit;
  private final DbConsoleProperties properties;
  private final ResultEditExecutor executor;

  public ResultEditService(
      DataSourceRegistry registry,
      ConfirmationService confirmations,
      TransactionManager transactions,
      TableStructureService tableStructures,
      RunningQueryRegistry runningQueries,
      AuditService audit,
      DbConsoleProperties properties,
      ResultEditExecutor executor) {
    this.registry = registry;
    this.confirmations = confirmations;
    this.transactions = transactions;
    this.tableStructures = tableStructures;
    this.runningQueries = runningQueries;
    this.audit = audit;
    this.properties = properties;
    this.executor = executor;
  }

  public ResultEditResponses save(
      String sessionToken, String username, ResultEditRequest request) {
    ResolvedDataSource source = registry.resolve(request.sourceId());
    // 与手动事务互斥（F-16/F-17）：保存走独立连接上的单一短事务
    transactions.requireNoOpenTransaction(sessionToken, source.id(), MUTEX_MESSAGE);
    int maxChanges = properties.resultEdit().maxChangesPerSave();
    if (request.changeCount() > maxChanges) {
      throw new SqlRejectException(
          "TOO_MANY_CHANGES", "单次保存的变更数量超过上限 " + maxChanges + "，请分批保存");
    }
    if (request.changeCount() == 0) {
      throw new SqlRejectException("NO_CHANGES", "没有可保存的变更");
    }
    TableStructureService.ResolvedColumnStructure resolved =
        tableStructures
            .resolveColumnStructure(
                source.id(), source.databaseType(), source.compatibilityMode(),
                request.tableParts())
            .orElseThrow(
                () ->
                    new SqlRejectException(
                        "TABLE_UNREADABLE", "来源表不可读或不存在，无法保存，请刷新后重试"));
    List<String> primaryKeys = primaryKeyNames(resolved.structure());
    if (primaryKeys.isEmpty()) {
      throw new SqlRejectException("NO_PRIMARY_KEY", "来源表没有主键，无法定位行，请刷新后重试");
    }
    // 语句标识符必须用元数据命中的大小写形态（引号标识符在 DM8/Oracle 系大小写敏感）
    List<ResultEditExecutor.PlannedStatement> planned =
        plan(source, resolved.structure(), primaryKeys, request, resolved.resolvedParts());
    ResultEditResponses confirmation = confirmationPhase(source, sessionToken, planned, request);
    if (confirmation != null) {
      return confirmation;
    }
    // 执行阶段（与单语句路径同惯例：预校验拒绝与确认要求不落审计，真正执行才审计）
    RunningQuery running = runningQueries.register(sessionToken, source.id());
    long startedAt = System.nanoTime();
    try {
      ResultEditResponses response;
      try {
        response = executor.execute(source, planned, running, timeoutSeconds());
      } catch (RuntimeException exception) {
        audit.recordExecution(
            username, source.id(), auditSummary(request),
            elapsedMillisSince(startedAt), "FAILED");
        throw exception;
      }
      audit.recordExecution(
          username, source.id(), auditSummary(request),
          elapsedMillisSince(startedAt), response.auditStatus());
      return response;
    } finally {
      runningQueries.complete(running);
    }
  }

  /** 重校验并生成语句（确定性：同一请求两次调用产出完全一致的语句与绑定文本）。 */
  private List<ResultEditExecutor.PlannedStatement> plan(
      ResolvedDataSource source,
      TableStructure structure,
      List<String> primaryKeys,
      ResultEditRequest request,
      List<String> statementTableParts) {
    Map<String, TableStructure.ColumnDefinition> tableColumns = columnsByLowerName(structure);
    List<ResultEditExecutor.PlannedStatement> planned = new ArrayList<>();
    for (int index = 0; index < request.deletes().size(); index++) {
      ResultEditRequest.DeleteChange change = request.deletes().get(index);
      KeyAndValues key = validateKey(tableColumns, primaryKeys, change.key(), "删除", index);
      validateValueSizes(change.key(), "删除", index);
      planned.add(
          new ResultEditExecutor.PlannedStatement(
              index,
              ResultEditSql.delete(
                  source.databaseType(), source.compatibilityMode(),
                  statementTableParts, key.columns(), key.values())));
    }
    for (int index = 0; index < request.updates().size(); index++) {
      ResultEditRequest.UpdateChange change = request.updates().get(index);
      KeyAndValues key = validateKey(tableColumns, primaryKeys, change.key(), "更新", index);
      if (change.set().isEmpty()) {
        throw rejection("更新", index, "没有变更列");
      }
      List<String> setColumns = new ArrayList<>();
      List<Object> setValues = new ArrayList<>();
      for (Map.Entry<String, Object> entry : change.set().entrySet()) {
        TableStructure.ColumnDefinition column =
            requireColumn(tableColumns, entry.getKey(), "更新", index);
        if (column.primaryKey()) {
          throw rejection("更新", index, "主键列不可修改：" + column.name());
        }
        if (entry.getValue() == null && !column.nullable()) {
          throw rejection("更新", index, "NOT NULL 列不能置空：" + column.name());
        }
        setColumns.add(column.name());
        setValues.add(entry.getValue());
      }
      validateValueSizes(change.set(), "更新", index);
      validateValueSizes(change.key(), "更新", index);
      planned.add(
          new ResultEditExecutor.PlannedStatement(
              index,
              ResultEditSql.update(
                  source.databaseType(), source.compatibilityMode(),
                  statementTableParts, key.columns(), key.values(), setColumns, setValues)));
    }
    for (int index = 0; index < request.inserts().size(); index++) {
      ResultEditRequest.InsertChange change = request.inserts().get(index);
      if (change.values().isEmpty()) {
        throw rejection("新增", index, "没有任何值，空行不会生成语句");
      }
      List<String> insertColumns = new ArrayList<>();
      List<Object> insertValues = new ArrayList<>();
      for (Map.Entry<String, Object> entry : change.values().entrySet()) {
        TableStructure.ColumnDefinition column =
            requireColumn(tableColumns, entry.getKey(), "新增", index);
        if (entry.getValue() == null && !column.nullable()) {
          throw rejection("新增", index, "NOT NULL 列不能为空：" + column.name());
        }
        insertColumns.add(column.name());
        insertValues.add(entry.getValue());
      }
      validateValueSizes(change.values(), "新增", index);
      planned.add(
          new ResultEditExecutor.PlannedStatement(
              index,
              ResultEditSql.insert(
                  source.databaseType(), source.compatibilityMode(),
                  statementTableParts, insertColumns, insertValues)));
    }
    return planned;
  }

  /**
   * 确认阶段（F-09 + F-17 裁决 2026-11-18）：每次保存都先经一次汇总确认——逐条签发
   * 「会话 + 数据源 + 语句 + 参数渲染」绑定凭据，任一凭据未校验通过即返回完整预览并
   * 停止执行；全部通过才进入批次执行。风险标注（reason）按 F-09 逐条判定（生产写等），
   * 无风险的语句不标注但仍参与绑定与校验。
   */
  private ResultEditResponses confirmationPhase(
      ResolvedDataSource source,
      String sessionToken,
      List<ResultEditExecutor.PlannedStatement> planned,
      ResultEditRequest request) {
    List<ResultEditResponses.StatementPreview> previews = new ArrayList<>(planned.size());
    boolean anyPending = false;
    for (int index = 0; index < planned.size(); index++) {
      ResultEditExecutor.PlannedStatement statement = planned.get(index);
      ResultEditSql.Generated generated = statement.generated();
      ConfirmationDecision.Requirement requirement =
          ConfirmationDecision.evaluate(
              source.environment(), new SqlStatement(generated.kind(), true));
      String reason =
          requirement == ConfirmationDecision.Requirement.NOT_REQUIRED
              ? null
              : requirement.name();
      String token = null;
      String bound = ResultEditSql.boundText(generated.sql(), generated.params());
      String presented = request.tokenAt(index);
      if (presented == null
          || !confirmations.verify(sessionToken, source.id(), bound, presented)) {
        token = confirmations.issue(sessionToken, source.id(), bound);
        anyPending = true;
      }
      previews.add(
          new ResultEditResponses.StatementPreview(
              index, statement.section(), statement.changeIndex(),
              generated.sql(), generated.params(), reason, token));
    }
    return anyPending ? new ResultEditResponses.ConfirmationRequired(previews) : null;
  }

  private record KeyAndValues(List<String> columns, List<Object> values) {}

  /** key 必须恰好覆盖全部主键列（大小写不敏感），值非空。 */
  private KeyAndValues validateKey(
      Map<String, TableStructure.ColumnDefinition> columns,
      List<String> primaryKeys,
      Map<String, Object> key,
      String section,
      int index) {
    if (key.size() != primaryKeys.size()) {
      throw rejection(section, index,
          "定位行必须恰好提供全部主键列（" + String.join(", ", primaryKeys) + "）");
    }
    List<Object> values = new ArrayList<>(primaryKeys.size());
    for (String primaryKey : primaryKeys) {
      Object value = valueForKey(key, primaryKey);
      if (value == null) {
        throw rejection(section, index, "主键列不能为空：" + primaryKey);
      }
      values.add(value);
    }
    return new KeyAndValues(primaryKeys, values);
  }

  private Object valueForKey(Map<String, Object> key, String primaryKey) {
    for (Map.Entry<String, Object> entry : key.entrySet()) {
      if (entry.getKey().toLowerCase(Locale.ROOT)
          .equals(primaryKey.toLowerCase(Locale.ROOT))) {
        return entry.getValue();
      }
    }
    return null;
  }

  private TableStructure.ColumnDefinition requireColumn(
      Map<String, TableStructure.ColumnDefinition> columns,
      String name,
      String section,
      int index) {
    TableStructure.ColumnDefinition column = columns.get(name.toLowerCase(Locale.ROOT));
    if (column == null) {
      throw rejection(section, index, "列不存在：" + name);
    }
    return column;
  }

  /** 字符串值按 UTF-8 字节数对齐单元格上限（F-08 maxCellBytes）；超限前置拒绝。 */
  private void validateValueSizes(Map<String, Object> values, String section, int index) {
    long maxCellBytes = properties.execution().maxCellBytes();
    for (Map.Entry<String, Object> entry : values.entrySet()) {
      if (entry.getValue() instanceof String text
          && text.getBytes(StandardCharsets.UTF_8).length > maxCellBytes) {
        throw rejection(section, index, "单元格内容超过大小上限：" + entry.getKey());
      }
    }
  }

  private static SqlRejectException rejection(String section, int index, String message) {
    return new SqlRejectException(
        "INVALID_CHANGE", "第 " + (index + 1) + " 条" + section + "变更无效：" + message);
  }

  private static List<String> primaryKeyNames(TableStructure structure) {
    return structure.columns().stream()
        .filter(TableStructure.ColumnDefinition::primaryKey)
        .map(TableStructure.ColumnDefinition::name)
        .toList();
  }

  private static Map<String, TableStructure.ColumnDefinition> columnsByLowerName(
      TableStructure structure) {
    Map<String, TableStructure.ColumnDefinition> byLowerName = new HashMap<>();
    for (TableStructure.ColumnDefinition column : structure.columns()) {
      byLowerName.putIfAbsent(column.name().toLowerCase(Locale.ROOT), column);
    }
    return byLowerName;
  }

  /** 审计摘要（F-10 不含完整 SQL 与数据）：段计数指纹即可定位一次保存的规模。 */
  private static String auditSummary(ResultEditRequest request) {
    return "结果编辑保存 DELETE " + request.deletes().size()
        + " / UPDATE " + request.updates().size()
        + " / INSERT " + request.inserts().size();
  }

  private int timeoutSeconds() {
    return Math.max(1, (int) properties.execution().timeout().toSeconds());
  }

  private static long elapsedMillisSince(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }
}
