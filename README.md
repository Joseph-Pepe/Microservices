# Microservice Ecosystem 🚀

An enterprise-grade, horizontally scalable microservice architecture engineered with **Java 21 Virtual Threads**, **Spring Boot 4+**, **Spring Cloud Gateway MVC**, and **PostgreSQL**. 

This system handles real-time computations, protected by stateless OAuth2 (JWT) security, an atomic distributed Redis rate limiter, and client-side load balancing. It is fully containerized and cloud-ready for **Google Cloud Platform (GCP)**.

## 🏛️ System Architecture

The application follows a modern N-Tier distributed microservice architecture utilizing Java's Project Loom (Virtual Threads) for maximum non-blocking I/O throughput:

```text
RAW HTTP REQUEST ──► [ Authorization: Bearer eyJhbGciOi... ]
                                         |
                                         v
+-------------------------------------------------------------------------------+
|               SPRING SECURITY MVC (The Perimeter Firewall)                    |
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
|      Commercial IDaaS Cloud       |     |     Spring Cloud API Gateway MVC (Tomcat)       |       |              Redis Cluster           |
|  (Auth0 / Okta / AWS Cognito)     |     |                 (Port 8080)                     |       |              (Port 6379)             |
+-----------------------------------+     +-------------------------------------------------+ <---> +--------------------------------------+
| • Issues OIDC / OAuth2 Bearer JWT | <-- |  1. OAuth2 Resource Server (Stateless JWT)      |       | • Key: rate_limit:{username=jwt.sub} |
| • Hosts /.well-known/jwks.json    |     |  2. Global CORS Preflight Configuration         |       | • Data: Sorted Set (ZSET)            |
+-----------------------------------+     |  3. Distributed Rate Limiter (Atomic Lua Script)|       | • Score: Epoch Millis Timestamp      |
                                          |  4. Client-Side Load Balancer (Round-Robin)     |       | • Eviction: Auto TTL                 |
                                          |  5. Java 21 Virtual Threads (Non-blocking I/O)  |       +--------------------------------------+
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
                            |    +----------------------+ +----------------------+ +----------------------+    |   │ ├── Controller Layer (Thin HTTP Handlers)        │
                            |    | [Layer 1] Controller | | [Layer 1] Controller | | [Layer 1] Controller |    |   │ ├── Service Layer (Math & Business Logic)        │
                            |    | [Layer 2] Service    | | [Layer 2] Service    | | [Layer 2] Service    |    |   │ ├── Circuit Breaker (Resilience4j Fallbacks)     │
                            |    |   + Circuit Breaker  | |   + Circuit Breaker  | |   + Circuit Breaker  |    |   │ └── Repository Layer (Hibernate ORM Bridge)      │
                            |    | [Layer 3] Repository | | [Layer 3] Repository | | [Layer 3] Repository |    |   └──────────────────────────────────────────────────┘
                            |    +----------------------+ +----------------------+ +----------------------+    |         
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
| **Runtime** | Java 21 LTS | JDK 21 utilizing Project Loom (Virtual Threads) |
| **Framework** | Spring Boot | Core Spring application engine |
| **Gateway** | Spring Cloud Gateway MVC | Servlet-based API Router & Load Balancer |
| **Security** | Spring Security 6 | Stateless OAuth2 perimeter authentication |
| **Caching/Limits** | Redis | Centralized memory datastore for atomic rate limiting |
| **Database** | PostgreSQL | Relational persistence engine |
| **ORM** | Hibernate ORM | Object-Relational Mapping & DDL generation |
| **Resilience** | Resilience4j | Circuit breakers & fault tolerance |
| **API Docs** | SpringDoc OpenAPI 3 / Swagger UI | Automated endpoint documentation |
| **Container** | Docker | Multi-stage lightweight containerization |
| **Cloud** | Google Cloud Platform (GCP) | Cloud Run (Serverless) + Cloud SQL (Postgres) + MemoryStore |

---

## 🚀 Local Development Setup

### Prerequisites
* **JDK 21+** installed and added to your system `PATH`.
* **Apache Maven** (or use the included `mvnw` wrapper scripts).
* **PostgreSQL Server** running locally on port `5432` (via Docker or native Windows/macOS installer).
* **Redis Server** running locally on port `6379`.

<b>Step #1: Boot the Microservice Cluster (Load Balancer Setup)</b>

To simulate a horizontally scaled production environment, open three separate terminals inside the vector-service directory and start three clones on different ports:

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

### 1. Java 21 Virtual Threads (api-gateway)
* Replaces legacy OS thread pools and reactive WebFlux complexity by enabling spring.threads.virtual.enabled=true.
* Achieves massive non-blocking I/O scale using straightforward, synchronous Java MVC code.

### 2. Stateless OAuth2 Perimeter Firewall
* Intercepts all incoming traffic before routing, enforcing JWT Bearer token validation via `SecurityConfig.java`.
* Unauthenticated requests are immediately rejected with a clean `401 Unauthorized` without ever touching downstream services.
* Default denyAll() fail-closed architecture ensures unmapped routes are never accidentally exposed.
* Handles global CORS preflight (OPTIONS) requests seamlessly for React/Angular frontend integration.

### 3. Distributed Atomic Rate Limiter with Fail-Open Safety (DistributedRateLimiterFilter.java)
* Executes an Atomic Lua Script across a single network round-trip to completely prevent race conditions (TOCTOU).
* Tracks user timestamps across a rolling **30-second window**, limiting authenticated users across the cluster (e.g., 10 requests per 30 seconds, fully configurable via @Value annotations) to shield backend servers from DDoS attacks and API spam.
* Returns standardized 429 Too Many Requests responses with unique request tracking IDs when cluster limits are exhausted.
* Engineered with a Fail-Open resiliency strategy: If the Redis cluster goes down, a try-catch safety block catches the exception, logs a critical alert, and allows API traffic to pass through uninterrupted rather than generating cascading 500 errors.

### 4. Downstream Header Hydration & Security Offloading
* The gateway handles cryptographically heavy JWT verification at the perimeter.
* Offloads execution weight from downstream nodes by appending an automated upstream identity confirmation header (X-Gateway-Validated: true) to safe, routed requests.

### 5. Client-Side Load Balancing (`spring-cloud-starter-loadbalancer`)
* Configured with simple instance discovery (`lb://vector-service`) mapping traffic across multiple ports (`8081`, `8082`, `8083`).
* Distributes compute load using a **Round-Robin** algorithm, ensuring uniform CPU utilization across all active microservice clones.

### 6. Circuit Breaker & Fault Tolerance (`Resilience4j`)
* Wraps critical math operations with @CircuitBreaker (e.g., like vector addition: `@CircuitBreaker(name = "vectorMathService", fallbackMethod = "addVectorsFallback")`) .
* Automatically trips when backend error thresholds are exceeded, instantly rerouting traffic to a fallback method that returns a safe default (zero-vector `{x:0, y:0, z:0}`) to prevent catastrophic cascading system failures.

### 7. Automated ORM & Persistence (`Spring Data JPA` + `Hibernate 7`)
* Uses thin interfaces (`VectorCalculationRepository`) extending `JpaRepository` to eliminate boilerplate JDBC code.
* Configured with `ddl-auto: update`, allowing Hibernate to dynamically inspect and generate PostgreSQL database tables (`vector_calculations`) at startup.
* Implements ACID-compliant persistence, saving timestamped vector inputs and calculated magnitudes into a permanent ledger.

### 8. High-Throughput Integration Testing (`WebTestClient`)
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

<b>6. Rate Limiter Exhaustion Test</b>

If you send more than 10 requests within a 30-second window, the gateway's Redis Lua script intercepts the traffic. Run this rapidly 11 times:

```
curl -v -H "Authorization: Bearer mock-test-token" -X POST http://localhost:8080/api/vectors/calculateMagnitude -H "Content-Type: application/json" -d "{\"x\": 1.0, \"y\": 2.0, \"z\": 3.0}"
```

Expected Response (429 Too Many Requests):JSON

```
{
  "timestamp": "2026-06-24T21:56:40.902+00:00",
  "path": "/api/vectors/calculateMagnitude",
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded! You are limited to 10 requests per 30 seconds across the cluster.",
  "requestId": "42d6c1d2-5"
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

## ☁️ Google Cloud Platform (GCP) Deployment

The entire ecosystem is structured for auto-scaling serverless container deployment using Google Cloud Run, backed by a managed GCP MemoryStore (Redis) instance and Google Cloud SQL (PostgreSQL).

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
│    ⚡ Powered by Java 21/25 Virtual Threads            │
└────────────────────────────────────────────────────────┘
       │                 │
       │ (Lua Script)    │ (Round-Robin Routing)
       ▼                 ▼
┌──────────────┐   ┌─────────────────────────────────────┐
│ MemoryStore  │   │        Microservice Cluster         │
│   (Redis)    │   │  (vector-api 1, vector-api 2, etc.) │
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
  --set-env-vars SPRING_DATA_REDIS_HOST=10.X.X.X \
  --set-env-vars SPRING_DATA_REDIS_PORT=6379 \
  --set-env-vars SPRING_DATA_REDIS_LETTUCE_POOL_MAX_ACTIVE=64 \
  --set-env-vars SPRING_DATA_REDIS_LETTUCE_POOL_MAX_IDLE=16 \
  --set-env-vars APP_RATE_LIMIT_WINDOW_MS=30000 \
  --set-env-vars APP_RATE_LIMIT_LIMIT=10 \
  --set-env-vars APP_CORS_ALLOWED_ORIGINS="https://your-frontend-domain.com" \
  --set-env-vars SPRING_THREADS_VIRTUAL_ENABLED=true
```

Your microservice ecosystem is now fully live, globally optimized, and automatically scaling from 0 to thousands of concurrent instances on Google Cloud!