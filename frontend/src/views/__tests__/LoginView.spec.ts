import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import LoginView from '../LoginView.vue'

type Login = (password: string) => Promise<'ok' | 'wrong-password' | 'unreachable'>

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

  it('distinguishes an unreachable backend from a wrong password', async () => {
    loginMock.mockResolvedValue('unreachable')
    const wrapper = mount(LoginView)

    await submitPassword(wrapper, 'hunter2')

    expect(wrapper.get('.error').text()).toBe('backend unreachable')
  })
})
