/**
 * Volume display formatting (ADR-0002: values are stored canonically in mL,
 * converted only at display).
 *
 * Below 1000 mL the value is shown as-is in mL; from 1000 mL upward it is
 * shown in liters with as many decimals as needed to be truthful (up to 3,
 * i.e. mL precision), trailing zeros trimmed: 1000 -> "1 L", 1250 -> "1.25 L".
 */
export function formatVolume(canonicalML: number): string {
  if (canonicalML < 1000) {
    return `${canonicalML} mL`
  }
  const liters = Number.parseFloat((canonicalML / 1000).toFixed(3))
  return `${liters} L`
}
