<script setup>
// "¿Qué es One2Many?": linked from the bottom of every phone page.
// Short sections + a menu at the top that jumps to each one.
import logoHeader from '@/assets/brand/logo-header.png'

const CREATOR_LINKEDIN = 'https://www.linkedin.com/in/gergely-horvath-p5tyos'
const GITHUB_REPO = 'https://github.com/P5tyos/one2many'

const SECTIONS = [
  { id: 'que-es', label: 'Qué es' },
  { id: 'como', label: 'Cómo funciona' },
  { id: 'privacidad', label: 'Privacidad' },
  { id: 'referencias', label: 'Referencias' },
]

function jumpTo(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
function back() {
  // came from a phone page: go back to it (at the same scroll position)
  if (window.history.length > 1) window.history.back()
}
</script>

<template>
  <main class="about">
    <header class="top">
      <img :src="logoHeader" alt="One2Many" class="logo" />
      <p class="tagline">Find your people.</p>
    </header>

    <!-- menu: jump to a section (scrolls away with the page) -->
    <nav class="menu">
      <button v-for="s in SECTIONS" :key="s.id" @click="jumpTo(s.id)">{{ s.label }}</button>
    </nav>

    <section id="que-es" class="card">
      <h2>Qué es</h2>
      <p>
        En un evento quieres estar presente: escuchar las charlas, tomar un café y hablar con la gente,
        no pasarte el rato con el móvil buscando a estas personas en LinkedIn®. Pero tampoco quieres irte
        sin conectar, o al menos sin que los demás puedan encontrarte.
      </p>
      <p>
        One2Many lo resuelve con un solo escaneo: apareces en la lista del evento y ves a todos los que
        se han unido, con su nombre y un enlace a su LinkedIn. Así conectas con quien quieras, cuando quieras,
        incluso con aquel que estuvo ocupado todo el rato.
      </p>
      <p class="muted small">
        Para encuentros de estudiantes, ferias de empleo, meetups, charlas, congresos y mucho más.
      </p>
    </section>

    <section id="como" class="card">
      <h2>Cómo funciona</h2>
      <ol class="steps">
        <li><span class="num">1</span>Enseña tu QR de LinkedIn al lector de la estación.</li>
        <li><span class="num">2</span>Escanea tu código personal con la cámara del móvil.</li>
        <li><span class="num">3</span>Escribe tu nombre y una frase, y conecta con quien quieras.</li>
      </ol>
    </section>

    <section id="privacidad" class="card">
      <h2>Privacidad</h2>
      <ul class="facts">
        <li><b>Sin cuentas ni apps.</b> Solo tu navegador.</li>
        <li><b>Solo lo necesario:</b> tu LinkedIn, tu nombre y tu frase, visibles para este evento.</li>
        <li><b>Nada se guarda después.</b> Solo un registro de moderación durante 7 días.</li>
        <li><b>Tu página funciona solo en tu móvil.</b> Sin publicidad ni seguimiento.</li>
      </ul>
    </section>

    <section id="referencias" class="card">
      <h2>Referencias</h2>
      <p>
        Creado por <b>Gergely (Greg) Horvath</b>, estudiante de DAW en Vigo, para un encuentro de estudiantes de
        DAM y DAW.
      </p>
      <div class="links">
        <a :href="CREATOR_LINKEDIN" target="_blank" rel="noopener" class="btn btn-primary">LinkedIn ↗</a>
        <a :href="GITHUB_REPO" target="_blank" rel="noopener" class="btn btn-secondary">GitHub ↗</a>
      </div>
      <p class="muted small disclaimer">
        One2Many es un proyecto independiente, sin relación con LinkedIn Corporation ni respaldado por
        ella. LinkedIn® es una marca registrada de LinkedIn Corporation.
      </p>
    </section>

    <button class="btn btn-secondary back" @click="back">← Volver</button>
  </main>
</template>

<style scoped>
.about {
  max-width: 560px;
  margin: 0 auto;
  padding: var(--space-5) var(--space-4) var(--space-7);
  display: grid;
  gap: var(--space-3);
}
.top {
  display: grid;
  justify-items: center;
  gap: var(--space-1);
}
.logo {
  width: 180px;
}
.tagline {
  margin: 0;
  font-weight: 700;
  color: var(--text-muted);
}

/* menu chips, sticky at the top */
.menu {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-2) 0;
  background: var(--bg);
}
.menu button {
  padding: var(--space-1) var(--space-3);
  border: 0;
  border-radius: var(--radius-sm);
  background: var(--surface);
  box-shadow: var(--shadow);
  color: var(--text);
  font-weight: 700;
  font-size: 0.9rem;
  cursor: pointer;
}

.card {
  padding: var(--space-4);
  scroll-margin-top: var(--space-3);
}
h2 {
  margin: 0 0 var(--space-2);
  font-size: 1.15rem;
}
p {
  margin: 0 0 var(--space-2);
  line-height: 1.5;
}
p:last-child {
  margin-bottom: 0;
}
.muted {
  color: var(--text-muted);
}
.small {
  font-size: 0.88rem;
}
.steps,
.facts {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: var(--space-2);
  line-height: 1.45;
}
.steps li {
  display: flex;
  gap: var(--space-3);
  align-items: center;
}
.num {
  flex: none;
  width: 26px;
  height: 26px;
  border-radius: var(--radius-sm);
  display: grid;
  place-items: center;
  background: var(--yellow-soft);
  font-weight: 800;
}
.facts li {
  padding-left: var(--space-3);
  border-left: 3px solid var(--teal-soft);
}
.links {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin: var(--space-3) 0;
}
.disclaimer {
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid #eef1f7;
}
.back {
  justify-self: center;
  margin-top: var(--space-2);
}
</style>
