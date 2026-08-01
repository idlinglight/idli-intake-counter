<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const password = ref('')
const error = ref('')
const submitting = ref(false)

async function submit() {
  if (submitting.value) return
  error.value = ''
  submitting.value = true
  const result = await auth.login(password.value)
  submitting.value = false
  if (result === 'wrong-password') {
    error.value = 'wrong password'
    password.value = ''
  } else if (result === 'unreachable') {
    error.value = 'backend unreachable'
  } else if (result === 'no-session') {
    // The password was right; the browser refused the Secure session cookie.
    error.value = 'the browser did not keep the session — open this page over https'
  }
  // On success App.vue swaps this surface for the app — nothing to do here.
}
</script>

<template>
  <main class="login">
    <h1 class="title">Log in</h1>

    <form class="form" @submit.prevent="submit">
      <label class="label" for="password">Password</label>
      <input
        id="password"
        v-model="password"
        class="password"
        type="password"
        name="password"
        autocomplete="current-password"
        required
      />

      <p v-if="error" class="error" role="alert">{{ error }}</p>

      <button type="submit" class="submit" :disabled="submitting">Log in</button>
    </form>
  </main>
</template>

<style scoped>
/* Mobile-first, like the logging surface: single column, big tap targets. */
.login {
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

.form {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.label {
  font-size: 0.9rem;
  opacity: 0.8;
}

.password {
  min-height: 3.25rem;
  padding: 0 1rem;
  font-size: 1.15rem;
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  background-color: var(--color-background-soft);
  color: var(--color-text);
}

.error {
  color: #c0392b;
}

.submit {
  min-height: 4rem;
  font-size: 1.15rem;
  font-weight: 600;
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  background-color: var(--color-background-soft);
  color: var(--color-text);
  cursor: pointer;
}

.submit:disabled {
  opacity: 0.5;
  cursor: default;
}

.submit:not(:disabled):active {
  background-color: var(--color-background-mute);
}
</style>
