import type { ApiClient } from './client'
import type {
  ConfirmationRequired,
  DataSourceInfo,
  ExecutionOutcome,
  HistoryEntry,
  SessionInfo,
  TableDefinition,
} from '../types'

/** 带错误码的 API 异常：code 供前端分派，message 面向用户。 */
export class ApiClientError extends Error {
  readonly code: string
  readonly status: number

  constructor(code: string, message: string, status: number) {
    super(message)
    this.name = 'ApiClientError'
    this.code = code
    this.status = status
  }
}

/** 真实后端 adapter：Cookie 会话 + 统一错误映射。 */
export class HttpApiClient implements ApiClient {
  private sessionExpiredHandler: (() => void) | null = null

  /** 注册会话失效回调：任何受保护接口返回 401 时触发。 */
  onSessionExpired(handler: () => void): void {
    this.sessionExpiredHandler = handler
  }

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    let response: Response
    try {
      response = await fetch(path, {
        method,
        headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
        body: body !== undefined ? JSON.stringify(body) : undefined,
      })
    } catch {
      throw new ApiClientError('NETWORK_ERROR', '网络请求失败，请检查服务是否可达。', 0)
    }
    if (response.status === 401 && path !== '/api/auth/login') {
      this.sessionExpiredHandler?.()
      throw new ApiClientError('UNAUTHENTICATED', '登录会话已失效，请重新登录。', 401)
    }
    if (!response.ok) {
      throw await this.toApiError(response)
    }
    if (response.status === 204) {
      return undefined as T
    }
    return (await response.json()) as T
  }

  private async toApiError(response: Response): Promise<ApiClientError> {
    let code = 'REQUEST_FAILED'
    let message = '请求失败，请稍后重试。'
    try {
      const body = (await response.json()) as { code?: string; message?: string }
      if (body.code) code = body.code
      if (body.message) message = body.message
    } catch {
      // 保持默认错误信息
    }
    return new ApiClientError(code, message, response.status)
  }

  auth = {
    login: (username: string, password: string): Promise<SessionInfo> =>
      this.request<SessionInfo>('POST', '/api/auth/login', { username, password }),
    logout: (): Promise<void> => this.request<void>('POST', '/api/auth/logout'),
    session: (): Promise<SessionInfo> => this.request<SessionInfo>('GET', '/api/auth/session'),
  }

  dataSources = {
    list: (): Promise<DataSourceInfo[]> => this.request<DataSourceInfo[]>('GET', '/api/data-sources'),
    testConnection: (id: string): Promise<void> =>
      this.request<void>('POST', `/api/data-sources/${encodeURIComponent(id)}/connection-test`),
  }

  metadata = {
    tables: (sourceId: string): Promise<TableDefinition[]> =>
      this.request<TableDefinition[]>(`GET`, `/api/data-sources/${encodeURIComponent(sourceId)}/tables`),
  }

  sql = {
    execute: (sourceId: string, sql: string, confirmationToken?: string): Promise<ExecutionOutcome | ConfirmationRequired> =>
      this.request<ExecutionOutcome | ConfirmationRequired>('POST', '/api/sql/execute', {
        sourceId,
        sql,
        confirmationToken,
      }),
    cancel: (sourceId: string): Promise<void> =>
      this.request<void>('POST', `/api/sql/${encodeURIComponent(sourceId)}/cancel`),
  }

  transaction = {
    commit: (sourceId: string): Promise<void> =>
      this.request<void>('POST', `/api/transaction/${encodeURIComponent(sourceId)}/commit`),
    rollback: (sourceId: string): Promise<void> =>
      this.request<void>('POST', `/api/transaction/${encodeURIComponent(sourceId)}/rollback`),
  }

  history = {
    list: (sourceId: string): Promise<HistoryEntry[]> =>
      this.request<HistoryEntry[]>(`GET`, `/api/history/${encodeURIComponent(sourceId)}`),
    clear: (sourceId: string): Promise<void> =>
      this.request<void>('POST', `/api/history/${encodeURIComponent(sourceId)}/clear`),
  }
}
