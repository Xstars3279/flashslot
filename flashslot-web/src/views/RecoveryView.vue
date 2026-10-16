<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { runIntent, showError } from '../api.js'
import { usePage } from '../composables/usePage.js'
import { slotTime, statusName } from '../format.js'
const tab = ref('releases'), acting = ref(false)
const releases = usePage('/admin/release-jobs'), messages = usePage('/admin/messages')
const user = reactive({ id: null, delta: 0, reason: '' })
async function action(path, label) {
  let reason
  try { const result = await ElMessageBox.prompt(`${label}。请说明核查结果和操作原因。`, label, { inputType: 'textarea', inputValidator: value => !!value?.trim() && value.trim().length <= 255 || '请填写1至255字的原因', confirmButtonText: label, cancelButtonText: '取消' }); reason = result.value.trim() } catch { return }
  acting.value = true
  try { await runIntent(path, { reason }); ElMessage.success('操作已提交'); await refresh() } catch (error) { showError(error) } finally { acting.value = false }
}
async function accountAction(clear) {
  if (!user.id || !user.reason.trim() || (!clear && !user.delta)) { ElMessage.warning('请填写用户ID、操作原因和有效调整分值'); return }
  try { await ElMessageBox.confirm(clear ? `确认解除用户${user.id}的预约限制？` : `确认将用户${user.id}的信用分调整${user.delta > 0 ? '+' : ''}${user.delta}分？`, '确认账号操作', { confirmButtonText: '确认处理', cancelButtonText: '返回' }) } catch { return }
  acting.value = true
  try { const data = await runIntent(`/admin/users/${user.id}/${clear ? 'restriction-clear' : 'credit-adjustments'}`, clear ? { reason: user.reason.trim() } : { delta: user.delta, reason: user.reason.trim() }); ElMessage.success(`处理完成，当前信用分${data.creditScore}`); user.reason = '' } catch (error) { showError(error) } finally { acting.value = false }
}
async function refresh() { if (tab.value === 'releases') await releases.load(); if (tab.value === 'messages') await messages.load() }
onMounted(refresh)
</script>
<template><div class="page-heading"><div><h1>异常处理</h1><p>核查异常后恢复任务。所有操作都会保留原因和审计记录。</p></div><el-button @click="refresh">刷新</el-button></div><section class="panel"><el-tabs v-model="tab" @tab-change="refresh"><el-tab-pane label="待人工处理的释放任务" name="releases"><el-alert type="warning" :closable="false" title="重试后仍按每30分钟的扫描执行。人工释放会放回库存，不再为本任务撮合候补；归属异常时系统会拒绝操作。" /><el-table :data="releases.items.value" v-loading="releases.busy.value" empty-text="暂无需要人工处理的释放任务"><el-table-column prop="jobNo" label="任务号" min-width="200" show-overflow-tooltip /><el-table-column prop="resourceId" label="设备ID" width="90" /><el-table-column label="剩余区间" min-width="180"><template #default="{ row }">{{ row.bookDate }}<div>{{ slotTime(row.effectiveStartSlot) }} — {{ slotTime(row.endSlot) }}</div></template></el-table-column><el-table-column prop="lastError" label="失败摘要" min-width="170" show-overflow-tooltip /><el-table-column label="操作" width="180"><template #default="{ row }"><el-button link type="primary" :disabled="acting" @click="action(`/admin/release-jobs/${row.jobNo}/retry`, '恢复重试')">恢复重试</el-button><el-button link type="danger" :disabled="acting" @click="action(`/admin/release-jobs/${row.jobNo}/release`, '人工释放')">人工释放</el-button></template></el-table-column></el-table><div class="pagination"><el-button v-if="releases.hasMore.value" @click="releases.load(true)">加载更多</el-button></div></el-tab-pane>
<el-tab-pane label="投递失败的消息" name="messages"><el-alert type="info" :closable="false" title="恢复后保留原事件编号重新投递，已完成的消费不会重复生成通知。此列表展示发布失败的消息，消费端死信需单独核查。" /><el-table :data="messages.items.value" v-loading="messages.busy.value" empty-text="暂无投递失败的消息"><el-table-column prop="eventId" label="事件编号" min-width="210" show-overflow-tooltip /><el-table-column prop="eventType" label="事件类型" min-width="200" /><el-table-column prop="attempts" label="尝试次数" width="100" /><el-table-column prop="lastError" label="失败摘要" min-width="170" show-overflow-tooltip /><el-table-column label="操作" width="110"><template #default="{ row }"><el-button link type="primary" :disabled="acting" @click="action(`/admin/messages/${row.eventId}/retry`, '重新投递')">重新投递</el-button></template></el-table-column></el-table><div class="pagination"><el-button v-if="messages.hasMore.value" @click="messages.load(true)">加载更多</el-button></div></el-tab-pane>
<el-tab-pane label="信用分与预约限制" name="accounts"><div class="account-recovery"><h2>处理用户申诉</h2><p class="muted">信用分范围0至100。加回分数不会自动解除预约限制，请分别处理。</p><el-form label-position="top"><el-form-item label="用户ID"><el-input-number v-model="user.id" :min="1" :precision="0" /></el-form-item><el-form-item label="信用分调整值（正数增加，负数扣除）"><el-input-number v-model="user.delta" :min="-100" :max="100" :precision="0" /></el-form-item><el-form-item label="核查结果 / 操作原因"><el-input v-model="user.reason" type="textarea" :rows="3" maxlength="255" show-word-limit /></el-form-item><div class="toolbar"><el-button type="primary" :loading="acting" @click="accountAction(false)">调整信用分</el-button><el-button :loading="acting" @click="accountAction(true)">解除预约限制</el-button></div></el-form></div></el-tab-pane></el-tabs></section></template>
