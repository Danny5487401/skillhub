# 官方 GitHub 深度探索接口增量（D2）

> V119统一管理补充：平台全部任务现在还要求 `platform-pause/v1`。当前Go声明四项官方能力加平台暂停能力，缺少后者的旧实例不能领取 `@github` 任务，也不显示为兼容实例。TEAM规则不变。管理员入口合并至“技能同步 → 采集配置”，详见[统一管理接口](github-sync-admin.md#统一技能同步管理)。
管理端机器可读合同见 [管理 OpenAPI](github-deep-discovery.openapi.yaml)，Java–Go见 [内部 OpenAPI](github-sync-internal.openapi.yaml)。本文固定当前协议；验证入口和限制见[验证说明](../../openspec/changes/add-github-skill-sync/verification.md)。

正式行为以 [需求](../../openspec/changes/add-github-skill-sync/deep-discovery.md)、[设计](../../openspec/changes/add-github-skill-sync/deep-discovery-design.md) 为准。本文固定字段与交互合同，物理表、索引与迁移见同一 OpenSpec 下的[持久化设计](../../openspec/changes/add-github-skill-sync/deep-discovery-storage.md)。

## 1. 已核对的实现基线

| 代码 | 当前事实 | D2 增量 |
| --- | --- | --- |
| `server/skillhub-app/src/main/java/com/iflytek/skillhub/controller/admin/AdminGithubSyncController.java` | `/api/v1/admin/github-sync`，`SUPER_ADMIN`，已有来源/运行/水位/周期接口 | 独立AdminGithubDeepDiscoveryController已实现配置GET/PUT与六个读模型接口，并完成受控HTTP验证 |
| `server/skillhub-app/src/main/java/com/iflytek/skillhub/dto/ApiResponse.java`、`PageResponse.java` | `code/msg/data/timestamp/requestId`，成功 code=0；分页 `items/total/page/size` | 保持包装，分页增加 `asOf` |
| `server/skillhub-app/src/main/java/com/iflytek/skillhub/controller/GithubSyncInternalController.java` | claim已扩展可选capabilities并按持久能力过滤；官方响应与legacy互斥；官方批次与仓库控制已接线 | 增加能力过滤、独立官方快照和出处；202不变 |
| `github-sync-go/internal/controlplane/client.go` | Go生产工作池声明四项官方能力，执行官方客户端与循环 | 启用前全部Java/Go升级；实例能力心跳已持久化并验证 |
| `server/skillhub-app/src/main/java/com/iflytek/skillhub/controller/GithubSyncWorkerHeartbeatController.java`、`repository/GithubSyncWorkerPresenceRepository.java` | 保留legacy全局联系时间，同时保存实例ID、协议与能力 | 官方在线判定使用实例能力和最近联系；心跳不证明业务正在推进 |

Web SDK 已从当前 Java 运行时 `/v3/api-docs` 生成。管理页面使用生成类型；Go wire 类型与内部合同保持同步。受控集成验证不替代真实 GitHub 搜索效果对照，完整边界见[验证说明](../../openspec/changes/add-github-skill-sync/verification.md)。

## 2. 管理端路径与授权

共同前缀：`/api/v1/admin/github-sync/deep-discovery`。所有读写仅 `SUPER_ADMIN`，范围固定为公共 `@github`，不接受客户端指定 namespace。写入沿用会话、CSRF 与审计；记录用户字符串 ID、配置版本及规范化差异。响应 `Cache-Control: no-store`，不包含凭证、租约、上传票据、原始查询响应或异常正文。

| 方法 | 相对路径 | data及实施状态 |
| --- | --- | --- |
| GET | `/configuration` | 当前启停、预算、排除项及version；已实现，隔离HTTP验证通过 |
| PUT | `/configuration` | 整体保存后的配置；请求含expectedVersion；已实现，隔离HTTP验证通过 |
| GET | `/summary?from=...&until=...` | 近期成果、当前积压、联系状态、等待原因、最多5个近期runId |
| GET | `/progress` | 当前策略各渠道的持久分片计数、最近业务进度和额度观测 |
| GET | `/repositories` | 仓库摘要分页 |
| GET | `/repositories/{repositoryId}` | 按需详情及关联sourceId/runId |
| GET | `/repositories/{repositoryId}/provenance` | 出处分页 |
| GET | `/repositories/{repositoryId}/paths` | 最近逐路径结果及Skill/version/review ID分页 |

没有“执行一次”接口。既有 `/runs/{runId}` 及其 `/tasks`、`/paths` 继续承担运行详情；本次不另造运行对象。历史清理后的关联runId可失效，详情返回404时页面提示历史已清理。导航使用现有权限校验，不能拼造未经实现的前端路由。

错误沿用 `ApiResponse` 数值 code，HTTP 400 为参数不合法，401未登录，403权限或CSRF不满足，404目标不在可见官方范围，409配置版本冲突。当前配置接口沿用GlobalExceptionHandler，以HTTP状态码作为数值code；配置冲突使用DomainConflictException及本地化消息。前端不得匹配翻译后的msg。

## 3. 配置并发、排除与预算

V108迁移持久初始化 `version=1, enabled=false, budgetProfile=BALANCED, exclusions=[], strategyVersion=null, updatedBy=null`；strategyVersion在官方目录调度首次事务对账后设为已交付目录的真实版本；这表示目录已装载，不表示结果接收/执行链路完成。三个预算档位是 `ECONOMY/BALANCED/ACCELERATED`。这些值仅命名档位，当前预约节奏以预算设计为准，部署方仍须结合实际额度校准，不能把加速理解为绕过水位或删除探索渠道。

PUT 请求例（示意数据）：

```json
{"expectedVersion":1,"enabled":true,"budgetProfile":"BALANCED","exclusions":[{"kind":"OWNER","value":"example"},{"kind":"REPOSITORY","value":"sample/skills"}]}
```

在同一事务中比较 expectedVersion 与当前 version；相同且有效则保存并递增 version，过期则409且不写任何字段。即便重复相同内容也先比较版本，不静默覆盖其他管理员操作。客户端重新读取并让管理员确认合并，不自动重试旧表单。官方策略升级只更新 strategyVersion，不能覆盖启停、预算和排除项；管理员配置的实际保存才递增version，策略版本单独演进；运行任务同时记录两者。

排除项最多1000条，OWNER 为登录名，REPOSITORY 为 `owner/name`；按合法 GitHub 坐标格式校验、统一大小写并去重，拒绝URL、通配符与查询语法。规范化后整体替换；返回规范化列表。排除按本次已核实的当前坐标匹配，不承诺旧名字跟随改名或转移。

调度语义：关闭/排除停止官方新发现及官方独占未登记候选的后续采集，保留候选、出处、进度。已登记来源按自身设置继续；其他独立启用入口需要的候选继续。取消排除或重开自动续排，不能复活已失效的租约。

## 4. 有界列表与按需详情

- 沿用 `page=0,size=20`；size为1–100，page为0–1000，且 `page*size<=100000`。超出返回400，提示缩小筛选；上限是合同防护，不是深分页性能已达标的证明。默认全部状态，服务端支持 q（owner/name，最长200）、status、channel、paused，多个条件取交集。
- 仓库按 `(firstDiscoveredAt DESC, candidateId DESC)` 稳定排序，firstDiscoveredAt为候选全局首次发现时间；candidateId为本地候选ID，详情仍按稳定repositoryId定位。首次缺省asOf使用服务器时间，后续沿用响应asOf，仅纳入 `firstDiscoveredAt<=asOf` 的候选。出处按 `(lastSeenAt DESC,id DESC)`、路径按 `(skillPath ASC)`，均继续分页。
- asOf是插入截止时间，**不是历史一致性快照**：已有记录的状态、排序时间与过滤条件仍可能变化，跨页可发生移动。客户端固定当前显示页，提示有新数据后手动刷新，禁止轮询自动重排；一致性快照或游标迁移要依据D3执行计划/规模测量另行收敛，不能暗示当前设计消除了页间漂移。
- total仅统计同过滤范围，不在每页重新计算业务成果聚合；实施时采用匹配索引和受限计数/缓存策略，缓存窗口须公开在观测时间中。所有列表最多100项，详情只含摘要；出处、路径、正文、日志不一起打包。
- progress最多3个默认渠道、100项额度观测；若主体超过上限，`quotaObservationsTruncated=true`，不得把截断后列表当完整额度池。等待项每组最多32项；同原因/工作类别合并。
- 关闭抽屉保留筛选与位置；页面离开或隐藏时停止轮询，请求不叠加。每次返回observedAt，asOf和observedAt作用不同。

## 5. 成果、积压与等待口径

近期区间 `[from,until)`，UTC时间，最长90天，from<until且until不得晚于服务端当前时间；这是查询边界，不决定数据保留期限。

| recent字段 | 持久事件和去重键 |
| --- | --- |
| newCandidateRepositories | namespace内候选首次创建时间；当时已有官方出处。按repository ID去重。已有候选后来首次被官方命中不计“新增候选” |
| firstValidRepositories | V115的FIRST_VALID事件及其occurred_at，official_at_event记录事件发生时的官方归因；同仓库一次。无法证明的历史不补造时间，不能用当前BOUND状态、最后命中或续租时间倒推 |
| newSkills | 首次成功完成采集校验并创建新Skill及版本的持久交接事件，按skillId去重；仅下载、202接收、手工绑定已存在Skill不计 |
| updatedVersions | 已有Skill创建新内容版本的交接事件，按versionId去重；与newSkills的首个版本互斥，重放或NO_CHANGE不计 |

这些是采集成果，不是发布数量。事件发生时固定归因，其他入口同时参与则保留共同出处，不宣称官方独占贡献；后补官方出处不能改写过去成果。历史缺少first-valid或归因事件时 `complete=false`、`availableSince`标明完整可用起点；受缺失影响的指标为null，禁止默认为0。完整期间零成果才是0。

current独立统计此刻曾有官方出处的@github仓库：pendingRepositoryChecks为待检查的去重仓库数（含暂停等待），pendingReviewVersions为仍在待审的去重版本数。它不随recent区间变化，关联入口共享项只计一次；当前筛选与来源归属依据数据库权威状态。`current.observedAt` 与 recent 聚合的 summary.observedAt 区分缓存/观测时间。

等待原因区分关闭、排除、正常预算、主限额、次级冷却、凭证不可用、候选容量、审核积压、执行器无联系、能力不足与覆盖缺口。每项包含受影响工作、仍能继续的工作、恢复条件与相关操作类别；retryAt无可靠依据时为null。到时只允许重新领取，不代表GitHub请求已执行。

内部credential-status新增可选officialQuotaObservation，保留旧githubQuota的必填项和状态枚举；新字段明确观测时间、主/次级限流与UNKNOWN，主体由Java按租约凭证关联，不能由Go任意指定。额度按资源类别与可确认的共享主体展示最近观测；不同token不能简单相加。未知主体不承诺独立额度；过期/缺失为UNKNOWN且remaining=null。reset/retry时间只作预计重试，不承诺恢复。凭证认证失败与限流分开；次级限流收紧共享请求并遵守退避，不能不断换token冲击同一限制。

## 6. 内部能力协商与固定快照

保留 `/internal/github-sync/v1`、`protocolMajor=1`、原任务kind、原discovery.entryType、状态和错误枚举。以下兼容扩展已接线，验证范围见[验证说明](../../openspec/changes/add-github-skill-sync/verification.md)：

1. claim新增可选 `capabilities: string[]`（最多32项），缺省空表示只支持旧任务。官方发现任务要求 `official-discovery/v1`、`official-checkpoint/v1`、`official-pause/v1` 全部匹配；官方独占候选的REPOSITORY_SYNC任务仅要求新增控制能力 `official-pause/v1`（已有仓库采集协议能力仍适用）；不兼容时不领取该任务，仍可领取适配的旧任务或返回204。未知能力忽略，不能当通配支持。
2. 官方发现仍是kind=DISCOVERY，但仅返回 `officialDiscovery`，不返回legacy discovery；response.requiredCapabilities明示完整能力集合。官方独占候选仓库任务同样携带requiredCapabilities并受领取过滤，以确保暂停合同生效。来源已登记或其他入口独立需要时保留对应既有执行资格。
3. 快照固定formatVersion、策略/查询版本、shardId、渠道、执行模式、结构化query、固定范围和fixedCutoffAt、预算以及已确认checkpoint。README与元数据有不同scope；KEYWORD的value保持1–256字符的有界整段短语，README也按完整短语编译，不拆词，只接受字母、数字、普通空格、减号、下划线、点和斜杠，不接受原始查询语法。不扩大自定义规则KEYWORD的含义；代码搜索不在v1默认渠道和scope中。
4. 作者扩展只接受已有有效种子，附seedRepositoryId、稳定ownerId、当前ownerLogin和expansionDepth=1；同作者复查进度去重，新作者不递归扩散。
5. 快照configurationVersion只供追溯。启停、排除、有效归属、租约与当前准入在领取和关键接收时再次检查；策略快照不会授权绕过最新管理决定。策略切换不在旧任务中热替换query/window。
6. 心跳可选body增加workerId/protocolMajor/capabilities，带body时三个字段必须齐全；老式无body或{}仍有效。Java按进程实例保存服务端接收时间，重启换workerId。可执行状态要求实例近期心跳且其claim声明支持任务能力，不将全局旧心跳冒充官方实例在线。lastProgressAt来自批次/包/检查点业务提交，不能取因renew变化的task.updated_at。
7. 部署顺序必须先Java持久字段和能力过滤，再Go执行能力，最后管理员启用。旧Java会忽略未知claim字段，因此新Go遇到不带官方快照的响应继续按旧合同处理。回滚到不会能力过滤的Java之前必须隔离/排空官方排队及重试任务；仅关闭页面不足以阻止旧worker误领。

## 7. 出处、分片与检查点提交

官方仓库搜索的OfficialQueryRange按 **pushed时间维度** 使用UTC整秒半开区间 `[since,until)`，必须满足 `since<until<=fixedCutoffAt`。Java把计划截止时间向下取整到UTC整秒并固定为fixedCutoffAt；起点从已确认覆盖边界减去保守重叠窗口后向下取整。下一窗口使用实际确认的整秒until衔接并继续重叠，不从未取整的原截止时间开始，因此被本次取整留到后面的不足一秒范围不会丢失；若取整后没有非空区间则等待下一次调度，不伪造窗口。具体重叠宽度在调度校准阶段确定。Go拒绝带小数秒的since/until，不静默截断；其有界编译将不含until转换为GitHub含终点的 `until-1秒`。这些要求只用于官方查询，不改变legacy RepositorySearchWindow或自定义规则窗口语义。

partition首期仅允许省略或空字符串，当前Go对非空值明确拒绝。Java在能力协商和次级分片执行器明确支持之前不得下发非空partition；达到整秒粒度仍不完整时保存GAP，不能假设次级分片已经实现。

`RepositoryCandidate.officialProvenance` 是可选新增字段，但官方任务回报必须提供与任务相符的出处。Java核对channel/strategy/query/version/shard/seed并补run/task/namespace身份及接收时间，不信任Go自报归属。候选按(namespace,repository ID)去重，出处按候选+持久queryId（UUID）合并first/lastSeen；同仓库多渠道可有多条出处，不重复创建Skill版本或待审目标。长期出处不按每个shard/seed增行；详情仅保留最近分片/种子的有界诊断摘要，可随运行保留期清空，queryId及first/lastSeen独立持久保存。

现有批次上限100仓库、previousCheckpoint与checkpoint及202空body保留。官方批次附officialBatch，checkpoint是OfficialCheckpoint对象的JSON字符串，仍限制4096字符并额外限制4096 UTF-8字节。首批previousCheckpoint=null；后续使用Java已确认的原始字符串，禁止客户端重编码后当相同值。

一个事务持久候选/出处、批次幂等摘要、分片结果/子分片与新检查点后才返回202。响应丢失可重放：同前后checkpoint和规范化业务payload返回原结果；payload不同RESULT_CONFLICT，游标已前进且非同批重放CHECKPOINT_CONFLICT。不能只写checkpoint再异步补候选，也不能丢弃超预算部分后推进整页。额度/候选预算中途耗尽必须保存可恢复的页内cursor或重放该页，去重后继续。

coverage仅描述当前分片：IN_PROGRESS继续，COMPLETE该分片已处理，SPLIT已经原子登记覆盖父范围的子分片，GAP保存未覆盖范围与原因。按时间拆分的childRanges必须非重叠且并集等于父范围；同一时间无法继续拆分时，首期直接保存GAP；未来仅在能力协商通过且受控执行器已实现时才能采用非空次级partition，不无限拆分。childRanges最多16，累计拆分深度和子任务数由Java复核；预算耗尽不写虚假的COMPLETE。

官方发现完成仍调用已有complete：最后discoveryCheckpoint必须等于持久确认值；COMPLETE只允许分片已完整完成或已持久交接子分片，GAP/IN_PROGRESS必须INCOMPLETE。父分片SPLIT完成不等于渠道完成，子分片独立计数；保存缺口与自动重试条件。旧checkpoint编码不变。兼容查询复用长期进度，新增或语义改变的queryVersion新建历史补搜；无法迁移的进度记录原因，从可证明安全的边界重放。长期查询进度不能只寄存在会被历史清理删除的task里。出处/成果事件独立保留；原task_hit/batch随终态任务清理级联删除，不能作为长期归因的唯一依据。

发现游标无法解析、版本不支持或身份不匹配时，不调用旧fail反复重试同一游标；调用 `POST /tasks/{taskId}/official-checkpoint-recovery`，回报结构化原因及被拒绝的原游标。Java验证租约与持久游标相同后原子停止旧进度、记录原因、安排原固定范围内的安全重放，返回REPLAY_SCHEDULED及replacementTaskId。若无可证明的更近边界，重放原任务整个固定范围，不猜测跳页；候选/成果依赖幂等去重。旧租约失效、游标不一致分别沿用LEASE_STALE/CHECKPOINT_CONFLICT。

## 8. 暂停、接收与离线安全边界

新增的 `POST /tasks/{taskId}/official-control` 使用LeaseProof，返回CONTINUE或PAUSE与最新配置版本；Go在开始下一页、候选树检查或包准入前查询。该响应不构成永久授权，Java在实际写入时仍核对当前配置、独立入口归属和租约。

- 发现请求已经在途：允许有效租约提交当前受限批次及检查点；关闭/排除命中的候选保留但不启动官方后续采集。页面停用不回滚已持久接收事实。
- 官方发现抵达安全边界后调用新的 `POST /tasks/{taskId}/official-pause`，携带lease、kind=DISCOVERY和最后确认discoveryCheckpoint（从未提交为null）；Java事务中复核当前仍应暂停、游标和代际，释放租约并保存暂停原因，返回新端点专用PAUSED。配置已重开/独立资格变化时返回新端点专用409 CONTROL_CHANGED，重新查control后继续；旧租约返回409 LEASE_STALE。PAUSED不是对旧complete返回枚举的扩展。
- 官方独占候选仓库也调用同一 `official-pause`，携带lease、kind=REPOSITORY_SYNC与repositoryProgress；要求任务requiredCapabilities含official-pause/v1，不要求officialDiscovery快照。**不再用旧complete承担配置暂停**；旧complete的合法commit和未来retryAt要求保持原样。
- repositoryProgress包含observedCommitSha、pathCheckpoint、incompleteSubtrees、pathResults。尚未取得commit时SHA及路径checkpoint为null，两个数组为空；Java确认任务尚无固定commit、已接收包或绑定commit的准入后才接受。已有固定commit时必须提交相同SHA（不符COMMIT_MISMATCH）；若首次在此报告合法SHA，可原子固定该SHA并保存进度。pathResults最多10000、incompleteSubtrees最多1000，遵循原路径规范；不得推进来源完整处理边界。
- 已持久接收包依据Java摄入记录保留，ACCEPTED路径必须引用同任务/commit/路径的ingestionId；漏报既有接收项不能将其撤销。Go先结束在途上传/回报，再提交暂停；暂停事务保存检查点、路径结果/重试路径并释放租约及未用准入占位，不要求retryAt。恢复领取沿用固定commit及acceptedPaths，不重复上传。未接收暂存对象仍受原票据/租约及清理合同管理，暂停不会把它们冒充已接收包。
- Java在暂停事务中重新解析候选当前归属，而非只看领取时source_id=NULL：途中已经BOUND成为正式来源、其他独立启用入口仍需要采集，或配置已恢复允许执行时，返回409 CONTROL_CHANGED，不写暂停进度、不释放当前租约；Go重新查询control并按当前资格继续。只有仍应暂停且租约、commit和路径合法时，保存进度与释放租约才原子成功。
- 候选enqueue及source_id=NULL的REPOSITORY_SYNC claim都检查最新配置/有效入口，不能只门控DISCOVERY。关闭后新包准入受阻，沿用BACKPRESSURE_ACTIVE并通过official-control区分原因；已持准入/已上传包允许以有效租约提交摄入，已接收或上传事实不删除、不回滚。后续新包在恢复前不启动。上传不是Skill创建或发布成功。
- 租约过期、被接管或代际不符时，所有进度/包/暂停写入拒绝；不以幂等为名接受旧租约。最后未确认批次可由新租约重做，业务接收保持幂等。

Go无联系时Java保留任务、检查点和retryAt，优先显示等待采集服务恢复；不继续声称正在执行GitHub请求。多实例仅在没有任何适配实例时显示官方能力不可用；有心跳不等于处理正常。进程启动恢复归部署工具负责，Java不拉起Go。

## 9. 验收与后续扩展

当前预算档位已使用持久预约节奏，具体规则见[预算设计](../../openspec/changes/add-github-skill-sync/deep-discovery-design.md#3-预算凭证与暂停)。未知额度主体不强行合并，统计缺失不补造历史；非空次级分片和代码搜索不属于默认策略。验收须观察能力门、配置并发、多渠道去重、检查点原子、关闭/排除竞态、独立入口继续运行、接管、统计归因以及列表规模，见[验证说明](../../openspec/changes/add-github-skill-sync/verification.md)。

## 14. 有界顺序时间分片（V112）

- Java新root的工程初值为最大深度32、累计最多126个child（含root最多127节点），单任务仍最多10次搜索请求/10页；并非预展开完整二叉树，也不表示已校准或保证无缺口。`maxSplitDepth`为当前节点剩余深度，`maxChildShards`为当前root剩余累计child额度，允许0，少于2不能二分。
- 每query仍只有一个active任务。SPLIT批次必须恰有两个UTC整秒、无重叠且完整覆盖父范围的子区间；Java在root行锁下复核深度和累计额度，候选、批次、父checkpoint和PENDING子节点原子写入。相同摘要重放不重复登记。Go确认202后以原SPLIT checkpoint调用complete(COMPLETE)；此成功仅终结父任务并交接，不是query覆盖成功。
- scheduler优先取已有root的PENDING子节点，继承固定策略/query/range/cutoff；退休query的已有子树继续，但不创建新root。只有所有叶子均COMPLETE，才按root原始until推进covered_until；GAP或任何未完成叶子均不能推进。现有无ledger任务按其原固定快照与旧预算补建根并结算。
- 已登记SPLIT拒绝checkpoint recovery，不能把父当作未交接范围再次重放。普通恢复只替换当前节点task关联，不改变root或范围。终态FAILED的ACTIVE节点可安全回到PENDING重放；SPLIT父FAILED则保留已登记children，清旧task关联后继续子节点，不重复创建子树。
- ledger包含固定快照，不依赖父task历史。task清理只将ledger.task_id置空，不能级联删除未完成树；完整root在下一root开始前删除，退休query的完整root也按既有history cutoff有界清理。持久query覆盖和candidate出处不删除。
- 策略执行代次升为2/official-search-v2，query语义版本保持不变。代次只阻止旧scheduler回写，**不能保证旧Java接收器理解child结算**；首次启用前全部Java和Go实例须升级并完成迁移与集成验收；旧Go虽然声明相同三个v1能力，却拒绝超过16的child预算，因此本片不支持旧显式opt-in worker混跑。生产 Go 声明完整能力，但能力名相同不能证明旧二进制理解新预算。
- GAP以原checkpoint、INCOMPLETE和未来retryAt请求结算，返回PARTIAL明确未完整；释放query活动指针，先执行PENDING兄弟，再重扫到期GAP。无PENDING时next_due_at取最早GAP retryAt。任一GAP仍阻止根覆盖推进。
- ledger保留GAP原checkpoint（含cursor.revisit累计退避）、原因、retryAt和最后generation。复用PARTIAL task/run时只收紧预算，query/range/cutoff/策略固定；清终结时间及旧租约字段，generation不重置，下次claim继续递增。仅删除该已终结任务的旧batch/task_hit诊断；候选首次发现/官方出处不删除，重扫不能当业务新增。
- 历史清理后task_id置空，ledger仍保留退避和检查点；重建任务仅改检查点shardId，保留cursor/revisit与generation。普通IN_PROGRESS仍原任务RETRY_WAIT，旧自定义任务完成规则不变。
- 默认Go能力保持关闭，尚未执行SQL/HTTP/并发或端到端验收。


## 15. 有效作者扩展（V113）

- 仓库metadata新增可选`ownerId`（稳定GitHub账号ID），保留旧请求构造与缺省语义。Java仅在有效租约、仓库ID相符时保存身份；省略不靠login推断，并清除该仓库旧稳定ID关联，避免转移后误将新login归给旧ID。实际包`validated`成功事务才持久seed证据，证据不随task/ingestion历史清理消失，BOUND或收到上传均不等价。
- 只有同时有metadata/README等非OWNER官方出处的有效仓库才能生成作者查询。按namespace+ownerId去重，固定seedRepositoryId、depth=1；OWNER发现本身不递归生成新作者。被OWNER先发现的库后来独立搜索命中且已有真实验证证据，可成为普通seed。每轮最多物化100个新owner，后续调度继续处理其余。
- 动态query_source=OWNER独立于内置目录退休；其语义身份是ownerId，规范login只是坐标，改名不提升queryVersion；策略代次为3/official-search-v3，query语义版本未变。OWNER任务除三项官方能力外必须声明`official-owner/v1`，search-only opt-in实例不能领取。
- 快照mode=OWNER_RECHECK，range/cutoff只标识固定执行身份与复查截止，不按pushed过滤；不进入时间分片ledger，不更新covered_until。完整枚举只更新last_full_recheck_at及下次复查时间；当前周期复用search_rule_hours，未校准。
- Go每租约通过`GET /user/{account_id}`解析稳定ID的规范login/type，逐仓库复核owner ID，并在末页再次校验身份。lookup后的首空批通过可选officialBatch.ownerLogin确认规范坐标，再继续枚举；该字段进入幂等摘要，Java保存坐标后下一control能响应新login排除，固定快照ownerId不变。改名或旧login复用不得换账号；错误/身份不一致保留未完成进度退避。
- 单租约仍最多10次CORE请求/10页，身份读取与末页复核均计请求，快照maxRequests至少3。每次列页前预留页请求及可能末页复核预算。OWNER检查点页码为正int32，按page/offset持续续页，无10页总上限；整型边界仍有结果时记RESULT_LIMIT GAP。OWNER GAP保持RETRY_WAIT，搜索GAP才轮转为PARTIAL；页列表是可变集合，后续复查弥补改名/新增造成的位移，不宣称一致性全量快照。
- owner排除按动态query最近确认的规范login在入队、claim、control/pause复核；所有返回仓库再按当前owner/name排除，已存在来源和独立入口资格维持第13节规则。暂停确认已接收页，不回滚候选。关闭/owner排除后同配置/租约最多接收一个新的在途批次，重放幂等不消耗该边界。
- 默认能力仍关闭；本片仅编译与静态复审，不代表SQL迁移、GitHub真实枚举、HTTP或跨进程恢复验收通过。


## 16. 管理员读模型与成果证据（V115）

六个只读端点由独立 `AdminGithubDeepDiscoveryReadController` 提供，固定 `@github`、`SUPER_ADMIN`、`no-store`。列表、出处和路径各自限制 page 0–1000、size 1–100（默认20）；列表先分页再聚合出处。`q` 使用字面子串匹配，不解释正则、SQL通配符或GitHub查询。`asOf` 限制插入截止，后续状态仍是最新投影，不声称历史事务快照。

近期指标基于 V115 长期事件；事件与实际候选创建、包验证及版本交接同一事务。候选 `INSERT DO NOTHING RETURNING` 判断真正新建，后补官方出处不计新增；首次有效必须通过真实包验证，不能从 BOUND 推断。发布结果携带实际创建分支的 `newSkill`，新 Skill 与已有 Skill 更新互斥，未变化、路径移动、仅绑定不发成果事件。事件保留当时官方归因及查询身份，不依赖运行日志存活。

迁移记录 `availableSince`。早于该时间的查询指标返回 null、`complete=false`。旧 validated 摄入、绑定及种子保存无时间先前验证标记；缺旧证据的候选进入未知集合，其后验证不能冒充确定的首次有效：只有查询区间内出现这种官方事件时，`firstValidRepositories=null`、`complete=false`，其他可靠指标仍返回数值。旧数据不按现在的出处伪造历史归因。

候选 REJECTED 区分 `NO_VALID_SKILL` 与 `VALIDATION_FAILED`；后者不是“没有技能”。`lastDiscoveredAt`取官方出处最近命中，`lastCheckedAt`取保留任务最近结算（包含失败尝试）。历史完整边界不可证实时 `historyAvailableSince=null`。路径保留 `UNCHANGED` 原始结果；原始错误正文不会充当 reasonCode。待审数量以 `SKILL_VERSION` 类型的真实 PENDING 审核记录按版本去重。

渠道进度包括仍在续跑的退休查询。搜索使用当前树叶子，尚未建立树的到期查询算一个待处理工作单元；作者使用当前一层查询，只有真实 `last_full_recheck_at` 才能证明最近枚举完成。根被下一次复查替换后计数可下降，不是历史累计或全站覆盖率。RUNNING 同时要求有效租约与90秒内业务进展，不使用续租或配置开启代替进度。



## 16. 额度观察与共享冷却（V116）

- 新`POST /tasks/{taskId}/official-quota`接受`{lease, observation}`，只允许当前具official-pause/v1的租约。Java从租约关联凭证（可以匿名），不信任客户端主体声明。旧credential-status仍必填credentialId/status，附加officialQuotaObservation已接收并保持原字段一致性检查。
- 观察按凭证键/resource保存，主体当前未知保持null。保存observedAt/receivedAt/validUntil，时间允许偏差300秒，观察有效期不超过5分钟，过期不显示确定余额；不累计token余额。未知主体不累加额度，也不证明不同凭证额度独立。
- 认证PRIMARY_EXHAUSTED及真实OBSERVED remaining=0写入租约绑定credential/resource的额度记录；匿名才按resource保存primary_until。单个SEARCH耗尽不阻断其他当前可用凭证，也不写成整凭证停用。SECONDARY_COOLDOWN使用部署级cooldown_until；缺省60秒、最多24小时，重复同observedAt不延长。UNKNOWN带retryAfterSeconds也触发共享失败退避，无余额不补造0。
- 新claim先检查共享门控：命中则延期刚领取租约并不返回任务；有任何已配置凭证但当前全部不可用也延期，不回落匿名。仓库CORE的新包准入受同一冷却约束，已有准入重放与上传/接收可以结算。官方control返回QUOTA_COOLDOWN/retryAt，pause按当前服务端时间转RETRY_WAIT；来源自身PAUSED仍优先。旧无官方能力的在途worker没有逐请求control保证，但新claim/admission仍受门控。
- 替换为不同token时同事务清理旧凭证的资源额度与官方观察；同token重存不清余额。官方与legacy租约报告按task→credential锁顺序再次核对fingerprint，替换后的旧报告不能写回额度或失效状态。
- Go在官方REST/Git请求边界同步观察（匿名也报告）；持久回报失败停止本租约后续请求并使用进程共享退避，不通过轮换token继续。PRIMARY进程门控按token摘要和resource，认证失败按token摘要隔离，SECONDARY与回报失败共享全进程；Git缺配额响应只报UNKNOWN，不伪造OBSERVED。
- GitHub剩余额度没有预约账本或“实时余额”承诺；V117官方执行预算是独立的持久节奏控制，不能抵扣或代表GitHub额度。生产Go声明能力，Java配置默认关闭。


### 等待反馈的证据与优先级

渠道等待聚合查询、当前任务、缺口叶子与作者实时排除，不再选取单个最小字符串。详情按当前仓库资格、来源自身暂停、活动任务、实例能力、水位和额度共享保护组合；同原因合并受影响工作，最多32项。历史任务的 DISABLED/EXCLUDED 不覆盖现时已恢复资格，过期冷却标签不表示保护仍生效。

水位判断复用真实采集容量 gate（含未解决坐标计数、暂停后的低水位恢复条件），不是只看可能滞后的 paused 标记。来源自己的暂停显示 DISABLED、影响 SOURCE_UPDATE/PACKAGE_ACQUISITION，并提示恢复该来源；不会提示改探索开关解决来源暂停。

V116共享保护作用于所有任务的新领取；CORE保护也阻止新的FULL包准入，已准入包可结算。旧协议已领取任务不保证逐请求停。`QUOTA_COOLDOWN`表示共享退避保护，不能据此断言GitHub已确认次级限流。SEARCH保护只影响搜索探索，CORE还影响作者探索、候选验证、来源更新与包采集。

联系状态使用全局最近心跳判定是否有采集服务联系；渠道分别核对所需能力，官方独占未绑定候选核对暂停协议能力，已有来源不会因为缺作者扩展能力被宣称停机。`continuingWork`只包含有效租约且90秒内有实际业务进度、当前资格允许、且未被任何已知等待原因阻断的工作类别；没有此证据返回空，不承诺任务正在执行。无实例联系时不会声称其他工作继续。

配置过凭证、但当前资源没有可用凭证时，新领取任务保存 `CREDENTIAL_UNAVAILABLE` 并短暂延后；不默默降级匿名，也不把该状态分类为确定限流。管理员反馈实时按凭证启用/有效状态及该资源最近额度判断，恢复可用后立即不再显示旧凭证等待；后续成功领取同时清除持久任务/查询中的旧凭证或冷却原因。系统从未配置凭证或删除全部凭证时，沿用匿名能力；无可靠凭证恢复时间返回 `retryAt=null`，内部轮询延迟不是恢复承诺。
