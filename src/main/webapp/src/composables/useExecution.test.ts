import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { API_CLIENT_KEY, type ApiClient } from '../api/client'
import { useDataSources } from './useDataSources'
import { useResults } from './useResults'
import { useManualTransaction } from './useManualTransaction'
import { useExecution } from './useExecution'
import type { ConfirmationRequired, DataSourceInfo, ExecutionOutcome } from '../types'

const SOURCE: DataSourceInfo = {
  id: 'mysql-work-note',
  displayName: 'MySQL 工作笔记库',
  environment: 'DEVELOPMENT',
  databaseType: 'MYSQL',
  compatibilityMode: 'NATIVE',
}

function fakeClient(executeImpl: ApiClient['sql']['execute']): ApiClient {
  return {
    auth: {
      login: async () => ({ username: 'admin' }),
      logout: async () => undefined,
      session: async () => ({ username: 'admin' }),
    },
    dataSources: {
      list: async () => [SOURCE],
      testConnection: async () => ({ ok: true, kind: 'NONE', message: '连接正常' }),
    },
    metadata: { tables: async () => [] },
    sql: { execute: executeImpl, cancel: async () => undefined },
    transaction: { commit: async () => undefined, rollback: async () => undefined },
    history: { list: async () => [], clear: async () => undefined },
  }
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
      return () => null
    },
  })
  mount(host, { global: { provide: { [API_CLIENT_KEY]: client } } })
  if (!handle) throw new Error('useExecution 未初始化')
  return handle
}

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
      .mockResolvedValueOnce({ kind: 'UPDATE', affectedRows: 3, elapsedMs: 5, uncommitted: false })
    const handle = await mountExecutionReady(fakeClient(async (id, sql, token) => execute(id, sql, token)))

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
