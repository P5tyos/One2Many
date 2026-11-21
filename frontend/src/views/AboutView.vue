<script setup>
import logoHeader from '@/assets/brand/logo-header.png'
</script>

<template>
  <main class="page">
    <img :src="logoHeader" alt="One2Many" class="logo" />
    <h1>About One2Many</h1>
    <p class="todo">Coming soon</p>
  </main>
</template>

<style scoped>
.page {
  max-width: 720px;
  margin: 0 auto;
  padding: var(--space-6) var(--space-4);
}
.logo {
  width: 260px;
}
.todo {
  color: var(--text-muted);
}
</style>
