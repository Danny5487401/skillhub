# 扫描与队列集成

适用 Java 后端与扫描服务、Redis Stream、对象存储之间的协议与失败处理；
部署步骤和配置详情引用 [Scanner文档](../../../scanner/README.md)，不复制运行手册或固定重试次数。

## 工程边界

- 领域扫描服务协调扫描状态；outbox dispatcher 发布任务；consumer 处理投递并调用 scanner 适配器。
- 任务持久化、投递、业务完成和消息确认是不同步骤。发送成功不能证明扫描完成，超时也不能证明远端没有执行。
- 保持任务/版本身份、原始时间和重试信息在重投递中可追踪；不能每次重新计时导致恢复窗口无限延长。
- 区分暂时不可用、可重试失败、终态失败与重复消息；策略依据现有代码和配置，不统一“失败立即确认”或“永远重试”。
- 包下载、临时文件和扫描目录使用现有路径安全边界；清理只针对本次拥有的文件，不能误删并行扫描仍在使用的目录。

## 应保留的行为

[consumer](../../../server/skillhub-app/src/main/java/com/iflytek/skillhub/stream/ScanTaskConsumer.java)
存在任务锁与 pending 恢复逻辑；锁被占用时保留原投递，避免重复消费抹去唯一持久任务。
扫描暂不可用和超过恢复窗口采用不同路径。失败已记录但确认失败后的重投递不能重复做业务失败处理。
[outbox dispatcher](../../../server/skillhub-app/src/main/java/com/iflytek/skillhub/task/ScanTaskOutboxDispatcher.java)
使用 claim、发送标记和退避；修改需要检查租约、发送与标记间失败以及多实例竞争。

## 验证锚点

[消费测试](../../../server/skillhub-app/src/test/java/com/iflytek/skillhub/stream/ScanTaskConsumerTest.java)
覆盖扫描不可用、锁占用、恢复窗口与确认失败后的重投递；
[路径安全测试](../../../server/skillhub-app/src/test/java/com/iflytek/skillhub/stream/ScanTaskConsumerPathSafetyTest.java)
覆盖临时路径边界；
[outbox测试](../../../server/skillhub-app/src/test/java/com/iflytek/skillhub/task/ScanTaskOutboxDispatcherTest.java)
用于检查调度行为。
这些是回归锚点，不意味着本次已运行或真实 Redis/Scanner 链路已验收。
