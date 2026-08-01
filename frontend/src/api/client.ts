import createClient from 'openapi-fetch'
import type { Middleware } from 'openapi-fetch'
import type { paths } from './schema'
import { readCookie, XSRF_COOKIE, XSRF_HEADER } from './csrf'

type UnauthorizedHandler = () => void
let unauthorizedHandler: UnauthorizedHandler | null = null

/**
 * Registers the handler invoked whenever an API response comes back 401
 * (the server session is gone, e.g. after a backend redeploy).
 *
 * A callback registry instead of importing the auth store here avoids a
 * client → store → client import cycle and any pinia-before-mount issues.
 */
export function onUnauthorized(handler: UnauthorizedHandler): void {
  unauthorizedHandler = handler
}

const csrfAndSession: Middleware = {
  onRequest({ request }) {
    // Mutating requests must carry the CSRF token; the cookie is re-read on
    // every request because login rotates it (see csrf.ts).
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      const token = readCookie(XSRF_COOKIE)
      if (token !== null) {
        request.headers.set(XSRF_HEADER, token)
      }
    }
  },
  onResponse({ response }) {
    if (response.status === 401) {
      unauthorizedHandler?.()
    }
  },
}

export const api = createClient<paths>({ baseUrl: '/' })
api.use(csrfAndSession)
