package io.github.dbconsole.resultedit;

import static io.github.dbconsole.web.JsonText.jsonText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.dbconsole.web.AuthTestSupport;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * F-17 MySQL 实库验收：可编辑结果标记、开发环境保存 round-trip（删除/更新/SET NULL/新增）、
 * 行不存在整体回滚。需要环境变量 DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD；
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
      "db-console.data-sources[0].jdbc-url=jdbc:mysql://47.98.61.214:3306/zhstudy",
      "db-console.data-sources[0].username=zhuhui",
      "db-console.data-sources[0].credential-ref=DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD",
    })
class ResultEditMySqlIntegrationTest {

  private static final String TABLE = "dbconsole_test_f17";

  @Autowired private MockMvc mockMvc;

  private Cookie session;

  private String adminPassword() {
    return System.getenv("DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD");
  }

  @BeforeAll
  void requireMysqlAndPrepareTable() throws Exception {
    Assumptions.assumeTrue(
        adminPassword() != null, "缺少 DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD，跳过 F-17 MySQL 验收");
    try (Connection connection = mysqlConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS " + TABLE);
      statement.execute(
          "CREATE TABLE " + TABLE
              + " (id INT PRIMARY KEY, name VARCHAR(64) NOT NULL, note VARCHAR(128), amount INT)");
      statement.execute(
          "INSERT INTO " + TABLE + " VALUES (1, 'a', 'x', 10), (2, 'b', NULL, 20)");
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

  @Test
  void marksSimpleSelectEditableAndSavesRoundTrip() throws Exception {
    mockMvc
        .perform(
            post("/api/sql/execute")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceId\":\"mysql-work-note\",\"sql\":%s}"
                        .formatted(jsonText("SELECT * FROM " + TABLE))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("QUERY"))
        .andExpect(jsonPath("$.editable.tableParts[0]").value(TABLE))
        .andExpect(jsonPath("$.editable.primaryKeys[0]").value("id"))
        .andExpect(jsonPath("$.editable.columns[0].primaryKey").value(true));

    // JOIN 不具备资格：整体只读
    mockMvc
        .perform(
            post("/api/sql/execute")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceId\":\"mysql-work-note\",\"sql\":%s}"
                        .formatted(
                            jsonText("SELECT t.id, u.id FROM " + TABLE + " t JOIN " + TABLE
                                + " u ON t.id = u.id"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.editable").doesNotExist());

    // F-17 裁决：任何环境的保存都先经一次汇总确认（DEV 无风险标注，凭据仍逐条校验）
    MvcResult confirmation =
        mockMvc
            .perform(saveRequest(deletesUpdatesInsertsBody(), null))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("RESULT_EDIT_CONFIRMATION"))
            .andExpect(jsonPath("$.statements[0].section").value("DELETE"))
            .andExpect(jsonPath("$.statements[1].section").value("UPDATE"))
            .andExpect(jsonPath("$.statements[2].section").value("INSERT"))
            .andExpect(jsonPath("$.statements[0].reason").doesNotExist())
            .andReturn();
    List<String> tokens =
        JsonPath.read(
            confirmation.getResponse().getContentAsString(),
            "$.statements[*].confirmationToken");

    mockMvc
        .perform(saveRequest(deletesUpdatesInsertsBody(), tokens))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("RESULT_EDIT_APPLIED"));

    try (Connection connection = mysqlConnection();
        Statement statement = connection.createStatement()) {
      assertThat(count(statement, "SELECT COUNT(*) FROM " + TABLE + " WHERE id = 1"))
          .isZero();
      try (ResultSet row =
          statement.executeQuery("SELECT name, note, amount FROM " + TABLE + " WHERE id = 2")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getString("name")).isEqualTo("b2");
        assertThat(row.getObject("note")).isNull();
        assertThat(row.getInt("amount")).isEqualTo(20);
      }
      assertThat(count(statement, "SELECT COUNT(*) FROM " + TABLE + " WHERE id = 3"))
          .isEqualTo(1);
    }
  }

  @Test
  void missingRowFailsWholeBatchWithRollback() throws Exception {
    String body =
        """
        {
          "sourceId": "mysql-work-note",
          "tableParts": ["%s"],
          "deletes": [],
          "updates": [
            {"key": {"id": 999}, "set": {"name": "ghost"}}
          ],
          "inserts": [
            {"values": {"id": 5, "name": "should-rollback"}}
          ]
        }
        """.formatted(TABLE);
    MvcResult confirmation =
        mockMvc
            .perform(saveRequest(body, null))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("RESULT_EDIT_CONFIRMATION"))
            .andReturn();
    List<String> tokens =
        JsonPath.read(
            confirmation.getResponse().getContentAsString(),
            "$.statements[*].confirmationToken");

    mockMvc
        .perform(saveRequest(body, tokens))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("RESULT_EDIT_FAILED"))
        .andExpect(jsonPath("$.section").value("UPDATE"))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("行不存在")));

    try (Connection connection = mysqlConnection();
        Statement statement = connection.createStatement()) {
      assertThat(count(statement, "SELECT COUNT(*) FROM " + TABLE + " WHERE id = 5")).isZero();
    }
  }

  private org.springframework.test.web.servlet.RequestBuilder saveRequest(
      String body, java.util.List<String> tokens) {
    String payload =
        tokens == null
            ? body
            : body.replaceFirst(
                "\\}$",
                ", \"confirmationTokens\": [\"" + String.join("\", \"", tokens) + "\"]}");
    return post("/api/sql/result-edit/save")
        .cookie(session)
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload);
  }

  private String deletesUpdatesInsertsBody() {
    return """
        {
          "sourceId": "mysql-work-note",
          "tableParts": ["%s"],
          "deletes": [{"key": {"id": 1}}],
          "updates": [
            {"key": {"id": 2}, "set": {"name": "b2", "note": null}}
          ],
          "inserts": [
            {"values": {"id": 3, "name": "c", "amount": 30}}
          ]
        }
        """.formatted(TABLE);
  }

  private static int count(Statement statement, String sql) throws SQLException {
    try (ResultSet resultSet = statement.executeQuery(sql)) {
      resultSet.next();
      return resultSet.getInt(1);
    }
  }

  private Connection mysqlConnection() throws SQLException {
    return DriverManager.getConnection(
        "jdbc:mysql://47.98.61.214:3306/zhstudy", "zhuhui", adminPassword());
  }
}
