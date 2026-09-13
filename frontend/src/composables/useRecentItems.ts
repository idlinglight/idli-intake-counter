import { ref } from 'vue'

export const STORAGE_KEY = 'idli.recentItems'

/** How many recent items the picker shows. */
export const RECENT_SHOWN = 5

/**
 * How many names are remembered. Deeper than shown so that deleted or
 * renamed items (which the view drops on render) don't leave the section
 * short.
 */
export const RECENT_STORED = 20

/**
 * Remembers which items were logged most recently, for the picker's "Recent"
 * section — a client-side experiment (issue #38) before committing the
 * signal to the backend.
 *
 * Entries never reference items (ADR-0007), so recency can't be derived from
 * history; it is stamped at log time instead. Keyed by item *name*: item ids
 * are not preserved across a replace-import, names are. Lives in
 * localStorage, so it is per device and gone when site data is cleared —
 * accepted limits of the experiment.
 *
 * The list is most-recent-first and free of duplicates; the view resolves
 * names against the current item list and drops what no longer exists.
 */
export function useRecentItems() {
  const recentNames = ref<string[]>(read())

  function touch(name: string) {
    const next = [name, ...recentNames.value.filter((other) => other !== name)].slice(
      0,
      RECENT_STORED,
    )
    recentNames.value = next
    write(next)
  }

  return { recentNames, touch }
}

// Storage access is wrapped throughout: a private window or blocked site data
// can make the accessor throw, and a stale or hand-edited value must not take
// the picker down with it — the section simply stays empty.
function read(): string[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((entry): entry is string => typeof entry === 'string').slice(0, RECENT_STORED)
  } catch {
    return []
  }
}

function write(names: string[]) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(names))
  } catch {
    // Quota or blocked storage: the in-memory list still serves this session.
  }
}
