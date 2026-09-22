# Stage 2A 实施进度

更新日期：2026-09-22。继续使用 `feat/stage-1-demo` 分支和仓库内 `.worktrees/stage-1-demo` 工作树。

## 前置里程碑与设计修订

- Stage 1：`v0.1.0-demo` 指向 `cb2fb0b`；旧工件已核对，见 [Stage 1 验收记录](stage-1-verification.md)。
- `87e582b` 修正阶段衔接：UUID 接口、V12–V17 迁移编号、脱敏后快照身份、运行归属、诊断短事务与租约，以及已发布知识的候选过滤。文档经独立审查。

## Task 1：有界 Prometheus / Loki 证据适配器

已实现来源适配器并完成以下验收；独立审查的 Spec 与 Quality 均通过。

- 查询 ID 与服务绑定，参数同时经过标识符限制、正则和可选枚举校验；不接受输入方提供查询表达式或 URL。
- Prometheus 使用 range API、受限 step 和 series limit；Loki 使用向后查询和 line limit。客户端继续限制所有 series 的总结果条数与 UTF-8 JSON 字节数。
- 连接限时 2 秒，单次请求到完整响应体限时 8 秒；只对 502、503、连接重置重试一次，按 scheme/host/port/endpoint path 隔离熔断。超时计入熔断但不重试；429、权限错误、非法 JSON 和超限也不重试。
- 关闭跳转、自动解压和默认 HTTP 查询遥测。异常不携带 provider 内容、URL 或原始异常链；业务遥测只包含注册 source、query ID 与结果分类。
- 数值时间排序、稳定 label 顺序与有界前缀截断产生可重复的归一化 hash。跨 series 超过 10,000 个样本会在规范化分配/排序前拒绝，畸形 UTF-8 字节不会静默替换。此时内容仍是不可信的内存数据，不能直接持久化或进入模型；Task 2 负责脱敏并重新计算快照 hash。

验收命令（使用 Java 21）：

```powershell
.\mvnw.cmd -B -ntp -pl apps/ops-api '-Dtest=PrometheusEvidenceSourceTest,LokiEvidenceSourceTest,EvidenceSourceContractTest,BoundedRestClientFactoryTest' test
.\mvnw.cmd -B -ntp -T 1C verify
.\mvnw.cmd -B -ntp '-Dtest=*IT' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

本轮已保存的红灯证据：`build/stage2a-task1-red.log`（缺少实现）、`build/stage2a-adapters-uri-red.log`（加号被解码为空格、小数秒解析错误）。已校正继承测试中日期与 epoch 不一致的问题，保留严格时间窗校验。

后端 reactor 回归日志：`build/stage2a-task1-reactor.log`。真实依赖集成日志：`build/stage2a-task1-integration.log`，PostgreSQL 17 + pgvector / Valkey 下 88 项通过、无跳过。core 和 Demo 的 Compose 配置检查通过。工件均为本地 Git 忽略文件。

追加红—绿证据：`build/stage2a-utf8-red.log` 与 `build/stage2a-task1-final-focused.log`；审查修正记录为 `build/stage2a-review-http-{red,green}.log` 和 `build/stage2a-review-normalizer-{red,green}.log`。极端 Instant 时间窗测试证明现有预算判断可正常拒绝，无需增加异常处理。

最终回归：`build/stage2a-task1-reviewed-reactor.log`，三应用均构建成功，118 项通过（API 91、Executor 26、Demo 1），其中证据专项 52 项（Prometheus 21、Loki 16、HTTP 15）。上述验收无失败或跳过；本轮没有重跑完整浏览器 Demo 发布门禁，也没有接入组织真实监控实例。

## 下一实施任务

Task 2：证据脱敏、冻结落库、跨运行关联与只读工具。需要 V12 数据库归属约束、并发去重与真实 PostgreSQL 测试。随后按计划依次推进 Runbook 检索、模型、Eval、治理界面、身份审计及 Stage 2A 总体验收。

当前 Demo 仍使用固定证据与确定性模型；真实来源的 profile 装配和端到端接入属于 Task 2 / 4 / 8。Stage 2A 尚未达到发布门禁，不能标记生产就绪。
