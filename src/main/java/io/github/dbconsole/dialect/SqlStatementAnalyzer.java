package io.github.dbconsole.dialect;

import io.github.dbconsole.config.DbConsoleProperties.CompatibilityMode;
import io.github.dbconsole.config.DbConsoleProperties.DatabaseType;
import java.util.List;

/**
 * 方言语句分析器（ADR-0001）：单条语句边界判定（字符串、引用标识符与注释中的分号不切分，
 * 允许单个末尾分号）、操作粗分类（含 WITH 的实际操作）与最外层 WHERE 检测。
 * 纯函数，安全兜底由 JDBC 单语句执行与数据库账号权限承担。
 */
public final class SqlStatementAnalyzer {

  private final SqlTokenizer tokenizer;

  private SqlStatementAnalyzer(boolean hashLineComments, boolean backslashEscapes) {
    this.tokenizer = new SqlTokenizer(hashLineComments, backslashEscapes);
  }

  public static SqlStatementAnalyzer forDialect(DatabaseType type, CompatibilityMode mode) {
    boolean mysqlFamily =
        type == DatabaseType.MYSQL
            || type == DatabaseType.TIDB
            || mode == CompatibilityMode.MYSQL;
    return new SqlStatementAnalyzer(mysqlFamily, mysqlFamily);
  }

  public SqlAnalysisResult analyze(String sql) {
    List<SqlTokenizer.Token> tokens = tokenizer.tokenize(sql == null ? "" : sql);
    List<SqlTokenizer.Token> significant =
        tokens.stream().filter(token -> token.type() != SqlTokenizer.TokenType.COMMENT).toList();
    if (significant.isEmpty()) {
      return new RejectedSql(
          SqlAnalysisResult.RejectionReason.EMPTY_OR_COMMENT_ONLY, "空白或纯注释输入");
    }
    long semicolonCount =
        significant.stream()
            .filter(token -> token.type() == SqlTokenizer.TokenType.SEMICOLON)
            .count();
    // 只允许单个末尾分号：出现两个及以上分号即视为多语句（F-04）
    if (semicolonCount > 1) {
      return new RejectedSql(
          SqlAnalysisResult.RejectionReason.MULTIPLE_STATEMENTS, "一次只能执行一条语句");
    }
    List<List<SqlTokenizer.Token>> statements = splitBySemicolon(significant);
    List<List<SqlTokenizer.Token>> nonEmpty =
        statements.stream().filter(part -> !part.isEmpty()).toList();
    if (nonEmpty.size() > 1) {
      return new RejectedSql(
          SqlAnalysisResult.RejectionReason.MULTIPLE_STATEMENTS, "一次只能执行一条语句");
    }
    if (nonEmpty.isEmpty()) {
      return new RejectedSql(
          SqlAnalysisResult.RejectionReason.EMPTY_OR_COMMENT_ONLY, "空白或纯注释输入");
    }
    List<SqlTokenizer.Token> statement = nonEmpty.get(0);
    int mainVerbIndex = mainVerbIndex(statement);
    return new SqlStatement(classify(statement, mainVerbIndex), hasOuterWhere(statement, mainVerbIndex));
  }

  private List<List<SqlTokenizer.Token>> splitBySemicolon(List<SqlTokenizer.Token> tokens) {
    List<List<SqlTokenizer.Token>> parts = new java.util.ArrayList<>();
    List<SqlTokenizer.Token> current = new java.util.ArrayList<>();
    for (SqlTokenizer.Token token : tokens) {
      if (token.type() == SqlTokenizer.TokenType.SEMICOLON) {
        parts.add(current);
        current = new java.util.ArrayList<>();
      } else {
        current.add(token);
      }
    }
    parts.add(current);
    return parts;
  }

  /** 主句动词下标：WITH 语句跳过 CTE 定义，普通语句为 0。 */
  private int mainVerbIndex(List<SqlTokenizer.Token> statement) {
    SqlTokenizer.Token first = statement.get(0);
    if (!first.isWord("WITH")) {
      return 0;
    }
    int index = 1;
    int state = 0; // 0=名字位 1=AS 位 2=组后（COMMA 回 0，否则主句）
    int depth = 0;
    while (index < statement.size()) {
      SqlTokenizer.Token token = statement.get(index);
      switch (token.type()) {
        case LPAREN -> depth++;
        case RPAREN -> depth = Math.max(0, depth - 1);
        default -> {
          // 括号内的 token 不参与 CTE 结构判定
        }
      }
      if (depth == 0) {
        if (state == 0 && token.type() == SqlTokenizer.TokenType.WORD) {
          state = 1;
        } else if (state == 1 && token.isWord("AS")) {
          state = 2;
        } else if (state == 2 && token.type() == SqlTokenizer.TokenType.COMMA) {
          state = 0;
        } else if (state == 2 && token.type() == SqlTokenizer.TokenType.WORD) {
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
      return next != null && next.isWord("TABLE")
          ? switch (verb.text().toUpperCase(java.util.Locale.ROOT)) {
            case "CREATE" -> StatementKind.CREATE_TABLE;
            case "ALTER" -> StatementKind.ALTER_TABLE;
            default -> StatementKind.DROP_TABLE;
          }
          : StatementKind.OTHER;
    }
    if (verb.isWord("COMMENT")) {
      SqlTokenizer.Token next = wordAfter(statement, mainVerbIndex);
      return next != null && next.isWord("ON") ? StatementKind.COMMENT_ON : StatementKind.OTHER;
    }
    return StatementKind.OTHER;
  }

  /** 主句动词之后深度 0 的 WHERE 视为最外层条件；括号内（子查询）不算。 */
  private boolean hasOuterWhere(List<SqlTokenizer.Token> statement, int mainVerbIndex) {
    int depth = 0;
    for (int index = mainVerbIndex; index < statement.size(); index++) {
      SqlTokenizer.Token token = statement.get(index);
      switch (token.type()) {
        case LPAREN -> depth++;
        case RPAREN -> depth = Math.max(0, depth - 1);
        case WORD -> {
          if (depth == 0 && token.isWord("WHERE")) {
            return true;
          }
        }
        default -> {
          // 其他 token 不影响深度与判定
        }
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
