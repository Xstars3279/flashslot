/** 网络/服务暂不可用时保留同一请求键；明确拒绝或成功后，下一次点击表示新的操作意图。 */
export function createIntentRunner(send, newKey = () => crypto.randomUUID()) {
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
