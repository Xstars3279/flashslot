<script setup>
import { onMounted, reactive, ref } from 'vue'
import { usePage } from '../composables/usePage.js'
import { dateTime } from '../format.js'
const filters = reactive({ actorId: '', operation: '', targetId: '' }), range = ref(null)
const { items, busy, hasMore, load } = usePage('/admin/audits', () => {
  const params = {}
  for (const [key, value] of Object.entries(filters)) if (value) params[key] = value
  if (range.value?.length === 2) { params.from = new Date(range.value[0]).toISOString(); params.to = new Date(range.value[1]).toISOString() }
  return params
})
onMounted(load)
</script>
<template><div class="page-heading"><div><h1>操作审计</h1><p>按操作人、业务对象和时间追溯管理变更。</p></div></div><section class="panel"><el-form inline class="audit-filters" @submit.prevent="load()"><el-form-item label="操作人ID"><el-input v-model="filters.actorId" placeholder="可选" inputmode="numeric" clearable /></el-form-item><el-form-item label="操作类型"><el-input v-model="filters.operation" placeholder="如 CREDIT_ADJUST" clearable maxlength="64" /></el-form-item><el-form-item label="业务对象"><el-input v-model="filters.targetId" placeholder="设备ID、订单号等" clearable maxlength="64" /></el-form-item><el-form-item label="时间范围"><el-date-picker v-model="range" type="datetimerange" start-placeholder="开始时间" end-placeholder="结束时间" /></el-form-item><el-form-item><el-button type="primary" native-type="submit" :loading="busy">查询审计</el-button></el-form-item></el-form><el-table :data="items" v-loading="busy" empty-text="暂无符合条件的操作记录"><el-table-column label="操作时间" min-width="170"><template #default="{ row }">{{ dateTime(row.createdAt) }}</template></el-table-column><el-table-column prop="actorUsername" label="操作人" min-width="140" /><el-table-column prop="operation" label="操作类型" min-width="190" /><el-table-column prop="targetId" label="业务对象" min-width="170" show-overflow-tooltip /><el-table-column prop="reason" label="操作原因" min-width="220" show-overflow-tooltip /></el-table><div class="pagination"><el-button v-if="hasMore" :loading="busy" @click="load(true)">加载更多</el-button></div></section></template>
