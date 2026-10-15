## ADDED Requirements

### Requirement: 独立调整范围 SHALL 保持技能身份和内容版本
现有合法维护者 SHALL 能为已发布、正常技能选择 PUBLIC、NAMESPACE_ONLY 或 PRIVATE，而无需上传 ZIP 或新建版本。技能 ID、坐标、内容版本和统计 SHALL 保留。冻结、归档命名空间或不可维护的技能 SHALL 拒绝操作。

#### Scenario: 缩小访问范围
- **WHEN** 维护者将 PUBLIC 改为 NAMESPACE_ONLY 或 PRIVATE，或将 NAMESPACE_ONLY 改为 PRIVATE
- **THEN** 新范围立即生效，详情、搜索、下载使用同一访问规则，并保留操作审计

### Requirement: 扩大范围 SHALL 使用现有统一技能审核
扩大范围 SHALL 创建现有技能审核中的元数据申请，记录原范围、目标范围和已发布版本。批准前原范围 SHALL 保持有效；拒绝或撤回 SHALL 不改变范围。审核页 SHALL 清楚显示变更前后范围，不伪装成头像变更。

#### Scenario: 私有转公开待审
- **WHEN** PRIVATE 技能提交扩大范围申请
- **THEN** 非维护者仍不能读取或下载；审核批准后才允许目标受众访问

### Requirement: 内容审核和范围申请 SHALL 互斥
存在待审内容版本或待审范围申请时 SHALL 先完成或撤回当前申请，再提交另一项。并发上传、范围提交、审核与撤回 SHALL 在同一技能上协调，不能绕过互斥。

#### Scenario: 待审范围期间上传版本
- **WHEN** 维护者在已有待审范围申请时上传新版本或提交内容审核
- **THEN** 请求被拒绝，现有申请和内容不变

### Requirement: 扩大范围 SHALL 不绕过安全和头像审核
沿用当前技能内容审核规则及目标受众头像规则。现有 PRIVATE 发布可能未执行安全扫描；实现 SHALL 核对扩大范围的扫描前置条件，不得通过独立范围接口绕过现有公开分发安全要求。

#### Scenario: 审核期间发布版本改变
- **WHEN** 范围申请绑定的发布版本或原范围与当前技能不符
- **THEN** 审批拒绝过期申请，不能覆盖较新的状态
