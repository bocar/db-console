import { inject, provide, type InjectionKey } from 'vue'
import type {
  ConfirmationRequired,
  DataSourceInfo,
  ExecutionOutcome,
  HistoryEntry,
  SessionInfo,
  TableDefinition,
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
    testConnection(id: string): Promise<void>
  }
  metadata: {
    tables(sourceId: string): Promise<TableDefinition[]>
  }
  sql: {
    execute(sourceId: string, sql: string, confirmationToken?: string): Promise<ExecutionOutcome | ConfirmationRequired>
    cancel(sourceId: string): Promise<void>
  }
  transaction: {
    commit(sourceId: string): Promise<void>
    rollback(sourceId: string): Promise<void>
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
