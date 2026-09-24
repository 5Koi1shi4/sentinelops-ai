[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$compose = @('compose', '-p', 'sentinelops-observe',
    '-f', (Join-Path $repo 'deploy/compose/compose.core.yml'),
    '-f', (Join-Path $repo 'deploy/compose/compose.demo.yml'))

function Convert-Base64Hex {
    param([string]$Value)
    return [BitConverter]::ToString([Convert]::FromBase64String($Value)).Replace('-', '').ToLowerInvariant()
}

function Get-TraceSpans {
    param($Trace)
    foreach ($batch in $Trace.batches) {
        foreach ($scope in $batch.scopeSpans) {
            foreach ($span in $scope.spans) {
                $span
            }
        }
    }
}

$row = & docker @compose exec -T postgres psql -U sentinelops -d sentinelops -Atc `
    "select id, aggregate_id, payload->>'traceparent' from outbox_event where event_type='execution.requested.v1' order by created_at desc limit 1"
if ($LASTEXITCODE -ne 0 -or -not $row) {
    throw 'No completed Demo execution Outbox row is available.'
}
$fields = ([string]$row).Trim().Split('|')
if ($fields.Count -ne 3 -or $fields[2] -notmatch '^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$') {
    throw 'The latest execution Outbox row has no valid traceparent.'
}
$eventId = $fields[0]
$executionId = $fields[1]
$traceparent = $fields[2]
$requestTraceId = $Matches[1]
$requestSpanId = $Matches[2]

$streamLines = @(& docker @compose exec -T valkey valkey-cli XREVRANGE sentinelops.executions + - COUNT 100)
if ($LASTEXITCODE -ne 0) {
    throw 'Cannot read the local execution Stream.'
}
$matchingStreamRecord = $false
for ($index = 0; $index -lt $streamLines.Count;) {
    if ($streamLines[$index] -notmatch '^\d+-\d+$') {
        throw 'The execution Stream response has an unexpected record format.'
    }
    $index++
    $values = @{}
    while ($index -lt $streamLines.Count -and $streamLines[$index] -notmatch '^\d+-\d+$') {
        if ($index + 1 -ge $streamLines.Count) {
            throw 'The execution Stream response has an incomplete field pair.'
        }
        $values[[string]$streamLines[$index]] = [string]$streamLines[$index + 1]
        $index += 2
    }
    if ($values.eventId -eq $eventId -and $values.executionId -eq $executionId -and
        $values.traceparent -eq $traceparent) {
        $matchingStreamRecord = $true
        break
    }
}
if (-not $matchingStreamRecord) {
    throw 'The execution Stream has no matching event ID, execution ID, and traceparent.'
}

$configJson = & docker @compose config --format json
if ($LASTEXITCODE -ne 0) {
    throw 'Cannot read the local Grafana configuration.'
}
$grafana = ($configJson | ConvertFrom-Json).services.grafana.environment
$pair = [string]$grafana.GF_SECURITY_ADMIN_USER + ':' +
    [string]$grafana.GF_SECURITY_ADMIN_PASSWORD
$authorization = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
$headers = @{ Authorization = 'Basic ' + $authorization }
$tempoBase = 'http://127.0.0.1:3000/api/datasources/proxy/uid/tempo'

function Get-TempoTrace {
    param([string]$TraceId)
    return Invoke-RestMethod -Uri ($tempoBase + '/api/traces/' + $TraceId) `
        -Headers $headers -TimeoutSec 10
}

$requestSpans = @(Get-TraceSpans (Get-TempoTrace $requestTraceId))
$create = $requestSpans | Where-Object name -eq 'sentinelops.execution.create' |
    Select-Object -First 1
if (-not $create -or (Convert-Base64Hex $create.spanId) -ne $requestSpanId) {
    throw 'The Outbox traceparent does not identify the execution creation span.'
}

$query = '{ .execution.id = "' + $executionId + '" }'
$searchUri = $tempoBase + '/api/search?q=' + [uri]::EscapeDataString($query) + '&limit=30'
$deadline = [DateTime]::UtcNow.AddSeconds(30)
$executorTraceId = $null
$verificationTraceId = $null
do {
    $search = Invoke-RestMethod -Uri $searchUri -Headers $headers -TimeoutSec 10
    foreach ($item in @($search.traces)) {
        if (-not $item.traceID) { continue }
        $spans = @(Get-TraceSpans (Get-TempoTrace $item.traceID))
        $names = @($spans | ForEach-Object name)
        if ($names -contains 'sentinelops.executor.stream.consume') {
            $executorTraceId = [string]$item.traceID
            $executorSpans = $spans
        }
        if ($names -contains 'sentinelops.verification.run') {
            $verificationTraceId = [string]$item.traceID
        }
    }
    if ($executorTraceId -and $verificationTraceId) { break }
    Start-Sleep -Seconds 2
} while ([DateTime]::UtcNow -lt $deadline)

if (-not $executorTraceId -or -not $verificationTraceId) {
    throw 'Tempo is missing the Executor or scheduled verification trace.'
}
$consume = $executorSpans | Where-Object name -eq 'sentinelops.executor.stream.consume' |
    Select-Object -First 1
$linked = @($consume.links | Where-Object {
    (Convert-Base64Hex $_.traceId) -eq $requestTraceId -and
    (Convert-Base64Hex $_.spanId) -eq $requestSpanId
})
if ($linked.Count -ne 1) {
    throw 'The Executor consume span lacks a link to the execution creation span.'
}
$names = @($executorSpans | ForEach-Object name)
foreach ($required in @('sentinelops.executor.message', 'sentinelops.executor.adapter',
        'sentinelops.execution.claim', 'sentinelops.execution.complete')) {
    if ($names -notcontains $required) {
        throw "The linked Executor trace is missing $required."
    }
}

[pscustomobject]@{
    result = 'PASS'
    executionId = $executionId
    requestTraceId = $requestTraceId
    executorTraceId = $executorTraceId
    verificationTraceId = $verificationTraceId
    linkedRequestSpanId = $requestSpanId
    linkedExecutorSpanCount = $executorSpans.Count
} | ConvertTo-Json -Depth 3
