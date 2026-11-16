import type {
  ConfirmationRequired,
  DatabaseType,
  Environment,
  HistoryEntry,
  QueryValue,
  ScriptStatementStatus,
  StatementKind,
} from './types'

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

/** 确认要求的原因（F-09），从 wire 类型派生。 */
export type ConfirmationReason = ConfirmationRequired['reason']

const CONFIRMATION_REASON_TEXT: Record<ConfirmationReason, string> = {
  PRODUCTION_WRITE: '目标环境为生产环境，所有数据变更都必须逐条确认。',
  MISSING_WHERE: '此更新或删除没有最外层 WHERE 条件，可能影响全表数据。',
  IMPLICIT_COMMIT_DDL: '此 DDL 会触发隐式提交，成功后无法随事务回滚。',
}

export function confirmationReasonText(reason: ConfirmationReason): string {
  return CONFIRMATION_REASON_TEXT[reason]
}

/** 脚本语句类别（F-13），从 wire 类型派生。 */
const STATEMENT_KIND_TEXT: Record<StatementKind, string> = {
  SELECT: '查询',
  INSERT: '插入',
  UPDATE: '更新',
  DELETE: '删除',
  DESCRIBE: '结构',
  CREATE_TABLE: '建表',
  ALTER_TABLE: '改表',
  DROP_TABLE: '删表',
  COMMENT_ON: '注释',
  OTHER: '其他',
}

export function displayStatementKind(kind: StatementKind): string {
  return STATEMENT_KIND_TEXT[kind]
}

const SCRIPT_STATUS_TEXT: Record<ScriptStatementStatus, string> = {
  PENDING: '待执行',
  RUNNING: '执行中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
  UNKNOWN_OUTCOME: '结果未知',
  SKIPPED: '未执行',
}

export function displayScriptStatus(status: ScriptStatementStatus): string {
  return SCRIPT_STATUS_TEXT[status]
}

/** 脚本停止类终态：失败、取消或结果未知——脚本循环据此停发后续语句（F-13 失败即停）。 */
export function isStopStatus(status: ScriptStatementStatus): boolean {
  return status === 'FAILED' || status === 'CANCELLED' || status === 'UNKNOWN_OUTCOME'
}
