import test from 'node:test'
import assert from 'node:assert/strict'
import { useDayCalendar } from '../src/composables/useDayCalendar.js'

test('late previous-day response cannot overwrite the selected day or redirect its save', async () => {
  const pending = [], writes = []
  const calendar = useDayCalendar((id, date) => new Promise(resolve => pending.push({ id, date, resolve })), async (...args) => writes.push(args))
  const first = calendar.load(1, '2026-10-16')
  const second = calendar.load(1, '2026-10-17')
  await assert.rejects(calendar.save(1, '2026-10-17'), /尚未加载/)
  pending[1].resolve({ revision: 0, enabled: true, openStartSlot: 20, openEndSlot: 40 })
  await second
  pending[0].resolve({ revision: 0, enabled: false, openStartSlot: 16, openEndSlot: 30 })
  await first
  assert.equal(calendar.data.openStartSlot, 20)
  assert.equal(calendar.data.enabled, true)
  await assert.rejects(calendar.save(1, '2026-10-16'), /尚未加载/)
  await calendar.save(1, '2026-10-17')
  assert.equal(writes.length, 1)
  assert.deepEqual(writes[0], [1, '2026-10-17', { revision: 0, enabled: true, openStartSlot: 20, openEndSlot: 40, reason: '' }])
})
