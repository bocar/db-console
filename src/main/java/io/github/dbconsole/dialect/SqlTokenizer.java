package io.github.dbconsole.dialect;

import java.util.ArrayList;
import java.util.List;

/**
 * 方言感知的 SQL 词法器：只做 tokenize，不做语法判定。
 * 字符串与引用标识符整块成词，注释整块成词——分号因此不会被误当语句边界。
 */
final class SqlTokenizer {

  enum TokenType {
    WORD,
    STRING,
    QUOTED_IDENTIFIER,
    COMMENT,
    SEMICOLON,
    LPAREN,
    RPAREN,
    COMMA,
    OTHER
  }

  record Token(TokenType type, String text) {
    boolean isWord(String keyword) {
      return type == TokenType.WORD && text.equalsIgnoreCase(keyword);
    }
  }

  private final boolean hashLineComments;
  private final boolean backslashEscapes;

  SqlTokenizer(boolean hashLineComments, boolean backslashEscapes) {
    this.hashLineComments = hashLineComments;
    this.backslashEscapes = backslashEscapes;
  }

  List<Token> tokenize(String sql) {
    List<Token> tokens = new ArrayList<>();
    int index = 0;
    int length = sql.length();
    while (index < length) {
      char current = sql.charAt(index);
      if (Character.isWhitespace(current)) {
        index++;
        continue;
      }
      if (current == '-' && index + 1 < length && sql.charAt(index + 1) == '-') {
        index = consumeLineComment(sql, index, tokens);
      } else if (current == '#' && hashLineComments) {
        // MySQL 系的 # 一律是行注释，直至行尾（DM8 不识别 # 注释）
        index = consumeLineComment(sql, index, tokens);
      } else if (current == '/' && index + 1 < length && sql.charAt(index + 1) == '*') {
        index = consumeBlockComment(sql, index, tokens);
      } else if (current == '\'') {
        index = consumeQuoted(sql, index, tokens, '\'', TokenType.STRING);
      } else if (current == '"') {
        index = consumeQuoted(sql, index, tokens, '"', TokenType.QUOTED_IDENTIFIER);
      } else if (current == '`') {
        index = consumeQuoted(sql, index, tokens, '`', TokenType.QUOTED_IDENTIFIER);
      } else if (isWordCharacter(current)) {
        index = consumeWord(sql, index, tokens);
      } else {
        TokenType type =
            switch (current) {
              case ';' -> TokenType.SEMICOLON;
              case '(' -> TokenType.LPAREN;
              case ')' -> TokenType.RPAREN;
              case ',' -> TokenType.COMMA;
              default -> TokenType.OTHER;
            };
        tokens.add(new Token(type, String.valueOf(current)));
        index++;
      }
    }
    return tokens;
  }

  private int consumeLineComment(String sql, int start, List<Token> tokens) {
    int end = sql.indexOf('\n', start);
    int stop = end == -1 ? sql.length() : end;
    tokens.add(new Token(TokenType.COMMENT, sql.substring(start, stop)));
    return stop;
  }

  private int consumeBlockComment(String sql, int start, List<Token> tokens) {
    int end = sql.indexOf("*/", start + 2);
    int stop = end == -1 ? sql.length() : end + 2;
    tokens.add(new Token(TokenType.COMMENT, sql.substring(start, stop)));
    return stop;
  }

  /** 单引号字符串 / 双引号与反引号引用标识符；重复引号为转义，MySQL 字符串支持反斜杠转义。 */
  private int consumeQuoted(
      String sql, int start, List<Token> tokens, char quote, TokenType type) {
    int index = start + 1;
    int length = sql.length();
    while (index < length) {
      char current = sql.charAt(index);
      if (backslashEscapes && current == '\\' && index + 1 < length) {
        index += 2;
        continue;
      }
      if (current == quote) {
        if (index + 1 < length && sql.charAt(index + 1) == quote) {
          index += 2;
          continue;
        }
        tokens.add(new Token(type, sql.substring(start, index + 1)));
        return index + 1;
      }
      index++;
    }
    tokens.add(new Token(type, sql.substring(start)));
    return length;
  }

  private int consumeWord(String sql, int start, List<Token> tokens) {
    int index = start;
    int length = sql.length();
    while (index < length && isWordCharacter(sql.charAt(index))) {
      index++;
    }
    tokens.add(new Token(TokenType.WORD, sql.substring(start, index)));
    return index;
  }

  private boolean isWordCharacter(char value) {
    return Character.isLetterOrDigit(value) || value == '_' || value == '$';
  }
}
