package io.github.dbconsole.script;

import io.github.dbconsole.config.DbConsoleProperties;
import io.github.dbconsole.dialect.RejectedSql;
import io.github.dbconsole.dialect.SqlAnalysisResult;
import io.github.dbconsole.dialect.SqlStatement;
import io.github.dbconsole.dialect.SqlStatementAnalyzer;
import io.github.dbconsole.dialect.StatementKind;
import io.github.dbconsole.jdbc.DataSourceRegistry;
import io.github.dbconsole.jdbc.ResolvedDataSource;
import io.github.dbconsole.sql.ConfirmationDecision;
import io.github.dbconsole.sql.ConfirmationService;
import io.github.dbconsole.sql.SqlRejectException;
import io.github.dbconsole.sql.StatementGates;
import io.github.dbconsole.transaction.TransactionManager;
import io.github.dbconsole.transaction.TransactionStateException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 脚本预校验（F-13）：整份脚本先按方言拆分并逐条分析，任一条未通过即整体无效
 * （fail-closed，逐条报告序号与原因）；通过时逐条给出类型与确认要求，需确认语句
 * 按既有 HMAC 机制逐条签发凭据（绑定会话 + 数据源 + 语句文本，与单语句执行同一不变式）。
 * 文件约束（扩展名、大小、语句数）、BOM 剥离与手动事务互斥在此收口；
 * 拆分与单语句判定全部复用方言层，本类不引入新的 SQL 语义。
 */
public class ScriptAnalyzer {

  private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".sql", ".txt");
  private static final int MAX_FILENAME_LENGTH = 255;
  private static final char BOM = '\uFEFF';

  private final DataSourceRegistry registry;
  private final ConfirmationService confirmations;
  private final TransactionManager transactions;
  private final ScriptRunStore runStore;
  private final DbConsoleProperties properties;

  public ScriptAnalyzer(
      DataSourceRegistry registry,
      ConfirmationService confirmations,
      TransactionManager transactions,
      ScriptRunStore runStore,
      DbConsoleProperties properties) {
    this.registry = registry;
    this.confirmations = confirmations;
    this.transactions = transactions;
    this.runStore = runStore;
    this.properties = properties;
  }

  public ScriptAnalyzeResponse analyze(String sessionToken, ScriptUploadRequest request) {
    ResolvedDataSource source = registry.resolve(request.sourceId());
    validateFilename(request.filename());
    requireNoOpenTransaction(sessionToken, source.id());
    String content = stripBom(request.content());
    if (exceedsUtf8Bytes(content, properties.script().maxFileBytes())) {
      throw new SqlRejectException(
          "SCRIPT_TOO_LARGE",
          "脚本文件超过大小上限（" + properties.script().maxFileBytes() + " 字节）");
    }
    SqlStatementAnalyzer analyzer =
        SqlStatementAnalyzer.forDialect(source.databaseType(), source.compatibilityMode());
    List<String> statementTexts = analyzer.splitStatements(content);
    if (statementTexts.size() > properties.script().maxStatements()) {
      throw new SqlRejectException(
          "SCRIPT_TOO_MANY_STATEMENTS",
          "脚本语句数超过上限（" + properties.script().maxStatements() + " 条）");
    }
    List<ScriptStatementError> errors = new ArrayList<>();
    List<ValidatedStatement> validated = new ArrayList<>();
    for (int position = 0; position < statementTexts.size(); position++) {
      int index = position + 1;
      String text = statementTexts.get(position);
      StatementCheck check = validateStatement(analyzer, text, index);
      if (check.error() != null) {
        errors.add(check.error());
      } else {
        validated.add(new ValidatedStatement(index, text, check.statement()));
      }
    }
    boolean wholeScriptValid = errors.isEmpty();
    // 仅整份通过的脚本入暂存并签发运行标识：失败脚本不可能完整跑完，无需汇总
    String scriptId =
        wholeScriptValid
            ? runStore.store(sessionToken, source.id(), request.filename(), content)
            : null;
    return new ScriptAnalyzeResponse(
        source.id(), request.filename(), scriptId, wholeScriptValid,
        buildPlans(sessionToken, source, validated, wholeScriptValid), errors);
  }

  /**
   * 凭据签发收口（F-13）：仅当整份脚本预校验全部通过时才逐语句签发确认凭据——
   * 「任一条未通过时整份脚本不执行」在服务端的落点是：失败脚本的任何需确认语句
   * 都拿不到执行能力，须修正脚本后重新上传。
   */
  private List<ScriptStatementPlan> buildPlans(
      String sessionToken,
      ResolvedDataSource source,
      List<ValidatedStatement> validated,
      boolean wholeScriptValid) {
    List<ScriptStatementPlan> plans = new ArrayList<>();
    for (ValidatedStatement entry : validated) {
      ConfirmationDecision.Requirement requirement =
          ConfirmationDecision.evaluate(source.environment(), entry.statement());
      boolean confirmationRequired = requirement != ConfirmationDecision.Requirement.NOT_REQUIRED;
      plans.add(
          new ScriptStatementPlan(
              entry.index(),
              entry.text(),
              entry.statement().kind().name(),
              confirmationRequired ? requirement.name() : null,
              entry.statement().kind().isImplicitCommitDdl(),
              wholeScriptValid && confirmationRequired
                  ? confirmations.issue(sessionToken, source.id(), entry.text())
                  : null));
    }
    return plans;
  }

  /** 单条语句校验：长度上限与操作范围走共享门槛（StatementGates），码与规则不分叉。 */
  private StatementCheck validateStatement(
      SqlStatementAnalyzer analyzer, String text, int index) {
    StatementGates.GateViolation lengthViolation =
        StatementGates.lengthViolation(text, properties.execution().maxSqlLength());
    if (lengthViolation != null) {
      return new StatementCheck(
          scriptError(index, text, lengthViolation.code(), lengthViolation.message()), null);
    }
    SqlAnalysisResult analysis = analyzer.analyze(text);
    if (analysis instanceof RejectedSql rejected) {
      return new StatementCheck(
          scriptError(index, text, "INVALID_SQL", "无法解析：" + rejected.message()), null);
    }
    if (analysis instanceof SqlStatement statement) {
      StatementGates.GateViolation scopeViolation = StatementGates.scopeViolation(statement);
      if (scopeViolation != null) {
        return new StatementCheck(
            scriptError(
                index, text, scopeViolation.code(),
                scopeViolation.message() + "（存储过程与触发器脚本不受支持）"),
            null);
      }
      return new StatementCheck(null, statement);
    }
    throw new IllegalStateException("unreachable: SqlAnalysisResult 为密封双变体");
  }

  /** 逐条错误文案统一加条次序号（工作台入口不带序号，码与判定规则共享）。 */
  private static ScriptStatementError scriptError(
      int index, String text, String code, String baseMessage) {
    return new ScriptStatementError(index, text, code, "第 " + index + " 条：" + baseMessage);
  }

  /** 逐字符累计 UTF-8 字节数并提前退出，不物化整份字节数组；代理对按 4 字节计。 */
  private static boolean exceedsUtf8Bytes(String content, long limit) {
    long total = 0;
    int length = content.length();
    for (int index = 0; index < length; index++) {
      char current = content.charAt(index);
      if (Character.isHighSurrogate(current)
          && index + 1 < length
          && Character.isLowSurrogate(content.charAt(index + 1))) {
        total += 4;
        index++;
      } else if (current < 0x80) {
        total += 1;
      } else if (current < 0x800) {
        total += 2;
      } else {
        total += 3;
      }
      if (total > limit) {
        return true;
      }
    }
    return false;
  }

  /** 单条语句校验结果：error 与 statement 二选一非空。 */
  private record StatementCheck(ScriptStatementError error, SqlStatement statement) {}

  /** 预校验通过的语句：序号、原文与分析结果。 */
  private record ValidatedStatement(int index, String text, SqlStatement statement) {}

  /** 脚本执行与手动事务互斥（F-16）：预校验即前置拒绝，避免通过后中途冲突。 */
  private void requireNoOpenTransaction(String sessionToken, String sourceId) {
    if (transactions.connectionFor(sessionToken, sourceId) != null) {
      throw new TransactionStateException(
          TransactionManager.CODE_TRANSACTION_OPEN,
          TransactionManager.MESSAGE_SCRIPT_REQUIRES_AUTO_COMMIT);
    }
  }

  private void validateFilename(String filename) {
    String trimmed = filename.trim();
    if (trimmed.isEmpty() || trimmed.length() > MAX_FILENAME_LENGTH) {
      throw new SqlRejectException("SCRIPT_FILE_TYPE", "文件名缺失或超过 255 字符");
    }
    int dot = trimmed.lastIndexOf('.');
    String extension = dot < 0 ? "" : trimmed.substring(dot).toLowerCase(Locale.ROOT);
    if (!ALLOWED_EXTENSIONS.contains(extension)) {
      throw new SqlRejectException("SCRIPT_FILE_TYPE", "仅支持 .sql 或 .txt 脚本文件");
    }
  }

  private static String stripBom(String content) {
    return content.startsWith(String.valueOf(BOM)) ? content.substring(1) : content;
  }
}
