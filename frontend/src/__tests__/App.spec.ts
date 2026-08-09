import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import App from '../App.vue'

const { authMock } = vi.hoisted(() => ({
  authMock: {
    checked: true,
    authenticated: true,
    check: vi.fn<() => Promise<void>>(),
    logout: vi.fn<() => Promise<boolean>>(),
  },
}))

vi.mock('@/stores/auth', () => ({ useAuthStore: () => authMock }))

function mountApp() {
  return mount(App, {
    global: { stubs: { RouterLink: true, RouterView: true, BuildInfo: true } },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  authMock.checked = true
  authMock.authenticated = true
  authMock.check.mockResolvedValue()
})

describe('App logout', () => {
  it('says so when the logout did not take', async () => {
    // logout() returns false while the server-side session may still be live.
    // Discarding that leaves the button looking inert and the user thinking
    // they are logged out.
    authMock.logout.mockResolvedValue(false)
    const wrapper = mountApp()

    await wrapper.get('button.logout').trigger('click')
    await flushPromises()

    expect(wrapper.get('.logout-error').attributes('role')).toBe('alert')
    expect(wrapper.get('.logout-error').text()).toContain('still logged in')
  })

  it('stays quiet when the logout succeeded', async () => {
    authMock.logout.mockResolvedValue(true)
    const wrapper = mountApp()

    await wrapper.get('button.logout').trigger('click')
    await flushPromises()

    expect(wrapper.find('.logout-error').exists()).toBe(false)
  })
})
