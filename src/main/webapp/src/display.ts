import type { DatabaseType, Environment, HistoryEntry, QueryValue } from './types'

const ENVIRONMENT_TEXT: Record<Environment, string> = {
  DEVELOPMENT: '开发',
  TEST: '测试',
  PRODUCTION: '生产',
}

const ENVIRONMENT_CLASS: Record<Environment, string> = {
  PRODUCTION: 'badge-production',
  TEST: 'badge-testing',
  DEVELOPMENT: 'badge-development',
}

const DATABASE_TYPE_TEXT: Record<DatabaseType, string> = {
  MYSQL: 'MySQL',
  DM8: '达梦 DM8',
  ORACLE: 'Oracle',
  SQL_SERVER: 'SQL Server',
  TIDB: 'TiDB',
  OCEANBASE: 'OceanBase',
}

export function displayEnvironment(environment: Environment): string {
  return ENVIRONMENT_TEXT[environment]
}

export function environmentBadgeClass(environment: Environment): string {
  return ENVIRONMENT_CLASS[environment]
}

export function displayDatabaseType(type: DatabaseType): string {
  return DATABASE_TYPE_TEXT[type]
}

export function formatDuration(durationMs: number): string {
  return `${durationMs} ms`
}

export function formatClockTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('zh-CN', { hour12: false })
}

export function displayHistoryStatus(entry: Pick<HistoryEntry, 'status' | 'uncommitted'>): string {
  if (entry.uncommitted) return '未提交'
  return entry.status === 'SUCCEEDED' ? '成功' : '失败'
}

/** 单元格显示：SQL NULL 与空字符串区分显示（F-08）。 */
export function displayQueryValue(value: QueryValue): string {
  if (value === null) return 'NULL'
  if (value === '') return '空字符串'
  return String(value)
}

/** 确认要求的原因说明（F-09）。 */
export type ConfirmationReason = 'PRODUCTION_WRITE' | 'MISSING_WHERE' | 'IMPLICIT_COMMIT_DDL'

export function confirmationReasonText(reason: ConfirmationReason): string {
  switch (reason) {
    case 'PRODUCTION_WRITE':
      return '目标环境为生产环境，所有数据变更都必须逐条确认。'
    case 'MISSING_WHERE':
      return '此更新或删除没有最外层 WHERE 条件，可能影响全表数据。'
    case 'IMPLICIT_COMMIT_DDL':
      return '此 DDL 会触发隐式提交，成功后无法随事务回滚。'
  }
}
