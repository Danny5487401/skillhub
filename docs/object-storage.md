# 对象存储部署与迁移参考

SkillHub 通过同一个 S3 接口连接 RustFS、PGSTY Silo 或外部 S3 服务，不需要按存储品牌修改业务代码。`local` 模式保留。本文新增的单机叠加配置不是高可用存储集群；本次验证范围是文末所列指定版本的新部署接入与业务操作，不包含生产数据迁移支持。

## 1. 选择部署方式

| 方式 | 使用配置 | 数据归属 |
|---|---|---|
| Silo | `compose.release.yml` + `compose.storage.silo.yml` | 独立 `silo_data` 卷 |
| RustFS | `compose.release.yml` + `compose.storage.rustfs.yml` | 独立 `rustfs_data` 卷 |
| 外部 S3 | 仅 `compose.release.yml` | 外部服务的桶 |
| local | 仅 `compose.release.yml`，显式 `SKILLHUB_STORAGE_PROVIDER=local` | 现有应用存储卷 |

一次只选择一个本地存储。使用 `scripts/storage-compose.sh`，它会拒绝重复选择和额外 `-f` 文件；不要自行同时合并两个存储文件。所有命令在仓库根目录执行，要求 Bash、Docker Compose v2；诊断另外要求 JDK 21+、Maven Wrapper 和 GNU timeout。

本文优先以 Silo 展示自建 S3 的操作步骤，RustFS 保留可选。这一顺序基于本项目当前接入验收及既有部署方式，不是通用可靠性排名，也不代表已验证 Silo 的长期生产可靠性。`.env.release.example` 仍显式选择 `local`，开发 Compose 的 `minio` 服务仍使用原有 Silo 镜像；仅使用基础 `compose.release.yml` 时，provider 的缺省值为 `s3`，但不会自动部署任一存储产品。已有实例不会自动切换。本次没有长期稳定性、大规模负载或高可用对比证据，部署方应按自身运维条件选择并验收。

## 2. 准备受保护配置

从 `.env.release.example` 准备 `.env.release`，再把 `deploy/storage/silo.env.example`（RustFS 使用 `deploy/storage/rustfs.env.example`）中的存储配置合入该文件，并替换所有占位值。不要直接用示例部署，也不要把实际配置加入 Git。

```sh
# 已有 .env.release 时不要重复复制覆盖
cp .env.release.example .env.release
chmod 600 .env.release
# 编辑完整应用配置，再合入选定产品的 env 示例
```

配置分为两组：

- `SKILLHUB_OBJECT_STORAGE_ROOT_USER` / `SKILLHUB_OBJECT_STORAGE_ROOT_PASSWORD` 仅供对象存储管理员初始化；叠加文件不会将它们传给 SkillHub server。
- `SKILLHUB_STORAGE_S3_ACCESS_KEY` / `SKILLHUB_STORAGE_S3_SECRET_KEY` 是单独的应用账号，限定在应用桶范围。不要复用存储 root 账号。

`SKILLHUB_STORAGE_S3_ENDPOINT=http://object-storage:9000` 用于后端对象操作及 Worker 的预签名 PUT；`SKILLHUB_STORAGE_S3_PUBLIC_ENDPOINT` 用于用户下载签名。后者必须是下载方真实可达的地址；示例中的 `127.0.0.1` 仅适用于当前机器本地验证。生产环境应准备专用存储域名、TLS 与保持签名主机/路径的反向代理，不能签名后替换 URL。

新叠加配置明确启用 S3、路径寻址、关闭自动建桶；其值覆盖基础 Compose 的对应配置。其他应用字段仍来自 `.env.release`。更改配置后重建对应服务才能生效，不由后台系统配置页面管理。

两个存储 API/Console 默认仅绑定 `127.0.0.1:9000/9001`。端口冲突时调整 `OBJECT_STORAGE_API_PORT` / `OBJECT_STORAGE_CONSOLE_PORT`，同时调整下载公开端点；容器内部端口和 endpoint 仍是 9000。不要为了远程浏览 Console 将管理接口无保护地暴露到公网。

## 3. 分步初始化，避免应用先于桶启动

下面用 Silo；选择 RustFS 时，改用其 env 示例，并把下文每条命令的 `--storage silo` 改为 `--storage rustfs`。固定 `--project-name`，后续更新、状态和停机也使用相同参数。

```sh
scripts/storage-compose.sh --storage silo --env-file .env.release \
  --project-name skillhub -- up -d object-storage
scripts/storage-compose.sh --storage silo --env-file .env.release \
  --project-name skillhub -- ps object-storage
```

上述命令会创建选定产品的容器和数据卷，但不会启动整个应用栈。RustFS 会先用同一固定镜像的一次性权限容器，将数据卷根目录交给其运行身份 `10001:10001`；它无网络、不持有密钥，完成后退出，不对已有对象递归修改权限。

存储健康后，由存储管理员完成以下初始化。使用对应版本的官方 Console 或管理客户端，不使用 SkillHub 的业务进程代为创建账号：

1. 在本地 Console `http://127.0.0.1:9001` 登录存储管理员账号。
2. 创建私有桶 `skillhub`；已存在时核对，不清空、不重建桶。
3. 创建独立业务身份并授予 `deploy/storage/application-policy.json` 中的桶内权限。若修改桶名，同步修改 policy 的两个 Resource。
4. 使用业务身份的凭证填写应用的 S3 access/secret。确认匿名对象读取被拒绝，业务身份不能操作其他桶或管理账号。
5. 重复初始化只做存在性和权限核对，不自动轮换密钥、删除用户或变更已有桶的数据。

不同产品的管理 API 与 Console 功能不完全一致；不要把 MinIO 管理命令未经验证地套到 RustFS。政策中包含读写删除对象、列举桶和读取桶区域；不授予创建桶、管理用户或公开桶权限。

### 可选：用已验证管理客户端初始化

本次使用 [PGSTY mcli RELEASE.2026-09-16T00-00-00Z](https://github.com/pgsty/mc/releases/tag/RELEASE.2026-09-16T00-00-00Z) 验证了 RustFS 1.0.3 和 Silo RELEASE.2026-10-18T00-00-00Z 的账号与策略管理。客户端安装和校验按其官方发布说明进行；`mcli` 是部署管理员工具，不是 SkillHub 的运行依赖。

先将 `.env.release` 中的 root、业务账号和桶参数安全注入当前管理会话。以下命令仅用于**首次创建账号**；已有账号先查询并核对，不能重复 `user add` 来重置凭证。

```sh
umask 077
export MC_CONFIG_DIR="$PWD/.dev/object-storage-admin"
mkdir -p "$MC_CONFIG_DIR"
# 这里是管理员从宿主机访问的地址，端口需与本部署一致
mcli alias set storage http://127.0.0.1:9000 \
  "$SKILLHUB_OBJECT_STORAGE_ROOT_USER" "$SKILLHUB_OBJECT_STORAGE_ROOT_PASSWORD"
mcli mb --ignore-existing "storage/$SKILLHUB_STORAGE_S3_BUCKET"
# 首次创建之前先查询；存在时跳过 user add，核对已有身份和权限
mcli admin user info storage "$SKILLHUB_STORAGE_S3_ACCESS_KEY"
# 仅确认不存在且信息查询不是网络/权限错误时执行
mcli admin user add storage "$SKILLHUB_STORAGE_S3_ACCESS_KEY" "$SKILLHUB_STORAGE_S3_SECRET_KEY"
# 先核对 policy 的桶名和权限；同名已有策略需人工核对，不能盲目覆盖
mcli admin policy create storage skillhub-app deploy/storage/application-policy.json
mcli admin policy attach storage skillhub-app --user "$SKILLHUB_STORAGE_S3_ACCESS_KEY"
```

重复部署时，使用 `mb --ignore-existing`、`admin user info` 和 `admin policy info` 核对状态，不删除桶、不重建账号或轮换凭证。管理客户端的本地配置含管理员凭证，应保持私有；它不挂载到应用容器。

## 4. 诊断和启动应用

先将上一步的业务 S3 参数注入当前 shell。诊断从宿主机执行时，endpoint 必须改为宿主机可达的 API 地址，而非仅容器网络可见的 `object-storage`。不要把含密钥的整行命令粘贴到共享日志中。

以下仅为本机验证示例；生产诊断的公开端点应使用真实下载域名，不能把 loopback 测试当成外网连通性证明。用子 shell 限定临时地址，避免它们覆盖后续 Compose 读取的发布配置。

```sh
# 其他 S3 参数和业务凭证应通过当前会话的安全配置方式注入
(
export SKILLHUB_STORAGE_S3_ENDPOINT=http://127.0.0.1:9000
export SKILLHUB_STORAGE_S3_PUBLIC_ENDPOINT=http://127.0.0.1:9000
export SKILLHUB_STORAGE_S3_BUCKET=skillhub
export SKILLHUB_STORAGE_S3_REGION=us-east-1
export SKILLHUB_STORAGE_S3_FORCE_PATH_STYLE=true
export SKILLHUB_STORAGE_S3_DISABLE_CHUNKED_ENCODING=true
scripts/storage-diagnostics.sh
)
```

该命令使用实际 `ObjectStorageService`，写入随机 `skillhub-diagnostics/<随机值>/` 对象，检查字节、元数据、GET/PUT 签名、匿名拒绝、签名过期/篡改和删除。完成后仅删除本次创建的精确对象；失败返回非零，原始日志默认保存在 Git 忽略的 `.dev/storage-diagnostics/`，权限受 umask 077 保护。日志可能包含签名 URL，分享前必须脱敏。超时或进程被强制终止时可能有残留，使用私有日志中的前缀核查并精确清理；不要清空整个桶。

交付验收时另运行 `scripts/storage-diagnostics.sh --full`，额外创建 1001 个小对象，检查真实翻页、统计上限与跨批次删除。满批删除如遇后端短暂逐项失败，适配层必须明确报错；验收会记录失败并最多尝试 3 次调用方恢复，最终独立确认对象已经不存在，不把 HTTP 200 当成全部删除成功；该模式最多运行 420 秒。普通诊断最多 180 秒，外层强制终止后可能需要按日志核查残留。

S3 PUT 会在应用私有临时文件中暂存请求体，以支持文件流签名和重试；暂存写入保持原文件的私有权限（POSIX 环境为 0600），读取或上传失败时清理文件，不因清理失败掩盖原始错误；部署时应计入并发上传的临时磁盘预算，不能只预算对象存储的数据卷。临时文件在操作成功或失败后清理，不将整个技能包缓存到 JVM 堆中。

诊断不是应用 liveness，不要把这个有写入动作的命令接到公开健康检查。

```sh
make validate-release-config
scripts/storage-compose.sh --storage silo --env-file .env.release \
  --project-name skillhub -- up -d
```

`make validate-release-config` 校验应用发布配置，不证明存储可用；同样，存储健康不证明发布、审核和下载业务已通过。启动后还需按本次变更的业务验收矩阵验证。

### GitHub 同步

沿用[同步运行手册](github-sync-operations.md)的服务密钥和其他配置，另外设置：

```dotenv
SKILLHUB_GITHUB_SYNC_ALLOWED_S3_HOSTS=object-storage:9000
SKILLHUB_GITHUB_SYNC_STAGING_CAPACITY_LIMIT_BYTES=1073741824
```

允许列表按完整主机及端口匹配，不能只填 `object-storage`。容量按部署预算调整为正数。Worker 必须可达内部 endpoint，不能把宿主机 `localhost` 作为容器上传地址。

```sh
scripts/storage-compose.sh --storage silo --env-file .env.release \
  --project-name skillhub -- --profile github-sync up -d
```

## 5. Helm / Kubernetes

存储独立部署和维护，SkillHub Chart 不安装 RustFS/Silo 子 Chart，不在应用卸载时删除对象存储。

`deploy/storage/helm-values.example.yaml` 只包含新增接入所需字段，需要合并到已有有效 values 中。使用已有 `existingSecret` 时，Chart 不创建应用 Secret；必须保留其他应用必需键，并加入：

- `skillhub-storage-s3-access-key`
- `skillhub-storage-s3-secret-key`

它们只存业务凭证，不存对象存储 root 密钥。启用 `s3.enabled` 后，`s3.*` 决定地址和协议参数，凭证从已有 Secret 读取；`s3.accessKey` / `s3.secretKey` 留空，不把明文写入 values。变更外部 Secret 后按部署策略滚动应用。

参见 [Kubernetes 指南](skillhub/guide/kubernetes.md)。模板渲染通过与真实 K8s 连通性、对象操作、升级验收分别记录。

## 6. 更新与停机

先备份并确认目标版本的迁移要求，再更新固定镜像。通过 `RUSTFS_IMAGE` / `SILO_IMAGE` 覆盖时仍应指定经验证的不可变摘要；覆盖制品需要重新验收。

```sh
scripts/storage-compose.sh --storage silo --env-file .env.release \
  --project-name skillhub -- ps
# 停止并移除本项目容器，保留命名数据卷；不要增加 -v
scripts/storage-compose.sh --storage silo --env-file .env.release \
  --project-name skillhub -- down
```

`deploy/runtime-mirror-images.txt` 保留原有 `minio:latest` 别名。新产品采用独立版本标签；已有镜像目标会被同步脚本跳过，修改映射清单不等于更新仓库内容。实际同步属于单独的发布操作，验收应比较目标 manifest digest。

## 7. 已有数据迁移的风险与操作参考

本节不宣称生产迁移支持，也不提供自动迁移功能。现有证据仅包括独立测试桶中 RustFS → Silo → RustFS 的小样本演练；未验证旧 MinIO 或 local 到新后端的真实迁移，也未完成大规模生产数据迁移、恢复及停机窗口验收。下列步骤是安全参考，不能作为可直接执行的生产迁移方案；具体实例需要另行清点数据、制定方案并完成代表性演练。

修改 endpoint/provider 不会迁移文件。RustFS、Silo 和 local 不得默认共用原始数据目录。

1. 记录现有配置、对象清单、元数据与加密方式，备份并验证恢复。目标使用独立桶/卷和业务账号。
2. 逻辑复制对象，保留 key、Content-Type 及业务需要的元数据；记录迁移工具版本与选项。加密对象经授权解密读取后写入新服务，不能直接复制内部存储文件。
3. 用对象数量、大小和独立内容哈希校验；不能把 multipart 或加密对象的 ETag 一律当 MD5。
4. 进入维护窗口，暂停发布、头像变更/审核、Suite 写入、同步、后台删除/过期清理；排空任务。已签发 PUT 也必须等到过期或撤销旧端写权限，避免最终同步后仍写旧桶。
5. 在冻结点完成最终同步，同时处理新增、覆盖和删除差异。保留旧源只读；已签发 GET 可在有效期内继续指向旧源，应保留读取或声明短暂中断窗口。
6. 切换配置、重建受影响服务，检查下载原始字节、头像、Suite 和同步导入/清理，再恢复写入。
7. 恢复写入前可以回退配置到未变的旧源；恢复写入后，必须重新停写并协调新后端的新增、覆盖、删除差异后再回切，防止丢失新文件或恢复已删除内容。

### 小样本逻辑复制演练参考

本次用上述版本的 `mcli` 在独立测试桶进行 RustFS → Silo → RustFS 小样本演练（正向清单 134 个对象，回退清单 135 个对象），包含在线初始复制、冻结后的新增／覆盖／删除、在途 PUT 过期，以及切换后产生新数据再回退。`mirror` 会保存本次验证对象的 Content-Type 和自定义元数据；版本历史、对象锁、加密和存储策略仍需部署方分别评估。

下面的 `old`、`new` 是操作者预先配置的管理客户端别名，不是内置端点。先核对方向、桶名和私有权限，再执行；`--remove` 会删除目标中源端不存在的对象，只能在已批准的目标桶范围操作。不能把它指向尚在写入的旧源或其他业务桶。

```sh
# 初始复制；此阶段源仍可写，不作为最终一致状态
mcli mirror --overwrite --max-workers 2 old/skillhub new/skillhub
# 按前述步骤暂停应用、Worker、定时清理等全部写入，并排空已签发 PUT
mcli mirror --dry-run --overwrite --remove --max-workers 2 old/skillhub new/skillhub
# 审核删除差异后，在冻结窗口执行最终同步
mcli mirror --overwrite --remove --max-workers 2 old/skillhub new/skillhub
# 对应对象逐项核对原始字节和元数据；不要用 ETag 代替哈希
mcli stat --json old/skillhub/OBJECT_KEY
mcli stat --json new/skillhub/OBJECT_KEY
mcli cat old/skillhub/OBJECT_KEY | sha256sum
mcli cat new/skillhub/OBJECT_KEY | sha256sum
```

示例的 `OBJECT_KEY` 必须替换为实际对象键；正式迁移应对完整清单逐项自动比对，不只抽一个文件。未使用 `--preserve`，避免同时复制桶策略和锁配置；目标桶及业务权限单独初始化并保持私有。对额外业务元数据先做代表性验证。

恢复写入后的回退采用同一停写和签名排空规则，重新核对方向后先 `new/skillhub → old/skillhub` 逻辑同步及比对，再回切应用，不能直接启动旧源。旧签名 URL 不保证跨产品切换后继续有效，应保留旧读端或明确中断窗口；新签名需在切换后重新申请。

旧源按部署方备份保留政策保存，确认恢复能力后再单独授权清理。失败时保留清单与源数据，不通过删除业务数据“修复”验收。

## 8. 制品和兼容性边界

| 产品 | 固定版本 | 多架构 manifest digest | 许可证 |
|---|---|---|---|
| RustFS | 1.0.3 | `sha256:9ca9559533b537d10482fe923288b676ad08f92a7069d9a17327fbae3cab688b` | Apache-2.0 |
| PGSTY Silo | RELEASE.2026-10-18T00-00-00Z | `sha256:2636fc4155d9a7e41ff58cdb65ddd13ba9304e67184e13c25bc5cb34016269d7` | AGPL-3.0-or-later |

两者的官方 manifest 均包含 linux/amd64 和 linux/arm64；本机运行结果只证明实际执行的架构。新增 Silo 叠加文件选用上述版本；已有开发服务及 `minio:latest` 别名保持原版本，不自动升级已有实例。

本次在 linux/amd64 上使用同一应用构件完成两种产品的新部署真实契约验收；不代表任意 S3 产品、任意版本、arm64 运行、长期稳定性、大规模负载、高可用部署或生产迁移已获验证。下表只列接入和业务操作结果，不是存储产品优劣排名或生产迁移支持矩阵。

| 范围 | RustFS 1.0.3 | Silo 2026-10-18 |
|---|---|---|
| 文件流上传、字节和元数据、超过 1000 项分页和删除 | 通过；满批曾出现 `ServiceUnavailable` 逐项失败，必须由调用方处理 | 通过 |
| GET/PUT 签名、所需头、过期/篡改、匿名私有访问 | 通过 | 通过 |
| 发布审核下载、头像待审/批准/移除、Suite | 通过 | 通过 |
| Go Worker 内部端点 PUT、导入及暂存清理 | 通过 | 通过 |

RustFS 的满批临时错误不是“HTTP 200 即全部成功”；适配层会明确抛错，诊断的有界恢复只证明操作可幂等恢复，不能证明后端从不产生逐项失败。只有已接入持久化补偿的删除链路才会由现有任务重试；部分草稿删除和 Suite 暂存清理仍为 best-effort，失败仅记录日志，可能留下对象，并不保证自动恢复。部署方应监测清理失败与残留，再按业务记录核查和精确清理，不能直接清空桶。不把诊断脚本的三次恢复误认为生产适配器自动重试。

制品核验采用 Trivy 0.76.0：RustFS 镜像系统包和官方发行 SBOM（1157 个组件）均未检出 High/Critical；Silo 镜像尚有 `CVE-2026-39414` 的 legacy MinIO 模块匹配。对这一条告警，已将镜像二进制的 commit `0cd388c666f8211f552604ae73448a066e9c25ce` 与发行标签源码绑定，并核对 CSV 有界读取修复；实际普通 2 MiB 与 gzip 解压后 2 MiB 的无换行记录均被拒绝且服务存活，本地分析裁定该特定漏洞路径已修补（依据：[上游公告](https://github.com/minio/minio/security/advisories/GHSA-h749-fxx7-pwpg)、[镜像对应提交的 CSV 读取实现](https://github.com/pgsty/silo/blob/0cd388c666f8211f552604ae73448a066e9c25ce/internal/s3select/csv/reader.go)）。没有全局忽略 CVE，也不据此声称整体镜像无风险。部署时应使用自己的当前漏洞库再次扫描。

未运行真实 Kubernetes 集群、外网 TLS/代理或远端镜像仓库同步；Helm 证据限于 lint 和模板契约。旧 `minio:latest` 仍映射既有版本，其风险与升级另行管理，不能把新增 Silo 的扫描结论套给旧别名。

参考：[RustFS 官方部署资料](https://github.com/rustfs/docs.rustfs.com/blob/main/content/en/installation/container/docker.md)、[RustFS 发布](https://github.com/rustfs/rustfs/releases/tag/1.0.3)、[Silo 源仓库](https://github.com/pgsty/silo)。厂商兼容性声明不代替 SkillHub 自身验收。
