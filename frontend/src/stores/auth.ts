import { ref } from 'vue'
import { defineStore } from 'pinia'
import { api, onUnauthorized } from '@/api/client'
import { readCookie, XSRF_COOKIE, XSRF_HEADER } from '@/api/csrf'

export type LoginResult = 'ok' | 'wrong-password' | 'unreachable'

/**
 * Login/logout are filter-based endpoints outside the OpenAPI contract —
 * the deliberate exception recorded in ADR-0006's amendment — hence this
 * small hand-written fetch instead of the typed client. The CSRF token is
 * read from the cookie per request because the backend rotates it on login.
 */
async function postForm(path: string, form?: Record<string, string>): Promise<Response> {
  const headers: Record<string, string> = {}
  const token = readCookie(XSRF_COOKIE)
  if (token !== null) {
    headers[XSRF_HEADER] = token
  }
  let body: URLSearchParams | undefined
  if (form !== undefined) {
    headers['Content-Type'] = 'application/x-www-form-urlencoded'
    body = new URLSearchParams(form)
  }
  return fetch(path, { method: 'POST', headers, body })
}

export const useAuthStore = defineStore('auth', () => {
  const checked = ref(false)
  const authenticated = ref(false)

  // Any 401 from the typed client means the server session is gone
  // (e.g. after a backend redeploy) — flip back to the login surface.
  onUnauthorized(() => {
    authenticated.value = false
  })

  /**
   * Asks the server whether the session is live. Also the first GET an
   * unauthenticated page performs, which makes the server issue the
   * XSRF-TOKEN cookie the login POST needs.
   */
  async function check(): Promise<void> {
    try {
      const { data } = await api.GET('/api/auth/session')
      authenticated.value = data?.authenticated === true
    } catch {
      // Unreachable backend: still mark the check as done so the UI can
      // move on; the login attempt will surface the connectivity error.
      authenticated.value = false
    }
    checked.value = true
  }

  async function login(password: string): Promise<LoginResult> {
    let response: Response
    try {
      // The username is fixed server-side; the UI only ever asks for a
      // password. The login POST is CSRF-exempt server-side (it carries the
      // password itself), so a 401 here always means: wrong password.
      response = await postForm('/api/auth/login', { username: 'user', password })
    } catch {
      return 'unreachable'
    }
    if (response.status === 401) {
      return 'wrong-password'
    }
    if (!response.ok) {
      return 'unreachable'
    }
    authenticated.value = true
    // Login rotated the CSRF token. Re-checking the session is a GET, which
    // makes the server re-issue the cookie before any further mutation.
    await check()
    return 'ok'
  }

  async function logout(): Promise<boolean> {
    let response: Response
    try {
      response = await postForm('/api/auth/logout')
    } catch {
      // Server unreachable: the server-side session may still be alive —
      // do NOT pretend the logout took.
      return false
    }
    if (!response.ok) {
      return false
    }
    authenticated.value = false
    // Logout deleted the XSRF-TOKEN cookie; this GET makes the server issue
    // a fresh one so the next login POST has a token to send.
    await check()
    return true
  }

  return { checked, authenticated, check, login, logout }
})
