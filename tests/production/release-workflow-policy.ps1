[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$workflowPath = Join-Path $repoRoot '.github/workflows/release.yml'
$failures = [System.Collections.Generic.List[string]]::new()

function Assert-True {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) { $failures.Add($Message) }
}

function Assert-Matches {
    param([string] $Text, [string] $Pattern, [string] $Message)
    if ($Text -notmatch $Pattern) { $failures.Add($Message) }
}

if (Test-Path -LiteralPath $workflowPath -PathType Leaf) {
    $workflow = [System.IO.File]::ReadAllText($workflowPath)
} else {
    $workflow = ''
    $failures.Add('Release workflow is missing at .github/workflows/release.yml.')
}

Assert-Matches $workflow '(?ms)^on:\s*\r?\n\s+push:\s*\r?\n\s+tags:\s*\r?\n\s+-\s*["'']v\*["'']\s*$' 'Release workflow must run for version tags.'
Assert-Matches $workflow '(?s)GITHUB_REF_TYPE.*GITHUB_REF_NAME.*pom\.xml.*package\.json.*package-lock\.json' 'Workflow must require a tag and compare its version with Maven, frontend, and lockfile versions.'
Assert-Matches $workflow '(?s)GITHUB_REF_NAME.*\^v\(\?<version>.*\$mavenVersion.*\$frontendVersion.*-cne' 'Version validation must reject non-vX.Y.Z tags and exact-version mismatches.'
Assert-Matches $workflow '(?s)\$frontendLockVersion.*-cne' 'The frontend lockfile version must exactly match the release tag.'

foreach ($gate in @(
    @{ pattern = '(?i)scripts[/\\]verify\.ps1\s+-Release'; message = 'Release verification must run with -Release.' },
    @{ pattern = '(?i)scripts[/\\]security-scan\.ps1'; message = 'Required security scan must run.' },
    @{ pattern = '(?i)scripts[/\\]fault-drill\.ps1'; message = 'The full fault drill must run.' },
    @{ pattern = '(?i)scripts[/\\]restore-check\.ps1\s+-TestDatabaseUrl\s+\$env:SENTINELOPS_RESTORE_TEST_URL'; message = 'Recovery verification must use the required restore-test URL.' },
    @{ pattern = '(?i)scripts[/\\]smoke\.ps1\s+-Profile\s+production'; message = 'Production database/browser smoke must run.' }
)) {
    Assert-Matches $workflow $gate.pattern $gate.message
}

Assert-Matches $workflow '(?im)^\s*-\s*name:\s*[^\r\n]*AI Eval' 'The release verification step must identify AI Eval as a required gate.'
Assert-Matches $workflow '(?s)name: Run release verification.*?shell:\s*pwsh.*?run:\s*\./scripts/verify\.ps1\s+-Release' 'The release verification script must run under PowerShell on the Linux CI runner.'
Assert-Matches $workflow '(?s)name: Run full fault and alert-storm drill.*?shell:\s*pwsh.*?run:\s*\./scripts/fault-drill\.ps1' 'The fault drill must run under PowerShell on the Linux CI runner.'
Assert-Matches $workflow '(?s)name: Install PostgreSQL 17 client tools.*?postgresql-client-17.*?psql --version.*?pg_dump --version.*?pg_restore --version' 'The Linux restore gate must install and check PostgreSQL 17 client tools.'
Assert-Matches $workflow "(?s)name: Verify PostgreSQL client tools in PowerShell PATH.*?shell:\s*pwsh.*?'psql'.*?'pg_dump'.*?'pg_restore'.*?Get-Command" 'Restore scripts must be able to resolve PostgreSQL client programs from PowerShell PATH.'
Assert-Matches $workflow '(?s)required.*SENTINELOPS_RESTORE_TEST_URL.*SENTINELOPS_WEB_URL.*SENTINELOPS_PROD_OIDC_ISSUER.*SENTINELOPS_OIDC_BROWSER_CLIENT_ID.*SENTINELOPS_OIDC_REDIRECT_URI.*IsNullOrWhiteSpace.*throw' 'Every restore, production-smoke, and production-console build input must be preflight-required and fail closed.'
foreach ($variable in @(
    'SENTINELOPS_RESTORE_TEST_URL', 'SENTINELOPS_WEB_URL', 'SENTINELOPS_PROD_OIDC_ISSUER',
    'SENTINELOPS_PROD_OPERATOR_TOKEN', 'SENTINELOPS_PROD_OBSERVER_TOKEN',
    'SENTINELOPS_PROD_WEBHOOK_SOURCE', 'SENTINELOPS_PROD_WEBHOOK_SECRET',
    'SENTINELOPS_PROD_SERVICE_KEY', 'SENTINELOPS_OIDC_BROWSER_CLIENT_ID', 'SENTINELOPS_OIDC_REDIRECT_URI'
)) {
    Assert-Matches $workflow ("(?m)^\s+" + [regex]::Escape($variable) + ':\s*\$\{\{\s*(?:secrets|vars)\.' + [regex]::Escape($variable) + '\s*\}\}') "Required CI input '$variable' must be wired from GitHub vars/secrets."
}
Assert-True ($workflow -notmatch '(?i)continue-on-error:\s*true|if:\s*.*SENTINELOPS_(?:RESTORE_TEST_URL|WEB_URL)|\|\|\s*true') 'Required release gates must not be conditional or allowed to fail.'

Assert-Matches $workflow "(?i)'buildx',\s*'build'" 'Release images must be built using Buildx.'
Assert-Matches $workflow '(?i)--load' 'Release images must remain local for artifact assembly.'
Assert-Matches $workflow '(?i)--pull' 'Release builds must pull the version-pinned Dockerfile base images.'
foreach ($image in @('ops-api', 'ops-executor', 'demo-service', 'ops-console')) {
    Assert-Matches $workflow ("sentinelops/" + [regex]::Escape($image)) "The '$image' image must be built."
}
Assert-Matches $workflow '(?i):\$env:RELEASE_VERSION' 'Every image tag must carry the verified release version.'
foreach ($dockerfile in @('apps/ops-api/Dockerfile', 'apps/ops-executor/Dockerfile', 'apps/demo-service/Dockerfile', 'web/ops-console/Dockerfile')) {
    Assert-Matches $workflow ([regex]::Escape($dockerfile)) "The '$dockerfile' multi-stage Dockerfile must be used."
    $dockerfilePath = Join-Path $repoRoot $dockerfile
    if (Test-Path -LiteralPath $dockerfilePath -PathType Leaf) {
        $dockerfileText = [System.IO.File]::ReadAllText($dockerfilePath)
        Assert-True ([regex]::Matches($dockerfileText, '(?im)^FROM\s+').Count -ge 2 -and $dockerfileText -match '(?i)COPY\s+--from=build') "The '$dockerfile' must define and consume separate build/runtime stages."
    } else {
        $failures.Add("Multi-stage image Dockerfile is missing: $dockerfile")
    }
}
Assert-Matches $workflow '(?i)build/security/sentinelops-java\.cdx\.json' 'The Java CycloneDX SBOM from the security gate must be included.'
Assert-Matches $workflow '(?i)build/security/ops-console\.cdx\.json' 'The Node CycloneDX SBOM from the security gate must be included.'
Assert-Matches $workflow '(?i)Get-FileHash[^\r\n]*SHA256|sha256sum' 'Release images and SBOMs must receive SHA-256 checksums.'
Assert-Matches $workflow '(?s)function Invoke-ReleaseImageScan.*?aquasec/trivy:0\.74\.0.*?--input.*?--scanners.*?--exit-code' 'The exact packaged image archives must be scanned with pinned Trivy before upload.'
Assert-Matches $workflow '(?s)& docker image save --output \$archivePath \$imageTag.*?Invoke-ReleaseImageScan -ArchivePath \$archivePath.*?\$checksumLines' 'Every final versioned image archive must be scanned before checksums and upload.'
Assert-Matches $workflow '(?s)Invoke-ReleaseImageScan.*?\b(vuln|vulnerability)\b.*?\bsecret\b' 'Final image archives need vulnerability and secret scans.'

$uploadMatches = [regex]::Matches($workflow, '(?m)^\s*uses:\s*actions/upload-artifact@')
Assert-True ($uploadMatches.Count -eq 1) 'Exactly one immutable CI artifact upload must exist.'
Assert-Matches $workflow '(?m)^\s*uses:\s*actions/upload-artifact@[0-9a-f]{40}\s*(?:#.*)?$' 'Artifact upload action must be pinned to a full commit SHA.'
Assert-Matches $workflow '(?m)^\s*uses:\s*actions/upload-artifact@[0-9a-f]{40}\s+#\s*v(?:[4-9]|[1-9][0-9]+)\.\d+\.\d+\s*$' 'Artifact upload must use an immutable-artifact-capable action release.'
Assert-Matches $workflow '(?m)^\s*retention-days:\s*90\s*$' 'The immutable release artifact must have an explicit retention period.'
if ($uploadMatches.Count -eq 1) {
    $uploadIndex = $uploadMatches[0].Index
    foreach ($pattern in @(
        '(?i)scripts[/\\]verify\.ps1\s+-Release', '(?i)scripts[/\\]security-scan\.ps1',
        '(?i)scripts[/\\]fault-drill\.ps1', '(?i)scripts[/\\]restore-check\.ps1',
        '(?i)scripts[/\\]smoke\.ps1', "(?i)'buildx',\s*'build'", '(?i)Get-FileHash[^\r\n]*SHA256|sha256sum'
    )) {
        $match = [regex]::Match($workflow, $pattern)
        Assert-True ($match.Success -and $match.Index -lt $uploadIndex) "CI artifact upload must follow successful gate/assembly step matching '$pattern'."
    }
    Assert-True ($workflow.Substring($uploadIndex) -notmatch '(?im)^\s*if:\s*always\s*\(') 'Release artifact upload must use the default success condition.'
}

Assert-True ($workflow -notmatch '(?i)docker\s+(?:login|push)|--push\b|gh\s+release\s+create|kubectl\s+(?:apply|rollout)|helm\s+upgrade|scp\s+') 'The CI release workflow must not publish images, create a GitHub Release, or deploy.'
Assert-True ($workflow -notmatch '(?im)^\s*git\s+tag\b') 'The CI release workflow must not create a local Git tag.'

if ($failures.Count -gt 0) {
    Write-Output 'Release workflow policy: FAIL'
    foreach ($failure in $failures) { Write-Output " - $failure" }
    exit 1
}

Write-Output 'Release workflow policy: PASS'
