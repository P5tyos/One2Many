import { describe, it, expect } from 'vitest'
import { canAcceptScan } from '@/lib/station'

describe('station lock', () => {
  it('accepts a new scan only when idle or showing a message', () => {
    expect(canAcceptScan('idle')).toBe(true)
    expect(canAcceptScan('message')).toBe(true)
  })

  it('is locked while a person is being handled', () => {
    for (const state of ['processing', 'ready', 'revealing', 'waiting']) {
      expect(canAcceptScan(state), state).toBe(false)
    }
  })

  it('treats anything unknown as locked', () => {
    expect(canAcceptScan(undefined)).toBe(false)
    expect(canAcceptScan('')).toBe(false)
  })
})
