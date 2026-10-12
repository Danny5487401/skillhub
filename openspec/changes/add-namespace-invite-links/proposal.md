## Why

Namespace 管理员目前只能逐个选择现有用户添加成员。短期、单次邀请链接能让成员自行加入，同时让管理员控制链接失效和成员权限边界。

## What Changes

- ACTIVE TEAM 的 OWNER/ADMIN 可以生成一条 24 小时内有效、仅一名非成员可兑换的邀请链接；替换未使用链接前须确认。
- 管理员可以单独作废当前邀请，不需要生成新链接；不影响已经加入的成员。
- 持链接者先按现有注册、登录和账号准入策略获得可用账号，再看到空间、当前账号和 MEMBER 权限，明确确认后加入。
- Token 不存明文、不进 URL path/query 或登录回跳参数；创建、预览、兑换、作废均受权限及事务约束。
- 邀请兑换与成员及 Namespace 生命周期操作并发时保持一致，不出现重复成员或部分消费。

## Capabilities

### New Capabilities

- `namespace-invitations`: 邀请创建、替换、作废、预览、确认加入、安全与并发规则。

### Modified Capabilities

无。

## Impact

- Namespace 领域、成员管理、账号合并事务、数据库迁移、审计、Session/CSRF 接口和 OpenAPI 类型。
- Namespace 管理页面、邀请页、登录回跳与子路径部署、多语言文案及浏览器验证。

## 相对 Issue #911 的调整

原 Issue 把独立撤销入口列为范围外；本方案增加“作废邀请”按钮和相应后端动作。邀请不绕过现有账号准入策略。
