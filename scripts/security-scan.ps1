[CmdletBinding()]
param(
    [string] $ExceptionFile = '.trivyignore.yaml',
    [string] $ReportDirectory = 'build/security'
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$script:failures = [System.Collections.Generic.List[string]]::new()
$repositoryRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$trivyImage = 'aquasec/trivy:0.74.0'
$script:securityImages = @(
    @{ name = 'ops-api'; dockerfile = 'apps/ops-api/Dockerfile'; tag = 'sentinelops/ops-api:stage2b-security' },
    @{ name = 'ops-executor'; dockerfile = 'apps/ops-executor/Dockerfile'; tag = 'sentinelops/ops-executor:stage2b-security' },
    @{ name = 'demo-service'; dockerfile = 'apps/demo-service/Dockerfile'; tag = 'sentinelops/demo-service:stage2b-security' },
    @{ name = 'ops-console'; dockerfile = 'web/ops-console/Dockerfile'; tag = 'sentinelops/ops-console:stage2b-security' }
)

if ([System.IO.Path]::IsPathRooted($ReportDirectory)) {
    $reportRoot = [System.IO.Path]::GetFullPath($ReportDirectory)
} else {
    $reportRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $ReportDirectory))
}
$reportRelative = [System.IO.Path]::GetRelativePath($repositoryRoot, $reportRoot)
$parentPrefix = '..' + [System.IO.Path]::DirectorySeparatorChar
if ($reportRelative -eq '.' -or $reportRelative -eq '..' -or $reportRelative.StartsWith($parentPrefix, [System.StringComparison]::Ordinal)) {
    throw 'ReportDirectory must be a child directory of the repository.'
}
$logPath = Join-Path $reportRoot 'security-scan.log'

function Write-ScanLog {
    param([Parameter(Mandatory)] [AllowEmptyString()] [string] $Message)

    $line = '{0:u} {1}' -f [DateTime]::UtcNow, $Message
    [Console]::WriteLine($line)
    if (Test-Path -LiteralPath $reportRoot) {
        Add-Content -LiteralPath $logPath -Value $line -Encoding utf8
    }
}

function Remove-StaleScanFile {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Label
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    $existing = Get-Item -LiteralPath $Path -Force
    if ($existing.PSIsContainer) {
        throw "Refusing to remove $Label output because the expected file path is a directory: $Path"
    }

    Remove-Item -LiteralPath $Path -Force
    Write-ScanLog "Removed previous-run $Label file before generating a fresh report: $Path"
}

function ConvertFrom-PolicyScalar {
    param(
        [Parameter(Mandatory)] [string] $Text,
        [Parameter(Mandatory)] [string] $Field
    )

    $value = $Text.Trim()
    if ($value.StartsWith('"')) {
        try {
            $decoded = ConvertFrom-Json -InputObject $value -ErrorAction Stop
        } catch {
            throw "Invalid quoted YAML scalar for '$Field'. Use a valid double-quoted string."
        }
        if ($decoded -isnot [string]) {
            throw "The '$Field' value must be a string."
        }
        return $decoded
    }

    if (($Field -eq 'id' -and $value -match '^CVE-[0-9]{4}-[0-9]{4,}$') -or
        ($Field -eq 'expired_at' -and $value -match '^[0-9]{4}-[0-9]{2}-[0-9]{2}$')) {
        return $value
    }

    throw "The '$Field' value must be double-quoted (except for CVE ids and ISO dates)."
}

function ConvertTo-AffectedTargets {
    param([Parameter(Mandatory)] [string] $Affected)

    $imageTags = @($script:securityImages | ForEach-Object { $_.tag })
    $affectedImages = [System.Collections.Generic.List[string]]::new()
    $affectedPaths = [System.Collections.Generic.List[string]]::new()
    foreach ($rawScope in $Affected.Split(';')) {
        $scope = $rawScope.Trim()
        if ($scope -match '^image:\s*(.+?)\s*$') {
            $imageTag = $Matches[1]
            if ($imageTags -notcontains $imageTag) {
                throw "Affected image target '$imageTag' is not a declared Trivy image target."
            }
            if ($affectedImages.Contains($imageTag)) {
                throw "Affected image target '$imageTag' is listed more than once."
            }
            $affectedImages.Add($imageTag)
            continue
        }

        if ($scope -match '^path:\s*(.+?)\s*$') {
            $candidate = $Matches[1].Replace('\', '/')
            if ([System.IO.Path]::IsPathRooted($candidate) -or $candidate -match '[:*?\[\]]' -or
                @($candidate.Split('/') | Where-Object { $_ -in @('', '.', '..') }).Count -gt 0) {
                throw "Affected filesystem path '$candidate' must be an exact repository-relative file path."
            }

            $fullPath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot ($candidate.Replace('/', [System.IO.Path]::DirectorySeparatorChar))))
            $relativePath = [System.IO.Path]::GetRelativePath($repositoryRoot, $fullPath)
            if ($relativePath -eq '.' -or $relativePath -eq '..' -or
                $relativePath.StartsWith('..' + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::Ordinal)) {
                throw "Affected filesystem path '$candidate' resolves outside the repository."
            }
            if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
                throw "Affected filesystem path '$candidate' does not identify a repository file."
            }
            if ($affectedPaths.Contains($candidate)) {
                throw "Affected filesystem path '$candidate' is listed more than once."
            }
            $affectedPaths.Add($candidate)
            continue
        }

        if ($scope -match '^component:\s*\S') {
            continue
        }

        throw "Affected scope '$scope' must use 'image:', 'path:', or 'component:'."
    }

    if ($affectedImages.Count -eq 0 -and $affectedPaths.Count -eq 0) {
        throw 'Affected must name at least one exact Trivy image target or filesystem file path.'
    }

    return [ordered]@{
        images = $affectedImages
        paths = $affectedPaths
    }
}

function Read-ValidatedExceptions {
    param([Parameter(Mandatory)] [string] $Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Exception policy file not found: $Path"
    }

    $lines = [System.IO.File]::ReadAllLines($Path)
    $exceptions = [System.Collections.Generic.List[object]]::new()
    $sectionSeen = $false
    $emptySection = $false
    $current = $null
    $seenFields = $null
    $arrayField = $null
    $lineNumber = 0

    foreach ($rawLine in $lines) {
        $lineNumber++
        $line = $rawLine.TrimEnd()
        if ([string]::IsNullOrWhiteSpace($line) -or $line -match '^\s*#') {
            continue
        }

        if ($line -match '^vulnerabilities:\s*(\[\])?\s*$') {
            if ($sectionSeen -or $null -ne $current) {
                throw "Duplicate or misplaced vulnerabilities section at line $lineNumber."
            }
            $sectionSeen = $true
            $emptySection = $Matches[1] -eq '[]'
            continue
        }

        if (-not $sectionSeen -or $emptySection) {
            throw "Unsupported exception policy YAML at line $lineNumber; only a vulnerabilities section is accepted."
        }

        if ($line -match '^  - id:\s*(.+?)\s*$') {
            if ($null -ne $current) {
                $exceptions.Add($current)
            }
            $id = ConvertFrom-PolicyScalar -Text $Matches[1] -Field 'id'
            $current = [ordered]@{
                id = $id
                purls = [System.Collections.Generic.List[string]]::new()
                affected = $null
                affected_images = [System.Collections.Generic.List[string]]::new()
                affected_paths = [System.Collections.Generic.List[string]]::new()
                expired_at = $null
                reason = $null
                compensating_control = $null
                owner = $null
            }
            $seenFields = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
            [void] $seenFields.Add('id')
            $arrayField = $null
            continue
        }

        if ($null -eq $current) {
            throw "Exception fields appeared before a CVE entry at line $lineNumber."
        }

        if ($line -match '^    purls:\s*$') {
            if (-not $seenFields.Add('purls')) {
                throw "Duplicate purls field in exception at line $lineNumber."
            }
            $arrayField = 'purls'
            continue
        }

        if ($line -match '^      -\s*(.+?)\s*$') {
            if ($arrayField -ne 'purls') {
                throw "Unexpected YAML list item at line $lineNumber."
            }
            $purl = ConvertFrom-PolicyScalar -Text $Matches[1] -Field 'purls'
            $exactPurlPattern = '^pkg:[A-Za-z0-9.+-]+/(?:[^/?#@\s]+/)*[^/?#@\s]+@[^/?#@\s]+(?:\?[^#\s]+)?(?:#[^\s]+)?$'
            if ($purl -notmatch $exactPurlPattern -or $purl.Contains('*')) {
                throw "Exception PURL '$purl' at line $lineNumber must identify one exact package version."
            }
            $current.purls.Add($purl)
            continue
        }

        if ($line -match '^    (affected|expired_at|reason|compensating_control|owner):\s*(.+?)\s*$') {
            $field = $Matches[1]
            if (-not $seenFields.Add($field)) {
                throw "Duplicate '$field' field in exception at line $lineNumber."
            }
            $current[$field] = ConvertFrom-PolicyScalar -Text $Matches[2] -Field $field
            $arrayField = $null
            continue
        }

        throw "Unsupported exception policy YAML at line $lineNumber."
    }

    if (-not $sectionSeen) {
        throw "Exception policy must contain a top-level 'vulnerabilities' section."
    }
    if ($null -ne $current) {
        $exceptions.Add($current)
    }

    $today = [DateTime]::UtcNow.Date
    $latestExpiry = $today.AddDays(90)
    $seenScope = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($exception in $exceptions) {
        foreach ($requiredField in @('purls', 'affected', 'expired_at', 'reason', 'compensating_control', 'owner')) {
            $fieldValue = $exception[$requiredField]
            if (($requiredField -eq 'purls' -and $fieldValue.Count -eq 0) -or
                ($requiredField -ne 'purls' -and [string]::IsNullOrWhiteSpace([string]$fieldValue))) {
                throw "Exception $($exception.id) is missing required field '$requiredField'."
            }
        }

        foreach ($textField in @('affected', 'reason', 'compensating_control', 'owner')) {
            $value = [string]$exception[$textField]
            if ([string]::IsNullOrWhiteSpace($value) -or $value -match '^(tbd|todo|n/?a|unknown)$') {
                throw "Exception $($exception.id) has an empty or placeholder '$textField' value."
            }
        }

        $affectedTargets = ConvertTo-AffectedTargets -Affected ([string]$exception.affected)
        $exception.affected_images = $affectedTargets.images
        $exception.affected_paths = $affectedTargets.paths

        $expiry = [DateTime]::MinValue
        $parsed = [DateTime]::TryParseExact(
            [string]$exception.expired_at,
            'yyyy-MM-dd',
            [System.Globalization.CultureInfo]::InvariantCulture,
            [System.Globalization.DateTimeStyles]::None,
            [ref]$expiry
        )
        if (-not $parsed) {
            throw "Exception $($exception.id) has an invalid expired_at date; use YYYY-MM-DD."
        }
        if ($expiry.Date -le $today) {
            throw "Exception $($exception.id) is expired as of $($today.ToString('yyyy-MM-dd'))."
        }
        if ($expiry.Date -gt $latestExpiry) {
            throw "Exception $($exception.id) expires beyond the 90-day policy window."
        }

        foreach ($purl in $exception.purls) {
            $scope = "$($exception.id)|$purl"
            if (-not $seenScope.Add($scope)) {
                throw "Duplicate exception scope for $($exception.id) and $purl."
            }
        }
    }

    return ,$exceptions
}

function Write-TrivyIgnoreFile {
    param(
        [Parameter(Mandatory)] [System.Collections.IList] $Exceptions,
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [ValidateSet('filesystem', 'image')] [string] $ScanType,
        [string] $ImageTag
    )

    if ($ScanType -eq 'image' -and [string]::IsNullOrWhiteSpace($ImageTag)) {
        throw 'ImageTag is required when generating an image-specific Trivy ignore file.'
    }

    $matchingExceptions = [System.Collections.Generic.List[object]]::new()
    foreach ($exception in $Exceptions) {
        if (($ScanType -eq 'filesystem' -and $exception.affected_paths.Count -gt 0) -or
            ($ScanType -eq 'image' -and $exception.affected_images.Contains($ImageTag))) {
            $matchingExceptions.Add($exception)
        }
    }

    $yaml = [System.Collections.Generic.List[string]]::new()
    if ($matchingExceptions.Count -eq 0) {
        $yaml.Add('vulnerabilities: []')
    } else {
        $yaml.Add('vulnerabilities:')
        foreach ($exception in $matchingExceptions) {
            $yaml.Add("  - id: $($exception.id)")
            if ($ScanType -eq 'filesystem') {
                $yaml.Add('    paths:')
                foreach ($affectedPath in $exception.affected_paths) {
                    $yaml.Add("      - $(ConvertTo-Json -InputObject $affectedPath -Compress)")
                }
            }
            $yaml.Add('    purls:')
            foreach ($purl in $exception.purls) {
                $yaml.Add("      - $(ConvertTo-Json -InputObject $purl -Compress)")
            }
            $yaml.Add("    expired_at: $($exception.expired_at)")
            $statement = "Affected artifact/image: $($exception.affected) Reason: $($exception.reason) Compensating control: $($exception.compensating_control) Owner: $($exception.owner)"
            $yaml.Add("    statement: $(ConvertTo-Json -InputObject $statement -Compress)")
        }
    }
    [System.IO.File]::WriteAllLines($Path, $yaml, [System.Text.UTF8Encoding]::new($false))
}

function Invoke-LoggedCommand {
    param(
        [Parameter(Mandatory)] [string] $Label,
        [Parameter(Mandatory)] [string] $Executable,
        [Parameter(Mandatory)] [string[]] $Arguments,
        [string] $WorkingDirectory = $repositoryRoot,
        [string] $OutputFile
    )

    Write-ScanLog "RUN $Label :: $Executable $($Arguments -join ' ')"
    $previousPreference = $ErrorActionPreference
    $previousLocation = Get-Location
    try {
        $ErrorActionPreference = 'Continue'
        Set-Location -LiteralPath $WorkingDirectory
        $output = @(& $Executable @Arguments 2>&1 | ForEach-Object { [string]$_ })
        $exitCode = $LASTEXITCODE
        if ($null -eq $exitCode) {
            $exitCode = 0
        }
    } catch {
        $output = @($_.Exception.Message)
        $exitCode = 127
    } finally {
        Set-Location -LiteralPath $previousLocation.Path
        $ErrorActionPreference = $previousPreference
    }

    foreach ($item in $output) {
        Write-ScanLog ([string]$item)
    }
    if ($OutputFile) {
        $rawOutput = [string]::Join([Environment]::NewLine, [string[]]$output)
        [System.IO.File]::WriteAllText($OutputFile, $rawOutput, [System.Text.UTF8Encoding]::new($false))
    }
    Write-ScanLog "EXIT $Label :: $exitCode"
    if ($exitCode -ne 0) {
        $script:failures.Add("$Label exited with code $exitCode")
    }
    return [int]$exitCode
}

function Confirm-JsonReport {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [ValidateSet('trivy', 'cyclonedx', 'npm-audit', 'dependency-check')] [string] $Kind
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        $script:failures.Add("Required $Kind report was not created: $Path")
        Write-ScanLog "ERROR Missing $Kind report: $Path"
        return $null
    }

    try {
        $document = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -ErrorAction Stop
        $properties = @($document.PSObject.Properties.Name)
        switch ($Kind) {
            'trivy' {
                if ($document.SchemaVersion -lt 2 -or $properties -notcontains 'Results') {
                    throw 'Trivy JSON is missing SchemaVersion 2 or Results.'
                }
                $resultCount = @($document.Results).Count
                Write-ScanLog "REPORT $Path is valid Trivy JSON (SchemaVersion $($document.SchemaVersion), $resultCount result record(s))."
            }
            'cyclonedx' {
                if ($document.bomFormat -ne 'CycloneDX' -or $document.specVersion -notmatch '^1\.[56]$' -or $properties -notcontains 'components') {
                    throw 'CycloneDX JSON is missing the expected bomFormat, specVersion, or components.'
                }
                Write-ScanLog "REPORT $Path is valid CycloneDX $($document.specVersion) JSON with $(@($document.components).Count) component(s)."
            }
            'npm-audit' {
                if ($null -eq $document.auditReportVersion -or $properties -notcontains 'vulnerabilities') {
                    throw 'npm audit JSON is missing auditReportVersion or vulnerabilities.'
                }
                Write-ScanLog "REPORT $Path is valid npm audit JSON (auditReportVersion $($document.auditReportVersion))."
            }
            'dependency-check' {
                if ($properties -notcontains 'dependencies') {
                    throw 'OWASP Dependency-Check JSON is missing dependencies.'
                }
                $dependencyCount = @($document.dependencies).Count
                if ($dependencyCount -eq 0) {
                    throw 'OWASP Dependency-Check JSON must contain at least one dependency record.'
                }
                $vulnerableDependencyCount = 0
                $uniqueCves = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
                foreach ($dependency in @($document.dependencies)) {
                    $dependencyHasVulnerability = $false
                    foreach ($vulnerability in @($dependency.vulnerabilities)) {
                        if ($null -eq $vulnerability) {
                            continue
                        }
                        $dependencyHasVulnerability = $true
                        if (-not [string]::IsNullOrWhiteSpace([string]$vulnerability.name)) {
                            [void] $uniqueCves.Add([string]$vulnerability.name)
                        }
                    }
                    if ($dependencyHasVulnerability) {
                        $vulnerableDependencyCount++
                    }
                }
                Write-ScanLog "REPORT $Path is valid Dependency-Check JSON with $dependencyCount dependency record(s), $vulnerableDependencyCount vulnerable dependency record(s), and $($uniqueCves.Count) unique CVE(s); CVSS 7.0 remains the Maven failure threshold."
            }
        }
        return $document
    } catch {
        $script:failures.Add("Invalid $Kind report at ${Path}: $($_.Exception.Message)")
        Write-ScanLog "ERROR Invalid $Kind JSON: $Path :: $($_.Exception.Message)"
        return $null
    }
}

function Confirm-AndCopyBom {
    param(
        [Parameter(Mandatory)] [string] $SourcePath,
        [Parameter(Mandatory)] [string] $DestinationPath,
        [Parameter(Mandatory)] [string] $Label
    )

    $document = Confirm-JsonReport -Path $SourcePath -Kind 'cyclonedx'
    if ($null -ne $document) {
        Copy-Item -LiteralPath $SourcePath -Destination $DestinationPath -Force
        Write-ScanLog "$Label copied to $DestinationPath."
    }
}

function Write-JavaTestSummary {
    param([DateTime] $StartedAtUtc = [DateTime]::MinValue)

    $reportFiles = [System.Collections.Generic.List[string]]::new()
    foreach ($module in Get-ChildItem -LiteralPath (Join-Path $repositoryRoot 'apps') -Directory) {
        foreach ($reportDirectoryName in @('surefire-reports', 'failsafe-reports')) {
            $reportDirectory = Join-Path $module.FullName "target/$reportDirectoryName"
            if (Test-Path -LiteralPath $reportDirectory -PathType Container) {
                foreach ($reportFile in Get-ChildItem -LiteralPath $reportDirectory -Filter 'TEST-*.xml' -File) {
                    if ($reportFile.LastWriteTimeUtc -ge $StartedAtUtc) {
                        $reportFiles.Add($reportFile.FullName)
                    }
                }
            }
        }
    }

    $totalTests = 0
    $totalFailures = 0
    $totalErrors = 0
    $totalSkipped = 0
    foreach ($reportFile in $reportFiles) {
        try {
            [xml] $testReport = Get-Content -LiteralPath $reportFile -Raw
            foreach ($suite in @($testReport.testsuite) + @($testReport.testsuites.testsuite)) {
                if ($null -eq $suite) {
                    continue
                }
                $totalTests += [int]$suite.tests
                $totalFailures += [int]$suite.failures
                $totalErrors += [int]$suite.errors
                $totalSkipped += [int]$suite.skipped
            }
        } catch {
            $script:failures.Add("Cannot parse Java test report ${reportFile}: $($_.Exception.Message)")
        }
    }

    Write-ScanLog "Java test report summary: $totalTests test(s), $totalFailures failure(s), $totalErrors error(s), $totalSkipped skipped across $($reportFiles.Count) report file(s)."
    if ($totalTests -eq 0) {
        $script:failures.Add('Java verify produced no Surefire or Failsafe test results; the Java test gate did not run.')
    }
}

function Get-TrivyFilesystemSkipDirectories {
    return @(
        '/workspace/.git',
        '/workspace/.toolchains',
        '/workspace/.worktrees',
        '/workspace/build',
        '/workspace/target',
        '/workspace/web/ops-console/node_modules',
        '/workspace/web/ops-console/build',
        '/workspace/web/ops-console/dist'
    )
}

function Get-TrivyScannerArguments {
    param(
        [Parameter(Mandatory)] [ValidateSet('filesystem', 'image')] [string] $TargetType,
        [Parameter(Mandatory)] [ValidateSet('vulnerability', 'secret')] [string] $ScanType
    )

    if ($ScanType -eq 'secret') {
        $arguments = @('--timeout', '15m', '--scanners', 'secret', '--exit-code', '1')
        if ($TargetType -eq 'filesystem') {
            $arguments += '--offline-scan'
        }
        return $arguments
    }

    $scanners = if ($TargetType -eq 'filesystem') { 'vuln,misconfig' } else { 'vuln' }
    $arguments = @('--timeout', '15m', '--scanners', $scanners, '--severity', 'HIGH,CRITICAL', '--exit-code', '1')
    if ($TargetType -eq 'filesystem') {
        $arguments += '--offline-scan'
    }
    return $arguments
}

function Get-TrivyMavenPurlIdentity {
    param([Parameter(Mandatory)] [string] $Purl)

    $match = [regex]::Match($Purl, '^(?<identity>pkg:maven/[^?#]+?@[^/?#]+)(?:[?#].*)?$')
    if (-not $match.Success) {
        throw "Invalid Maven package URL in Java SBOM or Trivy report: $Purl"
    }
    return $match.Groups['identity'].Value
}

function Get-TrivyRequiredMavenPurls {
    param([Parameter(Mandatory)] [string] $JavaBomPath)

    if (-not (Test-Path -LiteralPath $JavaBomPath -PathType Leaf)) {
        throw "Java CycloneDX SBOM is missing; cannot verify Trivy Maven dependency coverage: $JavaBomPath"
    }
    try {
        $bom = Get-Content -LiteralPath $JavaBomPath -Raw | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "Java CycloneDX SBOM is invalid JSON: $JavaBomPath. $($_.Exception.Message)"
    }
    if ($bom.bomFormat -ne 'CycloneDX' -or $bom.specVersion -notmatch '^1\.[56]$') {
        throw "Java SBOM is not valid CycloneDX 1.5 or 1.6: $JavaBomPath"
    }

    $requiredNames = @('spring-boot-starter-webmvc', 'spring-web', 'postgresql', 'tomcat-embed-core')
    $requiredPurls = [System.Collections.Generic.List[string]]::new()
    foreach ($name in $requiredNames) {
        $component = @($bom.components | Where-Object {
            $_.name -eq $name -and $_.purl -is [string] -and $_.purl.StartsWith('pkg:maven/', [System.StringComparison]::Ordinal)
        } | Select-Object -First 1)
        if ($component.Count -ne 1) {
            throw "Java CycloneDX SBOM is missing required Maven dependency '$name'."
        }
        $purl = [string]$component[0].purl
        $null = Get-TrivyMavenPurlIdentity -Purl $purl
        $requiredPurls.Add($purl)
    }
    return $requiredPurls.ToArray()
}

function Get-TrivyMavenRepositoryMount {
    param(
        [Parameter(Mandatory)] [string] $HostMavenRepository,
        [Parameter(Mandatory)] [string[]] $RequiredPurls
    )

    $resolvedRepository = [System.IO.Path]::GetFullPath($HostMavenRepository)
    if (-not (Test-Path -LiteralPath $resolvedRepository -PathType Container)) {
        throw "Maven cache directory does not exist: $resolvedRepository"
    }
    if ($resolvedRepository.Contains(',')) {
        throw "Maven cache path contains a comma and cannot be safely passed to Docker --mount: $resolvedRepository"
    }

    foreach ($purl in $RequiredPurls) {
        $identity = Get-TrivyMavenPurlIdentity -Purl $purl
        $match = [regex]::Match($identity, '^pkg:maven/(?<group>[^/]+)/(?<name>[^@]+)@(?<version>[^/?#]+)$')
        if (-not $match.Success) {
            throw "Maven package URL cannot be mapped to a local cache POM: $purl"
        }
        $group = [Uri]::UnescapeDataString($match.Groups['group'].Value)
        $name = [Uri]::UnescapeDataString($match.Groups['name'].Value)
        $version = [Uri]::UnescapeDataString($match.Groups['version'].Value)
        if ($group -notmatch '^[A-Za-z0-9_.-]+$' -or $name -notmatch '^[A-Za-z0-9_.+-]+$' -or $version -notmatch '^[A-Za-z0-9_.+-]+$') {
            throw "Maven package URL has unsafe coordinate characters: $purl"
        }

        $groupPath = $group.Replace('.', [System.IO.Path]::DirectorySeparatorChar)
        $pomPath = Join-Path (Join-Path (Join-Path (Join-Path $resolvedRepository $groupPath) $name) $version) "$name-$version.pom"
        if (-not (Test-Path -LiteralPath $pomPath -PathType Leaf)) {
            throw "Maven cache is missing required Java dependency POM '$purl' at '$pomPath'."
        }
    }

    return "type=bind,source=$resolvedRepository,target=/root/.m2/repository,readonly"
}

function Confirm-TrivyMavenCoverage {
    param(
        [Parameter(Mandatory)] [string] $JavaBomPath,
        [Parameter(Mandatory)] [string] $TrivyReportPath
    )

    $requiredPurls = @(Get-TrivyRequiredMavenPurls -JavaBomPath $JavaBomPath)
    if (-not (Test-Path -LiteralPath $TrivyReportPath -PathType Leaf)) {
        throw "Trivy filesystem JSON is missing; cannot verify Maven dependency coverage: $TrivyReportPath"
    }
    try {
        $report = Get-Content -LiteralPath $TrivyReportPath -Raw | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "Trivy filesystem report is invalid JSON: $TrivyReportPath. $($_.Exception.Message)"
    }

    $reportedPurls = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($package in @($report.Results | ForEach-Object { $_.Packages } | Where-Object { $null -ne $_ })) {
        $purl = [string]$package.Identifier.PURL
        if (-not [string]::IsNullOrWhiteSpace($purl)) {
            if (-not $purl.StartsWith('pkg:maven/', [System.StringComparison]::Ordinal)) {
                continue
            }
            $identity = Get-TrivyMavenPurlIdentity -Purl $purl
            $null = $reportedPurls.Add($identity)
        }
    }

    foreach ($expectedPurl in $requiredPurls) {
        $identity = Get-TrivyMavenPurlIdentity -Purl $expectedPurl
        if (-not $reportedPurls.Contains($identity)) {
            throw "Trivy filesystem report is missing key Java dependency '$identity'; the offline Maven cache may be incomplete."
        }
        Write-ScanLog "Trivy filesystem report contains key Java dependency $identity."
    }
}

function Initialize-Java21 {
    $javaExecutableName = if ($IsWindows) { 'java.exe' } else { 'java' }
    $javaHome = $env:JAVA_HOME
    $javaExecutable = if ($javaHome) { Join-Path $javaHome "bin/$javaExecutableName" } else { $null }
    $versionText = ''

    if ($javaExecutable -and (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
        $versionOutput = @(& $javaExecutable -version 2>&1 | ForEach-Object { [string]$_ })
        $versionText = [string]::Join(' ', [string[]]$versionOutput)
    }

    if ($versionText -notmatch '(?i)(?:version\s+")?21\.') {
        $toolchainRoot = Join-Path $repositoryRoot '.toolchains/jdk-21'
        foreach ($localJdk in Get-ChildItem -LiteralPath $toolchainRoot -Directory -ErrorAction SilentlyContinue) {
            $candidateJava = Join-Path $localJdk.FullName "bin/$javaExecutableName"
            if (-not (Test-Path -LiteralPath $candidateJava -PathType Leaf)) {
                continue
            }
            $candidateVersion = @(& $candidateJava -version 2>&1 | ForEach-Object { [string]$_ })
            $candidateVersionText = [string]::Join(' ', [string[]]$candidateVersion)
            if ($candidateVersionText -match '(?i)(?:version\s+")?21\.') {
                $javaHome = $localJdk.FullName
                $javaExecutable = $candidateJava
                $versionText = $candidateVersionText
                break
            }
        }

        if ($versionText -notmatch '(?i)(?:version\s+")?21\.') {
            $pathJava = Get-Command $javaExecutableName -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($null -ne $pathJava) {
                $javaExecutable = $pathJava.Source
                $versionOutput = @(& $javaExecutable -version 2>&1 | ForEach-Object { [string]$_ })
                $versionText = [string]::Join(' ', [string[]]$versionOutput)
                if ($versionText -match '(?i)(?:version\s+")?21\.') {
                    $javaHome = Split-Path -Parent (Split-Path -Parent $javaExecutable)
                }
            }
        }

        if ($versionText -notmatch '(?i)(?:version\s+")?21\.') {
            $script:failures.Add('Java 21 is required, but JAVA_HOME, .toolchains/jdk-21/, and PATH contain no Java 21 runtime.')
            Write-ScanLog 'ERROR Java 21 is required; Maven tests, OWASP audit and Java SBOM were not run.'
            return $false
        }
    }

    if ($versionText -notmatch '(?i)(?:version\s+")?21\.') {
        $script:failures.Add("Resolved JDK is not Java 21: $versionText")
        Write-ScanLog "ERROR Resolved JDK is not Java 21: $versionText"
        return $false
    }

    $env:JAVA_HOME = $javaHome
    $env:PATH = "$(Join-Path $javaHome 'bin')$([System.IO.Path]::PathSeparator)$env:PATH"
    Write-ScanLog "Using Java 21 from $javaHome :: $versionText"
    return $true
}

function Get-MavenInvocation {
    param(
        [Parameter(Mandatory)] [string] $WrapperPath,
        [Parameter(Mandatory)] [string[]] $Arguments,
        [bool] $OnWindows = $IsWindows
    )

    if ($OnWindows) {
        return [pscustomobject]@{
            Executable = $WrapperPath
            Arguments = @($Arguments)
        }
    }

    $bashCommand = Get-Command bash -ErrorAction Stop | Select-Object -First 1
    $bashExecutable = if ($bashCommand.Source) { $bashCommand.Source } else { $bashCommand.Path }
    if ([string]::IsNullOrWhiteSpace($bashExecutable)) {
        throw 'bash is required to run the non-executable Maven wrapper on non-Windows systems.'
    }

    return [pscustomobject]@{
        Executable = $bashExecutable
        Arguments = @($WrapperPath) + @($Arguments)
    }
}

function Get-DependencyCheckDataSourceArguments {
    $apiKey = [System.Environment]::GetEnvironmentVariable('NVD_API_KEY')
    if ([string]::IsNullOrWhiteSpace($apiKey)) {
        return '-DnvdDatafeedUrl=https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz'
    }

    return '-DnvdApiKeyEnvironmentVariable=NVD_API_KEY'
}

function Get-DependencyCheckArguments {
    param(
        [Parameter(Mandatory)] [string] $DataDirectory,
        [Parameter(Mandatory)] [string] $SuppressionFile
    )

    return @(
        "-DdataDirectory=$DataDirectory",
        '-DretireJsAnalyzerEnabled=false',
        "-DsuppressionFiles=$SuppressionFile",
        '-DfailBuildOnUnusedSuppressionRule=true'
    ) + @(Get-DependencyCheckDataSourceArguments)
}

function Get-MavenPurlCoordinates {
    param([Parameter(Mandatory)] [string] $Purl)

    if ($Purl.Contains('*')) {
        throw "Wildcard Maven PURLs are not allowed in OWASP exceptions: $Purl"
    }
    $identity = Get-TrivyMavenPurlIdentity -Purl $Purl
    $match = [regex]::Match($identity, '^pkg:maven/(?<group>[^/]+)/(?<artifact>[^@]+)@(?<version>[^@]+)$')
    if (-not $match.Success) {
        throw "OWASP exception requires an exact Maven PURL with a nonempty version: $Purl"
    }
    return [pscustomobject]@{
        group = [Uri]::UnescapeDataString($match.Groups['group'].Value)
        artifact = [Uri]::UnescapeDataString($match.Groups['artifact'].Value)
        version = [Uri]::UnescapeDataString($match.Groups['version'].Value)
    }
}

function Read-ValidatedDependencyCheckExceptions {
    param([Parameter(Mandatory)] [string] $Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "OWASP Dependency-Check exception policy not found: $Path"
    }
    try {
        $document = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "OWASP Dependency-Check exception policy is invalid JSON: $Path. $($_.Exception.Message)"
    }
    if ($document.PSObject.Properties.Name -notcontains 'exceptions') {
        throw "OWASP Dependency-Check exception policy must contain an 'exceptions' array: $Path"
    }

    $today = [DateTime]::UtcNow.Date
    $latestExpiry = $today.AddDays(90)
    $exceptions = [System.Collections.Generic.List[object]]::new()
    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in @($document.exceptions)) {
        foreach ($field in @('cve', 'purl', 'test_dependency_purl', 'scope', 'affected', 'expires_at', 'reason', 'compensating_control', 'owner')) {
            $fieldValue = [string]$entry.$field
            if ([string]::IsNullOrWhiteSpace($fieldValue) -or $fieldValue -match '^(?i:tbd|todo|n/?a|unknown)$') {
                throw "OWASP exception is missing required field '$field' or uses a placeholder."
            }
        }
        if ([string]$entry.cve -notmatch '^CVE-[0-9]{4}-[0-9]{4,}$') {
            throw "OWASP exception CVE id is invalid: $($entry.cve)"
        }
        if ([string]$entry.scope -ne 'test') {
            throw "OWASP exception $($entry.cve) must be explicitly marked scope 'test'."
        }

        $null = Get-MavenPurlCoordinates -Purl ([string]$entry.purl)
        $null = Get-MavenPurlCoordinates -Purl ([string]$entry.test_dependency_purl)
        $expiry = [DateTime]::MinValue
        $parsedExpiry = [DateTime]::TryParseExact(
            [string]$entry.expires_at,
            'yyyy-MM-dd',
            [System.Globalization.CultureInfo]::InvariantCulture,
            [System.Globalization.DateTimeStyles]::None,
            [ref]$expiry
        )
        if (-not $parsedExpiry -or $expiry.Date -le $today) {
            throw "OWASP exception $($entry.cve) has an invalid or expired expires_at date; use a future YYYY-MM-DD date."
        }
        if ($expiry.Date -gt $latestExpiry) {
            throw "OWASP exception $($entry.cve) expires beyond the 90-day policy window."
        }

        $key = "$($entry.cve)|$($entry.purl)"
        if (-not $seen.Add($key)) {
            throw "Duplicate OWASP exception scope for $($entry.cve) and $($entry.purl)."
        }
        $exceptions.Add([pscustomobject]@{
            cve = [string]$entry.cve
            purl = [string]$entry.purl
            test_dependency_purl = [string]$entry.test_dependency_purl
            scope = [string]$entry.scope
            affected = [string]$entry.affected
            expires_at = [string]$entry.expires_at
            reason = [string]$entry.reason
            compensating_control = [string]$entry.compensating_control
            owner = [string]$entry.owner
        })
    }
    return ,$exceptions
}

function Get-MavenDependencyTreeEntries {
    param([Parameter(Mandatory)] [string] $Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required Maven dependency tree was not created: $Path"
    }
    try {
        $root = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "Maven dependency tree is invalid JSON: $Path. $($_.Exception.Message)"
    }
    foreach ($field in @('groupId', 'artifactId', 'version')) {
        if ([string]::IsNullOrWhiteSpace([string]$root.$field)) {
            throw "Maven dependency tree is missing root '$field': $Path"
        }
    }

    $entries = [System.Collections.Generic.List[object]]::new()
    $pending = [System.Collections.Generic.Stack[object]]::new()
    $pending.Push($root)
    while ($pending.Count -gt 0) {
        $node = $pending.Pop()
        if (-not [string]::IsNullOrWhiteSpace([string]$node.groupId) -and
            -not [string]::IsNullOrWhiteSpace([string]$node.artifactId) -and
            -not [string]::IsNullOrWhiteSpace([string]$node.version)) {
            $entries.Add([pscustomobject]@{
                group = [string]$node.groupId
                artifact = [string]$node.artifactId
                version = [string]$node.version
                scope = [string]$node.scope
            })
        }
        foreach ($child in @($node.children)) {
            if ($null -ne $child) {
                $pending.Push($child)
            }
        }
    }
    return $entries.ToArray()
}

function Test-DependencyCheckExceptionScope {
    param(
        [Parameter(Mandatory)] [System.Collections.IList] $Exceptions,
        [Parameter(Mandatory)] [System.Collections.IList] $Entries
    )

    foreach ($exception in $Exceptions) {
        $containerCoordinates = Get-MavenPurlCoordinates -Purl $exception.test_dependency_purl
        $containerMatches = @($Entries | Where-Object {
            $_.group -eq $containerCoordinates.group -and
            $_.artifact -eq $containerCoordinates.artifact -and
            $_.version -eq $containerCoordinates.version
        })
        if ($containerMatches.Count -eq 0 -or @($containerMatches | Where-Object { $_.scope -ne 'test' }).Count -gt 0) {
            throw "OWASP exception $($exception.cve) requires $($exception.test_dependency_purl) to be present only in test scope; it is absent from test scope or present in another scope."
        }

        $vulnerableCoordinates = Get-MavenPurlCoordinates -Purl $exception.purl
        $vulnerableMatches = @($Entries | Where-Object {
            $_.group -eq $vulnerableCoordinates.group -and
            $_.artifact -eq $vulnerableCoordinates.artifact -and
            $_.version -eq $vulnerableCoordinates.version
        })
        if (@($vulnerableMatches | Where-Object { $_.scope -ne 'test' }).Count -gt 0) {
            throw "OWASP exception $($exception.cve) cannot suppress $($exception.purl) because it appears outside test scope."
        }
    }

    return $true
}

function Write-DependencyCheckSuppressionFile {
    param(
        [Parameter(Mandatory)] [System.Collections.IList] $Exceptions,
        [Parameter(Mandatory)] [string] $Path
    )

    $namespace = 'https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.4.xsd'
    $document = [System.Xml.XmlDocument]::new()
    $document.LoadXml("<suppressions xmlns=`"$namespace`" xmlns:xsi=`"http://www.w3.org/2001/XMLSchema-instance`" xsi:schemaLocation=`"$namespace $namespace`"></suppressions>")
    $root = $document.DocumentElement
    foreach ($exception in $Exceptions) {
        $suppression = $document.CreateElement('suppress', $namespace)
        $suppression.SetAttribute('until', "$($exception.expires_at)Z")

        $notes = $document.CreateElement('notes', $namespace)
        $notes.InnerText = @(
            "owner: $($exception.owner)",
            "scope: test via $($exception.test_dependency_purl)",
            "affected: $($exception.affected)",
            "reason: $($exception.reason)",
            "compensating control: $($exception.compensating_control)"
        ) -join [Environment]::NewLine
        [void] $suppression.AppendChild($notes)

        $packageUrl = $document.CreateElement('packageUrl', $namespace)
        $packageUrl.SetAttribute('regex', 'false')
        $packageUrl.InnerText = $exception.purl
        [void] $suppression.AppendChild($packageUrl)

        $cve = $document.CreateElement('cve', $namespace)
        $cve.InnerText = $exception.cve
        [void] $suppression.AppendChild($cve)
        [void] $root.AppendChild($suppression)
    }

    $settings = [System.Xml.XmlWriterSettings]::new()
    $settings.Encoding = [System.Text.UTF8Encoding]::new($false)
    $settings.Indent = $true
    $writer = [System.Xml.XmlWriter]::Create($Path, $settings)
    try {
        $document.Save($writer)
    } finally {
        $writer.Dispose()
    }
}

function Remove-IsolatedNodeWorkspace {
    param(
        [Parameter(Mandatory)] [string] $WorkspacePath,
        [Parameter(Mandatory)] [string] $ReportRoot,
        [scriptblock] $RemoveAction
    )

    if (-not (Test-Path -LiteralPath $WorkspacePath)) {
        return $true
    }

    $resolvedWorkspace = [System.IO.Path]::GetFullPath($WorkspacePath)
    $resolvedReportRoot = [System.IO.Path]::GetFullPath($ReportRoot)
    $safePrefix = $resolvedReportRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolvedWorkspace.StartsWith($safePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        $script:failures.Add("Refusing to remove Node workspace outside report directory: $resolvedWorkspace")
        Write-ScanLog "ERROR Refusing to remove Node workspace outside report directory: $resolvedWorkspace"
        return $false
    }

    if ($null -eq $RemoveAction) {
        $RemoveAction = {
            param($Path)
            [System.IO.Directory]::Delete($Path, $true)
        }
    }

    $lastRemovalError = $null
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        try {
            $null = & $RemoveAction $resolvedWorkspace
            if (Test-Path -LiteralPath $resolvedWorkspace) {
                throw 'Directory still exists after removal.'
            }
            Write-ScanLog 'Removed the isolated Node workspace; npm audit and SBOM JSON reports were retained.'
            return $true
        } catch {
            $lastRemovalError = $_.Exception.Message
            if ($attempt -lt 4) {
                Start-Sleep -Milliseconds (200 * [math]::Pow(2, $attempt - 1))
            }
        }
    }

    $message = "Failed to remove isolated Node workspace '$resolvedWorkspace' after 4 attempts: $lastRemovalError"
    $script:failures.Add($message)
    Write-ScanLog "ERROR $message"
    return $false
}

function Invoke-TrivyScan {
    param(
        [Parameter(Mandatory)] [string] $Label,
        [Parameter(Mandatory)] [string[]] $Arguments,
        [Parameter(Mandatory)] [string] $ReportPath
    )

    Remove-StaleScanFile -Path $ReportPath -Label $Label
    $null = Invoke-LoggedCommand -Label $Label -Executable 'docker' -Arguments $Arguments
    $null = Confirm-JsonReport -Path $ReportPath -Kind 'trivy'
}

try {
    New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
    Write-ScanLog "Security pipeline started. Trivy image is pinned to $trivyImage."

    if ([System.IO.Path]::IsPathRooted($ExceptionFile)) {
        $exceptionPath = [System.IO.Path]::GetFullPath($ExceptionFile)
    } else {
        $exceptionPath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $ExceptionFile))
    }

    $exceptions = Read-ValidatedExceptions -Path $exceptionPath
    $dependencyCheckExceptionPolicyPath = Join-Path $repositoryRoot 'docs/security/dependency-check-exceptions.json'
    $dependencyCheckExceptions = Read-ValidatedDependencyCheckExceptions -Path $dependencyCheckExceptionPolicyPath
    $filesystemIgnorePath = Join-Path $reportRoot 'trivyignore-filesystem.generated.yaml'
    Write-TrivyIgnoreFile -Exceptions $exceptions -Path $filesystemIgnorePath -ScanType filesystem
    $imageIgnorePaths = @{}
    foreach ($image in $script:securityImages) {
        $imageIgnorePath = Join-Path $reportRoot "trivyignore-image-$($image.name).generated.yaml"
        Write-TrivyIgnoreFile -Exceptions $exceptions -Path $imageIgnorePath -ScanType image -ImageTag $image.tag
        $imageIgnorePaths[$image.tag] = $imageIgnorePath
    }
    Write-ScanLog "Validated $($exceptions.Count) time-bounded vulnerability exception(s) before tests or scanners."
    Write-ScanLog "Validated $($dependencyCheckExceptions.Count) exact, time-bounded test-scope OWASP exception(s) before tests or scanners."

    if ($env:SENTINELOPS_SECURITY_SCAN_BEHAVIOR_TEST_CHILD -ne '1') {
        $behaviorTestScript = Join-Path $repositoryRoot 'tests/security-scan/security-scan-behavior.ps1'
        if (-not (Test-Path -LiteralPath $behaviorTestScript -PathType Leaf)) {
            throw "Required security scan behavior test is missing: $behaviorTestScript"
        }
        $pwshCommand = Get-Command pwsh -ErrorAction SilentlyContinue
        if ($null -eq $pwshCommand) {
            throw 'PowerShell 7 (pwsh) is required to run the security scan behavior tests.'
        }
        $previousTestChildMarker = $env:SENTINELOPS_SECURITY_SCAN_BEHAVIOR_TEST_CHILD
        try {
            $env:SENTINELOPS_SECURITY_SCAN_BEHAVIOR_TEST_CHILD = '1'
            $behaviorTestStatus = Invoke-LoggedCommand -Label 'Security scan behavior tests' -Executable $pwshCommand.Source -Arguments @('-NoProfile', '-File', $behaviorTestScript)
        } finally {
            if ($null -eq $previousTestChildMarker) {
                Remove-Item Env:SENTINELOPS_SECURITY_SCAN_BEHAVIOR_TEST_CHILD -ErrorAction SilentlyContinue
            } else {
                $env:SENTINELOPS_SECURITY_SCAN_BEHAVIOR_TEST_CHILD = $previousTestChildMarker
            }
        }
        if ($behaviorTestStatus -ne 0) {
            throw "Required security scan behavior tests failed with exit code $behaviorTestStatus; remaining gates were not started."
        }
    }

    $java21Ready = Initialize-Java21
    $mavenWrapper = if ($IsWindows) { Join-Path $repositoryRoot 'mvnw.cmd' } else { Join-Path $repositoryRoot 'mvnw' }
    if (-not (Test-Path -LiteralPath $mavenWrapper -PathType Leaf)) {
        $script:failures.Add("Maven wrapper is missing: $mavenWrapper")
        Write-ScanLog "ERROR Maven wrapper is missing: $mavenWrapper"
    } elseif (-not $java21Ready) {
        Write-ScanLog 'ERROR Maven gates are unavailable without Java 21.'
    } else {
        $mavenArgs = @('-B', '-ntp', '-T1C', '-Dtest=*Test,*Tests,*IT', 'verify')
        $mavenInvocation = Get-MavenInvocation -WrapperPath $mavenWrapper -Arguments $mavenArgs
        $javaBomSource = Join-Path $repositoryRoot 'target/sentinelops-java.json'
        $javaBomDestination = Join-Path $reportRoot 'sentinelops-java.cdx.json'
        $javaVerifyStartedAtUtc = [DateTime]::UtcNow
        Remove-StaleScanFile -Path $javaBomSource -Label 'Java CycloneDX SBOM source'
        Remove-StaleScanFile -Path $javaBomDestination -Label 'Java CycloneDX SBOM report'
        $null = Invoke-LoggedCommand -Label 'Java reactor tests and CycloneDX SBOM generation (Maven verify)' -Executable $mavenInvocation.Executable -Arguments $mavenInvocation.Arguments
        Write-JavaTestSummary -StartedAtUtc $javaVerifyStartedAtUtc
        $null = Confirm-AndCopyBom -SourcePath $javaBomSource -DestinationPath $javaBomDestination -Label 'Java SBOM'

        if ([string]::IsNullOrWhiteSpace($env:NVD_API_KEY)) {
            Write-ScanLog 'OWASP Dependency-Check uses the official daily NVD mirror because NVD_API_KEY is not configured. The mirror is best-effort and may lag by up to 24 hours; audit and CVSS failure gates remain enabled.'
        } else {
            Write-ScanLog 'OWASP Dependency-Check will read the NVD API key from NVD_API_KEY without exposing its value in the Maven arguments.'
        }
        Write-ScanLog 'OWASP RetireJS analysis is disabled because npm audit and Trivy provide the required Node dependency coverage; OWASP Java CVE analysis and its CVSS threshold remain enabled.'
        $dependencyCheckDataDirectory = Join-Path $reportRoot 'odc-datafeed-database'
        New-Item -ItemType Directory -Path $dependencyCheckDataDirectory -Force | Out-Null
        $dependencyCheckSuppressionPath = Join-Path $reportRoot 'dependency-check-suppressions.generated.xml'
        Remove-StaleScanFile -Path $dependencyCheckSuppressionPath -Label 'Dependency-Check suppression XML'
        $dependencyCheckReady = $true
        if ($dependencyCheckExceptions.Count -gt 0) {
            try {
                $dependencyTreeEntries = [System.Collections.Generic.List[object]]::new()
                foreach ($moduleName in @('ops-api', 'ops-executor', 'demo-service')) {
                    $moduleTreePath = Join-Path $reportRoot "maven-dependency-tree-$moduleName.json"
                    Remove-StaleScanFile -Path $moduleTreePath -Label "Maven dependency tree for $moduleName"
                    $treeArguments = @(
                        '-B', '-ntp', '-pl', "apps/$moduleName", '-DoutputType=json',
                        "-DoutputFile=$moduleTreePath",
                        'org.apache.maven.plugins:maven-dependency-plugin:3.10.0:tree'
                    )
                    $treeInvocation = Get-MavenInvocation -WrapperPath $mavenWrapper -Arguments $treeArguments
                    $treeStatus = Invoke-LoggedCommand -Label "Maven dependency tree scope verification for $moduleName" -Executable $treeInvocation.Executable -Arguments $treeInvocation.Arguments
                    if ($treeStatus -ne 0) {
                        throw "Maven dependency tree for $moduleName exited with code $treeStatus."
                    }
                    $moduleEntries = @(Get-MavenDependencyTreeEntries -Path $moduleTreePath)
                    if ($moduleEntries.Count -eq 0) {
                        throw "Maven dependency tree for $moduleName contains no dependencies."
                    }
                    $dependencyTreeEntries.AddRange([object[]]$moduleEntries)
                    Write-ScanLog "Parsed $($moduleEntries.Count) Maven dependency tree entries for $moduleName."
                }

                $null = Test-DependencyCheckExceptionScope -Exceptions $dependencyCheckExceptions -Entries $dependencyTreeEntries
                foreach ($dependencyCheckException in $dependencyCheckExceptions) {
                    Write-ScanLog "Validated OWASP exception $($dependencyCheckException.cve) for $($dependencyCheckException.purl): the test container is test-scope only and the vulnerable version is absent from non-test scopes."
                }
                Write-DependencyCheckSuppressionFile -Exceptions $dependencyCheckExceptions -Path $dependencyCheckSuppressionPath
                Write-ScanLog "Generated exact test-scope OWASP suppressions at $dependencyCheckSuppressionPath."
            } catch {
                $dependencyCheckReady = $false
                $script:failures.Add("OWASP test-scope exception validation failed: $($_.Exception.Message)")
                Write-ScanLog "ERROR OWASP test-scope exception validation failed: $($_.Exception.Message)"
            }
        } else {
            Write-DependencyCheckSuppressionFile -Exceptions $dependencyCheckExceptions -Path $dependencyCheckSuppressionPath
            Write-ScanLog "Generated an empty OWASP suppression file at $dependencyCheckSuppressionPath."
        }
        $dependencyCheckArgs = @('-B', '-ntp', '-T1C') + @(Get-DependencyCheckArguments -DataDirectory $dependencyCheckDataDirectory -SuppressionFile $dependencyCheckSuppressionPath) + @('org.owasp:dependency-check-maven:12.2.2:aggregate')
        $dependencyCheckSource = Join-Path $repositoryRoot 'target/security-reports/dependency-check-report.json'
        $dependencyCheckHtmlSource = Join-Path $repositoryRoot 'target/security-reports/dependency-check-report.html'
        $dependencyCheckDestination = Join-Path $reportRoot 'dependency-check-report.json'
        $dependencyCheckHtmlDestination = Join-Path $reportRoot 'dependency-check-report.html'
        if ($dependencyCheckReady) {
            Remove-StaleScanFile -Path $dependencyCheckSource -Label 'Dependency-Check JSON source'
            Remove-StaleScanFile -Path $dependencyCheckHtmlSource -Label 'Dependency-Check HTML source'
            Remove-StaleScanFile -Path $dependencyCheckDestination -Label 'Dependency-Check JSON report'
            Remove-StaleScanFile -Path $dependencyCheckHtmlDestination -Label 'Dependency-Check HTML report'
            $dependencyCheckInvocation = Get-MavenInvocation -WrapperPath $mavenWrapper -Arguments $dependencyCheckArgs
            $null = Invoke-LoggedCommand -Label 'OWASP Dependency-Check aggregate audit (CVSS 7.0 threshold)' -Executable $dependencyCheckInvocation.Executable -Arguments $dependencyCheckInvocation.Arguments
            $dependencyCheckDocument = Confirm-JsonReport -Path $dependencyCheckSource -Kind 'dependency-check'
            if ($null -ne $dependencyCheckDocument) {
                Copy-Item -LiteralPath $dependencyCheckSource -Destination $dependencyCheckDestination -Force
                if (Test-Path -LiteralPath $dependencyCheckHtmlSource -PathType Leaf) {
                    Copy-Item -LiteralPath $dependencyCheckHtmlSource -Destination $dependencyCheckHtmlDestination -Force
                } else {
                    $script:failures.Add("Dependency-Check did not create HTML report: $dependencyCheckHtmlSource")
                }
            } else {
                Write-ScanLog 'ERROR Dependency-Check JSON is missing or invalid; the nonempty report gate failed.'
            }
        } else {
            Write-ScanLog 'ERROR Dependency-Check did not run because its required test-scope exception validation failed.'
        }
    }

    $npmCommand = Get-Command npm -ErrorAction SilentlyContinue
    if ($null -eq $npmCommand) {
        $script:failures.Add('npm is unavailable; Node tests, audit and SBOM were not run.')
        Write-ScanLog 'ERROR npm is unavailable; Node tests, audit and SBOM were not run.'
    } else {
        $webRoot = Join-Path $repositoryRoot 'web/ops-console'
        $nodeWorkspace = Join-Path $reportRoot ('node-workspace-' + [guid]::NewGuid().ToString('N'))
        $npmCacheRoot = Join-Path $reportRoot 'npm-cache'
        $npmExecutable = $npmCommand.Source
        try {
            New-Item -ItemType Directory -Path $nodeWorkspace -Force | Out-Null
            New-Item -ItemType Directory -Path $npmCacheRoot -Force | Out-Null
            $npmAuditPath = Join-Path $reportRoot 'npm-audit.json'
            $nodeBomDestination = Join-Path $reportRoot 'ops-console.cdx.json'
            Remove-StaleScanFile -Path $npmAuditPath -Label 'npm audit JSON report'
            Remove-StaleScanFile -Path $nodeBomDestination -Label 'Node CycloneDX SBOM report'
            foreach ($entry in Get-ChildItem -LiteralPath $webRoot -Force) {
                if ($entry.PSIsContainer -and $entry.Name -in @('node_modules', 'dist', 'build', '.git', 'coverage', 'playwright-report', 'test-results')) {
                    continue
                }
                Copy-Item -LiteralPath $entry.FullName -Destination $nodeWorkspace -Recurse -Force
            }

            $npmCiStatus = Invoke-LoggedCommand -Label 'Node dependency installation from package-lock.json (npm ci in an isolated report workspace)' -Executable $npmExecutable -Arguments @('ci', '--no-fund', '--no-audit', '--cache', $npmCacheRoot) -WorkingDirectory $nodeWorkspace
            $npmAuditStatus = Invoke-LoggedCommand -Label 'npm dependency audit (HIGH and CRITICAL fail)' -Executable $npmExecutable -Arguments @('audit', '--audit-level=high', '--json', '--loglevel=silent', '--cache', $npmCacheRoot) -WorkingDirectory $nodeWorkspace -OutputFile $npmAuditPath
            $null = Confirm-JsonReport -Path $npmAuditPath -Kind 'npm-audit'
            $null = Invoke-LoggedCommand -Label 'Node lint' -Executable $npmExecutable -Arguments @('run', 'lint') -WorkingDirectory $nodeWorkspace
            $null = Invoke-LoggedCommand -Label 'Node tests (Vitest run mode)' -Executable $npmExecutable -Arguments @('test', '--', '--run') -WorkingDirectory $nodeWorkspace
            $null = Invoke-LoggedCommand -Label 'Node production build' -Executable $npmExecutable -Arguments @('run', 'build') -WorkingDirectory $nodeWorkspace
            $null = Invoke-LoggedCommand -Label 'Node CycloneDX SBOM generation' -Executable $npmExecutable -Arguments @('run', 'sbom') -WorkingDirectory $nodeWorkspace
            $nodeBomSource = Join-Path $nodeWorkspace 'dist/ops-console.cdx.json'
            $null = Confirm-AndCopyBom -SourcePath $nodeBomSource -DestinationPath $nodeBomDestination -Label 'Node SBOM'
            if ($npmCiStatus -ne 0 -or $npmAuditStatus -ne 0) {
                Write-ScanLog 'Node install or audit failed; remaining Node gates were still attempted where available.'
            }
        } catch {
            $script:failures.Add("Node security pipeline failed: $($_.Exception.Message)")
            Write-ScanLog "ERROR Node security pipeline failed: $($_.Exception.Message)"
        } finally {
            $null = Remove-IsolatedNodeWorkspace -WorkspacePath $nodeWorkspace -ReportRoot $reportRoot
        }
    }

    $dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $dockerCommand) {
        $script:failures.Add('Docker CLI is unavailable; Trivy container scans and image builds were not run.')
        Write-ScanLog 'ERROR Docker CLI is unavailable; Trivy container scans and image builds were not run.'
    } else {
        $dockerExecutable = $dockerCommand.Source
        $engineStatus = Invoke-LoggedCommand -Label 'Docker Engine availability check' -Executable $dockerExecutable -Arguments @('info')
        if ($engineStatus -ne 0) {
            $script:failures.Add("Docker Engine is unavailable (docker info exited with code $engineStatus); Trivy scans and image builds were not run.")
            Write-ScanLog 'ERROR Docker Engine unavailable; scan requirements remain unsatisfied.'
        } else {
            $versionLog = Join-Path $reportRoot 'trivy-version.log'
            $versionStatus = Invoke-LoggedCommand -Label 'Pinned Trivy version check' -Executable $dockerExecutable -Arguments @('run', '--rm', $trivyImage, '--version') -OutputFile $versionLog
            if ($versionStatus -ne 0) {
                $script:failures.Add("Unable to start the pinned Trivy image $trivyImage.")
            } elseif ((Get-Content -LiteralPath $versionLog -Raw) -notmatch '0\.74\.0') {
                $script:failures.Add("Trivy did not report the expected pinned version 0.74.0 from $trivyImage.")
            }

            $cacheRoot = Join-Path $reportRoot 'trivy-cache'
            New-Item -ItemType Directory -Path $cacheRoot -Force | Out-Null
            $mountRoot = "type=bind,source=$repositoryRoot,target=/workspace"
            $mountCache = "type=bind,source=$cacheRoot,target=/root/.cache/trivy"
            $mavenRepositoryRoot = Join-Path $HOME '.m2/repository'
            $requiredMavenPurls = @()
            $mountMavenCache = $null
            try {
                $requiredMavenPurls = @(Get-TrivyRequiredMavenPurls -JavaBomPath $javaBomDestination)
                $mountMavenCache = Get-TrivyMavenRepositoryMount -HostMavenRepository $mavenRepositoryRoot -RequiredPurls $requiredMavenPurls
            } catch {
                $script:failures.Add("Trivy offline Maven cache preflight failed: $($_.Exception.Message)")
                Write-ScanLog "ERROR Trivy offline Maven cache preflight failed: $($_.Exception.Message)"
            }
            $reportWorkspaceRelative = [System.IO.Path]::GetRelativePath($repositoryRoot, $reportRoot).Replace('\', '/')
            $filesystemIgnoreRelative = [System.IO.Path]::GetRelativePath($repositoryRoot, $filesystemIgnorePath).Replace('\', '/')
            $filesystemScanIgnorePath = "/workspace/$filesystemIgnoreRelative"

            $filesystemReport = Join-Path $reportRoot 'trivy-filesystem.json'
            $filesystemReportRelative = "$reportWorkspaceRelative/trivy-filesystem.json"
            $filesystemArguments = @(
                'run', '--rm', '--mount', $mountRoot, '--mount', $mountCache
            )
            if ($mountMavenCache) {
                $filesystemArguments += @('--mount', $mountMavenCache)
            }
            $filesystemArguments += @(
                '--workdir', '/workspace', $trivyImage, 'fs', '--ignorefile', $filesystemScanIgnorePath, '--format', 'json',
                '--output', "/workspace/$filesystemReportRelative"
            )
            $filesystemArguments += Get-TrivyScannerArguments -TargetType filesystem -ScanType vulnerability
            foreach ($skipDirectory in Get-TrivyFilesystemSkipDirectories) {
                $filesystemArguments += @('--skip-dirs', $skipDirectory)
            }
            $filesystemArguments += '/workspace'
            Invoke-TrivyScan -Label 'Trivy filesystem vulnerability and misconfiguration scan (HIGH,CRITICAL)' -Arguments $filesystemArguments -ReportPath $filesystemReport
            try {
                $null = Confirm-TrivyMavenCoverage -JavaBomPath $javaBomDestination -TrivyReportPath $filesystemReport
            } catch {
                $script:failures.Add("Trivy filesystem Java dependency coverage validation failed: $($_.Exception.Message)")
                Write-ScanLog "ERROR Trivy filesystem Java dependency coverage validation failed: $($_.Exception.Message)"
            }

            $filesystemSecretsReport = Join-Path $reportRoot 'trivy-filesystem-secrets.json'
            $filesystemSecretsReportRelative = "$reportWorkspaceRelative/trivy-filesystem-secrets.json"
            $filesystemSecretsArguments = @(
                'run', '--rm', '--mount', $mountRoot, '--mount', $mountCache
            )
            if ($mountMavenCache) {
                $filesystemSecretsArguments += @('--mount', $mountMavenCache)
            }
            $filesystemSecretsArguments += @(
                '--workdir', '/workspace', $trivyImage, 'fs', '--ignorefile', $filesystemScanIgnorePath,
                '--format', 'json', '--output', "/workspace/$filesystemSecretsReportRelative"
            )
            $filesystemSecretsArguments += Get-TrivyScannerArguments -TargetType filesystem -ScanType secret
            foreach ($skipDirectory in Get-TrivyFilesystemSkipDirectories) {
                $filesystemSecretsArguments += @('--skip-dirs', $skipDirectory)
            }
            $filesystemSecretsArguments += '/workspace'
            Invoke-TrivyScan -Label 'Trivy filesystem secrets scan (all severities)' -Arguments $filesystemSecretsArguments -ReportPath $filesystemSecretsReport

            foreach ($image in $script:securityImages) {
                $buildArguments = @('build', '--file', $image.dockerfile, '--tag', $image.tag, '.')
                $buildStatus = Invoke-LoggedCommand -Label "Build $($image.name) image" -Executable $dockerExecutable -Arguments $buildArguments
                if ($buildStatus -ne 0) {
                    continue
                }

                $archiveRelative = "$reportWorkspaceRelative/image-$($image.name).tar"
                $archivePath = Join-Path $reportRoot "image-$($image.name).tar"
                try {
                    Remove-StaleScanFile -Path $archivePath -Label "$($image.name) Docker image archive"
                    $saveStatus = Invoke-LoggedCommand -Label "Save $($image.name) image for scanning" -Executable $dockerExecutable -Arguments @('save', '--output', $archivePath, $image.tag)
                    if ($saveStatus -ne 0) {
                        continue
                    }

                    $imageReport = Join-Path $reportRoot "trivy-image-$($image.name).json"
                    $imageReportRelative = "$reportWorkspaceRelative/trivy-image-$($image.name).json"
                    $imageIgnoreWorkspaceRelative = [System.IO.Path]::GetRelativePath($repositoryRoot, $imageIgnorePaths[$image.tag]).Replace('\', '/')
                    $imageScanIgnorePath = "/workspace/$imageIgnoreWorkspaceRelative"
                    $imageArguments = @(
                        'run', '--rm', '--mount', $mountRoot, '--mount', $mountCache, '--workdir', '/workspace',
                        $trivyImage, 'image', '--input', "/workspace/$archiveRelative", '--ignorefile', $imageScanIgnorePath,
                        '--format', 'json', '--output', "/workspace/$imageReportRelative"
                    )
                    $imageArguments += Get-TrivyScannerArguments -TargetType image -ScanType vulnerability
                    Invoke-TrivyScan -Label "Trivy vulnerability scan for $($image.name) image (HIGH,CRITICAL)" -Arguments $imageArguments -ReportPath $imageReport

                    $imageSecretsReport = Join-Path $reportRoot "trivy-image-$($image.name)-secrets.json"
                    $imageSecretsReportRelative = "$reportWorkspaceRelative/trivy-image-$($image.name)-secrets.json"
                    $imageSecretsArguments = @(
                        'run', '--rm', '--mount', $mountRoot, '--mount', $mountCache, '--workdir', '/workspace',
                        $trivyImage, 'image', '--input', "/workspace/$archiveRelative", '--ignorefile', $imageScanIgnorePath,
                        '--format', 'json', '--output', "/workspace/$imageSecretsReportRelative"
                    )
                    $imageSecretsArguments += Get-TrivyScannerArguments -TargetType image -ScanType secret
                    Invoke-TrivyScan -Label "Trivy secrets scan for $($image.name) image (all severities)" -Arguments $imageSecretsArguments -ReportPath $imageSecretsReport
                } finally {
                    if (Test-Path -LiteralPath $archivePath -PathType Leaf) {
                        Remove-Item -LiteralPath $archivePath -Force
                        Write-ScanLog "Removed temporary image archive $archivePath; JSON reports were retained."
                    }
                }
            }
        }
    }

    if ($script:failures.Count -gt 0) {
        throw "One or more required security gates failed: $($script:failures -join '; ')."
    }

    Write-ScanLog 'All required tests, audits, SBOM generation and Trivy scans passed.'
    exit 0
} catch {
    Write-ScanLog "ERROR $($_.Exception.Message)"
    exit 1
}
