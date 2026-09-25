Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$javaHomeBefore = $env:JAVA_HOME
$pathBefore = $env:Path
try {
    $jdk = Get-ChildItem -LiteralPath (Join-Path $repo '.toolchains/jdk-21') -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin/java.exe') } |
        Select-Object -First 1
    if (-not $jdk) { throw 'Task7 requires the repository Java 21 toolchain.' }
    $env:JAVA_HOME = $jdk.FullName
    $env:Path = "$(Join-Path $jdk.FullName 'bin');$pathBefore"
    $testCounts = [ordered]@{}
    foreach ($case in @(
        @{ Module = 'ops-api'; ClassName = 'ExecutionCrashRecoveryIT'; Log = 'reclaim-postgres.log'; RequiredCases = @(
            'nonIdempotentStepRetriesAfterConfirmedPreDispatchCrash',
            'nonIdempotentDispatchWithoutResultEscalatesOnReclaim',
            'provenIdempotentDispatchCanBeReclaimedWithANewFence',
            'nonIdempotentUnknownPhaseEscalatesImmediately',
            'replaySafeUnknownPhaseRemainsClaimableAfterLeaseExpiry',
            'revokedAuthorizationAfterDispatchStillRecordsUnknownEffect'
        ) },
        @{ Module = 'ops-executor'; ClassName = 'RedisExecutionStreamConsumerIT'; Log = 'duplicate-valkey.log'; RequiredCases = @(
            'duplicateDeliveryAndRestartProduceOnlyOneAdapterSideEffect',
            'anotherExecutorReclaimsAnUnacknowledgedMessageAfterRestart',
            'reclaimCannotAcknowledgeAnExecutionProtectedByHeartbeats'
        ) }
    )) {
        $module = $case.Module
        $className = $case.ClassName
        $startedAt = [DateTime]::UtcNow
        Invoke-Logged -Name $className -Program (Join-Path $repo 'mvnw.cmd') -Arguments @(
            '-B', '-ntp', '-pl', "apps/$module", "-Dtest=$className", 'test'
        ) -Log $case.Log
        $directory = Join-Path $repo "apps/$module/target/surefire-reports"
        $file = Get-ChildItem -LiteralPath $directory -Filter "TEST-*.$className.xml" |
            Select-Object -First 1
        if (-not $file) { throw "Missing Surefire report for $className." }
        if ($file.LastWriteTimeUtc -lt $startedAt) {
            throw "$className Surefire report predates this drill invocation."
        }
        [xml]$suite = Get-Content -LiteralPath $file.FullName -Raw
        $tests = [int]$suite.testsuite.tests
        $failures = [int]$suite.testsuite.failures
        $errors = [int]$suite.testsuite.errors
        $skipped = [int]$suite.testsuite.skipped
        if ($tests -lt 1 -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
            throw "$className report is not fully green: tests=$tests failures=$failures errors=$errors skipped=$skipped."
        }
        $actualCases = @($suite.testsuite.testcase | ForEach-Object { [string]$_.name })
        foreach ($required in $case.RequiredCases) {
            if (-not @($actualCases | Where-Object { $_ -eq $required -or $_.StartsWith("$required(") }).Count) {
                throw "$className did not run required case $required."
            }
        }
        $testCounts[$className] = $tests
    }
    $results['scenarios']['reclaimAndDuplicate'] = [ordered]@{
        passed = $true; testCounts = $testCounts
        realPostgres = $true; realValkey = $true
        leaseExpiryAndFencingVerified = $true; duplicateStreamDeliveryVerified = $true
    }
} finally {
    if ($null -eq $javaHomeBefore) {
        Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
    } else { $env:JAVA_HOME = $javaHomeBefore }
    $env:Path = $pathBefore
}
