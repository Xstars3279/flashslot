<script setup>
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http, showError } from '../api.js'
import { signIn } from '../session.js'

const router = useRouter()
const form = reactive({ phone: '', code: '', password: '', confirmation: '' })
const sending = ref(false), registering = ref(false), developmentCode = ref(''), codePhone = ref(''), nextSendAt = ref(0), now = ref(Date.now())
const remaining = computed(() => codePhone.value === form.phone ? Math.max(0, Math.ceil((nextSendAt.value - now.value) / 1000)) : 0)
const timer = setInterval(() => { now.value = Date.now() }, 1000)
onUnmounted(() => clearInterval(timer))
watch(() => form.phone, () => { form.code = ''; developmentCode.value = '' })
function validPhone() { return /^1[3-9]\d{9}$/.test(form.phone) }
async function sendCode() {
  if (sending.value || registering.value || remaining.value) return
  if (!validPhone()) { ElMessage.warning('请输入11位中国大陆手机号'); return }
  sending.value = true
  try {
    const result = await http.post('/auth/registration/code', { phone: form.phone })
    developmentCode.value = result.developmentCode
    codePhone.value = form.phone
    now.value = Date.now(); nextSendAt.value = now.value + result.retryAfter * 1000
  } catch (error) { showError(error) }
  finally { sending.value = false }
}
async function register() {
  if (registering.value || sending.value) return
  if (!validPhone() || !/^\d{6}$/.test(form.code)) { ElMessage.warning('请填写合法手机号和六位验证码'); return }
  if (form.password.length < 8 || new TextEncoder().encode(form.password).length > 72) { ElMessage.warning('密码至少8个字符，最多72个UTF-8字节'); return }
  if (form.password !== form.confirmation) { ElMessage.warning('两次密码不一致'); return }
  registering.value = true
  try {
    const result = await http.post('/auth/register', { phone: form.phone, code: form.code, password: form.password })
    signIn(result)
    form.password = form.confirmation = form.code = ''; developmentCode.value = ''
    ElMessage.success('注册成功，已自动登录'); router.replace('/')
  } catch (error) { showError(error) }
  finally { registering.value = false }
}
</script>

<template>
  <section class="login-layout">
    <div><span class="section-kicker">注册使用者账号</span><h1>用自己的账号，<br>安排设备时间。</h1><p>手机号注册成功后直接登录。<br>以后可使用手机号和密码登录。</p><p>管理员账号由管理员创建。</p></div>
    <div class="panel login-panel">
      <h2>手机号注册</h2>
      <el-alert type="warning" title="当前为联调模式" description="六位验证码直接显示在页面，不发送真实短信，也不验证手机号实际归属。验证码5分钟内有效，输错5次失效。" :closable="false" />
      <el-form label-position="top" @submit.prevent="register">
        <el-form-item label="手机号"><el-input v-model="form.phone" type="tel" inputmode="numeric" autocomplete="tel" maxlength="11" :disabled="sending || registering" placeholder="请输入11位手机号" /></el-form-item>
        <el-form-item label="验证码"><div class="registration-code-row"><el-input v-model="form.code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" :disabled="registering" placeholder="六位数字" /><el-button :loading="sending" :disabled="registering || remaining > 0" @click="sendCode">{{ remaining ? `${remaining}秒后重试` : '获取验证码' }}</el-button></div></el-form-item>
        <el-alert v-if="developmentCode" type="info" :title="`联调验证码：${developmentCode}`" :description="`仅用于 ${codePhone} 的本次注册，5分钟内有效。`" :closable="false" role="status" />
        <el-form-item label="密码"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" maxlength="72" :disabled="registering" /><small>至少8个字符，最多72个UTF-8字节。</small></el-form-item>
        <el-form-item label="确认密码"><el-input v-model="form.confirmation" type="password" show-password autocomplete="new-password" maxlength="72" :disabled="registering" /></el-form-item>
        <el-button class="full-width" native-type="submit" type="primary" :loading="registering" :disabled="sending">注册并登录</el-button>
      </el-form>
      <RouterLink class="browse-link" to="/login">已有账号，去登录</RouterLink>
    </div>
  </section>
</template>

<style scoped>
.registration-code-row { display: flex; width: 100%; gap: 10px; }
.registration-code-row .el-input { min-width: 0; flex: 1; }
</style>
