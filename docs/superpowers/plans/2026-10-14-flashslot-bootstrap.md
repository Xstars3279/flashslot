# FlashSlot 工程基础与实体实施计划

**Goal:** 执行用户指定的第一步：独立应用骨架、开发文档依赖、14 张表的持久化实体。

**Architecture:** 在 flashslot/ 建立独立 Maven 模块化单体，使用 com.flashslot 业务包。已有 user/order 演示保留。以 db/01–05 为当前数据库事实来源，不使用旧 15 表 SQL。

**Tech Stack:** Java 21、Boot 3.5.6、MyBatis-Plus 3.5.14、MySQL、Flyway、Redis、AMQP、Security、springdoc 2.8.13、Lombok。

- [x] 创建独立 pom、启动类、配置与运行说明；引入文档要求的运行和测试依赖。
- [x] 将现有五个建表文件复制为 Flyway V1–V5；默认不自动 baseline 已有库。
- [x] 按业务包创建全部实体，字段类型/列名与 SQL 对齐；联合主键不伪造单主键；生成列禁止写入。
- [x] 为 UTC DATETIME 映射 Instant 提供统一 handler；JSON 映射 JsonNode；使用 Lombok getter/setter。
- [x] Maven 编译测试打包，验证时间转换、实体映射及启动配置；明确尚未实现认证和业务接口。

本轮不实现预约、候补、消息投递、登录或每日配额业务；不连接现有数据库或远程服务器。执行方式：当前会话直接实施。
