# CLI 工程规范

修改 `cli/` 的命令、安装、配置或凭证时读取。CLI 使用自己的 Bun 工具链和 Node 运行产物，
不套用 Web 的 pnpm、React 规则。命令说明见 [CLI README](../../../cli/README.md)，
工具和构建入口见 [package.json](../../../cli/package.json)与[Makefile](../../../Makefile)。

## 分层与输出契约

- 命令负责参数和展示，业务编排复用 services，持久化使用 stores，系统能力使用 platform；
  新行为先查对应模块和测试，不在命令中复制下载、凭证或安装逻辑。
- 输出走现有 [output.ts](../../../cli/src/shared/output.ts) 与 [CliError](../../../cli/src/shared/errors.ts)。
  人类格式与 `--json` 是不同消费契约；JSON 模式不能混入提示/进度导致无法解析。
- 保持各命令 stdout/stderr 和退出码语义。参数解析失败的既有契约为 stderr、退出码 5；
  其他失败从现有错误映射核对，不擅自把所有失败归为 1。
- 错误区分参数、认证、服务响应、网络和本地文件失败；只输出安全的上下文和可执行下一步，
  不泄露 token、完整凭证或内部堆栈。服务 requestId 存在时沿现有字段传递。
- 交互判断复用 [tty.ts](../../../cli/src/shared/tty.ts)：stdin/stdout 都是 TTY 且非 JSON。
  无人值守时按命令契约要求显式参数或安全失败，不能偷偷选择破坏性默认值。

## 配置、身份与本地状态

[registry-service.ts](../../../cli/src/services/registry-service.ts) 定义 registry 优先级：
显式参数 → `SKILLHUB_REGISTRY` → config → 内置默认；token 为显式参数 → `SKILLHUB_TOKEN` → 对应 registry 的存储凭证。
修改时同步子进程集成测试，不只测 resolver。不同 registry 凭证不能串用；登出只移除目标凭证，
不删除其他 registry、配置或安装记录。配置写入保留不属于本次更新的字段。

安装、升级、删除、同步修改需明确源身份、目标目录、已有用户修改和失败后状态。
复用现有目标锁、inventory 和 archive 能力；并发操作不得丢失另一安装的记录，
中途失败不能伪报完成或遗留与实际文件不符的成功状态。具体 overwrite/force 行为以命令正式契约为准，
不在规范迁移时改变用户数据策略。

## 测试与验收

CLI 行为或工具链变化运行 `make lint-cli`、`make typecheck-cli`、`make test-cli`、`make build-cli`；
仅帮助显示文案按[测试矩阵](../testing/index.md)执行相关输出测试，文档修改检查来源与链接。
CI 另做多平台和 Node 产物运行，其实际必需检查不因本地按影响选测而豁免。
单元测试覆盖规则，子进程测试同时断言退出码、stdout/stderr、目标文件和状态记录。
复用 [临时环境](../../../cli/test/helpers/temp-env.ts)、[进程 helper](../../../cli/test/helpers/run-cli.ts)
及 fake registry，隔离 home/cwd/凭证/宿主 `SKILLHUB_*` 环境；清理本次资源，测试不调用真实桌面应用或发布服务。

回归锚点：[错误输出](../../../cli/test/integration/error-output.test.ts)、
[身份优先级](../../../cli/test/integration/auth-resolution.test.ts)、
[多进程并发](../../../cli/test/integration/concurrency.test.ts)。mock registry 的通过仅证明 CLI 消费契约，
不能标为真实服务端验收。公共 API 变化追加[后端接口契约](../backend/api-contracts.md)和[测试入口](../testing/index.md)。
