package io.github.dbconsole.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
class SqlPaginationApiTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ConnectionFactory connections;
  @MockitoBean private TransactionManager transactions;

  private final Connection connection = mock(Connection.class);
  private final Statement statement = mock(Statement.class);
  private final ResultSet resultSet = mock(ResultSet.class);
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
  void setUp() throws Exception {
    session = AuthTestSupport.loginSession(mockMvc);
    when(connections.open(any(ResolvedDataSource.class))).thenReturn(connection);
    Statement unavailableCount = mock(Statement.class);
    when(connection.createStatement()).thenReturn(statement, unavailableCount);
    when(unavailableCount.execute(anyString())).thenThrow(new SQLException("count unavailable"));
    when(statement.execute(anyString())).thenReturn(true);
    when(statement.getResultSet()).thenReturn(resultSet);
    ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    when(resultSet.getMetaData()).thenReturn(metadata);
    when(metadata.getColumnCount()).thenReturn(1);
    when(metadata.getColumnLabel(1)).thenReturn("id");
    when(metadata.getColumnTypeName(1)).thenReturn("INT");
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getObject(1)).thenReturn(1);
  }

  @Test
  @DisplayName("翻页入口拒绝写入且不建立数据库连接")
  void pageEndpointRejectsWrites() throws Exception {
    mockMvc.perform(post("/api/sql/page")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\","
                + "\"sql\":\"UPDATE dbconsole_test_t SET name = 'x' WHERE id = 1\","
                + "\"page\":1,\"pageSize\":20}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("PAGINATION_NOT_ALLOWED"));

    verifyNoInteractions(connections);
  }

  @Test
  @DisplayName("翻页请求超过配置页大小上限时明确拒绝")
  void pageEndpointRejectsExcessivePageSize() throws Exception {
    mockMvc.perform(post("/api/sql/page")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"SELECT 1\","
                + "\"page\":1,\"pageSize\":1001}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));

    verifyNoInteractions(connections);
  }

  @Test
  @DisplayName("手动事务内翻页复用原事务连接")
  void pageEndpointUsesTransactionConnection() throws Exception {
    Connection transactionConnection = mock(Connection.class);
    Statement countStatement = mock(Statement.class);
    when(transactions.connectionFor(anyString(), eq("mysql-work-note")))
        .thenReturn(transactionConnection);
    when(transactionConnection.createStatement()).thenReturn(statement, countStatement);
    when(countStatement.execute(anyString())).thenThrow(new SQLException("count unavailable"));

    mockMvc.perform(post("/api/sql/page")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\","
                + "\"sql\":\"SELECT id FROM dbconsole_test_t ORDER BY id\","
                + "\"page\":2,\"pageSize\":20}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(2));

    verify(connections, never()).open(any(ResolvedDataSource.class));
    verify(transactions).touch(anyString());
  }

  @Test
  @DisplayName("无原分页子句的重名列查询直接追加 LIMIT 而不使用派生表")
  void duplicateColumnQueryAvoidsDerivedTable() throws Exception {
    String sql = "SELECT a.id, b.id FROM dbconsole_test_t a "
        + "JOIN dbconsole_test_t b ON a.id = b.id ORDER BY a.id";

    mockMvc.perform(post("/api/sql/execute")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"%s\"}".formatted(sql)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("QUERY"));

    ArgumentCaptor<String> executedSql = ArgumentCaptor.forClass(String.class);
    verify(statement).execute(executedSql.capture());
    assertThat(executedSql.getValue()).startsWith(sql).doesNotContain("SELECT * FROM (");
  }

  @Test
  @DisplayName("分页包装移除末尾分号并保留尾随注释")
  void paginationRemovesTerminatorBeforeTrailingComment() throws Exception {
    String sql = "SELECT id FROM dbconsole_test_t ORDER BY id; -- tail";

    mockMvc.perform(post("/api/sql/execute")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"%s\"}".formatted(sql)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("QUERY"));

    ArgumentCaptor<String> executedSql = ArgumentCaptor.forClass(String.class);
    verify(statement).execute(executedSql.capture());
    String wrapped = executedSql.getValue();
    assertThat(wrapped).doesNotContain("; -- tail").contains("-- tail");
    assertThat(wrapped.lastIndexOf("LIMIT 101 OFFSET 0"))
        .isGreaterThan(wrapped.lastIndexOf('\n'));
  }

  @Test
  @DisplayName("独立翻页入口按原查询重执行指定页与页大小")
  void pageEndpointReexecutesOriginalQuery() throws Exception {
    String sql = "SELECT id FROM dbconsole_test_t ORDER BY id";

    mockMvc.perform(post("/api/sql/page")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"%s\",\"page\":2,\"pageSize\":50}"
                .formatted(sql)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("QUERY"))
        .andExpect(jsonPath("$.page").value(2))
        .andExpect(jsonPath("$.pageSize").value(50))
        .andExpect(jsonPath("$.hasNextPage").value(false));

    ArgumentCaptor<String> executedSql = ArgumentCaptor.forClass(String.class);
    verify(statement).execute(executedSql.capture());
    assertThat(executedSql.getValue()).contains(sql).contains("LIMIT 51 OFFSET 50");
  }

  @Test
  @DisplayName("能安全取得总数时返回总行数、总页数与下一页标记")
  void initialQueryReturnsTotalsWhenCountSucceeds() throws Exception {
    String sql = "SELECT id FROM dbconsole_test_t ORDER BY id";
    AtomicInteger row = new AtomicInteger();
    when(resultSet.next()).thenAnswer(invocation -> row.incrementAndGet() <= 101);
    when(resultSet.getObject(1)).thenAnswer(invocation -> row.get());
    Statement countStatement = mock(Statement.class);
    ResultSet countResult = mock(ResultSet.class);
    when(connection.createStatement()).thenReturn(statement, countStatement);
    when(countStatement.execute(anyString())).thenReturn(true);
    when(countStatement.getResultSet()).thenReturn(countResult);
    when(countResult.next()).thenReturn(true);
    when(countResult.getLong(1)).thenReturn(250L);

    mockMvc.perform(post("/api/sql/execute")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"%s\"}".formatted(sql)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(100))
        .andExpect(jsonPath("$.truncated").value(false))
        .andExpect(jsonPath("$.hasNextPage").value(true))
        .andExpect(jsonPath("$.totalRows").value(250))
        .andExpect(jsonPath("$.totalPages").value(3));

    ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
    verify(countStatement).execute(countSql.capture());
    assertThat(countSql.getValue()).contains("SELECT COUNT(*) FROM (").contains(sql);
  }

  @Test
  @DisplayName("初始只读查询由后端包装并返回第一页元数据")
  void initialQueryReturnsFirstPage() throws Exception {
    String sql = "SELECT id FROM dbconsole_test_t ORDER BY id";

    mockMvc.perform(post("/api/sql/execute")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"mysql-work-note\",\"sql\":\"%s\"}".formatted(sql)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("QUERY"))
        .andExpect(jsonPath("$.rows[0][0]").value(1))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.pageSize").value(100))
        .andExpect(jsonPath("$.hasNextPage").value(false));

    ArgumentCaptor<String> executedSql = ArgumentCaptor.forClass(String.class);
    verify(statement).execute(executedSql.capture());
    assertThat(executedSql.getValue())
        .contains(sql)
        .contains("LIMIT 101 OFFSET 0");
  }
}
