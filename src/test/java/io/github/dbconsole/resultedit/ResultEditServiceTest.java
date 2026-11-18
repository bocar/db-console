package io.github.dbconsole.resultedit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.dbconsole.audit.AuditService;
import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.dialect.ResultEditSql;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.metadata.TableStructure;
import io.github.dbconsole.metadata.TableStructureService;
import io.github.dbconsole.sql.ConfirmationService;
import io.github.dbconsole.sql.RunningQueryRegistry;
import io.github.dbconsole.sql.SqlRejectException;
import io.github.dbconsole.transaction.TransactionManager;
import io.github.dbconsole.transaction.TransactionStateException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 结果编辑保存编排测试（F-17）：互斥、上限、重校验、确认门与审计时机。 */
class ResultEditServiceTest {

  private static final String SESSION_TOKEN = "session-token";
  private static final String USERNAME = "admin";
  private static final String DEV_SOURCE = "mysql-work-note";
  private static final String PROD_SOURCE = "dm8-navic";

  private final TransactionManager transactions = mock(TransactionManager.class);
  private final TableStructureService tableStructures = mock(TableStructureService.class);
  private final AuditService audit = mock(AuditService.class);
  private final ResultEditExecutor executor = mock(ResultEditExecutor.class);

  private DbConsoleProperties properties;
  private ConfirmationService confirmations;

  @BeforeEach
  void setUp() {
    properties = properties(3);
    confirmations = new ConfirmationService();
  }

  private ResultEditService service() {
    DataSourceRegistry registry = new DataSourceRegistry(properties, ref -> "db-secret");
    return new ResultEditService(
        registry, confirmations, transactions, tableStructures,
        new RunningQueryRegistry(properties.execution().maxConcurrency()),
        audit, properties, executor);
  }

  private static TableStructure table() {
    return new TableStructure(
        "orders",
        "",
        List.of(
            new TableStructure.ColumnDefinition("id", "INT", false, null, true, "", true),
            new TableStructure.ColumnDefinition("name", "VARCHAR", false, null, false, "", false),
            new TableStructure.ColumnDefinition("note", "VARCHAR", true, null, false, "", false)),
        List.of(),
        List.of());
  }

  private void structureVisible(String sourceId) {
    when(tableStructures.resolveColumnStructure(
            eq(sourceId), any(), any(), anyList()))
        .thenReturn(Optional.of(
            new io.github.dbconsole.metadata.TableStructureService.ResolvedColumnStructure(
                List.of("orders"), table())));
  }

  private static ResultEditRequest request(String sourceId) {
    Map<String, Object> updateKey = new LinkedHashMap<>();
    updateKey.put("id", 5);
    Map<String, Object> updateSet = new LinkedHashMap<>();
    updateSet.put("name", "新名字");
    return new ResultEditRequest(
        sourceId,
        List.of("orders"),
        List.of(new ResultEditRequest.DeleteChange(updateKey)),
        List.of(new ResultEditRequest.UpdateChange(updateKey, updateSet)),
        List.of(),
        null);
  }

  @Nested
  @DisplayName("前置守卫")
  class Guards {

    @Test
    @DisplayName("存在未提交手动事务时拒绝，不进入校验与审计（F-16 互斥）")
    void rejectsOpenTransaction() {
      doThrowForOpenTransaction();

      assertThatThrownBy(
              () -> service().save(SESSION_TOKEN, USERNAME, request(DEV_SOURCE)))
          .isInstanceOf(TransactionStateException.class)
          .hasMessageContaining("结果编辑保存使用独立短事务");

      verifyNoInteractions(audit);
      verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("变更数超过上限前置拒绝且不落审计")
    void rejectsBeyondCap() {
      properties = properties(1);
      structureVisible(DEV_SOURCE);

      ResultEditRequest twoChanges = request(DEV_SOURCE);
      assertThatThrownBy(() -> service().save(SESSION_TOKEN, USERNAME, twoChanges))
          .isInstanceOfSatisfying(
              SqlRejectException.class,
              rejected -> assertThat(rejected.code()).isEqualTo("TOO_MANY_CHANGES"))
          .hasMessageContaining("分批保存");

      verifyNoInteractions(audit);
      verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("来源表不可见时拒绝")
    void rejectsInvisibleTable() {
      when(tableStructures.resolveColumnStructure(eq(DEV_SOURCE), any(), any(), anyList()))
          .thenReturn(Optional.empty());

      assertThatThrownBy(() -> service().save(SESSION_TOKEN, USERNAME, request(DEV_SOURCE)))
          .isInstanceOfSatisfying(
              SqlRejectException.class,
              rejected -> assertThat(rejected.code()).isEqualTo("TABLE_UNREADABLE"));
    }

    @Test
    @DisplayName("变更校验失败拒绝：主键列不可修改")
    void rejectsPrimaryKeyInSet() {
      structureVisible(DEV_SOURCE);
      Map<String, Object> key = new LinkedHashMap<>();
      key.put("id", 5);
      Map<String, Object> set = new LinkedHashMap<>();
      set.put("id", 6);
      ResultEditRequest invalid =
          new ResultEditRequest(
              DEV_SOURCE, List.of("orders"), List.of(),
              List.of(new ResultEditRequest.UpdateChange(key, set)), List.of(), null);

      assertThatThrownBy(() -> service().save(SESSION_TOKEN, USERNAME, invalid))
          .isInstanceOfSatisfying(
              SqlRejectException.class,
              rejected -> assertThat(rejected.code()).isEqualTo("INVALID_CHANGE"))
          .hasMessageContaining("主键列不可修改");
    }

    @Test
    @DisplayName("NOT NULL 列置空拒绝")
    void rejectsNullOnNotNullColumn() {
      structureVisible(DEV_SOURCE);
      Map<String, Object> key = new LinkedHashMap<>();
      key.put("id", 5);
      Map<String, Object> set = new LinkedHashMap<>();
      set.put("name", null);
      ResultEditRequest invalid =
          new ResultEditRequest(
              DEV_SOURCE, List.of("orders"), List.of(),
              List.of(new ResultEditRequest.UpdateChange(key, set)), List.of(), null);

      assertThatThrownBy(() -> service().save(SESSION_TOKEN, USERNAME, invalid))
          .isInstanceOfSatisfying(
              SqlRejectException.class,
              rejected -> assertThat(rejected.code()).isEqualTo("INVALID_CHANGE"))
          .hasMessageContaining("NOT NULL");
    }
  }

  @Nested
  @DisplayName("确认与执行")
  class Confirmation {

    @Test
    @DisplayName("开发环境保存也先汇总确认：预览无风险标注但仍逐条签发凭据，确认后执行")
    void devSaveAlsoRequiresSummaryConfirmation() {
      structureVisible(DEV_SOURCE);
      ResultEditService resultEditService = service();
      ResultEditResponses first =
          resultEditService.save(SESSION_TOKEN, USERNAME, request(DEV_SOURCE));

      assertThat(first).isInstanceOf(ResultEditResponses.ConfirmationRequired.class);
      ResultEditResponses.ConfirmationRequired preview =
          (ResultEditResponses.ConfirmationRequired) first;
      assertThat(preview.statements()).hasSize(2);
      assertThat(preview.statements().get(0).reason()).isNull();
      assertThat(preview.statements().get(0).confirmationToken()).isNotBlank();
      verifyNoInteractions(executor);
      verifyNoInteractions(audit);

      when(executor.execute(any(), anyList(), any(), anyInt()))
          .thenReturn(
              new ResultEditResponses.Applied(
                  List.of(
                      new ResultEditResponses.AppliedStatement(0, "DELETE", 0, 1),
                      new ResultEditResponses.AppliedStatement(1, "UPDATE", 0, 1)),
                  12));
      ResultEditResponses applied =
          resultEditService.save(
              SESSION_TOKEN, USERNAME,
              withTokens(request(DEV_SOURCE), tokensOf(preview)));
      assertThat(applied).isInstanceOf(ResultEditResponses.Applied.class);
      verify(audit)
          .recordExecution(eq(USERNAME), eq(DEV_SOURCE), any(), anyLong(), eq("SUCCEEDED"));
    }

    @Test
    @DisplayName("生产环境写入：首call返回语句预览与凭据（含风险标注），不执行不落执行审计")
    void productionWriteRequiresConfirmation() {
      structureVisible(PROD_SOURCE);
      ResultEditResponses response =
          service().save(SESSION_TOKEN, USERNAME, request(PROD_SOURCE));

      assertThat(response).isInstanceOf(ResultEditResponses.ConfirmationRequired.class);
      ResultEditResponses.ConfirmationRequired required =
          (ResultEditResponses.ConfirmationRequired) response;
      assertThat(required.statements()).hasSize(2);
      assertThat(required.statements().get(0).sql())
          .isEqualTo("DELETE FROM \"orders\" WHERE \"id\" = ?");
      assertThat(required.statements().get(0).reason()).isEqualTo("PRODUCTION_WRITE");
      assertThat(required.statements().get(0).confirmationToken()).isNotBlank();
      verifyNoInteractions(executor);
      verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("携带有效凭据的生产保存执行；篡改值后凭据失效")
    void productionWriteExecutesWithValidToken() {
      structureVisible(PROD_SOURCE);
      ResultEditService resultEditService = service();
      ResultEditResponses.ConfirmationRequired first =
          (ResultEditResponses.ConfirmationRequired)
              resultEditService.save(SESSION_TOKEN, USERNAME, request(PROD_SOURCE));
      List<String> tokens =
          first.statements().stream()
              .map(ResultEditResponses.StatementPreview::confirmationToken)
              .toList();

      when(executor.execute(any(), anyList(), any(), anyInt()))
          .thenReturn(new ResultEditResponses.Applied(List.of(), 8));
      ResultEditResponses applied =
          resultEditService.save(
              SESSION_TOKEN, USERNAME, withTokens(request(PROD_SOURCE), tokens));
      assertThat(applied).isInstanceOf(ResultEditResponses.Applied.class);

      // 值变动后凭据失效：重签确认
      ResultEditResponses again =
          resultEditService.save(
              SESSION_TOKEN, USERNAME,
              withTokens(requestWithDifferentValue(), tokens));
      assertThat(again).isInstanceOf(ResultEditResponses.ConfirmationRequired.class);
    }

    @Test
    @DisplayName("执行器抛出运行时异常记 FAILED 审计并上抛")
    void executorRuntimeExceptionAudited() {
      structureVisible(DEV_SOURCE);
      when(executor.execute(any(), anyList(), any(), anyInt()))
          .thenThrow(new IllegalStateException("连接打开失败：refused"));

      ResultEditService resultEditService = service();
      ResultEditResponses first =
          resultEditService.save(SESSION_TOKEN, USERNAME, request(DEV_SOURCE));
      assertThatThrownBy(
              () -> resultEditService.save(
                  SESSION_TOKEN, USERNAME,
                  withTokens(request(DEV_SOURCE), tokensOf((ResultEditResponses.ConfirmationRequired) first))))
          .isInstanceOf(IllegalStateException.class);

      verify(audit)
          .recordExecution(eq(USERNAME), eq(DEV_SOURCE), any(), anyLong(), eq("FAILED"));
    }
  }

  @Nested
  @DisplayName("语句规划")
  class Planning {

    @Test
    @DisplayName("执行序为删除 → 更新 → 新增，参数与方言引用正确")
    void plansInDeleteUpdateInsertOrder() {
      structureVisible(DEV_SOURCE);
      Map<String, Object> insertValues = new LinkedHashMap<>();
      insertValues.put("name", "a");
      insertValues.put("note", null);
      Map<String, Object> key = new LinkedHashMap<>();
      key.put("id", 5);
      Map<String, Object> set = new LinkedHashMap<>();
      set.put("note", null);
      ResultEditResponses response = service().save(
          SESSION_TOKEN, USERNAME,
          new ResultEditRequest(
              DEV_SOURCE, List.of("orders"),
              List.of(new ResultEditRequest.DeleteChange(key)),
              List.of(new ResultEditRequest.UpdateChange(key, set)),
              List.of(new ResultEditRequest.InsertChange(insertValues)),
              null));

      // 汇总确认预览即执行序：删除 → 更新 → 新增
      assertThat(response).isInstanceOf(ResultEditResponses.ConfirmationRequired.class);
      List<ResultEditResponses.StatementPreview> planned =
          ((ResultEditResponses.ConfirmationRequired) response).statements();
      assertThat(planned).extracting(ResultEditResponses.StatementPreview::section)
          .containsExactly("DELETE", "UPDATE", "INSERT");
      assertThat(planned.get(0).sql()).isEqualTo("DELETE FROM `orders` WHERE `id` = ?");
      assertThat(planned.get(1).sql()).isEqualTo("UPDATE `orders` SET `note` = ? WHERE `id` = ?");
      assertThat(planned.get(1).params()).containsExactly(null, 5);
      assertThat(planned.get(2).sql()).isEqualTo("INSERT INTO `orders` (`name`, `note`) VALUES (?, ?)");
      assertThat(planned.get(2).params()).containsExactly("a", null);
    }

    @Test
    @DisplayName("确认绑定文本含参数渲染（值变即失效的地基）")
    void bindsParamsIntoConfirmationText() {
      String bound = ResultEditSql.boundText(
          "UPDATE `t` SET `n` = ? WHERE `id` = ?", List.of("x", 5));
      assertThat(bound).contains("\"x\"").contains("5").contains("-- 参数:");
    }
  }

  private static List<String> tokensOf(ResultEditResponses.ConfirmationRequired confirmation) {
    return confirmation.statements().stream()
        .map(ResultEditResponses.StatementPreview::confirmationToken)
        .toList();
  }

  private static ResultEditRequest withTokens(ResultEditRequest base, List<String> tokens) {
    return new ResultEditRequest(
        base.sourceId(), base.tableParts(), base.deletes(), base.updates(), base.inserts(),
        tokens);
  }

  private static ResultEditRequest requestWithDifferentValue() {
    Map<String, Object> key = new LinkedHashMap<>();
    key.put("id", 5);
    Map<String, Object> set = new LinkedHashMap<>();
    set.put("name", "别的值");
    return new ResultEditRequest(
        PROD_SOURCE, List.of("orders"), List.of(),
        List.of(new ResultEditRequest.UpdateChange(key, set)), List.of(), null);
  }

  private void doThrowForOpenTransaction() {
    org.mockito.Mockito.doThrow(
            new TransactionStateException(
                TransactionManager.CODE_TRANSACTION_OPEN,
                "结果编辑保存使用独立短事务，请先提交或回滚当前事务"))
        .when(transactions)
        .requireNoOpenTransaction(any(), any(), any());
  }

  private static DbConsoleProperties properties(int maxChanges) {
    DbConsoleProperties.DataSourceProperties dev =
        new DbConsoleProperties.DataSourceProperties(
            DEV_SOURCE,
            "MySQL 测试库",
            DbConsoleProperties.DataSourceEnvironment.DEVELOPMENT,
            DbConsoleProperties.DatabaseType.MYSQL,
            DbConsoleProperties.CompatibilityMode.NATIVE,
            "jdbc:mysql://127.0.0.1:1/never",
            "db-user",
            "DB_CREDENTIAL");
    DbConsoleProperties.DataSourceProperties prod =
        new DbConsoleProperties.DataSourceProperties(
            PROD_SOURCE,
            "DM8 生产库",
            DbConsoleProperties.DataSourceEnvironment.PRODUCTION,
            DbConsoleProperties.DatabaseType.DM8,
            DbConsoleProperties.CompatibilityMode.NATIVE,
            "jdbc:dm://127.0.0.1:1/never",
            "db-user",
            "DB_CREDENTIAL");
    return new DbConsoleProperties(
        new DbConsoleProperties.LoginProperties(USERNAME, "test-secret"),
        new DbConsoleProperties.SessionProperties(Duration.ofHours(8)),
        new DbConsoleProperties.ExecutionProperties(
            Duration.ofSeconds(30), 20_000, 8, 100, 1_000, 65_536, 10_485_760),
        new DbConsoleProperties.TransactionProperties(Duration.ofMinutes(10)),
        new DbConsoleProperties.AuditProperties("./target/test-audit", Duration.ofDays(30)),
        null,
        new DbConsoleProperties.ResultEditProperties(maxChanges),
        List.of(dev, prod));
  }
}
