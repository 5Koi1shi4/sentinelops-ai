[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

$repo = Split-Path -Parent $PSScriptRoot
$report = Join-Path $repo ('build/verification/stage2a/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$compose = @('compose', '-p', 'sentinelops-stage2a',
    '-f', (Join-Path $repo 'deploy/compose/compose.core.yml'),
    '-f', (Join-Path $repo 'deploy/compose/compose.demo.yml'))
$stackTouched = $false
$passed = $false
New-Item -ItemType Directory -Path $report -Force | Out-Null

function Invoke-Checked {
    param([string]$Name, [string]$Program, [string[]]$Arguments, [string]$Log)
    Write-Host "`n==> $Name"
    $path = Join-Path $report $Log
    & $Program @Arguments 2>&1 | Tee-Object -FilePath $path
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed ($LASTEXITCODE). See $path"
    }
}

function Invoke-Compose {
    param([string]$Name, [string[]]$Arguments, [string]$Log)
    Invoke-Checked -Name $Name -Program 'docker' -Arguments ($compose + $Arguments) -Log $Log
}

function Assert-Ready {
    param([string]$Name, [string]$Url, [string]$Expected)
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -TimeoutSec 5 -UseBasicParsing
            $body = if ($response.Content -is [byte[]]) {
                [Text.Encoding]::UTF8.GetString($response.Content)
            } else {
                [string]$response.Content
            }
            if ($response.StatusCode -eq 200 -and $body.Contains($Expected)) {
                Write-Host "READY $Name"
                return
            }
        } catch {
            # Startup and Loki ring joining can briefly return 503.
        }
        Start-Sleep -Seconds 1
    }
    throw "$Name was not ready within 90 seconds."
}

function Invoke-OptionalProviderSmoke {
    $provider = $env:SENTINELOPS_AI_PROVIDER
    if ($provider -notin @('openai-compatible', 'ollama')) {
        Write-Host 'Optional real-provider smoke: no provider configured.'
        return
    }
    $base = $env:SENTINELOPS_AI_BASE_URL
    $model = $env:SENTINELOPS_AI_MODEL
    if (-not $base -or -not $model) {
        throw 'Optional provider smoke requires SENTINELOPS_AI_BASE_URL and SENTINELOPS_AI_MODEL.'
    }
    $headers = @{}
    $endpoint = ''
    if ($provider -eq 'openai-compatible') {
        $reference = $env:SENTINELOPS_AI_API_KEY_SECRET_REF
        if (-not $reference -or $reference -notmatch '^env:[A-Z][A-Z0-9_]{0,127}$') {
            throw 'Optional provider smoke requires an env secret reference.'
        }
        $key = [Environment]::GetEnvironmentVariable($reference.Substring(4))
        if (-not $key) { throw 'Optional provider secret reference is unresolved.' }
        $headers.Authorization = "Bearer $key"
        $endpoint = $base.TrimEnd('/') + '/chat/completions'
    } else {
        $endpoint = $base.TrimEnd('/') + '/api/chat'
    }
    $body = if ($provider -eq 'ollama') {
        @{ model = $model; stream = $false; messages = @(@{ role = 'user'; content = 'Reply OK.' }) }
    } else {
        @{ model = $model; max_tokens = 8; messages = @(@{ role = 'user'; content = 'Reply OK.' }) }
    }
    $response = Invoke-RestMethod -Method Post -Uri $endpoint -Headers $headers -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 5 -Compress) -TimeoutSec 90
    if ($provider -eq 'ollama' -and -not $response.message.content) {
        throw 'Optional Ollama smoke returned no message.'
    }
    if ($provider -eq 'openai-compatible' -and -not $response.choices) {
        throw 'Optional compatible-provider smoke returned no choices.'
    }
    @{ provider = $provider; model = $model; passed = $true } |
        ConvertTo-Json -Compress | Set-Content -LiteralPath (Join-Path $report 'provider-smoke.json')
    Write-Host 'Optional real-provider smoke passed.'
}

Push-Location $repo
try {
    $javaExecutableName = if ($IsWindows) { 'java.exe' } else { 'java' }
    $javaHome = $env:JAVA_HOME
    $javaExecutable = if ($javaHome) { Join-Path $javaHome "bin/$javaExecutableName" } else { $null }
    $javaVersion = if ($javaExecutable -and (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
        & $javaExecutable -version 2>&1 | Out-String
    } else { '' }
    if ($javaVersion -notmatch '(?i)(?:version\s+")?21\.') {
        $localJdk = Get-ChildItem -LiteralPath (Join-Path $repo '.toolchains/jdk-21') -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "bin/$javaExecutableName") -PathType Leaf } |
            Select-Object -First 1
        if (-not $localJdk) { throw 'Java 21 is required in JAVA_HOME or .toolchains/jdk-21/.' }
        $javaHome = $localJdk.FullName
        $javaExecutable = Join-Path $javaHome "bin/$javaExecutableName"
        $javaVersion = & $javaExecutable -version 2>&1 | Out-String
    }
    if ($javaVersion -notmatch '(?i)(?:version\s+")?21\.') {
        throw 'The selected Java runtime is not Java 21.'
    }
    $env:JAVA_HOME = $javaHome
    $env:Path = "$(Join-Path $javaHome 'bin')$([System.IO.Path]::PathSeparator)$env:Path"
    $mavenProgram = if ($IsWindows) { Join-Path $repo 'mvnw.cmd' } else { 'bash' }
    $mavenPrefix = if ($IsWindows) { @() } else { @((Join-Path $repo 'mvnw')) }

    Invoke-Checked 'Maven full reactor verification' $mavenProgram ($mavenPrefix + @(
        '-B', '-ntp', '-Dtest=*Test,*Tests,*IT', '-Dsurefire.failIfNoSpecifiedTests=false', 'verify'
    )) 'maven-verify.log'
    Invoke-Checked 'Frontend clean install' 'npm' @('--prefix', 'web/ops-console', 'ci') 'npm-ci.log'
    Invoke-Checked 'OpenAPI type drift' 'npm' @('--prefix', 'web/ops-console', 'run', 'api:check') 'api-check.log'
    Invoke-Checked 'Frontend lint' 'npm' @('--prefix', 'web/ops-console', 'run', 'lint') 'lint.log'
    Invoke-Checked 'Frontend tests' 'npm' @('--prefix', 'web/ops-console', 'run', 'test', '--', '--run') 'vitest.log'
    Invoke-Checked 'Frontend build' 'npm' @('--prefix', 'web/ops-console', 'run', 'build') 'build.log'
    Invoke-Checked 'Frontend format' 'npm' @('--prefix', 'web/ops-console', 'run', 'format:check') 'format.log'
    Invoke-Checked 'Playwright Chromium availability' 'npm' @(
        '--prefix', 'web/ops-console', 'exec', '--', 'playwright', 'install', 'chromium'
    ) 'playwright-install.log'

    for ($round = 1; $round -le 2; $round++) {
        Invoke-Checked "Deterministic Eval round $round" $mavenProgram ($mavenPrefix + @(
            '-B', '-ntp', '-pl', 'apps/ops-api',
            '-Dtest=EvalRunIT#deterministicRunsAreReproduciblePersistedAndComparable', 'test'
        )) "eval-$round.log"
    }

    Invoke-Compose 'Compose config validation' @('config', '--quiet') 'compose-config.log'
    $stackTouched = $true
    Invoke-Compose 'Reset isolated Stage 2A stack' @('down', '--volumes', '--remove-orphans') 'compose-reset.log'
    Invoke-Compose 'Build and start Demo stack' @('up', '-d', '--build', '--wait', '--wait-timeout', '240') 'compose-up.log'
    Assert-Ready 'Console' 'http://127.0.0.1:4173/' 'SentinelOps'
    Assert-Ready 'Keycloak' 'http://127.0.0.1:8081/realms/sentinelops/.well-known/openid-configuration' 'issuer'
    Assert-Ready 'Prometheus' 'http://127.0.0.1:9090/-/ready' 'Ready'
    Assert-Ready 'Loki' 'http://127.0.0.1:3100/ready' 'ready'
    Assert-Ready 'Demo service' 'http://127.0.0.1:8082/actuator/health/readiness' 'UP'

    $env:SENTINELOPS_REAL_EVIDENCE_IT = 'true'
    try {
        Invoke-Checked 'Real Prometheus and Loki diagnosis' $mavenProgram ($mavenPrefix + @(
            '-B', '-ntp', '-pl', 'apps/ops-api', '-Dtest=RealEvidenceDiagnosisIT', 'test'
        )) 'real-evidence.log'
    } finally {
        Remove-Item Env:SENTINELOPS_REAL_EVIDENCE_IT -ErrorAction SilentlyContinue
    }
    Assert-Ready 'Demo service after fault cleanup' 'http://127.0.0.1:8082/actuator/health/readiness' 'UP'

    # The real-evidence test can deliver an Alertmanager event to the Compose API.
    # Reset its isolated data before the browser's single-incident workflow.
    Invoke-Compose 'Reset browser workflow state' @('down', '--volumes', '--remove-orphans') 'compose-browser-reset.log'
    Invoke-Compose 'Start browser workflow stack' @('up', '-d', '--build', '--wait', '--wait-timeout', '240') 'compose-browser-up.log'
    Assert-Ready 'Console for browser workflow' 'http://127.0.0.1:4173/' 'SentinelOps'
    Assert-Ready 'Loki for browser workflow' 'http://127.0.0.1:3100/ready' 'ready'

    $env:SENTINELOPS_VERIFICATION_DIR = $report
    Invoke-Checked 'Playwright incident and governance workflows' 'npm' @(
        '--prefix', 'web/ops-console', 'run', 'e2e', '--', '--project=chromium'
    ) 'playwright.log'
    Invoke-Checked 'Prompt-injection boundary' $mavenProgram ($mavenPrefix + @(
        '-B', '-ntp', '-pl', 'apps/ops-api', '-Dtest=PromptInjectionIT', 'test'
    )) 'prompt-injection.log'
    Invoke-OptionalProviderSmoke
    Invoke-Checked 'Git whitespace validation' 'git' @(
        '-c', "safe.directory=$($repo.Replace('\', '/'))", 'diff', '--check'
    ) 'git-diff-check.log'
    $passed = $true
    Write-Host "Stage 2A verification passed. Reports: $report"
} finally {
    if ($stackTouched) {
        & docker @compose ps --all *> (Join-Path $report 'compose-ps.log')
        & docker @compose down --volumes --remove-orphans *> (Join-Path $report 'compose-down.log')
    }
    Pop-Location
    if (-not $passed) { Write-Host "Stage 2A reports: $report" }
}
