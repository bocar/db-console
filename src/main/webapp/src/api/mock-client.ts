import type { ApiClient } from './client'
import { ApiClientError } from './http-client'
import type {
  CancelOutcome,
  ConfirmationRequired,
  ConnectionTestResult,
  DataSourceInfo,
  ExecutionOutcome,
  HistoryEntry,
  QueryColumn,
  QueryResult,
  QueryRow,
  ScriptAnalyzeResult,
  ScriptStatementError,
  ScriptStatementPlan,
  SessionInfo,
  StatementKind,
  TableDefinition,
  TransactionStatus,
  UnknownOutcome,
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
  private scriptRuns = new Map<string, { sourceId: string; filename: string; content: string }>()
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
    testConnection: async (id: string): Promise<ConnectionTestResult> => {
      this.requireSource(id)
      await this.delay(120)
      return { ok: true, kind: 'NONE', message: '连接正常' }
    },
  }

  metadata = {
    tables: async (sourceId: string): Promise<TableDefinition[]> => {
      this.requireSource(sourceId)
      return [...this.tables]
    },
    tableStructure: async (sourceId: string, table: string): Promise<TableDefinition> => {
      this.requireSource(sourceId)
      const found = this.tables.find((item) => item.name === table)
      if (!found) throw new ApiClientError('REQUEST_FAILED', `表 ${table} 不存在`, 404)
      return { ...found }
    },
    commentStatement: async (
      _sourceId: string, tableName: string, columnName: string, comment: string,
    ): Promise<{ sql: string }> => {
      return { sql: `ALTER TABLE ${tableName} MODIFY COLUMN ${columnName} VARCHAR(64) COMMENT '${comment}'` }
    },
  }

  sql = {
    execute: (sourceId: string, sql: string, confirmationToken?: string): Promise<ExecutionOutcome | ConfirmationRequired> =>
      new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          this.pendingExecution = null
          try {
            resolve(this.resolveExecution(sourceId, sql, confirmationToken, true))
          } catch (error) {
            reject(error instanceof Error ? error : new Error('执行失败'))
          }
        }, this.delayMs)
        this.pendingExecution = () => {
          clearTimeout(timer)
          this.pendingExecution = null
          resolve({ kind: 'CANCELLED', elapsedMs: 0 })
        }
      }),
    page: async (sourceId: string, sql: string, page: number, pageSize: number): Promise<QueryResult> => {
      this.requireSource(sourceId)
      if (!/^(select|with)\b/i.test(sql.trim())) {
        throw new ApiClientError('PAGINATION_NOT_ALLOWED', '只有只读查询支持分页', 422)
      }
      return this.queryResult(page, pageSize)
    },
    cancel: async (sourceId: string): Promise<CancelOutcome> => {
      this.requireSource(sourceId)
      const pending = this.pendingExecution
      if (pending) {
        pending()
        return { kind: 'CANCELLED' }
      }
      return { kind: 'COMPLETED' }
    },
  }

  script = {
    // 拆分语义说明：mock 用朴素分号切分近似预校验（前端无方言词法器，ADR-0001 的
    // 边界规则只在真实后端存在）；依赖 mock 断言「字符串内分号不切分」等拆分语义是无效测试
    analyze: async (sourceId: string, filename: string, content: string): Promise<ScriptAnalyzeResult> => {
      this.requireSource(sourceId)
      if (!/\.(sql|txt)$/i.test(filename.trim())) {
        throw new ApiClientError('SCRIPT_FILE_TYPE', '仅支持 .sql 或 .txt 脚本文件', 422)
      }
      const statements = content
        .split(';')
        .map((part) => part.trim())
        .filter((part) => part.length > 0 && !/^(--|#|\/\*)/.test(part))
      const plans: ScriptStatementPlan[] = []
      const errors: ScriptStatementError[] = []
      statements.forEach((sql, position) => {
        const kind = this.statementKind(sql)
        if (kind === 'OTHER') {
          errors.push({
            index: position + 1,
            sql,
            code: 'OUT_OF_SCOPE',
            message: `第 ${position + 1} 条语句超出允许的操作范围`,
          })
          return
        }
        const reason = this.confirmationReason(sourceId, sql, kind)
        plans.push({
          index: position + 1,
          sql,
          kind,
          confirmationReason: reason,
          implicitCommitDdl: MockApiClient.DDL_KINDS.has(kind),
          confirmationToken: reason ? `mock-token-${MockApiClient.nextId++}` : null,
        })
      })
      const valid = errors.length === 0
      const scriptId = valid ? `mock-script-${MockApiClient.nextId++}` : null
      if (scriptId) {
        this.scriptRuns.set(scriptId, { sourceId, filename, content })
      }
      return { sourceId, filename, scriptId, valid, statements: plans, errors }
    },
    executeStatement: async (
      sourceId: string,
      sql: string,
      confirmationToken?: string,
    ): Promise<ExecutionOutcome | ConfirmationRequired | UnknownOutcome> => {
      await this.delay(this.delayMs === 0 ? 0 : 60)
      return this.resolveExecution(sourceId, sql, confirmationToken, false)
    },
    recordRun: async (
      sourceId: string,
      scriptId: string,
      durationMs: number,
    ): Promise<void> => {
      this.requireSource(sourceId)
      const stored = this.scriptRuns.get(scriptId)
      if (!stored || stored.sourceId !== sourceId) {
        throw new ApiClientError('SCRIPT_RUN_NOT_FOUND', '脚本运行不存在或已失效，请重新上传后再执行', 422)
      }
      this.scriptRuns.delete(scriptId)
      this.historyEntries = [
        {
          id: MockApiClient.nextId++,
          sourceId,
          sql: stored.content,
          executedAt: new Date().toISOString(),
          durationMs,
          status: 'SUCCEEDED',
          uncommitted: false,
          filename: stored.filename,
          kind: 'SCRIPT',
        },
        ...this.historyEntries,
      ]
    },
  }

  transaction = {
    begin: async (sourceId: string): Promise<TransactionStatus> => {
      this.requireSource(sourceId)
      await this.delay(60)
      return { active: true, sourceId }
    },
    commit: async (sourceId: string): Promise<TransactionStatus> => {
      await this.delay(80)
      this.finishTransaction(sourceId)
      return { active: false, sourceId: null }
    },
    rollback: async (sourceId: string): Promise<TransactionStatus> => {
      await this.delay(80)
      this.finishTransaction(sourceId)
      return { active: false, sourceId: null }
    },
    status: async (sourceId: string): Promise<TransactionStatus> => {
      this.requireSource(sourceId)
      return { active: this.isTransactionPending(sourceId), sourceId }
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
      throw new ApiClientError('UNKNOWN_DATA_SOURCE', '未知数据源标识，请求已拒绝', 404)
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

  private queryResult(page: number, pageSize: number): QueryResult {
    const totalRows = this.queryRows.length
    const offset = (page - 1) * pageSize
    return {
      kind: 'QUERY',
      columns: this.queryColumns,
      rows: this.queryRows.slice(offset, offset + pageSize),
      elapsedMs: 0,
      truncated: false,
      page,
      pageSize,
      hasNextPage: offset + pageSize < totalRows,
      totalRows,
      totalPages: Math.ceil(totalRows / pageSize),
    }
  }

  private static readonly DDL_KINDS: ReadonlySet<StatementKind> = new Set([
    'CREATE_TABLE',
    'ALTER_TABLE',
    'DROP_TABLE',
    'COMMENT_ON',
  ])

  private static readonly WRITE_KINDS: ReadonlySet<StatementKind> = new Set([
    'INSERT',
    'UPDATE',
    'DELETE',
  ])

  private resolveExecution(
    sourceId: string,
    sql: string,
    confirmationToken: string | undefined,
    recordHistory: boolean,
  ): ExecutionOutcome | ConfirmationRequired {
    const statement = sql.trim()
    const kind = this.statementKind(statement)
    const reason = this.confirmationReason(sourceId, statement, kind)

    if (MockApiClient.DDL_KINDS.has(kind) && this.isTransactionPending(sourceId)) {
      throw new ApiClientError('TRANSACTION_OPEN', '请先提交或回滚当前事务，再执行隐式提交 DDL。', 409)
    }
    if (reason && !confirmationToken) {
      const confirmation: ConfirmationRequired = {
        kind: 'CONFIRMATION_REQUIRED',
        reason,
        implicitCommitDdl: MockApiClient.DDL_KINDS.has(kind),
        confirmationToken: `mock-token-${MockApiClient.nextId++}`,
      }
      return confirmation
    }

    const elapsedMs = statement.length % 70 + 34
    if (recordHistory) {
      this.historyEntries = [
        {
          id: MockApiClient.nextId++,
          sourceId,
          sql: statement,
          executedAt: new Date().toISOString(),
          durationMs: elapsedMs,
          status: 'SUCCEEDED',
          uncommitted: MockApiClient.WRITE_KINDS.has(kind),
          kind: 'SQL',
        },
        ...this.historyEntries,
      ]
    }
    if (MockApiClient.DDL_KINDS.has(kind)) {
      return { kind: 'UPDATE', affectedRows: null, elapsedMs, uncommitted: false, implicitCommitDdl: true }
    }
    if (MockApiClient.WRITE_KINDS.has(kind)) {
      this.transactionPendingBySource.set(sourceId, true)
      return { kind: 'UPDATE', affectedRows: 1, elapsedMs, uncommitted: true, implicitCommitDdl: false }
    }
    return this.queryResult(1, 100)
  }

  /** Mock 专用：按首动词粗分类语句类别；预校验与执行两侧共用此单一分类。 */
  private statementKind(sql: string): StatementKind {
    const KIND_BY_VERB: Record<string, StatementKind> = {
      SELECT: 'SELECT',
      INSERT: 'INSERT',
      UPDATE: 'UPDATE',
      DELETE: 'DELETE',
      CREATE: 'CREATE_TABLE',
      ALTER: 'ALTER_TABLE',
      DROP: 'DROP_TABLE',
      COMMENT: 'COMMENT_ON',
    }
    const verb = sql.trim().split(/\s+/)[0]?.toUpperCase() ?? ''
    return KIND_BY_VERB[verb] ?? 'OTHER'
  }

  /** Mock 专用：确认原因粗判（镜像后端 ConfirmationDecision.evaluate）；预校验与执行两侧的单一规则来源。 */
  private confirmationReason(sourceId: string, sql: string, kind: StatementKind): ConfirmationRequired['reason'] | null {
    const source = this.availableSources.find((item) => item.id === sourceId)
    if (MockApiClient.DDL_KINDS.has(kind)) return 'IMPLICIT_COMMIT_DDL'
    if (MockApiClient.WRITE_KINDS.has(kind)) {
      if (source?.environment === 'PRODUCTION') return 'PRODUCTION_WRITE'
      if ((kind === 'UPDATE' || kind === 'DELETE') && !/\bwhere\b/i.test(sql)) return 'MISSING_WHERE'
    }
    return null
  }
}
