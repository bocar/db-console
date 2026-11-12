import { ref } from 'vue'
import { useApiClient } from '../api/client'
import { useToast } from './useToast'

const pendingBySource = ref(new Map<string, boolean>())

/** 手动事务（F-16）：未提交状态与提交/回滚。 */
export function useManualTransaction() {
  const api = useApiClient()
  const toast = useToast()

  function isPending(sourceId: string): boolean {
    return pendingBySource.value.get(sourceId) ?? false
  }

  function markPending(sourceId: string): void {
    pendingBySource.value = new Map(pendingBySource.value).set(sourceId, true)
  }

  async function commit(sourceId: string): Promise<void> {
    try {
      await api.transaction.commit(sourceId)
      pendingBySource.value = new Map(pendingBySource.value).set(sourceId, false)
      toast.notify('事务已提交，所有变更已生效。')
    } catch (error) {
      toast.notify(error instanceof Error ? error.message : '提交失败。')
    }
  }

  async function rollback(sourceId: string): Promise<void> {
    try {
      await api.transaction.rollback(sourceId)
      pendingBySource.value = new Map(pendingBySource.value).set(sourceId, false)
      toast.notify('事务已回滚，未提交变更已撤销。')
    } catch (error) {
      toast.notify(error instanceof Error ? error.message : '回滚失败。')
    }
  }

  function reset(): void {
    pendingBySource.value = new Map()
  }

  return { isPending, markPending, commit, rollback, reset }
}
