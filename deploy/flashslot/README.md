# FlashSlot 联调部署

使用已经测试通过的 JAR 和前端 dist，独立 Compose 项目名为 flashslot。
在发布目录中放置 app.jar、web/、compose.yml、nginx.conf、rabbitmq.conf；启用监控还需 prometheus.yml、message-alerts.yml。
根目录专用 `.env` 保存 DB_PASSWORD、MYSQL_ROOT_PASSWORD、RABBIT_PASSWORD、JWT_SECRET 和 HTTP_PORT；权限设为 600，不入库。

启动：`docker compose --env-file /opt/flashslot/.env -f /opt/flashslot/current/compose.yml up -d`。
状态：同一命令前缀加 `ps`；应用日志加 `logs --tail=100 app`。
停止：加 `stop`，保留数据。不要执行 `down -v`，否则会删除独立数据库和消息卷。

仅 Nginx 的 HTTP_PORT 对外开放；API 共用 `/api/v1`；`/healthz` 查看服务健康。
MySQL、Redis、RabbitMQ 不发布宿主机端口。联调时前端开发服务器的 API_TARGET 可指向同一 HTTP 地址。
此配置面向小内存联调机，未启用 demo profile；首个管理员需在 Flyway 迁移完成后用[一次性 CLI](../../docs/admin-bootstrap.md)初始化。生产默认关闭注册，页面按后端能力隐藏注册入口；当前未接真实短信服务商。

登录限流由 Redis 共享计数，默认 IP 30 次/60 秒、账号 5 次/60 秒。Redis 故障会短暂降级到各实例自己的内存保护，并输出告警日志；不提供故障期间的全局严格额度。

入口 Nginx 使用固定容器地址 `172.30.87.2`，后端只信任该地址提供的 `X-Real-IP`。默认 Compose 子网为 `172.30.87.0/24`；若与宿主机网络冲突，在 `.env` 中同时设置 `NETWORK_SUBNET` 和其中未占用的 `PROXY_IP`，Compose 会同步配置入口地址与后端可信代理。不要将应用端口直接发布到公网，也不要把整个公网设置为可信代理。Nginx 会覆盖客户端提交的来源头；若另加负载均衡器，需要重新明确可信代理链和入口地址解析。

更新前备份数据库和 `.env`，保留上一发布目录。数据库迁移不可逆时，回滚旧 JAR 不能替代恢复备份。
首次启动不能将 HTTP 200 当成就绪，应检查 `/healthz`、实际登录、角色权限和一笔预约/取消的完整业务结果。


## 镜像与可选监控

所有镜像均以 tag@sha256 固定，解析及平台摘要记录在 [image-lock.json](image-lock.json)。升级时重新核验摘要并做兼容回归；固定摘要不代表自动得到后续补丁。

可选监控启动命令：

```sh
docker compose --env-file /opt/flashslot/.env -f /opt/flashslot/current/compose.yml --profile monitoring up -d
```

Prometheus 在 Compose 内抓取 `app:8080/actuator/prometheus`，数据卷保留 7 天；UI 仅绑定宿主 `127.0.0.1:9090`（可用 PROMETHEUS_PORT 修改）。不应对公网开放 UI。应用抓取 ACL 默认仅回环，Compose 明确允许其私有子网；ACL 使用实际连接地址且忽略转发头，Nginx 不代理指标路径。

[抓取配置](prometheus.yml)和[5 条告警规则](message-alerts.yml)覆盖抓取/快照不可用、发布积压、失败/死信、撮合逾期和人工复核。[指标语义](../../docs/message-metrics.md)说明失败率与数据库不可用行为。尚未配置 Alertmanager 或外部通知；规则出现告警不会自动发邮件或消息。

本轮已完成官方 promtool 配置与规则校验及静态 Compose 检查；本机没有 Docker，尚未实际启动该 Compose 监控组合，需部署时验证 targets、告警触发及数据持久化。
