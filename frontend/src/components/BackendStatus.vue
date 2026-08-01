<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/api/client'

const status = ref('…')

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
  <p class="backend-status">backend: {{ status }}</p>
</template>

<style scoped>
.backend-status {
  font-size: 12px;
  opacity: 0.6;
}
</style>
