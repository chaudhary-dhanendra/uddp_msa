<#
.SYNOPSIS
  Stops what scripts\start-all.ps1 started: the UI, the eight services and (unless -KeepKafka) Kafka.

.DESCRIPTION
  Uses the process ids in logs\pids. A service started by hand in its own window is found by its port instead, and
  stopped only when that port belongs to a java or node process. Kafka is stopped with "docker compose stop", so its
  data volume and the topics stay.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1
#>
[CmdletBinding()]
param(
    # Leave Kafka (and the observability containers) running.
    [switch]$KeepKafka,
    [int]$UiPort = 5173
)

$ErrorActionPreference = 'Continue'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$PidDir = Join-Path $Root 'logs\pids'

# Gateway first, discovery last: the reverse of the start order.
$targets = @(
    @{ name = 'onboarding-ui';     port = $UiPort },
    @{ name = 'gateway-service';   port = 8080 },
    @{ name = 'edge-service';      port = 8106 },
    @{ name = 'registry-service';  port = 8104 },
    @{ name = 'identity-service';  port = 8103 },
    @{ name = 'canonical-service'; port = 8102 },
    @{ name = 'ingestion-service'; port = 8101 },
    @{ name = 'statepack-service'; port = 8105 },
    @{ name = 'discovery-service'; port = 8761 }
)

function Stop-Tree([int]$id) {
    # /T also stops the children (Hop or Python run by a service, node under cmd for the UI).
    & taskkill.exe /PID $id /T /F *> $null
    return $LASTEXITCODE -eq 0
}

foreach ($t in $targets) {
    $stopped = $false
    $pidFile = Join-Path $PidDir "$($t.name).pid"
    if (Test-Path $pidFile) {
        $id = [int](Get-Content $pidFile | Select-Object -First 1)
        if (Get-Process -Id $id -ErrorAction SilentlyContinue) { $stopped = Stop-Tree $id }
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    }
    # Started by hand, or the pid file is gone: whoever listens on the port, if it is java or node.
    $owners = @(Get-NetTCPConnection -LocalPort $t.port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique)
    foreach ($o in $owners) {
        $proc = Get-Process -Id $o -ErrorAction SilentlyContinue
        if ($proc -and @('java', 'javaw', 'node') -contains $proc.ProcessName.ToLower()) {
            if (Stop-Tree $o) { $stopped = $true }
        } elseif ($proc) {
            Write-Host ("  {0,-18} port {1} is used by {2} (pid {3}), not stopped" -f $t.name, $t.port, $proc.ProcessName, $o) -ForegroundColor Yellow
        }
    }
    if ($stopped) { Write-Host ("  {0,-18} stopped" -f $t.name) -ForegroundColor Green }
    else { Write-Host ("  {0,-18} was not running" -f $t.name) -ForegroundColor DarkGray }
}

if (-not $KeepKafka) {
    Push-Location $Root
    try {
        & docker compose --profile observability stop
        Write-Host '  Kafka and observability containers stopped (data kept)' -ForegroundColor Green
    } finally { Pop-Location }
}
