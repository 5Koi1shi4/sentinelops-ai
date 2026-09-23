# SentinelOps Stage 1 Demo 运维手册

本手册演示从故障注入、告警归并、证据诊断、双人审批到受控恢复与客观验证的完整链路。所有账号和密钥均为本地 Demo 固件，不适用于共享或生产环境。

## 1. 启动并注入故障

在仓库根目录运行：

```powershell
.\scripts\demo.ps1
```

脚本会等待全部服务健康（最长 180 秒），再通过仅拥有 `demo:fault` scope 的机器身份激活 Checkout 连接池故障。Prometheus 每 2 秒抓取指标；故障持续 5 秒后，`SentinelOpsDemoCheckoutFault` 进入 firing。Alertmanager 将标准 webhook 发送给私有网络内的 Demo 签名中继，再由中继向控制平面发送签名后的原始正文。签名协议与限额见 [Webhook 接入](webhook-ingestion.md)。

## 2. 值班人员诊断并提交审批

1. 打开 [Ops Console](http://localhost:4173)，使用 `operator-demo` / `operator-demo` 登录。
2. 等待 `checkout-api` 的“数据库连接池耗尽”事故出现，然后打开事故详情。
3. 点击“运行证据诊断”。详情中应出现脱敏证据 `E-12`（连接池等待数）与 `E-13`（获取连接超时数），并形成 `RB-DB-POOL-03` 建议。
4. 点击“提交审批”。审批状态应为 `PENDING`。
5. 当前操作者是请求者，因此批准按钮不可用，并显示“请求者不能审批自己的 R1 变更”。这是预期的双人控制。

## 3. 独立审批并执行恢复

1. 在另一个浏览器上下文或无痕窗口打开控制台，使用 `approver-demo` / `approver-demo` 登录。
2. 打开同一个事故，批准待处理的 R1 方案。
3. 返回值班人员窗口并刷新，点击“执行已审批方案”。
4. 状态会依次经过 `EXECUTING` 与 `VERIFYING`，最终进入 `RESOLVED`。

Executor 先向控制平面领取带短租约和 fencing token 的签名票据，再用仅包含 `runbook:execute:checkout` scope 的机器令牌调用 Demo 恢复端点。控制平面不会只凭执行器报告关闭事故；它会持久化独立健康探针结果，只有成功阈值满足后才写入 `RESOLVED`。

## 4. 验证结果

控制台应显示：

- 事故状态为 `RESOLVED`；
- 执行状态为 `SUCCEEDED`；
- 时间线包含恢复验证成功事件；
- 证据和诊断引用仍可追溯。

也可在 [Prometheus](http://localhost:9090/graph?g0.expr=demo_recovery_side_effect_total) 查询 `demo_recovery_side_effect_total`，结果应为 `1`。

要执行包含重复 Stream 投递检查的完整自动验收，请先结束手动 Demo，或直接运行（脚本会清理旧数据）：

```powershell
.\scripts\verify.ps1
```

## 5. 停止与清理

```powershell
docker compose -p sentinelops `
  -f .\deploy\compose\compose.core.yml `
  -f .\deploy\compose\compose.demo.yml `
  down --volumes --remove-orphans
```

该命令只删除 `sentinelops` Compose 项目的容器、网络和命名数据卷；不会删除仓库文件。

## 故障排查

查看服务状态：

```powershell
docker compose -p sentinelops `
  -f .\deploy\compose\compose.core.yml `
  -f .\deploy\compose\compose.demo.yml `
  ps
```

查看日志：

```powershell
docker compose -p sentinelops `
  -f .\deploy\compose\compose.core.yml `
  -f .\deploy\compose\compose.demo.yml `
  logs --tail 200
```

- 端口占用：释放 `4173`、`8081`、`9090` 或 `9093` 后重新启动。
- Keycloak 旧配置：执行“停止与清理”中的命令，确保重新导入 realm 固件。
- 告警未出现：确认 `demo-service` 与 Prometheus 健康，并在 Prometheus 查询 `demo_fault_active` 是否为 `1`。
- 浏览器测试失败：查看最新的 `build/verification/<timestamp>/playwright-report/`、截图、trace 和 `compose.log`。
