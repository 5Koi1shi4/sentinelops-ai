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

## Task 2：脱敏、冻结与只读证据工具

已完成证据采集到只读工具的应用边界，最终回归及独立 Spec / Quality 审查均通过。

- 在任何来源调用前检查事故、服务、诊断运行及版本；外部采集不持有数据库事务。冻结事务按事故、运行顺序加锁并再次检查，拒绝已完成或过期运行的结果。
- 对正文、warning、标签和查询元数据统一脱敏，保留安全诊断文本；脱敏后重新排序，再按返回条数/字节预算截断并计算 SHA-256。采集使用服务端固定的 10,000 条 / 1 MiB 硬上限，硬上限已截断的数据拒绝冻结，原始 hash 不入库。
- 归一化 JSON 使用有界输出流；保留的标签在样本展开前限制单值 4 KiB、每 series 合计 8 KiB，避免重复标签放大中间分配。凭据规则覆盖嵌入 JSON、转义引号、空用户名 URI 和重叠匹配。
- 相同事故中的相同脱敏内容复用原快照，并给当前运行追加关联；不同 source/query/window 保持不同身份。`getEvidence` 仅能读取当前运行已关联的证据，返回对象不能修改冻结 JSON。
- V12 使用复合外键、归属触发器和运行身份不可变约束保护历史；包含归属错误的 V11 历史数据会使迁移失败并回滚，不会被自动改写。
- `queryMetrics`、`queryLogs`、`getEvidence` 是固定的只读工具；模型参数不能修改事故、服务、时间窗、来源 URL 或预算。JSON envelope 明确标记 `UNTRUSTED_EXTERNAL_DATA`，日志中的指令保持为数据。

首轮聚焦验收日志 `build/stage2a-task2-focused.log`：62 项通过，无跳过。审查后的聚焦日志 `build/stage2a-task2-reviewed-focused.log`：68 项通过，包括 15 项真实 PostgreSQL 冻结测试、15 项脱敏测试及工具、序列化、遥测边界验证。另有 2 项历史迁移拒绝测试。

红—绿证据包括 `build/stage2a-task2-canonical-{red,green}.log`（脱敏后重排）、`build/stage2a-task2-itemcap-red.log`（低条数预算）、`build/stage2a-task2-labelcap-red.log`（标签放大），以及 `build/stage2a-task2-redactor-{escapedquote,uri,boundary,overlap}-{red,green}.log`。旧 schema 测试硬编码 V11 的断言已更新为 V12，未放宽版本校验。

最终后端构建：`build/stage2a-task2-reviewed-reactor.log`，152 项通过（API 125、Executor 26、Demo 1）。完整真实依赖集成：`build/stage2a-task2-reviewed-integration.log`，105 项通过（API 93、Executor 3、Demo 9），包含现有 Demo 后端流程回归。两条命令均退出 0，无失败或跳过。独立复审确认低条数预算、凭据转义/重叠和归一化资源边界的修正已闭环。

## Task 3：Runbook 发布与混合检索

已完成草稿创建/修改、版本列表/差异、独立评审和发布 API，检索只返回已发布且属于授权服务、相同 embedding 模型的知识。接口与生成的 TypeScript 类型同步更新；独立 Spec / Quality 审查及增量复审均已通过。

- 草稿使用递增 revision 和 If-Match，所有写命令幂等；编辑清除旧评审，创建者和最后编辑者均不能评审。变更、评审和发布追加审计记录。
- 版本 diff 使用单条 SQL 的一致快照，避免并发编辑造成自比较出现差异；跨 Runbook 比较返回稳定 409。授权但不存在的服务在创建和检索时统一返回 404。
- 发布先在事务外生成分块与向量，再于短事务锁定版本，重新核对 revision、正文、definition checksum、reviewer 和生命周期；分块与发布原子提交，批量写入最多 100 行。并发重放只生成一份冻结内容。
- V13 增加全文/1536 维向量索引和分块只追加约束；数据库校验服务归属并固定 Runbook 身份。已发布内容不能修改或删除，内容不变的 published → retired 仍可执行。
- Markdown 分块保留段落/标题边界；超长段落按完整 Unicode 字符切分，最长 1200 code point，重叠不超过 150 UTF-16 code unit；文档最多 120000 code point、200 块，相同正文去重。
- 显式 SQL 使用全文与余弦候选的 reciprocal-rank fusion，每路候选 50、结果最多 10，返回精确版本、chunk、各路排名/分数和融合分数。真实 PostgreSQL EXPLAIN 验证 GIN/HNSW 可用。
- 向量维度、数量、有限值和非零检查严格；在入库/查询前归一化，避免极大/极小有限值导致余弦失真。provider 失败不留下半发布版本，也不返回其私有错误信息。

当前定义校验只放行已注册 Executor 实际支持的 `demo-http` 单步 `recover_connection_pool`、固定 replicas=1、R1 与预声明健康验证；未知操作、任意 Shell/SQL 和未注册回滚均拒绝。生产适配器扩展属于 Stage 2B。deterministic embedding 仅限显式 test/core/demo 且非 production，未配置 provider 时发布/搜索返回 503。

红灯日志：`build/task3-red.log`、`build/task3-chunks-red.log`、`build/task3-http-red.log`；边界红灯 `build/task3-boundary-red.log` 验证了契约长度/字符限制不一致和 pgvector 极值分数失真。`build/task3-unicode-red.log` 复现孤立 surrogate 与 Unicode 长度计数问题；现已在哈希/embedding/SQL 前拒绝非法 Unicode，长度统一按 OpenAPI 的 code point 语义计算。`build/task3-diff-red.log` 通过真实并发编辑复现自 diff 不一致，并验证 404/409 契约语义。

最终验收（Java 21）：

```powershell
.\mvnw.cmd -B -ntp '-Dtest=*Test,*Tests,*IT' '-Dsurefire.failIfNoSpecifiedTests=false' verify
.\mvnw.cmd -B -ntp -pl apps/ops-api '-Dtest=*Test,*Tests,*IT' verify
npm --prefix web/ops-console run api:check
npm --prefix web/ops-console run build
```

`build/task3-final-all.log` 的三应用完整回归 305 项通过。随后修正 diff 和未知服务响应后，`build/task3-final-api.log` 对最终 API 重新执行全部单元/模块/集成测试，268 项通过；结合未变动 Executor 29 项、Demo 10 项，最终覆盖共 307 项，均无失败或跳过。Task 3 专项 50 项包含在上述数字中，不重复计数。全部数据库测试使用真实 PostgreSQL 17 + pgvector，通知测试使用真实 Valkey。

OpenAPI 最终生成与 `api:check` 日志为 `build/task3-final-api-types.log`，前端生产构建为 `build/task3-web-build.log`，均通过。Vite 的沙箱子进程 EPERM 在正常权限下重跑解决，未修改依赖版本。本任务未修改前端页面、未重跑完整浏览器 E2E、未接入真实模型 provider，也未部署或推送远程。

## 下一实施任务

下一项为 Task 4：真实模型接入与有界诊断编排。随后按计划依次推进 Eval、治理界面、身份审计及 Stage 2A 总体验收。

当前 Demo 仍使用固定证据与确定性模型；Task 2 的安全冻结边界已就绪，真实来源的 profile 装配和端到端接入在 Task 4 / 8 完成。Stage 2A 尚未达到发布门禁，不能标记生产就绪。
