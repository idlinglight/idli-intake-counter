import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import HomeView from '../HomeView.vue'

type ApiResult = { data?: unknown; error?: unknown }
type ApiCall = (path: string, init?: unknown) => Promise<ApiResult>

const { getMock, postMock, deleteMock } = vi.hoisted(() => ({
  getMock: vi.fn<ApiCall>(),
  postMock: vi.fn<ApiCall>(),
  deleteMock: vi.fn<ApiCall>(),
}))

vi.mock('@/api/client', () => ({
  api: { GET: getMock, POST: postMock, DELETE: deleteMock },
}))

const metrics = [
  { id: 7, name: 'water', canonicalUnit: 'mL' },
  { id: 2, name: 'energy', canonicalUnit: 'kJ' },
]

let dayView: {
  date: string
  entries: { id: number; metricId: number; amount: number; loggedAt: string }[]
  totals: { metricId: number; metricName: string; canonicalUnit: string; total: number }[]
}

beforeEach(() => {
  vi.clearAllMocks()
  dayView = {
    date: '2026-08-01',
    entries: [
      { id: 11, metricId: 7, amount: 500, loggedAt: '2026-08-01T08:30:00' },
      { id: 12, metricId: 7, amount: 750, loggedAt: '2026-08-01T12:05:00' },
    ],
    totals: [{ metricId: 7, metricName: 'water', canonicalUnit: 'mL', total: 1250 }],
  }
  getMock.mockImplementation(async (path: string) => {
    if (path === '/api/metrics') return { data: metrics }
    return { data: dayView }
  })
  postMock.mockResolvedValue({ data: { id: 99 } })
  deleteMock.mockResolvedValue({ data: undefined })
})

describe('HomeView', () => {
  it("loads the water metric and the server-resolved today on mount", async () => {
    const wrapper = mount(HomeView)
    await flushPromises()

    expect(getMock).toHaveBeenCalledWith('/api/metrics')
    // The backend is the single clock — no client-side date computation.
    expect(getMock).toHaveBeenCalledWith('/api/days/today')
    expect(wrapper.get('[data-testid="water-total"]').text()).toBe('1.25 L')
    expect(wrapper.text()).toContain('08:30')
    expect(wrapper.text()).toContain('500 mL')
    expect(wrapper.text()).toContain('12:05')
    expect(wrapper.text()).toContain('750 mL')
  })

  it('posts the amount with the water metric id on quick-log and refreshes', async () => {
    const wrapper = mount(HomeView)
    await flushPromises()

    dayView = {
      ...dayView,
      entries: [
        ...dayView.entries,
        { id: 13, metricId: 7, amount: 250, loggedAt: '2026-08-01T14:00:00' },
      ],
      totals: [{ metricId: 7, metricName: 'water', canonicalUnit: 'mL', total: 1500 }],
    }

    const button = wrapper.findAll('button').find((b) => b.text() === '+250 mL')
    expect(button).toBeDefined()
    await button!.trigger('click')
    await flushPromises()

    expect(postMock).toHaveBeenCalledTimes(1)
    expect(postMock).toHaveBeenCalledWith('/api/entries', {
      body: { metricId: 7, amount: 250 },
    })
    expect(getMock).toHaveBeenCalledTimes(3) // metrics + day on mount, day again after post
    expect(wrapper.get('[data-testid="water-total"]').text()).toBe('1.5 L')
    expect(wrapper.text()).toContain('14:00')
  })

  it('deletes an entry via its id and refreshes', async () => {
    const wrapper = mount(HomeView)
    await flushPromises()

    dayView = {
      ...dayView,
      entries: [dayView.entries[1]!],
      totals: [{ metricId: 7, metricName: 'water', canonicalUnit: 'mL', total: 750 }],
    }

    await wrapper.findAll('.entry-delete')[0]!.trigger('click')
    await flushPromises()

    expect(deleteMock).toHaveBeenCalledTimes(1)
    expect(deleteMock).toHaveBeenCalledWith('/api/entries/{id}', {
      params: { path: { id: 11 } },
    })
    expect(getMock).toHaveBeenCalledTimes(3) // metrics + day on mount, day again after delete
    expect(wrapper.get('[data-testid="water-total"]').text()).toBe('750 mL')
    expect(wrapper.text()).not.toContain('08:30')
  })

  it('shows an inline message when the backend is unreachable', async () => {
    getMock.mockRejectedValue(new Error('network down'))

    const wrapper = mount(HomeView)
    await flushPromises()

    expect(wrapper.get('.error').text()).toBe('backend unreachable')
  })

  it('surfaces a failed log instead of silently doing nothing', async () => {
    const wrapper = mount(HomeView)
    await flushPromises()
    postMock.mockResolvedValue({ error: { status: 400 } })

    const button = wrapper.findAll('button').find((b) => b.text() === '+250 mL')
    await button!.trigger('click')
    await flushPromises()

    expect(wrapper.get('.error').text()).toBe('logging failed')
    // No refresh after a failed post; the shown day stays intact.
    expect(getMock).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-testid="water-total"]').text()).toBe('1.25 L')
  })

  it('keeps the previous day view and shows an error when the refresh fails', async () => {
    const wrapper = mount(HomeView)
    await flushPromises()
    getMock.mockImplementation(async (path: string) =>
      path === '/api/metrics' ? { data: metrics } : { error: { status: 500 } },
    )

    const button = wrapper.findAll('button').find((b) => b.text() === '+250 mL')
    await button!.trigger('click')
    await flushPromises()

    expect(wrapper.get('.error').text()).toBe('could not load today')
    // Stale data beats fake-empty data: the earlier day view is still shown.
    expect(wrapper.get('[data-testid="water-total"]').text()).toBe('1.25 L')
    expect(wrapper.text()).toContain('08:30')
  })

  it('surfaces a failed delete and still resyncs the day', async () => {
    const wrapper = mount(HomeView)
    await flushPromises()
    deleteMock.mockResolvedValue({ error: { status: 404 } })

    await wrapper.findAll('.entry-delete')[0]!.trigger('click')
    await flushPromises()

    expect(wrapper.get('.error').text()).toBe('delete failed')
    expect(getMock).toHaveBeenCalledTimes(3) // resync still happens
  })
})
