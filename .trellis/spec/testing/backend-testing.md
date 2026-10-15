# 后端测试设计

测试放在对应 Maven 模块的 `src/test/java/`。使用现有 JUnit 5、Mockito、AssertJ 与 Spring 测试切片；
沿用 `{ControllerName}Test`、`{ServiceName}Test`、`{FlowName}IntegrationTest`、
`{ControllerName}SecurityTest` 等相邻命名，不为统一命名重命名现有测试。

## 按需要证明的行为选层次

| 行为 | 测试边界与断言 |
|---|---|
| 领域状态、权限决策、计算 | 领域单测；正常、边界、拒绝及失败后状态，mock 外部端口而非被测规则 |
| HTTP 参数、认证上下文、响应与异常映射 | Controller / 安全测试；状态码、响应字段、匿名/越权负例；服务单测不能证明过滤器链 |
| JPA 查询、数据库约束、锁、原生 SQL | 仓储或数据库集成测试；检查实际结果与约束，不只 verify repository 调用 |
| 事务回滚、提交后副作用、并发更新 | 独立事务与连接，控制竞争顺序，检查最终持久状态、重复副作用与失败返回 |
| Flyway 迁移 | 适用 PostgreSQL 上运行迁移并验证升级后的 schema/数据；文本扫描仅为辅助护栏 |

数据库方言、锁或事务是风险来源时，H2 与 mock 不足以代替 PostgreSQL。复用项目的 Testcontainers
模式，或者任务已授权的隔离实例；禁止把测试指向共享/生产库。测试依赖缺失应报告受阻，
不能删除断言或跳过用例后宣称该契约已验证。

## 隔离与并发

- fixture 显式表达角色、归属、版本和初始状态，避免依赖本机已有数据。
- 回滚型测试只证明其覆盖的事务；后台线程、异步任务、提交回调需要单独等待与清理。
- 用 latch/barrier 或明确事务顺序触发竞争，设有界超时；不要依赖机器速度与 sleep。
- 验证允许的一组结果以及最终不变量，不强求未由业务契约指定的竞争赢家。
- 涉及 Redis、对象存储或消息时分别证明持久化和外部副作用，测试结束释放资源。

## 执行与已知经验

使用 `make test-backend-app`（含 `-am`）或 `make test-backend`。
只运行 `./mvnw -pl skillhub-app clean test` 可能使用本地 Maven 仓库的旧兄弟模块产物，
产生误导性的符号/签名错误。出现这类错误先核对 reactor 范围，不直接修改正确的业务签名。
过滤单个测试时同样考虑依赖模块及无匹配测试的处理，运行前核对当前 Maven 配置。

来源： [Makefile](../../../Makefile)、[测试依赖](../../../server/skillhub-app/pom.xml)、
[PostgreSQL 邀请并发用例](../../../server/skillhub-app/src/test/java/com/iflytek/skillhub/repository/NamespaceInvitationPostgresTest.java)、
[迁移静态护栏](../../../server/skillhub-app/src/test/java/com/iflytek/skillhub/db/FlywayMigrationGuardrailTest.java)。
前者可参考真实连接和并发接受邀请的组织方式；后者不替代数据库迁移运行。
