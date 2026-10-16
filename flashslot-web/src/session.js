import { reactive } from 'vue'

let saved = null
try { saved = JSON.parse(sessionStorage.getItem('flashslot-session')) } catch { /* 失效的本机缓存按未登录处理。 */ }
export const session = reactive({ token: saved?.accessToken || '', user: saved?.user || null })
export function signIn(data) {
  session.token = data.accessToken
  session.user = data.user
  sessionStorage.setItem('flashslot-session', JSON.stringify(data))
}
export function signOut() {
  session.token = ''
  session.user = null
  sessionStorage.removeItem('flashslot-session')
}
