import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import DataView from '../DataView.vue'

type ApiResult = { data?: unknown; error?: unknown; response: Response }
type ApiCall = (path: string, init?: unknown) => Promise<ApiResult>

const { getMock, postMock } = vi.hoisted(() => ({
  getMock: vi.fn<ApiCall>(),
  postMock: vi.fn<ApiCall>(),
}))

vi.mock('@/api/client', () => ({
  api: { GET: getMock, POST: postMock },
}))

const exportFile = {
  formatVersion: 2,
  exportedAt: '2026-08-01T12:00:00Z',
  metrics: [{ name: 'water', canonicalUnit: 'mL' }],
  items: [
    {
      name: 'water bottle',
      basisAmount: 100,
      basisUnit: 'mL',
      amounts: [{ metric: 'water', amount: 100 }],
      servings: [{ name: 'whole bottle', quantity: 500 }],
    },
  ],
  entries: [{ metric: 'water', amount: 250, loggedAt: '2026-08-01T08:00:00Z' }],
}

// jsdom implements neither object URLs nor anchor navigation.
const createObjectURL = vi.fn<(blob: Blob) => string>(() => 'blob:idli')
const revokeObjectURL = vi.fn<(url: string) => void>()
const anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

beforeEach(() => {
  vi.clearAllMocks()
  URL.createObjectURL = createObjectURL
  URL.revokeObjectURL = revokeObjectURL
  getMock.mockResolvedValue({
    data: exportFile,
    response: new Response(null, {
      headers: { 'content-disposition': 'attachment; filename="idli-export-2026-08-01.json"' },
    }),
  })
  postMock.mockResolvedValue({ data: { metrics: 1, items: 1, entries: 1 }, response: new Response() })
})

afterEach(() => {
  anchorClick.mockClear()
})

async function chooseFile(wrapper: VueWrapper, contents: string, name = 'export.json') {
  const input = wrapper.get('input[type="file"]')
  Object.defineProperty(input.element, 'files', {
    value: [new File([contents], name, { type: 'application/json' })],
    configurable: true,
  })
  await input.trigger('change')
  await flushPromises()
}

describe('DataView', () => {
  it('downloads the export under the server-issued filename', async () => {
    const wrapper = mount(DataView)

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(getMock).toHaveBeenCalledWith('/api/export')
    expect(createObjectURL).toHaveBeenCalledOnce()
    // The blob carries the export as (pretty-printed) JSON.
    const blob = createObjectURL.mock.calls[0]![0]
    expect(JSON.parse(await blob.text())).toEqual(exportFile)
    expect(anchorClick).toHaveBeenCalledOnce()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:idli')
    expect(wrapper.text()).not.toContain('failed')
  })

  it('shows an error instead of downloading when the export request fails', async () => {
    getMock.mockResolvedValue({ error: {}, response: new Response(null, { status: 401 }) })
    const wrapper = mount(DataView)

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('export failed')
    expect(anchorClick).not.toHaveBeenCalled()
  })

  it('previews a chosen file without posting anything yet', async () => {
    const wrapper = mount(DataView)

    await chooseFile(wrapper, JSON.stringify(exportFile))

    expect(postMock).not.toHaveBeenCalled()
    const confirm = wrapper.get('[data-testid="import-confirm"]')
    expect(confirm.text()).toContain('export.json')
    expect(confirm.text()).toContain('1 metrics')
    expect(confirm.text()).toContain('1 items')
    expect(confirm.text()).toContain('1 entries')
    expect(confirm.text()).toContain('formatVersion 2')
  })

  it('previews a formatVersion 1 file (no items key) as zero items', async () => {
    const wrapper = mount(DataView)
    const v1File = {
      formatVersion: 1,
      metrics: [{ name: 'water', canonicalUnit: 'mL' }],
      entries: [{ metric: 'water', amount: 250, loggedAt: '2026-08-01T08:00:00Z' }],
    }

    await chooseFile(wrapper, JSON.stringify(v1File))

    const confirm = wrapper.get('[data-testid="import-confirm"]')
    expect(confirm.text()).toContain('0 items')
    expect(confirm.text()).toContain('formatVersion 1')
  })

  it('imports with mode=replace only after explicit confirmation', async () => {
    const wrapper = mount(DataView)
    await chooseFile(wrapper, JSON.stringify(exportFile))

    const replaceButton = wrapper
      .findAll('button')
      .find((button) => button.text() === 'Replace everything')!
    await replaceButton.trigger('click')
    await flushPromises()

    expect(postMock).toHaveBeenCalledWith('/api/import', {
      params: { query: { mode: 'replace' } },
      body: exportFile,
    })
    expect(wrapper.text()).toContain('import done: 1 metrics, 1 items, 1 entries')
    // The confirmation is spent; a re-import needs a fresh file choice.
    expect(wrapper.find('[data-testid="import-confirm"]').exists()).toBe(false)
  })

  it('cancel discards the pending file without posting', async () => {
    const wrapper = mount(DataView)
    await chooseFile(wrapper, JSON.stringify(exportFile))

    const cancelButton = wrapper.findAll('button').find((button) => button.text() === 'Cancel')!
    await cancelButton.trigger('click')

    expect(postMock).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="import-confirm"]').exists()).toBe(false)
  })

  it('surfaces the server rejection reason when the import fails', async () => {
    postMock.mockResolvedValue({
      error: { message: 'unsupported formatVersion 0; this build reads formatVersion 1' },
      response: new Response(null, { status: 400 }),
    })
    const wrapper = mount(DataView)
    await chooseFile(wrapper, JSON.stringify(exportFile))

    const replaceButton = wrapper
      .findAll('button')
      .find((button) => button.text() === 'Replace everything')!
    await replaceButton.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('import failed: unsupported formatVersion 0')
  })

  it('falls back to the HTTP status when the rejection carries no message', async () => {
    postMock.mockResolvedValue({ error: {}, response: new Response(null, { status: 400 }) })
    const wrapper = mount(DataView)
    await chooseFile(wrapper, JSON.stringify(exportFile))

    const replaceButton = wrapper
      .findAll('button')
      .find((button) => button.text() === 'Replace everything')!
    await replaceButton.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('import failed (HTTP 400)')
  })

  it('rejects files that do not parse or are not export-shaped', async () => {
    const wrapper = mount(DataView)

    await chooseFile(wrapper, 'not json at all')
    expect(wrapper.text()).toContain('not a JSON file')
    expect(wrapper.find('[data-testid="import-confirm"]').exists()).toBe(false)

    await chooseFile(wrapper, JSON.stringify({ some: 'thing' }))
    expect(wrapper.text()).toContain('not an idli export file')
    expect(wrapper.find('[data-testid="import-confirm"]').exists()).toBe(false)

    // JSON.parse('null') succeeds — the one parse result that used to escape
    // the shape check and throw on property access.
    await chooseFile(wrapper, 'null')
    expect(wrapper.text()).toContain('not an idli export file')
    expect(wrapper.find('[data-testid="import-confirm"]').exists()).toBe(false)
  })
})
