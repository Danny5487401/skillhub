# add-github-skill-sync

服务端 GitHub 公共 Skill 发现、采集、审核与同步。

## 文档入口

- [技能同步统一管理入口](unified-admin.md)
- [统一管理实施合同](unified-admin-contract.md)
- [验证入口与边界](verification.md)
- [同步领域术语](glossary.md)

- [官方深度探索需求](deep-discovery.md)、[设计与验收矩阵](deep-discovery-design.md)、[持久化设计](deep-discovery-storage.md)
- [Go 服务产品需求](go-service-prd.md)
- [总体架构](design.md)与[状态／检查点设计](state-model.md)
- [Java–Go 内部 OpenAPI](../../../docs/api/github-sync-internal.openapi.yaml)与[接口说明](../../../docs/api/github-sync-internal.md)
- [官方探索管理 OpenAPI](../../../docs/api/github-deep-discovery.openapi.yaml)与[接口说明](../../../docs/api/github-deep-discovery.md)
- [实施任务与剩余事项](tasks.md)
- [验证说明](verification.md)

## 当前交付边界

Java 持久配置、任务和恢复状态，Go 独立执行公开仓库发现与逐包采集，包经上传和 Java 复核进入同步审核。平台和 TEAM 公开来源、管理页面、开源技能展示、审核页与同步修订号已有实现；官方深度探索纳入同一控制面和管理入口。

交付完成状态以任务清单为准；验证入口、必须观察的业务行为和延期边界见验证说明。构建和历史本地测试不能替代最终提交的集成验收。

## 启用边界

官方探索默认关闭。全平台和 namespace 水位初始为空，管理员配置前 Java 拒绝新候选树检查与逐包准入；已登记来源仍可执行 HEAD 检查。Go 在线、GitHub 凭证／额度可用、预算和容量允许是不同条件，启用开关不等于任务已执行。配置与部署步骤见[运行手册](../../../docs/github-sync-operations.md)。

OpenSpec 保存正式需求、设计和验收标准；Trellis 仅保留本地执行记录，不作为第二套需求，也不提交本地任务与凭证。
