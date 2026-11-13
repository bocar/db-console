package io.github.dbconsole.dialect;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dbconsole.config.DbConsoleProperties.CompatibilityMode;
import io.github.dbconsole.config.DbConsoleProperties.DatabaseType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SqlStatementAnalyzerTest {

  private static final SqlStatementAnalyzer MYSQL =
      SqlStatementAnalyzer.forDialect(DatabaseType.MYSQL, CompatibilityMode.NATIVE);
  private static final SqlStatementAnalyzer DM8 =
      SqlStatementAnalyzer.forDialect(DatabaseType.DM8, CompatibilityMode.NATIVE);

  @Nested
  @DisplayName("语句边界")
  class Boundary {

    @ParameterizedTest
    @ValueSource(strings = {"SELECT 1", " SELECT 1 ", "SELECT 1;", "SELECT 1 ;", "  \n SELECT 1 ; \n "})
    @DisplayName("单条语句与单个末尾分号被接受")
    void acceptsSingleStatementWithOptionalTrailingSemicolon(String sql) {
      assertThat(MYSQL.analyze(sql)).isInstanceOf(SqlStatement.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", ";", "\n\t", "-- nothing", "/* block only */", "-- a\n-- b"})
    @DisplayName("空白与纯注释输入被拒绝")
    void rejectsBlankOrCommentOnly(String sql) {
      assertThat(MYSQL.analyze(sql)).isInstanceOf(RejectedSql.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT 1; SELECT 2",
        "SELECT 1;SELECT 2;",
        "SELECT 1;;",
        "UPDATE t SET x = 1; DELETE FROM t",
    })
    @DisplayName("多语句输入被拒绝")
    void rejectsMultipleStatements(String sql) {
      assertThat(MYSQL.analyze(sql)).isInstanceOf(RejectedSql.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT ';' AS a FROM t",
        "SELECT 'it''s; fine' FROM t",
        "SELECT 'unterminated ; tail",
        "SELECT 1 #_weird; name",
        "SELECT \"semi;colon\" FROM t",
        "SELECT `odd;name` FROM t",
        "SELECT 1 -- trailing; comment",
        "SELECT 1 /* ; */ FROM t",
        "SELECT 'a' /* ; */ , \"b;c\" , `d;e` FROM t WHERE x = ';' ",
    })
    @DisplayName("字符串、引用标识符与注释中的分号不切分语句")
    void semicolonsInStringsCommentsAndQuotedIdentifiersDoNotSplit(String sql) {
      assertThat(MYSQL.analyze(sql)).isInstanceOf(SqlStatement.class);
    }

    @Test
    @DisplayName("DM8 双引号标识符中的分号不切分")
    void dm8QuotedIdentifierSemicolonDoesNotSplit() {
      assertThat(DM8.analyze("SELECT \"odd;name\" FROM t")).isInstanceOf(SqlStatement.class);
    }

    @Test
    @DisplayName("MySQL 的 # 行注释含分号不切分；DM8 中 # 非注释因此分号生效")
    void hashCommentIsMysqlOnly() {
      assertThat(MYSQL.analyze("SELECT 1 # hash; comment")).isInstanceOf(SqlStatement.class);
      assertThat(DM8.analyze("SELECT 1 # hash; comment")).isInstanceOf(RejectedSql.class);
    }

    @Test
    @DisplayName("反斜杠转义仅 MySQL 系生效：同一语句 DM8 视为多语句")
    void backslashEscapeIsMysqlOnly() {
      assertThat(MYSQL.analyze("SELECT 'it\\'s; fine'")).isInstanceOf(SqlStatement.class);
      assertThat(DM8.analyze("SELECT 'it\\'s; fine'")).isInstanceOf(RejectedSql.class);
    }
  }

  @Nested
  @DisplayName("操作分类")
  class Classification {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SELECT * FROM t | SELECT",
        "select * from t | SELECT",
        "SeLeCt 1 | SELECT",
        "INSERT INTO t VALUES (1) | INSERT",
        "update t set x = 1 | UPDATE",
        "DELETE FROM t | DELETE",
        "DESC t | DESCRIBE",
        "DESCRIBE t | DESCRIBE",
        "desc db.t | DESCRIBE",
        "CREATE TABLE t (id INT) | CREATE_TABLE",
        "create table if not exists t (id int) | CREATE_TABLE",
        "ALTER TABLE t ADD COLUMN c INT | ALTER_TABLE",
        "DROP TABLE t | DROP_TABLE",
        "drop table if exists t | DROP_TABLE",
        "COMMENT ON COLUMN t.c IS '备注' | COMMENT_ON",
        "GRANT SELECT ON t TO u | OTHER",
        "TRUNCATE TABLE t | OTHER",
        "CREATE INDEX i ON t (c) | OTHER",
    })
    @DisplayName("前导关键词决定操作类型（大小写不敏感）")
    void classifiesByLeadingKeyword(String sql, StatementKind expected) {
      SqlStatement statement = (SqlStatement) MYSQL.analyze(sql);
      assertThat(statement.kind()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "WITH c AS (SELECT 1) SELECT * FROM c | SELECT",
        "WITH c AS (SELECT 1) INSERT INTO t SELECT * FROM c | INSERT",
        "WITH a AS (SELECT 1), b AS (SELECT 2) UPDATE t SET x = 1 | UPDATE",
        "with c as (select 1) delete from t | DELETE",
        "WITH RECURSIVE r AS (SELECT 1) INSERT INTO t SELECT * FROM r | INSERT",
        "WITH c (a, b) AS (SELECT 1, 2) DELETE FROM t | DELETE",
    })
    @DisplayName("WITH 语句按主句动词分类（含多 CTE）")
    void classifiesWithByMainVerb(String sql, StatementKind expected) {
      SqlStatement statement = (SqlStatement) MYSQL.analyze(sql);
      assertThat(statement.kind()).isEqualTo(expected);
    }

    @Test
    @DisplayName("前导注释不干扰分类")
    void leadingCommentsIgnoredForClassification() {
      SqlStatement statement = (SqlStatement) MYSQL.analyze("-- setup\n/* note */ SELECT 1");
      assertThat(statement.kind()).isEqualTo(StatementKind.SELECT);
    }

    @Test
    @DisplayName("DM8 的 COMMENT ON 不误判为 DML")
    void dm8CommentOnClassified() {
      SqlStatement statement = (SqlStatement) DM8.analyze("COMMENT ON TABLE t IS '表说明'");
      assertThat(statement.kind()).isEqualTo(StatementKind.COMMENT_ON);
    }
  }

  @Nested
  @DisplayName("最外层 WHERE 检测")
  class OuterWhere {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "UPDATE t SET x = 1 WHERE id = 1 | true",
        "UPDATE t SET x = 1 | false",
        "UPDATE t SET x = (SELECT MAX(y) FROM s WHERE z = 1) | false",
        "DELETE FROM t WHERE id IN (SELECT id FROM s WHERE ok = 1) | true",
        "DELETE FROM t | false",
        "SELECT * FROM t WHERE x = (SELECT y FROM s WHERE z = 1) | true",
        "SELECT * FROM (SELECT * FROM s WHERE x = 1) sub | false",
        "SELECT * FROM (SELECT * FROM s WHERE x = 1) sub WHERE y = 2 | true",
        "WITH c AS (SELECT * FROM s WHERE x = 1) DELETE FROM t | false",
        "WITH c AS (SELECT * FROM s WHERE x = 1) DELETE FROM t WHERE y IN (SELECT 1) | true",
    })
    @DisplayName("只有最外层（深度 0）的 WHERE 计数")
    void onlyDepthZeroWhereCounts(String sql, boolean expected) {
      SqlStatement statement = (SqlStatement) MYSQL.analyze(sql);
      assertThat(statement.hasOuterWhere()).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("分页终结符移除")
  class PaginationTerminator {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SELECT 1|SELECT 1",
        "SELECT 1;|SELECT 1",
        "SELECT ';' FROM t|SELECT ';' FROM t",
        "SELECT \"a;b\" FROM t;|SELECT \"a;b\" FROM t",
        "SELECT `a;b` FROM t;|SELECT `a;b` FROM t",
        "SELECT 1; -- tail ; more|SELECT 1 -- tail ; more",
        "SELECT 1 /* ; */|SELECT 1 /* ; */",
    })
    @DisplayName("仅移除语句终结分号；字符串、引号标识符与注释中的分号保留")
    void removesOnlyStatementTerminator(String sql, String expected) {
      assertThat(MYSQL.removeTerminator(sql)).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("分页派生表判定")
  class PaginationDerivedTable {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SELECT * FROM t ORDER BY id|false",
        "SELECT * FROM t LIMIT 10|true",
        "SELECT * FROM t LIMIT 10 OFFSET 5|true",
        "SELECT * FROM t OFFSET 5|true",
        "SELECT * FROM t FETCH FIRST 10 ROWS ONLY|true",
        "SELECT * FROM t FOR UPDATE|true",
        "SELECT 'limit' FROM t|false",
        "SELECT \"limit\" FROM t|false",
        "SELECT `limit` FROM t|false",
        "SELECT 1 -- limit note|false",
        "SELECT 1 /* limit note */|false",
        "SELECT limitation FROM t|false",
        "SELECT * FROM (SELECT * FROM s LIMIT 5) sub|false",
        "SELECT * FROM (SELECT * FROM s LIMIT 5) sub WHERE x = 1|false",
    })
    @DisplayName("仅顶层 LIMIT/OFFSET/FETCH/FOR 需要派生表包装")
    void onlyTopLevelPagingClausesRequireDerivedTable(String sql, boolean expected) {
      assertThat(MYSQL.requiresDerivedTableForPagination(sql)).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("SELECT INTO 边界")
  class SelectInto {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SELECT * FROM t INTO OUTFILE '/tmp/export.csv' | OTHER",
        "SELECT * FROM t INTO DUMPFILE '/tmp/blob' | OTHER",
        "SELECT * INTO OUTFILE '/tmp/export.csv' FROM t | OTHER",
        "SELECT id, name FROM t INTO @a, @b | OTHER",
        "SELECT id FROM t INTO @@sort_buffer_size | OTHER",
        "select * from t into outfile '/tmp/export.csv' | OTHER",
        "WITH c AS (SELECT 1) SELECT * FROM c INTO OUTFILE '/tmp/export.csv' | OTHER",
    })
    @DisplayName("SELECT ... INTO（OUTFILE/DUMPFILE/变量）不再按只读查询放行")
    void selectWithTopLevelIntoIsNotReadOnly(String sql, StatementKind expected) {
      SqlStatement statement = (SqlStatement) MYSQL.analyze(sql);
      assertThat(statement.kind()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SELECT 'into' FROM t|SELECT",
        "SELECT \"into\" FROM t|SELECT",
        "SELECT `into` FROM t|SELECT",
        "SELECT 1 -- into note|SELECT",
        "SELECT 1 /* into */ FROM t|SELECT",
        "SELECT limitation FROM t|SELECT",
        "SELECT * FROM (SELECT 1 INTO @a) sub|SELECT",
    })
    @DisplayName("字符串、引号标识符、注释与括号内的 INTO 不影响只读分类")
    void intoInLiteralsCommentsAndSubqueriesStaysReadOnly(String sql, StatementKind expected) {
      SqlStatement statement = (SqlStatement) MYSQL.analyze(sql);
      assertThat(statement.kind()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "INSERT INTO t VALUES (1) | INSERT",
        "WITH c AS (SELECT 1) INSERT INTO t SELECT * FROM c | INSERT",
        "UPDATE t SET x = (SELECT y INTO @a FROM s) WHERE id = 1 | UPDATE",
    })
    @DisplayName("INSERT/UPDATE 语法中的 INTO 保持原分类")
    void intoInsideOtherStatementsKeepsTheirKind(String sql, StatementKind expected) {
      SqlStatement statement = (SqlStatement) MYSQL.analyze(sql);
      assertThat(statement.kind()).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("类别谓词")
  class KindPredicates {

    @Test
    @DisplayName("写入、DDL 与查询谓词覆盖确认规则")
    void predicatesMatchConfirmationRules() {
      assertThat(StatementKind.INSERT.isWrite()).isTrue();
      assertThat(StatementKind.UPDATE.isWrite()).isTrue();
      assertThat(StatementKind.DELETE.isWrite()).isTrue();
      assertThat(StatementKind.SELECT.isWrite()).isFalse();
      assertThat(StatementKind.CREATE_TABLE.isImplicitCommitDdl()).isTrue();
      assertThat(StatementKind.ALTER_TABLE.isImplicitCommitDdl()).isTrue();
      assertThat(StatementKind.DROP_TABLE.isImplicitCommitDdl()).isTrue();
      assertThat(StatementKind.COMMENT_ON.isImplicitCommitDdl()).isTrue();
    }
  }
}
