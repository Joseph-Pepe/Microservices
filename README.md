# Microservice Ecosystem 🚀

An enterprise-grade, horizontally scalable, C10K-capable microservice architecture engineered with **Java 21/25+ LTS Virtual Threads (Project Loom)**, **Project Leyden AOT Cache**, **Spring Boot 4.0+**, **Spring Cloud Gateway Server WebMVC**, **Valkey 8 / Redis 8**, and **PostgreSQL**.

This system sits in front of high-throughput AI streaming and compute microservices, protected by stateless **Zero-Trust OAuth2 (JWT)** security, a lock-free atomic **Valkey 8 / Redis Lua** rate limiter, **Resilience4j** circuit breakers with **RFC 9457 (`ProblemDetail`)** contracts, and **OpenTelemetry Collector** distributed tracing. It is 100% cloud-agnostic and production-ready across **AWS**, **Google Cloud Platform (GCP)**, and **Microsoft Azure**.

## 🏛️ System Architecture

The application follows a modern N-Tier distributed microservice architecture utilizing Java's Project Loom (Virtual Threads) for maximum non-blocking I/O throughput:

```text
RAW HTTP REQUEST ──► [ Authorization: Bearer eyJhbGciOi... ]
                                         |
                                         v
+-------------------------------------------------------------------------------+
|                SPRING SECURITY MVC (The Perimeter Firewall)                   |
+-------------------------------------------------------------------------------+
|  Rule 1: Path Verification                                                    |
|          • Is it "/api/vectors/ping"? ──► [PASS PUBLICLY]                     |
|          • Is it protected "/api/vectors/**"? ──► [PROCEED TO SECURITY SCAN]  |
|                                                                               |
|  Rule 2: Cryptographic Token Scan (.oauth2ResourceServer())                   |
|          • Is the Bearer token present? (No ──► 401 Unauthorized)             |
|          • Is the RSA signature valid against Auth0/Okta JWKS cache?          |
|            (Tampered/Expired ──► 401 Unauthorized)                            |
|                                                                               |
|  Rule 3: Context Hydration                                                    |
|          • Extract "sub" claim (e.g., "admin") and build a standard           |
|            SecurityContext ThreadLocal Principal.                             |
|                                                                               |
|  Rule 4: Zero-Trust Header Sanitization & Injection                           |
|          • Strip any spoofed incoming [X-Gateway-Validated] headers first!    |
|          • Append fresh [X-Gateway-Validated: true] to prove to downstream    |
|            microservices that traffic was authenticated at the perimeter.     |
+-------------------------------------------------------------------------------+
                                         |
                                         v
                 SUCCESSFUL FIREWALL CLEARANCE! (Passes down to 
                 Redis Filter & Round-Robin Load Balancer)
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
|  (Auth0 / Okta / AWS Cognito)     |     |           (Port 8080 • JDK 25/26 AOT)           |       |     (Port 6379 • Multi-Thread I/O)   |
+-----------------------------------+     +-------------------------------------------------+ <---> +--------------------------------------+
| • Issues OIDC / OAuth2 Bearer JWT | <-- |  1. Zero-Trust OAuth2 JWT + Header Sanitization |       | • Key: rate_limit:{username=jwt.sub} |
| • Hosts /.well-known/jwks.json    |     |  2. Lock-Free Rate Limiter (ThreadLocalRandom)  |       | • Script: Cached EVALSHA + PEXPIRE   |
+-----------------------------------+     |  3. Full-Duplex Virtual Threads (Tomcat + HTTP2)|       | • Engine: AWS ElastiCache / GCP      |
                                          |  4. Resilience4j + RFC 9457 ProblemDetail       |       +--------------------------------------+
                                          |  5. W3C OTLP Traces ──► OpenTelemetry Collector |
                                          +-------------------------------------------------+  
                                                                   |
                                                          (Round-Robin Routing)
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
                            |    | GatewaySecurityFilter| | GatewaySecurityFilter| | GatewaySecurityFilter|    |   │ └── GatewaySecurityFilter (Bouncer Check)        │
                            |    |   (Check Header)     | |   (Check Header)     | |   (Check Header)     |    |   │                                                  │
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
| **Runtime** | Java 25 LTS / JDK 26 | Project Loom Virtual Threads, Native Generational ZGC & Project Leyden AOT Cache (JEP 514/516) |
| **Framework** | Spring Boot 4.0+ | Core Spring 7 engine with RFC 9457 `ProblemDetail` & Global HTTP Client Timeouts |
| **Gateway** | Spring Cloud Gateway Server WebMVC | `spring-cloud-starter-gateway-server-webmvc` (Spring Cloud 2025.1 Oakwood) + Virtual-Thread `RestClient` |
| **Security** | Spring Security 6 / 7 | Stateless OAuth2 JWT Firewall + HSTS + Zero-Trust Header Sanitization |
| **Caching/Limits** | Valkey 8 / Redis 8 | Multi-threaded async I/O datastore (AWS ElastiCache & GCP Memorystore for Valkey parity) |
| **Database** | PostgreSQL 15+ | Relational persistence engine |
| **ORM** | Hibernate ORM 7 | Object-Relational Mapping & DDL generation |
| **Resilience** | Resilience4j | Circuit breakers, upstream timeouts & retry safety nets |
| **Observability** | OpenTelemetry Collector & Zipkin | Vendor-neutral OTLP pipeline routing W3C `traceparent` spans to Tempo, Jaeger v2, Datadog, or Zipkin |
| **API Docs** | SpringDoc OpenAPI 3 / Swagger UI | Automated endpoint documentation |
| **Container** | Docker (Multi-Stage) | Non-root Alpine JRE 25/26 with Layered JARs & Project Leyden `-XX:AOTCache` pre-warming |
| **Cloud** | AWS / GCP / Azure | 100% Cloud-Agnostic (Cloud Run, AWS ECS/Fargate, Azure Container Apps) |

--- 

## ⚙️ Environment Variables

The API Gateway is built following the **Twelve-Factor App** methodology. It requires zero code changes to move between environments; simply inject the following environment variables:

| Variable | Default Value | Description |
| :--- | :--- | :--- |
| `PORT` | `8080` | Dynamic port binding (Required for Cloud Run / AWS App Runner). |
| `REDIS_HOST` | `localhost` | The IP or DNS of your Valkey 8 / Redis 8 cluster. |
| `REDIS_PORT` | `6379` | The port for your Valkey 8 / Redis 8 cluster. |
| `BACKEND_SERVICE_URL` | `lb://vector-service` | The downstream routing URL. In production, change this to your cloud backend (e.g., `https://api.internal`). |
| `JWT_ISSUER_URI` | `https://your-tenant.us.auth0.com/` | Your OIDC/OAuth2 provider URL (Auth0, Okta, AWS Cognito, Azure Entra ID). |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector:4318/v1/traces` | The OpenTelemetry Collector OTLP HTTP ingestion endpoint. |
| `SPRING_PROFILES_ACTIVE`| `local` | Set to `local` or `test` for mock JWT decoding; set to `prod` in live deployments. |

---

## 🚀 Local Development Setup

### Prerequisites
- **JDK 25 LTS** (or **JDK 26** for JEP 516 ZGC Object Caching / **JDK 21 LTS**) installed and added to your system `PATH`.
- **Docker & Docker Compose** installed for cluster orchestration.
- **Apache Maven** (or use the included `mvnw` wrapper scripts).
- **PostgreSQL Server** running locally on port `5432` (via Docker or native installer).
- **Valkey 8 / Redis 8 Server** running locally on port `6379`.

## Docker (Recommended)

<b>Step #1: One-Click Docker Cluster</b>

To boot the entire distributed ecosystem (Java 25/26 Spring Boot 4 API Gateway, Node.js Backend, Valkey 8 Cluster, PostgreSQL, OpenTelemetry Collector, and Zipkin UI) simultaneously using your Leyden AOT multi-stage Docker builds:

```terminal
docker-compose up -d --build
```

<b>Note:</b> You can view your real-time distributed traces by navigating to the Zipkin dashboard at `http://localhost:9411`.

## Manual local Boot (Alternative)

Ensure PostgreSQL is running on port 5432 and Redis on port 6379 before proceeding.

<b>Step #1: Boot the Microservice Cluster (Load Balancer Setup)</b>

To simulate a horizontally scaled production environment manually, open three separate terminals inside the vector-service directory and start three clones on different ports:

<b>`Terminal 1 (Port 8081)`</b>

```terminal
mvnw clean spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

<b>`Terminal 2 (Port 8082)`</b>

```terminal
mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8082
```

<b>`Terminal 3 (Port 8083)`</b>

```terminal
mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8083
```

Note: Watch the logs of Terminal 1 upon boot — you will see Hibernate execute create table vector_calculations automatically!

<b>Step 2: Boot the API Gateway (Local Profile)</b>

Open a fourth terminal inside the api-gateway directory and launch the gateway. Important: Run this with the local profile to enable the mock JWT decoder, which bypasses the need for a live Auth0 tenant during testing.

```terminal
mvnw clean spring-boot:run -Dspring-boot.run.profiles=local
```

<b>Step 3: Verify the Database Connection</b>


```terminal
netstat -ano | findstr 5432
```

---

## ✨ Key Features

### 1. Full-Duplex Virtual Threads (`api-gateway` + `GatewayHttpClientConfig.java`)
- Replaces legacy OS thread pools and reactive WebFlux complexity by enabling `spring.threads.virtual.enabled=true` on Tomcat (`max-connections: 20000`) **and** backing the outbound `RestClient` (`JdkClientHttpRequestFactory`) with `Executors.newVirtualThreadPerTaskExecutor()` over HTTP/2.
- Both inbound servlet connections and outbound blocking I/O / AI Server-Sent Events (SSE) stream waits unmount from OS carrier threads in nanoseconds—achieving C10K+ scale with clean, debuggable synchronous code.

### 2. Stateless OAuth2 Perimeter Firewall (`SecurityConfig.java`)
- Intercepts all incoming traffic before routing, enforcing JWT Bearer token validation and strict `SessionCreationPolicy.STATELESS` memory management (zero `HttpSession` heap allocations).
- Enforces enterprise browser security headers out of the box: **HSTS** (1-year max-age with subdomains), **Clickjacking defense** (`X-Frame-Options: DENY`), and **MIME-sniffing protection** (`X-Content-Type-Options: nosniff`).
- Default `denyAll()` fail-closed architecture ensures unmapped routes are never accidentally exposed.
- Handles global CORS preflight (`OPTIONS`) requests and exposes `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `Retry-After`, and W3C `traceparent` headers to browser clients.

### 3. Lock-Free Distributed Atomic Rate Limiter (`Valkey 8 / Redis 8` + `DistributedRateLimiterFilter.java`)
- Powered by **Valkey 8 / Redis 8** with multi-threaded network I/O (`--io-threads 4`) to match **AWS ElastiCache** and **GCP Memorystore for Valkey**, executing a Java Text Block **Lua Script** via cached `EVALSHA` in a single network hop (~1ms) with millisecond-precise `PEXPIRE` eviction.
- Uses lock-free `ThreadLocalRandom` instead of `UUID.randomUUID()` (`SecureRandom`) to prevent Virtual Thread synchronization bottlenecks under C10K load.
- Emits standard `X-RateLimit-Limit` and `X-RateLimit-Remaining` HTTP headers on every response.

### 4. Zero-Trust Header Sanitization & Security Offloading
- Prevents external header-spoofing attacks by explicitly stripping any incoming `X-Gateway-Validated` header (`RemoveRequestHeader=X-Gateway-Validated`) before injecting a fresh `AddRequestHeader=X-Gateway-Validated, true`.
- Offloads cryptographic JWT verification at the perimeter so downstream microservices can trust the sanitized header.

### 5. Client-Side Load Balancing (`spring-cloud-starter-loadbalancer`)
- Configured with simple instance discovery (`lb://vector-service`) mapping traffic across multiple ports (`8081`, `8082`, `8083`).
- Distributes compute load using a **Round-Robin** algorithm, ensuring uniform CPU utilization across all active microservice clones.

### 6. Circuit Breaker & RFC 9457 Fault Tolerance (`Resilience4j` + `FallbackController.java`)
- Enforces strict upstream HTTP connect (`3000ms`) and read (`15000ms`) timeouts, **Retries** (for `502`/`504` blips), and **Circuit Breaking** (trips if 50% of requests fail or exceed the 15s slow-call threshold).
- Automatically reroutes failed traffic to `/fallback`, returning a strongly-typed **RFC 9457 `ProblemDetail`** (`application/problem+json`) 503 response with a `Retry-After: 20` header instead of legacy `HashMap` payloads.
- Downstream services additionally wrap critical math operations with `@CircuitBreaker` to return safe default zero-vectors (`{x:0, y:0, z:0}`) during partial failures.

### 7. Polyglot W3C Observability (`Micrometer` + `OpenTelemetry Collector`)
- Generates a unique W3C `traceId` at the gateway perimeter, injects `traceId` and `spanId` into standard console logs (MDC Log Correlation), and propagates context downstream via the `traceparent` HTTP header across Java and Node.js.
- Exports spans via OTLP (`http://otel-collector:4318/v1/traces`) to a vendor-neutral **OpenTelemetry Collector** (`otel-collector-config.yaml`), which batches and fans out traces to **Grafana Tempo**, **Jaeger v2**, **Datadog**, or **Zipkin** (`http://localhost:9411`) without code changes.

### 8. Cloud-Native Health Probes & Project Leyden AOT Cache (`Dockerfile`)
- Exposes unauthenticated `/actuator/health/readiness` and `/liveness` probes through the Spring Security firewall and native Docker `HEALTHCHECK`.
- Uses a hardened multi-stage Alpine JRE 25/26 build running as an unprivileged user (`gatewayuser:gatewaygroup`) with Spring Boot 4 layered extraction (`-Djarmode=tools extract --layers --launcher`).
- Performs a single-step **Project Leyden AOT Cache** training and assembly build (`-XX:AOTCacheOutput=application.aot` via JDK 25 JEP 514/515, and JDK 26 JEP 516 for GC-agnostic ZGC object caching) and loads `-XX:AOTCache=application.aot` alongside native **Generational ZGC** (`-XX:+UseZGC`), cutting Cloud Run and AWS ECS cold-start times by over 50% while keeping GC pauses sub-millisecond.

### 9. Enterprise Web Server & Cloud Proxy Tuning (`application.yml`)
- **Graceful Shutdown (`server.shutdown: graceful`):** Grants active Virtual Threads a `20s` drain window (`timeout-per-shutdown-phase: 20s`) to complete in-flight streams when AWS ECS, GCP Cloud Run, or Kubernetes scales down a pod.
- **Cloud Load Balancer Trust (`forward-headers-strategy: framework`):** Properly resolves client IPs and HTTPS protocols from `X-Forwarded-For` and `X-Forwarded-Proto` headers behind AWS ALB, GCP Load Balancers, and Azure Front Door.
- **HTTP/2 & GZIP Compression:** Multiplexes TCP connections and compresses both `application/json` and `application/problem+json` payloads larger than 1KB by ~70-80%.
- **Virtual-Thread Socket Optimization:** Tunes Tomcat to `max-connections: 20000`, `accept-count: 2000`, and `connection-timeout: 5000` (Slowloris DDoS protection).

### 10. Automated ORM & Persistence (`Spring Data JPA` + `Hibernate 7`)
* Uses thin interfaces (`VectorCalculationRepository`) extending `JpaRepository` to eliminate boilerplate JDBC code.
* Configured with `ddl-auto: update`, allowing Hibernate to dynamically inspect and generate PostgreSQL database tables (`vector_calculations`) at startup.
* Implements ACID-compliant persistence, saving timestamped vector inputs and calculated magnitudes into a permanent ledger.

### 11. High-Throughput Integration Testing (`WebTestClient`)
* Complete automated test suite (`GatewaySecurityAndRateLimitTests.java`) matching the synchronous Servlet architecture.
* Utilizes `@ActiveProfiles("local")` and custom timeout mutations to verify edge-case security failures and rate-limiter exhaustion.

---

## 📡 API Reference & cURL Verification

All client interactions must be directed to the API Gateway on Port 8080. Direct access to backend ports (8081-8083) should be blocked in production environments. Because we are running the gateway in local profile, you can use any string as your Bearer token.

<b>1. Routing Verification</b>

Tests the connection through the gateway to any available backend service.

```
curl -v -H "Authorization: Bearer mock-test-token" http://localhost:8080/api/vectors/ping
```

Expected Response (200 OK): 

```
PlaintextController and Service are successfully connected!
```

<b>2. Calculate Magnitude & Save to Ledger</b>

Calculates $\sqrt{x^2 + y^2 + z^2}$, returns the exact scalar result, and permanently logs the transaction in PostgreSQL.

```
curl -v -H "Authorization: Bearer mock-test-token" -X POST http://localhost:8080/api/vectors/calculateMagnitude \
  -H "Content-Type: application/json" \
  -d "{\"x\": 10.0, \"y\": 20.0, \"z\": 30.0}"
```

Expected Response (200 OK):JSON 

```
37.416573867739416
```

Check your backend terminals: You will see the requests rotate cleanly between Port 8081, 8082, and 8083!

<b>3. Add Vectors (Circuit Breaker Protected)</b>

Adds two 3D vectors together. If the backend math service goes offline or throws an exception, Resilience4j intercepts the failure and returns a default zero-vector.

```
curl -v -H "Authorization: Bearer mock-test-token" -X POST http://localhost:8080/api/vectors/addVectors \
  -H "Content-Type: application/json" \
  -d "{\"v1\": {\"x\": 1.0, \"y\": 2.0, \"z\": 3.0}, \"v2\": {\"x\": 4.0, \"y\": 5.0, \"z\": 6.0}}"
```

Expected Response (200 OK):JSON

```
{
  "x": 5.0,
  "y": 7.0,
  "z": 9.0
}
```

<b>4. Scale Vector</b> 

Multiplies a 3D vector's coordinates by a scalar path variable.

```
curl -v -H "Authorization: Bearer mock-test-token" -X POST http://localhost:8080/api/vectors/scale/3 \
  -H "Content-Type: application/json" \
  -d "{\"x\": 1.0, \"y\": 2.0, \"z\": 3.0}"
```

Expected Response (200 OK):JSON

```
{
  "x": 3.0,
  "y": 6.0,
  "z": 9.0
}
```

<b>5. Retrieve Database Ledger History</b>

Queries PostgreSQL via Spring Data JPA (SELECT * FROM vector_calculations) and returns the complete calculation ledger.

```
curl -v -H "Authorization: Bearer mock-test-token" http://localhost:8080/api/vectors/history
```

Expected Response (200 OK):JSON

```
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

<b>6. Rate Limiter Exhaustion Test (With Standard Rate-Limit Headers)</b>

If you send more than 10 requests within a 30-second window, the gateway's atomic Redis Lua script intercepts the traffic and emits standard rate-limit headers. Run this rapidly 11 times:

```bash
curl -i -H "Authorization: Bearer mock-test-token" -X POST http://localhost:8080/api/vectors/calculateMagnitude -H "Content-Type: application/json" -d "{\"x\": 1.0, \"y\": 2.0, \"z\": 3.0}"
```

Expected Response (429 Too Many Requests):

```JSON
{
  "timestamp": "2026-06-24T21:56:40.902+00:00",
  "path": "/api/vectors/calculateMagnitude",
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded! You are limited to 10 requests per 30 seconds across the cluster.",
  "requestId": "42d6c1d2-5"
}
```

```
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 0
Retry-After: 30
Content-Type: text/plain;charset=UTF-8

Rate limit exceeded! You are limited to 10 requests per 30 seconds across the cluster.
```

<b>7. Circuit Breaker Fallback Test (RFC 9457 ProblemDetail)</b>

If the downstream microservice cluster is offline or times out, the gateway's Resilience4j Circuit Breaker forwards the request to `/fallback` and returns a standardized RFC 9457 `ProblemDetail` payload:

```bash
curl -i http://localhost:8080/fallback
```

Expected Response (503 Service Unavailable):

```JSON
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

The system includes an automated integration test harness that spins up an isolated server environment using Spring Boot's @SpringBootTest and WebTestClient.To run the automated security and rate-limiting tests:

```terminal
cd api-gateway
mvnw clean test
```

Test Cases Covered:

1. testHackerAttempt_shouldReturn401Unauthorized: Proves that requests missing Bearer tokens are blocked at the perimeter before routing.
2. testSpammingServer_shouldTriggerRateLimiter429: Executes a rapid loop of 10 valid requests to fill the queue, then asserts that the 11th request is rejected by Redis with HTTP 429 Too Many Requests.

---

## 🌩️ Extreme Load Testing (C10K Verification)

Because this Gateway utilizes **Java 21 Virtual Threads** and Tomcat socket tuning (`max-connections: 20000`), it can handle massive concurrent spikes without crashing.

You can verify this locally using a modern HTTP load generation tool.

**1. Test Tomcat's Virtual Thread Capacity**
Flood the unauthenticated health endpoint with 5,000 concurrent workers for 15 seconds to watch Tomcat absorb the traffic without running out of OS threads:

```bash
hey -z 15s -c 5000 http://localhost:8080/actuator/health
```

**2. Test the Redis Distributed Rate Limiter**

Send a massive burst of authenticated traffic to a protected route. You will see the first 10 requests succeed (HTTP 200), and the remaining thousands instantly rejected by the atomic Redis Lua script (HTTP 429), perfectly shielding your backend instances:

```bash
hey -n 1000 -c 100 -m GET -H "Authorization: Bearer mock-test-token" http://localhost:8080/api/vectors/ping
```


---

## ☁️ Google Cloud Platform (GCP) Deployment

The entire ecosystem is structured for auto-scaling serverless container deployment using Google Cloud Run, backed by a managed GCP MemoryStore (Redis) instance and Google Cloud SQL (PostgreSQL).

```markdown
> **☁️ Cloud Agnostic Design:** While the deployment guide below features Google Cloud, this ecosystem relies strictly on standard Docker containers and injected environment variables. It can be deployed identically to **AWS** (App Runner, ECS Fargate, ElastiCache) or **Microsoft Azure** (Container Apps, Azure Cache for Redis) without modifying a single line of Java code.
```

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
│   ⚡ Java 25/26 Virtual Threads + Leyden AOT Cache     │
└────────────────────────────────────────────────────────┘
       │                 │
       │ (Lua EVALSHA)   │ (Virtual-Thread HTTP/2 Router)
       ▼                 ▼
┌──────────────┐   ┌─────────────────────────────────────┐
│ Memorystore  │   │        Microservice Cluster         │
│ (Valkey 8)   │   │  (vector-api 1, vector-api 2, etc.) │
└──────────────┘   └─────────────────────────────────────┘
```

<b>Step 1: Build the Docker Containers</b>

Both services utilize lightweight Alpine Linux Java runtime environments. Compile production, optimized JAR artifacts locally before containerization:

```
mvnw clean package -DskipTests
```

<b>Step 2: Deploy Vector API to Cloud Run (Connected to Cloud SQL)</b>

Use the Google Cloud CLI (gcloud) to build and deploy the downstream backend service. This command automatically injects Cloud SQL PostgreSQL socket connection properties into your runtime environment:

```terminal
cd Web_Application_Spring

gcloud run deploy vector-api \
  --source . \
  --region us-central1 \
  --allow-unauthenticated \
  --add-cloudsql-instances YOUR_PROJECT_ID:us-central1:vector-db-instance \
  --set-env-vars SPRING_DATASOURCE_URL="jdbc:postgresql://google/vector_db?cloudSqlInstance=YOUR_PROJECT_ID:us-central1:vector-db-instance&socketFactory=com.google.cloud.sql.postgres.SocketFactory" \
  --set-env-vars SPRING_DATASOURCE_USERNAME=postgres \
  --set-env-vars SPRING_DATASOURCE_PASSWORD=your_cloud_password
```

<b>Step 3: Deploy API Gateway to Cloud Run</b>

Once the `vector-api` deploys, note its generated production HTTPS URL (e.g., https://vector-api-xyz.a.run.app). Update your Gateway's routing configuration. Update your gateway's routing block to point to this address, then deploy the gateway container.

This command injects your externalized enterprise configuration rules—including your private GCP MemoryStore (Redis) IP address and Lettuce connection pooling optimizations—directly into the environment variables:

```terminal
cd api-gateway

gcloud run deploy api-gateway \
  --source . \
  --region us-central1 \
  --allow-unauthenticated \
  --set-env-vars REDIS_HOST=10.X.X.X \
  --set-env-vars REDIS_PORT=6379 \
  --set-env-vars BACKEND_SERVICE_URL=https://vector-api-xyz.a.run.app \
  --set-env-vars OTEL_EXPORTER_OTLP_ENDPOINT=https://your-otel-collector:4318/v1/traces \
  --set-env-vars JWT_ISSUER_URI=https://your-tenant.us.auth0.com/ \
  --set-env-vars SPRING_PROFILES_ACTIVE=prod \
  --set-env-vars SPRING_DATA_REDIS_LETTUCE_POOL_MAX_ACTIVE=64 \
  --set-env-vars SPRING_DATA_REDIS_LETTUCE_POOL_MAX_IDLE=16 \
  --set-env-vars APP_RATE_LIMIT_WINDOW_MS=30000 \
  --set-env-vars APP_RATE_LIMIT_LIMIT=10 \
  --set-env-vars APP_CORS_ALLOWED_ORIGINS="https://your-frontend-domain.com" \
  --set-env-vars SPRING_THREADS_VIRTUAL_ENABLED=true

*(Note: Google Cloud Run automatically injects the `$PORT` environment variable, which the API Gateway dynamic configuration natively binds to.)*
```

Your microservice ecosystem is now fully live, globally optimized, and automatically scaling from 0 to thousands of concurrent instances on Google Cloud!