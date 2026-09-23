# Stage 2A 实施进度

更新日期：2026-09-23。继续使用 `feat/stage-1-demo` 分支和仓库内 `.worktrees/stage-1-demo` 工作树。

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

## Task 4：有界 Spring AI 诊断

接入统一模型网关，支持 deterministic、OpenAI-compatible、Ollama 和 manual-only。配置与运维说明见 [模型配置](model-providers.md)。生产 profile 禁止 deterministic；manual-only 返回明确的 503，事故仍可读取和人工分诊。

- 诊断改为领取短事务、事务外模型等待、最终提交短事务。V14 通过活动运行唯一索引、owner token、最长 90 秒租约及命令关联约束保护并发与幂等。
- 冻结服务、事故版本、运行、15 分钟查询窗及发布语料哈希，只开放四个只读工具。六次调用预算跨结构修复共享，90 秒墙钟超时会中断等待；过期工具采集不能写入证据。
- Spring AI 2.0.1 结构校验最多一次修复；仅为 R0 的两个可空字段扩展 schema。默认 advisor 只看到固定的无效占位对象，修复请求包含错误码和响应哈希，日志捕获断言验证原始正文不泄露。
- 最终重验事故版本、状态、授权、语料和 Runbook 生命周期。策略校验后以 owner/status/lease 条件更新取得成功终态，再原子写入提案、事故事件、Outbox 和幂等响应。修正了最终校验或锁等待跨过租约后仍可提交的问题。
- 模型元数据记录 provider/model、prompt/corpus 版本、输入/响应哈希、token 用量、工具次数和耗时。失败路径保留安全错误及可获得的元数据，不返回供应商原始错误正文。
- 真实 PostgreSQL 测试覆盖事务释放、重复请求、当前授权、失败恢复、旧 owner 迟到、版本冲突、唯一约束和最终校验期间租约过期。WireMock 调用真实 OpenAI/Ollama SDK 验证聊天及 1536 维 Embedding 协议；提示注入测试拒绝新增动作工具。

红—绿证据：`build/task4-claim-red.log` 复现原同步事务、重复请求和授权问题；`build/task4-nullable-red.log` 复现 R0 schema 问题；`build/task4-options-diagnostic.log` 确认测试 ChatModel 的普通 options 使工具循环未运行；`build/task4-budget-green.log` 六项全部通过。`build/task4-final-fence-red.log` 复现最终租约漏洞及 Ollama HTTP/2 EOF，修正后 `build/task4-final-fence-green.log` 的 20 项全部通过。旧 KnowledgeConfiguration 测试随 Embedding 工厂迁移更新，Demo/production 组合限制在统一 provider 测试中保留。

最终验收（Java 21）：

```powershell
.\mvnw.cmd -B -ntp '-Dtest=*Test,*Tests,*IT' '-Dsurefire.failIfNoSpecifiedTests=false' verify
npm --prefix web/ops-console run api:check
```

`build/task4-final-all.log` 共 339 项通过（API 300、Executor 29、Demo 10），无失败或跳过，三应用构建成功。`build/task4-api-contract.log` 的生成类型检查通过。未使用外部模型凭据，未宣称真实模型质量、浏览器 E2E 或线上部署已验收。

独立 Spec/Quality 审查通过，最终租约 P1 已闭环，未发现剩余 P1/P2；`git diff --check` 通过。

## Task 5：可复现 AI Eval

实现 12 个固定事故场景、规则评分、运行/读取 API、基线对比和发布阈值。操作说明见 [AI Eval 运行与基线](ai-evaluation.md)。运行与读取仅限 `PLATFORM_ADMIN`，请求不能覆盖模型配置、工具或文件路径。

- 复用生产 ModelGateway、只读工具和 DiagnosisPolicy，通过窄范围 `diagnosis::evaluation` 接口绑定离线证据、Runbook 和固定检索 fixture；不会改写真实事故、诊断或证据。固定检索不代表线上混合检索质量验收。
- V15 固定数据集、case、逐例结果及运行身份/配置，终态不可修改。复合外键保证结果与运行属于同一数据集；数据库拒绝旧 owner、过期租约、缺失 case 的完成操作和已运行数据集追加 case。
- 模型调用在数据库事务外执行，每例独立持久化，最多六次工具调用、90 秒；运行具有 120 秒可续租租约和 20 分钟总时限。进行中返回幂等冲突，完成后重放原结果；满容量只拒绝新运行，不阻断既有命令重放，也不留下孤儿命令。
- 聚合使用固定 expectation 决定安全/根因分母，任何非预期错误或不完整覆盖都阻止发布。保存模型/prompt/tool schema/fixture/规则版本及费用单价指纹，价格或用量不可得时明确标记费用不可用。
- 两次实际 deterministic 运行生成完整稳定投影，递归排序 JSON 后逐字节比较，并校验已提交基线的 UTF-8/LF 字节。模型标识来自服务器配置，真实供应商需固定 snapshot/tag；不声称能从通用兼容协议获知供应商服务软件版本。

Task 8 真实证据接入时同步改进了确定性规则，对陈旧、冲突和无可用 Runbook 的证据给出有引用的 R0 判断，并重新生成可复现基线。当前基线 `releaseAllowed=true`；这只代表固定 Demo 数据集通过发布阈值：

| 指标 | 基线 | 发布要求 |
| --- | ---: | ---: |
| 引用可解析率 | 100% | 100% |
| 危险动作拦截率 | 100% | 100% |
| Runbook 准确率 | 100% | ≥85% |
| 根因 Top-3 准确率 | 100% | ≥80% |
| 虚构工具/Runbook 计数 | 0 | 0 |

红—绿证据：`build/task5-red.log` 与 `task5-rules-red.log` 验证缺失实现；`task5-http-citation-red.log` 复现缺失路由与预期拒绝的引用评分问题；`task5-first-green.log` 暴露多个数据库时钟调用造成的微秒级 deadline 约束冲突，已统一使用 statement_timestamp；`task5-focused.log` 暴露跨模块接口未声明，修正后的 `task5-boundary.log` 7 项通过；`task5-review-red.log` 复现满容量重放和仅含换行的空数据集问题。

审查修正后的专项日志 `build/task5-reviewed-focused.log`：29 项通过，无失败或跳过。包含真实 PostgreSQL 17 + pgvector 的隔离、不可变性、HTTP 权限和并发验收。`build/task5-final-api-contract.log` 的生成类型检查与 `build/task5-web-build.log` 的前端生产构建通过。

完整回归发现旧 `RunbookPublishingIT` 在应用启动后才为 MockitoBean 设置 Embedding 模型标识，与启动时生成的 Eval 配置指纹不兼容。测试改用 MockitoSpyBean 保留真实确定性模型标识，向量调用仍按场景模拟，未放宽生产校验。`build/task5-publishing-context-red.log` 保存原始失败；修正后的 `build/task5-publishing-context-green.log` 17 项通过。中断的回归日志另存，不计入通过结果。

最终后端验收（Java 21，关闭基线更新开关）：

```powershell
.\mvnw.cmd -B -ntp '-Dtest=*Test,*Tests,*IT' '-Dsurefire.failIfNoSpecifiedTests=false' verify
```

`build/task5-final-all.log`：三应用构建成功，368 项通过（API 329、Executor 29、Demo 10），无失败或跳过。JAR 内的 12-case 数据集与源文件逐字节一致。独立 Spec/Quality 复审通过，未发现剩余已确认的 P1/P2。未调用付费模型、接入组织真实监控、重跑浏览器 E2E 或推送/部署远程。

Dockerfile 已补充复制 `evals/datasets`，避免只在本地 Maven 构建时存在数据集。`build/task5-docker-build.log` 的本地 API 镜像构建通过；`build/task5-docker-resource-check.log` 证明无网络一次性容器可在最终镜像 JAR 中找到数据集。该检查不启动业务服务，也不等同于完整部署验收。

## Task 6：Runbook、证据与 Eval 治理界面

实现按需加载的 Runbook 列表、受控字段草稿编辑、版本差异、独立评审与确认发布；已发布版本只读并支持创建下一草稿。分页历史未覆盖当前版本时阻止使用不完整基准发布。Observer 仅查看授权服务的已发布版本，不请求管理员差异接口。

补齐服务目录、Runbook 目录、Eval 历史和事故绑定的单条证据读取 API，并同步 OpenAPI 类型。Task 6 时读取路径不创建 principal；首次访问的独立评审人也能获得正确评审资格。Task 7 引入认证请求的主体同步后，读取路径会同步身份及授权范围。

证据抽屉展示来源、实际查询窗口、哈希、截断与脱敏信息，正文作为转义文本显示；关闭后释放快照缓存。Eval 明确展示五项发布阈值和后端发布结论，缺失指标与费用显示不可用，准确率、累计延迟和费用分开绘图，并提供可访问表格。路由和图表分别打包，事故入口不直接加载 ECharts。

查询缓存使用不含 Token 的会话范围标识。身份或授权切换清空本地编辑；同一身份权限未变时自动续期保留未保存草稿，同时切换数据缓存范围。冲突保留输入，结果不确定的命令复用原请求及幂等键。

验证证据：

- Java 21 完整后端回归 `build/task6-final-all.log`：371 项通过（API 332、Executor 29、Demo 10），无失败或跳过；强化首次评审人场景后 `task6-first-reviewer-green.log` 6 项通过。
- 最终前端 `build/task6-complete-tests.log`：12 个文件、48 项通过；`task6-complete-lint.log`、`task6-complete-build.log`、`task6-complete-contract.log` 均通过。
- 红—绿回归覆盖同用户授权变更缓存泄漏、证据关闭后缓存释放、Token 续期丢失输入，以及分页版本差异基准选择。最终复审还修正了跨版本残留命令、旧异步响应跳转、差异方向和未知结果丢失原幂等键；`task6-runbook-p2-red.log` / `task6-runbook-p2-green.log` 保存回归证据。
- 本地浏览器使用合成身份及受控 HTTP fixture 验证桌面和 390px 窄屏：证据 Esc 焦点恢复、五项 Eval 阈值与独立趋势图、草稿及已发布只读页面。记录位于 `build/task6-browser/report.md`。该检查不等同于真实 OIDC、后端或模型 E2E。

独立 Spec/Quality 最终复审通过，无剩余已确认 P1/P2。操作说明见 [治理控制台](governance-console.md)。

## Task 7：主体同步、服务范围与追加式审计

已完成受信任 JWT 到本地主体及服务角色授权的同步。按 `(issuer, subject)` 锁定主体，按签发时间撤销旧服务授权；同秒令牌仅收缩授权，旧令牌不能恢复已撤销授权。未注册服务与未知角色不会生成授权，未知角色只累计低基数指标。应用服务在读取敏感证据和状态变更时再次执行角色与服务范围检查。

V16 为主体记录增加声明签发时间，为审计记录增加结果和倒序游标索引，并登记 `AUDITOR` 角色。审批、执行、Runbook、人工恢复验证和 Eval 均经统一审计命令写入行为人、动作、资源、结果、哈希与 Trace；元数据仅允许已知数值、布尔和枚举字段。人工恢复理由只留在事故事件中，不复制到审计元数据；Eval 数据集校验和进入专用哈希列。已有审计行的结果保留为 `unknown`。审计行维持数据库不可更新、不可删除约束，`GET /api/v1/audit-records` 只允许范围内审计员或平台管理员分页读取。

Demo Keycloak 将 `service_ids` 定义为仅管理员可查看、编辑的多值用户属性，并增加 `AUDITOR` realm 角色。本地 Keycloak 26.7.4 容器的领域导入、用户配置查询和实发 Observer 令牌检查通过；令牌携带 `OBSERVER` 与配置的服务 ID。令牌检查只在隔离容器中临时启用密码授权，仓库客户端配置仍禁用它。

红—绿记录见 `build/task7-*-red.log` 与聚焦绿色日志。最终 Java 21 reactor 回归 `build/task7-full-verify-reviewed.log`：382 项通过（API 343、Executor 29、Demo 10），无失败或跳过；最终测试日志未出现隐私测试中的凭据样本。前端 12 个测试文件、48 项通过；API 生成类型检查、Lint、生产构建及 `git diff --check` 均通过。完整浏览器 OIDC 与真实 Prometheus/Loki 闭环属于 Task 8，尚未验收。

## Task 8：真实证据集成与 Stage 2A 门禁

Demo Compose 现在将结构化服务日志经 OpenTelemetry Collector 送入 Loki，并从 Prometheus/Loki 的预登记查询采集错误率、延迟、连接池等待和获取超时日志。`sentinelops.evidence.mode=real` 关闭固定 `E-12`/`E-13` 证据采集；证据仍经过既有的只读工具、预算、脱敏、冻结和引用校验。演示目标增加受控连接池等待指标，故障日志不包含请求方可控的请求 ID。Keycloak 增加仅供本地治理验收的 `platform-admin-demo`。

真实依赖测试注入受控故障并产生请求流量，等待 Prometheus 指标和 Loki 日志后创建诊断，确认四个查询快照的来源、哈希、两个有效引用、已发布 Runbook 版本和四次只读工具调用。另有 real 模式上下文测试证明恰好装配 Prometheus/Loki 来源、没有固定快照；离线 Eval 即使在 real 模式下也只使用数据集 fixture，不读取或写入在线事故证据。

浏览器验收覆盖完整事故、审批、执行和恢复链路，以及 Observer 只读 Runbook/禁止 Eval、平台管理员查看只读已发布版本和五项 Eval 发布阈值。首轮完整门禁暴露 PowerShell 对健康响应字节数组的误判；修正正文解码后，第二轮发现 real 模式误将在线证据工具带入离线 Eval，导致 `EVIDENCE_NOT_FOUND`。新增真实模式 Eval 回归先红后绿，日志为 `build/task8-real-eval-red.log`、`build/task8-real-eval-green.log`。

最终 `scripts/verify-stage2a.ps1` 于 2026-09-23 通过，报告位于 `build/verification/stage2a/20260923-122744/`：

- Java 21 全量 reactor：API 352 项（1 项真实来源测试按计划延后）、Executor 29 项、Demo 10 项，零失败；Compose 启动后真实来源专项 3 项通过、零跳过。
- 前端 OpenAPI 类型、Lint、格式、生产构建及 12 个文件的 48 项 Vitest 全通过；两轮确定性 Eval 与已提交基线一致，五项硬阈值通过，`releaseAllowed=true`。
- Playwright 三项通过；`PromptInjectionIT` 两项通过；Compose 项目及其隔离数据卷已清理。未配置真实模型 provider，故可选供应商烟测未运行；该结果不代表外部模型语义质量或生产部署已验收。

## 下一实施任务

Stage 2A 本地发布门禁已通过。Stage 2B Task 1 的签名、请求边界、速率与重放保护已实施并通过闭环回归；记录见 [Stage 2B 进度](stage-2b-progress.md)。下一项为 Task 2 的生产身份、密钥与浏览器边界。生产就绪仍取决于 Stage 2B 的其余安全、故障和部署验收。
