import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { defineComponent, h, type Ref } from 'vue'
import {
  useRefreshOnReactivate,
  STALE_AFTER_MS,
  MIN_VISIBLE_MS,
} from '../useRefreshOnReactivate'

enableAutoUnmount(afterEach)

let visibility: DocumentVisibilityState

function mountHost(refresh: () => Promise<unknown>) {
  let refreshing!: Ref<boolean>
  const Host = defineComponent({
    setup() {
      refreshing = useRefreshOnReactivate(refresh).refreshing
      return () => h('div')
    },
  })
  const wrapper = mount(Host)
  return { wrapper, refreshing: () => refreshing.value }
}

/** One real reactivation fires both events, in this order. */
async function reactivate() {
  window.dispatchEvent(new Event('focus'))
  document.dispatchEvent(new Event('visibilitychange'))
  // Flush the handler's microtasks without advancing any timers.
  await vi.advanceTimersByTimeAsync(0)
}

beforeEach(() => {
  vi.useFakeTimers()
  visibility = 'visible'
  vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility)
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('useRefreshOnReactivate', () => {
  it('stays quiet when re-activated before the staleness window', async () => {
    const refresh = vi.fn<() => Promise<void>>(async () => {})
    mountHost(refresh)

    vi.advanceTimersByTime(STALE_AFTER_MS - 1000)
    await reactivate()

    expect(refresh).not.toHaveBeenCalled()
  })

  it('refreshes exactly once per reactivation after the window', async () => {
    const refresh = vi.fn<() => Promise<void>>(async () => {})
    mountHost(refresh)

    vi.advanceTimersByTime(STALE_AFTER_MS + 1000)
    await reactivate()

    // Two events, one refresh — the in-flight guard absorbs the pair.
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('ignores visibility events while the tab is hidden', async () => {
    const refresh = vi.fn<() => Promise<void>>(async () => {})
    mountHost(refresh)

    vi.advanceTimersByTime(STALE_AFTER_MS + 1000)
    visibility = 'hidden'
    await reactivate()

    expect(refresh).not.toHaveBeenCalled()
  })

  it('holds the indicator for the minimum duration on a fast refresh', async () => {
    const refresh = vi.fn<() => Promise<void>>(async () => {})
    const { refreshing } = mountHost(refresh)

    vi.advanceTimersByTime(STALE_AFTER_MS + 1000)
    await reactivate()

    // The fetch already resolved, but the indicator must outlive the flash.
    expect(refreshing()).toBe(true)
    await vi.advanceTimersByTimeAsync(MIN_VISIBLE_MS)
    expect(refreshing()).toBe(false)
  })

  it('drops the indicator immediately after a slow refresh', async () => {
    const refresh = vi.fn<() => Promise<void>>(
      () => new Promise<void>((resolve) => setTimeout(resolve, MIN_VISIBLE_MS * 2)),
    )
    const { refreshing } = mountHost(refresh)

    vi.advanceTimersByTime(STALE_AFTER_MS + 1000)
    await reactivate()
    expect(refreshing()).toBe(true)

    await vi.advanceTimersByTimeAsync(MIN_VISIBLE_MS * 2)
    expect(refreshing()).toBe(false)
  })

  it('resets the staleness clock after a reactivation refresh', async () => {
    const refresh = vi.fn<() => Promise<void>>(async () => {})
    mountHost(refresh)

    vi.advanceTimersByTime(STALE_AFTER_MS + 1000)
    await reactivate()
    await vi.advanceTimersByTimeAsync(MIN_VISIBLE_MS)

    // Fresh again: an immediate second reactivation must not refetch.
    await reactivate()
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('stops listening after unmount', async () => {
    const refresh = vi.fn<() => Promise<void>>(async () => {})
    const { wrapper } = mountHost(refresh)

    wrapper.unmount()
    vi.advanceTimersByTime(STALE_AFTER_MS + 1000)
    await reactivate()

    expect(refresh).not.toHaveBeenCalled()
  })
})
