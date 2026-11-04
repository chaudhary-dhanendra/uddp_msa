<#
.SYNOPSIS
  Brings the local UDDP_POSTGRES_* database to the M11 schema and a clean 7 -> 5 demo state with stable Golden
  identities, after taking a verified backup.

.DESCRIPTION
  1. Prints the current counts.
  2. Backs the database up with pg_dump (custom format) into backups/ (gitignored) and checks the dump with
     pg_restore --list. Nothing else runs if the backup fails.
  3. Applies pending Flyway migrations (V10 turns existing per-run Golden rows into stable identities) and validates.
  4. Starts Common Core on a free localhost port and rebuilds the current Golden set from the latest completed
     matching run (a full Splink run is made first if there is none), under the platform survivorship policy.
     Carry-forward keeps every Golden ID; a policy change only adds versions.
  5. Prints the counts again and fails unless Raw, current canonical and active Golden match the expected demo.

  Nothing is deleted or updated outside what Flyway and the Golden build do. Connection settings come only from the
  UDDP_POSTGRES_* variables; the password is passed to pg_dump through PGPASSWORD for this process only and is never
  printed. Since P1 the Golden build is a signed-in API call: pass -Username (a user with ONBOARDING_RUN, for
  example your platform administrator); the password is asked for and kept in memory only. Needs Java 21, Maven, Python with matching/requirements.txt and the PostgreSQL client tools (pg_dump,
  pg_restore, psql) on PATH or in PG_BIN.
#>
param(
    [Parameter(Mandatory = $true)][string]$Username,
    [int]$ExpectRaw = 7,
    [int]$ExpectCanonical = 7,
    [int]$ExpectGolden = 5
)
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'e2e/uddp-api.ps1')
$securePassword = Read-Host -AsSecureString "Password for $Username"
$core = Join-Path $root 'common-core'

function Fail([string]$message) {
    Write-Host "CLEAN-LOCAL-GOLDEN FAILED: $message" -ForegroundColor Red
    exit 1
}

function Tool([string]$name) {
    if ($env:PG_BIN -and (Test-Path (Join-Path $env:PG_BIN "$name.exe"))) { return (Join-Path $env:PG_BIN "$name.exe") }
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if (-not $command) { Fail "$name not found on PATH; install the PostgreSQL client tools or set PG_BIN." }
    return $command.Source
}

foreach ($name in 'UDDP_POSTGRES_HOST', 'UDDP_POSTGRES_PORT', 'UDDP_POSTGRES_DB', 'UDDP_POSTGRES_USER', 'UDDP_POSTGRES_PASSWORD') {
    $value = [Environment]::GetEnvironmentVariable($name, 'Process')
    if (-not $value) { $value = [Environment]::GetEnvironmentVariable($name, 'User') }
    if (-not $value) { Fail "$name is not set." }
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
$pgDump = Tool 'pg_dump'
$pgRestore = Tool 'pg_restore'
$psql = Tool 'psql'
$connection = @('-h', $env:UDDP_POSTGRES_HOST, '-p', $env:UDDP_POSTGRES_PORT, '-U', $env:UDDP_POSTGRES_USER)
$env:PGPASSWORD = $env:UDDP_POSTGRES_PASSWORD
$app = $null

function Psql([string]$sql) {
    $out = & $psql -X -q -t -A -v ON_ERROR_STOP=1 @connection -d $env:UDDP_POSTGRES_DB -c $sql
    if ($LASTEXITCODE -ne 0) { Fail "SQL failed: $sql" }
    return ($out | Out-String).Trim()
}

function Counts([string]$title) {
    $hasLifecycle = (Psql "select to_regclass('public.golden_resident_version') is not null") -eq 't'
    Write-Host $title
    Write-Host ("  flyway version          " + (Psql "select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1"))
    Write-Host ("  raw_record              " + (Psql 'select count(*) from raw_record'))
    Write-Host ("  canonical_resident      " + (Psql 'select count(*) from canonical_resident'))
    Write-Host ("  matching_run            " + (Psql 'select count(*) from matching_run'))
    Write-Host ("  golden_resident rows    " + (Psql 'select count(*) from golden_resident'))
    if ($hasLifecycle) {
        Write-Host ("  current canonical       " + (Psql 'select count(*) from canonical_resident_current'))
        Write-Host ("  active Golden           " + (Psql "select count(*) from golden_resident where status='ACTIVE'"))
        Write-Host ("  retired Golden          " + (Psql "select count(*) from golden_resident where status='RETIRED'"))
        Write-Host ("  Golden versions         " + (Psql 'select count(*) from golden_resident_version'))
        Write-Host ("  current memberships     " + (Psql 'select count(*) from golden_resident_membership where effective_to is null'))
    }
}

try {
    # ---- 1. Before ----------------------------------------------------------------------------------------
    Counts 'Before'

    # ---- 2. Verified backup -------------------------------------------------------------------------------
    $backups = Join-Path $root 'backups'
    New-Item -ItemType Directory -Force -Path $backups | Out-Null
    $backup = Join-Path $backups ("{0}-{1}.dump" -f $env:UDDP_POSTGRES_DB, (Get-Date -Format 'yyyyMMdd-HHmmss'))
    Write-Host "Backing up to $backup ..."
    & $pgDump @connection -d $env:UDDP_POSTGRES_DB -Fc -f $backup
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $backup) -or (Get-Item $backup).Length -eq 0) { Fail 'pg_dump failed; nothing was changed.' }
    $entries = & $pgRestore --list $backup
    if ($LASTEXITCODE -ne 0 -or -not ($entries -match 'TABLE DATA public golden_resident')) {
        Fail "The backup at $backup could not be verified; nothing was changed."
    }
    Write-Host ("Backup verified: {0:N0} bytes, {1} entries" -f (Get-Item $backup).Length, @($entries | Where-Object { $_ -notmatch '^;' }).Count)

    # ---- 3. Flyway ----------------------------------------------------------------------------------------
    Push-Location $core
    try {
        & mvn -q -B -DskipTests compile dependency:build-classpath "-Dmdep.outputFile=target/classpath.txt" "-Dmdep.includeScope=runtime"
        if ($LASTEXITCODE -ne 0) { Fail 'Maven build failed.' }
    } finally { Pop-Location }
    $classpath = (Join-Path $core 'target/classes') + [IO.Path]::PathSeparator + (Get-Content (Join-Path $core 'target/classpath.txt') -Raw).Trim()
    Remove-Item Env:UDDP_POSTGRES_URL -ErrorAction SilentlyContinue
    & java -cp $classpath gov.uddp.core.database.DatabaseMigrationCommand migrate
    if ($LASTEXITCODE -ne 0) { Fail "Flyway migrate failed; restore with: pg_restore --clean -d <db> $backup" }
    & java -cp $classpath gov.uddp.core.database.DatabaseMigrationCommand validate
    if ($LASTEXITCODE -ne 0) { Fail 'Flyway validate failed.' }

    # ---- 4. Rebuild the current Golden set ----------------------------------------------------------------
    $runId = Psql "select run_id from matching_run where status='COMPLETED' and mode='FULL' order by completed_at desc nulls last limit 1"
    if (-not $runId) {
        Write-Host 'No completed full matching run; running Splink ...'
        Push-Location $root
        try {
            $output = & python -m matching.run
            if ($LASTEXITCODE -ne 0) { Fail 'Matching failed.' }
        } finally { Pop-Location }
        $runId = (($output | Select-Object -Last 1) | ConvertFrom-Json).run_id
    }
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start(); $appPort = $listener.LocalEndpoint.Port; $listener.Stop()
    $appLog = Join-Path ([IO.Path]::GetTempPath()) "uddp-clean-local-golden-$appPort.log"
    Write-Host "Starting Common Core on port $appPort ..."
    $app = Start-Process java -PassThru -NoNewWindow -WorkingDirectory $core -RedirectStandardOutput $appLog `
        -RedirectStandardError "$appLog.err" `
        -ArgumentList @('-cp', "`"$classpath`"", 'gov.uddp.core.UddpCommonCoreApplication', "--server.port=$appPort",
            '--uddp.fixture-state-packs-enabled=true')
    $base = "http://127.0.0.1:$appPort"
    $up = $false
    for ($i = 0; $i -lt 90 -and -not $up; $i++) {
        try { Invoke-RestMethod -ErrorAction Stop "$base/health" -TimeoutSec 2 | Out-Null; $up = $true } catch { Start-Sleep -Seconds 1 }
        if ($app.HasExited) { Fail "Common Core exited during startup; see $appLog" }
    }
    if (-not $up) { Fail "Common Core did not start; see $appLog" }
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword))
    try {
        Connect-UddpApi $base $Username $plain | Out-Null
    } catch {
        Fail "Cannot sign in as $Username (create users in the UI first; the account must not need a password change)."
    } finally {
        $plain = $null
    }
    $build = Invoke-UddpApi -Method Post "$base/api/golden-residents/build" -ContentType 'application/json' `
        -Body (@{ matchingRunId = $runId } | ConvertTo-Json)
    Write-Host ("Golden build {0} from run {1} ({2}): created={3} reused={4} versioned={5} unchanged={6} retired={7}" -f
        $build.goldenBuildId, $runId, $build.policyVersion, $build.created, $build.reused, $build.versioned, $build.unchanged, $build.retired)

    # ---- 5. After -----------------------------------------------------------------------------------------
    Counts 'After'
    $raw = [int](Psql 'select count(*) from raw_record')
    $canonical = [int](Psql 'select count(*) from canonical_resident_current')
    $golden = [int](Psql "select count(*) from golden_resident where status='ACTIVE'")
    $withoutVersion = [int](Psql ("select count(*) from golden_resident g where g.status='ACTIVE' and not exists" +
        " (select 1 from golden_resident_version v where v.golden_resident_id=g.golden_resident_id and v.effective_to is null)"))
    $problems = @()
    if ($raw -ne $ExpectRaw) { $problems += "raw=$raw, expected $ExpectRaw" }
    if ($canonical -ne $ExpectCanonical) { $problems += "current canonical=$canonical, expected $ExpectCanonical" }
    if ($golden -ne $ExpectGolden) { $problems += "active Golden=$golden, expected $ExpectGolden" }
    if ($withoutVersion -ne 0) { $problems += "$withoutVersion active Golden identities have no current version" }
    if ($build.created -ne 0) { $problems += "the rebuild created $($build.created) new Golden IDs instead of keeping the existing ones" }
    if ($problems) { Fail (($problems -join '; ') + ". The backup is $backup") }
    Write-Host "CLEAN-LOCAL-GOLDEN PASSED: raw=$raw canonical=$canonical golden=$golden; backup $backup" -ForegroundColor Green
} finally {
    if ($app -and -not $app.HasExited) { Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue }
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}
