import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { ref, reactive, computed, compile, createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import ElementPlus, { ID_INJECTION_KEY } from 'element-plus'

function pageFor(view, capability) {
  let mounted
  const requests = []
  const file = readFileSync(new URL(`../src/views/${view}View.vue`, import.meta.url), 'utf8')
  const template = file.match(/<template>([\s\S]*?)<\/template>/)[1]
  const source = file.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  const context = { ref, reactive, computed, watch() {}, onMounted: fn => { mounted = fn }, onUnmounted() {}, setInterval() {}, clearInterval() {}, Date, TextEncoder,
    useRouter: () => ({}), useRoute: () => ({ query: {} }), ElMessage: { warning() {} }, signIn() {}, session: {}, showError() {},
    http: { get: async path => { requests.push(path); if (capability instanceof Error) throw capability; return capability }, post: async path => { requests.push(path); return {} } },
  }
  vm.runInNewContext(source + `\nglobalThis.page = { get enabled() { return typeof registrationEnabled === 'undefined' ? true : registrationEnabled.value }, registrationEnabled, registrationStatus, form, ${view === 'Register' ? 'sendCode, register, sending, registering, remaining, developmentCode, codePhone,' : 'busy, login,'} };`, context)
  return { page: context.page, requests, mount: async () => mounted?.(), render: async () => {
    const app = createSSRApp({ setup: () => context.page, render: compile(template) })
    app.use(ElementPlus)
    app.provide(ID_INJECTION_KEY, { prefix: 1, current: 0 })
    app.component('RouterLink', { props: ['to'], setup: (props, { slots }) => () => h('a', { href: props.to }, slots.default?.()) })
    return renderToString(app)
  } }
}

for (const view of ['Login', 'Register']) for (const capability of [{ registrationEnabled: false, registrationMode: 'DISABLED' }, new Error('offline')]) {
  test(`${view} fails closed when registration is disabled or capabilities are unavailable: ${capability instanceof Error ? 'offline' : 'disabled'}`, async () => {
    const harness = pageFor(view, capability)
    assert.equal(harness.page.enabled, false)
    await harness.mount()
    assert.equal(harness.page.enabled, false)
    if (view === 'Register') {
      Object.assign(harness.page.form, { phone: '13912345678', code: '123456', password: 'password123', confirmation: 'password123' })
      await harness.page.sendCode(); await harness.page.register()
    }
    assert.deepEqual(harness.requests, ['/auth/capabilities'])
  })
}
for (const view of ['Login', 'Register']) test(`${view} enables development registration only after capability response`, async () => {
  const harness = pageFor(view, { registrationEnabled: true, registrationMode: 'DEVELOPMENT' })
  assert.equal(harness.page.enabled, false)
  await harness.mount()
  assert.equal(harness.page.enabled, true)
})

for (const view of ['Login', 'Register']) test(`${view} renders a closed registration notice with no active registration controls`, async () => {
  const harness = pageFor(view, { registrationEnabled: false, registrationMode: 'DISABLED' })
  await harness.mount()
  const html = await harness.render()
  assert.match(html, /当前未开放注册，请联系管理员获取账号/)
  assert.doesNotMatch(html, /href="\/register"|普通用户可用手机号注册|当前为联调模式/)
  if (view === 'Register') {
    const inputs = html.match(/<input\b[^>]*>/g)
    assert.equal(inputs.length, 4)
    assert.ok(inputs.every(input => /disabled/.test(input)))
    assert.match(html, /<button[^>]*disabled[^>]*>[\s\S]*?获取验证码/)
  }
})

for (const view of ['Login', 'Register']) test(`${view} renders enabled development registration after confirmation`, async () => {
  const harness = pageFor(view, { registrationEnabled: true, registrationMode: 'DEVELOPMENT' })
  await harness.mount()
  const html = await harness.render()
  if (view === 'Login') assert.match(html, /href="\/register"/)
  else {
    assert.match(html, /当前为联调模式/)
    assert.ok(html.match(/<input\b[^>]*>/g).every(input => !/disabled/.test(input)))
  }
})
