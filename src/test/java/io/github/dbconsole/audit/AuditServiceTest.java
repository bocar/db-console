package io.github.dbconsole.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dbconsole.config.DbConsoleProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class AuditServiceTest {

  @TempDir Path directory;

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  @DisplayName("按天滚动使用注入时钟的时区")
  void dailyFileUsesClockZone() {
    Clock clock = Clock.fixed(Instant.parse("2026-11-13T23:30:00Z"), ZoneOffset.UTC);
    AuditService audit = service(clock, Duration.ofDays(30));

    audit.recordExecution("admin", "mysql-work-note", "SELECT 1", 12, "SUCCEEDED");

    assertThat(directory.resolve("audit-2026-11-13.jsonl")).exists();
    assertThat(directory.resolve("audit-2026-11-14.jsonl")).doesNotExist();
  }

  @Test
  @DisplayName("执行、提交、回滚事件字段完整且执行事件仅保存 SQL 指纹")
  void recordsAllEventTypesWithoutPlainTextSql() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneOffset.UTC);
    AuditService audit = service(clock, Duration.ofDays(30));
    String sql = "SELECT * FROM dbconsole_test_t WHERE name = 'secret'";

    audit.recordExecution("admin", "mysql-work-note", sql, 42, "SUCCEEDED");
    audit.recordCommit("admin", "mysql-work-note", 7, "SUCCEEDED");
    audit.recordRollback("admin", "mysql-work-note", 5, "FAILED");

    Path file = directory.resolve("audit-2026-11-13.jsonl");
    List<Map<String, Object>> entries = readEntries(file);
    assertThat(entries).hasSize(3);
    assertThat(entries.get(0))
        .containsOnlyKeys(
            "timestamp", "operator", "sourceId", "action", "elapsedMs", "status",
            "sqlFingerprint")
        .containsEntry("timestamp", "2026-11-13T10:00:00Z")
        .containsEntry("operator", "admin")
        .containsEntry("sourceId", "mysql-work-note")
        .containsEntry("action", "EXECUTE")
        .containsEntry("elapsedMs", 42)
        .containsEntry("status", "SUCCEEDED")
        .containsEntry("sqlFingerprint", "aaefc904a972cc28");
    assertThat(entries.get(1))
        .containsOnlyKeys("timestamp", "operator", "sourceId", "action", "elapsedMs", "status")
        .containsEntry("action", "COMMIT")
        .containsEntry("elapsedMs", 7)
        .containsEntry("status", "SUCCEEDED");
    assertThat(entries.get(2))
        .containsOnlyKeys("timestamp", "operator", "sourceId", "action", "elapsedMs", "status")
        .containsEntry("action", "ROLLBACK")
        .containsEntry("elapsedMs", 5)
        .containsEntry("status", "FAILED");
    assertThat(Files.readString(file)).doesNotContain(sql, "secret");
  }

  @Test
  @DisplayName("写入审计时按保留期删除过期文件并保留边界文件")
  void removesExpiredFilesAccordingToRetention() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-11-13T10:00:00.001Z"), ZoneOffset.UTC);
    AuditService audit = service(clock, Duration.ofDays(7));
    Path expired = Files.writeString(directory.resolve("audit-2026-11-05.jsonl"), "old\n");
    Path cutoff = Files.writeString(directory.resolve("audit-2026-11-06.jsonl"), "cutoff\n");
    Path unrelated = Files.writeString(directory.resolve("notes.jsonl"), "keep\n");

    audit.recordExecution("admin", "mysql-work-note", "SELECT 1", 12, "SUCCEEDED");

    assertThat(expired).doesNotExist();
    assertThat(cutoff).exists();
    assertThat(unrelated).exists();
  }

  @Test
  @DisplayName("过期审计删除失败会记录日志而不是静默忽略")
  void expiredFileDeletionFailureIsLogged(CapturedOutput output) throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneOffset.UTC);
    AuditService audit = service(clock, Duration.ofDays(7));
    Path expiredDirectory = directory.resolve("audit-2026-11-05.jsonl");
    Files.createDirectory(expiredDirectory);
    Files.writeString(expiredDirectory.resolve("child"), "not empty");

    audit.recordExecution("admin", "mysql-work-note", "SELECT 1", 12, "SUCCEEDED");

    assertThat(output).contains("审计文件删除失败", "audit-2026-11-05.jsonl");
  }

  private List<Map<String, Object>> readEntries(Path file) throws Exception {
    List<Map<String, Object>> entries = new ArrayList<>();
    for (String line : Files.readAllLines(file)) {
      entries.add(mapper.readValue(line, new TypeReference<>() {}));
    }
    return entries;
  }

  private AuditService service(Clock clock, Duration retention) {
    DbConsoleProperties properties =
        new DbConsoleProperties(
            new DbConsoleProperties.LoginProperties("admin", "test-secret"),
            new DbConsoleProperties.SessionProperties(Duration.ofHours(8)),
            new DbConsoleProperties.ExecutionProperties(
                Duration.ofSeconds(30), 20_000, 4, 100, 1_000, 65_536, 10_485_760),
            new DbConsoleProperties.TransactionProperties(Duration.ofMinutes(10)),
            new DbConsoleProperties.AuditProperties(directory.toString(), retention),
            List.of());
    return new AuditService(properties, clock, mapper);
  }
}
