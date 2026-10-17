# FlashSlot 服务器联调

最近部署：2026-10-17（北京时间），应用提交 `2c6a36f725a35c14f25622d4e629bf1518d554ea`。

- 页面：http://114.55.232.26/
- API：http://114.55.232.26/api/v1
- 健康检查：http://114.55.232.26/healthz
- 部署根目录：`/opt/flashslot`；当前发布：`/opt/flashslot/current` → `releases/20261017-2c6a36f`。
- Docker Compose 项目名 `flashslot`，独立数据库和消息数据卷。仅前端 Nginx 80 端口对外开放；服务器已有 1Panel 和其他项目保持不变。
- 运行配置 `/opt/flashslot/.env`，权限 600；本文不保存密码或密钥。应用使用 production profile，不启用 demo。
- 初次部署未创建测试账号、演示设备或业务记录，首次建库通过 Flyway V1–V7；本次启动顺序应用 V8–V10，十条迁移记录均成功。用户随后要求初始化管理员，已受控创建 `flashslot-admin`（ADMIN，启用）；密码单独交付，不写入文档。没有预建普通用户和设备。

运行命令：

```bash
cd /opt/flashslot
docker compose --env-file .env -f current/compose.yml ps
docker compose --env-file .env -f current/compose.yml logs --tail=100 app
docker compose --env-file .env -f current/compose.yml stop
docker compose --env-file .env -f current/compose.yml up -d
```

不要执行 `down -v`。更新前备份数据库和配置；切换发布目录后用 `up -d --force-recreate app web` 重新绑定构建产物。联调入口为 HTTP；前端已兼容没有 `crypto.randomUUID()` 的公网 HTTP 环境，使用 `getRandomValues()` 生成 UUID v4 幂等键。

验证范围：公网页面和 JS 资源返回 200、健康状态 UP、公开设备接口返回空列表、未登录的个人及管理员接口返回 401。初始化管理员后验证真实登录成功、账号管理返回 200、创建个人预约返回 403；验证 Token 已登出撤销。后端部署包沿用本机 190 项完整测试通过的版本；HTTP 兼容修复后前端 15 项测试和构建通过。其余业务联调由用户自行完成。

清理范围：Docker 中超过 24 小时的未使用构建缓存（约 3.655 GB）、APT 下载缓存、超过 14 天的系统日志（释放约 616 MB）。保留其他项目、容器、镜像、数据卷、数据库及备份；部署后磁盘仍约有 25 GB 可用。


## 2026-10-17 更新验收

- 上传已通过 268 项后端、35 项前端与 56 项结构校验的构建产物；容器内 JAR SHA-256 为 `8648e41f4f5e9d63a65a1866fdbd523836a636ecd233d27b38a093469764d000`，与本地一致。首页、主 JS/CSS 和登录/注册/排期/恢复页面 JS 均通过公网下载哈希核对。
- 旧发布保留在 `releases/20261016-2042`；更新前与停写后数据库备份、原 `.env` 和 Compose 位于 `/opt/flashslot/backups/20261017-2c6a36f/`，数据库与环境备份权限均为 600。没有删除数据卷，也未更新其他项目。
- 当前网络为 `172.30.87.0/24`。默认 `.2` 被 RabbitMQ 自动占用，已在服务器 `.env` 设置 `PROXY_IP=172.30.87.254`，Nginx 实际地址及后端 `TRUSTED_PROXIES=172.30.87.254/32` 一致。
- 服务器镜像源对新 Java 摘要返回错误媒体类型，备用入口未在本次准备窗口内完成下载。本次保留原来运行验证过的 Temurin **21.0.12+8**，固定为 `eclipse-temurin@sha256:61d6c7b34d36aee3f45d043101259f97f3c6d428dc2a6f75513789983c5e254f`。服务器 `compose.yml` 使用规范的 repository@digest，原仓库配置另存为 `compose.repository.yml`，实际差异记在 `release.json`。应用代码版本未变；以后重新覆盖 Compose 时须核对该差异，不能假定新 Java 摘要已经可用。
- 五个服务均运行且重启次数为 0，MySQL/RabbitMQ 健康；`/healthz` 为 UP。生产注册能力返回 disabled/no-store；公开设备与当日排期正常；匿名个人、管理及死信接口为 401，带 `UNAUTHENTICATED`。入口未代理 Prometheus 路径（该路径只返回前端 HTML），没有公开指标数据。
- 从私有网络真实抓取业务指标成功，snapshot_available=1、Outbox backlog=0、dead_letters=0、release pending/manual_review=0。原有 3 条 SCHEDULE_CHANGED 从 NEW 归档为 ARCHIVED；2 个账号、1 个订单均保留。新日历维护已生成上海当天起 14 天日历。通知/撮合及其死信队列各有 1 个消费者，ready/unacknowledged 均为 0。
- 此次验收为 HTTP、构建哈希、迁移、持久数据和中间件检查，未用用户密码做新的登录/预约写入，也未作浏览器点击或容量压测。可选 Prometheus 容器未启用，尚未接外部告警通知；指标端点可在授权私网抓取。公网报告保存在本机忽略目录 `.run/flashslot-server-20261017-verification.json`。
- 旧应用与 V10 的 schema 兼容性未经回退验证；故障回退需按备份和匹配的应用/schema 制定方案，不能只切回旧 JAR。


## 2026-10-17 手机号模拟注册启用

按用户明确要求保留手机号注册和前端显示验证码的模拟方式，不使用学号注册。应用代码仍为 `2c6a36f`；本次仅为 Compose 转发 `REGISTRATION_DEVELOPMENT_CODE_ENABLED`，并在服务器 `.env` 显式设置 true。`production` profile、Swagger 关闭和既有数据不变；更改前配置备份在 `/opt/flashslot/backups/20261017-phone-registration/`。

线上 capabilities 已返回 `registrationEnabled=true, registrationMode=DEVELOPMENT`。公网真实验证获取六位验证码、no-store、重复发送 429 冷却、错误验证码 400、注册 201 与自动登录、普通用户访问管理员 403、登出后 Token 401、手机号密码再次登录及重复账号 409。验证码和密码未写入验收报告；唯一临时账号已按 ID/手机号/角色精确删除，恢复原有 2 个账号。

原手机号流程的隔离 MySQL/Redis 回归 7 项全部通过，前端 35 项全部通过；日志 `.run/phone-registration-final.log`、`.run/phone-registration-web.log`。公网报告 `.run/phone-registration-server-verification.json`。应用与入口容器运行、重启次数均为 0，健康检查 UP。模拟注册不会发送真实短信，也不验证手机号实际归属；验证码冷却、次数上限与登录限流继续生效。
