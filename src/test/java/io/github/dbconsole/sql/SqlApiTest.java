package io.github.dbconsole.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.dbconsole.jdbc.CredentialSource;
import com.jayway.jsonpath.JsonPath;
import io.github.dbconsole.web.AuthTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * 执行接口规则测试：全部走拒绝或确认签发路径，不触数据库。
 * 数据源 jdbc-url 指向保留地址，任何连接尝试都会失败——从而证明这些路径未连库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "db-console.data-sources[0].id=mysql-work-note",
      "db-console.data-sources[0].display-name=MySQL 工作笔记库",
      "db-console.data-sources[0].environment=DEVELOPMENT",
      "db-console.data-sources[0].database-type=MYSQL",
      "db-console.data-sources[0].compatibility-mode=NATIVE",
      "db-console.data-sources[0].jdbc-url=jdbc:mysql://127.0.0.1:1/never",
      "db-console.data-sources[0].username=root",
      "db-console.data-sources[0].credential-ref=DB_CONSOLE_TEST_RULE",
      "db-console.data-sources[1].id=prod-source",
      "db-console.data-sources[1].display-name=生产库",
      "db-console.data-sources[1].environment=PRODUCTION",
      "db-console.data-sources[1].database-type=MYSQL",
      "db-console.data-sources[1].compatibility-mode=NATIVE",
      "db-console.data-sources[1].jdbc-url=jdbc:mysql://127.0.0.1:1/never",
      "db-console.data-sources[1].username=root",
      "db-console.data-sources[1].credential-ref=DB_CONSOLE_TEST_RULE",
    })
class SqlApiTest {

  @Autowired private MockMvc mockMvc;

  private Cookie session;

  @TestConfiguration
  static class FakeCredentials {
    @Bean
    @Primary
    CredentialSource testCredentialSource() {
      return ref -> Map.of("DB_CONSOLE_TEST_RULE", "irrelevant").get(ref);
    }
  }

  @BeforeEach
  void login() throws Exception {
    session = AuthTestSupport.loginSession(mockMvc);
  }

  private MvcResult execute(String sql, String confirmationToken) throws Exception {
    return execute("mysql-work-note", sql, confirmationToken);
  }

  private MvcResult execute(String sourceId, String sql, String confirmationToken)
      throws Exception {
    String body =
        confirmationToken == null
            ? "{\"sourceId\":\"%s\",\"sql\":%s}".formatted(sourceId, jsonText(sql))
            : "{\"sourceId\":\"%s\",\"sql\":%s,\"confirmationToken\":\"%s\"}"
                .formatted(sourceId, jsonText(sql), confirmationToken);
    return mockMvc
        .perform(
            post("/api/sql/execute")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  private static String jsonText(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  @Test
  @DisplayName("空白与纯注释输入返回 422 INVALID_SQL")
  void rejectsBlankInput() throws Exception {
    mockMvc.perform(post("/api/sql/execute").cookie(session).contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"   \"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("INVALID_SQL"));
  }

  @Test
  @DisplayName("多语句输入返回 422 INVALID_SQL")
  void rejectsMultipleStatements() throws Exception {
    mockMvc.perform(post("/api/sql/execute").cookie(session).contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"SELECT 1; SELECT 2\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("INVALID_SQL"));
  }

  @Test
  @DisplayName("超出 F-05 范围的操作返回 422 OUT_OF_SCOPE")
  void rejectsOutOfScopeStatement() throws Exception {
    mockMvc.perform(post("/api/sql/execute").cookie(session).contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"TRUNCATE TABLE t\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
  }

  @Test
  @DisplayName("超长 SQL 返回 422 SQL_TOO_LONG")
  void rejectsOverlongSql() throws Exception {
    String longSql = "SELECT '" + "x".repeat(21000) + "'";
    mockMvc.perform(post("/api/sql/execute").cookie(session).contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"%s\"}".formatted(longSql)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("SQL_TOO_LONG"));
  }

  @Test
  @DisplayName("未知数据源返回 404")
  void rejectsUnknownSource() throws Exception {
    mockMvc.perform(post("/api/sql/execute").cookie(session).contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"ghost\",\"sql\":\"SELECT 1\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("开发环境无 WHERE 更新返回确认要求（未触库）")
  void returnsConfirmationForMissingWhereWithoutTouchingDatabase() throws Exception {
    mockMvc
        .perform(post("/api/sql/execute").cookie(session).contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"UPDATE dbconsole_test_t SET name = 'x'\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("CONFIRMATION_REQUIRED"))
        .andExpect(jsonPath("$.reason").value("MISSING_WHERE"))
        .andExpect(jsonPath("$.confirmationToken").isNotEmpty());
  }

  @Test
  @DisplayName("携带失效令牌（SQL 已变化）重新签发确认要求而非执行")
  void invalidTokenReissuesConfirmation() throws Exception {
    MvcResult first = execute("UPDATE dbconsole_test_t SET name = 'x'", null);
    String staleToken = JsonPath.read(first.getResponse().getContentAsString(), "$.confirmationToken");

    mockMvc
        .perform(post("/api/sql/execute").cookie(session).contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"sourceId\":\"mysql-work-note\",\"sql\":\"UPDATE dbconsole_test_t SET name = 'y'\",\"confirmationToken\":\"%s\"}"
                    .formatted(staleToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("CONFIRMATION_REQUIRED"));
  }

  @Test
  @DisplayName("生产环境数据增删改一律需确认（含 CTE 写入），原因标注 PRODUCTION_WRITE")
  void productionWritesAlwaysRequireConfirmation() throws Exception {
    MvcResult insert = execute("prod-source", "INSERT INTO t VALUES (1)", null);
    mockMvc
        .perform(
            post("/api/sql/execute")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceId\":\"prod-source\",\"sql\":\"INSERT INTO t VALUES (1)\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("CONFIRMATION_REQUIRED"))
        .andExpect(jsonPath("$.reason").value("PRODUCTION_WRITE"));

    MvcResult cteWrite =
        execute("prod-source", "WITH c AS (SELECT 1) DELETE FROM t WHERE x IN (SELECT 1)", null);
    String cteBody = cteWrite.getResponse().getContentAsString();
    assertThat(cteBody).contains("\"kind\":\"CONFIRMATION_REQUIRED\"");
    assertThat(cteBody).contains("\"reason\":\"PRODUCTION_WRITE\"");
  }

  @Test
  @DisplayName("确认令牌换数据源即失效（F-09 数据源绑定）")
  void confirmationTokenRejectedAcrossDataSources() throws Exception {
    MvcResult issued = execute("UPDATE dbconsole_test_t SET name = 'x'", null);
    String token = JsonPath.read(issued.getResponse().getContentAsString(), "$.confirmationToken");

    mockMvc
        .perform(
            post("/api/sql/execute")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceId\":\"prod-source\",\"sql\":\"UPDATE dbconsole_test_t SET name = 'x'\",\"confirmationToken\":\"%s\"}"
                        .formatted(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("CONFIRMATION_REQUIRED"));
  }

  @Test
  @DisplayName("另一会话不能复用本会话的确认令牌（F-09 会话绑定）")
  void confirmationTokenRejectedAcrossSessions() throws Exception {
    MvcResult first = execute("UPDATE dbconsole_test_t SET name = 'x'", null);
    String token = JsonPath.read(first.getResponse().getContentAsString(), "$.confirmationToken");
    Cookie otherSession = AuthTestSupport.loginSession(mockMvc);

    mockMvc
        .perform(
            post("/api/sql/execute")
                .cookie(otherSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceId\":\"mysql-work-note\",\"sql\":\"UPDATE dbconsole_test_t SET name = 'x'\",\"confirmationToken\":\"%s\"}"
                        .formatted(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("CONFIRMATION_REQUIRED"));
  }

  @Test
  @DisplayName("未登录执行请求返回 401")
  void unauthenticatedExecutionRejected() throws Exception {
    mockMvc
        .perform(
            post("/api/sql/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"SELECT 1\"}"))
        .andExpect(status().isUnauthorized());
  }
}
