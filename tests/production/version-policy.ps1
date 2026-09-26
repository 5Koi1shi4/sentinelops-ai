[CmdletBinding()]
param([string]$ExpectedVersion = '1.0.0')

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$versions = [ordered]@{}
$versions['pom.xml'] = ([xml](Get-Content -LiteralPath (Join-Path $repositoryRoot 'pom.xml') -Raw)).project.version
foreach ($module in @('ops-api', 'ops-executor', 'demo-service')) {
    $path = "apps/$module/pom.xml"
    $versions[$path] = ([xml](Get-Content -LiteralPath (Join-Path $repositoryRoot $path) -Raw)).project.parent.version
}

$package = Get-Content -LiteralPath (Join-Path $repositoryRoot 'web/ops-console/package.json') -Raw |
    ConvertFrom-Json -AsHashtable
$lock = Get-Content -LiteralPath (Join-Path $repositoryRoot 'web/ops-console/package-lock.json') -Raw |
    ConvertFrom-Json -AsHashtable
$versions['web/ops-console/package.json'] = $package.version
$versions['web/ops-console/package-lock.json'] = $lock.version
$versions['web/ops-console/package-lock.json root'] = $lock.packages[''].version

$mismatches = @($versions.GetEnumerator() | Where-Object { $_.Value -cne $ExpectedVersion })
if ($mismatches.Count -gt 0) {
    $details = ($mismatches | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ', '
    throw "Release version mismatch; expected $ExpectedVersion`: $details"
}

Write-Host "Release version policy passed: $ExpectedVersion"
