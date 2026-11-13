package io.github.dbconsole.sql;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 执行确认凭据（F-09）：令牌由「登录会话 + 数据源标识 + SQL 文本」计算 HMAC，
 * 任一要素变化即失效；密钥为进程内随机，重启后旧令牌自然作废。
 */
public final class ConfirmationService {

  private final byte[] key = new byte[32];

  public ConfirmationService() {
    new SecureRandom().nextBytes(key);
  }

  public String issue(String username, String sourceId, String sql) {
    return hmac(message(username, sourceId, sql));
  }

  public boolean verify(String username, String sourceId, String sql, String token) {
    if (token == null || token.isBlank()) {
      return false;
    }
    return MessageDigest.isEqual(
        hmac(message(username, sourceId, sql)).getBytes(StandardCharsets.US_ASCII),
        token.getBytes(StandardCharsets.US_ASCII));
  }

  private String message(String username, String sourceId, String sql) {
    return username + "|" + sourceId + "|" + sha256(sql);
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 不可用", exception);
    }
  }

  private String hmac(String message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(raw.length * 2);
      for (byte b : raw) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (Exception exception) {
      throw new IllegalStateException("HMAC 计算失败", exception);
    }
  }
}
