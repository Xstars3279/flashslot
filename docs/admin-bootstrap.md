# 首个生产管理员的一次性初始化

应用默认不会初始化管理员。显式执行 `--bootstrap-admin` 后，只建立一条独立 MySQL JDBC 连接，完成初始化并退出：成功退出码为 `0`，失败为非 `0`。不会启动 Web 服务、Spring 容器、Redis、RabbitMQ 或任何调度任务。

## 前置条件与行为

- 使用与目标数据库迁移版本匹配的应用 jar。先通过既有部署流程完成 Flyway 迁移；此命令**不建库、不建表、不执行迁移**。Flyway 成功记录必须与 jar 中的迁移版本完整匹配；缺少迁移、存在失败记录、未来版本或缺少账号字段都会拒绝执行。
- 连接信息仅来自 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，三者必须存在，URL 与用户名必须非空，`DB_URL` 必须为 MySQL JDBC URL；密码允许显式空值以兼容本地临时数据库，由目标数据库完成认证。数据库用户需读取迁移历史、查询/插入 `app_user`，并可使用 MySQL `GET_LOCK`。
- 管理员用户名来自 `FLASHSLOT_BOOTSTRAP_USERNAME`，或交互式 Console 输入。规则与管理端创建账号一致：3–64 个字符，以字母或数字开头，其余可包含字母、数字、`_`、`.`、`-`。
- 管理员密码来自 `FLASHSLOT_BOOTSTRAP_PASSWORD`，或 Console 隐藏输入；至少 8 个字符，UTF-8 编码不超过 72 字节，不能全为空白。不能通过命令行参数传密码，也不要把密码写进配置、脚本、`.env`、命令历史或日志。
- 事务创建一个 `enabled=true`、`role=ADMIN`、`credential_version=0` 的账号，密码使用 BCrypt cost 12。任意 ADMIN 已存在时立即拒绝，包括已禁用的管理员；不重置密码、不重新启用、不覆盖账号，也不把已有 USER 升级为 ADMIN。
- 并发调用使用 MySQL 连接级 advisory lock 串行执行，等待最多 10 秒。提交/回滚后关闭连接释放锁；只有第一个调用能够创建管理员。失败后可修复环境再运行，已有管理员仍会阻止重复初始化。
- 输出仅包含固定提示，不输出密码、数据库用户名、JDBC URL 或异常堆栈。如果网络中断发生在提交阶段，应由运维通过受控数据库查询确认账号是否已创建，再决定是否重试。

## 本地运行已构建的 jar

先在当前进程环境中由受控凭据渠道提供 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`。在 PowerShell 的交互式终端中运行（构建使用 Java 21）：

```powershell
mvn -f flashslot/pom.xml -DskipTests package
$env:FLASHSLOT_BOOTSTRAP_USERNAME = 'initial.admin'
Remove-Item Env:FLASHSLOT_BOOTSTRAP_PASSWORD -ErrorAction SilentlyContinue
java -jar flashslot/target/flashslot-0.0.1-SNAPSHOT.jar --bootstrap-admin
if ($LASTEXITCODE -ne 0) { throw '管理员初始化失败，请根据固定提示排查。' }
```

Java 会在 Console 中隐藏读取密码。不要通过 IDE 的普通输出面板运行交互式模式；如果没有 Console 且没有密码环境变量，将拒绝执行。无人值守运行需通过现有秘密管理系统临时注入 `FLASHSLOT_BOOTSTRAP_PASSWORD`，执行后清理该环境变量；不要在 shell 命令中写入密码字面量。

## Docker Compose 一次性运行

先按部署流程准备并迁移数据库。以下命令在 `deploy/flashslot` 下执行，复用已配置的 `app` 服务数据库环境和只读 jar 挂载；`--no-deps` 不会自动启动其他服务，目标 MySQL 必须已就绪：

```sh
docker compose run --rm --no-deps \
  -e FLASHSLOT_BOOTSTRAP_USERNAME=initial.admin \
  app java -jar /app/app.jar --bootstrap-admin
```

保持 Compose 默认的交互式终端，不加 `-T`，密码由 Java Console 隐藏读取。此容器完成后立即退出并由 `--rm` 删除，正常 `app` 服务不会因此被启动。如果当前终端无法提供 Console，使用受控秘密管理渠道在宿主进程中临时设置密码环境变量，并仅转发变量名：

```sh
docker compose run --rm --no-deps -T \
  -e FLASHSLOT_BOOTSTRAP_USERNAME \
  -e FLASHSLOT_BOOTSTRAP_PASSWORD \
  app java -jar /app/app.jar --bootstrap-admin
```

两条环境变量必须已由外部安全渠道提供，执行后从宿主进程清理。环境变量对有权限检查容器/进程的管理员可见，交互式隐藏输入可减少这类暴露。初始化成功后通过正常登录接口验证，再按正常部署流程启动应用。已有管理员的密码恢复应走既定账号管理流程，不能使用本命令绕过。

## 验证

`mvn -f flashslot/pom.xml -Dtest=AdminBootstrapTest test` 验证输入、密码字节边界、错误脱敏与 JVM 入口立即退出。`AdminBootstrapIntegrationTest` 仅在 `FLASHSLOT_PRIVATE_TEST=true` 时运行，使用 `DB_*` 指向的私有临时 MySQL；它创建并删除严格 UUID 命名的独立测试数据库，需要测试用户具备 CREATE/DROP DATABASE 权限，覆盖真实迁移、BCrypt、禁用管理员保护、并发一次成功、缺字段/缺迁移/未来版本拒绝及插入失败后回滚重试。不要对生产数据库开启该测试开关。
