# 生产 Runbook 适配器安全配置

Task 4 引入 `production-http` 与 `kubernetes` 两种 Executor 适配器。只有设置相应的 `SENTINELOPS_TARGETS_HTTP_CATALOG_FILE` 或 `SENTINELOPS_TARGETS_KUBERNETES_CATALOG_FILE` 才注册适配器；文件在启动时读取并验证，部署时须以只读卷挂载。目录由运维人员管理，Runbook 步骤只提供动作 key、目标别名和受限参数，不能提供 URL、集群、命名空间、资源名、HTTP 方法或补丁正文。目录更改需要重启 Executor，并使用已发布的不可变 Runbook 版本重新审批。

## HTTP 目录

`SENTINELOPS_TARGETS_HTTP_CATALOG_FILE` 指向如下 JSON。示例地址和 scope 要换成目标系统实际的已授权端点；OAuth2 凭据仍由 Executor 的独立客户端配置提供。

```json
{
  "actions": {
    "restart_service": {
      "targetAlias": "checkout",
      "uri": "https://checkout.example.com/ops/restart",
      "method": "POST",
      "audience": "checkout-service",
      "scope": "runbook:execute",
      "parameters": {
        "replicas": {
          "type": "integer",
          "minimum": 1,
          "maximum": 3,
          "allowedValues": []
        }
      },
      "bodyTemplate": {"replicas": "${replicas}"},
      "allowPrivateNetwork": false
    }
  }
}
```

动作 key 对应已签名步骤的 `operation`。每个参数必须有类型和边界，并被正文模板精确使用；额外参数、路径变量、URL 查询和未注册目标一律拒绝。正文模板只允许扁平 JSON 标量。HTTP 动作仅允许 POST、PUT、PATCH；外部目标须使用 HTTPS，且不能用 IP 字面量、localhost 或 `.local`，执行前会检查 DNS 解析结果是否为私网地址。客户端将已检查的地址固定到本次连接，TLS 仍按目录主机名验证，避免检查后 DNS 重绑定。确实需要私网目标时，目录可对该固定端点显式设置 `allowPrivateNetwork: true`；它不允许 Runbook 自行构造地址。客户端禁止代理、跟随重定向和自动重试，连接超时 2 秒、请求超时 5 秒。请求传递 `Idempotency-Key` 与 `X-SentinelOps-Fencing-Token`，目标系统必须验证并持久化两者，才可能进一步评估未知结果重放。当前控制平面没有将生产 HTTP 动作列入重放白名单，未知结果升级人工核对。

目标在执行后仍可能返回 3xx；适配器拒绝跟随，但把已分发的 3xx 视为未知结果，要求人工核对，而不报告为确定失败。

生产网络策略仍应只允许 Executor 访问目录中的实际目标端点，且目录本身必须由可信部署流程审查和只读挂载。

## Kubernetes 目录与身份

`SENTINELOPS_TARGETS_KUBERNETES_CATALOG_FILE` 指向如下 JSON；一个 Executor 实例只允许一个集群。集群地址须为 HTTPS。Executor 从 Pod 的 `/var/run/secrets/kubernetes.io/serviceaccount/token` 和 `ca.crt` 读取服务账号凭据和信任根，不使用用户 kubeconfig。Token 每次使用时从挂载文件重新读取。缺失或空凭据拒绝执行；连接超时 2 秒、请求超时 5 秒，禁用客户端自动重试和跳过 TLS 验证。

```json
{
  "targets": {
    "checkout": {
      "cluster": "https://kubernetes.example.com",
      "namespace": "production",
      "kind": "Deployment",
      "name": "checkout-api",
      "minReplicas": 1,
      "maxReplicas": 5,
      "operations": ["restart_deployment", "scale_deployment"]
    }
  }
}
```

`restart_deployment` 不接受参数，只写入 `spec.template.metadata.annotations['sentinelops.io/restarted-at']`；`scale_deployment` 只接受目录界限内的整数 `replicas`，只写入 `spec.replicas`。两者先读取已登记 Deployment 的 `resourceVersion`，JSON Patch 以 `test /metadata/resourceVersion` 作并发前置条件，field manager 为 `sentinelops-executor`。不提供通用 patch、delete、Pod exec、Secret 或跨资源 API。Kubernetes API 不提供此 Runbook 的目标端稳定幂等键与 fencing 契约，因此这些操作在未知结果后不自动重放。

将 [executor-rbac.yaml](../../deploy/kubernetes/executor-rbac.yaml) 中的 namespace、ServiceAccount 和 `resourceNames` 改为该实例目录中的目标，然后用 `kubectl auth can-i --as=system:serviceaccount:production:sentinelops-executor get deployments.apps/checkout-api -n production` 验证允许的访问，并分别验证 `delete deployments.apps/checkout-api`、`create pods/exec`、`get secrets` 返回 `no`。Role 只在一个 namespace 生效；新增目标须更新目录和 Role 的资源名。Kubernetes 的 `resourceNames` 约束可用于按名称的 get/patch/update 请求；本配置不授予 list/watch、delete 或集群范围权限。[Kubernetes RBAC 文档](https://kubernetes.io/docs/reference/access-authn-authz/rbac/)列明了这些权限语义。

## 投运边界

生产目录、OAuth2 目标账号、Kubernetes ServiceAccount、网络策略以及目标端幂等/fencing 验证必须由部署方按实际环境配置。控制平面现在允许经独立评审发布固定的 `production-http/restart_service` 定义，参数仅为 1–3 的 `replicas`，目标别名固定为 `checkout`，恢复验证固定为 `production_checkout_health` 的 HTTPS JSON readiness。真实 PostgreSQL 17 集成测试覆盖发布后的不可变性，以及经审批创建 execution、领取并签出绑定目标/操作/参数的票据。生产 profile 不注册 Demo HTTP 适配器或 Demo 健康探针。实际外部目标的调用、幂等键与 fencing 持久化仍须在部署环境验证；未知结果继续升级人工。Kubernetes 动作目前没有进入控制平面发布白名单，不能通过直接修改数据库或复用 Demo 版本绕过发布与审批。
