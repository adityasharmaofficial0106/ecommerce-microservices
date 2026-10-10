<#
.SYNOPSIS
    End-to-end check of the platform with security enabled.

.DESCRIPTION
    Creates its OWN test data (uniquely named customers, category, products) so nothing you
    already created is modified. It never deletes anything.

    It does ONE thing outside the API: it promotes the test customer it just created to ADMIN
    with a direct database UPDATE, because there is deliberately no endpoint for that. Only the
    row it created is touched.

    Requires all three services running:
      user-service    http://localhost:8081
      product-service http://localhost:8082
      order-service   http://localhost:8083

.EXAMPLE
    .\scripts\verify-phase2.ps1
#>

$ErrorActionPreference = 'Stop'

$UserSvc    = 'http://localhost:8081'
$ProductSvc = 'http://localhost:8082'
$OrderSvc   = 'http://localhost:8083'
$Password   = 'correct-horse-battery'

$script:Passed = 0
$script:Failed = 0

# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------

function Write-Header($t) {
    Write-Host ''
    Write-Host ('=' * 72) -ForegroundColor DarkGray
    Write-Host "  $t" -ForegroundColor Cyan
    Write-Host ('=' * 72) -ForegroundColor DarkGray
}
function Write-Step($t)   { Write-Host ''; Write-Host "-> $t" -ForegroundColor White }
function Write-Pass($t)   { $script:Passed++; Write-Host "   [PASS] $t" -ForegroundColor Green }
function Write-Fail($t)   { $script:Failed++; Write-Host "   [FAIL] $t" -ForegroundColor Red }
function Write-Info($t)   { Write-Host "          $t" -ForegroundColor DarkGray }
function Write-Lesson($t) { Write-Host "   WHY:  $t" -ForegroundColor Yellow }

<#
Always returns @{ Status = <int>; Body = <object or $null> }, including for 4xx/5xx.
Works on Windows PowerShell 5.1 and PowerShell 7.
#>
function Invoke-Api {
    param(
        [string]$Method,
        [string]$Uri,
        $Body = $null,
        [string]$Token = $null
    )

    $headers = @{}
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }

    $params = @{
        Method      = $Method
        Uri         = $Uri
        ContentType = 'application/json'
        ErrorAction = 'Stop'
    }
    if ($headers.Count -gt 0) { $params.Headers = $headers }
    if ($null -ne $Body)      { $params.Body = ($Body | ConvertTo-Json -Depth 10) }

    try {
        $r = Invoke-WebRequest @params -UseBasicParsing
        $raw = $r.Content
        # Some content types come back as bytes rather than a string
        if ($raw -is [byte[]]) { $raw = [System.Text.Encoding]::UTF8.GetString($raw) }
        $parsed = $null
        if ($raw) { try { $parsed = $raw | ConvertFrom-Json } catch { $parsed = $null } }
        return @{ Status = [int]$r.StatusCode; Body = $parsed }
    }
    # Untyped catch: HttpResponseException exists only in PowerShell 7.
    catch {
        $resp = $_.Exception.Response
        if ($null -eq $resp) { return @{ Status = 0; Body = $null; Error = $_.Exception.Message } }

        $content = $null
        try {
            if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $content = $_.ErrorDetails.Message }
            else {
                $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
                $content = $reader.ReadToEnd(); $reader.Close()
            }
        } catch { }

        if ($content -is [byte[]]) { $content = [System.Text.Encoding]::UTF8.GetString($content) }
        $parsed = $null
        if ($content) { try { $parsed = $content | ConvertFrom-Json } catch { } }
        return @{ Status = [int]$resp.StatusCode; Body = $parsed }
    }
}

function Assert-Status {
    param([hashtable]$Result, [int]$Expected, [string]$What)
    if ($Result.Status -eq $Expected) {
        Write-Pass "$What -> HTTP $($Result.Status)"
        return $true
    }
    Write-Fail "$What -> expected HTTP $Expected but got $($Result.Status)"
    if ($Result.Body) { Write-Info ("response: " + ($Result.Body | ConvertTo-Json -Compress -Depth 5)) }
    return $false
}

function Get-Token {
    param([string]$Email)
    $r = Invoke-Api -Method POST -Uri "$UserSvc/api/v1/auth/login" -Body @{ email = $Email; password = $Password }
    if ($r.Status -ne 200) {
        Write-Fail "Could not log in as $Email (HTTP $($r.Status))"
        exit 1
    }
    return $r.Body.accessToken
}

function New-TestCustomer {
    param([string]$Label)
    $email = "p2-$Label-$(Get-Random -Maximum 999999)@example.com"
    $r = Invoke-Api -Method POST -Uri "$UserSvc/api/v1/customers" -Body @{
        firstName = 'Test'; lastName = $Label; email = $email
        phone = '+14165550123'; password = $Password
    }
    if ($r.Status -ne 201) {
        Write-Fail "Could not register $Label (HTTP $($r.Status))"
        exit 1
    }
    return @{ Id = $r.Body.id; Email = $email }
}

# ---------------------------------------------------------------------------
Write-Header 'CHECK 0 - Are all three services running?'

$allUp = $true
foreach ($svc in @(
    @{ Name = 'user-service'; Url = $UserSvc },
    @{ Name = 'product-service'; Url = $ProductSvc },
    @{ Name = 'order-service'; Url = $OrderSvc })) {
    $h = Invoke-Api -Method GET -Uri "$($svc.Url)/actuator/health"
    if ($h.Status -eq 200) { Write-Pass "$($svc.Name) is UP" }
    else { Write-Fail "$($svc.Name) is NOT reachable at $($svc.Url)"; $allUp = $false }
}
if (-not $allUp) {
    Write-Host ''
    Write-Host 'Start the missing service(s), then re-run.' -ForegroundColor Red
    exit 1
}
Write-Lesson 'Health endpoints stay public so container probes work without credentials.'

# ---------------------------------------------------------------------------
Write-Header 'SETUP - Two customers, one promoted to ADMIN'

Write-Step 'Registering two customers (registration is public)'
$alice = New-TestCustomer -Label 'alice'
$bob   = New-TestCustomer -Label 'bob'
Write-Pass "Registered two customers without any token"
Write-Info "alice = $($alice.Id)"
Write-Info "bob   = $($bob.Id)"
Write-Lesson 'Registration must be public - a new customer has no account to log in with yet.'

Write-Step 'Logging in as each'
$aliceToken = Get-Token -Email $alice.Email
$bobToken   = Get-Token -Email $bob.Email
Write-Pass 'Both received a token'

Write-Step 'Promoting alice to ADMIN (direct database UPDATE - there is no endpoint for this)'
$envVars = @{}
Get-Content (Join-Path (Split-Path -Parent $PSScriptRoot) '.env') | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
        $n, $v = $line -split '=', 2
        $envVars[$n.Trim()] = $v.Trim()
    }
}
$superuser = $envVars['POSTGRES_SUPERUSER']
if (-not $superuser) { $superuser = 'postgres' }

$sql = "UPDATE customers SET role = 'ADMIN' WHERE id = '$($alice.Id)'"
docker exec ecommerce-postgres psql -U $superuser -d ecommerce_user_db -c $sql 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) {
    Write-Pass 'alice is now an ADMIN in the database'
} else {
    Write-Fail 'Could not promote alice - is the ecommerce-postgres container running?'
    exit 1
}
Write-Lesson 'There is deliberately no API to grant ADMIN. Anyone who could call it could'
Write-Lesson 'promote themselves, which would make the whole role system pointless.'

Write-Step 'Checking the OLD token still says CUSTOMER'
$stale = Invoke-Api -Method GET -Uri "$UserSvc/api/v1/customers" -Token $aliceToken
if (Assert-Status $stale 403 'alice''s pre-promotion token is still refused') {
    Write-Lesson 'A token is a SNAPSHOT of who you were when it was issued. Changing the'
    Write-Lesson 'database does not reach back into tokens already handed out. Same reason a'
    Write-Lesson 'banned user keeps working until their token expires.'
}

Write-Step 'Logging in again for a fresh token'
$adminToken = Get-Token -Email $alice.Email
$check = Invoke-Api -Method GET -Uri "$UserSvc/api/v1/customers" -Token $adminToken
Assert-Status $check 200 'The new token works as ADMIN' | Out-Null

# ---------------------------------------------------------------------------
Write-Header 'CHECK 1 - Authentication: 401 when nobody is asking'

Write-Step 'GET /api/v1/customers with no token at all'
$r = Invoke-Api -Method GET -Uri "$UserSvc/api/v1/customers"
if (Assert-Status $r 401 'Refused without a token') {
    Write-Info "title: $($r.Body.title)"
}
Write-Lesson '401 = "I do not know who you are". The controller never ran.'

Write-Step 'GET with a token that has been tampered with'
$tampered = $bobToken.Substring(0, $bobToken.Length - 6) + 'AAAAAA'
$r = Invoke-Api -Method GET -Uri "$UserSvc/api/v1/customers/$($bob.Id)" -Token $tampered
Assert-Status $r 401 'A token with a broken signature is refused' | Out-Null
Write-Lesson 'You can READ a token payload, but changing it invalidates the signature.'

Write-Step 'GET with complete nonsense as the token'
$r = Invoke-Api -Method GET -Uri "$UserSvc/api/v1/customers/$($bob.Id)" -Token 'not-even-a-jwt'
Assert-Status $r 401 'A malformed token is refused' | Out-Null

# ---------------------------------------------------------------------------
Write-Header 'CHECK 2 - Authorization: 403 when you are known but not allowed'

Write-Step 'bob (a CUSTOMER) tries to list every customer'
$r = Invoke-Api -Method GET -Uri "$UserSvc/api/v1/customers" -Token $bobToken
if (Assert-Status $r 403 'A customer cannot list all customers') {
    Write-Info "title: $($r.Body.title)"
}
Write-Lesson '403 = "I know who you are and you still may not do this". Different from 401.'

Write-Step 'bob reads his OWN record'
$r = Invoke-Api -Method GET -Uri "$UserSvc/api/v1/customers/$($bob.Id)" -Token $bobToken
Assert-Status $r 200 'A customer may read their own record' | Out-Null

Write-Step 'bob tries to read ALICE''s record'
$r = Invoke-Api -Method GET -Uri "$UserSvc/api/v1/customers/$($alice.Id)" -Token $bobToken
Assert-Status $r 403 'A customer may NOT read another customer''s record' | Out-Null
Write-Lesson 'This is the IDOR guard - changing an id in the URL to read someone else''s data.'
Write-Lesson 'It is consistently the most common real-world API vulnerability.'

Write-Step 'alice (ADMIN) reads bob''s record'
$r = Invoke-Api -Method GET -Uri "$UserSvc/api/v1/customers/$($bob.Id)" -Token $adminToken
Assert-Status $r 200 'An admin may read any customer' | Out-Null

# ---------------------------------------------------------------------------
Write-Header 'CHECK 3 - The catalogue: public to read, ADMIN to change'

Write-Step 'Browsing products with NO token'
$r = Invoke-Api -Method GET -Uri "$ProductSvc/api/v1/products"
Assert-Status $r 200 'Anyone may browse the catalogue' | Out-Null
Write-Lesson 'You do not log in to window-shop. This is a business decision, not a lapse.'

# A DIFFERENT name for each attempt. Reusing one name only works while the first two
# attempts are correctly refused; if security is off, the first attempt succeeds and the
# next two collide with 409 - masking the real failure behind a confusing error.
function New-CategoryBody { @{ name = "P2-$(Get-Random -Maximum 999999)"; description = 'Created by verify-phase2' } }

Write-Step 'Creating a category with no token'
$r = Invoke-Api -Method POST -Uri "$ProductSvc/api/v1/categories" -Body (New-CategoryBody)
if (-not (Assert-Status $r 401 'Creating a category without a token is refused')) {
    if ($r.Status -eq 201) {
        Write-Host ''
        Write-Host '   product-service is NOT enforcing security - it created the category.' -ForegroundColor Red
        Write-Host '   Its new code is probably not running. Check, then rebuild and restart it:' -ForegroundColor Yellow
        Write-Host '     Select-String -Path product-service\pom.xml -Pattern "starter-security"' -ForegroundColor White
        Write-Host '     mvn -pl product-service clean package -DskipTests' -ForegroundColor White
        Write-Host ''
    }
}

Write-Step 'Creating a category as a CUSTOMER'
$r = Invoke-Api -Method POST -Uri "$ProductSvc/api/v1/categories" -Body (New-CategoryBody) -Token $bobToken
Assert-Status $r 403 'A customer may not change the catalogue' | Out-Null

Write-Step 'Creating a category as an ADMIN'
$r = Invoke-Api -Method POST -Uri "$ProductSvc/api/v1/categories" -Body (New-CategoryBody) -Token $adminToken
if (-not (Assert-Status $r 201 'An admin may create a category')) { exit 1 }
$categoryId = $r.Body.id

Write-Step 'Creating a product as an ADMIN (24.99 CAD)'
$sku = "P2-$(-join ((65..90) | Get-Random -Count 6 | ForEach-Object {[char]$_}))"
$r = Invoke-Api -Method POST -Uri "$ProductSvc/api/v1/products" -Token $adminToken -Body @{
    sku = $sku; name = 'Black cotton t-shirt'; description = 'Original description'
    price = 24.99; currency = 'CAD'; categoryId = $categoryId
}
if (-not (Assert-Status $r 201 'An admin may create a product')) { exit 1 }
$productId = $r.Body.id
Write-Info "productId = $productId  (SKU $sku)"

Write-Step 'The batch lookup endpoint without a token'
$r = Invoke-Api -Method GET -Uri "$ProductSvc/api/v1/products/lookup?ids=$productId"
Assert-Status $r 401 '/products/lookup requires a token even though browsing does not' | Out-Null
Write-Lesson 'It is an internal endpoint for order-service, not part of the public shop front.'

# ---------------------------------------------------------------------------
Write-Header 'CHECK 4 - Placing an order forwards your token downstream'

Write-Step 'Giving bob a shipping address'
$r = Invoke-Api -Method POST -Uri "$UserSvc/api/v1/customers/$($bob.Id)/addresses" -Token $bobToken -Body @{
    addressLine1 = '100 King St W'; city = 'Toronto'; state = 'ON'
    postalCode = 'M5X 1A9'; country = 'CA'; addressType = 'SHIPPING'
}
if (-not (Assert-Status $r 201 'bob adds his own address')) { exit 1 }
$addressId = $r.Body.id

$cart = @{
    customerId = $bob.Id
    shippingAddressId = $addressId
    items = @(@{ productId = $productId; quantity = 2 })
}

Write-Step 'Placing an order with NO token'
$r = Invoke-Api -Method POST -Uri "$OrderSvc/api/v1/orders" -Body $cart
Assert-Status $r 401 'Orders require a token' | Out-Null

Write-Step 'bob places an order for himself'
$r = Invoke-Api -Method POST -Uri "$OrderSvc/api/v1/orders" -Body $cart -Token $bobToken
if (-not (Assert-Status $r 201 'bob places his own order')) { exit 1 }
$orderId = $r.Body.id
Write-Info "orderId = $orderId  total $($r.Body.totalAmount) $($r.Body.currency)"
Write-Lesson 'This ONE request made order-service call user-service and product-service,'
Write-Lesson 'forwarding bob''s token to both. user-service saw "bob asking about bob" and'
Write-Lesson 'its ownership rule passed with no special case for service callers.'
Write-Lesson 'Without TokenRelayInterceptor this would have failed with 401 from a dependency.'

Write-Step 'bob tries to place an order FOR ALICE'
$r = Invoke-Api -Method POST -Uri "$OrderSvc/api/v1/orders" -Token $bobToken -Body @{
    customerId = $alice.Id; shippingAddressId = $addressId
    items = @(@{ productId = $productId; quantity = 1 })
}
Assert-Status $r 403 'You may not place an order in someone else''s name' | Out-Null

Write-Step 'alice tries to read bob''s order (as ADMIN, allowed)'
$r = Invoke-Api -Method GET -Uri "$OrderSvc/api/v1/orders/$orderId" -Token $adminToken
Assert-Status $r 200 'An admin may read any order' | Out-Null

Write-Step 'Creating a second customer to test order privacy'
$carol = New-TestCustomer -Label 'carol'
$carolToken = Get-Token -Email $carol.Email
$r = Invoke-Api -Method GET -Uri "$OrderSvc/api/v1/orders/$orderId" -Token $carolToken
Assert-Status $r 403 'A customer may not read another customer''s order' | Out-Null
Write-Lesson 'Who owns an order is in the DATABASE, not the URL - so this rule uses'
Write-Lesson '@PostAuthorize: load the order first, then check, then decide to return it.'

Write-Step 'carol tries to read bob''s order history'
$r = Invoke-Api -Method GET -Uri "$OrderSvc/api/v1/customers/$($bob.Id)/orders" -Token $carolToken
Assert-Status $r 403 'A customer may not read another customer''s history' | Out-Null

# ---------------------------------------------------------------------------
Write-Header 'CHECK 5 - Business rules still hold'

Write-Step 'Trying to smuggle a price into the request'
$r = Invoke-Api -Method POST -Uri "$OrderSvc/api/v1/orders" -Token $bobToken -Body @{
    customerId = $bob.Id; shippingAddressId = $addressId
    items = @(@{ productId = $productId; quantity = 1; unitPrice = 0.01 })
}
if (Assert-Status $r 201 'Order placed') {
    if ([decimal]$r.Body.totalAmount -eq 24.99) {
        Write-Pass "The injected 0.01 was ignored; charged $($r.Body.totalAmount)"
    } else {
        Write-Fail "Expected 24.99 but got $($r.Body.totalAmount)"
    }
}
Write-Lesson 'OrderItemRequest has no price field, so Jackson drops it. Structural, not a rule.'

Write-Step 'Raising the price and renaming the product'
$r = Invoke-Api -Method PUT -Uri "$ProductSvc/api/v1/products/$productId" -Token $adminToken -Body @{
    name = 'RENAMED - Premium t-shirt'; description = 'Price raised after the order'
    price = 99.99; currency = 'CAD'; categoryId = $categoryId
}
Assert-Status $r 200 'Admin updates the product' | Out-Null

Write-Step 'Re-reading the ORIGINAL order'
$r = Invoke-Api -Method GET -Uri "$OrderSvc/api/v1/orders/$orderId" -Token $bobToken
if (Assert-Status $r 200 'Order still readable') {
    $item = $r.Body.items[0]
    Write-Info "name on the order  : $($item.productName)"
    Write-Info "price on the order : $($item.unitPrice)"
    if ([decimal]$item.unitPrice -eq 24.99 -and $item.productName -notlike 'RENAMED*') {
        Write-Pass 'The order still shows the ORIGINAL price and name'
    } else {
        Write-Fail 'The order changed when the catalogue changed'
    }
}
Write-Lesson 'A receipt that rewrites itself when prices change is not a receipt.'

Write-Step 'Deactivating the product, then ordering it again'
Invoke-Api -Method DELETE -Uri "$ProductSvc/api/v1/products/$productId" -Token $adminToken | Out-Null
$r = Invoke-Api -Method POST -Uri "$OrderSvc/api/v1/orders" -Token $bobToken -Body $cart
if (Assert-Status $r 422 'A discontinued product cannot be ordered') {
    Write-Info "detail: $($r.Body.detail)"
}
Write-Lesson '422 = well-formed request, unusable reference. Not 400, not 404.'

# ---------------------------------------------------------------------------
Write-Header 'CHECK 6 - The order state machine'

Write-Step 'bob tries to advance his own order to CONFIRMED'
$r = Invoke-Api -Method PATCH -Uri "$OrderSvc/api/v1/orders/$orderId/status" -Token $bobToken -Body @{ status = 'CONFIRMED' }
Assert-Status $r 403 'A customer may not move an order through fulfilment' | Out-Null
Write-Lesson 'Advancing to PAID or SHIPPED is staff work, not something a shopper does.'

foreach ($target in @('CONFIRMED', 'PAYMENT_PENDING', 'PAID')) {
    Write-Step "Admin moves the order to $target"
    $r = Invoke-Api -Method PATCH -Uri "$OrderSvc/api/v1/orders/$orderId/status" -Token $adminToken -Body @{ status = $target }
    Assert-Status $r 200 "Transition to $target" | Out-Null
}

Write-Step 'Trying to cancel an order that has been PAID'
$r = Invoke-Api -Method PATCH -Uri "$OrderSvc/api/v1/orders/$orderId/cancel" -Token $adminToken
if (Assert-Status $r 409 'Cancelling after payment is refused') {
    Write-Info "detail: $($r.Body.detail)"
}
Write-Lesson 'Money has changed hands - that needs a refund flow, not a status flip.'

Write-Step 'Trying to jump from PAID straight to DELIVERED'
$r = Invoke-Api -Method PATCH -Uri "$OrderSvc/api/v1/orders/$orderId/status" -Token $adminToken -Body @{ status = 'DELIVERED' }
Assert-Status $r 409 'Skipping ahead in the lifecycle is refused' | Out-Null

# ---------------------------------------------------------------------------
Write-Header 'CHECK 7 - Cancelling is idempotent, and ownership is checked first'

Write-Step 'Creating a fresh product and order to cancel'
$r = Invoke-Api -Method POST -Uri "$ProductSvc/api/v1/products" -Token $adminToken -Body @{
    sku = "$sku-B"; name = 'Cotton cap'; price = 10.00; currency = 'CAD'; categoryId = $categoryId
}
$product2 = $r.Body.id
$r = Invoke-Api -Method POST -Uri "$OrderSvc/api/v1/orders" -Token $bobToken -Body @{
    customerId = $bob.Id; shippingAddressId = $addressId
    items = @(@{ productId = $product2; quantity = 1 })
}
if (-not (Assert-Status $r 201 'Second order placed')) { exit 1 }
$cancelId = $r.Body.id

Write-Step 'carol tries to cancel BOB''s order'
$r = Invoke-Api -Method PATCH -Uri "$OrderSvc/api/v1/orders/$cancelId/cancel" -Token $carolToken
Assert-Status $r 403 'You may not cancel someone else''s order' | Out-Null

Write-Step 'Confirming carol''s attempt changed nothing'
$r = Invoke-Api -Method GET -Uri "$OrderSvc/api/v1/orders/$cancelId" -Token $bobToken
if ($r.Body.status -eq 'PENDING') {
    Write-Pass "The order is still PENDING - the refusal happened BEFORE any change"
} else {
    Write-Fail "Expected PENDING but the order is $($r.Body.status)"
}
Write-Lesson 'This is why cancel checks ownership inside the service rather than with'
Write-Lesson '@PostAuthorize: a post-check would cancel the order and only then refuse'
Write-Lesson 'to show you the result. The damage would already be done.'

Write-Step 'bob cancels his own order'
$r = Invoke-Api -Method PATCH -Uri "$OrderSvc/api/v1/orders/$cancelId/cancel" -Token $bobToken
Assert-Status $r 200 'First cancel' | Out-Null

Write-Step 'bob cancels the same order again'
$r = Invoke-Api -Method PATCH -Uri "$OrderSvc/api/v1/orders/$cancelId/cancel" -Token $bobToken
if (Assert-Status $r 200 'Second cancel returns 200, not an error') {
    Write-Info "status = $($r.Body.status)"
}
Write-Lesson 'Idempotent: repeating an operation gives the same result. Networks retry.'

# ---------------------------------------------------------------------------
Write-Header 'CHECK 8 - Login is rate limited'

Write-Step 'Registering a fresh customer for the rate-limit test'
$dave = New-TestCustomer -Label 'dave'

Write-Step 'Five wrong passwords for dave'
$allWrong401 = $true
foreach ($i in 1..5) {
    $r = Invoke-Api -Method POST -Uri "$UserSvc/api/v1/auth/login" -Body @{ email = $dave.Email; password = "wrong-guess-$i" }
    if ($r.Status -ne 401) { $allWrong401 = $false; Write-Fail "Attempt $i expected 401 but got $($r.Status)" }
}
if ($allWrong401) { Write-Pass 'Five wrong passwords -> five ordinary 401s' }

Write-Step 'Sixth attempt - with the CORRECT password'
$r = Invoke-Api -Method POST -Uri "$UserSvc/api/v1/auth/login" -Body @{ email = $dave.Email; password = $Password }
if (Assert-Status $r 429 'Refused even with the right password') {
    Write-Info "retryAfterSeconds: $($r.Body.retryAfterSeconds)"
}
Write-Lesson 'The limit is checked BEFORE the password. Otherwise a script could keep guessing'
Write-Lesson 'and learn the answer the moment one guess slipped through.'

Write-Step 'The same test against an email that does not exist'
$ghost = "nobody-$(Get-Random -Maximum 999999)@example.com"
foreach ($i in 1..5) {
    Invoke-Api -Method POST -Uri "$UserSvc/api/v1/auth/login" -Body @{ email = $ghost; password = "guess-$i" } | Out-Null
}
$r = Invoke-Api -Method POST -Uri "$UserSvc/api/v1/auth/login" -Body @{ email = $ghost; password = 'guess-6' }
Assert-Status $r 429 'An unknown email is limited exactly like a real one' | Out-Null
Write-Lesson 'If only real accounts were limited, the 429 would reveal which emails are registered.'

# ---------------------------------------------------------------------------
# Clean up the privilege this run granted itself.
#
# Every run of this script promotes a throwaway customer to ADMIN. Without this
# step those accounts accumulate: after six runs the database held seven
# administrators, one real and six forgotten - a quarter of all accounts able to
# read every customer record. Leftover privileged test accounts are a well-worn
# route from one compromised login to a whole database.
#
# The rows are kept, only the role is dropped. Nothing is deleted.
Write-Header 'CLEANUP - dropping the ADMIN role this run granted'

$cleanupSql = "UPDATE customers SET role = 'CUSTOMER' WHERE id = '$($alice.Id)'"
docker exec ecommerce-postgres psql -U $superuser -d ecommerce_user_db -c $cleanupSql 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) {
    Write-Host '   [OK]   alice demoted back to CUSTOMER - no admin left behind' -ForegroundColor Green
} else {
    Write-Host '   [WARN] Could not demote alice. Check for leftover admins with:' -ForegroundColor Yellow
    Write-Host "          SELECT email, role FROM customers WHERE role = 'ADMIN'" -ForegroundColor Yellow
}
Write-Lesson 'A test suite that writes to a shared database has to tidy up after itself.'
Write-Lesson 'The integration tests avoid this entirely by using a throwaway Testcontainers'
Write-Lesson 'database - but this script runs against the real stack, which is the point of it.'

# ---------------------------------------------------------------------------
Write-Header 'SUMMARY'

Write-Host ''
Write-Host "  Passed: $script:Passed" -ForegroundColor Green
Write-Host "  Failed: $script:Failed" -ForegroundColor $(if ($script:Failed -gt 0) { 'Red' } else { 'DarkGray' })
Write-Host ''
Write-Host '  Test data created by this run (left in place, nothing deleted):' -ForegroundColor DarkGray
Write-Host "    alice         $($alice.Email)  (was ADMIN during the run, now demoted)" -ForegroundColor DarkGray
Write-Host "    bob           $($bob.Email)" -ForegroundColor DarkGray
Write-Host "    carol         $($carol.Email)" -ForegroundColor DarkGray
Write-Host "    dave          $($dave.Email)  (rate-limited for about a minute)" -ForegroundColor DarkGray
Write-Host "    order         $orderId" -ForegroundColor DarkGray
Write-Host ''
Write-Host "  All three log in with the password: $Password" -ForegroundColor DarkGray
Write-Host ''

if ($script:Failed -eq 0) {
    Write-Host '  All checks passed. Authentication, authorization and rate limiting work end to end.' -ForegroundColor Green
} else {
    Write-Host '  Some checks failed - scroll up for the [FAIL] lines.' -ForegroundColor Red
}
Write-Host ''
