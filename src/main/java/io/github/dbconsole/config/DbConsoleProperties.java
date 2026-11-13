package io.github.dbconsole.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "db-console")
public record DbConsoleProperties(
    @NotNull @Valid LoginProperties login,
    @NotNull @Valid SessionProperties session,
    @NotNull @Valid ExecutionProperties execution,
    @NotNull @Valid TransactionProperties transaction,
    @NotNull @Valid AuditProperties audit,
    @Valid List<DataSourceProperties> dataSources) {

  public DbConsoleProperties {
    dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
    long distinctIds = dataSources.stream().map(DataSourceProperties::id).distinct().count();
    if (distinctIds != dataSources.size()) {
      throw new IllegalArgumentException("Datasource identifiers must be unique");
    }
  }

  public record LoginProperties(@NotBlank String username, @NotBlank String password) {}

  public record SessionProperties(@NotNull Duration timeout) {}

  public record ExecutionProperties(
      @NotNull Duration timeout,
      @Positive int maxSqlLength,
      @Positive int maxConcurrency,
      @Positive int defaultPageSize,
      @Positive int maxPageSize,
      @Positive long maxCellBytes,
      @Positive long maxResultBytes) {

    public ExecutionProperties {
      if (defaultPageSize > maxPageSize) {
        throw new IllegalArgumentException("default-page-size must not exceed max-page-size");
      }
    }
  }

  public record TransactionProperties(@NotNull Duration idleTimeout) {}

  /** 零或负保留期会在写入当天即删当日审计文件，构造期直接拒绝。 */
  public record AuditProperties(@NotBlank String directory, @NotNull Duration retention) {

    public AuditProperties {
      if (retention == null || retention.compareTo(Duration.ZERO) <= 0) {
        throw new IllegalArgumentException("audit retention must be positive");
      }
    }
  }

  public record DataSourceProperties(
      @NotBlank String id,
      @NotBlank String displayName,
      @NotNull DataSourceEnvironment environment,
      @NotNull DatabaseType databaseType,
      @NotNull CompatibilityMode compatibilityMode,
      @NotBlank String jdbcUrl,
      @NotBlank String username,
      @NotBlank String credentialRef) {}

  public enum DataSourceEnvironment {
    DEVELOPMENT,
    TEST,
    PRODUCTION
  }

  public enum DatabaseType {
    MYSQL,
    DM8,
    ORACLE,
    SQL_SERVER,
    TIDB,
    OCEANBASE
  }

  public enum CompatibilityMode {
    NATIVE,
    MYSQL,
    ORACLE
  }
}
