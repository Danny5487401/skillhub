# 官方深度探索持久化增量

状态：当前迁移覆盖V108–V118；真实迁移、升级、并发及限定规模验证结果统一见[验收追踪](verification.md)。物理结构以SQL迁移为准，以下描述当前持久化边界。

## 复用与最小增量

| 结构（实际迁移命名） | 用途与边界 |
| --- | --- |
| 复用 github_sync_task | 继续使用 DISCOVERY/REPOSITORY_SYNC、租约代际、去重键、固定快照及检查点；新增 required_capabilities（有界能力集合）与 last_progress_at。旧任务能力集合为空，业务进度不能使用续租更新的 updated_at 代替。 |
| 新增 github_deep_discovery_config | 平台单例配置：namespace_id、enabled 默认 false、budget_profile 默认均衡、config_version、数量受限的 exclusions_json、updated_at。策略版本与管理员配置版本分开。 |
| 新增 github_deep_discovery_query | 长期查询/作者进度：query_key、query_version、strategy_version、definition_json（含channel与查询定义）、covered_until、checkpoint、active_task_id、next_due_at、last_progress_at、wait_reason、retired_at。唯一namespace_id/query_key/query_version；V113补query_source、github_owner_id、owner_login、seed_repository_id，作者只扩一层；V117补last_successful_claim_at。分片账本独立保存，不把无限分片栈放入4096字符checkpoint。 |
| 新增 github_official_discovery_shard | V112保存根/父节点、固定范围与快照、PENDING/ACTIVE/SPLIT/COMPLETE/GAP、可清理的task引用、缺口检查点/重试时间/租约代际。每查询一个未结束根；最多127节点，顺序执行。全部叶子完整才更新长期覆盖；已完成根在下次新根或保留期清理时删除，不删除出处和覆盖。 |
| 扩展 candidate/source origin | 在既有 origin_type CHECK 增加 OFFICIAL_DISCOVERY，origin_key 指向长期 query UUID 而非临时 task；候选出处按candidate/queryId合并，recent_shard_id/recent_run_id/recent_seed_repository_id/latest_strategy_version仅存在于github_discovery_candidate_origin，可随历史清理置空；github_source_origin不新增这些诊断列。两者保留既有SEARCH_RULE/CURATED_OWNER。同一候选仍以 namespace/repository_id 唯一。 |
| 首次有效事件（V115） | 使用github_discovery_outcome_event的FIRST_VALID、occurred_at、official_at_event和first_occurrence_known记录首次有效事实，不新增候选first_validated_at列。事件发生时是否已有官方出处决定归因；无法证明的旧历史由空时间或legacy_unknown_validation标记保留，不能从当前BOUND状态倒推首次有效。 |
| 新增轻量成果事件 | 记录真正新建 Skill、新建内容版本的事件时间、对象ID与当时归因，按事件类型/对象去重；不得把 NO_CHANGE 或关联已有 Skill 当新技能。与运行详情清理独立。 |
| 新增 github_discovery_outcome_event / github_discovery_metrics_epoch / github_discovery_legacy_unknown_validation | V115持久成果去重、可验证历史起点与无法证明首次有效的旧身份；与任务历史清理独立。 |
| 新增 github_official_quota_observation / github_official_resource_cooldown / github_official_secondary_cooldown | V116按资源观察与持久冷却；未知主体不累加余额，不维护GitHub配额预约账本。 |
| 新增 github_official_discovery_budget / github_official_discovery_reservation | V117保存单例平台最早领取时间与task/generation预约明细；这是本系统任务预算，不是GitHub配额余额。任务清理可删除明细，但不重置平台最早领取时间。 |
| 更新 github_candidate_discovery_eligibility | V118复用候选owner/name消除重复查询，保持最终11列和来源／排除／暂停规则；不增加缓存状态。 |
| 新增 github_sync_worker_instance | V114保存实例、协议主版本、能力集合与服务端最近联系时间；旧全局 presence保留，但不能证明支持官方探索。按完整官方能力集合及90秒窗口统计兼容实例；过期实例按既有保留期分批清理，不删除业务记录。 |

## 额度持久化范围

保留现有凭证资源额度观测，不累加不同 token。主限流按服务端租约绑定的凭证及资源保存，匿名单独记录；次级限流采用共享冷却。未知限额主体不被强行识别或合并。未知主体不声称额度独立，不自动展示精确总额。调度仅做保守限速，不建设精确实时余额系统。

V117的平台任务预算预约已经实现，规则见设计第3节：成功领取按快照maxRequests预约，续租不重复预约，过期接管重新预约；未使用份额不退款，没有闲置累积。它限制本系统领取节奏，不提供逐HTTP请求许可，不锁定GitHub侧配额；API仍不承诺精确可用请求数。

## 查询与统计

候选列表按 first_seen_at DESC,id DESC 稳定排序，文案为首次发现；官方后续命中已有候选不重写全局首次时间。当前列表通过持久官方出处过滤。近期“新增候选”只统计事件发生时已有官方归因的对象，不将晚到归因回填成官方新发现。

候选索引按 namespace、first_seen_at、id 和必要状态组合；出处反查 origin_type/origin_key/candidate_id；长期查询索引 next_due_at/id（未退休）；成果事件按 namespace/type/occurred_at/subject。每项索引须经实际 SQL/EXPLAIN 选取，避免重复索引。子串搜索不能假定普通 B-tree 可加速。

默认20、最大页大小由 API 合同限制。沿用现有 page/size 时明确深页成本；摘要独立缓存/查询并提供生成时间。无法恢复的历史成果从统计可靠起点开始展示，不填伪造时间，不把未知显示为零。

## 暂停与竞态

候选 REPOSITORY_SYNC 的 source_id 为 NULL，因此在候选入队、领取和关键接收三个位置核对最新配置及有效出处，不能只依赖 DISCOVERY ruleId 门控。存在其他独立启用入口仍需要时允许继续；已登记来源按自身状态管理。已有持久接收处理可结算，未开始的新包停止准入，保留进度。

官方发现接收按最新配置过滤排除项：保留完整原请求摘要及原始仓库 ID 的代际去重预算，过滤后仍确认本页检查点；被排除的新仓库不创建官方候选或出处。已有候选保留并刷新坐标，采集资格动态判断；取消排除后已有候选可续排，从未入库的仓库由后续历史补搜重新发现。

配置修改与结果接收必须有一致事务顺序/版本复核，避免关闭提交后仍无条件推进新工作。所有写入保留 task ID/generation/token hash/有效期检查。暂停不是删除，也不通过无穷远 due_at 隐藏任务。

## 历史保留、升级与回退

现有终态发现任务会被清理，task_hit/batch 会级联删除；长期 query、origin 和成果事件不得依赖这些记录保持存在。完整分片结算时原子推进长期覆盖边界；运行详情可过期，但已确认覆盖、出处与统计不能随之消失。退休查询保留，不删除被出处或在途任务引用的快照。

坏检查点应报告明确不可续跑原因；Java 从可证明安全的边界建立重放任务并去重，不盲目反复执行同一个坏游标。升级先兼容 schema 与 Java 能力过滤，再 Go，再页面启用；回退必须先关闭并排空/隔离官方任务，旧 Java 无能力过滤，不能直接带着官方任务降级。

当前 main 整合迁移范围为 V76–V119；官方探索从 V108 起，平台暂停为 V119。所有已发行 main SQL 保持原 checksum，升级验收见 [验证说明](verification.md)。


## V117：官方探索预约节奏

`github_official_discovery_budget` 为部署级单行状态，保存 `next_lease_eligible_at`，跨 Java/Go 实例、进程重启、配置启停与档位切换共享。它不是 GitHub 余额或按 Token 汇总额度。

`github_official_discovery_reservation` 以 `(task_id, lease_generation)` 去重，保存实际领取时的档位、预约逻辑请求数、授予时间和下次最早领取时间；随任务保留期级联清理。清理预约明细不删除平台预算行，也不提前最早领取时间。查询新增 `last_successful_claim_at`，用于官方查询间的持久公平排序，续租不更新它。

锁顺序、三档初值、预约和失败回滚语义以 [设计第3节](deep-discovery-design.md#3-预算凭证与暂停) 为准。对真实 PostgreSQL 的迁移、并发预约、回滚及清理行为必须由集成测试验证。
