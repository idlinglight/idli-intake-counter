<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '@/api/client'
import type { components } from '@/api/schema'
import { formatAmount } from '@/utils/format'
import { useRefreshOnReactivate } from '@/composables/useRefreshOnReactivate'
import RefreshIndicator from '@/components/RefreshIndicator.vue'

type DayView = components['schemas']['DayViewDto']
type Metric = components['schemas']['MetricDto']
type Item = components['schemas']['ItemDto']
type Entry = components['schemas']['EntryDto']

const quickAmounts = [250, 500, 1000]

const metrics = ref<Metric[]>([])
const items = ref<Item[]>([])
const day = ref<DayView | null>(null)
const error = ref('')

const selectedItemId = ref<number | null>(null)
// Applies to the next serving tap, then snaps back to 1 — the default path
// stays "pick item, tap serving, done".
const multiplier = ref(1)

const metricById = computed(() => new Map(metrics.value.map((metric) => [metric.id, metric])))
const waterMetric = computed(() => metrics.value.find((metric) => metric.name === 'water') ?? null)
const waterUnit = computed(() => waterMetric.value?.canonicalUnit ?? 'mL')

const waterTotal = computed(
  () =>
    (day.value?.totals ?? []).find((total) => total.metricId === waterMetric.value?.id)?.total ??
    0,
)

// Every item is loggable: composition alone is enough for the ad-hoc
// quantity path (authoring refuses composition-less items); servings just
// add one-tap shortcuts on top.
const selectedItem = computed(
  () => items.value.find((item) => item.id === selectedItemId.value) ?? null,
)

/**
 * The unified day list: entries sharing a groupId collapse into one row
 * (label + aggregated amounts, one atomic delete); ad-hoc entries render as
 * before. Grouping is purely presentational — the API stays plain entries.
 */
type DayRow = {
  key: string
  time: string
  main: string
  amounts: string
  entryId?: number
  groupId?: string
}

const dayRows = computed<DayRow[]>(() => {
  const rows: DayRow[] = []
  const groupEntries = new Map<string, Entry[]>()
  for (const entry of day.value?.entries ?? []) {
    const groupId = entry.groupId
    if (!groupId) {
      rows.push({
        key: `entry-${entry.id}`,
        time: entryTime(entry.loggedAt),
        main: entryText(entry),
        amounts: '',
        entryId: entry.id,
      })
      continue
    }
    if (!groupEntries.has(groupId)) {
      groupEntries.set(groupId, [])
      rows.push({
        key: `group-${groupId}`,
        time: entryTime(entry.loggedAt),
        main: entry.label ?? '',
        amounts: '',
        groupId,
      })
    }
    groupEntries.get(groupId)!.push(entry)
  }
  for (const row of rows) {
    if (row.groupId) {
      // The day endpoint returns id-DESC within a shared timestamp; re-sort
      // ascending so the row lists amounts in the order the log action
      // created them (metricId ASC — the EntryGroupDto contract).
      row.amounts = (groupEntries.get(row.groupId) ?? [])
        .slice()
        .sort((a, b) => (a.id ?? 0) - (b.id ?? 0))
        .map(entryText)
        .join(', ')
    }
  }
  return rows
})

const totalsText = computed(() =>
  (day.value?.totals ?? [])
    .map((total) => `${total.metricName} ${formatAmount(total.total ?? 0, total.canonicalUnit ?? '')}`)
    .join(' · '),
)

// The backend is the single clock: "today" is resolved server-side in the
// configured zone, so browser and server can never disagree on the day.
// On failure the previous day view is kept — stale data beats fake-empty data.
// The generation token drops responses overtaken by a newer refresh: a slow
// reactivation reload must not land after a quick-log's refresh and revert
// the just-logged entry (last writer would otherwise win).
let dayGen = 0
async function refreshDay(): Promise<boolean> {
  const gen = ++dayGen
  try {
    const { data } = await api.GET('/api/days/today')
    if (gen !== dayGen) return true // superseded — a newer refresh owns the state
    if (!data) {
      error.value = 'could not load today'
      return false
    }
    day.value = data
    return true
  } catch {
    if (gen === dayGen) error.value = 'backend unreachable'
    return false
  }
}

async function load(): Promise<boolean> {
  try {
    // The day fetch needs nothing from metrics/items — run all three in
    // parallel; the guards below only gate how the results are applied.
    const [metricsResult, itemsResult, dayOk] = await Promise.all([
      api.GET('/api/metrics'),
      api.GET('/api/items'),
      refreshDay(),
    ])
    if (!metricsResult.data) {
      error.value = 'backend unreachable'
      return false
    }
    metrics.value = metricsResult.data
    // Items only power the serving surface — a failed items fetch must not
    // take down water logging and the day list with it.
    items.value = itemsResult.data ?? []
    if (waterMetric.value?.id === undefined) {
      error.value = 'no "water" metric configured'
      return false
    }
    if (!itemsResult.data) {
      error.value = 'could not load items'
      return false
    }
    if (!dayOk) return false // refreshDay already reported its own error
    // Only a full success clears stale banners — a failed (background)
    // reload must not wipe a message it didn't resolve.
    error.value = ''
    return true
  } catch {
    error.value = 'backend unreachable'
    return false
  }
}

async function quickLog(amount: number) {
  const metricId = waterMetric.value?.id
  if (metricId === undefined) return
  error.value = ''
  try {
    const { error: postError } = await api.POST('/api/entries', {
      body: { metricId, amount },
    })
    if (postError) {
      error.value = 'logging failed'
      return
    }
  } catch {
    error.value = 'backend unreachable'
    return
  }
  await refreshDay()
}

async function logServing(servingId: number | undefined) {
  if (servingId === undefined) return
  // A cleared input leaves '' behind (v-model.number keeps unparseable raw
  // values) — sending it would silently log at ×1. Refuse loudly instead.
  if (typeof multiplier.value !== 'number' || !Number.isFinite(multiplier.value)) {
    error.value = 'enter a multiplier before logging'
    return
  }
  error.value = ''
  try {
    const body = multiplier.value === 1 ? {} : { multiplier: multiplier.value }
    const { error: postError, response } = await api.POST('/api/servings/{id}/entries', {
      params: { path: { id: servingId } },
      body,
    })
    if (postError) {
      // The backend's reason is user-meaningful here (e.g. an amount that
      // would round to zero) — show it when present, mirroring
      // AuthoringView's failureText fallback otherwise.
      const message = (postError as { message?: string }).message
      // The contract declares only the 201 response, so TS narrows the error
      // branch's `response` to never — widen it back to the real Response.
      error.value = message
        ? `logging failed: ${message}`
        : `logging failed (HTTP ${(response as Response | undefined)?.status})`
      return
    }
  } catch {
    error.value = 'backend unreachable'
    return
  }
  multiplier.value = 1
  await refreshDay()
}

// ── Ad-hoc quantities: weighed portions in the item's basis unit ──

const adhocQuantity = ref<number | null>(null)
const weighMode = ref(false)
const weighBefore = ref<number | null>(null)
const weighAfter = ref<number | null>(null)

/** v-model.number keeps unparseable raw values ('' stays a string) — treat anything non-finite as absent. */
function asAmount(value: number | null): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

const weighDelta = computed(() => {
  const before = asAmount(weighBefore.value)
  const after = asAmount(weighAfter.value)
  return before === null || after === null ? null : before - after
})

// What a Log tap would send; null disables the button (fractions, empty
// inputs, and non-positive deltas included — the backend wants whole > 0).
const adhocLogQuantity = computed(() => {
  const quantity = weighMode.value ? weighDelta.value : asAmount(adhocQuantity.value)
  return quantity !== null && Number.isInteger(quantity) && quantity > 0 ? quantity : null
})

// Deliberately multiplier-free: the typed quantity is already the precise
// knob (a leftover ×2 silently doubling a weighed 137 g is the mistake this
// avoids), and the API contract carries no multiplier either.
async function logAdhoc() {
  const itemId = selectedItem.value?.id
  const quantity = adhocLogQuantity.value
  if (itemId === undefined || quantity === null) return
  error.value = ''
  try {
    const { error: postError, response } = await api.POST('/api/items/{id}/entries', {
      params: { path: { id: itemId } },
      body: { quantity },
    })
    if (postError) {
      const message = (postError as { message?: string }).message
      error.value = message
        ? `logging failed: ${message}`
        : `logging failed (HTTP ${(response as Response | undefined)?.status})`
      return
    }
  } catch {
    error.value = 'backend unreachable'
    return
  }
  // Spent inputs snap back empty — a second tap must be a deliberate re-entry.
  adhocQuantity.value = null
  weighBefore.value = null
  weighAfter.value = null
  await refreshDay()
}

async function removeEntry(id: number | undefined) {
  if (id === undefined) return
  error.value = ''
  try {
    const { error: deleteError } = await api.DELETE('/api/entries/{id}', {
      params: { path: { id } },
    })
    if (deleteError) {
      // Refresh anyway: a 404 means the entry is already gone server-side.
      error.value = 'delete failed'
    }
  } catch {
    error.value = 'backend unreachable'
    return
  }
  await refreshDay()
}

async function removeGroup(groupId: string | undefined) {
  if (!groupId) return
  error.value = ''
  try {
    const { error: deleteError } = await api.DELETE('/api/entry-groups/{groupId}', {
      params: { path: { groupId } },
    })
    if (deleteError) {
      error.value = 'delete failed'
    }
  } catch {
    error.value = 'backend unreachable'
    return
  }
  await refreshDay()
}

/** "water 500 mL" — one entry's metric name and formatted amount. */
function entryText(entry: Entry): string {
  const metric = metricById.value.get(entry.metricId)
  if (!metric) return `metric #${entry.metricId}`
  return `${metric.name} ${formatAmount(entry.amount ?? 0, metric.canonicalUnit ?? '')}`
}

/** HH:MM (local time) of an entry timestamp — a pure display concern. */
function entryTime(loggedAt: string | undefined): string {
  if (!loggedAt) return ''
  const date = new Date(loggedAt)
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

onMounted(load)

// Full load, not just refreshDay: items authored on another device should
// appear in the picker too when this window wakes up.
const { refreshing } = useRefreshOnReactivate(load)
</script>

<template>
  <main class="home">
    <h1 class="title">Water</h1>

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <p class="total" data-testid="water-total">{{ formatAmount(waterTotal, waterUnit) }}</p>

    <div class="quick-log">
      <button
        v-for="amount in quickAmounts"
        :key="amount"
        type="button"
        class="quick-log-button"
        :disabled="waterMetric === null"
        @click="quickLog(amount)"
      >
        +{{ amount }} mL
      </button>
    </div>

    <!-- Above the fold, next to the water number it complements — at the
         bottom of the day list it was a scroll away and easily missed. -->
    <p v-if="totalsText" class="day-totals" data-testid="day-totals">{{ totalsText }}</p>

    <section v-if="items.length > 0" class="log-item" data-testid="log-item-section">
      <h2 class="subtitle">Log item</h2>
      <select v-model.number="selectedItemId" class="item-select" data-testid="item-select">
        <option :value="null">log an item…</option>
        <option v-for="item in items" :key="item.id" :value="item.id">
          {{ item.name }}
        </option>
      </select>
      <template v-if="selectedItem">
        <template v-if="(selectedItem.servings ?? []).length > 0">
          <div class="serving-buttons">
            <button
              v-for="serving in selectedItem.servings"
              :key="serving.id"
              type="button"
              class="quick-log-button serving-button"
              data-testid="serving-button"
              @click="logServing(serving.id)"
            >
              {{ serving.name }} ({{ serving.quantity }} {{ selectedItem.basisUnit }})
            </button>
          </div>
          <label class="multiplier">
            ×
            <input
              v-model.number="multiplier"
              data-testid="multiplier-input"
              class="multiplier-input"
              type="number"
              min="0.01"
              step="0.25"
            />
            <span class="multiplier-hint">applies to the next serving tap</span>
          </label>
        </template>
        <!-- The weighed-portion path: a quantity in the basis unit, logged
             directly — no serving needs to exist. "from weights" swaps in
             container before/after fields and logs the delta. -->
        <div class="adhoc" data-testid="adhoc-block">
          <label v-if="!weighMode" class="adhoc-field">
            <input
              v-model.number="adhocQuantity"
              data-testid="adhoc-quantity"
              class="multiplier-input"
              type="number"
              min="1"
              step="1"
            />
            {{ selectedItem.basisUnit }}
          </label>
          <template v-else>
            <label class="adhoc-field">
              before
              <input
                v-model.number="weighBefore"
                data-testid="weigh-before"
                class="multiplier-input"
                type="number"
                min="0"
                step="1"
              />
            </label>
            <label class="adhoc-field">
              after
              <input
                v-model.number="weighAfter"
                data-testid="weigh-after"
                class="multiplier-input"
                type="number"
                min="0"
                step="1"
              />
            </label>
            <span class="weigh-delta" data-testid="weigh-delta"
              >= {{ weighDelta ?? '?' }} {{ selectedItem.basisUnit }}</span
            >
          </template>
          <button
            type="button"
            class="quick-log-button adhoc-log"
            data-testid="adhoc-log"
            :disabled="adhocLogQuantity === null"
            @click="logAdhoc"
          >
            Log
          </button>
          <button
            type="button"
            class="weigh-toggle"
            data-testid="weigh-toggle"
            @click="weighMode = !weighMode"
          >
            {{ weighMode ? 'single amount' : 'from weights' }}
          </button>
        </div>
      </template>
    </section>

    <section class="day">
      <h2 class="subtitle">Today</h2>
      <RefreshIndicator v-if="refreshing" data-testid="refresh-indicator" />
      <ul v-if="dayRows.length > 0" class="entries">
        <li
          v-for="row in dayRows"
          :key="row.key"
          class="entry"
          :class="{ 'group-entry': row.groupId }"
          :data-testid="row.groupId ? 'group-row' : 'entry-row'"
        >
          <span class="entry-time">{{ row.time }}</span>
          <span class="entry-amount">{{ row.main }}</span>
          <span v-if="row.amounts" class="entry-amounts">{{ row.amounts }}</span>
          <button
            v-if="row.groupId"
            type="button"
            class="group-delete"
            data-testid="group-delete"
            :aria-label="`delete ${row.main}`"
            @click="removeGroup(row.groupId)"
          >
            &#x2715;
          </button>
          <button
            v-else
            type="button"
            class="entry-delete"
            :aria-label="`delete ${row.main} at ${row.time}`"
            @click="removeEntry(row.entryId)"
          >
            &#x2715;
          </button>
        </li>
      </ul>
      <p v-else-if="!error" class="empty">Nothing logged today.</p>
    </section>
  </main>
</template>

<style scoped>
/* Mobile-first logging surface: single column, big tap targets, minimal chrome. */
.home {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
  max-width: 26rem;
  margin: 0 auto;
  padding: 1rem 0;
}

.title {
  font-size: 1.1rem;
  font-weight: 600;
  color: var(--color-heading);
}

.subtitle {
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--color-heading);
}

.error {
  color: #c0392b;
}

.total {
  font-size: 3rem;
  font-weight: 700;
  line-height: 1.1;
  color: var(--color-heading);
}

.quick-log {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.75rem;
}

.quick-log-button {
  min-height: 4rem;
  font-size: 1.15rem;
  font-weight: 600;
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  background-color: var(--color-background-soft);
  color: var(--color-text);
  cursor: pointer;
}

.quick-log-button:disabled {
  opacity: 0.5;
  cursor: default;
}

.quick-log-button:not(:disabled):active {
  background-color: var(--color-background-mute);
}

.log-item,
.day {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.item-select {
  min-height: 3rem;
  padding: 0 0.75rem;
  font-size: 1rem;
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  background-color: var(--color-background-soft);
  color: var(--color-text);
}

.serving-buttons {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 0.75rem;
}

.serving-button {
  min-height: 3.5rem;
  font-size: 1rem;
}

.multiplier {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.multiplier-input {
  width: 5rem;
  min-height: 2.5rem;
  padding: 0 0.5rem;
  font-size: 1rem;
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  background-color: var(--color-background);
  color: var(--color-text);
}

.multiplier-hint {
  font-size: 0.85rem;
  opacity: 0.7;
}

.adhoc {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
}

.adhoc-field {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  font-size: 0.95rem;
}

.weigh-delta {
  font-size: 0.95rem;
  opacity: 0.8;
}

.adhoc-log {
  min-height: 2.5rem;
  padding: 0 1rem;
}

.weigh-toggle {
  border: none;
  background: transparent;
  color: var(--color-text);
  font-size: 0.85rem;
  opacity: 0.7;
  text-decoration: underline;
  cursor: pointer;
}

.entries {
  list-style: none;
  padding: 0;
  margin: 0;
}

.entry {
  display: flex;
  align-items: center;
  gap: 1rem;
  min-height: 3rem;
  border-bottom: 1px solid var(--color-border);
}

.group-entry {
  flex-wrap: wrap;
  padding: 0.25rem 0;
}

.entry-time {
  font-variant-numeric: tabular-nums;
  opacity: 0.7;
}

.entry-amount {
  flex: 1;
  font-weight: 600;
}

.entry-amounts {
  flex-basis: 100%;
  order: 4;
  font-size: 0.9rem;
  opacity: 0.85;
  padding-left: 3.4rem;
}

.entry-delete,
.group-delete {
  min-width: 2.75rem;
  min-height: 2.75rem;
  border: none;
  border-radius: 0.5rem;
  background: transparent;
  color: var(--color-text);
  opacity: 0.6;
  font-size: 1rem;
  cursor: pointer;
}

.entry-delete:active,
.group-delete:active {
  background-color: var(--color-background-mute);
}

.day-totals {
  font-size: 0.95rem;
  opacity: 0.85;
}

.empty {
  opacity: 0.6;
}
</style>
