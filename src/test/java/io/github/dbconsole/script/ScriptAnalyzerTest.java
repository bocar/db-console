package io.github.dbconsole.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.sql.ConfirmationService;
import io.github.dbconsole.transaction.TransactionManager;
import io.github.dbconsole.transaction.TransactionStateException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 脚本预校验测试（F-13）：文件约束、拆分语义、逐条范围校验、确认逐语句签发与事务互斥。
 * 拆分语义的完整矩阵（字符串/注释中的分号）见方言层 SqlStatementAnalyzerTest。
 */
class ScriptAnalyzerTest {

  private static final String SESSION_TOKEN = "session-token";
  private static final String DEV_SOURCE = "mysql-dev";
  private static final String PROD_SOURCE = "mysql-prod";

  private final TransactionManager transactions = mock(TransactionManager.class);

  private ConfirmationService confirmations;
  private ScriptAnalyzer analyzer;

  @BeforeEach
  void setUp() {
    confirmations = new ConfirmationService();
    DbConsoleProperties properties = properties(
        new DbConsoleProperties.ScriptProperties(512L, 3));
    DataSourceRegistry registry = new DataSourceRegistry(properties, ref -> "db-secret");
    analyzer =
        new ScriptAnalyzer(registry, confirmations, transactions, new ScriptRunStore(), properties);
  }

  @Test
  @DisplayName("合法脚本拆分为逐条计划：字符串内分号不切分、类型按语句判定")
  void validScriptProducesOrderedPlans() {
    String script =
        "-- 初始化\n"
        + "UPDATE t SET name = 'a;b' WHERE id = 1;\n"
        + "SELECT 1;\n"
        + "CREATE TABLE t2 (id INT);\n";

    ScriptAnalyzeResponse response = analyzer.analyze(SESSION_TOKEN, upload(DEV_SOURCE, "init.sql", script));

    assertThat(response.valid()).isTrue();
    assertThat(response.errors()).isEmpty();
    // 整份通过即签发运行标识，供汇总历史端点取回全文
    assertThat(response.scriptId()).isNotBlank();
    assertThat(response.statements()).extracting(ScriptStatementPlan::kind)
        .containsExactly("UPDATE", "SELECT", "CREATE_TABLE");
    assertThat(response.statements()).extracting(ScriptStatementPlan::index)
        .containsExactly(1, 2, 3);
    assertThat(response.statements().get(0).sql()).contains("'a;b'");
  }

  @Test
  @DisplayName("空白与纯注释片段不成语句，前置注释保留在语句文本中")
  void commentOnlyFragmentsAreSkippedAndLeadingCommentsKept() {
    String script = ";\n-- 只有两行注释\n\nSELECT 1;\n-- 尾注\n";

    ScriptAnalyzeResponse response = analyzer.analyze(SESSION_TOKEN, upload(DEV_SOURCE, "c.sql", script));

    assertThat(response.valid()).isTrue();
    assertThat(response.statements()).hasSize(1);
    assertThat(response.statements().get(0).sql()).startsWith("-- 只有两行注释");
  }

  @Test
  @DisplayName("UTF-8 BOM 被剥离后正常拆分")
  void bomIsStrippedBeforeSplit() {
    String script = "\uFEFFSELECT 1;";

    ScriptAnalyzeResponse response = analyzer.analyze(SESSION_TOKEN, upload(DEV_SOURCE, "bom.sql", script));

    assertThat(response.valid()).isTrue();
    assertThat(response.statements()).hasSize(1);
    assertThat(response.statements().get(0).sql()).doesNotContain("\uFEFF");
  }

  @Test
  @DisplayName("非 .sql/.txt 扩展名被拒绝")
  void unsupportedExtensionIsRejected() {
    assertThatThrownBy(
            () -> analyzer.analyze(SESSION_TOKEN, upload(DEV_SOURCE, "script.sh", "SELECT 1;")))
        .isInstanceOfSatisfying(
            io.github.dbconsole.sql.SqlRejectException.class,
            rejected -> assertThat(rejected.code()).isEqualTo("SCRIPT_FILE_TYPE"))
        .hasMessageContaining(".sql");
  }

  @Test
  @DisplayName("超过文件大小上限整份拒绝")
  void oversizeScriptIsRejected() {
    String script = "SELECT '" + "x".repeat(600) + "';";

    assertThatThrownBy(
            () -> analyzer.analyze(SESSION_TOKEN, upload(DEV_SOURCE, "big.sql", script)))
        .isInstanceOfSatisfying(
            io.github.dbconsole.sql.SqlRejectException.class,
            rejected -> assertThat(rejected.code()).isEqualTo("SCRIPT_TOO_LARGE"));
  }

  @Test
  @DisplayName("超过语句数上限整份拒绝")
  void tooManyStatementsIsRejected() {
    String script = "SELECT 1;\nSELECT 2;\nSELECT 3;\nSELECT 4;\n";

    assertThatThrownBy(
            () -> analyzer.analyze(SESSION_TOKEN, upload(DEV_SOURCE, "many.sql", script)))
        .isInstanceOfSatisfying(
            io.github.dbconsole.sql.SqlRejectException.class,
            rejected -> assertThat(rejected.code()).isEqualTo("SCRIPT_TOO_MANY_STATEMENTS"))
        .hasMessageContaining("3");
  }

  @Test
  @DisplayName("范围外语句逐条报错且整体无效，其余语句仍列出")
  void outOfScopeStatementMarksWholeScriptInvalid() {
    String script = "SELECT 1;\nTRUNCATE TABLE t;\n";

    ScriptAnalyzeResponse response = analyzer.analyze(SESSION_TOKEN, upload(DEV_SOURCE, "bad.sql", script));

    assertThat(response.valid()).isFalse();
    assertThat(response.statements()).hasSize(1);
    assertThat(response.errors()).hasSize(1);
    assertThat(response.errors().get(0).index()).isEqualTo(2);
    assertThat(response.errors().get(0).code()).isEqualTo("OUT_OF_SCOPE");
    assertThat(response.errors().get(0).message()).contains("第 2 条");
  }

  @Test
  @DisplayName("整份脚本存在失败语句时不签发任何确认凭据（整份不执行的服务端收口）")
  void invalidScriptIssuesNoConfirmationTokens() {
    String script = "UPDATE t SET name = 'a';\nTRUNCATE TABLE t;\n";

    ScriptAnalyzeResponse response = analyzer.analyze(SESSION_TOKEN, upload(DEV_SOURCE, "bad.sql", script));

    assertThat(response.valid()).isFalse();
    assertThat(response.scriptId()).isNull();
    assertThat(response.statements()).hasSize(1);
    assertThat(response.statements().get(0).confirmationReason()).isEqualTo("MISSING_WHERE");
    assertThat(response.statements().get(0).confirmationToken()).isNull();
  }

  @Test
  @DisplayName("存储过程体内的边界分号被切碎，逐条归入超范围拒绝")
  void procedureBodyIsRejectedAsOutOfScope() {
    String script = "CREATE PROCEDURE p() BEGIN SELECT 1; END;\n";

    ScriptAnalyzeResponse response = analyzer.analyze(SESSION_TOKEN, upload(DEV_SOURCE, "proc.sql", script));

    assertThat(response.valid()).isFalse();
    assertThat(response.errors()).isNotEmpty();
    assertThat(response.errors()).allMatch(error -> error.code().equals("OUT_OF_SCOPE"));
  }

  @Test
  @DisplayName("超过单语句长度上限的语句逐条报错（SQL_TOO_LONG）")
  void oversizeStatementIsReportedPerStatement() {
    String script = "SELECT '" + "x".repeat(300) + "';";

    ScriptAnalyzeResponse response = analyzer.analyze(SESSION_TOKEN, upload(DEV_SOURCE, "long.sql", script));

    assertThat(response.valid()).isFalse();
    assertThat(response.errors().get(0).code()).isEqualTo("SQL_TOO_LONG");
  }

  @Test
  @DisplayName("开发库：无 WHERE 的 UPDATE 逐条签发 MISSING_WHERE 凭据，SELECT 不签发")
  void devScriptIssuesMissingWhereTokenOnly() {
    String script = "UPDATE t SET name = 'a';\nSELECT 1;\n";

    ScriptAnalyzeResponse response = analyzer.analyze(SESSION_TOKEN, upload(DEV_SOURCE, "mix.sql", script));

    ScriptStatementPlan update = response.statements().get(0);
    ScriptStatementPlan select = response.statements().get(1);
    assertThat(update.confirmationReason()).isEqualTo("MISSING_WHERE");
    assertThat(update.confirmationToken()).isNotBlank();
    assertThat(select.confirmationReason()).isNull();
    assertThat(select.confirmationToken()).isNull();
    // 签发的凭据经公开确认门回环校验通过（绑定会话 + 数据源 + 语句文本）
    assertThat(
            confirmations.check(
                SESSION_TOKEN,
                DEV_SOURCE,
                io.github.dbconsole.config.DbConsoleProperties.DataSourceEnvironment.DEVELOPMENT,
                new io.github.dbconsole.dialect.SqlStatement(
                    io.github.dbconsole.dialect.StatementKind.UPDATE, false),
                update.sql(),
                update.confirmationToken()))
        .isInstanceOf(ConfirmationService.Passed.class);
  }

  @Test
  @DisplayName("生产库：写入与隐式提交 DDL 均签发凭据且原因正确")
  void prodScriptIssuesTokensForWritesAndDdl() {
    String script = "INSERT INTO t VALUES (1);\nDROP TABLE t2;\n";

    ScriptAnalyzeResponse response = analyzer.analyze(SESSION_TOKEN, upload(PROD_SOURCE, "prod.sql", script));

    assertThat(response.statements().get(0).confirmationReason()).isEqualTo("PRODUCTION_WRITE");
    assertThat(response.statements().get(0).confirmationToken()).isNotBlank();
    assertThat(response.statements().get(1).confirmationReason()).isEqualTo("IMPLICIT_COMMIT_DDL");
    assertThat(response.statements().get(1).implicitCommitDdl()).isTrue();
    assertThat(response.statements().get(1).confirmationToken())
        .isNotEqualTo(response.statements().get(0).confirmationToken());
  }

  @Test
  @DisplayName("存在未提交手动事务时预校验前置拒绝（F-16 互斥）")
  void openTransactionRejectsAnalyze() {
    doThrow(
            new TransactionStateException(
                TransactionManager.CODE_TRANSACTION_OPEN,
                "脚本执行使用自动提交连接，请先提交或回滚当前事务"))
        .when(transactions)
        .requireScriptAutoCommit(SESSION_TOKEN, DEV_SOURCE);

    assertThatThrownBy(
            () -> analyzer.analyze(SESSION_TOKEN, upload(DEV_SOURCE, "x.sql", "SELECT 1;")))
        .isInstanceOf(TransactionStateException.class)
        .hasMessageContaining("请先提交或回滚当前事务");
  }

  private static ScriptUploadRequest upload(String sourceId, String filename, String content) {
    return new ScriptUploadRequest(sourceId, filename, content);
  }

  private static DbConsoleProperties properties(DbConsoleProperties.ScriptProperties script) {
    DbConsoleProperties.DataSourceProperties dev =
        new DbConsoleProperties.DataSourceProperties(
            DEV_SOURCE,
            "开发库",
            DbConsoleProperties.DataSourceEnvironment.DEVELOPMENT,
            DbConsoleProperties.DatabaseType.MYSQL,
            DbConsoleProperties.CompatibilityMode.NATIVE,
            "jdbc:mysql://127.0.0.1:1/never",
            "db-user",
            "DB_CREDENTIAL");
    DbConsoleProperties.DataSourceProperties prod =
        new DbConsoleProperties.DataSourceProperties(
            PROD_SOURCE,
            "生产库",
            DbConsoleProperties.DataSourceEnvironment.PRODUCTION,
            DbConsoleProperties.DatabaseType.MYSQL,
            DbConsoleProperties.CompatibilityMode.NATIVE,
            "jdbc:mysql://127.0.0.1:1/never",
            "db-user",
            "DB_CREDENTIAL");
    return new DbConsoleProperties(
        new DbConsoleProperties.LoginProperties("admin", "test-secret"),
        new DbConsoleProperties.SessionProperties(Duration.ofHours(8)),
        new DbConsoleProperties.ExecutionProperties(
            Duration.ofSeconds(30), 200, 1, 100, 1_000, 65_536, 10_485_760),
        new DbConsoleProperties.TransactionProperties(Duration.ofMinutes(10)),
        new DbConsoleProperties.AuditProperties("./target/test-audit", Duration.ofDays(30)),
        script,
        null,
        List.of(dev, prod));
  }
}
