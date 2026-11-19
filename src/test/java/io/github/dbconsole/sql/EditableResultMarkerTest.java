package io.github.dbconsole.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dbconsole.config.DbConsoleProperties.CompatibilityMode;
import io.github.dbconsole.config.DbConsoleProperties.DatabaseType;
import io.github.dbconsole.config.DbConsoleProperties.DataSourceEnvironment;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.metadata.MetadataException;
import io.github.dbconsole.metadata.TableStructure;
import io.github.dbconsole.metadata.TableStructureService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 可编辑结果标记（F-17）：资格 → 结构联查 → 列映射；任何失配只读降级，不影响查询结果。 */
class EditableResultMarkerTest {

  private static final String SOURCE_ID = "mysql-work-note";

  private final TableStructureService tableStructures = mock(TableStructureService.class);
  private final ResolvedDataSource source = new ResolvedDataSource(
      SOURCE_ID, "MySQL 测试库", DataSourceEnvironment.DEVELOPMENT,
      DatabaseType.MYSQL, CompatibilityMode.NATIVE,
      "jdbc:mysql://127.0.0.1:1/never", "db-user", "db-secret");
  private final java.sql.Connection connection = mock(java.sql.Connection.class);

  private EditableResultMarker marker;

  @BeforeEach
  void setUp() {
    marker = new EditableResultMarker(tableStructures);
  }

  private void structureReturns(TableStructure structure) {
    when(tableStructures.columnStructure(
            org.mockito.ArgumentMatchers.eq(connection),
            org.mockito.ArgumentMatchers.any(io.github.dbconsole.dialect.Dialect.class),
            anyList()))
        .thenReturn(Optional.of(structure));
  }

  private static TableStructure table(TableStructure.ColumnDefinition... columns) {
    return new TableStructure("orders", "", List.of(columns), List.of(), List.of());
  }

  private static TableStructure.ColumnDefinition column(
      String name, boolean primaryKey, boolean nullable, boolean autoIncrement) {
    return new TableStructure.ColumnDefinition(name, "INT", nullable, null, primaryKey, "", autoIncrement);
  }

  private static SqlResponses.QueryResult query(String... columnNames) {
    List<SqlResponses.QueryColumn> columns =
        java.util.Arrays.stream(columnNames)
            .map(name -> new SqlResponses.QueryColumn(name, "INT"))
            .toList();
    return new SqlResponses.QueryResult(columns, List.of(), 5, false);
  }

  @Nested
  @DisplayName("标记成功")
  class Marked {

    @Test
    @DisplayName("单表 SELECT 且主键在结果列：附表限定名、主键组与按列映射")
    void marksSimpleSelect() {
      structureReturns(table(
          column("id", true, false, true),
          column("name", false, false, false),
          column("note", false, true, false)));

      SqlResponses.QueryResult marked =
          marker.mark(connection, source, "SELECT * FROM orders", query("id", "name", "note"));

      assertThat(marked.editable()).isNotNull();
      assertThat(marked.editable().tableParts()).containsExactly("orders");
      assertThat(marked.editable().primaryKeys()).containsExactly("id");
      assertThat(marked.editable().columns()).hasSize(3);
      assertThat(marked.editable().columns().get(0).primaryKey()).isTrue();
      assertThat(marked.editable().columns().get(0).autoIncrement()).isTrue();
      assertThat(marked.editable().columns().get(2).nullable()).isTrue();
    }

    @Test
    @DisplayName("限定表名与大小写不敏感列名映射（DM8 大写存储）")
    void mapsQualifiedTableCaseInsensitively() {
      ResolvedDataSource dm8 = new ResolvedDataSource(
          "dm8-prod", "DM8", DataSourceEnvironment.PRODUCTION,
          DatabaseType.DM8, CompatibilityMode.NATIVE,
          "jdbc:dm://x", "user", "secret");
      when(tableStructures.columnStructure(
              org.mockito.ArgumentMatchers.eq(connection),
              org.mockito.ArgumentMatchers.any(io.github.dbconsole.dialect.Dialect.class),
              anyList()))
          .thenReturn(Optional.of(table(
              column("ID", true, false, false), column("NAME", false, true, false))));

      SqlResponses.QueryResult marked =
          marker.mark(connection, dm8, "SELECT id, name FROM NAVIC.ORDERS", query("id", "name"));

      assertThat(marked.editable()).isNotNull();
      assertThat(marked.editable().tableParts()).containsExactly("NAVIC", "ORDERS");
      assertThat(marked.editable().primaryKeys()).containsExactly("ID");
    }

    @Test
    @DisplayName("表达式列映射为 null 但整体仍可编辑")
    void expressionColumnMapsNull() {
      structureReturns(table(
          column("id", true, false, false), column("amount", false, true, false)));

      SqlResponses.QueryResult marked =
          marker.mark(connection, source, "SELECT id, amount * 2 FROM orders", query("id", "amount * 2"));

      assertThat(marked.editable()).isNotNull();
      assertThat(marked.editable().columns()).hasSize(2);
      assertThat(marked.editable().columns().get(1)).isNull();
    }
  }

  @Nested
  @DisplayName("只读降级")
  class Degraded {

    @Test
    @DisplayName("无主键表不标记")
    void noPrimaryKeyDegrades() {
      structureReturns(table(column("name", false, true, false)));

      assertThat(marker.mark(connection, source, "SELECT * FROM orders", query("name")).editable()).isNull();
    }

    @Test
    @DisplayName("主键不在结果列不标记")
    void missingPrimaryKeyInResultDegrades() {
      structureReturns(table(
          column("id", true, false, false), column("name", false, true, false)));

      assertThat(marker.mark(connection, source, "SELECT name FROM orders", query("name")).editable()).isNull();
    }

    @Test
    @DisplayName("结构不可见（表不存在）不标记")
    void invisibleTableDegrades() {
      when(tableStructures.columnStructure(
              org.mockito.ArgumentMatchers.any(java.sql.Connection.class),
              org.mockito.ArgumentMatchers.any(io.github.dbconsole.dialect.Dialect.class),
              anyList()))
          .thenReturn(Optional.empty());

      assertThat(marker.mark(connection, source, "SELECT * FROM orders", query("id")).editable()).isNull();
    }

    @Test
    @DisplayName("结构读取失败降级且不影响查询结果")
    void metadataFailureDegrades() {
      when(tableStructures.columnStructure(
              org.mockito.ArgumentMatchers.any(java.sql.Connection.class),
              org.mockito.ArgumentMatchers.any(io.github.dbconsole.dialect.Dialect.class),
              anyList()))
          .thenThrow(new MetadataException("对象结构读取失败：down", new RuntimeException("down")));

      SqlResponses.QueryResult result = marker.mark(connection, source, "SELECT * FROM orders", query("id"));
      assertThat(result.editable()).isNull();
      assertThat(result.rows()).isEmpty();
    }

    @Test
    @DisplayName("重名结果列全部只读，主键可定位时整体仍可编辑")
    void duplicateResultColumnsReadonly() {
      structureReturns(table(
          column("id", true, false, false),
          column("name", false, true, false),
          column("amount", false, true, false)));

      SqlResponses.QueryResult marked =
          marker.mark(connection, source, "SELECT id, name, name FROM orders", query("id", "name", "name"));

      assertThat(marked.editable()).isNotNull();
      assertThat(marked.editable().columns().get(0)).isNotNull();
      assertThat(marked.editable().columns().get(1)).isNull();
      assertThat(marked.editable().columns().get(2)).isNull();
    }

    @Test
    @DisplayName("重名主键列不定位，整体只读")
    void duplicatedPrimaryKeyDegrades() {
      structureReturns(table(column("id", true, false, false)));

      assertThat(
              marker.mark(connection, source, "SELECT id, id FROM orders", query("id", "id")).editable())
          .isNull();
    }

    @Test
    @DisplayName("JOIN / DISTINCT / 聚合等不合格查询不查结构、不标记")
    void ineligibleShapeSkipsMetadataLookup() {
      org.mockito.Mockito.clearInvocations(tableStructures);

      assertThat(marker.mark(connection, source, "SELECT DISTINCT id FROM orders", query("id")).editable())
          .isNull();
      assertThat(marker.mark(connection, source, "SELECT * FROM orders JOIN bills ON 1 = 1", query("id"))
              .editable())
          .isNull();
      assertThat(marker.mark(connection, source, "SELECT COUNT(*) FROM orders", query("COUNT(*)")).editable())
          .isNull();
      org.mockito.Mockito.verifyNoInteractions(tableStructures);
    }
  }
}
