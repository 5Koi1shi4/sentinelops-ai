# 八分钟演示脚本

前提：在隔离电脑运行 `./scripts/demo.ps1`，打开 `http://localhost:4173`，准备 README 中的 Demo operator、approver 与 platform admin 账户。这个演示使用**真实** PostgreSQL/pgvector、Valkey、Prometheus、Loki、OpenTelemetry、OIDC 登录、审批和独立 Executor；故障目标是隔离的 `demo-service`，默认 AI 是确定性提供方，身份账号和恢复动作均为**模拟**。它不访问生产目标。

| 时间 | 操作和可见证据 |
| --- | --- |
| 0:00–0:45 | 在隔离 Demo 服务触发数据库连接池故障。说明故障入口只在 Demo profile 存在。 |
| 0:45–1:30 | 展示 Alertmanager 多条相关告警经签名中继进入同一 fingerprint 的事故；时间线保留每次 occurrence，事故不重复创建。 |
| 1:30–2:20 | 打开证据抽屉，展示来自真实 Prometheus/Loki 的错误率、延迟和日志引用；说明正文经过脱敏和大小限制。 |
| 2:20–3:10 | 以 operator 请求诊断，查看带 evidence ID 的主假设和只读工具结果；说明默认确定性模型是演示替身，生产模型或人工模式需另行配置。 |
| 3:10–4:00 | 展示 `replicas` 越界参数被拒绝，再提交合规的已发布 R1 Runbook。核对目标、参数、版本和 proposal hash。 |
| 4:00–4:55 | 让请求者尝试自批并看到拒绝；切换独立 approver，完成审批。 |
| 4:55–6:20 | operator 执行并重复点击，展示仅一个 execution/Outbox。Executor 领取短期签名票据并对 Demo 目标调用一次受限恢复动作；重复 Stream 消息不会增加副作用。 |
| 6:20–7:10 | 查看固定健康探针满足阈值后才进入 `RESOLVED`；执行成功但健康未恢复时不能关单。 |
| 7:10–8:00 | 展示事件时间线、审计、trace 和 Eval 阈值；说明 Redis/Executor 故障演练报告、生产适配器目录与生产部署尚需各自的真实环境验收。 |

演示前运行 `./scripts/verify-stage2a.ps1` 可从干净数据卷验证浏览器闭环与两轮确定性 Eval；完整故障演练使用 `./scripts/fault-drill.ps1`。不要把本地 Demo 的账号、密钥或容器端口用于共享环境。生产外部集成的完成范围见 [已知限制](../known-limitations.md)。
