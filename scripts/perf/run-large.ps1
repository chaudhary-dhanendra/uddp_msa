<#
.SYNOPSIS
  Large-volume run on a throwaway PostgreSQL 16: generic Hop ingest -> canonical processing -> Splink matching
  -> Golden build, printing wall time per stage and peak memory (Java + Python + Hop + the database container).
  Target: 200,000 rows end to end under 30 minutes on a 16 GB laptop (actual numbers go in docs/perf.md).

.DESCRIPTION
  Data: -InputFile takes a ration-members CSV in the state-1 PDS layout (for example M_Ration_Members_Large.csv) and
  runs the state-1 pack alone. Without it, matching/synth.py generates -Rows synthetic rows across the three sample
  states (seeded, no real personal data) and the three packs run against the generated files. The packs are copied
  to a temporary folder with only the source location rewritten; no state-specific code is involved.
  Never touches your UDDP_POSTGRES_* database; the container and the temporary files are removed at the end.
  Needs: HOP_HOME (this repository registered as a Hop project), Docker, Java 21, Maven, Python 3.12 with
  matching/requirements.txt installed. Blocking and DuckDB limits come from matching/resident.yaml
  (override the threshold with -BlockingThreshold, i.e. UDDP_MATCHING_BLOCKING_THRESHOLD).
#>
param(
    [int]$Rows = 200000,
    [string]$InputFile,
    [int]$Seed = 42,
    [int]$BlockingThreshold = 0,
    [switch]$KeepDatabase
)
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$core = Join-Path $root 'common-core'
. (Join-Path $root 'scripts/e2e/uddp-api.ps1')

function Fail([string]$message) { Write-Host "PERF RUN FAILED: $message" -ForegroundColor Red; exit 1 }
function Need([string]$command, [string]$hint) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { Fail "$command not found on PATH. $hint" }
}

$hopHome = $env:HOP_HOME
if (-not $hopHome) { $hopHome = [Environment]::GetEnvironmentVariable('HOP_HOME', 'User') }
if (-not $hopHome -or -not (Test-Path (Join-Path $hopHome 'hop-run.bat'))) { Fail 'HOP_HOME must point at Apache Hop (the folder containing hop-run.bat).' }
$hopRun = Join-Path $hopHome 'hop-run.bat'
Need docker 'Install and start Docker Desktop.'; Need java 'Install Java 21.'; Need mvn 'Install Maven.'; Need python 'Install Python 3.12.'
& docker info --format '{{.ServerVersion}}' *> $null
if ($LASTEXITCODE -ne 0) { Fail 'Docker is not running.' }
$hopProject = $env:UDDP_HOP_PROJECT
if (-not $hopProject) {
    $configFolder = if ($env:HOP_CONFIG_FOLDER) { $env:HOP_CONFIG_FOLDER } else { Join-Path $hopHome 'config' }
    $hopConfig = Join-Path $configFolder 'hop-config.json'
    if (Test-Path $hopConfig) {
        foreach ($project in (Get-Content $hopConfig -Raw | ConvertFrom-Json).projectsConfig.projectConfigurations) {
            $home_ = $project.projectHome
            if (-not [IO.Path]::IsPathRooted($home_)) { $home_ = Join-Path $hopHome $home_ }
            if ((Test-Path $home_) -and ((Resolve-Path $home_).Path.TrimEnd('\') -ieq $root.TrimEnd('\'))) { $hopProject = $project.projectName }
        }
    }
}
if (-not $hopProject) { Fail 'No Hop project has its home at this repository (see scripts/reset-and-run-e2e.ps1 for the registration command).' }

$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$work = Join-Path ([IO.Path]::GetTempPath()) "uddp-perf-$suffix"
New-Item -ItemType Directory -Path $work | Out-Null
$container = "uddp-perf-$suffix"; $database = 'uddp_perf'; $user = 'uddp_perf'
$bytes = New-Object byte[] 24
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$env:POSTGRES_PASSWORD = -join ($bytes | ForEach-Object { $_.ToString('x2') })
$app = $null; $sampler = $null
$stages = [ordered]@{}

function Psql([string]$sql) {
    $out = & docker exec $container psql -X -q -t -A -v ON_ERROR_STOP=1 -U $user -d $database -c $sql
    if ($LASTEXITCODE -ne 0) { Fail "SQL failed: $sql" }
    return ($out | Out-String).Trim()
}
function Stage([string]$name, [scriptblock]$body) {
    Write-Host "== $name"
    $started = Get-Date
    & $body
    $stages[$name] = @{ Start = $started; End = Get-Date }
    Write-Host ("   {0:N1} s" -f ($stages[$name].End - $started).TotalSeconds)
}

try {
    # ---- data and packs ----
    $packs = Join-Path $work 'packs'
    New-Item -ItemType Directory -Path $packs | Out-Null
    $files = @{}
    if ($InputFile) {
        $files['state-1'] = (Resolve-Path $InputFile).Path
    } else {
        Stage 'generate' {
            Push-Location $root
            try { & python -m matching.synth --size $Rows --seed $Seed --out (Join-Path $work 'data') | Out-Host } finally { Pop-Location }
            if ($LASTEXITCODE -ne 0) { Fail 'matching.synth failed.' }
        }
        $names = @{ 'state-1' = 'pds.csv'; 'state-2' = 'pension.csv'; 'state-3' = 'beneficiaries.csv' }
        foreach ($state in $names.Keys) { $files[$state] = Join-Path $work "data/$state/$($names[$state])" }
        Copy-Item $files['state-1'] (Join-Path $work 'M_Ration_Members_Large.csv')
    }
    $rowCount = 0
    foreach ($state in $files.Keys) {
        Copy-Item -Recurse (Join-Path $root "state-packs/$state") $packs
        foreach ($source in Get-ChildItem (Join-Path $packs "$state/sources/*.yaml")) {
            $text = (Get-Content $source -Raw) -replace '(?m)^location:.*$', ('location: ' + ($files[$state] -replace '\\', '/'))
            if ($text -notmatch '(?m)^recordKeyFields:' -and $text -match '(?m)^recordIdField:\s*(\S+)') {
                # Generated households have several members per family number: key a row by family number + first column (name).
                $firstColumn = ((Get-Content $files[$state] -TotalCount 1) -split ',')[0]
                $text = $text.TrimEnd() + "`nrecordKeyFields: [$($Matches[1]), $firstColumn]`n"
            }
            Set-Content -Path $source -Value $text -Encoding utf8
        }
        # The generator's ten districts (code and name) all resolve, so rows are not rejected for sample-only crosswalks.
        $districts = @{ '101' = 'Patna'; '102' = 'Gaya'; '103' = 'Nalanda'; '104' = 'Ranchi'; '105' = 'Pune'; '106' = 'Chennai'; '107' = 'Kolkata'; '108' = 'Jaipur'; '109' = 'Lucknow'; '110' = 'Amritsar' }
        $entries = foreach ($code in $districts.Keys) { $lgd = "'1000{0:D2}'" -f ([int]$code - 100); "'$code': $lgd"; "'$($districts[$code])': $lgd" }
        Set-Content -Path (Join-Path $packs "$state/crosswalks/district.yaml") -Value ("id: district`nvalues: { " + ($entries -join ', ') + " }") -Encoding utf8
        $rowCount += (Get-Content $files[$state] | Measure-Object -Line).Lines - 1
    }
    Write-Host "Source rows: $rowCount"

    # ---- throwaway database, memory sampler ----
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
    Remove-Item Env:UDDP_POSTGRES_URL -ErrorAction SilentlyContinue
    $env:UDDP_POSTGRES_HOST = '127.0.0.1'; $env:UDDP_POSTGRES_PORT = $port; $env:UDDP_POSTGRES_DB = $database
    $env:UDDP_POSTGRES_USER = $user; $env:UDDP_POSTGRES_PASSWORD = $env:POSTGRES_PASSWORD
    $env:UDDP_STATE_PACK_DIRECTORY = $packs
    if ($BlockingThreshold -gt 0) { $env:UDDP_MATCHING_BLOCKING_THRESHOLD = "$BlockingThreshold" }

    $memoryLog = Join-Path $work 'memory.csv'
    $sampler = Start-Job -ArgumentList $memoryLog, $container -ScriptBlock {
        param($log, $name)
        while ($true) {
            $processes = [long](Get-Process java, javaw, python -ErrorAction SilentlyContinue | Measure-Object WorkingSet64 -Sum).Sum
            $database = 0
            $usage = (& docker stats --no-stream --format '{{.MemUsage}}' $name 2>$null | Select-Object -First 1)
            if ($usage -match '^([\d.]+)\s*(KiB|MiB|GiB)') {
                $database = [double]$Matches[1] * @{ KiB = 1KB; MiB = 1MB; GiB = 1GB }[$Matches[2]]
            }
            "{0},{1}" -f (Get-Date).ToString('o'), [math]::Round(($processes + $database) / 1MB) | Add-Content $log
            Start-Sleep -Seconds 3
        }
    }

    Stage 'build + migrate' {
        Push-Location $core
        try { & mvn -q -B -DskipTests compile dependency:build-classpath "-Dmdep.outputFile=target/classpath.txt" "-Dmdep.includeScope=runtime" } finally { Pop-Location }
        if ($LASTEXITCODE -ne 0) { Fail 'Maven build failed.' }
        $script:classpath = (Join-Path $core 'target/classes') + [IO.Path]::PathSeparator + (Get-Content (Join-Path $core 'target/classpath.txt') -Raw).Trim()
        & java -cp $script:classpath gov.uddp.core.database.DatabaseMigrationCommand migrate | Out-Null
        if ($LASTEXITCODE -ne 0) { Fail 'Flyway migrate failed.' }
    }

    $batches = @()
    Stage 'ingest (Hop)' {
        $hopArgFile = Join-Path $work 'hop.args'
        @('UDDP_POSTGRES_HOST', 'UDDP_POSTGRES_PORT', 'UDDP_POSTGRES_DB', 'UDDP_POSTGRES_USER', 'UDDP_POSTGRES_PASSWORD') |
            ForEach-Object { "-D$_=" + [Environment]::GetEnvironmentVariable($_, 'Process') } | Set-Content -Path $hopArgFile -Encoding ascii
        $env:HOP_AUDIT_FOLDER = Join-Path $work 'hop-audit'
        foreach ($line in (& python (Join-Path $root 'scripts/e2e/list_sources.py') $packs $root)) {
            $s = $line | ConvertFrom-Json
            $batch = [Guid]::NewGuid().ToString()
            Psql ("insert into ingestion_batch(batch_id,state_code,source_system,source_type,source_schema_version," +
                "state_pack_version,pipeline_template,status) values('$batch','$($s.stateCode)','$($s.sourceId)'," +
                "'$($s.sourceType)','$($s.sourceSchemaVersion)','$($s.statePackVersion)','$($s.hopTemplate)','STARTED')") | Out-Null
            $parameters = @("SOURCE_FILE=$($s.sourceFile)", "CSV_HEADER=$($s.csvHeader)", "STATE_CODE=$($s.stateCode)",
                "SOURCE_SYSTEM=$($s.sourceId)", "SOURCE_ENTITY=$($s.sourceId)",
                "SOURCE_SCHEMA_VERSION=$($s.sourceSchemaVersion)", "STATE_PACK_VERSION=$($s.statePackVersion)",
                "BATCH_ID=$batch") -join '@@'
            $env:JDK_JAVA_OPTIONS = "@$hopArgFile"
            & $hopRun -j $hopProject -r local -l MINIMAL -f (Join-Path $root "hop/pipelines/$($s.hopTemplate)") -ps '@@' -p $parameters | Out-Null
            $hopExit = $LASTEXITCODE
            Remove-Item Env:JDK_JAVA_OPTIONS
            if ($hopExit -ne 0) { Fail "Hop ingest failed for $($s.packId)/$($s.sourceId)." }
            Psql ("update ingestion_batch set status='COMPLETED', completed_at=now()," +
                " record_count=(select count(*) from raw_record where batch_id='$batch') where batch_id='$batch'") | Out-Null
            $script:batches += $batch
        }
    }

    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start(); $appPort = $listener.LocalEndpoint.Port; $listener.Stop()
    $base = "http://127.0.0.1:$appPort"
    $demoCredentials = Join-Path $work 'users.txt'
    $app = Start-Process java -PassThru -NoNewWindow -WorkingDirectory $core -RedirectStandardOutput (Join-Path $work 'app.log') `
        -RedirectStandardError (Join-Path $work 'app.err') -ArgumentList @('-cp', "`"$classpath`"", 'gov.uddp.core.UddpCommonCoreApplication',
            "--server.port=$appPort", "--uddp.state-pack-directory=$packs", '--uddp.fixture-state-packs-enabled=true',
            '--uddp.security.demo.seed-users=true', "--uddp.security.demo.credentials-file=$demoCredentials")
    $up = $false
    for ($i = 0; $i -lt 120 -and -not $up; $i++) {
        try { Invoke-RestMethod -ErrorAction Stop "$base/health" -TimeoutSec 2 | Out-Null; $up = $true } catch { Start-Sleep -Seconds 1 }
        if ($app.HasExited) { Fail "Common Core exited during startup; see $work\app.err" }
    }
    if (-not $up) { Fail 'Common Core did not start.' }
    $demoUsers = Read-UddpDemoCredentials $demoCredentials
    Use-UddpUser $base 'platform.admin' $demoUsers['platform.admin']

    Stage 'canonical processing' {
        foreach ($batch in $batches) {
            $summary = Invoke-UddpApi -Method Post "$base/api/canonical/residents/process/batches/$batch" -TimeoutSec 7200
            Write-Host ("   batch {0}: read={1} mapped={2} skipped={3} failed={4}" -f $batch.Substring(0, 8), $summary.recordsRead,
                $summary.recordsMapped, $summary.recordsSkipped, $summary.recordsFailed)
        }
    }
    $runId = $null
    Stage 'matching (Splink)' {
        Push-Location $root
        try { $matchOutput = & python -m matching.run } finally { Pop-Location }
        if ($LASTEXITCODE -ne 0) { Fail 'Matching failed.' }
        $matchOutput | Where-Object { $_ -like 'Blocking on*' } | ForEach-Object { Write-Host "   $_" }
        $script:runId = (($matchOutput | Select-Object -Last 1) | ConvertFrom-Json).run_id
    }
    Stage 'golden build' {
        Invoke-UddpApi -Method Post "$base/api/golden-residents/build" -ContentType 'application/json' `
            -Body (@{ matchingRunId = $runId } | ConvertTo-Json) -TimeoutSec 7200 | Out-Null
    }

    # ---- report ----
    Stop-Job $sampler -ErrorAction SilentlyContinue
    $samples = @()
    if (Test-Path $memoryLog) {
        $samples = Get-Content $memoryLog | ForEach-Object { $t, $m = $_ -split ','; [pscustomobject]@{ At = [datetime]$t; Mb = [double]$m } }
    }
    Write-Host ''
    Write-Host ("{0,-24}{1,12}{2,16}" -f 'Stage', 'Time', 'Peak memory')
    $total = 0.0; $overall = 0.0
    foreach ($name in $stages.Keys) {
        $seconds = ($stages[$name].End - $stages[$name].Start).TotalSeconds
        $peak = ($samples | Where-Object { $_.At -ge $stages[$name].Start -and $_.At -le $stages[$name].End } | Measure-Object Mb -Maximum).Maximum
        $total += $seconds; if ($peak -gt $overall) { $overall = $peak }
        Write-Host ("{0,-24}{1,10:N1} s{2,13:N0} MB" -f $name, $seconds, $peak)
    }
    Write-Host ("{0,-24}{1,10:N1} s{2,13:N0} MB" -f 'TOTAL', $total, $overall)
    $raw = Psql 'select count(*) from raw_record'; $canonical = Psql 'select count(*) from canonical_resident'
    $golden = Psql "select count(*) from golden_resident where status='ACTIVE'"
    Write-Host "raw=$raw canonical=$canonical golden=$golden; end to end $([math]::Round($total / 60, 1)) min (target: 200k rows < 30 min)"
} finally {
    if ($sampler) { Stop-Job $sampler -ErrorAction SilentlyContinue; Remove-Job $sampler -Force -ErrorAction SilentlyContinue }
    if ($app -and -not $app.HasExited) { Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue }
    if ($KeepDatabase) { Write-Host "Keeping container $container (remove with: docker rm -f $container)" }
    else { & docker rm -f $container *> $null }
    Remove-Item Env:POSTGRES_PASSWORD, Env:JDK_JAVA_OPTIONS -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
