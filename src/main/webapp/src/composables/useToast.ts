import { ref } from 'vue'

const message = ref('')
let hideTimer: ReturnType<typeof setTimeout> | null = null

/** 全局提示：域无关的 UI 反馈。 */
export function useToast() {
  function notify(text: string): void {
    message.value = text
    if (hideTimer !== null) clearTimeout(hideTimer)
    hideTimer = setTimeout(() => {
      message.value = ''
    }, 2800)
  }

  function reset(): void {
    message.value = ''
    if (hideTimer !== null) {
      clearTimeout(hideTimer)
      hideTimer = null
    }
  }

  return { message, notify, reset }
}
