package io.github.dbconsole.metadata;

import java.util.List;

/** 表结构（wire）：列、索引与外键（F-03）。 */
public record TableStructure(
    String name,
    String comment,
    List<ColumnDefinition> columns,
    List<IndexDefinition> indexes,
    List<ForeignKeyDefinition> foreignKeys) {

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
