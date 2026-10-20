# 统一技能同步管理：实施合同

状态：2026-10-13，产品已确认；本页将研究结果冻结为实施合同，代码与生成类型须按此对齐。平台暂停协议及统一查询合同已冻结，实施验收结果另行记录。

## 统一仓库读接口

根路径 `/api/v1/admin/github-sync`，SUPER_ADMIN会话+既有CSRF规则，namespace由服务器解析为@github，不接受客户端扩大到TEAM。GET响应no-store，外层复用ApiResponse。

- GET `/repositories`：page>=0，size=1..100（默认20），q使用现有长度限制/LIKE转义；可选originType、state、sourceStatus和asOf。非法枚举400。
- GET `/repositories/{repositoryId}`：不存在或不在平台范围404。
- GET `/repositories/{repositoryId}/origins`：有界分页，合并并去重source/candidate的出处。
- GET `/repositories/{repositoryId}/paths`：复用逐路径安全投影，资格改为统一仓库存在性；保持NO_CHANGE审核关联约束。

Page为items/total/page/size/asOf。Summary为repositoryId、可空sourceId/candidateId、owner、name、firstSeenAt、state、可空sourceStatus/candidateStatus、去重有界originTypes。Detail包含repository以及可空latestRunId、lastCheckedAt、nextCheckAt、lastErrorCode。Origin包含originType/originKey/firstSeenAt/可空lastSeenAt、entryId/channel/queryId/runId/seedRepositoryId。不存在的观测不伪填。

state用于登记阶段：有来源REGISTERED；无来源为PENDING/VALIDATING/NO_VALID_SKILL/VALIDATION_FAILED/UNREACHABLE；异常BOUND但无来源使用BOUND_WITHOUT_SOURCE，不虚称有效。sourceStatus独立表达已登记来源的ACTIVE/PAUSED/UNREACHABLE。等待/审核不是同一枚举，不混合成一个误导性成功状态。

基集source LEFT JOIN candidate，以namespace+repository匹配，再UNION ALL无source的candidate。筛选出处使用EXISTS，过滤后分页，排序firstSeenAt DESC、repositoryId DESC；只关联本页详情，不把历史路径联入COUNT。firstSeenAt取首次发现/创建最早值，名称以source当前值优先。asOf只抑制后续新增导致的页漂移，不是跨请求历史快照；单请求total/items使用一致事务快照。

完整出处按type/key去重，首次取min，lastSeen仅真实观测；删除规则后仍保留出处。被清理run不返回失效链接。仅按查询计划证据增加必要索引，不增加无依据汇总表。

## 指定仓库添加

重复的同namespace/类型/目标请求幂等返回既有入口，不恢复已停用状态，不反复生成新任务；同稳定仓库ID归并依旧在来源解析阶段完成。未解析ID的配置不混入统一仓库total，按entry UUID在配置中展示真实解析任务状态，提供最近run/errorCode/retryAt（未知为空）。关联键使用任务snapshot真实ruleId，不按名字匹配；禁止返回原始snapshot或外部异常正文。

## 运行列表增量

扩展既有GET `/runs`的可选kind/status筛选，保留page/size及原调用兼容。kind维持真实DISCOVERY/REPOSITORY_SYNC两类主运行；后者页面标「仓库检查与采集」，HEAD_ONLY/FULL具体阶段在任务详情展示，不强行拆成三个虚构运行类型。status沿原枚举。所有筛选在分页前，COUNT与items口径一致，继续排除搜索预览及已清理详情。

增安全target摘要及targetCount：单目标显示明确仓库/账号/查询标识，多目标显示数量并引导分页任务；没有目标保持空，不把第一个任务冒充全部。时间沿用createdAt并准确标「创建时间」，不伪装尚未采集的实际开始时间。

## 现有类型与权限兼容

保留旧sources、deep-discovery及run详情API；新接口通过Springdoc生成前端schema，不手工编辑生成文件。准备清单复用已有真实配置/状态API，不额外造汇总DTO；各请求失败/未知分别体现，不能默认为就绪。仓库ID安全整数范围沿既有策略。

## 平台采集控制合同（冻结）

### 管理接口与持久状态

新增GET/PUT `/api/v1/admin/github-sync/platform-control`，SUPER_ADMIN、CSRF、审计及no-store沿已有方式。PUT为 `{expectedVersion, paused}`，乐观冲突返回409；同一版本重复相同意图允许幂等返回，不能多次翻转版本。响应为namespaceId、version、desiredPaused、state、pendingStopCount、expiredUnconfirmedCount、updatedAt（无数据时不得假造）。state为RUNNING/PAUSING/PAUSED；RUNNING仅表示控制未暂停，不代表Go在线，UI仍独立显示worker状态。

V119新增固定引用真实平台namespace的控制行：namespace_id、paused=false、单调version、updated_at/updated_by。任务增加platform_pause_version/generation/token_hash/stop_ack_at，捕获当前暂停epoch的在途租约，摘要不输出管理端。暂停锁控制行与捕获task；claim及admission在同事务先持平台控制共享锁，之后沿既有官方配置/预算/准入/task锁顺序，不反向死锁。暂停生效后不能越过门创建新平台租约或准入；TEAM仍按原条件领取。scheduler可保留有去重约束的已排任务，必须保证不因暂停重复排队，领取门是最终保证。

当前版本未确认任务存在时为PAUSING，包括过期/离线而未确认的任务。不能将租约超时或Go心跳丢失当停止证明；响应分开给出pending和expired数量。无在途或全部明确停止才PAUSED。恢复不依赖ack，切换paused=false并递增version，原进度及独立启停状态保留。保留策略不能清理当前暂停尚未确认记录而伪造PAUSED。

### Worker协议

能力 `platform-pause/v1` 仅作为平台任务领取要求，TEAM不强制新增能力；升级期间旧worker不能取得新的平台工作。旧在途任务不能假称可安全停止，显示待确认。claim附可空platformControlVersion识别受控任务。

新增POST `/internal/github-sync/v1/tasks/{taskId}/platform-control` 与 `/platform-pause`。control提交既有lease proof，返回action CONTINUE/PAUSE及platformVersion。pause提交observedVersion和相应kind的已确认discoveryCheckpoint/RepositoryProgress，所有进度校验复用现有安全规则。

仅执行线程在停止外部工作、完成必要结算及本地清理后发送停止确认。Java验证当前paused/version、捕获generation/token，原子保存进度/ack并将任务恢复为QUEUED但受平台门阻止领取，不记FAILED。旧epoch返回CONTROL_CHANGED，worker重新读取控制；旧generation不得改新租约。重复相同确认幂等。过期worker凭本epoch捕获proof只可确认停止，不能提交未持久进度或新包，不能影响新generation。

正常完成/失败回报只有协议明确保证外部执行结束且匹配捕获proof时才能确认停止；未知错误、过期回报不自动充当ack。控制网络故障时如实PAUSING，不能承诺即时停止。

### 安全点与已接收边界

受控平台任务最多每5秒目标频率观察控制，实际停止时间受请求超时和网络条件约束；TEAM不增加该轮询。观察协程仅传递停止意图，不带旧checkpoint旁路ack。执行线程在发现每请求/已接收批次、metadata/HEAD、Tree每请求、Git每网络步骤、准入前及上传接收后检查；与既有配额钩子组合，不覆盖。正在执行的外部获取有界结束或取消，不能等完整大仓库结束。

Java `acceptReport` 提交摄入、准入及upload ACCEPTED事务是已接收边界，单纯PUT对象不是。暂停前已准入且已完整打包的成果可按原租约完成上传/接收后ack；不完整本地包清理并释放准入，只保留已确认进度。发现已获取页可经现有幂等batch提交再ack；拒绝时停在最后确认checkpoint。已接收摄入的后续校验/扫描/待审不加控制门。

### 验证与部署兼容

真实PG竞态证明暂停/claim/admission线性化；覆盖legacy/official/preview发现、HEAD/FULL、TEAM、过期及失联、快速暂停恢复旧ack、确认响应丢失、重启与独立disabled。API新字段在Springdoc生成类型同步。V119追加迁移，不回写既有迁移；Java/Go部署要配套，新Java已启用能力门时旧Go平台采集会等待升级，TEAM规则不变。回退不能简单只换旧Go，需考虑能力和进行中暂停；本地发布前在隔离环境验证，不让预览进入自动采集。
