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

## Python Scanner 本体

修改 [skillhub_scanner_app.py](../../../scanner/skillhub_scanner_app.py) 的并发限制、取消、超时、响应脱敏或临时目录时，
先读 [本体测试](../../../scanner/tests/test_skillhub_scanner_app.py)，不只运行 Java consumer 测试。

| 场景 | 当前行为与可观察结果 | 测试名 |
|---|---|---|
| 扫描容量已满 | 新请求返回 503，`Retry-After: 30`，不启动额外扫描 | `test_excess_scan_is_rejected` |
| 客户端取消，但底层扫描尚未结束 | 容量仍被占用，新请求仍拒绝；原扫描结束后释放容量 | `test_client_disconnect_keeps_slot_until_scan_finishes` |
| 扫描超过硬超时 | 调用重启处理；真实实现记录 critical、关闭日志并退出进程 | `test_hard_timeout_requests_process_restart`（替换重启函数，未真正退出进程） |
| 响应 findings 含敏感文本 | 脱敏但保留原响应契约与正常文本 | `test_scan_findings_redact_credentials_without_changing_response_contract`、`test_safe_scan_findings_are_unchanged` |
| 初始化临时目录 | 仅重建私有运行目录，不清理父目录 | `test_import_recreates_private_runtime_temp_root_without_touching_parent` |

反例：HTTP 请求一取消就释放并发槽位，但底层线程仍扫描；再次重试会绕过容量限制。
沿用现有 shield/等待与 finally 释放路径；不要将客户端断开等同于计算已停止。

日志使用现有 `logging.getLogger(__name__)` 与参数化消息，新增日志不得包含 API key、上传包全文或未脱敏 findings。
当前包装层源码未配置 JSON formatter；不能把“结构化 JSON 日志”写成已具备的保证。
日志格式或容器退出恢复变化还需检查 [监控手册](../../../scanner/docs/monitoring-guide.md) 和运行配置。

仓库根目录运行现有 [CI](../../../.github/workflows/pr-scripts.yml) 使用的命令：

```bash
python3 -m unittest discover -s scanner/tests -p 'test_*.py'
```

期望所选用例全部通过。此套测试用替身隔离上游服务，不能证明 Cisco 扫描引擎、真实 HTTP 断连、容器重启或 Redis 链路已验收；
这些边界发生变化时按 [部署规范](../deployment/index.md) 补环境验证。
