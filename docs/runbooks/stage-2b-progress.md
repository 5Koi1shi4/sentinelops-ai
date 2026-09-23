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

## 下一实施任务

按计划开始 Task 3：执行租约、心跳、fencing 和未知结果语义。
