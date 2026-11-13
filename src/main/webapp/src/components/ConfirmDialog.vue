<script setup lang="ts">
import { ref } from 'vue'
import { AlertTriangle, Database, LockKeyhole, X } from 'lucide-vue-next'

const props = defineProps<{
  title: string
  sql: string
  sourceName: string
  environment: string
  implicitCommit: boolean
  reasonText?: string
}>()

const emit = defineEmits<{ confirm: []; cancel: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)

const open = () => dialog.value?.showModal()
const onClose = () => {
  if (dialog.value?.returnValue === 'confirm') emit('confirm')
  else emit('cancel')
  if (dialog.value) dialog.value.returnValue = ''
}

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="confirm-dialog" aria-labelledby="confirm-title" aria-describedby="confirm-description" @close="onClose">
    <form method="dialog">
      <div class="dialog-topline">
        <span class="warning-icon"><AlertTriangle :size="20" /></span>
        <button type="submit" class="icon-button" value="cancel" aria-label="关闭确认对话框"><X :size="18" /></button>
      </div>
      <h2 id="confirm-title">{{ props.title }}</h2>
      <p id="confirm-description" class="dialog-description">
        {{ props.reasonText ?? '请核对目标环境和完整语句。确认只对当前会话、数据源和这份 SQL 有效。' }}
      </p>
      <div class="target-box">
        <Database :size="16" />
        <span>{{ sourceName }}</span>
        <span class="badge" :class="{ 'badge-production': environment === '生产' }">{{ environment }}</span>
      </div>
      <pre class="sql-preview mono">{{ sql }}</pre>
      <div class="commit-warning" :class="{ implicit: implicitCommit }">
        <LockKeyhole :size="15" />
        <p v-if="implicitCommit">此语句会触发隐式提交。确认后将在独立连接执行，成功即生效，不能随当前事务回滚。</p>
        <p v-else>此操作将加入当前手动事务。执行成功后仍需提交；如需撤销，请使用回滚。</p>
      </div>
      <div class="dialog-actions">
        <button type="submit" class="button" value="cancel" autofocus>取消</button>
        <button type="submit" class="button button-danger" value="confirm">确认执行</button>
      </div>
    </form>
  </dialog>
</template>

<style scoped>
.confirm-dialog {
  inline-size: min(540px, calc(100% - 32px));
  max-block-size: calc(100dvh - 48px);
  padding: 0;
  border: 1px solid var(--line-strong);
  border-radius: 9px;
  color: var(--ink);
  background: var(--surface);
  box-shadow: 0 24px 70px #14261f33;
}

.confirm-dialog:modal {
  overscroll-behavior: contain;
}

.confirm-dialog::backdrop {
  overflow: hidden;
  overscroll-behavior: contain;
  background: #14241e66;
  backdrop-filter: blur(2px);
}

.confirm-dialog form {
  padding: 22px;
}

.dialog-topline {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.warning-icon {
  inline-size: 38px;
  block-size: 38px;
  border-radius: 7px;
  display: grid;
  place-items: center;
  color: var(--danger);
  background: var(--danger-soft);
}

.confirm-dialog h2 {
  margin: 16px 0 9px;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.025em;
}

.dialog-description {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}

.target-box {
  min-block-size: 40px;
  margin-block-start: 18px;
  padding-inline: 11px;
  border: 1px solid var(--line);
  border-radius: 5px;
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--surface-subtle);
  font-size: 12px;
}

.target-box > svg {
  color: var(--muted);
}

.target-box .badge {
  margin-inline-start: auto;
}

.sql-preview {
  max-block-size: 145px;
  margin: 12px 0;
  padding: 13px;
  border: 1px solid #2c4640;
  border-radius: 5px;
  overflow: auto;
  color: #d8e8e1;
  background: #1e312c;
  font-size: 11px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.commit-warning {
  padding: 11px 12px;
  border: 1px solid #e7d8bd;
  border-radius: 5px;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: #855b24;
  background: #fff8eb;
}

.commit-warning.implicit {
  border-color: #ecc9c4;
  color: #98453e;
  background: #fff2f0;
}

.commit-warning svg {
  margin-block-start: 2px;
  flex: 0 0 auto;
}

.commit-warning p {
  margin: 0;
  font-size: 11px;
  line-height: 1.65;
}

.dialog-actions {
  margin-block-start: 22px;
  display: flex;
  justify-content: flex-end;
  gap: 9px;
}
</style>
