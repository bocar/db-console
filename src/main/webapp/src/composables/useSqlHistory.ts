import { ref } from 'vue'
import { useApiClient } from '../api/client'
import type { HistoryEntry } from '../types'

const entries = ref<HistoryEntry[]>([])
let loadGeneration = 0

/** 历史（F-12）：当前登录会话内按数据源查看与清空；迟到的旧响应被丢弃。 */
export function useSqlHistory() {
  const api = useApiClient()

  async function load(sourceId: string): Promise<void> {
    const generation = ++loadGeneration
    try {
      const loaded = await api.history.list(sourceId)
      if (generation !== loadGeneration) return
      entries.value = loaded
    } catch {
      if (generation !== loadGeneration) return
      entries.value = []
      // 静默置空：错误面板态展示随 T14 端点落地，不弹 toast 干扰执行反馈
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
