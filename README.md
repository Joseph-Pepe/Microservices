# Microservice Ecosystem 🚀

An enterprise-grade, horizontally scalable microservice architecture engineered with **Java 21**, **Spring Boot 4.0**, **Spring Cloud Gateway**, and **PostgreSQL**. 

This system handles real-time computations, protected by reactive security, in-memory sliding-window rate limiting, and client-side load balancing. It is fully containerized and cloud-ready for **Google Cloud Platform (GCP)**.

## 🏛️ System Architecture

The application follows a modern N-Tier distributed microservice architecture:

              +-------------------------------------------------+
              |              External Web Clients               |
              |  (cURL, React / Angular Frontend, Mobile Apps)  |
              +-------------------------------------------------+
                                       |
                                       | HTTP POST/GET (Basic Auth: admin / vector-secret-123)
                                       v
              +-------------------------------------------------+
              |          Spring Cloud API Gateway               |
              |                (Port 8080)                      |
              +-------------------------------------------------+
              |  1. Perimeter Firewall (Spring Security WebFlux)|
              |  2. Sliding-Window Rate Limiter (10 req/30 sec) |
              |  3. Client-Side Load Balancer (Round-Robin)     |
              +-------------------------------------------------+
                                       |
                +----------------------+----------------------+
                |                      |                      |
         lb://vector-service    lb://vector-service    lb://vector-service
                |                      |                      |
                v                      v                      v
    +----------------------+ +----------------------+ +----------------------+
    |  Vector API Clone 1  | |  Vector API Clone 2  | |  Vector API Clone 3  |         ┌──────────────────────────────────────────────────┐
    |     (Port 8081)      | |     (Port 8082)      | |     (Port 8083)      |         │           Microservice (Spring JPA)              │
    +----------------------+ +----------------------+ +----------------------+         │  ├── Controller Layer (Thin HTTP Handlers)       │
    | [Layer 1] Controller | | [Layer 1] Controller | | [Layer 1] Controller |         |  ├── Service Layer (Math & Business Logic)       |
    | [Layer 2] Service    | | [Layer 2] Service    | | [Layer 2] Service    |         │  ├── Circuit Breaker (Resilience4j Fallbacks)    │
    |   + Circuit Breaker  | |   + Circuit Breaker  | |   + Circuit Breaker  |         │  └── Repository Layer (Hibernate ORM Bridge)     │
    | [Layer 3] Repository | | [Layer 3] Repository | | [Layer 3] Repository |         └──────────────────────┬───────────────────────────┘
    +----------------------+ +----------------------+ +----------------------+
                \                      |                      /
                 \                     |                     /
                  v                    v                    v
              +-------------------------------------------------+
              |             PostgreSQL Database                 |
              |         (Port 5432 / GCP Cloud SQL)             |
              +-------------------------------------------------+
              |  Table: vector_calculations                     |
              |  Schema: id, calculated_at, magnitude, x, y, z  |
              +-------------------------------------------------+


## 🛠️ Technology Stack

| Component | Technology | Description |
| :--- | :--- | :--- |
| **Runtime** | Java 21 LTS | JDK 21 |
| **Framework** | Spring Boot 4.0.6 | Core Spring application engine |
| **Gateway** | Spring Cloud Gateway | Reactive API Router & Load Balancer |
| **Security** | Spring Security (WebFlux) | Reactive perimeter authentication |
| **Database** | PostgreSQL 18.4 | Relational persistence engine |
| **ORM** | Hibernate ORM 7.2.12 | Object-Relational Mapping & DDL generation |
| **Resilience** | Resilience4j | Circuit breakers & fault tolerance |
| **API Docs** | SpringDoc OpenAPI 3 / Swagger UI | Automated endpoint documentation |
| **Container** | Docker | Multi-stage lightweight containerization |
| **Cloud** | Google Cloud Platform (GCP) | Cloud Run (Serverless) + Cloud SQL (Postgres) |

---

## 🚀 Local Development Setup

### Prerequisites
* **JDK 21+** installed and added to your system `PATH`.
* **Apache Maven** (or use the included `mvnw` wrapper scripts).
* **PostgreSQL Server** running locally on port `5432` (via Docker or native Windows/macOS installer).

<b>Boot the Microservice Cluster (Load Balancer Setup)</b>

To simulate a horizontally scaled production environment, open three separate terminals inside the Web_Application_Spring directory and start three clones on different ports:

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

<b>Step 3: Boot the API Gateway</b>

Open a fourth terminal inside the api-gateway directory and launch the gateway:

```terminal
mvnw clean spring-boot:run
```

---

## ✨ Key Features

### 1. Reactive Perimeter Firewall (`api-gateway`)
* Built on top of **Netty** and **Spring Security WebFlux**.
* Intercepts all incoming traffic before routing, enforcing HTTP Basic Authentication (`admin` / `vector-secret-123`) via `SecurityConfig.java`.
* Unauthenticated requests are immediately rejected with a clean `401 Unauthorized` without ever touching downstream services.

### 2. Sliding-Window Rate Limiter (`LocalRateLimiterFilter.java`)
* Features a thread-safe in-memory memory queue (`ConcurrentLinkedQueue`) that tracks user timestamps across a rolling **30-second window**.
* Limits authenticated users to **10 requests per 30 seconds** to shield backend servers from DDoS attacks and API spam.
* Throws reactive `ResponseStatusException` signals, returning standardized `429 Too Many Requests` JSON payloads with unique request tracking IDs.

### 3. Client-Side Load Balancing (`spring-cloud-starter-loadbalancer`)
* Configured with simple instance discovery (`lb://vector-service`) mapping traffic across multiple ports (`8081`, `8082`, `8083`).
* Distributes compute load using a **Round-Robin** algorithm, ensuring uniform CPU utilization across all active microservice clones.

### 4. Circuit Breaker & Fault Tolerance (`Resilience4j`)
* Wraps critical math operations (like vector addition) with `@CircuitBreaker(name = "vectorMathService", fallbackMethod = "addVectorsFallback")`.
* Automatically trips when backend error thresholds are exceeded, instantly rerouting traffic to a fallback method that returns a safe default (zero-vector `{x:0, y:0, z:0}`) to prevent catastrophic cascading system failures.

### 5. Automated ORM & Persistence (`Spring Data JPA` + `Hibernate 7`)
* Uses thin interfaces (`VectorCalculationRepository`) extending `JpaRepository` to eliminate boilerplate JDBC code.
* Configured with `ddl-auto: update`, allowing Hibernate to dynamically inspect and generate PostgreSQL database tables (`vector_calculations`) at startup.
* Implements ACID-compliant persistence, saving timestamped vector inputs and calculated magnitudes into a permanent ledger.

### 6. Automated Reactive Integration Testing (`WebTestClient`)
* Complete automated test suite (`GatewaySecurityAndRateLimitTests.java`) designed for WebFlux asynchronous pipelines.
* Uses custom timeout mutations (`Duration.ofSeconds(10)`) to gracefully handle server cold-starts while mathematically verifying perimeter security drops and rate-limiter queue exhaustion.

---



📡 API Reference & cURL Verification

All client interactions must be directed to the API Gateway on Port 8080. Direct access to backend ports (8081-8083) should be blocked in production environments.

<b>1. Routing Verification</b>

Tests the connection through the gateway to any available backend service.

```
curl -v -u admin:vector-secret-123 http://localhost:8080/api/vectors/ping
```

Expected Response (200 OK): 

```
PlaintextController and Service are successfully connected!
```

<b>2. Calculate Magnitude & Save to Ledger</b>

Calculates $\sqrt{x^2 + y^2 + z^2}$, returns the exact scalar result, and permanently logs the transaction in PostgreSQL.

```
curl -v -u admin:vector-secret-123 -X POST http://localhost:8080/api/vectors/calculateMagnitude \
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
curl -v -u admin:vector-secret-123 -X POST http://localhost:8080/api/vectors/addVectors \
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
curl -v -u admin:vector-secret-123 -X POST http://localhost:8080/api/vectors/scale/3 \
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

Queries PostgreSQL via Spring Data JPA (SELECT * FROM vector_calculations) and returns the complete calculation ledger.Bashcurl -v -u admin:vector-secret-123 http://localhost:8080/api/vectors/history
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

If you send more than 10 requests within a 30-second window, the gateway's bouncer intercepts the traffic:Bash# Fire rapidly 11 times in a row

```
curl -v -u admin:vector-secret-123 -X POST http://localhost:8080/api/vectors/calculateMagnitude -H "Content-Type: application/json" -d "{\"x\": 1.0, \"y\": 2.0, \"z\": 3.0}"
```

Expected Response (429 Too Many Requests):JSON

```
{
  "timestamp": "2026-06-24T21:56:40.902+00:00",
  "path": "/api/vectors/calculateMagnitude",
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded! You are limited to 10 requests per 30 seconds.",
  "requestId": "42d6c1d2-5"
}
```

🧪 Automated Testing Suite

The system includes an automated integration test harness that spins up an isolated reactive server environment using Spring Boot's @SpringBootTest and WebTestClient.To run the automated security and rate-limiting tests:

```
cd api-gateway
mvnw clean test
```

Test Cases Covered:

1. testHackerAttempt_shouldReturn401Unauthorized: Proves that requests missing Basic Auth headers are blocked at the perimeter before routing.
2. testSpammingServer_shouldTriggerRateLimiter429: Executes a rapid loop of 10 valid requests to fill the sliding window queue, then asserts that the 11th request is rejected with HTTP 429 Too Many Requests.

☁️ Google Cloud Platform (GCP) Deployment

The entire ecosystem is structured for serverless container deployment using Google Cloud Run and Google Cloud SQL.

Step 1: Build the Docker ContainersBoth services utilize lightweight Alpine Linux Java runtime containers. The included Dockerfile in each root directory:Dockerfile

```
FROM eclipse-temurin:21-jre-alpine
VOLUME /tmp
COPY target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

Compile production JAR artifacts locally:

```
mvnw clean package -DskipTests
```

Step 2: Deploy Vector API to Cloud Run (Connected to Cloud SQL)

Use the Google Cloud CLI (gcloud) to build and deploy the backend service, injecting Cloud SQL PostgreSQL socket connection properties dynamically:

```
cd Web_Application_Spring
gcloud run deploy vector-api \
  --source . \
  --region us-central1 \
  --allow-unauthenticated \
  --add-cloudsql-instances YOUR_PROJECT_ID:us-central1:vector-db-instance \
  --set-env-vars SPRING_DATASOURCE_URL=jdbc:postgresql://google/vector_db?cloudSqlInstance=YOUR_PROJECT_ID:us-central1:vector-db-instance&socketFactory=com.google.cloud.sql.postgres.SocketFactory \
  --set-env-vars SPRING_DATASOURCE_USERNAME=postgres \
  --set-env-vars SPRING_DATASOURCE_PASSWORD=your_cloud_password
```

Step 3: Deploy API Gateway to Cloud Run

Once the vector-api deploys, take its generated cloud HTTPS URL (e.g., https://vector-api-xyz.a.run.app) and update your Gateway's routing configuration. Then deploy the gateway:

```
cd api-gateway
gcloud run deploy api-gateway \
  --source . \
  --region us-central1 \
  --allow-unauthenticated
```

Microservice Ecosystem is now live, auto-scaling from 0 to thousands of instances globally on Google Cloud!
