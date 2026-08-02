<script setup lang="ts">
import { ref } from 'vue'
import type { components } from '@/api/schema'

type Metric = components['schemas']['MetricDto']
type Item = components['schemas']['ItemDto']
type ItemPayload = components['schemas']['ItemRequest']

type AmountRow = { metricId: number | null; amount: number | null }

const props = defineProps<{
  metrics: Metric[]
  /** When set, the form edits this item (prefilled); otherwise it creates. */
  initial?: Item | null
  busy?: boolean
}>()

const emit = defineEmits<{ submit: [payload: ItemPayload]; cancel: [] }>()

const name = ref(props.initial?.name ?? '')
const basisAmount = ref<number | null>(props.initial?.basisAmount ?? 100)
const basisUnit = ref(props.initial?.basisUnit ?? 'g')
const rows = ref<AmountRow[]>(
  props.initial?.amounts?.map((amount) => ({
    metricId: amount.metricId ?? null,
    amount: amount.amount ?? null,
  })) ?? [{ metricId: null, amount: null }],
)
const formError = ref('')

/** Each metric may appear once; a row keeps its own selection selectable. */
function availableMetrics(row: AmountRow): Metric[] {
  return props.metrics.filter(
    (metric) =>
      metric.id === row.metricId ||
      !rows.value.some((other) => other !== row && other.metricId === metric.id),
  )
}

function unitOf(row: AmountRow): string {
  return props.metrics.find((metric) => metric.id === row.metricId)?.canonicalUnit ?? ''
}

function addRow() {
  rows.value.push({ metricId: null, amount: null })
}

function removeRow(index: number) {
  rows.value.splice(index, 1)
}

// jsdom (and Enter-key submits in odd states) can bypass browser validation,
// so the guard here is the one that counts.
function submit() {
  formError.value = ''
  const complete = rows.value.filter((row) => row.metricId !== null && (row.amount ?? 0) > 0)
  if (
    !name.value ||
    (basisAmount.value ?? 0) <= 0 ||
    !basisUnit.value ||
    complete.length === 0 ||
    complete.length !== rows.value.length
  ) {
    formError.value = 'name, basis and at least one complete amount row are required'
    return
  }
  emit('submit', {
    name: name.value,
    basisAmount: basisAmount.value as number,
    basisUnit: basisUnit.value,
    amounts: complete.map((row) => ({ metricId: row.metricId as number, amount: row.amount as number })),
  })
}
</script>

<template>
  <form class="item-form" data-testid="item-form" @submit.prevent="submit">
    <p v-if="formError" class="error" role="alert">{{ formError }}</p>

    <label class="field">
      Name
      <input v-model.trim="name" data-testid="item-name" placeholder="protein bar: PS White Choc" required />
    </label>

    <div class="basis">
      <label class="field">
        Amounts are per
        <input
          v-model.number="basisAmount"
          data-testid="basis-amount"
          class="short"
          type="number"
          min="1"
          required
        />
      </label>
      <label class="field">
        <span class="visually-hidden">basis unit</span>
        <input
          v-model.trim="basisUnit"
          data-testid="basis-unit"
          class="short"
          list="basis-units"
          placeholder="g"
          required
        />
      </label>
      <datalist id="basis-units">
        <option value="g" />
        <option value="mL" />
        <option value="piece" />
      </datalist>
    </div>

    <div v-for="(row, index) in rows" :key="index" class="amount-row" data-testid="amount-row">
      <select v-model.number="row.metricId" data-testid="amount-metric" required>
        <option disabled :value="null">metric…</option>
        <option v-for="metric in availableMetrics(row)" :key="metric.id" :value="metric.id">
          {{ metric.name }}
        </option>
      </select>
      <input
        v-model.number="row.amount"
        data-testid="amount-value"
        class="short"
        type="number"
        min="1"
        required
      />
      <span class="unit">{{ unitOf(row) }}</span>
      <button
        v-if="rows.length > 1"
        type="button"
        class="ghost"
        :aria-label="`remove amount row ${index + 1}`"
        @click="removeRow(index)"
      >
        &#x2715;
      </button>
    </div>
    <button type="button" class="ghost" data-testid="add-amount-row" @click="addRow">
      + add metric amount
    </button>

    <div class="form-buttons">
      <button type="submit" class="action" data-testid="item-form-submit" :disabled="busy">
        {{ props.initial ? 'Save item' : 'Create item' }}
      </button>
      <button type="button" class="action" data-testid="item-form-cancel" :disabled="busy" @click="emit('cancel')">
        Cancel
      </button>
    </div>
  </form>
</template>

<style scoped>
.item-form {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding: 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
}

.error {
  color: #c0392b;
}

.field {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
}

.basis {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.amount-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

input,
select {
  min-height: 2rem;
  padding: 0 0.5rem;
  border: 1px solid var(--color-border);
  border-radius: 0.375rem;
  background-color: var(--color-background);
  color: var(--color-text);
  font-size: 0.95rem;
}

.short {
  width: 6.5rem;
}

.unit {
  min-width: 2rem;
  opacity: 0.7;
}

.ghost {
  align-self: flex-start;
  padding: 0.25rem 0.5rem;
  border: none;
  background: transparent;
  color: var(--color-text);
  opacity: 0.7;
  cursor: pointer;
}

.ghost:hover {
  opacity: 1;
}

.form-buttons {
  display: flex;
  gap: 0.75rem;
}

.action {
  min-height: 2.5rem;
  padding: 0 1rem;
  display: inline-flex;
  align-items: center;
  font-size: 0.95rem;
  font-weight: 600;
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  background-color: var(--color-background-soft);
  color: var(--color-text);
  cursor: pointer;
}

.action:disabled {
  opacity: 0.5;
  cursor: default;
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
}
</style>
