import type { ResultEditValue } from './types'

/**
 * 确认呈现（F-17 深化）：语句 + 参数的规范渲染——与后端确认凭据的绑定文本
 * （ResultEditSql.boundText 的 `-- 参数: [...]`）同源同形，保证「预览 ≈ 被确认的内容」。
 * 字符串经 JSON 转义、null/数字/布尔按字面，与后端 escapeJson 逐字符一致。
 */
export function renderStatementParams(params: readonly ResultEditValue[]): string {
  const rendered = params.map((param) => {
    if (param === null) return 'null'
    if (typeof param === 'string') return JSON.stringify(param)
    return String(param)
  })
  return `[${rendered.join(', ')}]`
}

export function statementPreviewText(sql: string, params: readonly ResultEditValue[]): string {
  return `${sql}\n-- 参数: ${renderStatementParams(params)}`
}
