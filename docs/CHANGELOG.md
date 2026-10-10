# Change log

Every change to the codebase: **what** changed, **where**, **why**, and **what the result is**.

Newest entries at the top. Each entry is self-contained, so you can read one without the others.

## Contents

**Phase 2 — Security**

- Step 9 — RS256: asymmetric token signing
- Step 8 — Rate limiting on login
- Step 7 — Completing method-level authorization
- Step 6 — Seed data: a shop that looks like a shop
- Step 5 — Documentation set: PDFs and the beginner's guide
- Step 4 — Rewriting the verification script for a secured platform
- Step 3 — Enforcing tokens in product-service and order-service
- Step 2 — Enforcing tokens in user-service
- Step 1 — Login and token issuing in user-service

**Phase 1 — Core services**

- Step 3 — order-service
- Step 2 — product-service
- Step 1 — user-service and foundations

---

## Phase 2, Step 9 — RS256: asymmetric token signing

Closes the last known limitation of Phase 2, named in Step 3 as a deliberate deferral.

### The problem

All three services shared one `JWT_SECRET`. With HS256 the key that **verifies** a signature is
the key that **creates** one — there is no way to grant a service the ability to check tokens
without also granting it the ability to issue them.

So product-service and order-service each held everything needed to build
`{"sub": "<any customer id>", "role": "ADMIN"}`, sign it, and present it anywhere on the platform.
Any single service being compromised meant the whole platform was compromised. "This service has
no issue method" was a convention, not a guarantee.

### The fix

```
   user-service                      product-service / order-service
   +-------------------------+       +----------------------------+
   |  PRIVATE key  - signs   |       |  PUBLIC key - verifies     |
   |  PUBLIC key   - verifies|       |  CANNOT issue a token      |
   |  never leaves this app  |       |                            |
   +-------------------------+       +----------------------------+
```

RSA splits the key in two. The public half verifies signatures and is mathematically incapable of
producing them, so it is safe to distribute. The private half stays in user-service, which becomes
the only thing on the platform that can mint an identity.

### What changed

| File | New / Changed | What it does |
|---|---|---|
| `user-service/.../configuration/RsaKeyProvider.java` | **New** | Loads the key pair from disk, or generates a 2048-bit pair and writes it as PEM on first startup |
| `product-service/.../configuration/PublicKeyProvider.java` | **New** | Loads the public key only; refuses to start if the file is missing |
| `order-service/.../configuration/PublicKeyProvider.java` | **New** | Same, in order-service |
| `user-service/.../service/JwtService.java` | Changed | `signWith(privateKey, Jwts.SIG.RS256)`; verifies with the public key |
| `product-service/.../configuration/JwtService.java` | Changed | `verifyWith(publicKey)`; no `SecretKey` anywhere |
| `order-service/.../configuration/JwtService.java` | Changed | Same, in order-service |
| `JwtProperties.java` × 3 | Changed | `secret` removed. user-service keeps `expiryMinutes` + `issuer`; the other two keep `issuer` only |
| `application.yml` × 3 | Changed | `jwt.secret` replaced by `jwt.key-directory` / `jwt.public-key-path` |
| `.gitignore` | Changed | Adds `keys/`, `*.pem`, `*.p12`, `*.jks` |
| `.env.example` | Changed | `JWT_SECRET` gone; documents that keys are generated, not configured. Adds the pgAdmin variables Compose requires |
| `docker-compose.yml` | Changed | Mounts `./keys` — read-write for user-service, **read-only** for the other two |
| `scripts/run.ps1` | Changed | Checks for the key pair instead of a secret, and skips test compilation |
| `scripts/doctor.ps1` | Changed | Reports RS256, and warns if a stale `JWT_SECRET` is still in `.env` |
| `user-service/.../JwtServiceTest.java` | Changed | Keys generated into a JUnit temp folder; new tests for RS256 |
| `user-service/.../AuthServiceTest.java` | Changed | Same |
| `user-service/.../SecurityRulesTest.java` | Changed | Loads `AuthController` into the slice |
| `user-service/.../CustomerApiIntegrationTest.java` | Changed | Authenticates its own DELETE; keys go to `target/test-keys` |

### Why the key is generated rather than configured

A secret in `.env.example` gets copied verbatim into real deployments. A secret in a README ends
up in the repository forever. Generating on first startup means the private key is never typed,
pasted, emailed or committed — it simply appears on the machine that needs it and nowhere else.

`keys/` and `*.pem` went into `.gitignore` **before** the first run, because git history is
permanent: a private key committed and deleted in the next commit is still in every clone, and
the only real remedy is generating a new pair and treating the old one as burned.

### Two test failures fixed along the way

Both pre-dated this change and had been hidden by building with `-DskipTests`. Both turned out to
be bugs in the tests, not the application:

- `SecurityRulesTest.loginIsPublic` expected 400, got **404**. `@WebMvcTest(CustomerController.class)`
  builds a context containing only that controller, so `/api/v1/auth/login` did not exist. The test
  would have passed identically if the endpoint had been deleted — it could not fail for the reason
  it was written to catch.
- `CustomerApiIntegrationTest.login_afterDeactivation_isRefused` expected 204, got **401**. The test
  sent a `DELETE` with no `Authorization` header. The filter chain was correct to refuse it.

### Result

| | Before | After |
|---|---|---|
| Services able to issue a token | 3 | **1** |
| Effect of compromising order-service | admin token for the whole platform | order data only |
| Key rotation | coordinate a restart of all three | replace the private key, publish the new public one |
| Key distribution | must stay secret in three places | public half is safe to publish |

`mvn -pl user-service test` — 67 tests, 0 failures. `verify-phase2.ps1` — 48/48, including the
token-relay check that exercises all three services with one token.

### Known consequences

- **Every token issued before the switch is now invalid.** Nothing can verify them. Clients log in
  again; with a 15-minute expiry the window is small.
- **user-service must start first on a fresh checkout.** It generates the pair; the other two
  refuse to start without `keys/jwt-public.pem`. This is fail-fast by design — a verifier with no
  key cannot authenticate anybody, and failing at startup beats failing on every request.
- **The public key is distributed as a file.** Production systems publish it at
  `/.well-known/jwks.json` so verifiers fetch it and rotation needs no redeployment. Deferred.
- **Losing `keys/` logs everybody out.** The pair is not backed up anywhere; a new one is generated
  and all existing tokens stop verifying.

---

## Phase 2, Step 8 — Rate limiting on login

### What changed

| File | New / Changed | What it does |
|---|---|---|
| `user-service/pom.xml` | Changed | Adds Caffeine; version supplied by Spring Boot's dependency management |
| `user-service/.../application.yml` | Changed | New `login-rate-limit` block: per-IP and per-email limits, cache bound |
| `configuration/LoginRateLimitProperties.java` | **New** | Typed settings, with safe defaults if the block is missing |
| `configuration/RateLimitConfig.java` | **New** | Registers those settings — on its own `@Configuration` class |
| `ratelimit/TokenBucket.java` | **New** | The algorithm: capacity, steady refill, injected clock |
| `ratelimit/LoginRateLimiter.java` | **New** | Two buckets per attempt — one per IP, one per submitted email |
| `exception/TooManyLoginAttemptsException.java` | **New** | Carries the wait time |
| `exception/GlobalExceptionHandler.java` | Changed | 429 with a `Retry-After` header and problem JSON |
| `service/AuthService.java` | Changed | Checks the limiter first; a successful login resets that email's bucket |
| `controller/AuthController.java` | Changed | Passes the client address to the service |
| `ratelimit/TokenBucketTest.java`, `LoginRateLimiterTest.java` | **New** | Arithmetic and rules, with a fake clock — no sleeping |
| `service/AuthServiceTest.java`, `controller/AuthControllerTest.java` | Changed | New signature; limiter-first, normalisation, 429 tests |
| `scripts/verify-phase2.ps1` | Changed | CHECK 8: five wrong passwords, then the right one is refused |

### Why

Nothing stopped a script trying thousands of passwords against `/auth/login`. BCrypt slows each
attempt, but a list of the 10,000 most common passwords would still get into any account that
used one.

### How it works

A **token bucket** holds a few attempts, each try takes one, and they drip back at a steady rate.
Forgiving to people — three typos still leave tries, and more return within minutes — and hard on
scripts, which get a short burst and then one guess per interval.

Every attempt needs a token from **two** buckets:

| Bucket | Limit | Stops |
|---|---|---|
| Per IP | 50, then +1 every 2 seconds | One machine trying many accounts — password spraying |
| Per email | 5, then +1 per minute | Many machines trying one account — a botnet |

Either alone has a hole the other covers.

### Decisions worth remembering

- **Checked before the database and before BCrypt.** A refused attempt costs the server almost
  nothing, and even the *correct* password is refused once the bucket is empty — otherwise a
  script could keep guessing and learn the answer the moment one guess landed.
- **The email bucket counts unknown emails too.** If only real accounts were limited, a 429 would
  reveal which emails are registered — user enumeration through a side door.
- **A successful login resets that email's bucket**, so earlier typos are forgiven. The IP bucket
  is not reset: one correct password must not refill the budget for everyone on that address.
- **Bounded memory.** Buckets live in a Caffeine cache with a maximum size, so a flood of random
  emails cannot exhaust the heap. Idle buckets expire — but only after comfortably longer than a
  bucket takes to refill, so waiting for eviction is never faster than waiting for the refill.
- **Hand-written algorithm rather than a library.** About forty lines, fully tested, and the
  algorithm is the lesson. Bucket4j is the usual production library.
- **The client address comes from the TCP connection**, not `X-Forwarded-For`, which any client can
  forge. Behind a gateway this must change to trust the header only from that gateway.

### Result

| Request | Before | After |
|---|---|---|
| Sixth wrong password within a minute | 401 | **429**, `Retry-After: 60` |
| Correct password after five wrong ones | 200 | **429** until the wait passes |
| Six attempts at an email that doesn't exist | 401 each time | **429** — identical to a real account |
| Correct password on the first try | 200 | 200 |

### Known consequences

- **Per instance.** Three copies of user-service give an attacker three times the attempts. A
  shared counter in Redis fixes this in the Redis phase.
- **Cache eviction under a flood.** With enough distinct random emails, a partly drained bucket can
  be evicted early. The per-IP limit makes that slow from one machine; a shared Redis counter
  removes it.
- **Shared addresses.** Everyone behind one office or mobile-carrier address shares the 50-attempt
  IP budget. Generous for normal use; worth revisiting with real traffic.

---

## Phase 2, Step 7 — Completing method-level authorization

### What changed

| File | New / Changed | What it does |
|---|---|---|
| `product-service/.../controller/ProductController.java` | Changed | `@PreAuthorize("hasRole('ADMIN')")` on create, update, delete |
| `product-service/.../controller/CategoryController.java` | Changed | Same three rules |
| `product-service/.../exception/GlobalExceptionHandler.java` | Changed | Handlers for `AccessDeniedException` (403) and `AuthenticationException` (401), placed above the catch-all |
| `order-service/.../controller/OrderController.java` | Changed | `@PreAuthorize` on place and on status; `@PostAuthorize` on read; cancel passes the caller's id and admin flag to the service |
| `order-service/.../controller/CustomerOrderController.java` | Changed | Class-level rule: your own history, or any if ADMIN |
| `order-service/.../service/OrderService.java` | Changed | `cancelOrder(id, callerId, callerIsAdmin)` checks ownership after loading, before mutating |
| `order-service/.../exception/GlobalExceptionHandler.java` | Changed | Same two handlers, plus `DownstreamAuthException` → 401 |
| `order-service/src/test/.../OrderServiceTest.java` | Changed | Calls updated to the new `cancelOrder` signature |
| `order-service/src/test/.../OrderControllerTest.java` | Changed | Stub updated to argument matchers for the new signature |
| `product-service/.../ProductServiceApplication.java`, `order-service/.../OrderServiceApplication.java` | Changed | `@EnableConfigurationProperties(JwtProperties.class)` |
| `product-service` and `order-service` `application.yml` | Changed | `jwt.secret` and `jwt.issuer` block |
| `scripts/run.ps1` | **New** | Loads `.env`, checks `JWT_SECRET`, starts one service |
| `scripts/seed-data.ps1` | Changed | Handles paged and bare-array responses; follows the real state machine (PROCESSING between PAID and SHIPPED) |
| `scripts/verify-phase2.ps1` | Changed | A different category name per attempt, so a security failure is not masked by a 409 |

### Why

`verify-phase2.ps1` reported 8 failures. Path-level rules (401 with no token) worked in all
three services, but product-service and order-service controllers had never received their
`@PreAuthorize` annotations. Any logged-in customer could create products, read other
customers' orders, push an order through fulfilment, and cancel orders that were not theirs.

### Three techniques, and where each was used

| Technique | Used on | Why |
|---|---|---|
| `@PreAuthorize` | Place order, change status, catalogue writes, order history | The rule needs only the URL or request body |
| `@PostAuthorize` | `GET /orders/{id}` | The owner is in the database; load first, then check. Safe only because it is a read |
| Check inside the service | `PATCH /orders/{id}/cancel` | Needs the loaded order AND changes data — a post-check would cancel first and refuse afterwards |

### What went wrong along the way, and what each taught

- **Partial file copies.** Every failure after the product-service and order-service security changes traced to zip copies that silently
  dropped files — pom dependencies, a `yml` block, controllers, `TokenRelayInterceptor`.
  Fixed permanently by moving to Git: `git status --short` identified the stale files in one
  command, after days of guessing.
- **Editor versus disk.** A fix sat in an unsaved VS Code buffer while Maven compiled the old
  file. The stack trace named an expression that no longer appeared in the editor. Lesson: the
  log shows what is *running*; the editor shows what you *meant*. Fixed by enabling Auto Save.
- **An `or` that only broke for customers.** `hasRole('ADMIN') or #request.customerId() ...` on a
  method with no `request` parameter short-circuited for admins and threw for everyone else.
  An admin-only test would have passed. This is why every rule is tested from both sides.
- **Unset environment variables.** `jwt.secret must be at least 32 characters; got 13` — 13 is the
  length of the literal text `${JWT_SECRET}`. Recurred five times; ended by `run.ps1`.
- **Identity must never be a request parameter.** An intermediate edit gave the cancel endpoint
  `UUID callerId, boolean callerIsAdmin` parameters. Spring binds those from the query string,
  so `?callerIsAdmin=true` would have granted admin to anyone. Identity comes only from the
  verified token.
- **Wrong advice, corrected.** `@EnableConfigurationProperties` placed on the properties record
  compiles but does nothing; it must sit on a `@Configuration` class. `@ConfigurationProperties`
  declares a mapping; `@EnableConfigurationProperties` registers it.
- **A stale build hides a broken source tree.** product-service appeared to work until a
  `clean` build removed old `.class` files. `clean` tells the truth.

### Result

`verify-phase2.ps1`: **45 of 45 passing.** Seed data creates a full order history across every
lifecycle state.

| Request | Before | After |
|---|---|---|
| Customer creates a category | 201 | **403** |
| Customer reads another customer's order | 200 | **403** |
| Customer reads their own order | 200 | 200 |
| Customer advances an order's status | 200 | **403** |
| Customer cancels another customer's order | 200, order cancelled | **403, order unchanged** |
| Customer places an order in someone else's name | 201 | **403** |

### Known consequences

- `OrderControllerTest` compiles but predates security: it sends no credentials, and the
  controller now reads the caller from the token. It needs security-aware setup before
  `mvn test` passes it. Builds use `-DskipTests` until then.
- HS256 shared secret, non-revocable tokens and no login rate limiting remain, as documented.

---

## Phase 2, Step 6 — Seed data: a shop that looks like a shop

### What changed

| File | New / Changed | What it does |
|---|---|---|
| `scripts/seed-data.ps1` | **New** | Fills the platform with realistic data through the real APIs |
| `.gitignore` | Changed | `.vscode/` is now committed (no secrets in it; it gives anyone cloning the repo one-click launches) |

### Why

The database held only test-rig data — `verify-alice`, one t-shirt, `correct-horse-battery`.
Fine for proving rules; useless for demos, screenshots, or the feel of a working product.

### The important decision: seed through the APIs, not the database

Inserting rows directly would bypass everything built so far — passwords would need hashing by
hand, security rules would never run, prices would not be validated. Going through the APIs
means the seed data is created exactly the way real traffic would be, and doubles as an
end-to-end exercise of the whole platform.

### What it creates

- **Meera Joshi**, store manager — the one ADMIN, promoted via a single database `UPDATE`
- **8 customers** with realistic names, emails, phone numbers and addresses in real Indian
  cities (Indore, Rewa, Ahmedabad, Delhi, Chennai, Mumbai, Hyderabad, Bhopal)
- **6 categories, 24 products** — recognisable items with plausible INR prices
- **~9 orders** spread across the whole lifecycle: delivered, shipped, paid, pending, and one
  cancelled by its customer while still pending

Re-runnable: existing customers are logged into rather than recreated; existing categories and
SKUs are reused. Nothing is ever deleted, including the old verify data — cleanup is a
decision to make together, not something a script does silently.

---

## Phase 2, Step 5 — Documentation set: PDFs and the beginner's guide

### What changed

| File | New / Changed | What it does |
|---|---|---|
| `docs/security-walkthrough.md` | **New** | Phase 2 implementation walkthrough with the full source of every security class and the reasoning behind each |
| `docs/beginners-guide.md` | **New** | The whole project in plain language: every tool explained, from-zero setup, the daily routine, a decoded table of every real error hit so far, and a glossary |
| Four PDFs (generated) | **New** | Documentation, Change Log, Code Walkthrough, Beginner's Guide |

### Why

A milestone (Phase 1 verified + Phase 2 complete) is the right moment to freeze documentation:
the reasoning is still fresh, and the next phase (Git, then Kafka) builds on top of it.

The beginner's guide exists because the project's own history is its best teaching material —
every error in its troubleshooting table genuinely occurred during the build, with the real
cause and the real fix.

### Result

The `docs/` folder is the single source of truth (Markdown, versionable); the PDFs are
generated artifacts for reading and sharing. When Git arrives, the Markdown goes in the repo
and PDFs can be regenerated at any milestone.

---

## Phase 2, Step 4 — Rewriting the verification script for a secured platform

### What changed

| File | New / Changed | What it does |
|---|---|---|
| `scripts/verify-phase2.ps1` | **New** | End-to-end check with authentication and authorization |
| `scripts/verify-phase1.ps1` | Changed | Now exits immediately with a pointer to the new script |
| `README.md` | Changed | Points at the new script and lists what it covers |

### Why

`verify-phase1.ps1` sends no tokens, so after Steps 2 and 3 every protected call returned 401.
That is correct behaviour, not a regression — but it left no single command to confirm the
platform works.

The old script is kept rather than deleted: comparing the two shows exactly what securing a
platform changes about how a client must behave.

### What the new script does that the old one could not

- Registers two customers and **promotes one to ADMIN** with a direct `UPDATE`, because there
  is deliberately no endpoint for granting roles
- Demonstrates that the **pre-promotion token still says CUSTOMER** — a token is a snapshot,
  and changing the database does not reach back into tokens already issued
- Sends a **tampered token** (last six characters replaced) and confirms it is refused
- Checks each rule from both sides: the customer who may, and the customer who may not
- Confirms that a refused cancel **changed nothing** — proving the ownership check runs before
  the state change, not after

### Result

One command reports on the whole platform again. Around 35 checks across seven groups, each
with a short note explaining what it proves.

### Known consequences

- The script needs the `ecommerce-postgres` container running, because promoting to ADMIN goes
  through `docker exec`.
- It creates three customers, one category and two products per run, and deletes nothing. Test
  data accumulates; that is deliberate, since deleting data is never done without asking.

---

## Phase 2, Step 3 — Enforcing tokens in product-service and order-service

### What changed

**product-service**

| File | New / Changed | What it does |
|---|---|---|
| `pom.xml` | Changed | Added `spring-boot-starter-security`, JJWT, `spring-security-test` |
| `application.yml` | Changed | New `jwt:` block — the **same secret** user-service signs with |
| `configuration/JwtProperties.java` | **New** | Binds `jwt.secret` and `jwt.issuer`. No expiry: this service does not issue tokens |
| `configuration/JwtService.java` | **New** | Verify only. There is deliberately no method to create a token |
| `configuration/JwtAuthenticationFilter.java` | **New** | Identifies the caller on every request |
| `configuration/SecurityConfig.java` | **New** | Catalogue reads public, everything else authenticated |
| `configuration/SecurityProblemHandlers.java` | **New** | 401/403 as problem JSON |
| `configuration/OpenApiConfig.java` | Changed | Authorize button in Swagger |
| `controller/ProductController.java`, `CategoryController.java` | Changed | `@PreAuthorize("hasRole('ADMIN')")` on create, update, delete |
| `exception/GlobalExceptionHandler.java` | Changed | Handlers for `AccessDeniedException` (403) and `AuthenticationException` (401) |

**order-service** — the same six security files, plus:

| File | New / Changed | What it does |
|---|---|---|
| `client/TokenRelayInterceptor.java` | **New** | Copies the caller's bearer token onto every outgoing Feign call |
| `client/FeignClientConfig.java` | Changed | Registers the interceptor |
| `client/ServiceErrorDecoder.java` | Changed | A downstream 401/403 becomes `DownstreamAuthException`, not a 500 |
| `exception/DownstreamAuthException.java` | **New** | Mapped to 401 |
| `controller/OrderController.java` | Changed | `@PreAuthorize` on place and status; `@PostAuthorize` on read; explicit check for cancel |
| `controller/CustomerOrderController.java` | Changed | Class-level rule: your own history, or any if ADMIN |
| `service/OrderService.java` | Changed | `cancelOrder` now takes the caller's id and admin flag |
| `service/OrderServiceTest.java` | Changed | Updated signature; two new ownership tests |

No database migrations.

### Why

Both services were still completely open. Anyone could create products, change prices, or read
any customer's entire order history.

There was also a problem created by the previous step: order-service calls user-service for the
customer and address, and those endpoints had just become protected. Without token forwarding,
placing an order would have started failing with 401 from a dependency.

### The decision: token propagation

When order-service calls the others, it **forwards the customer's own token** rather than using
a service identity of its own.

*Why:* user-service then sees "Jane asking about Jane", and the ownership rule written in Step 2
passes with no special case for service callers. Identity survives the whole chain, which also
means logs can trace one customer's action across three services.

*The cost, stated plainly:* a stolen token works against every service, not only the one it was
presented to. The alternative — each service holding its own machine identity with narrow
scopes — is what larger systems do, and is the natural upgrade.

*A real limitation:* `TokenRelayInterceptor` reads the request currently being served on this
thread. That works because Feign calls happen on the same thread as the inbound request. If this
service moves to async or reactive calls, the context will not follow and this class needs
rethinking.

### Three techniques, and why each was used where it was

| Technique | Used for | Why not the others |
|---|---|---|
| `@PreAuthorize` | Place order, list history, admin writes | The rule only needs the URL or request body, both available before the method runs |
| `@PostAuthorize` | `GET /orders/{id}` | The owner is in the database, not the URL, so the order must be loaded first. Safe **only because this is a read** |
| Explicit check in the service | `PATCH /orders/{id}/cancel` | Also needs the loaded order, but cancelling **changes data**. `@PostAuthorize` would let the cancel happen and only then refuse to show it |

That third row is the one worth remembering: `@PostAuthorize` on a mutating method is a real bug,
not a style preference.

### Result

**product-service**

| Request | Before | After |
|---|---|---|
| `GET /products` with no token | 200 | 200 — browsing stays public |
| `GET /products/{id}` with no token | 200 | 200 |
| `POST /products` with no token | 201 | **401** |
| `POST /products` as CUSTOMER | 201 | **403** |
| `POST /products` as ADMIN | 201 | 201 |
| `GET /products/lookup` with no token | 200 | **401** |

**order-service**

| Request | Before | After |
|---|---|---|
| Any endpoint with no token | worked | **401** |
| `POST /orders` for yourself | 201 | 201 — token forwarded downstream |
| `POST /orders` for another customer | 201 | **403** |
| `GET /orders/{id}` for someone else's order | 200 | **403** |
| `GET /customers/{other}/orders` | 200 | **403** |
| `PATCH /orders/{id}/status` as CUSTOMER | 200 | **403** — fulfilment is staff work |
| `PATCH /orders/{id}/cancel` on someone else's order | 200 | **403**, and nothing is changed |

### Known consequences

- **`verify-phase1.ps1` is now broken** — it sends no tokens. It will be rewritten to log in first.
- **The shared secret is a real weakness.** HS256 means every service that can *verify* a token
  can also *mint* one; a compromised product-service could issue itself an admin token. The fix
  is RS256 — user-service holds a private key, the others hold only the public key. Deferred
  deliberately, and worth naming in an interview.
  *(Resolved in Phase 2, Step 9.)*
- **There are no ADMIN users** unless you create one: `UPDATE customers SET role = 'ADMIN'
  WHERE email = '...'`, then log in again for a fresh token.
- **All three services need the same `JWT_SECRET`** in `.env`, and the same `issuer` value.
  A mismatch rejects every token with no obvious clue why.
  *(The shared secret was removed in Phase 2, Step 9; the `issuer` must still match.)*

---

## Phase 2, Step 2 — Enforcing tokens in user-service

### What changed

| File | New / Changed | What it does |
|---|---|---|
| `user-service/pom.xml` | Changed | Swapped `spring-security-crypto` for the full `spring-boot-starter-security`; added `spring-security-test` |
| `configuration/JwtAuthenticationFilter.java` | **New** | Reads the `Authorization` header on every request, verifies the token, records who the caller is |
| `configuration/SecurityConfig.java` | **New** | Decides which endpoints need a token; wires the filter into the chain |
| `configuration/SecurityProblemHandlers.java` | **New** | Makes 401/403 responses look like every other error (problem JSON) |
| `configuration/OpenApiConfig.java` | Changed | Adds the **Authorize** button to Swagger UI |
| `service/JwtService.java` | Changed | Added `parseToken()` — previously it could only *create* tokens, not *verify* them |
| `controller/CustomerController.java` | Changed | Added `@PreAuthorize` rules to five endpoints |
| `controller/AddressController.java` | Changed | Added one `@PreAuthorize` rule at class level (applies to all its methods) |
| `exception/GlobalExceptionHandler.java` | Changed | Added handlers for `AccessDeniedException` (403) and `AuthenticationException` (401) |
| `controller/SecurityRulesTest.java` | **New** | Tests the authorization rules as behaviour |
| `controller/CustomerControllerTest.java` | Changed | Imports the security beans; two tests now run as an admin |
| `controller/AuthControllerTest.java` | Changed | Imports the security beans |
| `integration/CustomerApiIntegrationTest.java` | Changed | Logs in and sends real tokens; added an "other customer is forbidden" test |

No database migration. No change to product-service or order-service.

### Why

Every endpoint was reachable by anyone. `GET /api/v1/customers` returned every customer's
email and phone number with no credentials at all, and `DELETE` would deactivate any of them.

A token that nothing checks is just a string. Step 1 made tokens; this step makes them matter.

### How it works, briefly

1. A request arrives with `Authorization: Bearer <token>`
2. `JwtAuthenticationFilter` runs **before any controller** — it verifies the signature and
   expiry locally (no database, no call to another service) and writes the caller's id and role
   into Spring Security's per-request `SecurityContext`
3. `SecurityConfig` decides whether this path is allowed to proceed unauthenticated
4. `@PreAuthorize` on the controller method applies the finer rule

The filter itself never rejects anything. It only *identifies*. Rejecting is
`SecurityConfig`'s job. Keeping those separate is what lets one filter serve both public and
protected endpoints.

### Two decisions worth remembering

**Default deny.** The config protects `anyRequest()` and then names the exceptions. The reverse
arrangement — listing what to protect — means any endpoint added later ships wide open, silently.
This way a forgotten endpoint fails closed: annoying, visible, fixed in a minute.

**Ownership checks.** `#id == authentication.principal` compares the id in the URL to the id in
the token. Without it, any logged-in customer could read any other customer by changing the
number in the URL. That bug class is called **IDOR** (insecure direct object reference) and is
consistently the most common real-world API vulnerability.

### A bug this step had to avoid

`@PreAuthorize` throws `AccessDeniedException` **inside** the controller call. The existing
catch-all `@ExceptionHandler(Exception.class)` would have caught it and returned **500** instead
of **403** — misleading to the caller, and it would hide a genuine security signal in the logs.

An explicit handler now sits above the catch-all, and `SecurityRulesTest` fails if it is removed.

### Result

| Request | Before | After |
|---|---|---|
| `GET /customers` with no token | 200, full list | **401** |
| `GET /customers` as a CUSTOMER | 200, full list | **403** |
| `GET /customers` as an ADMIN | 200 | 200 |
| `GET /customers/{own-id}` as that customer | 200 | 200 |
| `GET /customers/{someone-else}` as a customer | 200 | **403** |
| `POST /auth/login` | 200 | 200 (still public) |
| `POST /customers` (register) | 201 | 201 (still public) |
| `/swagger-ui.html`, `/actuator/health` | 200 | 200 (still public) |

### Known consequences

- **`verify-phase1.ps1` now fails** on user-service calls, because it sends no tokens. This is
  correct behaviour, not a regression. The script is updated once all three services are secured.
- **product-service and order-service are still open.** Anyone can create products or read orders.
- **Changing a role in the database does not change tokens already issued.** Promote yourself to
  ADMIN and you must log in again; the old token still says CUSTOMER. Same reason a banned user
  keeps working until their token expires.

---

## Phase 2, Step 1 — Login and token issuing in user-service

### What changed

| File | New / Changed | What it does |
|---|---|---|
| `user-service/pom.xml` | Changed | Added the JJWT library (api compile-scope, impl + jackson runtime) |
| `db/migration/V3__add_role_to_customers.sql` | **New** | Adds a `role` column to `customers` |
| `entity/Role.java` | **New** | `CUSTOMER` or `ADMIN` |
| `entity/Customer.java` | Changed | New `role` field; the constructor always sets `CUSTOMER` |
| `configuration/JwtProperties.java` | **New** | Type-safe binding of the `jwt.*` settings |
| `service/JwtService.java` | **New** | Builds signed tokens |
| `service/AuthService.java` | **New** | Verifies email + password, issues a token |
| `dto/LoginRequest.java`, `dto/TokenResponse.java` | **New** | The login contract |
| `controller/AuthController.java` | **New** | `POST /api/v1/auth/login` |
| `exception/InvalidCredentialsException.java` | **New** | Mapped to 401 |
| `repository/CustomerRepository.java` | Changed | Added `findByEmail` |
| `application.yml` | Changed | New `jwt:` block reading `JWT_SECRET` and `JWT_EXPIRY_MINUTES` |
| `.env.example`, `docker-compose.yml` | Changed | Pass the JWT settings through |

### Why

Before this, the platform had no concept of "who is calling". A token is the thing that lets
three separate services agree on an identity without any of them sharing a session store or
phoning user-service on every request.

### Decisions worth remembering

**Login lives in user-service, not a separate auth-service.** A separate service was built first
and then removed: it needed a fourth database created by hand, and split registration into two
writes across two databases with no shared transaction — requiring compensating deletes. Real
problems, worth learning, but a detour from understanding tokens.

**The `DEFAULT 'CUSTOMER'` in the migration is load-bearing.** The `customers` table already had
rows. A `NOT NULL` column cannot be added to a populated table without saying what goes in the
existing rows.

**Login failures are indistinguishable.** Unknown email, wrong password, and deactivated account
all return the same 401 with the same message. Different messages would let anyone discover
which addresses are registered — an attack called **user enumeration**.

**No default for `jwt.secret`.** A fallback value is how a project ships with a signing key that
anyone reading the repository already knows. Missing secret means the service refuses to start.

### Result

- `POST /api/v1/auth/login` returns `{ accessToken, tokenType: "Bearer", expiresIn: 900 }`
- The token carries `sub` (customer id), `role`, `iss`, `jti`, `iat`, `exp`
- Existing customers all became `CUSTOMER` when V3 ran; no data was lost
- Nothing checked the token yet — that was Step 2

---

## Phase 1, Step 3 — order-service

### What changed

New module `order-service` (port 8083), new database `ecommerce_order_db`, two migrations
(`orders`, `order_items`). Added to the root `pom.xml` and `docker-compose.yml`.

Key files: `entity/OrderStatus.java` (the state machine), `entity/Order.java` (aggregate root),
`entity/ShippingAddress.java` (embedded snapshot), `client/UserServiceClient.java` and
`client/ProductServiceClient.java` (OpenFeign), `client/ServiceErrorDecoder.java`,
`service/OrderService.java`.

### Why

An order needs customer and product data owned by other services. This is the service where the
project stops being three apps and becomes a distributed system.

### Decisions worth remembering

- **Prices never come from the client.** `OrderItemRequest` has only `productId` and `quantity`.
  A price field would let anyone buy anything for a cent.
- **One batch call for the whole cart**, not one per line. Otherwise it is the N+1 problem
  moved onto the network.
- **Orders snapshot what they reference** — unit price, product name, SKU, shipping address —
  so a past order never changes when the catalogue does.
- **`createOrder` is deliberately not `@Transactional`.** Annotating it would hold a database
  connection open across three HTTP calls; under load a slow dependency becomes a database outage.
- **Timeouts on every Feign client** (2s connect, 3s read). Without them, one hung dependency
  exhausts this service's threads and takes it down.
- **Downstream errors are translated**: a downstream 404 becomes our 422 naming what was
  missing; a timeout or 5xx becomes 503, never 500.

### Result

Placing an order calls all three services and computes the total from catalogue prices.
Reading an order needs no downstream calls at all — proven by an integration test.

---

## Phase 1, Step 2 — product-service

### What changed

New module `product-service` (port 8082), database `ecommerce_product_db`, migrations for
`categories` and `products`.

### Decisions worth remembering

- **Specifications for search.** Five optional filters means 32 possible combinations; derived
  query method names cannot cover that. `ProductSpecifications` builds the WHERE clause at runtime.
- **`/products/lookup` batch endpoint**, so order-service prices a whole cart in one call.
- **Money is `BigDecimal` / `NUMERIC(19,2)`**, never `double`. Floating point cannot represent
  `0.1` exactly.
- **SKU is a unique constraint, not the primary key**, and cannot be updated. Business
  identifiers change; primary keys should not.
- **422 introduced** for "well-formed request, unusable reference" — e.g. creating a product in
  a deactivated category.

---

## Phase 1, Step 1 — user-service and foundations

### What changed

Repository skeleton, aggregator `pom.xml`, `docker-compose.yml` with PostgreSQL, the init
script creating three databases and three logins, and the complete `user-service`.

### Decisions worth remembering

- **Database per service**, enforced by separate logins with `REVOKE ALL ... FROM PUBLIC`.
- **No cross-service foreign keys.**
- **DTOs everywhere**; entities are never serialised.
- **Flyway owns the schema**, Hibernate only validates it.
- **Soft delete** for customers, because orders reference them.
- **Optimistic locking** via `@Version`; concurrent updates return 409.
- **RFC 9457 Problem Details** for every error.
- **No shared `common` module** between services — that is the fastest route to a distributed
  monolith.
- **Testcontainers with real PostgreSQL**, not H2.

### Environment issues solved along the way

| Problem | Cause | Fix |
|---|---|---|
| Port 5432 already in use | A Windows PostgreSQL service was running | Stopped `postgresql-x64-18`; set its startup to Manual |
| `password authentication failed` | The init script runs once; editing `.env` afterwards does not change existing roles | `ALTER ROLE <name> WITH PASSWORD '<value>'` |
| `${USER_DB_USERNAME}` sent literally as the username | VS Code had the wrong folder open, so `${workspaceFolder}/.env` did not resolve | Reopened the inner `ecommerce-platform` folder |
| `verify-phase1.ps1` reported healthy services as down | Actuator's content type made PowerShell return bytes, not a string | Decode bytes before parsing; trust the status code |
| `HttpResponseException` type not found | That type only exists in PowerShell 7; this is Windows PowerShell 5.1 | Untyped `catch` |
