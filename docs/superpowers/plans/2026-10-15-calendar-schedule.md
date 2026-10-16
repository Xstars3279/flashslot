# 日历与排期模块实施

按现有开发设计，继续完成一模块接口；不扩展预约、登录和缓存模块。

- GET /api/v1/resources/{id}/schedule?date=：普通查询 DTO；返回 Result<ScheduleView>，一天 48 格、revision/serverTime、FREE/BOOKED/HANDOFF/CLOSED/PAST；仅本人可以看到本人订单号。
- PUT /api/v1/admin/resources/{id}/days/{date}：普通 DTO 包含 revision、enabled、openStartSlot、openEndSlot、reason，直接返回 Result<ResourceDayView>；不使用 If-Match 或字符串版本解析。
- 只修改已生成日历，缺失返回 CALENDAR_NOT_READY；日期按上海，过去日历不可改。
- 查询使用只读一致快照。写事务按 resource FOR SHARE → resource_day FOR UPDATE → 当前管理员账号锁 → revision 检查与变更 → Outbox/审计，锁内获取判定时间。
- 关闭或缩短时，只要当日存在未结束有效订单、有效 WAITING 或未结束 HANDOFF 就拒绝；扩大开放范围允许。
- 数据库 revision+1、SCHEDULE_CHANGED Outbox、审计同事务；MQ 实际发布、Redis 缓存、每天滚动生成日历仍后续实现。
- 联合主键用完整键显式 SQL；单主键新增 Outbox 使用 BaseMapper；所有新增方法补用途、参数和约束注释。
- 先增加真实 MySQL 集成测试观察失败，再实现；覆盖排期状态/隐私/截止边界、参数权限、版本竞争、占用冲突、Outbox/审计回滚。

## 实施与验收

已完成上述两个接口及 DTO/VO、Service、Mapper 和事件持久化支持，新增方法及既有资源辅助方法补充注释。
先运行测试确认 9 个新场景失败，再实现接口；最终私有临时 MySQL 上 Maven clean verify 共 20 项测试全部通过，其中排期模块新增 9 项。
本轮未执行远程操作或修改现有数据库；不包含预约接口、登录、缓存、事件发布或滚动日历生成任务。
