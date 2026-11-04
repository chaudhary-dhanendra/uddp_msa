<#
.SYNOPSIS
  One-command end-to-end proof on a throwaway PostgreSQL 16:
  Flyway -> generic Hop ingest of every State Pack source -> canonical processing -> Splink matching
  -> Golden build, then prints counts and asserts raw=7, canonical=7, golden=5, AUTO_MATCH=3 (golden = active
  Golden identities) and families=6 (active Golden Families, M14). Benefits (M15) are checked through the read API:
  benefits=5, Ram Singh's Resident 360 holds 3 benefits (PDS and pensions from different states), the duplicate
  report's only finding is Ram Singh, and one resident is eligible for the old age pension but not enrolled.

  With -IncrementalStatePackDirectory, the packs in that directory are left out of the baseline above and then
  onboarded incrementally: generic Hop ingest, canonical processing, `matching.run --incremental`, Golden build.
  The script then asserts the incremental counts and that every baseline Golden ID is unchanged and still active.

.DESCRIPTION
  Never touches your UDDP_POSTGRES_* database: a disposable postgres:16-alpine container is started on a
  random localhost port with a random password (never printed), and UDDP_POSTGRES_* are overridden for
  this process and its children only. The container is removed at the end (-KeepDatabase keeps it).

  Optional: -IncludePacks limits the run to the named pack folders; -LgdSnapshot loads that LGD CSV into the geography
  master before canonical processing; -SkipBenefitAssertions drops the sample-data benefit/stewardship checks;
  -PreMatchingScript is dot-sourced after canonical processing and before matching (same variables);
  -PostGoldenScript is dot-sourced after the Golden build and the count assertions (it sees $runId, $base, Psql, Fail).
  Excel sources (format: xlsx) are ingested with generic-excel-ingest.hpl.

  Needs: HOP_HOME (Apache Hop 2.x, with this repository registered as a Hop project), Docker, Java 21,
  Maven, Python 3.12 with matching/requirements.txt installed. Every State Pack source is ingested with
  the same generic pipeline named by its hopTemplate; there is no per-state code.
#>
param(
    [string]$StatePackDirectory = 'state-packs',
    [string]$AdditionalStatePackDirectory,
    [int]$ExpectRaw = 7,
    [int]$ExpectCanonical = 7,
    [int]$ExpectGolden = 5,
    [int]$ExpectAutoMatch = 3,
    [int]$ExpectFamilies = 6,
    [int]$ExpectBenefits = 5,
    [int]$ExpectRamBenefits = 3,
    [int]$ExpectDuplicateFindings = 1,
    [int]$ExpectNotEnrolled = 1,
    [string]$EligibilityAsOf = '2026-10-25',
    [string]$IncrementalStatePackDirectory,
    [int]$ExpectIncrementalRaw = 9,
    [int]$ExpectIncrementalCanonical = 9,
    [int]$ExpectIncrementalGolden = 6,
    [int]$ExpectIncrementalAutoMatch = 3,
    [int]$ExpectIncrementalFamilies = 8,
    [string[]]$IncludePacks,
    [string]$LgdSnapshot,
    [string]$PreMatchingScript,
    [string]$PostGoldenScript,
    [switch]$SkipBenefitAssertions,
    [switch]$KeepDatabase
)
# Native tools (Hop, Maven, Docker) log to stderr; every exit code is checked explicitly instead.
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'e2e/uddp-api.ps1')
$core = Join-Path $root 'common-core'
$packs = (Resolve-Path (Join-Path $root $StatePackDirectory)).Path
$temporaryPackRoot = $null
$extraPackDirectories = @($AdditionalStatePackDirectory, $IncrementalStatePackDirectory) | Where-Object { $_ }
if ($extraPackDirectories -or $IncludePacks) {
    $temporaryPackRoot = Join-Path ([IO.Path]::GetTempPath()) ("uddp-e2e-packs-" + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $temporaryPackRoot | Out-Null
    Get-ChildItem -LiteralPath $packs | Where-Object { -not $IncludePacks -or $IncludePacks -contains $_.Name } |
        ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $temporaryPackRoot -Recurse }
    foreach ($directory in $extraPackDirectories) {
        $extra = (Resolve-Path (Join-Path $root $directory)).Path
        Get-ChildItem -LiteralPath $extra | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $temporaryPackRoot -Recurse }
    }
    $packs = $temporaryPackRoot
}

function Fail([string]$message) {
    Write-Host "E2E FAILED: $message" -ForegroundColor Red
    exit 1
}

function Need([string]$command, [string]$hint) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { Fail "$command not found on PATH. $hint" }
}

# ---- 1. Prerequisites -------------------------------------------------------------------------------
$hopHome = $env:HOP_HOME
if (-not $hopHome) { $hopHome = [Environment]::GetEnvironmentVariable('HOP_HOME', 'User') }
if (-not $hopHome) { Fail 'HOP_HOME is not set. Point it at your Apache Hop installation (the folder containing hop-run.bat).' }
$hopRun = Join-Path $hopHome 'hop-run.bat'
if (-not (Test-Path $hopRun)) { Fail "HOP_HOME=$hopHome does not contain hop-run.bat." }
Need docker 'Install and start Docker Desktop.'
Need java 'Install Java 21.'
Need mvn 'Install Maven 3.9+.'
Need python 'Install Python 3.12 and run: pip install -r matching/requirements.txt'
& docker info --format '{{.ServerVersion}}' *> $null
if ($LASTEXITCODE -ne 0) { Fail 'Docker is installed but not running. Start Docker Desktop.' }

# The Hop project whose home is this repository (UDDP_HOP_PROJECT overrides the lookup).
$hopProject = $env:UDDP_HOP_PROJECT
if (-not $hopProject) {
    $configFolder = if ($env:HOP_CONFIG_FOLDER) { $env:HOP_CONFIG_FOLDER } else { Join-Path $hopHome 'config' }
    $hopConfig = Join-Path $configFolder 'hop-config.json'
    if (Test-Path $hopConfig) {
        $projects = (Get-Content $hopConfig -Raw | ConvertFrom-Json).projectsConfig.projectConfigurations
        foreach ($project in $projects) {
            $home_ = $project.projectHome
            if (-not [IO.Path]::IsPathRooted($home_)) { $home_ = Join-Path $hopHome $home_ }
            if ((Test-Path $home_) -and ((Resolve-Path $home_).Path.TrimEnd('\') -ieq $root.TrimEnd('\'))) {
                $hopProject = $project.projectName
            }
        }
    }
}
if (-not $hopProject) {
    Fail ("No Hop project has its home at $root. Register it once with:`n" +
        "  & `"$hopHome\hop-conf.bat`" --project-create --project uddp-mvp --project-home `"$root`" --project-config-file project-config.json")
}

# ---- 2. Throwaway PostgreSQL 16 -----------------------------------------------------------------------
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$container = "uddp-e2e-$suffix"
$database = 'uddp_e2e'
$user = 'uddp_e2e'
$bytes = New-Object byte[] 24
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$env:POSTGRES_PASSWORD = -join ($bytes | ForEach-Object { $_.ToString('x2') })
$app = $null
$hopArgFile = $null
$auditFolder = Join-Path ([IO.Path]::GetTempPath()) "uddp-e2e-hop-audit-$suffix"

function Psql([string]$sql) {
    $out = & docker exec $container psql -X -q -t -A -v ON_ERROR_STOP=1 -U $user -d $database -c $sql
    if ($LASTEXITCODE -ne 0) { Fail "SQL failed: $sql" }
    return ($out | Out-String).Trim()
}

# M15: benefits through the read API. Ram Singh must hold PDS and pension benefits from different states in one view,
# be the duplicate report's only finding, and the eligible-but-not-enrolled report must list the expected residents.
function Assert-Benefits([string]$label) {
    $ram = Psql ("select string_agg(distinct m.golden_resident_id::text, ',') from golden_resident_membership m" +
        " join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id" +
        " where m.effective_to is null and c.canonical_payload->>'name' = 'Ram Singh'")
    $benefitTotal = (Invoke-UddpApi "$base/api/v1/benefits?size=1").totalItems
    $view = Invoke-UddpApi "$base/api/v1/golden-residents/$ram/benefits"
    $pdsStates = @($view.benefits | Where-Object { $_.benefit.schemeId -eq 'PDS-RATION' } | ForEach-Object { $_.benefit.stateCode })
    $pensionStates = @($view.benefits | Where-Object { $_.benefit.schemeId -eq 'OLD-AGE-PENSION' } |
        ForEach-Object { $_.benefit.stateCode })
    $duplicates = Invoke-UddpApi "$base/api/v1/reports/duplicate-benefits"
    $eligibility = Invoke-UddpApi ("$base/api/v1/reports/eligible-not-enrolled" +
        "?schemeId=OLD-AGE-PENSION&asOf=$EligibilityAsOf")
    $notEnrolled = $eligibility.schemes[0].notEnrolledCount
    Write-Host ("  benefits            $benefitTotal   (Ram Singh: $($view.benefitCount) in $($view.departments -join '+')," +
        " PDS from $($pdsStates -join ',') and pensions from $($pensionStates -join ','))")
    Write-Host "  duplicate report    $($duplicates.findingCount) finding(s); eligible not enrolled: $notEnrolled ($label)"
    $problems = @()
    if ($benefitTotal -ne $ExpectBenefits) { $problems += "benefits=$benefitTotal, expected $ExpectBenefits" }
    if ($ram -match ',' -or $view.benefitCount -ne $ExpectRamBenefits) {
        $problems += "Ram Singh ($ram) holds $($view.benefitCount) benefits, expected $ExpectRamBenefits"
    }
    if ($pdsStates.Count -lt 1 -or $pensionStates.Count -lt 1) {
        $problems += 'Ram Singh does not show both PDS and pension benefits'
    } elseif (@($pdsStates + $pensionStates | Select-Object -Unique).Count -lt 2) {
        $problems += 'Ram Singh benefits are not from different states'
    }
    if ($duplicates.findingCount -ne $ExpectDuplicateFindings) {
        $problems += "duplicate findings=$($duplicates.findingCount), expected $ExpectDuplicateFindings"
    } elseif ($ExpectDuplicateFindings -gt 0 -and $duplicates.findings[0].goldenResidentId -ne $ram) {
        $problems += "duplicate finding is $($duplicates.findings[0].name), expected Ram Singh"
    }
    if ($notEnrolled -ne $ExpectNotEnrolled) { $problems += "eligible not enrolled=$notEnrolled, expected $ExpectNotEnrolled" }
    if ($problems) { Fail ($problems -join '; ') }
    $stateCount = @($pdsStates + $pensionStates | Select-Object -Unique).Count
    Write-Host ("BENEFIT E2E PASSED ($label): benefits=$benefitTotal, Ram Singh $($view.benefitCount) benefits from" +
        " $stateCount states, duplicate=Ram Singh, not enrolled=$notEnrolled") -ForegroundColor Green
}

try {
    Write-Host "Starting throwaway PostgreSQL 16 container $container ..."
    # The password reaches Docker through the POSTGRES_PASSWORD environment variable, not the command line.
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

    # UDDP_POSTGRES_* for this process and its children only (Hop metadata, Common Core, matching).
    Remove-Item Env:UDDP_POSTGRES_URL -ErrorAction SilentlyContinue
    $env:UDDP_POSTGRES_HOST = '127.0.0.1'
    $env:UDDP_POSTGRES_PORT = $port
    $env:UDDP_POSTGRES_DB = $database
    $env:UDDP_POSTGRES_USER = $user
    $env:UDDP_POSTGRES_PASSWORD = $env:POSTGRES_PASSWORD
    $env:UDDP_STATE_PACK_DIRECTORY = $packs
    $env:HOP_AUDIT_FOLDER = $auditFolder

    # ---- 3. Build and Flyway ---------------------------------------------------------------------------
    Write-Host 'Building Common Core ...'
    Push-Location $core
    try {
        & mvn -q -B -DskipTests compile dependency:build-classpath "-Dmdep.outputFile=target/classpath.txt" "-Dmdep.includeScope=runtime"
        if ($LASTEXITCODE -ne 0) { Fail 'Maven build failed.' }
    } finally { Pop-Location }
    $classpath = (Join-Path $core 'target/classes') + [IO.Path]::PathSeparator + (Get-Content (Join-Path $core 'target/classpath.txt') -Raw).Trim()
    Write-Host 'Running Flyway migrate ...'
    & java -cp $classpath gov.uddp.core.database.DatabaseMigrationCommand migrate
    if ($LASTEXITCODE -ne 0) { Fail 'Flyway migrate failed.' }

    # ---- 4. Generic Hop ingest of every State Pack source ---------------------------------------------
    $sources = & python (Join-Path $root 'scripts/e2e/list_sources.py') $packs $root
    if ($LASTEXITCODE -ne 0) { Fail 'Could not read State Pack sources.' }
    $incrementalPackIds = @()
    if ($IncrementalStatePackDirectory) {
        $incrementalSources = & python (Join-Path $root 'scripts/e2e/list_sources.py') (Join-Path $root $IncrementalStatePackDirectory) $root
        if ($LASTEXITCODE -ne 0) { Fail 'Could not read the incremental State Pack sources.' }
        $incrementalPackIds = @($incrementalSources | ForEach-Object { ($_ | ConvertFrom-Json).packId } | Select-Object -Unique)
    }
    # Hop resolves ${UDDP_POSTGRES_*} in metadata/rdbms/uddp-postgres.json from Java system properties, not
    # from the environment. hop-run.bat echoes its options and arguments, so the settings go in a temporary
    # JVM argument file named by JDK_JAVA_OPTIONS (the launcher prints only the file name).
    $hopArgFile = Join-Path ([IO.Path]::GetTempPath()) "uddp-e2e-hop-$suffix.args"
    @('UDDP_POSTGRES_HOST', 'UDDP_POSTGRES_PORT', 'UDDP_POSTGRES_DB', 'UDDP_POSTGRES_USER', 'UDDP_POSTGRES_PASSWORD') |
        ForEach-Object { "-D$_=" + [Environment]::GetEnvironmentVariable($_, 'Process') } |
        Set-Content -Path $hopArgFile -Encoding ascii
    function Ingest($s) {
        $batch = [Guid]::NewGuid().ToString()
        Write-Host "Ingesting $($s.packId)/$($s.sourceId) with $($s.hopTemplate) (batch $batch) ..."
        Psql ("insert into ingestion_batch(batch_id,state_code,source_system,source_type,source_schema_version," +
            "state_pack_version,pipeline_template,status) values('$batch','$($s.stateCode)','$($s.sourceId)'," +
            "'$($s.sourceType)','$($s.sourceSchemaVersion)','$($s.statePackVersion)','$($s.hopTemplate)','STARTED')") | Out-Null
        $ingestInput = if ($s.hopTemplate -eq 'generic-excel-ingest.hpl') {
            @("FILE=$($s.sourceFile)", "SHEETS=$($s.sheets)")
        } else {
            @("SOURCE_FILE=$($s.sourceFile)", "CSV_HEADER=$($s.csvHeader)")
        }
        $parameters = @($ingestInput +
            @("STATE_CODE=$($s.stateCode)",
            "SOURCE_SYSTEM=$($s.sourceId)", "SOURCE_ENTITY=$($s.sourceId)",
            "SOURCE_SCHEMA_VERSION=$($s.sourceSchemaVersion)", "STATE_PACK_VERSION=$($s.statePackVersion)",
            "BATCH_ID=$batch")) -join '@@'
        $pipeline = Join-Path $root "hop/pipelines/$($s.hopTemplate)"
        $env:JDK_JAVA_OPTIONS = "@$hopArgFile"
        & $hopRun -j $hopProject -r local -l MINIMAL -f $pipeline -ps '@@' -p $parameters | Out-Host
        $hopExit = $LASTEXITCODE
        Remove-Item Env:JDK_JAVA_OPTIONS
        if ($hopExit -ne 0) {
            Psql "update ingestion_batch set status='FAILED', completed_at=now(), error_message='hop-run failed' where batch_id='$batch'" | Out-Null
            Fail "Hop ingest failed for $($s.packId)/$($s.sourceId)."
        }
        Psql ("update ingestion_batch set status='COMPLETED', completed_at=now()," +
            " record_count=(select count(*) from raw_record where batch_id='$batch') where batch_id='$batch'") | Out-Null
        return $batch
    }
    $batches = @()
    foreach ($line in $sources) {
        $s = $line | ConvertFrom-Json
        if ($incrementalPackIds -contains $s.packId) { continue }
        $batches += (Ingest $s)
    }

    # ---- 5. Common Core: canonical processing ----------------------------------------------------------
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start(); $appPort = $listener.LocalEndpoint.Port; $listener.Stop()
    Write-Host "Starting Common Core on port $appPort ..."
    $appLog = Join-Path ([IO.Path]::GetTempPath()) "uddp-e2e-app-$suffix.log"
    # The demo seeder writes one-time passwords here; they are read into memory and the file is deleted.
    $demoCredentials = Join-Path ([IO.Path]::GetTempPath()) "uddp-e2e-users-$suffix.txt"
    $app = Start-Process java -PassThru -NoNewWindow -WorkingDirectory $core -RedirectStandardOutput $appLog `
        -RedirectStandardError "$appLog.err" `
        -ArgumentList (@('-cp', "`"$classpath`"", 'gov.uddp.core.UddpCommonCoreApplication', "--server.port=$appPort",
            "--uddp.state-pack-directory=$packs", '--uddp.fixture-state-packs-enabled=true',
            '--uddp.security.demo.seed-users=true', "--uddp.security.demo.credentials-file=$demoCredentials") +
            $(if ($LgdSnapshot) { @("--uddp.geography.lgd.source-file=$LgdSnapshot") } else { @() }))
    $base = "http://127.0.0.1:$appPort"
    $up = $false
    for ($i = 0; $i -lt 90 -and -not $up; $i++) {
        try { Invoke-RestMethod -ErrorAction Stop "$base/health" -TimeoutSec 2 | Out-Null; $up = $true } catch { Start-Sleep -Seconds 1 }
        if ($app.HasExited) { Fail "Common Core exited during startup; see $appLog" }
    }
    if (-not $up) { Fail "Common Core did not start; see $appLog" }
    # P1: every API call is signed in. The demo users of this throwaway database have one-time passwords that are
    # replaced by random ones in memory; nothing is printed.
    try { $demoUsers = Read-UddpDemoCredentials $demoCredentials } catch { Fail $_.Exception.Message }
    Use-UddpUser $base 'platform.admin' $demoUsers['platform.admin']
    if ($LgdSnapshot) {
        # The geography master comes from the snapshot file; geography names in canonical records resolve against it.
        Invoke-UddpApi -Method Post "$base/api/geography/sync/runs" | Out-Null
        $syncStatus = 'RUNNING'
        for ($i = 0; $i -lt 60 -and $syncStatus -eq 'RUNNING'; $i++) {
            Start-Sleep -Seconds 1
            $syncStatus = (Invoke-UddpApi "$base/api/geography/sync/runs?size=1").items[0].status
        }
        if ($syncStatus -notin @('SUCCEEDED', 'UNCHANGED')) { Fail "LGD sync ended as $syncStatus" }
        Write-Host "LGD master loaded from the snapshot ($syncStatus)"
    }
    foreach ($batch in $batches) {
        $summary = Invoke-UddpApi -Method Post "$base/api/canonical/residents/process/batches/$batch"
        Write-Host ("Canonical batch {0}: read={1} mapped={2} skipped={3} failed={4}" -f $batch, $summary.recordsRead,
            $summary.recordsMapped, $summary.recordsSkipped, $summary.recordsFailed)
    }

    # ---- 6. Splink matching ----------------------------------------------------------------------------
    if ($PreMatchingScript) { . $PreMatchingScript }
    Write-Host 'Running Splink matching ...'
    Push-Location $root
    try {
        $matchOutput = & python -m matching.run
        if ($LASTEXITCODE -ne 0) { Fail 'Matching failed.' }
    } finally { Pop-Location }
    $runId = (($matchOutput | Select-Object -Last 1) | ConvertFrom-Json).run_id

    # ---- 7. Golden build -------------------------------------------------------------------------------
    $golden = Invoke-UddpApi -Method Post "$base/api/golden-residents/build" -ContentType 'application/json' `
        -Body (@{ matchingRunId = $runId } | ConvertTo-Json)

    # ---- 8. Counts and assertions ----------------------------------------------------------------------
    $raw = [int](Psql 'select count(*) from raw_record')
    $canonical = [int](Psql 'select count(*) from canonical_resident')
    $errors = [int](Psql 'select count(*) from canonical_processing_error')
    $auto = [int](Psql "select count(*) from resident_match_pair where run_id='$runId' and decision='AUTO_MATCH'")
    $possible = [int](Psql "select count(*) from resident_match_pair where run_id='$runId' and decision='POSSIBLE_MATCH'")
    $goldenCount = [int](Psql "select count(*) from golden_resident where status='ACTIVE'")
    $families = [int](Psql "select count(*) from golden_family where status='ACTIVE'")
    $familyMembers = [int](Psql 'select count(*) from golden_family_membership where effective_to is null')
    Write-Host ''
    Write-Host 'E2E counts'
    Write-Host "  raw_record          $raw"
    Write-Host "  canonical_resident  $canonical   (processing errors: $errors)"
    Write-Host "  AUTO_MATCH pairs    $auto   (POSSIBLE_MATCH: $possible)"
    Write-Host "  golden_resident     $goldenCount   (active identities; run $runId, policy $($golden.policyVersion))"
    Write-Host "  golden_family       $families   (active households; $familyMembers current memberships)"
    $problems = @()
    if ($raw -ne $ExpectRaw) { $problems += "raw=$raw, expected $ExpectRaw" }
    if ($canonical -ne $ExpectCanonical) { $problems += "canonical=$canonical, expected $ExpectCanonical" }
    if ($goldenCount -ne $ExpectGolden) { $problems += "golden=$goldenCount, expected $ExpectGolden" }
    if ($auto -ne $ExpectAutoMatch) { $problems += "AUTO_MATCH=$auto, expected $ExpectAutoMatch" }
    if ($families -ne $ExpectFamilies) { $problems += "families=$families, expected $ExpectFamilies" }
    if ($problems) { Fail ($problems -join '; ') }
    Write-Host "E2E PASSED: raw=$raw canonical=$canonical golden=$goldenCount AUTO_MATCH=$auto families=$families" -ForegroundColor Green
    if (-not $SkipBenefitAssertions) { Assert-Benefits 'after the Golden build' }
    if ($PostGoldenScript) { . $PostGoldenScript }

    # ---- 8b. Stewardship smoke (M13), 3-state run only: split Ram Singh through REST, then merge him back ----------
    if (-not $IncrementalStatePackDirectory -and -not $AdditionalStatePackDirectory -and -not $IncludePacks) {
        $ramId = Psql ("select string_agg(distinct m.golden_resident_id::text, ',') from golden_resident_membership m" +
            " join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id" +
            " where m.effective_to is null and c.canonical_payload->>'name' = 'Ram Singh'")
        $detach = Psql ("select max(source_record_key) from golden_resident_membership" +
            " where golden_resident_id = '$ramId' and effective_to is null")
        # P1 maker-checker: one steward proposes, a second one approves (only then does Golden data change).
        Use-UddpUser $base 'data.steward' $demoUsers['data.steward']
        $proposal = Invoke-UddpApi -Method Post "$base/api/stewardship/proposals" -ContentType 'application/json' `
            -Body (@{ action = 'SPLIT'; goldenResidentIds = @($ramId); sourceRecordKeys = @($detach); reason = 'stewardship smoke' } | ConvertTo-Json)
        $pendingActive = [int](Psql "select count(*) from golden_resident where status='ACTIVE'")
        if ($pendingActive -ne $ExpectGolden) { Fail "a pending split proposal changed Golden data ($pendingActive active)" }
        Use-UddpUser $base 'data.steward2' $demoUsers['data.steward2']
        $split = Invoke-UddpApi -Method Post "$base/api/stewardship/proposals/$($proposal.proposalId)/approve" `
            -ContentType 'application/json' -Body '{}'
        $afterSplit = [int](Psql "select count(*) from golden_resident where status='ACTIVE'")
        $sideId = Psql "select golden_resident_id from golden_resident_membership where source_record_key = '$detach' and effective_to is null"
        Use-UddpUser $base 'data.steward' $demoUsers['data.steward']
        $proposal = Invoke-UddpApi -Method Post "$base/api/stewardship/proposals" -ContentType 'application/json' `
            -Body (@{ action = 'MERGE'; goldenResidentIds = @($ramId, $sideId); reason = 'stewardship smoke' } | ConvertTo-Json)
        Use-UddpUser $base 'data.steward2' $demoUsers['data.steward2']
        $merge = Invoke-UddpApi -Method Post "$base/api/stewardship/proposals/$($proposal.proposalId)/approve" `
            -ContentType 'application/json' -Body '{}'
        Use-UddpUser $base 'platform.admin' $demoUsers['platform.admin']
        $afterMerge = [int](Psql "select count(*) from golden_resident where status='ACTIVE'")
        $ramNow = Psql "select golden_resident_id from golden_resident_membership where source_record_key = '$detach' and effective_to is null"
        $reviewTasks = [int](Psql 'select count(*) from stewardship_review_task')
        Write-Host "  stewardship         split -> $afterSplit active, merge -> $afterMerge active (review tasks: $reviewTasks)"
        $problems = @()
        if ($split.action -ne 'SPLIT' -or $merge.action -ne 'MERGE') { $problems += "actions $($split.action)/$($merge.action)" }
        if ($afterSplit -ne $ExpectGolden + 1) { $problems += "after split golden=$afterSplit, expected $($ExpectGolden + 1)" }
        if ($afterMerge -ne $ExpectGolden) { $problems += "after merge golden=$afterMerge, expected $ExpectGolden" }
        if ($ramNow -ne $ramId -or $sideId -eq $ramId) { $problems += "Ram Singh Golden ID $ramId -> $ramNow (split side $sideId)" }
        if ($reviewTasks -ne $possible) { $problems += "review tasks=$reviewTasks, expected one per POSSIBLE_MATCH pair ($possible)" }
        if ($problems) { Fail ($problems -join '; ') }
        Write-Host "STEWARDSHIP E2E PASSED: split $afterSplit, merge $afterMerge, Ram Singh Golden ID unchanged ($ramId)" -ForegroundColor Green
        Assert-Benefits 'after stewardship split and merge'
    }

    # ---- 9. Optional incremental onboarding of further packs ---------------------------------------------
    if ($IncrementalStatePackDirectory) {
        # Every canonical of the baseline with its Golden ID: the incremental phase must leave all of them in place.
        $baselineIds = Psql ("select m.canonical_resident_id || '=' || m.golden_resident_id from golden_resident_membership m" +
            " where m.effective_to is null order by 1")
        $ramBefore = Psql ("select string_agg(distinct m.golden_resident_id::text, ',') from golden_resident_membership m" +
            " join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id" +
            " where m.effective_to is null and c.canonical_payload->>'name' = 'Ram Singh'")
        $incrementalBatches = @()
        foreach ($line in $sources) {
            $s = $line | ConvertFrom-Json
            if ($incrementalPackIds -contains $s.packId) { $incrementalBatches += (Ingest $s) }
        }
        foreach ($batch in $incrementalBatches) {
            $summary = Invoke-UddpApi -Method Post "$base/api/canonical/residents/process/batches/$batch"
            Write-Host ("Canonical batch {0}: read={1} mapped={2} skipped={3} failed={4}" -f $batch, $summary.recordsRead,
                $summary.recordsMapped, $summary.recordsSkipped, $summary.recordsFailed)
        }
        Write-Host 'Running incremental Splink matching ...'
        Push-Location $root
        try {
            $incrementalOutput = & python -m matching.run --incremental
            if ($LASTEXITCODE -ne 0) { Fail 'Incremental matching failed.' }
        } finally { Pop-Location }
        $incrementalMatch = ($incrementalOutput | Select-Object -Last 1) | ConvertFrom-Json
        $incrementalRun = $incrementalMatch.run_id
        $incrementalGolden = Invoke-UddpApi -Method Post "$base/api/golden-residents/build" `
            -ContentType 'application/json' -Body (@{ matchingRunId = $incrementalRun } | ConvertTo-Json)

        $raw2 = [int](Psql 'select count(*) from raw_record')
        $canonical2 = [int](Psql 'select count(*) from canonical_resident')
        $auto2 = [int](Psql "select count(*) from resident_match_pair where run_id='$incrementalRun' and decision='AUTO_MATCH'")
        $golden2 = [int](Psql "select count(*) from golden_resident where status='ACTIVE'")
        $families2 = [int](Psql "select count(*) from golden_family where status='ACTIVE'")
        $moved = @(($baselineIds -split '\r?\n') | Where-Object { $_ } | ForEach-Object {
            $canonicalId, $goldenId = $_ -split '='
            $now = Psql ("select golden_resident_id from golden_resident_membership where effective_to is null" +
                " and canonical_resident_id='$canonicalId'")
            if ($now -ne $goldenId) { $_ }
        })
        $ramAfter = Psql ("select string_agg(distinct m.golden_resident_id::text, ',') from golden_resident_membership m" +
            " join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id" +
            " where m.effective_to is null and c.canonical_payload->>'name' = 'Ram Singh'")
        $ramVersion = Psql "select max(version_number) from golden_resident_version where golden_resident_id='$ramAfter'"
        $memberAdded = [int](Psql ("select count(*) from golden_resident_event where golden_build_id='$($incrementalGolden.goldenBuildId)'" +
            " and event_type='MEMBER_ADDED' and golden_resident_id in (select golden_resident_id from golden_resident" +
            " where created_at < (select started_at from golden_build where golden_build_id='$($incrementalGolden.goldenBuildId)'))"))
        Write-Host ''
        Write-Host 'Incremental counts'
        Write-Host "  raw_record          $raw2"
        Write-Host "  canonical_resident  $canonical2"
        Write-Host "  matching mode       $($incrementalMatch.mode)   (records considered: $($incrementalMatch.records_considered))"
        Write-Host "  AUTO_MATCH pairs    $auto2   (incremental run $incrementalRun)"
        Write-Host "  golden_resident     $golden2   (active; created=$($incrementalGolden.created) versioned=$($incrementalGolden.versioned) unchanged=$($incrementalGolden.unchanged))"
        Write-Host "  golden_family       $families2   (active households)"
        Write-Host "  Ram Singh Golden ID $ramBefore -> $ramAfter (version $ramVersion)"
        $problems = @()
        if ($raw2 -ne $ExpectIncrementalRaw) { $problems += "raw=$raw2, expected $ExpectIncrementalRaw" }
        if ($canonical2 -ne $ExpectIncrementalCanonical) { $problems += "canonical=$canonical2, expected $ExpectIncrementalCanonical" }
        if ($golden2 -ne $ExpectIncrementalGolden) { $problems += "golden=$golden2, expected $ExpectIncrementalGolden" }
        if ($auto2 -ne $ExpectIncrementalAutoMatch) { $problems += "AUTO_MATCH=$auto2, expected $ExpectIncrementalAutoMatch" }
        if ($families2 -ne $ExpectIncrementalFamilies) { $problems += "families=$families2, expected $ExpectIncrementalFamilies" }
        if ($incrementalMatch.mode -ne 'INCREMENTAL') { $problems += "matching mode $($incrementalMatch.mode), expected INCREMENTAL" }
        if ($moved) { $problems += "baseline Golden IDs changed: $($moved -join ', ')" }
        if ($ramBefore -ne $ramAfter -or $ramBefore -match ',') { $problems += "Ram Singh Golden ID $ramBefore -> $ramAfter" }
        if ($memberAdded -lt 1) { $problems += 'no MEMBER_ADDED event on an existing identity' }
        if ($problems) { Fail ($problems -join '; ') }
        Write-Host ("INCREMENTAL E2E PASSED: raw=$raw2 canonical=$canonical2 golden=$golden2 AUTO_MATCH=$auto2 families=$families2" +
            " Ram Singh Golden ID unchanged ($ramAfter, version $ramVersion)") -ForegroundColor Green
        Assert-Benefits 'after incremental onboarding'
    }
} catch {
    # A failed REST call or command must fail the run with a non-zero exit code, never end it silently.
    Fail "$($_.Exception.Message)"
} finally {
    if ($app -and -not $app.HasExited) { Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue }
    if ($KeepDatabase) {
        Write-Host "Keeping container $container (remove with: docker rm -f $container)"
    } else {
        & docker rm -f $container *> $null
    }
    Remove-Item Env:POSTGRES_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:JDK_JAVA_OPTIONS -ErrorAction SilentlyContinue
    if ($hopArgFile) { Remove-Item -Force $hopArgFile -ErrorAction SilentlyContinue }
    Remove-Item -Recurse -Force $auditFolder -ErrorAction SilentlyContinue
    if ($temporaryPackRoot) { Remove-Item -Recurse -Force $temporaryPackRoot -ErrorAction SilentlyContinue }
}
