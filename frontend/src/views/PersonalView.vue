<script setup>
// The attendee's own page, on their phone: /p/:token
// First visit: a short form (name + one line about them). After that: everyone else, as cards.
// The token comes from the personal QR; on the first open the backend also hands out the
// "wristband" cookie, so from then on only this phone can use the page.
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { api } from '@/api'
import logoHeader from '@/assets/brand/logo-header.png'

const props = defineProps({ token: { type: String, required: true } })

const REFRESH_MS = 20000 // new people keep arriving: refresh the list this often
const SAVED_BANNER_MS = 5000
const NAME_MAX = 60 // same limits as the backend (ProfileDetailsRequest)
const MESSAGE_MAX = 100
const VISITED_KEY = 'o2m_visited' // remembered on this phone only
// soft tones for the initials avatars (rounded squares), picked by name so each person keeps theirs
const TONES = ['var(--coral-soft)', 'var(--teal-soft)', 'var(--yellow-soft)', 'var(--purple-soft)', 'var(--primary-soft)']

const view = ref('loading') // loading | error | form | list
const fatal = ref({ title: '', text: '', retry: false })
const me = ref(null) // { profileComplete, name, message, status }
const people = ref([]) // [{ name, message, linkedinUrl, host }]
const event = ref(null)

const form = ref({ name: '', message: '' })
const formError = ref('')
const fieldErrors = ref({})
const saving = ref(false)
const justSaved = ref(false)

// ---- visited people (who they already opened) ----
// Browser storage can be missing (private mode...): then visited marks just last until reload.
function loadVisited() {
  try {
    return new Set(JSON.parse(localStorage.getItem(VISITED_KEY)) || [])
  } catch {
    return new Set()
  }
}
const visited = ref(loadVisited())
function markVisited(person) {
  visited.value.add(person.linkedinUrl)
  try {
    localStorage.setItem(VISITED_KEY, JSON.stringify([...visited.value]))
  } catch {
    // no storage: fine
  }
}
const toVisit = computed(() => people.value.filter((p) => !visited.value.has(p.linkedinUrl)))
const alreadyVisited = computed(() => people.value.filter((p) => visited.value.has(p.linkedinUrl)))

// ---- small helpers for the cards ----
function initials(name) {
  return name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((word) => [...word][0]) // [...word] so an accented first letter stays whole
    .join('')
    .toUpperCase()
}
function tone(name) {
  let hash = 0
  for (const ch of name) hash = (hash * 31 + ch.codePointAt(0)) >>> 0
  return TONES[hash % TONES.length]
}

// ---- errors that end the page (wrong link, other phone, replaced session...) ----
// The backend's message is already translated to the phone's language: we show it as is.
function fail(e) {
  const titles = {
    404: 'Enlace no válido',
    409: 'Este código ya se ha usado',
    403: 'Esta página es de otro móvil',
    410: 'Se ha iniciado una nueva sesión',
  }
  if (e.status === undefined) {
    fatal.value = {
      title: 'Sin conexión',
      text: 'Comprueba tu conexión e inténtalo de nuevo.',
      retry: true,
    }
  } else {
    fatal.value = {
      title: titles[e.status] ?? 'Algo ha salido mal',
      text: e.message,
      retry: !titles[e.status],
    }
  }
  view.value = 'error'
}
const ENDS_PAGE = [403, 404, 409, 410]

// ---- loading ----
async function load() {
  view.value = 'loading'
  try {
    me.value = await api(`/api/attendees/${props.token}`)
    if (me.value.profileComplete) {
      await loadList()
      view.value = 'list'
    } else {
      startForm()
    }
  } catch (e) {
    fail(e)
  }
}
async function loadList() {
  people.value = await api(`/api/attendees/${props.token}/list`)
}

// quiet background refresh: new people, and my own status (the organiser may flag/approve me)
async function refresh() {
  if (view.value !== 'list' || document.visibilityState !== 'visible') return
  try {
    me.value = await api(`/api/attendees/${props.token}`)
    await loadList()
  } catch (e) {
    if (ENDS_PAGE.includes(e.status)) fail(e)
    // anything else (bad signal for a moment): try again next round
  }
}

// ---- the form ----
function startForm() {
  form.value = { name: me.value?.name ?? '', message: me.value?.message ?? '' }
  formError.value = ''
  fieldErrors.value = {}
  view.value = 'form'
}
async function save() {
  saving.value = true
  formError.value = ''
  fieldErrors.value = {}
  try {
    me.value = await api(`/api/attendees/${props.token}/profile`, {
      method: 'POST',
      body: { name: form.value.name.trim(), message: form.value.message.trim() },
    })
    await loadList()
    view.value = 'list'
    justSaved.value = true
    setTimeout(() => (justSaved.value = false), SAVED_BANNER_MS)
    window.scrollTo(0, 0)
  } catch (e) {
    if (e.status === 400 && e.fields) fieldErrors.value = e.fields
    else if (ENDS_PAGE.includes(e.status)) fail(e)
    else formError.value = e.message || 'No se ha podido guardar. Inténtalo de nuevo.' // 422 lands here
  } finally {
    saving.value = false
  }
}

let refreshTimer = null
onMounted(async () => {
  load()
  refreshTimer = setInterval(refresh, REFRESH_MS)
  document.addEventListener('visibilitychange', refresh) // back to the tab = fresh list at once
  try {
    event.value = await api('/api/event')
  } catch {
    // event info is decoration: the page works without it
  }
})
onBeforeUnmount(() => {
  clearInterval(refreshTimer)
  document.removeEventListener('visibilitychange', refresh)
})
</script>

<template>
  <main class="phone">
    <header class="top">
      <img :src="logoHeader" alt="One2Many" class="logo" />
      <div v-if="event?.name" class="event">
        <strong>{{ event.name }}</strong>
        <a v-if="event.linkedinUrl" :href="event.linkedinUrl" target="_blank" rel="noopener">
          Ver el evento en LinkedIn ↗
        </a>
      </div>
    </header>

    <!-- ===== loading ===== -->
    <p v-if="view === 'loading'" class="muted center">Cargando…</p>

    <!-- ===== something ends the page ===== -->
    <section v-else-if="view === 'error'" class="card state">
      <h1>{{ fatal.title }}</h1>
      <p>{{ fatal.text }}</p>
      <button v-if="fatal.retry" class="btn btn-primary" @click="load">Reintentar</button>
    </section>

    <!-- ===== the form ===== -->
    <form v-else-if="view === 'form'" class="card form" novalidate @submit.prevent="save">
      <h1>{{ me?.profileComplete ? 'Edita tu frase' : 'Preséntate' }}</h1>
      <p class="muted">Así te verán los demás. Tu perfil de LinkedIn se añade solo.</p>

      <!-- the name is typed once; after that only the organiser can correct it -->
      <template v-if="!me?.profileComplete">
        <label class="field">
          <span>Nombre y apellido</span>
          <input v-model="form.name" :maxlength="NAME_MAX" autocomplete="name" required />
        </label>
        <small class="name-note">Escríbelo con cuidado: después no se puede cambiar.</small>
        <small v-if="fieldErrors.name" class="field-error">{{ fieldErrors.name }}</small>
      </template>
      <p v-else class="fixed-name"><strong>{{ me.name }}</strong></p>

      <label class="field">
        <span>Una frase sobre ti <em class="optional">(opcional)</em></span>
        <textarea
          v-model="form.message"
          :maxlength="MESSAGE_MAX"
          rows="3"
          placeholder="Ej.: Estudiante de DAW, me encanta el backend y busco prácticas"
        ></textarea>
      </label>
      <div class="char-count" :class="{ near: form.message.length > MESSAGE_MAX - 10 }">
        {{ form.message.length }}/{{ MESSAGE_MAX }}
      </div>
      <small v-if="fieldErrors.message" class="field-error">{{ fieldErrors.message }}</small>

      <div v-if="formError" class="alert warn">
        <span class="alert-icon">!</span>
        <div>{{ formError }}</div>
      </div>

      <div class="actions">
        <button
          v-if="me?.profileComplete"
          type="button"
          class="btn btn-secondary"
          @click="view = 'list'"
        >
          Cancelar
        </button>
        <button type="submit" class="btn btn-primary" :disabled="saving || !form.name.trim()">
          {{ saving ? 'Guardando…' : 'Guardar y ver la lista' }}
        </button>
      </div>
    </form>

    <!-- ===== the list ===== -->
    <template v-else-if="view === 'list'">
      <div v-if="me.status === 'FLAGGED'" class="alert warn">
        <span class="alert-icon">!</span>
        <div>
          Tu presentación está oculta: el organizador la ha marcado. Edítala para volver a
          aparecer.
          <button class="link-btn" @click="startForm">Editar ahora</button>
        </div>
      </div>
      <div v-else-if="me.status === 'PENDING_REVIEW'" class="alert info">
        <span class="alert-icon">i</span>
        <div>Tu cambio está pendiente de revisión. Aparecerás cuando el organizador lo apruebe.</div>
      </div>
      <div v-else-if="justSaved" class="alert ok">
        <span class="alert-icon">✓</span>
        <div>
          <strong>Perfil guardado.</strong>
          {{
            people.length
              ? `Te damos la bienvenida: ya puedes conectar con ${people.length} ${people.length === 1 ? 'asistente' : 'asistentes'}.`
              : 'Te damos la bienvenida al evento.'
          }}
        </div>
      </div>

      <section>
        <h2 class="section-title">Así te ven los demás</h2>
        <div class="person card me-card">
          <span class="avatar" :style="{ background: tone(me.name) }">{{ initials(me.name) }}</span>
          <div class="person-body">
            <strong class="person-name">{{ me.name }}</strong>
            <p v-if="me.message" class="person-msg">{{ me.message }}</p>
          </div>
          <button class="btn btn-secondary btn-small" @click="startForm">Editar</button>
        </div>
      </section>

      <section>
        <h2 class="section-title">
          Por conocer <span class="count">{{ toVisit.length }}</span>
        </h2>
        <p v-if="toVisit.length" class="muted hint">
          Toca a alguien para abrir su LinkedIn y conectar.
        </p>
        <p v-else class="muted empty">
          {{
            people.length
              ? '¡Ya has visitado a todos! Vuelve en un rato: sigue llegando gente.'
              : 'Aún no hay nadie más. ¡Vuelve en un rato!'
          }}
        </p>
        <ul class="people">
          <li v-for="p in toVisit" :key="p.linkedinUrl">
            <a
              class="person card"
              :href="p.linkedinUrl"
              target="_blank"
              rel="noopener"
              @click="markVisited(p)"
            >
              <span class="avatar" :style="{ background: tone(p.name) }">{{ initials(p.name) }}</span>
              <div class="person-body">
                <strong class="person-name">
                  {{ p.name }} <span v-if="p.host" class="badge">Anfitrión</span>
                </strong>
                <p v-if="p.message" class="person-msg">{{ p.message }}</p>
              </div>
              <span class="go" aria-hidden="true">↗</span>
            </a>
          </li>
        </ul>
      </section>

      <section v-if="alreadyVisited.length">
        <h2 class="section-title">
          Ya visitados <span class="count">{{ alreadyVisited.length }}</span>
        </h2>
        <ul class="people">
          <li v-for="p in alreadyVisited" :key="p.linkedinUrl">
            <a class="person visited" :href="p.linkedinUrl" target="_blank" rel="noopener">
              <span class="avatar">{{ initials(p.name) }}</span>
              <div class="person-body">
                <strong class="person-name">
                  {{ p.name }} <span v-if="p.host" class="badge">Anfitrión</span>
                </strong>
                <p v-if="p.message" class="person-msg">{{ p.message }}</p>
              </div>
              <span class="tag-visited">✓ Visitado</span>
            </a>
          </li>
        </ul>
      </section>
    </template>

    <footer class="foot">
      <RouterLink to="/about">¿Qué es One2Many?</RouterLink>
      <span>Sin cuentas · nada se guarda después del evento</span>
    </footer>
  </main>
</template>

<style scoped>
.phone {
  max-width: 520px;
  margin: 0 auto;
  padding: var(--space-4) var(--space-4) var(--space-6);
  display: grid;
  gap: var(--space-4);
}

/* header */
.top {
  display: grid;
  gap: var(--space-2);
  justify-items: center;
  text-align: center;
}
.logo {
  width: 170px;
}
.event {
  display: grid;
  gap: 2px;
}
.event a {
  font-size: 0.9rem;
  font-weight: 600;
}

.muted {
  color: var(--text-muted);
}
.center {
  text-align: center;
}
h1 {
  font-size: 1.5rem;
  margin: 0 0 var(--space-2);
}

/* error state */
.state {
  text-align: center;
  display: grid;
  gap: var(--space-3);
  justify-items: center;
}
.state p {
  margin: 0;
}

/* form */
.form {
  display: grid;
  gap: var(--space-2);
}
.form .muted {
  margin: 0 0 var(--space-2);
}
.field {
  display: grid;
  gap: var(--space-1);
  font-weight: 700;
  margin-top: var(--space-2);
}
.optional {
  font-weight: 400;
  font-style: normal;
  color: var(--text-muted);
}
.field input,
.field textarea {
  font: inherit;
  font-size: 1rem; /* 16px+: phones don't zoom in when the field gets focus */
  font-weight: 400;
  padding: var(--space-3);
  border: 2px solid #dfe3ee;
  border-radius: var(--radius);
  background: #fff;
  color: var(--text);
  resize: none;
}
.field input:focus,
.field textarea:focus {
  outline: none;
  border-color: var(--primary);
}
.name-note {
  color: var(--text-muted);
}
.fixed-name {
  margin: 0;
  font-size: 1.1rem;
}
.char-count {
  justify-self: end;
  font-size: 0.85rem;
  color: var(--text-muted);
}
.char-count.near {
  color: var(--coral);
  font-weight: 700;
}
.field-error {
  color: var(--danger);
  font-weight: 600;
}
.actions {
  display: flex;
  gap: var(--space-3);
  justify-content: flex-end;
  margin-top: var(--space-3);
}
.btn:disabled {
  opacity: 0.5;
  cursor: default;
}
.btn-small {
  min-height: 36px;
  padding: var(--space-1) var(--space-3);
  font-size: 0.9rem;
}

/* alerts (from Greg's UI board: soft background, round icon) */
.alert {
  display: flex;
  gap: var(--space-3);
  align-items: flex-start;
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius);
  border: 1px solid;
  font-weight: 500;
}
.alert-icon {
  flex: none;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 800;
  font-size: 0.85rem;
}
.alert.warn {
  background: #fff1e8;
  border-color: #fbd3bf;
}
.alert.warn .alert-icon {
  background: var(--coral);
}
.alert.info {
  background: #edf3fd;
  border-color: #cddcf5;
}
.alert.info .alert-icon {
  background: var(--primary-soft);
}
.alert.ok {
  background: #eaf7f3;
  border-color: #c4e8de;
}
.alert.ok .alert-icon {
  background: var(--teal);
}
.link-btn {
  display: block;
  margin-top: var(--space-1);
  padding: 0;
  border: 0;
  background: none;
  color: var(--primary);
  font-weight: 700;
  text-decoration: underline;
  cursor: pointer;
}

/* sections */
section {
  display: grid;
  gap: var(--space-2);
}
.section-title {
  margin: var(--space-2) 0 0;
  font-size: 1rem;
  font-weight: 800;
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.count {
  min-width: 24px;
  padding: 0 var(--space-2);
  border-radius: var(--radius-sm);
  background: #e9edf6;
  font-size: 0.85rem;
  text-align: center;
}
.hint,
.empty {
  margin: 0;
  font-size: 0.9rem;
}

/* people cards */
.people {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-3);
}
.person {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  color: var(--text);
  text-decoration: none;
}
a.person.card:active {
  transform: scale(0.98);
}
.avatar {
  flex: none;
  width: 48px;
  height: 48px;
  border-radius: var(--radius); /* rounded square, not a circle (Greg) */
  display: grid;
  place-items: center;
  font-weight: 800;
  font-size: 1.05rem;
  color: var(--text);
}
.person-body {
  flex: 1;
  min-width: 0; /* lets long words wrap instead of pushing the card wider */
}
.person-name {
  display: block;
  font-size: 1.05rem;
}
.person-msg {
  margin: 2px 0 0;
  color: var(--text-muted);
  font-size: 0.92rem;
  overflow-wrap: anywhere;
}
.go {
  flex: none;
  color: var(--primary);
  font-weight: 800;
}
.badge {
  display: inline-block;
  margin-left: var(--space-1);
  padding: 1px var(--space-2);
  border-radius: var(--radius-sm);
  background: #efe7f8;
  color: var(--purple);
  font-size: 0.75rem;
  font-weight: 700;
  vertical-align: middle;
}
.me-card {
  border: 2px solid #e3e9f6;
}

/* visited: softer, quieter (Greg: strong on top, light at the bottom) */
.person.visited {
  background: #f3f2f7;
  border: 1px solid #e7e5ee;
  border-radius: var(--radius-lg);
}
.person.visited .avatar {
  background: #cdd2de;
  color: #fff;
}
.person.visited .person-name {
  color: var(--text-muted);
}
.tag-visited {
  flex: none;
  font-size: 0.8rem;
  color: var(--text-muted);
  font-weight: 600;
}

/* footer */
.foot {
  margin-top: var(--space-4);
  display: grid;
  gap: var(--space-1);
  justify-items: center;
  text-align: center;
  font-size: 0.85rem;
  color: var(--text-muted);
}
.foot a {
  font-weight: 700;
}
</style>
