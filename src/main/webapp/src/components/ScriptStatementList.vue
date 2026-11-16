<script setup lang="ts">
import { ref } from 'vue'
import { ChevronDown, ChevronRight } from 'lucide-vue-next'
import type { ScriptStatementState } from '../types'
import {
  confirmationReasonText,
  displayQueryValue,
  displayScriptStatus,
  displayStatementKind,
  formatDuration,
  isStopStatus,
} from '../display'

const props = defineProps<{ states: ScriptStatementState[] }>()
const emit = defineEmits<{ resume: [index: number] }>()

const expanded = ref<number | null>(null)

function toggle(index: number): void {
  expanded.value = expanded.value === index ? null : index
}

const statusClass = (state: ScriptStatementState) => state.status.toLowerCase().replace('_', '-')

const isQueryResult = (state: ScriptStatementState) => state.queryResult !== null
</script>

<template>
  <ol class="statement-list" aria-label="脚本语句列表">
    <li
      v-for="(state, position) in props.states"
      :key="state.plan.index"
      class="statement-row"
      :class="[statusClass(state), { open: expanded === position }]"
    >
      <div class="statement-summary">
        <button
          type="button"
          class="statement-toggle"
          :aria-expanded="expanded === position"
          :aria-label="`展开或收起第 ${state.plan.index} 条语句`"
          @click="toggle(position)"
        >
          <ChevronRight v-if="expanded !== position" :size="14" />
          <ChevronDown v-else :size="14" />
        </button>
        <span class="statement-index mono">#{{ state.plan.index }}</span>
        <span class="statement-kind">{{ displayStatementKind(state.plan.kind) }}</span>
        <span
          v-if="state.plan.confirmationReason"
          class="badge risk-badge"
          :title="confirmationReasonText(state.plan.confirmationReason)"
        >需确认</span>
        <span class="statement-status">{{ displayScriptStatus(state.status) }}</span>
        <span v-if="state.elapsedMs !== null" class="statement-duration">{{ formatDuration(state.elapsedMs) }}</span>
        <span v-if="state.affectedRows !== null" class="statement-affected">{{ state.affectedRows }} 行</span>
        <template v-if="isStopStatus(state.status)">
          <button
            type="button"
            class="resume-button"
            :title="state.status === 'UNKNOWN_OUTCOME' ? '结果未知：请先核实数据库状态再重试' : ''"
            @click="emit('resume', position)"
          >重试本条</button>
          <button
            v-if="position + 1 < props.states.length"
            type="button"
            class="resume-button secondary"
            @click="emit('resume', position + 1)"
          >跳过本条，从第 {{ position + 2 }} 条继续</button>
        </template>
      </div>
      <div v-if="state.message" class="statement-message" role="alert">{{ state.message }}</div>
      <div v-if="expanded === position" class="statement-detail">
        <pre class="mono">{{ state.plan.sql }}</pre>
        <div v-if="isQueryResult(state)" class="statement-query">
          <table>
            <thead>
              <tr>
                <th v-for="column in state.queryResult?.columns" :key="column.name">{{ column.name }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, rowIndex) in state.queryResult?.rows" :key="rowIndex">
                <td v-for="(value, cellIndex) in row" :key="cellIndex" class="mono">{{ displayQueryValue(value) }}</td>
              </tr>
            </tbody>
          </table>
          <p v-if="state.queryResult?.truncated" class="query-truncated">结果超出展示上限，仅显示截断后的首批行。</p>
        </div>
      </div>
    </li>
  </ol>
</template>

<style scoped>
.statement-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.statement-row {
  border: 1px solid var(--line);
  border-radius: 6px;
  margin-block-end: 6px;
  background: var(--surface);
}

.statement-row.open {
  border-color: #b6cbc2;
}

.statement-row.succeeded .statement-status {
  color: var(--success);
}

.statement-row.failed .statement-status,
.statement-row.unknown-outcome .statement-status {
  color: var(--danger);
}

.statement-row.cancelled .statement-status {
  color: var(--warning);
}

.statement-row.skipped .statement-status {
  color: var(--faint);
}

.statement-summary {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 6px 10px;
  font-size: 12px;
}

.statement-toggle {
  padding: 2px;
  border: 0;
  color: var(--muted);
  background: transparent;
  display: inline-flex;
}

.statement-index {
  color: var(--faint);
}

.statement-kind {
  min-inline-size: 40px;
  color: var(--ink);
  font-weight: 560;
}

.risk-badge {
  font-size: 10px;
}

.statement-status {
  font-weight: 560;
}

.statement-duration,
.statement-affected {
  color: var(--muted);
}

.resume-button {
  margin-inline-start: auto;
  padding: 2px 8px;
  border: 1px solid var(--line-strong);
  border-radius: 5px;
  color: var(--accent);
  background: transparent;
  font-size: 11px;
  font-weight: 550;
}

.resume-button.secondary {
  margin-inline-start: 0;
  color: var(--muted);
}

.resume-button:hover {
  background: var(--surface-hover);
}

.statement-message {
  padding: 0 10px 8px 35px;
  color: var(--danger);
  font-size: 12px;
}

.statement-detail {
  padding: 0 10px 10px 35px;
}

.statement-detail pre {
  max-block-size: 140px;
  margin: 0 0 8px;
  padding: 8px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 5px;
  background: #fbfcfb;
  font-size: 11px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.statement-query {
  max-block-size: 220px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 5px;
}

.statement-query table {
  inline-size: 100%;
  border-collapse: collapse;
  font-size: 11px;
}

.statement-query th,
.statement-query td {
  padding: 4px 8px;
  border-block-end: 1px solid var(--line);
  text-align: start;
  white-space: nowrap;
}

.statement-query th {
  color: var(--muted);
  background: #f6f8f7;
  font-weight: 560;
  position: sticky;
  inset-block-start: 0;
}

.query-truncated {
  margin: 8px 0 0;
  color: var(--warning);
  font-size: 11px;
}
</style>
