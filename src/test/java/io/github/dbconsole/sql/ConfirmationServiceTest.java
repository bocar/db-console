package io.github.dbconsole.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.config.DbConsoleProperties.DataSourceEnvironment;
import io.github.dbconsole.dialect.SqlStatement;
import io.github.dbconsole.dialect.SqlStatementAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfirmationServiceTest {

  private final ConfirmationService confirmations = new ConfirmationService();

  @Test
  @DisplayName("同一会话令牌、数据源与 SQL 文本的令牌校验通过")
  void tokenVerifiesForSameTriple() {
    String token = confirmations.issue("session-token-a", "mysql-work-note", "DELETE FROM t");

    assertThat(confirmations.verify("session-token-a", "mysql-work-note", "DELETE FROM t", token))
        .isTrue();
  }

  @Test
  @DisplayName("任一要素变化令牌即失效：会话令牌、数据源或 SQL 文本")
  void tokenFailsWhenAnyElementChanges() {
    String token = confirmations.issue("session-token-a", "mysql-work-note", "DELETE FROM t");

    assertThat(confirmations.verify("session-token-b", "mysql-work-note", "DELETE FROM t", token))
        .isFalse();
    assertThat(confirmations.verify("session-token-a", "dm8-navic", "DELETE FROM t", token)).isFalse();
    assertThat(confirmations.verify("session-token-a", "mysql-work-note", "DELETE FROM t WHERE id = 1", token))
        .isFalse();
  }

  @Test
  @DisplayName("空白令牌或乱码令牌不通过")
  void blankOrGarbageTokenFails() {
    assertThat(confirmations.verify("session-token-a", "mysql-work-note", "DELETE FROM t", "")).isFalse();
    assertThat(confirmations.verify("session-token-a", "mysql-work-note", "DELETE FROM t", "deadbeef"))
        .isFalse();
  }

  @Test
  @DisplayName("令牌不可预测：两次签发对同三元组可互验（HMAC 确定性）且不同服务实例密钥不同")
  void tokensAreDeterministicPerInstance() {
    String first = confirmations.issue("session-token-a", "s", "sql");
    String second = confirmations.issue("session-token-a", "s", "sql");

    assertThat(first).isEqualTo(second);
    assertThat(new ConfirmationService().verify("session-token-a", "s", "sql", first)).isFalse();
  }

  @Test
  @DisplayName("门：生产库写入未携带有效令牌 → Required 并随附新令牌")
  void gateRequiresFreshTokenForProductionWriteWithoutValidToken() {
    String sql = "UPDATE t SET x = 1 WHERE id = 1";
    // 跨数据源令牌必然失效：模拟「陈令牌」场景
    String presented = confirmations.issue("session-token-a", "other-source", sql);

    ConfirmationService.ConfirmationOutcome outcome =
        confirmations.check(
            "session-token-a", "mysql-work-note",
            DataSourceEnvironment.PRODUCTION, statement(sql), sql, presented);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            ConfirmationService.Required.class,
            required -> {
              assertThat(required.reason()).isEqualTo("PRODUCTION_WRITE");
              assertThat(required.implicitCommitDdl()).isFalse();
              assertThat(required.freshToken()).isNotBlank();
              assertThat(required.freshToken()).isNotEqualTo(presented);
            });
  }

  @Test
  @DisplayName("门：生产库写入携带有效令牌 → Passed")
  void gatePassesProductionWriteWithValidToken() {
    String sql = "UPDATE t SET x = 1 WHERE id = 1";
    String token = confirmations.issue("session-token-a", "mysql-work-note", sql);

    ConfirmationService.ConfirmationOutcome outcome =
        confirmations.check(
            "session-token-a", "mysql-work-note",
            DataSourceEnvironment.PRODUCTION, statement(sql), sql, token);

    assertThat(outcome).isInstanceOf(ConfirmationService.Passed.class);
  }

  @Test
  @DisplayName("门：开发库查询无需确认 → Passed")
  void gatePassesDevelopmentQueryWithoutConfirmation() {
    ConfirmationService.ConfirmationOutcome outcome =
        confirmations.check(
            "session-token-a", "mysql-work-note",
            DataSourceEnvironment.DEVELOPMENT, statement("SELECT 1"), "SELECT 1", null);

    assertThat(outcome).isInstanceOf(ConfirmationService.Passed.class);
  }

  @Test
  @DisplayName("门：隐式提交 DDL 的 Required 携带 DDL 标志（由确认要求推导，非语句二次推导）")
  void gateMarksImplicitCommitDdlFromRequirement() {
    String sql = "DROP TABLE t";

    ConfirmationService.ConfirmationOutcome outcome =
        confirmations.check(
            "session-token-a", "mysql-work-note",
            DataSourceEnvironment.DEVELOPMENT, statement(sql), sql, null);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            ConfirmationService.Required.class,
            required -> {
              assertThat(required.reason()).isEqualTo("IMPLICIT_COMMIT_DDL");
              assertThat(required.implicitCommitDdl()).isTrue();
            });
  }

  private static SqlStatement statement(String sql) {
    return (SqlStatement)
        SqlStatementAnalyzer.forDialect(
                DbConsoleProperties.DatabaseType.MYSQL,
                DbConsoleProperties.CompatibilityMode.NATIVE)
            .analyze(sql);
  }
}
