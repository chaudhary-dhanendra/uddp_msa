<#
.SYNOPSIS
  Runs the platform's Flyway commands against the database named by the UDDP_POSTGRES_* variables.

.DESCRIPTION
  migrate | info | validate          - normal Flyway operations
  baseline-existing                  - one-time adoption of a database whose V2-V7 were applied
                                       by hand before M8: checks that V7 objects exist and that there
                                       is no flyway_schema_history, then records a BASELINE row at
                                       version 7. It runs no DDL and changes no existing table or row.
                                       Undo: DROP TABLE flyway_schema_history.

  UDDP_POSTGRES_* are read from the process environment, falling back to the Windows user scope.
  Connection settings are never printed.
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('migrate', 'info', 'validate', 'baseline-existing')]
    [string]$Command
)
$ErrorActionPreference = 'Stop'
$core = Join-Path $PSScriptRoot '..\common-core'

foreach ($name in 'UDDP_POSTGRES_URL', 'UDDP_POSTGRES_HOST', 'UDDP_POSTGRES_PORT', 'UDDP_POSTGRES_DB',
        'UDDP_POSTGRES_USER', 'UDDP_POSTGRES_PASSWORD') {
    if (-not [Environment]::GetEnvironmentVariable($name, 'Process')) {
        $value = [Environment]::GetEnvironmentVariable($name, 'User')
        if ($value) { [Environment]::SetEnvironmentVariable($name, $value, 'Process') }
    }
}

Push-Location $core
try {
    & mvn -q -B -DskipTests compile dependency:build-classpath "-Dmdep.outputFile=target/classpath.txt" "-Dmdep.includeScope=runtime"
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }
    $classpath = 'target/classes' + [IO.Path]::PathSeparator + (Get-Content target/classpath.txt -Raw).Trim()
    $main = 'gov.uddp.core.database.DatabaseMigrationCommand'

    if ($Command -eq 'baseline-existing') {
        $check = & java -cp $classpath $main check-manual-v7
        if ($LASTEXITCODE -ne 0) { throw "Refusing to baseline: $check" }
        Write-Output $check
        & java -cp $classpath $main baseline 7 'V2-V7 applied manually before M8'
        if ($LASTEXITCODE -ne 0) { throw 'Flyway baseline failed' }
        & java -cp $classpath $main info
        & java -cp $classpath $main validate
    } else {
        & java -cp $classpath $main $Command
    }
    if ($LASTEXITCODE -ne 0) { throw "Flyway $Command failed" }
} finally {
    Pop-Location
}
