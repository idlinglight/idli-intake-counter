<template>
  <p class="refresh-indicator" role="status">
    <span class="glyph" aria-hidden="true">&#x27F3;</span> refreshing
  </p>
</template>

<style scoped>
.refresh-indicator {
  font-size: 12px;
  opacity: 0.6;
}

.glyph {
  display: inline-block;
  animation: refresh-spin 1s linear infinite;
}

@keyframes refresh-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .glyph {
    animation: none;
  }
}
</style>
