<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref } from 'vue'
import { Check, ChevronDown, CircleStop, Eraser, GitCommitHorizontal, History, LoaderCircle, Play, Plus, RotateCcw, Table2, X } from 'lucide-vue-next'
import AppHeader from './AppHeader.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import HistoryDialog from './HistoryDialog.vue'
import ObjectExplorer from './ObjectExplorer.vue'
import ResultPanel from './ResultPanel.vue'
import SqlEditor from './SqlEditor.vue'
import { dataSources, defaultSql, initialHistory, queryColumns, queryRows, tables } from '../data/mock'
import type { HistoryEntry } from '../types'

interface EditorHandle {
  executionText: () => string
  insertText: (text: string) => void
  focusEditor: () => void
}

interface ConfirmHandle { open: () => void }
interface HistoryHandle { open: () => void }

const emit = defineEmits<{ logout: [] }>()
const selectedSourceId = ref('orders-prod')
const selectedSource = computed(() => dataSources.find((source) => source.id === selectedSourceId.value) ?? dataSources[0])
const selectedTableName = ref('orders')
const selectedTable = computed(() => tables.find((table) => table.name === selectedTableName.value) ?? tables[0])
const sql = ref(defaultSql)
const editorRef = ref<EditorHandle | null>(null)
const confirmDialog = ref<ConfirmHandle | null>(null)
const historyDialog = ref<HistoryHandle | null>(null)
const pendingSql = ref(defaultSql)
const confirmTitle = ref('确认执行此操作？')
const implicitCommit = ref(false)
const isRunning = ref(false)
const canceling = ref(false)
const testing = ref(false)
const refreshing = ref(false)
const transactionPending = ref(true)
const activeResultTab = ref<'result' | 'structure' | 'info'>('result')
const hasResult = ref(true)
const page = ref(1)
const pageSize = ref(20)
const elapsed = ref(86)
const historyEntries = ref<HistoryEntry[]>(initialHistory.map((entry) => ({ ...entry })))
const toast = ref('')
const mobileSidebarOpen = ref(false)
const editorSize = ref(43)
const resizing = ref(false)
const workbench = ref<HTMLElement | null>(null)
const timers = new Set<number>()
let runTimer: number | undefined
let toastTimer: number | undefined

const visibleRows = computed(() => {
  if (!hasResult.value) return []
  const start = (page.value - 1) * pageSize.value
  return queryRows.slice(start, start + pageSize.value)
})
const visibleColumns = computed(() => hasResult.value ? queryColumns : [])
const totalRows = computed(() => hasResult.value ? queryRows.length : 0)
const currentHistory = computed(() => historyEntries.value.filter((entry) => entry.sourceId === selectedSourceId.value))
const workbenchStyle = computed(() => ({ '--editor-size': `${editorSize.value}%` }))

const schedule = (callback: () => void, delay: number) => {
  const timer = window.setTimeout(() => {
    timers.delete(timer)
    callback()
  }, delay)
  timers.add(timer)
  return timer
}

const notify = (message: string) => {
  toast.value = message
  if (toastTimer !== undefined) {
    window.clearTimeout(toastTimer)
    timers.delete(toastTimer)
  }
  toastTimer = schedule(() => { toast.value = '' }, 2800)
}

const execute = (text: string) => {
  const statement = text.trim()
  if (!statement) {
    notify('请输入一条 SQL 语句后再执行。')
    return
  }
  if (isRunning.value) return
  const isDdl = /^(create|alter|drop|comment)\b/i.test(statement)
  const isWrite = /^(insert|update|delete)\b/i.test(statement)
  if (isDdl && transactionPending.value) {
    notify('请先提交或回滚当前事务，再执行隐式提交 DDL。')
    return
  }
  const missingWhere = /^(update|delete)\b/i.test(statement) && !/\bwhere\b/i.test(statement)
  const needsConfirmation = isDdl || (selectedSource.value.environment === '生产' && isWrite) || missingWhere
  pendingSql.value = statement
  if (needsConfirmation) {
    implicitCommit.value = isDdl
    confirmTitle.value = selectedSource.value.environment === '生产' ? '确认在生产库执行？' : '确认执行此操作？'
    confirmDialog.value?.open()
    return
  }
  runStatement()
}

const runStatement = () => {
  const statement = pendingSql.value.trim()
  const isWrite = /^(insert|update|delete)\b/i.test(statement)
  const isDdl = /^(create|alter|drop|comment)\b/i.test(statement)
  const isDescribe = /^(desc|describe)\b/i.test(statement)
  isRunning.value = true
  canceling.value = false
  runTimer = schedule(() => {
    isRunning.value = false
    runTimer = undefined
    elapsed.value = statement.length % 70 + 34
    const entry: HistoryEntry = {
      id: Date.now(),
      sourceId: selectedSourceId.value,
      sql: statement,
      time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
      duration: `${elapsed.value} ms`,
      status: isWrite ? '未提交' : '成功',
    }
    historyEntries.value.unshift(entry)
    if (isWrite) {
      transactionPending.value = true
      hasResult.value = false
      activeResultTab.value = 'info'
      notify('执行成功，变更尚未提交。')
    } else if (isDdl) {
      hasResult.value = false
      activeResultTab.value = 'info'
      notify('DDL 已在独立连接执行并生效。')
    } else if (isDescribe) {
      const tableName = statement.replace(/^(desc|describe)\s+/i, '').replace(/[`"';]/g, '').split('.').pop()
      selectedTableName.value = tables.some((table) => table.name === tableName) ? tableName ?? 'orders' : 'orders'
      activeResultTab.value = 'structure'
      notify('表结构已更新。')
    } else {
      hasResult.value = true
      page.value = 1
      activeResultTab.value = 'result'
      notify('查询已完成，结果已更新。')
    }
  }, 850)
}

const executeFromToolbar = () => execute(editorRef.value?.executionText() ?? sql.value)

const cancelQuery = () => {
  if (!isRunning.value || canceling.value) return
  canceling.value = true
  if (runTimer !== undefined) {
    window.clearTimeout(runTimer)
    timers.delete(runTimer)
    runTimer = undefined
  }
  schedule(() => {
    isRunning.value = false
    canceling.value = false
    activeResultTab.value = 'info'
    notify('查询已取消，未自动重试。')
  }, 500)
}

const commitTransaction = () => {
  transactionPending.value = false
  historyEntries.value.forEach((entry) => {
    if (entry.sourceId === selectedSourceId.value && entry.status === '未提交') entry.status = '成功'
  })
  notify('事务已提交，所有变更已生效。')
}

const rollbackTransaction = () => {
  transactionPending.value = false
  historyEntries.value.forEach((entry) => {
    if (entry.sourceId === selectedSourceId.value && entry.status === '未提交') entry.status = '成功'
  })
  notify('事务已回滚，未提交变更已撤销。')
}

const changeSource = (id: string) => {
  if (id === selectedSourceId.value) return
  if (transactionPending.value) {
    notify('当前有未提交事务，请先提交或回滚再切换数据源。')
    return
  }
  selectedSourceId.value = id
  selectedTableName.value = 'orders'
  hasResult.value = false
  activeResultTab.value = 'result'
  page.value = 1
  mobileSidebarOpen.value = false
  notify(`已切换到${selectedSource.value.name}，旧结果已清除。`)
}

const testConnection = () => {
  testing.value = true
  schedule(() => {
    testing.value = false
    notify(`${selectedSource.value.name}连接正常，认证成功。`)
  }, 700)
}

const refreshObjects = () => {
  refreshing.value = true
  schedule(() => {
    refreshing.value = false
    notify('对象树已刷新。')
  }, 650)
}

const selectTable = (name: string) => {
  selectedTableName.value = name
  activeResultTab.value = 'structure'
  mobileSidebarOpen.value = false
}

const quoteIdentifier = (name: string) => selectedSource.value.type === 'DM8' ? `"${name}"` : `\`${name}\``
const insertObject = (name: string) => editorRef.value?.insertText(quoteIdentifier(name))
const openTable = (name: string) => {
  selectedTableName.value = name
  sql.value = `SELECT *\nFROM ${quoteIdentifier(name)}\nLIMIT 100;`
  nextTick(() => editorRef.value?.focusEditor())
}

const loadHistory = (value: string) => {
  sql.value = value
  nextTick(() => editorRef.value?.focusEditor())
  notify('历史语句已载入编辑器，尚未执行。')
}

const clearHistory = () => {
  historyEntries.value = historyEntries.value.filter((entry) => entry.sourceId !== selectedSourceId.value)
  notify('当前数据源的会话历史已清空。')
}

const newQuery = () => {
  sql.value = ''
  hasResult.value = false
  activeResultTab.value = 'result'
  nextTick(() => editorRef.value?.focusEditor())
}

const clearEditor = () => {
  sql.value = ''
  nextTick(() => editorRef.value?.focusEditor())
}

const changePageSize = (size: number) => {
  pageSize.value = size
  page.value = 1
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
  timers.forEach((timer) => window.clearTimeout(timer))
})
</script>

<template>
  <div class="workspace-shell">
    <AppHeader
      :sources="dataSources"
      :selected-source-id="selectedSourceId"
      :testing="testing"
      @change-source="changeSource"
      @test-connection="testConnection"
      @logout="emit('logout')"
      @toggle-sidebar="mobileSidebarOpen = !mobileSidebarOpen"
    />
    <div class="workspace-body">
      <button v-if="mobileSidebarOpen" type="button" class="sidebar-scrim" aria-label="关闭对象资源管理器" @click="mobileSidebarOpen = false"></button>
      <ObjectExplorer
        :tables="tables"
        :selected-table="selectedTableName"
        :schema="selectedSource.currentSchema"
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
                <span class="query-source mono">{{ selectedSource.database }}</span>
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
            <SqlEditor ref="editorRef" v-model="sql" @execute="execute" />
          </div>
          <div class="transaction-bar" :class="{ 'has-pending': transactionPending }">
            <GitCommitHorizontal :size="16" />
            <span class="transaction-mode">手动事务</span>
            <span class="transaction-state"><span class="transaction-dot"></span>{{ transactionPending ? '有变更未提交' : '无未提交变更' }}</span>
            <span class="transaction-context">当前会话 · {{ selectedSource.name }}</span>
            <div class="transaction-actions">
              <button type="button" class="button" :disabled="!transactionPending" @click="rollbackTransaction"><RotateCcw :size="13" />回滚</button>
              <button type="button" class="button commit-button" :disabled="!transactionPending" @click="commitTransaction"><Check :size="13" />提交 Commit</button>
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
          :columns="visibleColumns"
          :rows="visibleRows"
          :selected-table="selectedTable"
          :page="page"
          :page-size="pageSize"
          :total-rows="totalRows"
          :elapsed="elapsed"
          :active-tab="activeResultTab"
          :transaction-pending="transactionPending"
          :source-name="selectedSource.name"
          :has-result="hasResult"
          @tab-change="activeResultTab = $event"
          @page-change="page = $event"
          @page-size-change="changePageSize"
          @notify="notify"
        />
      </main>
    </div>
    <footer class="status-bar">
      <span class="status-connected"><span></span>已连接</span>
      <span>{{ selectedSource.type }}</span>
      <span class="status-schema"><Table2 :size="11" />{{ selectedTableName }}</span>
      <span class="status-right">UTF-8</span>
      <span>SQL</span>
      <span>数据库分页</span>
    </footer>
    <ConfirmDialog
      ref="confirmDialog"
      :title="confirmTitle"
      :sql="pendingSql"
      :source-name="selectedSource.name"
      :environment="selectedSource.environment"
      :implicit-commit="implicitCommit"
      @confirm="runStatement"
    />
    <HistoryDialog ref="historyDialog" :entries="currentHistory" :source-name="selectedSource.name" @load="loadHistory" @clear="clearHistory" />
    <div v-if="toast" class="toast-message" role="status"><Check :size="16" />{{ toast }}</div>
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

.toast-message {
  position: fixed;
  z-index: 100;
  inset-inline-end: 22px;
  inset-block-end: 43px;
  max-inline-size: min(440px, calc(100% - 32px));
  min-block-size: 42px;
  padding: 10px 15px;
  border: 1px solid #b9d4c8;
  border-radius: 6px;
  display: flex;
  align-items: center;
  gap: 9px;
  color: #275c45;
  background: #f2faf5;
  box-shadow: 0 10px 32px #1c382b1f;
  font-size: 12px;
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
