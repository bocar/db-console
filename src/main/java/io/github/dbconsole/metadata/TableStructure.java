package io.github.dbconsole.metadata;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

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

  /** 大小写不敏感列查找（存储形态各异：DM8 大写、MySQL 原样）。 */
  public Optional<ColumnDefinition> findColumn(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    return columns.stream()
        .filter(column -> column.name().toLowerCase(Locale.ROOT).equals(lower))
        .findFirst();
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
