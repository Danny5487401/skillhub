# 前端测试设计

适用于组件、Hook、页面状态与请求行为。使用现有 Vitest、Testing Library 和 Playwright，
工具配置以 [package.json](../../../web/package.json)、[Vite 配置](../../../web/vite.config.ts)为准。

## 选择边界

- 纯转换、校验、格式化用单元测试覆盖输入边界；不要为简单常量或文案写镜像实现的测试。
- 组件测试从用户动作与可见结果断言，包括错误提示、禁用/重复点击、键盘与焦点；
  需要 DOM 的用例按相邻测试配置 jsdom，不假定全部测试有浏览器环境。
- Hook 的参数映射、query key、失效范围可以隔离验证；若问题在请求竞争、缓存或重渲染，
  必须保留对应机制进行行为测试，完全 mock React Query 不能证明缓存隔离正确。
- 浏览器布局、真实导航、认证联动进入 [E2E](./e2e.md)；截图只证明捕获状态，不能证明流程完成。

## 异步、fixture 与隔离

1. 每例建立所需最小状态；清理 DOM、全局 stub、存储、计时器、监听器及 QueryClient 缓存，
   避免一个测试先运行才能通过。不同用户/命名空间用可区分的数据。
2. 用受控 promise 驱动 A、B 请求乱序完成，断言最终显示 B；涉及取消时验证取消后不覆盖状态，
   不能只断言调用了 abort。卸载、筛选切换、退出登录按修改范围加入。
3. 乐观更新验证成功确认和失败恢复，尤其不能把期间另一项成功修改回滚掉；没有乐观更新的流程不强加它。
4. 需要测试时钟时只模拟所需计时器并恢复；等待可观察条件，不能加固定 sleep 掩盖竞态。
5. fixture 保持真实类型与必要字段，复用已有 helper；测试 mock 不应成为生产类型来源。

先运行相关用例，再按[入口矩阵](./index.md)执行类型、lint、单测与构建。
可见改动还需核对长内容、小屏、键盘与 reduced-motion，具体要求见[前端验收](../frontend/quality-guidelines.md)。

## 来源与经验边界

[use-promotion-list.test.ts](../../../web/src/features/promotion/use-promotion-list.test.ts)
可参考参数与缓存键断言；目前未断言 mutation 的缓存失效，其 mock 方式也不证明 React Query 的真实竞态行为。
这是选择测试边界的例子，不要求所有 Hook 使用同一模板。

## 可直接执行的三个验证切片

以下命令从仓库根目录运行，依赖需已按锁文件安装。只选择与此次修改相关的切片，不因这里列出就全量执行。

| 改动 | 命令 | 通过时具体证明什么 |
|---|---|---|
| 晋升列表的分页/排序/key | `pnpm -C web test src/features/promotion/use-promotion-list.test.ts` | PENDING 默认参数、历史排序参数及 ASC/DESC 键区分 |
| 会话缓存清理 | `pnpm -C web test src/features/notification/notification-session.test.ts` | 用户键分区、指定私有缓存移除、公共搜索缓存保留 |
| 客户端路径/运行配置 | `pnpm -C web test src/api/client.test.ts` | 当前测试覆盖的 base URL 路径前缀、端点与运行配置行为；不证明取消或授权闭环 |

### 会话切换：为什么只断言“调用清理函数”不够

实际路径为 `useSessionBootstrap.onSuccess` → `clearSessionScopedQueries` →
`QueryClient.removeQueries`，随后才写入 `['auth', 'me']`。若新增私有查询却未纳入清理/分区机制，
清理函数照常被调用，仍可能留下上一用户的数据。

[现有缓存测试](../../../web/src/features/notification/notification-session.test.ts)使用真实 QueryClient：
先放入 A 的通知、标签和个人技能缓存，再清理，断言它们不存在，同时断言公共搜索缓存仍存在。
修改该机制时沿用这种“清理前有数据 → 清理后私有数据消失、公共数据保留”的正反断言，
并为新增缓存前缀补一项，而不是只检查 mock 函数调用次数。

当前测试使用已完成的缓存数据，不包含在途请求或真实登录流程。若改动涉及 A 请求尚未完成时
切到 B，另用受控 Promise 和真实 QueryClient 验证迟到的 A 数据不会成为 B 的结果。
不得把现有三个测试通过写成“账号切换、取消和竞态已全部验证”。
