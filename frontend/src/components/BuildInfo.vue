<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/api/client'

const status = ref('…')

// Baked into the bundle at image build (VITE_GIT_SHA build-arg) — a stale
// cached bundle keeps showing its own sha, which is exactly what makes a
// not-yet-served deploy visible. Dev builds have no sha.
const frontendSha = import.meta.env.VITE_GIT_SHA?.slice(0, 7) ?? 'dev'

onMounted(async () => {
  try {
    const { data } = await api.GET('/api/hello')
    status.value = data ? `${data.message} @ ${data.gitSha}` : 'unreachable'
  } catch {
    status.value = 'unreachable'
  }
})
</script>

<template>
  <p class="build-info">backend: {{ status }} · frontend: {{ frontendSha }}</p>
</template>

<style scoped>
.build-info {
  font-size: 12px;
  opacity: 0.6;
}
</style>
