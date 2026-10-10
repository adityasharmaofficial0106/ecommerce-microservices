# E-commerce Microservices

A production-patterned e-commerce backend: three independently deployable Spring Boot services,
each owning its own PostgreSQL database, secured with RS256-signed JWTs and communicating over REST.

**Java 26 · Spring Boot 3.5 · PostgreSQL 16 · Docker · OpenFeign · Flyway · Testcontainers**

---

## Architecture

| Service | Port | Owns | Responsibility |
|---|---|---|---|
| user-service | 8081 | `ecommerce_user_db` | Customers, addresses, authentication, token issuing |
| product-service | 8082 | `ecommerce_product_db` | Catalogue, categories, search, batch price lookup |
| order-service | 8083 | `ecommerce_order_db` | Order placement, lifecycle, cross-service orchestration |

Databases are isolated by separate logins — no service can read another's tables, and there are
no cross-service foreign keys. `order-service` is the only service that calls the others,
synchronously via OpenFeign, forwarding the caller's JWT downstream.

```
                            client
                              |
                              v
                     +------------------+
                     |  order-service   |  :8083
                     +--------+---------+
                              |
                  forwards the caller's JWT
                              |
              +---------------+---------------+
              v                               v
     +-----------------+           +-------------------+
     |  user-service   | :8081     |  product-service  | :8082
     |                 |           |                   |
     |  customer +     |           |  batch price      |
     |  address        |           |  lookup           |
     +-----------------+           +-------------------+
```

## Quick start

```bash
cp .env.example .env          # set every "change-me"
docker compose up -d postgres # waits for healthy
mvn clean package -DskipTests
```

Run each service in its own terminal with the environment loaded. **Start user-service first** —
it generates the RSA key pair on its first run, and the other two refuse to start without the
public key.

```bash
set -a; source .env; set +a
mvn -pl user-service spring-boot:run      # then product-service, then order-service
```

<details>
<summary>Windows PowerShell</summary>

```powershell
.\scripts\run.ps1 user        # loads .env, validates it, then starts the service
.\scripts\run.ps1 product
.\scripts\run.ps1 order
```
</details>

Then populate a realistic catalogue and order history, and verify the platform end to end:

```powershell
.\scripts\doctor.ps1          # diagnoses the environment: ports, container, credentials, health
.\scripts\seed-data.ps1       # 8 customers, 24 products, orders across every lifecycle state
.\scripts\verify-phase2.ps1   # 48 checks: authentication, authorization, business rules
```

API docs: `/swagger-ui.html` on each port. Health: `/actuator/health`.

## Security model

Authentication is issued once by user-service at `POST /api/v1/auth/login` and verified
**locally** by every service — no service calls another to validate a token, so authentication
survives user-service being down.

Tokens are signed with **RS256**: user-service holds the private key and is the only service on
the platform that can issue a token. The other two hold the public key, which verifies signatures
and is mathematically incapable of producing one.

```
   user-service                 product-service / order-service
   +---------------------+      +---------------------------+
   |  PRIVATE key        |      |  PUBLIC key               |
   |  signs + verifies   |      |  verifies only            |
   |  never leaves here  |      |  CANNOT issue a token     |
   +---------------------+      +---------------------------+
```

The key pair is generated on first startup and written to `keys/`, which is git-ignored. A leaked
private key would allow anyone to mint a token claiming any customer id with the `ADMIN` role.

| Rule | Enforcement |
|---|---|
| Default deny | `anyRequest().authenticated()` is the last rule; new endpoints ship closed |
| Public by exception | Login, registration, catalogue reads, Swagger, health probes |
| Ownership | `#id == authentication.principal` on every customer-scoped endpoint — the IDOR guard |
| Roles | Catalogue writes and order fulfilment are `ADMIN` only |
| Token propagation | order-service forwards the caller's token, so downstream ownership rules apply unchanged |
| Stateless | No `HttpSession` is ever created; any instance can serve any request |

Login is rate limited per IP and per email (token buckets), checked before the password is.
Passwords are BCrypt-hashed. Login failures are indistinguishable — an unknown email and a wrong
password return the same 401, so the API cannot be used to enumerate registered addresses.

## Design decisions

The reasoning behind each of these is in [docs/phase-1-design.md](docs/phase-1-design.md) and
[docs/CHANGELOG.md](docs/CHANGELOG.md).

- **Database per service**, enforced by separate logins and `REVOKE ALL ... FROM PUBLIC`
- **Asymmetric token signing (RS256)**, so the ability to verify a token is not the ability to
  issue one — a compromise of any single service cannot escalate to the whole platform
- **Prices are never accepted from the client** — `OrderItemRequest` has no price field, so the
  protection is structural rather than a validation rule someone can forget
- **Orders snapshot what they reference** (unit price, product name, SKU, shipping address), so a
  past order never changes when the catalogue does, and reading an order needs no downstream calls
- **Remote calls happen outside the database transaction**, so a slow dependency cannot exhaust
  the connection pool
- **Downstream failures are translated**: a downstream 404 becomes 422 naming what was missing; a
  timeout or 5xx becomes 503, never 500
- **Order transitions live in a state machine** on the `OrderStatus` enum, not scattered conditionals
- **Business identifiers are not primary keys** — SKU is a unique constraint and is immutable
- **Optimistic locking** via `@Version`; concurrent updates return 409 rather than overwriting
- **Flyway owns the schema**; Hibernate only validates it
- **No shared library between services**, to avoid coupling their release cycles
- **Testcontainers with real PostgreSQL**, not H2

## Documentation

| Document | Contents |
|---|---|
| [Phase 1 design](docs/phase-1-design.md) | Architecture, database design, API contract, communication |
| [Security walkthrough](docs/security-walkthrough.md) | The auth implementation with full source and reasoning |
| [Changelog](docs/CHANGELOG.md) | Every change: what, where, why, and the result |
| [Service walkthroughs](docs/) | Component-by-component explanation of each service |
| [Beginner's guide](docs/beginners-guide.md) | The project in plain language, from zero |

## Known limitations

Deliberate, documented, and deferred rather than hidden:

- **The public key is distributed as a file.** Production systems publish it at a JWKS endpoint
  (`/.well-known/jwks.json`) so verifiers fetch it and key rotation needs no redeployment.
- **Tokens cannot be revoked.** Deactivating a customer leaves their existing token valid until it
  expires. Short expiry (15 minutes) bounds the damage; refresh tokens and a Redis denylist are the
  next step.
- **Login rate limits are per instance.** Each copy of user-service keeps its own counters; a shared
  Redis counter is the next step.
- **No API gateway** — clients call each service directly.

## Roadmap

Resilience (circuit breakers) → event-driven communication with Kafka → Redis caching →
API gateway → observability (Prometheus, Grafana, distributed tracing) → React frontend.

---

Spring Boot 3.5.16 is the final open-source release of the 3.x line; the 4.x upgrade is planned as
a deliberate, documented step.
