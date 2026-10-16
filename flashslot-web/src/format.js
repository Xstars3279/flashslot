export function slotTime(slot) { return `${String(Math.floor(slot / 2)).padStart(2, '0')}:${slot % 2 ? '30' : '00'}` }
export function dateTime(value) { return value ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'short', hour12: false }).format(new Date(value)) : '—' }
export function shanghaiDate(offset = 0) {
  const date = new Date(Date.now() + offset * 86400000)
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(date)
}
export const statusName = { CONFIRMED: '待签到', CHECKED_IN: '使用中', CANCELED: '已取消', NO_SHOW: '已爽约', COMPLETED: '已完成', WAITING: '候补中', MATCHED: '已转正', EXPIRED: '已过期', REJECTED: '未转正', PENDING: '等待处理', MANUAL_REVIEW: '待人工处理', RELEASED: '已释放', ACTIVE: '开放预约', PAUSED: '暂停预约', RETIRED: '已退役', NEW: '等待投递', FAILED: '投递失败' }
export function statusType(status) { return ['COMPLETED', 'MATCHED', 'ACTIVE', 'CHECKED_IN'].includes(status) ? 'success' : ['NO_SHOW', 'FAILED', 'MANUAL_REVIEW', 'REJECTED'].includes(status) ? 'danger' : ['WAITING', 'PENDING', 'CONFIRMED'].includes(status) ? 'warning' : 'info' }
