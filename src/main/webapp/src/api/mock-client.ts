import type { ApiClient } from './client'
import { ApiClientError } from './http-client'
import type {
  ConfirmationRequired,
  DataSourceInfo,
  ExecutionOutcome,
  HistoryEntry,
  QueryColumn,
  QueryRow,
  SessionInfo,
  TableDefinition,
} from '../types'

/**
 * 测试 adapter：以固定数据与模拟行为满足 ApiClient 接口。
 * 模拟的后端规则（确认分派、隐式提交 DDL 拦截、事务未提交状态）只在此处存在，
 * 生产代码永远通过 HttpApiClient 访问真实后端。
 */
export class MockApiClient implements ApiClient {
  private static nextId = 1

  private readonly delayMs: number

  private loggedIn = false
  private historyEntries: HistoryEntry[] = []
  private transactionPendingBySource = new Map<string, boolean>()
  private pendingExecution: (() => void) | null = null

  /** delayMs 可注入：测试传 0 以免等待。 */
  constructor(delayMs = 850) {
    this.delayMs = delayMs
  }

  readonly availableSources: DataSourceInfo[] = [
    { id: 'mysql-work-note', displayName: 'MySQL 工作笔记库', environment: 'DEVELOPMENT', databaseType: 'MYSQL', compatibilityMode: 'NATIVE' },
    { id: 'dm8-navic', displayName: '达梦 NAVIC 库', environment: 'PRODUCTION', databaseType: 'DM8', compatibilityMode: 'NATIVE' },
    { id: 'analytics-dev', displayName: '分析开发库', environment: 'DEVELOPMENT', databaseType: 'MYSQL', compatibilityMode: 'NATIVE' },
  ]

  readonly tables: TableDefinition[] = [
    {
      name: 'orders',
      comment: '客户订单主表',
      columns: [
        { name: 'id', type: 'BIGINT', nullable: false, defaultValue: null, primaryKey: true, comment: '订单主键' },
        { name: 'order_no', type: 'VARCHAR(64)', nullable: false, defaultValue: null, primaryKey: false, comment: '业务订单号' },
        { name: 'customer_id', type: 'BIGINT', nullable: false, defaultValue: null, primaryKey: false, comment: '客户 ID' },
        { name: 'status', type: 'VARCHAR(24)', nullable: false, defaultValue: "'PENDING'", primaryKey: false, comment: '订单状态' },
        { name: 'total_amount', type: 'DECIMAL(18,2)', nullable: false, defaultValue: '0.00', primaryKey: false, comment: '订单总金额' },
        { name: 'remark', type: 'VARCHAR(512)', nullable: true, defaultValue: null, primaryKey: false, comment: '订单备注' },
        { name: 'created_at', type: 'DATETIME', nullable: false, defaultValue: 'CURRENT_TIMESTAMP', primaryKey: false, comment: '创建时间' },
        { name: 'updated_at', type: 'DATETIME', nullable: false, defaultValue: 'CURRENT_TIMESTAMP', primaryKey: false, comment: '更新时间' },
      ],
    },
    {
      name: 'customers',
      comment: '客户信息',
      columns: [
        { name: 'id', type: 'BIGINT', nullable: false, defaultValue: null, primaryKey: true, comment: '客户主键' },
        { name: 'customer_name', type: 'VARCHAR(128)', nullable: false, defaultValue: null, primaryKey: false, comment: '客户名称' },
        { name: 'phone', type: 'VARCHAR(32)', nullable: true, defaultValue: null, primaryKey: false, comment: '联系电话' },
        { name: 'created_at', type: 'DATETIME', nullable: false, defaultValue: 'CURRENT_TIMESTAMP', primaryKey: false, comment: '创建时间' },
      ],
    },
    {
      name: 'order_items',
      comment: '订单明细',
      columns: [
        { name: 'id', type: 'BIGINT', nullable: false, defaultValue: null, primaryKey: true, comment: '明细主键' },
        { name: 'order_id', type: 'BIGINT', nullable: false, defaultValue: null, primaryKey: false, comment: '订单 ID' },
        { name: 'sku_id', type: 'BIGINT', nullable: false, defaultValue: null, primaryKey: false, comment: '商品 ID' },
        { name: 'quantity', type: 'INT', nullable: false, defaultValue: '1', primaryKey: false, comment: '购买数量' },
      ],
    },
  ]

  private readonly queryColumns: QueryColumn[] = [
    { name: 'id', type: 'BIGINT' },
    { name: 'order_no', type: 'VARCHAR(64)' },
    { name: 'customer_name', type: 'VARCHAR(128)' },
    { name: 'status', type: 'VARCHAR(24)' },
    { name: 'total_amount', type: 'DECIMAL(18,2)' },
    { name: 'created_at', type: 'DATETIME' },
  ]

  private readonly queryRows: QueryRow[] = Array.from({ length: 57 }, (_, index) => {
    const customerNames = ['星河贸易有限公司', '青禾科技', '南山工作室', '远帆物流', '沐光设计', '森屿零售']
    const statuses = ['PAID', 'PENDING', 'SHIPPED', 'REFUNDED']
    return [
      String(920000000000000000n + BigInt(index)),
      `SO2026${String(11012001 + index)}`,
      index % 9 === 8 ? null : customerNames[index % customerNames.length],
      statuses[index % statuses.length],
      Number(((index * 7919 + 12800) / 100).toFixed(2)),
      `2026-11-${String((index % 12) + 1).padStart(2, '0')} ${String(9 + (index % 9)).padStart(2, '0')}:${String((index * 7) % 60).padStart(2, '0')}:18`,
    ]
  })

  auth = {
    login: async (username: string, password: string): Promise<SessionInfo> => {
      await this.delay(30)
      if (username !== 'admin' || password !== 'db-console') {
        throw new ApiClientError('BAD_CREDENTIALS', '用户名或密码不正确', 401)
      }
      this.loggedIn = true
      return { username }
    },
    logout: async (): Promise<void> => {
      this.loggedIn = false
    },
    session: async (): Promise<SessionInfo> => {
      await this.delay(10)
      if (!this.loggedIn) {
        throw new ApiClientError('UNAUTHENTICATED', '未登录或会话已失效，请重新登录', 401)
      }
      return { username: 'admin' }
    },
  }

  dataSources = {
    list: async (): Promise<DataSourceInfo[]> => [...this.availableSources],
    testConnection: async (id: string): Promise<void> => {
      this.requireSource(id)
      await this.delay(120)
    },
  }

  metadata = {
    tables: async (sourceId: string): Promise<TableDefinition[]> => {
      this.requireSource(sourceId)
      return [...this.tables]
    },
  }

  sql = {
    execute: (sourceId: string, sql: string, confirmationToken?: string): Promise<ExecutionOutcome | ConfirmationRequired> =>
      new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          this.pendingExecution = null
          try {
            resolve(this.resolveExecution(sourceId, sql, confirmationToken))
          } catch (error) {
            reject(error instanceof Error ? error : new Error('执行失败'))
          }
        }, this.delayMs)
        this.pendingExecution = () => {
          clearTimeout(timer)
          this.pendingExecution = null
          reject(new Error('查询已取消'))
        }
      }),
    cancel: async (sourceId: string): Promise<void> => {
      this.requireSource(sourceId)
      this.pendingExecution?.()
    },
  }

  transaction = {
    commit: async (sourceId: string): Promise<void> => {
      await this.delay(80)
      this.finishTransaction(sourceId)
    },
    rollback: async (sourceId: string): Promise<void> => {
      await this.delay(80)
      this.finishTransaction(sourceId)
    },
  }

  history = {
    list: async (sourceId: string): Promise<HistoryEntry[]> => {
      this.requireSource(sourceId)
      return this.historyEntries.filter((entry) => entry.sourceId === sourceId)
    },
    clear: async (sourceId: string): Promise<void> => {
      this.requireSource(sourceId)
      this.historyEntries = this.historyEntries.filter((entry) => entry.sourceId !== sourceId)
    },
  }

  /** 测试辅助：构造初始历史。 */
  seedHistory(entries: HistoryEntry[]): void {
    this.historyEntries = [...entries]
  }

  /** 测试辅助：读取某数据源的事务未提交状态。 */
  isTransactionPending(sourceId: string): boolean {
    return this.transactionPendingBySource.get(sourceId) ?? false
  }

  private requireSource(id: string): void {
    if (!this.availableSources.some((source) => source.id === id)) {
      throw new ApiClientError('REQUEST_FAILED', '未知数据源标识', 404)
    }
  }

  private finishTransaction(sourceId: string): void {
    this.transactionPendingBySource.set(sourceId, false)
    this.historyEntries = this.historyEntries.map((entry) =>
      entry.sourceId === sourceId && entry.uncommitted ? { ...entry, uncommitted: false } : entry,
    )
  }

  private delay(milliseconds: number): Promise<void> {
    const scaled = this.delayMs === 0 ? 0 : milliseconds
    return new Promise((resolve) => setTimeout(resolve, scaled))
  }

  private resolveExecution(
    sourceId: string,
    sql: string,
    confirmationToken?: string,
  ): ExecutionOutcome | ConfirmationRequired {
    const statement = sql.trim()
    const source = this.availableSources.find((item) => item.id === sourceId) ?? this.availableSources[0]
    const isDdl = /^(create|alter|drop|comment)\b/i.test(statement)
    const isWrite = /^(insert|update|delete)\b/i.test(statement)
    const missingWhere = /^(update|delete)\b/i.test(statement) && !/\bwhere\b/i.test(statement)

    if (isDdl && this.isTransactionPending(sourceId)) {
      throw new ApiClientError('TRANSACTION_OPEN', '请先提交或回滚当前事务，再执行隐式提交 DDL。', 409)
    }
    const requiresConfirmation = isDdl || (source.environment === 'PRODUCTION' && isWrite) || missingWhere
    if (requiresConfirmation && !confirmationToken) {
      const confirmation: ConfirmationRequired = {
        kind: 'CONFIRMATION_REQUIRED',
        reason: isDdl ? 'IMPLICIT_COMMIT_DDL' : source.environment === 'PRODUCTION' ? 'PRODUCTION_WRITE' : 'MISSING_WHERE',
        implicitCommitDdl: isDdl,
        confirmationToken: `mock-token-${MockApiClient.nextId++}`,
      }
      return confirmation
    }

    const elapsedMs = statement.length % 70 + 34
    this.historyEntries = [
      {
        id: MockApiClient.nextId++,
        sourceId,
        sql: statement,
        executedAt: new Date().toISOString(),
        durationMs: elapsedMs,
        status: 'SUCCEEDED',
        uncommitted: isWrite,
      },
      ...this.historyEntries,
    ]
    if (isDdl) {
      return { kind: 'UPDATE', affectedRows: null, elapsedMs, uncommitted: false }
    }
    if (isWrite) {
      this.transactionPendingBySource.set(sourceId, true)
      return { kind: 'UPDATE', affectedRows: 1, elapsedMs, uncommitted: true }
    }
    return { kind: 'QUERY', columns: this.queryColumns, rows: this.queryRows, elapsedMs, truncated: false }
  }
}
