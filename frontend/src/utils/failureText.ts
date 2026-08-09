/**
 * The backend's rejection reason (include-message) beats a bare status code:
 * "action failed: reason" when the body carries one, "action failed (HTTP n)"
 * otherwise. The one place mutation-failure wording lives — HomeView and
 * AuthoringView both route through it.
 */
export function failureText(
  action: string,
  result: { error?: unknown; response?: Response },
): string {
  const body = result.error as { message?: string } | undefined
  return body?.message
    ? `${action} failed: ${body.message}`
    : `${action} failed (HTTP ${result.response?.status})`
}
