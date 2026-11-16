import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { API_CLIENT_KEY, type ApiClient } from '../api/client'
import { fakeApiClient } from '../api/test-support'
import { useScriptExecution } from './useScriptExecution'
import { useToast } from './useToast'
import type { ScriptAnalyzeResult } from '../types'

const SOURCE_ID = 'mysql-work-note'
const SCRIPT_TEXT = 'SELECT 1;\nUPDATE t SET a = 1;\nSELECT 2;\n'
const SCRIPT_ID = 'mock-run-1'

function plan(index: number, sql: string, kind: 'SELECT' | 'UPDATE', token: string | null = null) {
  return {
    index,
    sql,
    kind,
    confirmationReason: token ? ('MISSING_WHERE' as const) : null,
    implicitCommitDdl: false,
    confirmationToken: token,
  }
}

function analyzeResult(statements = [plan(1, 'SELECT 1', 'SELECT'), plan(2, 'UPDATE t SET a = 1', 'UPDATE', 'token-2')]): ScriptAnalyzeResult {
  return { sourceId: SOURCE_ID, filename: 'm.sql', scriptId: SCRIPT_ID, valid: true, statements, errors: [] }
}

function fakeScriptClient(
  overrides: Partial<ApiClient['script']> = {},
  sqlOverrides: Partial<ApiClient['sql']> = {},
): ApiClient {
  const base = fakeApiClient()
  return {
    ...base,
    sql: { ...base.sql, ...sqlOverrides },
    script: { ...base.script, ...overrides },
  }
}

function mountScript(client: ApiClient) {
  let handle: ReturnType<typeof useScriptExecution> | undefined
  const host = defineComponent({
    setup() {
      handle = useScriptExecution()
      return () => null
    },
  })
  mount(host, { global: { provide: { [API_CLIENT_KEY]: client } } })
  if (!handle) throw new Error('useScriptExecution 未初始化')
  return handle
}

async function mountWithScript(client: ApiClient, result: ScriptAnalyzeResult = analyzeResult()) {
  // 预校验结果统一由本装配注入，用例只需覆盖 executeStatement / recordRun 等行为
  const patched: ApiClient = {
    ...client,
    script: { ...client.script, analyze: async () => result },
  }
  const handle = mountScript(patched)
  const file = { name: 'm.sql', text: async () => SCRIPT_TEXT } as unknown as File
  await handle.loadFile(file, SOURCE_ID)
  await flushPromises()
  return handle
}

describe('useScriptExecution 脚本编排', () => {
  beforeEach(() => {
    useToast().reset()
  })

  it('上传后进入就绪态：逐条待执行，凭据保留在计划中', async () => {
    const handle = await mountWithScript(fakeScriptClient())

    expect(handle.hasScript.value).toBe(true)
    expect(handle.states.value).toHaveLength(2)
    expect(handle.states.value.every((state) => state.status === 'PENDING')).toBe(true)
    expect(handle.needsConfirmationCount.value).toBe(1)
    expect(useToast().message.value).toContain('预校验通过')
  })

  it('非 .sql/.txt 文件在客户端即拒绝，不发起预校验', async () => {
    const analyze = vi.fn()
    const client = fakeScriptClient({ analyze: analyze as unknown as ApiClient['script']['analyze'] })
    const handle = mountScript(client)

    const file = { name: 'dump.sh', text: async () => 'SELECT 1;' } as unknown as File
    await handle.loadFile(file, SOURCE_ID)
    await flushPromises()

    expect(analyze).not.toHaveBeenCalled()
    expect(useToast().message.value).toContain('.sql')
  })

  it('全部成功后写入一条汇总历史（recordRun）', async () => {
    const recordRun = vi.fn(async () => undefined)
    const executeStatement = vi.fn(async (_sourceId: string, sql: string, token?: string) => {
      expect(token ?? null).toBe(sql === 'UPDATE t SET a = 1' ? 'token-2' : null)
      return { kind: 'UPDATE' as const, affectedRows: 1, elapsedMs: 5, uncommitted: false, implicitCommitDdl: false }
    })
    const handle = await mountWithScript(fakeScriptClient({ executeStatement, recordRun }))

    await handle.run(0, SOURCE_ID)
    await flushPromises()

    expect(handle.allSucceeded.value).toBe(true)
    expect(executeStatement).toHaveBeenCalledTimes(2)
    expect(recordRun).toHaveBeenCalledTimes(1)
    expect(recordRun).toHaveBeenCalledWith(SOURCE_ID, SCRIPT_ID, expect.any(Number))
  })

  it('中途失败即停止：失败语句之后全部标为未执行，且不写汇总历史', async () => {
    const recordRun = vi.fn(async () => undefined)
    const executeStatement = vi.fn(async (_sourceId: string, sql: string) => {
      if (sql === 'UPDATE t SET a = 1') throw new Error('数据库执行失败：约束冲突')
      return { kind: 'UPDATE' as const, affectedRows: 1, elapsedMs: 5, uncommitted: false, implicitCommitDdl: false }
    })
    const handle = await mountWithScript(
      fakeScriptClient({ executeStatement, recordRun }, {}),
      analyzeResult([plan(1, 'SELECT 1', 'SELECT'), plan(2, 'UPDATE t SET a = 1', 'UPDATE', 't'), plan(3, 'SELECT 2', 'SELECT')]),
    )

    await handle.run(0, SOURCE_ID)
    await flushPromises()

    const statuses = handle.states.value.map((state) => state.status)
    expect(statuses).toEqual(['SUCCEEDED', 'FAILED', 'SKIPPED'])
    expect(handle.states.value[1].message).toContain('约束冲突')
    // 恢复点 = 首个停止终态的下一条（stoppedAt + 1 = 2）
    expect(handle.progress.value.stoppedAt).toBe(1)
    expect(recordRun).not.toHaveBeenCalled()
    expect(useToast().message.value).toContain('已停止')
    expect(useToast().message.value).toContain('剩余 1 条未执行')
  })

  it('中途收到确认要求（凭据失效）按失败停止，要求重新上传', async () => {
    const recordRun = vi.fn(async () => undefined)
    const executeStatement = vi.fn(async () => ({
      kind: 'CONFIRMATION_REQUIRED' as const,
      reason: 'MISSING_WHERE' as const,
      implicitCommitDdl: false,
      confirmationToken: 'fresh',
    }))
    const handle = await mountWithScript(fakeScriptClient({ executeStatement, recordRun }))

    await handle.run(0, SOURCE_ID)
    await flushPromises()

    expect(handle.states.value[0].status).toBe('FAILED')
    expect(handle.states.value[0].message).toContain('重新上传')
    expect(handle.states.value[1].status).toBe('SKIPPED')
    expect(recordRun).not.toHaveBeenCalled()
  })

  it('当前数据源与预校验数据源不一致时拒绝执行', async () => {
    const executeStatement = vi.fn()
    const handle = await mountWithScript(fakeScriptClient({
      executeStatement: executeStatement as unknown as ApiClient['script']['executeStatement'],
    }))

    await handle.run(0, 'other-source')
    await flushPromises()

    expect(executeStatement).not.toHaveBeenCalled()
    expect(useToast().message.value).toContain('数据源已切换')
  })

  it('取消终态传播：当前语句已取消，后续未执行', async () => {
    const executeStatement = vi.fn(async () => ({ kind: 'CANCELLED' as const, elapsedMs: 3 }))
    const handle = await mountWithScript(fakeScriptClient({ executeStatement }))

    await handle.run(0, SOURCE_ID)
    await flushPromises()

    expect(handle.states.value[0].status).toBe('CANCELLED')
    expect(handle.states.value[1].status).toBe('SKIPPED')
    expect(executeStatement).toHaveBeenCalledTimes(1)
  })

  it('恢复执行从指定下标开始，仅执行剩余语句', async () => {
    const executed: string[] = []
    const executeStatement = vi.fn(async (_sourceId: string, sql: string) => {
      executed.push(sql)
      return { kind: 'UPDATE' as const, affectedRows: 1, elapsedMs: 5, uncommitted: false, implicitCommitDdl: false }
    })
    const handle = await mountWithScript(fakeScriptClient({ executeStatement }))

    await handle.run(1, SOURCE_ID)
    await flushPromises()

    expect(executed).toEqual(['UPDATE t SET a = 1'])
    expect(handle.states.value[0].status).toBe('PENDING')
    expect(handle.allSucceeded.value).toBe(false)
  })
})
