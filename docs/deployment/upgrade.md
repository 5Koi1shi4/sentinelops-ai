# 生产升级与回退

本手册适用于 [生产 Compose](production-compose.md)。升级前必须取得维护窗口、数据库管理员和业务值班人的确认，核对当前镜像版本、Flyway 版本、开放事故和运行中 execution。Compose 是单机部署方案，不保证无中断滚动发布；需要跨主机高可用时应使用有滚动与网络策略能力的编排平台。

## 升级前

1. 在隔离环境运行 `./scripts/verify.ps1 -Release`、`./scripts/security-scan.ps1`、`./scripts/fault-drill.ps1`、`./scripts/restore-check.ps1 -TestDatabaseUrl $env:SENTINELOPS_RESTORE_TEST_URL`，再对已授权的预生产 HTTPS 入口运行 `./scripts/smoke.ps1 -Profile production`。检查所有报告是本次版本、完整且成功。
2. 保存当前 `docker compose ... config --quiet` 所用的仓库 commit、镜像 digest、env 文件修订号、HTTP action catalog 修订号、OIDC 配置和密钥引用；不要把秘密值写入变更单。
3. 对业务数据库运行 [备份脚本](backup-restore.md)，记录 SHA-256 清单，并在独立测试实例对该归档执行显式 `-BackupFile` 恢复演练。只有备份和 Flyway validate 都通过才进入切换。
4. 检查迁移脚本从当前 Flyway 版本向前连续、没有删除或重写历史表；检查现有版本能否读取升级后 schema。若不兼容，安排短维护窗口并停止新审批/执行。任何未知执行结果先人工核对目标。

## 受控切换

先将 Executor 停止领取新任务，等待已领取任务完成或按 [运维手册](operations.md)记录并处理其未知结果。升级期间保留 PostgreSQL、Valkey 和持久化的 Caddy 证书卷。由 DBA 先创建/审查本次迁移所需角色和权限；`ops-api` 使用单独的迁移账号执行 Flyway，业务连接使用应用账号。

在仓库根目录以仓库外的 env 文件执行：

```powershell
$compose = @('--env-file', $env:ENV_FILE, '-p', 'sentinelops-production',
  '-f', './deploy/compose/compose.production.yml')
docker compose @compose config --quiet
docker compose @compose pull
docker compose @compose up -d --no-deps --wait ops-api
docker compose @compose up -d --no-deps --wait web caddy
docker compose @compose up -d --no-deps --wait ops-executor
```

若使用本地构建的离线镜像，先按已验证的版本构建/加载镜像，再运行相同顺序的 `up`；不要用未经验证的浮动标签。`pull` 仅适用于已经由部署方批准并上传的镜像仓库。每步确认 readiness、Flyway 成功、OIDC 登录、控制台 API 和 Executor 领取能力。若使用单实例 Compose，`up` 会重建服务并可能短暂中断；需要严格滚动时在外部编排器中逐个替换副本并保持兼容窗口。

切换后运行生产烟测，核对一条唯一指纹告警、鉴权、只读诊断/人工降级、越权拒绝和 Demo 入口缺席。监看出站模型/证据源的失败率、Outbox backlog、Stream 消费延迟、租约回收、未知执行结果、验证成功率以及审计写入。保存本次 `build/verification/` 报告。

## 失败回退

应用或配置失败时，停止新的 Executor 领取，回到已保存的镜像 digest 和配置，再验证 API/浏览器/只读查询。Flyway 迁移只向前；不能简单把数据库降版或恢复旧程序并假定 schema 兼容。若旧程序与新 schema 不兼容，应保持维护窗口，修复向前迁移或在 DBA 批准后从升级前备份恢复到隔离环境评估数据损失，再执行正式恢复方案。不要在生产库上运行 `restore-check.ps1` 或手工改写事故/审计历史。

如果动作已经分发而结果未知，系统会升级人工处理。先查询目标的幂等键/fencing 记录和真实状态，再决定是否允许新的人工审批；不要重投相同动作来试探。记录开始/结束时间、Flyway 版本、镜像 digest、恢复验证及实际 RPO/RTO。
