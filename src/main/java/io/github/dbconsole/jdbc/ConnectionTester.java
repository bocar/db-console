package io.github.dbconsole.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 连接测试：按数据源配置实际建立一次 JDBC 连接并分类失败原因。 */
public final class ConnectionTester {

  private static final Logger log = LoggerFactory.getLogger(ConnectionTester.class);

  private final DataSourceRegistry registry;
  private final ConnectionFactory connections;

  public ConnectionTester(DataSourceRegistry registry, ConnectionFactory connections) {
    this.registry = registry;
    this.connections = connections;
  }

  public ConnectionTestOutcome test(String sourceId) {
    ResolvedDataSource source = registry.resolve(sourceId);
    try (Connection ignored = connections.open(source)) {
      return ConnectionTestOutcome.success();
    } catch (SQLException exception) {
      ConnectionFailureKind kind = ConnectionFailureKind.classify(exception);
      // 日志只记分类与 SQLState：驱动原始消息可能内嵌账号或主机，不得落盘
      log.warn(
          "连接测试失败 sourceId={} kind={} sqlState={}",
          source.id(),
          kind,
          exception.getSQLState());
      return ConnectionTestOutcome.failure(kind);
    }
  }
}
