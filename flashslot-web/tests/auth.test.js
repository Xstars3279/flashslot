import test from 'node:test'
import assert from 'node:assert/strict'
import { http } from '../src/api.js'
import { session } from '../src/session.js'

test('fresh password login omits a previously expired bearer token', async () => {
  session.token = 'expired-token'
  const original = http.defaults.adapter
  let authorization
  http.defaults.adapter = async config => {
    authorization = config.headers.Authorization
    return { status: 200, statusText: 'OK', headers: {}, config, data: { code: 1, data: { accessToken: 'new-token' } } }
  }
  try {
    const result = await http.post('/auth/login', { username: 'example', password: 'example' })
    assert.equal(result.accessToken, 'new-token')
    assert.equal(authorization, undefined)
  } finally { session.token = ''; http.defaults.adapter = original }
})
