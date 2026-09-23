# ADR 0003：生产身份与信任边界

状态：已采纳（Stage 2B Task 2）

## 决策

浏览器通过 OIDC Authorization Code + PKCE 获取短期访问令牌。API 仅验证可信签名、精确 issuer、无默认宽限期的有效时间、非空 subject 和 `sentinelops-api` audience；内部 Executor 路由使用独立安全链，要求 `sentinelops-executor` audience 与 `sentinelops_executor` realm role。具有两种受众的混合令牌被拒绝。客户端提供的角色、服务范围或审批执行参数不能改变已验证的 JWT 与服务端固定提案。

API 只接受明确配置的跨域来源，Bearer 请求不携带跨域凭据。生产模式要求 HTTPS OIDC issuer/JWKS 和浏览器来源、非默认数据库密码、非确定性 AI provider、环境变量形式的签名私钥引用；缺少或不安全的配置使启动失败。Demo/test profile、Demo 用户和内联私钥不得与 production 共存；生产 profile 始终使用外部签名密钥配置。静态站点设置 CSP 与浏览器安全响应头；TLS 入口负责生产 HSTS。

每次 API 请求将可信声明同步到 PostgreSQL，随后核对该主体最新签发时间和角色/服务授权快照。新令牌撤销授权后，旧令牌的下一次请求返回 403；同秒不同声明只保留授权交集。此控制依赖身份提供方及时发行并使用新的撤销令牌；纯离线 JWT 在此之前仍可用到过期，因此生产身份提供方应配置短访问令牌寿命和紧急撤销流程。

## 原因与后果

API 与 Executor 的受众分离，避免服务账号因持有内部令牌而获得用户 API 权限。数据库快照在服务端防止较早令牌恢复已撤销的角色，不把浏览器缓存作为授权真相。无时间宽限期要求 API 与身份提供方时钟同步。生产密钥只经 `env:` 引用解析，配置文件和错误消息不含私钥值。正式部署仍须由组织提供 OIDC、TLS 域名、密钥交付和数据库凭据，并运行生产烟测。
