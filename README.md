# Microservice Ecosystem 🚀

An enterprise-grade, horizontally scalable microservice architecture engineered with **Java 21**, **Spring Boot 4.0**, **Spring Cloud Gateway**, and **PostgreSQL**. 

This system handles real-time computations, protected by reactive security, in-memory sliding-window rate limiting, and client-side load balancing. It is fully containerized and cloud-ready for **Google Cloud Platform (GCP)**.

---

## 🏛️ System Architecture

The application follows a modern N-Tier distributed microservice architecture:

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
