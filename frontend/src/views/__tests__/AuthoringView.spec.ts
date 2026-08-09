import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import AuthoringView from '../AuthoringView.vue'
import { STALE_AFTER_MS } from '@/composables/useRefreshOnReactivate'

// Views now register window/document listeners (reactivation refresh) —
// leaked mounts would make later tests' dispatched events fan out to every
// previously mounted instance.
enableAutoUnmount(afterEach)

type ApiResult = { data?: unknown; error?: unknown; response: Response }
type ApiCall = (path: string, init?: unknown) => Promise<ApiResult>

const { getMock, postMock, putMock, deleteMock } = vi.hoisted(() => ({
  getMock: vi.fn<ApiCall>(),
  postMock: vi.fn<ApiCall>(),
  putMock: vi.fn<ApiCall>(),
  deleteMock: vi.fn<ApiCall>(),
}))

vi.mock('@/api/client', () => ({
  api: { GET: getMock, POST: postMock, PUT: putMock, DELETE: deleteMock },
}))

const metrics = [
  { id: 1, name: 'energy', canonicalUnit: 'kJ' },
  { id: 2, name: 'protein', canonicalUnit: 'g' },
]

// The protein-bar user story: label data per 100 g, servings as bare quantities.
const items = [
  {
    id: 1,
    name: 'protein bar',
    basisAmount: 100,
    basisUnit: 'g',
    amounts: [
      { metricId: 1, amount: 2281 },
      { metricId: 2, amount: 30 },
    ],
    servings: [{ id: 11, name: 'half bar', quantity: 25 }],
  },
]

beforeEach(() => {
  vi.clearAllMocks()
  getMock.mockImplementation(async (path: string) => {
    if (path === '/api/metrics') return { data: metrics, response: new Response() }
    if (path === '/api/items') return { data: items, response: new Response() }
    return { error: {}, response: new Response(null, { status: 404 }) }
  })
  postMock.mockResolvedValue({ data: {}, response: new Response() })
  putMock.mockResolvedValue({ data: {}, response: new Response() })
  deleteMock.mockResolvedValue({ response: new Response(null, { status: 204 }) })
})

async function mountView() {
  const wrapper = mount(AuthoringView)
  await flushPromises()
  return wrapper
}

describe('AuthoringView', () => {
  it('renders metrics, the item tree, and computed per-serving values', async () => {
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('energy')
    expect(wrapper.text()).toContain('kJ')

    const item = wrapper.get('[data-testid="item"]')
    expect(item.text()).toContain('protein bar')
    expect(item.text()).toContain('per 100 g')
    // Composition as entered — label data verbatim.
    expect(item.text()).toContain('energy 2281 kJ, protein 30 g')

    // The serving shows what logging would snapshot: 25/100 × {2281, 30},
    // rounded (570.25 → 570 and 7.5 → 8).
    const serving = wrapper.get('[data-testid="serving"]')
    expect(serving.text()).toContain('half bar')
    expect(serving.text()).toContain('25 g')
    expect(serving.text()).toContain('energy 570 kJ, protein 8 g')
  })

  it('creates a metric and refetches', async () => {
    const wrapper = await mountView()

    await wrapper.get('[data-testid="metric-name"]').setValue('caffeine')
    await wrapper.get('[data-testid="metric-unit"]').setValue('mg')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(postMock).toHaveBeenCalledWith('/api/metrics', {
      body: { name: 'caffeine', canonicalUnit: 'mg' },
    })
    // Two initial loads plus two refetches after the mutation.
    expect(getMock).toHaveBeenCalledTimes(4)
  })

  it('surfaces the server reason when creating a metric conflicts', async () => {
    postMock.mockResolvedValue({
      error: { message: 'metric name already taken: energy' },
      response: new Response(null, { status: 409 }),
    })
    const wrapper = await mountView()

    await wrapper.get('[data-testid="metric-name"]').setValue('energy')
    await wrapper.get('[data-testid="metric-unit"]').setValue('kJ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('creating metric failed: metric name already taken: energy')
  })

  it('creates an item with the exact composed payload', async () => {
    const wrapper = await mountView()

    await wrapper.get('[data-testid="new-item"]').trigger('click')
    await wrapper.get('[data-testid="item-name"]').setValue('idli')
    await wrapper.get('[data-testid="basis-amount"]').setValue(1)
    await wrapper.get('[data-testid="basis-unit"]').setValue('piece')
    await wrapper.get('[data-testid="amount-metric"]').setValue(1)
    await wrapper.get('[data-testid="amount-value"]').setValue(250)
    await wrapper.get('[data-testid="item-form"]').trigger('submit')
    await flushPromises()

    expect(postMock).toHaveBeenCalledWith('/api/items', {
      body: {
        name: 'idli',
        basisAmount: 1,
        basisUnit: 'piece',
        amounts: [{ metricId: 1, amount: 250 }],
      },
    })
    expect(wrapper.find('[data-testid="item-form"]').exists()).toBe(false)
  })

  it('edits an item through a prefilled form and PUTs the replacement', async () => {
    const wrapper = await mountView()

    await wrapper.get('[data-testid="edit-item"]').trigger('click')
    const nameInput = wrapper.get('[data-testid="item-name"]')
    expect((nameInput.element as HTMLInputElement).value).toBe('protein bar')

    await nameInput.setValue('protein bar white')
    await wrapper.get('[data-testid="item-form"]').trigger('submit')
    await flushPromises()

    expect(putMock).toHaveBeenCalledWith('/api/items/{id}', {
      params: { path: { id: 1 } },
      body: {
        name: 'protein bar white',
        basisAmount: 100,
        basisUnit: 'g',
        amounts: [
          { metricId: 1, amount: 2281 },
          { metricId: 2, amount: 30 },
        ],
      },
    })
  })

  it('deletes an item only after the second, explicit click', async () => {
    const wrapper = await mountView()

    const deleteButton = wrapper.get('[data-testid="delete-item"]')
    await deleteButton.trigger('click')
    expect(deleteMock).not.toHaveBeenCalled()
    expect(deleteButton.text()).toContain('really delete?')

    await deleteButton.trigger('click')
    await flushPromises()
    expect(deleteMock).toHaveBeenCalledWith('/api/items/{id}', { params: { path: { id: 1 } } })
  })

  it('adds a serving under its item', async () => {
    const wrapper = await mountView()

    await wrapper.get('[data-testid="add-serving"]').trigger('click')
    await wrapper.get('[data-testid="serving-name"]').setValue('whole bar')
    await wrapper.get('[data-testid="serving-quantity"]').setValue(50)
    await wrapper.get('[data-testid="serving-form"]').trigger('submit')
    await flushPromises()

    expect(postMock).toHaveBeenCalledWith('/api/items/{id}/servings', {
      params: { path: { id: 1 } },
      body: { name: 'whole bar', quantity: 50 },
    })
  })

  it('edits a serving through the prefilled inline form', async () => {
    const wrapper = await mountView()

    await wrapper.get('[data-testid="edit-serving"]').trigger('click')
    const nameInput = wrapper.get('[data-testid="serving-name"]')
    expect((nameInput.element as HTMLInputElement).value).toBe('half bar')

    await wrapper.get('[data-testid="serving-quantity"]').setValue(30)
    await wrapper.get('[data-testid="serving-form"]').trigger('submit')
    await flushPromises()

    expect(putMock).toHaveBeenCalledWith('/api/servings/{id}', {
      params: { path: { id: 11 } },
      body: { name: 'half bar', quantity: 30 },
    })
  })

  it('deletes a serving directly (no cascade, no confirm step)', async () => {
    const wrapper = await mountView()

    await wrapper.get('[data-testid="delete-serving"]').trigger('click')
    await flushPromises()

    expect(deleteMock).toHaveBeenCalledWith('/api/servings/{id}', { params: { path: { id: 11 } } })
  })

  it('lists items newest first regardless of backend order', async () => {
    // Backend serves id-ascending (its stable order); the view flips it so a
    // just-created item appears at the top, next to the creation form.
    getMock.mockImplementation(async (path: string) => {
      if (path === '/api/metrics') return { data: metrics, response: new Response() }
      if (path === '/api/items')
        return {
          data: [
            { ...items[0], id: 1, name: 'older item' },
            { ...items[0], id: 2, name: 'newer item' },
          ],
          response: new Response(),
        }
      return { error: {}, response: new Response(null, { status: 404 }) }
    })

    const wrapper = await mountView()

    const names = wrapper.findAll('[data-testid="item"]').map((node) => node.text())
    expect(names[0]).toContain('newer item')
    expect(names[1]).toContain('older item')
  })

  it('refetches and shows the indicator when re-activated after going stale', async () => {
    const wrapper = await mountView()
    const itemCalls = () => getMock.mock.calls.filter(([path]) => path === '/api/items').length
    expect(itemCalls()).toBe(1)

    // Fake only Date so flushPromises (setTimeout-based) keeps working.
    vi.useFakeTimers({ toFake: ['Date'] })
    try {
      vi.setSystemTime(Date.now() + STALE_AFTER_MS + 1000)
      document.dispatchEvent(new Event('visibilitychange'))
      await flushPromises()

      expect(itemCalls()).toBe(2)
      expect(wrapper.find('[data-testid="refresh-indicator"]').exists()).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })
})
