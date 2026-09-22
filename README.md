# SentinelOps AI

SentinelOps AI 是一个面向单组织内部使用的事故响应平台。Stage 1 Demo 已贯通以下闭环：Alertmanager 告警进入控制平面，系统建立唯一事故、采集脱敏证据并生成有引用的诊断建议；值班人员提交 R1 恢复方案，独立审批人批准后，由最小权限 Executor 执行已注册 Runbook，最后通过持久化健康探针确认恢复并关闭事故。

## 快速启动

前置条件：Docker Desktop（Linux 容器）、PowerShell 7，以及未占用的本机端口 `4173`、`8081`、`9090`、`9093`。

```powershell
.\scripts\demo.ps1
```

脚本会构建并启动全部容器，在最多 180 秒内等待健康检查，然后注入可重复的 Checkout 数据库连接池故障。打开 [http://localhost:4173](http://localhost:4173) 即可操作。

这是本地专用的 Demo 身份数据，用户名与密码相同：

| 用户 | 密码 | 角色 | 用途 |
| --- | --- | --- | --- |
| `observer-demo` | `observer-demo` | `OBSERVER` | 只读查看事故 |
| `operator-demo` | `operator-demo` | `OBSERVER`, `ON_CALL_OPERATOR` | 诊断、提交审批、启动执行 |
| `approver-demo` | `approver-demo` | `OBSERVER`, `SRE_APPROVER` | 独立批准 R1 变更 |

完整演示步骤见 [Demo 运维手册](docs/runbooks/demo-flow.md)。

Stage 1 使用固定的 Demo 证据与确定性诊断适配器；Prometheus/Alertmanager 告警、OIDC 登录、审批、执行和恢复探针是真实链路。真实监控证据与模型集成按 Stage 2A 计划推进。

> 这些密码、客户端密钥和数据库默认值只用于隔离的本地 Demo。不要把它们复用到测试共享环境或生产环境。

可在启动前设置 `SENTINELOPS_DEMO_EXECUTOR_CLIENT_SECRET` 与 `SENTINELOPS_DEMO_CONTROLLER_CLIENT_SECRET`；Compose 会把同一值传给客户端和 Keycloak，realm 导入使用 [Keycloak 环境变量占位符](https://www.keycloak.org/server/importExport)。更改后须重新创建 Keycloak 容器以重新导入。

## 架构与安全边界

- `ops-api`：Spring Boot 控制平面，负责告警幂等、事故状态机、证据/诊断、审批、执行租约、Outbox 和恢复验证。
- `ops-executor`：独立 Java 执行单元。它只接受签名执行票据，并以 fencing token、幂等键和最小 OAuth scope 调用目标服务。
- `ops-console`：React 控制台，使用 Keycloak Authorization Code + PKCE；浏览器不持有服务客户端密钥。
- `demo-service`：只在 Demo profile 启用故障注入与恢复端点，并分别校验 `demo:fault` 和 `runbook:execute:checkout` scope。
- PostgreSQL/pgvector 保存事务状态与不可变记录，Valkey Stream 传递执行请求，Prometheus 与 Alertmanager 构成演示告警源。

控制平面、执行器、数据库和 Valkey 只位于 Compose 私有网络。仅下列本机地址被发布：

| 地址 | 服务 |
| --- | --- |
| [http://localhost:4173](http://localhost:4173) | Ops Console |
| [http://localhost:8081](http://localhost:8081) | Keycloak |
| [http://localhost:9090](http://localhost:9090) | Prometheus（本地诊断） |
| [http://localhost:9093](http://localhost:9093) | Alertmanager（本地诊断） |

## 完整验收

本地开发基线为 Java 21 与 Node.js `22.17.1`。运行：

```powershell
.\scripts\verify.ps1
```

验收会从干净数据卷开始，依次运行 Java 单元/模块/集成测试、前端安装/静态检查/Vitest/生产构建、Compose 校验与健康检查、Playwright 浏览器全流程，并重投同一条 Valkey Stream 执行消息验证恢复副作用只发生一次。无论成功或失败都会关闭并删除本次 Compose 数据卷，日志与浏览器工件保存在 `build/verification/<timestamp>/`。

如需手动停止 Demo 并清除本地数据：

```powershell
docker compose -p sentinelops `
  -f .\deploy\compose\compose.core.yml `
  -f .\deploy\compose\compose.demo.yml `
  down --volumes --remove-orphans
```

## 项目结构

```text
apps/ops-api          事故响应控制平面
apps/ops-executor     独立、最小权限的 Runbook Executor
apps/demo-service     可控故障与恢复目标
web/ops-console       React 运维控制台与 Playwright E2E
contracts/openapi     HTTP API 契约
deploy                Compose、Keycloak 与可观测性配置
docs/runbooks         演示与运维手册
docs/superpowers      产品设计与分阶段实施计划
scripts               一键演示和发布验收脚本
```
