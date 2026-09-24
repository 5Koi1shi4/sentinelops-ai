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

## 下一实施任务

按计划开始 Task 5：隐私安全的 OpenTelemetry、业务指标与运维仪表板。
