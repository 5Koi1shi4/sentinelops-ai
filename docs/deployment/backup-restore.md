# 数据库备份与恢复演练

本文的脚本只处理 SentinelOps PostgreSQL 数据库。备份产物包含业务数据和凭据相关审计信息，必须按组织的加密、访问控制和保留策略保护。

## 前置条件和权限

- PowerShell 7、PostgreSQL 客户端工具（pg_dump、psql、pg_restore）和 Java 21 JDK。Windows 使用 `mvnw.cmd`；Linux/macOS 使用 `bash` 和仓库的 `mvnw`。
- PostgreSQL 客户端和服务器版本应与部署版本兼容；生产基线为 PostgreSQL 17，并安装 pgvector。
- 备份身份需要连接指定数据库、读取 public 下所有要备份的表和序列，以及读取 flyway_schema_history。建议由 DBA 提供专用只读身份。PostgreSQL 15 及以上可通过 pg_read_all_data 授予关系读取权限；该角色不会绕过行级安全策略。
- 恢复验证身份需要连接 TestDatabaseUrl 中已有的控制数据库，并有 CREATEDB 权限。恢复归档可能创建扩展；隔离测试实例应预装 vector 所需文件，并使用获准的管理身份。脚本不在控制数据库中执行 DDL。
- restore-check.ps1 用平台对应的 Maven Wrapper 离线解析 ops-api 的 Flyway 和 PostgreSQL JDBC 运行时依赖。相应 Maven 依赖必须已在本机缓存中；缺少缓存会失败，不会访问 Maven 仓库。

将密码交给当前进程环境或组织的密钥管理工具。不要把含密码的 URI 写入脚本、文档、提交或命令历史。URI 中用户名和密码里的保留字符必须百分号编码。脚本将连接信息放入进程环境供 PostgreSQL 客户端和 Flyway 使用，不把密码作为子进程参数或输出内容。

## 创建备份

数据库 URI 和输出目录都必须显式提供；脚本没有生产默认目标。备份根目录应由运维人员预先创建或确认，并设置为仅授权备份操作者可读写的位置：

    if (-not $env:SENTINELOPS_BACKUP_DATABASE_URL) {
        throw 'Set SENTINELOPS_BACKUP_DATABASE_URL from the approved secret source first.'
    }
    if ([string]::IsNullOrWhiteSpace($env:SENTINELOPS_BACKUP_DIR)) {
        throw 'Set SENTINELOPS_BACKUP_DIR to an approved, access-controlled backup directory.'
    }
    .\scripts\backup.ps1 -DatabaseUrl $env:SENTINELOPS_BACKUP_DATABASE_URL -OutputDirectory $env:SENTINELOPS_BACKUP_DIR

URI 示例格式为 postgresql://<user>:<percent-encoded-password>@<host>:5432/<database>?sslmode=require。先从批准的密钥来源设置 SENTINELOPS_BACKUP_DATABASE_URL；不要将示例占位符当作可用凭据。

成功后，脚本在指定目录创建 UTC 时间戳 .dump（PostgreSQL custom archive）和相邻的 .dump.manifest.json。归档使用 pg_dump --format=custom --no-owner --no-acl。清单记录归档文件名、SHA-256、生成时间、数据库名和最新成功的版本化 Flyway 迁移版本，不记录主机、用户名或密码。

`pg_dump` 先写入唯一 `.partial-<GUID>` 文件，完成后以不覆盖的文件移动发布到 UTC 时间戳 `.dump` 名称；如果目标已存在，发布失败且不会覆盖旧文件。临时清单也使用 CreateNew 语义。pg_dump 失败后可能留下没有有效清单的 `.partial-<GUID>` 文件；它不是有效备份。脚本保留该文件供操作者检查，不会自动删除或轮换任何备份。

定期安排备份并在存储层加密，限制访问，并按数据分类要求设置保留期限。脚本本身不加密、不上传、不清除旧文件，也不提供 WAL 归档或时间点恢复。

## 恢复验证

准备一个独立的 PostgreSQL 测试实例和一个已经存在且已应用 Flyway 迁移的控制数据库。不要把 TestDatabaseUrl 指向生产实例。把测试实例的 URI 放入环境变量后执行：

    if (-not $env:SENTINELOPS_RESTORE_TEST_URL) {
        throw 'Set SENTINELOPS_RESTORE_TEST_URL to an isolated test PostgreSQL instance first.'
    }
    .\scripts\restore-check.ps1 -TestDatabaseUrl $env:SENTINELOPS_RESTORE_TEST_URL

省略 `-BackupFile` 时，脚本会先仅从该显式测试数据库生成一个新备份，再恢复到同一实例的新临时数据库并验证。备份输出根目录优先取 `$env:SENTINELOPS_BACKUP_DIR`；未设置时使用当前用户的系统临时目录下 `sentinelops/backup-restore`（Unix 遵循 `$TMPDIR`）。每次执行创建独立的 `restore-check-<UTC 时间>-<GUID>` 子目录，不复用目录，也不从生产配置推断源数据库。Linux/macOS 将新目录权限设为 `0700`；Windows 继承临时目录或受保护备份根目录的 ACL。命令输出会记录该备份的完整路径。

若要演练显式备份文件（例如经批准的生产备份副本），将它传给 `-BackupFile`；目标仍必须是隔离测试实例：

    .\scripts\restore-check.ps1 -BackupFile (Join-Path $env:SENTINELOPS_BACKUP_DIR 'sentinelops-<timestamp>.dump') -TestDatabaseUrl $env:SENTINELOPS_RESTORE_TEST_URL

脚本在开始连接前验证清单和归档 SHA-256，并检查 pg_restore 能读取归档。它拒绝主机名中包含 prod、production、prd 或 live 独立片段的目标；传入 -AllowProductionRestoreTarget 才会越过此名称检查。这个开关不会让现有数据库成为恢复目标，也不会给生产使用背书：脚本仍连接该主机并创建临时数据库，因此不要用它连接真实生产集群。

在目标集群中，脚本生成 sentinelops_restore_check_<GUID> 名称，先确认该名称不存在，再创建全新的 template0 数据库。它只对该临时数据库运行 pg_restore --no-owner --no-acl --single-transaction，不使用 --clean 或 --create，也不连接归档里记录的原数据库。随后它调用项目 ops-api 实际依赖的 Flyway API 执行 validate()，使用仓库中的 db/migration 目录，并运行只读的核心表、Flyway 成功状态和迁移版本查询。

finally 只在本次 CREATE DATABASE 成功、临时数据库名称符合生成格式、且数据库目录中的 OID 仍与本次记录一致时删除该临时库。身份无法确认或删除失败会报告清理失败并留下临时库供操作者检查；脚本不会根据名称猜测并删除其他数据库。用于临时 Java/Flyway 验证的文件写入系统临时目录，并在路径确认属于该临时目录后清理。显式和自动生成的备份归档及清单会被脚本保留；脚本不会轮换或主动删除它们。系统临时目录可能由操作系统定期清理，需长期保留的演练产物应配置 `$env:SENTINELOPS_BACKUP_DIR` 指向受控存储，或在验证后按组织的数据保留与删除流程管理。

## 清理、保留和演练边界

- 脚本不会删除控制数据库、原始数据、其他数据库、备份文件或同目录旧产物。
- 自动生成的恢复演练备份保留在已打印的唯一子目录；默认位于当前用户的系统临时目录，Linux/macOS 使用 `0700`，Windows 继承该临时目录 ACL。配置 `$env:SENTINELOPS_BACKUP_DIR` 时，Linux/macOS 使用 `0700`，Windows 继承配置根目录 ACL。仅授权人员应可读取其中的明文数据库归档；临时目录可能被系统清理。
- 恢复演练会在显式提供的 PostgreSQL 集群创建并删除唯一临时数据库；在目标集群保留备份数据的成本和审计日志应纳入演练记录。
- 如果脚本因连接失败、缺少工具、缺少离线依赖、权限不足、归档损坏或 Flyway 校验失败而中止，它会返回明确错误。若数据库已创建且身份可验证，finally 会尝试删除它；若仍有 sentinelops_restore_check_* 数据库，先核对其 OID 与本次日志，再由授权数据库管理员处理。
- 生产备份的访问权限、加密密钥轮换、异地副本、保留期限、删除审批、RPO/RTO 和恢复演练频率由部署方另行制定。一次本地恢复演练不能代替高可用、PITR 或跨区域灾难恢复验证。
