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
可参考参数、缓存键和失效断言，但其 mock 方式不证明 React Query 的真实竞态行为。
这是选择测试边界的例子，不要求所有 Hook 使用同一模板。
