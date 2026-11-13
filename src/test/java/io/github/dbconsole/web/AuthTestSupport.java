package io.github.dbconsole.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 测试辅助：以应用配置账号登录并返回会话 Cookie。 */
public final class AuthTestSupport {

  public static final String TEST_PASSWORD = "test-secret";

  private AuthTestSupport() {}

  public static Cookie loginSession(MockMvc mockMvc) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"admin\",\"password\":\"%s\"}".formatted(TEST_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();
    for (Cookie cookie : result.getResponse().getCookies()) {
      if ("DB_CONSOLE_SESSION".equals(cookie.getName())) {
        return cookie;
      }
    }
    throw new IllegalStateException("登录响应未包含会话 Cookie");
  }
}
