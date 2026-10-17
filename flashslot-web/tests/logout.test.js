import test from 'node:test'
import assert from 'node:assert/strict'
import * as api from '../src/api.js'
import { session } from '../src/session.js'

test('logout revokes the bearer before clearing browser credentials', async () => {
  const original = api.http.defaults.adapter, storage = globalThis.sessionStorage
  const calls = []
  globalThis.sessionStorage = { removeItem() { calls.push('clear') } }
  session.token = 'copied-token'
  api.http.defaults.adapter = async config => {
    assert.equal(session.token, 'copied-token')
    assert.equal(config.url, '/auth/logout')
    assert.equal(config.headers.Authorization, 'Bearer copied-token')
    calls.push('revoke')
    return { status: 200, headers: {}, config, data: { code: 1 } }
  }
  try {
    await api.logoutCurrent()
    assert.deepEqual(calls, ['revoke', 'clear'])
    assert.equal(session.token, '')
  } finally { api.http.defaults.adapter = original; globalThis.sessionStorage = storage; session.token = '' }
})

test('failed revocation keeps credentials so the user can retry logout', async () => {
  const original = api.http.defaults.adapter
  session.token = 'retry-token'
  api.http.defaults.adapter = async () => { throw new Error('offline') }
  try {
    await assert.rejects(() => api.logoutCurrent(), /offline/)
    assert.equal(session.token, 'retry-token')
  } finally { api.http.defaults.adapter = original; session.token = '' }
})
