param(
    [Parameter(Mandatory)]
    [ValidateSet('user', 'product', 'order')]
    [string]$Service
)

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path ".env")) {
    Write-Host ".env not found. Copy .env.example to .env and fill in the values." -ForegroundColor Red
    exit 1
}

Get-Content .env | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim())
    }
}

# --- Database credentials ----------------------------------------------------
# Checked here rather than left to Spring, because a missing value surfaces as
# "password authentication failed" - which looks like a wrong password and sends
# you hunting in the wrong place entirely.
$dbPassword = [Environment]::GetEnvironmentVariable("$($Service.ToUpper())_DB_PASSWORD")
if ([string]::IsNullOrWhiteSpace($dbPassword)) {
    Write-Host "$($Service.ToUpper())_DB_PASSWORD is missing from .env" -ForegroundColor Red
    exit 1
}

# --- Token signing keys ------------------------------------------------------
# Tokens are signed with an RSA key pair. user-service holds the private key and
# generates the pair on first run; the other two read only the public half and
# cannot start without it.
$keyDir = $env:JWT_KEY_DIR
if ([string]::IsNullOrWhiteSpace($keyDir)) { $keyDir = "keys" }
$publicKey = Join-Path $root (Join-Path ($keyDir -replace '^\.\./', '') "jwt-public.pem")

if ($Service -eq 'user') {
    if (-not (Test-Path $publicKey)) {
        Write-Host "No key pair found - user-service will generate one on startup." -ForegroundColor Yellow
    }
} elseif (-not (Test-Path $publicKey)) {
    Write-Host "jwt-public.pem not found at $publicKey" -ForegroundColor Red
    Write-Host "Start user-service first: it generates the key pair on its first run." -ForegroundColor Yellow
    exit 1
}

Write-Host "Starting $Service-service with .env loaded" -ForegroundColor Cyan

# Tests are not run here. A failing test should not stop you starting the app;
# run them deliberately with "mvn -pl <module> test".
mvn -pl "$Service-service" spring-boot:run "-Dmaven.test.skip=true"
