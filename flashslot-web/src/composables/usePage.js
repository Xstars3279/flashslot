import { ref } from 'vue'
import { http, showError } from '../api.js'
export function usePage(path, extra = () => ({})) {
  const items = ref([]), busy = ref(false), hasMore = ref(false), cursor = ref(null), metadata = ref({})
  let generation = 0
  async function load(more = false) {
    if (more && busy.value) return
    const ticket = ++generation
    busy.value = true
    try {
      const data = await http.get(path, { params: { ...extra(), ...(more && cursor.value ? { cursor: cursor.value } : {}), limit: 20 } })
      if (ticket !== generation) return
      items.value = more ? [...items.value, ...data.items] : data.items
      cursor.value = data.nextCursor; hasMore.value = data.hasMore; metadata.value = data
    } catch (error) { if (ticket === generation) showError(error) } finally { if (ticket === generation) busy.value = false }
  }
  return { items, busy, hasMore, metadata, load }
}
