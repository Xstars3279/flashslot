# FlashSlot 服务器联调

部署日期：2026-10-16（北京时间）。

- 页面：http://114.55.232.26/
- API：http://114.55.232.26/api/v1
- 健康检查：http://114.55.232.26/healthz
- 部署根目录：`/opt/flashslot`；当前发布：`/opt/flashslot/current` → `releases/20261016-2042`。
- Docker Compose 项目名 `flashslot`，独立数据库和消息数据卷。仅前端 Nginx 80 端口对外开放；服务器已有 1Panel 和其他项目保持不变。
- 运行配置 `/opt/flashslot/.env`，权限 600；本文不保存密码或密钥。应用使用 production profile，不启用 demo。
- 初次部署未创建测试账号、演示设备或业务记录，初始化库通过 Flyway V1–V7。用户随后要求初始化管理员，已受控创建 `flashslot-admin`（ADMIN，启用）；密码单独交付，不写入文档。没有预建普通用户和设备。

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
