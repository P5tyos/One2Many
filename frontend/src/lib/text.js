// Small text and time helpers shared by the screens (no screen code, so they can be unit tested).

// soft tones for the initials avatars (rounded squares), picked by name so each person keeps theirs
export const TONES = [
  'var(--coral-soft)',
  'var(--teal-soft)',
  'var(--yellow-soft)',
  'var(--purple-soft)',
  'var(--primary-soft)',
]

// for searching: case- and accent-insensitive ("lucia" finds "Lucía")
export function plain(text) {
  return (text ?? '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()
}

// "Ana García López" → "AG"
export function initials(name) {
  return name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((word) => [...word][0]) // [...word] so an accented first letter or an emoji stays whole
    .join('')
    .toUpperCase()
}

// the same name always gets the same tone
export function tone(name, tones = TONES) {
  let hash = 0
  for (const ch of name) hash = (hash * 31 + ch.codePointAt(0)) >>> 0
  return tones[hash % tones.length]
}

// dashboard "Oculto" column: "hidden for 4 min"
export function hiddenFor(iso, nowMs) {
  if (!iso) return '–'
  const min = Math.floor((nowMs - new Date(iso).getTime()) / 60000)
  return min < 1 ? '<1 min' : `${min} min`
}

// dashboard login warning: loud (red) only while it matters, i.e. during a pause or when the last
// failed outside login is less than 15 minutes old; otherwise a quiet grey line
export const LOGIN_ALARM_MS = 15 * 60 * 1000 // same as the backend's counting window
export function loginAlarm(stats, nowMs, alarmMs = LOGIN_ALARM_MS) {
  return (
    !!stats.loginPausedUntil ||
    (!!stats.lastLoginFailureAt && nowMs - new Date(stats.lastLoginFailureAt).getTime() < alarmMs)
  )
}
