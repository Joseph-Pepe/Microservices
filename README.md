# Microservice Ecosystem 🚀

An enterprise-grade, horizontally scalable microservice architecture engineered with **Java 21**, **Spring Boot 4.0**, **Spring Cloud Gateway**, and **PostgreSQL**. 

This system handles real-time computations, protected by reactive security, in-memory sliding-window rate limiting, and client-side load balancing. It is fully containerized and cloud-ready for **Google Cloud Platform (GCP)**.

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
    |  Vector API Clone 1  | |  Vector API Clone 2  | |  Vector API Clone 3  |
    |     (Port 8081)      | |     (Port 8082)      | |     (Port 8083)      |
    +----------------------+ +----------------------+ +----------------------+
    | [Layer 1] Controller | | [Layer 1] Controller | | [Layer 1] Controller |
    | [Layer 2] Service    | | [Layer 2] Service    | | [Layer 2] Service    |
    |   + Circuit Breaker  | |   + Circuit Breaker  | |   + Circuit Breaker  |
    | [Layer 3] Repository | | [Layer 3] Repository | | [Layer 3] Repository |
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

              

[ Client / cURL ]
│
▼  

(HTTP Basic Auth : Port 8080)
┌──────────────────────────────────────────────────┐
│              Spring Cloud Gateway                │
│  ├── Security Firewall (Reactive Basic Auth)     │
│  ├── Rate Limiter (10 Req / 30 Sec Sliding Win.) │
│  └── Load Balancer (Round-Robin : lb://)         │
└──────────────────────┬───────────────────────────┘
                       │
         ┌─────────────┼─────────────┐
         ▼             ▼             ▼
    [Port 8081]   [Port 8082]   [Port 8083]
┌──────────────────────────────────────────────────┐
│      Microservice Clones (Spring JPA)     │
│  ├── Controller Layer (Thin HTTP Handlers)       │
│  ├── Service Layer (Math & Business Logic)       │
│  ├── Circuit Breaker (Resilience4j Fallbacks)    │
│  └── Repository Layer (Hibernate ORM Bridge)     │
└──────────────────────┬───────────────────────────┘
                       │
                       ▼  
               (JDBC / Port 5432)
┌──────────────────────────────────────────────────┐
│            PostgreSQL 18 Database                │
│  └── Table: vector_calculations (History Ledger) │
└──────────────────────────────────────────────────┘
