import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import LoginPage from './LoginPage.vue'
import { API_CLIENT_KEY, type ApiClient } from '../api/client'
import { ApiClientError } from '../api/http-client'
import { resetSessionState } from '../composables/useSession'

function mountPage(auth: Partial<ApiClient['auth']>) {
  const client = { auth } as ApiClient
  return mount(LoginPage, {
    global: { provide: { [API_CLIENT_KEY]: client } },
  })
}

describe('LoginPage', () => {
  beforeEach(() => {
    resetSessionState()
  })

  it('submits credentials through the ApiClient seam', async () => {
    const login = vi.fn().mockResolvedValue({ username: 'admin' })
    const wrapper = mountPage({ login })

    await wrapper.find('#username').setValue('admin')
    await wrapper.find('#password').setValue('secret')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(login).toHaveBeenCalledWith('admin', 'secret')
  })

  it('shows backend error message when credentials are rejected', async () => {
    const wrapper = mountPage({
      login: () => Promise.reject(new ApiClientError('BAD_CREDENTIALS', '用户名或密码不正确', 401)),
    })

    await wrapper.find('#username').setValue('admin')
    await wrapper.find('#password').setValue('wrong')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('#login-error').text()).toBe('用户名或密码不正确')
  })

  it('validates blank fields locally before calling the backend', async () => {
    const login = vi.fn()
    const wrapper = mountPage({ login })

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(login).not.toHaveBeenCalled()
    expect(wrapper.find('#login-error').text()).toContain('请输入用户名和密码')
  })
})
