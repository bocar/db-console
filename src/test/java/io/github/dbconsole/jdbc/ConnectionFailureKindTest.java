package io.github.dbconsole.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConnectionFailureKindTest {

  @ParameterizedTest
  @CsvSource({
    "28000, , AUTHENTICATION",
    ", Access denied for user 'root'@'localhost', AUTHENTICATION",
    ", 用户名或密码不正确, AUTHENTICATION",
    ", login failed for user NAVIC, AUTHENTICATION",
    ", connect timed out, TIMEOUT",
    ", Connection refused, UNAVAILABLE",
    ", Communications link failure, UNAVAILABLE",
    "08006, Connection reset, UNAVAILABLE",
    ", Broken pipe, UNAVAILABLE",
    ", Connection is closed, UNAVAILABLE",
  })
  @DisplayName("按 SQLState 与消息特征分类连接失败")
  void classifiesFailures(String sqlState, String message, ConnectionFailureKind expected) {
    SQLException exception = new SQLException(message, sqlState);

    assertThat(ConnectionFailureKind.classify(exception)).isEqualTo(expected);
  }

  @Test
  @DisplayName("SQLTimeoutException 归为超时")
  void classifiesTimeoutException() {
    assertThat(ConnectionFailureKind.classify(new SQLTimeoutException("timeout")))
        .isEqualTo(ConnectionFailureKind.TIMEOUT);
  }

  @Test
  @DisplayName("空消息不会崩溃并归为不可达")
  void classifiesNullMessageSafely() {
    assertThat(ConnectionFailureKind.classify(new SQLException((String) null)))
        .isEqualTo(ConnectionFailureKind.UNAVAILABLE);
  }
}
