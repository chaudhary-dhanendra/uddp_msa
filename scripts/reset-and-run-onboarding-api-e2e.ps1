<#
.SYNOPSIS
  Proves /api/onboarding end to end with the REAL generic Hop pipeline and the REAL Python/Splink matching, on a
  throwaway PostgreSQL 16: every State Pack in -StatePackDirectory is registered, validated, activated and onboarded
  through the REST API only (the first FULL, every later one INCREMENTAL). Asserts raw=7, canonical=7, golden=5
  (active Golden identities) for the three sample states, and that no stored failure text contains the password.

.DESCRIPTION
  Never touches your UDDP_POSTGRES_* database: a disposable postgres:16-alpine container is started on a random
  localhost port with a random password (never printed). The backend receives the settings through its environment
  and hands them to Hop through a temporary JVM argument file (never echoed) and to Python through the environment.

  Needs: HOP_HOME (Apache Hop 2.x, with this repository registered as a Hop project), Docker, Java 21, Maven,
  Python 3.12 with matching/requirements.txt installed.
#>
param(
    [string]$StatePackDirectory = 'state-packs',
    [int]$ExpectRaw = 7,
    [int]$ExpectCanonical = 7,
    [int]$ExpectGolden = 5,
    [switch]$KeepDatabase
)
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'e2e/uddp-api.ps1')
$core = Join-Path $root 'common-core'
$packs = (Resolve-Path (Join-Path $root $StatePackDirectory)).Path

function Fail([string]$message) {
    Write-Host "ONBOARDING API E2E FAILED: $message" -ForegroundColor Red
    exit 1
}

# ---- 1. Prerequisites -------------------------------------------------------------------------------
$hopHome = $env:HOP_HOME
if (-not $hopHome) { $hopHome = [Environment]::GetEnvironmentVariable('HOP_HOME', 'User') }
if (-not $hopHome -or -not (Test-Path (Join-Path $hopHome 'hop-run.bat'))) {
    Fail 'HOP_HOME must point at an Apache Hop installation containing hop-run.bat.'
}
& docker info --format '{{.ServerVersion}}' *> $null
if ($LASTEXITCODE -ne 0) { Fail 'Docker is not running. Start Docker Desktop.' }
$hopProject = & (Join-Path $PSScriptRoot 'e2e/find-hop-project.ps1') -HopHome $hopHome -Root $root
if (-not $hopProject) { Fail "No Hop project has its home at $root (see docs/dev-setup.md)." }

# ---- 2. Throwaway PostgreSQL 16 -----------------------------------------------------------------------
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$container = "uddp-api-e2e-$suffix"
$database = 'uddp_e2e'
$user = 'uddp_e2e'
$bytes = New-Object byte[] 24
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$env:POSTGRES_PASSWORD = -join ($bytes | ForEach-Object { $_.ToString('x2') })
$app = $null
$auditFolder = Join-Path ([IO.Path]::GetTempPath()) "uddp-api-e2e-hop-audit-$suffix"
$settingsFolder = Join-Path ([IO.Path]::GetTempPath()) "uddp-api-e2e-settings-$suffix"

function Psql([string]$sql) {
    $out = & docker exec $container psql -X -q -t -A -v ON_ERROR_STOP=1 -U $user -d $database -c $sql
    if ($LASTEXITCODE -ne 0) { Fail "SQL failed: $sql" }
    return ($out | Out-String).Trim()
}

function Api([string]$method, [string]$path, $body) {
    $arguments = @{ Method = $method; Uri = "$base$path"; TimeoutSec = 900 }
    if ($null -ne $body) {
        $arguments.ContentType = 'application/json'
        $arguments.Body = ($body | ConvertTo-Json -Compress)
    }
    return Invoke-UddpApi @arguments
}

try {
    Write-Host "Starting throwaway PostgreSQL 16 container $container ..."
    & docker run -d --rm --name $container -e POSTGRES_PASSWORD -e "POSTGRES_USER=$user" -e "POSTGRES_DB=$database" `
        -p 127.0.0.1::5432 postgres:16-alpine | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail 'docker run postgres:16-alpine failed.' }
    $ready = $false
    for ($i = 0; $i -lt 60 -and -not $ready; $i++) {
        & docker exec $container pg_isready -q -h 127.0.0.1 -U $user -d $database *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true } else { Start-Sleep -Seconds 1 }
    }
    if (-not $ready) { Fail 'PostgreSQL did not become ready.' }
    $port = ((& docker port $container 5432/tcp) | Select-Object -First 1).Split(':')[-1]

    # UDDP_POSTGRES_* for this process and its children only; the backend migrates the empty database at startup.
    Remove-Item Env:UDDP_POSTGRES_URL -ErrorAction SilentlyContinue
    $env:UDDP_POSTGRES_HOST = '127.0.0.1'
    $env:UDDP_POSTGRES_PORT = $port
    $env:UDDP_POSTGRES_DB = $database
    $env:UDDP_POSTGRES_USER = $user
    $env:UDDP_POSTGRES_PASSWORD = $env:POSTGRES_PASSWORD
    $env:UDDP_HOP_PROJECT = $hopProject
    $env:HOP_AUDIT_FOLDER = $auditFolder
    $env:HOP_HOME = $hopHome

    # ---- 3. Build and start Common Core -----------------------------------------------------------------
    Write-Host 'Building Common Core ...'
    Push-Location $core
    try {
        & mvn -q -B -DskipTests compile dependency:build-classpath "-Dmdep.outputFile=target/classpath.txt" "-Dmdep.includeScope=runtime"
        if ($LASTEXITCODE -ne 0) { Fail 'Maven build failed.' }
    } finally { Pop-Location }
    $classpath = (Join-Path $core 'target/classes') + [IO.Path]::PathSeparator + (Get-Content (Join-Path $core 'target/classpath.txt') -Raw).Trim()
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start(); $appPort = $listener.LocalEndpoint.Port; $listener.Stop()
    $appLog = Join-Path ([IO.Path]::GetTempPath()) "uddp-api-e2e-app-$suffix.log"
    Write-Host "Starting Common Core on port $appPort (log $appLog) ..."
    $app = Start-Process java -PassThru -NoNewWindow -WorkingDirectory $core -RedirectStandardOutput $appLog `
        -RedirectStandardError "$appLog.err" `
        -ArgumentList @('-cp', "`"$classpath`"", 'gov.uddp.core.UddpCommonCoreApplication', "--server.port=$appPort",
            "--uddp.state-pack-directory=$packs", '--uddp.fixture-state-packs-enabled=true',
            "--uddp.settings-directory=$settingsFolder")
    $base = "http://127.0.0.1:$appPort"
    $up = $false
    for ($i = 0; $i -lt 120 -and -not $up; $i++) {
        try { Invoke-RestMethod -ErrorAction Stop "$base/health" -TimeoutSec 2 | Out-Null; $up = $true } catch { Start-Sleep -Seconds 1 }
        if ($app.HasExited) { Fail "Common Core exited during startup; see $appLog" }
    }
    if (-not $up) { Fail "Common Core did not start; see $appLog" }
    # P1: every API call is signed in; the first administrator of this throwaway database has a random password.
    Connect-UddpApi $base | Out-Null

    # ---- 4. Onboard every pack through the API only -------------------------------------------------------
    $manifests = Get-ChildItem -LiteralPath $packs -Directory | Sort-Object Name |
        ForEach-Object { Join-Path $_.FullName 'state.yaml' } | Where-Object { Test-Path $_ }
    $first = $true
    foreach ($manifest in $manifests) {
        $text = Get-Content $manifest -Raw
        $packId = ([regex]::Match($text, '(?m)^packId:\s*(\S+)')).Groups[1].Value
        $version = ([regex]::Match($text, '(?m)^version:\s*(\S+)')).Groups[1].Value
        $sourceFiles = ([regex]::Match($text, '(?m)^sources:\s*\[(.*)\]')).Groups[1].Value.Split(',') |
            ForEach-Object { $_.Trim() } | Where-Object { $_ }
        Api POST "/api/onboarding/state-packs/$packId/register" $null | Out-Null
        Api POST "/api/onboarding/state-packs/$packId/$version/transitions/VALIDATED" $null | Out-Null
        Api POST "/api/onboarding/state-packs/$packId/$version/transitions/ACTIVE" $null | Out-Null
        foreach ($sourceFile in $sourceFiles) {
            $sourceId = ([regex]::Match((Get-Content (Join-Path (Split-Path $manifest) $sourceFile) -Raw),
                '(?m)^sourceId:\s*(\S+)')).Groups[1].Value
            $processing = if ($first) { 'FULL' } else { 'INCREMENTAL' }
            Write-Host "Onboarding $packId/$sourceId ($processing) through the API ..."
            try {
                $run = Api POST '/api/onboarding/runs' @{ packId = $packId; version = $version; sourceSystem = $sourceId;
                    mode = 'FULL'; processingMode = $processing }
            } catch {
                Fail "Run for $packId/$sourceId failed: $($_.ErrorDetails.Message)"
            }
            $detail = Api GET "/api/onboarding/runs/$($run.runId)" $null
            if ($detail.status -ne 'COMPLETED') { Fail "Run $($run.runId) ended $($detail.status): $($detail.failureMessage)" }
            foreach ($stage in $detail.stages) {
                Write-Host ("  {0,-15} {1,-10} in={2} out={3}" -f $stage.stage, $stage.status, $stage.inputCount, $stage.outputCount)
            }
            $first = $false
        }
    }

    # ---- 5. Assertions ----------------------------------------------------------------------------------
    $raw = [int](Psql 'select count(*) from raw_record')
    $canonical = [int](Psql 'select count(*) from canonical_resident')
    $golden = [int](Psql "select count(*) from golden_resident where status='ACTIVE'")
    $auto = [int](Psql "select count(*) from resident_match_pair where decision='AUTO_MATCH'")
    $families = [int](Psql "select count(*) from golden_family where status='ACTIVE'")
    $benefits = [int](Psql 'select count(*) from benefit')
    $leaks = [int](Psql ("select count(*) from (select failure_message m from onboarding_run union all" +
        " select error_message from onboarding_stage_execution union all select error_message from ingestion_batch) x" +
        " where m like '%$($env:POSTGRES_PASSWORD)%'"))
    Write-Host "  raw records         $raw"
    Write-Host "  canonical residents $canonical"
    Write-Host "  Golden residents    $golden"
    Write-Host "  AUTO_MATCH pairs    $auto (across all runs)"
    Write-Host "  Golden families     $families"
    Write-Host "  benefits           $benefits"
    $problems = @()
    if ($raw -ne $ExpectRaw) { $problems += "raw=$raw, expected $ExpectRaw" }
    if ($canonical -ne $ExpectCanonical) { $problems += "canonical=$canonical, expected $ExpectCanonical" }
    if ($golden -ne $ExpectGolden) { $problems += "golden=$golden, expected $ExpectGolden" }
    if ($leaks -ne 0) { $problems += 'a stored failure message contains the database password' }
    if ($problems) { Fail ($problems -join '; ') }
    Write-Host "ONBOARDING API E2E PASSED: raw=$raw canonical=$canonical golden=$golden (real Hop and Splink)" -ForegroundColor Green
} catch {
    Fail "$($_.Exception.Message)"
} finally {
    if ($app -and -not $app.HasExited) { Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue }
    if ($KeepDatabase) {
        Write-Host "Keeping container $container (remove with: docker rm -f $container)"
    } else {
        & docker rm -f $container *> $null
    }
    Remove-Item Env:POSTGRES_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:UDDP_POSTGRES_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $auditFolder -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $settingsFolder -ErrorAction SilentlyContinue
}
