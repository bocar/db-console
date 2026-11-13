import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { API_CLIENT_KEY, type ApiClient } from '../api/client'
import { fakeApiClient } from '../api/test-support'
import { useDataSources } from './useDataSources'
import { useResults } from './useResults'
import { useManualTransaction } from './useManualTransaction'
import { useExecution } from './useExecution'
import { useToast } from './useToast'
import type {
  CancelOutcome,
  ConfirmationRequired,
  DataSourceInfo,
  ExecutionOutcome,
  UnknownOutcome,
} from '../types'

const SOURCE: DataSourceInfo = {
  id: 'mysql-work-note',
  displayName: 'MySQL 工作笔记库',
  environment: 'DEVELOPMENT',
  databaseType: 'MYSQL',
  compatibilityMode: 'NATIVE',
}

type ExecuteResult = ExecutionOutcome | ConfirmationRequired | UnknownOutcome

function fakeClient(
  executeImpl: ApiClient['sql']['execute'],
  cancelImpl: ApiClient['sql']['cancel'] = async () => ({ kind: 'CANCELLED' }),
): ApiClient {
  return fakeApiClient({
    dataSources: { ...fakeApiClient().dataSources, list: async () => [SOURCE] },
    sql: { ...fakeApiClient().sql, execute: executeImpl, cancel: cancelImpl },
  })
}

function mountExecution(client: ApiClient) {
  let handle: ReturnType<typeof useExecution> | undefined
  const host = defineComponent({
    setup() {
      const dataSources = useDataSources()
      const results = useResults()
      const transaction = useManualTransaction()
      void dataSources.load()
      handle = useExecution({ dataSources, results, transaction })
      mountedResults = results
      return () => null
    },
  })
  mount(host, { global: { provide: { [API_CLIENT_KEY]: client } } })
  if (!handle) throw new Error('useExecution 未初始化')
  return handle
}

let mountedResults: ReturnType<typeof useResults> | undefined

async function mountExecutionReady(client: ApiClient) {
  const handle = mountExecution(client)
  await flushPromises()
  return handle
}

describe('useExecution 确认流', () => {
  it('收到确认要求时挂起待确认语句，不进入执行完成态', async () => {
    const confirmation: ConfirmationRequired = {
      kind: 'CONFIRMATION_REQUIRED',
      reason: 'MISSING_WHERE',
      implicitCommitDdl: false,
      confirmationToken: 'token-1',
    }
    const handle = await mountExecutionReady(fakeClient(async () => confirmation))

    await handle.execute('UPDATE t SET x = 1')
    await flushPromises()

    expect(handle.pendingConfirmation.value?.confirmationToken).toBe('token-1')
    expect(handle.pendingSql.value).toBe('UPDATE t SET x = 1')
    expect(handle.isRunning.value).toBe(false)
  })

  it('确认后携带确认凭据重新执行同一语句', async () => {
    const execute =
      vi.fn<(id: string, sql: string, token?: string) => Promise<ExecutionOutcome | ConfirmationRequired>>()
      .mockResolvedValueOnce({
        kind: 'CONFIRMATION_REQUIRED',
        reason: 'MISSING_WHERE',
        implicitCommitDdl: false,
        confirmationToken: 'token-1',
      } as ConfirmationRequired)
      .mockResolvedValueOnce({ kind: 'UPDATE', affectedRows: 3, elapsedMs: 5, uncommitted: false, implicitCommitDdl: false })
    const handle = await mountExecutionReady(fakeClient(execute))

    await handle.execute('UPDATE t SET x = 1')
    await flushPromises()
    await handle.confirmExecution()
    await flushPromises()

    expect(execute).toHaveBeenLastCalledWith('mysql-work-note', 'UPDATE t SET x = 1', 'token-1')
    expect(handle.pendingConfirmation.value).toBeNull()
  })

  it('取消确认清空待确认状态', async () => {
    const handle = await mountExecutionReady(
      fakeClient(async () => ({
        kind: 'CONFIRMATION_REQUIRED',
        reason: 'MISSING_WHERE',
        implicitCommitDdl: false,
        confirmationToken: 't',
      } as ConfirmationRequired)),
    )

    await handle.execute('UPDATE t SET x = 1')
    await flushPromises()
    handle.cancelExecution()

    expect(handle.pendingConfirmation.value).toBeNull()
  })
})

describe('useExecution 查询取消', () => {
  beforeEach(() => {
    // composable 单例状态跨用例共享：先清空结果与提示，保证断言只看本用例
    mountedResults?.reset()
    useToast().reset()
  })

  function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
    let resolve!: (value: T) => void
    const promise = new Promise<T>((r) => {
      resolve = r
    })
    return { promise, resolve }
  }

  it('取消成功：执行以 CANCELLED 终态收尾并提示查询已取消', async () => {
    const gate = deferred<ExecuteResult>()
    const handle = await mountExecutionReady(
      fakeClient(() => gate.promise, async () => ({ kind: 'CANCELLED' })),
    )

    const running = handle.execute('SELECT * FROM dbconsole_test_t')
    await flushPromises()
    expect(handle.isRunning.value).toBe(true)

    await handle.cancelQuery()
    await flushPromises()
    expect(handle.canceling.value).toBe(true)

    gate.resolve({ kind: 'CANCELLED', elapsedMs: 5 })
    await running
    await flushPromises()

    expect(handle.isRunning.value).toBe(false)
    expect(handle.canceling.value).toBe(false)
    expect(useToast().message.value).toBe('查询已取消。')
    expect(mountedResults?.hasResult.value).toBe(false)
  })

  it('查询先于取消完成时保留结果并提示', async () => {
    const gate = deferred<ExecuteResult>()
    const handle = await mountExecutionReady(
      fakeClient(() => gate.promise, async () => ({ kind: 'COMPLETED' })),
    )

    const running = handle.execute('SELECT id FROM dbconsole_test_t')
    await flushPromises()

    await handle.cancelQuery()
    await flushPromises()
    expect(handle.canceling.value).toBe(false)
    expect(useToast().message.value).toBe('查询已先完成，结果已保留。')

    gate.resolve({
      kind: 'QUERY',
      columns: [{ name: 'id', type: 'INT' }],
      rows: [[1]],
      elapsedMs: 3,
      truncated: false,
    })
    await running
    await flushPromises()

    expect(handle.isRunning.value).toBe(false)
    expect(mountedResults?.queryResult.value?.rows).toEqual([[1]])
  })

  it('取消失败时提示原因并退出取消中状态', async () => {
    const gate = deferred<ExecuteResult>()
    const handle = await mountExecutionReady(
      fakeClient(
        () => gate.promise,
        async () => ({ kind: 'FAILED', message: '驱动不支持取消' }) as CancelOutcome,
      ),
    )

    const running = handle.execute('SELECT * FROM dbconsole_test_t')
    await flushPromises()

    await handle.cancelQuery()
    await flushPromises()

    expect(handle.canceling.value).toBe(false)
    expect(useToast().message.value).toBe('取消失败：驱动不支持取消')

    gate.resolve({ kind: 'CANCELLED', elapsedMs: 5 })
    await running
    await flushPromises()
    expect(handle.isRunning.value).toBe(false)
  })
})
