package io.github.dbconsole.sql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfirmationServiceTest {

  private final ConfirmationService confirmations = new ConfirmationService();

  @Test
  @DisplayName("同一会话、数据源与 SQL 文本的令牌校验通过")
  void tokenVerifiesForSameTriple() {
    String token = confirmations.issue("admin", "mysql-work-note", "DELETE FROM t");

    assertThat(confirmations.verify("admin", "mysql-work-note", "DELETE FROM t", token))
        .isTrue();
  }

  @Test
  @DisplayName("任一要素变化令牌即失效：会话、数据源或 SQL 文本")
  void tokenFailsWhenAnyElementChanges() {
    String token = confirmations.issue("admin", "mysql-work-note", "DELETE FROM t");

    assertThat(confirmations.verify("other-user", "mysql-work-note", "DELETE FROM t", token))
        .isFalse();
    assertThat(confirmations.verify("admin", "dm8-navic", "DELETE FROM t", token)).isFalse();
    assertThat(confirmations.verify("admin", "mysql-work-note", "DELETE FROM t WHERE id = 1", token))
        .isFalse();
  }

  @Test
  @DisplayName("空白令牌或乱码令牌不通过")
  void blankOrGarbageTokenFails() {
    assertThat(confirmations.verify("admin", "mysql-work-note", "DELETE FROM t", "")).isFalse();
    assertThat(confirmations.verify("admin", "mysql-work-note", "DELETE FROM t", "deadbeef"))
        .isFalse();
  }

  @Test
  @DisplayName("令牌不可预测：两次签发对同三元组可互验（HMAC 确定性）且不同服务实例密钥不同")
  void tokensAreDeterministicPerInstance() {
    String first = confirmations.issue("admin", "s", "sql");
    String second = confirmations.issue("admin", "s", "sql");

    assertThat(first).isEqualTo(second);
    assertThat(new ConfirmationService().verify("admin", "s", "sql", first)).isFalse();
  }
}
