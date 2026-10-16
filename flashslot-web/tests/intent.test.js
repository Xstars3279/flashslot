import test from 'node:test'
import assert from 'node:assert/strict'
import { createIntentRunner } from '../src/intent.js'

test('lost response retries original intent with the same key, then starts a new intent', async () => {
  const sent = []
  let sequence = 0
  const run = createIntentRunner(async (path, body, key) => {
    sent.push(key)
    if (sent.length === 1) throw new Error('network interrupted after commit')
    return { orderNo: 'BK_1' }
  }, () => `key-${++sequence}`)
  await assert.rejects(run('/bookings', { resourceId: 1 }))
  assert.equal((await run('/bookings', { resourceId: 1 })).orderNo, 'BK_1')
  assert.deepEqual(sent, ['key-1', 'key-1'])
  await run('/bookings', { resourceId: 1 })
  assert.equal(sent[2], 'key-2')
})
test('changing parameters creates a different key; explicit rejection allows a new attempt', async () => {
  const sent = []
  const run = createIntentRunner(async (path, body, key) => {
    sent.push(key)
    throw { response: { status: 409 } }
  }, () => `key-${sent.length}`)
  await assert.rejects(run('/waitlists', { resourceId: 1 }))
  await assert.rejects(run('/waitlists', { resourceId: 1 }))
  await assert.rejects(run('/waitlists', { resourceId: 2 }))
  assert.equal(new Set(sent).size, 3)
})
