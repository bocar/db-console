package io.github.dbconsole.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 连接测试：按数据源配置实际建立一次 JDBC 连接并分类失败原因。 */
public final class ConnectionTester {

  private static final Logger log = LoggerFactory.getLogger(ConnectionTester.class);
  private static final int TIMEOUT_MILLIS = 5000;

  private final DataSourceRegistry registry;

  public ConnectionTester(DataSourceRegistry registry) {
    this.registry = registry;
  }

  public ConnectionTestOutcome test(String sourceId) {
    ResolvedDataSource source = registry.resolve(sourceId);
    Properties props = new Properties();
    props.setProperty("user", source.username());
    props.setProperty("password", source.password());
    // MySQL 驱动识别的毫秒级超时；DM8 侧超时属性随方言层（T04）统一处理
    props.setProperty("connectTimeout", String.valueOf(TIMEOUT_MILLIS));
    props.setProperty("socketTimeout", String.valueOf(TIMEOUT_MILLIS));
    try (Connection ignored = DriverManager.getConnection(source.jdbcUrl(), props)) {
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
