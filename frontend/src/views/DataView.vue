<script setup lang="ts">
import { ref } from 'vue'
import { api } from '@/api/client'
import type { components } from '@/api/schema'

type ExportFile = components['schemas']['ExportDto']

const error = ref('')
const notice = ref('')

const pendingFile = ref<ExportFile | null>(null)
const pendingName = ref('')
const busy = ref(false)

/** The server names the file (its clock, its zone); fall back if the header is absent. */
function filenameFrom(disposition: string | null): string {
  const match = disposition?.match(/filename="?([^";]+)"?/)
  return match?.[1] ?? 'idli-export.json'
}

async function downloadExport() {
  error.value = ''
  notice.value = ''
  try {
    const { data, response } = await api.GET('/api/export')
    if (!data) {
      error.value = 'export failed'
      return
    }
    // Pretty-printed for the human archiving it; import cares about the
    // JSON, not the bytes.
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = filenameFrom(response.headers.get('content-disposition'))
    link.click()
    URL.revokeObjectURL(link.href)
  } catch {
    error.value = 'backend unreachable'
  }
}

async function onFileChosen(event: Event) {
  error.value = ''
  notice.value = ''
  pendingFile.value = null
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // Clearing lets the same file be picked again after a cancel.
  input.value = ''
  if (!file) return
  let parsed: unknown
  try {
    parsed = JSON.parse(await file.text())
  } catch {
    error.value = 'not a JSON file'
    return
  }
  // Shape sanity only, enough to render the confirmation honestly — the
  // server is the validator. The null check is real: JSON.parse('null')
  // succeeds, and null is the one parse result property access throws on.
  const candidate = parsed as ExportFile | null
  if (candidate === null || !Array.isArray(candidate.metrics) || !Array.isArray(candidate.entries)) {
    error.value = 'not an idli export file'
    return
  }
  pendingFile.value = candidate
  pendingName.value = file.name
}

function cancelImport() {
  pendingFile.value = null
  pendingName.value = ''
}

async function confirmImport() {
  if (!pendingFile.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await api.POST('/api/import', {
      params: { query: { mode: 'replace' } },
      body: pendingFile.value,
    })
    // Captured before the !data check: the contract documents no error
    // responses, so inside that branch TS narrows the result away entirely.
    const status = result.response.status
    // The backend puts the rejection reason in `message`
    // (server.error.include-message) — on a failing restore it IS the diagnosis.
    const body = result.error as { message?: string } | undefined
    if (!result.data) {
      error.value = body?.message ? `import failed: ${body.message}` : `import failed (HTTP ${status})`
      return
    }
    notice.value = `import done: ${result.data.metrics ?? 0} metrics, ${result.data.entries ?? 0} entries`
    pendingFile.value = null
    pendingName.value = ''
  } catch {
    error.value = 'backend unreachable'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="data">
    <h1 class="title">Data</h1>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>

    <section class="block">
      <h2 class="heading">Export</h2>
      <p class="hint">
        Downloads everything — metrics and entries — as one JSON file. That file is the
        backup; keep it somewhere safe.
      </p>
      <button type="button" class="action" @click="downloadExport">Download export</button>
    </section>

    <section class="block">
      <h2 class="heading">Import</h2>
      <p class="hint">
        Restores from an export file. Importing <strong>replaces all data</strong> with the
        file's content.
      </p>
      <label class="action file-picker">
        Choose export file…
        <input class="file-input" type="file" accept=".json,application/json" @change="onFileChosen" />
      </label>

      <div v-if="pendingFile" class="confirm" data-testid="import-confirm">
        <p>
          <strong>{{ pendingName }}</strong> contains {{ pendingFile.metrics.length }} metrics
          and {{ pendingFile.entries.length }} entries (formatVersion
          {{ pendingFile.formatVersion }}).
        </p>
        <p class="warning">This replaces everything currently in the database.</p>
        <div class="confirm-buttons">
          <button type="button" class="action danger" :disabled="busy" @click="confirmImport">
            Replace everything
          </button>
          <button type="button" class="action" :disabled="busy" @click="cancelImport">
            Cancel
          </button>
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped>
/* Utility surface, desktop-shaped like authoring will be — but harmless on a phone. */
.data {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  max-width: 34rem;
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

.notice {
  color: var(--color-heading);
}

.block {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.hint {
  opacity: 0.8;
}

.action {
  align-self: flex-start;
  min-height: 2.75rem;
  padding: 0 1rem;
  display: inline-flex;
  align-items: center;
  font-size: 1rem;
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

.danger {
  border-color: #c0392b;
  color: #c0392b;
}

.file-input {
  /* The styled label is the control; the input itself stays reachable for
     keyboard and tests without painting the browser's default widget. */
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
}

.file-picker {
  position: relative;
}

.file-picker:focus-within {
  outline: 1px solid var(--color-border-hover);
}

.confirm {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding: 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
}

.warning {
  font-weight: 600;
  color: #c0392b;
}

.confirm-buttons {
  display: flex;
  gap: 0.75rem;
}
</style>
