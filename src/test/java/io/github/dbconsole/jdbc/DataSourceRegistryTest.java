package io.github.dbconsole.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dbconsole.config.DbConsoleProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DataSourceRegistryTest {

  private static DbConsoleProperties properties() {
    return new DbConsoleProperties(
        new DbConsoleProperties.LoginProperties("admin", "app-secret"),
        new DbConsoleProperties.SessionProperties(Duration.ofHours(8)),
        new DbConsoleProperties.ExecutionProperties(
            Duration.ofSeconds(30), 20000, 4, 100, 1000, 65536, 10485760),
        new DbConsoleProperties.TransactionProperties(Duration.ofMinutes(10)),
        new DbConsoleProperties.AuditProperties("./audit", Duration.ofDays(30)),
        List.of(
            dataSource("mysql-work-note", "DB_CONSOLE_MYSQL_PASSWORD"),
            dataSource("dm8-navic", "DB_CONSOLE_DM8_PASSWORD")));
  }

  private static DbConsoleProperties.DataSourceProperties dataSource(String id, String ref) {
    return new DbConsoleProperties.DataSourceProperties(
        id,
        "显示名 " + id,
        DbConsoleProperties.DataSourceEnvironment.DEVELOPMENT,
        DbConsoleProperties.DatabaseType.MYSQL,
        DbConsoleProperties.CompatibilityMode.NATIVE,
        "jdbc:mysql://127.0.0.1:3306/work_note",
        "root",
        ref);
  }

  @Test
  @DisplayName("按标识解析出完整连接信息，凭据来自引用的环境变量")
  void resolvesDataSourceWithCredentialFromReference() {
    DataSourceRegistry registry =
        new DataSourceRegistry(
            properties(),
            ref ->
                "DB_CONSOLE_MYSQL_PASSWORD".equals(ref)
                    ? "mysql-secret"
                    : "dm8-secret");

    ResolvedDataSource resolved = registry.resolve("mysql-work-note");

    assertThat(resolved.id()).isEqualTo("mysql-work-note");
    assertThat(resolved.displayName()).isEqualTo("显示名 mysql-work-note");
    assertThat(resolved.username()).isEqualTo("root");
    assertThat(resolved.password()).isEqualTo("mysql-secret");
    assertThat(resolved.jdbcUrl()).isEqualTo("jdbc:mysql://127.0.0.1:3306/work_note");
  }

  @Test
  @DisplayName("未知数据源标识被拒绝")
  void rejectsUnknownDataSourceId() {
    DataSourceRegistry registry =
        new DataSourceRegistry(properties(), ref -> "any-" + ref);

    assertThatThrownBy(() -> registry.resolve("no-such-source"))
        .isInstanceOf(UnknownDataSourceException.class)
        .hasMessageContaining("no-such-source");
  }

  @Test
  @DisplayName("启动时凭据引用缺失即失败并指明变量名")
  void failsStartupWhenCredentialReferenceMissing() {
    assertThatThrownBy(
            () -> new DataSourceRegistry(properties(), ref -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DB_CONSOLE_MYSQL_PASSWORD");
  }

  @Test
  @DisplayName("公开信息列表不含任何连接细节")
  void summariesExposeNoConnectionDetails() {
    DataSourceRegistry registry = new DataSourceRegistry(properties(), ref -> "secret");

    List<DataSourceSummary> summaries = registry.summaries();

    assertThat(summaries).hasSize(2);
    assertThat(summaries.get(0))
        .extracting(DataSourceSummary::id, DataSourceSummary::displayName)
        .containsExactly("mysql-work-note", "显示名 mysql-work-note");
  }
}
