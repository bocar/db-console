/** 环境枚举，镜像后端 DataSourceEnvironment。 */
export type Environment = 'DEVELOPMENT' | 'TEST' | 'PRODUCTION'

/** 数据库类型枚举，镜像后端 DatabaseType。 */
export type DatabaseType = 'MYSQL' | 'DM8' | 'ORACLE' | 'SQL_SERVER' | 'TIDB' | 'OCEANBASE'

/** 兼容模式枚举，镜像后端 CompatibilityMode。 */
export type CompatibilityMode = 'NATIVE' | 'MYSQL' | 'ORACLE'

/** 数据源信息：浏览器可见的全部字段，不含连接信息。 */
export interface DataSourceInfo {
  id: string
  displayName: string
  environment: Environment
  databaseType: DatabaseType
  compatibilityMode: CompatibilityMode
}

export interface ColumnDefinition {
  name: string
  type: string
  nullable: boolean
  defaultValue: string | null
  primaryKey: boolean
  comment: string
}

export interface IndexDefinition {
  name: string
  unique: boolean
  columns: string[]
}

export interface ForeignKeyDefinition {
  name: string
  columns: string[]
  referencedTable: string
  referencedColumns: string[]
}

export interface TableDefinition {
  name: string
  comment: string
  columns: ColumnDefinition[]
  indexes?: IndexDefinition[]
  foreignKeys?: ForeignKeyDefinition[]
}

export interface QueryColumn {
  name: string
  type: string
}

/** 单元格传输值：null 表示 SQL NULL；数字与布尔按原值传输保精度（F-08）。 */
export type QueryValue = string | number | boolean | null
export type QueryRow = QueryValue[]

export interface SessionInfo {
  username: string
}

/** 连接测试诊断（F-02）：kind 区分认证失败与超时，message 不含凭据。 */
export interface ConnectionTestResult {
  ok: boolean
  kind: 'NONE' | 'AUTHENTICATION' | 'TIMEOUT' | 'UNAVAILABLE' | 'UNKNOWN'
  message: string
}

export interface QueryResult {
  kind: 'QUERY'
  columns: QueryColumn[]
  rows: QueryRow[]
  elapsedMs: number
  truncated: boolean
  /** 服务端分页元数据；DESC 等非分页查询可为空。 */
  page?: number | null
  pageSize?: number | null
  hasNextPage?: boolean | null
  totalRows?: number | null
  totalPages?: number | null
}

export interface UpdateResult {
  kind: 'UPDATE'
  affectedRows: number | null
  elapsedMs: number
  uncommitted: boolean
  /** DDL 语句标识（触发对象树刷新与「独立连接执行」文案）。 */
  implicitCommitDdl: boolean
}

export type ExecutionOutcome = QueryResult | UpdateResult

/** 执行结果未知（F-10）：写入或提交超时/断连且状态无法确认，不自动重试。 */
export interface UnknownOutcome {
  kind: 'UNKNOWN_OUTCOME'
  message: string
}

/** 需要执行确认的响应：凭据绑定会话、数据源与 SQL 文本（F-09），由后端签发。 */
export interface ConfirmationRequired {
  kind: 'CONFIRMATION_REQUIRED'
  reason: 'PRODUCTION_WRITE' | 'MISSING_WHERE' | 'IMPLICIT_COMMIT_DDL'
  implicitCommitDdl: boolean
  confirmationToken: string
}

/** 手动事务状态（F-16）：活动时携带绑定的数据源标识。 */
export interface TransactionStatus {
  active: boolean
  sourceId: string | null
}

/** 会话内历史（wire）：执行状态与事务状态分离。 */
export interface HistoryEntry {
  id: number
  sourceId: string
  sql: string
  executedAt: string
  durationMs: number
  status: 'SUCCEEDED' | 'FAILED'
  uncommitted: boolean
}
