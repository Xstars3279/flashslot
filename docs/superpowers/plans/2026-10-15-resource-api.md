# 设备资源模块接口

执行用户授权的一模块开发；沿用现有开发设计，不扩展预约模块。

- GET /api/v1/resources：按 ID 升序游标分页，默认 20，最大 100；仅 ACTIVE。
- GET /api/v1/resources/{id}：查询设备详情，包括管理操作后的版本。
- POST /api/v1/admin/resources：ADMIN 新增，name/location/默认格范围/reason；事务内生成从上海今天起 14 天日历，写管理员审计。
- PATCH /api/v1/admin/resources/{id}：局部修改名称、位置、默认开放范围；请求 DTO 的 version 为必填非负整数；成功 version+1；旧版本 409；既有日历不回写。
- 不接收 status 字段；暂停/退役另行实现，防止跳过预约检查。
- 管理写入校验可信认证角色及数据库当前账号启用状态、ADMIN 角色。认证 principal.name 暂约定为用户名；登录尚未实现，不开放匿名写入。
- 基础 Mapper 使用 MyBatis-Plus BaseMapper，服务使用 IService/ServiceImpl、LambdaQueryWrapper；Resource 的 @Version 由乐观锁拦截器处理。联合主键日历批量写入和账号行锁保留显式 SQL。资源/账号/审计写入事务顺序按资源在前、账号在后；审计字段不跨模块直接写，通过 operations 审计组件。
- 先增加真实 MySQL MockMvc 集成测试并观察失败，再实现请求 DTO、VO、Mapper、Service、Controller、异常映射和安全规则。
- 验证成功流程、参数错误、404、匿名/普通用户/禁用管理员、过期版本、同版本并发仅一次成功、日历与审计落库。

## 验证结果

已完成实现。先运行资源接口测试，因未实现接口及权限规则出现 5 个预期失败；实现后运行私有 MySQL 验证脚本，最终 10 项测试全部通过（资源模块 7 项、基础工程 3 项），Maven clean verify 构建通过。
资源测试覆盖权限失败时新增与更新的事务回滚；请求范围外字段、缺失请求体版本会被拒绝。
管理接口尚无登录签发入口，集成测试使用可信身份注入；未声称完成认证闭环、预约或日历维护模块。

按用户后续要求，DTO/VO 已改为普通 Lombok 类，请求字段在类内定义；Controller、安全过滤器、全局异常处理统一使用 Result<T>（code/message/data）。原有接口测试先按新响应结构验证失败，再完成改造。最新私有 MySQL clean verify 共 11 项测试通过（资源模块 8 项、基础工程 3 项），包含模拟审计写入异常时的统一 500 响应和资源/日历整体回滚。

后续按用户明确要求，Controller 与全局异常处理直接返回 Result<T>，移除 ResponseEntity、ETag/If-Match 及版本字符串解析。设备修改版本从 UpdateResourceRequest.version 获取，保持 HTTP 状态语义与数据库乐观锁。
