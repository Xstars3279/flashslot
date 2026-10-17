import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'

function routing(role) {
  let guard, routes
  const source = readFileSync(new URL('../src/router.js', import.meta.url), 'utf8')
    .replace(/^import .*$/gm, '').replace('export default router', '')
  vm.runInNewContext(source, {
    session: { token: 'valid-token', user: { role } }, document: {}, window: { addEventListener() {} },
    createWebHashHistory() {},
    createRouter(options) { routes = options.routes; return { beforeEach(fn) { guard = fn } } },
  })
  return path => {
    const route = routes.find(route => route.path === path)
    return guard({ ...route, fullPath: path })
  }
}

test('administrator enters management and cannot open personal booking routes', () => {
  const navigate = routing('ADMIN')
  for (const path of ['/', '/bookings', '/waitlists', '/notifications'])
    assert.equal(navigate(path), '/admin/devices', path)
  assert.equal(navigate('/admin/devices'), undefined)
  assert.equal(navigate('/account'), undefined)
})

test('user can book and view personal flows but cannot enter management', () => {
  const navigate = routing('USER')
  for (const path of ['/', '/bookings', '/waitlists', '/notifications', '/account'])
    assert.equal(navigate(path), undefined, path)
  assert.equal(navigate('/admin/devices'), '/')
})
