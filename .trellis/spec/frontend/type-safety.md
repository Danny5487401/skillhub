# 类型安全与 API 边界

适用于 API 消费、模型、生成 schema 和类型断言修改。

- 保持 TypeScript strict；不新增 `any` 逃避契约。未知外部输入用 `unknown` 并在边界收窄，
  TypeScript 泛型或断言不等于运行时校验。
- API 形状优先来自 `web/src/api/generated/schema.d.ts`，通过既有 API 模块引用；
  展示模型、表单草稿和必要适配可以独立定义，但不能再维护一份会漂移的服务端协议。
- 用户标识使用字符串；不要因为样本是数字文本便转成 number。资源 ID 的类型依据各自协议，不混为一谈。
- 不以 `as`、非空断言或手写 Required 掩盖服务端可选字段。适配层强化约束时说明实际保证，
  覆盖缺失/null/未知枚举的适用行为；不能把假设伪装为 schema 事实。
- 后端 OpenAPI 契约变化后通过 `make generate-api` 生成类型，生成文件随契约修改交付；不手改生成结果。
  命令实现以 [Makefile](../../../Makefile) 为准，检查前确认所用服务来自本次代码。

## 现有边界

[客户端](../../../web/src/api/client.ts)同时包含 openapi-fetch 与手写 convenience wrapper，
[API 类型](../../../web/src/api/types.ts)同时包含生成类型映射和手写接口。
新增消费复用这些入口并优先生成类型；不能宣称现有全部请求已经生成类型覆盖，
也不在规范整理中无范围限制地重写所有 wrapper。

[编译配置](../../../web/tsconfig.json)启用 strict；
[客户端测试](../../../web/src/api/client.test.ts)提供请求边界用例。审查时检查响应字段、请求参数、
可选性、身份类型与消费方一致，必要时补充边界测试。

生成一致性检查入口为 `scripts/check-openapi-generated.sh`，该脚本会启动本地依赖且清理时执行
Compose down，不是纯静态检查；在隔离环境按[测试入口](../testing/index.md)执行。
不能把类型检查通过等同于真实接口、授权或运行时响应验证通过。
