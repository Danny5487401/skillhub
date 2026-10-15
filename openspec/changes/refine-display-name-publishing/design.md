## Decisions

1. 发布 multipart 增加可选 displayName，trim 后非空值优先，最多 200 个字符；空值回退包内展示名称。原包校验保持严格。
2. 在现有 parsedMetadataJson 中增加可选 displayNameOverride；原始 frontmatter 不变，旧快照兼容。审核、撤下回退和所选版本提升从相应版本快照读取名称。
3. 鉴权只读 package-preview 复用包解压安全策略和元数据解析器，仅返回 name/displayName/description/version，不存储文件、不创建版本、不启动扫描。
4. 前端选包后读取建议名称；每次选择独立请求，过期结果不能覆盖当前文件，用户已编辑的名称不被新预填覆盖。读取失败可重新选包重试；头像单独更新不提交名称。
5. 生命周期版本投影提供可选 displayName/summary；现有权限决定 ownerPreview 是否可见。管理列表只保留一个主状态，治理状态优先于更新状态，待审更新优先于已发布状态；版本差异使用普通说明。
6. 保留现有单套预览数据；关键阶段独立 Review 后执行真实浏览器和生命周期验收。
