# Microservices Architecture & Interview Master Guide

This guide is designed to prepare you for technical interviews by providing an in-depth, structured breakdown of the entire distributed E-Commerce system. It details how each service functions internally, how services collaborate across the network, the architectural design patterns applied, and how to articulate every technical decision with confidence.

---

## 1. The 60-Second Interview Elevator Pitch

> *"I engineered a production-grade, distributed E-Commerce platform following the **Database-per-Service** and **API Gateway** patterns, using **Spring Boot**, **Spring Cloud**, **MySQL**, **Redis**, and **Resilience4j**. 
>
> All client traffic enters through a **Spring Cloud Gateway** that performs perimeter JWT authentication, validates token signatures, and mutates request headers to pass authenticated user identity downstream. Dynamic service discovery is powered by **Netflix Eureka**, enabling client-side load balancing without hardcoded IPs.
>
> In the domain layer, **Product Service** accelerates catalog reads using **Redis Cache-Aside** and provides dynamic multi-parameter querying via **Spring Data JPA Specifications**. **Order Service** coordinates transactions by validating and deducting inventory in real-time via **OpenFeign**, guarded against cascading failures by **Resilience4j Circuit Breakers, Retries, and Bulkheads**.
>
> For payments, I integrated the **Paytm Payment Gateway** with cryptographic **HMAC-SHA256 checksum verification** and database **idempotency deduplication** to protect against webhook replay attacks. Finally, an asynchronous **Notification Service** dispatches styled HTML receipts via **JavaMailSender** and event streams. The entire system is decoupled, resilient, and secure."*

---

## 2. Master Architectural Blueprint

```
                                      [ Client / Mobile / Frontend ]
                                                     │
                                                     │ HTTP Requests (Bearer JWT)
                                                     ▼
                                ┌──────────────────────────────────────────┐
                                │    Spring Cloud API Gateway (:8000)      │
                                │  - Edge Reverse Proxy & Routing (lb://)  │
                                │  - Global JWT Authentication Filter      │
                                │  - Header Mutation (X-User-Id, Role)     │
                                │  - Public Whitelists (Auth, Products, Wh)│
                                └────────────────────┬─────────────────────┘
                                                     │
       ┌────────────────────┬────────────────────────┼────────────────────────┬────────────────────┐
       ▼                    ▼                        ▼                        ▼                    ▼
┌──────────────┐     ┌──────────────┐         ┌──────────────┐         ┌──────────────┐     ┌──────────────┐
│ User Service │     │Product Service│        │Order Service │         │Payment Service│    │Notification  │
│   (:8083)    │     │   (:8081)    │         │   (:8082)    │         │   (:8084)    │     │Service (:8085│
│- Dual Tokens │     │- Catalog CRUD│         │- Place Order │         │- Paytm GW    │     │- Java Mail   │
│- Redis BlkLst│     │- JPA Spec Srch         │- Feign Stock │         │- HMAC-SHA256 │     │- HTML Receipts
│- Admin RBAC  │     │- Redis Cache │         │- Resilience4j│         │- Idempotency │     │- Kafka Events│
└──────┬───────┘     └──────┬───────┘         └──────┬───────┘         └──────┬───────┘     └──────┬───────┘
       │                    │                        │                        │                    │
  Redis Cache          Redis Cache             Feign │ Stock Check & Deduct   │                    │ Feign / Kafka
(localhost:6379)     (localhost:6379)          Client│ (with CircuitBreaker)  │                    │ Notifications
       │                    │                        ▼                        │                    ▼
  MySQL: user_db     MySQL: product_db_m       MySQL: order_db_m              │            MySQL: notification_db
                                                     ▲                        │
                                                     │ OpenFeign Status Update│
                                                     └────────────────────────┘
                                                       PUT /status (CONFIRMED)
                                                              │
                                                              ▼
                                                      MySQL: payment_db
══════════════════════════════════════════════════════════════════════════════════════════════════════════════════
                                     Netflix Eureka Server (:8761)
                 (Dynamic Service Registry: Heartbeat Tracking & Client-Side Load Balancing)
```

---

## 3. Deep-Dive Service Breakdown

### 1. Eureka Server (`:8761`) — *Dynamic Service Discovery*
- **Role:** Centralized phonebook for the entire cluster.
- **How It Works:**
  - Microservices register under logical application names (`USER-SERVICE`, `PRODUCT-SERVICE`, `ORDER-SERVICE`, `PAYMENT-SERVICE`, `NOTIFICATION-SERVICE`).
  - Instances send heartbeats every 30 seconds. If heartbeats stop for 90 seconds, Eureka de-registers the instance.
  - Eliminates hardcoded IP/port references and enables client-side load balancing via Ribbon / Spring Cloud LoadBalancer.

### 2. Spring Cloud API Gateway (`:8000`) — *Edge Perimeter & Header Enrichment*
- **Role:** Single ingress entry point, reverse proxy, and perimeter security gate.
- **How It Works:**
  1. **Route Predicates:** Directs URL prefixes (`/api/products/**`, `/api/orders/**`, `/api/auth/**`, `/api/payments/**`, `/api/notifications/**`) to target services using `lb://SERVICE-NAME`.
  2. **Security & Whitelisting:**
     - Whitelists `/api/auth/**`, `GET /api/products/**`, `/eureka/**`, `/actuator/**`, and the Paytm webhook `/api/payments/verify`.
     - Non-whitelisted endpoints require `Authorization: Bearer <token>`.
  3. **Global Filter & Header Mutation:**
     - Intercepts requests, validates token HMAC-SHA256 signature and expiration via `JwtUtil`.
     - Extracts claims (`userId`, `role`, `username`) and mutates the downstream request headers:
       - `X-User-Id: 10`
       - `X-User-Role: ROLE_USER`
       - `X-User-Name: john_doe`
     - Allows downstream domain services to trust identity headers without redundant database or token lookups.

### 3. User Service (`:8083`) — *Authentication, Refresh Tokens & Redis Blacklisting*
- **Role:** Identity management, authorization, and session invalidation.
- **Database:** `user_db` (MySQL) + Redis (`localhost:6379`).
- **Key Capabilities:**
  - **Dual Token Architecture:** Issues short-lived access tokens (15 minutes) and long-lived refresh tokens (7 days).
  - **Redis Token Blacklisting:** On `POST /api/auth/logout`, the access token is written to Redis with a TTL matching its expiration. The `JwtAuthenticationFilter` checks `tokenBlacklistService.isTokenBlacklisted()`, immediately revoking access even if the token has not expired naturally.
  - **Role-Based Access Control (RBAC):** Supports `ROLE_USER` and `ROLE_ADMIN`. Administrative routes (`/api/admin/users`) are secured with `@PreAuthorize("hasRole('ADMIN')")`.

### 4. Product Service (`:8081`) — *JPA Specification Dynamic Search & Redis Caching*
- **Role:** High-throughput product catalog and inventory state management.
- **Database:** `product_db_m` (MySQL) + Redis (`localhost:6379`).
- **Key Capabilities:**
  - **Dynamic JPA Specification Search:**
    - Uses Spring Data's `JpaSpecificationExecutor<Product>`.
    - Dynamically builds JPA criteria predicates based on query parameters:
      - `name`: Case-insensitive `LIKE %name%`
      - `category`: Exact match
      - `minPrice` / `maxPrice`: Greater-than-or-equal / Less-than-or-equal
      - `inStock`: Checks `stockQuantity > 0`
    - Prevents query explosion and eliminates fragile concatenation of custom queries.
  - **Redis Cache-Aside:**
    - `@Cacheable(value = "products", key = "#id")` and `@Cacheable(value = "productList")`.
    - Read hits resolve in < 5ms.
    - Write operations (`POST`, `PUT`, `DELETE`) trigger `@CacheEvict(allEntries = true)` to ensure immediate cache invalidation.
  - **Stock Management Endpoints:**
    - `GET /api/products/{id}/check-stock?quantity=X`: Non-mutating inventory check.
    - `POST /api/products/{id}/deduct-stock?quantity=X`: Decrements inventory and flushes Redis cache.

### 5. Order Service (`:8082`) — *OpenFeign Orchestration & Resilience4j Fault Tolerance*
- **Role:** Order creation, pricing calculation, and inventory coordination.
- **Database:** `order_db_m` (MySQL).
- **Key Capabilities:**
  - **OpenFeign Pre-Order Stock Validation:**
    - Before saving an order, invokes `productServiceClient.checkStock(productId, quantity)`.
    - If `quantity > stockQuantity`, returns `400 Bad Request ("Insufficient stock")`.
    - Invokes `productServiceClient.deductStock(productId, quantity)` to atomically reserve inventory.
  - **Resilience4j Fault Tolerance Suite:**
    - `@CircuitBreaker(name = "productService", fallbackMethod = "productFallback")`:
      - Monitors failure rate over a sliding window of 10 calls.
      - If 50% fail, trips to `OPEN` state, immediately executing fallback without overloading `product-service`.
      - Transitions to `HALF_OPEN` after 5 seconds to test recovery.
    - `@Retry(name = "productService")`: Retries transient network glitches up to 3 times with exponential backoff.
    - `@Bulkhead(name = "productService")`: Restricts concurrent calls to 10 threads, preventing thread pool exhaustion.

### 6. Payment Service (`:8084`) — *Paytm Gateway, Checksums & Idempotency*
- **Role:** Transaction lifecycle, gateway orchestration, and order finalization.
- **Database:** `payment_db` (MySQL).
- **Key Capabilities:**
  - **Paytm Payment Gateway Integration:**
    - Connects to Paytm Sandbox using `mid`, `merchantKey`, and `callbackUrl`.
  - **HMAC-SHA256 Signature Security:**
    - Generates cryptographic signature for initiation requests.
    - On callback/webhook (`POST /api/payments/verify`), recalculates the HMAC-SHA256 signature using `PaytmChecksumUtil` and compares with `CHECKSUMHASH`. If signature doesn't match, rejects as a security violation.
  - **Database Idempotency Deduplication:**
    - Webhook callbacks can be retried multiple times by payment providers.
    - When a callback is received, the service inspects the existing payment record: if already marked `SUCCESS`, it returns immediately without re-triggering order updates or notification emails.
  - **Order Confirmation:**
    - Calls `orderServiceClient.updateOrderStatus(orderId, "CONFIRMED")` via OpenFeign.

### 7. Notification Service (`:8085`) — *Java Mail Sender & Kafka Event Stream*
- **Role:** Asynchronous messaging, audit trail, and customer communications.
- **Database:** `notification_db` (MySQL).
- **Key Capabilities:**
  - **JavaMailSender HTML Receipts:** Formats and dispatches styled HTML emails for order placement and payment receipts.
  - **Audit Logging:** Every dispatched notification is persisted in `notification_db` with recipient, subject, eventType, and status.
  - **Kafka Consumer with Graceful Fallback:**
    - Configured with `@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true")`.
    - Listens to topics `order-events` and `payment-events`.
    - If Kafka is disabled or unavailable locally, direct OpenFeign triggers ensure notifications are still processed without crashes.

---

## 4. End-to-End Walkthrough Script for the Interviewer

When asked: *"Walk me through the complete end-to-end lifecycle of placing an order and paying for it,"* follow this script:

```
1. Authentication:
   Client ──► POST /api/auth/login ──► API Gateway ──► User Service
   └── User Service validates BCrypt password, returns Access Token (15m) and Refresh Token (7d).

2. Catalog Search & Filter:
   Client ──► GET /api/products/search?category=Electronics&minPrice=100&inStock=true
   └── API Gateway whitelists request ──► Product Service executes dynamic JPA Specification ──► Returns filtered items.

3. Order Placement & Inventory Reservation:
   Client ──► POST /api/orders/placeOrder (productId=5, quantity=2) with Bearer Token
   ├── Gateway validates JWT signature and expiration.
   ├── Gateway injects 'X-User-Id: 10' and 'X-User-Role: ROLE_USER' headers.
   ├── Order Service receives request:
   │   ├── Invokes ProductServiceClient.checkStock(5, 2) via OpenFeign (Resilience4j protected)
   │   │   └── If stock is insufficient, throws 400 Bad Request.
   │   ├── Invokes ProductServiceClient.deductStock(5, 2) to reserve items and invalidate Redis cache.
   │   ├── Computes total price ($100.00).
   │   └── Saves order in order_db_m with status = 'PENDING' (OrderId=101).
   └── Order Service calls Notification Service to dispatch "Order Placed" email.

4. Paytm Payment Initiation:
   Client ──► POST /api/payments/initiate (orderId=101, amount=100.00)
   ├── Payment Service builds parameter map (MID, ORDER_ID, TXN_AMOUNT, CALLBACK_URL).
   ├── Computes HMAC-SHA256 checksum signature using Paytm secret key.
   ├── Records payment entry in payment_db with status = 'PENDING'.
   └── Returns checksum and redirection URL to frontend.

5. Payment Gateway Callback & Idempotent Verification:
   Paytm Gateway ──► POST /api/payments/verify (ORDERID=101, TXNID=..., STATUS=TXN_SUCCESS, CHECKSUMHASH=...)
   ├── Gateway bypasses authentication for webhook callback.
   ├── Payment Service verifies HMAC-SHA256 signature against parameters.
   ├── Idempotency Check: Verifies payment 101 has not already been finalized.
   ├── Updates payment status to 'SUCCESS' and records Paytm transaction ID in payment_db.
   ├── OpenFeign Call: Invokes OrderServiceClient.updateOrderStatus(101, 'CONFIRMED').
   │   └── Order Service transitions order state to 'CONFIRMED' in order_db_m.
   └── OpenFeign Call: Invokes NotificationServiceClient.sendPaymentSuccess(...)
       └── Notification Service sends styled HTML payment receipt to customer email.
```

---

## 5. Architectural Patterns Q&A

### Q1: Why did you adopt the Database-per-Service pattern?
> **Answer:** *"A shared database is an anti-pattern in microservices because it creates tight coupling at the data tier. If multiple services read and write to the same tables, schema changes in one domain can silently break another. Database-per-Service guarantees domain autonomy: each team owns its data model and enforces business invariants through explicit REST/Feign APIs."*

### Q2: Why validate JWTs at the API Gateway rather than within each microservice?
> **Answer:** *"Centralizing JWT validation at the Gateway enforces the DRY principle and establishes a clear perimeter defense. Invalid, expired, or tampered tokens are rejected with HTTP 401 before consuming any internal compute or database resources. The Gateway mutates the request headers by injecting `X-User-Id` and `X-User-Role`, allowing downstream domain services to focus strictly on business logic."*

### Q3: How did you implement Redis Token Blacklisting for stateless JWT logout?
> **Answer:** *"Because JWTs are stateless and signed cryptographically, they remain valid until expiration even after a user logs out. To handle logout securely, when a user calls `POST /api/auth/logout`, we extract the remaining TTL of the access token and store the token string in Redis with that TTL as its key expiration. When requests arrive, the filter checks `TokenBlacklistService.isTokenBlacklisted()`. If present in Redis, the request is rejected with HTTP 401. Once the token's natural expiry passes, Redis automatically evicts the key, preventing memory bloat."*

### Q4: Why use Spring Data JPA Specifications over custom SQL queries?
> **Answer:** *"In e-commerce search, users can combine arbitrary filters—category, minPrice, maxPrice, availability, or keywords. Writing separate queries or concatenating SQL strings leads to combinatorial explosion and SQL injection vulnerabilities. JPA Specifications utilize the JPA Criteria API, allowing us to compose reusable predicates dynamically based on whatever parameters are provided at runtime."*

### Q5: How does Resilience4j protect your system from cascading microservice failures?
> **Answer:** *"When one downstream service experiences high latency or crashes, calling threads can back up and exhaust the caller's thread pool, causing a cascade of failures. Resilience4j prevents this using:
> 1. **CircuitBreaker:** Monitors failure rates. When errors exceed 50%, it trips `OPEN`, failing fast and routing requests to a fallback method without hitting the downstream service.
> 2. **Retry:** Automatically retries transient network errors with exponential backoff.
> 3. **Bulkhead:** Restricts the maximum number of concurrent calls to an external dependency, isolating thread pools."*

### Q6: How does your Paytm integration ensure security and idempotency?
> **Answer:** *"First, for data integrity, we generate and verify cryptographic HMAC-SHA256 checksums on all request parameters and webhook responses using the merchant secret key. If a payload is tampered with, the signature mismatch causes an immediate rejection.
> Second, payment gateways frequently retry webhooks if network latency delays their acknowledgment. To ensure idempotency, our verify method checks whether the transaction has already reached terminal status (`SUCCESS` or `REFUNDED`). If so, we return the existing payment confirmation without re-executing order status updates or sending duplicate confirmation emails."*

### Q7: Why use Spring Cloud OpenFeign over RestTemplate or WebClient?
> **Answer:** *"RestTemplate requires manual URL construction, query string escaping, and error handling boilerplate. OpenFeign is declarative—we define a clean Java interface annotated with Spring MVC mappings. Spring Cloud dynamically generates the proxy implementation and integrates natively with Eureka for dynamic discovery and client-side load balancing (`lb://ORDER-SERVICE`)."*
