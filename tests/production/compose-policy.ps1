$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$composePath = Join-Path $repositoryRoot 'deploy\compose\compose.production.yml'
$caddyPath = Join-Path $repositoryRoot 'deploy\caddy\Caddyfile'
$environmentPath = Join-Path $repositoryRoot 'deploy\env\production.env.example'
$failures = [System.Collections.Generic.List[string]]::new()

function Read-PolicyFile([string] $Path, [string] $Name) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        $failures.Add("Missing $Name at $Path")
        return ''
    }

    return [System.IO.File]::ReadAllText($Path)
}

function Assert-Match([string] $Text, [string] $Pattern, [string] $Message) {
    if ($Text -notmatch $Pattern) {
        $failures.Add($Message)
    }
}

function Get-ServiceBlock([string] $Text, [string] $ServiceName) {
    $pattern = '(?ms)^  ' + [regex]::Escape($ServiceName) + ':\s*\r?\n(?<body>.*?)(?=^  [A-Za-z0-9_-]+:\s*$|^[A-Za-z][A-Za-z0-9_-]*:\s*$|\z)'
    $match = [regex]::Match($Text, $pattern)
    if (-not $match.Success) { return $null }
    return $match.Groups['body'].Value
}

$compose = Read-PolicyFile $composePath 'production Compose file'
$caddy = Read-PolicyFile $caddyPath 'Caddyfile'
$environment = Read-PolicyFile $environmentPath 'production environment example'

if ($compose) {
    foreach ($serviceName in @('ops-api', 'ops-executor', 'web', 'caddy')) {
        $block = Get-ServiceBlock $compose $serviceName
        if ($null -eq $block) {
            $failures.Add("Missing required production service '$serviceName'")
            continue
        }

        if ($serviceName -ne 'web') {
            Assert-Match $block '(?m)^    user:\s*["'']?10001:10001["'']?\s*$' "$serviceName must run as a non-root UID/GID"
        }
        Assert-Match $block '(?m)^    read_only:\s*true\s*$' "$serviceName must use a read-only root filesystem"
        Assert-Match $block '(?m)^    tmpfs:' "$serviceName must provide a temporary filesystem"
        Assert-Match $block '(?m)^    cap_drop:\s*\[\s*ALL\s*\]\s*$' "$serviceName must drop all Linux capabilities"
        Assert-Match $block '(?m)^    security_opt:\s*\[\s*no-new-privileges:true\s*\]\s*$' "$serviceName must disallow privilege gain"
        Assert-Match $block '(?m)^    healthcheck:' "$serviceName must define a health check"
        Assert-Match $block '(?m)^    restart:\s*unless-stopped\s*$' "$serviceName must define a restart policy"
        Assert-Match $block '(?m)^    cpus:\s*["'']?[0-9]' "$serviceName must define a CPU limit"
        Assert-Match $block '(?m)^    mem_limit:\s*["'']?[0-9]' "$serviceName must define a memory limit"
    }

    $executor = Get-ServiceBlock $compose 'ops-executor'
    if ($null -ne $executor) {
        Assert-Match $executor '(?m)^      SPRING_DATA_REDIS_SSL_ENABLED:\s*\$\{SENTINELOPS_VALKEY_SSL_ENABLED:\?[^}]+\}' 'Executor must explicitly configure Valkey TLS'
        Assert-Match $executor '(?m)^    networks:' 'Executor must use an explicitly scoped network set'
        Assert-Match $executor '(?m)^      - executor-egress\s*$' 'Executor must use the operator-managed restricted egress network'
        Assert-Match $executor '(?m)^      - source: executor_oauth_client_secret\s*$' 'Executor must receive its dedicated OAuth client secret'
        Assert-Match $executor '(?m)^      SENTINELOPS_TARGETS_HTTP_CATALOG_FILE:\s*/run/config/http-actions\.json\s*$' 'Executor must load the production HTTP action catalog from its mounted file'
        Assert-Match $executor '(?is)source:\s*\$\{SENTINELOPS_TARGETS_HTTP_CATALOG_FILE:\?[^}]+\}.*?target:\s*/run/config/http-actions\.json.*?read_only:\s*true' 'Executor HTTP catalog must be a required read-only external file'
        if ($executor -match '(?m)^      - frontend\s*$') { $failures.Add('Executor must not join the browser/frontend network') }
        Assert-Match $executor '(?m)^      SPRING_CONFIG_IMPORT:\s*optional:configtree:/run/secrets/' 'Executor secrets must be delivered through a read-only secret config tree'
        if ($executor -match '(?i)SENTINELOPS_DB_|spring\.datasource|jdbc:postgres|db_(?:admin|app|migrator)_password|model_api_key|webhook') {
            $failures.Add('Executor must not receive database, model, or webhook credentials')
        }
    }

    $api = Get-ServiceBlock $compose 'ops-api'
    if ($null -ne $api) {
        Assert-Match $api '(?m)^      SPRING_DATA_REDIS_SSL_ENABLED:\s*\$\{SENTINELOPS_VALKEY_SSL_ENABLED:\?[^}]+\}' 'API must explicitly configure Valkey TLS'
        Assert-Match $api '(?m)^      SENTINELOPS_PROD_CHECKOUT_HEALTH_URL:\s*\$\{SENTINELOPS_PROD_CHECKOUT_HEALTH_URL:\?[^}]+\}' 'API must receive the required production HTTPS health probe URL'
        Assert-Match $api '(?m)^      SPRING_CONFIG_IMPORT:\s*optional:configtree:/run/secrets/' 'API secrets must be delivered through a read-only secret config tree'
        foreach ($secret in @('db_app_password', 'db_migrator_password', 'valkey_password', 'execution_signing_key', 'alertmanager_webhook_secret', 'model_api_key')) {
            Assert-Match $api ('(?m)^      - source: ' + [regex]::Escape($secret) + '\s*$') "API must receive its '$secret' secret as a file"
        }
    }

    foreach ($serviceName in @('postgres', 'valkey')) {
        $block = Get-ServiceBlock $compose $serviceName
        if ($null -ne $block) {
            Assert-Match $block '(?m)^    profiles:\s*\[\s*["'']local-data["'']\s*\]\s*$' "$serviceName must be an explicit local-data option"
            if ($block -match '(?m)^    ports:') {
                $failures.Add("$serviceName must not publish a host port")
            }
        }
    }

    foreach ($forbidden in @('demo-service', 'demo-checkout', 'SENTINELOPS_DEMO_SERVICE_URL', 'keycloak', 'docker\.sock', 'privileged:\s*true', 'Access-Control-Allow-Origin\s*[:=]\s*\*')) {
        if ($compose -match $forbidden) { $failures.Add("Production Compose contains forbidden configuration matching '$forbidden'") }
    }

    foreach ($variable in @(
        'SENTINELOPS_DOMAIN', 'SENTINELOPS_DB_URL', 'SENTINELOPS_DB_USERNAME',
        'SENTINELOPS_DB_MIGRATOR_USERNAME', 'SENTINELOPS_DB_ADMIN_PASSWORD_FILE',
        'SENTINELOPS_DB_APP_PASSWORD_FILE', 'SENTINELOPS_DB_MIGRATOR_PASSWORD_FILE',
        'SENTINELOPS_OIDC_ISSUER', 'SENTINELOPS_OIDC_JWK_SET_URI', 'SENTINELOPS_OIDC_TOKEN_URI',
        'SENTINELOPS_ALLOWED_ORIGINS', 'SENTINELOPS_OIDC_BROWSER_ORIGIN',
        'SENTINELOPS_OIDC_BROWSER_CLIENT_ID', 'SENTINELOPS_OIDC_REDIRECT_URI',
        'SENTINELOPS_EXECUTOR_ID', 'SENTINELOPS_EXECUTOR_CLIENT_ID', 'SENTINELOPS_EXECUTOR_CLIENT_SECRET_FILE',
        'SENTINELOPS_EXECUTION_SIGNING_KEY_FILE', 'SENTINELOPS_WEBHOOK_SECRET_FILE',
        'SENTINELOPS_TARGETS_HTTP_CATALOG_FILE', 'SENTINELOPS_PROD_CHECKOUT_HEALTH_URL',
        'SENTINELOPS_AI_PROVIDER', 'SENTINELOPS_AI_BASE_URL', 'SENTINELOPS_AI_MODEL',
        'SENTINELOPS_AI_EMBEDDING_MODEL', 'SENTINELOPS_AI_EMBEDDING_DIMENSIONS',
        'SENTINELOPS_MODEL_API_KEY_FILE', 'SENTINELOPS_PROMETHEUS_URL', 'SENTINELOPS_LOKI_URL',
        'SENTINELOPS_VALKEY_HOST', 'SENTINELOPS_VALKEY_PORT', 'SENTINELOPS_VALKEY_USERNAME',
        'SENTINELOPS_VALKEY_SSL_ENABLED',
        'SENTINELOPS_VALKEY_PASSWORD_FILE', 'SENTINELOPS_VALKEY_ACL_FILE',
        'SENTINELOPS_CADDY_DATA_DIR', 'SENTINELOPS_CADDY_CONFIG_DIR',
        'SENTINELOPS_EXECUTOR_EGRESS_NETWORK'
    )) {
        $required = '\$\{' + [regex]::Escape($variable) + ':\?[^}]+\}'
        Assert-Match $compose $required "Compose must require '$variable' with an explanatory interpolation error"
        Assert-Match $environment ("(?m)^" + [regex]::Escape($variable) + '=') "production.env.example must document '$variable'"
    }

    $interpolatedVariables = [regex]::Matches($compose, '\$\{([A-Z][A-Z0-9_]*):\?[^}]+\}') |
        ForEach-Object { $_.Groups[1].Value } |
        Sort-Object -Unique
    foreach ($variable in $interpolatedVariables) {
        Assert-Match $environment ("(?m)^" + [regex]::Escape($variable) + '=') "production.env.example must document every required Compose input ('$variable')"
    }

    if ($compose -match '(?i)(?:POSTGRES_PASSWORD|SENTINELOPS_\w*PASSWORD)\s*:\s*(?:sentinelops|sentinelops-demo-db|password|changeme)\b' -or
        $environment -match '(?im)^SENTINELOPS_\w*PASSWORD\s*=\s*(?:sentinelops|sentinelops-demo-db|password|changeme)\b') {
        $failures.Add('Production configuration must not contain a default database or Valkey password')
    }

    if ($compose -match '(?i)(?:SENTINELOPS_MODEL_API_KEY|SENTINELOPS_EXECUTOR_CLIENT_SECRET)\s*:\s*(?!\$\{)' -or
        $compose -match '(?i)api[-_ ]?key\s*:\s*["'']?(?:sk-|[A-Za-z0-9]{24,})') {
        $failures.Add('Production Compose must not inline model or Executor API keys')
    }

    if ($environment -match '(?im)^(?:SENTINELOPS_.*PASSWORD|SENTINELOPS_.*SECRET|SENTINELOPS_.*API_KEY)=.+') {
        $failures.Add('production.env.example must contain secret file paths, not secret values')
    }
    Assert-Match $environment '(?m)^SENTINELOPS_DB_URL=jdbc:postgresql://[^\s]+\?sslmode=verify-full(?:&[^\s]*)?$' 'External PostgreSQL example must require certificate and hostname verification'
    Assert-Match $environment '(?m)^SENTINELOPS_VALKEY_SSL_ENABLED=true\s*$' 'External Valkey example must enable TLS'
}

if ($caddy) {
    Assert-Match $caddy '\{\$SENTINELOPS_DOMAIN\}' 'Caddy must bind its TLS site to the configured domain'
    Assert-Match $caddy '(?i)Strict-Transport-Security' 'Caddy must set HSTS on its TLS site'
    Assert-Match $caddy '(?is)/healthz.*?/actuator/health/readiness' 'Caddy must expose only API readiness through /healthz'
    Assert-Match $caddy '(?is)/actuator/\*.*?404' 'Caddy must deny public actuator paths'
    Assert-Match $caddy '(?is)/internal/\*.*?404' 'Caddy must deny public internal paths'
    Assert-Match $caddy '(?is)/api/checkout(?:\s|/).*?respond @demoCheckout 404' 'Caddy must deny the Demo checkout route'
    Assert-Match $caddy '(?i)reverse_proxy\s+web:8080' 'Caddy must send ordinary browser routes to the web service'
}

if ($failures.Count -gt 0) {
    Write-Output 'Production Compose policy: FAIL'
    foreach ($failure in $failures) { Write-Output " - $failure" }
    exit 1
}

Write-Output 'Production Compose policy: PASS'
