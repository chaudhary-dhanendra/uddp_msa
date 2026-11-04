# Creates the six UDDP service roles and their schemas in the local PostgreSQL (docs/msa-design.md, section 6).
# Connects as UDDP_POSTGRES_USER on UDDP_POSTGRES_HOST:UDDP_POSTGRES_PORT. The service roles get the password in
# UDDP_MSA_SERVICE_PASSWORD, or UDDP_POSTGRES_PASSWORD when that is unset. Never prints a password.
# It touches only uddp_raw, uddp_identity, uddp_registry and uddp_platform; never the monolith's uddp database.
$ErrorActionPreference = 'Stop'

foreach ($name in 'UDDP_POSTGRES_HOST', 'UDDP_POSTGRES_PORT', 'UDDP_POSTGRES_USER', 'UDDP_POSTGRES_PASSWORD') {
    if (-not [Environment]::GetEnvironmentVariable($name)) { throw "Set $name first." }
}

$psql = (Get-Command psql -ErrorAction SilentlyContinue).Source
if (-not $psql) {
    $psql = Get-ChildItem 'C:\Program Files\PostgreSQL\*\bin\psql.exe' -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $psql) { throw 'psql was not found; add the PostgreSQL bin folder to PATH.' }

$env:PGPASSWORD = $env:UDDP_POSTGRES_PASSWORD
if (-not $env:UDDP_MSA_SERVICE_PASSWORD) { $env:UDDP_MSA_SERVICE_PASSWORD = $env:UDDP_POSTGRES_PASSWORD }
try {
    & $psql -X -q -h $env:UDDP_POSTGRES_HOST -p $env:UDDP_POSTGRES_PORT -U $env:UDDP_POSTGRES_USER -d postgres `
        -f (Join-Path $PSScriptRoot 'msa-db-roles.sql')
    if ($LASTEXITCODE -ne 0) { throw "psql failed with exit code $LASTEXITCODE" }
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}
