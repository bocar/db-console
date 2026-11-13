package io.github.dbconsole.sql;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 执行确认凭据（F-09）：令牌由「会话令牌 + 数据源标识 + SQL 文本」计算 HMAC，
 * 会话、数据源或 SQL 任一变化即失效（登出/过期/换会话都不再可用）；
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

  public String issue(String sessionToken, String sourceId, String sql) {
    return hmac(message(sessionToken, sourceId, sql));
  }

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
