<#
.SYNOPSIS
  Part 4 acceptance in the browser: an empty platform configured on the Setup screen, then States 1-4 onboarded
  through the Onboarding Studio with the REAL generic Hop templates and Splink, Ram Singh's 360 with ST1-ST4 and the
  portability evidence. Screenshots of every step (1366px) go to docs/demo/screens/. Asserts the database counts.

.DESCRIPTION
  Never touches your databases or your %USERPROFILE%\.uddp settings: a throwaway postgres:16-alpine container gets a
  random password (never printed), and the demo runs with temporary settings and Studio folders. Uses
  scripts/run-demo.ps1, then the Playwright journey in web/onboarding-ui/e2e (Microsoft Edge channel).
#>
param([switch]$KeepDatabase)
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$container = "uddp-studio-acceptance-$suffix"
$work = Join-Path ([IO.Path]::GetTempPath()) "uddp-studio-acceptance-$suffix"
$user = 'uddp'

function Fail([string]$message) {
    Write-Host "STUDIO ACCEPTANCE FAILED: $message" -ForegroundColor Red
    exit 1
}

function Psql([string]$sql) {
    $out = & docker exec $container psql -X -q -t -A -v ON_ERROR_STOP=1 -U $user -d uddp_demo -c $sql
    if ($LASTEXITCODE -ne 0) { Fail "SQL failed: $sql" }
    return ($out | Out-String).Trim()
}

& docker info --format '{{.ServerVersion}}' *> $null
if ($LASTEXITCODE -ne 0) { Fail 'Docker is not running.' }
$bytes = New-Object byte[] 24
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$env:POSTGRES_PASSWORD = -join ($bytes | ForEach-Object { $_.ToString('x2') })
$exitCode = 1
try {
    & docker run -d --rm --name $container -e POSTGRES_PASSWORD -e "POSTGRES_USER=$user" -p 127.0.0.1::5432 postgres:16-alpine | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail 'docker run failed.' }
    $ready = $false
    for ($i = 0; $i -lt 60 -and -not $ready; $i++) {
        & docker exec $container pg_isready -q -h 127.0.0.1 -U $user *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true } else { Start-Sleep -Seconds 1 }
    }
    if (-not $ready) { Fail 'PostgreSQL did not become ready.' }
    $port = ((& docker port $container 5432/tcp) | Select-Object -First 1).Split(':')[-1]

    # Free ports, so a demo that is already running (8081/4173) is neither used nor disturbed.
    $ports = foreach ($i in 1..2) {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $listener.Start(); $listener.LocalEndpoint.Port; $listener.Stop()
    }
    & (Join-Path $PSScriptRoot 'run-demo.ps1') -SettingsDirectory (Join-Path $work 'settings') -DataDirectory $work `
        -Port $ports[0] -UiPort $ports[1]
    if ($LASTEXITCODE -ne 0) { Fail 'The demo did not start.' }
    $env:UDDP_DEMO_URL = "http://127.0.0.1:$($ports[1])"

    $screens = Join-Path $root 'docs/demo/screens'
    if (Test-Path $screens) { Get-ChildItem $screens -Filter *.png | Remove-Item -Force }
    $env:UDDP_DEMO_DB_HOST = '127.0.0.1'
    $env:UDDP_DEMO_DB_PORT = $port
    $env:UDDP_DEMO_DB_NAME = 'uddp_demo'
    $env:UDDP_DEMO_DB_USER = $user
    $env:UDDP_DEMO_DB_PASSWORD = $env:POSTGRES_PASSWORD
    Push-Location (Join-Path $root 'web/onboarding-ui')
    try {
        & npx playwright test
        $playwright = $LASTEXITCODE
    } finally { Pop-Location }
    Remove-Item Env:UDDP_DEMO_DB_PASSWORD -ErrorAction SilentlyContinue
    if ($playwright -ne 0) { Fail 'The Playwright demo journey failed (see web/onboarding-ui/test-results).' }

    $counts = [ordered]@{
        raw = [int](Psql 'select count(*) from raw_record')
        canonical = [int](Psql 'select count(*) from canonical_resident')
        autoMatch = [int](Psql "select count(*) from resident_match_pair where decision='AUTO_MATCH'")
        golden = [int](Psql "select count(*) from golden_resident where status='ACTIVE'")
        families = [int](Psql "select count(*) from golden_family where status='ACTIVE'")
        benefits = [int](Psql 'select count(*) from benefit')
        ramSources = [int](Psql ("select count(*) from golden_resident_membership m join canonical_resident c" +
            " on c.canonical_resident_id = m.canonical_resident_id where m.effective_to is null" +
            " and c.canonical_payload->>'name' = 'Ram Singh'"))
        ramGoldenIds = [int](Psql ("select count(distinct m.golden_resident_id) from golden_resident_membership m" +
            " join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id" +
            " where m.effective_to is null and c.canonical_payload->>'name' = 'Ram Singh'"))
        ramHistory = [int](Psql ("select count(distinct v.golden_resident_id) from golden_resident_version v" +
            " where v.golden_resident_id in (select m.golden_resident_id from golden_resident_membership m" +
            " join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id" +
            " where c.canonical_payload->>'name' = 'Ram Singh')"))
        leaks = [int](Psql ("select count(*) from (select failure_message m from onboarding_run union all" +
            " select error_message from onboarding_stage_execution) x where m like '%$($env:POSTGRES_PASSWORD)%'"))
    }
    $counts.GetEnumerator() | ForEach-Object { Write-Host ("  {0,-13} {1}" -f $_.Key, $_.Value) }
    $expected = @{ raw = 9; canonical = 9; golden = 6; families = 8; benefits = 5; ramSources = 4; ramGoldenIds = 1; ramHistory = 1; leaks = 0 }
    $problems = @($expected.Keys | Where-Object { $counts[$_] -ne $expected[$_] } |
        ForEach-Object { "$_=$($counts[$_]), expected $($expected[$_])" })
    if ($problems) { Fail ($problems -join '; ') }
    Write-Host 'STUDIO ACCEPTANCE PASSED: Setup -> States 1-4 in the UI with real Hop and Splink; Ram Singh has one Golden ID across ST1-ST4.' -ForegroundColor Green
    $exitCode = 0
} finally {
    & (Join-Path $PSScriptRoot 'run-demo.ps1') -Stop | Out-Null
    if ($KeepDatabase) {
        Write-Host "Keeping container $container (remove with: docker rm -f $container)"
    } else {
        & docker rm -f $container *> $null
        Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
    }
    Remove-Item Env:POSTGRES_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:UDDP_DEMO_URL -ErrorAction SilentlyContinue
}
exit $exitCode
