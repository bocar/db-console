<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Check, FileUp, LoaderCircle, Play, ShieldAlert, CircleStop, X } from 'lucide-vue-next'
import AppHeader from './AppHeader.vue'
import ScriptStatementList from './ScriptStatementList.vue'
import ToastMessage from './ToastMessage.vue'
import { confirmationReasonText, displayEnvironment, environmentBadgeClass } from '../display'
import { useDataSources } from '../composables/useDataSources'
import { useManualTransaction } from '../composables/useManualTransaction'
import { useScriptExecution } from '../composables/useScriptExecution'
import { useSession } from '../composables/useSession'
import { useToast } from '../composables/useToast'

const emit = defineEmits<{ logout: []; switchView: [view: 'workspace' | 'script'] }>()

const { username } = useSession()
const { notify, message: toastMessage } = useToast()
const dataSources = useDataSources()
const { sources, selectedSourceId, selectedSource, testing } = dataSources
const transaction = useManualTransaction()
const script = useScriptExecution()

const fileInput = ref<HTMLInputElement | null>(null)
const confirmPanelOpen = ref(false)
let transactionSynced = false

const environmentBadge = computed(() =>
  selectedSource.value ? environmentBadgeClass(selectedSource.value.environment) : '')

const summaryLines = computed(() => {
  const byReason = new Map<string, number>()
  for (const plan of script.plans.value) {
    if (plan.confirmationReason) {
      byReason.set(plan.confirmationReason, (byReason.get(plan.confirmationReason) ?? 0) + 1)
    }
  }
  return [...byReason.entries()].map(([reason, count]) => ({
    reason,
    text: confirmationReasonText(reason as Parameters<typeof confirmationReasonText>[0]),
    count,
  }))
})

onMounted(async () => {
  await dataSources.load()
  if (!transactionSynced && selectedSource.value) {
    transaction.sync(selectedSource.value.id)
    transactionSynced = true
  }
})

watch(selectedSourceId, () => {
  if (script.hasScript.value) {
    script.reset()
    notify('已切换数据源，此前脚本预校验与凭据已作废。')
  }
})

function changeSource(id: string): void {
  if (script.running.value) {
    notify('脚本执行中，请等待完成或取消后再切换数据源。')
    return
  }
  if (transaction.active.value) {
    notify('当前有未提交事务，请先提交或回滚再切换数据源。')
    return
  }
  dataSources.selectSource(id)
}

function pickFile(): void {
  fileInput.value?.click()
}

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  const source = selectedSource.value
  if (!file || !source) return
  await script.loadFile(file, source.id)
  // 允许重复选择同一文件：清空 value 使 change 事件可再次触发
  input.value = ''
}

function startRun(): void {
  const source = selectedSource.value
  if (!source) return
  confirmPanelOpen.value = false
  void script.run(0, source.id)
}

function resumeRun(index: number): void {
  const source = selectedSource.value
  if (!source) return
  void script.run(index, source.id)
}
</script>

<template>
  <div class="script-shell">
    <AppHeader
      :sources="sources"
      :selected-source-id="selectedSourceId"
      :username="username ?? ''"
      :testing="testing"
      active-view="script"
      :sidebar-toggle="false"
      @change-source="changeSource"
      @test-connection="dataSources.testConnection()"
      @logout="emit('logout')"
      @switch-view="emit('switchView', $event)"
    />
    <main class="script-body" aria-label="脚本执行">
      <section class="upload-panel">
        <div class="upload-head">
          <h1>脚本执行</h1>
          <p>上传 SQL 脚本文件，服务端按方言整份预校验后逐条执行（F-13）。每条语句独立提交，失败即停，可从失败条次恢复。</p>
        </div>
        <div class="upload-row">
          <input
            ref="fileInput"
            type="file"
            accept=".sql,.txt"
            class="visually-hidden"
            aria-label="选择 SQL 脚本文件"
            @change="onFileChange"
          />
          <button type="button" class="button upload-button" :disabled="script.analyzing.value || !selectedSource" @click="pickFile">
            <LoaderCircle v-if="script.analyzing.value" :size="15" class="spin" />
            <FileUp v-else :size="15" />
            {{ script.analyzing.value ? '预校验中…' : '选择并上传脚本' }}
          </button>
          <span v-if="selectedSource" class="badge" :class="environmentBadge">{{ displayEnvironment(selectedSource.environment) }}环境</span>
          <span class="upload-hint">支持 .sql / .txt · UTF-8 · 单语句上限与语句数上限由服务端配置</span>
        </div>
      </section>

      <section v-if="!script.filename.value" class="placeholder-panel">
        <FileUp :size="30" />
        <p>上传脚本后，这里将展示语句顺序、类型与确认要求。</p>
      </section>

      <section v-if="script.states.value.length > 0" class="result-panel">
        <div class="script-summary">
          <span class="mono filename">{{ script.filename.value }}</span>
          <span class="summary-item">共 {{ script.progress.value.total }} 条</span>
          <span class="summary-item success">成功 {{ script.progress.value.succeeded }} 条</span>
          <span v-if="script.needsConfirmationCount.value > 0" class="summary-item warning">
            需确认 {{ script.needsConfirmationCount.value }} 条
          </span>
          <span v-if="script.running.value" class="summary-item running">
            <LoaderCircle :size="13" class="spin" />执行中…
          </span>
          <div class="summary-actions">
            <button
              v-if="!script.running.value && !script.allSucceeded.value && script.plans.value.length > 0"
              type="button"
              class="button button-primary"
              @click="confirmPanelOpen = true"
            >
              <Play :size="14" fill="currentColor" />
              {{ script.progress.value.stoppedAt >= 0 ? '重新执行整个脚本' : '执行整个脚本' }}
            </button>
            <button
              v-if="script.running.value"
              type="button"
              class="button cancel-button"
              @click="script.cancelRun()"
            >
              <CircleStop :size="14" />{{ script.cancelRequested.value ? '取消中…' : '取消执行' }}
            </button>
          </div>
        </div>

        <div v-if="confirmPanelOpen" class="confirm-panel" role="alertdialog" aria-label="确认执行脚本">
          <div class="confirm-head">
            <ShieldAlert :size="17" />
            <strong>确认执行整份脚本？</strong>
          </div>
          <p class="confirm-meta">
            目标：{{ selectedSource?.displayName ?? '' }}
            <span class="badge" :class="environmentBadge">{{ displayEnvironment(selectedSource?.environment ?? 'DEVELOPMENT') }}环境</span>
            共 {{ script.progress.value.total }} 条语句，逐条独立提交，失败即停。
          </p>
          <ul v-if="summaryLines.length > 0" class="confirm-risks">
            <li v-for="line in summaryLines" :key="line.reason">
              {{ line.count }} 条需确认（{{ line.text }}）
            </li>
          </ul>
          <p v-else class="confirm-risks-none">本脚本无需执行确认的语句。</p>
          <div class="confirm-actions">
            <button type="button" class="button button-primary" @click="startRun">
              <Check :size="14" />确认执行
            </button>
            <button type="button" class="button" @click="confirmPanelOpen = false"><X :size="14" />取消</button>
          </div>
        </div>

        <ScriptStatementList :states="script.states.value" @resume="resumeRun" />
      </section>
    </main>
    <ToastMessage :message="toastMessage" />
  </div>
</template>

<style scoped>
.script-shell {
  min-block-size: 100dvh;
  display: flex;
  flex-direction: column;
  background: #f4f6f5;
}

.script-body {
  flex: 1;
  inline-size: min(880px, calc(100% - 32px));
  margin: 0 auto;
  padding: 18px 0 32px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.upload-panel {
  padding: 16px 18px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--surface);
}

.upload-head h1 {
  margin: 0;
  font-size: 17px;
  font-weight: 620;
}

.upload-head p {
  margin: 5px 0 12px;
  color: var(--muted);
  font-size: 12px;
}

.upload-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.upload-button {
  min-block-size: 32px;
}

.upload-hint {
  color: var(--faint);
  font-size: 11px;
}

.placeholder-panel {
  padding: 46px;
  border: 1px dashed var(--line-strong);
  border-radius: 9px;
  display: grid;
  place-items: center;
  color: var(--faint);
}

.placeholder-panel p {
  margin: 10px 0 0;
  font-size: 12px;
}

.result-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.script-summary {
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--surface);
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 12px;
}

.filename {
  font-weight: 620;
}

.summary-item {
  color: var(--muted);
}

.summary-item.success {
  color: var(--success);
}

.summary-item.warning {
  color: var(--warning);
}

.summary-item.running {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--accent);
}

.summary-actions {
  margin-inline-start: auto;
  display: flex;
  gap: 8px;
}

.confirm-panel {
  padding: 13px 15px;
  border: 1px solid var(--warning);
  border-radius: 9px;
  background: #fffaf0;
}

.confirm-head {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--ink);
  font-size: 13px;
}

.confirm-head svg {
  color: var(--warning);
}

.confirm-meta {
  margin: 7px 0;
  color: var(--muted);
  font-size: 12px;
}

.confirm-risks {
  margin: 0 0 10px;
  padding-inline-start: 18px;
  color: var(--ink);
  font-size: 12px;
}

.confirm-risks li {
  margin-block: 3px;
}

.confirm-risks-none {
  margin: 0 0 10px;
  color: var(--muted);
  font-size: 12px;
}

.confirm-actions {
  display: flex;
  gap: 8px;
}

.spin {
  animation: spin 900ms linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.visually-hidden {
  position: absolute;
  inline-size: 1px;
  block-size: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}

@media (max-width: 640px) {
  .script-body {
    inline-size: calc(100% - 20px);
  }
}
</style>
