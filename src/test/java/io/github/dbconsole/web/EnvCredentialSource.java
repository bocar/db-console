package io.github.dbconsole.web;

import io.github.dbconsole.jdbc.CredentialSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试共享：从环境变量解析凭据，缺失时返回占位值让上下文可启动（测试体由 assumeTrue 跳过）。
 */
@TestConfiguration(proxyBeanMethods = false)
public class EnvCredentialSource {

  @Bean
  @Primary
  CredentialSource testCredentialSource() {
    return ref -> {
      String value = System.getenv(ref);
      return value != null ? value : "placeholder-credential";
    };
  }
}
