import test from 'node:test'
import assert from 'node:assert/strict'
import { http } from '../src/api.js'
import { session } from '../src/session.js'

for (const publicPath of ['/auth/login', '/auth/register', '/auth/registration/code']) test(`public authentication ${publicPath} omits a previously expired bearer token`, async () => {
  session.token = 'expired-token'
  const original = http.defaults.adapter
  let authorization
  http.defaults.adapter = async config => {
    authorization = config.headers.Authorization
    return { status: 200, statusText: 'OK', headers: {}, config, data: { code: 1, data: { accessToken: 'new-token' } } }
  }
  try {
    const result = await http.post(publicPath, { username: 'example', password: 'example' })
    assert.equal(result.accessToken, 'new-token')
    assert.equal(authorization, undefined)
  } finally { session.token = ''; http.defaults.adapter = original }
})

for (const scenario of [
  { name: 'late 401 from an old token preserves the new login', path: '/me/profile', sentToken: 'old-token', currentToken: 'new-token', cleared: false },
  { name: '401 for the current token still expires its session', path: '/me/profile', sentToken: 'current-token', currentToken: 'current-token', cleared: true },
  { name: 'failed password login does not expire an existing session', path: '/auth/login', sentToken: 'current-token', currentToken: 'current-token', cleared: false },
]) {
  test(scenario.name, async () => {
    const originalAdapter = http.defaults.adapter
    const originalWindow = globalThis.window, originalStorage = globalThis.sessionStorage
    let fail, started, expired = 0
    const ready = new Promise(resolve => { started = resolve })
    globalThis.window = new EventTarget()
    globalThis.window.addEventListener('flashslot-session-expired', () => expired++)
    globalThis.sessionStorage = { removeItem() {} }
    http.defaults.adapter = config => new Promise((resolve, reject) => {
      fail = () => reject(Object.assign(new Error('Unauthorized'), { config, response: { status: 401 } }))
      started()
    })
    try {
      session.token = scenario.sentToken
      const pending = http.get(scenario.path).catch(error => error)
      await ready
      session.token = scenario.currentToken
      fail()
      await pending
      assert.equal(session.token, scenario.cleared ? '' : scenario.currentToken)
      assert.equal(expired, scenario.cleared ? 1 : 0)
    } finally {
      session.token = ''; http.defaults.adapter = originalAdapter
      globalThis.window = originalWindow; globalThis.sessionStorage = originalStorage
    }
  })
}
