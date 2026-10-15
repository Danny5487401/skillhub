# 数据库、查询与迁移

适用 PostgreSQL/JPA/Flyway 代码。数据模型见 [领域模型](../../../docs/02-domain-model.md)，
数据库配置及版本核对实际构建和部署配置。

## 仓储与读模型

- 聚合读取、状态迁移和规则判断用 domain 仓储接口，由 infra 实现。
- 页面列表、join、DTO 投影用 app 查询仓储；薄的单聚合读取不必新增查询抽象。
- 必要的直接 SQL/EntityManager 在类注释解释原因，参数绑定避免拼接用户输入。
- 修改分页、排序、过滤或计数时同时检查数据查询与 total 查询，以及权限过滤；关注稳定排序、空结果、同值排序与跨页重复。
- 数据库特性、索引和锁行为用真实 PostgreSQL 验证，mock 返回成功不证明 SQL 可执行。

## 迁移约定

结构变更使用 [Flyway迁移](../../../server/skillhub-app/src/main/resources/db/migration/)。
已发布迁移不靠改旧文件纠正；新增迁移需考虑现有数据、默认值、回填、约束和新旧应用兼容。
新建库成功不能证明存量升级成功；影响已有数据时验证代表性旧数据升级。
用户身份列保持字符串语义。结构完整性与多变的业务政策分开：新增 CHECK 等约束需说明稳定性、迁移代价及与应用规则的关系，不能把全部工作流写死在 SQL 中。

变更查询前定位实际慢点与调用频率，检查 N+1、查询范围和必要索引；性能结论提供代表性数据与执行计划，不能从“加了索引”直接推导收益。
涉及缓存时说明权威数据来源、失效触发和身份/命名空间隔离，不以缓存替代授权。

## 验证与边界

状态竞争、事务回滚见 [事务与并发](./transactions-and-concurrency.md)。
迁移发布顺序和回退限制读 [部署规范](../deployment/index.md)；测试方法读 [后端测试](../testing/backend-testing.md)。
例证：[查询实现](../../../server/skillhub-app/src/main/java/com/iflytek/skillhub/repository/JpaMySkillQueryRepository.java)、
[查询测试](../../../server/skillhub-app/src/test/java/com/iflytek/skillhub/repository/JpaMySkillQueryRepositoryTest.java)。
