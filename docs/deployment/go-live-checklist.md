# 正式上线放行条件

本清单把“正式上线”定义为：在单一组织的真实环境中，向值班人员开放生产控制台、接入真实告警与证据源，并允许经独立审批后对登记的 `checkout` 目标执行首条 `production-http/restart_service` Runbook。仅完成本地 Demo、配置渲染或只读试运行，不等于该范围已上线。

放行规则：下列 G1–G8 必须针对同一提交、镜像和目标环境留存可审查的通过证据。任何必需资源、权限、安全边界或恢复验证缺失时，结论为 **NO-GO**。部署负责人、业务值班负责人、身份/安全负责人和 DBA 应记录各自的审查结论；证据只记录版本、摘要、脱敏报告位置和审批记录，不记录密码、Token、私钥或完整环境文件。

## 当前判定（2026-09-28）

NO-GO。代码已达到本地发布候选：`verify.ps1 -Release`、完整安全扫描和故障演练的本地记录见[生产加固进度](../runbooks/stage-2b-progress.md)；基线提交 `97dca14` 的 [GitHub CI](https://github.com/5Koi1shi4/sentinelops-ai/actions/runs/36291911314) 已通过。后续提交仍须核对其自身的 CI 结果。

目前尚未取得真实生产主机/域名、组织 OIDC 客户端、受控密钥、实际 `checkout` 目标及独立恢复测试数据库，因此无法完成目标端契约、真实备份恢复、生产浏览器烟测和切换后验收。尚未创建 `v1.0.0` 标签。剩余技术边界见[已知限制](../known-limitations.md)。

## G1：部署范围与责任

- [ ] 部署方确认单组织范围、值班/审批/DBA/安全责任人、维护窗口、事件升级路径、数据保留期，以及可接受的 RPO、RTO 和服务中断时间。
- [ ] 确认使用[生产 Compose](production-compose.md)的单主机能力是否满足上述目标。若要求跨主机高可用、零中断滚动、PITR 或异地容灾，先由部署方补齐对应平台和演练；现有 Compose 不提供这些能力。
- [ ] 明确真实告警来源、可查询的 Prometheus/Loki 范围、首个执行目标和可执行时段。生产组合不得启用 Demo 服务、故障注入、默认账号或确定性模型。

## G2：主机、数据与网络

- [ ] 提供 Linux 主机、Docker Engine/Compose v2、PowerShell 7、可解析域名和证书签发所需的入站网络。仅 Caddy 发布 80/443；有效 TLS 证书、`/healthz` 和各服务 readiness 均通过。
- [ ] PostgreSQL 17 安装 pgvector，迁移角色和业务角色分离，应用角色为最小权限；外部 JDBC 使用 `sslmode=verify-full` 和可信 CA。外部 Valkey 使用 TLS/ACL。Executor 不持有业务数据库凭据。
- [ ] Executor 使用专用出口网络，并由宿主或云网络策略限制到 OIDC token、选用的 Valkey 和登记的目标地址；Docker 网络成员资格本身不算目标级 ACL。
- [ ] Prometheus/Loki 提供受限只读访问。若上游需要认证，先配置受控只读代理或新增经测试的认证方案，不把凭据写进 URL。

## G3：身份、密钥与权限

- [ ] 企业 OIDC issuer、JWKS 和 token 端点均为 HTTPS。浏览器客户端使用 Authorization Code + PKCE，回调精确为 `https://<domain>/auth/callback`；Executor 使用独立 client credentials、独立 audience 和最小 scope。
- [ ] 用真实 observer、operator、独立 approver 和无权限身份验证角色映射、拒绝访问、撤权生效及请求者不能单独批准。签名 Webhook 来源通过 HMAC、时间窗与重放检查。
- [ ] 数据库、Valkey、Webhook、OAuth、模型密钥及带 `kid` 的 RSA-3072 或更强私有 JWK 由组织密钥系统交付为仓库外的受限只读文件；各密钥不同，Webhook HMAC 至少 32 字节。验证签票、公钥验票和[轮换流程](operations.md)，不得把真实值提交 Git、写入 CLI 参数或打印在 CI 日志中。

## G4：模型、证据与执行目标

- [ ] 选择已批准的真实模型提供方并验证权限、成本/延迟和 Eval，或明确使用 `manual-only`；生产不得使用 `deterministic`。验证证据查询权限、脱敏及模型/证据源故障时的人工降级。
- [ ] 由运维审查只读挂载的 [HTTP action catalog](../runbooks/adapter-security.md)、目标 OAuth scope 和出口 ACL。首条动作仅为 `production-http/restart_service`，目标别名 `checkout`，`replicas` 为 1–3；固定 HTTPS 健康端点返回 `{"status":"UP"}`。
- [ ] 在受控目标上证明 `Idempotency-Key` 与 `X-SentinelOps-Fencing-Token` 的验证和持久化，并执行一次独立审批的受限 canary：核对审批快照、Runbook checksum、一次目标副作用、审计及客观健康验证。若目标契约无法证明，不放行生产执行能力。已分发但结果未知的生产动作仍须人工核对，不能自动重放。Kubernetes 动作不在首版发布白名单。

## G5：数据保护与恢复

- [ ] 以[备份脚本](backup-restore.md)对实际业务库生成 custom 归档和 SHA-256 清单，确认备份身份、加密、访问控制、保留及异地副本符合组织策略。
- [ ] 使用**独立 PostgreSQL 17 测试实例**和显式 `-BackupFile` 恢复该备份副本，验证 SHA-256、`pg_restore`、Flyway 及关键业务/审计数据，记录实测 RPO/RTO 和临时库清理结果。不得把真实生产库作为 `restore-check.ps1` 的目标。
- [ ] 审查迁移顺序、最小权限、Caddy 证书状态备份及[升级/回退步骤](upgrade.md)。数据库迁移只向前；不能把旧镜像回切视作自动数据库回滚。

## G6：同一版本的自动与人工验收

- [ ] 对拟发布提交运行 `./scripts/verify.ps1 -Release`、`./scripts/security-scan.ps1` 和 `./scripts/fault-drill.ps1 -Scenario full`，全部退出码为 0，并核对 Java/前端、真实 PostgreSQL、Eval、Playwright、漏洞/密钥、告警风暴、重复投递及故障恢复报告完整。
- [ ] GitHub `main` 上同一提交的 CI 为成功；任何后续代码或配置变更都需重新运行。不得用本地历史报告代替该提交的远端结果。
- [ ] 在获授权的预生产 HTTPS 入口提供受限测试身份及签名来源，运行 `./scripts/smoke.ps1 -Profile production`；核对登录、权限拒绝、合成告警、只读诊断/人工降级、Demo 入口缺席，且烟测未误触执行或误报恢复。正式切换后按变更窗口再运行受控烟测。
- [ ] 在目标环境按 G4 执行真实目标 canary，并验证异常时停止新领取、未知结果人工核对和可观测性告警。生产烟测脚本本身不覆盖这一执行闭环。

## G7：运行与回退准备

- [ ] 配置并实测[运维手册](operations.md)中的证书续期、API/Executor、数据库、Valkey Stream/Outbox、执行租约、未知结果、审计和恢复验证监控与告警；明确值班接收人和升级时限。
- [ ] 根据真实告警突发、保留期与查询并发做容量评估。隔离环境的 6,000 次告警风暴结果不能直接作为公网容量结论。
- [ ] 记录当前 commit、镜像 digest、Flyway 版本、受审查的目录/配置修订及密钥引用；演练停止 Executor 新领取、应用回退与数据库前向修复/受控恢复决策。不存在自动回滚时须明确维护窗口和人工处置人。

## G8：版本放行与切换

- [ ] G1–G7 对拟发布版本全部通过后，由负责人批准创建 `v1.0.0` 等与 Maven、前端及锁文件精确匹配的标签；不移动或复用已发布标签。
- [ ] 配齐[发布工作流](../../.github/workflows/release.yml)所需的 GitHub vars：`SENTINELOPS_WEB_URL`、`SENTINELOPS_PROD_OIDC_ISSUER`、`SENTINELOPS_PROD_WEBHOOK_SOURCE`、`SENTINELOPS_OIDC_BROWSER_CLIENT_ID`、`SENTINELOPS_OIDC_REDIRECT_URI`；secrets：`SENTINELOPS_RESTORE_TEST_URL`、`SENTINELOPS_PROD_OPERATOR_TOKEN`、`SENTINELOPS_PROD_OBSERVER_TOKEN`、`SENTINELOPS_PROD_WEBHOOK_SECRET`、`SENTINELOPS_PROD_SERVICE_KEY`。Runner 必须能安全访问独立恢复实例和获授权的烟测入口；这些值不得写入仓库。
- [ ] 标签触发的 Release CI 全部通过，核对不可变镜像归档、Java/Node SBOM、最终归档扫描报告和 `SHA256SUMS`；部署方再按批准的 digest 分发并切换。该工作流**不**推送镜像、创建 GitHub Release 或部署服务。
- [ ] 切换后确认 TLS、登录、只读证据、审批/执行边界、受控烟测、监控和备份调度均正常，记录放行人、时间及回退决策。任一阻断项失败即停止上线或停止新的生产执行，按[升级手册](upgrade.md)处置。

## 放行记录

每次发布在组织变更单中记录：Git commit 与 tag、镜像 digest、目标环境、G1–G8 的责任人和脱敏证据链接、外部恢复演练的实测 RPO/RTO、生产烟测与 canary 结果、已知剩余风险及 Go/No-Go 结论。不要把真实配置、数据库归档、Token 或私钥附在公开仓库工单中。
