# FlashSlot 建表文件

这些文件是当前讨论结果对应的建表入口。先选择一个空的目标数据库，再按编号执行。它们不创建数据库、不删除或覆盖已有表；不要与 docs 中旧的 15 表设计附件重复执行。

| 顺序 | 文件 | 表与用途 |
| --- | --- | --- |
| 01 | `01_resource_calendar.sql` | resource、resource_day：设备身份、连续开放日历、设备日期行锁目标 |
| 02 | `02_user_booking.sql` | app_user、booking_order：账号、信用现值、完整预约订单 |
| 03 | `03_waitlist_release_occupation.sql` | waitlist_record、release_job、slot_occupation、user_slot_occupation：FIFO 候补、可恢复交接、设备/用户时间格唯一性 |
| 04 | `04_credit_ledger.sql` | credit_ledger：变更原因、实际分值差、业务事件防重 |
| 05 | `05_reliability_audit.sql` | api_idempotency、outbox_event、consumer_inbox、notification、admin_audit：请求回放、消息恢复、通知消费防重、操作追踪 |

合计 14 张表，字段已添加中文注释。03 在候补和任务创建后补上订单的循环外键；转正顺序为已有候补/任务 → 插入新订单 → 更新候补与任务，不关闭外键检查。

## 已确认与待确认规则

- 30 分钟一格；用户选择完整区间，后端生成集合并用一条普通 INSERT 批量写明细。
- 一台设备一个格不能被两个订单占用：slot_occupation 联合主键兜底。
- 用户已确认不能跨设备预约重叠时段：user_slot_occupation 联合主键兜底。
- 每台设备每天只有一个连续开放区间；不建多开放区间表或实验室表。
- 每日总时长限制仍待用户确认，因此没有创建 user_day_quota。订单保留 charged_slots，既可以在用户锁内汇总，也可在后续确认需要时增加计数表；当前 SQL 不会自动实施每日 4 小时上限。
- 单次最多 8 格（4 小时）、订单状态与用途枚举沿用 v1.1 设计。时间规则调整必须同步改约束和测试。

## 数据库保证与应用责任

唯一约束、外键和 CHECK 可以拒绝重复明细和无效字段，但不会自动创建所有时间格、检查用户权限或推进状态机。

预约事务必须依约定先锁 resource 和 resource_day，再锁相关订单/候补、用户及业务明细；全部时间格写入与订单同事务。任意一格冲突整体回滚，不能使用 INSERT IGNORE。

外键会取得父表锁，必须纳入锁序。api_idempotency 故意不设用户外键，因为它在资源锁之前写入，避免提前取得用户父表锁；应用仍要认证并校验用户。

占用行的 booking_id/release_job_id 外键仅保证关联记录存在，不证明设备、日期、用户和区间一致；跨表归属还要在业务事务中检查。取消释放必须匹配精确订单/任务身份，不能只按 user_id 删除。

version 和 revision 由应用在对应事务中增加，不会自动随 updated_at 递增。账号、设备与历史订单不物理删除，归档另行设计。

## 本地约束验证

需要 PowerShell 7，以及 PATH 中的 MySQL 8.0.16+ mysql.exe 和 mysqld.exe。在项目根目录运行：

```powershell
./scripts/verify-flashslot-schema.ps1
```

脚本生成独立临时数据目录，在回环地址随机端口启动私有 MySQL，执行全部编号 SQL 与测试数据，最后关闭该实例并清理自己的临时目录。它不连接已有数据库，也不在已有库上执行迁移。

测试覆盖：联合唯一约束、多格 INSERT 原子性、用户跨设备冲突、候补重复与重新申请、信用事件防重、请求幂等作用域、Inbox/通知防重、外键与 CHECK、事务回滚、取消后的 HANDOFF 与候补转正关联、旧订单不能误删新归属。

报告写入 `.run/flashslot-schema-verification.json`。这是数据库结构与 SQL 操作验证，不等于完整应用的状态机、并发压测或 MQ 故障恢复验证。
