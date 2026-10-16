<script setup>
import { onMounted, ref } from 'vue'
import { http } from '../api.js'
import { dateTime } from '../format.js'

const profile = ref(null), loading = ref(false), error = ref('')
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try { profile.value = await http.get('/me/profile') }
  catch (cause) { profile.value = null; error.value = cause.response?.data?.msg || '账号状态读取失败，请重试' }
  finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div class="page-heading">
    <div><h1>我的账号</h1><p>查看当前信用分和预约限制，按时使用每一次预约。</p></div>
    <el-button :loading="loading" @click="load">刷新状态</el-button>
  </div>
  <section class="panel" v-loading="loading" :aria-busy="loading" aria-label="账号状态">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="profile">
      <el-alert :type="profile.restricted ? 'warning' : 'success'" :closable="false" show-icon
        :title="profile.restricted ? '账号当前处于预约限制期' : '账号当前未受预约限制'"
        :description="profile.restricted ? `限制至 ${dateTime(profile.restrictionUntil)}，到期后可重新申请。已有预约请继续按时签到。` : '预约仍需满足设备开放、申请截止时间和时段冲突等条件。'" />
      <el-descriptions :column="1" border>
        <el-descriptions-item label="账号 ID">{{ profile.id }}</el-descriptions-item>
        <el-descriptions-item label="用户名">{{ profile.username }}</el-descriptions-item>
        <el-descriptions-item label="当前角色">{{ profile.role === 'ADMIN' ? '管理员' : '使用者' }}</el-descriptions-item>
        <el-descriptions-item label="信用分">{{ profile.creditScore }} / 100</el-descriptions-item>
        <el-descriptions-item label="限制截止">{{ dateTime(profile.restrictionUntil) }}{{ profile.restrictionUntil && !profile.restricted ? '（已到期）' : '' }}</el-descriptions-item>
        <el-descriptions-item label="状态更新时间">{{ dateTime(profile.serverTime) }}</el-descriptions-item>
      </el-descriptions>
      <div class="spaced-top toolbar"><RouterLink to="/">去预约设备</RouterLink><RouterLink to="/bookings">查看我的预约</RouterLink></div>
    </template>
  </section>
  <section class="panel spaced-top" aria-label="使用规则">
    <h2>预约与签到规则</h2>
    <p>可申请今天起 7 天内的时段，每格 30 分钟，一次连续 1 至 8 格，最长 4 小时。请在开始前至少 30 分钟完成申请。</p>
    <p>签到窗口为预约开始前 10 分钟至开始后 15 分钟，使用设备现场的有效签到码。逾期未签到会记为爽约并影响信用分。</p>
    <p>无法使用时，请及时取消预约；已经签到的预约可提前结束。候补每 30 分钟撮合一次，转正结果可在“我的候补”和消息通知中查看。</p>
  </section>
</template>
