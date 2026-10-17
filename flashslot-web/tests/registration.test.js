import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'

async function registration(send) {
  const source = readFileSync(new URL('../src/views/RegisterView.vue', import.meta.url), 'utf8')
    .match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  const navigations = [], logins = [], errors = []
  let mounted
  const context = {
    reactive: value => value, ref: value => ({ value }), computed: fn => ({ get value() { return fn() } }),
    onMounted(fn) { mounted = fn }, onUnmounted() {}, watch() {}, setInterval() {}, clearInterval() {}, Date, TextEncoder,
    useRouter: () => ({ replace: path => navigations.push(path) }),
    ElMessage: { warning: message => errors.push(message), success() {} },
    showError: error => errors.push(error.message), http: { post: send, get: async () => ({ registrationEnabled: true, registrationMode: 'DEVELOPMENT' }) }, signIn: result => logins.push(result),
  }
  vm.createContext(context)
  vm.runInContext(source + '\nglobalThis.page = { form, register, sendCode, developmentCode, remaining };', context)
  await mounted()
  Object.assign(context.page.form, { phone: '13912345678', code: '123456', password: 'test-password-123', confirmation: 'test-password-123' })
  return { ...context.page, navigations, logins, errors }
}
test('successful phone registration saves returned login and opens user home', async () => {
  const login = { accessToken: 'new-user-token', user: { role: 'USER' } }
  let request
  const page = await registration(async (path, body) => { request = { path, body }; return login })
  await page.register()
  assert.equal(request.path, '/auth/register')
  assert.equal(request.body.phone, '13912345678')
  assert.equal(request.body.role, undefined)
  assert.deepEqual(page.logins, [login])
  assert.deepEqual(page.navigations, ['/'])
  assert.equal(page.form.password, '')
})
test('failed code verification cannot save a login or navigate away', async () => {
  const page = await registration(async () => { throw new Error('验证码错误或已失效') })
  await page.register()
  assert.equal(page.logins.length, 0)
  assert.equal(page.navigations.length, 0)
  assert.equal(page.errors[0], '验证码错误或已失效')
})
test('development code is displayed and resend obeys cooldown', async () => {
  let calls = 0
  const page = await registration(async () => { calls++; return { developmentCode: '001234', retryAfter: 60 } })
  await page.sendCode()
  assert.equal(page.developmentCode.value, '001234')
  assert.equal(page.remaining.value, 60)
  await page.sendCode()
  assert.equal(calls, 1)
})
