import test from 'node:test'
import assert from 'node:assert/strict'
import { http } from '../src/api.js'
import { usePage } from '../src/composables/usePage.js'

test('changing unread filter during an outstanding request accepts only the latest response', async () => {
  const original = http.get
  const pending = []
  let unreadOnly = false
  http.get = (path, options) => new Promise(resolve => pending.push({ params: options.params, resolve }))
  try {
    const page = usePage('/me/notifications', () => ({ unreadOnly }))
    const oldRequest = page.load()
    unreadOnly = true
    const newRequest = page.load()
    assert.equal(pending.length, 2)
    assert.equal(pending[1].params.unreadOnly, true)
    pending[1].resolve({ items: [{ id: 'unread' }], nextCursor: null, hasMore: false })
    await newRequest
    pending[0].resolve({ items: [{ id: 'old-read' }], nextCursor: 'old-cursor', hasMore: true })
    await oldRequest
    assert.deepEqual(page.items.value, [{ id: 'unread' }])
    assert.equal(page.hasMore.value, false)
    assert.equal(page.busy.value, false)
  } finally {
    for (const request of pending) request.resolve({ items: [], hasMore: false })
    http.get = original
  }
})

test('load more after changing audit filters starts a fresh page instead of mixing results', async () => {
  const original = http.get
  const requests = []
  let operation = 'RESOURCE_CREATE'
  http.get = async (path, options) => {
    requests.push(options.params)
    return { items: [{ operation }], nextCursor: requests.length === 1 ? 100 : null, hasMore: requests.length === 1 }
  }
  try {
    const page = usePage('/admin/audits', () => ({ operation }))
    await page.load()
    operation = 'CREDIT_ADJUST'
    await page.load(true)
    assert.equal(requests[1].cursor, undefined)
    assert.equal(requests[1].operation, 'CREDIT_ADJUST')
    assert.deepEqual(page.items.value, [{ operation: 'CREDIT_ADJUST' }])
  } finally { http.get = original }
})

test('unchanged filters keep cursor pagination and append matching results', async () => {
  const original = http.get
  const requests = []
  http.get = async (path, options) => {
    requests.push(options.params)
    return { items: [{ id: requests.length }], nextCursor: 100, hasMore: true }
  }
  try {
    const page = usePage('/admin/audits', () => ({ operation: 'CREDIT_ADJUST' }))
    await page.load()
    await page.load(true)
    assert.equal(requests[1].cursor, 100)
    assert.deepEqual(page.items.value, [{ id: 1 }, { id: 2 }])
  } finally { http.get = original }
})
