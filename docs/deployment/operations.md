# 生产运维与事件响应

## 例行检查

每天检查 Caddy TLS 证书续期、`/healthz`、API/Executor readiness、PostgreSQL 连接池、Valkey Stream backlog、Outbox 待发布数、执行租约、`execution_outcome_unknown`、恢复验证失败和审计写入。Prometheus/Loki 与模型服务不可用时，诊断应返回受控降级或转人工；事故和审批的真相以 PostgreSQL 为准。

[生产 Compose](production-compose.md)为 API 设置 2 CPU/2 GiB、Executor 1 CPU/1 GiB、Web 与 Caddy 各 0.5 CPU/256 MiB；本地数据 profile 的 PostgreSQL 为 2 CPU/2 GiB、Valkey 为 1 CPU/512 MiB。这些是首版容器上限，实际容量要按告警突发、保留期、查询并发和恢复演练结果重新测定。不要用本地 6,000 次告警压测推断公网容量。

备份应由组织调度器按 RPO 定期运行，并将归档和 SHA-256 清单加密、限制访问、异地保留；按约定频率用独立测试 PostgreSQL 做 [恢复演练](backup-restore.md)。脚本没有自动轮换、PITR 或跨区域复制。Caddy `/data` 和 `/config` 的证书状态也要纳入受保护备份。

## 密钥和身份轮换

执行签名 JWK 当前按单活密钥读取。轮换前暂停新的执行，等待领取/租约结束并人工核对未知结果；替换只读 secret 文件、重启 API，并使 Executor 刷新 JWKS。不要在仍有有效票据时直接丢弃旧公钥。Webhook 来源当前每个来源只解析一份 HMAC 秘密，需与 Alertmanager/中继安排短切换窗口，验证新签名和重放拒绝后再撤销旧值。OAuth Executor 客户端密钥、模型密钥、数据库和 Valkey 密码分别轮换，最小权限不变；替换文件后重启读取该 secret 的服务并测试权限边界。轮换日志只记录密钥标识、时间与结果，不记录秘密正文。

定期复查 OIDC issuer/audience、浏览器回调、角色与服务范围；离职或撤权应立即使新命令不可用。生产 HTTP action catalog、目标 OAuth scope 和 Executor 出口 ACL 必须共同审查。目录变更后重启 Executor，并重新发布/审批对应不可变 Runbook 版本；不能直接修改数据库绕过治理。

## SentinelOps 自身故障

- **API/数据库不可用：**暂停新命令和告警重放，恢复数据库后先核对 Flyway、Outbox 和只追加审计，再恢复 Executor。不要把 Valkey 当作事故最终状态。
- **Valkey/Executor 中断：**保留 PostgreSQL execution/Outbox，恢复后让消费者从持久状态核对并领取。已分发但未确认的生产 HTTP 动作保留人工升级；不可自动重试未知副作用。
- **OIDC 异常或越权迹象：**停止新的审批/执行入口，检查 issuer、JWKS、client audience 与服务范围映射，保全审计和访问日志。重新启用前用 observer/operator/approver 身份验证拒绝与职责分离。
- **Webhook 噪声或伪造：**检查 HMAC、时间窗、nonce、来源限额和相同指纹的 occurrence 数；必要时在来源/入口限流。不得删除事故事件来掩盖突发。
- **模型或监控来源异常：**切到已批准的 `manual-only` 模式或让诊断返回明确不可用，继续保留人工流程；不启用生产 deterministic 模型，也不把模型结论当作恢复证明。
- **疑似重复副作用：**立即停止该目标的新执行，保存 execution ID、step ID、fencing token、目标幂等记录与审计，人工核对目标后决定修复。不要直接改写 execution 状态或重投消息。

每次事件结束后记录检测、确认、恢复时间，标出缺失或污染的证据，更新 Runbook 和 Eval 版本。参照 [升级手册](upgrade.md)处理有迁移或镜像变更的恢复。
