package io.github.dbconsole.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Assumptions;
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
 * 本地 MySQL 集成验收：需要环境变量 DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD（真实密码，不落测试代码），
 * 缺失时整类跳过。
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
      "db-console.data-sources[0].jdbc-url=jdbc:mysql://localhost:3306/work_note",
      "db-console.data-sources[0].username=root",
      "db-console.data-sources[0].credential-ref=DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD",
      "db-console.data-sources[1].id=mysql-bad-auth",
      "db-console.data-sources[1].display-name=错密码库",
      "db-console.data-sources[1].environment=TEST",
      "db-console.data-sources[1].database-type=MYSQL",
      "db-console.data-sources[1].compatibility-mode=NATIVE",
      "db-console.data-sources[1].jdbc-url=jdbc:mysql://localhost:3306/work_note",
      "db-console.data-sources[1].username=root",
      "db-console.data-sources[1].credential-ref=DB_CONSOLE_TEST_BAD_PASSWORD",
    })
class DataSourceRegistryMySqlIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private DataSourceRegistry registry;

  @BeforeEach
  void requireLocalMysql() {
    Assumptions.assumeTrue(
        System.getenv("DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD") != null,
        "缺少 DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD，跳过本地 MySQL 集成验收");
  }

  @TestConfiguration
  static class Credentials {
    @Bean
    @Primary
    CredentialSource testCredentialSource() {
      return ref -> {
        if ("DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD".equals(ref)) {
          return System.getenv(ref);
        }
        return "definitely-not-the-password";
      };
    }
  }

  private Cookie session;

  @BeforeEach
  void login() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"admin\",\"password\":\"test-secret\"}"))
            .andExpect(status().isOk())
            .andReturn();
    for (Cookie cookie : result.getResponse().getCookies()) {
      if ("DB_CONSOLE_SESSION".equals(cookie.getName())) {
        session = cookie;
      }
    }
  }

  @Test
  @DisplayName("注册表解析出真实连接信息（凭据来自环境变量）")
  void registryResolvesRealCredentials() {
    ResolvedDataSource resolved = registry.resolve("mysql-work-note");

    assertThat(resolved.jdbcUrl()).isEqualTo("jdbc:mysql://localhost:3306/work_note");
    assertThat(resolved.password()).isEqualTo(System.getenv("DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD"));
  }

  @Test
  @DisplayName("本地 MySQL 连接测试成功")
  void connectionTestSucceeds() throws Exception {
    mockMvc
        .perform(post("/api/data-sources/mysql-work-note/connection-test").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));
  }

  @Test
  @DisplayName("错密码归类为认证失败")
  void connectionTestClassifiesAuthenticationFailure() throws Exception {
    mockMvc
        .perform(post("/api/data-sources/mysql-bad-auth/connection-test").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(false))
        .andExpect(jsonPath("$.kind").value("AUTHENTICATION"));
  }
}
