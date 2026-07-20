# Microservice Ecosystem 🚀

An enterprise-grade, horizontally scalable microservice architecture engineered with **Java 21**, **Spring Boot 4.0**, **Spring Cloud Gateway**, and **PostgreSQL**. 

This system handles real-time computations, protected by reactive security, in-memory sliding-window rate limiting, and client-side load balancing. It is fully containerized and cloud-ready for **Google Cloud Platform (GCP)**.

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
    |  Vector API Clone 1  | |  Vector API Clone 2  | |  Vector API Clone 3  |                      ┌──────────────────────────────────────────────────┐
    |     (Port 8081)      | |     (Port 8082)      | |     (Port 8083)      |                      │        Microservice Clones (Spring JPA)          │
    +----------------------+ +----------------------+ +----------------------+                      │  ├── Controller Layer (Thin HTTP Handlers)       │
    | [Layer 1] Controller | | [Layer 1] Controller | | [Layer 1] Controller |                      |  ├── Service Layer (Math & Business Logic)       |
    | [Layer 2] Service    | | [Layer 2] Service    | | [Layer 2] Service    |                      │  ├── Circuit Breaker (Resilience4j Fallbacks)    │
    |   + Circuit Breaker  | |   + Circuit Breaker  | |   + Circuit Breaker  |                      │  └── Repository Layer (Hibernate ORM Bridge)     │
    | [Layer 3] Repository | | [Layer 3] Repository | | [Layer 3] Repository |                      └──────────────────────┬───────────────────────────┘
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
