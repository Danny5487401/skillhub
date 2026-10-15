# API 与行为契约维护

适用后端、前端和 CLI 的公共接口变更；消费者可直接读本页，无需加载整个后端目录。
具体端点、坐标、RBAC、版本标签和兼容语义以 [API设计](../../../docs/06-api-design.md)、
[领域模型](../../../docs/02-domain-model.md)、[生命周期](../../../docs/14-skill-lifecycle.md)及相关 OpenSpec 为正式来源。
不在 Trellis 复制端点/角色/状态表。

身份、会话、设备授权或 API token 变化追加[认证设计](../../../docs/03-authentication-design.md)及对应实现测试。
token 持久化保留现有安全哈希机制，不退化为明文存储，不把展示前缀当作凭证本体。
发布、上传、解压或包校验变化追加[包协议](../../../docs/07-skill-protocol.md)，核对实际校验策略；
不在此复制易过期的扩展名与大小限制表，拒绝路径和边界值应有测试。

## 工程约定

- DTO 放 app 的 dto 包，Controller 负责参数绑定、认证上下文与响应包装；业务规则由领域/应用服务承担。
- 用户身份使用 String。客户端传入 owner/user 字段不等于已认证操作者，授权需使用可信身份上下文。
- 普通 JSON 接口沿用 ApiResponse/ApiResponseFactory，文案通过现有本地化机制生成；流式文件和兼容接口按已有协议处理，不能强行套壳。
- 分页字段、页码基数、排序、空值、缺省值与可选字段逐项核对端点和消费者；不凭框架默认值推导公共协议。
- 参数格式、权限与领域前置状态分别校验；HTTP 错误处理见 [错误专题](./error-handling.md)。
- 同一修改说明成功后的状态、失败后的状态、可重试条件和副作用；取消 HTTP 等待不自动意味着服务端事务取消。

## 身份与兼容边界

Session/CSRF 行为核对 [SecurityConfig](../../../server/skillhub-auth/src/main/java/com/iflytek/skillhub/auth/config/SecurityConfig.java) 与相应认证链。
浏览器会话使用的 XSRF-TOKEN/X-XSRF-TOKEN 与 token/兼容接口的例外不能混为一条“所有写请求都带 CSRF”。
Mock 身份仅按现有开发 profile 使用，不替代真实认证验收。
命名空间 slug、二轴坐标到 ClawHub 单 slug 的解析、冲突优先级和 well-known discovery 变更时，同时检查 compat 与 CLI 消费方。
不要根据 namespace 名称前缀猜测类型或权限。

## 契约变更验收

1. 明确受影响消费者、字段可选性、错误结构与兼容策略；不能因类型仍编译就认定行为兼容。
2. 保持 Controller/OpenAPI 注解和 DTO 一致；从正确运行中的后端执行 `make generate-api`，检查生成 diff 并随契约提交，勿手改生成类型。
3. 选择 HTTP/消费方测试验证实际响应和失败路径；至少覆盖此次变化触及的身份/权限边界。
4. [生成漂移脚本](../../../scripts/check-openapi-generated.sh) 会启动依赖并在退出时执行 Compose down；仅在隔离、可清理的本地环境运行，不能作为无副作用的文档检查。

## 已知文档漂移

2026-10-15 静态核查：docs/14 的撤回描述仍为 DRAFT，而
[withdrawPendingVersion 实现](../../../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/service/SkillGovernanceService.java) 设置 UPLOADED。
旧工程 Skill 的状态表也不是新的权威。修改相关功能时对照
[治理测试](../../../server/skillhub-domain/src/test/java/com/iflytek/skillhub/domain/skill/service/SkillGovernanceServiceTest.java)和正式需求解决差异；本次迁移不裁定新业务状态。
发布审核绕过、可见性、latest 指针、警告确认、删除约束均须核对当前服务与测试，不从历史快照复制规则。
