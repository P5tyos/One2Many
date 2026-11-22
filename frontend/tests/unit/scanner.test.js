import { describe, it, expect, beforeEach } from 'vitest'
import { createStationScanner, createDashboardScanner } from '@/lib/scanner'

const LINK = 'https://www.linkedin.com/in/ana-garcia?fromQR=1'

// one keydown event, like the browser sends it
function key(k, extra = {}) {
  return { key: k, code: k === ' ' ? 'Space' : 'Key' + k.toUpperCase(), repeat: false, ctrlKey: false, altKey: false, metaKey: false, ...extra }
}

// ======================================================================
describe('station scanner', () => {
  let scanner
  let t
  beforeEach(() => {
    scanner = createStationScanner()
    t = 1000
  })

  // type a text with a fixed gap between keys; returns the results of the keys
  function type(text, gap = 10) {
    return [...text].map((c) => {
      t += gap
      return scanner.keyDown(key(c), t)
    })
  }
  function enter(gap = 10) {
    t += gap
    return scanner.keyDown(key('Enter'), t)
  }

  it('a fast burst of 20+ characters ending with Enter is a scan', () => {
    const keys = type(LINK)
    const r = enter()
    expect(r).toEqual({ preventDefault: true, action: 'scan', text: LINK })
    expect(keys.every((k) => k.preventDefault && k.action === null)).toBe(true)
  })

  it('exactly 20 characters is a scan; 19 is a cut-short scan', () => {
    type('a'.repeat(20))
    expect(enter().action).toBe('scan')
    type('b'.repeat(19))
    expect(enter()).toEqual({ preventDefault: true, action: 'partial', text: 'b'.repeat(19) })
  })

  it('a short fast burst (5 to 19 characters) is reported as "lectura incompleta"', () => {
    type('https://ww')
    expect(enter()).toEqual({ preventDefault: true, action: 'partial', text: 'https://ww' })
  })

  it('fewer than 5 characters and Enter does nothing', () => {
    type('abcd')
    expect(enter()).toEqual({ preventDefault: true, action: null, text: '' })
  })

  it('a slow key starts the buffer again', () => {
    type('hello') // something typed earlier
    t += 400 // a pause
    type(LINK)
    expect(enter().text).toBe(LINK)
  })

  it('a gap of exactly 150 ms still counts as the same burst; 151 ms starts over', () => {
    type('x'.repeat(10))
    type('y'.repeat(10), 150)
    expect(enter(150).text).toBe('x'.repeat(10) + 'y'.repeat(10))

    type('x'.repeat(10))
    t += 1 // so the next key arrives 151 ms after the last one
    type('y'.repeat(10), 150)
    expect(enter().action).toBe('partial') // only the y's are left
  })

  it('human typing (slower than 150 ms per key) is never a scan', () => {
    type(LINK, 200)
    expect(enter(200).action).toBe(null) // only the last character is in the buffer
  })

  it('Enter empties the buffer', () => {
    type(LINK)
    enter()
    expect(enter().action).toBe(null)
  })

  it('keys with Ctrl, Alt or Meta are ignored and not cancelled (shortcuts keep working)', () => {
    for (const mod of ['ctrlKey', 'altKey', 'metaKey']) {
      const r = scanner.keyDown(key('r', { [mod]: true }), (t += 10))
      expect(r).toEqual({ preventDefault: false, action: null, text: '' })
    }
    // and they don't break a scan in progress
    type(LINK.slice(0, 20))
    scanner.keyDown(key('c', { ctrlKey: true }), (t += 10))
    type(LINK.slice(20))
    expect(enter().text).toBe(LINK)
  })

  it('Space reveals the QR (once per press) and never becomes part of a scan', () => {
    const first = scanner.keyDown(key(' '), (t += 10))
    expect(first).toEqual({ preventDefault: true, action: 'reveal', text: '' })
    const held = scanner.keyDown(key(' ', { repeat: true }), (t += 10))
    expect(held).toEqual({ preventDefault: true, action: null, text: '' })

    for (let i = 0; i < 25; i++) scanner.keyDown(key(' '), (t += 10))
    expect(enter().action).toBe(null) // 25 spaces + Enter are not a scan
  })

  it('other special keys (Shift, arrows, F5) are not cancelled and not collected', () => {
    for (const k of ['Shift', 'ArrowLeft', 'F5', 'Tab']) {
      expect(scanner.keyDown(key(k), (t += 10))).toEqual({ preventDefault: false, action: null, text: '' })
    }
    // Shift between characters (scanners press it for ":" "/" "?") doesn't break the burst
    for (const c of LINK) {
      scanner.keyDown(key('Shift'), (t += 5))
      scanner.keyDown(key(c), (t += 5))
    }
    expect(enter().text).toBe(LINK)
  })
})

// ======================================================================
describe('dashboard scanner', () => {
  let scanner
  let t
  beforeEach(() => {
    scanner = createDashboardScanner()
    t = 1000
  })

  // type into `field` (or outside any box if null); like the browser, a character lands in the
  // box AFTER keydown, unless keydown was cancelled
  function type(text, gap, field = null) {
    return [...text].map((c) => {
      t += gap
      const r = scanner.keyDown(key(c), t, field)
      if (field && !r.preventDefault) field.value += c
      return r
    })
  }
  function enter(field = null, gap = 10) {
    t += gap
    return scanner.keyDown(key('Enter'), t, field)
  }
  function applyRestore(r) {
    if (r.restore) r.restore.field.value = r.restore.value
  }

  describe('outside a text box', () => {
    it('a fast burst of 20+ characters + Enter is a scan', () => {
      type(LINK, 10)
      expect(enter()).toEqual({ preventDefault: true, stopPropagation: true, scan: LINK, restore: null })
    })

    it('characters are cancelled (no Firefox Quick Find on "/")', () => {
      expect(type('/', 10)[0].preventDefault).toBe(true)
    })

    it('human typing is not a scan, and the Enter is cancelled', () => {
      type(LINK, 200)
      expect(enter(null, 200)).toEqual({ preventDefault: true, stopPropagation: false, scan: null, restore: null })
    })

    it('a space empties the buffer (a scanner never sends spaces)', () => {
      type('a'.repeat(15), 10)
      scanner.keyDown(key(' '), (t += 10), null)
      type('b'.repeat(15), 10)
      expect(enter().scan).toBe(null) // only 15 b's left
    })

    it('Ctrl/Alt/Meta keys are left alone', () => {
      const r = scanner.keyDown(key('v', { ctrlKey: true }), (t += 10), null)
      expect(r).toEqual({ preventDefault: false, stopPropagation: false, scan: null, restore: null })
    })
  })

  describe('inside a text box', () => {
    it('a scanner-speed burst (avg < 50 ms) + Enter is caught, the box restored and Enter cancelled', () => {
      const box = { value: 'Encuentro DAM / DAW' }
      const keys = type(LINK, 10, box)
      expect(keys.every((k) => !k.preventDefault)).toBe(true) // the characters do land in the box…
      expect(box.value).toBe('Encuentro DAM / DAW' + LINK)

      const r = enter(box)
      expect(r.scan).toBe(LINK)
      expect(r.preventDefault).toBe(true) // …but the form is not submitted
      expect(r.stopPropagation).toBe(true)
      expect(r.restore.field).toBe(box)
      applyRestore(r)
      expect(box.value).toBe('Encuentro DAM / DAW') // …and the box is put back
    })

    it('normal human typing in a box is never caught, even fast typing', () => {
      // with spaces, and a 24-character word without any, at fast human speeds
      for (const text of ['Networking entre estudiantes', 'Desarrolladorjunior2026x']) {
        for (const gap of [60, 80, 120, 149]) {
          const box = { value: '' }
          type(text, gap, box)
          const r = enter(box, gap)
          expect(r, `${text} at ${gap} ms`).toEqual({ preventDefault: false, stopPropagation: false, scan: null, restore: null })
          expect(box.value).toBe(text) // everything typed stays in the box
          t += 1000
        }
      }
    })

    it('the limit is the average: 49 ms per key is a scan, 51 ms is not', () => {
      const a = { value: '' }
      type('x'.repeat(25), 49, a)
      expect(enter(a).scan).toBe('x'.repeat(25))

      t += 1000
      const b = { value: '' }
      type('y'.repeat(25), 51, b)
      expect(enter(b).scan).toBe(null)
    })

    it('fewer than 20 characters at scanner speed are not caught (the form works normally)', () => {
      const box = { value: '' }
      type('abcdefghij', 10, box)
      expect(enter(box)).toEqual({ preventDefault: false, stopPropagation: false, scan: null, restore: null })
      expect(box.value).toBe('abcdefghij')
    })

    it('moving to another box starts a new burst', () => {
      const a = { value: '' }
      const b = { value: 'before' }
      type('x'.repeat(10), 10, a)
      type('y'.repeat(15), 10, b) // same speed, different box
      expect(enter(b).scan).toBe(null) // only 15 characters belong to box b
    })

    it('the box is restored to what it held when the burst began', () => {
      const box = { value: '' }
      type('Vigo', 300, box) // a human typed this slowly
      t += 1000
      type(LINK, 8, box) // then the scanner fired into the same box
      const r = enter(box)
      applyRestore(r)
      expect(box.value).toBe('Vigo')
    })
  })
})
