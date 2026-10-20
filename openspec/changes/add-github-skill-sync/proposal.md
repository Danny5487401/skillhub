## Why

SkillHub 开源版目前需要管理员逐个上传 Skill，无法从公开 GitHub 仓库持续发现和更新以 `SKILL.md` 为根的技能包。Issue #516 需要一套可私有化部署、可观察且受审核约束的服务端同步能力，让社区来源进入市场，同时保持 SkillHub 对版本、权限和发布的控制。

## What Changes

- 在管理后台增加 GitHub 来源管理：平台管理员可配置结构化搜索规则、精选仓库及精选用户或组织；TEAM 管理员可为本团队登记公开仓库。自动发现的来源归不可删除的 `@github` 平台 namespace，TEAM 来源归自己的 namespace。
- 增加独立 Go 采集服务，与 SkillHub Java 一起部署。Java 持有配置、凭证、任务、租约、来源账本、版本和审核；Go 轮询领取发现或仓库同步任务，访问公开 GitHub、固定 commit、枚举 `SKILL.md` 并逐 Skill 包上传临时采集对象，逐包回报结果。
- 新 Skill 和内容变化通过现有安全扫描及独立的同步审核队列进入发布流程。同步内容保持上游只读；已发布版本、待审目标、路径冲突、上游不可达或目录消失都有明确且可审计的处理方式。
- 管理后台展示规则、来源、运行进度、GitHub 额度、延后与失败原因；同步任务依据额度、执行容量、审核积压及 namespace 公平性控制节奏。管理员可手动触发或暂停，但手动操作不绕过限流和审核门禁。
- 首期只处理 GitHub 公开仓库，支持匿名 API 或管理员配置的系统级公开读取凭证池。不纳入私有仓库、Gitee、CLI 导入、团队自有凭证池、团队自动搜索或自动审核通过；这些方向另行评估。

## Capabilities

### New Capabilities

- `github-source-management`：公开来源的登记、自动发现、归属、规则、权限及管理页面。
- `github-sync-execution`：Java 与 Go 的任务协作、固定快照、增量更新、限流、断点续跑和可观察状态。
- `github-sync-review`：同步内容的独立审核队列、版本更新、冲突处理及发布保护。

### Modified Capabilities

无。当前主 OpenSpec 没有需要修改的已归档 capability；本变更通过新增能力复用现有 Skill 生命周期与审核实现。

## Impact

- **后端与数据**：新增来源、规则、凭证引用、发现运行、仓库进度、逐 Skill 结果及租约记录；扩展 namespace 治理、包导入、扫描与审核编排。
- **服务与部署**：新增单个 Go 服务容器，支持私有化部署；SkillHub Java 提供版本化的服务间任务接口和短时授权的临时对象上传入口。Go 不直连 SkillHub 数据库，也不具备发布权限。
- **Web**：新增 GitHub 同步管理页、TEAM 范围内的来源管理视图、单独的同步审核列表及 GitHub 来源展示。
- **安全与兼容**：公开 GitHub 仓库不自动获得 SkillHub 公开发布资格；保留上游许可证与 NOTICE，所有采集包由 Java 重新校验。现有人工上传、Skill 版本和普通审核路径继续可用。
