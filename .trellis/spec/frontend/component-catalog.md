# 组件目录与选型

这份共享目录维护盘点方法、维护决策和候选分类；它不声明某个分支已安装或已通过验证。
当前检出的具体组件表、官方差异与执行结果记录到当前任务，避免内部或旧分支快照随模板传播。
开始组件工作前重新核对目录、组件 API、调用方和测试。

## 当前检出盘点格式

每个组件至少记录：

| 字段 | 要求 |
|---|---|
| 检出身份 | origin、工作树、分支、基线、核对日期；只保留在任务记录 |
| 本地路径与用途 | 实际文件、导入方式、基础/业务组合归属 |
| 公开 API | 源码中实际导出、原生属性、ref、变体、受控契约；不得编造官方属性 |
| 底层与调用方 | 原生 HTML/Radix/自有实现、代表调用方与相关测试 |
| 官方参考 | 当前适配来源及版本；区别参考和来源证明 |
| 差异与结论 | 保留/调整/增加变体/按需补缺，写理由与必须保留的行为 |
| 实现与验证 | 已存在、待适配、已检查、未验证；区分源码、工程检查与浏览器证据 |

常见盘点对象包括 Button、Input、Textarea、Label、Card、Table、Select、Dropdown Menu、
Dialog、Tabs；数量以当前文件为准，不能从这份示例清单推断实际文件存在。
Button 变体与 loading/asChild、Dialog 的底层、Tabs 的交互尤须读取源码。

业务无关组合按实际存在情况调查页头、空态、分页、确认、骨架、复制与通知；
已有能力满足需求时保留，不因官方同名组件存在就另建一套。

## 官方参考与兼容性

官方组件目录：<https://ui.shadcn.com/docs/components>。
配置：<https://ui.shadcn.com/docs/components-json>。
Tailwind 迁移说明：<https://ui.shadcn.com/docs/tailwind-v4>。
具体组件文档与注册表在使用时核对；示例链接不是当次 URL 存活或兼容性验证结果。
保留当前合理定制，逐项检查生成目录、导入、依赖和动画插件；不直接复制不兼容的新栈示例。

## 按需候选

| 场景 | 可调查项 | 边界 |
|---|---|---|
| 表单组合 | Field、Input Group、Native Select | 先检查现有 Label/Input 组合和当前栈兼容性 |
| 输入选择 | Combobox、Checkbox、Radio Group、Switch | 先查原生控件或已有业务能力，再判断是否需要新原语 |
| 展示 | Badge、Avatar、Separator、Accordion、Collapsible | 不把候选写成可直接 import 的已安装组件 |
| 数据表 | Data Table | 组合方案；优先现有 Table/分页，排序筛选按业务需求定义 |
| 导航 | Breadcrumb、Navigation Menu、Sidebar、Command | 沿用 router 与已有导航，缺口成立才增加 |
| 弹层 | Alert Dialog、Popover、Tooltip、Sheet、Drawer | 需要实际验证焦点、Portal 与底层兼容性 |
| 反馈 | Alert、Spinner、Progress | 现有文字/图标/通知能满足时不另建体系 |
| 专门需求 | Calendar、Date Picker、Chart、Resizable、Scroll Area | 真实需求成立再查依赖，不默认安装大型工具链 |

清单是调查入口，不代表对应版本、文档、注册表或依赖已经在当前任务验证。
