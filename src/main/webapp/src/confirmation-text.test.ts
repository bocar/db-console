import { describe, expect, it } from 'vitest'
import { renderStatementParams, statementPreviewText } from './confirmation-text'

/** 确认呈现渲染（F-17）：与后端凭据绑定文本（boundText）同形。 */
describe('confirmation-text', () => {
  it('null / 字符串 / 数字 / 布尔可区分，字符串经 JSON 转义', () => {
    expect(renderStatementParams([null, 'x', 5, true])).toBe('[null, "x", 5, true]')
    expect(renderStatementParams(['引"号\\n', 3.14])).toBe('["引\\"号\\\\n", 3.14]')
    expect(renderStatementParams([])).toBe('[]')
  })

  it('语句预览为「SQL + 参数渲染」两行', () => {
    expect(statementPreviewText('UPDATE `t` SET `n` = ? WHERE `id` = ?', ['x', 5]))
      .toBe('UPDATE `t` SET `n` = ? WHERE `id` = ?\n-- 参数: ["x", 5]')
  })
})
