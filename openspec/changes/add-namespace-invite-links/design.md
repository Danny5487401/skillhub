## Context

现有 Namespace 成员只能由管理员直接添加。`NamespaceMemberService`、平台管理服务、生命周期服务和账号合并均会修改同一 Namespace 的状态或成员，但目前没有共同的 Namespace 行锁。前端已有 Session/CSRF、登录 returnTo 与 BASE_PATH 逻辑；当前 returnTo helper 会保留 fragment，所以 `/join` 必须先移除 Token 再触发登录跳转。

## Decisions

1. 每个 Namespace 只保存一条当前邀请：Token SHA-256、签发人和时间、过期时间、消费人和时间。原文只在生成响应中返回一次。作废删除当前邀请记录或等价地使其不可匹配，写审计；不删除已加入成员。
2. 涉及用户的写操作先按 userId 升序锁参与者用户行，再按 Namespace ID 升序锁目标 Namespace 行，最后读取或写入邀请与成员；账号合并沿用先锁用户的现有顺序，以免与成员或审计外键检查形成锁环。邀请创建、替换、作废以及带操作者审计的生命周期操作先锁操作者，再锁 Namespace；接受先锁接收者，再锁 Namespace。当前不写用户审计的 Namespace 删除只锁 Namespace。批量操作预先收集 ID、去重并排序。接受在锁内重检，成员创建、消费和审计原子提交。
3. 前端在 `/join` 入口同步读取 fragment，放入当前标签页 sessionStorage，保留 path/query 清除 fragment，再应用原登录逻辑。登录 `returnTo=/join` 不传 Token。分享链接复用 `resolvePublicRegistryUrl` 构造并兼容 BASE_PATH。
4. Preview/Accept 复用 Issue 的判定顺序：先验证 Token 为当前邀请，再查已有成员；已有成员返回 ALREADY_MEMBER。非成员必须面对 ACTIVE TEAM、未消费且未过期的邀请。失效统一 404，不区分随机、旧、作废、过期或已用 Token。Accept 锁前只按 Token 哈希查询标量 Namespace ID；取得 Namespace 锁后重新加载当前邀请并校验，不能沿用锁前已经进入 ORM 一级缓存的邀请实体，否则并发兑换可能都看到未消费状态。
5. 只有可用浏览器 Session 能创建、预览、接受、作废；现有账号准入不能由邀请绕过。浏览器请求使用既有 CSRF。创建/预览/接受/作废与缓存或日志配置均不得泄露原文和哈希。
6. 管理页面保留原直接添加成员入口，新增邀请按钮和单独作废按钮。仍可兑换的旧链接替换前先确认；剪贴板失败时展示本次生成的同一链接。
7. 正式路径沿用 Issue #911 的复数合同：`POST /api/v1/namespaces/{slug}/invitations` 创建或替换，`POST /api/v1/namespace-invitations/preview|accept` 预览及确认。状态查询和作废使用同一 `/namespaces/{slug}/invitations` 路径的 GET/DELETE；保留现有 `/api/web` 别名。Preview 返回 `namespaceSlug`、`namespaceDisplayName`、`namespaceAvatarUrl`、`outcome`、`expiresAt`；Accept 仅返回 `namespaceSlug`、`outcome`。无效邀请的本地化键为 `error.namespace.invitation.invalid`。

## Risks and verification

- 账号合并已先锁 UserAccount；邀请创建/替换/作废/接受、直接成员变更、平台成员管理及账号合并要按相同的用户后 Namespace 顺序加锁，避免与成员或审计外键检查形成环。
- 并发兑换、直接添加与兑换、替换/作废与兑换、冻结/归档/删除与兑换均需事务级验证。
- 根路径及子路径部署都要验证分享 URL、登录回跳和 fragment 清理。
- API Token、跨团队管理员、平台角色和禁用账号的越权路径需明确拒绝。

## 管理交互与具名片段（2026-10-13，已确认，实施中）

新分享地址为 `/join#invite=<token>`，兼容原 `/join#<token>`。进入页仍在登录跳转前同步捕获并清片段，Token不进入HTTP路径、查询、returnTo或日志。新片段无效时清旧暂存Token，无片段登录回跳才恢复暂存值。

命名空间卡片不平铺成员、邀请、作废、冻结、归档按钮；管理详情按技能／成员／审核／设置集中导航。成员页弹窗管理邀请，治理操作进入设置并保留旧权限与危险动作确认。公共空间技能浏览及旧成员／审核链接保持可用。平台审核权不等于成员管理权；GLOBAL不可冻结归档，但合法技能审核仍可用。
