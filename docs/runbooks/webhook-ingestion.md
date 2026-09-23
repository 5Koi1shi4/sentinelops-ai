# Alertmanager Webhook 接入

控制平面只接收已登记来源的签名 Webhook。请求地址为 `POST /api/v1/integrations/alertmanager/webhook`，正文是未经重排或重新编码的 Alertmanager v4 JSON 字节。发送方设置以下请求头：

| 请求头 | 值 |
| --- | --- |
| `X-Sentinel-Source` | 已登记的来源名，例如 `demo-alertmanager` |
| `X-Sentinel-Timestamp` | Unix 秒时间戳，与接收时钟相差不超过 300 秒 |
| `X-Sentinel-Nonce` | 每次投递新生成的 128 位随机数，32 位十六进制编码 |
| `X-Sentinel-Signature` | `v1=` 加 64 位十六进制 HMAC-SHA256 |

签名输入为 `timestamp + "\n" + nonce + "\n" + rawBody` 的 UTF-8 前缀和原始正文；使用该来源专属密钥计算 HMAC-SHA256。签名验证采用常数时间比较。重新投递同一业务事件时必须使用新时间戳和 nonce，业务事件 ID 保持不变。已使用的 nonce 以 SHA-256 摘要持久化，重复 nonce 返回 409；过期记录由后台每分钟分批清理。

配置 `SENTINELOPS_WEBHOOK_SOURCE_REFS`，格式为 `source=env:SECRET_ENV_NAME`，多个来源以逗号分隔。每个密钥至少 32 字节，值只通过对应环境变量注入，不写进来源映射。生产 profile 若没有已登记来源或引用无法解析，会拒绝启动。来源配置变更需要重启接收服务；密钥轮换应先登记新来源并切换发送方，再撤销旧来源。

接收顺序固定为：1 MiB 正文上限、已知来源、时间戳、nonce 格式、签名、持久化 nonce、来源限流、JSON 与 schema 校验、事故幂等摄取。单次最多 200 条 alert；每个标签映射最多 100 项，每个标签值最多 4096 UTF-8 字节，全部 annotation 键和值合计最多 64 KiB。来源每实例限额为每分钟 60 次、瞬时桶容量 20 次；429 包含 `Retry-After: 1`。413 表示正文或 alert 数量超限，401 表示来源、时钟或签名无效，400 表示内容无效。发送方应按 `Retry-After` 与非 2xx 响应安排有界重投。

Alertmanager 无法直接生成逐请求的时间戳和 HMAC 头。本地 Demo 的 `demo-alert-relay` 容器从私有 `control-plane` 网络接收其原始 JSON，生成新 nonce 并签名后向 `ops-api` 转发一次；它不发布宿主机端口。下游失败时中继返回 502，重投由 Alertmanager 负责。`demo-service` 的常规容器不启用中继路由；该路由要求 `SENTINELOPS_DEMO_MODE=true` 和 `SENTINELOPS_DEMO_ALERT_RELAY_MODE=true` 同时设置。Demo Compose 中的密钥默认值仅供本机演示，生产部署不得使用 Demo Compose 或该默认值。
