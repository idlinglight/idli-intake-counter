<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '@/api/client'
import type { components } from '@/api/schema'
import { formatVolume } from '@/utils/volume'

type DayView = components['schemas']['DayViewDto']

const quickAmounts = [250, 500, 1000]

const waterMetricId = ref<number | null>(null)
const day = ref<DayView | null>(null)
const error = ref('')

/** Today as a local-time YYYY-MM-DD string. */
function todayLocalDate(): string {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const dayOfMonth = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${dayOfMonth}`
}

const waterEntries = computed(() =>
  (day.value?.entries ?? []).filter((entry) => entry.metricId === waterMetricId.value),
)

const waterTotal = computed(
  () =>
    (day.value?.totals ?? []).find((total) => total.metricId === waterMetricId.value)?.total ?? 0,
)

async function refreshDay() {
  const { data } = await api.GET('/api/days/{date}', {
    params: { path: { date: todayLocalDate() } },
  })
  day.value = data ?? null
}

async function load() {
  error.value = ''
  try {
    const { data: metrics } = await api.GET('/api/metrics')
    if (!metrics) {
      error.value = 'backend unreachable'
      return
    }
    const water = metrics.find((metric) => metric.name === 'water')
    if (water?.id === undefined) {
      error.value = 'no "water" metric configured'
      return
    }
    waterMetricId.value = water.id
    await refreshDay()
  } catch {
    error.value = 'backend unreachable'
  }
}

async function quickLog(amount: number) {
  if (waterMetricId.value === null) return
  error.value = ''
  try {
    await api.POST('/api/entries', {
      body: { metricId: waterMetricId.value, amount },
    })
    await refreshDay()
  } catch {
    error.value = 'backend unreachable'
  }
}

async function removeEntry(id: number | undefined) {
  if (id === undefined) return
  error.value = ''
  try {
    await api.DELETE('/api/entries/{id}', { params: { path: { id } } })
    await refreshDay()
  } catch {
    error.value = 'backend unreachable'
  }
}

/** HH:MM (local time) of an entry timestamp — a pure display concern. */
function entryTime(loggedAt: string | undefined): string {
  if (!loggedAt) return ''
  const date = new Date(loggedAt)
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

onMounted(load)
</script>

<template>
  <main class="water">
    <h1 class="title">Water</h1>

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <p class="total" data-testid="water-total">{{ formatVolume(waterTotal) }}</p>

    <div class="quick-log">
      <button
        v-for="amount in quickAmounts"
        :key="amount"
        type="button"
        class="quick-log-button"
        :disabled="waterMetricId === null"
        @click="quickLog(amount)"
      >
        +{{ amount }} mL
      </button>
    </div>

    <ul v-if="waterEntries.length > 0" class="entries">
      <li v-for="entry in waterEntries" :key="entry.id" class="entry">
        <span class="entry-time">{{ entryTime(entry.loggedAt) }}</span>
        <span class="entry-amount">{{ formatVolume(entry.amount ?? 0) }}</span>
        <button
          type="button"
          class="entry-delete"
          :aria-label="`delete ${formatVolume(entry.amount ?? 0)} at ${entryTime(entry.loggedAt)}`"
          @click="removeEntry(entry.id)"
        >
          &#x2715;
        </button>
      </li>
    </ul>
    <p v-else-if="!error" class="empty">Nothing logged today.</p>
  </main>
</template>

<style scoped>
/* Mobile-first logging surface: single column, big tap targets, minimal chrome. */
.water {
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

.entry-time {
  font-variant-numeric: tabular-nums;
  opacity: 0.7;
}

.entry-amount {
  flex: 1;
  font-weight: 600;
}

.entry-delete {
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

.entry-delete:active {
  background-color: var(--color-background-mute);
}

.empty {
  opacity: 0.6;
}
</style>
