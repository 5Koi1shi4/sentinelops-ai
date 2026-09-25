# Stage 2B 实施进度

## Task 1：入站告警 Webhook 加固

控制平面按正文上限、来源、时间戳、nonce、HMAC、持久化重放记录、来源限流、JSON/schema、事故摄取的固定顺序处理请求。V17 保存来源与 nonce 的 SHA-256 摘要，后台分批删除过期行；签名使用原始字节并做常数时间比较。来源密钥只通过 `env:` 引用解析，生产 profile 没有来源或引用未解析时拒绝启动。每来源单实例限额为 60 次/分钟，瞬时桶容量 20 次，429 附带 `Retry-After`。

Demo Compose 加入只连接私有 `control-plane` 网络、没有宿主机端口的签名中继。Alertmanager 向中继发送原始 v4 JSON；中继为每次投递生成新时间戳和 128 位随机 nonce，签名后单次转发到 API。普通 Demo 目标容器与非 Demo 模式均不注册中继路由。契约、生成的前端类型和 [Webhook 接入手册](webhook-ingestion.md)已同步。

红—绿记录：`build/stage2b-webhook-security-red.log` 记录旧控制器对未签名等 6 项请求错误接受；`build/stage2b-relay-red.log` 记录中继类缺失。聚焦回归 `build/stage2b-webhook-focused.log` 为 API 23 项与 Demo 中继 2 项通过；`build/stage2b-relay-route-it.log` 为路由及原有 Demo 流程 9 项通过。2,000 个异常签名请求的模糊测试见 `build/stage2b-webhook-fuzz.log`，零失败或部分事故写入。V17 schema 专项见 `build/stage2b-schema-version-green.log`，5 项通过。

首次完整门禁因原 schema 测试固定期待 V16 失败，已改为校验 V17 表和索引；第二次后端、前端和 Eval 通过，但 Docker 环境访问 Maven Central 新依赖返回 403。只读网络检查确认 Google 托管的 Central 镜像可用；镜像构建成功日志为 `build/stage2b-ops-api-docker-mirror.log`，容器构建仅使用版本化、无凭据的 Maven 设置文件。

最终 `scripts/verify-stage2a.ps1` 于 2026-09-23 通过，报告为 `build/verification/stage2a/20260923-135603/`：Java 21 全 reactor API 359 项（1 项真实来源测试按脚本安排跳过）、Executor 29 项、Demo 13 项，零失败；隔离 Compose 启动后真实 Prometheus/Loki 专项 3 项通过。前端类型、Lint、格式、48 项 Vitest 与构建通过；两轮确定性 Eval 均通过。Playwright 3 项通过，其中完整事故用例从真实 Demo 故障与 Alertmanager 告警开始，经过私有签名中继、诊断、审批、执行和恢复验证。提示注入专项 2 项通过。Compose 容器、网络和隔离数据卷已清理。未配置真实模型 provider，因此可选供应商烟测未运行。

## Task 2：生产身份、来源、密钥和浏览器边界加固（已完成）

已实现 API 与 Executor 分离的 JWT audience、issuer、零时间宽限和 subject 校验，生产来源白名单及应用层角色复核；审批决策体拒绝多余字段，数据库授权快照阻止旧令牌恢复已撤销的权限。生产 profile 对 Demo/test、确定性 AI、HTTP OIDC、默认数据库密码和内联签名私钥拒绝启动，并且不注册临时签名密钥。控制台采用 OIDC Authorization Code + PKCE、同源回调和 sessionStorage，退出时清除查询缓存；Nginx 提供静态 CSP 与安全响应头，HSTS 留给 TLS 入口。威胁边界和残余风险见 [STRIDE 威胁模型](../threat-model/stride.md)及 [ADR 0003](../adr/0003-security-and-trust-boundaries.md)。

红—绿记录：审批越权与额外参数、JWT 受众/时间、生产启动、旧令牌授权以及 production/test 同时激活的临时密钥测试先失败后通过。浏览器 E2E 在红阶段因 Docker 不可用未运行，之后对重建的 Compose 栈通过。真实 PostgreSQL 17 授权同步专项 5 项通过（`build/stage2b-task2-principal-it-final.log`）。全量 Java 21 reactor 按 `*Test,*Tests,*IT` 执行：API 372 项（按测试安排跳过 1 项）、Executor 29 项、Demo 13 项，零失败（`build/stage2b-task2-all-java-it-rerun.log`）。第一次全量运行暴露旧 `EvalHttpIT` 把管理员和操作员设为同一 subject；将测试身份分开后，该专项 2 项通过，全量复跑通过。

前端按锁文件正常 `npm ci` 后，OpenAPI 类型检查、Lint、格式检查、54 项标准 Vitest 与 Vite 生产构建均通过（`build/stage2b-task2-npm-ci-final.log`、`build/stage2b-task2-vitest-final.log`、`build/stage2b-task2-web-build-final.log`）。隔离 Compose 全部服务达到 Healthy；Nginx 实际响应包含预期 CSP、nosniff、Referrer-Policy、Permissions-Policy 和拒绝嵌入头，HTTP 与伪造转发协议请求均无 HSTS。Playwright 完整 6 项通过（`build/stage2b-task2-all-browser-e2e.log`），涵盖事故闭环、治理流程与 3 项安全边界；隔离项目容器、网络和数据卷已清理。CORS 专项验证控制台写命令所需 `If-Match` 与 `Idempotency-Key`。只读代码评审未发现阻断或高优先级问题。

## Task 3：执行租约、心跳、fencing 和未知结果语义（已完成）

V18 增加不可更新、不可删除的 `execution_attempt_event`，记录 `prepared`、`dispatched` 和终态。Executor 在已验票且已续租后准备步骤；Demo HTTP 适配器在真正发出请求前记录 `dispatched`，结果回报与 `acknowledged` 或 `failed_before_dispatch` 同事务落库。正式内部结果 API 拒绝缺少阶段证据的回报。领取、续租和阶段追加在数据库核验当前 owner、票据 JTI、fencing token、有效审批、Runbook checksum 与租约；完成回报锁定当前执行并由条件更新再次检验 owner、fencing token 与未过期租约。旧 owner 的迟到完成不能覆盖新 owner。租约为 30 秒，心跳间隔 10 秒；两次连续暂时性心跳失败会阻止新的分发，票据过期边界不得开始新步骤，控制平面与 OAuth HTTP 调用设 2 秒连接、5 秒读取超时。

已分发但结果未知时，仅目标为 `demo-checkout` 的 `demo-http/recover_connection_pool` 具备已验证的稳定幂等键与目标 fencing，允许新租约重试；其他操作立即升级事故，留下 `unknown_after_dispatch`、一次脱敏未知结果摘要和人工核对说明。审查发现审批在分发后失效会漏记未知结果，已增加真实 PostgreSQL 回归并修复，审批失效原因一并写入事件。新增生产适配器须在 Task 4 逐项证明重放语义后扩充白名单。

红—绿记录包括 `build/stage2b-task3-lease-red.log`、`build/stage2b-task3-journal-red.log`、`build/stage2b-task3-crash-red.log`、`build/stage2b-task3-executor-phase-red.log`、`build/stage2b-task3-heartbeat-red.log`、`build/stage2b-task3-ticket-expiry-red.log` 和 `build/stage2b-task3-revoked-unknown-red.log`。Java 21 完整回归为 API 385 项（按既有测试安排跳过 1 项）、Executor 37 项、Demo 13 项，零失败（`build/stage2b-task3-all-java-it-final.log`）；最后的审查修复又通过执行/租约 PostgreSQL 专项 23 项（`build/stage2b-task3-revoked-unknown-green.log`）。Valkey Stream 专项 3 项通过（`build/stage2b-task3-redis-consumer-green.log`）。只读审查发现的唯一 P2 已修复。

## Task 4：生产 HTTP 与 Kubernetes Runbook 适配器（适配器范围已完成）

Executor 增加启动时从只读 JSON 目录加载的 `production-http` 和 `kubernetes` 适配器。HTTP 仅按动作 key 选择固定 URL、方法、OAuth audience/scope 与扁平正文模板；参数有类型和边界，额外字段拒绝。外部 HTTPS 目标禁止 IP 字面量及私网 DNS 答案；连接固定到已检查的地址，并继续按目录主机名做 TLS 验证。客户端禁代理、重定向和自动重试，已分发后的 3xx、网络中断或暂时性响应按未知结果升级人工核对。生产 HTTP 目标只有自行验证并持久化稳定幂等键与 fencing token 后，才能考虑加入重放白名单；目前仍不自动重放。

Kubernetes 只对目录登记集群、namespace 和名称的 Deployment 执行 `restart_deployment` 或有界 `scale_deployment`。JSON Patch 先测试 `resourceVersion`，然后仅写入重启时间注解或 `spec.replicas`；不暴露通用 patch/delete/exec。服务账号 token 与 CA 从 Pod 挂载读取，集群必须使用 HTTPS；客户端禁自动重试。RBAC 示例只对指定 namespace 的命名 Deployment 授予 get/patch/update。Kubernetes 没有目标端幂等/fencing 契约，未知结果不重放。配置格式、部署要求和剩余投运步骤见 [适配器安全手册](adapter-security.md)。

红—绿记录：`build/stage2b-task4-adapter-red.log` 记录类缺失；`build/stage2b-task4-catalog-strict-red.log` 记录未知目录字段原本被忽略；`build/stage2b-task4-ip-range-red.log` 记录 IPv6 ULA/共享地址检查缺失；`build/stage2b-task4-review-red.log` 记录 3xx 与 Kubernetes HTTP 集群被错误接受；`build/stage2b-task4-dns-pin-red.log` 记录固定地址传输尚未实现。最终聚焦 17 项和 Executor Java 21 全量 54 项（含 Valkey Testcontainers 3 项）零失败，见 `build/stage2b-task4-review-first.log` 与 `build/stage2b-task4-executor-final.log`。RBAC YAML 使用本地解析器验证了资源名、namespace Role 与动词集合；没有实际 Kubernetes 集群可运行 `kubectl auth can-i`，该项须在部署环境复核。

控制平面的 Runbook 发布定义目前仍只允许 Demo 操作。Task 4 交付的是生产适配器和安全目录，尚未构成可发布的端到端生产 Runbook；设计中的真实执行路径必须在 v1.0.0 总体验收前补齐发布白名单、真实健康验证与集成测试，不能通过直接改数据库绕过治理。

## Task 5：隐私安全遥测与运维仪表板（已完成）

Task 5 已实现隐私安全的 OpenTelemetry 业务跨度/日志/指标、Collector/Tempo/Grafana 本地栈和三个仪表板，运行与关联约束见 [可观测性运行手册](observability.md)。遥测测试使用真实 PostgreSQL 17 和内存 OTel 导出器注入敏感 canary，核验正文不进入跨度、标签或日志；引用有效性、幂等重放、回滚计数、租约与 fencing 冲突、Executor Stream lag 及适配器跨度均有专项验证。

真实栈核查发现 OTLP Timer 导出为毫秒指标以及 Demo 签名来源别名未计入 MTTD；仪表板查询与 MTTD 逻辑修复分别经真实 Prometheus 指标名和红—绿 PostgreSQL 测试验证。Grafana 13.2.2 三个数据源健康，三个仪表板加载，24 条 PromQL 在真实 Prometheus 中解析成功；Collector 在闭环期间无导出错误。另向隔离栈发送一条新 HMAC 签名 v4 告警得到 HTTP 202，Prometheus 中 MTTD count=1、sum 约 60.17 秒。首次复跑浏览器测试因沿用已完成事故的数据卷而超时；重置本任务专用卷后通过。

原计划要求从 webhook 经人工审批直到验证组成一条 trace，与异步审批和定时验证的实际边界不符。设计与计划现明确采用跨 trace 关联：Outbox 保存经校验的 W3C `traceparent`，Stream 传递该上下文，Executor 建立独立 trace 并通过 Span Link 指向创建执行的跨度；定时验证以 execution UUID 关联。真实栈校验 `build/stage2b-task5-trace-runtime-check-final.log` 为 PASS，确认创建跨度、Stream 消息、Executor 消息/适配器/API 领取及完成跨度、Span Link 和独立验证 trace。隐私 canary、传播边界及 Executor 轮询父跨度问题均经过红—绿测试。只读复审无阻断或高优先级问题；校验脚本仅扫描最近 100 条 Stream 记录，在极高并发下可能误报未找到。

最终 Java 21 全 reactor 回归见 `build/stage2b-task5-all-java-it-final.log`：API 397 项（按既有安排跳过 1 项）、Executor 58 项、Demo 13 项，零失败。计划指定的三模块遥测门禁见 `build/stage2b-task5-plan-gate-final.log`，构建成功；最终隔离栈真实浏览器闭环见 `build/stage2b-task5-trace-e2e-final.log`，1 项通过。Task 5 完成后曾按用户要求暂停；用户随后要求恢复实施。

## Task 6：数据库最小权限与安全门禁（已完成）

Core/Demo 数据库现分为 `sentinelops_migrator` 与 `sentinelops_app`：前者执行 Flyway，后者仅获得审查过的业务 DML，Executor 没有业务数据库凭据。迁移后回调为可变表补充显式 UPDATE，历史记录保持只追加；V19 使应用角色只能删除已过期的 Webhook nonce。新增角色与授权脚本不包含密码，Core/Demo 使用独立 Docker secret 文件。真实 PostgreSQL 17 权限专项 12 项通过（`build/stage2b-task6-v19-roleless-green.log`），Schema 与告警回归 21 项通过（`build/stage2b-task6-v19-schema-alert-green.log`）。隔离 Compose 的 API readiness 为 UP，运行连接和迁移连接分别使用预期角色；有效 nonce 未被清理，过期 nonce 被清理。证据见 `build/stage2b-task6-expiry-compose-verified.log`，该项目专用容器、网络和数据卷已清理。

安全脚本、CI 工作流与异常策略已实现。首次完整扫描在 OWASP Dependency-Check 的 NVD API 首次同步停滞后按缺失报告判失败；同轮 `npm ci` 遇到 `ECONNRESET`。后续改用官方 NVD 数据源与持久缓存，再依据报告修复依赖漏洞。Linux PowerShell 容器本地试验因镜像仓库 TLS 握手超时未运行；Windows 行为测试和完整本地门禁已通过，CI 工作流要求在 Ubuntu 上再次运行，远端 CI 尚未触发。

OWASP 改用官方 NVD 数据源并关闭由 npm audit/Trivy 重复覆盖的 RetireJS 分析后，初次生成 187 条依赖记录的 JSON/HTML 报告，按 CVSS 7 门禁正确失败（`build/security/odc-datafeed-retirejs-disabled-standalone.log`）。依赖树确认高危 Vert.x 4.5.28 来自 Executor 的 Fabric8 运行时，Kotlin 2.3.21 存在于运行时；受影响的 HttpClient 5.5.1/HttpCore 5.3.6 位于 Testcontainers 的 shaded 测试依赖，而 Executor 的直接运行时版本已分别为 5.6.4/5.4.3（`build/stage2b-task6-odc-dependency-tree.log`）。运行时依赖已升级；测试依赖按下述范围规则处理。

Vert.x 已按实际使用模块固定为 4.5.34（独立提交 `b5f0c4b`），避免导入整套 BOM 意外降级 Log4j API；第一次 BOM 尝试的 Executor 红灯日志为 `build/stage2b-task6-vertx-executor-tests.log`，修正后依赖树确认 Log4j API 2.25.5，Executor 58 项全通过（`build/stage2b-task6-vertx-executor-tests-green.log`）。Kotlin 升为 2.4.20、Okio 升为 3.16.4 后，旧 `kotlin-stdlib-common` 不再出现在三个服务的依赖树（`build/stage2b-task6-kotlin-okio-upgrade-tree.log`）；该升级独立提交 `d5aba8d`。新鲜 Java 21 全 reactor `verify` 为 API 409 项（跳过 1）、Executor 58 项、Demo 13 项，零失败，见 `build/stage2b-task6-kotlin-okio-full-reactor.log`。

Trivy 首次在线文件系统预扫遇 Maven Central HTTP 429；按官方排障建议改用已填充 Maven 缓存的只读挂载与离线扫描，并用 Java BOM 对照关键依赖覆盖。离线预扫发现原 Tomcat embedded 11.0.24 的三条高危扫描项；按 Apache 已发布的修复版本将其升级到 11.0.26，作为独立提交 `0f95876`。升级后 Maven 依赖树显示三个 Tomcat embedded 构件均为 11.0.26，真实 PostgreSQL 17 的 Webhook/Schema 回归 11 项通过（`build/stage2b-task6-tomcat-focused.log`），Trivy 文件系统预扫为零高危漏洞和零高危配置问题（`build/security/trivy-filesystem-tomcat-green.json`）。

Web 旧运行镜像的 Trivy 预扫发现约 48 项 HIGH/CRITICAL Alpine 漏洞。升级到官方 NGINX 1.30.5 / Alpine 3.24 并升级 `libexpat` 后，成品镜像可构建、非 root NGINX 配置检查通过，Trivy HIGH/CRITICAL 扫描为 0 项（`build/security/trivy-image-web-fixed-preflight.json`）；基础镜像升级单独提交为 `ece0e7c`。
旧 Maven/Ubuntu Java 运行基础镜像还有 11 项可修复 HIGH 漏洞。先改为 Temurin 21.0.12+8 JRE / Alpine 3.24，提交 `648c09e`；核对官方安全基线后，再从 Alpine 3.24 安装带 SHA-256 校验的 Temurin 21.0.12.1+1 JRE，独立提交 `491235f`。三个最终成品镜像的 Trivy 预扫均为 0 项 HIGH/CRITICAL，包括 JAR 依赖（`build/security/trivy-image-api-jre-baseline-preflight.json`、`trivy-image-executor-jre-baseline-preflight.json`、`trivy-image-demo-jre-baseline-preflight.json`）。独立 Compose 项目 `stage2b-task6-jre-baseline` 的 API、Executor、Demo、Web 均健康，三个 Java 进程实际运行 21.0.12.1+1 且 readiness 返回 UP，Web HTTP 200，API 以 UID 10001 运行；证据见 `build/stage2b-task6-jre-baseline-compose-verified.log`。该项目专用容器、网络和数据卷已清理。

最终完整安全门禁见 `build/stage2b-task6-full-security-cleanup-final.log` 和 `build/security/`：Java 21 全 reactor 480 项（按既有安排跳过 1 项）零失败、零错误；Java/Node CycloneDX SBOM 分别含 336/540 个组件；`npm audit` 为零漏洞，前端 Lint、Vitest 和生产构建通过。OWASP 以真实依赖报告和 CVSS 7 阈值通过。Testcontainers 的 `docker-java-transport-zerodep:3.7.1` 含 shaded HttpClient、HttpCore 和 HttpCore H2；五条精确 PURL/CVE 规则只适用于经三个 Maven 模块依赖树验证的 test scope，过期日为 2026-10-25，未使用规则会使门禁失败。此前无效的 XML 组合通过红—绿行为测试修正；Windows 临时 Node 目录清理改用路径检查后的 .NET 删除，完整复跑已验证成功。

Trivy 对文件系统和 API、Executor、Demo、Web 四个成品镜像分别运行漏洞/配置与 secrets 扫描，十份 JSON 报告均无命中，归档于 `build/security/`。完整本地门禁退出码为 0；远端 GitHub CI 需在后续推送或 PR 时验证 Ubuntu 执行环境。

## Task 7：故障、重复投递、恶意输入和告警风暴验证（已完成）

新增隔离的 `fault-drill.ps1`，每次创建独立 Compose 项目和随机测试密钥，检查宿主端口、运行真实浏览器流程与 PostgreSQL 17/Valkey Testcontainers 专项，并在结束时清理该项目的容器、网络及数据卷。机器可读的脱敏结果仅保存在 `build/verification/faults/<run-id>/report.json`；原始诊断日志与浏览器产物单独保存在 `build/fault-logs/<run-id>/`。脚本在清理失败时也会把整轮标为失败。

Executor 停机与 Valkey 中断均从真实事故、诊断、审批走到待执行状态。中断期间没有执行尝试或目标副作用，也没有错误标记为已恢复；恢复后恰好一次执行尝试、一次目标副作用并完成验证。租约过期、fencing、旧 owner 迟到回报和重复 Stream 投递另以真实 PostgreSQL/Valkey 专项分别验证 6 项与 4 项。依赖故障专项 5 项覆盖 PostgreSQL、Valkey、模型、Loki、遥测故障；模型输出新增敏感信息拦截，在持久化方案前拒绝凭据 canary。提示注入语料包含 35 条、11 种语言，经真实 Spring AI 路径运行 3 项，未产生未授权操作或泄露 canary。

Webhook 的 Demo 专用高流量来源配置为每分钟 10,000 次、突发 200 次，正式默认仍为每分钟 60 次、突发 20 次。默认限流场景验证 429 与 `Retry-After`；红阶段低限额风暴仅接受 79/6,000 次，`build/stage2b-task7-alert-storm-baseline-red.log` 保存该证据。修复后同一事故指纹的 60 秒、每秒 100 次签名投递全部接受，形成 6,000 条 occurrence event，始终只有一个活跃事故。最终两轮完整报告 `build/verification/faults/20260925-190346-cdbe1ded/report.json`、`build/verification/faults/20260925-192747-157a24b2/report.json` 均为 PASS：各接受 6,000/6,000 次，p95 分别为 17.60/13.76 毫秒，恢复场景各一次副作用，清理均成功。两轮之间 E: 外置盘短暂断连使一次运行中断，Docker Desktop 的 E: bind mount 随后失效；中断项目已按独立项目名清理，重启 Docker Desktop 后只读挂载检查通过，再进行上述第二轮完整复跑。中断与启动失败的记录未计入通过次数。

Java 21 全 reactor 回归 `build/stage2b-task7-all-java-it-final.log`：API 419 项（既有安排跳过 1 项）、Executor 58 项、Demo 13 项，零失败；前端 Lint 和格式检查通过。只读复审未发现阻断或高优先级问题。外部生产服务故障由上述受控依赖和隔离栈模拟，当前证据属于本地验收。
