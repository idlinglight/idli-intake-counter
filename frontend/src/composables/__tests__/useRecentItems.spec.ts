import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { RECENT_STORED, STORAGE_KEY, useRecentItems } from '../useRecentItems'

beforeEach(() => {
  localStorage.clear()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('useRecentItems', () => {
  it('starts empty and moves touched names to the front without duplicates', () => {
    const { recentNames, touch } = useRecentItems()
    expect(recentNames.value).toEqual([])

    touch('pasta')
    touch('protein bar')
    touch('pasta')

    expect(recentNames.value).toEqual(['pasta', 'protein bar'])
  })

  it('persists across instances', () => {
    useRecentItems().touch('pasta')
    useRecentItems().touch('oil')

    expect(useRecentItems().recentNames.value).toEqual(['oil', 'pasta'])
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)!)).toEqual(['oil', 'pasta'])
  })

  it('caps the remembered list', () => {
    const { recentNames, touch } = useRecentItems()
    for (let i = 0; i < RECENT_STORED + 3; i++) touch(`item ${i}`)

    expect(recentNames.value).toHaveLength(RECENT_STORED)
    expect(recentNames.value[0]).toBe(`item ${RECENT_STORED + 2}`)
  })

  it('ignores garbage in storage instead of failing', () => {
    localStorage.setItem(STORAGE_KEY, 'not json')
    expect(useRecentItems().recentNames.value).toEqual([])

    localStorage.setItem(STORAGE_KEY, JSON.stringify({ nope: true }))
    expect(useRecentItems().recentNames.value).toEqual([])

    localStorage.setItem(STORAGE_KEY, JSON.stringify(['pasta', 7, null, 'oil']))
    expect(useRecentItems().recentNames.value).toEqual(['pasta', 'oil'])
  })

  it('keeps working in memory when storage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })

    const { recentNames, touch } = useRecentItems()
    touch('pasta')

    expect(recentNames.value).toEqual(['pasta'])
  })
})
