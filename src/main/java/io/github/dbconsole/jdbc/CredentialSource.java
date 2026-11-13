package io.github.dbconsole.jdbc;

/** 凭据引用的解析来源：生产实现读环境变量，测试提供内存映射。 */
@FunctionalInterface
public interface CredentialSource {

  String resolve(String reference);

  /** 生产实现：从环境变量解析凭据引用。 */
  static CredentialSource fromEnvironment() {
    return System::getenv;
  }
}
