package io.github.dbconsole.transaction;

import io.github.dbconsole.auth.SessionService;
import io.github.dbconsole.jdbc.ConnectionFactory;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 手动事务管理（F-16）：同一登录会话 + 数据源绑定单一 JDBC 连接（自动提交关闭），
 * 提交/回滚作用于整笔事务；退出、会话失效或空闲超时回滚并释放连接。
 * 连接的取得走 ConnectionFactory seam，与执行路径共用。
 */
public class TransactionManager {

  public static final String CODE_TRANSACTION_OPEN = "TRANSACTION_OPEN";
  public static final String CODE_NO_TRANSACTION = "NO_TRANSACTION";

  private static final Logger log = LoggerFactory.getLogger(TransactionManager.class);

  private final ConnectionFactory connections;
  private final SessionService sessions;
  private final Duration idleTimeout;
  private final Clock clock;
  private final Map<String, ActiveTransaction> transactionsByToken = new ConcurrentHashMap<>();

  public TransactionManager(
      ConnectionFactory connections, SessionService sessions, Duration idleTimeout, Clock clock) {
    this.connections = connections;
    this.sessions = sessions;
    this.idleTimeout = idleTimeout;
    this.clock = clock;
  }

  /** 开启事务：连接自动提交关闭并按会话持有；已有未结束事务时拒绝。 */
  public void begin(String sessionToken, ResolvedDataSource source) {
    transactionsByToken.compute(
        sessionToken,
        (token, existing) -> {
          if (existing != null) {
            throw new TransactionStateException(
                CODE_TRANSACTION_OPEN, "已有未提交事务，请先提交或回滚后再开启新事务");
          }
          try {
            Connection connection = connections.open(source);
            connection.setAutoCommit(false);
            return new ActiveTransaction(source.id(), connection, clock.instant());
          } catch (SQLException exception) {
            throw new IllegalStateException("事务连接打开失败：" + exception.getMessage(), exception);
          }
        });
  }

  /** 提交整笔事务并释放连接。 */
  public void commit(String sessionToken) {
    finish(sessionToken, Connection::commit, "提交");
  }

  /** 回滚整笔事务并释放连接。 */
  public void rollback(String sessionToken) {
    finish(sessionToken, Connection::rollback, "回滚");
  }

  public boolean hasActive(String sessionToken) {
    return transactionsByToken.containsKey(sessionToken);
  }

  public Optional<String> activeSource(String sessionToken) {
    ActiveTransaction transaction = transactionsByToken.get(sessionToken);
    return transaction == null ? Optional.empty() : Optional.of(transaction.sourceId());
  }

  /**
   * 事务连接获取 seam：会话有活动事务且数据源一致时返回事务连接（执行方不得关闭），
   * 跨数据源拒绝；无事务返回 null，由调用方走自动提交连接。
   */
  public Connection connectionFor(String sessionToken, String sourceId) {
    ActiveTransaction transaction = transactionsByToken.get(sessionToken);
    if (transaction == null) {
      return null;
    }
    if (!transaction.sourceId().equals(sourceId)) {
      throw new TransactionStateException(
          CODE_TRANSACTION_OPEN, "请先结束当前数据源上的未提交事务，再操作其他数据源");
    }
    transaction.touch(clock.instant());
    return transaction.connection();
  }

  /** 执行语句后刷新事务空闲时钟。 */
  public void touch(String sessionToken) {
    ActiveTransaction transaction = transactionsByToken.get(sessionToken);
    if (transaction != null) {
      transaction.touch(clock.instant());
    }
  }

  /** 登出/会话失效回调：即时回滚该会话的未提交事务。 */
  public void onSessionEnd(String sessionToken) {
    finishQuietly(sessionToken, "会话结束");
  }

  /** 登出事件监听（SessionService 发布）。 */
  @org.springframework.context.event.EventListener(io.github.dbconsole.auth.SessionService.SessionEnded.class)
  public void onSessionEnded(io.github.dbconsole.auth.SessionService.SessionEnded event) {
    onSessionEnd(event.token());
  }

  /** 定时清理（空闲超时/会话失效），由调度器调用。 */
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30_000)
  public void scheduledCleanup() {
    cleanupExpired();
  }

  /** 清理：空闲超时或会话已失效的事务回滚释放（定时调度调用）。 */
  public void cleanupExpired() {
    Instant now = clock.instant();
    for (Map.Entry<String, ActiveTransaction> entry : transactionsByToken.entrySet()) {
      String token = entry.getKey();
      ActiveTransaction transaction = entry.getValue();
      boolean idle = Duration.between(transaction.lastActivity(), now).compareTo(idleTimeout) > 0;
      boolean sessionGone = sessions.authenticate(token).isEmpty();
      if (idle || sessionGone) {
        log.info(
            "事务清理 sourceId={} 原因={} 已回滚", transaction.sourceId(), idle ? "空闲超时" : "会话失效");
        finishQuietly(token, idle ? "空闲超时" : "会话失效");
      }
    }
  }

  private void finish(String sessionToken, JdbcAction action, String name) {
    ActiveTransaction transaction = transactionsByToken.remove(sessionToken);
    if (transaction == null) {
      throw new TransactionStateException(CODE_NO_TRANSACTION, "当前没有未提交事务");
    }
    try (Connection connection = transaction.connection()) {
      action.execute(connection);
    } catch (SQLException exception) {
      throw new IllegalStateException(name + "失败：" + exception.getMessage(), exception);
    }
  }

  private void finishQuietly(String sessionToken, String reason) {
    ActiveTransaction transaction = transactionsByToken.remove(sessionToken);
    if (transaction == null) {
      return;
    }
    try (Connection connection = transaction.connection()) {
      connection.rollback();
    } catch (SQLException exception) {
      log.warn("事务回滚失败（{}）sourceId={}：{}", reason, transaction.sourceId(), exception.getSQLState());
    }
  }

  @FunctionalInterface
  private interface JdbcAction {
    void execute(Connection connection) throws SQLException;
  }
}
