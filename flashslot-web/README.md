# FlashSlot 前端

Vue 3 + Vue Router + Element Plus + Axios + Vite。用户端和管理端共用登录与导航，根据角色显示入口，最终权限由后端复核。

```powershell
cd flashslot-web
npm ci
npm test
npm run build
# 后端默认8080，其他端口可通过API_TARGET配置。
$env:API_TARGET = 'http://127.0.0.1:18080'
npm run dev
```

完整本机体验从工作区根目录执行：

```powershell
pwsh -NoProfile -File scripts/start-flashslot-local.ps1
# 结束体验，保留独立MySQL体验数据：
pwsh -NoProfile -File scripts/stop-flashslot-local.ps1
```

启动脚本要求Java21、Maven、Node22.12+、MySQL8、Redis及Erlang。本机Redis可用`REDIS_SERVER_EXE`指定；RabbitMQ使用官方临时发行包和独立随机端口。前端默认`127.0.0.1:5173`、后端`127.0.0.1:18080`，仅监听回环地址。既有数据库、Redis和用户RabbitMQ均不修改。

显式`local,demo`配置在独立`flashslot_local`体验库中创建`demo-user`和`demo-admin`。每次启动随机生成密码并显示在终端，也可以通过`-DemoPassword`传入至少12位密码。密码仅通过子进程环境传入，不保存到迁移、源代码或进程状态文件。正常local配置不创建默认账号。`-SkipBuild`复用已构建后端和前端依赖。

页面：设备排期与预约/候补；我的预约详情、取消/提前结束与签到；候补取消；通知与已读；设备/单日日历配置与现场签到码；异常释放任务、投递重试、信用调整与限制解除；审计筛选与分页。

“我的账号”显示当前账号 ID、角色、信用分、预约限制及服务器更新时间，可手动刷新。限制状态来自数据库与服务器时间，不按浏览器时钟或登录时的旧快照推断。页面提供预约、签到及候补规则说明。

后端启动及每分钟补齐上海今天起 14 天日历，保留已有日历配置；前端预约日期仍为今天起 7 天。

凭据仅保存在当前浏览器会话的sessionStorage；退出清除本机凭据，当前后端未实现JWT主动撤销。网络或5xx失败重试同一次操作时保留幂等键；明确业务拒绝后下一次提交使用新键。前端不自动重放写请求。无效身份跳转登录，无权访问的管理接口仍由后端拒绝。

当前按用户要求在本机运行，未部署远程服务器。生产部署应使用`npm run build`输出，配置同源`/api`反向代理、TLS和正式账号/密钥；禁止启用demo配置。
