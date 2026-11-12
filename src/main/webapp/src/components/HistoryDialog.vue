<script setup lang="ts">
import { computed, ref } from 'vue'
import { Clock3, History, Search, Trash2, X } from 'lucide-vue-next'
import type { HistoryEntry } from '../types'
import { displayHistoryStatus, formatClockTime, formatDuration } from '../display'

const props = defineProps<{ entries: HistoryEntry[]; sourceName: string }>()
const emit = defineEmits<{ load: [sql: string]; clear: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const filter = ref('')

const filteredEntries = computed(() => {
  const keyword = filter.value.trim().toLowerCase()
  return keyword ? props.entries.filter((entry) => entry.sql.toLowerCase().includes(keyword)) : props.entries
})

const open = () => dialog.value?.showModal()
const load = (sql: string) => {
  emit('load', sql)
  dialog.value?.close()
}

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="history-dialog" aria-labelledby="history-title" @close="() => { if (dialog) dialog.returnValue = '' }">
    <div class="history-header">
      <div>
        <h2 id="history-title">SQL 历史</h2>
        <p>{{ sourceName }} · 仅保留当前登录会话</p>
      </div>
      <button type="button" class="icon-button" aria-label="关闭历史记录" @click="dialog?.close()"><X :size="19" /></button>
    </div>
    <form class="history-search" role="search" action="#history" method="get" @submit.prevent>
      <Search :size="15" />
      <label class="visually-hidden" for="history-filter">查找历史 SQL</label>
      <input id="history-filter" v-model="filter" type="search" name="history-filter" placeholder="查找历史 SQL" autocomplete="off" />
    </form>
    <div class="history-list">
      <article v-for="entry in filteredEntries" :key="entry.id" class="history-entry">
        <div class="history-meta">
          <span><Clock3 :size="12" />{{ formatClockTime(entry.executedAt) }}</span>
          <span>{{ formatDuration(entry.durationMs) }}</span>
          <span class="history-status" :class="{ pending: entry.uncommitted, failed: entry.status === 'FAILED' }">{{ displayHistoryStatus(entry) }}</span>
        </div>
        <pre class="mono">{{ entry.sql }}</pre>
        <button type="button" class="load-history" @click="load(entry.sql)">载入编辑器</button>
      </article>
      <div v-if="filteredEntries.length === 0" class="history-empty">
        <History :size="28" />
        <p>没有匹配的历史语句</p>
      </div>
    </div>
    <div class="history-footer">
      <span>载入后不会自动执行</span>
      <button type="button" class="button" :disabled="entries.length === 0" @click="emit('clear')"><Trash2 :size="14" />清空当前数据源历史</button>
    </div>
  </dialog>
</template>

<style scoped>
.history-dialog {
  inline-size: min(680px, calc(100% - 32px));
  max-block-size: min(720px, calc(100dvh - 48px));
  padding: 0;
  border: 1px solid var(--line-strong);
  border-radius: 9px;
  color: var(--ink);
  background: var(--surface);
  box-shadow: 0 24px 70px #14261f33;
}

.history-dialog:modal {
  overscroll-behavior: contain;
}

.history-dialog::backdrop {
  overflow: hidden;
  overscroll-behavior: contain;
  background: #14241e66;
  backdrop-filter: blur(2px);
}

.history-header {
  min-block-size: 76px;
  padding: 16px 19px;
  border-block-end: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.history-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.history-header p {
  margin: 5px 0 0;
  color: var(--muted);
  font-size: 11px;
}

.history-search {
  inline-size: calc(100% - 36px);
  min-block-size: 35px;
  margin: 13px 18px;
  padding-inline: 10px;
  border: 1px solid var(--line);
  border-radius: 5px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--faint);
}

.history-search:focus-within {
  border-color: var(--accent);
}

.history-search input {
  min-inline-size: 0;
  border: 0;
  outline: 0;
  color: var(--ink);
  background: transparent;
  flex: 1;
  font-size: 12px;
}

.history-list {
  max-block-size: 450px;
  padding: 0 18px 13px;
  overflow: auto;
  overscroll-behavior: contain;
}

.history-entry {
  position: relative;
  margin-block-end: 9px;
  padding: 11px 12px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fbfcfb;
}

.history-entry:hover {
  border-color: #b6cbc2;
}

.history-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--faint);
  font-size: 10px;
}

.history-meta > span:first-child {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.history-status {
  color: var(--success);
}

.history-status.pending {
  color: var(--warning);
}

.history-status.failed {
  color: var(--danger);
}

.history-entry pre {
  max-block-size: 83px;
  margin: 9px 0;
  overflow: hidden;
  color: #3c4d46;
  font-size: 10px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.load-history {
  padding: 0;
  border: 0;
  color: var(--accent);
  background: transparent;
  font-size: 11px;
  font-weight: 550;
}

.history-empty {
  padding: 50px;
  display: grid;
  place-items: center;
  color: var(--faint);
}

.history-empty p {
  margin: 10px 0;
  font-size: 12px;
}

.history-footer {
  min-block-size: 57px;
  padding-inline: 18px;
  border-block-start: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 15px;
  color: var(--faint);
  font-size: 11px;
}

.history-footer .button {
  font-size: 11px;
}
</style>
