/** 公网 HTTP 没有 randomUUID 时，仍使用浏览器安全随机数生成 UUID v4。 */
function newIntentKey() {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** 网络/服务暂不可用时保留同一请求键；明确拒绝或成功后，下一次点击表示新的操作意图。 */
export function createIntentRunner(send, newKey = newIntentKey) {
  const keys = new Map()
  return async function run(path, body = {}) {
    const fingerprint = JSON.stringify([path, body])
    if (!keys.has(fingerprint)) keys.set(fingerprint, newKey())
    try {
      const result = await send(path, body, keys.get(fingerprint))
      keys.delete(fingerprint)
      return result
    } catch (error) {
      if (error.response && error.response.status < 500) keys.delete(fingerprint)
      throw error
    }
  }
}
