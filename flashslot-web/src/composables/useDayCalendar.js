import { reactive, ref } from 'vue'

/** 保存目标来自成功加载的快照，日期切换和响应乱序均不能跨日写入。 */
export function useDayCalendar(fetchDay, updateDay) {
  const data = reactive({}), target = ref(null), busy = ref(false)
  let generation = 0
  async function load(resourceId, date) {
    const ticket = ++generation
    busy.value = true
    target.value = null
    try {
      const result = await fetchDay(resourceId, date)
      if (ticket !== generation) return false
      Object.assign(data, { revision: result.revision, enabled: result.enabled, openStartSlot: result.openStartSlot, openEndSlot: result.openEndSlot, reason: '' })
      target.value = { resourceId, date }
      return true
    } catch (error) { if (ticket === generation) throw error; return false }
    finally { if (ticket === generation) busy.value = false }
  }
  async function save(resourceId, date) {
    if (busy.value || !target.value || target.value.resourceId !== resourceId || target.value.date !== date) throw new Error('日历尚未加载完成，请刷新后再保存')
    return updateDay(target.value.resourceId, target.value.date, { ...data })
  }
  return { data, target, busy, load, save }
}
