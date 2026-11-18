package io.github.dbconsole.dialect;

import io.github.dbconsole.config.DbConsoleProperties.CompatibilityMode;
import io.github.dbconsole.config.DbConsoleProperties.DatabaseType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 可编辑结果资格检测（F-17 / GLOSSARY：可编辑结果）：识别「简单单表 SELECT」——
 * 无 WITH 前缀、无 DISTINCT / UNION 族 / GROUP BY / HAVING / 窗口函数 / 聚合函数，
 * FROM 为单一基表引用（可带库或模式限定与别名，无 JOIN / 逗号并列 / 派生表）——
 * 并提取去引号的来源表限定名部分。WHERE / ORDER BY / LIMIT / OFFSET / FETCH 不影响资格。
 * 判不出即返回空，由调用方整体只读降级（fail-closed，语法兜底在数据库）。
 * 与 {@link SqlStatementAnalyzer} 同源的词法器与深度扫描习惯；纯函数可复用。
 */
public final class SingleTableSelectAnalyzer {

  /** 行折叠聚合（检测到即整体只读）：常见 MySQL / DM8 方言集，未知函数按表达式列降级。 */
  private static final Set<String> ROW_FOLDING_AGGREGATES =
      Set.of(
          "COUNT", "SUM", "AVG", "MIN", "MAX", "GROUP_CONCAT", "STRING_AGG", "ARRAY_AGG",
          "LISTAGG", "MEDIAN", "STDDEV", "VARIANCE", "BIT_AND", "BIT_OR", "BIT_XOR",
          "JSON_ARRAYAGG", "JSON_OBJECTAGG", "WM_CONCAT");

  /** 表引用结束后允许出现的主句关键字；其余（JOIN / 逗号 / UNION / FOR…）一律判多表或超范围。 */
  private static final Set<String> CLAUSE_KEYWORDS =
      Set.of("WHERE", "ORDER", "LIMIT", "OFFSET", "FETCH");

  private static final SingleTableSelectAnalyzer MYSQL_FAMILY =
      new SingleTableSelectAnalyzer(true, true);
  private static final SingleTableSelectAnalyzer OTHER_FAMILY =
      new SingleTableSelectAnalyzer(false, false);

  private final SqlTokenizer tokenizer;

  private SingleTableSelectAnalyzer(boolean hashLineComments, boolean backslashEscapes) {
    this.tokenizer = new SqlTokenizer(hashLineComments, backslashEscapes);
  }

  /** 与 {@link SqlStatementAnalyzer#forDialect} 同一套方言族判定。 */
  public static SingleTableSelectAnalyzer forDialect(DatabaseType type, CompatibilityMode mode) {
    return DialectFamily.isMysqlFamily(type, mode) ? MYSQL_FAMILY : OTHER_FAMILY;
  }

  /**
   * 检测简单单表 SELECT 并返回去引号的来源表限定名部分（如 {@code db.orders} →
   * {@code [db, orders]}）；不满足资格返回空。只看最外层（深度 0）记号：
   * 括号内的子查询不参与判定，其所在选择项按表达式列在标记阶段降级。
   */
  public Optional<List<String>> detect(String sql) {
    List<SqlTokenizer.Token> tokens = significantTokens(sql);
    if (tokens.isEmpty() || !tokens.get(0).isWord("SELECT")) {
      return Optional.empty();
    }
    int index = 1;
    if (index < tokens.size() && tokens.get(index).isWord("DISTINCT")) {
      return Optional.empty();
    }
    if (index < tokens.size() && tokens.get(index).isWord("ALL")) {
      index++;
    }
    int fromIndex = scanSelectList(tokens, index);
    if (fromIndex < 0) {
      return Optional.empty();
    }
    return tableReferenceAfter(tokens, fromIndex + 1);
  }

  /** 扫描选择列表直至最外层 FROM；命中任何行折叠特征（聚合 / 窗口 / 集合 / 分组）即不合格。 */
  private int scanSelectList(List<SqlTokenizer.Token> tokens, int from) {
    int depth = 0;
    for (int index = from; index < tokens.size(); index++) {
      SqlTokenizer.Token token = tokens.get(index);
      if (token.type() == SqlTokenizer.TokenType.LPAREN) {
        depth++;
      } else if (token.type() == SqlTokenizer.TokenType.RPAREN) {
        depth = Math.max(0, depth - 1);
      } else if (depth == 0) {
        if (token.isWord("FROM")) {
          return index;
        }
        if (token.isWord("UNION") || token.isWord("INTERSECT") || token.isWord("EXCEPT")
            || token.isWord("MINUS")) {
          return -1;
        }
        if (token.isWord("GROUP") || token.isWord("HAVING") || token.isWord("OVER")) {
          return -1;
        }
        if (isRowFoldingAggregate(tokens, index)) {
          return -1;
        }
      }
    }
    return -1;
  }

  /** 聚合函数调用：聚合名后紧跟左括号（深度 0）。 */
  private boolean isRowFoldingAggregate(List<SqlTokenizer.Token> tokens, int index) {
    SqlTokenizer.Token token = tokens.get(index);
    return token.type() == SqlTokenizer.TokenType.WORD
        && ROW_FOLDING_AGGREGATES.contains(token.text().toUpperCase(Locale.ROOT))
        && index + 1 < tokens.size()
        && tokens.get(index + 1).type() == SqlTokenizer.TokenType.LPAREN;
  }

  /** 解析 FROM 后的单一表引用（限定名 + 可选别名），之后必须是主句关键字或语句末尾。 */
  private Optional<List<String>> tableReferenceAfter(List<SqlTokenizer.Token> tokens, int start) {
    int index = start;
    if (index >= tokens.size() || !isNameToken(tokens.get(index))) {
      return Optional.empty();
    }
    List<String> parts = new ArrayList<>();
    parts.add(dequote(tokens.get(index)));
    index++;
    while (index + 1 < tokens.size()
        && isDot(tokens.get(index))
        && isNameToken(tokens.get(index + 1))) {
      parts.add(dequote(tokens.get(index + 1)));
      index += 2;
    }
    index = skipAlias(tokens, index);
    if (index < tokens.size()) {
      SqlTokenizer.Token next = tokens.get(index);
      boolean clauseKeyword =
          next.type() == SqlTokenizer.TokenType.WORD
              && CLAUSE_KEYWORDS.contains(next.text().toUpperCase(Locale.ROOT));
      if (!clauseKeyword) {
        return Optional.empty();
      }
    }
    return Optional.of(List.copyOf(parts));
  }

  /** 跳过可选别名（AS name 或裸 name；裸别名不吞主句关键字）。 */
  private int skipAlias(List<SqlTokenizer.Token> tokens, int index) {
    if (index >= tokens.size()) {
      return index;
    }
    SqlTokenizer.Token token = tokens.get(index);
    if (token.isWord("AS")) {
      return index + 1 < tokens.size() && isNameToken(tokens.get(index + 1))
          ? index + 2
          : index;
    }
    if (isNameToken(token) && !isClauseKeyword(token)) {
      return index + 1;
    }
    return index;
  }

  private boolean isClauseKeyword(SqlTokenizer.Token token) {
    return token.type() == SqlTokenizer.TokenType.WORD
        && CLAUSE_KEYWORDS.contains(token.text().toUpperCase(Locale.ROOT));
  }

  private static boolean isNameToken(SqlTokenizer.Token token) {
    return token.type() == SqlTokenizer.TokenType.WORD
        || token.type() == SqlTokenizer.TokenType.QUOTED_IDENTIFIER;
  }

  private static boolean isDot(SqlTokenizer.Token token) {
    return token.type() == SqlTokenizer.TokenType.OTHER && token.text().equals(".");
  }

  /** 引用标识符去包裹并还原内部双写引号；普通单词原样保留大小写。 */
  private static String dequote(SqlTokenizer.Token token) {
    String text = token.text();
    if (token.type() != SqlTokenizer.TokenType.QUOTED_IDENTIFIER) {
      return text;
    }
    char quote = text.charAt(0);
    String inner = text.substring(1, text.length() - 1);
    return inner.replace(String.valueOf(quote) + quote, String.valueOf(quote));
  }

  private List<SqlTokenizer.Token> significantTokens(String sql) {
    return tokenizer.tokenize(sql == null ? "" : sql).stream()
        .filter(
            token ->
                token.type() != SqlTokenizer.TokenType.COMMENT
                    && token.type() != SqlTokenizer.TokenType.SEMICOLON)
        .toList();
  }
}
