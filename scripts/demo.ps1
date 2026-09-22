[CmdletBinding()]
param(
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$composeArguments = @(
    "compose",
    "-p", "sentinelops",
    "-f", (Join-Path $repositoryRoot "deploy/compose/compose.core.yml"),
    "-f", (Join-Path $repositoryRoot "deploy/compose/compose.demo.yml")
)

function Invoke-Compose {
    param([Parameter(Mandatory)][string[]]$Arguments)

    & docker @composeArguments @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
    }
}

function Get-DemoControllerToken {
    $secret = if ($env:SENTINELOPS_DEMO_CONTROLLER_CLIENT_SECRET) {
        $env:SENTINELOPS_DEMO_CONTROLLER_CLIENT_SECRET
    } else {
        "sentinelops-demo-controller-secret"
    }

    $response = Invoke-RestMethod `
        -Method Post `
        -Uri "http://localhost:8081/realms/sentinelops/protocol/openid-connect/token" `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{
            grant_type = "client_credentials"
            client_id = "sentinelops-demo-controller"
            client_secret = $secret
            scope = "demo:fault"
        } `
        -TimeoutSec 15

    if (-not $response.access_token) {
        throw "Keycloak did not return a Demo controller access token."
    }
    return $response.access_token
}

Push-Location $repositoryRoot
try {
    $upArguments = @("up", "-d")
    if (-not $SkipBuild) {
        $upArguments += "--build"
    }
    $upArguments += @("--wait", "--wait-timeout", "180")

    Write-Host "Starting the SentinelOps Demo stack (health deadline: 180 seconds)..."
    Invoke-Compose -Arguments $upArguments

    Write-Host "Injecting the deterministic checkout connection-pool fault..."
    $token = Get-DemoControllerToken
    $faultResponse = & docker @composeArguments exec -T demo-service `
        wget -qO- `
        --header "Authorization: Bearer $token" `
        --post-data= `
        http://127.0.0.1:8082/internal/demo/faults/connection-pool
    if ($LASTEXITCODE -ne 0) {
        throw "Demo fault injection failed with exit code $LASTEXITCODE."
    }
    $fault = ($faultResponse | Out-String) | ConvertFrom-Json
    if (-not $fault.active) {
        throw "The Demo service did not report an active fault."
    }

    Write-Host ""
    Write-Host "SentinelOps Demo is ready: http://localhost:4173"
    Write-Host ""
    Write-Host "Demo-only identities (credentials are documented in README.md):"
    Write-Host "  observer-demo  - OBSERVER"
    Write-Host "  operator-demo  - OBSERVER, ON_CALL_OPERATOR"
    Write-Host "  approver-demo  - OBSERVER, SRE_APPROVER"
    Write-Host ""
    Write-Host "These identities and defaults are local Demo fixtures; never reuse them in production."
} finally {
    Pop-Location
}
