[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$verificationDirectory = Join-Path $repositoryRoot "build/verification/$timestamp"
$composeArguments = @(
    "compose",
    "-p", "sentinelops",
    "-f", (Join-Path $repositoryRoot "deploy/compose/compose.core.yml"),
    "-f", (Join-Path $repositoryRoot "deploy/compose/compose.demo.yml")
)
$stackTouched = $false
$verificationSucceeded = $false

New-Item -ItemType Directory -Path $verificationDirectory -Force | Out-Null

function Invoke-LoggedNative {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$ArgumentList,
        [Parameter(Mandatory)][string]$LogFile
    )

    Write-Host "`n==> $Name"
    $logPath = Join-Path $verificationDirectory $LogFile
    & $FilePath @ArgumentList 2>&1 | Tee-Object -FilePath $logPath
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "$Name failed with exit code $exitCode. See $logPath"
    }
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$ArgumentList,
        [Parameter(Mandatory)][string]$Description
    )

    $output = & $FilePath @ArgumentList 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "$Description failed with exit code $exitCode.`n$($output | Out-String)"
    }
    return ($output | Out-String).Trim()
}

function Invoke-ComposeLogged {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$LogFile
    )

    Invoke-LoggedNative `
        -Name $Name `
        -FilePath "docker" `
        -ArgumentList ($composeArguments + $Arguments) `
        -LogFile $LogFile
}

function Invoke-ComposeCapture {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$Description
    )

    return Invoke-NativeCapture `
        -FilePath "docker" `
        -ArgumentList ($composeArguments + $Arguments) `
        -Description $Description
}

function Initialize-Java21 {
    $javaHome = $env:JAVA_HOME
    $hasJava21 = $false
    if ($javaHome -and (Test-Path (Join-Path $javaHome "bin/java.exe"))) {
        $versionText = & (Join-Path $javaHome "bin/java.exe") -version 2>&1 | Out-String
        $hasJava21 = $versionText -match 'version "21\.'
    }

    if (-not $hasJava21) {
        $toolchainRoot = Join-Path $repositoryRoot ".toolchains/jdk-21"
        $localJdk = Get-ChildItem -LiteralPath $toolchainRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName "bin/java.exe") } |
            Select-Object -First 1
        if (-not $localJdk) {
            throw "Java 21 is required. Set JAVA_HOME or install a JDK under .toolchains/jdk-21/."
        }
        $javaHome = $localJdk.FullName
    }

    $env:JAVA_HOME = $javaHome
    $env:Path = "$(Join-Path $javaHome 'bin');$env:Path"
    Write-Host "Using Java from $javaHome"
}

function Assert-HttpReady {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][string]$ContentPattern
    )

    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Uri -TimeoutSec 5
            if ($response.StatusCode -eq 200 -and $response.Content -match $ContentPattern) {
                Write-Host "  READY $Name"
                return
            }
        } catch {
            # Compose has already bounded startup; this loop tolerates a brief host-port delay.
        }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "$Name did not become ready at $Uri."
}

function Invoke-PostgresScalar {
    param([Parameter(Mandatory)][string]$Query)

    return Invoke-ComposeCapture `
        -Arguments @(
            "exec", "-T", "postgres",
            "psql", "-v", "ON_ERROR_STOP=1", "-U", "sentinelops", "-d", "sentinelops",
            "-At", "-c", $Query
        ) `
        -Description "PostgreSQL release assertion"
}

function Assert-Equal {
    param(
        [Parameter(Mandatory)][string]$Actual,
        [Parameter(Mandatory)][string]$Expected,
        [Parameter(Mandatory)][string]$Description
    )

    if ($Actual.Trim() -ne $Expected) {
        throw "$Description failed: expected '$Expected', got '$Actual'."
    }
    Write-Host "  PASS $Description"
}

function Get-RecoverySideEffectCount {
    $metrics = Invoke-ComposeCapture `
        -Arguments @("exec", "-T", "demo-service", "wget", "-qO-", "http://127.0.0.1:8082/actuator/prometheus") `
        -Description "Fresh Demo recovery counter"
    $samples = [regex]::Matches($metrics, '(?m)^demo_recovery_side_effect_total\s+([0-9.eE+-]+)\s*$')
    if ($samples.Count -ne 1) {
        throw "Demo metrics did not return exactly one recovery counter series."
    }
    return [double]::Parse($samples[0].Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
}

function Assert-DuplicateDeliveryIsIdempotent {
    Write-Host "`n==> Duplicate Stream redelivery"
    $outboxRow = Invoke-PostgresScalar -Query @"
select id::text || '|' || aggregate_id::text
from outbox_event
where event_type = 'execution.requested.v1'
order by created_at desc, id desc
limit 1
"@
    $parts = $outboxRow.Trim().Split("|")
    if ($parts.Count -ne 2) {
        throw "Could not identify the execution outbox record for redelivery."
    }
    $eventId = $parts[0]
    $executionId = $parts[1]

    $recordId = Invoke-ComposeCapture `
        -Arguments @(
            "exec", "-T", "valkey", "valkey-cli", "--raw",
            "XADD", "sentinelops.executions", "*",
            "eventId", $eventId,
            "executionId", $executionId
        ) `
        -Description "Duplicate execution Stream insertion"
    $recordId = $recordId.Trim()

    $consumed = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        Start-Sleep -Seconds 1
        $groupJson = Invoke-ComposeCapture `
            -Arguments @(
                "exec", "-T", "valkey", "valkey-cli", "--json",
                "XINFO", "GROUPS", "sentinelops.executions"
            ) `
            -Description "Execution Stream group inspection"
        $groups = @($groupJson | ConvertFrom-Json)
        $executorGroup = $groups | Where-Object { $_.name -eq "ops-executors" } | Select-Object -First 1
        if ($executorGroup -and
            $executorGroup."last-delivered-id" -eq $recordId -and
            [int]$executorGroup.pending -eq 0) {
            $consumed = $true
        }
    } while (-not $consumed -and [DateTime]::UtcNow -lt $deadline)

    if (-not $consumed) {
        throw "The executor did not acknowledge duplicate Stream record $recordId within 30 seconds."
    }

    Assert-Equal `
        -Actual (Invoke-PostgresScalar -Query "select count(*) from execution_attempt") `
        -Expected "1" `
        -Description "duplicate delivery creates no second execution attempt"

    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        Start-Sleep -Seconds 1
        $sideEffects = Get-RecoverySideEffectCount
    } while ($sideEffects -ne 1 -and [DateTime]::UtcNow -lt $deadline)
    if ($sideEffects -ne 1) {
        throw "Duplicate delivery changed the recovery side-effect counter to $sideEffects."
    }
    Write-Host "  PASS duplicate delivery acknowledged with one recovery side effect"
}

function Assert-NoSecretMaterial {
    Write-Host "`n==> Secret-material scan"
    $patterns = '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|AKIA[0-9A-Z]{16}|sk-(live|proj)-[A-Za-z0-9_-]{20,}'
    $matches = & rg -n --hidden `
        -g "!.git/**" `
        -g "!build/**" `
        -g "!**/target/**" `
        -g "!**/node_modules/**" `
        -- $patterns $repositoryRoot 2>&1
    $scanExit = $LASTEXITCODE
    if ($scanExit -eq 0) {
        throw "Possible non-Demo secret material found:`n$($matches | Out-String)"
    }
    if ($scanExit -ne 1) {
        throw "Secret-material scan failed with exit code $scanExit."
    }
    Write-Host "  PASS no private key or high-confidence live-token pattern found"
}

function Save-ComposeDiagnostics {
    if (-not $stackTouched) {
        return
    }
    try {
        & docker @composeArguments ps --all *> (Join-Path $verificationDirectory "compose-ps.log")
        & docker @composeArguments logs --no-color *> (Join-Path $verificationDirectory "compose.log")
    } catch {
        Write-Warning "Could not save all Compose diagnostics: $($_.Exception.Message)"
    }
}

function Stop-ComposeStack {
    if (-not $stackTouched) {
        return 0
    }
    $output = & docker @composeArguments down --volumes --remove-orphans 2>&1
    $exitCode = $LASTEXITCODE
    $output | Tee-Object -FilePath (Join-Path $verificationDirectory "compose-down.log") |
        ForEach-Object { Write-Host $_ }
    return $exitCode
}

Push-Location $repositoryRoot
try {
    Initialize-Java21

    Invoke-LoggedNative `
        -Name "Maven unit/module verification" `
        -FilePath (Join-Path $repositoryRoot "mvnw.cmd") `
        -ArgumentList @("--batch-mode", "verify") `
        -LogFile "maven-verify.log"
    Invoke-LoggedNative `
        -Name "Maven integration tests" `
        -FilePath (Join-Path $repositoryRoot "mvnw.cmd") `
        -ArgumentList @(
            "--batch-mode",
            "-Dtest=*IT",
            "-Dsurefire.failIfNoSpecifiedTests=false",
            "test"
        ) `
        -LogFile "maven-integration.log"

    Invoke-LoggedNative `
        -Name "Frontend clean install" `
        -FilePath "npm" `
        -ArgumentList @("--prefix", "web/ops-console", "ci") `
        -LogFile "npm-ci.log"
    Invoke-LoggedNative `
        -Name "Frontend lint" `
        -FilePath "npm" `
        -ArgumentList @("--prefix", "web/ops-console", "run", "lint") `
        -LogFile "npm-lint.log"
    Invoke-LoggedNative `
        -Name "Frontend unit tests" `
        -FilePath "npm" `
        -ArgumentList @("--prefix", "web/ops-console", "run", "test", "--", "--run") `
        -LogFile "npm-test.log"
    Invoke-LoggedNative `
        -Name "Frontend typecheck and production build" `
        -FilePath "npm" `
        -ArgumentList @("--prefix", "web/ops-console", "run", "build") `
        -LogFile "npm-build.log"
    Invoke-LoggedNative `
        -Name "Frontend formatting" `
        -FilePath "npm" `
        -ArgumentList @("--prefix", "web/ops-console", "run", "format:check") `
        -LogFile "npm-format.log"
    Invoke-LoggedNative `
        -Name "Generated OpenAPI client consistency" `
        -FilePath "npm" `
        -ArgumentList @("--prefix", "web/ops-console", "run", "api:check") `
        -LogFile "npm-api-check.log"
    Invoke-LoggedNative `
        -Name "Playwright Chromium availability" `
        -FilePath "npm" `
        -ArgumentList @("--prefix", "web/ops-console", "exec", "--", "playwright", "install", "chromium") `
        -LogFile "playwright-install.log"

    Assert-NoSecretMaterial

    Invoke-ComposeLogged `
        -Name "Compose configuration validation" `
        -Arguments @("config", "--quiet") `
        -LogFile "compose-config.log"

    $stackTouched = $true
    Invoke-ComposeLogged `
        -Name "Clean previous Demo state" `
        -Arguments @("down", "--volumes", "--remove-orphans") `
        -LogFile "compose-reset.log"
    Invoke-ComposeLogged `
        -Name "Build and start healthy Demo stack (180 second deadline)" `
        -Arguments @("up", "-d", "--build", "--wait", "--wait-timeout", "180") `
        -LogFile "compose-up.log"

    Write-Host "`n==> Host and internal readiness checks"
    Assert-HttpReady -Name "Ops Console" -Uri "http://localhost:4173/" -ContentPattern "SentinelOps"
    Assert-HttpReady `
        -Name "Keycloak realm" `
        -Uri "http://localhost:8081/realms/sentinelops/.well-known/openid-configuration" `
        -ContentPattern '"issuer"'
    Assert-HttpReady -Name "Prometheus" -Uri "http://localhost:9090/-/ready" -ContentPattern "Ready"
    Assert-HttpReady -Name "Alertmanager" -Uri "http://localhost:9093/-/ready" -ContentPattern "OK"
    Invoke-ComposeLogged `
        -Name "Internal application readiness" `
        -Arguments @(
            "exec", "-T", "ops-api", "wget", "-qO-",
            "http://127.0.0.1:8080/actuator/health/readiness"
        ) `
        -LogFile "ops-api-readiness.log"
    Invoke-ComposeLogged `
        -Name "Internal executor readiness" `
        -Arguments @(
            "exec", "-T", "ops-executor", "wget", "-qO-",
            "http://127.0.0.1:8080/actuator/health/readiness"
        ) `
        -LogFile "ops-executor-readiness.log"

    $env:SENTINELOPS_VERIFICATION_DIR = $verificationDirectory
    Invoke-LoggedNative `
        -Name "Browser end-to-end incident workflow" `
        -FilePath "npm" `
        -ArgumentList @("--prefix", "web/ops-console", "run", "e2e") `
        -LogFile "playwright-e2e.log"

    Write-Host "`n==> Persisted release assertions"
    Assert-Equal `
        -Actual (Invoke-PostgresScalar -Query "select count(*) from incident where fingerprint = 'demo-checkout-connection-pool'") `
        -Expected "1" `
        -Description "one incident for the alert fingerprint"
    Assert-Equal `
        -Actual (Invoke-PostgresScalar -Query "select status from incident where fingerprint = 'demo-checkout-connection-pool'") `
        -Expected "resolved" `
        -Description "incident resolves after verification"
    Assert-Equal `
        -Actual (Invoke-PostgresScalar -Query "select status from execution order by created_at desc limit 1") `
        -Expected "succeeded" `
        -Description "execution succeeds"
    Assert-Equal `
        -Actual (Invoke-PostgresScalar -Query "select status from verification_cycle order by started_at desc limit 1") `
        -Expected "succeeded" `
        -Description "verification cycle is persisted as successful"
    Assert-Equal `
        -Actual (Invoke-PostgresScalar -Query "select count(*) from verification_attempt where successful") `
        -Expected "1" `
        -Description "successful verification probe is persisted"

    Assert-DuplicateDeliveryIsIdempotent

    Invoke-LoggedNative `
        -Name "Git whitespace validation" `
        -FilePath "git" `
        -ArgumentList @(
            "-c", "safe.directory=$($repositoryRoot.Replace('\', '/'))",
            "diff", "--check"
        ) `
        -LogFile "git-diff-check.log"

    $verificationSucceeded = $true
    Write-Host "`nAll Stage 1 release gates passed."
    Write-Host "Verification artifacts: $verificationDirectory"
} finally {
    Save-ComposeDiagnostics
    $cleanupExitCode = Stop-ComposeStack
    Pop-Location
    if ($verificationSucceeded -and $cleanupExitCode -ne 0) {
        throw "Verification passed, but Compose teardown failed with exit code $cleanupExitCode."
    }
    if (-not $verificationSucceeded) {
        Write-Host "Verification artifacts preserved at $verificationDirectory"
    }
}
