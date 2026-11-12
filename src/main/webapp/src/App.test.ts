import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import { resetSessionState } from './composables/useSession'

vi.mock('./components/WorkspacePage.vue', () => ({
  default: { name: 'WorkspacePageStub', render: () => null },
}))

describe('App root wiring', () => {
  it('provides an ApiClient and renders boot, login, then workspace', async () => {
    resetSessionState()
    const sessionProbe = vi.fn().mockRejectedValue({ code: 'UNAUTHENTICATED' })
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/auth/session') return sessionProbe()
      return Promise.reject(new Error('unexpected fetch: ' + url))
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(App)
    expect(wrapper.text()).toContain('正在检查登录状态')

    await flushPromises()
    expect(wrapper.text()).toContain('登录工作台')

    wrapper.unmount()
    vi.unstubAllGlobals()
  })
})
