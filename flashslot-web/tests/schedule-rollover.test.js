import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { ref, reactive, computed, watch, nextTick } from 'vue'
import { shanghaiDate as realShanghaiDate } from '../src/format.js'

function schedulePage(instant = '2026-10-17T15:59:59.000Z') {
  let now = Date.parse(instant), mounted, unmounted, timer, delay
  const pending = [], listeners = new Map()
  const document = { visibilityState: 'visible', addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: (name, fn) => { if (listeners.get(name) === fn) listeners.delete(name) } }
  const Clock = class extends Date { static now() { return now } }
  const shanghaiDate = offset => { const old = Date.now; Date.now = () => now; try { return realShanghaiDate(offset) } finally { Date.now = old } }
  const source = readFileSync(new URL('../src/views/ScheduleView.vue', import.meta.url), 'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  const context = { ref, computed, watch, Date: Clock, document, shanghaiDate, onMounted: fn => { mounted = fn }, onUnmounted: fn => { unmounted = fn },
    setTimeout: (fn, ms) => { timer = fn; delay = ms; return 1 }, clearTimeout: () => { timer = null },
    useRouter: () => ({}), ElMessage: {}, session: {}, showError() {}, allResources: async () => [{ id: 1 }],
    http: { get: (url, options) => new Promise(resolve => pending.push({ date: options.params.date, resolve })) },
  }
  vm.runInNewContext(source + '\nglobalThis.page = { dates, date, start, end, schedule, loading };', context)
  return { ...context.page, pending, listeners, mount: () => mounted(), unmount: () => unmounted?.(), get delay() { return delay }, get hasTimer() { return !!timer },
    advance: value => { now = Date.parse(value) }, tick: () => timer?.(), visible: () => listeners.get('visibilitychange')?.() }
}

test('Shanghai midnight rolls the seven-day window, resets expired selection, and rejects old responses', async () => {
  const page = schedulePage()
  await page.mount(); await nextTick()
  page.start.value = 20; page.end.value = 22
  assert.equal(page.pending[0].date, '2026-10-17')
  page.advance('2026-10-17T16:00:00.000Z'); page.tick(); await nextTick()
  assert.equal(page.date.value, '2026-10-18')
  assert.deepEqual(Array.from(page.dates.value), ['2026-10-18','2026-10-19','2026-10-20','2026-10-21','2026-10-22','2026-10-23','2026-10-24'])
  assert.equal(page.start.value, null)
  assert.equal(page.schedule.value, null)
  assert.equal(page.pending.length, 2)
  page.pending[1].resolve({ slots: [], marker: 'new' }); await nextTick(); await nextTick()
  page.pending[0].resolve({ slots: [], marker: 'old' }); await nextTick(); await nextTick()
  assert.equal(page.schedule.value.marker, 'new')
  page.unmount()
})

test('visibility recovery keeps a valid future date, reloads it and cleans up lifecycle resources', async () => {
  const page = schedulePage()
  await page.mount(); await nextTick()
  page.date.value = '2026-10-20'; await nextTick()
  page.start.value = 20; page.end.value = 22
  page.advance('2026-10-18T03:00:00.000Z'); page.visible(); await nextTick()
  assert.equal(page.date.value, '2026-10-20')
  assert.equal(page.dates.value[0], '2026-10-18')
  assert.equal(page.start.value, null)
  assert.equal(page.pending.length, 3)
  assert.equal(page.pending[2].date, '2026-10-20')
  assert.ok(page.delay > 0 && page.delay <= 86400000)
  page.unmount()
  assert.equal(page.hasTimer, false)
  assert.equal(page.listeners.size, 0)
  page.pending[2].resolve({ marker: 'after unmount' }); await nextTick(); await nextTick()
  assert.equal(page.schedule.value, null)
})
