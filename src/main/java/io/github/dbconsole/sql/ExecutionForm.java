package io.github.dbconsole.sql;

import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.resultedit.ResultEditResponses;
import java.util.List;

/**
 * 执行形态（语句执行编舞深化）：一次「把语句跑到终态」的请求载体。
 * sealed 层级让每种形态只携带自己的事实——批次形态无法携带分页参数、
 * 翻页形态无法携带确认凭据（类型上不可表示）；形态本身即策略，
 * 编舞收口在 {@link ExecutionOrchestrator#run} 一处。泛型 {@code R} 声明该形态的终态响应族。
 *
 * @param <R> 该形态的终态响应族（形状不跨形态合并）
 */
public sealed interface ExecutionForm<R extends TerminalResponse>
    permits ExecutionForm.EditBatchForm, ExecutionForm.PageForm,
        ExecutionForm.ScriptStatementForm, ExecutionForm.WorkbenchQueryForm {

  /** 工作台首次执行（F-04）：SELECT 走首页分页 + 可编辑标记 + 历史；写语句走确认门。 */
  record WorkbenchQueryForm(
      String sessionToken, String username, String sourceId,
      String sql, String confirmationToken)
      implements ExecutionForm<SqlResponses> {}

  /** 脚本单语句（F-13）：恒自动提交（与手动事务互斥）、不分页、不写会话历史。 */
  record ScriptStatementForm(
      String sessionToken, String username, String sourceId,
      String sql, String confirmationToken)
      implements ExecutionForm<SqlResponses> {}

  /** 翻页取数（F-11）：仅只读查询，不重复标记、不写历史、不新开连接。 */
  record PageForm(
      String sessionToken, String username, String sourceId,
      String sql, int page, int pageSize)
      implements ExecutionForm<SqlResponses> {}

  /** 暂存更改批次（F-17）：无条件汇总确认、逐条凭据、独立短事务执行。 */
  record EditBatchForm(
      ResolvedDataSource source,
      String sessionToken,
      String username,
      List<ResultEditExecutor.PlannedStatement> planned,
      List<String> confirmationTokens)
      implements ExecutionForm<ResultEditResponses> {}
}
