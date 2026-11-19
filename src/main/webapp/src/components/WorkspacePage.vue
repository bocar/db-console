<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { Check, ChevronDown, CircleStop, Eraser, GitCommitHorizontal, History, LoaderCircle, Play, Plus, RotateCcw, Table2, X } from 'lucide-vue-next'
import AppHeader from './AppHeader.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import HistoryDialog from './HistoryDialog.vue'
import ObjectExplorer from './ObjectExplorer.vue'
import ResultPanel from './ResultPanel.vue'
import SqlEditor from './SqlEditor.vue'
import ToastMessage from './ToastMessage.vue'
import { confirmationReasonText, displayDatabaseType, displayEnvironment, environmentBadgeClass } from '../display'
import { statementPreviewText } from '../confirmation-text'
import { quoteIdentifier, selectTopStatement } from '../dialect'
import { useDataSources } from '../composables/useDataSources'
import { useExecution } from '../composables/useExecution'
import { useManualTransaction } from '../composables/useManualTransaction'
import { useObjectTree } from '../composables/useObjectTree'
import { useResultEditing } from '../composables/useResultEditing'
import { useResults } from '../composables/useResults'
import { useSession } from '../composables/useSession'
import { useSqlHistory } from '../composables/useSqlHistory'
import { useToast } from '../composables/useToast'

interface EditorHandle {
  executionText: () => string
  insertText: (text: string) => void
  focusEditor: () => void
}

interface ConfirmHandle { open: () => void }
interface HistoryHandle { open: () => void }

const emit = defineEmits<{ logout: []; switchView: [view: 'workspace' | 'script'] }>()

const { username } = useSession()
const { notify, message: toastMessage } = useToast()
const dataSources = useDataSources()
const { sources, selectedSourceId, selectedSource, testing } = dataSources
const objectTree = useObjectTree()
const { tables, selectedTableName, selectedTable, loading: refreshing } = objectTree
const results = useResults()
const { activeTab } = results
const transaction = useManualTransaction()
const historyStore = useSqlHistory()
const { load: loadHistory, clear: clearHistoryFor } = historyStore
const execution = useExecution({
  dataSources,
  results,
  transaction,
  onSchemaChanged: () => {
    const source = selectedSource.value
    if (source) void objectTree.loadTables(source.id)
  },
})
const { sql, isRunning, canceling, pendingSql, pendingConfirmation, execute, confirmExecution, cancelExecution, cancelQuery } = execution
// 结果表格编辑（F-17）：批次确认弹窗（翻页/执行守卫已内化进 useResults/useExecution）
const resultEditing = useResultEditing()
const { pendingBatch, confirmBatch, cancelBatch } = resultEditing

const editorRef = ref<EditorHandle | null>(null)
const confirmDialog = ref<ConfirmHandle | null>(null)
const batchConfirmDialog = ref<ConfirmHandle | null>(null)
const historyDialog = ref<HistoryHandle | null>(null)
const mobileSidebarOpen = ref(false)
const editorSize = ref(43)
const resizing = ref(false)
const workbench = ref<HTMLElement | null>(null)

const transactionActive = computed(() => transaction.active.value && !!selectedSource.value)
const currentHistory = computed(() => (selectedSource.value ? historyStore.currentFor(selectedSource.value.id) : []))
const confirmTitle = computed(() =>
  pendingConfirmation.value?.reason === 'PRODUCTION_WRITE' ? '确认在生产库执行？' : '确认执行此操作？')
const confirmEnvironment = computed(() => (selectedSource.value ? displayEnvironment(selectedSource.value.environment) : ''))
const confirmImplicitCommit = computed(() => pendingConfirmation.value?.implicitCommitDdl ?? false)
const workbenchStyle = computed(() => ({ '--editor-size': `${editorSize.value}%` }))

async function loadWorkspaceForSource(): Promise<void> {
  const source = selectedSource.value
  if (!source) return
  const tasks: Promise<void>[] = [objectTree.loadTables(source.id), loadHistory(source.id)]
  // 事务状态只在首次挂载同步；会话中切源时 changeSource 守卫已保证先结束事务
  if (!workspaceLoadedOnce) {
    tasks.push(transaction.sync(source.id))
    workspaceLoadedOnce = true
  }
  await Promise.all(tasks)
}
let workspaceLoadedOnce = false

onMounted(async () => {
  await dataSources.load()
  await loadWorkspaceForSource()
})

watch(selectedSourceId, () => {
  void loadWorkspaceForSource()
})

watch(pendingConfirmation, (confirmation) => {
  if (confirmation) confirmDialog.value?.open()
})

const batchSqlText = computed(() => {
  const batch = pendingBatch.value
  if (!batch) return ''
  return batch.statements
    .map((statement) => {
      const risk = statement.reason
        ? `【${confirmationReasonText(statement.reason)}】\n`
        : ''
      return `${risk}${statementPreviewText(statement.sql, statement.params)}`
    })
    .join('\n;\n')
})

watch(pendingBatch, (batch) => {
  if (batch) batchConfirmDialog.value?.open()
})

const executeFromToolbar = () => {
  void execute(editorRef.value?.executionText() ?? sql.value)
}

const handleEditorExecute = (text: string) => {
  void execute(text)
}

const commitTransaction = () => {
  const source = selectedSource.value
  if (source) void transaction.commit(source.id)
}

const rollbackTransaction = () => {
  const source = selectedSource.value
  if (source) void transaction.rollback(source.id)
}

const beginTransaction = () => {
  const source = selectedSource.value
  if (source) void transaction.begin(source.id)
}

const changeSource = (id: string) => {
  const current = selectedSource.value
  if (!current || id === current.id) return
  if (transaction.blockedByOpenTransaction()) return
  // 切源与新查询是跨 module 意图，暂存门显式自询（F-17）
  if (results.blockedByPendingChanges()) return
  dataSources.selectSource(id)
  results.reset()
  mobileSidebarOpen.value = false
  notify('已切换数据源，旧对象与旧结果已清除。')
}

const refreshObjects = () => {
  const source = selectedSource.value
  if (!source) return
  void objectTree.loadTables(source.id).then(() => notify('对象树已刷新。'))
}

const selectTable = (name: string) => {
  objectTree.selectTable(name)
  activeTab.value = 'structure'
  mobileSidebarOpen.value = false
}

const insertObject = (name: string) => {
  const source = selectedSource.value
  if (source) editorRef.value?.insertText(quoteIdentifier(name, source))
}

const openTable = (name: string) => {
  const source = selectedSource.value
  if (!source) return
  objectTree.selectTable(name)
  sql.value = selectTopStatement(name, source)
  nextTick(() => editorRef.value?.focusEditor())
}

const loadHistoryEntry = (value: string) => {
  sql.value = value
  nextTick(() => editorRef.value?.focusEditor())
  notify('历史语句已载入编辑器，尚未执行。')
}

const clearHistory = () => {
  const source = selectedSource.value
  if (!source) return
  void clearHistoryFor(source.id).then(() => notify('当前数据源的会话历史已清空。'))
}

const newQuery = () => {
  if (results.blockedByPendingChanges()) return
  sql.value = ''
  results.reset()
  nextTick(() => editorRef.value?.focusEditor())
}

const clearEditor = () => {
  sql.value = ''
  nextTick(() => editorRef.value?.focusEditor())
}

const startResize = (event: PointerEvent) => {
  resizing.value = true
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

const resizeEditor = (event: PointerEvent) => {
  if (!resizing.value || !workbench.value) return
  const rect = workbench.value.getBoundingClientRect()
  editorSize.value = Math.min(67, Math.max(27, ((event.clientY - rect.top) / rect.height) * 100))
}

const stopResize = () => { resizing.value = false }
const resizeWithKeyboard = (event: KeyboardEvent) => {
  if (event.key === 'ArrowUp') editorSize.value = Math.max(27, editorSize.value - 3)
  else if (event.key === 'ArrowDown') editorSize.value = Math.min(67, editorSize.value + 3)
  else return
  event.preventDefault()
}

onUnmounted(() => {
  dataSources.reset()
  objectTree.reset()
  results.reset()
  transaction.reset()
  historyStore.reset()
  execution.reset()
})
</script>

<template>
  <div class="workspace-shell">
    <AppHeader
      :sources="sources"
      :selected-source-id="selectedSourceId"
      :username="username ?? ''"
      :testing="testing"
      @change-source="changeSource"
      @test-connection="dataSources.testConnection()"
      @logout="emit('logout')"
      @toggle-sidebar="mobileSidebarOpen = !mobileSidebarOpen"
      @switch-view="emit('switchView', $event)"
    />
    <div class="workspace-body">
      <button v-if="mobileSidebarOpen" type="button" class="sidebar-scrim" aria-label="关闭对象资源管理器" @click="mobileSidebarOpen = false"></button>
      <ObjectExplorer
        :tables="tables"
        :selected-table="selectedTableName"
        :source-label="selectedSource?.displayName ?? ''"
        :refreshing="refreshing"
        :mobile-open="mobileSidebarOpen"
        @select="selectTable"
        @insert="insertObject"
        @open="openTable"
        @refresh="refreshObjects"
        @close="mobileSidebarOpen = false"
      />
      <main ref="workbench" class="workbench" :style="workbenchStyle" aria-label="SQL 工作台">
        <section class="editor-panel" aria-label="SQL 编辑与执行">
          <div class="query-tabbar">
            <div class="query-tabs" role="tablist" aria-label="查询标签页">
              <div class="query-tab active" role="tab" aria-selected="true">
                <span class="query-tab-title">查询 1</span>
                <span class="query-source mono">{{ selectedSource ? displayDatabaseType(selectedSource.databaseType) : '' }}</span>
                <button type="button" class="query-close" aria-label="关闭查询 1" @click="newQuery"><X :size="13" /></button>
              </div>
              <button type="button" class="icon-button add-query" title="新建查询" aria-label="新建查询" @click="newQuery"><Plus :size="16" /></button>
            </div>
            <button type="button" class="history-button" @click="historyDialog?.open()"><History :size="15" />历史<span class="history-count">{{ currentHistory.length }}</span></button>
          </div>
          <div class="editor-toolbar">
            <button type="button" class="button button-primary execute-button" :disabled="isRunning" @click="executeFromToolbar">
              <LoaderCircle v-if="isRunning" :size="15" class="running-icon" />
              <Play v-else :size="14" fill="currentColor" />
              {{ isRunning ? '执行中' : '执行' }}
              <kbd v-if="!isRunning">Ctrl ↵</kbd>
            </button>
            <button v-if="isRunning" type="button" class="button cancel-button" :disabled="canceling" @click="cancelQuery">
              <CircleStop :size="14" />{{ canceling ? '取消中…' : '取消查询' }}
            </button>
            <span v-else class="selection-hint">有选区时执行选区</span>
            <span class="toolbar-spacer"></span>
            <span class="dialect-label">SQL <ChevronDown :size="12" /></span>
            <button type="button" class="button clear-button" @click="clearEditor"><Eraser :size="14" />清空</button>
          </div>
          <div class="editor-host">
            <SqlEditor ref="editorRef" v-model="sql" @execute="handleEditorExecute" />
          </div>
          <div class="transaction-bar" :class="{ 'has-pending': transactionActive }">
            <GitCommitHorizontal :size="16" />
            <span class="transaction-mode">手动事务</span>
            <span class="transaction-state"><span class="transaction-dot"></span>{{ transactionActive ? '事务进行中，变更未提交' : '未开启事务' }}</span>
            <span class="transaction-context">当前会话 · {{ selectedSource?.displayName ?? '' }}</span>
            <span v-if="selectedSource" class="badge" :class="environmentBadgeClass(selectedSource.environment)">{{ displayEnvironment(selectedSource.environment) }}环境</span>
            <div class="transaction-actions">
              <button v-if="!transactionActive" type="button" class="button" :disabled="transaction.busy.value || !selectedSource" @click="beginTransaction">开启事务</button>
              <template v-else>
                <button type="button" class="button" :disabled="transaction.busy.value" @click="rollbackTransaction"><RotateCcw :size="13" />回滚</button>
                <button type="button" class="button commit-button" :disabled="transaction.busy.value" @click="commitTransaction"><Check :size="13" />提交 Commit</button>
              </template>
            </div>
          </div>
        </section>
        <div
          class="horizontal-splitter"
          :class="{ resizing }"
          role="separator"
          aria-label="调整编辑器与结果区高度"
          aria-orientation="horizontal"
          :aria-valuenow="Math.round(editorSize)"
          aria-valuemin="27"
          aria-valuemax="67"
          tabindex="0"
          @pointerdown="startResize"
          @pointermove="resizeEditor"
          @pointerup="stopResize"
          @pointercancel="stopResize"
          @keydown="resizeWithKeyboard"
        ><span></span></div>
        <ResultPanel
          :selected-table="selectedTable ?? undefined"
          :transaction-pending="transactionActive"
          :source-name="selectedSource?.displayName ?? ''"
        />
      </main>
    </div>
    <footer class="status-bar">
      <span>{{ selectedSource ? displayDatabaseType(selectedSource.databaseType) : '' }}</span>
      <span class="status-schema"><Table2 :size="11" />{{ selectedTableName }}</span>
      <span class="status-right">UTF-8</span>
      <span>SQL</span>
      <span>服务端分页</span>
    </footer>
    <ConfirmDialog
      ref="confirmDialog"
      :title="confirmTitle"
      :sql="pendingSql"
      :source-name="selectedSource?.displayName ?? ''"
      :environment="confirmEnvironment"
      :implicit-commit="confirmImplicitCommit"
      :reason-text="pendingConfirmation ? confirmationReasonText(pendingConfirmation.reason) : undefined"
      @confirm="confirmExecution"
      @cancel="cancelExecution"
    />
    <ConfirmDialog
      ref="batchConfirmDialog"
      title="确认提交表格更改？"
      :sql="batchSqlText"
      :source-name="selectedSource?.displayName ?? ''"
      :environment="confirmEnvironment"
      :implicit-commit="false"
      reason-text="以下语句将按删除、更新、新增的顺序在单一短事务内执行，任一失败整体回滚。确认只对当前这批语句有效。"
      commit-warning="确认后将在独立连接的单一短事务内执行：全部成功一起生效，任一失败整体回滚。"
      @confirm="confirmBatch"
      @cancel="cancelBatch"
    />
    <HistoryDialog ref="historyDialog" :entries="currentHistory" :source-name="selectedSource?.displayName ?? ''" @load="loadHistoryEntry" @clear="clearHistory" />
    <ToastMessage :message="toastMessage" />
  </div>
</template>

<style scoped>
.workspace-shell {
  block-size: 100dvh;
  min-block-size: 620px;
  display: flex;
  flex-direction: column;
  overflow: clip;
  background: var(--canvas);
}

.workspace-body {
  min-inline-size: 0;
  min-block-size: 0;
  display: grid;
  grid-template-columns: 266px minmax(0, 1fr);
  flex: 1;
}

.workbench {
  --editor-size: 43%;
  min-inline-size: 0;
  min-block-size: 0;
  display: grid;
  grid-template-rows: minmax(200px, var(--editor-size)) 5px minmax(190px, 1fr);
  background: var(--surface);
}

.editor-panel {
  min-inline-size: 0;
  min-block-size: 0;
  display: flex;
  flex-direction: column;
}

.query-tabbar {
  min-block-size: 38px;
  padding-inline: 10px 12px;
  border-block-end: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f7f9f8;
}

.query-tabs {
  min-inline-size: 0;
  align-self: stretch;
  display: flex;
  align-items: center;
}

.query-tab {
  position: relative;
  min-inline-size: 190px;
  block-size: 100%;
  padding-inline: 12px 7px;
  border-inline: 1px solid var(--line);
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--accent);
  background: var(--surface);
}

.query-tab::before {
  content: '';
  position: absolute;
  inset-block-start: 0;
  inset-inline: 0;
  block-size: 2px;
  background: var(--accent);
}

.query-tab-title {
  font-size: 12px;
  font-weight: 560;
}

.query-source {
  max-inline-size: 105px;
  overflow: hidden;
  color: var(--faint);
  font-size: 9px;
  text-overflow: ellipsis;
}

.query-close {
  inline-size: 23px;
  block-size: 23px;
  margin-inline-start: auto;
  padding: 0;
  border: 0;
  border-radius: 4px;
  display: grid;
  place-items: center;
  color: var(--faint);
  background: transparent;
}

.query-close:hover {
  color: var(--ink);
  background: var(--surface-hover);
}

.add-query {
  margin-inline-start: 6px;
}

.history-button {
  min-block-size: 28px;
  padding: 0 7px;
  border: 0;
  border-radius: 4px;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--muted);
  background: transparent;
  font-size: 11px;
}

.history-button:hover {
  color: var(--accent);
  background: var(--surface-hover);
}

.history-count {
  min-inline-size: 17px;
  padding: 1px 4px;
  border-radius: 7px;
  background: #e8eeeb;
  font-size: 9px;
  text-align: center;
}

.editor-toolbar {
  min-block-size: 44px;
  padding-inline: 13px;
  border-block-end: 1px solid #edf1ef;
  display: flex;
  align-items: center;
  gap: 9px;
}

.execute-button {
  min-inline-size: 110px;
  min-block-size: 30px;
  padding-inline: 11px;
  font-size: 12px;
}

.execute-button kbd {
  margin-inline-start: 4px;
  padding-inline-start: 9px;
  border-inline-start: 1px solid #ffffff42;
  color: #d5eae3;
  font-family: var(--code-font);
  font-size: 9px;
  font-weight: 400;
}

.selection-hint {
  color: var(--faint);
  font-size: 10px;
}

.toolbar-spacer {
  flex: 1;
}

.dialect-label {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--muted);
  font-size: 10px;
}

.clear-button,
.cancel-button {
  min-block-size: 29px;
  padding-inline: 8px;
  color: var(--muted);
  border-color: transparent;
  background: transparent;
  font-size: 11px;
}

.cancel-button {
  color: var(--danger);
}

.editor-host {
  min-inline-size: 0;
  min-block-size: 0;
  flex: 1;
}

.transaction-bar {
  min-block-size: 39px;
  padding-inline: 13px 10px;
  border-block-start: 1px solid var(--line);
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  background: #f8faf9;
  font-size: 10px;
}

.transaction-bar.has-pending {
  color: #79531f;
  background: var(--warning-soft);
  box-shadow: inset 3px 0 #ba7a29;
}

.transaction-mode {
  color: var(--ink);
  font-weight: 570;
}

.transaction-state {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.transaction-dot {
  inline-size: 6px;
  block-size: 6px;
  border-radius: 50%;
  background: #8e9b95;
}

.has-pending .transaction-dot {
  background: #b57727;
}

.transaction-context {
  padding-inline-start: 9px;
  border-inline-start: 1px solid #dbcbaa;
  color: var(--faint);
}

.transaction-actions {
  margin-inline-start: auto;
  display: flex;
  gap: 6px;
}

.transaction-actions .button {
  min-block-size: 27px;
  padding-inline: 8px;
  font-size: 10px;
}

.has-pending .commit-button {
  color: #ffffff;
  border-color: #8b5e20;
  background: #8b5e20;
}

.horizontal-splitter {
  position: relative;
  z-index: 4;
  border-block: 1px solid var(--line);
  background: #f2f5f3;
  cursor: row-resize;
  touch-action: none;
}

.horizontal-splitter span {
  position: absolute;
  inset-block-start: 1px;
  inset-inline-start: calc(50% - 18px);
  inline-size: 36px;
  block-size: 1px;
  background: #b7c3bd;
}

.horizontal-splitter:hover,
.horizontal-splitter.resizing {
  background: #dce9e3;
}

.status-bar {
  min-block-size: 25px;
  padding-inline: 12px;
  border-block-start: 1px solid var(--line);
  display: flex;
  align-items: center;
  gap: 17px;
  color: #75847d;
  background: #f8faf9;
  font-size: 9px;
}

.status-connected,
.status-schema {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.status-connected > span {
  inline-size: 6px;
  block-size: 6px;
  border-radius: 50%;
  background: var(--success);
}

.status-right {
  margin-inline-start: auto;
}

.running-icon {
  animation: rotate 800ms linear infinite;
}

.sidebar-scrim {
  display: none;
}

@keyframes rotate {
  to { transform: rotate(360deg); }
}

@media (max-width: 900px) {
  .workspace-body {
    grid-template-columns: minmax(0, 1fr);
  }

  .sidebar-scrim {
    position: fixed;
    z-index: 50;
    inset: 95px 0 25px;
    border: 0;
    display: block;
    background: #14241e45;
  }
}

@media (max-width: 680px) {
  .workspace-shell {
    min-block-size: 100dvh;
    block-size: auto;
    overflow: visible;
  }

  .workspace-body {
    display: block;
  }

  .workbench {
    display: flex;
    flex-direction: column;
  }

  .editor-panel {
    block-size: 430px;
    flex: 0 0 auto;
  }

  .horizontal-splitter {
    display: none;
  }

  .result-panel {
    block-size: 490px;
    border-block-start: 5px solid var(--line);
  }

  .transaction-context,
  .selection-hint,
  .dialect-label,
  .query-source {
    display: none;
  }

  .transaction-bar {
    padding-inline: 9px;
    gap: 6px;
  }

  .transaction-actions .button {
    padding-inline: 6px;
  }

  .status-bar {
    gap: 11px;
  }

  .status-schema,
  .status-bar > span:last-child {
    display: none;
  }
}
</style>
