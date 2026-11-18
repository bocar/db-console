package io.github.dbconsole.sql;

import io.github.dbconsole.config.DbConsoleProperties.DataSourceEnvironment;
import io.github.dbconsole.dialect.SqlStatement;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 执行确认（F-09）：确认规则与环境分级（F-16）的判定、令牌的「会话 + 数据源 + SQL 文本」
 * HMAC 绑定，以及「校验失败即随附重新签发的新令牌」不变式，统一收口在 {@link #check}。
 * 调用方只面对 Passed / Required 二选一，无需了解规则与令牌的先后顺序。
 * 密钥为进程内随机，重启后旧令牌自然作废。
 */
public final class ConfirmationService {

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private final SecretKeySpec keySpec;

  public ConfirmationService() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    this.keySpec = new SecretKeySpec(key, HMAC_ALGORITHM);
  }

  /** 确认门判定结果：通过，或要求确认（携带原因、DDL 标志与重新签发的新令牌）。 */
  public sealed interface ConfirmationOutcome permits Passed, Required {}

  /** 无需确认（或呈现令牌校验通过），调用方继续执行语句。 */
  public record Passed() implements ConfirmationOutcome {}

  /** 要求确认：reason 为要求枚举名（wire 契约），freshToken 与「会话 + 数据源 + SQL」绑定。 */
  public record Required(String reason, boolean implicitCommitDdl, String freshToken)
      implements ConfirmationOutcome {}

  /**
   * 确认门：判定当前语句是否需要执行确认，需要时校验呈现令牌；
   * 校验失败即重新签发新令牌随 Required 返回（失败即重签不变式由此强制）。
   */
  public ConfirmationOutcome check(
      String sessionToken,
      String sourceId,
      DataSourceEnvironment environment,
      SqlStatement statement,
      String sql,
      String presentedToken) {
    ConfirmationDecision.Requirement requirement =
        ConfirmationDecision.evaluate(environment, statement);
    if (requirement == ConfirmationDecision.Requirement.NOT_REQUIRED) {
      return new Passed();
    }
    if (verify(sessionToken, sourceId, sql, presentedToken)) {
      return new Passed();
    }
    return new Required(
        requirement.name(),
        requirement == ConfirmationDecision.Requirement.IMPLICIT_COMMIT_DDL,
        issue(sessionToken, sourceId, sql));
  }

  /**
   * 签发与「会话 + 数据源 + SQL 文本」绑定的确认凭据。单语句执行由 {@link #check}
   * 随 Required 响应签发；脚本预校验（F-13）在整份脚本通过后逐语句调用，
   * 绑定强度与单语句路径完全一致。
   */
  public String issue(String sessionToken, String sourceId, String sql) {
    return hmac(message(sessionToken, sourceId, sql));
  }

  /**
   * 校验确认凭据（F-09）：脚本预校验（F-13）与结果编辑保存（F-17）在跨包流程中复用，
   * 绑定不变式与单语句路径完全一致。
   */
  public boolean verify(String sessionToken, String sourceId, String sql, String token) {
    if (token == null || token.isBlank()) {
      return false;
    }
    return MessageDigest.isEqual(
        hmac(message(sessionToken, sourceId, sql)).getBytes(StandardCharsets.US_ASCII),
        token.getBytes(StandardCharsets.US_ASCII));
  }

  private String message(String sessionToken, String sourceId, String sql) {
    return sessionToken + "|" + sourceId + "|" + sha256(sql);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 不可用", exception);
    }
  }

  private String hmac(String message) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(keySpec);
      return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
      throw new IllegalStateException("HMAC 计算失败", exception);
    }
  }
}
