package io.github.dbconsole.jdbc;

import io.github.dbconsole.config.DbConsoleProperties;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * 按数据源配置建立 JDBC 连接的统一入口：连接与读取超时统一注入，
 * 连接测试、语句执行与手动事务共用，避免各处重复拼接驱动属性。
 */
public final class ConnectionFactory {

  private static final int CONNECT_TIMEOUT_MILLIS = 5000;

  private final DbConsoleProperties properties;

  public ConnectionFactory(DbConsoleProperties properties) {
    this.properties = properties;
  }

  public Connection open(ResolvedDataSource source) throws SQLException {
    Properties props = new Properties();
    props.setProperty("user", source.username());
    props.setProperty("password", source.password());
    props.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_MILLIS));
    props.setProperty(
        "socketTimeout",
        String.valueOf(properties.execution().timeout().toMillis() + CONNECT_TIMEOUT_MILLIS));
    return DriverManager.getConnection(source.jdbcUrl(), props);
  }
}
