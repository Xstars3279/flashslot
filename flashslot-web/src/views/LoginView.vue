<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http, showError } from '../api.js'
import { signIn, session } from '../session.js'
const form = reactive({ username: '', password: '' }), busy = ref(false)
const route = useRoute(), router = useRouter()
async function login() {
  if (!form.username.trim() || !form.password) return
  busy.value = true
  try { signIn(await http.post('/auth/login', form)); router.replace(session.user?.role === 'ADMIN' ? '/admin/devices' : typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') ? route.query.redirect : '/') }
  catch (error) { showError(error) } finally { busy.value = false }
}
</script>
<template><section class="login-layout"><div><span class="section-kicker">共享设备，专注研究</span><h1>下一段时间，<br>留给你的探索。</h1><p>查看设备开放时间，提前预约。<br>暂时没有空位，也可以加入候补。</p><div class="login-grid" aria-hidden="true"><i v-for="n in 24" :key="n" :class="{ filled: [3, 4, 8, 13, 14, 19].includes(n) }"></i></div></div><div class="panel login-panel"><h2>登录 FlashSlot</h2><p class="muted">普通用户可用手机号注册；管理员使用管理账号登录</p><el-form :model="form" label-position="top" @submit.prevent="login"><el-form-item label="手机号 / 账号"><el-input v-model="form.username" autocomplete="username" maxlength="64" placeholder="请输入手机号或账号" /></el-form-item><el-form-item label="密码"><el-input v-model="form.password" type="password" show-password autocomplete="current-password" maxlength="72" placeholder="请输入密码" /></el-form-item><el-button type="primary" native-type="submit" :loading="busy" :disabled="!form.username.trim() || !form.password" class="full-width">登录</el-button></el-form><RouterLink class="browse-link" to="/register">没有账号？手机号注册</RouterLink><RouterLink class="browse-link" to="/">先查看设备开放时间</RouterLink></div></section></template>
