<#
.SYNOPSIS
  Starts the UDDP demo: the backend on port 8081 (in setup mode when no database is configured) and the UI, then
  prints the URL. -Stop stops both.

.DESCRIPTION
  State Pack configuration is stored in PostgreSQL, so the demo starts with zero states on a fresh database, and it
  starts WITHOUT your UDDP_POSTGRES_* variables, so the Setup screen configures the database from the UI
  (-UseEnvironment keeps them). Settings are stored in -SettingsDirectory (default %USERPROFILE%\.uddp, password
  encrypted). Hop (HOP_HOME) and Python receive the database settings from the backend; no password is printed.

  Sign-in (P1): on a new database the Setup screen creates the first administrator. With -DemoUsers the backend
  instead creates one demo user per role, each with its own random one-time password (changed at first sign-in);
  the script waits until the database is ready (after the Setup screen, if needed), prints those passwords once and
  deletes the temporary file that carried them. They never appear in runtime/ logs.

  Needs: Java 21, Maven, Node 22 (npm ci already run in web/onboarding-ui), HOP_HOME with this repository
  registered as a Hop project, Python 3.12 with matching/requirements.txt installed.
#>
param(
    [switch]$Stop,
    [int]$Port = 8081,
    [int]$UiPort = 4173,
    [string]$SettingsDirectory = (Join-Path $env:USERPROFILE '.uddp'),
    # Where the filesystem-backed upload staging workspace lives (default: the repository root, gitignored).
    [string]$DataDirectory,
    [switch]$UseEnvironment,
    [switch]$DemoUsers
)
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$core = Join-Path $root 'common-core'
$ui = Join-Path $root 'web/onboarding-ui'
$runtime = Join-Path $root 'runtime'
$pidFile = Join-Path $runtime 'demo.pids'
if (-not $DataDirectory) { $DataDirectory = $root }
$studioWorkspace = Join-Path $DataDirectory 'studio-workspace'

function Stop-Demo {
    if (-not (Test-Path $pidFile)) {
        Write-Host 'The demo is not running.'
        return
    }
    foreach ($line in Get-Content $pidFile) {
        if ($line -match '^\d+$') {
            # /T stops the whole process tree (java, node and their children).
            & taskkill /PID $line /T /F *> $null
        }
    }
    Remove-Item $pidFile -Force
    Write-Host 'Demo stopped.'
}

if ($Stop) {
    Stop-Demo
    exit 0
}
if (Test-Path $pidFile) {
    Write-Host 'Stopping the previous demo first ...'
    Stop-Demo
}
# A port held by another process (an earlier demo started by hand, another app) would answer the health check
# instead of this backend, so refuse to start rather than report someone else's server.
foreach ($candidate in @($Port, $UiPort)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $candidate -ErrorAction SilentlyContinue) {
        Write-Host "Port $candidate is already in use; stop that process or pass -Port/-UiPort." -ForegroundColor Red
        exit 1
    }
}
New-Item -ItemType Directory -Force -Path $runtime, $studioWorkspace | Out-Null

$hopHome = $env:HOP_HOME
if (-not $hopHome) { $hopHome = [Environment]::GetEnvironmentVariable('HOP_HOME', 'User') }
if ($hopHome) {
    $env:HOP_HOME = $hopHome
    $hopProject = & (Join-Path $PSScriptRoot 'e2e/find-hop-project.ps1') -HopHome $hopHome -Root $root
    if ($hopProject) { $env:UDDP_HOP_PROJECT = $hopProject }
} else {
    Write-Host 'Warning: HOP_HOME is not set; onboarding runs will fail at INGESTED.' -ForegroundColor Yellow
}

Write-Host 'Building the backend ...'
Push-Location $core
try {
    & mvn -q -B -DskipTests compile dependency:build-classpath "-Dmdep.outputFile=target/classpath.txt" "-Dmdep.includeScope=runtime"
    if ($LASTEXITCODE -ne 0) { Write-Host 'Maven build failed.' -ForegroundColor Red; exit 1 }
} finally { Pop-Location }
$classpath = (Join-Path $core 'target/classes') + [IO.Path]::PathSeparator + (Get-Content (Join-Path $core 'target/classpath.txt') -Raw).Trim()

Write-Host 'Building the UI ...'
Push-Location $ui
try {
    & npm run build *> (Join-Path $runtime 'demo-ui-build.log')
    if ($LASTEXITCODE -ne 0) { Write-Host "UI build failed; see runtime/demo-ui-build.log" -ForegroundColor Red; exit 1 }
} finally { Pop-Location }

# The backend's environment: without UDDP_POSTGRES_* the Setup screen configures the database.
$saved = @{}
if (-not $UseEnvironment) {
    foreach ($name in 'UDDP_POSTGRES_URL', 'UDDP_POSTGRES_HOST', 'UDDP_POSTGRES_PORT', 'UDDP_POSTGRES_DB',
            'UDDP_POSTGRES_USER', 'UDDP_POSTGRES_PASSWORD') {
        $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
}
$backendArguments = @('-cp', "`"$classpath`"", 'gov.uddp.core.UddpCommonCoreApplication', "--server.port=$Port",
    "--uddp.studio.workspace-directory=`"$studioWorkspace`"",
    "--uddp.settings-directory=`"$SettingsDirectory`"")
$credentials = $null
if ($DemoUsers) {
    # The seeder writes the one-time passwords to this file (never to the log); it is printed once and deleted.
    $credentials = Join-Path ([IO.Path]::GetTempPath()) ("uddp-demo-users-" + [Guid]::NewGuid().ToString('N') + '.txt')
    $backendArguments += @('--uddp.security.demo.seed-users=true', "--uddp.security.demo.credentials-file=`"$credentials`"")
}
$backend = Start-Process java -PassThru -WindowStyle Hidden -WorkingDirectory $core `
    -RedirectStandardOutput (Join-Path $runtime 'demo-backend.log') -RedirectStandardError (Join-Path $runtime 'demo-backend.err.log') `
    -ArgumentList $backendArguments
foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }

$env:UDDP_API_URL = "http://127.0.0.1:$Port"
$uiProcess = Start-Process npm.cmd -PassThru -WindowStyle Hidden -WorkingDirectory $ui `
    -RedirectStandardOutput (Join-Path $runtime 'demo-ui.log') -RedirectStandardError (Join-Path $runtime 'demo-ui.err.log') `
    -ArgumentList @('exec', '--', 'vite', 'preview', '--port', "$UiPort", '--strictPort')
@($backend.Id, $uiProcess.Id) | Set-Content $pidFile

$up = $false
for ($i = 0; $i -lt 120 -and -not $up; $i++) {
    try {
        $health = Invoke-RestMethod -ErrorAction Stop "http://127.0.0.1:$Port/health" -TimeoutSec 2
        $up = $true
    } catch { Start-Sleep -Seconds 1 }
    if ($backend.HasExited) { Write-Host 'The backend exited; see runtime/demo-backend.log' -ForegroundColor Red; exit 1 }
}
if (-not $up) { Write-Host 'The backend did not start; see runtime/demo-backend.log' -ForegroundColor Red; exit 1 }
$uiUp = $false
for ($i = 0; $i -lt 60 -and -not $uiUp; $i++) {
    try { Invoke-WebRequest -UseBasicParsing -ErrorAction Stop "http://127.0.0.1:$UiPort/" -TimeoutSec 2 | Out-Null; $uiUp = $true }
    catch { Start-Sleep -Seconds 1 }
}
if (-not $uiUp) { Write-Host 'The UI did not start; see runtime/demo-ui.log' -ForegroundColor Red; exit 1 }

Write-Host ''
Write-Host "UDDP demo running ($($health.mode) mode, database source: $($health.databaseSource))." -ForegroundColor Green
Write-Host "  Open http://127.0.0.1:$UiPort/"
Write-Host "  Backend http://127.0.0.1:$Port (logs in runtime/)"
Write-Host '  Stop with: scripts/run-demo.ps1 -Stop'

if ($DemoUsers) {
    if ($health.mode -ne 'READY') {
        Write-Host ''
        Write-Host 'Complete the Setup screen; the demo users are created once the database is ready (Ctrl+C to skip) ...'
    }
    while (-not (Test-Path $credentials)) {
        if ($backend.HasExited) { Write-Host 'The backend exited; see runtime/demo-backend.log' -ForegroundColor Red; exit 1 }
        Start-Sleep -Seconds 2
    }
    Start-Sleep -Seconds 1
    Write-Host ''
    Get-Content $credentials | ForEach-Object { Write-Host "  $_" }
    Remove-Item $credentials -Force
    Write-Host ''
    Write-Host 'These one-time passwords are shown only now; each user chooses a new password at first sign-in.' -ForegroundColor Yellow
}
