[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $DatabaseUrl,

    [Parameter(Mandatory = $true)]
    [string] $OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

function ConvertFrom-PostgresUrl {
    param([Parameter(Mandatory = $true)][string] $Value)

    try {
        $uri = [Uri]::new($Value, [UriKind]::Absolute)
    } catch {
        throw 'DatabaseUrl must be a valid explicit PostgreSQL connection URI.'
    }
    if ($uri.Scheme -notin @('postgres', 'postgresql') -or [string]::IsNullOrWhiteSpace($uri.DnsSafeHost)) {
        throw 'DatabaseUrl must use postgres:// or postgresql:// and name a host.'
    }

    $rawPath = $uri.AbsolutePath.TrimStart('/')
    if ([string]::IsNullOrWhiteSpace($rawPath)) {
        throw 'DatabaseUrl must name a database explicitly.'
    }
    $database = [Uri]::UnescapeDataString($rawPath)

    $rawUserInfo = $uri.UserInfo
    if ([string]::IsNullOrWhiteSpace($rawUserInfo)) {
        throw 'DatabaseUrl must name a PostgreSQL user explicitly.'
    }
    $separator = $rawUserInfo.IndexOf(':')
    $encodedUser = if ($separator -ge 0) { $rawUserInfo.Substring(0, $separator) } else { $rawUserInfo }
    $user = [Uri]::UnescapeDataString($encodedUser)
    if ([string]::IsNullOrWhiteSpace($user)) {
        throw 'DatabaseUrl must name a PostgreSQL user explicitly.'
    }
    $password = if ($separator -ge 0) { [Uri]::UnescapeDataString($rawUserInfo.Substring($separator + 1)) } else { $null }

    $environment = @{
        PGHOST = $uri.DnsSafeHost
        PGPORT = if ($uri.IsDefaultPort) { '5432' } else { [string]$uri.Port }
        PGUSER = $user
        PGDATABASE = $database
        PGPASSWORD = $password
        PGHOSTADDR = $null
        PGSERVICE = $null
        PGSERVICEFILE = $null
        PGSSLMODE = $null
        PGSSLROOTCERT = $null
        PGSSLCERT = $null
        PGSSLKEY = $null
        PGSSLPASSWORD = $null
        PGCONNECT_TIMEOUT = '10'
        PGAPPNAME = $null
        PGTARGETSESSIONATTRS = $null
        PGOPTIONS = $null
        PGCHANNELBINDING = $null
        PGGSSENCMODE = $null
    }
    $queryMap = @{
        sslmode = 'PGSSLMODE'
        sslrootcert = 'PGSSLROOTCERT'
        sslcert = 'PGSSLCERT'
        sslkey = 'PGSSLKEY'
        sslpassword = 'PGSSLPASSWORD'
        connect_timeout = 'PGCONNECT_TIMEOUT'
        application_name = 'PGAPPNAME'
        target_session_attrs = 'PGTARGETSESSIONATTRS'
        options = 'PGOPTIONS'
        channel_binding = 'PGCHANNELBINDING'
        gssencmode = 'PGGSSENCMODE'
    }
    foreach ($pair in $uri.Query.TrimStart('?').Split('&', [StringSplitOptions]::RemoveEmptyEntries)) {
        $parts = $pair.Split('=', 2)
        $key = [Uri]::UnescapeDataString($parts[0]).ToLowerInvariant()
        $value = if ($parts.Count -gt 1) { [Uri]::UnescapeDataString($parts[1]) } else { '' }
        if (-not $queryMap.ContainsKey($key)) {
            throw "DatabaseUrl contains an unsupported PostgreSQL option '$key'."
        }
        $environment[$queryMap[$key]] = $value
    }

    [pscustomobject]@{ Host = $uri.DnsSafeHost; Database = $database; Environment = $environment }
}

function Save-ProcessEnvironment {
    param([string[]] $Names)
    $snapshot = @{}
    foreach ($name in $Names) { $snapshot[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    return $snapshot
}

function Set-ProcessEnvironment {
    param([hashtable] $Values)
    foreach ($name in $Values.Keys) {
        $value = $Values[$name]
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

function Restore-ProcessEnvironment {
    param([hashtable] $Snapshot)
    foreach ($name in $Snapshot.Keys) {
        [Environment]::SetEnvironmentVariable($name, $Snapshot[$name], 'Process')
    }
}

function Resolve-RequiredProgram {
    param([Parameter(Mandatory = $true)][string] $Name)
    $program = Get-Command -Name $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $program) {
        $program = Get-Command -Name ($Name + '.ps1') -CommandType ExternalScript -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if ($null -eq $program) {
        throw "Required PostgreSQL client '$Name' was not found on PATH. Install the PostgreSQL client tools before creating a backup."
    }
    return $program.Source
}

function Invoke-Program {
    param(
        [Parameter(Mandatory = $true)][string] $Program,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $FailureMessage
    )
    $output = @(& $Program @Arguments 2>$null | ForEach-Object { [string]$_ })
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw "$FailureMessage (exit code $exitCode)." }
    return ($output -join [Environment]::NewLine).Trim()
}

function Invoke-PsqlQuery {
    param([string] $Psql, [string] $Query)
    Invoke-Program -Program $Psql -Arguments @(
        '--no-password', '--no-psqlrc', '--tuples-only', '--no-align',
        '--set=ON_ERROR_STOP=1', '--command', $Query, '--dbname', $env:PGDATABASE
    ) -FailureMessage 'Could not query the explicit backup database'
}

$connection = ConvertFrom-PostgresUrl -Value $DatabaseUrl
$pgDump = Resolve-RequiredProgram -Name 'pg_dump'
$psql = Resolve-RequiredProgram -Name 'psql'

$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ', [Globalization.CultureInfo]::InvariantCulture)
$archiveName = "sentinelops-$stamp.dump"
$archivePath = Join-Path $outputRoot $archiveName
$manifestPath = "$archivePath.manifest.json"
if ((Test-Path -LiteralPath $archivePath) -or (Test-Path -LiteralPath $manifestPath)) {
    throw "Backup output already exists for timestamp $stamp; no existing file was modified."
}
$temporaryArchivePath = "$archivePath.partial-$([guid]::NewGuid().ToString('N'))"

$pgEnvironmentNames = @(
    'PGHOST', 'PGHOSTADDR', 'PGPORT', 'PGUSER', 'PGDATABASE', 'PGPASSWORD',
    'PGSERVICE', 'PGSERVICEFILE', 'PGSSLMODE', 'PGSSLROOTCERT', 'PGSSLCERT',
    'PGSSLKEY', 'PGSSLPASSWORD', 'PGCONNECT_TIMEOUT', 'PGAPPNAME',
    'PGTARGETSESSIONATTRS', 'PGOPTIONS', 'PGCHANNELBINDING', 'PGGSSENCMODE'
)
$oldPgEnvironment = Save-ProcessEnvironment -Names $pgEnvironmentNames
try {
    Set-ProcessEnvironment -Values $connection.Environment
    Write-Host "Backing up explicit PostgreSQL target '$($connection.Host)/$($connection.Database)'."
    $flywayQuery = @"
SELECT CASE
  WHEN to_regclass('public.flyway_schema_history') IS NULL THEN 'missing'
  ELSE COALESCE((
    SELECT version
    FROM public.flyway_schema_history
    WHERE success = true AND version IS NOT NULL
    ORDER BY installed_rank DESC
    LIMIT 1
  ), 'none')
END;
"@
    $flywayVersion = Invoke-PsqlQuery -Psql $psql -Query $flywayQuery
    if ($flywayVersion -in @('missing', 'none') -or [string]::IsNullOrWhiteSpace($flywayVersion)) {
        throw 'Backup target has no successful versioned Flyway migration; refusing to create an unversioned backup.'
    }

    $null = Invoke-Program -Program $pgDump -Arguments @(
        '--format=custom', '--no-owner', '--no-acl', '--no-password',
        '--file', $temporaryArchivePath, '--dbname', $connection.Database
    ) -FailureMessage 'pg_dump failed for the explicit backup target'
    if (-not (Test-Path -LiteralPath $temporaryArchivePath -PathType Leaf) -or (Get-Item -LiteralPath $temporaryArchivePath).Length -le 0) {
        throw 'pg_dump completed without creating a non-empty archive.'
    }
    [IO.File]::Move($temporaryArchivePath, $archivePath)

    $hash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest = [ordered]@{
        format = 'postgresql-custom'
        createdAtUtc = [DateTime]::UtcNow.ToString('o', [Globalization.CultureInfo]::InvariantCulture)
        file = $archiveName
        sha256 = $hash
        flywayVersion = $flywayVersion
        database = $connection.Database
    } | ConvertTo-Json -Depth 4

    $temporaryManifestPath = "$manifestPath.tmp-$([guid]::NewGuid().ToString('N'))"
    $manifestBytes = [Text.UTF8Encoding]::new($false).GetBytes($manifest + [Environment]::NewLine)
    $manifestStream = [IO.File]::Open($temporaryManifestPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $manifestStream.Write($manifestBytes, 0, $manifestBytes.Length) } finally { $manifestStream.Dispose() }
    [IO.File]::Move($temporaryManifestPath, $manifestPath)

    Write-Host "Backup archive: $archivePath"
    Write-Host "SHA-256 manifest: $manifestPath"
    Write-Host "Flyway schema version: $flywayVersion"
} catch {
    if (Test-Path -LiteralPath $temporaryArchivePath -PathType Leaf) {
        Write-Warning "Backup did not complete. The unique partial archive is '$temporaryArchivePath'; it has no valid manifest and requires operator review."
    } elseif ((Test-Path -LiteralPath $archivePath -PathType Leaf) -and -not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        Write-Warning "Backup did not complete. The archive '$archivePath' has no valid manifest and requires operator review."
    }
    throw
} finally {
    Restore-ProcessEnvironment -Snapshot $oldPgEnvironment
}
