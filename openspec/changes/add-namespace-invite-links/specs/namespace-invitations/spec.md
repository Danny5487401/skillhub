## Purpose

让 Namespace 管理员使用可控的短期单次链接邀请新成员。

## ADDED Requirements

### Requirement: REQ-INV-01 管理员 SHALL 创建、替换及作废邀请

有权管理员 SHALL 可创建、替换及作废邀请。

只有 ACTIVE TEAM 的 OWNER/ADMIN 可以创建邀请；平台角色不能绕过成员角色限制。每个 Namespace 仅有一条当前邀请，24 小时内仅一名非成员可兑换一次。替换仍可用的邀请前须确认；管理员可以单独作废当前邀请，且已加入成员不受影响。

#### Scenario: 替换未使用链接
- **WHEN** 管理员再次点击邀请且当前链接仍可兑换
- **THEN** 页面先说明旧链接将失效并要求确认
- **AND** 仅确认后生成新链接，旧链接立即失效

#### Scenario: 单独作废
- **WHEN** 管理员点击作废并确认
- **THEN** 当前链接立即失效，不会创建新链接或移除成员

### Requirement: REQ-INV-02 邀请 SHALL 服从现有账号准入

邀请 SHALL 服从现有账号准入规则。

邀请只授予目标 Namespace 的 MEMBER 权限，不使用户绕过现有注册、登录、外部身份准入和账号审批。接受者必须先拥有可用登录会话，看到目标 Namespace、当前账号和 MEMBER 可访问及发布资源的说明，再明确确认加入。页面加载不得自动兑换。

#### Scenario: 新用户未通过准入
- **WHEN** 持链接者无法按既有策略获得可用账号
- **THEN** 邀请不能使其进入目标 Namespace

#### Scenario: 已登录非成员
- **WHEN** 非成员打开有效链接
- **THEN** 页面展示 Namespace、账号和权限，只有点击确认才新增 MEMBER

### Requirement: REQ-INV-03 Token SHALL 避免意外泄露

Token SHALL 避免出现在日志、持久化明文和登录 URL 中。

Token 使用安全随机数，数据库仅保存哈希；原文只在生成响应中出现一次。分享 URL 使用 `/join#token`，页面立即移入当前标签页 sessionStorage 并清除 fragment；登录回跳只包含应用内 `/join`，不带 Token。创建、预览、兑换、作废响应禁止缓存；日志和审计不得包含原文或哈希。四类操作仅允许浏览器 Session，变更请求遵守 CSRF。
Token 原始随机字节不得少于 32 字节，并采用 Base64URL 编码；数据库仅保存 SHA-256 哈希。

#### Scenario: 经过登录回跳
- **WHEN** 未登录者打开邀请后登录
- **THEN** Token 不出现在登录回跳 URL，回到原标签页后才从 sessionStorage 恢复邀请

#### Scenario: 子路径部署
- **WHEN** 应用部署在 `/skillhub/`
- **THEN** 分享链接指向公开基地址下 `/skillhub/join#token`，应用内 returnTo 仍为 `/join`

### Requirement: REQ-INV-04 服务端 SHALL 在接受时重新判断状态

服务端 SHALL 在接受邀请时重新判断全部状态。

预览不消费邀请。接受时重新检查 Token 是否仍为当前、用户是否已有成员关系、Namespace 是否 ACTIVE TEAM、邀请是否未消费且未过期。Token 匹配当前邀请且用户已是成员时返回 ALREADY_MEMBER，不再检查过期、消费或 Namespace 冻结归档状态，也不消费。未知、过期、替换、作废或已用 Token 对非成员返回统一失效响应。冻结或归档时非成员不能加入也不消费，恢复后仅在原有效期内可兑换。
统一失效响应使用 HTTP 404、envelope `code=404` 与 `data=null`；前端只按状态处理，不解析本地化文案。

#### Scenario: 预览后链接被替换
- **WHEN** 用户看到预览后管理员替换或作废了链接
- **THEN** 接受请求失败且不新增成员

#### Scenario: 已有成员打开当前链接
- **WHEN** Token 仍是当前邀请且当前用户已经是成员
- **THEN** 返回 ALREADY_MEMBER，直接进入 Namespace，不消耗邀请

#### Scenario: 被移除者重用已消费链接
- **WHEN** 曾兑换的成员后来被移除
- **THEN** 原链接仍保持已消费，不能再次加入

### Requirement: REQ-INV-05 并发操作 SHALL 保持成员和邀请原子一致

并发操作 SHALL 保持成员和邀请原子一致。

邀请创建、替换、作废和接受与成员添加/删除/转交、Namespace 冻结/归档/恢复/删除、账号合并在相同 Namespace 上须串行化。涉及用户和 Namespace 的写操作先按用户 ID 升序、再按 Namespace ID 升序加锁；创建/替换/作废锁操作者，接受锁接收者；纯 Namespace 生命周期与删除只锁 Namespace。接受时成员创建、邀请消费和审计同一事务完成；并发兑换最多一名非成员成功，不能产生“已消费但无成员”。批量涉及多个 Namespace 的操作按去重 ID 升序锁定。

#### Scenario: 两人同时兑换
- **WHEN** 两个非成员同时确认同一链接
- **THEN** 最多一人新增 MEMBER，另一人收到统一失效结果

#### Scenario: 管理员直接添加与邀请并发
- **WHEN** 管理员直接添加同一用户和该用户兑换邀请同时发生
- **THEN** 不出现未处理的唯一约束异常、重复成员或错误消费

### Requirement: REQ-INV-06 前端 SHALL 完成链接分享和状态闭环

前端 SHALL 完成链接分享、登录回跳和邀请状态闭环。

管理页提供生成、替换确认、复制失败后的手动复制以及单独作废；接收页提供加载、确认、已加入和失效状态。成功、已加入或终态失效时清除 sessionStorage 中的 Token；网络错误保留 Token 以重试。生成公开链接必须复用现有公开基地址解析方式，清理 fragment 时保留当前 path 和 query。中文、英文和俄文文案均覆盖。

#### Scenario: 剪贴板写入失败
- **WHEN** 创建成功但浏览器拒绝写剪贴板
- **THEN** 页面显示同一链接供手动复制，不再次生成邀请

#### Scenario: 网络错误
- **WHEN** 预览或接受遇到可重试网络错误
- **THEN** 页面保留 Token，用户可重试而无需新邀请
