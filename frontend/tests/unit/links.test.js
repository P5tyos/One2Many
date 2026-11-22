import { describe, it, expect } from 'vitest'
import { hasLink } from '@/lib/links'

// The phone form's link check must give the same answer as the backend
// (AttendeeService.containsLink). These are the backend tests' own examples
// (ReviewFixesTest "W. No links…"), plus a few more.

const BLOCKED = [
  // starts like a link
  'Mira https://evil.com',
  'http://algo',
  'HTTPS://EJEMPLO',
  'https://ana',
  'Visita www.ejemplo.es',
  'WWW.ALGO',
  'Ana www.ana.com',
  // looks like an address
  'evil.com',
  'escríbeme en evil.com',
  'ejemplo.com',
  'ana@gmail.com',
  'mi web: ana-dev.io',
  'ana.io',
  'github.com/ana',
  'proyecto.gal',
  'mi.es',
  'web.es',
  'bit.ly',
  'bit.ly/x',
  'bit.ly/abc',
  'prácticas.es', // lowercase ending, no space after the full stop: the backend blocks it too
  // short endings that are everyday words count only before a "/"
  't.me/ana',
  't.co/abc',
  'go.to/x',
  'bit.co/abc',
  'canal.tv/live',
  'sitio.gl/a',
]

const ALLOWED = [
  'Node.js y Vue.js',
  'Vue.js Fan',
  'ASP.NET Core y asp.net',
  'ADO.NET, VB.NET',
  'socket.io y Socket.IO',
  'prácticas.Es mi sueño',
  'Next.js, Three.js, D3.js',
  'Versión 1.2.3, nota 9.5',
  'C#, .NET y Java',
  'Hola.me llamo Ana',
  'Estudio DAM.me encanta',
  'Next step.to learn',
  'Trabajo en Vigo.co',
  'Hola.tv',
  'Ola.gl',
  'Visita EVIL.COM', // uppercase ending: not treated as an address (backend: same)
  'evil . com',
  '',
]

describe('link check (same rule as the backend)', () => {
  it.each(BLOCKED)('blocks %j', (text) => {
    expect(hasLink(text)).toBe(true)
  })

  it.each(ALLOWED)('allows %j', (text) => {
    expect(hasLink(text)).toBe(false)
  })

  it('handles missing text', () => {
    expect(hasLink(null)).toBe(false)
    expect(hasLink(undefined)).toBe(false)
  })

  it('gives the same answer when called repeatedly (no regex state between calls)', () => {
    for (let i = 0; i < 3; i++) {
      expect(hasLink('ana.io')).toBe(true)
      expect(hasLink('Node.js')).toBe(false)
      expect(hasLink('asp.net y ana.io')).toBe(true)
    }
  })
})
