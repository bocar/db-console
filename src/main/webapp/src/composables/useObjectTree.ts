import { computed, ref } from 'vue'
import { useApiClient } from '../api/client'
import type { TableDefinition } from '../types'

const tables = ref<TableDefinition[]>([])
const loading = ref(false)
const loadError = ref('')
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
      loadError.value = ''
      if (!tables.value.some((table) => table.name === selectedTableName.value)) {
        selectedTableName.value = tables.value[0]?.name ?? ''
      }
    } catch (error) {
      if (generation !== loadGeneration) return
      tables.value = []
      // 错误态记录在 loadError 供面板展示（T06 端点落地时消费），不弹 toast 干扰执行反馈
      loadError.value = error instanceof Error ? error.message : '对象结构加载失败。'
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
    loadError.value = ''
    selectedTableName.value = ''
  }

  return { tables, loading, loadError, selectedTableName, selectedTable, loadTables, selectTable, reset }
}
