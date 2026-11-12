<#
.SYNOPSIS
  Starts the whole UDDP microservices stack on one Windows machine with one command.

.DESCRIPTION
  Kafka (docker compose), then discovery-service, statepack, ingestion, canonical, identity, registry, edge and
  gateway as background Java processes, then the onboarding UI. Each step waits until the service reports UP on
  /actuator/health and is registered in Eureka before the next one starts. No extra windows are opened: every
  process writes to logs\<name>.out.log and logs\<name>.err.log, and its JSON log to logs\<name>.log.

  A service that is already running and healthy is left alone, so the script can be re-run after a failure.
  Stop everything with scripts\stop-all.ps1.

  Settings come from the environment, or from deploy\local\msa.env (KEY=VALUE lines, gitignored; see
  scripts\msa.env.example). Required: UDDP_MSA_SERVICE_PASSWORD (or UDDP_POSTGRES_PASSWORD), the password of the
  service roles created by deploy\postgres\02-msa-service-roles.sql. UDDP_INTERNAL_TOKEN is generated for this run
  when it is not set.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1 -Build -Observability -Open

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1 -DemoUsers   # after scripts\reset-databases.ps1
#>
[CmdletBinding()]
param(
    # Run "mvn install -DskipTests" first (otherwise the jars in services\*\target are used as they are).
    [switch]$Build,
    # Also start Prometheus, Grafana, Tempo, Loki and friends (docker compose --profile observability).
    [switch]$Observability,
    # Do not touch Docker (Kafka is already running somewhere else).
    [switch]$SkipKafka,
    # Start only the backend.
    [switch]$SkipUi,
    # Always rebuild the UI (it is rebuilt anyway when src is newer than dist).
    [switch]$RebuildUi,
    # Port of the UI (vite preview).
    [int]$UiPort = 5173,
    # Seconds to wait for each service to become healthy.
    [int]$TimeoutSec = 240,
    # Open the UI in the browser at the end.
    [switch]$Open,
    # Demo mode: the edge creates the seven demo users (platform.admin, state.admin, ...) if they do not exist yet
    # and prints their one-time passwords to logs\edge-service.out.log (never to the JSON log).
    [switch]$DemoUsers
)

$ErrorActionPreference = 'Stop'

# Windows PowerShell 5.1 turns anything a native program writes to stderr (docker's warnings, for example) into an
# error once it is redirected, and with ErrorActionPreference=Stop that ends the script. Native programs are judged
# by their exit code ($LASTEXITCODE) instead, so they run with Continue.
function Invoke-Native([scriptblock]$Block) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Block } finally { $ErrorActionPreference = $previous }
}
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$LogDir = Join-Path $Root 'logs'
$PidDir = Join-Path $LogDir 'pids'
New-Item -ItemType Directory -Force -Path $LogDir, $PidDir | Out-Null

function Write-Step([string]$text) { Write-Host ''; Write-Host "==> $text" -ForegroundColor Cyan }
function Write-Ok([string]$text) { Write-Host "    $text" -ForegroundColor Green }
function Write-Warn2([string]$text) { Write-Host "    $text" -ForegroundColor Yellow }
function Fail([string]$text) { Write-Host ''; Write-Host "FAILED: $text" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------------------------------------------------------
# Settings
# ---------------------------------------------------------------------------------------------------------------
Write-Step 'Settings'

$envFile = Join-Path $Root 'deploy\local\msa.env'
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#') -or -not $t.Contains('=')) { continue }
        $i = $t.IndexOf('=')
        $key = $t.Substring(0, $i).Trim()
        $value = $t.Substring($i + 1).Trim().Trim('"').Trim("'")
        # A variable already set in this window wins over the file.
        if (-not [Environment]::GetEnvironmentVariable($key, 'Process')) {
            [Environment]::SetEnvironmentVariable($key, $value, 'Process')
        }
    }
    Write-Ok "read deploy\local\msa.env"
}

function Get-Env([string]$name) { return [Environment]::GetEnvironmentVariable($name, 'Process') }
function Set-Default([string]$name, [string]$value) { if (-not (Get-Env $name)) { [Environment]::SetEnvironmentVariable($name, $value, 'Process') } }

if (-not (Get-Env 'UDDP_POSTGRES_PASSWORD')) {
    if (Get-Env 'UDDP_MSA_SERVICE_PASSWORD') {
        $env:UDDP_POSTGRES_PASSWORD = $env:UDDP_MSA_SERVICE_PASSWORD
    } else {
        Fail ('Set UDDP_MSA_SERVICE_PASSWORD (the service roles'' password from 02-msa-service-roles.sql), e.g.' +
              "`n    `$env:UDDP_MSA_SERVICE_PASSWORD = '<password>'`n    or put it in deploy\local\msa.env")
    }
}
if (-not (Get-Env 'UDDP_INTERNAL_TOKEN')) {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $env:UDDP_INTERNAL_TOKEN = ($bytes | ForEach-Object { $_.ToString('x2') }) -join ''
    Write-Warn2 'UDDP_INTERNAL_TOKEN was not set: generated one for this run (all services get the same value)'
}
Set-Default 'UDDP_POSTGRES_HOST' 'localhost'
Set-Default 'UDDP_POSTGRES_PORT' '5432'
Set-Default 'UDDP_KAFKA_BOOTSTRAP_SERVERS' 'localhost:9092'
Set-Default 'UDDP_LOG_DIR' $LogDir
if (-not (Get-Env 'HOP_HOME')) {
    Write-Warn2 'HOP_HOME is not set: ingestion starts, but sources that run an Apache Hop pipeline will fail'
}
Write-Ok ("Postgres {0}:{1}, Kafka {2}, logs in {3}" -f $env:UDDP_POSTGRES_HOST, $env:UDDP_POSTGRES_PORT,
          $env:UDDP_KAFKA_BOOTSTRAP_SERVERS, $env:UDDP_LOG_DIR)

$java = 'java'
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
if (-not (Get-Command $java -ErrorAction SilentlyContinue)) { Fail 'java was not found (set JAVA_HOME or put java on PATH)' }

# ---------------------------------------------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------------------------------------------
function Test-Port([string]$hostName, [int]$port) {
    # "localhost" may resolve to ::1 first while PostgreSQL listens on IPv4 only: try the IPv4 address too, and give
    # each attempt 3 seconds.
    $names = @($hostName)
    if ($hostName -eq 'localhost') { $names = @('127.0.0.1', '::1') }
    foreach ($name in $names) {
        $client = New-Object Net.Sockets.TcpClient
        try {
            $async = $client.BeginConnect($name, $port, $null, $null)
            if ($async.AsyncWaitHandle.WaitOne(3000)) { $client.EndConnect($async); return $true }
        } catch { } finally { $client.Close() }
    }
    return $false
}

function Test-Health([int]$port) {
    try {
        $r = Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 5 -UseBasicParsing
        return $r.status -eq 'UP'
    } catch { return $false }
}

function Test-Registered([string]$app) {
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:8761/eureka/apps/$($app.ToUpper())" -TimeoutSec 5 `
            -Headers @{ Accept = 'application/json' } -UseBasicParsing
        return $r.StatusCode -eq 200 -and $r.Content -match '"status"\s*:\s*"UP"'
    } catch { return $false }
}

function Show-LogTail([string]$name) {
    foreach ($suffix in 'err.log', 'out.log') {
        $f = Join-Path $LogDir "$name.$suffix"
        if ((Test-Path $f) -and (Get-Item $f).Length -gt 0) {
            Write-Host "    --- last lines of logs\$name.$suffix ---" -ForegroundColor DarkGray
            Get-Content $f -Tail 25 | ForEach-Object { Write-Host "    $_" }
        }
    }
}

# Starts a process hidden, with stdout/stderr in logs\, and only the given variables changed for it.
function Start-Background([string]$name, [string]$file, [string[]]$arguments, [string]$workDir, [hashtable]$vars) {
    $saved = @{}
    foreach ($k in $vars.Keys) {
        $saved[$k] = [Environment]::GetEnvironmentVariable($k, 'Process')
        [Environment]::SetEnvironmentVariable($k, $vars[$k], 'Process')
    }
    try {
        $p = Start-Process -FilePath $file -ArgumentList $arguments -WorkingDirectory $workDir -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $LogDir "$name.out.log") `
            -RedirectStandardError (Join-Path $LogDir "$name.err.log") -PassThru
        $null = $p.Handle  # keeps the handle, so ExitCode is known if the process stops
    } finally {
        foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k], 'Process') }
    }
    Set-Content -Path (Join-Path $PidDir "$name.pid") -Value $p.Id -Encoding ascii
    return $p
}

# ---------------------------------------------------------------------------------------------------------------
# Build and preflight
# ---------------------------------------------------------------------------------------------------------------
if ($Build) {
    Write-Step 'Building (mvn install -DskipTests)'
    Push-Location $Root
    try { Invoke-Native { & mvn -q install -DskipTests }; if ($LASTEXITCODE -ne 0) { Fail 'the Maven build failed' } } finally { Pop-Location }
    Write-Ok 'built'
}

# Order matters: Eureka first; statepack before the edge (the edge installs the activated packs); gateway last.
$services = @(
    @{ name = 'discovery-service'; port = 8761 },
    @{ name = 'statepack-service'; port = 8105 },
    @{ name = 'ingestion-service'; port = 8101 },
    @{ name = 'canonical-service'; port = 8102 },
    @{ name = 'identity-service';  port = 8103 },
    @{ name = 'registry-service';  port = 8104 },
    @{ name = 'edge-service';      port = 8106 },
    @{ name = 'gateway-service';   port = 8080 }
)

Write-Step 'Checking prerequisites'
$missing = @()
foreach ($s in $services) {
    $s.dir = Join-Path $Root "services\$($s.name)"
    $s.jar = Join-Path $s.dir "target\uddp-$($s.name)-0.1.0-SNAPSHOT-exec.jar"
    if (-not (Test-Path $s.jar)) { $missing += $s.jar }
}
if ($missing.Count -gt 0) { Fail ("jar(s) not built, run with -Build or 'mvn install -DskipTests':`n    " + ($missing -join "`n    ")) }
Write-Ok 'all eight jars are built'

if (-not (Test-Port $env:UDDP_POSTGRES_HOST ([int]$env:UDDP_POSTGRES_PORT))) {
    Fail "PostgreSQL is not reachable at $($env:UDDP_POSTGRES_HOST):$($env:UDDP_POSTGRES_PORT) (is the service running?)"
}
Write-Ok 'PostgreSQL is reachable'

# ---------------------------------------------------------------------------------------------------------------
# Kafka
# ---------------------------------------------------------------------------------------------------------------
if (-not $SkipKafka) {
    Write-Step 'Kafka (docker compose)'
    Invoke-Native { & docker info *> $null }
    if ($LASTEXITCODE -ne 0) { Fail 'Docker is not running: start Docker Desktop and run this again' }
    Push-Location $Root
    try {
        Invoke-Native { & docker compose up -d kafka kafka-init }
        if ($LASTEXITCODE -ne 0) { Fail 'docker compose up kafka failed' }
        # kafka-init creates the topics and exits; wait for it so no service starts before its topics exist.
        $initId = Invoke-Native { & docker compose ps -aq kafka-init 2> $null } | Select-Object -First 1
        if ($initId) {
            $job = Start-Job { param($id) docker wait $id 2> $null } -ArgumentList $initId
            if (-not (Wait-Job $job -Timeout 180)) { Stop-Job $job; Fail 'kafka-init did not finish in 180 s (docker compose logs kafka-init)' }
            $code = (Receive-Job $job -ErrorAction SilentlyContinue | Select-Object -Last 1)
            Remove-Job $job -Force
            if ("$code".Trim() -ne '0') { Fail "kafka-init exited with $code (docker compose logs kafka-init)" }
        }
        if ($Observability) {
            Invoke-Native { & docker compose --profile observability up -d }
            if ($LASTEXITCODE -ne 0) { Fail 'docker compose --profile observability up failed' }
        }
    } finally { Pop-Location }
    Write-Ok 'Kafka is up and the topics exist'
}

# ---------------------------------------------------------------------------------------------------------------
# Services
# ---------------------------------------------------------------------------------------------------------------
$pgUrlEdge = "jdbc:postgresql://$($env:UDDP_POSTGRES_HOST):$($env:UDDP_POSTGRES_PORT)/uddp_platform?currentSchema=edge"

foreach ($s in $services) {
    $name = $s.name
    Write-Step "$name (port $($s.port))"
    $isDiscovery = $name -eq 'discovery-service'

    if ((Test-Health $s.port) -and ($isDiscovery -or (Test-Registered $name))) {
        Write-Ok 'already running and healthy, left as it is'
        continue
    }
    if (Test-Port '127.0.0.1' $s.port) {
        Fail "port $($s.port) is in use but $name is not healthy there. Run scripts\stop-all.ps1 (or close the old window) and try again."
    }

    # The edge alone talks to uddp_platform/edge through UDDP_POSTGRES_*; for every other service those three are
    # cleared, so a developer environment that still points them at the monolith's database cannot leak in.
    if ($name -eq 'edge-service') {
        $vars = @{ UDDP_POSTGRES_URL = $pgUrlEdge; UDDP_POSTGRES_USER = 'uddp_edge'; UDDP_POSTGRES_DB = 'uddp_platform' }
    } else {
        $vars = @{ UDDP_POSTGRES_URL = $null; UDDP_POSTGRES_USER = $null; UDDP_POSTGRES_DB = $null }
    }

    # Working directory = the service folder: the defaults (../../platform, hop, ...) are relative to it.
    $javaArgs = @('-jar', "`"$($s.jar)`"")
    if ($DemoUsers -and $name -eq 'edge-service') { $javaArgs += '--uddp.security.demo.seed-users=true' }
    $p = Start-Background $name $java $javaArgs $s.dir $vars

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $healthy = $false
    while ((Get-Date) -lt $deadline) {
        if ($p.HasExited) { Show-LogTail $name; Fail "$name stopped during startup (exit code $($p.ExitCode))" }
        if ((Test-Health $s.port) -and ($isDiscovery -or (Test-Registered $name))) { $healthy = $true; break }
        Start-Sleep -Seconds 2
        Write-Host '.' -NoNewline
    }
    Write-Host ''
    if (-not $healthy) { Show-LogTail $name; Fail "$name was not healthy and registered after $TimeoutSec s (logs\$name.*.log)" }
    Write-Ok ("UP (pid {0}, {1:N0} s)" -f $p.Id, ((Get-Date) - $p.StartTime).TotalSeconds)
}

# The gateway reaches the edge through Eureka; wait until it can route a call there.
Write-Step 'Gateway route to the edge'
$deadline = (Get-Date).AddSeconds(90)
$routed = $false
while ((Get-Date) -lt $deadline) {
    try {
        $r = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/health' -TimeoutSec 5 -UseBasicParsing
        if ($r.StatusCode -eq 200) { $routed = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if (-not $routed) { Fail 'http://localhost:8080/health does not answer (logs\gateway-service.*.log)' }
Write-Ok 'http://localhost:8080/health answers'

# ---------------------------------------------------------------------------------------------------------------
# UI
# ---------------------------------------------------------------------------------------------------------------
if (-not $SkipUi) {
    Write-Step "Onboarding UI (port $UiPort)"
    $ui = Join-Path $Root 'web\onboarding-ui'
    if (Test-Port '127.0.0.1' $UiPort) {
        Write-Ok "port $UiPort is already serving, left as it is"
    } else {
        if (-not (Get-Command npm -ErrorAction SilentlyContinue)) { Fail 'npm was not found (install Node.js)' }
        Push-Location $ui
        try {
            if (-not (Test-Path 'node_modules')) {
                Write-Ok 'npm ci (first run)'
                Invoke-Native { & npm ci }; if ($LASTEXITCODE -ne 0) { Fail 'npm ci failed' }
            }
            $dist = Join-Path $ui 'dist\index.html'
            $stale = -not (Test-Path $dist)
            if (-not $stale) {
                $built = (Get-Item $dist).LastWriteTime
                $newest = Get-ChildItem -Path 'src', 'index.html', 'package.json', 'vite.config.ts' -Recurse -File -ErrorAction SilentlyContinue |
                    Sort-Object LastWriteTime -Descending | Select-Object -First 1
                $stale = $newest -and $newest.LastWriteTime -gt $built
            }
            if ($RebuildUi -or $stale) {
                Write-Ok 'npm run build'
                Invoke-Native { & npm run build }; if ($LASTEXITCODE -ne 0) { Fail 'the UI build failed' }
            } else {
                Write-Ok 'dist is up to date, not rebuilt'
            }
        } finally { Pop-Location }

        $p = Start-Background 'onboarding-ui' 'cmd.exe' @('/c', "npm run preview -- --port $UiPort") $ui @{}
        $deadline = (Get-Date).AddSeconds(60)
        while ((Get-Date) -lt $deadline -and -not (Test-Port '127.0.0.1' $UiPort)) {
            if ($p.HasExited) { Show-LogTail 'onboarding-ui'; Fail 'the UI preview server stopped' }
            Start-Sleep -Seconds 1
        }
        if (-not (Test-Port '127.0.0.1' $UiPort)) { Show-LogTail 'onboarding-ui'; Fail "the UI did not start on port $UiPort" }
        Write-Ok "UP (pid $($p.Id))"
    }
}

# ---------------------------------------------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------------------------------------------
Write-Host ''
Write-Host 'UDDP is running.' -ForegroundColor Green
if (-not $SkipUi) { Write-Host "  UI        http://localhost:$UiPort" }
Write-Host '  Gateway   http://localhost:8080   (every API call goes here)'
Write-Host '  Eureka    http://localhost:8761'
if ($Observability) { Write-Host '  Grafana   http://localhost:3000    Prometheus http://localhost:9090' }
Write-Host "  Logs      $LogDir"
Write-Host '  Stop      scripts\stop-all.ps1'
if ($DemoUsers -and (Test-Path (Join-Path $LogDir 'edge-service.out.log'))) {
    Write-Host ''
    Write-Host 'Demo users created on this start (empty if they already existed):'
    Get-Content (Join-Path $LogDir 'edge-service.out.log') | Select-String -Pattern '^(UDDP demo users|USERNAME|[a-z]+\.[a-z]+ )' |
        ForEach-Object { Write-Host "  $($_.Line)" }
}
if ($Open -and -not $SkipUi) { Start-Process "http://localhost:$UiPort" }
