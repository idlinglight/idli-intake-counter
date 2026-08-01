import { describe, it, expect, vi, beforeEach } from 'vitest'
import { api, onUnauthorized } from '../client'

// jsdom has no server, and undici's Request needs an absolute URL, so every
// call overrides baseUrl and injects a mock fetch (both supported per-request
// by openapi-fetch). The global middleware under test still runs.
const BASE = 'http://test.local'

const fetchMock = vi.fn<typeof fetch>()

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function sentRequest(call: number): Request {
  return fetchMock.mock.calls[call]![0] as Request
}

beforeEach(() => {
  vi.clearAllMocks()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
})

describe('api client CSRF middleware', () => {
  it('attaches X-XSRF-TOKEN from the cookie on mutating requests', async () => {
    document.cookie = 'XSRF-TOKEN=token-a'
    fetchMock.mockResolvedValue(jsonResponse({ id: 1 }, 201))

    await api.POST('/api/entries', {
      body: { metricId: 1, amount: 250 },
      baseUrl: BASE,
      fetch: fetchMock,
    })

    expect(sentRequest(0).headers.get('X-XSRF-TOKEN')).toBe('token-a')
  })

  it('re-reads the cookie on every request, so a rotated token is picked up', async () => {
    // A fresh Response per call — a body can only be consumed once.
    fetchMock.mockImplementation(async () => jsonResponse({ id: 1 }, 201))

    document.cookie = 'XSRF-TOKEN=token-a'
    await api.POST('/api/entries', {
      body: { metricId: 1, amount: 250 },
      baseUrl: BASE,
      fetch: fetchMock,
    })
    // Login rotates the token server-side; the client must not cache it.
    document.cookie = 'XSRF-TOKEN=token-b'
    await api.POST('/api/entries', {
      body: { metricId: 1, amount: 500 },
      baseUrl: BASE,
      fetch: fetchMock,
    })

    expect(sentRequest(0).headers.get('X-XSRF-TOKEN')).toBe('token-a')
    expect(sentRequest(1).headers.get('X-XSRF-TOKEN')).toBe('token-b')
  })

  it('does not attach the header on GET', async () => {
    document.cookie = 'XSRF-TOKEN=token-a'
    fetchMock.mockResolvedValue(jsonResponse({ authenticated: true }))

    await api.GET('/api/auth/session', { baseUrl: BASE, fetch: fetchMock })

    expect(sentRequest(0).headers.get('X-XSRF-TOKEN')).toBeNull()
  })

  it('notifies the registered handler on a 401 response, and only then', async () => {
    const handler = vi.fn<() => void>()
    onUnauthorized(handler)

    fetchMock.mockResolvedValue(jsonResponse({ authenticated: true }))
    await api.GET('/api/auth/session', { baseUrl: BASE, fetch: fetchMock })
    expect(handler).not.toHaveBeenCalled()

    fetchMock.mockResolvedValue(new Response(null, { status: 401 }))
    await api.GET('/api/days/today', { baseUrl: BASE, fetch: fetchMock })
    expect(handler).toHaveBeenCalledTimes(1)
  })
})
