import { describe, it, expect } from 'vitest'
import { formatAmount } from '../format'

describe('formatAmount', () => {
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
  ])('formats %i mL as "%s"', (ml, expected) => {
    expect(formatAmount(ml, 'mL')).toBe(expected)
  })

  it.each([
    [0, '0 kJ'],
    [1250, '1250 kJ'],
  ])('renders non-volume units as-is: %i kJ -> "%s"', (kj, expected) => {
    expect(formatAmount(kj, 'kJ')).toBe(expected)
  })
})
