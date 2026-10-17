# FlashSlot 联调部署

使用已经测试通过的 JAR 和前端 dist，独立 Compose 项目名为 flashslot。
在发布目录中放置 app.jar、web/、compose.yml、nginx.conf、rabbitmq.conf。
根目录专用 `.env` 保存 DB_PASSWORD、MYSQL_ROOT_PASSWORD、RABBIT_PASSWORD、JWT_SECRET 和 HTTP_PORT；权限设为 600，不入库。

启动：`docker compose --env-file /opt/flashslot/.env -f /opt/flashslot/current/compose.yml up -d`。
状态：同一命令前缀加 `ps`；应用日志加 `logs --tail=100 app`。
停止：加 `stop`，保留数据。不要执行 `down -v`，否则会删除独立数据库和消息卷。

仅 Nginx 的 HTTP_PORT 对外开放；API 共用 `/api/v1`；`/healthz` 查看服务健康。
MySQL、Redis、RabbitMQ 不发布宿主机端口。联调时前端开发服务器的 API_TARGET 可指向同一 HTTP 地址。
此配置面向小内存联调机，未启用 demo profile；账号需在 Flyway 迁移完成后受控初始化。

登录限流由 Redis 共享计数，默认 IP 30 次/60 秒、账号 5 次/60 秒。Redis 故障会短暂降级到各实例自己的内存保护，并输出告警日志；不提供故障期间的全局严格额度。

入口 Nginx 使用固定容器地址 `172.30.87.2`，后端只信任该地址提供的 `X-Real-IP`。默认 Compose 子网为 `172.30.87.0/24`；若与宿主机网络冲突，在 `.env` 中同时设置 `NETWORK_SUBNET` 和其中未占用的 `PROXY_IP`，Compose 会同步配置入口地址与后端可信代理。不要将应用端口直接发布到公网，也不要把整个公网设置为可信代理。Nginx 会覆盖客户端提交的来源头；若另加负载均衡器，需要重新明确可信代理链和入口地址解析。

更新前备份数据库和 `.env`，保留上一发布目录。数据库迁移不可逆时，回滚旧 JAR 不能替代恢复备份。
首次启动不能将 HTTP 200 当成就绪，应检查 `/healthz`、实际登录、角色权限和一笔预约/取消的完整业务结果。
