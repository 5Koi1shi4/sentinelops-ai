[CmdletBinding()]
param(
    [ValidateSet('production')]
    [string]$Profile = 'production'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

$repo = Split-Path -Parent $PSScriptRoot
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$reportDirectory = Join-Path $repo "build/verification/smoke/$runId"
$diagnostics = Join-Path $repo "build/smoke-logs/$runId"
$web = Join-Path $repo 'web/ops-console'
$playwrightCommand = if ($IsWindows) { 'playwright.cmd' } else { 'playwright' }
$playwright = Join-Path $web "node_modules/.bin/$playwrightCommand"
$priorVerificationDirectory = [Environment]::GetEnvironmentVariable('SENTINELOPS_VERIFICATION_DIR', 'Process')
$priorProductionSmoke = [Environment]::GetEnvironmentVariable('SENTINELOPS_PRODUCTION_SMOKE', 'Process')
$phase = 'configuration'
$passed = $false
$results = [ordered]@{
    runId = $runId
    profile = $Profile
    startedAt = [DateTimeOffset]::UtcNow.ToString('O')
}

New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $diagnostics -Force | Out-Null

try {
    $required = @(
        'SENTINELOPS_WEB_URL', 'SENTINELOPS_PROD_OIDC_ISSUER',
        'SENTINELOPS_PROD_OPERATOR_TOKEN', 'SENTINELOPS_PROD_OBSERVER_TOKEN',
        'SENTINELOPS_PROD_WEBHOOK_SOURCE', 'SENTINELOPS_PROD_WEBHOOK_SECRET',
        'SENTINELOPS_PROD_SERVICE_KEY'
    )
    foreach ($name in $required) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            throw "$name is required for production smoke."
        }
    }

    $console = [Uri]$env:SENTINELOPS_WEB_URL
    $issuer = [Uri]$env:SENTINELOPS_PROD_OIDC_ISSUER
    if ($console.Scheme -ne 'https' -or $issuer.Scheme -ne 'https' -or
        [string]::IsNullOrWhiteSpace($console.Host) -or [string]::IsNullOrWhiteSpace($issuer.Host) -or
        $console.AbsolutePath -ne '/' -or $console.Query -or $console.Fragment -or
        $console.UserInfo -or $issuer.UserInfo -or $issuer.Query -or $issuer.Fragment) {
        throw 'Production smoke requires HTTPS console origin and HTTPS OIDC issuer URLs.'
    }
    if (-not (Test-Path -LiteralPath $playwright -PathType Leaf)) {
        throw 'Playwright is missing; run npm ci in web/ops-console.'
    }

    $phase = 'tests'
    $env:SENTINELOPS_VERIFICATION_DIR = $diagnostics
    $env:SENTINELOPS_PRODUCTION_SMOKE = 'true'
    Push-Location $web
    try {
        & $playwright test e2e/production-smoke.spec.ts --reporter=list *> (Join-Path $diagnostics 'playwright.log')
        $testExit = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($testExit -ne 0) {
        throw 'Production smoke checks failed; see build/smoke-logs for the scoped raw diagnostics.'
    }
    $passed = $true
} catch {
    $results.errorCode = if ($phase -eq 'configuration') { 'SMOKE_CONFIGURATION_INVALID' } else { 'SMOKE_TEST_FAILED' }
    throw
} finally {
    [Environment]::SetEnvironmentVariable('SENTINELOPS_VERIFICATION_DIR', $priorVerificationDirectory, 'Process')
    [Environment]::SetEnvironmentVariable('SENTINELOPS_PRODUCTION_SMOKE', $priorProductionSmoke, 'Process')
    $results.passed = $passed
    $results.finishedAt = [DateTimeOffset]::UtcNow.ToString('O')
    $results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $reportDirectory 'report.json')
    if ($passed) { Write-Host "Production smoke passed. Report: $reportDirectory" }
}
