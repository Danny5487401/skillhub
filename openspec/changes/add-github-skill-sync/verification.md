# GitHub 技能同步验证说明

正式预期来自三个 [Spec](specs/) 以及 [统一管理合同](unified-admin-contract.md)。本文件说明可复现的验证入口；运行日志、截图、临时账号和部署方凭证不属于公共文档。历史测试结果不能替代当前提交与当前 main 整合后的验收。

## 本地检查

从仓库根目录执行已有工具链：

```bash
make test-backend
make typecheck-web
make lint-web
make test-frontend
make build-frontend
(cd github-sync-go && go test -count=1 ./... && go vet ./...)
openspec validate add-github-skill-sync --strict
bash scripts/tests/validate-release-config-test.sh
bash charts/skillhub/tests/configuration-contracts.sh
```

后端定向测试必须包含 `-am`，不能使用本机旧模块产物替代依赖构建。Go 普通测试不访问 GitHub；显式公网用例和 Go–Java HTTP 互操作入口见 [Go README](../../../github-sync-go/README.md)。数据库、MinIO、并发及重启用例需要 Docker；条件跳过必须独立报告，不能计为通过。

## 集成验收范围

| 范围 | 必须观察的行为 |
| --- | --- |
| 初始化与迁移 | main V1–V75 原 checksum 保持；新 V76–V119 唯一且连续；空库与 main 旧库可升级；保留标识冲突明确拒绝、不接管原数据 |
| 发现与来源 | 指定仓库、指定账号、自定义规则、官方探索；候选按仓库 ID 去重、多个出处保留；TEAM 权限与可见性隔离 |
| 执行与恢复 | 分页和检查点原子、超限与不完整结果保留缺口、树枚举失败隔离、固定 commit、租约接管和旧租约拒绝 |
| 容量与额度 | 包/文件/临时卷上限、水位回落后的当前内容重核、多个 Token 不累加额度、配置池不可用时等待而不回退匿名 |
| 暂停与配置 | 平台暂停不影响 TEAM、在途任务安全确认、已接收包继续处理、探索停用不停止已登记来源、版本冲突和 CSRF 拒绝 |
| 包与审核 | 本地和 S3 模式实际上传、摘要复核、真实扫描、独立同步队列、批准/拒绝、更新待审不替换已发布版本、普通审核行为保持 |
| 浏览器与性能 | 三页签及抽屉、按需正文/差异和缓存、深浅主题与窄屏、大列表服务端分页、响应大小/延迟/数据库执行计划 |
| 安全与升级 | API/日志/指标不泄漏凭证或正文；Java/Go 配套升级能力门；最终镜像与验收提交一致 |

最终交付应使用隔离的 release Compose、MinIO 与 Scanner，记录实际提交、配置生效、迁移历史、业务断言及清理结果。配置和启动命令见 [管理员手册](../../../docs/github-sync-operations.md)。不要把编译、mock 响应、历史样本或条件跳过当作部署与公网采集通过。

## 验证边界

ARM64 整镜像、持续公网召回、长期容量、所有旧二进制混跑及反向数据库降级需要各自独立验收；本地功能用例不能给出这些保证。搜索受 GitHub 索引、结果上限和额度限制，不承诺全站穷尽或生产 SLA。完整任务完成状态保留在 [tasks.md](tasks.md)，延期项保持未完成。
