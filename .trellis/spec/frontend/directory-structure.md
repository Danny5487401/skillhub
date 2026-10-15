# 前端目录与依赖归属

适用于新增文件、拆分组件、移动逻辑和导入调整。具体架构背景见[前端架构](../../../docs/08-frontend-architecture.md)。

| 位置 | 职责 |
|---|---|
| `web/src/app/` | 应用入口、路由、Provider 与整体布局 |
| `web/src/pages/` | 路由级页面及页面组合 |
| `web/src/features/` | 业务功能与该功能的交互、Hook |
| `web/src/entities/` | 领域对象展示 |
| `web/src/shared/` | 跨功能基础组件、工具与共享能力 |
| `web/src/api/` | 请求边界、API 类型适配；generated 为生成结果 |
| `web/src/i18n/` | 国际化配置与翻译资源 |

选择能承担语义的最低合适层，而不是把所有逻辑下沉到 shared。页面专属权限、导航、发布流程
留在业务目录；组件共享取决于职责与变化原因，不取决于出现次数。已有共享业务 Hook 的分布
不代表新增功能都应继续集中在一个文件；不为了符合示意树搬动有效代码。

移动时核对直接导入、别名、barrel/re-export、字符串路径、测试和调用方。先复用
[组件约定](./component-guidelines.md)中的基础能力；业务规则不得为迁就通用组件 API 被改写。
技术栈和具体版本读取[依赖声明](../../../web/package.json)，不在规范里维护易过期功能或依赖清单。

## 验证与锚点

检查路由能定位页面、原导入仍解析、共享模块没有新增不必要的页面依赖；检查方法见
[前端测试](../testing/frontend-testing.md)。示例：[应用装配](../../../web/src/app/providers.tsx)、
[业务 Hook 兼容导出](../../../web/src/features/skill/use-search-skills.ts)及其
[导出测试](../../../web/src/features/skill/use-search-skills.test.ts)。导出测试只证明路径有效，不证明请求行为。
