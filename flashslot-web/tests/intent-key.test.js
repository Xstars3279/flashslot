import test from 'node:test'
import assert from 'node:assert/strict'
import { webcrypto } from 'node:crypto'
import { createIntentRunner } from '../src/intent.js'

test('HTTP environments without randomUUID can still submit unique idempotent writes', async () => {
  const original = Object.getOwnPropertyDescriptor(globalThis, 'crypto')
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value: { getRandomValues: webcrypto.getRandomValues.bind(webcrypto) } })
  const keys = []
  const send = createIntentRunner(async (path, body, key) => keys.push(key))
  try {
    await send('/bookings', { startSlot: 18 })
    await send('/bookings', { startSlot: 20 })
    assert.equal(new Set(keys).size, 2)
    for (const key of keys) assert.match(key, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  } finally {
    if (original) Object.defineProperty(globalThis, 'crypto', original)
    else delete globalThis.crypto
  }
})
