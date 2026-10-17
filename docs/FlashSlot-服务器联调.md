# FlashSlot 服务器部署记录

最近验收：2026-10-17（Asia/Shanghai）。以下是当次确认的结果，不代表持续健康监控；本次文档整理未重新连接服务器。

## 入口与版本

- 页面：<http://114.55.232.26/>；手机号模拟注册：<http://114.55.232.26/#/register>。
- API：`http://114.55.232.26/api/v1`；健康：`http://114.55.232.26/healthz`。
- 应用提交：`2c6a36f725a35c14f25622d4e629bf1518d554ea`；模拟注册配置提交：`593843a9a4f6d5a4e2d60297bccd2405858f4cb4`。
- 发布目录：`/opt/flashslot/current` → `releases/20261017-2c6a36f`；Compose 项目名 `flashslot`。
- 环境：production，不启用 demo；配置 `/opt/flashslot/.env`，权限 600。只向公网发布 Nginx 80 端口。
- 应用 JAR SHA-256：`8648e41f4f5e9d63a65a1866fdbd523836a636ecd233d27b38a093469764d000`。
- MySQL 迁移成功至 V10；已有账号和业务数据保留，未删除卷或改动其他项目。

## 环境差异

服务器 `.env` 已显式设置 `REGISTRATION_DEVELOPMENT_CODE_ENABLED=true`。手机号验证码直接返回前端显示，只用于模拟注册，不发送短信、不验证手机号归属。前端 capabilities 返回 `registrationEnabled=true, registrationMode=DEVELOPMENT`；应用默认配置仍为关闭。

网络为 `172.30.87.0/24`。默认 `.2` 曾被 RabbitMQ 自动占用，服务器显式设置 `PROXY_IP=172.30.87.254`，Nginx 实际地址与后端 `TRUSTED_PROXIES=172.30.87.254/32` 一致。

新 Java 镜像摘要在服务器镜像源遇到媒体类型错误，因此实际保留已验证的 Temurin **21.0.12+8**：

```text
eclipse-temurin@sha256:61d6c7b34d36aee3f45d043101259f97f3c6d428dc2a6f75513789983c5e254f
```

服务器 Compose 使用规范的 repository@digest 引用；仓库原配置另存为 `compose.repository.yml`，实际差异写入 `release.json`。重新覆盖部署配置前须核对运行镜像，不假定仓库中的新 Java 摘要在该镜像源已可拉取。

## 常用操作与备份

```bash
cd /opt/flashslot
docker compose --env-file .env -f current/compose.yml ps
docker compose --env-file .env -f current/compose.yml logs --tail=100 app
docker compose --env-file .env -f current/compose.yml stop
docker compose --env-file .env -f current/compose.yml up -d
```

切换发布目录后重建 app/web 以重新绑定挂载，不能只 restart。不要执行 `down -v`。原发布保留在 `releases/20261016-2042`；更新前及停写后的数据库备份、旧环境与 Compose 位于 `/opt/flashslot/backups/20261017-2c6a36f/`。模拟注册开关更改前配置位于 `/opt/flashslot/backups/20261017-phone-registration/`。

数据库与环境备份权限均为 600。旧应用与 V10 的 schema 回退兼容性未验证，回退必须结合匹配版本与数据库备份，不能只换旧 JAR。

## 最近验收结果

- 五个服务运行；MySQL/RabbitMQ 健康，应用和入口重启次数为 0，`/healthz` 为 UP。
- 公网首页、主 JS/CSS、登录/注册/排期/恢复页面 JS 的哈希与本地发布包一致。公开设备与当日排期正常，后台维护上海当天起 14 天日历。
- 匿名个人/管理接口返回 401 与 UNAUTHENTICATED；普通用户不能访问管理员接口。入口未代理 Prometheus 路径，该路径返回 SPA HTML而非指标。
- 实际完成模拟验证码获取、no-store、重复发送 429 冷却、错误验证码 400、注册 201/自动登录、登出撤销、手机号密码再次登录、重复账号 409。验收临时账号已精确清理，没有保存密码、验证码或 Token。
- 私网指标 snapshot_available=1，Outbox backlog、死信和待处理/人工复核释放任务均为 0；原 3 条历史 SCHEDULE_CHANGED 已归档。通知/撮合及各自死信队列有消费者，ready/unacknowledged 均为 0。
- 可选 Prometheus 容器未启用，未配置外部告警通知。当前为 HTTP 单机演示，尚未做生产容量、HTTPS 或完整备份恢复演练。

本机完整回归及重现命令见[验证说明](FlashSlot-本机验收.md)。部署步骤见[Compose 说明](../deploy/flashslot/README.md)，首个管理员初始化见[CLI 文档](admin-bootstrap.md)。
