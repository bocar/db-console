package io.github.dbconsole.metadata;

import io.github.dbconsole.jdbc.ConnectionFactory;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 表结构提取（F-03/F-06）：优先 JDBC DatabaseMetaData，含列（注释/类型/可空/默认/主键）、
 * 索引（名称/唯一/列序）与外键（约束名/列对应）。DESC 与对象树共用此查询。
 */
@Service
public class TableStructureService {

  private final ConnectionFactory connections;

  public TableStructureService(ConnectionFactory connections) {
    this.connections = connections;
  }

  /** 列出当前库/模式下全部表的简要结构（列级）。 */
  public List<TableStructure> listTables(ResolvedDataSource source) {
    try (Connection connection = connections.open(source)) {
      DatabaseMetaData meta = connection.getMetaData();
      String catalog = connection.getCatalog();
      String schema = connection.getSchema();
      List<TableStructure> tables = new ArrayList<>();
      try (ResultSet rs = meta.getTables(catalog, schema, "%", new String[] {"TABLE"})) {
        while (rs.next()) {
          String tableName = rs.getString("TABLE_NAME");
          tables.add(structure(meta, catalog, schema, tableName));
        }
      }
      return tables;
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "对象结构读取失败：" + exception.getMessage(), exception);
    }
  }

  /** 单表完整结构：列 + 索引 + 外键；表不存在时返回 null。 */
  public TableStructure tableStructure(ResolvedDataSource source, String tableName) {
    try (Connection connection = connections.open(source)) {
      DatabaseMetaData meta = connection.getMetaData();
      String catalog = connection.getCatalog();
      String schema = connection.getSchema();
      // 限定名按方言拆分：db.table 或 schema.table
      String[] parts = tableName.split("\\.");
      if (parts.length == 2) {
        catalog = parts[0];
        tableName = parts[1];
      }
      if (tableExists(meta, catalog, schema, tableName)) {
        return structure(meta, catalog, schema, tableName);
      }
      return null;
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "表结构读取失败：" + exception.getMessage(), exception);
    }
  }

  private boolean tableExists(
      DatabaseMetaData meta, String catalog, String schema, String tableName)
      throws SQLException {
    try (ResultSet rs = meta.getTables(catalog, schema, tableName, new String[] {"TABLE"})) {
      return rs.next();
    }
  }

  private TableStructure structure(
      DatabaseMetaData meta, String catalog, String schema, String tableName)
      throws SQLException {
    // 列（含主键标记）
    Map<String, TableStructure.ColumnDefinition> columns = new LinkedHashMap<>();
    try (ResultSet rs = meta.getColumns(catalog, schema, tableName, "%")) {
      while (rs.next()) {
        String columnName = rs.getString("COLUMN_NAME");
        String defaultValue = rs.getString("COLUMN_DEF");
        String comment = rs.getString("REMARKS");
        columns.put(
            columnName,
            new TableStructure.ColumnDefinition(
                columnName,
                rs.getString("TYPE_NAME"),
                rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                defaultValue,
                false,
                comment == null ? "" : comment));
      }
    }
    // 主键标记
    try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, tableName)) {
      while (rs.next()) {
        String columnName = rs.getString("COLUMN_NAME");
        TableStructure.ColumnDefinition existing = columns.get(columnName);
        if (existing != null) {
          columns.put(
              columnName,
              new TableStructure.ColumnDefinition(
                  existing.name(),
                  existing.type(),
                  existing.nullable(),
                  existing.defaultValue(),
                  true,
                  existing.comment()));
        }
      }
    }
    // 索引（名称/唯一/列序）
    List<TableStructure.IndexDefinition> indexes = new ArrayList<>();
    Map<String, List<String>> indexColumns = new LinkedHashMap<>();
    Map<String, Boolean> indexUnique = new LinkedHashMap<>();
    try (ResultSet rs = meta.getIndexInfo(catalog, schema, tableName, false, false)) {
      while (rs.next()) {
        String indexName = rs.getString("INDEX_NAME");
        if (indexName == null) {
          continue;
        }
        String columnName = rs.getString("COLUMN_NAME");
        indexColumns
            .computeIfAbsent(indexName, k -> new ArrayList<>())
            .add(columnName == null ? "" : columnName);
        indexUnique.putIfAbsent(indexName, !rs.getBoolean("NON_UNIQUE"));
      }
    }
    for (Map.Entry<String, List<String>> entry : indexColumns.entrySet()) {
      indexes.add(
          new TableStructure.IndexDefinition(
              entry.getKey(), indexUnique.getOrDefault(entry.getKey(), false), entry.getValue()));
    }
    // 外键（约束名/本表列/引用表列）
    List<TableStructure.ForeignKeyDefinition> foreignKeys = new ArrayList<>();
    Map<String, List<String>> fkColumns = new LinkedHashMap<>();
    Map<String, List<String>> fkRefColumns = new LinkedHashMap<>();
    Map<String, String> fkRefTable = new LinkedHashMap<>();
    try (ResultSet rs = meta.getImportedKeys(catalog, schema, tableName)) {
      while (rs.next()) {
        String fkName = rs.getString("FK_NAME");
        if (fkName == null) {
          continue;
        }
        fkColumns
            .computeIfAbsent(fkName, k -> new ArrayList<>())
            .add(rs.getString("FKCOLUMN_NAME"));
        fkRefColumns
            .computeIfAbsent(fkName, k -> new ArrayList<>())
            .add(rs.getString("PKCOLUMN_NAME"));
        fkRefTable.putIfAbsent(fkName, rs.getString("PKTABLE_NAME"));
      }
    }
    for (Map.Entry<String, List<String>> entry : fkColumns.entrySet()) {
      String fkName = entry.getKey();
      foreignKeys.add(
          new TableStructure.ForeignKeyDefinition(
              fkName,
              entry.getValue(),
              fkRefTable.getOrDefault(fkName, ""),
              fkRefColumns.getOrDefault(fkName, List.of())));
    }
    // 表注释
    String tableComment = "";
    try (ResultSet rs = meta.getTables(catalog, schema, tableName, new String[] {"TABLE"})) {
      if (rs.next()) {
        String remarks = rs.getString("REMARKS");
        tableComment = remarks == null ? "" : remarks;
      }
    }
    return new TableStructure(
        tableName, tableComment, new ArrayList<>(columns.values()), indexes, foreignKeys);
  }
}
