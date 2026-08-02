<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, RouterView } from 'vue-router'
import BackendStatus from './components/BackendStatus.vue'
import LoginView from './views/LoginView.vue'
import { useAuthStore } from './stores/auth'

const auth = useAuthStore()

const logoutError = ref('')

// This first GET also makes the server issue the XSRF-TOKEN cookie
// that the (mutating) login POST needs.
onMounted(() => auth.check())

// logout() returns false when the session may still be alive server-side
// (unreachable backend, missing CSRF token, any non-2xx). Swallowing that
// would leave the button looking inert while the user believes they are out.
async function logOut() {
  logoutError.value = (await auth.logout()) ? '' : 'logout failed — you are still logged in'
}
</script>

<template>
  <header>
    <img alt="Vue logo" class="logo" src="@/assets/logo.svg" width="125" height="125" />

    <div class="wrapper">
      <nav v-if="auth.authenticated">
        <RouterLink to="/">Home</RouterLink>
        <RouterLink to="/authoring">Authoring</RouterLink>
        <RouterLink to="/data">Data</RouterLink>
        <RouterLink to="/about">About</RouterLink>
      </nav>

      <div class="status-row">
        <BackendStatus />
        <button v-if="auth.authenticated" type="button" class="logout" @click="logOut">
          log out
        </button>
      </div>

      <!-- Guarded by authenticated: a later logout by any route clears it. -->
      <p v-if="logoutError && auth.authenticated" class="logout-error" role="alert">
        {{ logoutError }}
      </p>
    </div>
  </header>

  <!-- Nothing but the header until the session check has answered. -->
  <template v-if="auth.checked">
    <RouterView v-if="auth.authenticated" />
    <LoginView v-else />
  </template>
</template>

<style scoped>
header {
  line-height: 1.5;
  max-height: 100vh;
}

.logo {
  display: block;
  margin: 0 auto 2rem;
}

nav {
  width: 100%;
  font-size: 12px;
  text-align: center;
  margin-top: 2rem;
}

nav a.router-link-exact-active {
  color: var(--color-text);
}

nav a.router-link-exact-active:hover {
  background-color: transparent;
}

nav a {
  display: inline-block;
  padding: 0 1rem;
  border-left: 1px solid var(--color-border);
}

nav a:first-of-type {
  border: 0;
}

.status-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
}

.logout {
  padding: 0.25rem 0.5rem;
  border: none;
  background: transparent;
  color: var(--color-text);
  font-size: 12px;
  opacity: 0.6;
  cursor: pointer;
}

.logout:hover {
  opacity: 1;
}

.logout-error {
  margin-top: 0.5rem;
  font-size: 12px;
  text-align: center;
  color: #c0392b;
}

@media (min-width: 1024px) {
  header {
    display: flex;
    place-items: center;
    padding-right: calc(var(--section-gap) / 2);
  }

  .logo {
    margin: 0 2rem 0 0;
  }

  header .wrapper {
    display: flex;
    place-items: flex-start;
    flex-wrap: wrap;
  }

  nav {
    text-align: left;
    margin-left: -1rem;
    font-size: 1rem;

    padding: 1rem 0;
    margin-top: 1rem;
  }

  .status-row {
    justify-content: flex-start;
  }
}
</style>
