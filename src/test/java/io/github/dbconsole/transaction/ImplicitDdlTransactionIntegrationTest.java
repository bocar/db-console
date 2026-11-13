package io.github.dbconsole.transaction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.dbconsole.web.AuthTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F-16 后半验收：隐式提交 DDL 拦截后事务保持未提交状态，可继续提交或回滚。
 * 需要环境变量 DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(io.github.dbconsole.web.EnvCredentialSource.class)
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
class ImplicitDdlTransactionIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @BeforeEach
  void requireMysql() {
    Assumptions.assumeTrue(
        System.getenv("DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD") != null,
        "缺少 DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD，跳过 MySQL 集成验收");
  }

  private Cookie session;

  @BeforeEach
  void login() throws Exception {
    session = AuthTestSupport.loginSession(mockMvc);
  }

  @Test
  @DisplayName("DDL 拦截后事务保持未提交状态，回滚后数据复原，DDL 在新连接独立执行")
  void ddlBlockedThenTransactionRollbackThenDdlOnIndependentConnection() throws Exception {
    // 1) 开启事务并写入
    mockMvc.perform(post("/api/transaction/mysql-work-note/begin").cookie(session))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/sql/execute")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceId\":\"mysql-work-note\",\"sql\":\"CREATE TABLE dbconsole_test_t09 (id INT PRIMARY KEY)\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSACTION_OPEN"));

    // 2) 事务仍可回滚（DDL 拦截未影响事务状态）
    mockMvc
        .perform(post("/api/transaction/mysql-work-note/rollback").cookie(session))
        .andExpect(status().isOk());

    // 3) DDL 需要确认（各环境均须确认）
    var confirmation =
        mockMvc
            .perform(
                post("/api/sql/execute")
                    .cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"sourceId\":\"mysql-work-note\",\"sql\":\"CREATE TABLE dbconsole_test_t09 (id INT PRIMARY KEY)\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("CONFIRMATION_REQUIRED"))
            .andExpect(jsonPath("$.reason").value("IMPLICIT_COMMIT_DDL"))
            .andReturn();
    String token =
        JsonPath.read(
            confirmation.getResponse().getContentAsString(), "$.confirmationToken");

    // 4) 确认后独立连接执行、立即生效（affectedRows null 表示 DDL）
    mockMvc
        .perform(
            post("/api/sql/execute")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceId\":\"mysql-work-note\",\"sql\":\"CREATE TABLE dbconsole_test_t09 (id INT PRIMARY KEY)\",\"confirmationToken\":\"" + token + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("UPDATE"))
        .andExpect(jsonPath("$.affectedRows").doesNotExist());

    // 5) 新连接可查到新建表（立即生效）
    Cookie fresh = AuthTestSupport.loginSession(mockMvc);
    mockMvc
        .perform(
            post("/api/sql/execute")
                .cookie(fresh)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceId\":\"mysql-work-note\",\"sql\":\"SELECT COUNT(*) AS CNT FROM dbconsole_test_t09\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0][0]").value(0));

    // 清理
    var dropConfirmation =
        mockMvc
            .perform(
                post("/api/sql/execute")
                    .cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"sourceId\":\"mysql-work-note\",\"sql\":\"DROP TABLE dbconsole_test_t09\"}"))
            .andReturn();
    String dropToken =
        JsonPath.read(
            dropConfirmation.getResponse().getContentAsString(), "$.confirmationToken");
    mockMvc
        .perform(
            post("/api/sql/execute")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceId\":\"mysql-work-note\",\"sql\":\"DROP TABLE dbconsole_test_t09\",\"confirmationToken\":\"" + dropToken + "\"}"))
        .andExpect(status().isOk());
  }
}
