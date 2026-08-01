import { describe, it, expect } from 'vitest'
import { formatVolume } from '../volume'

describe('formatVolume', () => {
  it.each([
    [0, '0 mL'],
    [250, '250 mL'],
    [500, '500 mL'],
    [999, '999 mL'],
    [1000, '1 L'],
    [1001, '1.001 L'],
    [1250, '1.25 L'],
    [1500, '1.5 L'],
    [2000, '2 L'],
    [12345, '12.345 L'],
  ])('formats %i mL as "%s"', (canonicalML, expected) => {
    expect(formatVolume(canonicalML)).toBe(expected)
  })
})
