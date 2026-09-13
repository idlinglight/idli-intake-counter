import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import HomeView from '../HomeView.vue'
import { STALE_AFTER_MS } from '@/composables/useRefreshOnReactivate'
import { STORAGE_KEY as RECENT_KEY } from '@/composables/useRecentItems'

// Views now register window/document listeners (reactivation refresh) —
// leaked mounts would make later tests' dispatched events fan out to every
// previously mounted instance.
enableAutoUnmount(afterEach)

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
  { id: 3, name: 'protein', canonicalUnit: 'g' },
]

const proteinBar = {
  id: 4,
  name: 'protein bar',
  basisAmount: 100,
  basisUnit: 'g',
  amounts: [
    { metricId: 2, amount: 2281 },
    { metricId: 3, amount: 30 },
  ],
  servings: [
    { id: 5, name: 'half bar', quantity: 25 },
    { id: 6, name: 'whole bar', quantity: 50 },
  ],
}

let itemsData: (typeof proteinBar)[]

let dayView: {
  date: string
  entries: {
    id: number
    metricId: number
    amount: number
    loggedAt: string
    groupId?: string
    label?: string
  }[]
  totals: { metricId: number; metricName: string; canonicalUnit: string; total: number }[]
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  itemsData = [proteinBar]
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
    if (path === '/api/items') return { data: itemsData }
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
    expect(getMock).toHaveBeenCalledWith('/api/items')
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
    expect(getMock).toHaveBeenCalledTimes(4) // metrics + items + day on mount, day again after post
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
    expect(getMock).toHaveBeenCalledTimes(4) // metrics + items + day on mount, day again after delete
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
    expect(getMock).toHaveBeenCalledTimes(3)
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
    expect(getMock).toHaveBeenCalledTimes(4) // resync still happens
  })

  it('hides the item picker only when there are no items at all', async () => {
    // Serving-less items stay loggable via the ad-hoc input, so they no
    // longer hide the section — an empty catalog still does.
    itemsData = []

    const wrapper = mount(HomeView)
    await flushPromises()

    expect(wrapper.find('[data-testid="log-item-section"]').exists()).toBe(false)
  })

  it('logs a serving with one tap after picking the item', async () => {
    const wrapper = mount(HomeView)
    await flushPromises()

    await wrapper.get('[data-testid="item-select"]').setValue(4)
    const servingButtons = wrapper.findAll('[data-testid="serving-button"]')
    expect(servingButtons.map((b) => b.text())).toEqual(['half bar (25 g)', 'whole bar (50 g)'])

    await servingButtons[0]!.trigger('click')
    await flushPromises()

    // Multiplier 1 sends an empty body — the default path stays two taps.
    expect(postMock).toHaveBeenCalledWith('/api/servings/{id}/entries', {
      params: { path: { id: 5 } },
      body: {},
    })
    expect(getMock).toHaveBeenCalledTimes(4) // day refreshed after the log
  })

  it('sends the multiplier and resets it to 1 after a successful log', async () => {
    const wrapper = mount(HomeView)
    await flushPromises()

    await wrapper.get('[data-testid="item-select"]').setValue(4)
    const multiplierInput = wrapper.get('[data-testid="multiplier-input"]')
    await multiplierInput.setValue(0.5)
    await wrapper.findAll('[data-testid="serving-button"]')[1]!.trigger('click')
    await flushPromises()

    expect(postMock).toHaveBeenCalledWith('/api/servings/{id}/entries', {
      params: { path: { id: 6 } },
      body: { multiplier: 0.5 },
    })
    expect((multiplierInput.element as HTMLInputElement).value).toBe('1')
  })

  it('surfaces the backend reason when a serving log is rejected', async () => {
    const wrapper = mount(HomeView)
    await flushPromises()
    postMock.mockResolvedValue({
      error: { message: "logging 'bar' would round metric 'trace' to 0; refusing a silent no-op" },
    })

    await wrapper.get('[data-testid="item-select"]').setValue(4)
    await wrapper.findAll('[data-testid="serving-button"]')[0]!.trigger('click')
    await flushPromises()

    expect(wrapper.get('.error').text()).toContain("logging failed: logging 'bar' would round")
  })

  it('renders grouped entries as one row and deletes them as one group', async () => {
    dayView = {
      ...dayView,
      entries: [
        // The real day endpoint orders loggedAt DESC, id DESC — the group's
        // members arrive id-reversed relative to creation order.
        {
          id: 22,
          metricId: 3,
          amount: 15,
          loggedAt: '2026-08-01T09:15:00',
          groupId: 'abc-123',
          label: 'protein bar – half bar ×2',
        },
        {
          id: 21,
          metricId: 2,
          amount: 1141,
          loggedAt: '2026-08-01T09:15:00',
          groupId: 'abc-123',
          label: 'protein bar – half bar ×2',
        },
        ...dayView.entries,
      ],
      totals: [
        { metricId: 7, metricName: 'water', canonicalUnit: 'mL', total: 1250 },
        { metricId: 2, metricName: 'energy', canonicalUnit: 'kJ', total: 1141 },
        { metricId: 3, metricName: 'protein', canonicalUnit: 'g', total: 15 },
      ],
    }
    const wrapper = mount(HomeView)
    await flushPromises()

    // Two grouped entries collapse into ONE row; the ad-hoc rows stay as-is.
    const groupRows = wrapper.findAll('[data-testid="group-row"]')
    expect(groupRows).toHaveLength(1)
    expect(groupRows[0]!.text()).toContain('protein bar – half bar ×2')
    expect(groupRows[0]!.text()).toContain('energy 1141 kJ, protein 15 g')
    expect(wrapper.findAll('.entry-delete')).toHaveLength(2)
    expect(wrapper.get('[data-testid="day-totals"]').text()).toBe(
      'water 1.25 L · energy 1141 kJ · protein 15 g',
    )

    await wrapper.get('[data-testid="group-delete"]').trigger('click')
    await flushPromises()

    expect(deleteMock).toHaveBeenCalledWith('/api/entry-groups/{groupId}', {
      params: { path: { groupId: 'abc-123' } },
    })
  })

  it('logs an ad-hoc quantity with the exact body and refreshes the day', async () => {
    const wrapper = mount(HomeView)
    await flushPromises()
    await wrapper.get('[data-testid="item-select"]').setValue(4)

    await wrapper.get('[data-testid="adhoc-quantity"]').setValue(137)
    await wrapper.get('[data-testid="adhoc-log"]').trigger('click')
    await flushPromises()

    expect(postMock).toHaveBeenCalledWith('/api/items/{id}/entries', {
      params: { path: { id: 4 } },
      body: { quantity: 137 },
    })
    // Day refreshed, input snapped back empty for a deliberate re-entry.
    expect(getMock.mock.calls.filter(([path]) => path === '/api/days/today').length).toBe(2)
    expect((wrapper.get('[data-testid="adhoc-quantity"]').element as HTMLInputElement).value).toBe('')
  })

  it('computes the container delta in weigh mode and logs it', async () => {
    const wrapper = mount(HomeView)
    await flushPromises()
    await wrapper.get('[data-testid="item-select"]').setValue(4)

    await wrapper.get('[data-testid="weigh-toggle"]').trigger('click')
    await wrapper.get('[data-testid="weigh-before"]').setValue(412)
    await wrapper.get('[data-testid="weigh-after"]').setValue(275)

    expect(wrapper.get('[data-testid="weigh-delta"]').text()).toBe('= 137 g')
    await wrapper.get('[data-testid="adhoc-log"]').trigger('click')
    await flushPromises()

    expect(postMock).toHaveBeenCalledWith('/api/items/{id}/entries', {
      params: { path: { id: 4 } },
      body: { quantity: 137 },
    })
  })

  it('refuses non-positive deltas and fractional quantities via the disabled button', async () => {
    const wrapper = mount(HomeView)
    await flushPromises()
    await wrapper.get('[data-testid="item-select"]').setValue(4)

    // Fraction: the backend wants whole basis units.
    await wrapper.get('[data-testid="adhoc-quantity"]').setValue(136.5)
    expect(wrapper.get('[data-testid="adhoc-log"]').attributes('disabled')).toBeDefined()

    // after > before — an "ate negative pasta" delta stays unloggable.
    await wrapper.get('[data-testid="weigh-toggle"]').trigger('click')
    await wrapper.get('[data-testid="weigh-before"]').setValue(275)
    await wrapper.get('[data-testid="weigh-after"]').setValue(412)
    expect(wrapper.get('[data-testid="weigh-delta"]').text()).toBe('= -137 g')
    expect(wrapper.get('[data-testid="adhoc-log"]').attributes('disabled')).toBeDefined()
    expect(postMock).not.toHaveBeenCalled()
  })

  it('offers serving-less items in the picker with the ad-hoc input only', async () => {
    itemsData = [
      { ...proteinBar, id: 8, name: 'bare pasta', servings: [] },
    ]
    const wrapper = mount(HomeView)
    await flushPromises()

    await wrapper.get('[data-testid="item-select"]').setValue(8)
    expect(wrapper.findAll('[data-testid="serving-button"]')).toHaveLength(0)
    expect(wrapper.find('[data-testid="multiplier-input"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="adhoc-quantity"]').exists()).toBe(true)
  })


  // ── Picker "Recent" section (issue #38) ──

  it('shows no Recent section before anything was logged on this device', async () => {
    const wrapper = mount(HomeView)
    await flushPromises()

    expect(wrapper.find('[data-testid="recent-group"]').exists()).toBe(false)
    expect(wrapper.findAll('optgroup')).toHaveLength(0)
    expect(wrapper.findAll('[data-testid="item-select"] option').map((o) => o.text())).toEqual([
      'log an item…',
      'protein bar',
    ])
  })

  it('lists a just-logged serving item under Recent, above the stable full list', async () => {
    const pasta = { ...proteinBar, id: 8, name: 'pasta', servings: [] }
    itemsData = [proteinBar, pasta]
    const wrapper = mount(HomeView)
    await flushPromises()

    await wrapper.get('[data-testid="item-select"]').setValue(8)
    await wrapper.get('[data-testid="adhoc-quantity"]').setValue(137)
    await wrapper.get('[data-testid="adhoc-log"]').trigger('click')
    await flushPromises()

    const recent = wrapper.get('[data-testid="recent-group"]')
    expect(recent.attributes('label')).toBe('Recent')
    expect(recent.findAll('option').map((o) => o.text())).toEqual(['pasta'])
    // The full list keeps its order — only the section on top changes.
    const groups = wrapper.findAll('optgroup')
    expect(groups[1]!.attributes('label')).toBe('All items')
    expect(groups[1]!.findAll('option').map((o) => o.text())).toEqual(['protein bar', 'pasta'])
    expect(JSON.parse(localStorage.getItem(RECENT_KEY)!)).toEqual(['pasta'])
  })

  it('moves a serving-logged item to the front of Recent', async () => {
    localStorage.setItem(RECENT_KEY, JSON.stringify(['pasta']))
    itemsData = [proteinBar, { ...proteinBar, id: 8, name: 'pasta', servings: [] }]
    const wrapper = mount(HomeView)
    await flushPromises()

    await wrapper.get('[data-testid="item-select"]').setValue(4)
    await wrapper.findAll('[data-testid="serving-button"]')[0]!.trigger('click')
    await flushPromises()

    expect(
      wrapper.get('[data-testid="recent-group"]').findAll('option').map((o) => o.text()),
    ).toEqual(['protein bar', 'pasta'])
  })

  it('does not stamp recency when the log fails', async () => {
    postMock.mockResolvedValue({ error: { message: 'would round to 0' } })
    const wrapper = mount(HomeView)
    await flushPromises()

    await wrapper.get('[data-testid="item-select"]').setValue(4)
    await wrapper.findAll('[data-testid="serving-button"]')[0]!.trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="recent-group"]').exists()).toBe(false)
    expect(localStorage.getItem(RECENT_KEY)).toBeNull()
  })

  it('drops remembered names that no longer match an item', async () => {
    // Deleted, renamed, or from a different dataset: unknown names vanish,
    // known ones keep their order.
    localStorage.setItem(RECENT_KEY, JSON.stringify(['gone', 'protein bar']))
    const wrapper = mount(HomeView)
    await flushPromises()

    expect(
      wrapper.get('[data-testid="recent-group"]').findAll('option').map((o) => o.text()),
    ).toEqual(['protein bar'])
  })

  it('selecting from Recent drives the same serving surface', async () => {
    localStorage.setItem(RECENT_KEY, JSON.stringify(['protein bar']))
    const wrapper = mount(HomeView)
    await flushPromises()

    await wrapper.get('[data-testid="item-select"]').setValue(4)
    expect(wrapper.findAll('[data-testid="serving-button"]')).toHaveLength(2)
  })

  it('reloads and shows the indicator when re-activated after going stale', async () => {
    const wrapper = mount(HomeView)
    await flushPromises()
    const dayCalls = () => getMock.mock.calls.filter(([path]) => path === '/api/days/today').length
    expect(dayCalls()).toBe(1)

    // Fake only Date so flushPromises (setTimeout-based) keeps working.
    vi.useFakeTimers({ toFake: ['Date'] })
    try {
      vi.setSystemTime(Date.now() + STALE_AFTER_MS + 1000)
      document.dispatchEvent(new Event('visibilitychange'))
      await flushPromises()

      expect(dayCalls()).toBe(2)
      // Still inside MIN_VISIBLE_MS — the indicator explains the movement.
      expect(wrapper.find('[data-testid="refresh-indicator"]').exists()).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })
})
