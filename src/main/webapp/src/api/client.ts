import { inject, provide, type InjectionKey } from 'vue'
import type {
  CancelOutcome,
  CancelledOutcome,
  ConfirmationRequired,
  ConnectionTestResult,
  DataSourceInfo,
  ExecutionOutcome,
  HistoryEntry,
  QueryResult,
  ScriptAnalyzeResult,
  SessionInfo,
  TableDefinition,
  TransactionStatus,
  UnknownOutcome,
} from '../types'

/**
 * 前端唯一数据缝（ADR-0003）：全部工作台数据访问穿过此接口。
 * MockApiClient 与 HttpApiClient 是它的两个 adapter。
 */
export interface ApiClient {
  auth: {
    login(username: string, password: string): Promise<SessionInfo>
    logout(): Promise<void>
    session(): Promise<SessionInfo>
  }
  dataSources: {
    list(): Promise<DataSourceInfo[]>
    testConnection(id: string): Promise<ConnectionTestResult>
  }
  metadata: {
    tables(sourceId: string): Promise<TableDefinition[]>
    tableStructure(sourceId: string, table: string): Promise<TableDefinition>
    commentStatement(sourceId: string, tableName: string, columnName: string, comment: string): Promise<{ sql: string }>
  }
  sql: {
    execute(sourceId: string, sql: string, confirmationToken?: string): Promise<ExecutionOutcome | ConfirmationRequired | UnknownOutcome>
    page(sourceId: string, sql: string, page: number, pageSize: number): Promise<QueryResult | CancelledOutcome>
    cancel(sourceId: string): Promise<CancelOutcome>
  }
  /** 脚本执行（F-13）：预校验、逐条执行与运行汇总历史。 */
  script: {
    analyze(sourceId: string, filename: string, content: string): Promise<ScriptAnalyzeResult>
    executeStatement(sourceId: string, sql: string, confirmationToken?: string): Promise<ExecutionOutcome | ConfirmationRequired | UnknownOutcome>
    recordRun(sourceId: string, scriptId: string, durationMs: number): Promise<void>
  }
  transaction: {
    begin(sourceId: string): Promise<TransactionStatus>
    commit(sourceId: string): Promise<TransactionStatus>
    rollback(sourceId: string): Promise<TransactionStatus>
    status(sourceId: string): Promise<TransactionStatus>
  }
  history: {
    list(sourceId: string): Promise<HistoryEntry[]>
    clear(sourceId: string): Promise<void>
  }
}

export const API_CLIENT_KEY: InjectionKey<ApiClient> = Symbol('db-console ApiClient')

/** 在应用根部注入 ApiClient adapter（App.vue setup 中调用一次）。 */
export function provideApiClient(client: ApiClient): void {
  provide(API_CLIENT_KEY, client)
}

/** 取得当前注入的 ApiClient；只能在 setup 上下文内调用。 */
export function useApiClient(): ApiClient {
  const client = inject(API_CLIENT_KEY)
  if (!client) {
    throw new Error('ApiClient 未注入：请在应用根部调用 provideApiClient')
  }
  return client
}
