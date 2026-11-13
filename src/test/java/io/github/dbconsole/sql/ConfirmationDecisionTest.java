package io.github.dbconsole.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dbconsole.config.DbConsoleProperties.DataSourceEnvironment;
import io.github.dbconsole.dialect.SqlStatement;
import io.github.dbconsole.dialect.StatementKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConfirmationDecisionTest {

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "PRODUCTION | INSERT | true | PRODUCTION_WRITE",
      "PRODUCTION | UPDATE | true | PRODUCTION_WRITE",
      "PRODUCTION | DELETE | true | PRODUCTION_WRITE",
      "PRODUCTION | SELECT | true | NOT_REQUIRED",
      "DEVELOPMENT | UPDATE | true | NOT_REQUIRED",
      "DEVELOPMENT | UPDATE | false | MISSING_WHERE",
      "DEVELOPMENT | DELETE | false | MISSING_WHERE",
      "DEVELOPMENT | INSERT | false | NOT_REQUIRED",
      "TEST | DELETE | false | MISSING_WHERE",
      "DEVELOPMENT | CREATE_TABLE | false | IMPLICIT_COMMIT_DDL",
      "PRODUCTION | CREATE_TABLE | true | IMPLICIT_COMMIT_DDL",
      "PRODUCTION | DROP_TABLE | true | IMPLICIT_COMMIT_DDL",
      "PRODUCTION | COMMENT_ON | true | IMPLICIT_COMMIT_DDL",
      "DEVELOPMENT | SELECT | false | NOT_REQUIRED",
      "DEVELOPMENT | DESCRIBE | false | NOT_REQUIRED",
  })
  @DisplayName("确认要求按环境、操作类别与最外层 WHERE 分派（F-09/F-16）")
  void dispatchesByEnvironmentKindAndWhere(
      DataSourceEnvironment environment,
      StatementKind kind,
      boolean hasOuterWhere,
      ConfirmationDecision.Requirement expected) {
    SqlStatement statement = new SqlStatement(kind, hasOuterWhere);

    assertThat(ConfirmationDecision.evaluate(environment, statement)).isEqualTo(expected);
  }
}
