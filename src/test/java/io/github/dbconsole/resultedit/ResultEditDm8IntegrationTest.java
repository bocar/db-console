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
 * F-17 DM8 实库验收（PRODUCTION 数据源）：大小写不敏感标记、生产写确认回路
 * （语句预览 + 凭据 → 携凭据提交生效）。需要环境变量 DB_CONSOLE_DM8_NAVIC_PASSWORD；
 * 破坏性操作仅落在 dbconsole_test_ 前缀表上（隔离纪律），结束清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(io.github.dbconsole.web.EnvCredentialSource.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
    properties = {
      "db-console.data-sources[0].id=dm8-navic",
      "db-console.data-sources[0].display-name=达梦 NAVIC 库",
      "db-console.data-sources[0].environment=PRODUCTION",
      "db-console.data-sources[0].database-type=DM8",
      "db-console.data-sources[0].compatibility-mode=NATIVE",
      "db-console.data-sources[0].jdbc-url=jdbc:dm://47.98.61.214:5236?schema=NAVIC",
      "db-console.data-sources[0].username=navic",
      "db-console.data-sources[0].credential-ref=DB_CONSOLE_DM8_NAVIC_PASSWORD",
    })
class ResultEditDm8IntegrationTest {

  private static final String TABLE = "dbconsole_test_f17";
  private static final String JDBC_URL = "jdbc:dm://47.98.61.214:5236?schema=NAVIC";

  @Autowired private MockMvc mockMvc;

  private Cookie session;

  private String dm8Password() {
    return System.getenv("DB_CONSOLE_DM8_NAVIC_PASSWORD");
  }

  @BeforeAll
  void requireDm8AndPrepareTable() throws Exception {
    Assumptions.assumeTrue(
        dm8Password() != null, "缺少 DB_CONSOLE_DM8_NAVIC_PASSWORD，跳过 F-17 DM8 验收");
    try (Connection connection = dm8Connection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE " + TABLE);
      statement.execute(
          "CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, name VARCHAR(64) NOT NULL)");
      statement.execute("INSERT INTO " + TABLE + " VALUES (1, 'a')");
    } catch (SQLException dropMissing) {
      // 首次运行表不存在时 DROP 报错，忽略后重试建表
      try (Connection connection = dm8Connection();
          Statement statement = connection.createStatement()) {
        statement.execute(
            "CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, name VARCHAR(64) NOT NULL)");
        statement.execute("INSERT INTO " + TABLE + " VALUES (1, 'a')");
      }
    }
  }

  @AfterAll
  void dropTable() throws Exception {
    if (dm8Password() == null) {
      return;
    }
    try (Connection connection = dm8Connection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE " + TABLE);
    }
  }

  @BeforeEach
  void login() throws Exception {
    session = AuthTestSupport.loginSession(mockMvc);
  }

  @Test
  void productionSaveRequiresConfirmationThenApplies() throws Exception {
    // 未加引号的小写查询在 DM8（大写存储）上仍可标记：大小写不敏感映射
    mockMvc
        .perform(
            post("/api/sql/execute")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceId\":\"dm8-navic\",\"sql\":%s}"
                        .formatted(jsonText("SELECT * FROM " + TABLE))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("QUERY"))
        .andExpect(jsonPath("$.editable").exists())
        .andExpect(jsonPath("$.editable.primaryKeys[0]").value("ID"));

    String body = updateBody();
    MvcResult confirmation =
        mockMvc
            .perform(save(body, null))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("RESULT_EDIT_CONFIRMATION"))
            .andExpect(jsonPath("$.statements[0].reason").value("PRODUCTION_WRITE"))
            .andExpect(
                jsonPath("$.statements[0].sql")
                    .value(org.hamcrest.Matchers.containsString("\"DBCONSOLE_TEST_F17\"")))
            .andReturn();
    String token =
        JsonPath.read(confirmation.getResponse().getContentAsString(), "$.statements[0].confirmationToken");

    mockMvc
        .perform(save(body, List.of(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("RESULT_EDIT_APPLIED"));

    try (Connection connection = dm8Connection();
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery("SELECT name FROM " + TABLE + " WHERE id = 1")) {
      assertThat(row.next()).isTrue();
      assertThat(row.getString("name")).isEqualTo("a2");
    }
  }

  private String updateBody() {
    return """
        {
          "sourceId": "dm8-navic",
          "tableParts": ["%s"],
          "deletes": [],
          "updates": [
            {"key": {"id": 1}, "set": {"name": "a2"}}
          ],
          "inserts": []
        }
        """.formatted(TABLE);
  }

  private org.springframework.test.web.servlet.RequestBuilder save(
      String body, List<String> tokens) {
    String payload =
        tokens == null
            ? body
            : body.replaceFirst(
                "\\}$", ", \"confirmationTokens\": [\"" + String.join("\", \"", tokens) + "\"]}");
    return post("/api/sql/result-edit/save")
        .cookie(session)
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload);
  }

  private Connection dm8Connection() throws SQLException {
    return DriverManager.getConnection(JDBC_URL, "navic", dm8Password());
  }
}
