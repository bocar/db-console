import { ref } from 'vue'
import { useApiClient } from '../api/client'
import { ApiClientError } from '../api/http-client'
import type { ConfirmationRequired } from '../types'
import { useToast } from './useToast'
import type { useDataSources } from './useDataSources'
import type { useManualTransaction } from './useManualTransaction'
import type { useResults } from './useResults'

export interface ExecutionDeps {
  dataSources: ReturnType<typeof useDataSources>
  results: ReturnType<typeof useResults>
  transaction: ReturnType<typeof useManualTransaction>
}

const sql = ref('')
const isRunning = ref(false)
const canceling = ref(false)
const pendingSql = ref('')
const pendingConfirmation = ref<ConfirmationRequired | null>(null)

/**
 * SQL 执行（F-04/F-05/F-09）：语句判定与确认分派由后端完成，
 * 前端只按响应驱动：确认要求 → 弹窗 → 凭确认凭据重试。
 */
export function useExecution(deps: ExecutionDeps) {
  const api = useApiClient()
  const toast = useToast()

  async function execute(text: string): Promise<void> {
    const statement = text.trim()
    if (!statement) {
      toast.notify('请输入一条 SQL 语句后再执行。')
      return
    }
    if (isRunning.value) return
    const source = deps.dataSources.selectedSource.value
    if (!source) {
      toast.notify('请先选择数据源。')
      return
    }
    pendingSql.value = statement
    await run(source.id, statement)
  }

  async function confirmExecution(): Promise<void> {
    const confirmation = pendingConfirmation.value
    const source = deps.dataSources.selectedSource.value
    if (!confirmation || !source) return
    await run(source.id, pendingSql.value, confirmation.confirmationToken)
  }

  function cancelExecution(): void {
    pendingConfirmation.value = null
  }

  async function run(sourceId: string, statement: string, confirmationToken?: string): Promise<void> {
    isRunning.value = true
    canceling.value = false
    try {
      const outcome = await api.sql.execute(sourceId, statement, confirmationToken)
      if (outcome.kind === 'CONFIRMATION_REQUIRED') {
        pendingConfirmation.value = outcome
        return
      }
      pendingConfirmation.value = null
      if (outcome.kind === 'QUERY') {
        deps.results.showQuery(outcome)
        toast.notify('查询已完成，结果已更新。')
      } else if (outcome.uncommitted) {
        deps.results.showUpdate(outcome)
        deps.transaction.markPending(sourceId)
        toast.notify('执行成功，变更尚未提交。')
      } else {
        deps.results.showUpdate(outcome)
        toast.notify('语句已在独立连接执行并生效。')
      }
    } catch (error) {
      const message = error instanceof Error && error.message ? error.message : '执行失败。'
      toast.notify(error instanceof ApiClientError ? error.message : message)
    } finally {
      isRunning.value = false
      canceling.value = false
    }
  }

  async function cancelQuery(): Promise<void> {
    const source = deps.dataSources.selectedSource.value
    if (!source || !isRunning.value || canceling.value) return
    canceling.value = true
    try {
      await api.sql.cancel(source.id)
    } catch (error) {
      canceling.value = false
      toast.notify(error instanceof Error ? `取消失败：${error.message}` : '取消失败。')
    }
  }

  function reset(): void {
    sql.value = ''
    isRunning.value = false
    canceling.value = false
    pendingSql.value = ''
    pendingConfirmation.value = null
  }

  return {
    sql,
    isRunning,
    canceling,
    pendingSql,
    pendingConfirmation,
    execute,
    confirmExecution,
    cancelExecution,
    cancelQuery,
    reset,
  }
}
