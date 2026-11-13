package io.github.dbconsole.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dbconsole.config.DbConsoleProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 审计（F-10）：执行/提交/回滚写 JSONL 按天滚动。
 * 记录操作者、数据源、时间、耗时与状态；附 SQL SHA-256 指纹前 16 位（不含明文）。
 * 绝不记录凭据、完整 SQL 或结果数据；写入失败不阻塞业务（记错误日志）。
 */
@Service
public class AuditService {

  private static final Logger log = LoggerFactory.getLogger(AuditService.class);

  private final Path directory;
  private final Duration retention;
  private final Clock clock;
  private final ObjectMapper mapper;
  private LocalDate lastCleanupDay;

  public AuditService(DbConsoleProperties properties, Clock clock, ObjectMapper mapper) {
    this.directory = Path.of(properties.audit().directory());
    this.retention = properties.audit().retention();
    this.clock = clock;
    this.mapper = mapper;
    try {
      Files.createDirectories(directory);
    } catch (IOException exception) {
      log.warn("审计目录创建失败：{}", exception.getMessage());
    }
  }

  /** 记录 SQL 执行。 */
  public void recordExecution(
      String username, String sourceId, String sql, long elapsedMs, String status) {
    write(username, sourceId, "EXECUTE", elapsedMs, status, fingerprint(sql));
  }

  /** 记录提交。 */
  public void recordCommit(String username, String sourceId, long elapsedMs, String status) {
    write(username, sourceId, "COMMIT", elapsedMs, status, null);
  }

  /** 记录回滚。 */
  public void recordRollback(String username, String sourceId, long elapsedMs, String status) {
    write(username, sourceId, "ROLLBACK", elapsedMs, status, null);
  }

  private synchronized void write(
      String username, String sourceId, String action, long elapsedMs,
      String status, String sqlFingerprint) {
    Instant now = clock.instant();
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("timestamp", now.toString());
    entry.put("operator", username);
    entry.put("sourceId", sourceId);
    entry.put("action", action);
    entry.put("elapsedMs", elapsedMs);
    entry.put("status", status);
    if (sqlFingerprint != null) {
      entry.put("sqlFingerprint", sqlFingerprint);
    }
    try {
      String line = mapper.writeValueAsString(entry) + "\n";
      Path file = directory.resolve(
          "audit-" + LocalDate.ofInstant(now, clock.getZone()) + ".jsonl");
      Files.writeString(file, line, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException exception) {
      log.error("审计写入失败 action={} sourceId={}: {}", action, sourceId, exception.getMessage());
    }
    maybeCleanup(now);
  }

  /** SQL SHA-256 指纹前 16 位（不含任何明文）。 */
  private static String fingerprint(String sql) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 16);
    } catch (NoSuchAlgorithmException exception) {
      return "unavailable";
    }
  }

  /** 淘汰超过保留期的审计文件（每天首次写入时检查）。 */
  private void maybeCleanup(Instant now) {
    LocalDate today = LocalDate.ofInstant(now, clock.getZone());
    if (today.equals(lastCleanupDay)) {
      return;
    }
    lastCleanupDay = today;
    LocalDate cutoff = LocalDate.ofInstant(now.minus(retention), clock.getZone());
    try (var files = Files.list(directory)) {
      files.filter(path -> {
        String name = path.getFileName().toString();
        return name.startsWith("audit-") && name.endsWith(".jsonl");
      }).forEach(path -> cleanupFile(path, cutoff));
    } catch (IOException exception) {
      log.warn("审计清理失败：{}", exception.getMessage());
    }
  }

  private void cleanupFile(Path path, LocalDate cutoff) {
    String name = path.getFileName().toString();
    String datePart = name.substring("audit-".length(), name.length() - ".jsonl".length());
    LocalDate fileDate;
    try {
      fileDate = LocalDate.parse(datePart);
    } catch (DateTimeParseException exception) {
      return;
    }
    if (!fileDate.isBefore(cutoff)) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException exception) {
      log.warn("审计文件删除失败 file={}: {}", name, exception.getMessage());
    }
  }
}
