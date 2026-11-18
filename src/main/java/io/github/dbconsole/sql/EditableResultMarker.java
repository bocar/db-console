package io.github.dbconsole.sql;

import io.github.dbconsole.dialect.SingleTableSelectAnalyzer;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.metadata.TableStructure;
import io.github.dbconsole.metadata.TableStructureService;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 可编辑结果标记（F-17 / GLOSSARY：可编辑结果）：单表资格检测（{@link SingleTableSelectAnalyzer}）
 * → 轻量表结构联查（列 + 主键）→ 结果列到表列的大小写不敏感映射。任何失配——形状不合格、
 * 结构不可见、无主键、主键不在结果列、重名主键列——都降级为 editable=null（整体只读），
 * 查询结果本身不受影响；结构读取异常同样降级（fail-closed，记录 warn）。
 */
class EditableResultMarker {

  private static final Logger log = LoggerFactory.getLogger(EditableResultMarker.class);

  private final TableStructureService tableStructures;

  EditableResultMarker(TableStructureService tableStructures) {
    this.tableStructures = tableStructures;
  }

  /**
   * 尝试把查询结果标记为可编辑；不可编辑时原样返回。结构读取搭用执行查询的既有连接
   * （不为标记新开连接，F-11 翻页连接纪律不受影响）。
   */
  SqlResponses.QueryResult mark(
      Connection connection,
      ResolvedDataSource source,
      String sql,
      SqlResponses.QueryResult result) {
    SingleTableSelectAnalyzer analyzer =
        SingleTableSelectAnalyzer.forDialect(source.databaseType(), source.compatibilityMode());
    List<String> tableParts = analyzer.detect(sql).orElse(null);
    if (tableParts == null) {
      return result;
    }
    TableStructure structure;
    try {
      structure =
          tableStructures
              .columnStructure(
                  connection, source.databaseType(), source.compatibilityMode(), tableParts)
              .orElse(null);
    } catch (RuntimeException exception) {
      // 标记绝不破坏查询展示（fail-closed 降级），原因留痕
      log.warn("可编辑标记降级 sourceId={} table={} 原因={}",
          source.id(), tableParts, exception.getMessage());
      return result;
    }
    if (structure == null) {
      return result;
    }
    List<String> primaryKeys =
        structure.columns().stream()
            .filter(TableStructure.ColumnDefinition::primaryKey)
            .map(TableStructure.ColumnDefinition::name)
            .toList();
    if (primaryKeys.isEmpty()) {
      return result;
    }
    SqlResponses.EditableInfo editable = mapColumns(structure, primaryKeys, result, tableParts);
    return editable == null ? result : result.withEditable(editable);
  }

  /**
   * 结果列 → 表列映射：重名结果列全部只读；主键列必须恰好各出现一次且可映射，
   * 否则整体只读。返回按结果列位置的表列定义（null = 只读列）。
   */
  private SqlResponses.EditableInfo mapColumns(
      TableStructure structure,
      List<String> primaryKeys,
      SqlResponses.QueryResult result,
      List<String> tableParts) {
    Map<String, Long> resultNameCounts = new HashMap<>();
    for (SqlResponses.QueryColumn column : result.columns()) {
      resultNameCounts.merge(column.name().toLowerCase(Locale.ROOT), 1L, Long::sum);
    }
    Map<String, TableStructure.ColumnDefinition> tableByLowerName = new HashMap<>();
    for (TableStructure.ColumnDefinition column : structure.columns()) {
      tableByLowerName.putIfAbsent(column.name().toLowerCase(Locale.ROOT), column);
    }
    List<SqlResponses.EditableColumn> mapped = new ArrayList<>(result.columns().size());
    for (SqlResponses.QueryColumn resultColumn : result.columns()) {
      String lowerName = resultColumn.name().toLowerCase(Locale.ROOT);
      boolean duplicated = resultNameCounts.get(lowerName) > 1;
      TableStructure.ColumnDefinition tableColumn = tableByLowerName.get(lowerName);
      mapped.add(duplicated || tableColumn == null ? null : toEditableColumn(tableColumn));
    }
    boolean primaryKeyCovered =
        primaryKeys.stream()
            .allMatch(
                key ->
                    resultNameCounts.getOrDefault(key.toLowerCase(Locale.ROOT), 0L) == 1L
                        && tableByLowerName.containsKey(key.toLowerCase(Locale.ROOT)));
    if (!primaryKeyCovered) {
      return null;
    }
    // List.copyOf 不接受 null 元素（只读列位为 null），改用不可变包装
    return new SqlResponses.EditableInfo(
        List.copyOf(tableParts), List.copyOf(primaryKeys),
        Collections.unmodifiableList(new ArrayList<>(mapped)));
  }

  private static SqlResponses.EditableColumn toEditableColumn(
      TableStructure.ColumnDefinition column) {
    return new SqlResponses.EditableColumn(
        column.name(),
        column.type(),
        column.nullable(),
        column.defaultValue(),
        column.autoIncrement(),
        column.primaryKey());
  }
}
