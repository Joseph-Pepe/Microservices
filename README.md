# Microservice Ecosystem 🚀

A horizontally scalable microservice architecture engineered with **Java 26 Virtual Threads (Project Loom)**, **Project Leyden AOT Cache (JEP 514/515/516)**, **Spring Boot 4.0+**, **Spring Cloud Gateway Server WebMVC**, **Valkey 8 / Redis 8**, and **PostgreSQL**.

This system sits in front of a high-throughput AI streaming application and the `vector-service` microservice, protected by stateless **Zero-Trust OAuth2 (JWT)** perimeter authentication, **short-lived HS256-signed internal gateway tokens (`X-Internal-Auth`)**, an atomic **Valkey 8 / Redis Lua GCRA** rate limiter with a 3-state circuit-broken local fallback, **Resilience4j** circuit breakers with **RFC 9457 (`ProblemDetail`)** contracts, and **OpenTelemetry Collector** distributed tracing. It is cloud-agnostic and production-ready across **AWS**, **Google Cloud Platform (GCP)**, and **Microsoft Azure**.

## 🏛️ System Architecture

The application follows an N-Tier distributed microservice architecture utilizing Java's Project Loom (Virtual Threads) for high-concurrency blocking I/O and streaming:

```text
RAW HTTP REQUEST ──► [ Authorization: Bearer eyJhbGciOi... ]
                                         |
                                         v
+-------------------------------------------------------------------------------+
|               SPRING SECURITY MVC (Edge Perimeter • Order -100)               |
+-------------------------------------------------------------------------------+
|  Rule 1: Path Verification                                                    |
|          • "/actuator/health/**", "/fallback" ──► [PERMITTED PROBES]          |
|          • "/api/vectors/ping" ──► [PUBLIC (STILL IP-RATE-LIMITED)]           |
|          • "/api/vectors/**"   ──► [REQUIRE VALID BEARER JWT]                 |
|          • Everything else     ──► [DENY ALL (403/401)]                       |
|                                                                               |
|  Rule 2: Cryptographic Token Scan (.oauth2ResourceServer())                   |
|          • Missing or malformed Bearer token ──► 401 Unauthorized             |
|          • Invalid RSA/ECDSA signature or expired JWT ──► 401 Unauthorized    |
|                                                                               |
|  Rule 3: Context Hydration                                                    |
|          • Extract "sub" & "roles" claims into stateless SecurityContext.     |
+-------------------------------------------------------------------------------+
                                         |
                                         v
+-------------------------------------------------------------------------------+
|         DISTRIBUTED GCRA RATE LIMITER FILTER (Order -99)                      |
+-------------------------------------------------------------------------------+
|  • Authenticated routes: bucket key = rate_limit:{user:<sub>}                 |
|  • Public "/api/vectors/ping": bucket key = rate_limit:{ip:<remoteAddr>}      |
|  • Primary: Atomic Valkey 8 Lua GCRA (O(1) key, server TIME, {tag} hash-tag)  |
|  • Fallback: 3-state Breaker (CLOSED -> OPEN -> single-probe HALF_OPEN)       |
|              + atomic ConcurrentHashMap.compute(...) local GCRA (10k bounded) |
+-------------------------------------------------------------------------------+
                                         |
                                         v
+-------------------------------------------------------------------------------+
|       ZERO-TRUST INTERNAL SIGNING & METHOD-AWARE ROUTING (SignInternalAuth)   |
+-------------------------------------------------------------------------------+
|  • Strip any spoofed inbound [X-Gateway-Validated] & [X-Internal-Auth]        |
|  • Mint fresh 30s-TTL HS256 JWT [X-Internal-Auth] (sub, roles, jti, iss)      |
|  • Route by workload:                                                         |
|      - /api/vectors/stream/** ──► No Retry, 120s TimeLimiter (SSE safe)       |
|      - GET/HEAD               ──► Retry (2x on 502/504), 15s TimeLimiter      |
|      - POST/PUT/PATCH/DELETE  ──► No Retry (idempotency safe), 30s TimeLimiter|
+-------------------------------------------------------------------------------+
```

```text
                                          +-------------------------------------------------+
                                          |              External Web Clients               |
                                          |  (cURL, React / Angular Frontend, Mobile Apps)  |
                                          +-------------------------------------------------+
                                                    |                            |
                               1. Authenticate &    |                            | 2. HTTP POST/GET (API Request)
                                  Obtain Bearer JWT |                            |    Header: Authorization: Bearer <JWT>
                                                    v                            v
+-----------------------------------+     +-------------------------------------------------+       +--------------------------------------+
|       Commercial IDaaS Cloud      |     |   Spring Boot 4 Gateway Server WebMVC (Tomcat)  |       |       Valkey 8 / Redis 8 Cluster     |
|  (Auth0 / Okta / AWS Cognito)     |     |             (Port 8080 • JDK 26 AOT)            |       |   (Port 6379 • 250ms Fail-Fast I/O)  |
+-----------------------------------+     +-------------------------------------------------+ <---> +--------------------------------------+
| • Issues OIDC / OAuth2 Bearer JWT | <-- |  1. Edge OAuth2 JWT + Spoof Header Stripping    |       | • Key: rate_limit:{user:sub | ip:ip} |
| • Hosts /.well-known/jwks.json    |     |  2. Atomic Valkey GCRA + 3-State Local Fallback |       | • Script: Cached EVALSHA + GET/SET PX|
+-----------------------------------+     |  3. Protocol-Aware RestClient (h1 internal/h2)  |       | • Time: Valkey server TIME           |
                                          |  4. HS256 X-Internal-Auth Minting (30s TTL)     |       +--------------------------------------+
                                          |  5. Method-Split Routes + RFC 9457 Fallback     |
                                          |  6. W3C OTLP Traces ──► OpenTelemetry Collector |
                                          +-------------------------------------------------+
                                                                   |
                                             (Health-Check-Aware Round-Robin Routing)
                                                                   |
                                            +----------------------+----------------------+
                                            |                      |                      |
                                     lb://vector-service    lb://vector-service    lb://vector-service
                                            |                      |                      |
                                            v                      v                      v
                            +----------------------------------------------------------------------------------+
                            |                            Microservice Cluster                                  |
                            |                          (Ports 8081, 8082, 8083)                                |
                            |    +----------------------+ +----------------------+ +----------------------+    |   ┌──────────────────────────────────────────────────┐
                            |    |  Vector API Clone 1  | |  Vector API Clone 2  | |  Vector API Clone 3  |    |   │         Microservice Internal Layers             │
                            |    |     (Port 8081)      | |     (Port 8082)      | |     (Port 8083)      |    |   ├──────────────────────────────────────────────────┤
                            |    +----------------------+ +----------------------+ +----------------------+    |   │ [Filter Layer]                                   │
                            |    | GatewaySecurityFilter| | GatewaySecurityFilter| | GatewaySecurityFilter|    |   │ └── GatewaySecurityFilter +                      │
                            |    | + InternalAuthToken- | | + InternalAuthToken- | | + InternalAuthToken- |    |   │     InternalAuthTokenVerifier (HS256 JWT Check)  │
                            |    |   Verifier           | |   Verifier           | |   Verifier           |    |   │                                                  │
                            |    +----------------------+ +----------------------+ +----------------------+    |   │ [Application Layers]                             │
                            |    | [Layer 1] Controller | | [Layer 1] Controller | | [Layer 1] Controller |    |   │ ├── Controller Layer (Thin HTTP Handlers)        │
                            |    | [Layer 2] Service    | | [Layer 2] Service    | | [Layer 2] Service    |    |   │ ├── Service Layer (Math & Business Logic)        │
                            |    |   + Circuit Breaker  | |   + Circuit Breaker  | |   + Circuit Breaker  |    |   │ ├── Circuit Breaker (Resilience4j Fallbacks)     │
                            |    | [Layer 3] Repository | | [Layer 3] Repository | | [Layer 3] Repository |    |   │ └── Repository Layer (Hibernate ORM Bridge)      │
                            |    +----------------------+ +----------------------+ +----------------------+    |   └──────────────────────────────────────────────────┘
                            |                                                                                  |
                            +----------------------------------------------------------------------------------+
                                                                   |
                                                Hibernate ORM / JDBC (Repository Bridge)
                                                                   |
                                               +-------------------+-------------------+
                                               |                   |                   |
                                               v                   v                   v
                                           +-------------------------------------------------+
                                           |               PostgreSQL Database               |
                                           |           (Port 5432 / GCP Cloud SQL)           |
                                           +-------------------------------------------------+
                                           |  • Table: vector_calculations                   |
                                           |  • HikariCP Connection Pools                    |
                                           +-------------------------------------------------+
```

## 🛠️ Technology Stack

| Component | Technology | Description |
| :--- | :--- | :--- |
| **Runtime** | Java 26 | Project Loom Virtual Threads, Generational ZGC, Compact Object Headers & Project Leyden AOT Cache (JEP 514/515/516) |
| **Framework** | Spring Boot 4.0+ | Core Spring 7 engine with RFC 9457 `ProblemDetail` & Jackson 3 |
| **Gateway** | Spring Cloud Gateway Server WebMVC | `spring-cloud-starter-gateway-server-webmvc` (Spring Cloud 2025.1 Oakwood) + Virtual-Thread `RestClient` |
| **Security** | Spring Security 7 + Internal HS256 JWTs | Stateless OAuth2 edge JWT verification, spoofed-header stripping, and 30s `X-Internal-Auth` service-to-service JWTs |
| **Caching/Limits** | Valkey 8 / Redis 8 | Multi-threaded async I/O datastore executing atomic GCRA Lua scripts (native Lettuce Netty multiplexing) |
| **Database** | PostgreSQL 15+ | Relational persistence engine |
| **ORM** | Hibernate ORM 7 | Object-Relational Mapping & DDL generation |
| **Resilience** | Resilience4j | Method-split circuit breakers, upstream timeouts, and `GET`-only retry safety nets |
| **Observability** | OpenTelemetry Collector & Zipkin | Vendor-neutral OTLP pipeline routing W3C `traceparent` spans to Tempo, Jaeger v2, Datadog, or Zipkin |
| **API Docs** | SpringDoc OpenAPI 3 / Swagger UI | Automated endpoint documentation |
| **Container** | Docker (Multi-Stage) | Non-root Alpine JRE 26 with Layered JARs & warmed Project Leyden `-XX:AOTCache` |
| **Cloud** | AWS / GCP / Azure | Cloud-Agnostic (Cloud Run, AWS ECS/Fargate, Azure Container Apps) |

---

## ⚙️ Environment Variables

The API Gateway follows the **Twelve-Factor App** methodology and requires zero code changes across environments:

| Variable | Default Value | Description |
| :--- | :--- | :--- |
| `PORT` | `8080` | Dynamic port binding (Required for Cloud Run / AWS App Runner). |
| `REDIS_HOST` | `localhost` | The IP or DNS of your Valkey 8 / Redis 8 node or cluster. |
| `REDIS_PORT` | `6379` | The port for your Valkey 8 / Redis 8 cluster. |
| `BACKEND_SERVICE_URL` | `lb://vector-service` | Downstream routing target (`lb://vector-service` locally, or `https://...` in cloud). |
| `JWT_ISSUER_URI` | `https://your-tenant.us.auth0.com/` | External OIDC/OAuth2 provider URL (Auth0, Okta, AWS Cognito, Azure Entra ID). |
| `INTERNAL_AUTH_SECRET` | `dev-only-...` *(32+ bytes)* | Shared HMAC-SHA256 signing key between `api-gateway` and `vector-service` (**startup fails if default in `prod`**). |
| `INTERNAL_AUTH_ISSUER` | `api-gateway` | Expected `iss` claim in `X-Internal-Auth` tokens. |
| `INTERNAL_AUTH_TTL_SECONDS` | `30` | Lifetime (1–60s) of signed `X-Internal-Auth` tokens minted by `api-gateway`. |
| `RATE_LIMIT_WINDOW_MS` | `30000` | GCRA burst window in milliseconds. |
| `RATE_LIMIT_LIMIT` | `10` | Maximum burst capacity per window. |
| `PROXY_CONNECT_TIMEOUT` | `3s` | Upstream TCP connect timeout. |
| `PROXY_READ_TIMEOUT` | `120s` | Streaming-safe outbound read timeout (`RestClient`). |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:3000,...` | Comma-separated allowed browser origins. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector:4318/v1/traces` | OpenTelemetry Collector OTLP HTTP ingestion endpoint. |
| `OTEL_TRACING_SAMPLE_RATE` | `1.0` | Trace sampling probability (`1.0` in dev, `0.1`/`0.01` in prod). |
| `SPRING_PROFILES_ACTIVE` | `local` | Set to `local` or `test` for mock edge JWT decoding; set to `prod` in live deployments. |

---

## 🚀 Local Development Setup

### Prerequisites
- **JDK 26** installed and added to your system `PATH` (supports JEP 516 ZGC + Leyden AOT Object Caching).
- **Docker & Docker Compose** installed for cluster orchestration.
- **Apache Maven** (or use the included `mvnw` / `mvnw.cmd` wrapper scripts).
- **PostgreSQL Server** running locally on port `5432` (via Docker or native installer).
- **Valkey 8 / Redis 8 Server** running locally on port `6379`.

### Docker (Recommended)

**Step #1: One-Click Docker Cluster**

Compile both Spring Boot JARs first, then boot the entire distributed ecosystem (`api-gateway`, `vector-service`, single-node `valkey`, `postgres-db`, `otel-collector`, and `zipkin`) simultaneously:

```bash
cd api-gateway && ./mvnw clean package -DskipTests
cd ../Web_Application_Spring && ./mvnw clean package -DskipTests
cd ../api-gateway && docker compose up -d --build
```

> **Note:** View real-time distributed traces in the Zipkin UI at `http://localhost:9411`.

### Manual Local Boot (Alternative)

Ensure PostgreSQL is running on port `5432` and Valkey/Redis is running on port `6379` before proceeding.

**Step #1: Boot the Microservice Cluster (Load Balancer Setup)**

To simulate a horizontally scaled environment, open three separate terminals inside `Web_Application_Spring` and start three clones on ports `8081`, `8082`, and `8083`:

**Terminal 1 (Port `8081`):**
```bash
./mvnw clean spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

**Terminal 2 (Port `8082`):**
```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8082
```

**Terminal 3 (Port `8083`):**
```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8083
```

> Watch the logs of Terminal 1 upon boot — Hibernate automatically creates the `vector_calculations` table.

**Step #2: Boot the API Gateway (Local Profile)**

Open a fourth terminal inside `api-gateway` and launch the gateway with the `local` profile (enables the mock edge `JwtDecoder`):

```bash
./mvnw clean spring-boot:run -Dspring-boot.run.profiles=local
```

**Step #3: Verify the Database Connection**

```bash
netstat -ano | findstr 5432
```

---

## ✨ Key Features

### 1. Full-Duplex Virtual Threads & Protocol-Aware `RestClient` (`GatewayHttpClientConfig.java`)
- Enables `spring.threads.virtual.enabled=true` on Tomcat (`max-connections: 20000`) and injects a Spring-managed Virtual Thread `ExecutorService` (`destroyMethod = "close"`) into `java.net.http.HttpClient`.
- Automatically selects **`HTTP_1_1` for cleartext internal `http://` hops** (avoiding wasted `h2c` upgrade handshakes) and **`HTTP_2` for `https://` TLS endpoints** (ALPN multiplexing).
- Uses a `120s` outbound read timeout (`PROXY_READ_TIMEOUT`) so long-lived AI Server-Sent Events (SSE) streams are not truncated prematurely, while per-route Resilience4j `TimeLimiter` enforces strict ceilings per workload.

### 2. Stateless OAuth2 Perimeter Firewall (`SecurityConfig.java`)
- Enforces JWT Bearer token validation before routing and `SessionCreationPolicy.STATELESS` (zero `HttpSession` heap allocations).
- Applies browser security headers: **HSTS** (1-year max-age with subdomains), **Clickjacking defense** (`X-Frame-Options: DENY`), and **MIME-sniffing protection** (`X-Content-Type-Options: nosniff`).
- Fail-closed `.anyRequest().denyAll()` ACL with explicit CORS exposure of `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `Retry-After`, and W3C `traceparent`.

### 3. Atomic Valkey GCRA Rate Limiter + 3-State Circuit-Broken Local Fallback (`DistributedRateLimiterFilter.java`)
- **Explicit Filter Ordering (`FilterRegistrationBean` at `-99`):** Runs directly after Spring Security (`-100`), ensuring unauthenticated requests to protected routes are rejected with `401` before ever touching Valkey, while public `/api/vectors/ping` traffic is still rate-limited by client IP (`rate_limit:{ip:<remoteAddr>}`).
- **Primary Tier (Atomic Valkey 8 Lua GCRA):** Executes a single-hop cached `EVALSHA` **Generic Cell Rate Algorithm (GCRA)** script storing a single timestamp per key (`O(1)` RAM), using **Valkey server `TIME`** (no multi-pod clock skew), `{identity}` cluster hash-tags (`rate_limit:{user:<sub>}`), and dynamic `Retry-After` calculations. Uses Lettuce’s native Netty multiplexing (no `commons-pool2` lock contention).
- **Resilient Degraded Tier (`CLOSED` → `OPEN` → single-probe `HALF_OPEN`):** Trips `OPEN` for `15s` after `5` consecutive Valkey failures (`250ms` socket timeout). After cooldown, **only a single thread** probes Valkey (`HALF_OPEN`) while concurrent requests continue safely on local fallback—eliminating post-outage thundering herds.
- **Atomic Local Fallback:** Uses `ConcurrentHashMap.compute(...)` over an `O(1)` per-key GCRA bucket (zero TOCTOU race conditions under concurrent bursts) with expired-entry purging and bounded oldest-batch eviction at `10,000` keys.

### 4. Cryptographic Zero-Trust Service Boundary (`InternalAuthTokenService.java`, `InternalAuthFilterFunctions.java`, `GatewaySecurityFilter.java`, `InternalAuthTokenVerifier.java`)
- Strips any untrusted client-supplied `X-Gateway-Validated` and `X-Internal-Auth` headers at the gateway edge.
- Custom `SignInternalAuth` gateway filter mints a **30-second HS256-signed JWT** (`X-Internal-Auth`) carrying `sub`, `roles`, `jti`, `iss`, `iat`, and `exp`.
- Downstream `vector-service` enforces `GatewaySecurityFilter` + `InternalAuthTokenVerifier` on every API route: pins `alg == HS256`, verifies HMAC signatures in constant time (`MessageDigest.isEqual`) before parsing claims, validates issuer/TTL/expiration, rejects missing headers (`403`) or forged/expired tokens (`401`), and refuses to boot in `prod` if `INTERNAL_AUTH_SECRET` uses the default dev key.

### 5. Health-Check-Aware Load Balancing (`spring-cloud-starter-loadbalancer`)
- Uses `SimpleDiscoveryClient` (`lb://vector-service` across ports `8081`, `8082`, `8083`) combined with `spring.cloud.loadbalancer.configurations: health-check` (`HealthCheckServiceInstanceListSupplier`) backed by a fast `1s`/`2s` probe `RestClient` so dead instances are actively ejected.

### 6. Method-Split Routing, Resilience4j & RFC 9457 Fault Tolerance (`application.yml` + `FallbackController.java`)
- Splits `/api/vectors/**` by idempotency and workload:
  - **`vector_service_stream_route` (`/api/vectors/stream/**`):** No retries; `120s` Resilience4j `TimeLimiter` for AI SSE streams.
  - **`vector_service_read_route` (`GET`/`HEAD`):** `CircuitBreaker` (`15s` TimeLimiter) wrapping `Retry` (`retries: 2` on `BAD_GATEWAY`/`GATEWAY_TIMEOUT`).
  - **`vector_service_mutation_route` (`POST`/`PUT`/`PATCH`/`DELETE`):** **No automatic retries** (prevents duplicate PostgreSQL ledger entries or double-billed inference calls); `30s` `TimeLimiter`.
- `FallbackController` reads `RequestDispatcher.FORWARD_REQUEST_URI` so RFC 9457 `ProblemDetail` (`503 Service Unavailable`, `Retry-After: 20`) preserves the exact failed `/api/vectors/...` path in `instance`.

### 7. Polyglot W3C Observability (`Micrometer` + `OpenTelemetry Collector`)
- Generates W3C `traceId`/`spanId` at the gateway perimeter, injects MDC log correlation, propagates `traceparent` downstream, and exports OTLP spans to the OpenTelemetry Collector (`otel-collector-config.yaml`) fanning out to Zipkin (`http://localhost:9411`), Grafana Tempo, Jaeger v2, or Datadog.

### 8. Cloud-Native Health Probes & Warmed JDK 26 Leyden AOT Cache (`Dockerfile`)
- Both `api-gateway` and `vector-service` run as unprivileged non-root users on **Alpine JRE 26**.
- `api-gateway/Dockerfile` performs Spring Boot 4 layered JAR extraction and a **real HTTP warmup** (`/actuator/health/readiness`, `/api/vectors/ping`, `POST /api/vectors/calculateMagnitude`, `/fallback`) with `-XX:+UseZGC -XX:+UseCompactObjectHeaders -XX:AOTCacheOutput=application.aot` (JEP 514/515/516), shuts down gracefully via `SIGTERM`, and fails the build loudly if `application.aot` is missing or empty.

### 9. Enterprise Web Server & Cloud Proxy Tuning (`application.yml`)
- `server.shutdown: graceful` with `20s` drain window (`timeout-per-shutdown-phase: 20s`).
- `forward-headers-strategy: framework` for cloud load balancers (GCP LB, AWS ALB, Azure Front Door).
- GZIP compression (`application/json`, `application/problem+json`, `>1KB`) and Tomcat socket limits (`max-connections: 20000`, `accept-count: 2000`, `connection-timeout: 5000`, `1MB` form/swallow caps).

### 10. Automated ORM & Persistence (`Spring Data JPA` + `Hibernate 7`)
- `VectorCalculationRepository` extends `JpaRepository` with ACID-compliant PostgreSQL persistence (`vector_calculations`).

### 11. Chaos, Zero-Trust & Concurrency Integration Tests
- **`api-gateway` (`GatewaySecurityAndRateLimitTests.java` — 13 tests):** Verifies unauthenticated `401`, spoofed `X-Gateway-Validated`/`X-Internal-Auth` header stripping and replacement with valid 30s HS256 JWTs, forged/expired/`alg=none` internal token rejection, Valkey GCRA `429` + dynamic `Retry-After`, IP rate limiting on `/api/vectors/ping`, Valkey outage circuit-breaking (`times(5)`), 30-thread zero-TOCTOU local fallback enforcement, single-probe `HALF_OPEN` recovery, `POST` no-retry vs. `GET` 3-attempt retry on `504`, and RFC 9457 `ProblemDetail` `instance` preservation.
- **`Web_Application_Spring` (`Vector3DControllerTest.java` + `Vector3DServiceTest.java` — 7 tests):** Verifies signed `X-Internal-Auth` acceptance + `X-Internal-Principal` hydration, missing/`X-Gateway-Validated`-only `403` rejection, forged/expired/`alg=none`/wrong-issuer `401` rejection, and pure vector service logic.

---

## 📡 API Reference & cURL Verification

All client interactions must target the API Gateway on Port `8080`. Direct calls to `vector-service` (`8081–8083`) without a valid `X-Internal-Auth` HS256 JWT are rejected (`403` if missing, `401` if forged/expired). In `local` profile, any RFC 6750–valid string works as your external Bearer token.

**1. Routing Verification (IP-Rate-Limited Public Endpoint)**

```bash
curl -v http://localhost:8080/api/vectors/ping
```

Expected Response (`200 OK`):

```text
Controller and Service are successfully connected!
```

**2. Calculate Magnitude & Save to Ledger**

Calculates $\sqrt{x^2 + y^2 + z^2}$ and permanently logs the transaction in PostgreSQL:

```bash
curl -v -H "Authorization: Bearer mock-test-token" -X POST http://localhost:8080/api/vectors/calculateMagnitude \
  -H "Content-Type: application/json" \
  -d "{\"x\": 10.0, \"y\": 20.0, \"z\": 30.0}"
```

Expected Response (`200 OK`):

```json
37.416573867739416
```

Check your backend terminals: You will see the requests rotate cleanly between Port 8081, 8082, and 8083!


**3. Add Vectors (Circuit Breaker Protected)**

Adds two 3D vectors together. If the backend math service goes offline or throws an exception, Resilience4j intercepts the failure and returns a default zero-vector.

```bash
curl -v -H "Authorization: Bearer mock-test-token" -X POST http://localhost:8080/api/vectors/addVectors \
  -H "Content-Type: application/json" \
  -d "{\"v1\": {\"x\": 1.0, \"y\": 2.0, \"z\": 3.0}, \"v2\": {\"x\": 4.0, \"y\": 5.0, \"z\": 6.0}}"
```

Expected Response (`200 OK`):

```json
{
  "x": 5.0,
  "y": 7.0,
  "z": 9.0
}
```

**4. Scale Vector**

Multiplies a 3D vector's coordinates by a scalar path variable.

```bash
curl -v -H "Authorization: Bearer mock-test-token" -X POST http://localhost:8080/api/vectors/scale/3 \
  -H "Content-Type: application/json" \
  -d "{\"x\": 1.0, \"y\": 2.0, \"z\": 3.0}"
```

Expected Response (`200 OK`):

```json
{
  "x": 3.0,
  "y": 6.0,
  "z": 9.0
}
```

**5. Retrieve Database Ledger History**

Queries PostgreSQL via Spring Data JPA (SELECT * FROM vector_calculations) and returns the complete calculation ledger.


```bash
curl -v -H "Authorization: Bearer mock-test-token" http://localhost:8080/api/vectors/history
```

Expected Response (`200 OK`):

```json
[
  {
    "id": 1,
    "x": 10.0,
    "y": 20.0,
    "z": 30.0,
    "magnitude": 37.416573867739416,
    "calculatedAt": "2026-06-24T17:51:12.524652"
  }
]
```

**6. GCRA Rate Limiter Exhaustion Test (Dynamic `Retry-After`)**

Run rapidly 11 times within the burst window:

```bash
for i in $(seq 1 11); do
  curl -i -H "Authorization: Bearer mock-test-token" -X POST http://localhost:8080/api/vectors/calculateMagnitude \
    -H "Content-Type: application/json" -d "{\"x\": 1.0, \"y\": 2.0, \"z\": 3.0}"
done
```

Expected Response on the 11th request (`429 Too Many Requests`):

```text
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 0
Retry-After: 3
Content-Type: text/plain;charset=UTF-8

Rate limit exceeded! Quota of 10 requests per 30 seconds exhausted. Retry after 3 seconds.
```

**7. Circuit Breaker Fallback Test (RFC 9457 `ProblemDetail`)**

```bash
curl -i http://localhost:8080/fallback
```

Expected Response (`503 Service Unavailable`):

```json
{
  "type": "about:blank",
  "title": "Service Unavailable",
  "status": 503,
  "detail": "The AI generation service is experiencing heavy traffic and is temporarily unavailable. Please retry shortly.",
  "instance": "/fallback",
  "timestamp": "2026-06-24T22:00:00.000Z",
  "retryAfterSeconds": 20
}
```

---

## 🧪 Automated Testing Suite

Run both automated test suites:

```bash
cd api-gateway && ./mvnw clean test
cd ../Web_Application_Spring && ./mvnw clean test
```

### `api-gateway` (`GatewaySecurityAndRateLimitTests.java` — 13 Tests)
1. `testHackerAttempt_shouldReturn401Unauthorized`: Missing JWT Bearer token rejected at edge (`401`).
2. `testUnauthenticatedRequest_withForgedInternalAuthHeaders_shouldReturn401Unauthorized`: Forged `X-Gateway-Validated` / `X-Internal-Auth` headers without a valid edge JWT rejected (`401`).
3. `testSpoofedInternalHeaders_shouldBeStrippedAndReplacedWithSignedJwt`: Proves spoofed inbound `X-Gateway-Validated` and `X-Internal-Auth` headers are stripped and replaced with a freshly signed 30s HS256 JWT (`sub`, `roles`, `jti`, `iss`).
4. `testInternalAuthTokenVerification_shouldRejectForgedAndExpiredTokens`: Verifies wrong-secret, expired, and `alg=none` internal JWTs are rejected.
5. `testSpammingServer_shouldTriggerRateLimiter429`: Proves GCRA allows burst of 10 and blocks the 11th with `429` + dynamic `Retry-After`.
6. `testPing_shouldEnforceIpRateLimit429`: Proves public `/api/vectors/ping` is IP-rate-limited (`429` on 11th request) and receives `sub="anonymous"`, `roles=[]` downstream.
7. `testValkeyOutage_shouldTripCircuitAndEnforceLocalFallback429`: Proves circuit trips `OPEN` after 5 Valkey failures and local GCRA blocks the 11th request (`429`).
8. `testLocalFallback_shouldPreventConcurrentToctouBypass`: Fires 30 concurrent Virtual Threads simultaneously and proves atomic `ConcurrentHashMap.compute` GCRA allows exactly 10 and blocks 20.
9. `testHalfOpen_shouldProbeValkeyOnceAfterCooldownAndRecover`: Fires concurrent requests upon cooldown expiration and proves only **1** `HALF_OPEN` probe hits Valkey before recovering to `CLOSED`.
10. `testPostRequest_shouldNotRetryOn504`: Proves `POST` hits downstream exactly once on `504` (zero duplicate retries).
11. `testGetRequest_shouldRetryOn504`: Proves `GET` retries twice on `504` (`3` total attempts).
12. `testCircuitBreakerFallback_shouldReturnRfc9457ProblemDetail503`: Verifies direct `/fallback` RFC 9457 `ProblemDetail` contract.
13. `testCircuitBreakerFallback_shouldPreserveOriginalForwardUri`: Proves gateway-forwarded Resilience4j fallback preserves `/api/vectors/trigger-fallback` in `ProblemDetail.instance`.

### `Web_Application_Spring` (`Vector3DControllerTest.java` + `Vector3DServiceTest.java` — 7 Tests)
1. `testAddVectorsEndpoint_withValidSignedInternalAuth_shouldReturn200`: Verifies valid 30s HS256 `X-Internal-Auth` JWT passes and hydrates `X-Internal-Principal`.
2. `testAddVectorsEndpoint_withoutInternalAuthHeader_shouldReturn403Forbidden`: Missing `X-Internal-Auth` rejected (`403`).
3. `testAddVectorsEndpoint_withOnlyLegacyGatewayValidatedHeader_shouldReturn403Forbidden`: Legacy `X-Gateway-Validated: true` alone rejected (`403`).
4. `testAddVectorsEndpoint_withForgedInternalAuthSignature_shouldReturn401Unauthorized`: Wrong-secret token rejected (`401`).
5. `testAddVectorsEndpoint_withExpiredInternalAuthToken_shouldReturn401Unauthorized`: Expired token rejected (`401`).
6. `testAddVectorsEndpoint_withAlgNoneOrWrongIssuer_shouldReturn401Unauthorized`: `alg=none` and untrusted issuer tokens rejected (`401`).
7. `testAddVectors`: Pure service-layer math unit verification.

---

## 🌩️ Load Testing

**1. Test Tomcat Virtual Thread Socket Acceptance (`/actuator/health`)**

```bash
hey -z 15s -c 5000 http://localhost:8080/actuator/health
```

**2. Test IP GCRA Rate Limiter on `/api/vectors/ping`**
```bash
hey -n 1000 -c 100 -m GET http://localhost:8080/api/vectors/ping
```
*(The first 10 requests succeed; the remaining 990 are immediately blocked with HTTP `429` by Valkey GCRA before reaching `vector-service`.)*

---

## 🌩️ End-to-End Load Testing (`k6`)

Rather than benchmarking unauthenticated `/actuator/health` probes, `api-gateway/load-tests/gateway-benchmark.js` tests the **complete L7 proxy data path**: edge OAuth2 JWT decoding → Valkey GCRA `EVALSHA` → `Gradient2` Adaptive Concurrency Limiter → `SignInternalAuth` HS256 minting → `RestClient` proxying → `vector-service` PostgreSQL persistence.

**1. Run End-to-End Proxy + GCRA Burst Benchmark (`local` profile):**

```bash
k6 run api-gateway/load-tests/gateway-benchmark.js
```
**2. Run Against Staging / Production with a Real Bearer JWT & Streaming Scenario:**

```bash
k6 run \
  -e GATEWAY_URL=https://api.your-domain.com \
  -e BEARER_TOKEN="eyJhbGciOiJSUzI1NiIs..." \
  -e ENABLE_SSE_SCENARIO=true \
  api-gateway/load-tests/gateway-benchmark.js
```

---

### Run Step 3 Verification Now

```bash
cd api-gateway && ./mvnw clean test
```

---

## ☁️ Google Cloud Platform (GCP) Deployment

> **☁️ Cloud Agnostic Design:** While the deployment guide below features Google Cloud, this ecosystem relies strictly on standard Docker containers and injected environment variables. It can be deployed identically to **AWS** (App Runner, ECS Fargate, ElastiCache) or **Microsoft Azure** (Container Apps, Azure Cache for Redis) without modifying a single line of Java code.

```text
TRAFFIC INBOUND (Global Users)
       │
       ▼
┌────────────────────────────────────────────────────────┐
│     Google Cloud Armor (Layer 3/4 DDoS Scrubbing)      │
└────────────────────────────────────────────────────────┘
       │ (Filters volumetric infrastructure attacks)
       ▼
┌────────────────────────────────────────────────────────┐
│    Google Global Cloud Load Balancer (Anycast IP)      │
└────────────────────────────────────────────────────────┘
       │ (Terminates SSL, routes traffic to nearest region)
       ▼
┌────────────────────────────────────────────────────────┐
│    Cloud Run (Hosts your api-gateway Docker image)     │
│     ⚡ Java 26 Virtual Threads + Leyden AOT Cache      │
└────────────────────────────────────────────────────────┘
       │                 │
       │ (GCRA Lua)      │ (Virtual-Thread Router + X-Internal-Auth)
       ▼                 ▼
┌──────────────┐   ┌─────────────────────────────────────┐
│ Memorystore  │   │        Microservice Cluster         │
│ (Valkey 8)   │   │  (vector-api • --ingress=internal)  │
└──────────────┘   └─────────────────────────────────────┘
```

### Step 1: Create Secret & Dedicated Service Accounts (Zero-Trust IAM + Shared Signing Key)

```bash
# 1. Store a 256-bit+ signing key in Google Secret Manager
openssl rand -base64 48 | gcloud secrets create internal-auth-secret --data-file=-

# 2. Create dedicated runtime service accounts (least privilege)
gcloud iam service-accounts create api-gateway-sa --display-name="API Gateway Runtime SA"
gcloud iam service-accounts create vector-api-sa --display-name="Vector API Runtime SA"

# 3. Grant both service accounts Secret Manager access to internal-auth-secret
for SA in api-gateway-sa vector-api-sa; do
  gcloud secrets add-iam-policy-binding internal-auth-secret \
    --member="serviceAccount:${SA}@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/secretmanager.secretAccessor"
done
```

### Step 2: Deploy `vector-api` Locked to Internal VPC Ingress Only (`--ingress=internal` + `--no-allow-unauthenticated`)

```bash
cd Web_Application_Spring

gcloud run deploy vector-api \
  --source . \
  --region us-central1 \
  --service-account="vector-api-sa@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --ingress=internal \
  --no-allow-unauthenticated \
  --add-cloudsql-instances YOUR_PROJECT_ID:us-central1:vector-db-instance \
  --set-secrets="INTERNAL_AUTH_SECRET=internal-auth-secret:latest" \
  --set-env-vars SPRING_PROFILES_ACTIVE=prod \
  --set-env-vars INTERNAL_AUTH_ISSUER=api-gateway \
  --set-env-vars SPRING_DATASOURCE_URL="jdbc:postgresql://google/vector_db?cloudSqlInstance=YOUR_PROJECT_ID:us-central1:vector-db-instance&socketFactory=com.google.cloud.sql.postgres.SocketFactory" \
  --set-env-vars SPRING_DATASOURCE_USERNAME=postgres \
  --set-env-vars SPRING_DATASOURCE_PASSWORD=your_cloud_password

# Allow ONLY the api-gateway service account to invoke vector-api (defense-in-depth alongside X-Internal-Auth)
gcloud run services add-iam-policy-binding vector-api \
  --region us-central1 \
  --member="serviceAccount:api-gateway-sa@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/run.invoker"
```

### Step 3: Deploy `api-gateway` with VPC Egress to Reach Internal `vector-api`

```bash
cd api-gateway

gcloud run deploy api-gateway \
  --source . \
  --region us-central1 \
  --service-account="api-gateway-sa@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --ingress=all \
  --allow-unauthenticated \
  --network=default \
  --subnet=default \
  --vpc-egress=all-traffic \
  --set-secrets="INTERNAL_AUTH_SECRET=internal-auth-secret:latest" \
  --set-env-vars REDIS_HOST=10.X.X.X \
  --set-env-vars REDIS_PORT=6379 \
  --set-env-vars BACKEND_SERVICE_URL=https://vector-api-xyz.a.run.app \
  --set-env-vars OTEL_EXPORTER_OTLP_ENDPOINT=https://your-otel-collector:4318/v1/traces \
  --set-env-vars JWT_ISSUER_URI=https://your-tenant.us.auth0.com/ \
  --set-env-vars INTERNAL_AUTH_ISSUER=api-gateway \
  --set-env-vars INTERNAL_AUTH_TTL_SECONDS=30 \
  --set-env-vars SPRING_PROFILES_ACTIVE=prod \
  --set-env-vars RATE_LIMIT_WINDOW_MS=30000 \
  --set-env-vars RATE_LIMIT_LIMIT=10 \
  --set-env-vars APP_CORS_ALLOWED_ORIGINS="https://your-frontend-domain.com" \
  --set-env-vars SPRING_THREADS_VIRTUAL_ENABLED=true
```

> **Note:** Google Cloud Run automatically injects the `$PORT` environment variable, which `api-gateway` (`server.port: ${PORT:8080}`) and `vector-service` (`server.port: ${PORT:${SERVER_PORT:8081}}`) both bind to automatically.
>
> **Cloud Run IAM + Gateway Auth Note:** `--ingress=internal` already blocks all public internet access to `vector-api`. Because Cloud Run's `--no-allow-unauthenticated` IAM check validates a GCP OIDC identity token in the `Authorization` header (while `api-gateway` currently forwards the client's `Authorization` header unless stripped and replaced with a GCP ID token / `X-Serverless-Authorization`), either attach a GCP ID token in `Authorization` (stripping the end-user `Authorization` header once validated at the gateway), or run `vector-api` with `--ingress=internal` + `--allow-unauthenticated` combined with `GatewaySecurityFilter`'s mandatory `X-Internal-Auth` HS256 JWT verification.

---

### Verify Both Services

```bash
cd api-gateway && ./mvnw clean test
cd ../Web_Application_Spring && ./mvnw clean test
cd ../api-gateway && docker build -t api-gateway:jdk26-aot .
```