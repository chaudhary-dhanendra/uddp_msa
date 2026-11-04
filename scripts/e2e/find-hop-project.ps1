<#
.SYNOPSIS
  Prints the name of the Hop project whose home is the repository root (UDDP_HOP_PROJECT wins when set);
  prints nothing when none is registered.
#>
param(
    [Parameter(Mandatory = $true)][string]$HopHome,
    [Parameter(Mandatory = $true)][string]$Root
)
if ($env:UDDP_HOP_PROJECT) {
    Write-Output $env:UDDP_HOP_PROJECT
    return
}
$configFolder = if ($env:HOP_CONFIG_FOLDER) { $env:HOP_CONFIG_FOLDER } else { Join-Path $HopHome 'config' }
$hopConfig = Join-Path $configFolder 'hop-config.json'
if (-not (Test-Path $hopConfig)) {
    return
}
$projects = (Get-Content $hopConfig -Raw | ConvertFrom-Json).projectsConfig.projectConfigurations
foreach ($project in $projects) {
    $projectHome = $project.projectHome
    if (-not [IO.Path]::IsPathRooted($projectHome)) {
        $projectHome = Join-Path $HopHome $projectHome
    }
    if ((Test-Path $projectHome) -and ((Resolve-Path $projectHome).Path.TrimEnd('\') -ieq $Root.TrimEnd('\'))) {
        Write-Output $project.projectName
        return
    }
}
