[CmdletBinding()]
param(
    [string] $BackupFile,
    [Parameter(Mandatory = $true)][string] $TestDatabaseUrl,
    [switch] $AllowProductionRestoreTarget
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function ConvertFrom-PostgresUrl {
    param([string] $Value)
    try { $uri = [Uri]::new($Value, [UriKind]::Absolute) } catch { throw 'TestDatabaseUrl must be a valid explicit PostgreSQL URI.' }
    if ($uri.Scheme -notin @('postgres', 'postgresql') -or [string]::IsNullOrWhiteSpace($uri.DnsSafeHost)) {
        throw 'TestDatabaseUrl must use postgres:// or postgresql:// and name a host.'
    }
    $database = [Uri]::UnescapeDataString($uri.AbsolutePath.TrimStart('/'))
    if ([string]::IsNullOrWhiteSpace($database)) { throw 'TestDatabaseUrl must name an existing control database explicitly.' }
    $userInfo = $uri.UserInfo
    if ([string]::IsNullOrWhiteSpace($userInfo)) { throw 'TestDatabaseUrl must name a PostgreSQL user explicitly.' }
    $separator = $userInfo.IndexOf(':')
    $user = [Uri]::UnescapeDataString($(if ($separator -ge 0) { $userInfo.Substring(0, $separator) } else { $userInfo }))
    if ([string]::IsNullOrWhiteSpace($user)) { throw 'TestDatabaseUrl must name a PostgreSQL user explicitly.' }
    $password = if ($separator -ge 0) { [Uri]::UnescapeDataString($userInfo.Substring($separator + 1)) } else { $null }

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
        PGAPPNAME = 'sentinelops-restore-check'
        PGOPTIONS = $null
    }
    $queryEnvironment = @{
        sslmode = 'PGSSLMODE'
        sslrootcert = 'PGSSLROOTCERT'
        sslcert = 'PGSSLCERT'
        sslkey = 'PGSSLKEY'
        sslpassword = 'PGSSLPASSWORD'
        connect_timeout = 'PGCONNECT_TIMEOUT'
        application_name = 'PGAPPNAME'
        options = 'PGOPTIONS'
    }
    foreach ($pair in $uri.Query.TrimStart('?').Split('&', [StringSplitOptions]::RemoveEmptyEntries)) {
        $parts = $pair.Split('=', 2)
        $key = [Uri]::UnescapeDataString($parts[0]).ToLowerInvariant()
        if (-not $queryEnvironment.ContainsKey($key)) { throw "TestDatabaseUrl contains unsupported PostgreSQL option '$key'." }
        $environment[$queryEnvironment[$key]] = if ($parts.Count -gt 1) { [Uri]::UnescapeDataString($parts[1]) } else { '' }
    }
    [pscustomobject]@{ Host = $uri.DnsSafeHost; Database = $database; User = $user; Environment = $environment }
}

function Test-ProductionHostname {
    param([string] $HostName)
    return [regex]::IsMatch($HostName, '(?i)(?:^|[._-])(?:prod(?:uction)?|prd|live)(?:[._-]|$)')
}

function Save-ProcessEnvironment {
    param([string[]] $Names)
    $snapshot = @{}
    foreach ($name in $Names) { $snapshot[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    return $snapshot
}

function Set-ProcessEnvironment {
    param([hashtable] $Values)
    foreach ($name in $Values.Keys) { [Environment]::SetEnvironmentVariable($name, $Values[$name], 'Process') }
}

function Restore-ProcessEnvironment {
    param([hashtable] $Snapshot)
    foreach ($name in $Snapshot.Keys) { [Environment]::SetEnvironmentVariable($name, $Snapshot[$name], 'Process') }
}

function Resolve-RequiredProgram {
    param([string] $Name)
    $command = Get-Command -Name $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $command) {
        $command = Get-Command -Name ($Name + '.ps1') -CommandType ExternalScript -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if ($null -eq $command) { throw "Required PostgreSQL client '$Name' was not found on PATH. Install PostgreSQL client tools before restore validation." }
    return $command.Source
}

function Get-MavenInvocation {
    param([string] $WrapperPath, [string[]] $Arguments)
    if ($IsWindows) {
        return [pscustomobject]@{ Executable = $WrapperPath; Arguments = @($Arguments) }
    }
    $bash = Get-Command -Name bash -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $bash) { throw 'bash is required to run the Maven wrapper on non-Windows systems.' }
    return [pscustomobject]@{ Executable = $bash.Source; Arguments = @($WrapperPath) + @($Arguments) }
}

function New-AutomaticBackupDirectory {
    $backupRoot = if (-not [string]::IsNullOrWhiteSpace($env:SENTINELOPS_BACKUP_DIR)) {
        $env:SENTINELOPS_BACKUP_DIR
    } else {
        Join-Path ([IO.Path]::GetTempPath()) 'sentinelops/backup-restore'
    }
    $backupRoot = [IO.Path]::GetFullPath($backupRoot)
    [IO.Directory]::CreateDirectory($backupRoot) | Out-Null
    $leaf = 'restore-check-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ', [Globalization.CultureInfo]::InvariantCulture) + '-' + [guid]::NewGuid().ToString('N')
    $directory = Join-Path $backupRoot $leaf
    if (Test-Path -LiteralPath $directory) { throw 'Generated automatic backup directory already exists; no existing files were changed.' }
    New-Item -ItemType Directory -Path $directory -ErrorAction Stop | Out-Null
    if (-not $IsWindows) {
        $chmod = Resolve-RequiredProgram 'chmod'
        $null = Invoke-Program -Program $chmod -Arguments @('700', $directory) -FailureMessage 'Could not restrict automatic backup directory permissions to its owner'
        Write-Host "Automatic backup directory: $directory (mode 0700)."
    } else {
        Write-Host "Automatic backup directory: $directory (inherits access controls from its protected parent)."
    }
    return $directory
}

function Invoke-Program {
    param([string] $Program, [string[]] $Arguments, [string] $FailureMessage)
    $output = @(& $Program @Arguments 2>$null | ForEach-Object { [string]$_ })
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw "$FailureMessage (exit code $exitCode)." }
    return ($output -join [Environment]::NewLine).Trim()
}

function Invoke-PsqlQuery {
    param([string] $Psql, [string] $Query, [string] $FailureMessage = 'Could not query the explicit restore test PostgreSQL target')
    Invoke-Program -Program $Psql -Arguments @(
        '--no-password', '--no-psqlrc', '--tuples-only', '--no-align',
        '--set=ON_ERROR_STOP=1', '--command', $Query, '--dbname', $env:PGDATABASE
    ) -FailureMessage $FailureMessage
}

function Test-Java21 {
    param([string] $Java)
    $versionOutput = @(& $Java '-version' 2>&1 | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
    $exitCode = $LASTEXITCODE
    return ($exitCode -eq 0 -and $versionOutput -match '(?i)\b(?:openjdk|java)\s+(?:version\s+)?["'']?21(?:[.\s"'']|$)')
}

function Find-JavaToolchain {
    $candidates = [Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { $candidates.Add($env:JAVA_HOME) }
    $localJdkRoot = Join-Path $repoRoot '.toolchains/jdk-21'
    if (Test-Path -LiteralPath $localJdkRoot -PathType Container) {
        foreach ($directory in Get-ChildItem -LiteralPath $localJdkRoot -Directory) { $candidates.Add($directory.FullName) }
    }
    $javaName = if ($IsWindows) { 'java.exe' } else { 'java' }
    $javacName = if ($IsWindows) { 'javac.exe' } else { 'javac' }
    foreach ($candidate in $candidates) {
        $java = Join-Path $candidate (Join-Path 'bin' $javaName)
        $javac = Join-Path $candidate (Join-Path 'bin' $javacName)
        if ((Test-Path -LiteralPath $java -PathType Leaf) -and (Test-Path -LiteralPath $javac -PathType Leaf) -and (Test-Java21 $java)) {
            return [pscustomobject]@{ Home = $candidate; Java = $java; Javac = $javac }
        }
    }
    $java = Get-Command -Name $javaName -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    $javac = Get-Command -Name $javacName -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($java -and $javac -and (Test-Java21 $java.Source)) {
        return [pscustomobject]@{ Home = (Split-Path -Parent (Split-Path -Parent $java.Source)); Java = $java.Source; Javac = $javac.Source }
    }
    throw 'Java 21 JDK with java and javac is required for Flyway validation; install it or configure JAVA_HOME.'
}

function Invoke-FlywayValidation {
    param([string] $MavenWrapper, [string] $Java, [string] $Javac, [string] $ClasspathPath, [string] $WorkDirectory)

    $mavenArgs = @(
        '-B', '-ntp', '-q', '-o', '-pl', 'apps/ops-api',
        'dependency:build-classpath', '-DincludeScope=runtime', "-Dmdep.outputFile=$ClasspathPath"
    )
    Push-Location $repoRoot
    try {
        $mavenInvocation = Get-MavenInvocation -WrapperPath $MavenWrapper -Arguments $mavenArgs
        $null = & $mavenInvocation.Executable @($mavenInvocation.Arguments) 2>$null
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $ClasspathPath -PathType Leaf)) {
            throw 'Could not resolve the existing ops-api Flyway/JDBC runtime dependencies in offline Maven mode.'
        }
    } finally { Pop-Location }

    $classPath = (Get-Content -LiteralPath $ClasspathPath -Raw).Trim()
    if ([string]::IsNullOrWhiteSpace($classPath)) { throw 'Maven returned an empty ops-api runtime classpath.' }
    $sourcePath = Join-Path $WorkDirectory 'FlywayRestoreValidation.java'
    $javaSource = @'
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

public final class FlywayRestoreValidation {
    public static void main(String[] args) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setServerName(System.getenv("PGHOST"));
        dataSource.setPortNumber(Integer.parseInt(System.getenv("PGPORT")));
        dataSource.setDatabaseName(System.getenv("PGDATABASE"));
        dataSource.setUser(System.getenv("PGUSER"));
        String password = System.getenv("PGPASSWORD");
        if (password != null && !password.isEmpty()) dataSource.setPassword(password);
        dataSource.setConnectTimeout(Integer.parseInt(System.getenv("PGCONNECT_TIMEOUT")));
        String applicationName = System.getenv("PGAPPNAME");
        if (applicationName != null && !applicationName.isEmpty()) dataSource.setApplicationName(applicationName);
        String sslMode = System.getenv("PGSSLMODE");
        if (sslMode != null && !sslMode.isEmpty()) dataSource.setSslMode(sslMode);
        String sslRootCert = System.getenv("PGSSLROOTCERT");
        if (sslRootCert != null && !sslRootCert.isEmpty()) dataSource.setSslRootCert(sslRootCert);
        String sslCert = System.getenv("PGSSLCERT");
        if (sslCert != null && !sslCert.isEmpty()) dataSource.setSslCert(sslCert);
        String sslKey = System.getenv("PGSSLKEY");
        if (sslKey != null && !sslKey.isEmpty()) dataSource.setSslKey(sslKey);
        String sslPassword = System.getenv("PGSSLPASSWORD");
        if (sslPassword != null && !sslPassword.isEmpty()) dataSource.setSslPassword(sslPassword);
        String options = System.getenv("PGOPTIONS");
        if (options != null && !options.isEmpty()) dataSource.setOptions(options);
        Flyway.configure().dataSource(dataSource)
            .locations(System.getenv("SENTINELOPS_FLYWAY_LOCATION"))
            .load().validate();
    }
}
'@
    [IO.File]::WriteAllText($sourcePath, $javaSource, [Text.UTF8Encoding]::new($false))
    $migrationDirectory = (Join-Path $repoRoot 'apps/ops-api/src/main/resources/db/migration').Replace('\', '/')
    $oldLocation = [Environment]::GetEnvironmentVariable('SENTINELOPS_FLYWAY_LOCATION', 'Process')
    try {
        [Environment]::SetEnvironmentVariable('SENTINELOPS_FLYWAY_LOCATION', 'filesystem:' + $migrationDirectory, 'Process')
        $null = & $Javac '--release' '21' '--class-path' $classPath '-d' $WorkDirectory $sourcePath 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'Could not compile the temporary Flyway validation helper with ops-api runtime dependencies.' }
        $combinedClasspath = $WorkDirectory + [IO.Path]::PathSeparator + $classPath
        $null = Invoke-Program -Program $Java -Arguments @('--class-path', $combinedClasspath, 'FlywayRestoreValidation') -FailureMessage 'Flyway validate failed for the restored temporary database'
    } finally {
        [Environment]::SetEnvironmentVariable('SENTINELOPS_FLYWAY_LOCATION', $oldLocation, 'Process')
    }
}

$connection = ConvertFrom-PostgresUrl -Value $TestDatabaseUrl
if ((Test-ProductionHostname $connection.Host) -and -not $AllowProductionRestoreTarget) {
    throw 'Refusing restore validation because TestDatabaseUrl names a production-looking host. Use a test PostgreSQL instance or explicitly pass -AllowProductionRestoreTarget; validation still creates only a new temporary database.'
}

$psql = Resolve-RequiredProgram 'psql'
$pgRestore = Resolve-RequiredProgram 'pg_restore'
$mavenWrapper = Join-Path $repoRoot $(if ($IsWindows) { 'mvnw.cmd' } else { 'mvnw' })
if (-not (Test-Path -LiteralPath $mavenWrapper -PathType Leaf)) { throw 'The platform-appropriate Maven wrapper (mvnw.cmd or mvnw) is missing; Flyway validation cannot run.' }
$javaToolchain = Find-JavaToolchain

if ([string]::IsNullOrWhiteSpace($BackupFile)) {
    $backupScript = Join-Path $PSScriptRoot 'backup.ps1'
    if (-not (Test-Path -LiteralPath $backupScript -PathType Leaf)) { throw 'backup.ps1 is missing; automatic test backup cannot run.' }
    $automaticBackupDirectory = New-AutomaticBackupDirectory
    Write-Host "Creating restore-check backup from the explicit test database '$($connection.Host)/$($connection.Database)' only."
    & $backupScript -DatabaseUrl $TestDatabaseUrl -OutputDirectory $automaticBackupDirectory
    $archives = @(Get-ChildItem -LiteralPath $automaticBackupDirectory -Filter 'sentinelops-*.dump' -File)
    if ($archives.Count -ne 1) { throw 'Automatic backup did not create exactly one timestamped archive.' }
    $BackupFile = $archives[0].FullName
}

if (-not (Test-Path -LiteralPath $BackupFile -PathType Leaf)) { throw 'BackupFile does not exist or is not a file.' }
$backup = (Resolve-Path -LiteralPath $BackupFile).Path
$manifestPath = "$backup.manifest.json"
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw 'Backup SHA-256 manifest is missing.' }
try { $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json } catch { throw 'Backup SHA-256 manifest is invalid JSON.' }
if ($manifest.format -ne 'postgresql-custom' -or $manifest.file -ne [IO.Path]::GetFileName($backup) -or
    [string]::IsNullOrWhiteSpace([string]$manifest.sha256) -or $manifest.sha256 -notmatch '^[0-9a-fA-F]{64}$' -or
    [string]::IsNullOrWhiteSpace([string]$manifest.flywayVersion)) {
    throw 'Backup manifest does not describe this PostgreSQL custom archive or has no Flyway version.'
}
$actualHash = (Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash
if (-not [string]::Equals($actualHash, [string]$manifest.sha256, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Backup archive SHA-256 does not match its manifest.'
}

$null = Invoke-Program -Program $pgRestore -Arguments @('--list', $backup) -FailureMessage 'pg_restore could not read the backup archive'

$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$work = Join-Path $tempRoot ('sentinelops-restore-check-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null
$tempDatabase = 'sentinelops_restore_check_' + [guid]::NewGuid().ToString('N')
$pgEnvironmentNames = @('PGHOST','PGHOSTADDR','PGPORT','PGUSER','PGDATABASE','PGPASSWORD','PGSERVICE','PGSERVICEFILE','PGSSLMODE','PGSSLROOTCERT','PGSSLCERT','PGSSLKEY','PGSSLPASSWORD','PGCONNECT_TIMEOUT','PGAPPNAME','PGOPTIONS')
$oldPgEnvironment = Save-ProcessEnvironment $pgEnvironmentNames
$oldJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
$oldPath = [Environment]::GetEnvironmentVariable('PATH', 'Process')
$databaseCreated = $false
$databaseOid = $null
$operationFailure = $null
$cleanupFailure = $null

try {
    Set-ProcessEnvironment $connection.Environment
    $exists = Invoke-PsqlQuery $psql "SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_catalog.pg_database WHERE datname = '$tempDatabase') THEN 'exists' ELSE 'missing' END;" 'Unable to connect to the explicit restore test PostgreSQL target or read its database catalog'
    if ($exists -ne 'missing') { throw 'Generated temporary database name already exists; no database was changed.' }

    $quotedName = '"' + $tempDatabase + '"'
    $null = Invoke-PsqlQuery $psql ('CREATE DATABASE ' + $quotedName + ' WITH TEMPLATE template0;') 'PostgreSQL could not create the restore-check database; the target account needs CREATEDB'
    $databaseCreated = $true
    $databaseOid = Invoke-PsqlQuery $psql "SELECT oid::text FROM pg_catalog.pg_database WHERE datname = '$tempDatabase' AND datistemplate = false;" 'Created temporary database identity could not be verified'
    if ($databaseOid -notmatch '^\d+$') { throw 'Created temporary database identity could not be verified; automatic cleanup is disabled for safety.' }

    $restoreEnvironment = @{}
    foreach ($name in $connection.Environment.Keys) { $restoreEnvironment[$name] = $connection.Environment[$name] }
    $restoreEnvironment.PGDATABASE = $tempDatabase
    Set-ProcessEnvironment $restoreEnvironment
    Write-Host "Restoring '$([IO.Path]::GetFileName($backup))' into a new temporary database on '$($connection.Host)'."
    $null = Invoke-Program $pgRestore @('--exit-on-error','--single-transaction','--no-owner','--no-acl','--dbname',$tempDatabase,$backup) 'pg_restore failed for the temporary database'

    [Environment]::SetEnvironmentVariable('JAVA_HOME', $javaToolchain.Home, 'Process')
    [Environment]::SetEnvironmentVariable('PATH', (Join-Path $javaToolchain.Home 'bin') + [IO.Path]::PathSeparator + $oldPath, 'Process')
    Invoke-FlywayValidation $mavenWrapper $javaToolchain.Java $javaToolchain.Javac (Join-Path $work 'ops-api-runtime-classpath.txt') $work

    $coreTables = Invoke-PsqlQuery $psql @'
SELECT count(*)::text
FROM pg_catalog.pg_tables
WHERE schemaname = 'public'
  AND tablename IN ('flyway_schema_history', 'service_catalog', 'incident');
'@ 'Read-only integrity check could not inspect restored core tables'
    if ($coreTables -ne '3') { throw 'Read-only integrity check found missing core SentinelOps tables.' }
    $failedMigrations = Invoke-PsqlQuery $psql 'SELECT count(*)::text FROM public.flyway_schema_history WHERE success IS DISTINCT FROM true;' 'Read-only integrity check could not inspect Flyway history'
    if ($failedMigrations -ne '0') { throw 'Read-only integrity check found failed Flyway history rows.' }
    $currentVersion = Invoke-PsqlQuery $psql "SELECT COALESCE((SELECT version FROM public.flyway_schema_history WHERE success = true AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1), 'none');" 'Read-only integrity check could not read the restored Flyway version'
    if ($currentVersion -eq 'none') { throw 'Read-only integrity check found no successful versioned Flyway migration.' }
    Write-Host "Flyway validate passed; restored schema version: $currentVersion."
} catch {
    $operationFailure = $_.Exception.Message
} finally {
    Set-ProcessEnvironment $connection.Environment
    if ($databaseCreated -and $databaseOid -match '^\d+$' -and $tempDatabase -match '^sentinelops_restore_check_[0-9a-f]{32}$') {
        try {
            $currentOid = Invoke-PsqlQuery $psql "SELECT oid::text FROM pg_catalog.pg_database WHERE datname = '$tempDatabase' AND datistemplate = false;" 'Temporary database identity could not be rechecked for cleanup'
            if ($currentOid -eq $databaseOid) {
                $quotedName = '"' + $tempDatabase + '"'
                $null = Invoke-PsqlQuery $psql ('DROP DATABASE ' + $quotedName + ';') 'Could not drop the verified restore-check temporary database'
                Write-Host 'Removed the temporary restore-check database.'
            } else { $cleanupFailure = 'Cleanup skipped because the database name no longer identifies the database created by this run.' }
        } catch { $cleanupFailure = $_.Exception.Message }
    } elseif ($databaseCreated) {
        $cleanupFailure = 'Cleanup skipped because the temporary database identity was not confirmed.'
    }

    Restore-ProcessEnvironment $oldPgEnvironment
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $oldJavaHome, 'Process')
    [Environment]::SetEnvironmentVariable('PATH', $oldPath, 'Process')
    if (Test-Path -LiteralPath $work -PathType Container) {
        $resolvedWork = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $work).Path)
        $safePrefix = $tempRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        if (-not $resolvedWork.StartsWith($safePrefix, [StringComparison]::OrdinalIgnoreCase)) {
            $cleanupFailure = "Refusing to remove helper files outside the temp root: $resolvedWork"
        } else { Remove-Item -LiteralPath $resolvedWork -Recurse -Force }
    }
}

if ($operationFailure -and $cleanupFailure) { throw "$operationFailure Cleanup also failed: $cleanupFailure" }
if ($operationFailure) { throw $operationFailure }
if ($cleanupFailure) { throw $cleanupFailure }
Write-Host 'Restore validation passed. The temporary database was removed; the backup files were retained.'
