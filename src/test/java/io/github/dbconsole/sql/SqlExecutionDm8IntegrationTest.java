package io.github.dbconsole.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.dbconsole.web.AuthTestSupport;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * T17 §5.2 DM8 实库验收：分页连续性（F-11）、WITH…LIMIT 派生表包装、
 * 生产数据源确认流（F-09）。需要环境变量 DB_CONSOLE_DM8_NAVIC_PASSWORD；
 * 破坏性操作仅落在 dbconsole_test_ 前缀表上（隔离纪律），结束清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(io.github.dbconsole.web.EnvCredentialSource.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
    properties = {
      "db-console.data-sources[0].id=dm8-navic",
      "db-console.data-sources[0].display-name=达梦 NAVIC 库",
      "db-console.data-sources[0].environment=PRODUCTION",
      "db-console.data-sources[0].database-type=DM8",
      "db-console.data-sources[0].compatibility-mode=NATIVE",
      "db-console.data-sources[0].jdbc-url=jdbc:dm://47.98.61.214:5236?schema=NAVIC",
      "db-console.data-sources[0].username=navic",
      "db-console.data-sources[0].credential-ref=DB_CONSOLE_DM8_NAVIC_PASSWORD",
    })
class SqlExecutionDm8IntegrationTest {

  private static final String TABLE = "dbconsole_test_t17";
  private static final String JDBC_URL = "jdbc:dm://47.98.61.214:5236?schema=NAVIC";

  @Autowired private MockMvc mockMvc;

  private Cookie session;

  private String dm8Password() {
    return System.getenv("DB_CONSOLE_DM8_NAVIC_PASSWORD");
  }

  @BeforeAll
  void requireDm8AndPrepareTable() throws Exception {
    Assumptions.assumeTrue(
        dm8Password() != null, "缺少 DB_CONSOLE_DM8_NAVIC_PASSWORD，跳过 DM8 集成验收");
    try (Connection connection = dm8Connection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS " + TABLE);
      statement.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY)");
    }
  }

  @AfterAll
  void dropTable() throws Exception {
    if (dm8Password() == null) {
      return;
    }
    try (Connection connection = dm8Connection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS " + TABLE);
    }
  }

  @BeforeEach
  void login() throws Exception {
    session = AuthTestSupport.loginSession(mockMvc);
  }

  @Test
  void orderedFixedRowsContinueAcrossPagesWithoutGapsOrDuplicates() throws Exception {
    try (Connection connection = dm8Connection();
        Statement statement = connection.createStatement()) {
      for (int id = 1; id <= 25; id++) {
        statement.executeUpdate("INSERT INTO " + TABLE + " VALUES (" + id + ")");
      }
    }
    try {
      String sql = "SELECT id FROM " + TABLE + " ORDER BY id";
      List<Integer> ids = new ArrayList<>();
      for (int page = 1; page <= 3; page++) {
        MvcResult result = mockMvc.perform(post("/api/sql/page")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"sourceId\":\"dm8-navic\",\"sql\":%s,"
                    + "\"page\":%d,\"pageSize\":10}").formatted(jsonText(sql), page)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(page))
            .andExpect(jsonPath("$.totalRows").value(25))
            .andExpect(jsonPath("$.totalPages").value(3))
            .andExpect(jsonPath("$.hasNextPage").value(page < 3))
            .andReturn();
        List<List<Integer>> rows = JsonPath.read(body(result), "$.rows");
        rows.forEach(row -> ids.add(row.get(0)));
      }
      assertThat(ids).containsExactlyElementsOf(IntStream.rangeClosed(1, 25).boxed().toList());
    } finally {
      try (Connection connection = dm8Connection();
          Statement statement = connection.createStatement()) {
        statement.execute("DELETE FROM " + TABLE);
      }
    }
  }

  /** 观察项（ADR-0002）：顶层已带 LIMIT 的 WITH 查询经派生表包装后分页，DM8 须可执行。 */
  @Test
  void withLimitQueryIsPagedThroughDerivedTable() throws Exception {
    try (Connection connection = dm8Connection();
        Statement statement = connection.createStatement()) {
      for (int id = 1; id <= 5; id++) {
        statement.executeUpdate("INSERT INTO " + TABLE + " VALUES (" + id + ")");
      }
    }
    try {
      String sql = "WITH limited AS (SELECT id FROM " + TABLE + " ORDER BY id LIMIT 3)"
          + " SELECT id FROM limited ORDER BY id";
      mockMvc.perform(post("/api/sql/page")
              .cookie(session)
              .contentType(MediaType.APPLICATION_JSON)
              .content(("{\"sourceId\":\"dm8-navic\",\"sql\":%s,"
                  + "\"page\":1,\"pageSize\":10}").formatted(jsonText(sql))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.totalRows").value(3))
          .andExpect(jsonPath("$.rows.length()").value(3));
    } finally {
      try (Connection connection = dm8Connection();
          Statement statement = connection.createStatement()) {
        statement.execute("DELETE FROM " + TABLE);
      }
    }
  }

  /** §5.2：生产确认用标记为生产的数据源验证（F-09：无凭据拒绝、带凭据放行）。 */
  @Test
  void productionSourceRequiresConfirmationForWrite() throws Exception {
    String sql = "INSERT INTO " + TABLE + " VALUES (1)";
    MvcResult first = mockMvc.perform(post("/api/sql/execute")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceId\":\"dm8-navic\",\"sql\":%s}".formatted(jsonText(sql))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("CONFIRMATION_REQUIRED"))
        .andExpect(jsonPath("$.reason").value("PRODUCTION_WRITE"))
        .andReturn();
    String token = JsonPath.read(first.getResponse().getContentAsString(), "$.confirmationToken");

    mockMvc.perform(post("/api/sql/execute")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content(("{\"sourceId\":\"dm8-navic\",\"sql\":%s,"
                + "\"confirmationToken\":\"%s\"}").formatted(jsonText(sql), token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("UPDATE"));

    try (Connection connection = dm8Connection();
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM " + TABLE);
    }
  }

  private Connection dm8Connection() throws SQLException {
    return DriverManager.getConnection(JDBC_URL, "navic", dm8Password());
  }

  private static String jsonText(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString();
  }
}
