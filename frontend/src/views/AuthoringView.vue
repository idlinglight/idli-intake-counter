<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '@/api/client'
import type { components } from '@/api/schema'
import { formatAmount } from '@/utils/format'
import ItemForm from '@/components/ItemForm.vue'

type Metric = components['schemas']['MetricDto']
type Item = components['schemas']['ItemDto']
type Serving = components['schemas']['ServingDto']
type ItemPayload = components['schemas']['ItemRequest']

const metrics = ref<Metric[]>([])
const items = ref<Item[]>([])
const error = ref('')
const busy = ref(false)

const metricName = ref('')
const metricUnit = ref('')

const itemFormOpen = ref(false)
const editingItem = ref<Item | null>(null)

// Two-step inline confirm: deleting an item takes its servings with it.
const armedDeleteItemId = ref<number | null>(null)

const servingFormItemId = ref<number | null>(null)
const editingServingId = ref<number | null>(null)
const servingName = ref('')
const servingQuantity = ref<number | null>(null)

const metricById = computed(() => new Map(metrics.value.map((metric) => [metric.id, metric])))

async function refresh() {
  try {
    const [metricsResult, itemsResult] = await Promise.all([
      api.GET('/api/metrics'),
      api.GET('/api/items'),
    ])
    if (!metricsResult.data || !itemsResult.data) {
      error.value = 'backend unreachable'
      return
    }
    metrics.value = metricsResult.data
    // Newest first: a just-created item lands next to the form that made it,
    // where adding servings continues. Sorted here, not in the backend — this
    // is a view choice of this surface, and Home's picker keeps its own order.
    items.value = [...itemsResult.data].sort((a, b) => (b.id ?? 0) - (a.id ?? 0))
    // A refresh that succeeded is the freshest truth — clear stale banners
    // (e.g. a 404 from a delete that raced: the row is gone, all is well).
    error.value = ''
  } catch {
    error.value = 'backend unreachable'
  }
}

/** The backend's rejection reason (include-message) beats a bare status code. */
function failureText(action: string, result: { error?: unknown; response: Response }): string {
  const body = result.error as { message?: string } | undefined
  return body?.message ? `${action} failed: ${body.message}` : `${action} failed (HTTP ${result.response.status})`
}

type MutationResult = { data?: unknown; error?: unknown; response: Response }

/**
 * The one envelope every mutation shares: error/busy bookkeeping, failure
 * text, network-error catch, refresh. Deletes differ twice: success is a
 * bodyless 204 (so failure is result.error, not missing data), and a failure
 * still refreshes — a 404 means the row is already gone server-side.
 */
async function mutate(
  action: string,
  call: () => Promise<MutationResult>,
  { onSuccess, isDelete = false }: { onSuccess?: () => void; isDelete?: boolean } = {},
) {
  error.value = ''
  busy.value = true
  try {
    const result = await call()
    const failed = isDelete ? Boolean(result.error) : !result.data
    if (failed && !isDelete) {
      error.value = failureText(action, result)
      return
    }
    if (!failed) {
      onSuccess?.()
    }
    await refresh()
    // After the refresh, so its success-clear cannot wipe an honest failure.
    if (failed) {
      error.value = failureText(action, result)
    }
  } catch {
    error.value = 'backend unreachable'
  } finally {
    busy.value = false
  }
}

async function createMetric() {
  await mutate(
    'creating metric',
    () =>
      api.POST('/api/metrics', {
        body: { name: metricName.value, canonicalUnit: metricUnit.value },
      }),
    {
      onSuccess: () => {
        metricName.value = ''
        metricUnit.value = ''
      },
    },
  )
}

function openItemForm(item: Item | null) {
  error.value = ''
  itemFormOpen.value = true
  editingItem.value = item
}

function closeItemForm() {
  itemFormOpen.value = false
  editingItem.value = null
}

async function submitItem(payload: ItemPayload) {
  // Editing an item without an id cannot be addressed; creating needs none.
  const editId = editingItem.value?.id
  if (editingItem.value !== null && editId === undefined) return
  await mutate(
    editId === undefined ? 'creating item' : 'saving item',
    () =>
      editId === undefined
        ? api.POST('/api/items', { body: payload })
        : api.PUT('/api/items/{id}', { params: { path: { id: editId } }, body: payload }),
    { onSuccess: closeItemForm },
  )
}

async function removeItem(item: Item) {
  const id = item.id
  if (id === undefined) return
  if (armedDeleteItemId.value !== id) {
    armedDeleteItemId.value = id
    return
  }
  armedDeleteItemId.value = null
  await mutate('deleting item', () => api.DELETE('/api/items/{id}', { params: { path: { id } } }), {
    isDelete: true,
  })
}

function openServingForm(item: Item, serving?: Serving) {
  error.value = ''
  servingFormItemId.value = item.id ?? null
  editingServingId.value = serving?.id ?? null
  servingName.value = serving?.name ?? ''
  servingQuantity.value = serving?.quantity ?? null
}

function closeServingForm() {
  servingFormItemId.value = null
  editingServingId.value = null
  servingName.value = ''
  servingQuantity.value = null
}

async function submitServing() {
  const itemId = servingFormItemId.value
  // Whole numbers only — the backend rejects fractions rather than rounding.
  const quantity = servingQuantity.value
  if (itemId === null || !servingName.value || quantity === null || !Number.isInteger(quantity) || quantity <= 0)
    return
  const body = { name: servingName.value, quantity }
  const servingId = editingServingId.value
  await mutate(
    'saving serving',
    () =>
      servingId === null
        ? api.POST('/api/items/{id}/servings', { params: { path: { id: itemId } }, body })
        : api.PUT('/api/servings/{id}', { params: { path: { id: servingId } }, body }),
    { onSuccess: closeServingForm },
  )
}

async function removeServing(serving: Serving) {
  const id = serving.id
  if (id === undefined) return
  await mutate(
    'deleting serving',
    () => api.DELETE('/api/servings/{id}', { params: { path: { id } } }),
    { isDelete: true },
  )
}

/** "energy 2281 kJ, protein 30 g" — the composition as entered, per basis. */
function compositionLine(item: Item): string {
  return (item.amounts ?? [])
    .map((amount) => {
      const metric = metricById.value.get(amount.metricId)
      return metric
        ? `${metric.name} ${formatAmount(amount.amount ?? 0, metric.canonicalUnit ?? '')}`
        : `metric #${amount.metricId}`
    })
    .join(', ')
}

// Display-only mirror of what logging will snapshot later: composition scaled
// to the serving's quantity, rounded to integer canonical units.
function servingComputedLine(item: Item, serving: Serving): string {
  const basis = item.basisAmount ?? 0
  if (basis <= 0) return ''
  return (item.amounts ?? [])
    .map((amount) => {
      const metric = metricById.value.get(amount.metricId)
      const value = Math.round(((amount.amount ?? 0) * (serving.quantity ?? 0)) / basis)
      return metric
        ? `${metric.name} ${formatAmount(value, metric.canonicalUnit ?? '')}`
        : `metric #${amount.metricId}`
    })
    .join(', ')
}

onMounted(refresh)
</script>

<template>
  <main class="authoring">
    <h1 class="title">Authoring</h1>

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <section class="block">
      <h2 class="heading">Metrics</h2>
      <ul class="metric-list">
        <li v-for="metric in metrics" :key="metric.id" class="metric-row">
          <span class="metric-name">{{ metric.name }}</span>
          <span class="metric-unit">{{ metric.canonicalUnit }}</span>
        </li>
      </ul>
      <form class="inline-form" @submit.prevent="createMetric">
        <input
          v-model.trim="metricName"
          data-testid="metric-name"
          placeholder="name, e.g. protein"
          required
        />
        <input
          v-model.trim="metricUnit"
          data-testid="metric-unit"
          class="short"
          placeholder="unit, e.g. g"
          required
        />
        <button type="submit" class="action" data-testid="create-metric" :disabled="busy">
          Add metric
        </button>
      </form>
      <p class="hint">
        The unit is canonical and fixed once created; amounts are whole numbers, so pick it
        fine-grained enough (mg rather than g if halves will matter).
      </p>
    </section>

    <section class="block">
      <h2 class="heading">Items</h2>

      <button
        v-if="!itemFormOpen"
        type="button"
        class="action"
        data-testid="new-item"
        :disabled="metrics.length === 0"
        @click="openItemForm(null)"
      >
        New item…
      </button>
      <p v-if="metrics.length === 0" class="hint">
        Create a metric first — an item's amounts are amounts of metrics.
      </p>
      <ItemForm
        v-if="itemFormOpen && editingItem === null"
        :metrics="metrics"
        :busy="busy"
        @submit="submitItem"
        @cancel="closeItemForm"
      />

      <article v-for="item in items" :key="item.id" class="item" data-testid="item">
        <ItemForm
          v-if="editingItem?.id === item.id"
          :key="item.id"
          :metrics="metrics"
          :initial="item"
          :busy="busy"
          @submit="submitItem"
          @cancel="closeItemForm"
        />
        <template v-else>
          <div class="item-head">
            <h3 class="item-name">{{ item.name }}</h3>
            <span class="item-basis">per {{ item.basisAmount }} {{ item.basisUnit }}</span>
            <button type="button" class="ghost" data-testid="edit-item" @click="openItemForm(item)">
              edit
            </button>
            <button
              type="button"
              class="ghost danger-text"
              data-testid="delete-item"
              :disabled="busy"
              @click="removeItem(item)"
            >
              {{ armedDeleteItemId === item.id ? 'really delete? (takes its servings)' : 'delete' }}
            </button>
          </div>
          <p class="composition">{{ compositionLine(item) }}</p>

          <ul v-if="(item.servings ?? []).length > 0" class="servings">
            <li v-for="serving in item.servings" :key="serving.id" class="serving" data-testid="serving">
              <span class="serving-name">{{ serving.name }}</span>
              <span class="serving-quantity">{{ serving.quantity }} {{ item.basisUnit }}</span>
              <span class="serving-computed">{{ servingComputedLine(item, serving) }}</span>
              <button
                type="button"
                class="ghost"
                data-testid="edit-serving"
                @click="openServingForm(item, serving)"
              >
                edit
              </button>
              <button
                type="button"
                class="ghost danger-text"
                data-testid="delete-serving"
                :disabled="busy"
                @click="removeServing(serving)"
              >
                delete
              </button>
            </li>
          </ul>

          <form
            v-if="servingFormItemId === item.id"
            class="inline-form"
            data-testid="serving-form"
            @submit.prevent="submitServing"
          >
            <input
              v-model.trim="servingName"
              data-testid="serving-name"
              placeholder="name, e.g. whole bar"
              required
            />
            <input
              v-model.number="servingQuantity"
              data-testid="serving-quantity"
              class="short"
              type="number"
              min="1"
              required
            />
            <span class="unit">{{ item.basisUnit }}</span>
            <button type="submit" class="action" data-testid="serving-submit" :disabled="busy">
              {{ editingServingId === null ? 'Add serving' : 'Save serving' }}
            </button>
            <button type="button" class="action" :disabled="busy" @click="closeServingForm">
              Cancel
            </button>
          </form>
          <button
            v-else
            type="button"
            class="ghost"
            data-testid="add-serving"
            @click="openServingForm(item)"
          >
            + add serving
          </button>
        </template>
      </article>
    </section>
  </main>
</template>

<style scoped>
/* Desktop-shaped authoring surface: dense forms, keyboard-first — still
   usable on a phone, just not optimized for it. */
.authoring {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  max-width: 44rem;
  margin: 0 auto;
  padding: 1rem 0;
}

.title {
  font-size: 1.1rem;
  font-weight: 600;
  color: var(--color-heading);
}

.heading {
  font-size: 1rem;
  font-weight: 600;
  color: var(--color-heading);
}

.error {
  color: #c0392b;
}

.block {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.hint {
  opacity: 0.8;
  font-size: 0.9rem;
}

.metric-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.metric-row {
  display: flex;
  gap: 1rem;
  min-height: 2rem;
  align-items: center;
  border-bottom: 1px solid var(--color-border);
}

.metric-name {
  flex: 1;
  font-weight: 600;
}

.metric-unit {
  opacity: 0.7;
}

.inline-form {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}

input {
  min-height: 2.25rem;
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
  opacity: 0.7;
}

.action {
  min-height: 2.5rem;
  padding: 0 1rem;
  display: inline-flex;
  align-items: center;
  align-self: flex-start;
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

.action:not(:disabled):active {
  background-color: var(--color-background-mute);
}

.ghost {
  padding: 0.25rem 0.5rem;
  border: none;
  background: transparent;
  color: var(--color-text);
  opacity: 0.7;
  font-size: 0.9rem;
  cursor: pointer;
  align-self: flex-start;
}

.ghost:hover {
  opacity: 1;
}

/* Disabled while a mutation is in flight — a double-click on delete must not
   fire a second request that 404s and reports a phantom failure. */
.ghost:disabled {
  opacity: 0.4;
  cursor: default;
}

.danger-text {
  color: #c0392b;
}

.item {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding: 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
}

.item-head {
  display: flex;
  align-items: baseline;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.item-name {
  font-size: 1rem;
  font-weight: 600;
  color: var(--color-heading);
}

.item-basis {
  opacity: 0.7;
}

.composition {
  font-size: 0.95rem;
}

.servings {
  list-style: none;
  padding: 0;
  margin: 0;
}

.serving {
  display: flex;
  align-items: baseline;
  gap: 0.75rem;
  min-height: 2rem;
  border-bottom: 1px solid var(--color-border);
  flex-wrap: wrap;
}

.serving-name {
  font-weight: 600;
}

.serving-quantity {
  opacity: 0.7;
}

.serving-computed {
  flex: 1;
  font-size: 0.9rem;
  opacity: 0.85;
}
</style>
