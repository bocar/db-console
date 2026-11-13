package io.github.dbconsole.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.dbconsole.audit.AuditService;
import io.github.dbconsole.jdbc.ConnectionFactory;
import io.github.dbconsole.jdbc.CredentialSource;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.transaction.TransactionManager;
import io.github.dbconsole.web.AuthTestSupport;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 查询取消与执行限制接口验收（F-14/F-10）：全部经 MockMvc 走公开 API。
 * JDBC 语句以锁存器模拟可控长查询；取消路径仅限本会话（F-14），并发超限与超时明确反馈（F-10）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "db-console.execution.max-concurrency=1",
      "db-console.data-sources[0].id=mysql-work-note",
      "db-console.data-sources[0].display-name=MySQL 工作笔记库",
      "db-console.data-sources[0].environment=DEVELOPMENT",
      "db-console.data-sources[0].database-type=MYSQL",
      "db-console.data-sources[0].compatibility-mode=NATIVE",
      "db-console.data-sources[0].jdbc-url=jdbc:mysql://127.0.0.1:1/never",
      "db-console.data-sources[0].username=root",
      "db-console.data-sources[0].credential-ref=DB_CONSOLE_TEST_RULE",
    })
class SqlCancelApiTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ConnectionFactory connections;
  @MockitoBean private TransactionManager transactions;
  @MockitoBean private AuditService audit;

  private final Connection connection = mock(Connection.class);
  private final Statement statement = mock(Statement.class);

  private final ExecutorService executor = Executors.newCachedThreadPool();
  private Cookie session;
  private CountDownLatch queryRunning;
  private CountDownLatch releaseQuery;

  @TestConfiguration
  static class FakeCredentials {
    @Bean
    @Primary
    CredentialSource testCredentialSource() {
      return ref -> Map.of("DB_CONSOLE_TEST_RULE", "irrelevant").get(ref);
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    queryRunning = new CountDownLatch(1);
    releaseQuery = new CountDownLatch(1);
    session = AuthTestSupport.loginSession(mockMvc);
    when(connections.open(any(ResolvedDataSource.class))).thenReturn(connection);
    when(connection.createStatement()).thenReturn(statement);
    ResultSet resultSet = mock(ResultSet.class);
    ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    when(statement.getResultSet()).thenReturn(resultSet);
    when(resultSet.getMetaData()).thenReturn(metadata);
    when(metadata.getColumnCount()).thenReturn(1);
    when(metadata.getColumnLabel(1)).thenReturn("id");
    when(metadata.getColumnTypeName(1)).thenReturn("INT");
    when(resultSet.next()).thenReturn(false);
    // 可控长查询：execute 挂起，直到语句被取消（驱动中断异常）或测试显式放行
    when(statement.execute(anyString()))
        .thenAnswer(
            invocation -> {
              queryRunning.countDown();
              if (!releaseQuery.await(5, TimeUnit.SECONDS)) {
                return false;
              }
              throw new SQLException("Statement cancelled", "70100");
            });
    doAnswer(
            invocation -> {
              releaseQuery.countDown();
              return null;
            })
        .when(statement)
        .cancel();
  }

  @AfterEach
  void tearDown() {
    releaseQuery.countDown();
    executor.shutdownNow();
  }

  /** 在后台线程发起执行请求并等待语句进入挂起执行中。 */
  private Future<MvcResult> startBlockedExecution(Cookie cookie) {
    queryRunning = new CountDownLatch(1);
    Future<MvcResult> executing =
        executor.submit(
            () ->
                mockMvc
                    .perform(
                        post("/api/sql/execute")
                            .cookie(cookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                "{\"sourceId\":\"mysql-work-note\",\"sql\":\"SELECT SLEEP(60)\"}"))
                    .andReturn());
    try {
      assertThat(queryRunning.await(2, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
    return executing;
  }

  private MvcResult cancel(String sourceId, Cookie cookie) throws Exception {
    return mockMvc
        .perform(post("/api/sql/{sourceId}/cancel", sourceId).cookie(cookie))
        .andReturn();
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString();
  }

  private static String jsonKind(MvcResult result) throws Exception {
    return JsonPath.read(body(result), "$.kind");
  }

  @Test
  @DisplayName("可控长查询取消成功：取消响应 CANCELLED，执行以 CANCELLED 终态收尾")
  void cancelCancelsRunningLongQuery() throws Exception {
    Future<MvcResult> executing = startBlockedExecution(session);

    mockMvc
        .perform(post("/api/sql/mysql-work-note/cancel").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("CANCELLED"));
    verify(statement).cancel();

    MvcResult executed = executing.get(5, TimeUnit.SECONDS);
    assertThat(jsonKind(executed)).isEqualTo("CANCELLED");
    verify(audit)
        .recordExecution(
            eq("admin"), eq("mysql-work-note"), anyString(), anyLong(), eq("CANCELLED"));
  }

  @Test
  @DisplayName("查询先于取消完成时保留结果，取消报告 COMPLETED")
  void cancelAfterQueryCompletedReportsCompletedAndKeepsResult() throws Exception {
    when(statement.execute(anyString()))
        .thenAnswer(
            invocation -> {
              queryRunning.countDown();
              releaseQuery.await(5, TimeUnit.SECONDS);
              return true;
            });
    MvcResult executed =
        mockMvc
            .perform(
                post("/api/sql/execute")
                    .cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"SELECT id FROM t\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("QUERY"))
            .andReturn();

    mockMvc
        .perform(post("/api/sql/mysql-work-note/cancel").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("COMPLETED"));

    assertThat(jsonKind(executed)).isEqualTo("QUERY");
  }

  @Test
  @DisplayName("取消失败时返回原因，查询继续执行直至正常完成")
  void cancelFailureReportsReasonAndQueryContinues() throws Exception {
    when(statement.execute(anyString()))
        .thenAnswer(
            invocation -> {
              queryRunning.countDown();
              releaseQuery.await(5, TimeUnit.SECONDS);
              return true;
            });
    Future<MvcResult> executing = startBlockedExecution(session);
    doThrow(new SQLException("driver refuses cancel")).when(statement).cancel();

    mockMvc
        .perform(post("/api/sql/mysql-work-note/cancel").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("FAILED"))
        .andExpect(
            jsonPath("$.message").value(Matchers.containsString("driver refuses cancel")));

    verify(statement).cancel();
    releaseQuery.countDown();
    MvcResult executed = executing.get(5, TimeUnit.SECONDS);
    assertThat(jsonKind(executed)).isEqualTo("QUERY");
  }

  @Test
  @DisplayName("无法取消其他会话的查询：不可见且驱动语句未被触碰")
  void cancelFromAnotherSessionCannotStopRunningQuery() throws Exception {
    Cookie otherSession = AuthTestSupport.loginSession(mockMvc);
    when(statement.execute(anyString()))
        .thenAnswer(
            invocation -> {
              queryRunning.countDown();
              releaseQuery.await(5, TimeUnit.SECONDS);
              return true;
            });
    Future<MvcResult> executing = startBlockedExecution(session);

    mockMvc
        .perform(post("/api/sql/mysql-work-note/cancel").cookie(otherSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("COMPLETED"));

    verify(statement, never()).cancel();
    releaseQuery.countDown();
    MvcResult executed = executing.get(5, TimeUnit.SECONDS);
    assertThat(jsonKind(executed)).isEqualTo("QUERY");
  }

  @Test
  @DisplayName("并发超过上限时执行明确拒绝 CONCURRENCY_EXCEEDED（F-10）")
  void executionBeyondConcurrencyLimitIsRejected() throws Exception {
    Cookie otherSession = AuthTestSupport.loginSession(mockMvc);
    startBlockedExecution(session);

    mockMvc
        .perform(
            post("/api/sql/execute")
                .cookie(otherSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"SELECT 2\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("CONCURRENCY_EXCEEDED"));

    releaseQuery.countDown();
  }

  @Test
  @DisplayName("同一会话同源的第二条执行被拒绝 SESSION_QUERY_RUNNING（F-14「指定」语义）")
  void secondExecutionOnSameSessionAndSourceRejected() throws Exception {
    startBlockedExecution(session);

    mockMvc
        .perform(
            post("/api/sql/execute")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"SELECT 2\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("SESSION_QUERY_RUNNING"));

    releaseQuery.countDown();
  }

  @Test
  @DisplayName("查询执行超时返回 408 QUERY_TIMEOUT（F-10）")
  void queryTimeoutReturnsExplicitCode() throws Exception {
    when(statement.execute(anyString())).thenThrow(new SQLTimeoutException("statement timed out"));

    mockMvc
        .perform(
            post("/api/sql/execute")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"SELECT SLEEP(60)\"}"))
        .andExpect(status().isRequestTimeout())
        .andExpect(jsonPath("$.code").value("QUERY_TIMEOUT"));
  }

  @Test
  @DisplayName("取消请求指向未知数据源返回 404")
  void cancelUnknownDataSourceRejected() throws Exception {
    mockMvc
        .perform(post("/api/sql/ghost/cancel").cookie(session))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("UNKNOWN_DATA_SOURCE"));
  }

  @Test
  @DisplayName("未登录取消请求返回 401")
  void cancelWithoutSessionRejected() throws Exception {
    mockMvc
        .perform(post("/api/sql/mysql-work-note/cancel"))
        .andExpect(status().isUnauthorized());
  }
}
