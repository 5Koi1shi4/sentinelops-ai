# Trivy 漏洞例外策略

默认不忽略任何漏洞。只有经过负责人评审的临时例外才能进入 `.trivyignore.yaml`；扫描脚本会在运行 Docker 或 Trivy 之前拒绝格式错误、字段缺失、已过期或期限超过 90 天的例外。

每条例外必须包含：

- `id`：CVE 标识。
- `purls`：受影响软件包的精确 Package URL，必须包含非空 `@version`；可附带合法 qualifiers 和 subpath。不得使用通配符，也不能仅写包名，否则会扩大到所有版本。
- `affected`：以分号分隔的精确作用域：`image: <security-scan 中声明的镜像标签>` 限定该镜像，`path: <仓库相对文件>` 限定文件系统扫描；至少需要一个 `image:` 或 `path:`。`component:` 可补充说明受影响构件，但不会单独限定扫描范围。
- `expired_at`：UTC 日期格式 `YYYY-MM-DD`，必须在今天之后的 90 天内。
- `reason`：接受风险的具体原因。
- `compensating_control`：目前生效的补偿控制。
- `owner`：负责跟进修复和续期的个人或团队。

示例（不得直接复制占位内容作为正式例外）：

```yaml
vulnerabilities:
  - id: CVE-2026-12345
    purls:
      - "pkg:maven/org.example/example-library@1.2.3"
    affected: "image: sentinelops/ops-api:stage2b-security; path: pom.xml; component: org.example:example-library:1.2.3"
    expired_at: 2026-10-15
    reason: "受影响代码路径在当前发行版中未启用。"
    compensating_control: "入口网关阻止访问受影响端点，并持续记录拒绝事件。"
    owner: "security@example.com"
```

`security-scan.ps1` 会在运行测试或扫描器之前验证上述字段，再按文件系统和每个镜像目标分别生成临时 ignore 文件。文件系统例外带有精确 `paths` 列表；镜像例外只进入匹配的镜像 ignore 文件，避免一个镜像的批准扩散到其他构件。Trivy 仅对匹配的 CVE、PURL 和目标应用例外；例外到期后必须先修复或重新评审，不得自动续期。

Trivy 漏洞和配置扫描只拦截 HIGH、CRITICAL 级别；secret scanner 单独运行且不按严重级别过滤，发现任何 secret 都会失败。例外只用于漏洞，不会跳过 secrets scan。

## OWASP Dependency-Check 临时例外

`dependency-check-exceptions.json` 只记录已验证为 test-scope 的短期例外。每项必须精确列出 CVE、带版本的漏洞 PURL、受影响的 test dependency PURL、原因、补偿控制、负责人和到期日；到期日最多 90 天。脚本会在 Dependency-Check 前从每个 Java 模块生成 Maven 依赖树，确认受影响传递依赖仅在 test scope，且漏洞 PURL 的精确版本不在任何非 test scope。缺少依赖树、坐标出现在运行时范围、字段无效或例外过期都会使门禁失败。

验证通过后，脚本才会生成按精确 PURL 和 CVE 匹配的 OWASP suppression XML，并启用未使用规则失败。Java 测试、运行时 CVSS 7.0 门禁和最终镜像 Trivy 扫描仍会执行；不得用全局跳过 test scope 替代精确例外。
