# 生产 Compose 部署

此配置部署单组织 SentinelOps 控制平面、Executor、控制台与 Caddy。正式放行需逐项满足[上线条件](go-live-checklist.md)。默认连接组织提供的 PostgreSQL 17/pgvector、Valkey、OIDC、Prometheus、Loki 和模型服务。`local-data` 仅供明确选择的单机部署；它不提供数据库高可用。没有外部域名、身份提供方、目标服务和密钥时，`docker compose config` 只能验证配置结构，不能证明生产烟测通过。

## 前置条件

- Linux 主机、Docker Engine/Compose v2、PowerShell 7、可解析到该主机的域名，以及可用的 80/443 TCP 和 443 UDP。Caddy 自动申请证书需要 DNS 和入站网络满足证书颁发方要求。
- PostgreSQL 17 且安装 pgvector；独立的迁移角色与最小权限应用角色。外部 PostgreSQL 的 JDBC URL 必须设置 `sslmode=verify-full` 并提供可信证书链；外部 Valkey 必须启用 TLS。本地 `local-data` 的 `postgres`/`valkey` 服务名可在内部网络使用明文，且不发布宿主端口。参照 [数据库角色脚本](../../deploy/postgres/provision-roles.sh) 审查实际授权。Executor 不取得业务数据库凭据。
- 组织 OIDC issuer/JWKS/token URL 均使用 HTTPS；浏览器客户端登记精确 `https://<domain>/auth/callback`，使用 Authorization Code + PKCE；Executor 使用独立 client credentials 客户端，仅获内部执行 API 和已登记目标所需 scope。将 `sentinelops-api`、`sentinelops-executor` 的 audience 和服务范围角色映射到实际身份系统。
- Prometheus/Loki 已提供受限只读查询入口。模型使用经批准的 OpenAI-compatible 服务，或选择 `manual-only` 降级。没有真实模型时不要设置 `deterministic` 生产提供方。
- 每个分离的密码、Webhook HMAC、执行签名私钥 JWK 和模型密钥写入宿主机受保护的单独文件。文件归部署方密钥管理；不得提交到仓库或写入 Compose CLI 参数。生产密钥轮换见 [运维手册](operations.md)。
- 预先创建 `SENTINELOPS_EXECUTOR_EGRESS_NETWORK` 指向的 Docker 网络，并在宿主防火墙/网络策略中限制 Executor 仅访问 OIDC token、所选 Valkey 和目录中的目标地址。Compose 网络成员资格不能约束同一出口网络的具体目标 IP。
- 按 [适配器安全配置](../runbooks/adapter-security.md)创建受审核的 HTTP action catalog。首条可发布动作固定为 `production-http/restart_service`，目标别名 `checkout`，`replicas` 为 1–3；`SENTINELOPS_PROD_CHECKOUT_HEALTH_URL` 必须指向该目标的固定 HTTPS 健康 JSON，成功响应含 `{"status":"UP"}`。

## 准备配置

复制 [生产环境示例](../../deploy/env/production.env.example)到仓库外的受控位置，逐项替换域名、账号、URL、目录与 secret 文件路径。示例主机名和凭据路径只是占位符。Caddy 的 data/config 目录需预先存在，并由容器 UID `10001` 写入；其证书状态必须持久化和备份。

生产 `ops-api` 通过只读 Docker secrets/config tree 读取 `spring.datasource.password`、`spring.flyway.password`、`spring.data.redis.password`、执行签名 JWK、Webhook HMAC 和模型密钥。Executor 只接收 Valkey ACL 密码与独立 OAuth 客户端密钥，并只读挂载 HTTP 目录。`production` profile 在启动时验证 HTTPS OIDC/CORS、非默认数据库密码、外部数据连接 TLS、签名密钥引用和真实或人工模型模式；失败会阻止启动。外部 PostgreSQL URL 禁止 `NonValidatingFactory`、自定义主机名验证器和 URL 内联账号密码；示例中的 `DefaultJavaSSLFactory` 使用 Java 信任库。组织私有 CA 不在默认信任库时，应在受控镜像或只读信任库中导入，不要改为不验证证书的连接模式。[pgJDBC SSL 说明](https://jdbc.postgresql.org/documentation/ssl/)和[Spring Redis SSL 说明](https://docs.spring.io/spring-boot/reference/data/nosql.html)列出相应设置。

部署方用操作系统或密钥管理器的密码学随机数生成器创建各不相同的数据库、Valkey、Webhook 和客户端秘密；Webhook HMAC 至少 32 字节，不复用登录密码。执行签名文件必须是带 `kid` 的 RSA-3072 或更强私有 JWK，算法为 `RS256`，而不是 PEM 文本或只有公钥的 JWKS。生成和交付由组织密钥系统完成，先在隔离环境验证解析、签票与 Executor 公钥验票，再投入运行；不要把私钥转换过程或明文写进 shell 历史。迁移账号只用于 API 启动时的 Flyway 阶段，业务账号用于运行连接；升级应先在预生产运行一次受控迁移任务并检查 Flyway，再让新的 API/Executor 接管流量。Compose 本身没有独立的迁移 Job 编排和自动回滚。

在仓库根目录先检查配置。下面的 `ENV_FILE` 必须是仓库外的实际绝对路径：

```powershell
$env:ENV_FILE = '/etc/sentinelops/production.env'
docker info --format '{{.ServerVersion}}'
docker compose --env-file $env:ENV_FILE -p sentinelops-production `
  -f ./deploy/compose/compose.production.yml config --quiet
./tests/production/compose-policy.ps1
```

`config` 可以发现缺失的 `${NAME:?message}` 值。不要使用 `config` 的完整输出分享故障记录，因为它会展开 URL、文件路径与部分构建参数。

## 启动与最小暴露面

外接数据服务时：

```powershell
docker compose --env-file $env:ENV_FILE -p sentinelops-production `
  -f ./deploy/compose/compose.production.yml up -d --build --wait
```

选择 `local-data` 时，先启动 `postgres`，运行一次 `postgres-privileges` 建角色，再启动其他服务；对应 env 的 DB/Valkey 地址要指向 Compose 服务名。此流程使用受保护的本地密码/ACL 文件，不开放 PostgreSQL 或 Valkey 宿主端口。

```powershell
$compose = @('--env-file', $env:ENV_FILE, '-p', 'sentinelops-production',
  '-f', './deploy/compose/compose.production.yml', '--profile', 'local-data')
docker compose @compose up -d --wait postgres valkey
docker compose @compose run --rm postgres-privileges
docker compose @compose up -d --build --wait ops-api ops-executor web caddy
```

仅 Caddy 发布 80/443；`/healthz` 映射到 API readiness。公网 `/actuator*`、`/internal/*` 与 Demo checkout 路径返回 404。其余浏览器 API 经控制台 Nginx 的 `/api/` 反代进入 API。Caddy 只在 TLS 站点设置 HSTS。生产组合中没有 Demo 服务、故障注入入口、Demo Keycloak、固定测试账户或宿主 Docker socket。

## 首次核验

1. 在主机内核对 API/Executor/Web/Caddy health，确认 Caddy 获得有效证书、`https://<domain>/healthz` 返回 `UP`。检查 OIDC 的 issuer、audience、浏览器回调和 Executor 客户端授予。
2. 以 observer、operator 和独立 approver 的真实身份核对服务范围与职责分离；用无权限身份验证拒绝。Webhook 来源签名与防重放配置参照 [Webhook 接入](../runbooks/webhook-ingestion.md)。
3. 通过受控源提供生产烟测凭据和签名源，运行 `./scripts/smoke.ps1 -Profile production`。烟测会发送一条带唯一指纹的合成签名告警，因此应在可接受的测试服务范围内运行，并检查它没有启动执行或误报恢复。
4. 运行 [备份恢复演练](backup-restore.md)，再按 [升级手册](upgrade.md)建立变更和回退基线。

不要在未经授权的环境直接运行烟测或创建公网资源。当前仓库交付的是配置与验证脚本；外部部署需要目标主机、域名、OIDC 客户端、数据库、密钥与目标服务的实际资料。
