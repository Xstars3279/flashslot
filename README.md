# FlashSlot 设备预约 MVP

当前主项目为 FlashSlot：用户可登录、查看排期、预约或候补、签到、取消/提前结束、查看通知及个人信用和限制；管理员可维护设备与单日日历、获取现场签到码、恢复异常任务、调整信用和查看审计。后端会自动补齐今天起 14 天日历，预约窗口仍为今天起 7 天。

本轮范围为本地运行，暂不做 Docker 和部署。启动与停止：

```powershell
pwsh -NoProfile -File scripts/start-flashslot-local.ps1
pwsh -NoProfile -File scripts/stop-flashslot-local.ps1
```

前端地址 `http://127.0.0.1:5173`；体验账号 `demo-user` / `demo-admin`，随机密码由启动脚本显示。脚本使用独立的本机 MySQL、Redis 和 RabbitMQ，停止后保留体验数据库。

详见 [前端与本地启动说明](flashslot-web/README.md)、[后端接口与规则](flashslot/README.md) 和 [本机验收记录](docs/FlashSlot-本机验收.md)。

## 原最小微服务调用示例

设备预约项目 FlashSlot 已在独立目录搭建，见 [FlashSlot 开发说明](flashslot/README.md)。本页以下内容属于原微服务练习。

Java 21、Spring Boot 3.2.0、Spring Cloud 2023.0.0、Spring Cloud Alibaba 2023.0.0.0-RC1。
本次沿用原项目的框架版本，重点是先跑通调用链。

## 调用流程

```text
客户端 POST /orders
        ↓
order 服务 :8082
        ↓ UserClient（Feign，服务名 user）
Nacos 提供 user 的健康实例列表
        ↓ Spring Cloud LoadBalancer 选择实例
user :8081 或 user :8083 → GET /users/{id}
        ↓
订单服务保存订单并返回用户信息、实际调用的用户实例
```

实际 HTTP 调用方向是订单 → 用户。两个用户进程使用同一个 jar、同一个服务名 `user`，只改变端口。
Feign 没有设置 `url`，不绑定用户服务的 IP 或端口。配置中的 `127.0.0.1:8848` 是注册中心地址。
本例注册 IP 固定为 `127.0.0.1`，适用于同一台电脑练习；部署到不同机器时需要修改注册 IP。

## 启动

确保 `JAVA_HOME` 指向 JDK 21，`mvn` 在 PATH 中，8081、8082、8083 未被占用。

1. 先启动本机 Nacos（默认 8848）。你当前安装目录的启动命令：

   ```powershell
   & D:\nacos\nacos\bin\startup.cmd -m standalone
   ```

   本次已启动 Nacos，并沿用其原有配置，没有修改 Nacos 安装文件。

2. 在项目根目录运行：

   ```powershell
   ./scripts/start-demo.ps1
   ```

   脚本先构建并运行测试，再后台启动 `user:8081`、`user:8083`、`order:8082`。
   如果已经打包，可以用 `./scripts/start-demo.ps1 -SkipBuild`。
   如果 PowerShell 提示脚本执行策略阻止，可使用：

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/start-demo.ps1
   ```

3. 验证：

   ```powershell
   ./scripts/test-demo.ps1
   ```

   脚本创建 10 个订单，输出每次调用的用户实例，验证两个实例都被调用，并读取一条已创建订单。
   首次调用会等待注册和订阅完成。检查失败时脚本报错，不会把单实例调用当作验证通过。
   检查报告保存在 `.run/verification.json`。

也可以在三个独立终端手动启动（先 `mvn package`）：

```powershell
java -jar user/target/user-1.0-SNAPSHOT.jar --server.port=8081
java -jar user/target/user-1.0-SNAPSHOT.jar --server.port=8083
java -jar order/target/order-1.0-SNAPSHOT.jar --server.port=8082
```

Nacos 控制台：http://127.0.0.1:8848/nacos/ 。查看 public 命名空间、DEFAULT_GROUP：
`user` 有两个健康实例（8081、8083），`order` 有一个（8082）。

## 自己发送请求

预置两个用户：`1 / 张三`、`2 / 李四`。

```powershell
# 直接查询某个用户实例
Invoke-RestMethod http://127.0.0.1:8081/users/1

# 创建订单，订单服务内部调用用户服务
$body = @{ userId = 1; productName = 'book'; quantity = 2 } | ConvertTo-Json
$order = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8082/orders `
    -ContentType 'application/json' -Body $body
$order | ConvertTo-Json -Depth 5

# 用刚刚返回的 id 查询订单
Invoke-RestMethod "http://127.0.0.1:8082/orders/$($order.id)" | ConvertTo-Json -Depth 5
```

创建成功返回 HTTP 201 和 `Location: /orders/{id}`，响应示例：

```json
{
  "id": 1,
  "productName": "book",
  "quantity": 2,
  "user": { "id": 1, "name": "张三", "instance": "user:8081" }
}
```

多次**创建订单**，观察 `user.instance` 变化。查询已有订单返回创建时保存的用户快照，
不会再次调用用户，因此反复查询同一订单时实例字段不会变化。

| 接口 | 含义 |
|---|---|
| `GET /users/{id}` | 查询用户，未知用户返回 404 |
| `POST /orders` | 创建订单；需要正整数 userId、非空 productName、正整数 quantity |
| `GET /orders/{id}` | 查询内存中的订单，未知订单返回 404 |

创建订单时，参数不合法返回 400，用户不存在返回 404，用户调用失败返回 503。
Feign 连接超时为 2 秒、读取超时为 3 秒。自动化测试关闭 Nacos 注册并模拟用户客户端；
真实服务发现与负载均衡由 `test-demo.ps1` 验证。

## 关键代码

- `user/.../controller/UserController.java`：用户查询及实例标记。
- `order/.../client/UserClient.java`：`@FeignClient(name = "user")`，按服务名调用。
- `order/.../service/OrderService.java`：查询用户、保存订单、处理远程错误。
- `order/.../controller/OrderController.java`：订单 HTTP 接口。
- 两个模块的 `application.properties`：端口、Nacos 注册及超时设置。
- 根 `pom.xml`：模块聚合、Java 编译、JUnit 5 测试与可执行 jar 打包配置。

无需数据库。本例使用内存数据，订单服务重启后订单清空、编号重新开始。
保留了原有 MySQL 驱动依赖，但尚未使用数据库。

## 停止和日志

```powershell
./scripts/stop-demo.ps1
```

只停止启动脚本记录的三个应用进程，保留独立管理的 Nacos。
手动在终端启动的服务使用 Ctrl+C 停止。
应用输出位于 `.run/user-8081.out.log`、`.run/user-8083.out.log`、`.run/order-8082.out.log`，
错误输出位于对应的 `.err.log`；进程记录位于 `.run/services.json`。

如果 Nacos 地址不同，启动前设置 `$env:NACOS_SERVER_ADDR = '主机:端口'`。
如果本机 Nacos 开启认证，按实际账号设置 `NACOS_USERNAME`、`NACOS_PASSWORD` 环境变量。
本次本机 Nacos 的认证配置为关闭。
