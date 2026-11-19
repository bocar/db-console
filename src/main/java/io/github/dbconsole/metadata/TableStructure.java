package io.github.dbconsole.metadata;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 表结构（wire）：列、索引与外键（F-03）。列身份语义（主键组、大小写不敏感列查找）
 * 由本结构自己回答——DM8/Oracle 系存储为大写，消费方不得各自推导（F-17 深化）。
 */
public record TableStructure(
    String name,
    String comment,
    List<ColumnDefinition> columns,
    List<IndexDefinition> indexes,
    List<ForeignKeyDefinition> foreignKeys) {

  /** 主键列名组（按列定义序）。 */
  public List<String> primaryKeyNames() {
    return columns.stream()
        .filter(ColumnDefinition::primaryKey)
        .map(ColumnDefinition::name)
        .toList();
  }

  /**
   * 小写列名 → 列定义索引（大小写同名时首列优先）。列查找统一走本索引：
   * 批量场景（结果列映射、编辑计划逐变更校验）一次建索引复用，避免逐列全扫 +
   * 重复 toLowerCase（O(N×M)，F-17 挂账回补；原 findColumn 已无调用位，随批移除）。
   */
  public Map<String, ColumnDefinition> lowerCaseColumnIndex() {
    Map<String, ColumnDefinition> index = new HashMap<>();
    for (ColumnDefinition column : columns) {
      index.putIfAbsent(column.name().toLowerCase(Locale.ROOT), column);
    }
    return Collections.unmodifiableMap(index);
  }

  public record ColumnDefinition(
      String name,
      String type,
      boolean nullable,
      String defaultValue,
      boolean primaryKey,
      String comment,
      boolean autoIncrement) {

    /** 对象树等不需要自增标志的既有构造位（F-17 之前的行为）。 */
    public ColumnDefinition(
        String name, String type, boolean nullable, String defaultValue,
        boolean primaryKey, String comment) {
      this(name, type, nullable, defaultValue, primaryKey, comment, false);
    }
  }

  public record IndexDefinition(String name, boolean unique, List<String> columns) {}

  public record ForeignKeyDefinition(
      String name,
      List<String> columns,
      String referencedTable,
      List<String> referencedColumns) {}
}
