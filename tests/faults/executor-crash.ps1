Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-DemoSideEffectCount {
    $response = Invoke-RestMethod -Uri 'http://127.0.0.1:9090/api/v1/query?query=demo_recovery_side_effect_total' -TimeoutSec 10
    if ($response.status -ne 'success' -or -not $response.data.result) { return 0L }
    return [long][double]$response.data.result[0].value[1]
}

$executorStopped = $false
try {
    Invoke-Compose -Name 'Stop Executor before approved work' -Arguments @('stop', '-t', '10', 'ops-executor') -Log 'executor-stop.log'
    $executorStopped = $true
    $env:SENTINELOPS_FAULT_TEST_DIR = './faults'
    $env:SENTINELOPS_VERIFICATION_DIR = $diagnostics
    try {
        Invoke-Logged -Name 'Create approved pending execution through browser' `
            -Program (Join-Path $repo 'web/ops-console/node_modules/.bin/playwright.cmd') -Arguments @(
            'test', '--config', (Join-Path $repo 'web/ops-console/playwright.config.ts'),
            '--project=chromium'
        ) -Log 'executor-browser.log'
    } finally {
        Remove-Item Env:SENTINELOPS_FAULT_TEST_DIR, Env:SENTINELOPS_VERIFICATION_DIR -ErrorAction SilentlyContinue
    }
    $browserLog = Get-Content -LiteralPath (Join-Path $diagnostics 'executor-browser.log') -Raw
    $match = [regex]::Match($browserLog, 'TASK7_INCIDENT_ID=([0-9a-f-]{36})')
    if (-not $match.Success) { throw 'Browser drill did not report its incident ID.' }
    $incidentId = $match.Groups[1].Value
    $pending = Get-DatabaseScalar "select status from execution where incident_id='$incidentId'"
    $incidentDuringOutage = Get-DatabaseScalar "select status from incident where id='$incidentId'"
    $attemptsBefore = [long](Get-DatabaseScalar "select count(*) from execution_attempt a join execution e on e.id=a.execution_id where e.incident_id='$incidentId'")
    $effectsBefore = Get-DemoSideEffectCount
    if ($pending -ne 'pending' -or $incidentDuringOutage -in @('resolved', 'suppressed') -or $attemptsBefore -ne 0 -or $effectsBefore -ne 0) {
        throw "Executor outage invariant failed: execution=$pending incident=$incidentDuringOutage attempts=$attemptsBefore sideEffects=$effectsBefore."
    }
    Invoke-Compose -Name 'Restart Executor after approved work' -Arguments @('start', 'ops-executor') -Log 'executor-start.log'
    $executorStopped = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    $status = ''
    $incidentStatus = ''
    $effectsAfter = 0L
    while ([DateTime]::UtcNow -lt $deadline) {
        $status = Get-DatabaseScalar "select status from execution where incident_id='$incidentId'"
        $incidentStatus = Get-DatabaseScalar "select status from incident where id='$incidentId'"
        $effectsAfter = Get-DemoSideEffectCount
        if ($status -eq 'succeeded' -and $incidentStatus -eq 'resolved' -and $effectsAfter -eq 1) { break }
        Start-Sleep -Seconds 3
    }
    $attemptsAfter = [long](Get-DatabaseScalar "select count(*) from execution_attempt a join execution e on e.id=a.execution_id where e.incident_id='$incidentId'")
    if ($status -ne 'succeeded' -or $incidentStatus -ne 'resolved' -or $effectsAfter -ne 1 -or $attemptsAfter -ne 1) {
        throw "Executor recovery invariant failed: execution=$status incident=$incidentStatus attempts=$attemptsAfter sideEffects=$effectsAfter."
    }
    $results['scenarios']['executor'] = [ordered]@{
        passed = $true; pendingWhileStopped = $pending; attemptsWhileStopped = $attemptsBefore
        incidentDuringOutage = $incidentDuringOutage
        attemptsAfterRecovery = $attemptsAfter; sideEffectsAfterRecovery = $effectsAfter
        incidentStatus = $incidentStatus
    }
} finally {
    if ($executorStopped) {
        Invoke-Compose -Name 'Restore Executor after failed drill' -Arguments @('start', 'ops-executor') -Log 'executor-restore.log'
    }
}
