# 日志、请求追踪与审计

适用新增日志、异常日志、异步任务与审计字段。

- 使用 SLF4J 参数化日志和既有上下文，记录能定位操作的必要字段。业务事件通常 INFO、调试 DEBUG；预期拒绝不必 ERROR，真正故障保留必要原因。
- 沿用 RequestIdAccessor/RequestIdFilter 的校验、作用域与清理机制；不要直接信任任意请求头，也不要让线程复用残留上一请求上下文。
- token、密码、Cookie、OAuth secret、私钥和原始敏感请求体不进入日志；查询串、异常文本、对象 toString 同样可能泄露。
- 复用适用的脱敏工具并测试新增敏感字段；现有过滤器只覆盖已知字段，存在过滤器不证明所有输入已安全。
- 区分故障排查日志与业务审计。审计沿既有 AuditLogService 传递可信操作者和必要目标，不能靠普通 INFO 日志替代正式审计。
- 循环、批处理和重试避免逐条噪声；保留任务标识、失败阶段与重试状态，不记录完整包内容或凭证。

修改追踪验证无效 ID、正常响应和异常后的作用域释放；修改敏感字段日志用包含假凭证的测试输入断言其未输出。

来源：[请求ID实现](../../../server/skillhub-app/src/main/java/com/iflytek/skillhub/filter/RequestIdFilter.java)、
[请求ID测试](../../../server/skillhub-app/src/test/java/com/iflytek/skillhub/filter/RequestIdFilterTest.java)、
[请求日志实现](../../../server/skillhub-app/src/main/java/com/iflytek/skillhub/filter/RequestLoggingFilter.java)、
[请求日志测试](../../../server/skillhub-app/src/test/java/com/iflytek/skillhub/filter/RequestLoggingFilterTest.java)。
