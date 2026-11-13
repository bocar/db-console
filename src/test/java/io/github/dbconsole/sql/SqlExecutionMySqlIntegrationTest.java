package io.github.dbconsole.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.dbconsole.jdbc.CredentialSource;
import io.github.dbconsole.web.AuthTestSupport;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 本地 MySQL 执行链路集成验收：需要环境变量 DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD，
 * 破坏性操作仅落在 dbconsole_test_ 前缀表上（隔离纪律），结束清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(io.github.dbconsole.web.EnvCredentialSource.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
    properties = {
      "db-console.data-sources[0].id=mysql-work-note",
      "db-console.data-sources[0].display-name=MySQL 工作笔记库",
      "db-console.data-sources[0].environment=DEVELOPMENT",
      "db-console.data-sources[0].database-type=MYSQL",
      "db-console.data-sources[0].compatibility-mode=NATIVE",
      "db-console.data-sources[0].jdbc-url=jdbc:mysql://localhost:3306/work_note",
      "db-console.data-sources[0].username=root",
      "db-console.data-sources[0].credential-ref=DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD",
    })
class SqlExecutionMySqlIntegrationTest {

  private static final String TABLE = "dbconsole_test_t05";

  @Autowired private MockMvc mockMvc;

  private Cookie session;


  private String adminPassword() {
    return System.getenv("DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD");
  }

  @BeforeAll
  void requireLocalMysqlAndPrepareTable() throws Exception {
    Assumptions.assumeTrue(
        adminPassword() != null, "缺少 DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD，跳过 MySQL 集成验收");
    try (Connection connection = mysqlConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS " + TABLE);
      statement.execute(
          "CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, name VARCHAR(64), amount DECIMAL(18,2))");
    }
  }

  @AfterAll
  void dropTable() throws Exception {
    if (adminPassword() == null) {
      return;
    }
    try (Connection connection = mysqlConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS " + TABLE);
    }
  }

  @BeforeEach
  void login() throws Exception {
    session = AuthTestSupport.loginSession(mockMvc);
  }

  private Connection mysqlConnection() throws SQLException {
    return DriverManager.getConnection(
        "jdbc:mysql://localhost:3306/work_note", "root", adminPassword());
  }

  private MvcResult execute(String sql) throws Exception {
    return mockMvc
        .perform(
            post("/api/sql/execute")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceId\":\"mysql-work-note\",\"sql\":%s}".formatted(jsonText(sql))))
        .andReturn();
  }

  private MvcResult executeWithToken(String sql) throws Exception {
    MvcResult confirmation = execute(sql);
    String token =
        com.jayway.jsonpath.JsonPath.read(
            confirmation.getResponse().getContentAsString(), "$.confirmationToken");
    return mockMvc
        .perform(
            post("/api/sql/execute")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceId\":\"mysql-work-note\",\"sql\":%s,\"confirmationToken\":\"%s\"}"
                        .formatted(jsonText(sql), token)))
        .andReturn();
  }

  private static String jsonText(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  private String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString();
  }

  @Test
  void selectScalarQueryReturnsColumnsAndRow() throws Exception {
    mockMvc
        .perform(post("/api/sql/execute").cookie(session).contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"SELECT 1 AS one\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("QUERY"))
        .andExpect(jsonPath("$.columns[0].name").value("one"))
        .andExpect(jsonPath("$.rows[0][0]").value(1));
  }

  @Test
  void insertUpdateDeleteRoundTripOnIsolatedTable() throws Exception {
    mockMvc
        .perform(post("/api/sql/execute").cookie(session).contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"sourceId\":\"mysql-work-note\",\"sql\":\"INSERT INTO %s VALUES (1, '甲', 12.34)\"}"
                    .formatted(TABLE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("UPDATE"))
        .andExpect(jsonPath("$.affectedRows").value(1))
        .andExpect(jsonPath("$.uncommitted").value(false));

    mockMvc
        .perform(post("/api/sql/execute").cookie(session).contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"sourceId\":\"mysql-work-note\",\"sql\":\"UPDATE %s SET name = '乙' WHERE id = 1\"}"
                    .formatted(TABLE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.affectedRows").value(1));

    mockMvc
        .perform(post("/api/sql/execute").cookie(session).contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"sourceId\":\"mysql-work-note\",\"sql\":\"SELECT name, amount FROM %s WHERE id = 1\"}"
                    .formatted(TABLE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0][0]").value("乙"))
        .andExpect(jsonPath("$.rows[0][1]").value("12.34"));

    mockMvc
        .perform(post("/api/sql/execute").cookie(session).contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"sourceId\":\"mysql-work-note\",\"sql\":\"DELETE FROM %s WHERE id = 1\"}"
                    .formatted(TABLE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.affectedRows").value(1));
  }

  @Test
  void missingWhereUpdateRequiresConfirmationThenExecutesWithToken() throws Exception {
    MvcResult first = execute("UPDATE " + TABLE + " SET name = 'x'");
    Object firstKind = com.jayway.jsonpath.JsonPath.read(body(first), "$.kind");
    assertThat(firstKind).isEqualTo("CONFIRMATION_REQUIRED");

    MvcResult second = executeWithToken("UPDATE " + TABLE + " SET name = 'x'");
    Object secondKind = com.jayway.jsonpath.JsonPath.read(body(second), "$.kind");
    Object secondAffected = com.jayway.jsonpath.JsonPath.read(body(second), "$.affectedRows");
    assertThat(secondKind).isEqualTo("UPDATE");
    assertThat(secondAffected).isEqualTo(0);
  }

  @Test
  void createAndDropTableAsImplicitCommitDdlRequireConfirmation() throws Exception {
    MvcResult create = execute("CREATE TABLE " + TABLE + "_ddl (id INT PRIMARY KEY)");
    Object createReason = com.jayway.jsonpath.JsonPath.read(body(create), "$.reason");
    assertThat(createReason).isEqualTo("IMPLICIT_COMMIT_DDL");

    MvcResult created = executeWithToken("CREATE TABLE " + TABLE + "_ddl (id INT PRIMARY KEY)");
    Object createdKind = com.jayway.jsonpath.JsonPath.read(body(created), "$.kind");
    Object createdAffected = com.jayway.jsonpath.JsonPath.read(body(created), "$.affectedRows");
    assertThat(createdKind).isEqualTo("UPDATE");
    assertThat(createdAffected).isNull();

    executeWithToken("DROP TABLE " + TABLE + "_ddl");
  }

  @Test
  void databaseErrorIsLocatableWithoutStackTrace() throws Exception {
    mockMvc
        .perform(post("/api/sql/execute").cookie(session).contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"sourceId\":\"mysql-work-note\",\"sql\":\"SELECT * FROM dbconsole_test_missing_table\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DATABASE_ERROR"))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("dbconsole_test_missing_table")));
  }

  @Test
  void zeroRowQueryKeepsColumns() throws Exception {
    mockMvc
        .perform(post("/api/sql/execute").cookie(session).contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"sourceId\":\"mysql-work-note\",\"sql\":\"SELECT id FROM %s WHERE id = -1\"}"
                    .formatted(TABLE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.columns[0].name").value("id"))
        .andExpect(jsonPath("$.rows.length()").value(0));
  }
}
