import { ref } from 'vue'
import { useApiClient } from '../api/client'
import type { HistoryEntry } from '../types'

const entries = ref<HistoryEntry[]>([])
const loadError = ref('')
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
      loadError.value = ''
    } catch (error) {
      if (generation !== loadGeneration) return
      entries.value = []
      // 错误态记录在 loadError 供面板展示（T14 端点落地时消费），不弹 toast 干扰执行反馈
      loadError.value = error instanceof Error ? error.message : '历史加载失败。'
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
    loadError.value = ''
  }

  return { entries, loadError, load, currentFor, clear, reset }
}
