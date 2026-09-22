# Stage 1 验收记录

2026-09-22 在 Java 21、Node.js 22.17.1 和 Docker Desktop Linux 引擎下运行 `scripts/verify.ps1`，退出码为 `0`。

- Java 单元/模块测试：66 个通过；真实依赖集成测试：88 个通过，无跳过。
- 前端：5 个测试文件、7 个用例通过；lint、类型检查、生产构建、格式与 OpenAPI 生成一致性通过。
- Compose：9 个服务在 180 秒限时内健康；仅发布 localhost 端口，禁止自动重启。
- Playwright：空库启动后完成告警、登录、诊断、职责分离审批、执行和恢复验证。
- PostgreSQL：同一指纹只有一条事故，事故已恢复，执行和验证周期成功，成功探针已持久化。
- Valkey：重复消息被确认消费，执行尝试仍为 1；直接读取目标指标确认恢复副作用仍为 1。
- 使用非默认 Demo 客户端密钥完成全流程，覆盖 Keycloak 环境变量导入。
- 高置信密钥模式扫描和 Git 空白检查通过；最终清理成功。

本地完整工件目录：`build/verification/20260922-143158/`（Git 忽略）。

2026-09-22 接续实施时核对：`v0.1.0-demo` 指向 `cb2fb0b68938c9be8d296ee7ff2dcef1c161ff70`。保留工件中的 `maven-verify.log`、`maven-integration.log` 均以 `BUILD SUCCESS` 结束，`playwright-e2e.log` 记录 `1 passed`。这是对既有验收记录的核验；接续期间的新测试结果单独记录，不冒充此里程碑的重跑。

本轮新增回归覆盖空事故队列自动发现新告警，以及已关闭事故收到迟到恢复信号后保持同一事故 ID。两项回归均先复现失败后验证修复。

Stage 1 只声明 Demo 里程碑：监控证据和模型为确定性适配器；真实证据、模型与生产加固继续按 Stage 2A / 2B 实施。
