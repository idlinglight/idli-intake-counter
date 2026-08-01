/**
 * Amount display formatting (ADR-0002: values are stored in the metric's
 * canonical unit, converted only at display).
 *
 * mL gets volume-friendly scaling: below 1000 mL as-is, from 1000 mL upward
 * in liters with as many decimals as needed to stay truthful (up to 3, i.e.
 * mL precision), trailing zeros trimmed: 1000 -> "1 L", 1250 -> "1.25 L".
 * Every other unit renders as-is with its unit symbol.
 */
export function formatAmount(amount: number, canonicalUnit: string): string {
  if (canonicalUnit === 'mL' && amount >= 1000) {
    const liters = Number.parseFloat((amount / 1000).toFixed(3))
    return `${liters} L`
  }
  return `${amount} ${canonicalUnit}`
}
