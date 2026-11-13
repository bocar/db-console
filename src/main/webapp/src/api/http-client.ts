import type { ApiClient } from './client'
import type {
  CancelOutcome,
  CancelledOutcome,
  ConfirmationRequired,
  ConnectionTestResult,
  DataSourceInfo,
  ExecutionOutcome,
  HistoryEntry,
  QueryResult,
  SessionInfo,
  TableDefinition,
  TransactionStatus,
} from '../types'

/** 错误码单一来源：镜像后端 ApiError.code，新增码需同步（手写 wire 契约，ADR-0003）。 */
const API_ERROR_CODES = [
  'UNAUTHENTICATED',
  'BAD_CREDENTIALS',
  'INVALID_REQUEST',
  'NETWORK_ERROR',
  'REQUEST_FAILED',
  'TRANSACTION_OPEN',
  'UNKNOWN_DATA_SOURCE',
  'INVALID_SQL',
  'OUT_OF_SCOPE',
  'SQL_TOO_LONG',
  'CONCURRENCY_EXCEEDED',
  'SESSION_QUERY_RUNNING',
  'QUERY_TIMEOUT',
  'DATABASE_ERROR',
  'INVALID_PAGE_SIZE',
  'PAGINATION_NOT_ALLOWED',
] as const

export type ApiErrorCode = (typeof API_ERROR_CODES)[number]

/** 带错误码的 API 异常：code 供前端分派，message 面向用户。 */
export class ApiClientError extends Error {
  readonly code: ApiErrorCode
  readonly status: number

  constructor(code: ApiErrorCode, message: string, status: number) {
    super(message)
    this.name = 'ApiClientError'
    this.code = code
    this.status = status
  }
}

function toErrorCode(value: unknown): ApiErrorCode {
  return typeof value === 'string' && (API_ERROR_CODES as readonly string[]).includes(value)
    ? (value as ApiErrorCode)
    : 'REQUEST_FAILED'
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
    let code: ApiErrorCode = 'REQUEST_FAILED'
    let message = '请求失败，请稍后重试。'
    try {
      const body = (await response.json()) as { code?: string; message?: string }
      if (body.code) code = toErrorCode(body.code)
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
    testConnection: (id: string): Promise<ConnectionTestResult> =>
      this.request<ConnectionTestResult>(
        'POST', `/api/data-sources/${encodeURIComponent(id)}/connection-test`),
  }

  metadata = {
    tables: (sourceId: string): Promise<TableDefinition[]> =>
      this.request<TableDefinition[]>(`GET`, `/api/data-sources/${encodeURIComponent(sourceId)}/tables`),
    tableStructure: (sourceId: string, table: string): Promise<TableDefinition> =>
      this.request<TableDefinition>(
        'GET',
        `/api/data-sources/${encodeURIComponent(sourceId)}/tables/${encodeURIComponent(table)}/structure`),
    commentStatement: (
      sourceId: string, tableName: string, columnName: string, comment: string,
    ): Promise<{ sql: string }> =>
      this.request<{ sql: string }>(
        'POST',
        `/api/data-sources/${encodeURIComponent(sourceId)}/comment-statement`,
        { tableName, columnName, comment }),
  }

  sql = {
    execute: (sourceId: string, sql: string, confirmationToken?: string): Promise<ExecutionOutcome | ConfirmationRequired> =>
      this.request<ExecutionOutcome | ConfirmationRequired>('POST', '/api/sql/execute', {
        sourceId,
        sql,
        confirmationToken,
      }),
    page: (
      sourceId: string,
      sql: string,
      page: number,
      pageSize: number,
    ): Promise<QueryResult | CancelledOutcome> =>
      this.request<QueryResult | CancelledOutcome>('POST', '/api/sql/page', {
        sourceId,
        sql,
        page,
        pageSize,
      }),
    cancel: (sourceId: string): Promise<CancelOutcome> =>
      this.request<CancelOutcome>('POST', `/api/sql/${encodeURIComponent(sourceId)}/cancel`),
  }

  transaction = {
    begin: (sourceId: string): Promise<TransactionStatus> =>
      this.request<TransactionStatus>('POST', `/api/transaction/${encodeURIComponent(sourceId)}/begin`),
    commit: (sourceId: string): Promise<TransactionStatus> =>
      this.request<TransactionStatus>('POST', `/api/transaction/${encodeURIComponent(sourceId)}/commit`),
    rollback: (sourceId: string): Promise<TransactionStatus> =>
      this.request<TransactionStatus>('POST', `/api/transaction/${encodeURIComponent(sourceId)}/rollback`),
    status: (sourceId: string): Promise<TransactionStatus> =>
      this.request<TransactionStatus>('GET', `/api/transaction/${encodeURIComponent(sourceId)}`),
  }

  history = {
    list: (sourceId: string): Promise<HistoryEntry[]> =>
      this.request<HistoryEntry[]>(`GET`, `/api/history/${encodeURIComponent(sourceId)}`),
    clear: (sourceId: string): Promise<void> =>
      this.request<void>('DELETE', `/api/history/${encodeURIComponent(sourceId)}`),
  }
}
