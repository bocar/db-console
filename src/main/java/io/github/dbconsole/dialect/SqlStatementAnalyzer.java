package io.github.dbconsole.dialect;

import io.github.dbconsole.config.DbConsoleProperties.CompatibilityMode;
import io.github.dbconsole.config.DbConsoleProperties.DatabaseType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 方言语句分析器（ADR-0001）：单条语句边界判定（字符串、引用标识符与注释中的分号不切分，
 * 允许单个末尾分号）、操作粗分类（含 WITH 的实际操作、最外层 INTO 使 SELECT 归入超范围）
 * 与最外层 WHERE 检测。
 * 纯函数，安全兜底由 JDBC 单语句执行与数据库账号权限承担。
 *
 * <p>未闭合的字符串或引用标识符会被吞到输入末尾——结果是整段仍算一条语句，
 * 语法错误交由数据库拒绝（fail-closed，语法兜底不在本层）。
 */
public final class SqlStatementAnalyzer {

  private static final String ONLY_ONE_STATEMENT_MESSAGE = "一次只能执行一条语句";
  private static final SqlStatementAnalyzer MYSQL_FAMILY = new SqlStatementAnalyzer(true, true);
  private static final SqlStatementAnalyzer OTHER_FAMILY = new SqlStatementAnalyzer(false, false);

  /** WITH 头部扫描的状态位：期待 CTE 名 / 期待 AS / 组结束位。 */
  private enum CtePosition {
    NAME,
    AS_KEYWORD,
    AFTER_GROUP
  }

  private final SqlTokenizer tokenizer;

  private SqlStatementAnalyzer(boolean hashLineComments, boolean backslashEscapes) {
    this.tokenizer = new SqlTokenizer(hashLineComments, backslashEscapes);
  }

  /** 分析器无状态可复用：按方言族返回预建实例。 */
  public static SqlStatementAnalyzer forDialect(DatabaseType type, CompatibilityMode mode) {
    boolean mysqlFamily =
        type == DatabaseType.MYSQL
            || type == DatabaseType.TIDB
            || mode == CompatibilityMode.MYSQL;
    return mysqlFamily ? MYSQL_FAMILY : OTHER_FAMILY;
  }

  public SqlAnalysisResult analyze(String sql) {
    List<SqlTokenizer.Token> tokens = tokenizer.tokenize(sql == null ? "" : sql);
    List<SqlTokenizer.Token> significant =
        tokens.stream().filter(token -> token.type() != SqlTokenizer.TokenType.COMMENT).toList();
    if (significant.isEmpty()) {
      return new RejectedSql("空白或纯注释输入");
    }
    long semicolonCount =
        significant.stream()
            .filter(token -> token.type() == SqlTokenizer.TokenType.SEMICOLON)
            .count();
    // 只允许单个末尾分号：出现两个及以上分号即视为多语句（F-04）
    if (semicolonCount > 1) {
      return new RejectedSql(ONLY_ONE_STATEMENT_MESSAGE);
    }
    List<List<SqlTokenizer.Token>> statements = splitBySemicolon(significant);
    List<List<SqlTokenizer.Token>> nonEmpty =
        statements.stream().filter(part -> !part.isEmpty()).toList();
    if (nonEmpty.size() > 1) {
      return new RejectedSql(ONLY_ONE_STATEMENT_MESSAGE);
    }
    if (nonEmpty.isEmpty()) {
      return new RejectedSql("空白或纯注释输入");
    }
    List<SqlTokenizer.Token> statement = nonEmpty.get(0);
    int mainVerbIndex = mainVerbIndex(statement);
    StatementKind kind = classify(statement, mainVerbIndex);
    boolean hasOuterWhere = hasOuterWhere(statement, mainVerbIndex);
    return new SqlStatement(kind, hasOuterWhere);
  }

  /** 移除已校验语句的唯一终结分号，保留原始空白与尾随注释。 */
  public String removeTerminator(String sql) {
    for (SqlTokenizer.Token token : tokenizer.tokenize(sql)) {
      if (token.type() == SqlTokenizer.TokenType.SEMICOLON) {
        return sql.substring(0, token.start()) + sql.substring(token.start() + 1);
      }
    }
    return sql;
  }

  /** 已有顶层分页或锁定子句时，分页须使用派生表保留原子句边界。 */
  public boolean requiresDerivedTableForPagination(String sql) {
    int depth = 0;
    for (SqlTokenizer.Token token : tokenizer.tokenize(sql)) {
      if (token.type() == SqlTokenizer.TokenType.LPAREN) {
        depth++;
      } else if (token.type() == SqlTokenizer.TokenType.RPAREN) {
        depth = Math.max(0, depth - 1);
      } else if (depth == 0
          && (token.isWord("LIMIT") || token.isWord("OFFSET") || token.isWord("FETCH")
              || token.isWord("FOR"))) {
        return true;
      }
    }
    return false;
  }

  private List<List<SqlTokenizer.Token>> splitBySemicolon(List<SqlTokenizer.Token> tokens) {
    List<List<SqlTokenizer.Token>> parts = new ArrayList<>();
    List<SqlTokenizer.Token> current = new ArrayList<>();
    for (SqlTokenizer.Token token : tokens) {
      if (token.type() == SqlTokenizer.TokenType.SEMICOLON) {
        parts.add(current);
        current = new ArrayList<>();
      } else {
        current.add(token);
      }
    }
    parts.add(current);
    return parts;
  }

  /**
   * 主句动词下标：WITH 语句跳过 CTE 定义（名称 [列清单] AS (…) 的重复序列，
   * 含 WITH RECURSIVE 形式——RECURSIVE 被名称位容忍），普通语句为 0。
   */
  private int mainVerbIndex(List<SqlTokenizer.Token> statement) {
    SqlTokenizer.Token first = statement.get(0);
    if (!first.isWord("WITH")) {
      return 0;
    }
    int index = 1;
    CtePosition position = CtePosition.NAME;
    int depth = 0;
    while (index < statement.size()) {
      SqlTokenizer.Token token = statement.get(index);
      if (token.type() == SqlTokenizer.TokenType.LPAREN) {
        depth++;
      } else if (token.type() == SqlTokenizer.TokenType.RPAREN) {
        depth = Math.max(0, depth - 1);
      }
      if (depth == 0) {
        if (position == CtePosition.NAME && token.type() == SqlTokenizer.TokenType.WORD) {
          position = CtePosition.AS_KEYWORD;
        } else if (position == CtePosition.AS_KEYWORD && token.isWord("AS")) {
          position = CtePosition.AFTER_GROUP;
        } else if (position == CtePosition.AFTER_GROUP
            && token.type() == SqlTokenizer.TokenType.COMMA) {
          position = CtePosition.NAME;
        } else if (position == CtePosition.AFTER_GROUP
            && token.type() == SqlTokenizer.TokenType.WORD) {
          return index;
        }
      }
      index++;
    }
    return 0;
  }

  private StatementKind classify(List<SqlTokenizer.Token> statement, int mainVerbIndex) {
    if (mainVerbIndex >= statement.size()
        || statement.get(mainVerbIndex).type() != SqlTokenizer.TokenType.WORD) {
      return StatementKind.OTHER;
    }
    SqlTokenizer.Token verb = statement.get(mainVerbIndex);
    if (verb.isWord("SELECT")) {
      if (hasOuterInto(statement, mainVerbIndex)) {
        return StatementKind.OTHER;
      }
      return StatementKind.SELECT;
    }
    if (verb.isWord("INSERT")) {
      return StatementKind.INSERT;
    }
    if (verb.isWord("UPDATE")) {
      return StatementKind.UPDATE;
    }
    if (verb.isWord("DELETE")) {
      return StatementKind.DELETE;
    }
    if (verb.isWord("DESC") || verb.isWord("DESCRIBE")) {
      return StatementKind.DESCRIBE;
    }
    if (verb.isWord("CREATE") || verb.isWord("ALTER") || verb.isWord("DROP")) {
      SqlTokenizer.Token next = wordAfter(statement, mainVerbIndex);
      if (next == null || !next.isWord("TABLE")) {
        return StatementKind.OTHER;
      }
      String text = verb.text().toUpperCase(Locale.ROOT);
      if ("CREATE".equals(text)) {
        return StatementKind.CREATE_TABLE;
      }
      if ("ALTER".equals(text)) {
        return StatementKind.ALTER_TABLE;
      }
      return StatementKind.DROP_TABLE;
    }
    if (verb.isWord("COMMENT")) {
      SqlTokenizer.Token next = wordAfter(statement, mainVerbIndex);
      return next != null && next.isWord("ON") ? StatementKind.COMMENT_ON : StatementKind.OTHER;
    }
    return StatementKind.OTHER;
  }

  /**
   * SELECT 主句动词后最外层的 INTO（OUTFILE/DUMPFILE/变量赋值）使语句不再是纯只读查询（F-05
   * 只放行原生 SELECT），归入超范围；括号内（子查询/CTE 体）的 INTO 不误拒——
   * MySQL 侧语法本就拒绝 CTE 体内的 INTO，数据库账号权限为最终兜底。
   */
  private boolean hasOuterInto(List<SqlTokenizer.Token> statement, int mainVerbIndex) {
    return hasOuterWord(statement, mainVerbIndex, "INTO");
  }

  /** 主句动词之后深度 0 的 WHERE 视为最外层条件；括号内（子查询）不算。 */
  private boolean hasOuterWhere(List<SqlTokenizer.Token> statement, int mainVerbIndex) {
    return hasOuterWord(statement, mainVerbIndex, "WHERE");
  }

  /** 自 fromIndex 起扫描：仅深度 0 的指定关键词命中（动词位本身不会等于条件关键词）。 */
  private boolean hasOuterWord(
      List<SqlTokenizer.Token> statement, int fromIndex, String keyword) {
    int depth = 0;
    for (int index = fromIndex; index < statement.size(); index++) {
      SqlTokenizer.Token token = statement.get(index);
      if (token.type() == SqlTokenizer.TokenType.LPAREN) {
        depth++;
      } else if (token.type() == SqlTokenizer.TokenType.RPAREN) {
        depth = Math.max(0, depth - 1);
      } else if (depth == 0 && token.isWord(keyword)) {
        return true;
      }
    }
    return false;
  }

  private SqlTokenizer.Token wordAfter(List<SqlTokenizer.Token> statement, int from) {
    for (int index = from + 1; index < statement.size(); index++) {
      if (statement.get(index).type() == SqlTokenizer.TokenType.WORD) {
        return statement.get(index);
      }
    }
    return null;
  }
}
