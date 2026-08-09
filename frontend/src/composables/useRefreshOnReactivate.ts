import { onMounted, onUnmounted, ref } from 'vue'

/** Reactivation only counts as stale after this much quiet time. */
export const STALE_AFTER_MS = 5 * 60 * 1000

/**
 * Indicator floor: the fetch is usually near-instant, and a sub-perceptible
 * flash cannot explain the list movement it exists to announce.
 */
export const MIN_VISIBLE_MS = 600

/**
 * Re-runs `refresh` when the window is re-activated (tab or app switch) and
 * the view's data may have gone stale — the answer to logging on one device
 * and later looking at another device's long-open window.
 *
 * `refresh` reports failure by resolving to `false` (or by rejecting); a
 * failed attempt leaves the staleness clock untouched, so the next
 * reactivation retries instead of sitting out STALE_AFTER_MS on stale data
 * (the classic case: focus fires on laptop wake before Wi-Fi is back).
 *
 * Staleness is measured from mount / the last reactivation refresh, not from
 * the view's own mutation refreshes; the worst case of that simplification is
 * one redundant refetch, since mutations only happen while the tab is active.
 *
 * `refreshing` drives the visual indicator.
 */
export function useRefreshOnReactivate(refresh: () => Promise<unknown>) {
  const refreshing = ref(false)
  let lastFresh = 0
  let inFlight = false
  let unmounted = false
  let hideTimer: ReturnType<typeof setTimeout> | undefined

  async function reactivated() {
    // One reactivation usually fires both focus and visibilitychange.
    if (inFlight) return
    if (document.visibilityState === 'hidden') return
    if (Date.now() - lastFresh < STALE_AFTER_MS) return
    inFlight = true
    refreshing.value = true
    const shownAt = Date.now()
    let ok = false
    try {
      ok = (await refresh()) !== false
    } catch {
      // A rejecting refresh is a failed refresh — swallow it here (nothing
      // upstream of a DOM event listener could handle it anyway) and leave
      // the staleness clock untouched so the next reactivation retries.
    } finally {
      if (ok) lastFresh = Date.now()
      inFlight = false
      const remaining = MIN_VISIBLE_MS - (Date.now() - shownAt)
      // After unmount there is no indicator to hold — and a timer scheduled
      // here would outlive the clearTimeout that already ran in onUnmounted.
      if (remaining > 0 && !unmounted) {
        hideTimer = setTimeout(() => {
          refreshing.value = false
        }, remaining)
      } else {
        refreshing.value = false
      }
    }
  }

  onMounted(() => {
    lastFresh = Date.now()
    document.addEventListener('visibilitychange', reactivated)
    window.addEventListener('focus', reactivated)
  })

  onUnmounted(() => {
    unmounted = true
    document.removeEventListener('visibilitychange', reactivated)
    window.removeEventListener('focus', reactivated)
    clearTimeout(hideTimer)
  })

  return { refreshing }
}
