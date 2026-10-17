# FlashSlot 项目与消息队列审查

审查日期：2026-10-17（Asia/Shanghai）。对象是当前工作区，包含未提交和未跟踪代码，不限于最近一次提交。未连接服务器，未修改业务源码。

> 当前状态（2026-10-17）：下文保留首次审查快照，不能直接当作当前缺陷列表。代理限流、跨批扫描和消费失败先落库已在此前修复。本轮新增 V10、消息指标与受限抓取端点、死信上下文及固定镜像摘要。

| 原发现 | 当前处理 |
| --- | --- |
| 1 代理来源、2 扫描饥饿、4 死信可靠性 | 此前已修复并通过隔离故障回归 |
| 3 历史事件永久 NEW | 已登记历史类型改为 ARCHIVED，V10 迁移旧记录；默认保留 90 天后分批清理 |
| 5 死信上下文 | 保存来源队列、exchange/routing key、受控失败分类、投递次数与最近时间；管理页可查看 |
| 6 消息运行指标 | 发布/消费 Counter、Timer、积压/死信/任务 Gauge；包含解析拒绝；可选 Prometheus 抓取及 5 条告警规则 |
| 镜像可复现 | Compose 使用 tag@sha256，核验信息见 image-lock.json |

仍有边界：自动撮合依赖 RabbitMQ；PUBLISHED/Inbox 保留避免破坏防重，长期容量治理仍需另行设计；TLS、容量压测和实际部署告警演练尚未覆盖。Prometheus 未接外部告警通知。当前技术行为以项目 README、消息指标及部署文档为准。此次仅本地修改与验证，未连接服务器。

## 结论

通知与候补撮合两条 MQ 链路已形成基本闭环，适合当前单体 MVP。业务写入与 Outbox 同事务、发布确认、租约、消费去重、有限重试、死信落库和管理员恢复都已有实现。现有测试也覆盖了多种故障，并非仅有正常发送测试。

但不能把当前实现描述为“整个事件体系均已接入 MQ”或“所有失败都有可靠恢复保证”。排期变更与部分业务事件未被投递；自动撮合依赖 MQ 可用；死信转移可靠性、运行指标、数据保留策略仍有缺口。项目还有反向代理限流和到期扫描抗故障问题。

当前没有证据表明需要拆分微服务、引入 Redis 异步落订单或更换消息产品。应先修复具体缺陷，再完善现有结构。

## 主要发现（按优先级）

### 1. P1：反向代理下所有用户共享登录 IP 限额

- 位置：`flashslot/src/main/java/com/flashslot/auth/controller/AuthController.java:40`；`auth/service/LoginRateLimiter.java:18`；`deploy/flashslot/nginx.conf:8`。
- 当前登录用 `HttpServletRequest.getRemoteAddr()` 限制每 IP 每分钟 30 次。Compose 的外部请求都经 Nginx 转发，后端未配置可信代理和转发头解析，因此这里取得的是代理容器 IP。
- 触发：一个客户端在一分钟内耗尽 30 次登录请求，其他客户端即使使用不同账号，也会收到 429。成功和失败登录均计数。
- 建议：明确可信代理网段，由入口覆盖可信客户端地址头，后端仅接受可信代理提供的来源。不要直接无条件信任客户端自行提交的 X-Forwarded-For。注册验证码接口也使用 getRemoteAddr，启用时应一并处理。
- 验收：经真实 Nginx 的两个不同客户端独立计数；伪造来源头不能绕过；代理之外的直连行为有明确约束。
- 证据边界：代码与部署配置交叉验证，未在实际服务器执行代理流量实验。

### 2. P2：到期扫描存在失败记录占满批次后的饥饿

- 位置：`flashslot/src/main/java/com/flashslot/booking/mapper/BookingExpiryMapper.java:21`；`booking/service/impl/BookingExpiryServiceImpl.java:24`；`waitlist/mapper/WaitlistMapper.java:21`；`waitlist/service/impl/WaitlistExpiryServiceImpl.java:32`。
- 每轮固定取最早的 100 条。单条事务失败后仅写日志，原记录仍满足筛选条件，没有扫描游标、重试时间或隔离状态。
- 触发：最早 100 条持续发生局部故障，例如同一资源日历锁长期冲突或订单占用数据不一致，后面的健康记录就一直无法入选。失败记录不足 100 条时主要降低吞吐。
- 影响：后续订单的自动完成、爽约结算和释放任务生成，以及候补过期处理可能停滞。“一条失败不影响本批其余条目”并不等于“后续批次不会被阻塞”。
- 建议：每次扫描用 `(到期时间, ID)` 游标继续翻批，越过本轮失败项，并设时间/条数预算；或持久化 next_retry_at，异常记录退避并告警。异常订单仍应保留占用保护，不能为了推进扫描而强制终结。
- 验收：准备 101 条到期记录，前 100 条持续失败，第 101 条仍能处理；故障修复后前 100 条可以重试且不会重复扣分或释放。
- 证据边界：代码路径验证；本次未新增并执行上述故障注入测试。

### 3. P2：不投递的事件与待投递事件共用 Outbox，永久保留 NEW

- 位置：`flashslot/src/main/java/com/flashslot/messaging/service/ScheduleEventService.java:28`；`messaging/model/PublishedEvent.java:7`；`messaging/service/impl/OutboxPublisherServiceImpl.java:43`；`common/mapper/HistoryCleanupMapper.java:7`。
- `SCHEDULE_CHANGED`、`CREDIT_CHANGED`、`RELEASE_REQUESTED`、`RELEASE_COMPLETED`、`RELEASE_MATCHED` 等写入 Outbox，但发布器只支持通知类型与 `RELEASE_DISPATCHED`。未支持的事件永留 NEW，当前清理也不处理 Outbox。
- 影响：每次排期变化都会增加永久积压；NEW 状态混合“待投递”和“根本不会投递”，影响容量规划、最老待发布年龄及故障定位。现有测试明确断言 SCHEDULE_CHANGED 保持 NEW，说明这是已知边界，而非漏配一条绑定。
- 建议：先决定每类事件是否确实需要消息订阅。真正需要缓存订阅时，补路由、消费者和幂等策略；仅用于留痕的事件迁到独立事件历史表或明确归档状态，避免继续占用可投递 Outbox。现有排期通过数据库 revision 切换缓存键，不应为了“MQ 完整”再增加没有实际用途的消费者。
- 验收：每个可投递 event_type 有明确路由和消费者；历史事件不计入待发布积压；持续预约/取消后不产生无主 NEW。

### 4. P2：业务队列到死信队列之间仍有可靠性空窗

- 位置：`flashslot/src/main/java/com/flashslot/messaging/config/MessagingConfiguration.java:48`、`:93`、`:114`。
- 当前只声明 durable 队列，没有显式 quorum 类型或 at-least-once dead-letter 配置；消费失败通过 reject 交给 broker 的 DLX。
- durable、persistent 和原始发布 confirm 不能证明后续死信转移也可靠。默认 classic 队列的死信转发不提供相同的 publisher confirm 保证；死信交换机/路由缺失或目标不能接收时可能丢失失败消息。
- 影响：原 Outbox 已是 PUBLISHED，但 Inbox、通知和 dead_letter 都没有记录。当前没有按消费者核对这种缺口的补偿流程。
- 建议：选择并验证一种方案：①兼容版本上的 quorum 队列及 at-least-once dead-letter 策略；②消费者重试耗尽后先可靠保存失败原文，再允许结束原消息。另可增加按消费者定义的长时间未完成对账。已有队列改类型需要迁移计划，不能只改声明参数后直接启动。
- 验收：断开死信绑定、目标队列不可用、落库失败、broker 重启时，消息最终可恢复或有明确可见的待处理状态。
- 证据边界：配置及消息确认语义审查；本次未运行破坏 DLX 拓扑的故障实验，不代表观察到了线上丢消息。

### 5. P2：死信落库丢弃路由与失败上下文

- 位置：`flashslot/src/main/java/com/flashslot/messaging/listener/DeadLetterListener.java:14`；`messaging/service/DeadLetterStore.java:23`；`flashslot/src/main/resources/db/migration/V7__dead_letter.sql`。
- 接收器仅传 body；消费队列、原 exchange/routing key、x-death 次数与时间等信息未保存。可解析消息统一记录 CONSUMPTION_FAILED，不能区分数据库故障、约束失败或业务异常。
- 影响：管理员看到“消费失败，待核查依赖”，却缺少核查与决定是否恢复所需的依据。按 body 哈希去重也无法区分同一原文在不同消费目标的失败。
- 建议：保存受控的来源元数据、失败分类、首次/最近失败时间、累计次数和关联标识；具体异常在消费失败处脱敏记录，不依赖 DLX 自动提供 Java 异常。管理员列表继续只返回安全摘要，原文严格受控。
- 验收：同一事件在不同消费者失败可区分；数据库故障与版本错误可区分；不向普通用户暴露原文、凭据或内部堆栈。

### 6. P2：已引入指标依赖，但消息运行状态无法被有效监控

- 位置：`flashslot/pom.xml`；`flashslot/src/main/resources/application.yml:57`；`messaging/service/impl/OutboxPublisherServiceImpl.java:49`。
- 有 Prometheus registry 依赖，但 Web 暴露仅 health/info；业务代码没有消息相关 Counter/Timer/Gauge。当前 readiness 只依赖数据库，这是合理的可用性边界，却不能说明自动撮合是否正在推进。
- 影响：发布器关闭、消费停止、积压增长、失败任务增加时，应用仍可显示就绪，主要靠人工看日志和异常页发现问题。
- 建议：在受控内网暴露抓取端点，增加可投递 Outbox 最老年龄/数量、发送结果与耗时、过期租约、消费失败、死信数量、PENDING 释放任务年龄、MANUAL_REVIEW 数量；broker 监控 ready/unacked、连接阻塞和磁盘/内存告警。设置与半小时业务节奏相符的告警阈值。
- 验收：停发布器或消费者后能够告警；恢复后指标回落；未订阅历史事件不污染积压；eventId 不作为指标标签，避免高基数。

## MQ 使用完整性对照

| 环节 | 当前情况 | 判断 |
|---|---|---|
| 业务提交与事件持久化 | 同事务；事件保存失败回滚业务 | 已实现 |
| 发布领取 | 独立事务，60 秒租约，令牌隔离旧结果 | 已实现 |
| 发布可靠性 | persistent + correlated confirm + mandatory return | 已实现；仅证明 broker 接收 |
| 发布重试 | 退避，12 次后 FAILED，管理员原 ID 重投 | 已实现 |
| 通知消费 | 用户锁、notification 与 Inbox 同事务，提交后容器 AUTO ACK | 已实现；AUTO 不等于 no-ack |
| 撮合消费 | 每候选短事务，任务状态/占用归属/约束防重，Inbox 过滤完成指令 | 已实现；不承诺消息只投递一次 |
| 撮合调度 | 半小时派发，每分钟补偿，固定事件 ID，单次 20 步 | 已实现；保留当前业务周期 |
| 消费重试 | 最多 3 次，约 1s/2s，耗尽后 reject/DLX | 已实现；短暂数据库故障也可能进入死信 |
| 死信持久化 | 数据库提交后 ACK，保存失败重新入队 | 已实现；不覆盖进入 DLQ 之前的空窗 |
| 人工恢复 | 通知事务恢复；撮合重排 Outbox；幂等、权限和审计 | 已实现；重排成功不代表撮合完成 |
| 排期/信用等事件 | 仅落库，发布类型白名单不包含 | 未形成订阅闭环，需明确用途 |
| 端到端对账与告警 | 无专用消息指标和完成缺口对账 | 待补 |
| 消息历史保留与归档 | Outbox/Inbox/通知/死信无统一生命周期策略 | 待补 |

## 全项目其他改进

- **公网交付补 TLS**：仓库 Nginx 仅 listen 80。若直接按此公开提供服务，登录密码与 Bearer Token 缺少传输保护。可在入口终止 HTTPS；本次没有核实服务器是否已有外部 TLS 入口。
- **统一 MQ 故障语义和文档**：当前 MQ 停止不会回滚已提交预约，但 HANDOFF/PENDING 的自动撮合会等待 MQ。数据库补偿负责重新派发，不直接运行撮合。设计文档第 199/468/744 行附近仍含旧版“数据库扫描直接兜底”描述，文末和后端 README 已更新；应合并为一致的当前说明。是否增加 DB 直接执行的降级模式，应先明确恢复时的并发和运维语义，不建议擅自改变目前链路。
- **消息历史容量治理**：为 PUBLISHED Outbox、Inbox、已恢复死信及通知设置保留与归档策略。Inbox 防重窗口必须覆盖允许的最长重放期；不能先清 Inbox 却允许更老事件随时重放。原始死信应有大小限制和访问边界。
- **发布与消费容量实测**：发布器单线程逐条等待 confirm、每批 50 条、每轮预算 10 秒。正常流量下可能足够，但无压测数据证明。先测确认延迟、故障恢复速度及队列增长，再决定是否用有界并发。租约需覆盖真实发送上限；confirm timeout 只在 rabbit.send 返回后开始等待，不能当成整个发送过程的总超时。
- **多实例登录限流**：当前 LoginRateLimiter 是进程内状态，扩容后额度会按实例放大。准备多副本时迁到入口或共享限流服务，同时保留账号维度保护。
- **前端跨午夜日期刷新**：`flashslot-web/src/views/ScheduleView.vue:14` 的七天列表在组件创建时固定。页面跨上海午夜停留后，“今天”与可选日期不会自动更新；可在日期变化或页面重新可见时更新列表及排期。后端仍会校验真实预约窗口。
- **锁定部署产物**：Compose 使用 mysql:8.0、redis:7-alpine、rabbitmq:3-management-alpine 等浮动系列标签；与文档中的目标版本存在差异。记录实际测试版本并锁定补丁版本/digest，升级时做兼容和队列迁移验收，不能据标签推断当前运行版本。
- **建立持续验收入口**：默认 mvn test 可大量跳过集成测试。将私有隔离脚本纳入明确的 CI/发布验收，检查真实执行与跳过计数。前端补真实浏览器主流程与经 Nginx 的登录测试。

## 建议实施顺序与验收

| 批次 | 改进内容 | 可验收结果 |
|---|---|---|
| 1 | 可信代理与限流；若公网使用则补 TLS | 多客户端限流互不干扰；来源不可伪造；凭据走 HTTPS |
| 2 | 到期扫描游标/退避；失败记录告警 | 最早 100 条失败不阻挡第 101 条；恢复后无重复业务效果 |
| 3 | 明确事件目录；分离留痕事件；保留策略 | 每类事件有 owner、路由/订阅或归档用途；无永久无主 NEW |
| 4 | 可靠失败存储/DLX；死信元数据；完成对账 | 消费失败及死信目标不可用仍可追踪恢复 |
| 5 | 指标告警；故障与容量验收；文档/CI/日期刷新 | 能发现并量化停滞，清晰报告恢复耗时和真实容量 |

## 本次验证

1. 默认 `mvn -B -ntp test`：220 项，0 失败、0 错误、208 跳过，实际执行 12 项。该结果单独不足以证明 MQ 可用。
2. `pwsh -NoProfile -File scripts/verify-flashslot-foundation.ps1 -WithRabbit -WithRedis`：私有临时本机 MySQL/RabbitMQ/Redis，后端 **220 项全部执行通过，0 跳过**，Flyway V1–V9 迁移与构建成功；脚本退出码 0。测试日志中包含预期故障注入异常，以最终断言结果为准。
3. 独立审查执行前端 `npm test`：**20/20 通过**；`npm run build`：通过。
4. 日志：`.run/review-integration.log`；隔离验收摘要：`.run/flashslot-foundation-verification.json`。`.run` 为忽略目录；测试产生的 target/dist 也是构建产物。

上述验证证明当前已有测试通过，不证明本报告中新指出的边界已被覆盖。未执行真实代理双客户端、100 条持续失败、DLX 目标失效、broker 崩溃与备份恢复、长期容量和生产压测；未读取远程实时状态。审查没有发现已证实的重复预约、重复撮合或重复通知缺陷，但不能据此作不存在其他问题的保证。
