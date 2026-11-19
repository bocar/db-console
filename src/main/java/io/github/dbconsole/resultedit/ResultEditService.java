package io.github.dbconsole.resultedit;

import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.dialect.Dialect;
import io.github.dbconsole.dialect.ResultEditSql;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.metadata.TableStructure;
import io.github.dbconsole.metadata.TableStructureService;
import io.github.dbconsole.sql.ExecutionForm;
import io.github.dbconsole.sql.ExecutionOrchestrator;
import io.github.dbconsole.sql.ResultEditExecutor;
import io.github.dbconsole.sql.SqlRejectException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.charset.StandardCharsets;

/**
 * 结果编辑保存策略（F-17 / ADR-0006）：暂存更改 → 服务端重校验（数量上限、列与主键
 * 对照最新表结构）→ 按方言生成参数化语句（删除 → 更新 → 新增），随后构造批次执行形态
 * 交 {@link ExecutionOrchestrator} 编舞（互斥守卫、汇总确认门、登记、审计、终态分类
 * 已内化于编舞）。无状态：两次调用（确认前/后）由同一请求确定性重建语句，
 * 凭据校验即等价校验重建结果。
 */
public class ResultEditService {

  private final DataSourceRegistry registry;
  private final TableStructureService tableStructures;
  private final DbConsoleProperties properties;
  private final ExecutionOrchestrator orchestrator;

  public ResultEditService(
      DataSourceRegistry registry,
      TableStructureService tableStructures,
      DbConsoleProperties properties,
      ExecutionOrchestrator orchestrator) {
    this.registry = registry;
    this.tableStructures = tableStructures;
    this.properties = properties;
    this.orchestrator = orchestrator;
  }

  public ResultEditResponses save(
      String sessionToken, String username, ResultEditRequest request) {
    ResolvedDataSource source = registry.resolve(request.sourceId());
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
            .resolveColumnStructure(source.id(), source.dialect(), request.tableParts())
            .orElseThrow(
                () ->
                    new SqlRejectException(
                        "TABLE_UNREADABLE", "来源表不可读或不存在，无法保存，请刷新后重试"));
    List<String> primaryKeys = resolved.structure().primaryKeyNames();
    if (primaryKeys.isEmpty()) {
      throw new SqlRejectException("NO_PRIMARY_KEY", "来源表没有主键，无法定位行，请刷新后重试");
    }
    // 语句标识符必须用元数据命中的大小写形态（引号标识符在 DM8/Oracle 系大小写敏感）
    List<ResultEditExecutor.PlannedStatement> planned =
        plan(source, resolved.structure(), primaryKeys, request, resolved.resolvedParts());
    // 编舞（互斥守卫、汇总确认门、登记、审计、终态分类）收口在 ExecutionOrchestrator
    return orchestrator.run(
        new ExecutionForm.EditBatchForm(
            source, sessionToken, username, planned, request.confirmationTokens()));
  }

  /** 重校验并生成语句（确定性：同一请求两次调用产出完全一致的语句与绑定文本）。 */
  private List<ResultEditExecutor.PlannedStatement> plan(
      ResolvedDataSource source,
      TableStructure structure,
      List<String> primaryKeys,
      ResultEditRequest request,
      List<String> statementTableParts) {
    Dialect dialect = source.dialect();
    List<ResultEditExecutor.PlannedStatement> planned = new ArrayList<>();
    for (int index = 0; index < request.deletes().size(); index++) {
      ResultEditRequest.DeleteChange change = request.deletes().get(index);
      KeyAndValues key = validateKey(structure, primaryKeys, change.key(), "删除", index);
      validateValueSizes(change.key(), "删除", index);
      planned.add(
          new ResultEditExecutor.PlannedStatement(
              index,
              ResultEditSql.delete(
                  dialect, statementTableParts, key.columns(), key.values())));
    }
    for (int index = 0; index < request.updates().size(); index++) {
      ResultEditRequest.UpdateChange change = request.updates().get(index);
      KeyAndValues key = validateKey(structure, primaryKeys, change.key(), "更新", index);
      if (change.set().isEmpty()) {
        throw rejection("更新", index, "没有变更列");
      }
      List<String> setColumns = new ArrayList<>();
      List<Object> setValues = new ArrayList<>();
      for (Map.Entry<String, Object> entry : change.set().entrySet()) {
        TableStructure.ColumnDefinition column =
            requireColumn(structure, entry.getKey(), "更新", index);
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
                  dialect,
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
            requireColumn(structure, entry.getKey(), "新增", index);
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
                  dialect, statementTableParts, insertColumns, insertValues)));
    }
    return planned;
  }

  private record KeyAndValues(List<String> columns, List<Object> values) {}

  /** key 必须恰好覆盖全部主键列（大小写不敏感），值非空。 */
  private KeyAndValues validateKey(
      TableStructure structure,
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
      TableStructure structure, String name, String section, int index) {
    return structure
        .findColumn(name)
        .orElseThrow(() -> rejection(section, index, "列不存在：" + name));
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


}
