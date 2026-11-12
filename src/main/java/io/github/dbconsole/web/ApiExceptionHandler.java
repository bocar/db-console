package io.github.dbconsole.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 请求校验失败统一返回 ApiError 形态。 */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
    return ResponseEntity.badRequest()
        .body(new ApiError("INVALID_REQUEST", "请求参数不合法"));
  }
}
