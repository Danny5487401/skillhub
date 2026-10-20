# GitHub 同步管理接口（实施中）

本页记录已落地的管理接口；完整业务规则以 [OpenSpec](../../openspec/changes/add-github-skill-sync/README.md) 为准。普通用户会话、GitHub 凭证和 Go 服务密钥均不能代替平台 `SUPER_ADMIN` 权限。写入接口沿用 Web 会话的 CSRF 校验，并记录操作审计。

## 系统级公开 GitHub 凭证

平台 `SUPER_ADMIN` 可通过以下接口维护零枚或多枚系统凭证。Token 在管理员接口中只通过创建或替换请求体传入，不出现在管理列表与响应、持久任务快照、审计详情或 URL。管理响应使用 `Cache-Control: no-store`；写入沿用用户会话 CSRF 与审计。Go 只在受机器身份保护的任务领取响应中临时得到凭证，并以当前租约回报额度、认证失效或限流；限流不会把凭证标成失效。Java 按领取任务所需的 `SEARCH` 或 `CORE` 资源选择凭证：该类别已观测余额为零且重置时间未到时跳过，另一类别仍可使用；显式限流的全局等待时间仍优先生效。无可用凭证时任务仍可匿名访问公开 GitHub API。隔离的 Java HTTP/PostgreSQL 与独立 Go 进程已验证以上三种领取路径；真实 GitHub Token 不参与该测试。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/admin/github-sync/credentials` | 列出名称、Token SHA-256 前 16 位指纹、状态、密钥版本、密钥是否可用和限流时间，不返回 Token。 |
| `POST` | `/api/v1/admin/github-sync/credentials` | 以 `{"label":"公开读取 1","token":"..."}` 加密新增。 |
| `PUT` | `/api/v1/admin/github-sync/credentials/{id}` | 以同样请求体替换 Token、可修改名称，并恢复为可用状态。 |
| `PUT` | `/api/v1/admin/github-sync/credentials/{id}/enabled` | 以 `{"enabled":false}` 停用，或以 `true` 启用；认证失效项必须先替换 Token。 |

部署需提供 `SKILLHUB_GITHUB_SYNC_CREDENTIAL_KEYS`（`v1=<32 字节随机密钥的 Base64>`，多个版本用逗号分隔）和 `SKILLHUB_GITHUB_SYNC_ACTIVE_CREDENTIAL_KEY_VERSION`（如 `v1`）。不配置时只能匿名运行，创建凭证会被拒绝。轮换密钥时先保留旧版本并追加新版本为 active，旧密文仍可解；替换 Token 后采用新版本加密，确认所有旧密文都更新后才能移除旧密钥。不要把这些值提交到仓库或输出到日志。部署方应把它们放入 Compose 环境文件或 Kubernetes Secret，并确保 Go 到 Java 的内部连接采用可信网络与 TLS 边界。管理员页面已提供凭证表单及已观测额度；Go 会在有效 GitHub API 响应后按当前任务租约回报额度。额度回报失败不会阻断采集，页面数据可能延迟。

## 积压准入水位

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/admin/github-sync/admission-policy` | 读取全平台和单 namespace 的高低水位、候选预算、全平台暂停状态、当前未解决项与 `@github` 候选数量及准入状态。 |
| `GET` | `/api/v1/admin/github-sync/worker-status` | 读取 Go 采集服务的最后在线时间和在线判断；从未连接时 `lastSeenAt` 为空。 |
| `PUT` | `/api/v1/admin/github-sync/admission-policy` | 同时设置两组水位和候选预算；写入操作者和新旧值的审计记录。 |
| `GET` | `/api/v1/admin/github-sync/schedule-policy` | 读取仓库更新、精选用户或组织、广泛搜索三类目标周期。 |
| `PUT` | `/api/v1/admin/github-sync/schedule-policy` | 以 `repositoryHours`、`curatedOwnerHours`、`searchRuleHours`（均为 1–720）更新三类周期与已有入口。 |
| `GET` | `/api/v1/admin/github-sync/sources?page=0&size=20` | 分页查看平台已解析来源、最近任务和逐包摄入状态计数，包含失败包数 `failedPackages`。 |
| `GET` | `/api/v1/admin/github-sync/sources/{sourceId}/skills?page=0&size=20` | 查看来源各 Skill 路径最近一次采集、摄入与审核状态。 |
| `POST` | `/api/v1/admin/github-sync/sources/{sourceId}/rescan` | 将平台来源提前纳入正常调度；已有开放任务时合并请求。 |
| `POST` | `/api/v1/admin/github-sync/sources/{sourceId}/retry-rejected-skill` | 对一个最新审核结果为拒绝的 Skill 路径请求重新采集并送审。请求体：`{"skillPath":"skills/example"}`。 |
| `POST` | `/api/v1/admin/github-sync/conflicts/{ingestionId}/bind-skill` | 平台同名冲突确认绑定到已有 Skill ID，并重试该同步包。 |
| `GET` | `/api/v1/admin/github-sync/sources/{sourceId}/ignore-patterns` | 查看平台来源追加的候选目录忽略规则。 |
| `PUT` | `/api/v1/admin/github-sync/sources/{sourceId}/ignore-patterns` | 用 `{"ignorePatterns":["examples/legacy"]}` 整体替换规则。 |
| `GET` | `/api/v1/admin/github-sync/runs?page=0&size=20` | 分页查看平台发现及仓库采集运行与后续包处理计数。 |
| `GET` | `/api/v1/admin/github-sync/runs/{runId}` | 查看一轮平台运行的类型、状态、时间、任务数、路径数、包处理阶段计数、最近任务错误码和下次重试时间。 |
| `GET` | `/api/v1/admin/github-sync/runs/{runId}/tasks?page=0&size=20` | 分页查看该轮发现或仓库任务的仓库、模式、状态和错误码。 |
| `GET` | `/api/v1/admin/github-sync/runs/{runId}/paths?page=0&size=20` | 分页查看该轮实际产生的 Skill 路径结果、采集结果、摄入错误码及关联审核状态。 |

运行详情按 `runId` 与平台 namespace 定位，发现运行可能没有 Skill 路径；预览运行以及已按保留策略清理的运行详情返回 404。路径结果来自这轮任务，不把来源的最新路径状态当成历史运行结果。任务和路径分别分页，`page` 从 0 开始，`size` 为 1–100；凭证、任务快照及原始 GitHub 响应不在这些响应中。

逐路径响应增加 `latestBindingIngestionStatus`、`retryRequested` 与 `retryInProgress`。最新绑定项为 `REJECTED` 时，管理员可请求逐 Skill 重试；当前忽略的路径及暂停来源不可重试。请求会合并或提前安排来源任务，必要时把排队中的仅 HEAD 任务升级为完整扫描。每次请求只自动安排一次完整采集；任务终止且未产生新待审项时，管理员可以再次请求，不会因目录缺失形成无限重扫。只有请求后的新包才可用本次重试意图重新送审；审核历史和原拒绝记录保留，成功建立新待审目标后清除重试标记。高水位准入仍生效，重试不保证立即执行。

来源列表附带最近任务的 `latestTaskErrorCode`、`latestTaskErrorDetail` 和 `latestTaskRetryAt`。诊断详情限于 Worker 或 GitHub HTTP 的已定义代码，不含原始 GitHub 响应、Token 或异常文本。隔离环境已验证有效租约的失败回报会同时持久化诊断码和系统凭证的 SEARCH 额度，含 URL 的详情请求返回 400，旧租约重放返回 409。

调度配置初始为仓库 24 小时、精选用户或组织 24 小时、广泛搜索 72 小时。升级部署若仍设置 `SKILLHUB_GITHUB_SYNC_REPOSITORY_CHECK_INTERVAL`，在管理员首次保存后台周期前，仓库完成后的检查时间继续按原环境值计算；`GET` 的 `repositoryHours` 此时为 `null`，`effectiveRepositoryInterval` 返回实际生效的 ISO 8601 时长。保存后后台配置接管仓库周期，并更新已存在的精选仓库、精选用户或组织、搜索规则的目标周期，包括先前在单条入口上设置的周期；已有更早的到期时间不会被延后，已领取/等待重试任务不会因此跳过额度与积压保护。以后新增入口使用类别默认值，单条搜索规则和精选用户仍可另填周期。

`PUT` 请求体：

```json
{
  "globalHigh": 1000,
  "globalLow": 500,
  "namespaceHigh": 200,
  "namespaceLow": 100,
  "candidatePendingLimit": 1000,
  "candidatePerLeaseLimit": 100
}
```

水位数值仅展示字段格式，不是建议默认值。新部署时两组水位为 `null`，Java 拒绝新的逐包采集准入；管理员应结合试运行的待审数量、包大小、对象存储和审核能力决定实际水位。水位要求 `0 ≤ low < high`；候选预算要求 `1 ≤ candidatePerLeaseLimit ≤ candidatePendingLimit`，并限制为 10000/100000 以内。数据库初始候选预算分别为 100 和 1000，试运行后可调整。`candidatePendingLimit` 限制单 namespace 中排队或正在验证的候选仓库总数，`candidatePerLeaseLimit` 是单次发现租约的新增上限。读取与修改均使用项目统一的 `ApiResponse` 包装；`globalPaused` 是执行状态，不由此接口直接写入。
当前响应还包含 `globalUnresolvedCount`、`platformUnresolvedCount`、`platformPendingCandidateCount` 和 `platformPaused`。前两项沿用新包准入使用的去重口径，按最新未解决 `(namespace, repository ID, Skill 路径)` 计数，并包含仍持有效租约的包准入占位；候选数量只统计 `@github` 下的 `PENDING`、`VALIDATING`，与候选水位口径一致。`platformPaused` 是 `@github` namespace 的准入暂停状态，与 `globalPaused` 分开显示。平台页面每 30 秒重读这些运行值；它们是观察时刻的快照，不保证多个计数在并发写入下完全同时。

## 自动发现配置

以下接口要求平台 `SUPER_ADMIN`，所有写入均记审计；返回体为项目统一 `ApiResponse`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/admin/github-sync/discovery-entries` | 列出平台搜索规则、精选用户或组织、精选仓库入口。 |
| `POST` | `/api/v1/admin/github-sync/discovery-entries/search-rules` | 创建搜索规则，初始停用。 |
| `POST` | `/api/v1/admin/github-sync/discovery-entries/search-rules/preview` | 按同样的结构化规则请求排队预览，返回预览任务 ID；不保存规则或登记来源。 |
| `GET` | `/api/v1/admin/github-sync/discovery-entries/search-rules/previews/{id}` | 轮询状态、各条件的仓库命中估计、最多五个样例及结果不完整信号。 |
| `PUT` | `/api/v1/admin/github-sync/discovery-entries/search-rules/{id}` | 编辑规则并递增版本；进行中的任务继续使用旧快照。 |
| `POST` | `/api/v1/admin/github-sync/discovery-entries/curated-owners` | 登记精选公开用户或组织并启用枚举。 |
| `POST` | `/api/v1/admin/github-sync/discovery-entries/curated-repositories` | 登记精选公开仓库并启用探测；接受 `ownerLogin` 和 `repositoryName`。 |
| `PUT` | `/api/v1/admin/github-sync/discovery-entries/{id}/enabled` | 启用或停用发现入口；重复设置不改变版本。 |

搜索规则请求示例：

```json
{
  "title": "Agent Skill 仓库",
  "targetHours": 72,
  "selectors": [
    {"kind": "KEYWORD", "value": "agent skills", "priority": "CORE"},
    {"kind": "TOPIC", "value": "agent-skills", "priority": "EXTENSION"}
  ]
}
```

精选用户或组织请求体为 `{"ownerLogin":"example","targetHours":24}`；精选仓库请求体为 `{"ownerLogin":"example","repositoryName":"skills"}`；启停请求体为 `{"enabled":true}`。关键词和 topic 只能通过结构化条件提交，不能传入原始 GitHub 查询语句。首次部署预置一条停用规则，不会自行执行广泛搜索；精选用户入口目前会登记仓库线索。精选仓库由 Go 解析公开仓库及稳定 repository ID，Java 首次登记为来源并进入周期采集；重复解析只能指向首次固定的 repository ID。入口固定 ID 后，后续发现任务优先使用同 namespace 来源绑定的当前仓库坐标，避免改名或转移后持续访问最初填写的地址；来源详情中的坐标反映最近一次已核对的上游元数据。达到积压水位时仍可登记来源，但新包采集受准入水位阻止。来源创建可能晚于配置提交，读取入口的 `repositoryId` 可区分待首次解析与已解析。发现任务每分钟检查到期入口，同一入口若已有排队、运行或等待重试的任务，不会重复排队。

发现任务按规则版本和固定搜索窗口执行，核心条件先于扩展条件；同一仓库跨条件命中时按 repository ID 去重。搜索结果超过 1,000 条或 GitHub 返回不完整标志时，Go 拆分原时间窗继续查找，不能将截断页算作完整发现。精选用户和组织的公开仓库先作为带入口出处的候选线索保存，只有后续仓库树与有效 Skill 校验通过才成为正式来源。上述规则、截断处理和两类精选账号的 Java→Go→Java 合同已用受控仓库元数据验证；公开 GitHub 的多仓库规模验收见 6.3。

目前已实现入口配置、发现调度、候选仓库任务排队、有效包校验后的来源绑定、精选仓库直接来源登记、已登记来源的周期任务，以及已校验包的 Skill 版本与同步审核任务创建。高水位或尚未配置水位时，新候选不会进入仓库树检查；已登记来源仍可检查 HEAD，新 commit 在容量恢复后进入完整采集。没有有效 Skill 的候选仓库 7 天后重查；读取失败按 1/3/7 天退避，连续三次标记为不可达并继续低频重试。平台管理页首版提供水位配置、精选仓库与账号登记、搜索规则新建、编辑和入口启停，以及来源、运行、逐路径状态查询和来源忽略路径。系统凭证配置和 TEAM 公开仓库来源页面已有首版。无副作用规则预览已有 Java/Go/Web 首版；审核详情页已接入来源、首次文件树、逐文件变更摘要及受限文本差异预览。公开 API 变更须从实际 Java Springdoc 生成 Web 类型。

预览沿用 Java 任务租约，由 Go 对每个条件只搜索第一页并回写命中估计和公开仓库样例；搜索显式限定 `is:public`，各条件命中可能重叠，不能相加作为去重仓库数。GitHub 元数据搜索可能漏召回。预览不创建候选、来源或待审 Skill，预览运行从正式采集运行列表排除。隔离 PostgreSQL/Redis 环境中，Java→Go→Java 的真实公网双条件预览已完成，候选、来源和发现批次均为 0；受控响应还覆盖核心优先、样例上限、后续条件限流时不提交部分结果和持久重试。原始 GitHub 查询语法被拒绝，预览租约不能写发现批次，完成后的旧租约不能重复回写。精选仓库的跨进程来源登记及周期任务排队由受控 GitHub 元数据验证；可选的公网精选仓库演练遇到限流并进入 `RETRY_WAIT`，待在 6.3 的小范围验收中重试。

来源忽略规则以仓库相对目录前缀表示，最多 64 条，每条最长 1024 字符，不接受 `.`、`..`、空段、反斜杠或控制字符。只有已解析为来源绑定的仓库才能配置；平台默认忽略的 10 类目录始终有效。修改规则会重新安排当前 commit 的完整扫描；旧任务的快照不能把新规则下的来源标记为完整处理。Go 按快照跳过候选，Java 在准入、摄入和发布前按当前规则复核。被忽略的已有 Skill 不会自动删除或撤回。

## 同步审核队列

`GET /api/v1/reviews/sync?status=PENDING` 单独查询 GitHub 同步审核；每项包含 `review`、`ingestionId`、`runId`、`repositoryId`、仓库 owner/name、`skillPath` 和 `commitSha`。可选 `namespaceId`、`page`、`size` 与 `sortDirection`，权限沿用当前审核规则；来源摘要只对当前页通过权限过滤的审核项批量查询。普通 `GET /api/v1/reviews` 和 `/pending` 只查询普通提交。旧审核记录在迁移后标记为 `USER`；同步审核记录使用 `GITHUB_SYNC` 并关联包摄入 ID。后台交接可以创建新同步审核项；同一 Skill 的新内容会把旧项标记为 `SUPERSEDED`，不再允许批准。来源与许可证证据接口、审核详情页只读展示及变更文件的文本差异预览已提供；平台审核区和 TEAM namespace 审核区已增加独立同步队列，并在当前分页内按仓库与运行归组；每个 Skill 从详情页单独批准或拒绝。队列可查看待审、已通过、已拒绝和已被新版本替代的记录。验收应同时覆盖 Java 队列映射、既有审核服务、Web 组件以及含真实同步审核数据的浏览器流程。

同一来源路径的后台发布按数据库事务串行处理，并在事务内重新读取当前绑定。若较新的采集任务已形成待审目标，迟到的旧包会保留为 `SUPERSEDED` 摄入历史，不再创建版本或改写当前绑定。新内容待审期间，上一已发布版本继续可用；旧待审项保留历史但不能再批准。

是否升版依据规范化包文件和许可证证据的内容指纹，不依据 GitHub commit 字符串。不同 commit 的内容相同则记为 `NO_CHANGE`；包内许可证文件变化会产生新指纹并重新进入同步审核。内容指纹只用于同一来源绑定的内容判断，不能证明许可证法律有效性。

每个 Skill 另有持久化的 `syncRevision` 内容修订号。首次同步为 1；与最近同步目标内容不同则加 1；已拒绝内容显式重试时沿用原号；内容回退仍加 1。`GET /api/v1/reviews/sync/{id}/source`、市场技能详情的 `githubSource`、技能版本列表及市场列表的 `publishedVersion` 均返回该字段；市场列表优先显示“第 N 次修订”。原 `version` 字符串继续作为安装与精确定位用的技术版本，不改接口含义。

`GET /api/v1/reviews/sync/{id}/source` 返回单条同步审核的仓库 ID、任务当时的 owner/name、固定 commit、Skill 路径、内容指纹、摄入状态、许可证/NOTICE 证据，以及 `baseVersionId` 和 `fileChanges`。基线为审核提交前最近的已发布版本；首次导入的基线为空，文件列表显示全部新增文件；更新仅列出新增、修改、删除的路径及旧/新 SHA-256、大小，不传文件内容。只有按现有审核规则可查看该审核项的人员能读取；普通审核 ID 返回未找到。证据状态为 `MISSING`、`PRESENT_UNCLASSIFIED` 或 `PRESENT_UNPREVIEWABLE`：`MISSING` 指没有找到许可证类文件，即使存在 NOTICE 也仍为缺失；其余状态只表示取得证据的可预览程度，不推断法律有效性。单文件证据预览受 64 KiB 限制；上游仓库根文件的 Git blob 摘要与 Skill 包文件的 SHA-256 分开标注。同步审核详情页以只读方式展示来源、许可证证据及文件变化；当前版本文件仍使用已有审核预览接口；基线文件由 `GET /api/v1/reviews/sync/{id}/base-file?path=...` 读取。该接口沿用审核查看权限，只允许读取该审核差异中的旧版文件，且单文件不得超过 1 MiB。前端仅对已有文本预览支持的文件类型开放差异按钮。

审核详情首屏使用 `GET /api/v1/reviews/{id}/skill-detail?includeDocumentation=false` 读取技能、版本与文件元数据，进入“技能内容”页签后再读取说明正文。省略参数时默认包含正文，保持已有调用行为。逐文件在线预览调用 `GET /api/v1/reviews/{id}/file?path=...&preview=true` 和 `GET /api/v1/reviews/sync/{id}/base-file?path=...&preview=true`；预览最多允许 256 KiB，服务端同时检查记录大小与实际读取字节数，超限返回错误而非截断正文。完整文件下载不带 `preview` 参数，仍沿用原权限与大小规则。首次导入没有旧文件，不请求 `base-file`。历史记录只在打开“审核记录”页签时加载；来源响应不嵌入差异正文。

`@github` 目录的许可证目前没有自动识别结果；审核者批准这类同步项时，必须在审核意见中写明处理依据。缺少意见的批准请求返回业务错误，不能因为具有超级管理员权限而跳过。TEAM 来源仍展示同样的证据和警告，并沿用人工审核权限。

同步审核来源详情也返回采集 `runId`，详情页显示该 ID，以便从单条审核追溯到采集运行；审核记录自身展示提交者和审核者。首次导入的实际文件树及预览位于详情侧栏，来源卡片的文件列表列出新增路径。安全扫描已有结果时展示扫描卡片；尚在扫描、暂无结果或结果读取失败时，详情页分别显示对应状态。

## TEAM 公开仓库来源

TEAM namespace 的 OWNER、ADMIN 可使用以下接口；成员和其他 namespace 的管理员不能跨范围读取或修改。冻结或归档的 namespace 可读取配置，但不能新增或修改。请求沿用会话及 CSRF 保护，写入留审计。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/namespaces/{namespaceSlug}/github-sources` | 列出本团队已配置的公开仓库入口。 |
| `POST` | `/api/v1/namespaces/{namespaceSlug}/github-sources` | 登记 `ownerLogin`、`repositoryName` 与可选 `visibility`；默认 `NAMESPACE_ONLY`。 |
| `PUT` | `/api/v1/namespaces/{namespaceSlug}/github-sources/{entryId}/enabled` | 以 `{"enabled":false}` 暂停、以 `true` 恢复入口和已解析来源。 |
| `PUT` | `/api/v1/namespaces/{namespaceSlug}/github-sources/{entryId}/visibility` | 以 `{"visibility":"PUBLIC"}` 或 `NAMESPACE_ONLY` 修改后续导入的可见范围。 |
| `GET` | `/api/v1/namespaces/{namespaceSlug}/github-sources/resolved?page=0&size=20` | 分页查看本团队已解析来源、最近任务及逐包摄入状态计数，包含 `failedPackages`。 |
| `GET` | `/api/v1/namespaces/{namespaceSlug}/github-sources/resolved/{sourceId}/skills?page=0&size=20` | 查看本团队来源的逐 Skill 路径状态。 |
| `POST` | `/api/v1/namespaces/{namespaceSlug}/github-sources/resolved/{sourceId}/rescan` | TEAM 管理员将已解析来源提前纳入正常调度；已有开放任务时合并请求。 |
| `POST` | `/api/v1/namespaces/{namespaceSlug}/github-sources/resolved/{sourceId}/retry-rejected-skill` | TEAM OWNER/ADMIN 对本团队已拒绝的 Skill 路径请求重新采集并送审；请求体同平台接口。 |
| `POST` | `/api/v1/namespaces/{namespaceSlug}/github-sources/conflicts/{ingestionId}/bind-skill` | TEAM 管理员确认同名冲突或路径变化，并绑定到本 namespace 的已有 Skill ID。 |
| `GET` | `/api/v1/namespaces/{namespaceSlug}/github-sources/resolved/{sourceId}/ignore-patterns` | 查看本团队已解析来源的追加忽略规则。 |
| `PUT` | `/api/v1/namespaces/{namespaceSlug}/github-sources/resolved/{sourceId}/ignore-patterns` | 在本团队来源上整体替换追加忽略规则。 |
| `GET` | `/api/v1/namespaces/{namespaceSlug}/github-sources/runs?page=0&size=20` | 分页查看本团队的发现与仓库采集运行。 |

重扫接口不接收包或 commit，也不直接创建待审 Skill。返回 `sourceId`、`status`（`SCHEDULED` 或 `MERGED`）、可选 `taskId`、`taskStatus` 与 `nextCheckAt`。已有 `QUEUED` 任务会提前排序；`LEASED` 任务照常运行；`RETRY_WAIT` 保留原重试时间。没有开放任务时，仅把来源的下次检查时间提前到当前，最多等待正常调度周期。`PAUSED` 来源返回业务错误。正常任务仍按积压准入、GitHub 额度和 HEAD/FULL 判定执行；相同 commit 的完整内容不重新导入。

Go 只解析公开 GitHub 仓库；Java 将首次解析的 repository ID 固定到该 TEAM 入口，在 TEAM namespace 中建立独立来源绑定。页面可用返回体的 `repositoryId` 区分待首次解析与已解析。暂停不撤回既有 Skill；已批准入的在途包可结算，之后不再发放新包准入。可见范围变更不追改已发布 Skill。TEAM 管理页提供公开仓库登记、启停、可见范围、逐 Skill 状态及本 namespace 的运行记录。

来源、运行和逐 Skill 路径列表的 `page` 从 0 开始，`size` 为 1–100，缺省 20。来源返回最近仓库任务的 `latestTaskStatus`、`latestTaskMode`、错误码与 `latestTaskRetryAt`，另按每个 Skill 路径的最新摄入记录状态分别计数 `receivedPackages`、`processingPackages`、`pendingReviewPackages`、`blockedPackages`、`failedPackages`；旧失败在同一路径重新摄入后不再计入来源当前状态。运行列表的阶段计数仍按该次运行的摄入记录统计，因此保留历史运行结果。`trackedSkills` 是已绑定 Skill 数，包含待审核 Skill，**不是已发布数**。运行的 `ACQUISITION_COMPLETE` 只表示采集结束；`receivedPackages` 等字段用于识别 Java 摄入和审核仍在进行。逐路径接口按 Skill 路径返回最近一次采集 outcome、受控原因码、摄入状态和审核状态；来源 ID 必须属于当前平台或 TEAM namespace，否则返回未找到。本期列表不返回包正文或凭证。

平台和 TEAM 管理页面在前台查看时每 30 秒重取来源、运行与已展开的逐 Skill 列表；平台凭证页的已观测额度也同频刷新。手动重扫成功后会立即刷新相关查询；离开页面后不持续轮询。

运行列表的 `nextRetryAt` 是该运行中所有 `RETRY_WAIT` 任务最早的重试时间；没有等待重试的任务时为空。它表示采集任务的下一次尝试，不表示 Java 摄入或审核会在此时间完成。

运行列表的 `lastErrorCode` 是当前处于等待重试、部分完成或失败的采集任务，以及等待重试、阻塞或失败的包处理中，最近更新的一条错误码；没有这类错误时为空。发现运行没有仓库来源详情，仍可通过此字段查看失败原因。错误码只用于定位当前异常，不包含仓库文件内容或凭证。

Go 服务独立于工作池，每 30 秒向内部 `/heartbeat` 发送一次受机器身份保护的心跳。Java 只保存全平台最近一次成功心跳，不保存 Go 主机名或凭证；最近 90 秒内有心跳时 `online=true`。后台每 30 秒刷新，停止或失联后约 90–120 秒显示离线。该状态与高水位造成的“新包准入暂停”是两件事，后台分别显示；`online` 只表示 Go 与 Java 最近可通信，不保证 GitHub 出站访问成功。

## 同名冲突与路径变化人工确认

发布时若来源 Skill 的规范化 slug 已被同 namespace 的 Skill 占用，该摄入项进入 `BLOCKED`，`ingestionErrorCode` 为 `SLUG_CONFLICT`，逐路径接口同时返回 `targetSkillSlug` 和 `ingestionId`。平台 `SUPER_ADMIN` 可处理 `@github` 中的冲突；TEAM 的 OWNER/ADMIN 只能处理本团队 namespace 的冲突。其他状态不能调用绑定接口，跨 namespace 的摄入 ID 返回未找到。

两个绑定接口使用相同请求体：

```json
{"skillId":123,"reason":"已核对为同一技能，保留现有历史版本"}
```

`skillId` 必须是本 namespace 中 slug 相同、未归档且没有普通用户待审版本的 Skill；已经由其他来源管理的 Skill 或已经占用的 GitHub 路径不能再次绑定。`reason` 去除首尾空白后至少 10 字符，最多 1000 字符。成功后返回 `QUEUED_FOR_RETRY`，记录操作者、仓库 ID、来源 ID、路径和处理依据；后台任务会对该固定 commit 的包重新执行发布与审核流程。预留绑定不把当前包指纹当作已发布指纹，因而不会错误地判为无变化。创建同步待审版本时，Skill 的技术所有者转为保留的系统 actor，由 namespace 管理；若创建失败，原所有权保持不变。此操作不会直接批准或公开新内容。

完整仓库扫描结束后，若旧路径消失，且新路径的完整包内容指纹只匹配本仓库一个已绑定 Skill，Java 自动更新路径绑定、沿用 Skill ID，并记录系统审计；相同内容不创建新版本。旧路径仍出现在本次扫描结果时不会被认定为移动。多个候选或内容变化导致无法唯一匹配时，摄入项标记 `PATH_MOVE_AMBIGUOUS`，不得自动新建 Skill；进行中的扫描等待完成；终止的部分扫描不据此推断目录消失或自动移动。若部分状态仅由其他包的确定性失败造成，新路径已接收且所有既有绑定路径都在本次结果中，可作为独立 Skill 进入同步审核；旧路径未得到确认时仍标记 `PATH_MOVE_SCAN_INCOMPLETE`。

平台 `SUPER_ADMIN` 或 TEAM OWNER/ADMIN 可用上述绑定接口处理 `PATH_MOVE_AMBIGUOUS`。逐路径列表显示已绑定的 Skill ID；管理员应核对旧路径及目标 Skill 后提交其 ID 和处理依据。服务端再次验证本次任务为完整扫描、新路径已采集、旧路径在该快照中确实不存在、目标 Skill 未归档且已由同 namespace、同仓库来源管理。成功后保留 Skill ID 与历史版本，记录操作者及旧新路径，并重新处理固定 commit 的包；内容变化才创建待审版本，相同内容只更新来源路径。接口本身不批准内容。`PATH_MOVE_SCAN_INCOMPLETE` 不能人工确认为移动，须等后续完整重扫。被当前忽略规则覆盖的旧路径或新路径也不能通过人工确认。

来源绑定记录上游 `UNKNOWN/PRESENT/MISSING`、最近核对 commit 和首次缺失时间，并通过逐路径响应的 `upstreamPresence`、`presenceCommitSha`、`missingSince` 展示。`UNKNOWN` 表示尚无有效完整核对；`PRESENT` 和 `MISSING` 都只说明最近一次有效完整扫描的结果，不是实时 GitHub 状态。仅完整扫描且忽略规则未变化时对账；部分扫描、暂停来源和当前忽略的路径不触发缺失推断，后续完整重扫看到旧路径会恢复 `PRESENT`。不可达来源成功完成扫描后恢复为 `ACTIVE`，旧快照不能覆盖较新的状态；过期扫描或规则变化的路径移动摄入项标记 `PATH_MOVE_STALE_SNAPSHOT`。目录消失本身不会删除、撤回或隐藏已发布 Skill。


## 统一技能同步管理

产品方案见[统一管理入口](../../openspec/changes/add-github-skill-sync/unified-admin.md)，状态与一致性约束见[实施合同](../../openspec/changes/add-github-skill-sync/unified-admin-contract.md)。下列接口固定管理平台 `@github`，需要 `SUPER_ADMIN` 会话；写请求沿用 CSRF，返回既有 `ApiResponse` 包装并设置 `Cache-Control: no-store`。TEAM 继续使用 namespace 来源接口。

### 仓库列表与详情

根路径为 `/api/v1/admin/github-sync`：

| 方法与路径 | 查询参数与行为 |
| --- | --- |
| `GET /repositories` | `page=0`、`size=20`（1..100）、可选 `asOf`、`q`（最长200字符）、`originType`、`state`、`sourceStatus`。按 namespace 与 GitHub 稳定仓库 ID 合并候选和来源，筛选后分页。 |
| `GET /repositories/{repositoryId}` | 返回仓库摘要及最近检查/运行；仅其他 namespace 存在的仓库同样返回404。 |
| `GET /repositories/{repositoryId}/origins` | `page`、`size`、`asOf`；分页展示各发现方式的持久出处，按类型与来源键去重。 |
| `GET /repositories/{repositoryId}/paths` | `page`、`size`、`asOf`；复用逐 Skill 路径及审核安全投影，按需读取。 |

分页返回 `items / total / page / size / asOf`。非法枚举或分页参数返回400。`asOf` 是新增记录的时间截止点，防止新发现不断挤动当前分页，不代表跨请求冻结全部状态；同次列表请求的总数与列表使用一致事务快照。

仓库摘要字段：`repositoryId`、可空 `sourceId / candidateId`、`owner / name`、`firstSeenAt`、`state`、可空 `sourceStatus / candidateStatus`、`originTypes`。`state` 取 `REGISTERED / PENDING / VALIDATING / NO_VALID_SKILL / VALIDATION_FAILED / UNREACHABLE / BOUND_WITHOUT_SOURCE`，只表达登记阶段，不表示审核通过或市场可见。已登记来源状态另用 `ACTIVE / PAUSED / UNREACHABLE`。出处类型为 `CURATED_REPOSITORY / CURATED_OWNER / SEARCH_RULE / OFFICIAL_DISCOVERY / TEAM_CONFIG`，不会因此扩大到 TEAM 数据。

详情返回 `repository` 及可空 `latestRunId / lastCheckedAt / nextCheckAt / lastErrorCode`。出处返回 `originType / originKey / firstSeenAt / lastSeenAt / entryId / channel / queryId / runId / seedRepositoryId`；没有实际观测的字段为空，已清理详情的运行不提供失效链接。旧 `/sources` 和官方探索专用接口保留兼容。

### 指定仓库与运行

指定仓库重复添加返回同一 namespace、同类型、同目标的已有入口；不恢复其停用状态，不修改既有到期时间或重复排任务。尚未解析稳定仓库 ID 的入口在采集配置中展示，不计入统一仓库数量。

入口响应增加可空 `latestExecution`：`taskId / runId / status / errorCode / retryAt`，按持久任务的规则 ID 关联，不凭名称猜测。未知字段为空，不返回原始任务快照或外部异常正文。

既有 `GET /runs` 增加可选 `kind / status`，保留原分页调用。`kind` 为 `DISCOVERY / REPOSITORY_SYNC`，状态为 `QUEUED / LEASED / RETRY_WAIT / PARTIAL / ACQUISITION_COMPLETE / FAILED`。列表排除预览和已清理详情；`createdAt` 是创建时间，不是实际开始时间。

每项增加 `targetCount` 及可空 `target: {type, value}`：单目标显示摘要，多目标显示数量；同仓库优先按稳定 ID 去重，缺失时才按规范化坐标区分。HEAD_ONLY/FULL 仍在仓库任务详情中区分，不拆成虚构运行类型。

### 平台暂停与恢复

`GET /platform-control` 返回：

| 字段 | 说明 |
| --- | --- |
| `namespaceId`、`version` | 平台 namespace 与单调控制版本。 |
| `desiredPaused` | 管理员保存的暂停意图。 |
| `state` | `RUNNING / PAUSING / PAUSED`；RUNNING只代表没有暂停，不证明Go在线。 |
| `pendingStopCount` | 当前暂停版本仍未确认停止的在途任务数。 |
| `expiredUnconfirmedCount` | 上述任务中租约已过期但仍无停止确认的数量。 |
| `updatedAt` | 持久状态更新时间。 |

`PUT /platform-control` 请求示例：

```json
{"expectedVersion": 3, "paused": true}
```

请求使用最近读取的版本，响应结构同 GET。并发版本冲突返回409，重新读取后再决定操作；相同版本、相同意图不反复推进版本。变更记录管理员审计。

暂停独立于自动探索和容量策略，阻止平台的新发现、检查和下载，TEAM不受影响。Go执行线程到安全点后确认；过期或失联不能被当作已停止。Java已经接收的包及人工审核继续，恢复不覆盖各来源和配置独立启停。部署须配套支持 `platform-pause/v1` 的Go版本，详细边界见[内部协议](github-sync-internal.md#9-平台github采集安全暂停)。
