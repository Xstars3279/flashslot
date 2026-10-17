import { createRouter, createWebHashHistory } from 'vue-router'
import { session } from './session.js'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/', component: () => import('./views/ScheduleView.vue'), meta: { title: '设备预约', user: true } },
  { path: '/login', component: () => import('./views/LoginView.vue'), meta: { title: '登录' } },
  { path: '/register', component: () => import('./views/RegisterView.vue'), meta: { title: '手机号注册' } },
  { path: '/bookings', component: () => import('./views/BookingsView.vue'), meta: { title: '我的预约', login: true, user: true } },
  { path: '/waitlists', component: () => import('./views/WaitlistsView.vue'), meta: { title: '我的候补', login: true, user: true } },
  { path: '/notifications', component: () => import('./views/NotificationsView.vue'), meta: { title: '消息通知', login: true, user: true } },
  { path: '/account', component: () => import('./views/AccountView.vue'), meta: { title: '我的账号', login: true } },
  { path: '/admin/accounts', component: () => import('./views/AccountsView.vue'), meta: { title: '账号管理', admin: true, login: true } },
  { path: '/admin/devices', component: () => import('./views/DevicesView.vue'), meta: { title: '设备与日历', admin: true, login: true } },
  { path: '/admin/recovery', component: () => import('./views/RecoveryView.vue'), meta: { title: '异常处理', admin: true, login: true } },
  { path: '/admin/audits', component: () => import('./views/AuditsView.vue'), meta: { title: '操作审计', admin: true, login: true } },
  { path: '/:pathMatch(.*)*', redirect: '/' },
] })
router.beforeEach(to => {
  if (to.meta.login && !session.token) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.user && session.user?.role === 'ADMIN') return '/admin/devices'
  if (to.meta.admin && session.user?.role !== 'ADMIN') return '/'
  document.title = `${to.meta.title} · FlashSlot`
})
window.addEventListener('flashslot-session-expired', () => { router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } }) })
export default router
