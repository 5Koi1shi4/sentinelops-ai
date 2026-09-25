# 依赖故障与告警风暴恢复

适用范围：SentinelOps AI 的事故接收、证据诊断、Outbox 和独立 Executor。本文中的故障注入只在隔离 Demo/测试环境使用；生产环境先确认实际依赖状态、影响范围和审批状态，再按对应步骤恢复。

## 运行与证据

在仓库根目录执行 `./scripts/fault-drill.ps1`。脚本为每轮创建独立 Compose project，脱敏后的机器报告写入 `build/verification/faults/<run-id>/report.json`。原始 k6、Compose、浏览器和 Testcontainers 日志与截图另存于本机 `build/fault-logs/<run-id>/`，可能含敏感诊断内容，分享前须单独审查。脚本结束时只清理本轮 project 的容器与卷。故障演练通过后再运行第二轮，确认不是依赖残留状态的偶然结果。

`report.json` 必须显示所有场景通过。告警风暴在 60 秒内签名发送 6,000 次请求：成功接收 6,000 次、丢失调度次数为零、HTTP 失败率低于 1%、p95 小于 500 ms；数据库中只有一个活动事故、6,000 次发生计数和 6,000 条 `alert_received` 事件。默认来源应出现带 `Retry-After` 的 429，且被限流请求不得产生事故事件。负载来源 `load-test` 的 10,000 次/分钟与突发 200 次配置只允许在 Demo profile 中启用；其他来源维持 60 次/分钟与突发 20 次。

## PostgreSQL 不可用

**预期状态：**签名 Webhook 返回 503，客户端不得把请求记为已接收；事故和事件不得凭空出现。恢复数据库连接与应用健康后，使用原 `X-SentinelOps-Event-Id` 和新的签名 nonce 重投不确定结果的告警。服务端会按来源与事件 ID 去重。不要在数据库中手工插入事件或改写事故状态。

检查连接、Flyway 版本和应用数据库角色，再核对 `incident_event` 中的来源事件 ID。若请求曾超时而不是明确返回 503，先查询是否已落库，再使用同一事件 ID 重投；不要改用新事件 ID 造成重复发生记录。

## Valkey / Redis Streams 不可用

**预期状态：**PostgreSQL 中的业务命令和 Outbox 仍然持久化，Outbox 保留未发布记录；Executor 不得绕过 Outbox 直接执行动作。恢复 Valkey 后观察 `outbox_event.published_at`，应逐步从空值变为已发布时间，并核对对应 `execution_attempt` 与目标系统的副作用计数。

先修复网络或 Valkey 服务，再让现有 Relay 按有界退避继续投递。若事件进入隔离状态，记录原因并人工核对执行是否已经发生；不要通过修改 `published_at`、清空 Stream 或反复创建 execution 来“补偿”。

## Executor 停止或崩溃

**预期状态：**审批完成后的 execution 可以保持 `pending`，无执行尝试和目标副作用。Executor 恢复后只能凭有效票据和 fencing token claim；同一执行步骤的副作用计数必须为一，旧 owner 的迟到结果不得覆盖新 claim。事故只能在预声明的健康验证通过后进入 `resolved`。

恢复 Executor 前，检查 execution 的审批、Runbook 版本、目标和租约。若执行中断时目标动作结果不可判定，保留 `unknown` 并升级人工核查；不要自动重做无法证明幂等的动作。恢复后核对 `execution_attempt`、目标系统副作用指标、`verification_attempt` 和事故事件时间线。

## 模型、Loki 或遥测不可用

模型超时应记录 `MODEL_TIMEOUT`，诊断运行失败，事故留在人工分诊状态，不得生成建议或执行记录。Loki 不可用时不能伪造缺失日志证据，也不能据此提出不安全操作。恢复数据源后由有权限的操作员重新发起诊断，使用新的幂等键和当前事故版本。模型输出若含凭据样式内容，`MODEL_OUTPUT_SENSITIVE` 会拒绝整份建议；不要从原始输出复制内容到人工建议。

OTLP 收集器不可用不应使已认证告警丢失或收到虚假的失败回执。先确认事故已在 PostgreSQL 持久化，再恢复收集器并查看导出错误率。不要为排错打开完整 prompt、原始日志正文、Token 或票据的默认日志记录。

## 演练失败时

先查看本轮 `report.json` 与本机 `build/fault-logs/<run-id>/` 中的对应场景日志，按失败阶段区分“故障注入未成功”“断言发现业务缺口”“测试环境未就绪”。保留红灯证据，修复根因后重跑完整脚本两次。不要放宽 6,000 次发生事件、单次副作用、无越权工具、无凭据回显或无虚假 `resolved` 等断言。
