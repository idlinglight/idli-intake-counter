import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import LoginView from '../LoginView.vue'

import type { LoginResult } from '@/stores/auth'

type Login = (password: string) => Promise<LoginResult>

const { loginMock } = vi.hoisted(() => ({
  loginMock: vi.fn<Login>(),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ login: loginMock }),
}))

async function submitPassword(wrapper: ReturnType<typeof mount>, password: string) {
  await wrapper.get('input[type="password"]').setValue(password)
  await wrapper.get('form').trigger('submit.prevent')
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('LoginView', () => {
  it('submits the entered password to the auth store', async () => {
    loginMock.mockResolvedValue('ok')
    const wrapper = mount(LoginView)

    // Password-manager friendly single field; the fixed username stays hidden.
    const input = wrapper.get('input[type="password"]')
    expect(input.attributes('autocomplete')).toBe('current-password')

    await submitPassword(wrapper, 'hunter2')

    expect(loginMock).toHaveBeenCalledTimes(1)
    expect(loginMock).toHaveBeenCalledWith('hunter2')
    expect(wrapper.find('.error').exists()).toBe(false)
  })

  it('shows an inline error on a wrong password and clears the field', async () => {
    loginMock.mockResolvedValue('wrong-password')
    const wrapper = mount(LoginView)

    await submitPassword(wrapper, 'nope')

    expect(wrapper.get('.error').text()).toBe('wrong password')
    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('')
  })

  it('asks for a retry when the backend is busy and keeps the password', async () => {
    loginMock.mockResolvedValue('busy')
    const wrapper = mount(LoginView)

    await submitPassword(wrapper, 'hunter2')

    expect(wrapper.get('.error').text()).toContain('try again')
    // Nothing was wrong with it — the retry should be one tap.
    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe(
      'hunter2',
    )
  })

  it('says what to do when password login is closed and keeps the password', async () => {
    loginMock.mockResolvedValue('closed')
    const wrapper = mount(LoginView)

    await submitPassword(wrapper, 'hunter2')

    // The reader is the operator: name the release, not just the state.
    expect(wrapper.get('.error').text()).toContain('closed')
    expect(wrapper.get('.error').text()).toContain('restart the backend')
    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe(
      'hunter2',
    )
  })

  it('distinguishes an unreachable backend from a wrong password', async () => {
    loginMock.mockResolvedValue('unreachable')
    const wrapper = mount(LoginView)

    await submitPassword(wrapper, 'hunter2')

    expect(wrapper.get('.error').text()).toBe('backend unreachable')
  })

  it('explains a dropped session cookie instead of re-rendering silently', async () => {
    // Right password, but the browser refused the Secure cookie. Without a
    // message the form just comes back blank-faced, every time, forever.
    loginMock.mockResolvedValue('no-session')
    const wrapper = mount(LoginView)

    await submitPassword(wrapper, 'hunter2')

    expect(wrapper.get('.error').text()).toContain('https')
  })
})
