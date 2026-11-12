<#
.SYNOPSIS
  Deletes the four UDDP service databases and creates them again, empty, for a fresh demo.

.DESCRIPTION
  1. Stops the services (scripts\stop-all.ps1), so nothing holds a connection.
  2. Drops uddp_raw, uddp_identity, uddp_registry and uddp_platform. Nothing else is touched: the monolith's "uddp"
     database, the dept_demo source database and every other database stay as they are.
  3. Runs deploy\postgres\01-msa-databases.sql and 02-msa-service-roles.sql again (databases, roles, schemas).
     The tables are created by each service's Flyway migrations on its next start.
  4. Unless -KeepKafka: removes the Kafka container and its data volume, so no event from the old data is replayed
     into the new databases. start-all.ps1 creates Kafka and every topic again.
  5. Clears the services' local working files (landing folder, pack cache, Studio workspace).

  After it: scripts\start-all.ps1. The edge seeds the demo users again and writes their one-time passwords to
  logs\edge-service.out.log.

  Needs psql (PATH, or C:\Program Files\PostgreSQL\<version>\bin) and UDDP_MSA_SERVICE_PASSWORD (or
  deploy\local\msa.env). The postgres superuser password is taken from PGPASSWORD, or asked for.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\reset-databases.ps1
#>
[CmdletBinding()]
param(
    # Do not ask "type RESET".
    [switch]$Force,
    # Keep Kafka's data (only for when you know the topics hold nothing you mind).
    [switch]$KeepKafka,
    [string]$SuperUser = 'postgres'
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
$Databases = @('uddp_raw', 'uddp_identity', 'uddp_registry', 'uddp_platform')

function Fail([string]$text) { Write-Host ''; Write-Host "FAILED: $text" -ForegroundColor Red; exit 1 }
function Write-Step([string]$text) { Write-Host ''; Write-Host "==> $text" -ForegroundColor Cyan }

# Same optional settings file as start-all.ps1.
$envFile = Join-Path $Root 'deploy\local\msa.env'
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#') -or -not $t.Contains('=')) { continue }
        $i = $t.IndexOf('=')
        $key = $t.Substring(0, $i).Trim()
        $value = $t.Substring($i + 1).Trim().Trim('"').Trim("'")
        if (-not [Environment]::GetEnvironmentVariable($key, 'Process')) {
            [Environment]::SetEnvironmentVariable($key, $value, 'Process')
        }
    }
}
if (-not $env:UDDP_MSA_SERVICE_PASSWORD) {
    if ($env:UDDP_POSTGRES_PASSWORD) { $env:UDDP_MSA_SERVICE_PASSWORD = $env:UDDP_POSTGRES_PASSWORD }
    else { Fail 'Set UDDP_MSA_SERVICE_PASSWORD (the service roles'' password) or put it in deploy\local\msa.env' }
}
if (-not $env:UDDP_POSTGRES_HOST) { $env:UDDP_POSTGRES_HOST = 'localhost' }
if (-not $env:UDDP_POSTGRES_PORT) { $env:UDDP_POSTGRES_PORT = '5432' }

$psql = Get-Command psql -ErrorAction SilentlyContinue
if ($psql) { $psql = $psql.Source }
else {
    $psql = Get-ChildItem 'C:\Program Files\PostgreSQL\*\bin\psql.exe' -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $psql) { Fail 'psql was not found (add C:\Program Files\PostgreSQL\<version>\bin to PATH)' }

Write-Host ''
Write-Host ("This DELETES all data in {0} on {1}:{2}" -f ($Databases -join ', '), $env:UDDP_POSTGRES_HOST, $env:UDDP_POSTGRES_PORT) -ForegroundColor Yellow
if (-not $KeepKafka) { Write-Host 'and all Kafka events and topics.' -ForegroundColor Yellow }
Write-Host 'Other databases (uddp, dept_demo, ...) are not touched.' -ForegroundColor Yellow
if (-not $Force) {
    $answer = Read-Host 'Type RESET to continue'
    if ($answer -ne 'RESET') { Write-Host 'Nothing was changed.'; exit 0 }
}

if (-not $env:PGPASSWORD) {
    $secure = Read-Host "Password of the PostgreSQL superuser '$SuperUser'" -AsSecureString
    $env:PGPASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}
$pgArgs = @('-X', '-v', 'ON_ERROR_STOP=1', '-h', $env:UDDP_POSTGRES_HOST, '-p', $env:UDDP_POSTGRES_PORT, '-U', $SuperUser)

Invoke-Native { & $psql @pgArgs -d postgres -tAc 'select 1' *> $null }
if ($LASTEXITCODE -ne 0) { Fail "cannot sign in to PostgreSQL as $SuperUser (check the password and that the server runs)" }

Write-Step 'Stopping the services'
& (Join-Path $PSScriptRoot 'stop-all.ps1') -KeepKafka

Write-Step 'Dropping the service databases'
foreach ($db in $Databases) {
    # Close any connection left (psql, pgAdmin, a service started elsewhere), then drop.
    Invoke-Native { & $psql @pgArgs -d postgres -q -c "select pg_terminate_backend(pid) from pg_stat_activity where datname = '$db' and pid <> pg_backend_pid();" *> $null }
    Invoke-Native { & $psql @pgArgs -d postgres -q -c "drop database if exists $db;" }
    if ($LASTEXITCODE -ne 0) { Fail "could not drop $db" }
    Write-Host "    $db dropped" -ForegroundColor Green
}

Write-Step 'Creating them again (deploy\postgres\01 and 02)'
Invoke-Native { & $psql @pgArgs -d postgres -q -f (Join-Path $Root 'deploy\postgres\01-msa-databases.sql') }
if ($LASTEXITCODE -ne 0) { Fail '01-msa-databases.sql failed' }
Invoke-Native { & $psql @pgArgs -d postgres -q -f (Join-Path $Root 'deploy\postgres\02-msa-service-roles.sql') }
if ($LASTEXITCODE -ne 0) { Fail '02-msa-service-roles.sql failed' }
Write-Host '    databases, roles and schemas created (tables follow on the first start)' -ForegroundColor Green

if (-not $KeepKafka) {
    Write-Step 'Removing Kafka and its data'
    Push-Location $Root
    try {
        Invoke-Native { & docker compose rm -s -f kafka kafka-init }
        $volumes = @(Invoke-Native { & docker volume ls -q --filter 'label=com.docker.compose.volume=uddp-kafka-data' 2> $null })
        foreach ($v in $volumes) {
            Invoke-Native { & docker volume rm $v 2> $null } | Out-Null
            Write-Host "    volume $v removed" -ForegroundColor Green
        }
    } finally { Pop-Location }
}

Write-Step 'Clearing local working files'
$work = @(
    (Join-Path $env:TEMP 'uddp-landing'),
    (Join-Path $env:TEMP 'uddp-canonical-packs'),
    (Join-Path $Root 'services\statepack-service\target\studio-workspace')
)
foreach ($w in $work) {
    if (Test-Path $w) { Remove-Item $w -Recurse -Force; Write-Host "    $w cleared" -ForegroundColor Green }
}

Write-Host ''
Write-Host 'Fresh. Now run scripts\start-all.ps1' -ForegroundColor Green
Write-Host 'The demo users'' one-time passwords will be in logs\edge-service.out.log:'
Write-Host '    Select-String -Path logs\edge-service.out.log -Pattern "password"'
