package io.github.dbconsole.transaction;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 本地 MySQL 手动事务集成验收（F-16 前半）：提交前后可见性、整笔回滚、
 * 跨会话隔离、空闲超时清理与登出回滚。破坏性操作仅落在 dbconsole_test_ 前缀表。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
    properties = {
      "db-console.transaction.idle-timeout=PT2S",
      "db-console.data-sources[0].id=mysql-work-note",
      "db-console.data-sources[0].display-name=MySQL 工作笔记库",
      "db-console.data-sources[0].environment=DEVELOPMENT",
      "db-console.data-sources[0].database-type=MYSQL",
      "db-console.data-sources[0].compatibility-mode=NATIVE",
      "db-console.data-sources[0].jdbc-url=jdbc:mysql://localhost:3306/work_note",
      "db-console.data-sources[0].username=root",
      "db-console.data-sources[0].credential-ref=DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD",
    })
class TransactionMySqlIntegrationTest {

  private static final String TABLE = "dbconsole_test_t08";

  @Autowired private MockMvc mockMvc;
  @Autowired private TransactionManager transactions;

  @TestConfiguration
  static class Credentials {
    @Bean
    @Primary
    CredentialSource testCredentialSource() {
      return ref -> {
        String value = System.getenv(ref);
        return value != null ? value : "placeholder-credential";
      };
    }
  }

  private String adminPassword() {
    return System.getenv("DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD");
  }

  private Cookie session;

  @BeforeAll
  void requireLocalMysqlAndPrepareTable() throws Exception {
    Assumptions.assumeTrue(
        adminPassword() != null, "缺少 DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD，跳过 MySQL 事务验收");
    try (Connection connection = mysqlConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS " + TABLE);
      statement.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, name VARCHAR(64))");
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

  private MockHttpServletRequestBuilder sql(String sqlText) {
    return post("/api/sql/execute")
        .cookie(session)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"%s\"}".formatted(sqlText));
  }

  private MockHttpServletRequestBuilder tx(String action) {
    return post("/api/transaction/mysql-work-note/" + action).cookie(session);
  }

  private String visibleRows() throws Exception {
    MvcResult result = mockMvc.perform(sql("SELECT COUNT(*) AS CNT FROM " + TABLE)).andReturn();
    Object count =
        com.jayway.jsonpath.JsonPath.read(
            result.getResponse().getContentAsString(), "$.rows[0][0]");
    return String.valueOf(count);
  }

  @Test
  void uncommittedChangesInvisibleToOtherConnectionsUntilCommit() throws Exception {
    mockMvc.perform(tx("begin")).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));

    mockMvc.perform(sql("INSERT INTO " + TABLE + " VALUES (1, '甲')"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.uncommitted").value(true));

    // 独立连接（其他会话）提交前不可见
    Cookie otherSession = AuthTestSupport.loginSession(mockMvc);
    MvcResult otherView =
        mockMvc
            .perform(
                post("/api/sql/execute")
                    .cookie(otherSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"sourceId\":\"mysql-work-note\",\"sql\":\"SELECT COUNT(*) AS CNT FROM "
                            + TABLE
                            + "\"}"))
            .andReturn();
    Object beforeCommit = com.jayway.jsonpath.JsonPath.read(otherView.getResponse().getContentAsString(), "$.rows[0][0]");
    assertThat(String.valueOf(beforeCommit)).isEqualTo("0");

    mockMvc.perform(tx("commit")).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

    // 提交后独立连接可见
    Cookie thirdSession = AuthTestSupport.loginSession(mockMvc);
    MvcResult committed =
        mockMvc
            .perform(
                post("/api/sql/execute")
                    .cookie(thirdSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"sourceId\":\"mysql-work-note\",\"sql\":\"SELECT COUNT(*) AS CNT FROM "
                            + TABLE
                            + "\"}"))
            .andReturn();
    Object afterCommit = com.jayway.jsonpath.JsonPath.read(committed.getResponse().getContentAsString(), "$.rows[0][0]");
    assertThat(String.valueOf(afterCommit)).isEqualTo("1");
    mockMvc.perform(sql("DELETE FROM " + TABLE + " WHERE id = 1")).andExpect(status().isOk());
  }

  @Test
  void rollbackRestoresAllChanges() throws Exception {
    mockMvc.perform(tx("begin")).andExpect(status().isOk());
    mockMvc.perform(sql("INSERT INTO " + TABLE + " VALUES (2, '乙')"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.uncommitted").value(true));

    mockMvc.perform(tx("rollback")).andExpect(status().isOk());

    assertThat(visibleRows()).isEqualTo("0");
  }

  @Test
  void otherSessionCannotCommitMyTransaction() throws Exception {
    mockMvc.perform(tx("begin")).andExpect(status().isOk());

    Cookie other = AuthTestSupport.loginSession(mockMvc);
    mockMvc
        .perform(post("/api/transaction/mysql-work-note/commit").cookie(other))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("NO_TRANSACTION"));

    mockMvc.perform(tx("rollback")).andExpect(status().isOk());
  }

  @Test
  void implicitCommitDdlBlockedWhileTransactionOpen() throws Exception {
    mockMvc.perform(tx("begin")).andExpect(status().isOk());

    mockMvc
        .perform(sql("CREATE TABLE " + TABLE + "_ddl (id INT)"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSACTION_OPEN"));

    mockMvc.perform(tx("rollback")).andExpect(status().isOk());
  }

  @Test
  void idleTimeoutRollsBackAndReleases() throws Exception {
    mockMvc.perform(tx("begin")).andExpect(status().isOk());
    mockMvc.perform(sql("INSERT INTO " + TABLE + " VALUES (3, '丙')"))
        .andExpect(status().isOk());

    Thread.sleep(2500);
    transactions.cleanupExpired();

    assertThat(transactions.hasActive(currentToken())).isFalse();
    assertThat(visibleRows()).isEqualTo("0");
  }

  @Test
  void logoutRollsBackImmediately() throws Exception {
    mockMvc.perform(tx("begin")).andExpect(status().isOk());
    mockMvc.perform(sql("INSERT INTO " + TABLE + " VALUES (4, '丁')"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/auth/logout").cookie(session)).andExpect(status().isNoContent());

    Cookie fresh = AuthTestSupport.loginSession(mockMvc);
    MvcResult afterLogout =
        mockMvc
            .perform(
                post("/api/sql/execute")
                    .cookie(fresh)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"sourceId\":\"mysql-work-note\",\"sql\":\"SELECT COUNT(*) AS CNT FROM "
                            + TABLE
                            + "\"}"))
            .andReturn();
    Object afterLogoutCount = com.jayway.jsonpath.JsonPath.read(afterLogout.getResponse().getContentAsString(), "$.rows[0][0]");
    assertThat(String.valueOf(afterLogoutCount)).isEqualTo("0");
    this.session = fresh;
  }

  private String currentToken() {
    return session.getValue();
  }
}
