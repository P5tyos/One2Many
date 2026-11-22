// Scanner detection, without any screen code (so it can be unit tested).
// The USB QR scanner behaves like a super-fast keyboard: it "types" the LinkedIn link and presses
// Enter. Characters that arrive fast and end with Enter are a scan; a human types much slower.
// Used by StationView.vue and DashboardView.vue, which only act on the results.

export const SCANNER_MAX_GAP_MS = 150 // scanner keys come fast (Shift+key for : / ? adds delay); humans are slower
export const SCANNER_MIN_LENGTH = 20 // a LinkedIn link is always longer than this
export const SCANNER_MIN_PARTIAL = 5 // shorter than a link but this long: a scan that got cut ("lectura incompleta")
export const SCANNER_MAX_AVG_MS = 50 // dashboard, inside a text box: inhuman average speed per key

// ---- station ----
// One keydown in, one decision out:
//   { preventDefault, action: 'reveal' | 'scan' | 'partial' | null, text }
// 'reveal' = Space pressed (show the personal QR); 'partial' = a cut-short scan to report to Greg.
export function createStationScanner() {
  let buffer = ''
  let lastKeyTime = 0

  return {
    keyDown(e, now) {
      if (e.code === 'Space') {
        // don't scroll the page; holding Space repeats keydown, but only the first one counts
        return { preventDefault: true, action: e.repeat ? null : 'reveal', text: '' }
      }
      if (e.ctrlKey || e.altKey || e.metaKey) {
        return { preventDefault: false, action: null, text: '' } // shortcuts (Ctrl+R, F5...) still work
      }
      const preventDefault = e.key === 'Enter' || e.key.length === 1 // the station owns the keyboard

      if (e.key === 'Enter') {
        const text = buffer
        buffer = ''
        if (text.length >= SCANNER_MIN_LENGTH) return { preventDefault, action: 'scan', text }
        if (text.length >= SCANNER_MIN_PARTIAL) return { preventDefault, action: 'partial', text }
        return { preventDefault, action: null, text: '' }
      }
      if (e.key.length === 1) {
        // a slow key = a human pressing keys, or the start of a new scan: start over
        if (now - lastKeyTime > SCANNER_MAX_GAP_MS) buffer = ''
        buffer += e.key
        lastKeyTime = now
      }
      return { preventDefault, action: null, text: '' }
    },
  }
}

// ---- dashboard ----
// Windows sends the scanner's "typing" to whichever window was clicked last, so the dashboard
// recognises a scan too and hands it to the station (manual-add mailbox).
// - Outside text boxes: fast keys + Enter, 20+ characters (same rule as the station).
// - Inside a text box the characters land in the box first, so the rule is stricter: 20+ characters
//   at an average < 50 ms per key + Enter. Then the box must be put back as it was and the Enter
//   cancelled, so a LinkedIn link never ends up in the event details by accident.
// `field` = the focused text box (anything with a .value) or null.
// Result: { preventDefault, stopPropagation, scan: text | null, restore: { field, value } | null }
export function createDashboardScanner() {
  let buffer = ''
  let lastKeyTime = 0
  let burstStart = 0
  let burstField = null // the text box the burst started in (null = no box)
  let burstFieldValueBefore = ''

  const nothing = () => ({ preventDefault: false, stopPropagation: false, scan: null, restore: null })

  return {
    keyDown(e, now, field) {
      if (e.ctrlKey || e.altKey || e.metaKey) return nothing()
      if (e.key === ' ') {
        buffer = '' // a scanner never sends spaces: this is a human
        return nothing()
      }

      if (e.key === 'Enter') {
        const text = buffer
        const avgGap = text.length > 1 ? (lastKeyTime - burstStart) / (text.length - 1) : Infinity
        buffer = ''
        const isScan = text.length >= SCANNER_MIN_LENGTH && (!field || avgGap < SCANNER_MAX_AVG_MS)
        if (isScan) {
          return {
            preventDefault: true, // no form submit
            stopPropagation: true,
            scan: text,
            restore: field && field === burstField ? { field, value: burstFieldValueBefore } : null,
          }
        }
        return { ...nothing(), preventDefault: !field }
      }

      if (e.key.length === 1) {
        const newBurst = now - lastKeyTime > SCANNER_MAX_GAP_MS || field !== burstField
        if (newBurst) {
          buffer = ''
          burstStart = now
          burstField = field
          burstFieldValueBefore = field ? field.value : '' // keydown comes before the letter lands
        }
        buffer += e.key
        lastKeyTime = now
        // outside boxes: Firefox must not start Quick Find on "/"
        return { ...nothing(), preventDefault: !field }
      }
      return nothing()
    },
  }
}
