import { ref } from 'vue'
import { ApiClientError } from '../api/http-client'
import { useApiClient, type ApiClient } from '../api/client'

export type SessionStatus = 'unknown' | 'anonymous' | 'authenticated'

const username = ref<string | null>(null)
const status = ref<SessionStatus>('unknown')
const loginError = ref('')
const loggingIn = ref(false)

/** 测试辅助：复位模块级会话状态。 */
export function resetSessionState(): void {
  username.value = null
  status.value = 'unknown'
  loginError.value = ''
  loggingIn.value = false
}

/**
 * 登录会话（F-01）：模块级单例。
 * 根组件 provide 与 inject 同处一个 setup 时注入不可见（Vue 注入只向下生效），
 * 因此根组件可显式传入 client；其余组件从注入获取。
 */
export function useSession(clientOverride?: ApiClient) {
  const api = clientOverride ?? useApiClient()

  async function refresh(): Promise<void> {
    try {
      const info = await api.auth.session()
      username.value = info.username
      status.value = 'authenticated'
    } catch {
      username.value = null
      status.value = 'anonymous'
    }
  }

  async function login(account: string, password: string): Promise<boolean> {
    loginError.value = ''
    loggingIn.value = true
    try {
      const info = await api.auth.login(account, password)
      username.value = info.username
      status.value = 'authenticated'
      return true
    } catch (error) {
      loginError.value =
        error instanceof ApiClientError ? error.message : '登录失败，请稍后重试。'
      return false
    } finally {
      loggingIn.value = false
    }
  }

  async function logout(): Promise<void> {
    try {
      await api.auth.logout()
    } catch {
      // 后端登出失败也在本地登出
    } finally {
      username.value = null
      status.value = 'anonymous'
      loginError.value = ''
    }
  }

  /** 会话失效（401 回调）：立即回到未登录态。 */
  function expire(): void {
    username.value = null
    status.value = 'anonymous'
    loginError.value = ''
  }

  return { username, status, loginError, loggingIn, refresh, login, logout, expire }
}
