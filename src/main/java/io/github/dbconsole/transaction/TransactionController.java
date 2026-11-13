package io.github.dbconsole.transaction;

import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.web.SessionAuthInterceptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 手动事务接口（F-16）：开启、提交、回滚与状态查询。 */
@RestController
@RequestMapping("/api/transaction")
public class TransactionController {

  private final TransactionManager transactions;
  private final DataSourceRegistry registry;

  public TransactionController(TransactionManager transactions, DataSourceRegistry registry) {
    this.transactions = transactions;
    this.registry = registry;
  }

  /** 开启当前会话在指定数据源上的手动事务。 */
  @PostMapping("/{sourceId}/begin")
  public TransactionStatus begin(
      @RequestAttribute(SessionAuthInterceptor.SESSION_TOKEN_ATTRIBUTE) String sessionToken,
      @PathVariable String sourceId) {
    transactions.begin(sessionToken, registry.resolve(sourceId));
    return status(sessionToken);
  }

  @PostMapping("/{sourceId}/commit")
  public TransactionStatus commit(
      @RequestAttribute(SessionAuthInterceptor.SESSION_TOKEN_ATTRIBUTE) String sessionToken,
      @PathVariable String sourceId) {
    transactions.commit(sessionToken);
    return new TransactionStatus(false, null);
  }

  @PostMapping("/{sourceId}/rollback")
  public TransactionStatus rollback(
      @RequestAttribute(SessionAuthInterceptor.SESSION_TOKEN_ATTRIBUTE) String sessionToken,
      @PathVariable String sourceId) {
    transactions.rollback(sessionToken);
    return new TransactionStatus(false, null);
  }

  @GetMapping("/{sourceId}")
  public TransactionStatus status(
      @RequestAttribute(SessionAuthInterceptor.SESSION_TOKEN_ATTRIBUTE) String sessionToken,
      @PathVariable String sourceId) {
    return status(sessionToken);
  }

  private TransactionStatus status(String sessionToken) {
    return transactions.activeSource(sessionToken)
        .map(source -> new TransactionStatus(true, source))
        .orElseGet(() -> new TransactionStatus(false, null));
  }

  /** 事务状态：活动时携带绑定的数据源标识。 */
  public record TransactionStatus(boolean active, String sourceId) {}
}
