package io.github.dbconsole.sql;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueryResultMapperTest {

  private static io.github.dbconsole.config.DbConsoleProperties.ExecutionProperties limits(
      int maxRows, long resultBytes, long cellBytes) {
    return new io.github.dbconsole.config.DbConsoleProperties.ExecutionProperties(
        java.time.Duration.ofSeconds(30), 20000, 4, maxRows, maxRows, cellBytes, resultBytes);
  }

  @Test
  @DisplayName("按列位置映射行并保留列元数据（零行保留列名）")
  void mapsRowsByPositionAndKeepsColumnsForZeroRows() throws Exception {
    ResultSet resultSet = resultSet(new Object[0][], "id", "name");

    SqlResponses.QueryResult result = QueryResultMapper.map(resultSet, limits(100, 10_000_000, 65_536), 5);

    assertThat(result.kind()).isEqualTo("QUERY");
    assertThat(result.columns()).hasSize(2);
    assertThat(result.columns().get(0).name()).isEqualTo("id");
    assertThat(result.rows()).isEmpty();
    assertThat(result.truncated()).isFalse();
  }

  @Test
  @DisplayName("数值精度保持：Long 与 BigDecimal 转字符串，Integer 保持数字")
  void preservesNumericPrecision() throws Exception {
    Object[] row = {920000000000000001L, new BigDecimal("12.34"), 7, null, ""};
    ResultSet resultSet = resultSet(new Object[][] {row}, "big", "amount", "small", "n", "e");

    SqlResponses.QueryResult result = QueryResultMapper.map(resultSet, limits(100, 10_000_000, 65_536), 1);

    assertThat(result.rows().get(0))
        .containsExactly("920000000000000001", "12.34", 7, null, "");
  }

  @Test
  @DisplayName("时间转文本、布尔保持、字节数组转 Base64")
  void mapsTimesBooleansAndBinary() throws Exception {
    Timestamp timestamp = Timestamp.valueOf(LocalDateTime.of(2026, 11, 13, 8, 30, 0));
    byte[] bytes = {1, 2, 3};
    Object[] row = {timestamp, Boolean.TRUE, bytes};
    ResultSet resultSet = resultSet(new Object[][] {row}, "at", "ok", "data");

    SqlResponses.QueryResult result = QueryResultMapper.map(resultSet, limits(100, 10_000_000, 65_536), 1);

    assertThat(result.rows().get(0).get(0)).isEqualTo("2026-11-13 08:30:00.0");
    assertThat(result.rows().get(0).get(1)).isEqualTo(true);
    assertThat(result.rows().get(0).get(2)).isEqualTo(java.util.Base64.getEncoder().encodeToString(bytes));
  }

  @Test
  @DisplayName("分页取数超过页行数时标记 hasNextPage 而不误报内容截断")
  void marksNextPageWithoutContentTruncation() throws Exception {
    Object[][] data = {{1}, {2}, {3}, {4}};
    ResultSet resultSet = resultSet(data, "id");

    SqlResponses.QueryResult result = QueryResultMapper.map(resultSet, limits(100, 10_000_000, 65_536), 5, 2);

    assertThat(result.rows()).hasSize(2);
    assertThat(result.truncated()).isFalse();
    assertThat(result.hasNextPage()).isTrue();
  }


  @Test
  @DisplayName("单元格超限截断并标记字符数")
  void truncatesCellBeyondLimit() throws Exception {
    String longText = "x".repeat(200);
    Object[] row = {longText};
    ResultSet resultSet = resultSet(new Object[][] {row}, "data");

    SqlResponses.QueryResult result = QueryResultMapper.map(resultSet, limits(10, 10_000_000, 100), 1);
    String cell = (String) result.rows().get(0).get(0);
    assertThat(cell).startsWith("x").contains("<truncated:").endsWith("chars>");
  }

  @Test
  @DisplayName("非分页路径行数上限命中报内容截断，不产生下一页语义")
  void unpagedRowCapReportsTruncationWithoutNextPage() throws Exception {
    Object[][] data = {{1}, {2}, {3}, {4}};
    ResultSet resultSet = resultSet(data, "id");

    SqlResponses.QueryResult result = QueryResultMapper.map(resultSet, limits(2, 10_000_000, 65_536), 1);

    assertThat(result.rows()).hasSize(2);
    assertThat(result.truncated()).isTrue();
    assertThat(result.hasNextPage()).isNull();
  }

  @Test
  @DisplayName("总体积超限截断并标记 truncated")
  void truncatesResultBeyondTotalBytes() throws Exception {
    Object[][] data = {{"aaaa"}, {"bbbb"}, {"cccc"}, {"dddd"}};
    ResultSet resultSet = resultSet(data, "text");

    SqlResponses.QueryResult result = QueryResultMapper.map(resultSet, limits(100, 20, 65_536), 1);
    assertThat(result.rows().size()).isLessThan(data.length);
    assertThat(result.truncated()).isTrue();
  }


  private static ResultSet resultSet(Object[][] data, String... columnNames) throws SQLException {
    ResultSet resultSet = mock(ResultSet.class);
    ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    when(resultSet.getMetaData()).thenReturn(metadata);
    when(metadata.getColumnCount()).thenReturn(columnNames.length);
    for (int index = 1; index <= columnNames.length; index++) {
      when(metadata.getColumnLabel(index)).thenReturn(columnNames[index - 1]);
      when(metadata.getColumnTypeName(index)).thenReturn("TYPE");
    }
    int[] currentRow = {-1};
    when(resultSet.next())
        .thenAnswer(
            invocation -> {
              currentRow[0]++;
              return currentRow[0] < data.length;
            });
    when(resultSet.getObject(org.mockito.ArgumentMatchers.anyInt()))
        .thenAnswer(
            invocation -> {
              int index = invocation.getArgument(0, Integer.class);
              return data[currentRow[0]][index - 1];
            });
    return resultSet;
  }
}
