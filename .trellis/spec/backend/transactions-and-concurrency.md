# 事务、并发与外部副作用

适用状态迁移、批量写入、重复请求、异步处理及对象存储/消息副作用。
本页要求显式分析契约，不给所有接口增加幂等键、分布式锁或统一重试。

## 修改前确定边界

- 列出必须共同提交的数据以及可延迟的副作用，确定事务真正由哪个 Spring 代理调用进入。
- 明确前置校验到写入之间是否存在竞争；预检查不代替锁定后或条件更新时的状态校验。
- 对重复提交说明复用结果、拒绝冲突还是允许重复；根据现有业务契约选择机制，不能自行改变产品语义。
- 定义失败后的可观察状态：回滚哪些数据、已经发出的消息或上传文件由谁补偿、如何发现和恢复。
- 取消请求/超时只表示一段等待结束，不能推断数据库或外部系统已经撤销操作。

## 现有实现模式及适用范围

[扫描重试服务](../../../server/skillhub-app/src/main/java/com/iflytek/skillhub/service/SecurityScanRetryAppService.java)
先做标量状态预检查，再锁定版本并重判；有活动扫描时复用响应。这个模式只证明该用例的处理，不意味着全部 API 幂等。
[并发回归测试](../../../server/skillhub-app/src/test/java/com/iflytek/skillhub/service/SecurityScanRetryLockingTest.java)
用两个事务验证等待锁后看见已提交新状态，避免持久化上下文中的预加载旧实体误导决策。
复用经验时检查 ORM 缓存、锁顺序和超时，不只看有无 `@Transactional`。

[版本治理](../../../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/service/SkillGovernanceService.java)
将对象删除放在提交后，失败交给补偿；数据库回滚不能自动恢复外部删除。
[补偿服务](../../../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/service/SkillStorageDeletionCompensationService.java)
区分普通清理失败与发布回滚后的独立事务记录。修改时检查实际代理调用与提交时序，不把“记录补偿”视为已可靠持久化的证明。

## 验证要求

- 竞争测试控制交错顺序并断言最终持久状态；单线程 mock 不证明真实锁语义。
- 在事务提交、外部调用失败、重投递等关键边界注入失败，验证无丢失/重复副作用及补偿可达。
- 幂等范围若涉及任务ID、版本或请求键，验证同键与不同键，不以请求次数推定逻辑执行次数。
- 检查拒绝/回滚时事件、审核记录、指针与对象文件的一致性；正式业务预期引用生命周期文档并核对其漂移。

测试设计见 [后端测试](../testing/backend-testing.md)，队列细节见 [扫描集成](./scanning-integration.md)。
