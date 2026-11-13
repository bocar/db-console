package io.github.dbconsole.sql;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.sql.rowset.serial.SerialBlob;

/** ResultSet → wire 结果：按列位置取行，数值精度与 NULL 语义按 F-08 保持。 */
final class QueryResultMapper {

  private QueryResultMapper() {}

  static SqlResponses.QueryResult map(
      ResultSet resultSet, int maxRows, long resultBytesLimit, long cellBytesLimit, long elapsedMs)
      throws SQLException {
    ResultSetMetaData metadata = resultSet.getMetaData();
    int columnCount = metadata.getColumnCount();
    List<SqlResponses.QueryColumn> columns = new ArrayList<>(columnCount);
    for (int index = 1; index <= columnCount; index++) {
      columns.add(
          new SqlResponses.QueryColumn(
              metadata.getColumnLabel(index), metadata.getColumnTypeName(index)));
    }
    List<List<Object>> rows = new ArrayList<>();
    boolean truncated = false;
    long totalBytes = 0;
    while (resultSet.next()) {
      if (rows.size() >= maxRows) {
        truncated = true;
        break;
      }
      List<Object> row = new ArrayList<>(columnCount);
      long rowBytes = 0;
      for (int index = 1; index <= columnCount; index++) {
        Object value = mapValue(resultSet.getObject(index), cellBytesLimit);
        row.add(value);
        rowBytes += value == null ? 4 : String.valueOf(value).length() * 2L;
      }
      totalBytes += rowBytes;
      if (totalBytes > resultBytesLimit) {
        truncated = true;
        break;
      }
      rows.add(row);
    }
    return new SqlResponses.QueryResult(columns, rows, elapsedMs, truncated);
  }

  /**
   * 单元格传输值：Long/BigInteger/BigDecimal 转字符串（超出 JS 安全整数精度），
   * 时间转文本，二进制转 Base64，NULL 与空字符串保持区分。
   * 读取失败按 SQLException 上抛，由执行层统一映射。
   */
  static Object mapValue(Object value, long cellBytesLimit) throws SQLException {
    if (value == null) {
      return null;
    }
    if (value instanceof BigDecimal decimal) {
      return decimal.toPlainString();
    }
    if (value instanceof BigInteger || value instanceof Long) {
      return value.toString();
    }
    if (value instanceof Float || value instanceof Double) {
      double d = ((Number) value).doubleValue();
      return Double.isNaN(d) || Double.isInfinite(d) ? value.toString() : d;
    }
    if (value instanceof Timestamp
        || value instanceof LocalDate
        || value instanceof LocalDateTime
        || value instanceof LocalTime
        || value instanceof java.sql.Date
        || value instanceof java.sql.Time) {
      return value.toString();
    }
    if (value instanceof byte[] bytes) {
      return Base64.getEncoder().encodeToString(bytes);
    }
    if (value instanceof SerialBlob blob) {
      return Base64.getEncoder().encodeToString(blob.getBytes(1, (int) blob.length()));
    }
    if (value instanceof String text) {
      return truncateCell(text, cellBytesLimit);
    }
    if (value instanceof Integer || value instanceof Short
        || value instanceof Byte || value instanceof Boolean) {
      return value;
    }
    return truncateCell(value.toString(), cellBytesLimit);
  }

  private static String truncateCell(String text, long cellBytesLimit) {
    if (text.length() * 2L <= cellBytesLimit) {
      return text;
    }
    int maxChars = (int) (cellBytesLimit / 2);
    return text.substring(0, maxChars) + "<truncated:" + text.length() + "chars>";
  }
}
