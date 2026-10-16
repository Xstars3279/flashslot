<script setup>
import { onMounted, ref, watch } from 'vue'
import { http, showError } from '../api.js'
import { usePage } from '../composables/usePage.js'
import { dateTime } from '../format.js'
const unreadOnly = ref(false), acting = ref(false)
const { items, busy, hasMore, metadata, load } = usePage('/me/notifications', () => ({ unreadOnly: unreadOnly.value }))
async function read(row) { acting.value = true; try { await http.post(`/me/notifications/${row.id}/read`); await load() } catch (error) { showError(error) } finally { acting.value = false } }
watch(unreadOnly, () => load()); onMounted(load)
</script>
<template><div class="page-heading"><div><h1>消息通知 <span class="count-label">{{ metadata.unreadCount || 0 }} 条未读</span></h1><p>预约进度、候补结果和使用提醒。</p></div><div class="toolbar"><el-switch v-model="unreadOnly" active-text="只看未读" /><el-button :loading="busy" @click="load()">刷新</el-button></div></div><section class="panel notification-list" v-loading="busy"><article v-for="row in items" :key="row.id" :class="{ unread: !row.readAt }"><span class="message-dot" aria-hidden="true"></span><div><h3>{{ row.title }}</h3><p>{{ row.content }}</p><small>{{ dateTime(row.createdAt) }}</small><div class="message-links"><RouterLink v-if="row.orderNo" to="/bookings">查看我的预约</RouterLink><RouterLink v-else-if="row.waitlistNo" to="/waitlists">查看我的候补</RouterLink></div></div><el-button v-if="!row.readAt" text type="primary" :disabled="acting" @click="read(row)">标为已读</el-button><span v-else class="muted">已读</span></article><el-empty v-if="!items.length && !busy" description="暂无消息，预约进度会出现在这里" /><div class="pagination"><el-button v-if="hasMore" @click="load(true)" :loading="busy">加载更多</el-button></div></section></template>
