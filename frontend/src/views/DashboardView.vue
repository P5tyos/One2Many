<script setup>
// Greg's dashboard, on the laptop: who has joined, moderation, re-scan confirmations,
// and the event details (editable at any time; the station and phones pick them up).
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { api } from '@/api'
import { createDashboardScanner } from '@/lib/scanner'
import { plain, hiddenFor as hiddenForAt, loginAlarm as loginAlarmAt } from '@/lib/text'
import logoHeader from '@/assets/brand/logo-header.png'

const POLL_MS = 2500 // fresh data this often (re-scans need a quick answer)
const RESCAN_TIMEOUT_S = 120 // same as one2many.moderation.rescan-timeout-seconds

const needsLogin = ref(false)
const stats = ref({ joined: 0, completed: 0, flagged: 0, pendingReview: 0, pendingRescans: 0, totalJoined: 0 })
const attendees = ref([])
const rescans = ref([])
const now = ref(Date.now()) // ticks every second, for the re-scan countdown
const actionError = ref('')
const station = ref(null) // what the station screen shows (reported by the station itself)

// ---- loading (everything at once, every few seconds) ----
async function poll() {
  try {
    const [s, a, r, st] = await Promise.all([
      api('/api/admin/stats'),
      api('/api/admin/attendees'),
      api('/api/admin/rescans'),
      api('/api/admin/station/state'),
    ])
    stats.value = s
    attendees.value = a
    rescans.value = r
    station.value = st
    needsLogin.value = false
  } catch (e) {
    if (e.status === 401) needsLogin.value = true
  }
}

// one moderator action, then refresh at once (not in 2.5 s)
async function act(path, method = 'POST', body) {
  actionError.value = ''
  try {
    await api(path, { method, body })
  } catch (e) {
    if (e.status === 401) needsLogin.value = true
    else actionError.value = e.message
  }
  await poll()
}

// ---- the table ----
const query = ref('')
// newest first; search by name, sentence or link (plain(): src/lib/text.js)
const rows = computed(() => {
  const q = plain(query.value.trim())
  const list = [...attendees.value].reverse()
  if (!q) return list
  return list.filter((a) =>
    [a.name, a.message, a.linkedinUrl].some((field) => plain(field).includes(q)),
  )
})
const ROW_HINT = {
  'row-flagged': 'Marcado: oculto para los demás. En su móvil ve un aviso naranja y debe cambiar su texto.',
  'row-pending': 'Ha cambiado su texto tras varias marcas. Léelo: si está bien, Aprobar; si no, Marcar otra vez.',
  'row-hidden':
    'Posible palabrota escondida dentro de otra palabra. Si está bien: Aceptar (no cambia nada para la persona). Si no: Marcar.',
}
// hidden for 10+ minutes = probably gone or not fixing it: moved to a section at the bottom
const INACTIVE_AFTER_MS = 10 * 60 * 1000
function isInactive(a) {
  return a.hiddenSince && now.value - new Date(a.hiddenSince).getTime() >= INACTIVE_AFTER_MS
}
const inactiveCount = computed(() => rows.value.filter(isInactive).length)
// active rows on top, then a small gap + heading, then the inactive ones (always shown, still red)
const displayRows = computed(() => {
  const active = rows.value.filter((a) => !isInactive(a))
  if (!inactiveCount.value) return active
  const separator = { adminId: '__separator', separator: true }
  return [...active, separator, ...rows.value.filter(isInactive)]
})

function rowClass(a) {
  if (a.status === 'FLAGGED') return 'row-flagged'
  if (a.status === 'PENDING_REVIEW') return 'row-pending'
  if (a.possibleHiddenWord) return 'row-hidden'
  return ''
}
const STATUS_LABEL = { VISIBLE: 'Visible', FLAGGED: 'Marcado', PENDING_REVIEW: 'Pendiente' }
function time(iso) {
  return new Date(iso).toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit' })
}
// what the table SHOWS of a link (the link itself stays complete): /qr/ codes can be 100+
// characters and would stretch the table sideways (seen in the rehearsal, 24 Nov)
const LINK_SHOWN_MAX = 32
function shortLink(url) {
  const path = url.replace('https://www.linkedin.com', '')
  return path.length > LINK_SHOWN_MAX ? path.slice(0, LINK_SHOWN_MAX) + '…' : path
}

// two-click delete: first click asks, second click within a few seconds does it
const confirmingDelete = ref(null)
function remove(a) {
  if (confirmingDelete.value === a.adminId) {
    confirmingDelete.value = null
    act(`/api/admin/attendees/${a.adminId}`, 'DELETE')
  } else {
    confirmingDelete.value = a.adminId
    setTimeout(() => {
      if (confirmingDelete.value === a.adminId) confirmingDelete.value = null
    }, 20000) // long: Greg may be chatting to someone
  }
}

// inline rename: click "Renombrar", type, Enter
const renamingId = ref(null)
const renameValue = ref('')
function startRename(a) {
  renamingId.value = a.adminId
  renameValue.value = a.name ?? ''
}
async function saveRename(a) {
  const name = renameValue.value.trim()
  renamingId.value = null
  if (name && name !== a.name) await act(`/api/admin/attendees/${a.adminId}/name`, 'POST', { name })
}

// ---- manual add: send a link to the station ----
const LINK_START = 'https://www.linkedin.com/in/'
const manualUrl = ref(LINK_START)
const manualMsg = ref(null) // { ok, text }
async function sendManual(fromScanner) {
  manualMsg.value = null
  const link = typeof fromScanner === 'string' ? fromScanner : manualUrl.value.trim()
  try {
    await api('/api/admin/station/manual', { method: 'POST', body: { linkedinUrl: link } })
    manualMsg.value = {
      ok: true,
      text: typeof fromScanner === 'string' ? 'Escaneo recibido aquí: enviado a la estación.' : 'Enviado: mira la estación.',
    }
    if (typeof fromScanner !== 'string') manualUrl.value = LINK_START
  } catch (e) {
    if (e.status === 401) needsLogin.value = true
    manualMsg.value = { ok: false, text: e.fields?.linkedinUrl ?? e.message }
  }
  setTimeout(() => (manualMsg.value = null), 6000)
}

// ---- the scanner works here too ----
// Windows sends the scanner's "typing" to whichever window was clicked last. So the dashboard
// recognises a scan as well and hands it to the station through the manual-add mailbox
// (the station shows the QR, lock included).
// - Outside text boxes: fast keys + Enter, 20+ characters (same rule as the station).
// - Inside a text box (search, event form...): the characters land in the box first, so the rule
//   is stricter: 20+ characters at an inhuman AVERAGE speed (< 50 ms per key; the fastest humans
//   are well above 100) + Enter. Then the box is put back as it was, the Enter is cancelled
//   (nothing gets saved), and the link goes to the station. A LinkedIn link can never end up
//   in the event details by accident (Greg, 21 Nov).
// The detection rules live in src/lib/scanner.js; here we only act on its decision.
const dashboardScanner = createDashboardScanner()
function activeTextField() {
  const el = document.activeElement
  return el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') ? el : null
}
function onKeyDown(e) {
  const result = dashboardScanner.keyDown(e, performance.now(), activeTextField())
  if (result.preventDefault) e.preventDefault() // no form submit; outside boxes no Firefox Quick Find
  if (result.stopPropagation) e.stopPropagation()
  if (result.restore) {
    result.restore.field.value = result.restore.value // undo the scanner's typing
    result.restore.field.dispatchEvent(new Event('input', { bubbles: true })) // tell Vue (v-model)
  }
  if (result.scan) sendManual(result.scan)
}

// ---- login warning: loud only while it matters (rule in src/lib/text.js) ----
const loginAlarm = computed(() => loginAlarmAt(stats.value, now.value))

// ---- station mirror ----
const STATION_OFFLINE_MS = 12000 // no heartbeat for this long = station closed or logged out
const stationOnline = computed(
  () => station.value && now.value - station.value.reportedAt < STATION_OFFLINE_MS,
)
const stationCountdown = computed(() => {
  if (!station.value?.windowEndsAt) return ''
  const s = Math.max(0, Math.round((station.value.windowEndsAt - now.value) / 1000))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
})

// "hidden for 4 min" (src/lib/text.js)
function hiddenFor(iso) {
  return hiddenForAt(iso, now.value)
}

// ---- re-scan confirmation (popup) ----
const currentRescan = computed(() => rescans.value[0] ?? null) // oldest first
function secondsLeft(r) {
  const passed = (now.value - new Date(r.requestedAt).getTime()) / 1000
  return Math.max(0, Math.ceil(RESCAN_TIMEOUT_S - passed))
}

// ---- event details ----
const eventForm = ref({ name: '', date: '', location: '', organizers: '', description: '', linkedinUrl: '' })
const eventErrors = ref({})
const eventSaved = ref(false)
const eventSaving = ref(false)
async function loadEvent() {
  try {
    const e = await api('/api/event')
    eventForm.value = { ...e, linkedinUrl: e.linkedinUrl ?? '' }
  } catch {
    // keep the empty form
  }
}
async function saveEvent() {
  eventSaving.value = true
  eventErrors.value = {}
  eventSaved.value = false
  try {
    const e = await api('/api/admin/event', { method: 'PUT', body: eventForm.value })
    eventForm.value = { ...e, linkedinUrl: e.linkedinUrl ?? '' }
    eventSaved.value = true
    setTimeout(() => (eventSaved.value = false), 3000)
  } catch (e) {
    if (e.status === 401) needsLogin.value = true
    else eventErrors.value = e.fields ?? { _: e.message }
  } finally {
    eventSaving.value = false
  }
}

// ---- emergency: release all sessions (two clicks) ----
const confirmingRelease = ref(false)
const releaseDone = ref(false)
async function releaseAll() {
  if (!confirmingRelease.value) {
    confirmingRelease.value = true
    setTimeout(() => (confirmingRelease.value = false), 20000)
    return
  }
  confirmingRelease.value = false
  await act('/api/admin/release-sessions')
  releaseDone.value = true
  setTimeout(() => (releaseDone.value = false), 5000)
}

// ---- reset (new event): two clicks ----
const confirmingReset = ref(false)
function reset() {
  if (confirmingReset.value) {
    confirmingReset.value = false
    act('/api/admin/reset')
  } else {
    confirmingReset.value = true
    setTimeout(() => (confirmingReset.value = false), 4000)
  }
}

let pollTimer = null
let clockTimer = null
onMounted(() => {
  window.addEventListener('keydown', onKeyDown, true) // capture: before any form sees Enter
  poll()
  loadEvent()
  pollTimer = setInterval(poll, POLL_MS)
  clockTimer = setInterval(() => (now.value = Date.now()), 1000)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeyDown, true)
  clearInterval(pollTimer)
  clearInterval(clockTimer)
})
</script>

<template>
  <div class="dash">
    <header class="bar">
      <img :src="logoHeader" alt="One2Many" class="logo" />
      <span class="bar-title">Panel del organizador</span>
      <span v-if="eventForm.name" class="bar-event">· {{ eventForm.name }}</span>
      <span class="spacer"></span>
      <a href="/station" target="_blank" rel="noopener">Abrir estación ↗</a>
      <form action="/logout" method="post">
        <button class="linklike" type="submit">Cerrar sesión</button>
      </form>
    </header>

    <a v-if="needsLogin" href="/login" class="login-banner">
      Sesión caducada: inicia sesión →
      <small>(después vuelve a esta pestaña y recarga)</small>
    </a>

    <!-- ===== numbers ===== -->
    <section class="stats">
      <div class="stat" title="Personas en la lista ahora. Entre paréntesis: todas las que se han unido, incluidas las eliminadas">
        <b>{{ stats.joined }} <small class="total">/ {{ stats.totalJoined }} en total</small></b><span>En la lista</span>
      </div>
      <div class="stat"><b>{{ stats.completed }}</b><span>Perfil completo</span></div>
      <div class="stat" :class="{ alert: stats.flagged }"><b>{{ stats.flagged }}</b><span>Marcados</span></div>
      <div class="stat" :class="{ alert: stats.pendingReview }">
        <b>{{ stats.pendingReview }}</b><span>Esperan aprobación</span>
      </div>
      <div class="stat" :class="{ alert: stats.pendingRescans }">
        <b>{{ stats.pendingRescans }}</b><span>Re-escaneos</span>
      </div>
    </section>

    <div v-if="stats.pendingReview" class="notice pending">
      {{ stats.pendingReview }}
      {{ stats.pendingReview === 1 ? 'persona ha cambiado su texto' : 'personas han cambiado su texto' }}
      tras varias marcas y espera tu aprobación (filas azules).
    </div>
    <!-- someone outside is guessing the admin password (only Greg sees this; never on the station) -->
    <!-- red while a pause runs or the last failure was recent; afterwards a quiet grey line -->
    <div v-if="stats.loginFailures" class="notice" :class="loginAlarm ? 'error' : 'quiet'">
      🔒 {{ stats.loginFailures }}
      {{ stats.loginFailures === 1 ? 'intento fallido' : 'intentos fallidos' }} de inicio de sesión desde fuera
      <template v-if="stats.lastLoginFailureAt">(el último a las {{ time(stats.lastLoginFailureAt) }})</template>.
      <template v-if="stats.loginPausedUntil">
        <strong>Inicios de sesión en pausa hasta {{ time(stats.loginPausedUntil) }}</strong>
        (tu sesión sigue abierta).
      </template>
      <span v-else>Tu sesión no se ve afectada.</span>
    </div>
    <div v-if="actionError" class="notice error">{{ actionError }}</div>

    <div class="layout">
      <!-- ===== attendees ===== -->
      <section class="card table-card">
        <div class="table-head">
          <h2>Asistentes</h2>
          <input v-model="query" type="search" placeholder="Buscar nombre, frase o enlace" />
        </div>
        <p class="legend">
          <span class="dot flagged"></span> marcado
          <span class="dot pending"></span> espera aprobación
          <span class="dot hidden"></span> posible palabra oculta: revisa y acepta
          <span class="legend-tip">· pasa el ratón por una fila o botón para ver qué hace</span>
        </p>

        <table>
          <thead>
            <tr>
              <th>Hora</th>
              <th>Nombre</th>
              <th>Frase</th>
              <th>Estado</th>
              <th title="Su móvil ha abierto la página">Abierta</th>
              <th title="Cuánto tiempo lleva sin aparecer en la lista de los demás">Oculto</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!rows.length">
              <td colspan="7" class="empty">
                {{ query ? 'Nadie coincide.' : 'Todavía no se ha unido nadie.' }}
              </td>
            </tr>
            <template v-for="a in displayRows" :key="a.adminId">
            <tr v-if="a.separator" class="sep">
              <td colspan="7">
                Ocultos más de 10 minutos ({{ inactiveCount }})
              </td>
            </tr>
            <tr v-else :class="rowClass(a)" :title="ROW_HINT[rowClass(a)]">
              <td class="nowrap muted">{{ time(a.joinedAt) }}</td>
              <td>
                <form v-if="renamingId === a.adminId" class="rename" @submit.prevent="saveRename(a)">
                  <input v-model="renameValue" maxlength="60" autofocus @keydown.esc="renamingId = null" />
                  <button class="btn btn-primary btn-xs" type="submit">OK</button>
                </form>
                <template v-else>
                  <strong v-if="a.name">{{ a.name }}</strong>
                  <em v-else class="muted">sin nombre aún</em>
                </template>
                <a :href="a.linkedinUrl" :title="a.linkedinUrl" target="_blank" rel="noopener" class="li">
                  {{ shortLink(a.linkedinUrl) }} ↗
                </a>
              </td>
              <td class="msg">{{ a.message }}</td>
              <td class="nowrap">
                <span class="status" :class="a.status">{{ STATUS_LABEL[a.status] }}</span>
                <small v-if="a.flagCount" class="muted"> · {{ a.flagCount }}×</small>
                <span v-if="a.possibleHiddenWord" class="hint-chip">¿palabra oculta?</span>
              </td>
              <td class="center">{{ a.pageOpened ? '✓' : '–' }}</td>
              <td class="center nowrap" :class="{ 'hidden-time': a.hiddenSince }">{{ hiddenFor(a.hiddenSince) }}</td>
              <td class="actions">
                <button
                  v-if="a.possibleHiddenWord"
                  class="btn btn-xs btn-ok"
                  title="El texto está bien: quita el aviso amarillo. No cambia nada para la persona."
                  @click="act(`/api/admin/attendees/${a.adminId}/accept`)"
                >
                  Aceptar
                </button>
                <button
                  v-if="a.status !== 'VISIBLE'"
                  class="btn btn-xs btn-ok"
                  title="Vuelve a mostrarlo en la lista de los demás."
                  @click="act(`/api/admin/attendees/${a.adminId}/approve`)"
                >
                  Aprobar
                </button>
                <button
                  v-if="a.status === 'VISIBLE' && a.name"
                  class="btn btn-xs btn-warn"
                  title="Lo oculta de la lista. En su móvil ve un aviso y tiene que cambiar su texto."
                  @click="act(`/api/admin/attendees/${a.adminId}/flag`)"
                >
                  Marcar
                </button>
                <button v-if="a.name" class="btn btn-xs btn-secondary" title="Corrige su nombre (mismo código, mismo móvil)." @click="startRename(a)">
                  Renombrar
                </button>
                <button class="btn btn-xs btn-danger" title="Lo borra del todo. Su página deja de funcionar." @click="remove(a)">
                  {{ confirmingDelete === a.adminId ? '¿Seguro?' : 'Eliminar' }}
                </button>
              </td>
            </tr>
            </template>
          </tbody>
        </table>
      </section>

      <!-- ===== side: event details + reset ===== -->
      <aside class="side">
        <section class="card mirror-card" title="Lo que ve la persona en la estación, en tiempo real (el QR nunca se muestra aquí)">
          <h2>Estación ahora</h2>
          <div class="mirror" :class="[stationOnline ? station.state : 'offline', station?.messageKind]">
            <template v-if="!stationOnline">
              <b>Estación sin conexión</b>
              <small>Ábrela (y sin sesión caducada) para verla aquí</small>
            </template>
            <template v-else-if="station.state === 'idle'">
              <b>Find your people.</b>
              <small>Anuncio · esperando escaneo</small>
            </template>
            <template v-else-if="station.state === 'processing'">
              <b>Un momento…</b>
            </template>
            <template v-else-if="station.state === 'ready'">
              <b>Código listo</b>
              <small>Puede mostrarlo {{ station.revealsLeft }} {{ station.revealsLeft === 1 ? 'vez' : 'veces' }} más · {{ stationCountdown }}</small>
            </template>
            <template v-else-if="station.state === 'revealing'">
              <span class="mini-qr">QR</span>
              <small>Mostrando su código · {{ stationCountdown }}</small>
            </template>
            <template v-else-if="station.state === 'waiting'">
              <b>Esperando tu confirmación</b>
              <small>Re-escaneo: mira el aviso</small>
            </template>
            <template v-else-if="station.state === 'message'">
              <b>{{ station.message }}</b>
            </template>
            <span v-if="stationOnline && station.staffNote" class="mirror-note">{{ station.staffNote }}</span>
          </div>
        </section>

        <form class="card manual-add" @submit.prevent="sendManual">
          <h2>Añadir a mano</h2>
          <p class="muted small">Si el lector no puede leer su QR: pega su enlace de LinkedIn. El código personal aparece en la estación. (También puedes escanear con esta ventana activa.)</p>
          <input v-model="manualUrl" />
          <button class="btn btn-primary" type="submit">Enviar a la estación</button>
          <p v-if="manualMsg" class="small" :class="manualMsg.ok ? 'ok-text' : 'field-error'">{{ manualMsg.text }}</p>
        </form>

        <form class="card event-form" @submit.prevent="saveEvent">
          <h2>Datos del evento</h2>
          <p class="muted small">Se ven en la estación y en los móviles en segundos.</p>
          <label>Nombre <input v-model="eventForm.name" maxlength="80" /></label>
          <label>Fecha y hora <input v-model="eventForm.date" maxlength="80" placeholder="Mar 25 nov, 16:00" /></label>
          <label>Lugar <input v-model="eventForm.location" maxlength="120" /></label>
          <label>Organiza <input v-model="eventForm.organizers" maxlength="120" /></label>
          <label>Descripción <textarea v-model="eventForm.description" maxlength="300" rows="3"></textarea></label>
          <label>
            Enlace del evento en LinkedIn
            <input v-model="eventForm.linkedinUrl" maxlength="300" placeholder="https://www.linkedin.com/events/..." />
          </label>
          <p v-for="(msg, field) in eventErrors" :key="field" class="field-error">{{ msg }}</p>
          <button class="btn btn-primary" type="submit" :disabled="eventSaving">
            {{ eventSaved ? '✓ Guardado' : eventSaving ? 'Guardando…' : 'Guardar' }}
          </button>
          <p class="muted small">Si reinicias la aplicación, vuelven los datos de application.properties.</p>
        </form>

        <div class="card danger-zone">
          <h2>Emergencia</h2>
          <p class="muted small">
            Si el túnel se reinicia (nueva dirección), las páginas de los móviles dejan de funcionar.
            Esto libera todas las sesiones: cada persona vuelve a escanear y recibe su código al
            momento, sin confirmación. Perfiles y lista se mantienen.
          </p>
          <button class="btn btn-secondary" @click="releaseAll">
            {{ releaseDone ? '✓ Sesiones liberadas' : confirmingRelease ? '¿Seguro?' : 'Liberar todas las sesiones' }}
          </button>
        </div>

        <div class="card danger-zone">
          <h2>Nuevo evento</h2>
          <p class="muted small">Borra a todos los asistentes. No se puede deshacer.</p>
          <button class="btn btn-danger" @click="reset">
            {{ confirmingReset ? 'Pulsa otra vez para borrar todo' : 'Vaciar lista' }}
          </button>
        </div>
      </aside>
    </div>

    <!-- ===== re-scan popup ===== -->
    <div v-if="currentRescan" class="overlay">
      <div class="card modal">
        <h2>Nuevo escaneo: ¿es la misma persona?</h2>
        <p>
          <strong>{{ currentRescan.name || 'Sin nombre aún' }}</strong> ya tiene su página abierta en un
          móvil y acaba de escanear otra vez en la estación.
        </p>
        <p>
          <a :href="currentRescan.linkedinUrl" target="_blank" rel="noopener">
            {{ shortLink(currentRescan.linkedinUrl) }} ↗
          </a>
        </p>
        <p class="muted">
          Si confirmas, recibe un código nuevo y su móvil anterior deja de funcionar. Si no es esa
          persona, rechaza.
        </p>
        <p class="countdown">Se cancela solo en {{ secondsLeft(currentRescan) }} s</p>
        <div class="modal-actions">
          <button class="btn btn-secondary" @click="act(`/api/admin/rescans/${currentRescan.rescanId}/reject`)">
            No, rechazar
          </button>
          <button class="btn btn-primary" @click="act(`/api/admin/rescans/${currentRescan.rescanId}/confirm`)">
            Sí, es la misma persona
          </button>
        </div>
        <p v-if="rescans.length > 1" class="muted small">Y {{ rescans.length - 1 }} más esperando.</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dash {
  max-width: 1500px;
  margin: 0 auto;
  padding: var(--space-3) var(--space-5) var(--space-6);
  display: grid;
  gap: var(--space-4);
}

/* top bar */
.bar {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}
.logo {
  height: 34px;
}
.bar-title {
  font-weight: 800;
}
.bar-event {
  color: var(--text-muted);
}
.spacer {
  flex: 1;
}
.bar a,
.linklike {
  font-weight: 700;
}
.linklike {
  border: 0;
  background: none;
  color: var(--primary);
  cursor: pointer;
  text-decoration: underline;
  padding: 0;
}
.login-banner {
  display: block;
  padding: var(--space-3);
  text-align: center;
  border-radius: var(--radius);
  background: var(--yellow-soft);
  color: var(--text);
  font-weight: 700;
  text-decoration: none;
}

/* numbers */
.stats {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: var(--space-3);
}
.stat {
  background: var(--surface);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  padding: var(--space-3) var(--space-4);
  display: grid;
}
.stat b {
  font-size: 1.9rem;
  line-height: 1.1;
}
.total {
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--text-muted);
}
.stat span {
  color: var(--text-muted);
  font-size: 0.9rem;
}
.stat.alert {
  background: #fff1e8;
  box-shadow: inset 0 0 0 2px var(--coral-soft);
}

.notice {
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius);
  font-weight: 600;
}
.notice.pending {
  background: #edf3fd;
  border: 1px solid #cddcf5;
}
.notice.quiet {
  background: #f1f2f5;
  color: var(--text-muted);
  font-weight: 500;
  font-size: 0.9rem;
}
.notice.error {
  background: var(--danger-soft);
  border: 1px solid #f3c4c4;
}

.layout {
  display: grid;
  grid-template-columns: 1fr 340px;
  gap: var(--space-4);
  align-items: start;
}
h2 {
  margin: 0;
  font-size: 1.15rem;
}
.muted {
  color: var(--text-muted);
}
.small {
  font-size: 0.85rem;
  margin: 0;
}

/* table */
.table-card {
  padding: var(--space-4);
  overflow-x: auto;
}
.table-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-3);
}
.table-head input,
.event-form input,
.event-form textarea,
.rename input {
  font: inherit;
  padding: var(--space-2) var(--space-3);
  border: 2px solid #dfe3ee;
  border-radius: var(--radius-sm);
  background: #fff;
  color: var(--text);
}
.table-head input {
  width: 280px;
}
.legend {
  margin: var(--space-2) 0;
  font-size: 0.85rem;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.legend-tip {
  margin-left: var(--space-2);
  font-style: italic;
}
.dot {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  display: inline-block;
  margin-left: var(--space-2);
}
.dot.flagged {
  background: #f6c9c9;
}
.dot.pending {
  background: #cddcf5;
}
.dot.hidden {
  background: #fbe7a6;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.92rem;
}
th {
  text-align: left;
  font-size: 0.8rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--text-muted);
  padding: var(--space-2);
  border-bottom: 2px solid #e9edf6;
}
td {
  padding: var(--space-2);
  border-bottom: 1px solid #eef1f7;
  vertical-align: top;
}
.row-flagged td {
  background: var(--danger-soft);
}
.row-pending td {
  background: #edf3fd;
}
/* the gap between active people and the ones hidden for 10+ min */
.sep td {
  padding: var(--space-6) var(--space-2) var(--space-2);
  border-bottom: 2px solid #e9edf6;
  font-size: 0.8rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--text-muted);
}
.row-hidden td {
  background: #fff6d6;
}
.nowrap {
  white-space: nowrap;
}
.center {
  text-align: center;
}
.empty {
  text-align: center;
  color: var(--text-muted);
  padding: var(--space-5);
}
.li {
  display: block;
  font-size: 0.8rem;
  text-decoration: none;
}
.msg {
  max-width: 360px;
  overflow-wrap: anywhere;
}
.status {
  padding: 1px var(--space-2);
  border-radius: var(--radius-sm);
  font-size: 0.8rem;
  font-weight: 700;
  background: #e9edf6;
}
.status.FLAGGED {
  background: #f6c9c9;
  color: #9b2c2c;
}
.status.PENDING_REVIEW {
  background: #cddcf5;
  color: var(--primary-dark);
}
.hint-chip {
  display: block;
  margin-top: 2px;
  font-size: 0.78rem;
  font-weight: 700;
  color: #8a6400;
}
.actions {
  white-space: nowrap;
  text-align: right;
}
.actions .btn {
  margin-left: var(--space-1);
}
.btn-xs {
  min-height: 28px;
  padding: 2px var(--space-2);
  font-size: 0.8rem;
  border-radius: var(--radius-sm);
}
.btn-ok {
  background: var(--teal);
  color: #fff;
}
.btn-warn {
  background: var(--yellow);
  color: var(--text);
}
.rename {
  display: flex;
  gap: var(--space-1);
}
.rename input {
  width: 100%;
  padding: 2px var(--space-2);
}

/* side */
.side {
  display: grid;
  gap: var(--space-4);
}
/* station mirror: a small copy of the station screen */
.mirror-card {
  padding: var(--space-4);
  display: grid;
  gap: var(--space-2);
}
.mirror {
  position: relative;
  aspect-ratio: 16 / 9;
  border-radius: var(--radius);
  background: var(--bg);
  border: 2px solid #e3e9f6;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: var(--space-1);
  text-align: center;
  padding: var(--space-3);
}
.mirror small {
  color: var(--text-muted);
}
.mirror.revealing {
  background: var(--primary-dark);
  border-color: var(--primary-dark);
}
.mirror.revealing small {
  color: #dfe6f5;
}
.mini-qr {
  width: 52px;
  height: 52px;
  border-radius: var(--radius-sm);
  background: #fff;
  display: grid;
  place-items: center;
  font-weight: 800;
  color: var(--primary-dark);
}
.mirror.message.ok {
  background: #eaf7f3;
}
.mirror.offline {
  background: #f1f2f5;
}
.mirror-note {
  position: absolute;
  left: var(--space-2);
  right: var(--space-2);
  bottom: var(--space-2);
  padding: 2px var(--space-2);
  border-left: 3px solid var(--danger);
  border-radius: 4px;
  background: var(--danger-soft);
  font-size: 0.75rem;
  text-align: left;
}
.hidden-time {
  color: var(--danger);
  font-weight: 700;
}
.manual-add input {
  font: inherit;
  padding: var(--space-2) var(--space-3);
  border: 2px solid #dfe3ee;
  border-radius: var(--radius-sm);
  background: #fff;
  color: var(--text);
}
.ok-text {
  color: #23806c;
  font-weight: 700;
}
.manual-add,
.event-form,
.danger-zone {
  display: grid;
  gap: var(--space-2);
  padding: var(--space-4);
}
.event-form label {
  display: grid;
  gap: 2px;
  font-weight: 700;
  font-size: 0.85rem;
}
.event-form textarea {
  resize: vertical;
}
.field-error {
  margin: 0;
  color: var(--danger);
  font-weight: 600;
  font-size: 0.85rem;
}
.btn:disabled {
  opacity: 0.5;
}

/* re-scan popup */
.overlay {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: grid;
  place-items: center;
  background: rgba(13, 32, 69, 0.45);
}
.modal {
  width: min(520px, 92vw);
  display: grid;
  gap: var(--space-2);
}
.modal p {
  margin: 0;
}
.countdown {
  font-weight: 800;
  color: var(--coral);
}
.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-2);
}
</style>
