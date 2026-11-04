<#
.SYNOPSIS
  Authenticated calls to the UDDP API for the e2e scripts (dot-source this file).

.DESCRIPTION
  Since P1 every API call needs a signed-in session and every write the CSRF token from the XSRF-TOKEN cookie.
  Connect-UddpApi signs in: on a fresh throwaway database it creates the first platform administrator through the
  Setup API with a random password that is kept only in memory (never printed); otherwise it signs in with the
  credentials it is given. Invoke-UddpApi takes the same arguments as Invoke-RestMethod and adds the session and
  the CSRF header.
#>

$script:UddpApiSession = $null

function New-UddpPassword {
    $bytes = New-Object byte[] 18
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    # Upper, lower, digit and symbol, so it meets the password policy.
    return 'Ua1-' + [Convert]::ToBase64String($bytes).Replace('/', 'x').Replace('+', 'y')
}

function Get-UddpCsrfToken([string]$Base) {
    $cookie = $script:UddpApiSession.Cookies.GetCookies([Uri]$Base) | Where-Object { $_.Name -eq 'XSRF-TOKEN' } |
        Select-Object -First 1
    if ($cookie) { return [Uri]::UnescapeDataString($cookie.Value) }
    return $null
}

function Invoke-UddpApi {
    param(
        [Parameter(Mandatory = $true, Position = 0)][string]$Uri,
        [string]$Method = 'Get',
        [string]$ContentType,
        $Body,
        [int]$TimeoutSec = 0
    )
    $arguments = @{ Uri = $Uri; Method = $Method; ErrorAction = 'Stop' }
    if ($ContentType) { $arguments.ContentType = $ContentType }
    if ($null -ne $Body) { $arguments.Body = $Body }
    if ($TimeoutSec -gt 0) { $arguments.TimeoutSec = $TimeoutSec }
    if ($script:UddpApiSession) {
        $arguments.WebSession = $script:UddpApiSession
        if ($Method -notin @('Get', 'GET', 'Head', 'HEAD')) {
            $origin = ([Uri]$Uri).GetLeftPart([UriPartial]::Authority)
            $token = Get-UddpCsrfToken $origin
            if ($token) { $arguments.Headers = @{ 'X-XSRF-TOKEN' = $token } }
        }
    }
    return Invoke-RestMethod @arguments
}

<#
  Signs in to the backend at $Base. With no credentials the database must be new (no users yet): the first
  administrator is created with a random in-memory password. Returns the username.
#>
function Connect-UddpApi([string]$Base, [string]$Username = 'e2e.admin', [string]$Password) {
    $session = $null
    Invoke-RestMethod -ErrorAction Stop "$Base/api/v1/auth/csrf" -SessionVariable session | Out-Null
    $script:UddpApiSession = $session
    if (-not $Password) {
        $status = Invoke-UddpApi "$Base/api/setup/status"
        if (-not $status.adminRequired) {
            throw 'The database already has users; pass credentials to Connect-UddpApi.'
        }
        $Password = New-UddpPassword
        $admin = @{ username = $Username; displayName = 'E2E administrator'; password = $Password } | ConvertTo-Json
        Invoke-UddpApi "$Base/api/setup/admin" -Method Post -ContentType 'application/json' -Body $admin | Out-Null
    } else {
        $login = @{ username = $Username; password = $Password } | ConvertTo-Json
        Invoke-UddpApi "$Base/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $login | Out-Null
    }
    return $Username
}

<# Creates another user (for example the second approver) and returns its one-time password, kept in memory. #>
function New-UddpUser([string]$Base, [string]$Username, [string[]]$Roles, [string[]]$DepartmentScope = @('ALL')) {
    $body = @{ username = $Username; displayName = $Username; roles = $Roles; departmentScope = $DepartmentScope } | ConvertTo-Json
    $created = Invoke-UddpApi "$Base/api/v1/users" -Method Post -ContentType 'application/json' -Body $body
    return $created.oneTimePassword
}

<#
  Runs $Action signed in as another user (sign in, change the one-time password, act, sign back in as before).
  The passwords stay in memory.
#>
function Invoke-AsUddpUser([string]$Base, [string]$Username, [string]$OneTimePassword, [scriptblock]$Action) {
    $previous = $script:UddpApiSession
    try {
        $session = $null
        Invoke-RestMethod -ErrorAction Stop "$Base/api/v1/auth/csrf" -SessionVariable session | Out-Null
        $script:UddpApiSession = $session
        $login = @{ username = $Username; password = $OneTimePassword } | ConvertTo-Json
        $me = Invoke-UddpApi "$Base/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $login
        if ($me.mustChangePassword) {
            $change = @{ currentPassword = $OneTimePassword; newPassword = (New-UddpPassword) } | ConvertTo-Json
            Invoke-UddpApi "$Base/api/v1/auth/change-password" -Method Post -ContentType 'application/json' -Body $change |
                Out-Null
        }
        return & $Action
    } finally {
        $script:UddpApiSession = $previous
    }
}

$script:UddpUserSessions = @{}

<#
  The demo users' one-time passwords from the file the backend's demo seeder wrote
  (uddp.security.demo.credentials-file): waits for it, reads it into memory and deletes it. Nothing is printed.
#>
function Read-UddpDemoCredentials([string]$Path, [int]$TimeoutSeconds = 60) {
    for ($i = 0; $i -lt $TimeoutSeconds -and -not (Test-Path $Path); $i++) { Start-Sleep -Seconds 1 }
    if (-not (Test-Path $Path)) { throw 'The demo users were not created.' }
    Start-Sleep -Seconds 1
    $credentials = @{}
    foreach ($line in Get-Content $Path) {
        $fields = $line.Trim() -split '\s+'
        if ($fields.Count -ge 3 -and $fields[0] -match '^[a-z][a-z0-9._@-]+$' -and $fields[0] -ne 'username') {
            $credentials[$fields[0]] = $fields[1]
        }
    }
    Remove-Item $Path -Force
    return $credentials
}

<#
  Makes $Username the user of the following Invoke-UddpApi calls. The first time it signs in with the one-time
  password and replaces it with a random one (kept in memory only); later calls reuse that session.
#>
function Use-UddpUser([string]$Base, [string]$Username, [string]$OneTimePassword) {
    if ($script:UddpUserSessions.ContainsKey($Username)) {
        $script:UddpApiSession = $script:UddpUserSessions[$Username]
        return
    }
    $session = $null
    Invoke-RestMethod -ErrorAction Stop "$Base/api/v1/auth/csrf" -SessionVariable session | Out-Null
    $script:UddpApiSession = $session
    $login = @{ username = $Username; password = $OneTimePassword } | ConvertTo-Json
    $me = Invoke-UddpApi "$Base/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $login
    if ($me.mustChangePassword) {
        $change = @{ currentPassword = $OneTimePassword; newPassword = (New-UddpPassword) } | ConvertTo-Json
        Invoke-UddpApi "$Base/api/v1/auth/change-password" -Method Post -ContentType 'application/json' -Body $change |
            Out-Null
    }
    $script:UddpUserSessions[$Username] = $session
}
