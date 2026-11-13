import { computed, ref } from 'vue'
import { useApiClient } from '../api/client'
import type { DataSourceInfo } from '../types'
import { useToast } from './useToast'

const sources = ref<DataSourceInfo[]>([])
const loading = ref(false)
const loadError = ref('')
const selectedSourceId = ref('')
const testing = ref(false)

/** 数据源（F-02）：列表、选中与连接测试。 */
export function useDataSources() {
  const api = useApiClient()
  const toast = useToast()

  const selectedSource = computed<DataSourceInfo | null>(
    () => sources.value.find((source) => source.id === selectedSourceId.value) ?? sources.value[0] ?? null,
  )

  async function load(): Promise<void> {
    loading.value = true
    loadError.value = ''
    try {
      sources.value = await api.dataSources.list()
      if (!sources.value.some((source) => source.id === selectedSourceId.value)) {
        selectedSourceId.value = sources.value[0]?.id ?? ''
      }
    } catch (error) {
      loadError.value = error instanceof Error ? error.message : '数据源列表加载失败。'
    } finally {
      loading.value = false
    }
  }

  function selectSource(id: string): void {
    if (id !== selectedSourceId.value) selectedSourceId.value = id
  }

  async function testConnection(): Promise<void> {
    const source = selectedSource.value
    if (!source || testing.value) return
    testing.value = true
    try {
      const result = await api.dataSources.testConnection(source.id)
      toast.notify(`${source.displayName}：${result.message}`)
    } catch (error) {
      toast.notify(error instanceof Error ? error.message : '连接测试失败。')
    } finally {
      testing.value = false
    }
  }

  function reset(): void {
    sources.value = []
    loading.value = false
    loadError.value = ''
    selectedSourceId.value = ''
    testing.value = false
  }

  return { sources, loading, loadError, selectedSourceId, selectedSource, testing, load, selectSource, testConnection, reset }
}
