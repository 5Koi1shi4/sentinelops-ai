[CmdletBinding()]
param(
    [ValidateSet('full', 'storm', 'default-limit', 'redis', 'executor', 'model')]
    [string]$Scenario = 'full',
    [switch]$BaselineRateLimit
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

$repo = Split-Path -Parent $PSScriptRoot
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$report = Join-Path $repo "build/verification/faults/$runId"
$diagnostics = Join-Path $repo "build/fault-logs/$runId"
$project = "sentinelops-task7-$($runId.Replace('-', '').ToLowerInvariant())"
$compose = @('compose', '-p', $project,
    '-f', (Join-Path $repo 'deploy/compose/compose.core.yml'),
    '-f', (Join-Path $repo 'deploy/compose/compose.demo.yml'),
    '-f', (Join-Path $repo 'deploy/compose/compose.faults.yml'))
$stackTouched = $false
$passed = $false
$script:apiUrl = ''
$script:currentScenario = 'setup'
$managedEnvironment = @(
    'SENTINELOPS_WEBHOOK_LOAD_SECRET', 'SENTINELOPS_WEBHOOK_DEFAULT_SECRET',
    'SENTINELOPS_TASK7_LOAD_LIMIT', 'SENTINELOPS_TASK7_LOAD_BURST'
)
$priorEnvironment = @{}
foreach ($name in $managedEnvironment) {
    $priorEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$results = [ordered]@{
    runId = $runId
    scenario = $Scenario
    project = $project
    startedAt = [DateTimeOffset]::UtcNow.ToString('O')
    scenarios = [ordered]@{}
}
New-Item -ItemType Directory -Path $report -Force | Out-Null
New-Item -ItemType Directory -Path $diagnostics -Force | Out-Null

function Invoke-Logged {
    param([string]$Name, [string]$Program, [string[]]$Arguments, [string]$Log)
    Write-Host "==> $Name"
    $path = Join-Path $diagnostics $Log
    & $Program @Arguments 2>&1 | Tee-Object -FilePath $path
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed ($LASTEXITCODE). See $path"
    }
}

function Invoke-Compose {
    param([string]$Name, [string[]]$Arguments, [string]$Log)
    Invoke-Logged -Name $Name -Program 'docker' -Arguments ($compose + $Arguments) -Log $Log
}

function Get-DatabaseScalar {
    param([string]$Sql)
    $output = & docker @compose exec -T postgres psql -U sentinelops -d sentinelops -tAc $Sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Task7 database verification failed: $output" }
    return ([string]($output | Select-Object -Last 1)).Trim()
}

function Assert-ApiReady {
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/actuator/health/readiness' -TimeoutSec 4
            if ($response.StatusCode -eq 200 -and $response.Content.Contains('"status":"UP"')) {
                $script:apiUrl = 'http://127.0.0.1:8080'
                return
            }
        } catch {
            # Core/Demo can publish the API only through the console proxy.
        }
        try {
            $response = Invoke-WebRequest -Uri 'http://127.0.0.1:4173/' -TimeoutSec 4
            if ($response.StatusCode -eq 200 -and $response.Content.Contains('SentinelOps')) {
                $script:apiUrl = 'http://127.0.0.1:4173'
                return
            }
        } catch {}
        Start-Sleep -Seconds 2
    }
    throw 'Task7 API did not reach readiness through the host port or console proxy.'
}

function Assert-HostPortsAvailable {
    $requiredPorts = @(3000, 3100, 4173, 8081, 8082, 9090, 9093)
    $listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    foreach ($port in $requiredPorts) {
        if (@($listeners | Where-Object { $_.Port -eq $port }).Count -gt 0) {
            throw "Task7 host port $port is already used by an existing process."
        }
    }
}

function Invoke-K6Scenario {
    param([string]$Mode, [string]$Fingerprint, [string]$Secret)
    $summary = Join-Path $diagnostics "$Mode-summary.json"
    Invoke-Logged -Name "k6 $Mode" -Program 'k6' -Arguments @(
        'run', '--quiet',
        '-e', "API_URL=$script:apiUrl",
        '-e', "LOAD_MODE=$Mode",
        '-e', "STORM_FINGERPRINT=$Fingerprint",
        '-e', "WEBHOOK_SECRET=$Secret",
        '--summary-export', $summary,
        (Join-Path $repo 'tests/load/alert-storm.js')
    ) -Log "$Mode-k6.log" | Out-Null
    return (Get-Content -LiteralPath $summary -Raw | ConvertFrom-Json)
}

function Invoke-Storm {
    $fingerprint = "task7-storm-$($runId.ToLowerInvariant())"
    $summary = Invoke-K6Scenario -Mode 'storm' -Fingerprint $fingerprint -Secret $env:SENTINELOPS_WEBHOOK_LOAD_SECRET
    $accepted = [long]$summary.metrics.webhook_accepted.count
    $incidents = [long](Get-DatabaseScalar "select count(*) from incident where fingerprint = '$fingerprint' and status not in ('resolved','suppressed')")
    $occurrences = [long](Get-DatabaseScalar "select coalesce(sum(occurrence_count),0) from incident where fingerprint = '$fingerprint'")
    $events = [long](Get-DatabaseScalar "select count(*) from incident_event e join incident i on i.id=e.incident_id where i.fingerprint = '$fingerprint' and e.event_type='alert_received'")
    if ($accepted -ne 6000 -or $incidents -ne 1 -or $occurrences -ne 6000 -or $events -ne 6000) {
        throw "Alert storm mismatch: accepted=$accepted activeIncidents=$incidents occurrences=$occurrences events=$events; expected 6000,1,6000,6000."
    }
    $results.scenarios.storm = [ordered]@{
        passed = $true; accepted = $accepted; activeIncidents = $incidents
        occurrences = $occurrences; occurrenceEvents = $events
        p95Milliseconds = $summary.metrics.http_req_duration.'p(95)'
    }
}

function Invoke-DefaultLimit {
    $fingerprint = "task7-limit-$($runId.ToLowerInvariant())"
    $summary = Invoke-K6Scenario -Mode 'default-limit' -Fingerprint $fingerprint -Secret $env:SENTINELOPS_WEBHOOK_DEFAULT_SECRET
    $accepted = [long]$summary.metrics.webhook_accepted.count
    $limited = [long]$summary.metrics.webhook_rate_limited.count
    $events = [long](Get-DatabaseScalar "select count(*) from incident_event e join incident i on i.id=e.incident_id where i.fingerprint = '$fingerprint' and e.event_type='alert_received'")
    if ($limited -lt 1 -or $events -ne $accepted) {
        throw "Default limit mismatch: accepted=$accepted rateLimited=$limited events=$events."
    }
    $results.scenarios.defaultLimit = [ordered]@{
        passed = $true; accepted = $accepted; rateLimited = $limited; occurrenceEvents = $events
    }
}

Push-Location $repo
try {
    Invoke-Logged -Name 'Docker engine' -Program 'docker' -Arguments @('info', '--format', '{{.ServerVersion}}') -Log 'docker-info.log'
    $env:SENTINELOPS_WEBHOOK_LOAD_SECRET = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
    $env:SENTINELOPS_WEBHOOK_DEFAULT_SECRET = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
    if ($BaselineRateLimit) {
        $env:SENTINELOPS_TASK7_LOAD_LIMIT = '60'
        $env:SENTINELOPS_TASK7_LOAD_BURST = '20'
    } else {
        Remove-Item Env:SENTINELOPS_TASK7_LOAD_LIMIT, Env:SENTINELOPS_TASK7_LOAD_BURST -ErrorAction SilentlyContinue
    }
    Invoke-Compose -Name 'Compose configuration' -Arguments @('config', '--quiet') -Log 'compose-config.log'
    Assert-HostPortsAvailable
    $stackTouched = $true
    Invoke-Compose -Name 'Start isolated Task7 stack' -Arguments @('up', '-d', '--build', '--wait', '--wait-timeout', '300') -Log 'compose-up.log'
    Assert-ApiReady
    if ($Scenario -in @('full', 'executor')) {
        $script:currentScenario = 'executor'
        & (Join-Path $repo 'tests/faults/executor-crash.ps1')
        $script:currentScenario = 'reclaimAndDuplicate'
        & (Join-Path $repo 'tests/faults/reclaim-duplicate.ps1')
    }
    if ($Scenario -in @('full', 'model')) {
        $script:currentScenario = 'modelAndDependencies'
        & (Join-Path $repo 'tests/faults/model-timeout.ps1')
    }
    if ($Scenario -in @('full', 'redis')) {
        $script:currentScenario = 'redis'
        & (Join-Path $repo 'tests/faults/redis-outage.ps1')
    }
    if ($Scenario -in @('full', 'default-limit')) {
        $script:currentScenario = 'defaultLimit'
        Invoke-DefaultLimit
    }
    if ($Scenario -in @('full', 'storm')) {
        $script:currentScenario = 'storm'
        Invoke-Storm
    }
    $passed = $true
} catch {
    $results.errorCode = 'DRILL_FAILED'
    $results.failedScenario = $script:currentScenario
    throw
} finally {
    $cleanupSucceeded = $true
    if ($stackTouched) {
        & docker @compose ps --all *> (Join-Path $diagnostics 'compose-ps.log')
        $psExit = $LASTEXITCODE
        & docker @compose down --volumes --remove-orphans *> (Join-Path $diagnostics 'compose-down.log')
        $downExit = $LASTEXITCODE
        $cleanupSucceeded = $psExit -eq 0 -and $downExit -eq 0
    }
    if (-not $cleanupSucceeded) {
        $results.errorCode = 'CLEANUP_FAILED'
        $results.failedScenario = 'cleanup'
    }
    $results.cleanupSucceeded = $cleanupSucceeded
    $results.passed = $passed -and $cleanupSucceeded
    $results.finishedAt = [DateTimeOffset]::UtcNow.ToString('O')
    $results | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $report 'report.json')
    foreach ($name in $managedEnvironment) {
        [Environment]::SetEnvironmentVariable($name, $priorEnvironment[$name], 'Process')
    }
    Pop-Location
    if (-not $cleanupSucceeded) { throw "Task7 Docker cleanup failed. Diagnostics: $diagnostics" }
    if ($passed) { Write-Host "Task7 fault drill passed. Report: $report" }
}
