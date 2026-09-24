# Stage 2B 可观测性运行手册

## 本地栈

在仓库根目录运行：

```powershell
docker compose -p sentinelops-observe -f .\deploy\compose\compose.core.yml -f .\deploy\compose\compose.demo.yml config --quiet
docker compose -p sentinelops-observe -f .\deploy\compose\compose.core.yml -f .\deploy\compose\compose.demo.yml up -d --build
```

Grafana 仅绑定本机 `127.0.0.1:3000`。启动前通过 `SENTINELOPS_GRAFANA_ADMIN_USER` 和 `SENTINELOPS_GRAFANA_ADMIN_PASSWORD` 设置本地账号。已预置的三个只读仪表板分别覆盖事故与 MTTD/MTTR、诊断与 AI 质量、审批与执行。Prometheus、Loki、Tempo 的数据源也由文件预置，UI 不可编辑。Collector 接收三个服务的 OTLP HTTP 数据，并保留 Demo 服务既有的结构化文件日志采集。Tempo 本地数据保留 24 小时；此 Compose 栈用于本地演示，不承担生产持久化和高可用。

Demo Compose 将 API 与 Executor 的 trace 采样率设为 100%，用于稳定核验跨进程 Span Link；生产环境应按流量、保留期和成本单独配置采样策略。

启动后打开 Grafana 中的 `SentinelOps` 文件夹检查三个仪表板，并在 Tempo Explore 中按 trace ID 查找跨度。Prometheus 可在 `127.0.0.1:9090` 查询 `sentinelops_incident_detected_total`、`sentinelops_diagnosis_citation_validation_total` 和 `sentinelops_executor_stream_lag`。没有事件或定价配置时，相应曲线显示无数据；不要把无数据当作零。

当前 Collector → Prometheus 的 OTLP 转换把 Micrometer Timer 导出为 `_milliseconds_sum`、`_milliseconds_count` 和 `_milliseconds_bucket`。仪表板的耗时面板将毫秒平均值除以 1000 后按秒显示；变更 Collector 或 Meter 导出器后应先核对实际指标名，再调整查询。

## 遥测边界

平台业务跨度只使用固定操作名。指标标签限定为有限状态、风险等级和已登记的来源、工具、模型、查询、适配器名称。事故、诊断运行、审批和执行 UUID 只进入跨度属性及日志上下文，不进入指标标签。提示词、证据正文、Bearer 令牌、审批意见、执行票据、模型工具参数和返回正文不得进入跨度、标签或业务日志。业务日志只发出固定操作、结果与 UUID 关联字段；Outbox 持久化失败摘要只保留异常类型。

Webhook 请求中的传入 trace context 可连接同步入站调用；Executor 到 API 的 HTTP 调用由 OTel 自动传播。执行请求在事务内将受限 W3C `traceparent` 写入 Outbox，Relay 只将有效值传到 Valkey Stream；Executor 消费时建立独立 trace，并用 Span Link 指向创建执行请求的跨度。审批等待、Stream 和定时健康验证是异步边界，每次人类命令也可产生独立 trace；用 `incident.id`、`diagnosis.run.id`、`approval.id`、`execution.id` 关联这些 trace。TelemetryPrivacyIT 验证控制平面业务里程碑及 Outbox context，OutboxRelayIT 验证 PostgreSQL 到 Valkey 的传播，Executor 测试验证 Span Link；完整 Compose 闭环另核验生产路径。

## 指标解释

- MTTD 从合法 Alertmanager `startsAt` 到首次成功事故摄取，超出 30 天的异常时间不计入。带任意已配置来源别名的 v4 告警都可计入；来源名无需恰好等于 `alertmanager`。
- MTTR 从事故创建到健康验证确认恢复。诊断与审批耗时按服务内实际历时计；重复幂等命令不重复计入结果。
- `sentinelops.diagnosis.citation.validation` 对有引用的通过提案或被引用校验拒绝的提案各计一次。无引用的 R0 提案不计入通过次数。
- AI Token 按服务返回的 prompt/completion usage 计量。成本只有在同时配置可信 provider、model、输入及输出每百万 Token 美元单价时产生；不支持的模型保持无数据。环境变量分别为 `SENTINELOPS_AI_PRICING_PROVIDER`、`SENTINELOPS_AI_PRICING_MODEL`、`SENTINELOPS_AI_PRICING_INPUT_USD_PER_MILLION_TOKENS`、`SENTINELOPS_AI_PRICING_OUTPUT_USD_PER_MILLION_TOKENS`。价格由部署者维护，改变报价时应记录生效时间；历史计数不会重估。
- Outbox backlog 和 quarantine backlog、Stream lag、租约冲突、fencing 冲突均按当前服务实例报告；`NaN` 的 Stream lag 表示 Valkey 尚无可读 group lag。

## 排障

若 Grafana 无数据，先检查 `docker compose ... ps` 中 Collector、Prometheus、Loki、Tempo 是否运行，然后检查 Collector 日志的导出错误。通过 Prometheus 查询原始指标名，再检查仪表板表达式。若 Tempo 无业务跨度，确认服务环境变量 `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318` 和 `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`，并在 API 触发一次新事故。不要为了排障启用 Spring AI prompt/completion 或工具正文导出。

本地停止：

```powershell
docker compose -p sentinelops-observe -f .\deploy\compose\compose.core.yml -f .\deploy\compose\compose.demo.yml down
```
