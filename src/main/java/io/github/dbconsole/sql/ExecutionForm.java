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
    permits ExecutionForm.EditBatchForm {

  /** 暂存更改批次（F-17）：无条件汇总确认、逐条凭据、独立短事务执行。 */
  record EditBatchForm(
      ResolvedDataSource source,
      String sessionToken,
      String username,
      String mutexMessage,
      List<ResultEditExecutor.PlannedStatement> planned,
      List<String> confirmationTokens)
      implements ExecutionForm<ResultEditResponses> {}
}
