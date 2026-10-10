# =====================================================================
#  doctor.ps1 - one command that tells you why the stack will not start
# =====================================================================
#
#  Run this FIRST whenever anything refuses to come up. It walks the
#  same chain a request walks, from the outside in:
#
#     Docker daemon -> port 5432 -> container -> .env -> database
#                   -> service ports -> health endpoints
#
#  Each link is checked only if the previous one passed, because a
#  failure early on makes everything after it meaningless noise.
#
#  Usage:   .\scripts\doctor.ps1
#
#  Written for Windows PowerShell 5.1 (the one that ships with Windows).
# =====================================================================

$ErrorActionPreference = 'Continue'

$script:Failures = 0
$script:Warnings = 0

function Write-Head($Text) {
    Write-Host ""
    Write-Host "=======================================================================" -ForegroundColor DarkGray
    Write-Host "  $Text" -ForegroundColor Cyan
    Write-Host "=======================================================================" -ForegroundColor DarkGray
}

function Write-Ok($Text) {
    Write-Host "   [OK]   $Text" -ForegroundColor Green
}

function Write-Fail($Text, $Fix) {
    $script:Failures++
    Write-Host "   [FAIL] $Text" -ForegroundColor Red
    if ($Fix) { Write-Host "          FIX: $Fix" -ForegroundColor Yellow }
}

function Write-Warn($Text, $Fix) {
    $script:Warnings++
    Write-Host "   [WARN] $Text" -ForegroundColor Yellow
    if ($Fix) { Write-Host "          FIX: $Fix" -ForegroundColor Yellow }
}

function Write-Info($Text) {
    Write-Host "          $Text" -ForegroundColor DarkGray
}

# ---------------------------------------------------------------------
# Move to the project root, whichever folder the script was launched from
# ---------------------------------------------------------------------
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
Write-Host ""
Write-Host "  Project root: $root" -ForegroundColor DarkGray

# =====================================================================
#  1. Is the Docker daemon reachable?
# =====================================================================
Write-Head "1. Docker daemon"

$dockerOk = $false
docker info 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) {
    Write-Ok "Docker is running"
    $dockerOk = $true
} else {
    Write-Fail "Docker is not reachable" "Start Docker Desktop and wait for the whale icon to stop animating."
}

# =====================================================================
#  2. Who owns port 5432?
# =====================================================================
Write-Head "2. Port 5432 (PostgreSQL)"

# Any process LISTENING on 5432, regardless of interface
$listeners = @(netstat -ano | Select-String ":5432\s" | Select-String "LISTENING")

if ($listeners.Count -eq 0) {
    Write-Warn "Nothing is listening on 5432" "Expected if the container is stopped - section 3 will confirm."
} else {
    # Pull the PID off the end of the first matching line
    $pid5432 = ($listeners[0].ToString().Trim() -split '\s+')[-1]
    $owner = $null
    try { $owner = Get-Process -Id $pid5432 -ErrorAction Stop } catch { }

    if ($owner -and ($owner.ProcessName -match 'postgres')) {
        # A native Windows postgres.exe - this is the impostor that
        # steals the port from Docker and answers with the wrong database
        Write-Fail "A native Windows PostgreSQL owns 5432 (PID $pid5432, $($owner.ProcessName).exe)" `
                   "In an ADMIN PowerShell: Stop-Service postgresql-x64-18 ; Set-Service postgresql-x64-18 -StartupType Manual"
        Write-Info "This is the classic one. Your services will connect to it instead of"
        Write-Info "Docker and report 'password authentication failed' - because that server"
        Write-Info "has no service roles at all, and Postgres hides the difference on purpose."
    } elseif ($owner) {
        Write-Ok "Port 5432 is held by $($owner.ProcessName).exe (PID $pid5432)"
        Write-Info "Expected: com.docker.backend / vpnkit / wslrelay when Docker owns it."
    } else {
        Write-Ok "Port 5432 is in use by PID $pid5432"
    }
}

# Hyper-V can fence off a port range without anything using it
$excluded = netsh interface ipv4 show excludedportrange protocol=tcp 2>&1 | Out-String
$fenced = $false
foreach ($line in ($excluded -split "`n")) {
    if ($line -match '^\s*(\d+)\s+(\d+)') {
        if (5432 -ge [int]$matches[1] -and 5432 -le [int]$matches[2]) { $fenced = $true }
    }
}
if ($fenced) {
    Write-Warn "5432 sits inside a Windows/Hyper-V reserved port range" `
               "In an ADMIN PowerShell: net stop winnat ; docker compose up -d postgres ; net start winnat"
}

# =====================================================================
#  3. Is the Postgres container up and healthy?
# =====================================================================
Write-Head "3. Postgres container"

$containerUp = $false
if ($dockerOk) {
    $state = (docker inspect -f "{{.State.Status}}" ecommerce-postgres 2>&1 | Out-String).Trim()

    if ($LASTEXITCODE -ne 0 -or $state -eq '') {
        Write-Fail "Container 'ecommerce-postgres' does not exist" "docker compose up -d postgres"
    } elseif ($state -ne 'running') {
        Write-Fail "Container exists but is '$state'" "docker compose up -d postgres"
        Write-Info "Note: 'docker compose up' REUSES the existing volume. Your data is safe."
        Write-Info "Never use 'down -v' to fix a startup problem - that erases everything."
    } else {
        $health = (docker inspect -f "{{.State.Health.Status}}" ecommerce-postgres 2>&1 | Out-String).Trim()
        if ($health -eq 'healthy') {
            Write-Ok "Container is running and healthy"
            $containerUp = $true
        } elseif ($health -eq 'starting') {
            Write-Warn "Container is running but still 'starting'" "Wait 15 seconds and run this script again."
        } else {
            Write-Ok "Container is running (health: $health)"
            $containerUp = $true
        }
    }
}

# =====================================================================
#  4. Does .env exist, and are the values filled in?
# =====================================================================
Write-Head "4. Environment file"

$envOk = $false
if (-not (Test-Path ".env")) {
    Write-Fail ".env is missing" "Copy .env.example to .env and fill in the values."
} else {
    Get-Content .env | ForEach-Object {
        if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
            [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim())
        }
    }
    Write-Ok ".env found and loaded into this session"

    $required = @('USER_DB_PASSWORD', 'PRODUCT_DB_PASSWORD', 'ORDER_DB_PASSWORD')
    $missing = @()
    foreach ($name in $required) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ([string]::IsNullOrWhiteSpace($value)) { $missing += $name }
    }

    if ($missing.Count -gt 0) {
        Write-Fail "Empty or missing: $($missing -join ', ')" "Set these in .env before starting any service."
    } else {
        Write-Ok "All three database passwords are present"
        $envOk = $true
    }

    # Phase 2 ships with a shared secret; Step 9 replaces it with a key pair.
    # Accept either, but say which one is in force.
    if (-not [string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
        if ($env:JWT_SECRET.Length -lt 32) {
            Write-Fail "JWT_SECRET is only $($env:JWT_SECRET.Length) characters" "HS256 needs at least 32. A length of 13 means the literal placeholder leaked through."
        } else {
            Write-Ok "JWT_SECRET present ($($env:JWT_SECRET.Length) chars) - signing mode: HS256 (shared secret)"
        }
    } elseif (-not [string]::IsNullOrWhiteSpace($env:JWT_KEY_DIR)) {
        Write-Ok "JWT_KEY_DIR present - signing mode: RS256 (key pair)"
        if (Test-Path (Join-Path $root "keys\jwt-public.pem")) {
            Write-Ok "Public key found at keys\jwt-public.pem"
        } else {
            Write-Warn "No keys\jwt-public.pem yet" "Start user-service first - it generates the pair on first run."
        }
    } else {
        Write-Fail "Neither JWT_SECRET nor JWT_KEY_DIR is set" "One of them must be present for tokens to be signed."
    }
}

# =====================================================================
#  5. Can each service actually log in to its own database?
# =====================================================================
Write-Head "5. Database connections"

if ($containerUp -and $envOk) {

    # role | database | a table that must exist | friendly label
    $databases = @(
        @{ Role = 'user_service';    Db = 'ecommerce_user_db';    Table = 'customers'; Pass = $env:USER_DB_PASSWORD },
        @{ Role = 'product_service'; Db = 'ecommerce_product_db'; Table = 'products';  Pass = $env:PRODUCT_DB_PASSWORD },
        @{ Role = 'order_service';   Db = 'ecommerce_order_db';   Table = 'orders';    Pass = $env:ORDER_DB_PASSWORD }
    )

    foreach ($d in $databases) {
        # -h 127.0.0.1 forces TCP, so the password is genuinely checked.
        # Without it psql uses the local socket, which trusts everyone
        # and would pass even with the wrong password.
        $out = docker exec -e PGPASSWORD=$($d.Pass) ecommerce-postgres `
                 psql -h 127.0.0.1 -U $($d.Role) -d $($d.Db) -t -A `
                 -c "select count(*) from $($d.Table)" 2>&1 | Out-String

        $out = $out.Trim()

        if ($out -match '28P01') {
            Write-Fail "$($d.Role): password rejected" "docker exec ecommerce-postgres psql -U postgres -c `"ALTER ROLE $($d.Role) WITH PASSWORD '<value from .env>'`""
        } elseif ($out -match 'does not exist') {
            Write-Fail "$($d.Role): $out" "Check the database name, or let Flyway run by starting the service."
        } elseif ($out -match '^\d+$') {
            Write-Ok "$($d.Role) -> $($d.Db): connected, $out rows in $($d.Table)"
        } else {
            Write-Fail "$($d.Role): unexpected response" $out
        }
    }
} else {
    Write-Info "Skipped - the container or .env check above has to pass first."
}

# =====================================================================
#  6. Are the three services listening, and do they answer?
# =====================================================================
Write-Head "6. Services"

$services = @(
    @{ Name = 'user-service';    Port = 8081 },
    @{ Name = 'product-service'; Port = 8082 },
    @{ Name = 'order-service';   Port = 8083 }
)

foreach ($s in $services) {
    $bound = @(netstat -ano | Select-String ":$($s.Port)\s" | Select-String "LISTENING")

    if ($bound.Count -eq 0) {
        Write-Warn "$($s.Name) is not listening on $($s.Port)" ".\scripts\run.ps1 $($s.Name -replace '-service','')"
        continue
    }

    try {
        $response = Invoke-WebRequest -Uri "http://localhost:$($s.Port)/actuator/health" `
                                      -UseBasicParsing -TimeoutSec 5
        $raw = $response.Content
        # Actuator replies with its own media type, which makes PowerShell
        # hand back raw bytes instead of a string. Decode before reading.
        if ($raw -is [byte[]]) { $raw = [System.Text.Encoding]::UTF8.GetString($raw) }

        if ($response.StatusCode -eq 200) {
            Write-Ok "$($s.Name) is UP on $($s.Port)"
        } else {
            Write-Fail "$($s.Name) answered HTTP $($response.StatusCode)" "Check that service's terminal for a stack trace."
        }
    } catch {
        Write-Fail "$($s.Name) holds the port but will not answer" "The process is alive but startup failed - read its terminal."
    }
}

# =====================================================================
#  Summary
# =====================================================================
Write-Head "Summary"

if ($script:Failures -eq 0 -and $script:Warnings -eq 0) {
    Write-Host "   Everything checks out. Run .\scripts\verify-phase2.ps1 next." -ForegroundColor Green
} elseif ($script:Failures -eq 0) {
    # Warnings only. Nothing is broken - these are things not started yet.
    # A diagnostic that cries wolf gets ignored, so do not say "failure" here.
    Write-Host "   No failures - $($script:Warnings) warning(s)." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "   The stack itself is sound. The items above are things that are not" -ForegroundColor DarkGray
    Write-Host "   running yet rather than things that are broken. Start them with the" -ForegroundColor DarkGray
    Write-Host "   FIX lines shown, then run this again." -ForegroundColor DarkGray
} else {
    Write-Host "   Failures: $($script:Failures)    Warnings: $($script:Warnings)" -ForegroundColor Red
    Write-Host ""
    Write-Host "   Fix the FIRST failure above and run this again. Later checks are" -ForegroundColor DarkGray
    Write-Host "   often just the first one echoing downstream." -ForegroundColor DarkGray
}
Write-Host ""