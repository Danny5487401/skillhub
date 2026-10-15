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

## 场景：关键业务操作的审计随重构丢失

修改发布、下载、删除、授权及审核流程时，即使没有改日志代码，也必须检查审计链路。
先在旧调用链定位事件生产位置，记录 action、目标类型/ID、可信操作者、请求上下文以及记录发生在成功还是失败分支；
新调用链必须保留所需语义。匿名下载等场景应遵循原契约，不能自行伪造用户身份。
找不到原记录或业务文档与实现不一致时报告缺口，不能宣称已有覆盖，也不借规范整理擅自新增业务政策。

具体参考：[PromotionPortalAppServiceTest](../../../server/skillhub-app/src/test/java/com/iflytek/skillhub/service/PromotionPortalAppServiceTest.java)
的 `approvePromotion_recordsSelfReviewAuditDetailForSuperAdminSelfApproval`：除了调用成功，
还验证 `PROMOTION_APPROVE`、`PROMOTION_REQUEST`、目标 ID、操作者与 `selfReview` 明细。
[AuditLogService](../../../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/audit/AuditLogService.java)
及其 [测试](../../../server/skillhub-domain/src/test/java/com/iflytek/skillhub/domain/audit/AuditLogServiceTest.java)
是公共记录入口，不要在各服务自行拼接另一套审计持久化方式。

反例：重构后 HTTP 仍返回 200，因而删除旧审计调用；或用一条 `log.info("deleted")` 替代业务审计。
验收：对受影响操作补/保留 action、目标、操作者与必要明细断言，检查拒绝/失败分支不会记录虚假的成功事件。
mock 的 `record` 断言只能证明调用参数；改动审计持久化或事务时还需验证实际提交/回滚结果。
