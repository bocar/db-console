package io.github.dbconsole.metadata;

import io.github.dbconsole.config.DbConsoleProperties.CompatibilityMode;
import io.github.dbconsole.config.DbConsoleProperties.DatabaseType;
import io.github.dbconsole.dialect.Dialect;
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
import java.util.Optional;
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

  /** 列出当前库/模式下全部表（名称+注释）；列/索引/外键由单表结构接口懒加载。 */
  public List<TableStructure> listTables(String sourceId) {
    ResolvedDataSource source = registry.resolve(sourceId);
    try (Connection connection = connections.open(source)) {
      DatabaseMetaData meta = connection.getMetaData();
      List<TableStructure> tables = new ArrayList<>();
      try (ResultSet rs =
          meta.getTables(connection.getCatalog(), connection.getSchema(), "%",
              new String[] {"TABLE"})) {
        while (rs.next()) {
          String remarks = rs.getString("REMARKS");
          tables.add(new TableStructure(
              rs.getString("TABLE_NAME"),
              remarks == null ? "" : remarks,
              List.of(), List.of(), List.of()));
        }
      }
      return tables;
    } catch (SQLException exception) {
      throw new MetadataException("对象结构读取失败：" + exception.getMessage(), exception);
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
      return structure(meta, catalog, schema, tableName);
    } catch (SQLException exception) {
      throw new MetadataException("表结构读取失败：" + exception.getMessage(), exception);
    }
  }



  /** 轻量结构读取的命中结果：resolvedParts 为实际命中的大小写形态（语句生成须用它，DM8 大写存储）。 */
  public record ResolvedColumnStructure(List<String> resolvedParts, TableStructure structure) {}

  /**
   * 轻量单表结构（F-17）·保存路径：自开连接，只取列与主键（不查索引、外键与表注释），
   * 返回命中大小写形态与结构——生成 SQL 的标识符必须用命中形态（引号标识符在 DM8/Oracle
   * 系大小写敏感）。限定名部分由调用方给出（已去引号）：1 段=表，2 段=schema.表（MySQL 系
   * 按 catalog.表理解，取 catalog 位），3 段=catalog.schema.表；大小写按原样、全大写、
   * 全小写依次尝试（DM8 存储为大写），均无结果返回空。
   */
  public Optional<ResolvedColumnStructure> resolveColumnStructure(
      String sourceId, Dialect dialect, List<String> tableParts) {
    if (tableParts == null || tableParts.isEmpty()) {
      return Optional.empty();
    }
    ResolvedDataSource source = registry.resolve(sourceId);
    try (Connection connection = connections.open(source)) {
      return columnStructure(
          connection.getMetaData(), connection.getCatalog(), connection.getSchema(),
          dialect, tableParts);
    } catch (SQLException exception) {
      throw new MetadataException("表结构读取失败：" + exception.getMessage(), exception);
    }
  }

  /** 搭用已有连接的轻量结构读取（F-17 可编辑标记：不新开连接）。 */
  public Optional<TableStructure> columnStructure(
      Connection connection, Dialect dialect, List<String> tableParts) {
    if (tableParts == null || tableParts.isEmpty()) {
      return Optional.empty();
    }
    try {
      return columnStructure(
              connection.getMetaData(), connection.getCatalog(), connection.getSchema(),
              dialect, tableParts)
          .map(ResolvedColumnStructure::structure);
    } catch (SQLException exception) {
      throw new MetadataException("表结构读取失败：" + exception.getMessage(), exception);
    }
  }

  private Optional<ResolvedColumnStructure> columnStructure(
      DatabaseMetaData meta,
      String connectionCatalog,
      String connectionSchema,
      Dialect dialect,
      List<String> tableParts) throws SQLException {
    boolean mysqlFamily = dialect.mysqlFamily();
    for (List<String> parts :
        List.of(tableParts, upperCased(tableParts), lowerCased(tableParts))) {
      String table = parts.get(parts.size() - 1);
      String qualifier = parts.size() >= 2 ? parts.get(parts.size() - 2) : null;
      String catalog = parts.size() >= 3 ? parts.get(0) : connectionCatalog;
      String schema = connectionSchema;
      if (qualifier != null) {
        if (mysqlFamily) {
          catalog = qualifier;
        } else {
          schema = qualifier;
        }
      }
      Map<String, TableStructure.ColumnDefinition> columns =
          loadColumns(meta, catalog, schema, table);
      if (columns.isEmpty()) {
        continue;
      }
      markPrimaryKeys(meta, catalog, schema, table, columns);
      return Optional.of(
          new ResolvedColumnStructure(
              List.copyOf(parts),
              new TableStructure(
                  table, "", new ArrayList<>(columns.values()), List.of(), List.of())));
    }
    return Optional.empty();
  }

  private static List<String> upperCased(List<String> parts) {
    return parts.stream().map(String::toUpperCase).toList();
  }

  private static List<String> lowerCased(List<String> parts) {
    return parts.stream().map(String::toLowerCase).toList();
  }

  private TableStructure structure(
      DatabaseMetaData meta, String catalog, String schema, String tableName)
      throws SQLException {
    String tableComment = loadTableComment(meta, catalog, schema, tableName);
    Map<String, TableStructure.ColumnDefinition> columns = loadColumns(meta, catalog, schema, tableName);
    markPrimaryKeys(meta, catalog, schema, tableName, columns);
    List<TableStructure.IndexDefinition> indexes = loadIndexes(meta, catalog, schema, tableName);
    List<TableStructure.ForeignKeyDefinition> foreignKeys = loadForeignKeys(meta, catalog, schema, tableName);
    return new TableStructure(
        tableName, tableComment, new ArrayList<>(columns.values()), indexes, foreignKeys);
  }

  private Map<String, TableStructure.ColumnDefinition> loadColumns(
      DatabaseMetaData meta, String catalog, String schema, String tableName) throws SQLException {
    Map<String, TableStructure.ColumnDefinition> columns = new LinkedHashMap<>();
    try (ResultSet rs = meta.getColumns(catalog, schema, tableName, "%")) {
      while (rs.next()) {
        String comment = rs.getString("REMARKS");
        String autoIncrement = rs.getString("IS_AUTOINCREMENT");
        columns.put(
            rs.getString("COLUMN_NAME"),
            new TableStructure.ColumnDefinition(
                rs.getString("COLUMN_NAME"),
                rs.getString("TYPE_NAME"),
                rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                rs.getString("COLUMN_DEF"),
                false,
                comment == null ? "" : comment,
                "YES".equalsIgnoreCase(autoIncrement)));
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
    try (ResultSet rs = meta.getIndexInfo(catalog, schema, tableName, false, true)) {
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

  /** 单次 getTables 同时判定存在性与读取注释。 */
  private String loadTableComment(
      DatabaseMetaData meta, String catalog, String schema, String tableName) throws SQLException {
    try (ResultSet rs = meta.getTables(catalog, schema, tableName, new String[] {"TABLE"})) {
      if (rs.next()) {
        String remarks = rs.getString("REMARKS");
        return remarks == null ? "" : remarks;
      }
    }
    // 未找到
    throw new TableNotFoundException(tableName);
  }
}
