Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Demo uses a deterministic model. The real-provider timeout, Loki failure,
# telemetry outage and database failure are exercised through isolated
# PostgreSQL/Valkey Testcontainers plus bounded HTTP provider fixtures.
$javaHomeBefore = $env:JAVA_HOME
$pathBefore = $env:Path
try {
    $jdk = Get-ChildItem -LiteralPath (Join-Path $repo '.toolchains/jdk-21') -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin/java.exe') } |
        Select-Object -First 1
    if (-not $jdk) { throw 'Task7 requires the repository Java 21 toolchain.' }
    $env:JAVA_HOME = $jdk.FullName
    $env:Path = "$(Join-Path $jdk.FullName 'bin');$pathBefore"
    $startedAt = [DateTime]::UtcNow
    Invoke-Logged -Name 'Dependency failures and prompt-injection corpus' `
        -Program (Join-Path $repo 'mvnw.cmd') -Arguments @(
            '-B', '-ntp', '-pl', 'apps/ops-api',
            '-Dtest=DependencyFailureIT,PromptInjectionCorpusIT', 'test'
        ) -Log 'dependency-corpus.log'
    $requiredCases = @{
        DependencyFailureIT = @(
            'modelTimeoutLeavesIncidentInManualTriageWithNoProposalOrExecution',
            'lokiUnavailableFailsDiagnosisClosedWithoutMissingEvidenceOrUnsafeProposal',
            'unavailableOtlpCollectorDoesNotRejectOrLoseAReceivedAlert',
            'valkeyUnavailableLeavesExecutionOutboxBackloggedWithoutCreatingAttempt',
            'postgresDatabaseUnavailableReturns503AndDoesNotAcknowledgeTheAlert'
        )
        PromptInjectionCorpusIT = @(
            'everyCorpusEntryStaysUntrustedAndCannotRegisterOrInvokeAnActionTool',
            'unknownAndUnpublishedRunbookVersionsAreRejectedAfterReadingHostileEvidence',
            'modelCanaryCannotBePersistedInASuggestionOrEmittedToDefaultLogs'
        )
    }
    $testCounts = [ordered]@{}
    foreach ($className in $requiredCases.Keys) {
        $file = Get-ChildItem -LiteralPath (Join-Path $repo 'apps/ops-api/target/surefire-reports') `
            -Filter "TEST-*.$className.xml" | Select-Object -First 1
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
        foreach ($required in $requiredCases[$className]) {
            if (-not @($actualCases | Where-Object { $_ -eq $required -or $_.StartsWith("$required(") }).Count) {
                throw "$className did not run required case $required."
            }
        }
        $testCounts[$className] = $tests
    }
    $results['scenarios']['modelAndDependencies'] = [ordered]@{
        passed = $true; testCounts = $testCounts
        realPostgres = $true; realValkey = $true; boundedModelAndLoki = $true
    }
} finally {
    if ($null -eq $javaHomeBefore) {
        Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
    } else { $env:JAVA_HOME = $javaHomeBefore }
    $env:Path = $pathBefore
}
