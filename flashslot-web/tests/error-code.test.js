import test from 'node:test'
import assert from 'node:assert/strict'
import { http } from '../src/api.js'

test('HTTP failures expose the stable business code to callers', async () => {
  const previous = http.defaults.adapter
  http.defaults.adapter = async config => {
    throw Object.assign(new Error('Conflict'), {
      config, response: { status: 409, data: { code: 0, errorCode: 'IDEMPOTENCY_CONFLICT', msg: '此请求键已用于其他参数' } },
    })
  }
  try {
    await assert.rejects(http.post('/bookings', {}), error => error.errorCode === 'IDEMPOTENCY_CONFLICT')
  } finally { http.defaults.adapter = previous }
})

test('business rejection in a successful HTTP response preserves code and response', async () => {
  const previous = http.defaults.adapter
  http.defaults.adapter = async config => ({ status: 200, headers: {}, config,
    data: { code: 0, errorCode: 'CHECKIN_EXPIRED', msg: '签到已截止' } })
  try {
    await assert.rejects(http.post('/bookings/example/check-in', {}), error =>
      error.errorCode === 'CHECKIN_EXPIRED' && error.response?.status === 200)
  } finally { http.defaults.adapter = previous }
})
