# PostgreSQL 数据库角色与权限

## Core/Demo 本地启动

从仓库根目录单独启动 Core 的数据库和基础依赖：

```sh
docker compose -f deploy/compose/compose.core.yml up -d postgres postgres-privileges valkey
```

Demo 环境在 Core 之上增加演示和可观测性服务，并启动 API：

```sh
docker compose -f deploy/compose/compose.core.yml -f deploy/compose/compose.demo.yml up -d --build
```

`postgres-privileges` 是一个每次启动都会运行的一次性服务。它从 Docker secrets 读取管理员、迁移用户和运行时用户密码，然后以数据库管理员身份执行 `init/001_roles.sql` 和 `grants/runtime-grants.sql`。服务成功结束后才会启动 `ops-api`。

首次创建 Core/Demo 数据卷时，PostgreSQL 管理员先安装 `vector` 扩展；之后 Flyway 使用 `sentinelops_migrator` 执行迁移。Flyway 迁移成功后，随 API 镜像打包的 `db/callback/afterMigrate.sql` 只为已审核的可变表授予 `UPDATE`。只有 Flyway 和 callback 均成功，API 才会通过 readiness 检查。API 的连接池只使用 `sentinelops_app`。Core-only 配置不启动 API：Core profile 需要外部执行票据签名密钥；本地 API 应通过 Demo overlay 启动，不要把临时签名密钥加入生产配置。

`demo-secrets/` 中的密码文件是公开、仅供本地 Core/Demo 使用的占位符，不可用于生产。`db-admin-password` 有意沿用此前本地 Demo 的默认值 `sentinelops-demo-db`，使已有 Core/Demo 数据卷在升级后仍可由 provisioner 认证；若旧卷使用过自定义 `SENTINELOPS_DB_PASSWORD`，管理员 secret 文件必须先匹配该现有密码。其他本地角色使用不同的占位符密码。生产部署不得引用这些文件。

管理员、迁移和运行时密码文件都必须是单行，不能包含 CR 或 LF（包括文件末尾换行）。`provision-roles.sh` 会严格拒绝带换行的 secret，不会删除或规范化字符；这可确保数据库设置的密码与 Spring configtree 从同一文件读取的内容一致。创建 secret 文件时不要追加换行。

## 托管数据库和生产部署

数据库管理员需先确认服务器已安装兼容的 pgvector 包，并使用受审查的管理员连接执行 `init/001_roles.sql`。该脚本会在执行迁移前安装 `vector`，创建或更新 `sentinelops_migrator`、`sentinelops_app`，并调用 `grants/runtime-grants.sql`。数据库管理员登录名通过 `SENTINELOPS_DB_ADMIN_USER` 指定；迁移和运行时密码通过 `SENTINELOPS_DB_MIGRATOR_PASSWORD`、`SENTINELOPS_DB_APP_PASSWORD` 从环境传给 psql。SQL 脚本不含密码字面值。

可从已批准的管理主机运行 `provision-roles.sh`（需安装 `psql`）。通过 `SENTINELOPS_DB_ADMIN_PASSWORD_FILE`、`SENTINELOPS_DB_MIGRATOR_PASSWORD_FILE`、`SENTINELOPS_DB_APP_PASSWORD_FILE` 指定外部 secret 文件；按需设置 `SENTINELOPS_DB_HOST`、`SENTINELOPS_DB_NAME` 和 `SENTINELOPS_DB_ADMIN_USER`。生产配置必须提供管理员、迁移和运行时凭据，且迁移用户与 API 运行时用户必须分离。密码文件不得含 CR 或 LF，带换行时 provisioner 会失败关闭。不要把 secret 文件内容放进 SQL、镜像或版本库。

生产 API 镜像须包含 `apps/ops-api/src/main/resources/db/callback/afterMigrate.sql`。该资源与 Flyway 迁移一起打包，不能只依赖本地 Compose 挂载。每次 API 启动时 Flyway 以 `spring.flyway.user` 执行迁移和 callback；缺少单独迁移凭据时，迁移会以运行时角色运行并因无 DDL 权限而失败关闭。应在发布检查中确认迁移用户完成 Flyway，且 readiness 在 callback 成功后才变为健康。

## 授权规则

- 旧 `public` 表、视图、序列、函数、过程和用户自定义类型转交给 `sentinelops_migrator`，使该角色可执行后续迁移。扩展拥有的对象不会被转交。
- `sentinelops_app` 在 `public` 上仅有 `USAGE`；已有和新建对象默认只得到 `SELECT`、`INSERT`。新序列只授予 `USAGE`、`SELECT`。没有默认 `UPDATE` 或 `DELETE`。
- `grants/runtime-grants.sql` 和 Flyway 的 `afterMigrate.sql` 都列出当前需更新的可变表。新增可变表时，先进行权限审查，再同时更新这两处清单。
- 迁移用户创建的非扩展函数/过程不向 `PUBLIC` 或运行时角色授予 `EXECUTE`；该角色的未来函数默认也撤销 `PUBLIC EXECUTE`。扩展拥有的函数保持原授权，以便 pgvector 运算符和检索继续工作。
- 审计、事件、证据、审批、知识和评估历史表只允许运行时读取、追加。`role_grant` 允许运行时删除已撤销授权；`webhook_replay_nonce` 虽授予 `DELETE`，但 V19 RLS 策略只允许 app 删除 `expires_at <= statement_timestamp()` 的过期 nonce，仍有效的 nonce 无法由 app 删除。迁移所有者可管理该表，app 无权关闭 RLS；其他现有或未来表没有运行时 `DELETE`。
- `sentinelops_app` 不能执行 DDL、创建角色、数据库或扩展，也不能读取 Flyway 历史表。`ops-executor` 不配置 JDBC 凭据，也不获数据库角色。

脚本可重复执行，以支持本地既有数据卷升级。对托管生产库，平台管理员仍须按变更流程审核和执行初始化/前向授权脚本；应用启动只通过随镜像携带的受审查 Flyway callback 授予明确列出的可变表更新权限。
