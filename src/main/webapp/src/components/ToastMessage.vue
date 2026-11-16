<script setup lang="ts">
import { Check } from 'lucide-vue-next'

defineProps<{ message: string }>()
</script>

<template>
  <div v-if="message" class="toast-message" role="status"><Check :size="16" />{{ message }}</div>
</template>

<style scoped>
/* 全局提示的统一样式：原 WorkspacePage 版式收口至此，供各工作面复用 */
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
</style>
