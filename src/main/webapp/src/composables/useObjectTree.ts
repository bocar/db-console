import { computed, ref } from 'vue'
import { useApiClient } from '../api/client'
import type { TableDefinition } from '../types'

const tables = ref<TableDefinition[]>([])
const loading = ref(false)
const selectedTableName = ref('')
let loadGeneration = 0

/** 对象树（F-03）：表结构与当前选中表；迟到的旧响应被丢弃。 */
export function useObjectTree() {
  const api = useApiClient()

  const selectedTable = computed<TableDefinition | null>(
    () => tables.value.find((table) => table.name === selectedTableName.value) ?? null,
  )

  async function loadTables(sourceId: string): Promise<void> {
    const generation = ++loadGeneration
    loading.value = true
    try {
      const loaded = await api.metadata.tables(sourceId)
      if (generation !== loadGeneration) return
      tables.value = loaded
      if (!tables.value.some((table) => table.name === selectedTableName.value)) {
        selectedTableName.value = tables.value[0]?.name ?? ''
      }
    } catch {
      if (generation !== loadGeneration) return
      tables.value = []
      // 静默置空：错误面板态展示随 T06 端点落地，不弹 toast 干扰执行反馈
    } finally {
      if (generation === loadGeneration) {
        loading.value = false
      }
    }
  }

  function selectTable(name: string): void {
    selectedTableName.value = name
  }

  function reset(): void {
    tables.value = []
    loading.value = false
    selectedTableName.value = ''
  }

  return { tables, loading, selectedTableName, selectedTable, loadTables, selectTable, reset }
}
