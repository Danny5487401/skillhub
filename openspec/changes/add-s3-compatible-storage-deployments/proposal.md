## Why

[#937](https://github.com/iflytek/skillhub/issues/937) 希望提供 MinIO 之外的对象存储选择。SkillHub 已有 S3 协议适配层，缺的是可复用的交付配置、真实兼容性证据和迁移操作边界。

## What Changes

- 保持 `local` / `s3` 配置契约，通过同一 S3 接口接入 RustFS、PGSTY Silo 或外部 S3 服务。
- 为正式 Compose 栈增加可选 RustFS / Silo 叠加文件，使用独立数据卷，不静默替换现有部署。
- 明确内外网端点、预签名、业务凭证、桶初始化、GitHub 同步 Worker 网络和 Helm 外部存储配置。
- 增加共享真实契约验收、迁移风险及停写和回退参考，以及镜像供应链说明；新部署接入验证不构成生产迁移支持。

## Capabilities

### New Capabilities

- `s3-compatible-storage`: 不同 S3 实现下的对象读写、签名、权限和错误行为契约。
- `object-storage-deployment`: 可选部署、配置诊断、兼容性声明与迁移参考的安全边界。

### Modified Capabilities

无。当前 `openspec/specs/` 尚无相应能力正文；新增规格记录本次目标，不代表重写全部已有实现。

## Impact

主要影响 `server/skillhub-storage`、Compose、配置校验、镜像清单、Helm 示例和部署手册。验证发布下载、头像审核对象、Suite 包和 GitHub 同步调用链；业务模块不引入供应商 SDK。

不改数据库、公开 API、技能生命周期或权限策略，不增加后台存储配置页面。实现与本地验收状态见 tasks.md；最终提交、CI 和发布门禁由对应 PR 记录。
