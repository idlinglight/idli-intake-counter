import { describe, it, expect, vi, afterEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import BuildInfo from '../BuildInfo.vue'

vi.mock('@/api/client', () => ({
  api: {
    GET: vi.fn<() => Promise<{ data: { message: string; gitSha: string } }>>(async () => ({
      data: { message: 'idli', gitSha: 'test123' },
    })),
  },
}))

afterEach(() => {
  vi.unstubAllEnvs()
})

describe('BuildInfo', () => {
  it('shows message and gitSha from the backend', async () => {
    const wrapper = mount(BuildInfo)
    await flushPromises()

    expect(wrapper.text()).toContain('idli @ test123')
  })

  it('shows the baked-in frontend sha shortened to 7 chars', async () => {
    vi.stubEnv('VITE_GIT_SHA', 'abcdef0123456789abcdef0123456789abcdef01')

    const wrapper = mount(BuildInfo)
    await flushPromises()

    expect(wrapper.text()).toContain('frontend: abcdef0')
  })

  it('falls back to "dev" when no sha was baked in', async () => {
    const wrapper = mount(BuildInfo)
    await flushPromises()

    expect(wrapper.text()).toContain('frontend: dev')
  })
})
