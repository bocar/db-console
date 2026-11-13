import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import { API_CLIENT_KEY, type ApiClient } from '../api/client'
import { fakeApiClient } from '../api/test-support'
import { ApiClientError } from '../api/http-client'
import { resetSessionState, useSession } from './useSession'

function mountSession(client: ApiClient) {
  let session: ReturnType<typeof useSession> | undefined
  const host = defineComponent({
    setup() {
      session = useSession()
      return () => null
    },
  })
  mount(host, { global: { provide: { [API_CLIENT_KEY]: client } } })
  if (!session) throw new Error('session composable 未初始化')
  return session
}

function fakeClient(overrides: Partial<ApiClient['auth']> = {}): ApiClient {
  return fakeApiClient({ auth: { ...fakeApiClient().auth, ...overrides } })
}

describe('useSession', () => {
  beforeEach(() => {
    resetSessionState()
  })

  it('refresh marks authenticated when session probe succeeds', async () => {
    const session = mountSession(fakeClient())

    await session.refresh()

    expect(session.status.value).toBe('authenticated')
    expect(session.username.value).toBe('admin')
  })

  it('refresh marks anonymous when session probe rejects', async () => {
    const session = mountSession(
      fakeClient({ session: () => Promise.reject(new ApiClientError('UNAUTHENTICATED', '未登录', 401)) }),
    )

    await session.refresh()

    expect(session.status.value).toBe('anonymous')
    expect(session.username.value).toBeNull()
  })

  it('login success authenticates and returns true', async () => {
    const session = mountSession(fakeClient())

    const succeeded = await session.login('admin', 'secret')

    expect(succeeded).toBe(true)
    expect(session.status.value).toBe('authenticated')
    expect(session.loginError.value).toBe('')
  })

  it('login failure surfaces backend message and keeps prior status', async () => {
    const session = mountSession(
      fakeClient({ login: () => Promise.reject(new ApiClientError('BAD_CREDENTIALS', '用户名或密码不正确', 401)) }),
    )

    const succeeded = await session.login('admin', 'wrong')

    expect(succeeded).toBe(false)
    expect(session.status.value).toBe('unknown')
    expect(session.loginError.value).toBe('用户名或密码不正确')
  })

  it('logout returns to anonymous even when backend call fails', async () => {
    const session = mountSession(
      fakeClient({ logout: () => Promise.reject(new ApiClientError('NETWORK_ERROR', '网络错误', 0)) }),
    )
    await session.login('admin', 'secret')

    await session.logout()

    expect(session.status.value).toBe('anonymous')
  })

  it('expire immediately drops to anonymous', () => {
    const session = mountSession(fakeClient())

    session.expire()

    expect(session.status.value).toBe('anonymous')
  })
})
