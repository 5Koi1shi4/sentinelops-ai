Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-DemoSideEffectCount {
    $response = Invoke-RestMethod -Uri 'http://127.0.0.1:9090/api/v1/query?query=demo_recovery_side_effect_total' -TimeoutSec 10
    if ($response.status -ne 'success' -or -not $response.data.result) { return 0L }
    return [long][double]$response.data.result[0].value[1]
}

$effectsBefore = Get-DemoSideEffectCount
$valkeyStopped = $false
try {
    Invoke-Compose -Name 'Stop Valkey before approved execution' -Arguments @('stop', '-t', '10', 'valkey') -Log 'redis-stop.log'
    $valkeyStopped = $true
    $env:SENTINELOPS_FAULT_TEST_DIR = './faults'
    $env:SENTINELOPS_VERIFICATION_DIR = $diagnostics
    try {
        Invoke-Logged -Name 'Create approved execution while Valkey is down' `
            -Program (Join-Path $repo 'web/ops-console/node_modules/.bin/playwright.cmd') -Arguments @(
            'test', '--config', (Join-Path $repo 'web/ops-console/playwright.config.ts'),
            '--project=chromium'
        ) -Log 'redis-browser.log'
    } finally {
        Remove-Item Env:SENTINELOPS_FAULT_TEST_DIR, Env:SENTINELOPS_VERIFICATION_DIR -ErrorAction SilentlyContinue
    }
    $browserLog = Get-Content -LiteralPath (Join-Path $diagnostics 'redis-browser.log') -Raw
    $match = [regex]::Match($browserLog, 'TASK7_INCIDENT_ID=([0-9a-f-]{36})')
    if (-not $match.Success) { throw 'Browser drill did not report its incident ID.' }
    $incidentId = $match.Groups[1].Value
    $pending = Get-DatabaseScalar "select status from execution where incident_id='$incidentId'"
    $incidentDuringOutage = Get-DatabaseScalar "select status from incident where id='$incidentId'"
    $attemptsBefore = [long](Get-DatabaseScalar "select count(*) from execution_attempt a join execution e on e.id=a.execution_id where e.incident_id='$incidentId'")
    $unpublished = [long](Get-DatabaseScalar "select count(*) from outbox_event o join execution e on e.id=o.aggregate_id where e.incident_id='$incidentId' and o.aggregate_type='execution' and o.event_type='execution.requested.v1' and o.published_at is null")
    $effectsDuring = Get-DemoSideEffectCount
    if ($pending -ne 'pending' -or $incidentDuringOutage -in @('resolved', 'suppressed') -or $attemptsBefore -ne 0 -or $unpublished -ne 1 -or $effectsDuring -ne $effectsBefore) {
        throw "Valkey outage invariant failed: execution=$pending incident=$incidentDuringOutage attempts=$attemptsBefore unpublished=$unpublished sideEffectsBefore=$effectsBefore sideEffectsDuring=$effectsDuring."
    }

    Invoke-Compose -Name 'Restart Valkey after outage' -Arguments @('start', 'valkey') -Log 'redis-start.log'
    $valkeyStopped = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    $published = 0L
    $status = ''
    $incidentStatus = ''
    $effectsAfter = $effectsDuring
    while ([DateTime]::UtcNow -lt $deadline) {
        $published = [long](Get-DatabaseScalar "select count(*) from outbox_event o join execution e on e.id=o.aggregate_id where e.incident_id='$incidentId' and o.aggregate_type='execution' and o.event_type='execution.requested.v1' and o.published_at is not null")
        $status = Get-DatabaseScalar "select status from execution where incident_id='$incidentId'"
        $incidentStatus = Get-DatabaseScalar "select status from incident where id='$incidentId'"
        $effectsAfter = Get-DemoSideEffectCount
        if ($published -eq 1 -and $status -eq 'succeeded' -and $incidentStatus -eq 'resolved' -and $effectsAfter -eq ($effectsBefore + 1)) { break }
        Start-Sleep -Seconds 3
    }
    $attemptsAfter = [long](Get-DatabaseScalar "select count(*) from execution_attempt a join execution e on e.id=a.execution_id where e.incident_id='$incidentId'")
    if ($published -ne 1 -or $status -ne 'succeeded' -or $incidentStatus -ne 'resolved' -or $effectsAfter -ne ($effectsBefore + 1) -or $attemptsAfter -ne 1) {
        throw "Valkey recovery invariant failed: published=$published execution=$status incident=$incidentStatus attempts=$attemptsAfter sideEffectsBefore=$effectsBefore sideEffectsAfter=$effectsAfter."
    }
    $results['scenarios']['redis'] = [ordered]@{
        passed = $true; pendingDuringOutage = $unpublished; attemptsDuringOutage = $attemptsBefore
        incidentDuringOutage = $incidentDuringOutage
        publishedAfterRecovery = $published; attemptsAfterRecovery = $attemptsAfter
        sideEffectsAfterRecovery = $effectsAfter - $effectsBefore; incidentStatus = $incidentStatus
    }
} finally {
    if ($valkeyStopped) {
        Invoke-Compose -Name 'Restore Valkey after failed drill' -Arguments @('start', 'valkey') -Log 'redis-restore.log'
    }
}
