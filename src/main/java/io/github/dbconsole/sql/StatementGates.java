package io.github.dbconsole.sql;

import io.github.dbconsole.dialect.SqlStatement;
import io.github.dbconsole.dialect.StatementKind;

/**
 * 单语句通用门槛（F-04/F-05）：长度上限与操作范围两条规则的工作台/脚本共享收口。
 * 违规返回码与基础文案（通过返回 null）；工作台直接抛出，脚本预校验加条次序号后
 * 收进逐条错误——文案允许入口分化，码与判定规则不分叉。
 */
public final class StatementGates {

  public static final String CODE_SQL_TOO_LONG = "SQL_TOO_LONG";
  public static final String CODE_OUT_OF_SCOPE = "OUT_OF_SCOPE";

  private StatementGates() {}

  /** 长度门槛：超限返回违规（含上限值），否则 null。 */
  public static GateViolation lengthViolation(String sql, int maxLength) {
    if (sql.length() > maxLength) {
      return new GateViolation(CODE_SQL_TOO_LONG, "SQL 长度超过上限（" + maxLength + " 字符）");
    }
    return null;
  }

  /** 操作范围门槛：范围外动词返回违规，否则 null。 */
  public static GateViolation scopeViolation(SqlStatement statement) {
    if (statement.kind() == StatementKind.OTHER) {
      return new GateViolation(CODE_OUT_OF_SCOPE, "该语句超出允许的操作范围");
    }
    return null;
  }

  /** 门槛违规：错误码与面向用户的基础文案。 */
  public record GateViolation(String code, String message) {}
}
