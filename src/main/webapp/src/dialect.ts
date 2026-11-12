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
