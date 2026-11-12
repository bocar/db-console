package io.github.dbconsole.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 请求校验失败统一返回 ApiError 形态，并保留原因记录到应用日志。 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
    log.warn("请求校验失败：{}", ex.getMessage());
    return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "请求参数不合法"));
  }
}
