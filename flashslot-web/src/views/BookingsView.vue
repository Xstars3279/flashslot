<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, runIntent, showError } from '../api.js'
import { usePage } from '../composables/usePage.js'
import { dateTime, slotTime, statusName, statusType } from '../format.js'
const { items, busy, hasMore, load } = usePage('/me/bookings')
const detail = ref(null), acting = ref(false)
async function open(order) { try { detail.value = await http.get(`/bookings/${order.orderNo}`) } catch (error) { showError(error) } }
async function cancel(order) {
  try { await ElMessageBox.confirm(order.status === 'CHECKED_IN' ? '确认提前结束使用？剩余未开始的时段将释放。' : '确认取消这次预约？', '取消预约', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '返回' }) } catch { return }
  acting.value = true
  try { await runIntent(`/bookings/${order.orderNo}/cancel`); ElMessage.success('预约已处理'); detail.value = null; await load() } catch (error) { showError(error) } finally { acting.value = false }
}
async function checkIn(order) {
  let code
  try { const result = await ElMessageBox.prompt('请输入设备现场展示的有效签到码', '设备签到', { inputType: 'textarea', inputValidator: value => !!value?.trim() || '请输入签到码', confirmButtonText: '签到', cancelButtonText: '取消' }); code = result.value.trim() } catch { return }
  acting.value = true
  try { await http.post(`/bookings/${order.orderNo}/check-in`, { checkInCode: code }); ElMessage.success('签到成功'); await load() } catch (error) { showError(error) } finally { acting.value = false }
}
onMounted(load)
</script>
<template><div class="page-heading"><div><h1>我的预约</h1><p>从预约到签到，查看每次设备使用的进度。</p></div><el-button @click="load()" :loading="busy">刷新</el-button></div><section class="panel" v-loading="busy"><el-table :data="items" empty-text="还没有预约，去设备预约选择一个时段吧"><el-table-column label="设备" min-width="160"><template #default="{ row }"><strong>{{ row.resourceName }}</strong><div class="muted">{{ row.resourceLocation }}</div></template></el-table-column><el-table-column label="预约时间" min-width="190"><template #default="{ row }">{{ row.bookDate }}<div>{{ slotTime(row.startSlot) }} — {{ slotTime(row.endSlot) }}</div></template></el-table-column><el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="statusType(row.status)">{{ statusName[row.status] }}</el-tag></template></el-table-column><el-table-column label="来源" width="100"><template #default="{ row }">{{ row.source === 'WAITLIST' ? '候补转正' : '直接预约' }}</template></el-table-column><el-table-column label="操作" min-width="230"><template #default="{ row }"><el-button link type="primary" @click="open(row)">详情</el-button><el-button v-if="row.status === 'CONFIRMED'" link type="primary" :disabled="acting" @click="checkIn(row)">签到</el-button><el-button v-if="['CONFIRMED', 'CHECKED_IN'].includes(row.status)" link type="danger" :disabled="acting" @click="cancel(row)">{{ row.status === 'CHECKED_IN' ? '提前结束' : '取消预约' }}</el-button></template></el-table-column></el-table><div class="pagination"><el-button v-if="hasMore" :loading="busy" @click="load(true)">加载更多</el-button></div></section><el-dialog :model-value="!!detail" title="预约详情" width="min(620px, 94vw)" @close="detail = null"><el-descriptions v-if="detail" :column="1" border><el-descriptions-item label="订单号">{{ detail.orderNo }}</el-descriptions-item><el-descriptions-item label="设备">{{ detail.resourceName }}</el-descriptions-item><el-descriptions-item label="状态">{{ statusName[detail.status] }}</el-descriptions-item><el-descriptions-item label="签到开放">{{ dateTime(detail.checkInOpenAt) }}</el-descriptions-item><el-descriptions-item label="签到截止">{{ dateTime(detail.checkInDeadline) }}</el-descriptions-item><el-descriptions-item label="实际签到">{{ dateTime(detail.checkedInAt) }}</el-descriptions-item><el-descriptions-item label="释放进度">{{ statusName[detail.releaseStatus] || '无需释放' }}</el-descriptions-item><el-descriptions-item label="结束原因">{{ detail.reasonText || detail.terminationReason || '—' }}</el-descriptions-item></el-descriptions></el-dialog></template>
