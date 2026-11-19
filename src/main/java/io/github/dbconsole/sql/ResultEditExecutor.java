package io.github.dbconsole.sql;

import io.github.dbconsole.dialect.ResultEditSql;
import io.github.dbconsole.dialect.StatementKind;
import io.github.dbconsole.jdbc.ConnectionFactory;
import io.github.dbconsole.jdbc.ConnectionFailureKind;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.resultedit.ResultEditResponses;
import io.github.dbconsole.sql.RunningQuery;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 结果编辑批次执行器（F-17 / ADR-0006）：独立连接上的单一显式短事务——
 * 按删除 → 更新 → 新增顺序逐条参数化执行，任一失败整体回滚并定位失败变更；
 * 写入或提交的超时、断连与取消意图归为执行结果未知（F-10，不自动重试）。
 * 语句间检查取消意图，当前语句通过挂载的 PreparedStatement 支持取消。
 */
public class ResultEditExecutor {

  private static final Logger log = LoggerFactory.getLogger(ResultEditExecutor.class);
  private static final int DRIVER_MESSAGE_LIMIT = 500;

  private final ConnectionFactory connections;

  ResultEditExecutor(ConnectionFactory connections) {
    this.connections = connections;
  }

  /** 已规划的单条语句：段内序号与生成结果（所属段由 kind().name() 派生）。 */
  public record PlannedStatement(int changeIndex, ResultEditSql.Generated generated) {

    /** 所属变更段的 wire 名（DELETE/UPDATE/INSERT）。 */
    String section() {
      return generated().kind().name();
    }
  }

  public ResultEditResponses execute(
      ResolvedDataSource source,
      List<PlannedStatement> planned,
      RunningQuery running,
      int timeoutSeconds) {
    long startedAt = System.nanoTime();
    Connection connection = null;
    PlannedStatement current = null;
    boolean committing = false;
    try {
      connection = connections.open(source);
      connection.setAutoCommit(false);
      List<ResultEditResponses.AppliedStatement> applied = new ArrayList<>(planned.size());
      for (PlannedStatement statement : planned) {
        if (running.cancelRequested()) {
          rollbackQuietly(connection);
          log.warn("结果编辑保存被取消，整体回滚 sourceId={} elapsedMs={}",
              source.id(), elapsedMillisSince(startedAt));
          return new ResultEditResponses.Unknown("保存已取消，写入结果未知，请核实数据库状态");
        }
        current = statement;
        try (PreparedStatement prepared =
            connection.prepareStatement(statement.generated().sql())) {
          running.attachForBatch(prepared);
          prepared.setQueryTimeout(timeoutSeconds);
          bindParams(prepared, statement.generated().params());
          int affected = prepared.executeUpdate();
          if (statement.generated().kind() == StatementKind.DELETE
              || statement.generated().kind() == StatementKind.UPDATE) {
            if (affected == 0) {
              rollbackQuietly(connection);
              return new ResultEditResponses.Failed(
                  statement.section(), statement.changeIndex(),
                  "行不存在（可能已被删除或主键被他人修改），本次保存已整体回滚",
                  elapsedMillisSince(startedAt));
            }
          }
          applied.add(
              new ResultEditResponses.AppliedStatement(
                  applied.size(), statement.section(), statement.changeIndex(), affected));
        }
      }
      committing = true;
      connection.commit();
      log.info("结果编辑保存提交完成 sourceId={} statements={} elapsedMs={}",
          source.id(), planned.size(), elapsedMillisSince(startedAt));
      return new ResultEditResponses.Applied(applied, elapsedMillisSince(startedAt));
    } catch (SQLException exception) {
      rollbackQuietly(connection);
      return classifyFailure(
          source, exception, running, current, committing, elapsedMillisSince(startedAt));
    } finally {
      if (connection != null) {
        try {
          connection.close();
        } catch (SQLException closeFailure) {
          // 关闭失败不影响执行结果。
          // §4 要求 try-with-resources，此处为已记录的例外：TWRC 的 close 抛出会丢弃
          // 已定返回值（提交成功会被误报为异常），与 StatementExecution 同款取舍。
          log.debug("批次连接关闭失败 sourceId={}", source.id());
        }
      }
    }
  }

  /**
   * SQLException 终态分类（与 StatementExecution 同构）：取消意图与断连、超时归为
   * 执行结果未知（F-10 写入语义，整体已回滚仍需核实）；其余定位到失败变更整体回滚。
   * 提交阶段的失败无法定位单条变更，一律批次级呈现。
   */
  private ResultEditResponses classifyFailure(
      ResolvedDataSource source,
      SQLException exception,
      RunningQuery running,
      PlannedStatement current,
      boolean committing,
      long elapsedMs) {
    boolean outcomeUnknown =
        running.cancelRequested()
            || exception instanceof SQLTimeoutException
            || ConnectionFailureKind.classify(exception).isOutcomeUnknown();
    if (outcomeUnknown) {
      log.warn("结果编辑保存结果未知 sourceId={} sqlState={} elapsedMs={}",
          source.id(), exception.getSQLState(), elapsedMs);
      return new ResultEditResponses.Unknown(safeMessage(exception));
    }
    log.warn("结果编辑保存失败并回滚 sourceId={} sqlState={} elapsedMs={}",
        source.id(), exception.getSQLState(), elapsedMs);
    if (committing || current == null) {
      return new ResultEditResponses.Failed(
          null, -1, safeMessage(exception), elapsedMs);
    }
    return new ResultEditResponses.Failed(
        current.section(), current.changeIndex(), safeMessage(exception), elapsedMs);
  }

  private void rollbackQuietly(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      log.warn("结果编辑保存回滚失败 sqlState={}", rollbackFailure.getSQLState());
    }
  }

  /** null 以 setNull(VARCHAR) 绑定（跨驱动最稳的 NULL 形态），其余值交给 setObject。 */
  private static void bindParams(PreparedStatement prepared, List<Object> params)
      throws SQLException {
    for (int index = 0; index < params.size(); index++) {
      Object value = params.get(index);
      if (value == null) {
        prepared.setNull(index + 1, Types.VARCHAR);
      } else {
        prepared.setObject(index + 1, value);
      }
    }
  }

  /** 驱动消息面向用户可定位并截断防护；凭据不出现在驱动错误消息中。 */
  private static String safeMessage(SQLException exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return "数据库执行失败，原因未知，本次保存已整体回滚";
    }
    return message.length() > DRIVER_MESSAGE_LIMIT
        ? message.substring(0, DRIVER_MESSAGE_LIMIT) + "…"
        : message;
  }

  private static long elapsedMillisSince(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }
}
