package io.github.dbconsole.resultedit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * 结果编辑保存请求（F-17 / GLOSSARY：暂存更改）：三类变更（删除 → 更新 → 新增 的执行序）
 * 与按语句序的确认凭据。值类型为 null（SQL NULL）、字符串、数字或布尔；空串语义不传输（Q11）。
 *
 * @param tableParts 来源表限定名部分（来自可编辑结果标记，去引号）
 * @param confirmationTokens 与生成语句按序对应的确认凭据（未呈现为 null）
 */
public record ResultEditRequest(
    @NotBlank String sourceId,
    @NotNull List<String> tableParts,
    @NotNull List<DeleteChange> deletes,
    @NotNull List<UpdateChange> updates,
    @NotNull List<InsertChange> inserts,
    List<String> confirmationTokens) {

  /** 删除：key 必须恰好覆盖全部主键列。 */
  public record DeleteChange(@NotNull Map<String, Object> key) {}

  /** 修改：仅变更列；key 必须恰好覆盖全部主键列。 */
  public record UpdateChange(
      @NotNull Map<String, Object> key, @NotNull Map<String, Object> set) {}

  /** 新增：只携带提供值的列，其余走库端默认。 */
  public record InsertChange(@NotNull Map<String, Object> values) {}

  /** 第 statementIndex 条语句的呈现凭据；未提供为 null。 */
  public String tokenAt(int statementIndex) {
    if (confirmationTokens == null || statementIndex < 0
        || statementIndex >= confirmationTokens.size()) {
      return null;
    }
    return confirmationTokens.get(statementIndex);
  }

  /** 变更总数（上限判定用）。 */
  public int changeCount() {
    return deletes.size() + updates.size() + inserts.size();
  }
}
