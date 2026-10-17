# 消息指标

应用通过 Micrometer 记录真实消息发布与消费边界；Prometheus 端点、来源网段控制与抓取配置见部署配置。以下为 Prometheus 导出名。标签只使用固定结果、通道和状态，不包含 eventId、userId、订单号、异常文本或 payload。

| 指标 | 标签 | 语义 |
| --- | --- | --- |
| `flashslot_message_publish_attempts_total` | `result=published/failed/skipped` | 每次调用 publish 的结果；领取不到事件为 skipped，broker ACK 且成功写回 PUBLISHED 才是 published，其余为 failed |
| `flashslot_message_publish_duration_seconds_count/sum/max` | 同上 | 包含领取、发送、等待 broker confirm 和状态写回的尝试耗时 |
| `flashslot_message_consume_attempts_total` | `channel=notification/release`, `result=success/failed` | 每次消费者处理尝试；重试、已完成重复投递均是新 attempt，不代表新增业务事件 |
| `flashslot_message_consume_duration_seconds_count/sum/max` | 同上 | 格式拒绝记录解析/校验耗时；合法消息记录业务处理耗时，不重复计数。通知消费正常返回后等待事务完成，提交成功为 success，回滚/提交失败为 failed |
| `flashslot_message_outbox_backlog` | 无 | PublishedEvent.names 支持的事件中 NEW + SENDING 数量，包含尚未到 next_run_at 的事件与未到期租约 |
| `flashslot_message_outbox_oldest_age_seconds` | 无 | 上述积压最早 created_at 到注入 Clock 当前时间的秒数；空积压为 0，时钟在创建时间之前时夹到 0 |
| `flashslot_message_outbox_events` | `status=new/sending/failed/archived` | new/sending 仅计可发布类型；failed/archived 计全部类型。ARCHIVED 历史事件不会进入可发布积压 |
| `flashslot_message_dead_letters` | 无 | dead_letter 中 FAILED、尚未恢复的记录数量 |
| `flashslot_message_release_jobs` | `status=pending/manual_review` | release_job 中待处理、人工复核任务数量 |
| `flashslot_message_release_oldest_age_seconds` | 无 | PENDING 任务最早 next_run_at 的逾期秒数，空任务或尚未到期为 0；数据库不可用时为 NaN |
| `flashslot_message_snapshot_available` | 无 | 数据库快照读取成功为 1，失败为 0 |

Counter 和 Timer 从进程启动后累计；服务重启会重置。发布 failed 包括 broker/序列化/数据库异常或旧租约未能写回，不表示这条事件永久失败。通知事务开始失败发生在方法进入之前，因此不会计作消费 attempt；release success 表示本次处理调用正常返回，不保证任务一定撮合成功，业务状态仍以 release_job Gauge 为准。

所有数据库 Gauge 共享一个惰性刷新、10 秒缓存的快照，每个实例最多每 10 秒执行一次专用聚合 SQL；没有新增定时任务。SQL 只读取状态、类型和创建时间，不读取消息体，也不逐行加载事件。数据库失败会缓存失败状态，所有业务 Gauge 返回 NaN，snapshot_available 返回 0，避免把数据库故障显示为空积压。成功缓存有效期内的短暂故障最迟在下次刷新时显现。最老年龄在有效快照的 created_at 上按当前 Clock 计算。

建议同时告警快照不可用、FAILED/死信/人工复核持续存在，以及积压年龄持续超过业务允许延迟。只有 snapshot_available 为 1 时才解释业务数量；不要将 NaN 替换为 0。发布失败率宜使用 `rate(...{result="failed"}[5m])` 除以 published+failed 的 rate；skipped 不进入失败率分母。消费失败率表示处理尝试失败率，不能解释成独立消息丢失率。

release 逾期年龄可发现 Outbox 已 PUBLISHED、消费者却未完成任务的停摆。撮合已改为取消即派发，建议规则为超过 10 分钟并持续 2 分钟；这也可能源于业务暂停，需要结合消费者运行状态与任务情况核查，不能直接推定消息丢失。

验证：`MessageProcessingMetricsTest` 通过真实服务调用检查结果、重试计数、事务回滚与 Timer；`MessageMetricsSnapshotTest` 检查缓存共享、年龄、数据库故障 NaN；私有临时 MySQL 环境的 `MessageMetricsIntegrationTest` 执行真实聚合 SQL，验证支持类型过滤、历史归档分离与 UTC 时间映射。

部署与访问控制见[部署说明](../deploy/flashslot/README.md)，[抓取配置](../deploy/flashslot/prometheus.yml)和[告警规则](../deploy/flashslot/message-alerts.yml)可通过 monitoring profile 启用。非法 JSON、版本与事件类型拒绝同样计入 failed；其后每次重试会再次计数。
