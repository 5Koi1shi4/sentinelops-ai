[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$backupPath = Join-Path $repoRoot 'scripts\backup.ps1'
$restorePath = Join-Path $repoRoot 'scripts\restore-check.ps1'

function Assert-True {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Matches {
    param([string] $Text, [string] $Pattern, [string] $Message)
    if ($Text -notmatch $Pattern) { throw $Message }
}

function Assert-RequiredParameter {
    param([string] $Path, [string] $Name)
    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref] $tokens, [ref] $parseErrors)
    if ($parseErrors.Count -gt 0) { throw "PowerShell parse errors in $($Path): $($parseErrors -join '; ')" }
    $parameter = $ast.ParamBlock.Parameters | Where-Object { $_.Name.VariablePath.UserPath -eq $Name } | Select-Object -First 1
    if ($null -eq $parameter) { throw "Required parameter '$Name' is missing from $([IO.Path]::GetFileName($Path))." }
    $mandatory = $parameter.Attributes |
        Where-Object { $_.TypeName.FullName -eq 'Parameter' } |
        ForEach-Object { $_.NamedArguments } |
        Where-Object { $_.ArgumentName -eq 'Mandatory' -and $_.Argument.Extent.Text -eq '$true' }
    if (-not $mandatory) { throw "Parameter '$Name' must be mandatory and explicit." }
}

function Assert-OptionalParameter {
    param([string] $Path, [string] $Name)
    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref] $tokens, [ref] $parseErrors)
    if ($parseErrors.Count -gt 0) { throw "PowerShell parse errors in $($Path): $($parseErrors -join '; ')" }
    $parameter = $ast.ParamBlock.Parameters | Where-Object { $_.Name.VariablePath.UserPath -eq $Name } | Select-Object -First 1
    if ($null -eq $parameter) { throw "Optional parameter '$Name' is missing from $([IO.Path]::GetFileName($Path))." }
    $mandatory = $parameter.Attributes |
        Where-Object { $_.TypeName.FullName -eq 'Parameter' } |
        ForEach-Object { $_.NamedArguments } |
        Where-Object { $_.ArgumentName -eq 'Mandatory' -and $_.Argument.Extent.Text -eq '$true' }
    if ($mandatory) { throw "Parameter '$Name' must remain optional for the standalone restore-check workflow." }
}

function Add-FakeProgram {
    param([string] $Name, [string] $ScriptBody)
    $path = Join-Path $fakeTools ($Name + '.ps1')
    [IO.File]::WriteAllText($path, $ScriptBody, [Text.UTF8Encoding]::new($false))
    return $path
}

try {
    Assert-True (Test-Path -LiteralPath $backupPath -PathType Leaf) 'backup.ps1 is missing.'
    Assert-True (Test-Path -LiteralPath $restorePath -PathType Leaf) 'restore-check.ps1 is missing.'

    $backup = Get-Content -LiteralPath $backupPath -Raw
    $restore = Get-Content -LiteralPath $restorePath -Raw
    Assert-RequiredParameter -Path $backupPath -Name 'DatabaseUrl'
    Assert-RequiredParameter -Path $backupPath -Name 'OutputDirectory'
    Assert-RequiredParameter -Path $restorePath -Name 'TestDatabaseUrl'
    Assert-OptionalParameter -Path $restorePath -Name 'BackupFile'

    Assert-Matches $backup '--format=custom' 'Backup must use PostgreSQL custom archive format.'
    Assert-Matches $backup '--no-owner' 'Backup must omit object owners.'
    Assert-Matches $backup '--no-acl' 'Backup must omit ACLs.'
    Assert-Matches $backup '(?i)SHA256|SHA-256' 'Backup must calculate a SHA-256 checksum.'
    Assert-Matches $backup '(?i)flyway_schema_history|FlywayVersion' 'Backup manifest must record the current Flyway migration version.'
    Assert-Matches $backup "PGCONNECT_TIMEOUT\s*=\s*'10'" 'Backup database connection must fail deterministically within the default timeout.'
    Assert-Matches $backup '\[IO\.File\]::Move\(\$temporaryArchivePath,\s*\$archivePath\)' 'Backup publication must use a no-overwrite move into the final timestamped name.'
    Assert-Matches $backup 'CreateNew' 'Temporary manifest files must be created without overwriting existing files.'
    Assert-True ($backup -notmatch '(?i)--password(?:=|\s)') 'Database passwords must not be passed as command-line arguments.'

    Assert-Matches $restore 'AllowProductionRestoreTarget' 'Restore must expose an explicit production-host override switch.'
    Assert-Matches $restore '(?i)(prod|production|prd)' 'Restore must detect production-looking hostnames.'
    Assert-Matches $restore 'CREATE DATABASE' 'Restore must create a new temporary database.'
    Assert-Matches $restore 'DROP DATABASE' 'Restore must clean its temporary database.'
    Assert-Matches $restore '(?i)pg_restore' 'Restore must use pg_restore.'
    Assert-Matches $restore '(?i)flyway.*validate|\.validate\(' 'Restore must run Flyway validation.'
    Assert-Matches $restore '(?i)backup\.ps1' 'Restore without BackupFile must create a fresh archive using the backup script.'
    Assert-Matches $restore 'TestDatabaseUrl' 'Automatic backup source must be the explicit test database URL.'
    Assert-Matches $restore 'SENTINELOPS_BACKUP_DIR' 'Automatic backup directory must support a protected operator-controlled root.'
    Assert-Matches $restore 'backup-restore' 'Automatic backup directory must be unique and scoped to restore validation.'
    Assert-Matches $restore 'chmod|Set-Acl|inherited' 'Automatic backup files must be placed under a permission-protected directory.'
    Assert-Matches $restore 'mvnw(\.cmd)?' 'Flyway validation must use the platform-appropriate Maven wrapper.'
    Assert-Matches $restore '\$javaName\s*=\s*if\s*\(\$IsWindows\)\s*\{\s*''java\.exe''\s*\}\s*else\s*\{\s*''java''\s*\}' 'Java executable selection must support Windows and Unix JDK layouts.'
    Assert-Matches $restore '\$javacName\s*=\s*if\s*\(\$IsWindows\)\s*\{\s*''javac\.exe''\s*\}\s*else\s*\{\s*''javac''\s*\}' 'Java compiler selection must support Windows and Unix JDK layouts.'
    Assert-Matches $restore '(?s)function Test-Java21.*?-version.*?21' 'Java discovery must verify the toolchain is actually Java 21.'
    Assert-Matches $restore '(?i)to_regclass|integrity' 'Restore must run read-only integrity checks.'
    Assert-Matches $restore '(?i)pg_database|datname|databaseOid|DatabaseOid' 'Cleanup must verify the temporary database identity.'
    Assert-True ($restore -notmatch '(?i)--clean(?:\s|$)|--create(?:\s|$)') 'Restore must not ask pg_restore to drop or create/replace a database.'

    $allowSwitch = [regex]::IsMatch($restore, '\[switch\]\s*\$AllowProductionRestoreTarget')
    Assert-True $allowSwitch '-AllowProductionRestoreTarget must be an opt-in switch.'

    $probeRoot = Join-Path ([IO.Path]::GetTempPath()) ('sentinelops-restore-policy-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $probeRoot -Force | Out-Null
    try {
        $credentialSentinel = 'restore-policy-secret-must-not-leak'

        $fakeTools = Join-Path $probeRoot 'bin'
        $backupOutput = Join-Path $probeRoot 'backups'
        New-Item -ItemType Directory -Path $fakeTools, $backupOutput -Force | Out-Null
        $pgDumpShim = @'
param([Parameter(ValueFromRemainingArguments = $true)][string[]] $ClientArguments)
$next = $false
foreach ($argument in $ClientArguments) {
    if ($next) { [IO.File]::WriteAllText($argument, 'policy-dump-bytes'); $next = $false; continue }
    if ($argument -eq '--file') { $next = $true }
}
[IO.File]::WriteAllText($env:SENTINELOPS_POLICY_PG_DUMP_ARGS, ($ClientArguments -join ' '))
$global:LASTEXITCODE = 0
'@
        $psqlShim = @'
param([Parameter(ValueFromRemainingArguments = $true)][string[]] $ClientArguments)
Add-Content -LiteralPath $env:SENTINELOPS_POLICY_PSQL_TARGETS -Value ($env:PGHOST + '/' + $env:PGDATABASE)
Write-Output '19'
$global:LASTEXITCODE = 0
'@
        $pgRestoreShim = @'
param([Parameter(ValueFromRemainingArguments = $true)][string[]] $ClientArguments)
Add-Content -LiteralPath $env:SENTINELOPS_POLICY_PG_RESTORE_ARGS -Value ($ClientArguments -join ' ')
$global:LASTEXITCODE = 43
'@
        $null = Add-FakeProgram -Name 'pg_dump' -ScriptBody $pgDumpShim
        $null = Add-FakeProgram -Name 'psql' -ScriptBody $psqlShim
        $null = Add-FakeProgram -Name 'pg_restore' -ScriptBody $pgRestoreShim
        $oldPath = $env:PATH
        $oldArgsPath = $env:SENTINELOPS_POLICY_PG_DUMP_ARGS
        $oldPsqlTargetsPath = $env:SENTINELOPS_POLICY_PSQL_TARGETS
        $oldRestoreArgsPath = $env:SENTINELOPS_POLICY_PG_RESTORE_ARGS
        $argsPath = Join-Path $probeRoot 'pg-dump-arguments.txt'
        $psqlTargetsPath = Join-Path $probeRoot 'psql-targets.txt'
        $restoreArgsPath = Join-Path $probeRoot 'pg-restore-arguments.txt'
        try {
            $env:PATH = $fakeTools + [IO.Path]::PathSeparator + $oldPath
            $env:SENTINELOPS_POLICY_PG_DUMP_ARGS = $argsPath
            $env:SENTINELOPS_POLICY_PSQL_TARGETS = $psqlTargetsPath
            $env:SENTINELOPS_POLICY_PG_RESTORE_ARGS = $restoreArgsPath
            $backupOutputText = @(& $backupPath -DatabaseUrl "postgresql://policy-user:$credentialSentinel@localhost:5432/sentinelops?sslmode=disable" -OutputDirectory $backupOutput *>&1 | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
        } finally {
            $env:PATH = $oldPath
            if ($null -eq $oldArgsPath) { Remove-Item Env:SENTINELOPS_POLICY_PG_DUMP_ARGS -ErrorAction SilentlyContinue }
            else { $env:SENTINELOPS_POLICY_PG_DUMP_ARGS = $oldArgsPath }
            if ($null -eq $oldPsqlTargetsPath) { Remove-Item Env:SENTINELOPS_POLICY_PSQL_TARGETS -ErrorAction SilentlyContinue }
            else { $env:SENTINELOPS_POLICY_PSQL_TARGETS = $oldPsqlTargetsPath }
            if ($null -eq $oldRestoreArgsPath) { Remove-Item Env:SENTINELOPS_POLICY_PG_RESTORE_ARGS -ErrorAction SilentlyContinue }
            else { $env:SENTINELOPS_POLICY_PG_RESTORE_ARGS = $oldRestoreArgsPath }
        }
        Assert-True ($backupOutputText -notmatch [regex]::Escape($credentialSentinel)) 'Backup output leaked URL credentials.'
        $archives = @(Get-ChildItem -LiteralPath $backupOutput -Filter '*.dump' -File)
        Assert-True ($archives.Count -eq 1) 'Backup did not create exactly one timestamped archive.'
        $archive = $archives[0]
        $backupManifestPath = "$($archive.FullName).manifest.json"
        Assert-True (Test-Path -LiteralPath $backupManifestPath -PathType Leaf) 'Backup did not create its adjacent manifest.'
        $backupManifest = Get-Content -LiteralPath $backupManifestPath -Raw | ConvertFrom-Json
        $archiveHash = (Get-FileHash -LiteralPath $archive.FullName -Algorithm SHA256).Hash
        Assert-True ($backupManifest.sha256 -eq $archiveHash) 'Manifest SHA-256 does not match the generated archive.'
        Assert-True ($backupManifest.flywayVersion -eq '19') 'Manifest did not capture the current Flyway version.'
        $pgDumpArguments = Get-Content -LiteralPath $argsPath -Raw
        foreach ($requiredArgument in @('--format=custom', '--no-owner', '--no-acl')) {
            Assert-True ($pgDumpArguments.Contains($requiredArgument)) "pg_dump did not receive $requiredArgument."
        }
        Assert-True ($pgDumpArguments -notmatch [regex]::Escape($credentialSentinel)) 'pg_dump arguments leaked URL credentials.'
        Assert-True ((Get-Content -LiteralPath $backupManifestPath -Raw) -notmatch [regex]::Escape($credentialSentinel)) 'Backup manifest leaked URL credentials.'

        $automaticBackupRoot = Join-Path $probeRoot 'controlled-backup-root'
        $autoRestoreOutput = ''
        $autoRestoreFailure = ''
        $oldPath = $env:PATH
        $oldArgsPath = $env:SENTINELOPS_POLICY_PG_DUMP_ARGS
        $oldPsqlTargetsPath = $env:SENTINELOPS_POLICY_PSQL_TARGETS
        $oldRestoreArgsPath = $env:SENTINELOPS_POLICY_PG_RESTORE_ARGS
        $oldBackupRoot = $env:SENTINELOPS_BACKUP_DIR
        $autoArgsPath = Join-Path $probeRoot 'automatic-pg-dump-arguments.txt'
        $autoTargetsPath = Join-Path $probeRoot 'automatic-psql-targets.txt'
        $autoRestoreArgsPath = Join-Path $probeRoot 'automatic-pg-restore-arguments.txt'
        try {
            $env:PATH = $fakeTools + [IO.Path]::PathSeparator + $oldPath
            $env:SENTINELOPS_POLICY_PG_DUMP_ARGS = $autoArgsPath
            $env:SENTINELOPS_POLICY_PSQL_TARGETS = $autoTargetsPath
            $env:SENTINELOPS_POLICY_PG_RESTORE_ARGS = $autoRestoreArgsPath
            $env:SENTINELOPS_BACKUP_DIR = $automaticBackupRoot
            try {
                $autoRestoreOutput = @(& $restorePath -TestDatabaseUrl "postgresql://policy-user:$credentialSentinel@localhost:5432/restore_control?sslmode=disable" *>&1 | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
            } catch {
                $autoRestoreFailure = $_.Exception.Message
            }
        } finally {
            $env:PATH = $oldPath
            if ($null -eq $oldArgsPath) { Remove-Item Env:SENTINELOPS_POLICY_PG_DUMP_ARGS -ErrorAction SilentlyContinue }
            else { $env:SENTINELOPS_POLICY_PG_DUMP_ARGS = $oldArgsPath }
            if ($null -eq $oldPsqlTargetsPath) { Remove-Item Env:SENTINELOPS_POLICY_PSQL_TARGETS -ErrorAction SilentlyContinue }
            else { $env:SENTINELOPS_POLICY_PSQL_TARGETS = $oldPsqlTargetsPath }
            if ($null -eq $oldRestoreArgsPath) { Remove-Item Env:SENTINELOPS_POLICY_PG_RESTORE_ARGS -ErrorAction SilentlyContinue }
            else { $env:SENTINELOPS_POLICY_PG_RESTORE_ARGS = $oldRestoreArgsPath }
            if ($null -eq $oldBackupRoot) { Remove-Item Env:SENTINELOPS_BACKUP_DIR -ErrorAction SilentlyContinue }
            else { $env:SENTINELOPS_BACKUP_DIR = $oldBackupRoot }
        }
        $automaticBackups = @(Get-ChildItem -LiteralPath $automaticBackupRoot -Directory -Filter 'restore-check-*')
        Assert-True ($automaticBackups.Count -eq 1) 'No-BackupFile restore did not create a new scoped backup directory.'
        $automaticArchives = @(Get-ChildItem -LiteralPath $automaticBackups[0].FullName -Filter 'sentinelops-*.dump' -File)
        Assert-True ($automaticArchives.Count -eq 1) 'No-BackupFile restore did not create a fresh custom archive from the explicit test target.'
        Assert-True (Test-Path -LiteralPath "$($automaticArchives[0].FullName).manifest.json" -PathType Leaf) 'Automatic test backup omitted its SHA-256 manifest.'
        $autoTargets = Get-Content -LiteralPath $autoTargetsPath -Raw
        Assert-True ($autoTargets -match 'localhost/restore_control') 'Automatic backup did not connect to the explicit TestDatabaseUrl source.'
        Assert-True ($autoRestoreFailure -match '(?i)pg_restore could not read the backup archive') 'Restore did not proceed from automatic backup creation to archive preflight.'
        Assert-True (($autoRestoreOutput + $autoRestoreFailure) -notmatch [regex]::Escape($credentialSentinel)) 'Automatic backup/restore output leaked URL credentials.'

        $prodUrl = "postgresql://policy-user:$credentialSentinel@production-db.example.invalid:5432/sentinelops"
        $rejection = $null
        try {
            & $restorePath -TestDatabaseUrl $prodUrl 2>&1 | Out-Null
        } catch {
            $rejection = $_.Exception.Message
        }
        Assert-True ($rejection -match '(?i)production') 'Production-looking restore target was not rejected without the override.'
        Assert-True ($rejection -notmatch [regex]::Escape($credentialSentinel)) 'Production-target error leaked URL credentials.'

        $overrideResult = $null
        try {
            & $restorePath -TestDatabaseUrl $prodUrl -AllowProductionRestoreTarget 2>&1 | Out-Null
        } catch {
            $overrideResult = $_.Exception.Message
        }
        Assert-True ($overrideResult -notmatch '(?i)production') 'Explicit override did not pass the production-host policy gate.'
        Assert-True ($overrideResult -notmatch '(?i)parameter.*BackupFile.*mandatory|missing.*BackupFile') 'BackupFile was still required after the production-host override.'
        Assert-True ($overrideResult -notmatch [regex]::Escape($credentialSentinel)) 'Restore preflight error leaked URL credentials.'
    } finally {
        if (Test-Path -LiteralPath $probeRoot -PathType Container) {
            $resolvedProbe = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $probeRoot).Path)
            $safePrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
            if (-not $resolvedProbe.StartsWith($safePrefix, [StringComparison]::OrdinalIgnoreCase)) {
                throw "Refusing to remove policy test directory outside the temp root: $resolvedProbe"
            }
            Remove-Item -LiteralPath $resolvedProbe -Recurse -Force
        }
    }

    Write-Output 'PASS backup and restore policy checks.'
} catch {
    Write-Error -ErrorRecord $_
    exit 1
}
