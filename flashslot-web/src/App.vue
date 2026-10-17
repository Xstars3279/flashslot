<script setup>
import { useRoute, useRouter } from 'vue-router'
import { Monitor, Calendar, Clock, Bell, User, Setting, Tools, Document, SwitchButton } from '@element-plus/icons-vue'
import { session } from './session.js'
import { computed, ref } from 'vue'
import { logoutCurrent, showError } from './api.js'
const loggingOut = ref(false)
const route = useRoute(), router = useRouter()
const isAdmin = computed(() => session.user?.role === 'ADMIN')
const links = computed(() => isAdmin.value ? [{ to: '/account', text: '账号与密码', icon: User }] : [{ to: '/', text: '设备预约', icon: Monitor }, { to: '/bookings', text: '我的预约', icon: Calendar }, { to: '/waitlists', text: '我的候补', icon: Clock }, { to: '/notifications', text: '消息通知', icon: Bell }, { to: '/account', text: '我的账号', icon: User }])
const adminLinks = [{ to: '/admin/accounts', text: '账号管理', icon: User }, { to: '/admin/devices', text: '设备与日历', icon: Setting }, { to: '/admin/recovery', text: '异常处理', icon: Tools }, { to: '/admin/audits', text: '操作审计', icon: Document }]
async function logout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try { await logoutCurrent(); if (!session.token) router.push('/') }
  catch (error) { showError(error) }
  finally { loggingOut.value = false }
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <RouterLink :to="isAdmin ? '/admin/devices' : '/'" class="brand"><span class="brand-mark"><i></i><i></i><i></i></span><span>FlashSlot<small>{{ isAdmin ? '设备管理工作台' : '实验设备预约' }}</small></span></RouterLink>
      <nav aria-label="主导航">
        <RouterLink v-for="link in links" :key="link.to" :to="link.to" :class="{ active: route.path === link.to }"><el-icon><component :is="link.icon" /></el-icon>{{ link.text }}</RouterLink>
        <template v-if="session.user?.role === 'ADMIN'"><p class="nav-label">管理工作台</p><RouterLink v-for="link in adminLinks" :key="link.to" :to="link.to" :class="{ active: route.path === link.to }"><el-icon><component :is="link.icon" /></el-icon>{{ link.text }}</RouterLink></template>
      </nav>
      <div class="sidebar-note"><template v-if="isAdmin"><strong>设备管理工作台</strong><p>账号、设备、异常处理<br>与操作审计。</p></template><template v-else><strong>让设备时间<br>用在需要的地方。</strong><p>预约、候补、签到<br>在一个地方完成。</p></template></div>
    </aside>
    <div class="main-shell">
      <header class="topbar"><span>{{ route.meta.title }}</span><div v-if="session.user" class="identity"><el-tag size="small" type="info">{{ session.user.role === 'ADMIN' ? '管理员' : '使用者' }}</el-tag><span>{{ session.user.username }}</span><el-button text :icon="SwitchButton" :loading="loggingOut" @click="logout">退出</el-button></div><el-button v-else @click="router.push('/login')">登录账号</el-button></header>
      <main><RouterView /></main>
      <footer>FlashSlot 设备共享预约 <span>时间按北京时间显示</span></footer>
    </div>
  </div>
</template>
