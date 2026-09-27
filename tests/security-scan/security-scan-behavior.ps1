$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$securityScan = Join-Path $repoRoot 'scripts/security-scan.ps1'
$testRoot = Join-Path $repoRoot ('build/security-scan-tests/' + [guid]::NewGuid().ToString('N'))
$fakeBin = Join-Path $testRoot 'bin'
$dockerMarker = Join-Path $testRoot 'docker-was-called.txt'
$pwshCommand = Get-Command pwsh -ErrorAction Stop
$oldPath = $env:PATH
$oldChildMarker = $env:SENTINELOPS_SECURITY_SCAN_BEHAVIOR_TEST_CHILD

New-Item -ItemType Directory -Path $fakeBin -Force | Out-Null
if ($IsWindows) {
    $fakeDockerPath = Join-Path $fakeBin 'docker.cmd'
    @'
@echo called>>"%SECURITY_SCAN_DOCKER_MARKER%"
@exit /b 0
'@ | Set-Content -LiteralPath $fakeDockerPath -Encoding Ascii
    $fakeBashPath = Join-Path $fakeBin 'bash.cmd'
    @'
@echo off
@exit /b 0
'@ | Set-Content -LiteralPath $fakeBashPath -Encoding Ascii
} else {
    $fakeDockerPath = Join-Path $fakeBin 'docker'
    $fakeDockerScript = @'
#!/bin/sh
printf 'called\n' >> "$SECURITY_SCAN_DOCKER_MARKER"
exit 0
'@
    [System.IO.File]::WriteAllText($fakeDockerPath, $fakeDockerScript, [System.Text.UTF8Encoding]::new($false))
    $dockerMode = [System.IO.UnixFileMode]::UserRead -bor [System.IO.UnixFileMode]::UserWrite -bor [System.IO.UnixFileMode]::UserExecute -bor [System.IO.UnixFileMode]::GroupRead -bor [System.IO.UnixFileMode]::GroupExecute -bor [System.IO.UnixFileMode]::OtherRead -bor [System.IO.UnixFileMode]::OtherExecute
    [System.IO.File]::SetUnixFileMode($fakeDockerPath, $dockerMode)
    $fakeBashPath = Join-Path $fakeBin 'bash'
    [System.IO.File]::WriteAllText($fakeBashPath, "#!/bin/sh`nexit 0`n", [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::SetUnixFileMode($fakeBashPath, $dockerMode)
}

$env:PATH = "$fakeBin$([System.IO.Path]::PathSeparator)$oldPath"
$env:SECURITY_SCAN_DOCKER_MARKER = $dockerMarker
$env:SENTINELOPS_SECURITY_SCAN_BEHAVIOR_TEST_CHILD = '1'

function Assert-RejectedBeforeDocker {
    param(
        [Parameter(Mandatory)] [string] $Name,
        [Parameter(Mandatory)] [string] $Yaml,
        [Parameter(Mandatory)] [string] $ExpectedMessage
    )

    $exceptionFile = Join-Path $testRoot "$Name.trivyignore.yaml"
    $reportDirectory = Join-Path $testRoot "$Name-reports"
    Set-Content -LiteralPath $exceptionFile -Value $Yaml -Encoding utf8

    $output = & $pwshCommand.Source -NoProfile -File $securityScan -ExceptionFile $exceptionFile -ReportDirectory $reportDirectory 2>&1 | Out-String
    $exitCode = $LASTEXITCODE

    if ($exitCode -eq 0) {
        throw "$Name exception was accepted; expected a non-zero exit code."
    }
    if ($output -notmatch [regex]::Escape($ExpectedMessage)) {
        throw "$Name rejection did not explain the problem '$ExpectedMessage'. Output: $output"
    }
    if (Test-Path -LiteralPath $dockerMarker) {
        throw "$Name validation called Docker before rejecting the exception."
    }

    Write-Output "PASS $Name rejected before Docker (exit $exitCode)."
}

try {
    Assert-RejectedBeforeDocker -Name 'expired' -ExpectedMessage 'expired' -Yaml @'
vulnerabilities:
  - id: "CVE-2024-12345"
    purls:
      - "pkg:npm/example-package@1.0.0"
    affected: "image: sentinelops/ops-console:stage2b-security"
    expired_at: 2020-01-01
    reason: "The vulnerable code is unreachable in this deployment."
    compensating_control: "The service blocks this route at the gateway."
    owner: "security@example.invalid"
'@

    Assert-RejectedBeforeDocker -Name 'incomplete' -ExpectedMessage 'compensating_control' -Yaml @'
vulnerabilities:
  - id: "CVE-2024-12345"
    purls:
      - "pkg:npm/example-package@1.0.0"
    affected: "image: sentinelops/ops-console:stage2b-security"
    expired_at: 2099-01-01
    reason: "The vulnerable code is unreachable in this deployment."
    owner: "security@example.invalid"
'@

    $validExpiry = [DateTime]::UtcNow.Date.AddDays(30).ToString('yyyy-MM-dd')
    $unknownImagePolicy = @"
vulnerabilities:
  - id: "CVE-2026-12345"
    purls:
      - "pkg:npm/example-package@1.0.0"
    affected: "image: sentinelops/unknown:latest"
    expired_at: $validExpiry
    reason: "The vulnerable code is unreachable in this deployment."
    compensating_control: "The service blocks this route at the gateway."
    owner: "security@example.invalid"
"@
    $tokens = $null
    $parseErrors = $null
    $scriptAst = [System.Management.Automation.Language.Parser]::ParseFile($securityScan, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -gt 0) {
        throw "Security scan script did not parse: $($parseErrors -join '; ')"
    }
    $script:repositoryRoot = $repoRoot
    $script:securityImages = @(
        @{ name = 'ops-api'; tag = 'sentinelops/ops-api:stage2b-security' },
        @{ name = 'ops-executor'; tag = 'sentinelops/ops-executor:stage2b-security' },
        @{ name = 'demo-service'; tag = 'sentinelops/demo-service:stage2b-security' },
        @{ name = 'ops-console'; tag = 'sentinelops/ops-console:stage2b-security' }
    )
    foreach ($functionName in @('ConvertFrom-PolicyScalar', 'ConvertTo-AffectedTargets', 'Read-ValidatedExceptions', 'Write-TrivyIgnoreFile', 'Get-TrivyScannerArguments', 'Get-MavenInvocation', 'Get-DependencyCheckDataSourceArguments', 'Get-DependencyCheckArguments', 'Read-ValidatedDependencyCheckExceptions', 'Get-MavenPurlCoordinates', 'Get-MavenDependencyTreeEntries', 'Test-DependencyCheckExceptionScope', 'Write-DependencyCheckSuppressionFile', 'Remove-IsolatedNodeWorkspace', 'Confirm-JsonReport', 'Get-TrivyMavenPurlIdentity', 'Get-TrivyRequiredMavenPurls', 'Get-TrivyMavenRepositoryMount', 'Confirm-TrivyMavenCoverage', 'Remove-StaleScanFile', 'Write-ScanLog')) {
        $functionAst = $scriptAst.Find({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName
        }, $true)
        if ($null -eq $functionAst) {
            throw "$functionName function was not found in the production script."
        }
        Invoke-Expression $functionAst.Extent.Text
    }

    $unknownImagePath = Join-Path $testRoot 'unknown-image.trivyignore.yaml'
    Set-Content -LiteralPath $unknownImagePath -Value $unknownImagePolicy -Encoding utf8
    $unknownImageError = $null
    try {
        $null = Read-ValidatedExceptions -Path $unknownImagePath
    } catch {
        $unknownImageError = $_.Exception.Message
    }
    if ($unknownImageError -notmatch 'not a declared Trivy image target') {
        throw "Unknown Trivy image target was not rejected: $unknownImageError"
    }
    Write-Output 'PASS unknown image exception target was rejected by the policy parser.'

    $versionlessPurlPath = Join-Path $testRoot 'versionless-purl.trivyignore.yaml'
    $versionlessPurlPolicy = @"
vulnerabilities:
  - id: "CVE-2026-12346"
    purls:
      - "pkg:maven/io.example/example-library"
    affected: "image: sentinelops/ops-api:stage2b-security"
    expired_at: $validExpiry
    reason: "The vulnerable code is unreachable in this deployment."
    compensating_control: "The service blocks this route at the gateway."
    owner: "security@example.invalid"
"@
    Set-Content -LiteralPath $versionlessPurlPath -Value $versionlessPurlPolicy -Encoding utf8
    $versionlessPurlError = $null
    try {
        $null = Read-ValidatedExceptions -Path $versionlessPurlPath
    } catch {
        $versionlessPurlError = $_.Exception.Message
    }
    if ($versionlessPurlError -notmatch 'exact package version') {
        throw "Versionless exception PURL was accepted or rejected for the wrong reason: $versionlessPurlError"
    }
    Write-Output 'PASS versionless package URLs are rejected before scanner execution.'

    $script:reportRoot = Join-Path $testRoot 'scan-log-reports'
    $script:logPath = Join-Path $script:reportRoot 'security-scan.log'
    New-Item -ItemType Directory -Path $script:reportRoot -Force | Out-Null
    try {
        Write-ScanLog -Message ''
    } catch {
        throw "Write-ScanLog rejected an empty Maven output line: $($_.Exception.Message)"
    }
    if (-not (Test-Path -LiteralPath $script:logPath -PathType Leaf)) {
        throw 'Write-ScanLog did not append the empty Maven output line to its log.'
    }
    Write-Output 'PASS empty command output line was accepted by Write-ScanLog.'

    $transientCleanupRoot = Join-Path $testRoot 'node-transient-cleanup-fixture'
    New-Item -ItemType Directory -Path $transientCleanupRoot -Force | Out-Null
    $script:cleanupAttempts = 0
    $script:failures = [System.Collections.Generic.List[string]]::new()
    $transientCleanupResult = Remove-IsolatedNodeWorkspace -WorkspacePath $transientCleanupRoot -ReportRoot $testRoot -RemoveAction {
        param($Path)
        $script:cleanupAttempts++
        if ($script:cleanupAttempts -eq 1) {
            throw 'transient directory not empty'
        }
        Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction Stop
    }
    if ($transientCleanupResult -ne $true -or $script:cleanupAttempts -ne 2 -or
        $script:failures.Count -ne 0 -or (Test-Path -LiteralPath $transientCleanupRoot)) {
        throw 'A transient Windows directory removal race must retry and leave no security gate failure.'
    }
    Write-Output 'PASS isolated Node workspace cleanup retries a transient directory removal race.'

    $defaultCleanupRoot = Join-Path $testRoot 'node-default-cleanup-fixture'
    $defaultNestedDirectory = Join-Path $defaultCleanupRoot 'node_modules/example/deep'
    New-Item -ItemType Directory -Path $defaultNestedDirectory -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $defaultNestedDirectory 'package.json') -Value '{"name":"example"}'
    $script:failures = [System.Collections.Generic.List[string]]::new()
    $defaultCleanupResult = Remove-IsolatedNodeWorkspace -WorkspacePath $defaultCleanupRoot -ReportRoot $testRoot
    if ($defaultCleanupResult -ne $true -or $script:failures.Count -ne 0 -or
        (Test-Path -LiteralPath $defaultCleanupRoot)) {
        throw 'Default Node cleanup must delete nested package directories inside the verified report root.'
    }
    Write-Output 'PASS default isolated Node cleanup removes nested package directories.'

    $outsideCleanupRoot = Join-Path $testRoot 'report-root-sibling/node-workspace-outside'
    New-Item -ItemType Directory -Path $outsideCleanupRoot -Force | Out-Null
    $outsideMarker = Join-Path $outsideCleanupRoot 'keep.txt'
    Set-Content -LiteralPath $outsideMarker -Value 'outside the allowed report root'
    $script:failures = [System.Collections.Generic.List[string]]::new()
    $outsideCleanupResult = Remove-IsolatedNodeWorkspace -WorkspacePath $outsideCleanupRoot -ReportRoot (Join-Path $testRoot 'report-root')
    if ($outsideCleanupResult -ne $false -or $script:failures.Count -ne 1 -or
        $script:failures[0] -notmatch 'outside report directory' -or
        -not (Test-Path -LiteralPath $outsideMarker -PathType Leaf)) {
        throw 'Node cleanup must refuse a sibling directory outside its report root, even with a matching path prefix.'
    }
    Write-Output 'PASS isolated Node cleanup refuses a sibling directory outside its report root.'

    $nodeCleanupRoot = Join-Path $testRoot 'node-cleanup-fixture'
    New-Item -ItemType Directory -Path $nodeCleanupRoot -Force | Out-Null
    $script:failures = [System.Collections.Generic.List[string]]::new()
    $cleanupResult = Remove-IsolatedNodeWorkspace -WorkspacePath $nodeCleanupRoot -ReportRoot $testRoot -RemoveAction {
        param($Path)
        throw 'simulated locked file'
    }
    if ($cleanupResult -ne $false -or $script:failures.Count -ne 1 -or
        $script:failures[0] -notmatch 'simulated locked file' -or
        -not (Test-Path -LiteralPath $nodeCleanupRoot -PathType Container)) {
        throw 'A failed isolated Node workspace cleanup must be recorded without escaping as an exception or deleting evidence.'
    }
    Write-Output 'PASS isolated Node workspace cleanup failure is recorded and does not throw.'

    $odcDataDirectory = Join-Path $testRoot 'odc-datafeed-database'
    $odcSuppressionFile = Join-Path $testRoot 'dependency-check-suppressions.xml'
    $odcArguments = @(Get-DependencyCheckArguments -DataDirectory $odcDataDirectory -SuppressionFile $odcSuppressionFile)
    if ($odcArguments -notcontains "-DdataDirectory=$odcDataDirectory" -or
        $odcArguments -notcontains '-DretireJsAnalyzerEnabled=false' -or
        $odcArguments -notcontains "-DsuppressionFiles=$odcSuppressionFile" -or
        $odcArguments -notcontains '-DfailBuildOnUnusedSuppressionRule=true' -or
        $odcArguments -notcontains '-DnvdDatafeedUrl=https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz') {
        throw "OWASP must use the persistent datafeed database, exact test-scope suppressions with unused-rule failure, and the official feed. Got: $($odcArguments -join ' ')"
    }
    $rootPomText = [System.IO.File]::ReadAllText((Join-Path $repoRoot 'pom.xml'))
    if ($rootPomText -notmatch '<artifactId>dependency-check-maven</artifactId>\s*<version>12\.2\.2</version>' -or
        $rootPomText -notmatch '<failBuildOnCVSS>\s*7\.0\s*</failBuildOnCVSS>' -or
        $rootPomText -notmatch '<failOnError>\s*true\s*</failOnError>') {
        throw 'The pinned OWASP Maven plugin must fail closed at CVSS 7.0 and on analysis errors.'
    }
    Write-Output 'PASS OWASP uses the persistent official NVD feed database and leaves its CVE audit enabled without RetireJS network access.'

    $dependencyCheckPolicyPath = Join-Path $repoRoot 'docs/security/dependency-check-exceptions.json'
    $dependencyCheckExceptions = Read-ValidatedDependencyCheckExceptions -Path $dependencyCheckPolicyPath
    if ($dependencyCheckExceptions.Count -ne 5) {
        throw "Expected the reviewed exact test-scope OWASP exceptions, got $($dependencyCheckExceptions.Count)."
    }
    $treeFixturePath = Join-Path $testRoot 'dependency-tree-test.json'
    $treeFixture = @{
        groupId = 'io.sentinelops'
        artifactId = 'fixture'
        version = '0.1.0-SNAPSHOT'
        scope = ''
        children = @(
            @{
                groupId = 'com.github.docker-java'
                artifactId = 'docker-java-transport-zerodep'
                version = '3.7.1'
                scope = 'test'
                children = @()
            }
        )
    } | ConvertTo-Json -Depth 8
    [System.IO.File]::WriteAllText($treeFixturePath, $treeFixture, [System.Text.UTF8Encoding]::new($false))
    $treeEntries = @(Get-MavenDependencyTreeEntries -Path $treeFixturePath)
    $null = Test-DependencyCheckExceptionScope -Exceptions $dependencyCheckExceptions -Entries $treeEntries
    Write-Output 'PASS OWASP exception scope validator accepts an exact Testcontainers test-only dependency tree.'

    $runtimeTreeFixture = @{
        groupId = 'io.sentinelops'
        artifactId = 'fixture'
        version = '0.1.0-SNAPSHOT'
        scope = ''
        children = @(
            @{
                groupId = 'com.github.docker-java'
                artifactId = 'docker-java-transport-zerodep'
                version = '3.7.1'
                scope = 'test'
                children = @()
            },
            @{
                groupId = 'org.apache.httpcomponents.client5'
                artifactId = 'httpclient5'
                version = '5.5.1'
                scope = 'runtime'
                children = @()
            }
        )
    } | ConvertTo-Json -Depth 8
    [System.IO.File]::WriteAllText($treeFixturePath, $runtimeTreeFixture, [System.Text.UTF8Encoding]::new($false))
    $runtimeEntries = @(Get-MavenDependencyTreeEntries -Path $treeFixturePath)
    $runtimeException = $null
    try {
        $null = Test-DependencyCheckExceptionScope -Exceptions $dependencyCheckExceptions -Entries $runtimeEntries
    } catch {
        $runtimeException = $_.Exception.Message
    }
    if ($runtimeException -notmatch 'outside test scope') {
        throw "OWASP exception did not reject a vulnerable PURL found at runtime: $runtimeException"
    }
    $missingContainerFixture = @{
        groupId = 'io.sentinelops'
        artifactId = 'fixture'
        version = '0.1.0-SNAPSHOT'
        scope = ''
        children = @()
    } | ConvertTo-Json -Depth 8
    [System.IO.File]::WriteAllText($treeFixturePath, $missingContainerFixture, [System.Text.UTF8Encoding]::new($false))
    $missingContainerEntries = @(Get-MavenDependencyTreeEntries -Path $treeFixturePath)
    $missingContainerException = $null
    try {
        $null = Test-DependencyCheckExceptionScope -Exceptions $dependencyCheckExceptions -Entries $missingContainerEntries
    } catch {
        $missingContainerException = $_.Exception.Message
    }
    if ($missingContainerException -notmatch 'absent from test scope') {
        throw "OWASP exception did not fail closed when its test-only container was absent: $missingContainerException"
    }

    $suppressionFilePath = $odcSuppressionFile
    Write-DependencyCheckSuppressionFile -Exceptions $dependencyCheckExceptions -Path $suppressionFilePath
    $suppressionXml = [System.Xml.XmlDocument]::new()
    $suppressionXml.Load($suppressionFilePath)
    $namespaceManager = [System.Xml.XmlNamespaceManager]::new($suppressionXml.NameTable)
    $namespaceManager.AddNamespace('odc', 'https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.4.xsd')
    $suppressionNodes = $suppressionXml.SelectNodes('/odc:suppressions/odc:suppress', $namespaceManager)
    if ($suppressionNodes.Count -ne $dependencyCheckExceptions.Count) {
        throw 'OWASP suppression XML omitted or added exception rules.'
    }
    foreach ($exception in $dependencyCheckExceptions) {
        $matchingNode = @($suppressionNodes | Where-Object {
            $_.SelectSingleNode('odc:cve', $namespaceManager).InnerText -eq $exception.cve -and
            $_.SelectSingleNode('odc:packageUrl', $namespaceManager).InnerText -eq $exception.purl
        })
        if ($matchingNode.Count -ne 1 -or $matchingNode[0].SelectSingleNode('odc:packageUrl', $namespaceManager).GetAttribute('regex') -ne 'false' -or
            $matchingNode[0].GetAttribute('until') -ne "$($exception.expires_at)Z") {
            throw "OWASP suppression rule is not exact or missing its expiry for $($exception.cve) / $($exception.purl)."
        }
        $elementNames = @($matchingNode[0].ChildNodes | Where-Object { $_.NodeType -eq 'Element' } | ForEach-Object { $_.LocalName })
        if (($elementNames -join ',') -ne 'notes,packageUrl,cve') {
            throw "OWASP suppression for $($exception.cve) must use the schema-valid exact packageUrl selector followed by its CVE."
        }
    }
    Write-Output 'PASS OWASP suppression XML matches exact PURL/CVE pairs and expires each rule.'

    $script:failures = [System.Collections.Generic.List[string]]::new()
    $emptyDependencyCheckPath = Join-Path $testRoot 'empty-dependency-check.json'
    [System.IO.File]::WriteAllText($emptyDependencyCheckPath, '{"dependencies":[]}', [System.Text.UTF8Encoding]::new($false))
    $emptyDependencyCheck = Confirm-JsonReport -Path $emptyDependencyCheckPath -Kind 'dependency-check'
    if ($null -ne $emptyDependencyCheck -or $script:failures.Count -ne 1 -or
        $script:failures[0] -notmatch 'at least one dependency record') {
        throw 'OWASP Dependency-Check accepted an empty dependency report or failed to record the validation error.'
    }
    $validDependencyCheckPath = Join-Path $testRoot 'valid-dependency-check.json'
    [System.IO.File]::WriteAllText($validDependencyCheckPath, '{"dependencies":[{"fileName":"sentinelops-api.jar","vulnerabilities":[{"name":"CVE-2026-12345"}]}]}', [System.Text.UTF8Encoding]::new($false))
    $validDependencyCheck = Confirm-JsonReport -Path $validDependencyCheckPath -Kind 'dependency-check'
    if ($null -eq $validDependencyCheck -or @($validDependencyCheck.dependencies).Count -ne 1) {
        throw 'OWASP Dependency-Check rejected a valid report with a dependency record.'
    }
    $dependencyCheckTestLog = Get-Content -LiteralPath $script:logPath -Raw
    if ($dependencyCheckTestLog -notmatch '1 vulnerable dependency record\(s\), and 1 unique CVE\(s\)') {
        throw 'OWASP report verification did not summarize vulnerable dependency and unique CVE counts.'
    }
    Write-Output 'PASS OWASP report verification rejects empty dependency arrays and accepts nonempty scans.'

    $staleReportPath = Join-Path $script:reportRoot 'trivy-filesystem.json'
    $unrelatedReportPath = Join-Path $script:reportRoot 'npm-audit.json'
    Set-Content -LiteralPath $staleReportPath -Value '{"stale":true}' -Encoding utf8
    Set-Content -LiteralPath $unrelatedReportPath -Value '{"keep":true}' -Encoding utf8
    Remove-StaleScanFile -Path $staleReportPath -Label 'Trivy filesystem report'
    if (Test-Path -LiteralPath $staleReportPath) {
        throw 'The previous Trivy report was not removed before a new scan.'
    }
    if (-not (Test-Path -LiteralPath $unrelatedReportPath -PathType Leaf) -or
        (Get-Content -LiteralPath $unrelatedReportPath -Raw) -notmatch 'keep') {
        throw 'Clearing a stale Trivy report modified an unrelated audit report.'
    }
    Write-Output 'PASS stale scan output is cleared by exact path while unrelated report evidence remains.'

    $skipDirectoriesAst = $scriptAst.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-TrivyFilesystemSkipDirectories'
    }, $true)
    if ($null -eq $skipDirectoriesAst) {
        throw 'Get-TrivyFilesystemSkipDirectories function was not found in the production script.'
    }
    Invoke-Expression $skipDirectoriesAst.Extent.Text
    $skipDirectories = @(Get-TrivyFilesystemSkipDirectories)
    foreach ($localDirectory in @('/workspace/.toolchains', '/workspace/.worktrees')) {
        if ($skipDirectories -notcontains $localDirectory) {
            throw "Trivy filesystem scan does not exclude local directory '$localDirectory'."
        }
    }
    if ($skipDirectories -contains '/workspace') {
        throw 'Trivy filesystem exclusions must preserve the repository source tree.'
    }
    Write-Output 'PASS Trivy filesystem scan skips local toolchain/worktree directories while preserving source.'

    $filesystemVulnerabilityArguments = @(Get-TrivyScannerArguments -TargetType filesystem -ScanType vulnerability)
    $filesystemSecretArguments = @(Get-TrivyScannerArguments -TargetType filesystem -ScanType secret)
    $imageVulnerabilityArguments = @(Get-TrivyScannerArguments -TargetType image -ScanType vulnerability)
    $imageSecretArguments = @(Get-TrivyScannerArguments -TargetType image -ScanType secret)
    foreach ($arguments in @($filesystemVulnerabilityArguments, $filesystemSecretArguments, $imageVulnerabilityArguments, $imageSecretArguments)) {
        if (($arguments -join ' ') -notmatch '--timeout 15m') {
            throw "Trivy scans must have a 15-minute timeout to allow the Java DB to download: $($arguments -join ' ')"
        }
    }
    Write-Output 'PASS all four Trivy scan modes use a 15-minute timeout.'
    foreach ($arguments in @($filesystemVulnerabilityArguments, $imageVulnerabilityArguments)) {
        if (($arguments -join ' ') -notmatch '--severity HIGH,CRITICAL') {
            throw "Vulnerability scan did not preserve the HIGH,CRITICAL threshold: $($arguments -join ' ')"
        }
    }
    if ($filesystemVulnerabilityArguments -notcontains '--offline-scan' -or
        $filesystemSecretArguments -notcontains '--offline-scan' -or
        $imageVulnerabilityArguments -contains '--offline-scan' -or
        $imageSecretArguments -contains '--offline-scan') {
        throw 'Both Trivy filesystem scans must use Maven-cache-backed --offline-scan mode; image scans must stay online-capable.'
    }
    foreach ($arguments in @($filesystemSecretArguments, $imageSecretArguments)) {
        if (($arguments -join ' ') -match '--severity') {
            throw "Secrets scan incorrectly filters secret findings by severity: $($arguments -join ' ')"
        }
        if (($arguments -join ' ') -notmatch '--scanners secret --exit-code 1') {
            throw "Secrets scan does not fail on all secret findings: $($arguments -join ' ')"
        }
    }
    $secretArgumentCalls = @($scriptAst.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.CommandAst] -and
            $node.GetCommandName() -eq 'Get-TrivyScannerArguments' -and
            $node.Extent.Text -match '-ScanType secret'
    }, $true))
    $vulnerabilityArgumentCalls = @($scriptAst.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.CommandAst] -and
            $node.GetCommandName() -eq 'Get-TrivyScannerArguments' -and
            $node.Extent.Text -match '-ScanType vulnerability'
    }, $true))
    if ($secretArgumentCalls.Count -ne 2 -or $vulnerabilityArgumentCalls.Count -ne 2) {
        throw "Expected separate filesystem/image secret and vulnerability scan integrations; found $($secretArgumentCalls.Count) secret and $($vulnerabilityArgumentCalls.Count) vulnerability calls."
    }
    $productionScriptText = [System.IO.File]::ReadAllText($securityScan)
    if ($productionScriptText -notmatch '\$filesystemSecretsArguments \+= @\(''--mount'', \$mountMavenCache\)') {
        throw 'The Trivy filesystem secrets scan must mount the same populated Maven cache as the vulnerability scan.'
    }
    if ($productionScriptText -notmatch 'trivy-filesystem-secrets\.json' -or
        $productionScriptText -notmatch 'trivy-image-\$\(\$image\.name\)-secrets\.json') {
        throw 'Separate filesystem and image secret scan JSON reports are not configured.'
    }
    if ($productionScriptText -notmatch '\$npmCacheRoot = Join-Path \$reportRoot .npm-cache.' -or
        $productionScriptText -match '\$npmCacheRoot = Join-Path \$nodeWorkspace') {
        throw 'npm must use a persistent cache under the report directory, separate from each temporary Node workspace.'
    }
    if ($productionScriptText -notmatch 'Get-DependencyCheckArguments -DataDirectory \$dependencyCheckDataDirectory' -or
        $productionScriptText -notmatch '\$dependencyCheckDataDirectory = Join-Path \$reportRoot .odc-datafeed-database.') {
        throw 'OWASP must use its persistent report-directory database and the tested Dependency-Check options.'
    }
    $dependencyTreePosition = $productionScriptText.IndexOf('maven-dependency-plugin:3.10.0:tree', [System.StringComparison]::Ordinal)
    $dependencyCheckPosition = $productionScriptText.IndexOf("-Label 'OWASP Dependency-Check aggregate audit", [System.StringComparison]::Ordinal)
    if ($dependencyTreePosition -lt 0 -or $dependencyCheckPosition -lt 0 -or $dependencyTreePosition -gt $dependencyCheckPosition -or
        $productionScriptText -notmatch 'Test-DependencyCheckExceptionScope -Exceptions \$dependencyCheckExceptions') {
        throw 'OWASP exceptions must be checked against pinned Maven dependency trees before the audit runs.'
    }
    if ($productionScriptText -notmatch 'dependencyCheckExceptions.Count -gt 0' -or
        $productionScriptText -notmatch 'failBuildOnUnusedSuppressionRule=true') {
        throw 'OWASP exception scope validation and unused-suppression failure must remain active.'
    }
    $workspaceCleanupPosition = $productionScriptText.IndexOf('Remove-IsolatedNodeWorkspace -WorkspacePath $nodeWorkspace', [System.StringComparison]::Ordinal)
    $dockerGatePosition = $productionScriptText.IndexOf('$dockerCommand = Get-Command docker', [System.StringComparison]::Ordinal)
    if ($workspaceCleanupPosition -lt 0 -or $dockerGatePosition -lt 0 -or $workspaceCleanupPosition -gt $dockerGatePosition) {
        throw 'Node workspace cleanup must return a failure status and allow Docker/Trivy gates to run afterward.'
    }
    $scriptBehaviorTestPosition = $productionScriptText.IndexOf("-Label 'Security scan behavior tests'", [System.StringComparison]::Ordinal)
    $scriptMavenGatePosition = $productionScriptText.IndexOf("'-Dtest=*Test,*Tests,*IT'", [System.StringComparison]::Ordinal)
    if ($scriptBehaviorTestPosition -lt 0 -or $scriptMavenGatePosition -lt 0 -or $scriptBehaviorTestPosition -gt $scriptMavenGatePosition) {
        throw 'The main security scan must run the behavior tests before the Maven verification gate.'
    }
    foreach ($workflowName in @('ci.yml', 'security.yml')) {
        $workflowPath = Join-Path $repoRoot ".github/workflows/$workflowName"
        $workflowText = [System.IO.File]::ReadAllText($workflowPath)
        $javaSetupStep = [regex]::Match($workflowText, '(?ms)^      - name: Set up Java 21\r?\n.*?(?=^      - name:|\z)').Value
        if ($javaSetupStep -notmatch '(?m)^\s+cache:\s*maven\s*$') {
            throw "Workflow '$workflowName' must cache Maven dependencies and the Dependency-Check database."
        }
        $odcCacheStep = [regex]::Match($workflowText, '(?ms)^      - name: Cache OWASP and npm scan data\r?\n.*?(?=^      - name:|\z)').Value
        if ($odcCacheStep -notmatch 'actions/cache@cdf6c1fa76f9f475f3d7449005a359c84ca0f306 # v5.0.3' -or
            $odcCacheStep -notmatch '(?m)^\s+path:\s*\|\s*$' -or
            $odcCacheStep -notmatch 'build/security/odc-datafeed-database' -or
            $odcCacheStep -notmatch 'build/security/npm-cache') {
            throw "Workflow '$workflowName' must persist the exact OWASP datafeed database and npm cache with the pinned actions/cache v5.0.3 action."
        }
        $cachePosition = $workflowText.IndexOf('Cache OWASP and npm scan data', [System.StringComparison]::Ordinal)
        $behaviorPosition = $workflowText.IndexOf('Run security scan behavior tests', [System.StringComparison]::Ordinal)
        if ($cachePosition -lt 0 -or $behaviorPosition -lt 0 -or $cachePosition -gt $behaviorPosition) {
            throw "Workflow '$workflowName' must restore the OWASP/npm cache before running its security gates."
        }
        $javaVersionMatch = [regex]::Match($workflowText, '(?m)^\s+java-version:\s*(\S+)\s*$')
        if (-not $javaVersionMatch.Success -or $javaVersionMatch.Groups[1].Value -ne '21.0.12.1') {
            throw "Workflow '$workflowName' must request the Temurin 21.0.12.1 security patch using setup-java's supported version syntax."
        }
        $scanStep = [regex]::Match($workflowText, '(?ms)^      - name: Run required tests, dependency audits, SBOM generation and Trivy scans\r?\n.*?(?=^      - name:|\z)').Value
        if ($scanStep -notmatch '(?m)^\s+NVD_API_KEY:\s*\$\{\{\s*secrets\.NVD_API_KEY\s*\}\}\s*$') {
            throw "Workflow '$workflowName' must pass the optional NVD_API_KEY secret to the audit script."
        }
        $behaviorStepPosition = $workflowText.IndexOf('Run security scan behavior tests', [System.StringComparison]::Ordinal)
        $securityScanStepPosition = $workflowText.IndexOf('Run required tests, dependency audits, SBOM generation and Trivy scans', [System.StringComparison]::Ordinal)
        if ($behaviorStepPosition -lt 0 -or $securityScanStepPosition -lt 0 -or $behaviorStepPosition -gt $securityScanStepPosition) {
            throw "Workflow '$workflowName' must run the behavior test step before the complete security scan."
        }
        if ($workflowText -notmatch 'build/security/\*\.xml') {
            throw "Workflow '$workflowName' must upload the generated OWASP suppression XML for auditability."
        }
    }
    $hadNvdApiKey = Test-Path Env:NVD_API_KEY
    $originalNvdApiKey = $env:NVD_API_KEY
    try {
        Remove-Item Env:NVD_API_KEY -ErrorAction SilentlyContinue
        $mirrorArguments = @(Get-DependencyCheckDataSourceArguments)
        if ($mirrorArguments.Count -ne 1 -or
            $mirrorArguments[0] -ne '-DnvdDatafeedUrl=https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz') {
            throw "Missing NVD_API_KEY must use the official Dependency-Check mirror feed. Got: $($mirrorArguments -join ' ')"
        }
        $env:NVD_API_KEY = 'behavior-test-secret'
        $keyArguments = @(Get-DependencyCheckDataSourceArguments)
        if ($keyArguments.Count -ne 1 -or
            $keyArguments[0] -ne '-DnvdApiKeyEnvironmentVariable=NVD_API_KEY' -or
            ($keyArguments -join ' ') -match 'behavior-test-secret') {
            throw 'Configured NVD_API_KEY must be selected through the plugin environment-variable input without exposing its value in Maven arguments.'
        }
    } finally {
        if ($hadNvdApiKey) {
            $env:NVD_API_KEY = $originalNvdApiKey
        } else {
            Remove-Item Env:NVD_API_KEY -ErrorAction SilentlyContinue
        }
    }
    Write-Output 'PASS OWASP uses the daily official NVD datafeed without a key and safely uses NVD_API_KEY when configured.'
    Write-Output 'PASS Trivy secrets scans are unfiltered by severity and fail on findings; vulnerability scans retain HIGH,CRITICAL.'
    Write-Output 'PASS behavior tests are wired into the local and both CI security gates.'

    $linuxMavenInvocation = Get-MavenInvocation -WrapperPath '/repo/mvnw' -Arguments @('-B', 'verify') -OnWindows:$false
    if ([IO.Path]::GetFileName($linuxMavenInvocation.Executable) -notmatch '^bash(?:\.cmd)?$' -or
        $linuxMavenInvocation.Arguments.Count -ne 3 -or
        $linuxMavenInvocation.Arguments[0] -ne '/repo/mvnw' -or
        $linuxMavenInvocation.Arguments[1] -ne '-B' -or
        $linuxMavenInvocation.Arguments[2] -ne 'verify') {
        throw "Linux Maven invocation must call bash with the non-executable wrapper path and original arguments; got '$($linuxMavenInvocation.Executable)' :: '$($linuxMavenInvocation.Arguments -join ' ')'."
    }
    $windowsMavenInvocation = Get-MavenInvocation -WrapperPath 'C:/repo/mvnw.cmd' -Arguments @('-B', 'verify') -OnWindows:$true
    if ($windowsMavenInvocation.Executable -ne 'C:/repo/mvnw.cmd' -or
        $windowsMavenInvocation.Arguments.Count -ne 2 -or
        $windowsMavenInvocation.Arguments[0] -ne '-B' -or
        $windowsMavenInvocation.Arguments[1] -ne 'verify') {
        throw 'Windows Maven invocation must call mvnw.cmd directly and preserve its original arguments.'
    }
    if ([regex]::Matches($productionScriptText, 'Get-MavenInvocation -WrapperPath \$mavenWrapper -Arguments \$(?:mavenArgs|dependencyCheckArgs)').Count -ne 2) {
        throw 'Both Maven verification and Dependency-Check must use the platform-aware Maven invocation.'
    }
    Write-Output 'PASS Maven wrapper invocation uses bash on Linux and mvnw.cmd on Windows.'

    $javaBomFixturePath = Join-Path $testRoot 'required-java-bom.json'
    $requiredJavaBomFixture = @{
        bomFormat = 'CycloneDX'
        specVersion = '1.6'
        components = @(
            @{ name = 'spring-boot-starter-webmvc'; purl = 'pkg:maven/org.springframework.boot/spring-boot-starter-webmvc@4.1.1?type=jar' },
            @{ name = 'spring-web'; purl = 'pkg:maven/org.springframework/spring-web@7.0.9?type=jar' },
            @{ name = 'postgresql'; purl = 'pkg:maven/org.postgresql/postgresql@42.7.13?type=jar' },
            @{ name = 'tomcat-embed-core'; purl = 'pkg:maven/org.apache.tomcat.embed/tomcat-embed-core@11.0.26?type=jar' }
        )
    } | ConvertTo-Json -Depth 6
    [System.IO.File]::WriteAllText($javaBomFixturePath, $requiredJavaBomFixture, [System.Text.UTF8Encoding]::new($false))
    $requiredMavenPurls = @(Get-TrivyRequiredMavenPurls -JavaBomPath $javaBomFixturePath)
    if ($requiredMavenPurls.Count -ne 4) {
        throw "Expected four key Java Maven package URLs, got $($requiredMavenPurls.Count)."
    }
    $mavenRepositoryFixture = Join-Path $testRoot 'm2/repository'
    foreach ($coordinate in @(
        @{ group = 'org.springframework.boot'; name = 'spring-boot-starter-webmvc'; version = '4.1.1' },
        @{ group = 'org.springframework'; name = 'spring-web'; version = '7.0.9' },
        @{ group = 'org.postgresql'; name = 'postgresql'; version = '42.7.13' },
        @{ group = 'org.apache.tomcat.embed'; name = 'tomcat-embed-core'; version = '11.0.26' }
    )) {
        $groupPath = $coordinate.group.Replace('.', [System.IO.Path]::DirectorySeparatorChar)
        $artifactDirectory = Join-Path (Join-Path (Join-Path $mavenRepositoryFixture $groupPath) $coordinate.name) $coordinate.version
        New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $artifactDirectory "$($coordinate.name)-$($coordinate.version).pom") -Value '<project />' -Encoding utf8
    }
    $mavenRepositoryMount = Get-TrivyMavenRepositoryMount -HostMavenRepository $mavenRepositoryFixture -RequiredPurls $requiredMavenPurls
    if ($mavenRepositoryMount -notmatch [regex]::Escape($mavenRepositoryFixture) -or
        $mavenRepositoryMount -notmatch ',target=/root/\.m2/repository,readonly$') {
        throw "Maven repository mount must be explicit and read-only: $mavenRepositoryMount"
    }
    $missingMavenCacheError = $null
    try {
        $null = Get-TrivyMavenRepositoryMount -HostMavenRepository (Join-Path $testRoot 'missing-maven-repository') -RequiredPurls $requiredMavenPurls
    } catch {
        $missingMavenCacheError = $_.Exception.Message
    }
    if ($missingMavenCacheError -notmatch 'Maven cache') {
        throw "Missing Maven cache was not rejected explicitly: $missingMavenCacheError"
    }

    $trivyMavenReportPath = Join-Path $testRoot 'trivy-maven-packages.json'
    $trivyMavenReport = @{
        Results = @(
            @{ Packages = @(
                @{ Identifier = @{ PURL = 'pkg:maven/org.springframework.boot/spring-boot-starter-webmvc@4.1.1' } },
                @{ Identifier = @{ PURL = 'pkg:maven/org.springframework/spring-web@7.0.9' } },
                @{ Identifier = @{ PURL = 'pkg:maven/org.postgresql/postgresql@42.7.13' } },
                @{ Identifier = @{ PURL = 'pkg:maven/org.apache.tomcat.embed/tomcat-embed-core@11.0.26' } },
                @{ Identifier = @{ PURL = 'pkg:npm/%40radix-ui/react-dialog@1.1.23' } }
            ) }
        )
    } | ConvertTo-Json -Depth 8
    [System.IO.File]::WriteAllText($trivyMavenReportPath, $trivyMavenReport, [System.Text.UTF8Encoding]::new($false))
    $null = Confirm-TrivyMavenCoverage -JavaBomPath $javaBomFixturePath -TrivyReportPath $trivyMavenReportPath
    $trivyMavenReport = @{
        Results = @(
            @{ Packages = @(
                @{ Identifier = @{ PURL = 'pkg:maven/org.springframework.boot/spring-boot-starter-webmvc@4.1.1' } },
                @{ Identifier = @{ PURL = 'pkg:maven/org.springframework/spring-web@7.0.9' } },
                @{ Identifier = @{ PURL = 'pkg:maven/org.apache.tomcat.embed/tomcat-embed-core@11.0.26' } }
            ) }
        )
    } | ConvertTo-Json -Depth 8
    [System.IO.File]::WriteAllText($trivyMavenReportPath, $trivyMavenReport, [System.Text.UTF8Encoding]::new($false))
    $missingMavenPackageError = $null
    try {
        $null = Confirm-TrivyMavenCoverage -JavaBomPath $javaBomFixturePath -TrivyReportPath $trivyMavenReportPath
    } catch {
        $missingMavenPackageError = $_.Exception.Message
    }
    if ($missingMavenPackageError -notmatch 'postgresql') {
        throw "Incomplete offline Maven scan was not rejected for the missing key dependency: $missingMavenPackageError"
    }
    if ($productionScriptText -notmatch 'Confirm-TrivyMavenCoverage -JavaBomPath \$javaBomDestination -TrivyReportPath \$filesystemReport') {
        throw 'The real Trivy filesystem report is not checked for key Java dependency coverage.'
    }
    if ($productionScriptText -notmatch 'Trivy offline Maven cache preflight failed' -or
        $productionScriptText -notmatch 'Trivy filesystem Java dependency coverage validation failed' -or
        $productionScriptText -notmatch 'if \(\$mountMavenCache\)') {
        throw 'Missing Maven cache or package coverage must be recorded as a hard scan failure; the cache mount must be conditional on successful validation.'
    }
    Write-Output 'PASS Trivy filesystem scan mounts a populated Maven cache read-only, runs offline, and fails if key Java dependencies are absent.'

    $emptyPolicyPath = Join-Path $testRoot 'empty.trivyignore.yaml'
    Set-Content -LiteralPath $emptyPolicyPath -Value 'vulnerabilities: []' -Encoding utf8
    $emptyExceptions = Read-ValidatedExceptions -Path $emptyPolicyPath
    if ($null -eq $emptyExceptions) {
        throw 'A valid empty exception policy returned null instead of an empty collection.'
    }
    if ($emptyExceptions.Count -ne 0) {
        throw "An empty exception policy returned $($emptyExceptions.Count) exception(s)."
    }
    $emptyIgnorePath = Join-Path $testRoot 'empty.trivyignore.generated.yaml'
    Write-TrivyIgnoreFile -Exceptions $emptyExceptions -Path $emptyIgnorePath -ScanType filesystem
    $generatedIgnore = Get-Content -LiteralPath $emptyIgnorePath -Raw
    if ($generatedIgnore.Trim() -ne 'vulnerabilities: []') {
        throw "An empty exception policy generated an invalid Trivy ignore file: $generatedIgnore"
    }
    Write-Output 'PASS empty exception policy generated an empty Trivy ignore file.'

    $scopedPolicyPath = Join-Path $testRoot 'scoped.trivyignore.yaml'
    $scopedPolicy = @"
vulnerabilities:
  - id: "CVE-2026-12345"
    purls:
      - "pkg:maven/io.example/example-library@1.2.3?type=jar#module"
    affected: "image: sentinelops/ops-api:stage2b-security; path: pom.xml; component: io.example:example-library:1.2.3"
    expired_at: $validExpiry
    reason: "The vulnerable code is unreachable in this deployment."
    compensating_control: "The service blocks this route at the gateway."
    owner: "security@example.invalid"
"@
    Set-Content -LiteralPath $scopedPolicyPath -Value $scopedPolicy -Encoding utf8
    $scopedExceptions = Read-ValidatedExceptions -Path $scopedPolicyPath
    if ($scopedExceptions.Count -ne 1) {
        throw "Expected one parsed scoped exception, got $($scopedExceptions.Count)."
    }
    $scopedException = $scopedExceptions[0]
    if ($scopedException.affected_images -notcontains 'sentinelops/ops-api:stage2b-security' -or
        $scopedException.affected_paths -notcontains 'pom.xml') {
        throw 'The exception parser did not preserve its exact image and filesystem path scopes.'
    }

    $filesystemIgnorePath = Join-Path $testRoot 'scoped-filesystem.trivyignore.yaml'
    $apiIgnorePath = Join-Path $testRoot 'scoped-api.trivyignore.yaml'
    $consoleIgnorePath = Join-Path $testRoot 'scoped-console.trivyignore.yaml'
    $repositoryPomPath = Join-Path $repoRoot 'pom.xml'
    $repositoryPomHashBefore = (Get-FileHash -LiteralPath $repositoryPomPath -Algorithm SHA256).Hash
    $previousLocation = Get-Location
    $previousProcessDirectory = [Environment]::CurrentDirectory
    try {
        Set-Location -LiteralPath $testRoot
        [Environment]::CurrentDirectory = $testRoot
        Write-TrivyIgnoreFile -Exceptions $scopedExceptions -Path $filesystemIgnorePath -ScanType filesystem
        Write-TrivyIgnoreFile -Exceptions $scopedExceptions -Path $apiIgnorePath -ScanType image -ImageTag 'sentinelops/ops-api:stage2b-security'
        Write-TrivyIgnoreFile -Exceptions $scopedExceptions -Path $consoleIgnorePath -ScanType image -ImageTag 'sentinelops/ops-console:stage2b-security'
    } finally {
        [Environment]::CurrentDirectory = $previousProcessDirectory
        Set-Location -LiteralPath $previousLocation.Path
    }
    if (Test-Path -LiteralPath (Join-Path $testRoot 'pom.xml')) {
        throw 'Filesystem ignore generation overwrote an unrelated current-directory pom.xml instead of using its requested output path.'
    }
    $repositoryPomHashAfter = (Get-FileHash -LiteralPath $repositoryPomPath -Algorithm SHA256).Hash
    if ($repositoryPomHashAfter -ne $repositoryPomHashBefore) {
        throw 'Trivy ignore generation modified the repository pom.xml outside its requested output path.'
    }
    foreach ($expectedIgnorePath in @($filesystemIgnorePath, $apiIgnorePath, $consoleIgnorePath)) {
        if (-not (Test-Path -LiteralPath $expectedIgnorePath -PathType Leaf)) {
            throw "Trivy ignore generation did not create its requested output file: $expectedIgnorePath"
        }
    }
    $filesystemIgnore = Get-Content -LiteralPath $filesystemIgnorePath -Raw
    $apiIgnore = Get-Content -LiteralPath $apiIgnorePath -Raw
    $consoleIgnore = Get-Content -LiteralPath $consoleIgnorePath -Raw
    if ($filesystemIgnore -notmatch 'CVE-2026-12345' -or $filesystemIgnore -notmatch 'paths:' -or $filesystemIgnore -notmatch 'pom\.xml') {
        throw 'The filesystem exception was not narrowed to the affected file path.'
    }
    if ($apiIgnore -notmatch 'CVE-2026-12345' -or $consoleIgnore -match 'CVE-2026-12345') {
        throw 'Image exception leaked across scanner targets.'
    }
    Write-Output 'PASS Trivy ignore files scope a CVE/PURL to the affected filesystem path and image only.'

    $initializerAst = $scriptAst.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Initialize-Java21'
    }, $true)
    if ($null -eq $initializerAst) {
        throw 'Initialize-Java21 function was not found in the production script.'
    }

    $script:failures = [System.Collections.Generic.List[string]]::new()
    Invoke-Expression $initializerAst.Extent.Text
    $savedJavaHome = $env:JAVA_HOME
    $savedJavaPath = $env:PATH
    $savedRepositoryRoot = $script:repositoryRoot
    try {
        $javaExecutableName = if ($IsWindows) { 'java.exe' } else { 'java' }
        $knownJava21Home = $null
        if ($IsWindows) {
            $java21Candidates = [System.Collections.Generic.List[string]]::new()
            if (-not [string]::IsNullOrWhiteSpace($savedJavaHome)) {
                $java21Candidates.Add($savedJavaHome)
            }
            $localJdkRoot = Join-Path $repoRoot '.toolchains/jdk-21'
            if (Test-Path -LiteralPath $localJdkRoot -PathType Container) {
                foreach ($localJdk in Get-ChildItem -LiteralPath $localJdkRoot -Directory) {
                    $java21Candidates.Add($localJdk.FullName)
                }
            }
            foreach ($candidate in $java21Candidates) {
                $candidateJava = Join-Path $candidate "bin/$javaExecutableName"
                if (-not (Test-Path -LiteralPath $candidateJava -PathType Leaf)) {
                    continue
                }
                $candidateVersion = @(& $candidateJava -version 2>&1 | ForEach-Object { [string]$_ }) -join ' '
                if ($candidateVersion -match '(?i)(?:version\s+")?21\.') {
                    $knownJava21Home = $candidate
                    break
                }
            }
        } else {
            $knownJava21Home = Join-Path $testRoot 'java21-fixture'
            $java21Bin = Join-Path $knownJava21Home 'bin'
            New-Item -ItemType Directory -Path $java21Bin -Force | Out-Null
            $javaFixturePath = Join-Path $java21Bin 'java'
            $javaFixtureScript = @'
#!/bin/sh
printf '%s\n' 'openjdk version "21.0.0-test"'
exit 0
'@
            [System.IO.File]::WriteAllText($javaFixturePath, $javaFixtureScript.Replace("`r`n", "`n"), [System.Text.UTF8Encoding]::new($false))
            $javaMode = [System.IO.UnixFileMode]::UserRead -bor [System.IO.UnixFileMode]::UserWrite -bor [System.IO.UnixFileMode]::UserExecute -bor [System.IO.UnixFileMode]::GroupRead -bor [System.IO.UnixFileMode]::GroupExecute -bor [System.IO.UnixFileMode]::OtherRead -bor [System.IO.UnixFileMode]::OtherExecute
            [System.IO.File]::SetUnixFileMode($javaFixturePath, $javaMode)
        }
        if ($null -eq $knownJava21Home) {
            throw 'The behavior test requires Java 21 from JAVA_HOME, local toolchain, or setup-java.'
        }

        $env:JAVA_HOME = Join-Path $testRoot 'missing-jdk-17'
        $env:PATH = "$(Join-Path $knownJava21Home 'bin')$([System.IO.Path]::PathSeparator)$savedJavaPath"
        $script:repositoryRoot = $testRoot
        if (-not (Initialize-Java21)) {
            throw "Java 21 initializer did not find Java 21 on PATH after rejecting the invalid JAVA_HOME: $($script:failures -join '; ')"
        }
        $selectedJava = Join-Path $env:JAVA_HOME "bin/$javaExecutableName"
        $selectedVersion = & $selectedJava -version 2>&1 | Out-String
        if ($selectedVersion -notmatch '(?i)(?:version\s+")?21\.') {
            throw "Java 21 initializer selected a non-Java-21 JDK from PATH: $selectedVersion"
        }
        Write-Output "PASS Java initializer selected Java 21 from PATH at $env:JAVA_HOME."
    }
    finally {
        $env:JAVA_HOME = $savedJavaHome
        $env:PATH = $savedJavaPath
        $script:repositoryRoot = $savedRepositoryRoot
    }
}
finally {
    $env:PATH = $oldPath
    Remove-Item Env:SECURITY_SCAN_DOCKER_MARKER -ErrorAction SilentlyContinue
    if ($null -eq $oldChildMarker) {
        Remove-Item Env:SENTINELOPS_SECURITY_SCAN_BEHAVIOR_TEST_CHILD -ErrorAction SilentlyContinue
    } else {
        $env:SENTINELOPS_SECURITY_SCAN_BEHAVIOR_TEST_CHILD = $oldChildMarker
    }
    if (Test-Path -LiteralPath $testRoot -PathType Container) {
        $resolvedTestRoot = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $testRoot).Path)
        $safeTestParent = [System.IO.Path]::GetFullPath((Join-Path $repoRoot 'build/security-scan-tests'))
        $safeTestParentPrefix = $safeTestParent + [System.IO.Path]::DirectorySeparatorChar
        if (-not $resolvedTestRoot.StartsWith($safeTestParentPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove security test directory outside '$safeTestParent': $resolvedTestRoot"
        }
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
