# 诊断模型配置

Stage 2A 的模型入口统一为 `ModelBackedDiagnosisEngine`，再按 `sentinelops.ai.provider` 选择网关。每次诊断固定服务、事故版本、运行 ID、此前 15 分钟查询窗和已发布 Runbook 语料哈希。模型只读工具累计最多 6 次，结构错误最多修复一次，总模型等待上限 90 秒。

## 配置方式

默认 `deterministic` 供本地、测试和 Demo 使用；启用 `production` profile 时禁止此模式，包括同时启用 `demo` 的情况。生产缺少模型资源时应显式设置 `manual-only`：应用可启动，诊断返回 `503 AI_PROVIDER_UNAVAILABLE`，事故保留在可人工处置的状态。

OpenAI-compatible 示例（使用外部配置文件或对应环境变量，不把密钥写入配置）：

```yaml
sentinelops:
  ai:
    provider: openai-compatible
    base-url: https://model.example.invalid/v1
    model: configured-chat-model
    embedding-model: configured-embedding-model
    embedding-dimensions: 1536
    api-key-secret-ref: env:SENTINELOPS_MODEL_API_KEY
```

密钥由运行环境提供 `SENTINELOPS_MODEL_API_KEY`。引用必须采用 `env:大写变量名`，未解析或空密钥会阻止启动。模型 URL 不允许携带 userinfo、query 或 fragment。供应商兼容端点需支持 Chat Completions、工具调用和 Embeddings；此处地址和模型名仅为占位符。

Ollama 示例：

```yaml
sentinelops:
  ai:
    provider: ollama
    base-url: http://127.0.0.1:11434
    model: configured-chat-model
    embedding-model: configured-1536-dimensional-embedding-model
    embedding-dimensions: 1536
```

应提前安装可用模型；配置不会自动下载模型。必须选择实际输出 1536 维的 Embedding 模型。响应数量、维数、有限值和非零向量均会校验，不截断或补齐向量。知识检索按 embedding 模型标识隔离；更换模型需要用对应模型发布新的知识版本。

## 运行与失败语义

- 只暴露 `queryMetrics`、`queryLogs`、`getEvidence`、`searchRunbooks`；参数不能覆盖服务、事故、运行和时间窗。
- 领取、等待模型、提交分成三个阶段，模型等待期间不持有数据库事务。活动运行由数据库唯一索引限制；同幂等键重放不会再次调用模型。
- 超时、超限、非法工具或二次结构失败不创建提案。服务版本变化、语料变化、Runbook 不再发布或租约失效同样拒绝提交。需要重试时读取当前事故版本，使用新的幂等键。
- 记录 provider、model、prompt/corpus 版本、输入/响应哈希、用量、工具次数和耗时；失败记录保留可获得的安全元数据。常规日志不记录完整 prompt、原始证据、工具参数/结果或原始模型响应。
- R0 可返回人工调查建议；动作提案仍经过服务端策略检查和既有审批流程，模型不能调用执行入口。

本地验收使用 WireMock 调用真实 Spring AI OpenAI/Ollama SDK，并用真实 PostgreSQL 验证提案与运行状态。它不代表外部供应商可用性或语义质量已经验收。真实 Prometheus/Loki 来源的 profile 装配及 Stage 2A 端到端验收仍按 Task 8 推进。
