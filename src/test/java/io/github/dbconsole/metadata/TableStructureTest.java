package io.github.dbconsole.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 列身份语义单测：大小写不敏感列索引（存储形态各异：DM8 大写、MySQL 原样）。 */
class TableStructureTest {

  @Test
  void lowerCaseColumnIndexResolvesCaseInsensitively() {
    TableStructure structure = table(column("ID"), column("NAME"));

    var index = structure.lowerCaseColumnIndex();

    assertThat(index.get("id")).isEqualTo(column("ID"));
    assertThat(index.get("name")).isEqualTo(column("NAME"));
    assertThat(index).doesNotContainKey("missing");
  }

  @Test
  void lowerCaseColumnIndexPrefersFirstColumnOnCaseDuplicate() {
    // 大小写敏感存储（如 PostgreSQL 形态）可能同存 ID/Id：取先定义者
    TableStructure structure = table(column("Id"), column("ID"));

    var index = structure.lowerCaseColumnIndex();

    assertThat(index.get("id")).isEqualTo(column("Id"));
    assertThat(index).hasSize(1);
  }

  @Test
  void lowerCaseColumnIndexIsUnmodifiable() {
    var index = table(column("ID")).lowerCaseColumnIndex();

    assertThatCode(() -> index.put("hack", column("HACK")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static TableStructure.ColumnDefinition column(String name) {
    return new TableStructure.ColumnDefinition(name, "VARCHAR(64)", true, null, false, null);
  }

  private static TableStructure table(TableStructure.ColumnDefinition... columns) {
    return new TableStructure("t", null, List.of(columns), List.of(), List.of());
  }
}
