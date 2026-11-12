export interface DataSource {
  id: string
  name: string
  environment: '生产' | '测试' | '开发'
  database: string
  currentSchema: string
  type: string
}

export interface ColumnDefinition {
  name: string
  type: string
  nullable: boolean
  defaultValue: string | null
  primaryKey: boolean
  comment: string
}

export interface TableDefinition {
  name: string
  comment: string
  columns: ColumnDefinition[]
}

export interface QueryColumn {
  name: string
  type: string
}

export type QueryValue = string | null
export type QueryRow = QueryValue[]

export interface HistoryEntry {
  id: number
  sourceId: string
  sql: string
  time: string
  duration: string
  status: '成功' | '未提交' | '失败'
}
