import assert from 'node:assert/strict'
import { performance } from 'node:perf_hooks'
import { writeFile } from 'node:fs/promises'

const base = process.env.FLASHSLOT_LOCAL_URL || 'http://127.0.0.1:18080'
const password = process.env.FLASHSLOT_LOCAL_PASSWORD
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(base) || !password) throw new Error('Set FLASHSLOT_LOCAL_PASSWORD and a loopback FLASHSLOT_LOCAL_URL. This script only tests the independent local demo.')
async function request(path, method = 'GET', body, token, key) {
  const response = await fetch(`${base}/api/v1${path}`, { method, headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(key ? { 'Idempotency-Key': key } : {}) }, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(15000) })
  return { status: response.status, body: await response.json() }
}
const tokens = []
for (const username of ['demo-user', 'demo-admin']) {
  const result = await request('/auth/login', 'POST', { username, password })
  assert.equal(result.status, 200)
  tokens.push(result.body.data.accessToken)
}
const created = await request('/admin/resources', 'POST', { name: `并发联调设备 ${new Date().toISOString().slice(11, 19)}`, location: '本机联调区', defaultOpenStartSlot: 16, defaultOpenEndSlot: 44, reason: '本机并发与消息闭环验证' }, tokens[1])
assert.equal(created.status, 201)
const resourceId = created.body.data.id
const date = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(Date.now() + 86400000))
const interval = { resourceId, bookDate: date, startSlot: 26, endSlot: 28, purpose: 'DEVICE_TEST' }
const started = performance.now()
const results = await Promise.all(Array.from({ length: 20 }, async (_, i) => {
  const before = performance.now()
  const result = await request('/bookings', 'POST', interval, tokens[i % 2], crypto.randomUUID())
  return { ...result, account: i % 2, elapsedMs: performance.now() - before }
}))
assert.equal(results.filter(r => r.status === 201).length, 1)
assert.equal(results.filter(r => r.status === 409).length, 19)
const winner = results.find(r => r.status === 201)
const loser = 1 - winner.account
const order = winner.body.data.orderNo
const candidate = await request('/waitlists', 'POST', interval, tokens[loser], crypto.randomUUID())
assert.equal(candidate.status, 201)
const candidateNo = candidate.body.data.waitlistNo
const cancelCandidate = await request(`/me/waitlists/${candidateNo}/cancel`, 'POST', {}, tokens[loser], crypto.randomUUID())
assert.equal(cancelCandidate.body.data.status, 'CANCELED')
const cancelKey = crypto.randomUUID()
const cancel = await request(`/bookings/${order}/cancel`, 'POST', {}, tokens[winner.account], cancelKey)
assert.equal(cancel.body.data.status, 'CANCELED')
const replay = await request(`/bookings/${order}/cancel`, 'POST', {}, tokens[winner.account], cancelKey)
assert.deepEqual(replay.body, cancel.body)
const schedule = await request(`/resources/${resourceId}/schedule?date=${date}`)
assert.equal(schedule.body.data.slots[26].state, 'HANDOFF')
assert.equal(schedule.body.data.slots[26].orderNo, null)
let notified = false
for (let i = 0; i < 16; i++) {
  const notifications = await request('/me/notifications', 'GET', undefined, tokens[loser])
  const message = notifications.body.data.items.find(item => item.waitlistNo === candidateNo && item.type === 'WAITLIST_CANCELED')
  if (message) {
    const read = await request(`/me/notifications/${message.id}/read`, 'POST', {}, tokens[loser])
    assert.equal(read.status, 200)
    notified = true; break
  }
  await new Promise(resolve => setTimeout(resolve, 500))
}
assert.ok(notified, 'Real RabbitMQ cancellation notification must arrive')
const sorted = results.map(r => r.elapsedMs).sort((a, b) => a - b)
const report = { checkedAt: new Date().toISOString(), environment: 'independent local demo, real MySQL/Redis/RabbitMQ', resourceId, concurrentRequests: 20, created: 1, rejectedConflicts: 19, batchElapsedMs: Math.round(performance.now() - started), requestP95Ms: Math.round(sorted[18]), candidateCanceled: true, cancellationReplayed: true, publicSchedulePrivate: true, notificationConsumedAndRead: true, scope: 'small concurrency smoke test, not a production capacity benchmark' }
await writeFile(new URL('../.run/flashslot-local-verification.json', import.meta.url), JSON.stringify(report, null, 2))
console.log(JSON.stringify(report, null, 2))
