import { ref } from 'vue'
import { useApiClient } from '../api/client'
import type { HistoryEntry } from '../types'

const entries = ref<HistoryEntry[]>([])

/** 历史（F-12）：当前登录会话内按数据源查看与清空。 */
export function useSqlHistory() {
  const api = useApiClient()

  async function load(sourceId: string): Promise<void> {
    try {
      entries.value = await api.history.list(sourceId)
    } catch {
      entries.value = []
    }
  }

  function currentFor(sourceId: string): HistoryEntry[] {
    return entries.value.filter((entry) => entry.sourceId === sourceId)
  }

  async function clear(sourceId: string): Promise<void> {
    try {
      await api.history.clear(sourceId)
      entries.value = entries.value.filter((entry) => entry.sourceId !== sourceId)
    } catch {
      // 清空失败保持现状
    }
  }

  function reset(): void {
    entries.value = []
  }

  return { entries, load, currentFor, clear, reset }
}
