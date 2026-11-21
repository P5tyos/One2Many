<script setup>
// ─────────────────────────────────────────────────────────────────────────────
// STATION SCREEN — the monitor facing the queue.
//
// It's a small "state machine": the screen is always in exactly one state,
// and events (a scan, a key press, a timer) move it to the next one.
//
//   idle ──scan──▶ processing ──ISSUED──▶ ready ⇄ revealing (while SPACE/mouse held)
//                      │                    │
//                      └─AWAITING_CONFIRMATION─▶ waiting ──CONFIRMED──▶ ready
//                                                         └─REJECTED/EXPIRED──▶ message
//   message ──after a few seconds──▶ idle
// ─────────────────────────────────────────────────────────────────────────────
import { ref, computed, nextTick, onMounted, onBeforeUnmount, watch } from 'vue'
import QRCode from 'qrcode'
import { api } from '@/api'
import logoHeader from '@/assets/brand/logo-header.png'
import logoQr from '@/assets/brand/logo-symbol-qr.png'
import bgBlobs from '@/assets/brand/bg-blobs.png'

// ---- settings ----
const MAX_REVEALS = 3 // how many times the QR can be shown per person
const REVEAL_WINDOW_MS = 3 * 60 * 1000 // ...within 3 minutes
const MESSAGE_MS = 4000 // how long info/error messages stay up
const HEARTBEAT_MS = 4000 // mirror: tell the dashboard "still here" this often
const MANUAL_POLL_MS = 1500 // dashboard's manual add: how often the station checks for a link
const OPENED_POLL_MS = 1500 // how often the station asks "has their phone opened it?"
const SCANNER_MAX_GAP_MS = 150 // scanner keys come fast (Shift+key for : / ? adds delay); humans are slower
const SCANNER_MIN_LENGTH = 20 // a LinkedIn link is always longer than this
const RESCAN_POLL_MS = 1500
const STATS_POLL_MS = 5000
const COUNTER_FROM = 10 // show "X people joined" only above this

// ---- state ----
const state = ref('idle') // idle | processing | ready | revealing | waiting | message
const message = ref({ text: '', kind: 'info', detail: '' }) // kind: info | ok | error
const qrImage = ref(null) // the personal QR as an image (data URL)
const revealsUsed = ref(0)
const secondsLeft = ref(0)
const hint = ref('') // short flash hint, e.g. "show your code first"

const event = ref(null)
const joined = ref(0)
const needsLogin = ref(false)
// QR links are built from this page's address: on localhost no phone can open them
// (only in the built app: while developing on localhost:5173 it would just be noise)
const onLocalhost =
  import.meta.env.PROD && ['localhost', '127.0.0.1', '[::1]'].includes(window.location.hostname)

const manualOpen = ref(false)
const manualUrl = ref('')
const manualInput = ref(null) // the <input>, so we can put the cursor in it

let rescanId = null
let timers = { message: null, window: null, tick: null, rescan: null, stats: null, hint: null }

const revealsLeft = computed(() => MAX_REVEALS - revealsUsed.value)

// ---- small helpers ----
function clearTimer(name) {
  clearTimeout(timers[name])
  clearInterval(timers[name])
  timers[name] = null
}

// ---- staff note: technical problems are for Greg (holding the scanner), not the attendee ----
const staffNote = ref(null) // { text, detail }
function noteForStaff(text, detail = '') {
  staffNote.value = { text, detail }
  clearTimer('staff')
  timers.staff = setTimeout(() => (staffNote.value = null), 10000)
}
// a failed scan: tell Greg, and quietly go back to the advert
function scanFailed(text, detail = '') {
  forgetPerson()
  state.value = 'idle'
  noteForStaff(text, detail)
}

function flashHint(text) {
  hint.value = text
  clearTimer('hint')
  timers.hint = setTimeout(() => (hint.value = ''), 2500)
}

function showMessage(text, kind = 'info', detail = '') {
  forgetPerson()
  message.value = { text, kind, detail }
  state.value = 'message'
  clearTimer('message')
  // errors stay longer, so Greg has time to read what went wrong
  timers.message = setTimeout(() => (state.value = 'idle'), kind === 'error' ? 8000 : MESSAGE_MS)
}

// wipe everything about the current person (the QR must not linger on screen)
function forgetPerson() {
  qrImage.value = null
  revealsUsed.value = 0
  rescanId = null
  ;['window', 'tick', 'rescan', 'opened'].forEach(clearTimer)
}

// ---- the lock: can a new person be scanned right now? ----
function canAcceptScan() {
  // Locked while someone's code is pending. It frees itself when their phone opens the page
  // (auto), when the 3 reveals are used up, when the 3 minutes run out, or with "Cancelar".
  // (The old rule "free after the first reveal" was from before we could detect the opening.)
  return state.value === 'idle' || state.value === 'message'
}

// ---- scanning ----
async function submitScan(url) {
  if (!canAcceptScan()) {
    noteForStaff('Bloqueado: la persona anterior aún no ha abierto su página (o pulsa Cancelar)')
    return
  }
  clearTimer('message') // a message's "back to idle" timer must not fire mid-flow
  state.value = 'processing'
  try {
    const result = await api('/api/attendees/scan', {
      method: 'POST',
      body: { linkedinUrl: url.trim() },
    })
    if (result.status === 'ISSUED') {
      await startReady(result.token)
    } else {
      // AWAITING_CONFIRMATION: this person's page is already open somewhere
      forgetPerson()
      rescanId = result.rescanId
      state.value = 'waiting'
      timers.rescan = setInterval(pollRescan, RESCAN_POLL_MS)
    }
  } catch (e) {
    if (e.status === 401) {
      needsLogin.value = true
      scanFailed('Sesión de organizador caducada: inicia sesión (banner amarillo)')
    } else if (e.status === 400) {
      // show what was actually read: garbled text = keyboard layout mismatch (Windows vs scanner)
      scanFailed('No es un enlace de LinkedIn válido', `Leído: ${url.trim()}`)
    } else {
      scanFailed(e.message || 'Error inesperado', `Estado HTTP: ${e.status ?? 'sin conexión'}`)
    }
  }
}

let rescanFailures = 0
async function pollRescan() {
  try {
    const r = await api(`/api/admin/rescans/${rescanId}`)
    rescanFailures = 0
    if (r.status === 'CONFIRMED') {
      clearTimer('rescan')
      await startReady(r.token)
    } else if (r.status === 'REJECTED' || r.status === 'EXPIRED') {
      showMessage('No confirmado. Habla con el organizador.', 'error')
    }
  } catch (e) {
    // give up only if the re-scan is really gone (404) or the connection stays down (~8 s)
    rescanFailures++
    if (e.status === 404 || rescanFailures >= 5) {
      showMessage('No confirmado. Habla con el organizador.', 'error')
    }
  }
}

// ---- ready / reveal ----
async function startReady(token) {
  forgetPerson()
  // the QR points to this same website: works locally and through the tunnel alike
  const link = `${window.location.origin}/p/${token}`
  // error correction 'H' = up to ~30% of the code can be covered → room for the logo
  qrImage.value = await QRCode.toDataURL(link, {
    errorCorrectionLevel: 'H',
    margin: 2,
    width: 720,
    color: { dark: '#000000', light: '#ffffff' },
  })
  state.value = 'ready'
  windowEndsAt = Date.now() + REVEAL_WINDOW_MS
  secondsLeft.value = REVEAL_WINDOW_MS / 1000
  timers.tick = setInterval(() => (secondsLeft.value = Math.max(0, secondsLeft.value - 1)), 1000)
  // as soon as their phone opens the page, we're done: close the QR, free the station
  timers.opened = setInterval(async () => {
    try {
      const r = await api(`/api/admin/tokens/${token}/opened`)
      if (r.opened && (state.value === 'ready' || state.value === 'revealing')) {
        showMessage('✓ Abierto en tu móvil. ¡Disfruta del evento!', 'ok')
      }
    } catch {
      // a missed check is harmless: the next one comes in 1.5 s
    }
  }, OPENED_POLL_MS)
  timers.window = setTimeout(() => {
    if (state.value === 'ready' || state.value === 'revealing') {
      showMessage('Se acabó el tiempo. Vuelve a escanear tu QR de LinkedIn.', 'info')
    }
  }, REVEAL_WINDOW_MS)
}

// the person changed their mind / something went wrong: forget them, back to the advert
function cancelCurrent() {
  if (state.value === 'waiting' && rescanId) {
    api(`/api/admin/rescans/${rescanId}/reject`, { method: 'POST' }).catch(() => {})
  }
  forgetPerson()
  state.value = 'idle'
}

function startReveal() {
  if (state.value === 'ready' && revealsLeft.value > 0) state.value = 'revealing'
}

function stopReveal() {
  if (state.value !== 'revealing') return
  revealsUsed.value++
  if (revealsLeft.value <= 0) {
    // 3 reveals used and the phone never opened the page (otherwise we'd have left already)
    showMessage('No se ha abierto en tu móvil. Vuelve a escanear tu QR de LinkedIn.', 'info')
  } else {
    state.value = 'ready'
  }
}

// ---- keyboard: scanner vs human, and the SPACE key ----
// The scanner behaves like a super-fast keyboard: it "types" the link and presses Enter.
// We collect characters; if they arrived fast (scanner) and Enter follows, it's a scan.
let buffer = ''
let lastKeyTime = 0

function onKeyDown(e) {
  if (manualOpen.value) return // Greg is typing in the manual box: scanner input ignored

  if (e.code === 'Space') {
    e.preventDefault() // don't scroll the page
    if (!e.repeat) startReveal()
    return
  }

  const now = performance.now()
  if (e.ctrlKey || e.altKey || e.metaKey) return // shortcuts (Ctrl+R, F5...) still work
  if (e.key === 'Enter' || e.key.length === 1) e.preventDefault() // the station owns the keyboard

  if (e.key === 'Enter') {
    const text = buffer
    buffer = ''
    if (text.length >= SCANNER_MIN_LENGTH) submitScan(text)
    else if (text.length >= 5) {
      // part of a scan arrived too slowly and got cut: say so instead of doing nothing
      console.debug('[station] scan too short, ignored:', text)
      noteForStaff('Lectura incompleta, vuelve a escanear', `Leído: ${text}`)
    }
    return
  }
  if (e.key.length === 1) {
    // a slow key = a human pressing keys, or the start of a new scan: start over
    if (now - lastKeyTime > SCANNER_MAX_GAP_MS) buffer = ''
    buffer += e.key
    lastKeyTime = now
  }
}

function onKeyUp(e) {
  if (e.code === 'Space') stopReveal()
}

// ---- manual entry (when the scanner can't read a phone) ----
function openManual() {
  manualUrl.value = 'https://www.linkedin.com/in/'
  manualOpen.value = true
  nextTick(() => manualInput.value?.focus()) // wait until the box exists, then focus it
}
function submitManual() {
  manualOpen.value = false
  submitScan(manualUrl.value)
}

// ---- mirror for the dashboard: report what's on screen (never the QR or token) ----
let windowEndsAt = null
async function reportState() {
  try {
    await api('/api/admin/station/state', {
      method: 'POST',
      body: {
        state: state.value,
        revealsLeft: revealsLeft.value,
        windowEndsAt: state.value === 'ready' || state.value === 'revealing' ? windowEndsAt : null,
        message: state.value === 'message' ? message.value.text : null,
        messageKind: state.value === 'message' ? message.value.kind : null,
        staffNote: staffNote.value?.text ?? null,
      },
    })
  } catch {
    // not logged in / offline: the dashboard will show "station not connected"
  }
}
watch([state, revealsLeft, message, staffNote], reportState)

// ---- manual add from the dashboard: same flow as a scan ----
async function checkDashboardManual() {
  if (!canAcceptScan()) return // busy: leave the link in the mailbox (it waits up to 30 s)
  try {
    const r = await api('/api/admin/station/manual')
    if (r?.linkedinUrl) submitScan(r.linkedinUrl) // the lock still applies, like a real scan
  } catch {
    // not logged in / offline: loadStats shows the banner
  }
}

// ---- background data: event info + counter ----
async function loadStats() {
  try {
    const stats = await api('/api/admin/stats')
    joined.value = stats.joined
    needsLogin.value = false
    event.value = await api('/api/event') // Greg may edit the event details on the dashboard
  } catch (e) {
    if (e.status === 401) needsLogin.value = true
  }
}

onMounted(async () => {
  window.addEventListener('keydown', onKeyDown)
  window.addEventListener('keyup', onKeyUp)
  window.addEventListener('pointerup', stopReveal)
  window.addEventListener('blur', stopReveal) // alt-tab while holding: hide the QR
  try {
    event.value = await api('/api/event')
  } catch {
    event.value = null
  }
  loadStats()
  timers.stats = setInterval(loadStats, STATS_POLL_MS)
  timers.manualDash = setInterval(checkDashboardManual, MANUAL_POLL_MS)
  timers.heartbeat = setInterval(reportState, HEARTBEAT_MS)
  reportState()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeyDown)
  window.removeEventListener('keyup', onKeyUp)
  window.removeEventListener('pointerup', stopReveal)
  window.removeEventListener('blur', stopReveal)
  Object.keys(timers).forEach(clearTimer)
})

const timeLeftText = computed(() => {
  const m = Math.floor(secondsLeft.value / 60)
  const s = String(secondsLeft.value % 60).padStart(2, '0')
  return `${m}:${s}`
})
</script>

<template>
  <!-- pointerdown anywhere = "hold the mouse button" to reveal -->
  <div class="station" @pointerdown="startReveal">
    <img :src="bgBlobs" alt="" class="bg-blobs" aria-hidden="true" />

    <!-- staff not logged in -->
    <div v-if="onLocalhost" class="localhost-banner">
      ⚠ Estás en localhost: los QR personales solo funcionarán en este ordenador. Abre la estación
      con la dirección del túnel (https://….trycloudflare.com/station).
    </div>
    <a v-if="needsLogin" href="/login" class="login-banner" @pointerdown.stop>
      Inicia sesión de organizador para empezar →
    </a>

    <!-- ===== EVENT SECTION (for the organisers) ===== -->
    <header v-if="event && event.name" class="event-band">
      <h2 class="event-name">{{ event.name }}</h2>
      <p class="event-meta">
        <span v-if="event.date">{{ event.date }}</span>
        <span v-if="event.location">{{ event.location }}</span>
        <span v-if="event.organizers">Organiza: {{ event.organizers }}</span>
      </p>
    </header>

    <!-- ===== IDLE / ADVERT (also shown behind messages) ===== -->
    <section v-if="['idle', 'processing', 'message'].includes(state)" class="advert">
      <div class="left">
        <img :src="logoHeader" alt="One2Many" class="logo" />
        <h1 class="tagline">Find your people.</h1>
        <ol class="steps">
          <li>
            <span class="num">1</span>
            <div>
              <strong>Abre tu QR de LinkedIn</strong>
              <small>App de LinkedIn → icono QR en la barra de búsqueda → «Mi código»</small>
            </div>
          </li>
          <li>
            <span class="num">2</span>
            <div><strong>Enséñalo al lector</strong></div>
          </li>
          <li>
            <span class="num">3</span>
            <div>
              <strong>Escanea tu código personal</strong>
              <small>Con la cámara del móvil (la app de LinkedIn no lo lee)</small>
            </div>
          </li>
        </ol>
      </div>
      <aside class="right">
        <div class="counter card">
          <template v-if="joined > COUNTER_FROM">
            <span class="counter-num">{{ joined }}</span>
            <span>personas ya se han unido</span>
          </template>
          <template v-else>
            <span class="counter-first">¡Sé de los primeros!</span>
          </template>
        </div>
        <p class="privacy">
          Sin cuentas. Nada se guarda después del evento, salvo un registro de moderación durante 7
          días.
        </p>
      </aside>
    </section>

    <!-- ===== READY: hold to reveal ===== -->
    <section v-if="state === 'ready'" class="ready">
      <div class="card ready-card">
        <h1>Tu código personal está listo</h1>
        <!-- three fixed lines, so the two yellow keys sit under each other and never break -->
        <p class="big">
          Mantén pulsada la <kbd>barra espaciadora</kbd><br />
          o el <kbd>botón del ratón</kbd> en cualquier parte<br />
          para mostrarlo
        </p>
        <p class="camera-tip">📷 Escanéalo con la <strong>cámara del móvil</strong>, no con la app de LinkedIn</p>
        <p class="meta">
          Puedes mostrarlo {{ revealsLeft }} {{ revealsLeft === 1 ? 'vez' : 'veces' }} más ·
          {{ timeLeftText }}
        </p>
      </div>
      <!-- small, at the bottom of the screen: not an option the attendee should think about.
           .stop: a click here must not count as "hold the mouse to reveal" -->
      <button class="cancel-btn" @pointerdown.stop @click="cancelCurrent">Cancelar</button>
    </section>

    <!-- ===== REVEALING: navy full screen, QR in the middle ===== -->
    <section v-if="state === 'revealing'" class="reveal">
      <div class="qr-card">
        <img :src="qrImage" alt="Tu código personal" class="qr" />
        <img :src="logoQr" alt="" class="qr-logo" />
      </div>
      <p class="reveal-text">Escanéalo con la cámara de tu móvil</p>
      <p class="reveal-sub">Suelta para ocultarlo</p>
    </section>

    <!-- ===== WAITING for the organiser (re-scan) ===== -->
    <section v-if="state === 'waiting'" class="ready">
      <div class="card ready-card">
        <h1>Un momento, por favor</h1>
        <p class="big">El organizador está confirmando</p>
      </div>
      <button class="cancel-btn" @pointerdown.stop @click="cancelCurrent">Cancelar</button>
    </section>

    <!-- ===== messages (over the advert) ===== -->
    <div v-if="state === 'message'" class="toast" :class="message.kind">
      {{ message.text }}
      <small v-if="message.detail" class="toast-detail">{{ message.detail }}</small>
    </div>
    <div v-if="state === 'processing'" class="toast info">Un momento…</div>
    <div v-if="hint" class="toast info">{{ hint }}</div>

    <!-- ===== manual entry (organiser) ===== -->
    <button
      v-if="!manualOpen && (state === 'idle' || state === 'message')"
      class="manual-toggle"
      title="Introducir enlace a mano"
      @pointerdown.stop
      @click="openManual"
    >
      ⌨
    </button>
    <div v-if="staffNote" class="staff-note">
      <strong>{{ staffNote.text }}</strong>
      <small v-if="staffNote.detail">{{ staffNote.detail }}</small>
    </div>
    <form
      v-if="manualOpen && (state === 'idle' || state === 'message')"
      class="manual card"
      @pointerdown.stop
      @submit.prevent="submitManual"
      @keydown.esc="manualOpen = false"
    >
      <label for="manual-url">Enlace de LinkedIn (el escáner está en pausa)</label>
      <input id="manual-url" ref="manualInput" v-model="manualUrl" type="url" />
      <div class="manual-actions">
        <button type="button" class="btn btn-secondary" @click="manualOpen = false">Cancelar</button>
        <button type="submit" class="btn btn-primary">Añadir</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.station {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  user-select: none;
}
.bg-blobs {
  position: absolute;
  right: -40px;
  bottom: -40px;
  width: min(48vw, 640px);
  pointer-events: none;
}

/* event band */
.event-band {
  position: relative;
  padding: var(--space-5) var(--space-7);
  background: var(--surface);
  box-shadow: var(--shadow);
}
.event-name {
  font-size: 2.2rem;
  margin: 0;
}
.event-meta {
  margin: var(--space-1) 0 0;
  font-size: 1.25rem;
  color: var(--text-muted);
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-5);
}

/* advert */
.advert {
  position: relative;
  flex: 1;
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: var(--space-7);
  align-items: center;
  padding: var(--space-6) var(--space-7);
}
.logo {
  width: min(520px, 90%);
}
.tagline {
  font-size: clamp(2.6rem, 5vw, 4.5rem);
  margin: var(--space-4) 0 var(--space-6);
}
.steps {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: var(--space-4);
}
.steps li {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  font-size: 1.5rem;
}
.steps small {
  display: block;
  font-size: 1.05rem;
  color: var(--text-muted);
}
.num {
  flex: none;
  width: 52px;
  height: 52px;
  border-radius: var(--radius);
  display: grid;
  place-items: center;
  font-weight: 800;
  font-size: 1.5rem;
  background: var(--primary-soft);
  color: var(--text);
}
.steps li:nth-child(2) .num {
  background: var(--coral-soft);
}
.steps li:nth-child(3) .num {
  background: var(--teal-soft);
}
.counter {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: var(--space-2);
  font-size: 1.4rem;
  padding: var(--space-6);
}
.counter-num {
  font-size: 5rem;
  font-weight: 800;
  line-height: 1;
  color: var(--primary);
}
.counter-first {
  font-size: 2rem;
  font-weight: 800;
}
.privacy {
  margin-top: var(--space-4);
  font-size: 0.95rem;
  color: var(--text-muted);
}

/* ready + waiting */
.ready {
  position: relative;
  flex: 1;
  display: grid;
  place-items: center;
  padding: var(--space-6);
}
.ready-card {
  max-width: 900px;
  text-align: center;
  padding: var(--space-7);
}
.ready-card h1 {
  font-size: 3rem;
}
.big {
  font-size: 1.8rem;
  margin: 0 0 var(--space-4);
}
.meta {
  color: var(--text-muted);
  font-size: 1.2rem;
  margin: 0;
}
kbd {
  font-family: inherit;
  font-weight: 700;
  background: var(--yellow-soft);
  padding: 0 var(--space-2);
  border-radius: var(--radius-sm);
}

/* reveal: navy full screen, moderate QR */
.reveal {
  position: fixed;
  inset: 0;
  z-index: 10;
  background: var(--text);
  color: #fff;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-4);
}
.qr-card {
  position: relative;
  background: #fff;
  padding: var(--space-4);
  border-radius: var(--radius-lg);
}
.qr {
  display: block;
  width: min(42vh, 380px);
  height: auto;
}
.qr-logo {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 21%; /* ≤ ~20% of the QR width, as the error correction allows */
  transform: translate(-50%, -50%);
  border-radius: var(--radius);
  background: #fff;
}
.reveal-text {
  font-size: 1.8rem;
  font-weight: 700;
  margin: 0;
}
.reveal-sub {
  margin: 0;
  opacity: 0.7;
}

/* toasts */
.toast {
  position: fixed;
  left: 50%;
  bottom: var(--space-6);
  transform: translateX(-50%);
  z-index: 20;
  padding: var(--space-4) var(--space-6);
  border-radius: var(--radius);
  font-size: 1.4rem;
  font-weight: 700;
  box-shadow: var(--shadow);
  background: var(--surface);
}
.toast-detail {
  display: block;
  margin-top: var(--space-2);
  font-size: 0.9rem;
  font-weight: 400;
  font-family: monospace;
  max-width: 80vw;
  overflow-wrap: anywhere;
}
.toast.error {
  background: var(--danger);
  color: #fff;
}
.toast.ok {
  background: var(--teal-soft);
}

/* login banner */
.localhost-banner {
  position: relative;
  z-index: 5;
  padding: var(--space-2) var(--space-3);
  text-align: center;
  background: var(--danger-soft);
  color: var(--text);
  font-weight: 700;
}
.login-banner {
  position: relative;
  z-index: 5;
  display: block;
  padding: var(--space-3);
  text-align: center;
  background: var(--yellow-soft);
  color: var(--text);
  font-weight: 700;
  text-decoration: none;
}

/* manual entry */
.staff-note {
  position: fixed;
  left: calc(var(--space-4) + 44px + var(--space-3)); /* just right of the keyboard button */
  bottom: var(--space-4);
  z-index: 5;
  max-width: min(640px, 70vw);
  padding: var(--space-2) var(--space-3);
  border-left: 4px solid var(--danger);
  border-radius: var(--radius-sm);
  background: var(--danger-soft);
  color: var(--text);
  font-size: 0.9rem;
}
.staff-note small {
  display: block;
  font-family: monospace;
  overflow-wrap: anywhere;
}
kbd {
  white-space: nowrap; /* "barra espaciadora" never splits over two lines */
}
.cancel-btn {
  position: fixed;
  left: var(--space-4);
  bottom: var(--space-4);
  z-index: 5;
  padding: var(--space-2) var(--space-3);
  border: 0;
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow);
  color: var(--text-muted);
  font-weight: 600;
  opacity: 0.6;
  cursor: pointer;
}
.cancel-btn:hover {
  opacity: 1;
}
.camera-tip {
  margin-top: var(--space-3);
  font-size: 1.2rem;
}
.manual-toggle {
  position: fixed;
  left: var(--space-4);
  bottom: var(--space-4);
  z-index: 5;
  width: 44px;
  height: 44px;
  border: 0;
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow);
  opacity: 0.5;
  cursor: pointer;
}
.manual-toggle:hover {
  opacity: 1;
}
.manual {
  position: fixed;
  left: var(--space-4);
  bottom: var(--space-4);
  z-index: 15;
  width: min(520px, 90vw);
  display: grid;
  gap: var(--space-3);
  user-select: text;
}
.manual input {
  font: inherit;
  padding: var(--space-3);
  border: 2px solid var(--primary-soft);
  border-radius: var(--radius);
}
.manual-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
}
</style>
