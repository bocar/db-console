package io.github.dbconsole.script;

import static io.github.dbconsole.web.JsonText.jsonText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.dbconsole.jdbc.CredentialSource;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * 脚本接口外部契约测试（F-13）：预校验响应形态、文件约束错误码、确认凭据签发与
 * 失败即重签不变式、汇总历史落点。全部走拒绝、确认签发或历史路径，不触数据库
 * （数据源 jdbc-url 指向保留地址，任何连接尝试都会失败）。
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
    })
class ScriptApiTest {

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

  @Test
  @DisplayName("预校验返回逐条计划：无 WHERE 的 UPDATE 签发 MISSING_WHERE 凭据")
  void analyzeReturnsPlansWithConfirmationTokens() throws Exception {
    MvcResult result = analyze("mysql-work-note", "migration.sql", "UPDATE t SET a = 1;\nSELECT 1;\n")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(true))
        .andExpect(jsonPath("$.filename").value("migration.sql"))
        .andExpect(jsonPath("$.scriptId").isNotEmpty())
        .andExpect(jsonPath("$.statements.length()").value(2))
        .andExpect(jsonPath("$.statements[0].kind").value("UPDATE"))
        .andExpect(jsonPath("$.statements[0].confirmationReason").value("MISSING_WHERE"))
        .andExpect(jsonPath("$.statements[1].confirmationToken").doesNotExist())
        .andReturn();

    String token = JsonPath.read(result.getResponse().getContentAsString(),
        "$.statements[0].confirmationToken");
    assertThat(token).isNotBlank();
  }

  @Test
  @DisplayName("预校验发现范围外语句时整体无效并逐条报告序号")
  void analyzeReportsOutOfScopePerStatement() throws Exception {
    analyze("mysql-work-note", "bad.sql", "SELECT 1;\nTRUNCATE TABLE t;\n")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(false))
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].index").value(2))
        .andExpect(jsonPath("$.errors[0].code").value("OUT_OF_SCOPE"));
  }

  @Test
  @DisplayName("整份脚本未通过时不为需确认语句签发凭据（整份不执行的服务端收口）")
  void analyzeWithholdsTokensWhenScriptInvalid() throws Exception {
    analyze("mysql-work-note", "bad.sql", "UPDATE t SET a = 1;\nTRUNCATE TABLE t;\n")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(false))
        .andExpect(jsonPath("$.statements[0].confirmationReason").value("MISSING_WHERE"))
        .andExpect(jsonPath("$.statements[0].confirmationToken").doesNotExist());
  }

  @Test
  @DisplayName("非 .sql/.txt 文件被拒绝且错误码明确")
  void analyzeRejectsUnsupportedFileType() throws Exception {
    analyze("mysql-work-note", "dump.sh", "SELECT 1;")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("SCRIPT_FILE_TYPE"));
  }

  @Test
  @DisplayName("脚本执行缺凭据时返回确认要求，凭据校验失败即重签（失败即重签不变式）")
  void scriptExecuteRequiresAndReissuesConfirmationTokens() throws Exception {
    MvcResult analyzed = analyze("mysql-work-note", "m.sql", "UPDATE t SET a = 1;\n").andReturn();
    String body = analyzed.getResponse().getContentAsString();
    String freshToken = JsonPath.read(body, "$.statements[0].confirmationToken");
    // 计划文本即执行文本：拆分后的语句不含末尾分号，凭据绑定逐字符一致
    String planSql = JsonPath.read(body, "$.statements[0].sql");
    assertThat(planSql).isEqualTo("UPDATE t SET a = 1");

    mockMvc.perform(post("/api/sql/script/execute").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"sql\":%s}".formatted(jsonText(planSql))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("CONFIRMATION_REQUIRED"))
        .andExpect(jsonPath("$.confirmationToken").isNotEmpty());

    mockMvc.perform(post("/api/sql/script/execute").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"sql\":%s,\"confirmationToken\":\"wrong-token\"}"
                .formatted(jsonText(planSql))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("CONFIRMATION_REQUIRED"))
        .andExpect(jsonPath("$.confirmationToken").value(freshToken));
  }

  @Test
  @DisplayName("汇总历史按 scriptId 落库：文件名与全文取自预校验暂存，条目带 SCRIPT 类别")
  void scriptRunHistoryIsRecordedAsSummaryEntry() throws Exception {
    MvcResult analyzed = analyze("mysql-work-note", "migration.sql", "UPDATE t SET a = 1;\n").andReturn();
    String scriptId = JsonPath.read(analyzed.getResponse().getContentAsString(), "$.scriptId");

    mockMvc.perform(post("/api/sql/script/history").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"scriptId\":\"%s\",\"durationMs\":120}"
                .formatted(scriptId)))
        .andExpect(status().isOk());

    MvcResult history = mockMvc
        .perform(get("/api/history/mysql-work-note").cookie(session))
        .andExpect(status().isOk())
        .andReturn();
    String body = history.getResponse().getContentAsString();
    assertThat((Integer) JsonPath.read(body, "$.length()")).isEqualTo(1);
    assertThat((String) JsonPath.read(body, "$[0].filename")).isEqualTo("migration.sql");
    assertThat((String) JsonPath.read(body, "$[0].kind")).isEqualTo("SCRIPT");
    // 汇总条目存完整脚本原文（含末尾换行）
    assertThat((String) JsonPath.read(body, "$[0].sql")).isEqualTo("UPDATE t SET a = 1;\n");
  }

  @Test
  @DisplayName("未知或已消费的 scriptId 拒绝入史并明确报错")
  void unknownScriptIdIsRejected() throws Exception {
    mockMvc.perform(post("/api/sql/script/history").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"scriptId\":\"no-such-run\",\"durationMs\":1}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("SCRIPT_RUN_NOT_FOUND"));
  }

  @Test
  @DisplayName("同一 scriptId 消费后不可重复入史")
  void scriptIdCannotBeConsumedTwice() throws Exception {
    MvcResult analyzed = analyze("mysql-work-note", "m.sql", "SELECT 1;\n").andReturn();
    String scriptId = JsonPath.read(analyzed.getResponse().getContentAsString(), "$.scriptId");
    mockMvc.perform(post("/api/sql/script/history").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"scriptId\":\"%s\",\"durationMs\":5}"
                .formatted(scriptId)))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/sql/script/history").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"scriptId\":\"%s\",\"durationMs\":5}"
                .formatted(scriptId)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("SCRIPT_RUN_NOT_FOUND"));
  }

  @Test
  @DisplayName("脚本单语句执行不写会话历史：确认要求路径不留任何历史条目")
  void scriptExecuteDoesNotRecordSessionHistory() throws Exception {
    mockMvc.perform(post("/api/sql/script/execute").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"UPDATE t SET a = 1;\"}"))
        .andExpect(status().isOk());

    MvcResult history = mockMvc
        .perform(get("/api/history/mysql-work-note").cookie(session))
        .andExpect(status().isOk())
        .andReturn();
    assertThat((Integer) JsonPath.read(history.getResponse().getContentAsString(), "$.length()"))
        .isZero();
  }

  private ResultActions analyze(String sourceId, String filename, String content) throws Exception {
    return mockMvc.perform(post("/api/sql/script/analyze").cookie(session)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"sourceId\":\"%s\",\"filename\":%s,\"content\":%s}"
            .formatted(sourceId, jsonText(filename), jsonText(content))));
  }
}
