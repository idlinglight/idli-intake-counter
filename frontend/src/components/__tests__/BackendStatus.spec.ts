import { describe, it, expect, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import BackendStatus from '../BackendStatus.vue'

vi.mock('@/api/client', () => ({
  api: {
    GET: vi.fn<() => Promise<{ data: { message: string; gitSha: string } }>>(async () => ({
      data: { message: 'idli', gitSha: 'test123' },
    })),
  },
}))

describe('BackendStatus', () => {
  it('shows message and gitSha from the backend', async () => {
    const wrapper = mount(BackendStatus)
    await flushPromises()

    expect(wrapper.text()).toContain('idli @ test123')
  })
})
