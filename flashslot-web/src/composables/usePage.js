import { ref } from 'vue'
import { http, showError } from '../api.js'
export function usePage(path, extra = () => ({})) {
  const items = ref([]), busy = ref(false), hasMore = ref(false), cursor = ref(null), metadata = ref({})
  let generation = 0
  let pageQuery = null, pendingQuery = null
  async function load(more = false) {
    const params = { ...extra() }
    const query = JSON.stringify(Object.entries(params).sort(([a], [b]) => a.localeCompare(b)))
    if (more === true && busy.value && query === pendingQuery) return
    const append = more === true && query === pageQuery && cursor.value != null
    if (query !== pageQuery) {
      items.value = []; cursor.value = null; hasMore.value = false; metadata.value = {}
    }
    const ticket = ++generation
    pendingQuery = query
    busy.value = true
    try {
      const data = await http.get(path, { params: { ...params, ...(append ? { cursor: cursor.value } : {}), limit: 20 } })
      if (ticket !== generation) return
      items.value = append ? [...items.value, ...data.items] : data.items
      pageQuery = query
      cursor.value = data.nextCursor; hasMore.value = data.hasMore; metadata.value = data
    } catch (error) { if (ticket === generation) showError(error) } finally { if (ticket === generation) busy.value = false }
  }
  return { items, busy, hasMore, metadata, load }
}
