package io.github.dbconsole.web;

import io.github.dbconsole.jdbc.UnknownDataSourceException;
import io.github.dbconsole.metadata.MetadataException;
import io.github.dbconsole.metadata.TableNotFoundException;
import io.github.dbconsole.sql.DatabaseExecutionException;
import io.github.dbconsole.sql.QueryTimeoutException;
import io.github.dbconsole.sql.SqlRejectException;
import io.github.dbconsole.transaction.TransactionManager;
import io.github.dbconsole.transaction.TransactionStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一错误响应：ApiError 形态，并保留原因记录到应用日志。 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
    log.warn("请求校验失败：{}", ex.getMessage());
    return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "请求参数不合法"));
  }

  @ExceptionHandler(UnknownDataSourceException.class)
  public ResponseEntity<ApiError> handleUnknownDataSource(UnknownDataSourceException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ApiError("UNKNOWN_DATA_SOURCE", "未知数据源标识，请求已拒绝"));
  }

  @ExceptionHandler(TableNotFoundException.class)
  public ResponseEntity<ApiError> handleTableNotFound(TableNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ApiError("TABLE_NOT_FOUND", ex.getMessage()));
  }

  @ExceptionHandler(MetadataException.class)
  public ResponseEntity<ApiError> handleMetadata(MetadataException ex) {
    log.warn("元数据读取失败：{}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ApiError("METADATA_ERROR", ex.getMessage()));
  }

  @ExceptionHandler(SqlRejectException.class)
  public ResponseEntity<ApiError> handleSqlRejection(SqlRejectException ex) {
    return ResponseEntity.unprocessableEntity().body(new ApiError(ex.code(), ex.getMessage()));
  }

  @ExceptionHandler(QueryTimeoutException.class)
  public ResponseEntity<ApiError> handleQueryTimeout(QueryTimeoutException ex) {
    log.warn("查询执行超时：已按配置时限中止");
    return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
        .body(new ApiError("QUERY_TIMEOUT", ex.getMessage()));
  }

  @ExceptionHandler(TransactionStateException.class)
  public ResponseEntity<ApiError> handleTransactionState(TransactionStateException ex) {
    HttpStatus status =
        TransactionManager.CODE_NO_TRANSACTION.equals(ex.code())
            ? HttpStatus.BAD_REQUEST
            : HttpStatus.CONFLICT;
    return ResponseEntity.status(status).body(new ApiError(ex.code(), ex.getMessage()));
  }

  @ExceptionHandler(DatabaseExecutionException.class)
  public ResponseEntity<ApiError> handleDatabaseExecution(DatabaseExecutionException ex) {
    // 详情（含 sqlState 与原因链）已由服务层记录，这里不重复落盘消息，避免 SQL 片段进日志
    log.warn("数据库执行失败：code=DATABASE_ERROR cause={}",
        ex.getCause() == null ? "unknown" : ex.getCause().getClass().getSimpleName());
    return ResponseEntity.badRequest().body(new ApiError("DATABASE_ERROR", ex.getMessage()));
  }
}
