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

// Items without servings have nothing tappable — they never appear here.
const loggableItems = computed(() => items.value.filter((item) => (item.servings ?? []).length > 0))
const selectedItem = computed(
  () => loggableItems.value.find((item) => item.id === selectedItemId.value) ?? null,
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
async function refreshDay() {
  try {
    const { data } = await api.GET('/api/days/today')
    if (!data) {
      error.value = 'could not load today'
      return
    }
    day.value = data
  } catch {
    error.value = 'backend unreachable'
  }
}

async function load() {
  error.value = ''
  try {
    const [metricsResult, itemsResult] = await Promise.all([
      api.GET('/api/metrics'),
      api.GET('/api/items'),
    ])
    if (!metricsResult.data) {
      error.value = 'backend unreachable'
      return
    }
    metrics.value = metricsResult.data
    // Items only power the serving surface — a failed items fetch must not
    // take down water logging and the day list with it.
    items.value = itemsResult.data ?? []
    if (waterMetric.value?.id === undefined) {
      error.value = 'no "water" metric configured'
      return
    }
    await refreshDay()
    if (!itemsResult.data) {
      error.value = 'could not load items'
    }
  } catch {
    error.value = 'backend unreachable'
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

    <section v-if="loggableItems.length > 0" class="log-item" data-testid="log-item-section">
      <h2 class="subtitle">Log item</h2>
      <select v-model.number="selectedItemId" class="item-select" data-testid="item-select">
        <option :value="null">log an item…</option>
        <option v-for="item in loggableItems" :key="item.id" :value="item.id">
          {{ item.name }}
        </option>
      </select>
      <template v-if="selectedItem">
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
          <span class="multiplier-hint">applies to the next tap</span>
        </label>
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
