import type { DataSourceInfo } from './types'

/** 按数据源方言引用标识符（F-03）：MySQL 系用反引号，其余用双引号。 */
export function quoteIdentifier(name: string, source: DataSourceInfo): string {
  const usesBacktick =
    source.databaseType === 'MYSQL' ||
    source.databaseType === 'TIDB' ||
    source.compatibilityMode === 'MYSQL'
  const quote = usesBacktick ? '`' : '"'
  return `${quote}${name.replaceAll(quote, quote + quote)}${quote}`
}

/** 按方言生成“查看表前 N 行”语句（F-03 打开表）。 */
export function selectTopStatement(table: string, source: DataSourceInfo, limit = 100): string {
  return `SELECT *\nFROM ${quoteIdentifier(table, source)}\nLIMIT ${limit};`
}
