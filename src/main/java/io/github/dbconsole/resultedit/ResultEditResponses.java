package io.github.dbconsole.resultedit;

import io.github.dbconsole.sql.TerminalResponse;
import java.util.List;

/** 结果编辑保存响应的密封层次（wire 契约与前端 types.ts 对齐，F-17）。 */
public sealed interface ResultEditResponses extends TerminalResponse {

  /** 审计终态（F-10）：由响应变体穷尽定义。 */
  @Override
  String auditStatus();

  /** 全部语句已在单一短事务内提交。 */
  record Applied(String kind, List<AppliedStatement> statements, long elapsedMs)
      implements ResultEditResponses {

    public Applied(List<AppliedStatement> statements, long elapsedMs) {
      this("RESULT_EDIT_APPLIED", statements, elapsedMs);
    }

    @Override
    public String auditStatus() {
      return "SUCCEEDED";
    }
  }

  /** 单条已应用语句：所属变更段与段内序号，受影响行数。 */
  record AppliedStatement(int index, String section, int changeIndex, Integer affectedRows) {}

  /** 整体回滚：失败定位到某条变更（section=null 且 changeIndex=-1 表示批次级失败）。 */
  record Failed(String kind, String section, int changeIndex, String message, long elapsedMs)
      implements ResultEditResponses {

    public Failed(String section, int changeIndex, String message, long elapsedMs) {
      this("RESULT_EDIT_FAILED", section, changeIndex, message, elapsedMs);
    }

    @Override
    public String auditStatus() {
      return "FAILED";
    }
  }

  /** 需要执行确认（F-09）：语句与参数的完整预览，需确认项携带新签发凭据。 */
  record ConfirmationRequired(String kind, List<StatementPreview> statements)
      implements ResultEditResponses {

    public ConfirmationRequired(List<StatementPreview> statements) {
      this("RESULT_EDIT_CONFIRMATION", statements);
    }

    @Override
    public String auditStatus() {
      return "SUCCEEDED";
    }
  }

  /** 单条语句预览：SQL（占位符）+ 按序参数；reason/token 仅需确认项携带。 */
  record StatementPreview(
      int index, String section, int changeIndex, String sql, List<Object> params,
      String reason, String confirmationToken) {}

  /** 执行结果未知（F-10）：写入或提交超时、断连或取消，不自动重试；与单语句路径同 wire kind。 */
  record Unknown(String kind, String message) implements ResultEditResponses {

    public Unknown(String message) {
      this("UNKNOWN_OUTCOME", message);
    }

    @Override
    public String auditStatus() {
      return "UNKNOWN";
    }
  }
}
