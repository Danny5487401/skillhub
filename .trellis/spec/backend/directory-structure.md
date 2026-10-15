# 模块与目录约定

适用类归属、模块依赖和读写模型划分。架构目的见 [系统架构](../../../docs/01-system-architecture.md)。

| 职责 | 模块及包 |
|---|---|
| 实体、值对象、领域仓储接口 | `server/skillhub-domain`，`domain/{submodule}` |
| 领域服务、事件、本地化异常 | `domain/{submodule}/service`、`domain/event`、`domain/shared/exception` |
| 持久化适配器 | `server/skillhub-infra`，`infra/jpa`、`infra/repository`（跟随现有实现） |
| Controller、DTO、应用服务 | `server/skillhub-app`，`controller`、`dto`、`service` |
| 展示投影、跨来源查询 | `skillhub-app`，`repository/*QueryRepository` |
| 认证授权、搜索、存储、通知 | 对应 `skillhub-auth/search/storage/notification` 模块 |

包前缀为 `com.iflytek.skillhub`。沿现有业务子域命名，不按表格批量移动存量代码。
包应通过 `package-info.java` 说明职责；新增包补齐，存量缺失不借本次改动批量补写或搬迁。

## 依赖与职责

设计方向为 app 装配各模块，infra 实现 domain 仓储接口，auth/search/notification 使用领域模型。
Controller 绑定参数、提取可信身份、包装响应，不直接做领域状态迁移。
应用服务表达用例编排；领域服务返回领域对象，不返回 app DTO。
复杂 join、快照与展示投影放查询仓储；简单单聚合读取可以直接调用领域仓储，避免空壳转发层。
领域事件用于已有跨模块副作用协作；是否提交后处理、是否需要持久化投递，读 [事务专题](./transactions-and-concurrency.md)，不能仅靠事件名称判断可靠性。

## 已知偏差与审查

[domain POM](../../../server/skillhub-domain/pom.xml) 仍依赖 storage；这是与理想内层隔离的存量偏差。
本地修改不顺带修复整个架构，也不据此继续增加 domain 到 infra/auth/search/storage 的依赖。
新增跨模块访问检查 POM 和 import；业务确需改变边界时走设计评审并记录原因。

用户身份在认证、API、权限、审计与 owner/reviewer/actor 字段保持 String，依据 [领域模型](../../../docs/02-domain-model.md)。
不要推广为所有资源 ID 都是 String。

例证：[查询仓储](../../../server/skillhub-app/src/main/java/com/iflytek/skillhub/repository/JpaMySkillQueryRepository.java)、
[领域仓储适配器](../../../server/skillhub-infra/src/main/java/com/iflytek/skillhub/infra/jpa/JpaSkillStorageDeletionCompensationRepositoryAdapter.java)。
