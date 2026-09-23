# SentinelOps AI STRIDE 威胁模型

范围：浏览器、OIDC、API、Alertmanager、中继、PostgreSQL、Valkey、模型与证据源、独立 Executor 和目标服务。保护资产包括身份及服务范围、原始告警与证据、不可变诊断提案、审批决策、执行票据、Runbook、事故状态与审计记录。

| 边界与威胁 | 类别 | 现有控制与责任方 | 验证 | 残余风险或后续任务 |
| --- | --- | --- | --- | --- |
| 伪造浏览器身份或角色 | Spoofing / Elevation | API：JWT 签名、issuer、时间、subject、独立 API audience；角色只取受验证声明；PostgreSQL 同步最新授权快照 | `ProductionSecurityIT`、`SentinelJwtAuthenticationConverterTest`、`PrincipalSynchronizationIT` | 身份提供方尚未发行撤销令牌时，已有 JWT 可在有效期内使用；需短寿命和紧急撤销流程 |
| Executor 令牌调用用户 API，或用户令牌调用内部接口 | Spoofing / Elevation | API：分离 `/internal/**` 安全链、Executor audience 与 realm role，拒绝混合受众 | `ProductionSecurityIT`、`SentinelJwtAuthenticationConverterTest`、`ExecutionControlHttpIT` | Executor 凭据被盗仍可能调用内部接口；生产需短期凭据、最小权限和轮换 |
| 伪造或重放 Alertmanager Webhook | Spoofing / Tampering / DoS | API：原始字节 HMAC、来源密钥引用、时间窗、持久化 nonce、正文上限、限流；Demo 中继仅在私网 | `WebhookSecurityIT`、`WebhookFuzzTest`、`DemoAlertSigningRelayTest` | 来源密钥泄露仍可在轮换前发送有效请求；需运维密钥轮换 |
| 告警、日志或文档中的提示注入 | Tampering / Elevation | 诊断：不可信文本不能改变系统策略或调用写工具；证据只读、提案结构与引用校验 | `PromptInjectionIT`、Eval 安全门禁 | 新模型与新数据源仍需持续 Eval；模型建议永不直接批准或执行 |
| 模型或用户诱导出站 SSRF | Information Disclosure / Elevation | API：现有工具和证据源由服务端静态注册，不接收模型指定的任意 URL | `EvidenceToolsTest` | Stage 2B Task 4 的生产 HTTP/Kubernetes 适配器仍需严格地址和资源 allowlist |
| 审批者修改执行参数、自批或跨服务批准 | Tampering / Elevation | 审批服务：固定 proposal hash 与策略校验、职责分离、仅 SRE_APPROVER 可批准、决策体拒绝多余字段 | `ApprovalMutationSecurityIT`、审批模块集成测试 | 身份提供方角色配置错误或双人串谋需审计和组织流程约束 |
| 执行票据被窃取或修改 | Spoofing / Tampering | API/Executor：RSA 签名、kid、audience、过期时间、execution/Runbook 绑定；私钥从环境引用解析 | `ExecutionTicketTest`、`ExecutionControlHttpIT` | 凭据泄露需轮换；生产网络和密钥管理由部署方提供 |
| 旧 Executor 持有过期 fencing token 回报结果 | Tampering / Elevation | 执行控制：claim 与 fencing token 比较；迟到结果不得推进状态 | `ExecutionControlHttpIT` | Stage 2B Task 3 继续补全租约、心跳、崩溃恢复和未知结果演练 |
| Demo/test 配置暴露到正式环境 | Elevation / Information Disclosure | 启动校验：拒绝 Demo/test profile、Demo 用户、确定性 AI、默认密码、HTTP OIDC、通配来源和内联私钥；生产不注册临时签名密钥 | `ProductionStartupValidatorTest`、生产烟测（Task 8） | 部署方仍须提供受管 TLS、OIDC 和 Secret 注入；正式 Compose 尚待 Task 8 |
| 操作争议、日志泄露与流量耗尽 | Repudiation / Information Disclosure / DoS | 事故/审计事件只追加；Webhook 正文和来源限额；API 限制证据输出和模型工具预算 | `WebhookSecurityIT`、`AuditPrivacyIT`、Eval 安全门禁 | Stage 2B Task 5/7 将补观测内容审查、负载与故障演练 |

生产发布前复核本表中标为后续任务的控制，以对应任务的真实测试和部署证据替换残余风险说明。
