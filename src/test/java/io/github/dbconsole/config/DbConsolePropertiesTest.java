package io.github.dbconsole.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class DbConsolePropertiesTest {

  private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(30);
  private static final int MAX_SQL_LENGTH = 20000;
  private static final int MAX_CONCURRENCY = 4;
  private static final int DEFAULT_PAGE_SIZE = 100;
  private static final int MAX_PAGE_SIZE = 1000;
  private static final long MAX_CELL_BYTES = 65536;
  private static final long MAX_RESULT_BYTES = 10485760;

  @Test
  @DisplayName("外部配置键按 kebab-case 绑定到不可变配置模型并解析枚举")
  void bindsExternalConfigKeysToImmutableModel() {
    Map<String, String> source = validSource();
    Binder binder = new Binder(new MapConfigurationPropertySource(source));

    DbConsoleProperties properties =
        binder.bind("db-console", Bindable.of(DbConsoleProperties.class)).get();

    assertThat(properties.login().username()).isEqualTo("admin");
    assertThat(properties.login().password()).isEqualTo("app-secret");
    assertThat(properties.session().timeout()).isEqualTo(Duration.ofHours(8));
    assertThat(properties.execution().timeout()).isEqualTo(EXECUTION_TIMEOUT);
    assertThat(properties.execution().maxSqlLength()).isEqualTo(MAX_SQL_LENGTH);
    assertThat(properties.execution().maxConcurrency()).isEqualTo(MAX_CONCURRENCY);
    assertThat(properties.execution().defaultPageSize()).isEqualTo(DEFAULT_PAGE_SIZE);
    assertThat(properties.execution().maxPageSize()).isEqualTo(MAX_PAGE_SIZE);
    assertThat(properties.execution().maxCellBytes()).isEqualTo(MAX_CELL_BYTES);
    assertThat(properties.execution().maxResultBytes()).isEqualTo(MAX_RESULT_BYTES);
    assertThat(properties.transaction().idleTimeout()).isEqualTo(Duration.ofMinutes(10));
    assertThat(properties.audit().directory()).isEqualTo("./audit");
    assertThat(properties.audit().retention()).isEqualTo(Duration.ofDays(30));

    assertThat(properties.dataSources()).hasSize(1);
    DbConsoleProperties.DataSourceProperties dataSource = properties.dataSources().get(0);
    assertThat(dataSource.id()).isEqualTo("mysql-work-note");
    assertThat(dataSource.displayName()).isEqualTo("MySQL 开发库");
    assertThat(dataSource.environment())
        .isEqualTo(DbConsoleProperties.DataSourceEnvironment.DEVELOPMENT);
    assertThat(dataSource.databaseType()).isEqualTo(DbConsoleProperties.DatabaseType.MYSQL);
    assertThat(dataSource.compatibilityMode())
        .isEqualTo(DbConsoleProperties.CompatibilityMode.NATIVE);
    assertThat(dataSource.jdbcUrl()).isEqualTo("jdbc:mysql://127.0.0.1:3306/work_note");
    assertThat(dataSource.username()).isEqualTo("root");
    assertThat(dataSource.credentialRef()).isEqualTo("DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD");
  }

  @Test
  @DisplayName("未配置数据源时绑定为空列表而不是 null")
  void bindsMissingDataSourcesToEmptyList() {
    Map<String, String> source = validSource();
    source.keySet().removeIf(key -> key.startsWith("db-console.data-sources"));
    Binder binder = new Binder(new MapConfigurationPropertySource(source));

    DbConsoleProperties properties =
        binder.bind("db-console", Bindable.of(DbConsoleProperties.class)).get();

    assertThat(properties.dataSources()).isEmpty();
  }

  @Test
  @DisplayName("重复的数据源标识在构造时被拒绝")
  void rejectsDuplicateDataSourceIds() {
    DbConsoleProperties.DataSourceProperties first = dataSource("mysql-work-note");
    DbConsoleProperties.DataSourceProperties second = dataSource("mysql-work-note");

    assertThatThrownBy(
            () ->
                new DbConsoleProperties(
                    login(),
                    new DbConsoleProperties.SessionProperties(Duration.ofHours(8)),
                    execution(),
                    new DbConsoleProperties.TransactionProperties(Duration.ofMinutes(10)),
                    audit(),
                    List.of(first, second)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique");
  }

  @Test
  @DisplayName("空白的凭据引用违反校验约束")
  void rejectsBlankCredentialRef() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var invalid = withCredentialRef(dataSource("mysql-work-note"), " ");

      var violations = factory.getValidator().validateProperty(invalid, "credentialRef");

      assertThat(violations).isNotEmpty();
    }
  }

  private static Map<String, String> validSource() {
    Map<String, String> source = new HashMap<>();
    source.put("db-console.login.username", "admin");
    source.put("db-console.login.password", "app-secret");
    source.put("db-console.session.timeout", "PT8H");
    source.put("db-console.execution.timeout", "PT30S");
    source.put("db-console.execution.max-sql-length", String.valueOf(MAX_SQL_LENGTH));
    source.put("db-console.execution.max-concurrency", String.valueOf(MAX_CONCURRENCY));
    source.put("db-console.execution.default-page-size", String.valueOf(DEFAULT_PAGE_SIZE));
    source.put("db-console.execution.max-page-size", String.valueOf(MAX_PAGE_SIZE));
    source.put("db-console.execution.max-cell-bytes", String.valueOf(MAX_CELL_BYTES));
    source.put("db-console.execution.max-result-bytes", String.valueOf(MAX_RESULT_BYTES));
    source.put("db-console.transaction.idle-timeout", "PT10M");
    source.put("db-console.audit.directory", "./audit");
    source.put("db-console.audit.retention", "P30D");
    source.put("db-console.data-sources[0].id", "mysql-work-note");
    source.put("db-console.data-sources[0].display-name", "MySQL 开发库");
    source.put("db-console.data-sources[0].environment", "DEVELOPMENT");
    source.put("db-console.data-sources[0].database-type", "MYSQL");
    source.put("db-console.data-sources[0].compatibility-mode", "NATIVE");
    source.put("db-console.data-sources[0].jdbc-url", "jdbc:mysql://127.0.0.1:3306/work_note");
    source.put("db-console.data-sources[0].username", "root");
    source.put("db-console.data-sources[0].credential-ref", "DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD");
    return source;
  }

  private static DbConsoleProperties.DataSourceProperties dataSource(String id) {
    return new DbConsoleProperties.DataSourceProperties(
        id,
        "MySQL 开发库",
        DbConsoleProperties.DataSourceEnvironment.DEVELOPMENT,
        DbConsoleProperties.DatabaseType.MYSQL,
        DbConsoleProperties.CompatibilityMode.NATIVE,
        "jdbc:mysql://127.0.0.1:3306/work_note",
        "root",
        "DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD");
  }

  private static DbConsoleProperties.DataSourceProperties withCredentialRef(
      DbConsoleProperties.DataSourceProperties source, String credentialRef) {
    return new DbConsoleProperties.DataSourceProperties(
        source.id(),
        source.displayName(),
        source.environment(),
        source.databaseType(),
        source.compatibilityMode(),
        source.jdbcUrl(),
        source.username(),
        credentialRef);
  }

  private static DbConsoleProperties.LoginProperties login() {
    return new DbConsoleProperties.LoginProperties("admin", "app-secret");
  }

  private static DbConsoleProperties.ExecutionProperties execution() {
    return new DbConsoleProperties.ExecutionProperties(
        EXECUTION_TIMEOUT,
        MAX_SQL_LENGTH,
        MAX_CONCURRENCY,
        DEFAULT_PAGE_SIZE,
        MAX_PAGE_SIZE,
        MAX_CELL_BYTES,
        MAX_RESULT_BYTES);
  }

  private static DbConsoleProperties.AuditProperties audit() {
    return new DbConsoleProperties.AuditProperties("./audit", Duration.ofDays(30));
  }
}
