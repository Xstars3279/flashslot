<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, showError } from '../api.js'
import { usePage } from '../composables/usePage.js'
import { session } from '../session.js'

const username = ref(''), saving = ref(false), dialog = ref(false), target = ref(null)
const form = reactive({ username: '', role: 'USER', password: '', reason: '' })
const page = usePage('/admin/users', () => ({ username: username.value.trim() || undefined }))
const { items, busy, hasMore } = page
function open(row = null) {
  target.value = row
  Object.assign(form, { username: '', role: 'USER', password: '', reason: '' })
  dialog.value = true
}
function clearPassword() { form.password = '' }
async function save() {
  if (saving.value) return
  if (!form.reason.trim() || (!target.value && !/^[A-Za-z0-9][A-Za-z0-9_.-]{2,63}$/.test(form.username))) { ElMessage.warning('请填写合法账号和操作原因'); return }
  if (form.password.length < 8 || new TextEncoder().encode(form.password).length > 72) { ElMessage.warning('密码至少 8 个字符，最多 72 个 UTF-8 字节'); return }
  saving.value = true
  try {
    if (target.value) await http.post(`/admin/users/${target.value.id}/password-reset`, { newPassword: form.password, reason: form.reason })
    else await http.post('/admin/users', { ...form })
    ElMessage.success(target.value ? '密码已重置，旧登录凭据已撤销' : '账号已创建')
    dialog.value = false; clearPassword(); await page.load()
  } catch (error) { showError(error) }
  finally { saving.value = false }
}
async function toggle(row) {
  if (saving.value) return
  try {
    const { value } = await ElMessageBox.prompt(`${row.enabled ? '停用后将撤销旧登录凭据。' : '启用后需重新登录。'}请输入操作原因。`, `${row.enabled ? '停用' : '启用'}账号 ${row.username}`, { inputValidator: value => !!value?.trim() && value.length <= 256 || '请填写 1 至 256 字操作原因', confirmButtonText: '确认', cancelButtonText: '取消' })
    saving.value = true
    await http.patch(`/admin/users/${row.id}/status`, { enabled: !row.enabled, reason: value })
    ElMessage.success('账号状态已更新'); await page.load()
  } catch (error) { if (error !== 'cancel' && error !== 'close') showError(error) }
  finally { saving.value = false }
}
onMounted(() => page.load())
</script>

<template>
  <div class="page-heading"><div><h1>账号管理</h1><p>创建使用者或管理员账号，管理账号状态与密码。</p></div><el-button type="primary" @click="open()">创建账号</el-button></div>
  <section class="panel" v-loading="busy">
    <el-form inline @submit.prevent="page.load()"><el-form-item label="用户名"><el-input v-model="username" clearable maxlength="64" placeholder="精确查询用户名" /></el-form-item><el-button native-type="submit" :loading="busy">查询 / 刷新</el-button></el-form>
    <el-table :data="items" empty-text="暂无匹配账号">
      <el-table-column prop="id" label="ID" width="80" /><el-table-column prop="username" label="用户名" min-width="180" />
      <el-table-column label="角色" width="110"><template #default="{ row }">{{ row.role === 'ADMIN' ? '管理员' : row.role === 'USER' ? '使用者' : '演示账号' }}</template></el-table-column>
      <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '已启用' : '已停用' }}</el-tag></template></el-table-column>
      <el-table-column label="操作" min-width="220"><template #default="{ row }"><el-button link type="primary" :disabled="saving" @click="open(row)">重置密码</el-button><el-button link :type="row.enabled ? 'danger' : 'primary'" :disabled="saving || row.username === session.user?.username" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column>
    </el-table>
    <el-button v-if="hasMore" class="spaced-top" :loading="busy" @click="page.load(true)">加载更多</el-button>
  </section>
  <el-dialog v-model="dialog" :title="target ? `重置密码：${target.username}` : '创建账号'" width="min(520px, 94vw)" :close-on-click-modal="!saving" :before-close="done => { if (!saving) done() }" @closed="clearPassword">
    <el-form label-position="top" @submit.prevent="save">
      <template v-if="!target"><el-form-item label="用户名"><el-input v-model="form.username" maxlength="64" autocomplete="off" /><small>3 至 64 位字母、数字、下划线、点或连字符，以字母或数字开头。</small></el-form-item><el-form-item label="角色"><el-select v-model="form.role"><el-option label="使用者" value="USER" /><el-option label="管理员" value="ADMIN" /></el-select></el-form-item></template>
      <el-alert v-else title="重置后该账号的所有旧登录凭据立即失效。" type="warning" :closable="false" />
      <el-form-item label="新密码"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" maxlength="72" /><small>至少 8 个字符，最多 72 个 UTF-8 字节。</small></el-form-item>
      <el-form-item label="操作原因"><el-input v-model="form.reason" type="textarea" maxlength="256" show-word-limit /></el-form-item>
      <el-button type="primary" native-type="submit" :loading="saving">{{ target ? '确认重置密码' : '创建账号' }}</el-button>
    </el-form>
  </el-dialog>
</template>
