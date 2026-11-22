import { describe, it, expect } from 'vitest'
import { plain, initials, tone, TONES, hiddenFor, loginAlarm, LOGIN_ALARM_MS } from '@/lib/text'

describe('initials', () => {
  it('takes the first letter of the first two words', () => {
    expect(initials('Ana García López')).toBe('AG')
    expect(initials('ana')).toBe('A')
    expect(initials('  María   José  ')).toBe('MJ')
  })

  it('keeps accented and special letters whole', () => {
    expect(initials('Élodie Ñúñez')).toBe('ÉÑ')
    expect(initials('łukasz żółć')).toBe('ŁŻ')
  })

  it('keeps an emoji whole instead of breaking it in half', () => {
    expect(initials('😀 Dev')).toBe('😀D')
    expect(initials('👩‍💻 Ana')).toBe('👩A') // only the first part of a combined emoji
  })
})

describe('avatar tone', () => {
  it('is always one of the tones', () => {
    for (const name of ['Ana', 'Pablo Fernández', '😀', 'x'.repeat(60)]) {
      expect(TONES).toContain(tone(name))
    }
  })

  it('is stable: the same name always gets the same tone', () => {
    expect(tone('Carmen Sánchez')).toBe(tone('Carmen Sánchez'))
    expect(tone('Hugo')).toBe(tone('Hugo'))
  })

  it('spreads different names over the tones', () => {
    const names = ['Ana', 'Pablo', 'Lucía', 'Javier', 'María', 'Diego', 'Carmen', 'Hugo', 'Iván', 'Nerea', 'Sara']
    expect(new Set(names.map((n) => tone(n))).size).toBeGreaterThanOrEqual(3)
  })

  it('can use another list of tones', () => {
    expect(tone('Ana', ['only'])).toBe('only')
  })
})

describe('accent-insensitive search text', () => {
  it('"lucia" finds "Lucía"', () => {
    expect(plain('Lucía Martínez')).toContain(plain('lucia'))
    expect(plain('LUCÍA')).toBe('lucia')
  })

  it('works with letters typed as two characters (e + accent)', () => {
    expect(plain('Lucía')).toBe('lucia')
    expect(plain('José')).toBe('jose')
  })

  it('treats ñ as n for searching', () => {
    expect(plain('Peña')).toBe('pena')
  })

  it('handles missing text', () => {
    expect(plain(null)).toBe('')
    expect(plain(undefined)).toBe('')
  })
})

describe('"Oculto" minutes on the dashboard', () => {
  const now = Date.parse('2026-11-25T08:30:00Z')
  const ago = (ms) => new Date(now - ms).toISOString()

  it('shows a dash when the person is not hidden', () => {
    expect(hiddenFor(null, now)).toBe('–')
  })

  it('shows "<1 min" during the first minute', () => {
    expect(hiddenFor(ago(0), now)).toBe('<1 min')
    expect(hiddenFor(ago(59_999), now)).toBe('<1 min')
  })

  it('shows whole minutes after that', () => {
    expect(hiddenFor(ago(60_000), now)).toBe('1 min')
    expect(hiddenFor(ago(4.5 * 60_000), now)).toBe('4 min')
    expect(hiddenFor(ago(12 * 60_000), now)).toBe('12 min')
  })
})

describe('login banner: red or grey', () => {
  const now = Date.parse('2026-11-25T08:30:00Z')
  const ago = (ms) => new Date(now - ms).toISOString()

  it('is not red without any failure', () => {
    expect(loginAlarm({ loginFailures: 0, loginPausedUntil: null, lastLoginFailureAt: null }, now)).toBe(false)
  })

  it('is red while a pause runs', () => {
    expect(loginAlarm({ loginPausedUntil: new Date(now + 60_000).toISOString(), lastLoginFailureAt: ago(10_000) }, now)).toBe(true)
  })

  it('is red when the last failure is under 15 minutes old', () => {
    expect(loginAlarm({ loginPausedUntil: null, lastLoginFailureAt: ago(LOGIN_ALARM_MS - 1) }, now)).toBe(true)
  })

  it('turns grey once the last failure is 15 minutes old', () => {
    expect(loginAlarm({ loginPausedUntil: null, lastLoginFailureAt: ago(LOGIN_ALARM_MS) }, now)).toBe(false)
    expect(loginAlarm({ loginPausedUntil: null, lastLoginFailureAt: ago(2 * 60 * 60_000) }, now)).toBe(false)
  })

  it('uses 15 minutes, the same as the backend counting window', () => {
    expect(LOGIN_ALARM_MS).toBe(15 * 60 * 1000)
  })
})
