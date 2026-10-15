# 错误处理

适用领域失败、HTTP 映射和面向用户的错误信息。

- 领域失败沿用 `LocalizedDomainException` 及现有 bad-request/not-found/forbidden 子类，携带消息键与安全参数；不要在领域层返回 HTTP DTO。
- 认证链、Controller advice 和兼容层各按已有协议处理，不能假设所有错误必然经过同一个 advice。
- 复用 ApiResponseFactory 和现有消息资源。面向用户的业务提示可本地化，内部异常详情不直接拼进响应。
- 不用异常文案匹配来新增业务分类。发现存量特殊兼容处理时保持其适用范围，避免扩展成通用规则。
- 错误响应验证 HTTP 状态、响应结构、消息与失败后数据状态；只断言“抛了异常”不足以证明事务回滚。
- 不吞掉基础设施失败并返回业务成功；是否重试取决于错误类别及重复执行安全性。

当前统一 handler 将乐观锁冲突映射为 409、对象存储访问故障映射为 503，并隐藏内部错误细节。
这些是实现锚点，不代表所有失败都应转换成同一状态码。

来源：[异常实现](../../../server/skillhub-app/src/main/java/com/iflytek/skillhub/exception/GlobalExceptionHandler.java)、
[响应与脱敏断言](../../../server/skillhub-app/src/test/java/com/iflytek/skillhub/exception/GlobalExceptionHandlerTest.java)、
[领域异常](../../../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/shared/exception/LocalizedDomainException.java)。
