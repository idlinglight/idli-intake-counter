/** Name of the JS-readable cookie the backend issues the CSRF token in. */
export const XSRF_COOKIE = 'XSRF-TOKEN'

/** Header every mutating request must carry the token in, or the server answers 403. */
export const XSRF_HEADER = 'X-XSRF-TOKEN'

/**
 * Reads a cookie value fresh from `document.cookie` at call time.
 *
 * Deliberately not cached: the backend rotates the XSRF-TOKEN cookie on
 * login, so a stored value goes stale. Reading per request means whatever
 * the server last issued is what gets sent.
 */
export function readCookie(name: string): string | null {
  const prefix = `${name}=`
  for (const part of document.cookie.split('; ')) {
    if (part.startsWith(prefix)) {
      return decodeURIComponent(part.slice(prefix.length))
    }
  }
  return null
}
