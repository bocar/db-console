package io.github.dbconsole.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.dbconsole.web.AuthTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest {

  private static final String SESSION_COOKIE = "DB_CONSOLE_SESSION";

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("正确凭据登录：返回账号并种下会话 Cookie，可访问会话探针")
  void loginSucceedsAndSetsSessionCookie() throws Exception {
    Cookie session = loginCookie();

    assertThat(session).isNotNull();

    mockMvc
        .perform(get("/api/auth/session").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("admin"));
  }

  @Test
  @DisplayName("错误密码拒绝登录，提示不区分账号或密码错误")
  void loginRejectsWrongPassword() throws Exception {
    mockMvc
        .perform(loginRequest("admin", "wrong-password"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
  }

  @Test
  @DisplayName("空白字段属于请求校验错误")
  void loginRejectsBlankFields() throws Exception {
    mockMvc.perform(loginRequest(" ", "")).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("未登录访问受保护接口一律 401")
  void protectedEndpointRejectsMissingSession() throws Exception {
    mockMvc
        .perform(get("/api/auth/session"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  @DisplayName("登出使会话立即失效")
  void logoutInvalidatesSession() throws Exception {
    Cookie session = loginCookie();

    mockMvc.perform(post("/api/auth/logout").cookie(session)).andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/auth/session").cookie(session))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("未登录登出也是无副作用成功")
  void logoutWithoutSessionIsNoop() throws Exception {
    mockMvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
  }

  private MockHttpServletRequestBuilder loginRequest(String username, String password) {
    return post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password));
  }

  private Cookie loginCookie() throws Exception {
    return AuthTestSupport.loginSession(mockMvc);
  }

}
