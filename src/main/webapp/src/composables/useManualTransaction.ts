import { ref } from 'vue'
import { useApiClient } from '../api/client'
import { useToast } from './useToast'

const active = ref(false)
const busy = ref(false)

/** 手动事务（F-16）：开启、提交与回滚；状态与后端事务层同步。 */
export function useManualTransaction() {
  const api = useApiClient()
  const toast = useToast()

  async function begin(sourceId: string): Promise<void> {
    if (active.value || busy.value) return
    busy.value = true
    try {
      const status = await api.transaction.begin(sourceId)
      active.value = status.active
      toast.notify('手动事务已开启，本数据源上的变更将暂不提交。')
    } catch (error) {
      toast.notify(error instanceof Error ? error.message : '开启事务失败。')
    } finally {
      busy.value = false
    }
  }

  async function commit(sourceId: string): Promise<void> {
    await finish(sourceId, api.transaction.commit, '事务已提交，所有变更已生效。', '提交失败。')
  }

  async function rollback(sourceId: string): Promise<void> {
    await finish(sourceId, api.transaction.rollback, '事务已回滚，未提交变更已撤销。', '回滚失败。')
  }

  async function finish(
    sourceId: string,
    action: (id: string) => Promise<{ active: boolean }>,
    successMessage: string,
    failureMessage: string,
  ): Promise<void> {
    busy.value = true
    try {
      const status = await action(sourceId)
      active.value = status.active
      toast.notify(successMessage)
    } catch (error) {
      toast.notify(error instanceof Error ? error.message : failureMessage)
    } finally {
      busy.value = false
    }
  }

  /** 从服务端同步事务状态（登入/切换数据源后）；失败保持现状不误清。 */
  async function sync(sourceId: string): Promise<void> {
    try {
      const status = await api.transaction.status(sourceId)
      active.value = status.active
    } catch (error) {
      toast.notify(error instanceof Error ? `事务状态同步失败：${error.message}` : '事务状态同步失败。')
    }
  }

  function markPending(): void {
    active.value = true
  }

  function reset(): void {
    active.value = false
    busy.value = false
  }

  return { active, busy, begin, commit, rollback, sync, markPending, reset }
}
