# 请求 Hook、缓存与并发行为

适用于请求、Mutation、缓存键、失效、取消和重试修改。服务器状态使用 TanStack Query，
不在 `useEffect` 中另建取数与缓存链。Effect 可用于确需的外部订阅等副作用，但要清理资源；
命令式上传、下载等操作复用现有 API 边界，不为形式一致改写其业务语义。

## 工程约定

- 复用 `web/src/api/client.ts` 的类型化请求边界；不要绕过既有 CSRF、基础路径、语言头与错误处理。
  生成类型和现存 wrapper 的边界见[类型安全](./type-safety.md)。
- 业务 Hook 放所属功能；跨功能稳定能力才放 shared。返回值明确区分加载、失败、数据和操作状态。
- 缓存键纳入影响响应的筛选、排序、分页、资源和必要的身份/语言维度。复用已有键工厂，
  让读取、写入和失效针对同一身份；不无条件清空全站缓存。
- Mutation 成功后按影响更新详情、列表、计数或权限缓存；检查当前筛选/分页是否仍成立。
  错误处理遵守当前局部与全局处理边界，避免重复弹提示。

## 具体例子：列表排序与联动刷新

[usePromotionList](../../../web/src/features/promotion/use-promotion-list.ts)先把默认分页、状态与排序
归一化为 `normalizedParams`，同一个对象用于 `['promotions', normalizedParams]` 和 API 调用。
因此 `sortDirection: 'ASC'` 与 `'DESC'` 的结果不会共用一个缓存身份。

```ts
// 正例：请求条件与缓存身份使用同一份归一化参数
queryKey: ['promotions', normalizedParams],
queryFn: () => promotionApi.list(normalizedParams),

// 反例：请求已改变排序，键却不区分，可能复用另一排序的结果
queryKey: ['promotions'],
queryFn: () => promotionApi.list(normalizedParams),
```

同文件的审批/拒绝成功回调同时失效 `['promotions']` 和 `['governance']`，因为治理面板也消费
该结果。添加新的消费者时先定位其 query key；只刷新按钮所在列表可能让另一个页面保留旧状态。
这不是要求所有 mutation 都刷新这两个前缀，而是要求写出“受影响消费者 → 缓存键”的实际对应关系。

验证排序变更时分别断言 API 参数含 ASC/DESC、两个 query key 不相同；验证 mutation 变更时
断言实际受影响的缓存失效，不能只断言 mutation 请求成功。现有
[列表测试](../../../web/src/features/promotion/use-promotion-list.test.ts)覆盖前三者中的参数/键，
不覆盖 mutation 刷新或真实 QueryClient 竞态，按此次修改补足对应边界。

## 接口与行为契约

1. 快速切换 A→B 后，A 的迟到结果不能作为 B 显示或写入 B 的缓存。正确 query key 之外，
   核对手写回调、额外本地状态和同资源并发 mutation；最新可接受结果由业务语义决定。
2. 支持取消的请求应将查询 signal 传到请求边界并清理监听/计时器。区分主动取消、超时和业务失败。
   隐藏组件或停止等待不等于服务器写入撤销；需要撤销业务时使用正式接口契约。
3. 保留旧结果必须是明确的过渡体验，不能误标成新条件的最终结果；跨身份或敏感资源边界不沿用旧数据。
4. 乐观更新仅在失败可恢复且冲突可处理时使用。记录受影响快照，失败恢复，并与后续成功结果协调，
   避免旧请求失败回滚掉新请求成功。复杂写入可以等待成功后刷新，无须默认乐观更新。
5. 不盲目重试写操作；先确认重复执行的业务语义。读取重试也需区分授权失败、取消和暂时性失败，
   不能把所有错误统一包装成可重试异常。

## 现状锚点与待核实差异

- [查询实现](../../../web/src/shared/hooks/use-skill-queries.ts)中搜索键带参数，使用 `keepPreviousData`。
  源码注释记录减少搜索时卸载抖动的目的；不是所有页面都应保留旧结果的依据。
- [缓存键测试](../../../web/src/shared/hooks/query-keys.test.ts)与
  [查询测试](../../../web/src/shared/hooks/use-skill-queries.test.ts)是可参考锚点，不能据此声称竞态已全面覆盖。
- 当前上述查询函数未普遍透传 Query signal；[请求客户端](../../../web/src/api/client.ts)虽支持 signal/timeout，
  仍将 `AbortError` 转成超时错误。新增取消能力需同时核查这一映射及调用方，本文不声称取消闭环已实现。
- [Provider](../../../web/src/app/providers.tsx)集中默认重试和全局错误处理；端点例外应说明原因，不能复制另一套默认策略。

## 验证

针对变更覆盖逆序响应、切换后旧请求完成、Mutation 失败恢复、身份隔离、受影响缓存刷新；
涉及取消则断言主动取消与超时的表现。用可控 Promise/网络 fixture 触发，不能用 re-export
存在性或查询配置字符串代替行为证明。测试方法见[前端测试](../testing/frontend-testing.md)。
