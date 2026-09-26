# 已知限制与投运前事项

本页区分代码能力和外部环境验收。最终 `v1.0.0` 标签只在全部本地发布门禁和所需真实环境验证完成后创建。

- **外部部署：**仓库提供生产 Compose、TLS 样例和运行手册；目前没有获提供的生产主机、域名、OIDC 客户端、目标服务、密钥或独立恢复测试数据库。不能据配置渲染声称已上线，生产烟测和真实备份恢复结果须另行记录。
- **首条真实动作：**控制平面允许经独立评审发布固定的 `production-http/restart_service`，目标别名 `checkout`，`replicas` 为 1–3，且以预设 HTTPS 健康探针验证。Executor 的 HTTP 目录按固定地址、方法和范围执行；实际目标端的稳定幂等键与 fencing 持久化尚需部署方核验。已分发后的未知结果升级人工，不自动重放。Kubernetes 适配器有安全契约测试与最小 RBAC 样例，但首版控制平面不发布 Kubernetes 动作。
- **模型和证据源：**Demo 默认确定性模型；生产拒绝 deterministic，必须配置真实提供方或 `manual-only`。真实模型效果、供应商延迟/成本和组织 Prometheus/Loki 权限尚需在目标环境验收。生产 Compose 目前仍要求模型密钥文件路径等模型变量，即使选择 `manual-only`；这是部署配置便利性限制，不会调用模型。
- **监控认证：**生产 Compose 只传 Prometheus/Loki 的固定 URL，尚未提供独立的上游认证 secret/代理配置。需要认证时由部署方提供受控内部只读代理，或在新增配置与测试后接入；不能把凭据放在 URL 中。
- **部署架构：**Compose 适合单主机或受控小规模部署，不提供跨主机高可用、零中断滚动、数据库 PITR、异地容灾或网络目标级出口 ACL。Executor 的出口 allowlist 必须由宿主/云网络策略设置；仅加入 Docker 网络不构成目的地限制。
- **健康验证：**首条生产 HTTP 动作只支持固定 `checkout` HTTPS JSON readiness 中 `status=UP` 的探针。与目标 SLO、多个健康区域或更细粒度恢复阈值的映射需按服务扩展并新增审查/测试。
- **备份：**脚本输出 PostgreSQL custom 归档、SHA-256 与 Flyway 版本，恢复演练仅在显式隔离实例创建临时库。调度、加密、保留、PITR 与组织级 RPO/RTO 由部署方负责；在目标测试实例演练前不能称恢复门禁通过。
- **发布：**Release CI 只在被推送的版本 tag 上验证并上传不可变流水线工件；它不推送镜像、不创建 GitHub Release，也不部署公网。远端 GitHub Actions 结果需在实际推送后单独核验。
