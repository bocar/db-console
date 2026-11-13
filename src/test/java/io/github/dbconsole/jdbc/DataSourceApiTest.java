package io.github.dbconsole.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "db-console.data-sources[0].id=mysql-work-note",
      "db-console.data-sources[0].display-name=MySQL 工作笔记库",
      "db-console.data-sources[0].environment=DEVELOPMENT",
      "db-console.data-sources[0].database-type=MYSQL",
      "db-console.data-sources[0].compatibility-mode=NATIVE",
      "db-console.data-sources[0].jdbc-url=jdbc:mysql://127.0.0.1:3306/work_note",
      "db-console.data-sources[0].username=root",
      "db-console.data-sources[0].credential-ref=DB_CONSOLE_TEST_MYSQL",
      "db-console.data-sources[1].id=dead-source",
      "db-console.data-sources[1].display-name=不可达库",
      "db-console.data-sources[1].environment=PRODUCTION",
      "db-console.data-sources[1].database-type=DM8",
      "db-console.data-sources[1].compatibility-mode=NATIVE",
      "db-console.data-sources[1].jdbc-url=jdbc:mysql://127.0.0.1:1/none",
      "db-console.data-sources[1].username=nobody",
      "db-console.data-sources[1].credential-ref=DB_CONSOLE_TEST_DEAD",
    })
class DataSourceApiTest {

  @Autowired private MockMvc mockMvc;

  private Cookie session;

  @TestConfiguration
  static class FakeCredentials {
    @Bean
    @Primary
    CredentialSource testCredentialSource() {
      return ref -> Map.of(
          "DB_CONSOLE_TEST_MYSQL", "irrelevant",
          "DB_CONSOLE_TEST_DEAD", "irrelevant").get(ref);
    }
  }

  @BeforeEach
  void login() throws Exception {
    session = AuthTestSupport.loginSession(mockMvc);
  }

  @Test
  @DisplayName("数据源列表只暴露公开字段，不含连接信息与凭据")
  void listExposesPublicFieldsOnly() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/data-sources").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value("mysql-work-note"))
            .andExpect(jsonPath("$[0].displayName").value("MySQL 工作笔记库"))
            .andExpect(jsonPath("$[0].environment").value("DEVELOPMENT"))
            .andExpect(jsonPath("$[0].databaseType").value("MYSQL"))
            .andExpect(jsonPath("$[1].environment").value("PRODUCTION"))
            .andReturn();

    String body = result.getResponse().getContentAsString();
    assertThat(body)
        .doesNotContain("jdbcUrl")
        .doesNotContain("username")
        .doesNotContain("credentialRef")
        .doesNotContain("password");
  }

  @Test
  @DisplayName("未知数据源标识的连接测试返回 404")
  void connectionTestRejectsUnknownId() throws Exception {
    mockMvc
        .perform(post("/api/data-sources/no-such-source/connection-test").cookie(session))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("UNKNOWN_DATA_SOURCE"));
  }

  @Test
  @DisplayName("不可达数据源返回诊断结果且消息不含凭据")
  void connectionTestReportsUnreachableWithoutCredentials() throws Exception {
    MvcResult result =
        mockMvc
            .perform(post("/api/data-sources/dead-source/connection-test").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andReturn();

    String body = result.getResponse().getContentAsString();
    assertThat(body).doesNotContain("irrelevant");
  }
}
