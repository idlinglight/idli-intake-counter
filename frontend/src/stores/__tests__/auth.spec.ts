import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '../auth'

type ApiResult = { data?: unknown; error?: unknown }
type ApiGet = (path: string) => Promise<ApiResult>

const { getMock, onUnauthorizedMock } = vi.hoisted(() => ({
  getMock: vi.fn<ApiGet>(),
  onUnauthorizedMock: vi.fn<(handler: () => void) => void>(),
}))

vi.mock('@/api/client', () => ({
  api: { GET: getMock },
  onUnauthorized: onUnauthorizedMock,
}))

// Login/logout bypass the typed client (filter-based endpoints outside the
// OpenAPI contract), so they are observed on the global fetch.
const fetchMock = vi.fn<typeof fetch>()
vi.stubGlobal('fetch', fetchMock)

function loginCall(call: number): { url: string; init: RequestInit } {
  const [url, init] = fetchMock.mock.calls[call]!
  return { url: url as string, init: init! }
}

beforeEach(() => {
  vi.clearAllMocks()
  setActivePinia(createPinia())
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
})

describe('auth store', () => {
  it('check() marks checked and takes authenticated from the session endpoint', async () => {
    getMock.mockResolvedValue({ data: { authenticated: true } })
    const store = useAuthStore()

    expect(store.checked).toBe(false)
    await store.check()

    expect(getMock).toHaveBeenCalledWith('/api/auth/session')
    expect(store.checked).toBe(true)
    expect(store.authenticated).toBe(true)
  })

  it('check() stays unauthenticated when the session says so', async () => {
    getMock.mockResolvedValue({ data: { authenticated: false } })
    const store = useAuthStore()

    await store.check()

    expect(store.checked).toBe(true)
    expect(store.authenticated).toBe(false)
  })

  it('check() still completes (unauthenticated) when the backend is unreachable', async () => {
    getMock.mockRejectedValue(new Error('network down'))
    const store = useAuthStore()

    await store.check()

    expect(store.checked).toBe(true)
    expect(store.authenticated).toBe(false)
  })

  it('login() posts the fixed username form-encoded with the CSRF header and re-checks', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-1'
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    getMock.mockResolvedValue({ data: { authenticated: true } })
    const store = useAuthStore()

    const result = await store.login('hunter2')

    expect(result).toBe('ok')
    const { url, init } = loginCall(0)
    expect(url).toBe('/api/auth/login')
    expect(init.method).toBe('POST')
    const headers = new Headers(init.headers)
    expect(headers.get('X-XSRF-TOKEN')).toBe('csrf-1')
    expect(headers.get('Content-Type')).toBe('application/x-www-form-urlencoded')
    expect(String(init.body)).toBe('username=user&password=hunter2')
    // Login rotates the CSRF token: the follow-up session GET makes the
    // server re-issue the cookie before any further mutation.
    expect(getMock).toHaveBeenCalledWith('/api/auth/session')
    expect(store.authenticated).toBe(true)
  })

  it('login() signals no-session when the re-check finds no session', async () => {
    // The browser refused the Secure JSESSIONID (plain-http origin that is not
    // localhost): the POST still answers 204, so the status code alone would
    // report success and strand the user on a silent login form.
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    getMock.mockResolvedValue({ data: { authenticated: false } })
    const store = useAuthStore()

    const result = await store.login('hunter2')

    expect(result).toBe('no-session')
    expect(store.authenticated).toBe(false)
  })

  it('login() signals no-session when the re-check cannot reach the backend', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    getMock.mockRejectedValue(new Error('network down'))
    const store = useAuthStore()

    const result = await store.login('hunter2')

    expect(result).toBe('no-session')
    expect(store.authenticated).toBe(false)
  })

  it('login() signals wrong-password on 401 and stays unauthenticated', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }))
    const store = useAuthStore()

    const result = await store.login('nope')

    expect(result).toBe('wrong-password')
    expect(store.authenticated).toBe(false)
    expect(getMock).not.toHaveBeenCalled()
  })

  it('login() signals unreachable when the request fails', async () => {
    fetchMock.mockRejectedValue(new Error('network down'))
    const store = useAuthStore()

    const result = await store.login('hunter2')

    expect(result).toBe('unreachable')
    expect(store.authenticated).toBe(false)
  })

  it('logout() posts with the CSRF header, clears authenticated and re-checks', async () => {
    getMock.mockResolvedValueOnce({ data: { authenticated: true } })
    const store = useAuthStore()
    await store.check()
    expect(store.authenticated).toBe(true)

    document.cookie = 'XSRF-TOKEN=csrf-2'
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    getMock.mockResolvedValueOnce({ data: { authenticated: false } })
    const result = await store.logout()

    expect(result).toBe(true)
    expect(store.authenticated).toBe(false)
    const { url, init } = loginCall(0)
    expect(url).toBe('/api/auth/logout')
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('X-XSRF-TOKEN')).toBe('csrf-2')
    // The re-check GET makes the server issue a fresh XSRF cookie for the
    // next login POST (logout deleted the old one).
    expect(getMock).toHaveBeenCalledTimes(2)
  })

  it('logout() keeps authenticated when the server rejects the logout', async () => {
    getMock.mockResolvedValueOnce({ data: { authenticated: true } })
    const store = useAuthStore()
    await store.check()

    fetchMock.mockResolvedValue(new Response(null, { status: 502 }))
    const result = await store.logout()

    // The server-side session may still be alive: do not pretend.
    expect(result).toBe(false)
    expect(store.authenticated).toBe(true)
    expect(getMock).toHaveBeenCalledTimes(1)
  })

  it('logout() keeps authenticated when the backend is unreachable', async () => {
    getMock.mockResolvedValueOnce({ data: { authenticated: true } })
    const store = useAuthStore()
    await store.check()

    fetchMock.mockRejectedValue(new Error('network down'))
    const result = await store.logout()

    expect(result).toBe(false)
    expect(store.authenticated).toBe(true)
  })

  it('login() works without any CSRF cookie (post-logout state)', async () => {
    // Login is CSRF-exempt server-side; with no cookie present the POST
    // simply carries no token header and still succeeds first try.
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    getMock.mockResolvedValue({ data: { authenticated: true } })
    const store = useAuthStore()

    const result = await store.login('hunter2')

    expect(result).toBe('ok')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(new Headers(loginCall(0).init.headers).get('X-XSRF-TOKEN')).toBeNull()
    expect(store.authenticated).toBe(true)
  })

  it('registers a 401 handler that flips the store to unauthenticated', async () => {
    getMock.mockResolvedValue({ data: { authenticated: true } })
    const store = useAuthStore()
    await store.check()
    expect(store.authenticated).toBe(true)

    expect(onUnauthorizedMock).toHaveBeenCalledTimes(1)
    const handler = onUnauthorizedMock.mock.calls[0]![0]
    handler()

    expect(store.authenticated).toBe(false)
  })
})
