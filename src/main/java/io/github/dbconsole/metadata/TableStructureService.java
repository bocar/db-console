package io.github.dbconsole.metadata;

import io.github.dbconsole.jdbc.ConnectionFactory;
import io.github.dbconsole.jdbc.DataSourceRegistry;
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
  private final DataSourceRegistry registry;

  public TableStructureService(ConnectionFactory connections, DataSourceRegistry registry) {
    this.connections = connections;
    this.registry = registry;
  }

  /** 列出当前库/模式下全部表的结构。 */
  public List<TableStructure> listTables(String sourceId) {
    ResolvedDataSource source = registry.resolve(sourceId);
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

  /** 单表完整结构：列 + 索引 + 外键；表不存在时抛 404。 */
  public TableStructure tableStructure(String sourceId, String tableName) {
    ResolvedDataSource source = registry.resolve(sourceId);
    try (Connection connection = connections.open(source)) {
      DatabaseMetaData meta = connection.getMetaData();
      String catalog = connection.getCatalog();
      String schema = connection.getSchema();
      // 限定名拆分：优先按 schema.table 解析（DM8 等模式型数据库），
      // 引号内的点号不切分（方言感知留待 T17 补查完善）
      String stripped = tableName.replaceAll("^[`\"']|[`\"']$", "");
      String[] parts = stripped.split("\\.");
      if (parts.length == 2) {
        // 尝试 schema.table；JDBC getTables 对 MySQL catalog 与 schema 同义
        schema = parts[0];
        tableName = parts[1];
      }
      TableStructure structure = tableExists(meta, catalog, schema, tableName)
          ? structure(meta, catalog, schema, tableName) : null;
      if (structure == null) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.NOT_FOUND,
            "表 " + tableName + " 不存在或无权访问");
      }
      return structure;
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
    Map<String, TableStructure.ColumnDefinition> columns = loadColumns(meta, catalog, schema, tableName);
    markPrimaryKeys(meta, catalog, schema, tableName, columns);
    List<TableStructure.IndexDefinition> indexes = loadIndexes(meta, catalog, schema, tableName);
    List<TableStructure.ForeignKeyDefinition> foreignKeys = loadForeignKeys(meta, catalog, schema, tableName);
    String tableComment = loadTableComment(meta, catalog, schema, tableName);
    return new TableStructure(
        tableName, tableComment, new ArrayList<>(columns.values()), indexes, foreignKeys);
  }

  private Map<String, TableStructure.ColumnDefinition> loadColumns(
      DatabaseMetaData meta, String catalog, String schema, String tableName) throws SQLException {
    Map<String, TableStructure.ColumnDefinition> columns = new LinkedHashMap<>();
    try (ResultSet rs = meta.getColumns(catalog, schema, tableName, "%")) {
      while (rs.next()) {
        String comment = rs.getString("REMARKS");
        columns.put(
            rs.getString("COLUMN_NAME"),
            new TableStructure.ColumnDefinition(
                rs.getString("COLUMN_NAME"),
                rs.getString("TYPE_NAME"),
                rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                rs.getString("COLUMN_DEF"),
                false,
                comment == null ? "" : comment));
      }
    }
    return columns;
  }

  private void markPrimaryKeys(
      DatabaseMetaData meta,
      String catalog,
      String schema,
      String tableName,
      Map<String, TableStructure.ColumnDefinition> columns) throws SQLException {
    try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, tableName)) {
      while (rs.next()) {
        String columnName = rs.getString("COLUMN_NAME");
        TableStructure.ColumnDefinition existing = columns.get(columnName);
        if (existing != null) {
          columns.put(
              columnName,
              new TableStructure.ColumnDefinition(
                  existing.name(), existing.type(), existing.nullable(),
                  existing.defaultValue(), true, existing.comment()));
        }
      }
    }
  }

  private List<TableStructure.IndexDefinition> loadIndexes(
      DatabaseMetaData meta, String catalog, String schema, String tableName) throws SQLException {
    Map<String, List<String>> indexColumns = new LinkedHashMap<>();
    Map<String, Boolean> indexUnique = new LinkedHashMap<>();
    try (ResultSet rs = meta.getIndexInfo(catalog, schema, tableName, false, false)) {
      while (rs.next()) {
        String indexName = rs.getString("INDEX_NAME");
        if (indexName == null) continue;
        String columnName = rs.getString("COLUMN_NAME");
        indexColumns.computeIfAbsent(indexName, k -> new ArrayList<>())
            .add(columnName == null ? "" : columnName);
        indexUnique.putIfAbsent(indexName, !rs.getBoolean("NON_UNIQUE"));
      }
    }
    List<TableStructure.IndexDefinition> indexes = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : indexColumns.entrySet()) {
      indexes.add(new TableStructure.IndexDefinition(
          entry.getKey(), indexUnique.getOrDefault(entry.getKey(), false), entry.getValue()));
    }
    return indexes;
  }

  private List<TableStructure.ForeignKeyDefinition> loadForeignKeys(
      DatabaseMetaData meta, String catalog, String schema, String tableName) throws SQLException {
    Map<String, List<String>> fkColumns = new LinkedHashMap<>();
    Map<String, List<String>> fkRefColumns = new LinkedHashMap<>();
    Map<String, String> fkRefTable = new LinkedHashMap<>();
    try (ResultSet rs = meta.getImportedKeys(catalog, schema, tableName)) {
      while (rs.next()) {
        String fkName = rs.getString("FK_NAME");
        if (fkName == null) continue;
        fkColumns.computeIfAbsent(fkName, k -> new ArrayList<>()).add(rs.getString("FKCOLUMN_NAME"));
        fkRefColumns.computeIfAbsent(fkName, k -> new ArrayList<>()).add(rs.getString("PKCOLUMN_NAME"));
        fkRefTable.putIfAbsent(fkName, rs.getString("PKTABLE_NAME"));
      }
    }
    List<TableStructure.ForeignKeyDefinition> foreignKeys = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : fkColumns.entrySet()) {
      String fkName = entry.getKey();
      foreignKeys.add(new TableStructure.ForeignKeyDefinition(
          fkName, entry.getValue(), fkRefTable.getOrDefault(fkName, ""),
          fkRefColumns.getOrDefault(fkName, List.of())));
    }
    return foreignKeys;
  }

  private String loadTableComment(
      DatabaseMetaData meta, String catalog, String schema, String tableName) throws SQLException {
    try (ResultSet rs = meta.getTables(catalog, schema, tableName, new String[] {"TABLE"})) {
      if (rs.next()) {
        String remarks = rs.getString("REMARKS");
        return remarks == null ? "" : remarks;
      }
    }
    return "";
  }
}
