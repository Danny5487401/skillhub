# 真实 E2E 与 Smoke

涉及跨页面业务、认证、权限、发布与运维流程时读取。操作步骤见[现有 E2E 手册](../../../docs/e2e.md)，
配置事实以 [Playwright 配置](../../../web/playwright.config.ts)和[Smoke 配置](../../../web/playwright.smoke.config.ts)为准。
不要复制手册中的固定用例数量、地址或历史执行结果为规范。

## 真实链路与数据

- `web/e2e` 保持真实请求，不用 `page.route` 拦截关键 API 或伪造业务响应。
  隔离的组件/视觉 mock 测试可以存在，但应放在适用测试层且明确不是真实后端 E2E。
- 复用 [session helper](../../../web/e2e/helpers/session.ts) 的认证与 worker 账号隔离；
  数据创建与清理复用[数据 helper](../../../web/e2e/helpers/test-data-builder.ts)。
  新场景核对 helper 实际清理能力，不假定它会回收所有数据。
- 测试账号和数据必须专用。记录本次创建的对象，失败时也按精确对象清理，不清空共享数据库。
- 优先 `getByRole`、`getByLabel`、`getByTestId`；等待 URL、状态或元素条件，不新增盲等。
- 操作成功需验证用户结果或持久状态；按钮可点、页面可达不能替代完整业务验收。

## 执行与环境

入口为 `make test-e2e-frontend`、`make test-e2e-smoke-frontend`。
默认 Playwright 可启动前端，但不会因此自动提供完整后端；先识别 URL、服务和账号来源。
外部目标使用当前配置支持的 `PLAYWRIGHT_BASE_URL`，不要未经确认指向真实用户环境。
Smoke 是关键路径子集；通过不能宣称全量覆盖。CI/本地重试后通过应记录首次失败并排查不稳定原因。

运维流程变化时更新相应脚本：
[basic](../../../scripts/smoke-test.sh)、[namespace](../../../scripts/namespace-smoke-test.sh)、
[governance](../../../scripts/governance-smoke-test.sh)、[promotion](../../../scripts/promotion-smoke-test.sh)。
脚本会产生业务写入，运行前读取参数、账号、创建和清理逻辑。
认证链排查需核对 cookie jar、登录后 CSRF token 与实际权限，不能靠关闭安全检查让 Smoke 通过。
报告保留命令、目标环境、版本、失败步骤及脱敏证据；trace、截图和日志不可携带会话凭证公开传播。
