package io.github.dbconsole.sql;

/** 执行终态响应的亲缘标记：各响应族形状不合并，仅共享审计终态归纳（F-10）。 */
public interface TerminalResponse {

  /** 审计终态由响应变体穷尽定义，调用方不拼装状态串。 */
  String auditStatus();
}
