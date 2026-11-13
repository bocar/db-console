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
      String comment) {}

  public record IndexDefinition(String name, boolean unique, List<String> columns) {}

  public record ForeignKeyDefinition(
      String name,
      List<String> columns,
      String referencedTable,
      List<String> referencedColumns) {}
}
