import axios from 'axios'
import { ElMessage } from 'element-plus'
import { session, signOut } from './session.js'
import { createIntentRunner } from './intent.js'

export const http = axios.create({ baseURL: '/api/v1', timeout: 15000 })
http.interceptors.request.use(config => {
  if (session.token && !['/auth/login', '/auth/register', '/auth/registration/code'].includes(config.url)) config.headers.Authorization = `Bearer ${session.token}`
  return config
})
http.interceptors.response.use(response => {
  if (response.data.code !== 1) return Promise.reject(new Error(response.data.msg || '操作未完成'))
  return response.data.data
}, error => {
  // 迟到的旧请求和密码登录失败不能注销之后建立的新会话。
  const authorization = axios.AxiosHeaders.from(error.config?.headers).get('Authorization')
  if (error.response?.status === 401 && session.token && authorization === `Bearer ${session.token}`) {
    signOut()
    window.dispatchEvent(new Event('flashslot-session-expired'))
  }
  return Promise.reject(error)
})
export const runIntent = createIntentRunner((path, body, key) => http.post(path, body, { headers: { 'Idempotency-Key': key } }))
export function showError(error) { ElMessage.error(error.response?.data?.msg || error.message || '连接失败，请重试') }

export async function logoutCurrent() {
  const token = session.token
  if (!token) return
  await http.post('/auth/logout', {})
  if (session.token === token) signOut()
}

export async function allResources(managed = false) {
  let cursor = 0
  const items = []
  do {
    const page = await http.get(managed ? '/admin/resources' : '/resources', { params: { cursor, limit: 100 } })
    items.push(...page.items)
    if (!page.hasMore) break
    cursor = page.nextCursor
  } while (cursor)
  return items
}
