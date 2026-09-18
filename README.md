# Distributed E-Commerce Microservices Architecture

A production-grade, distributed microservices platform built using **Spring Boot 4.1.1 / Spring Cloud 2025**, **MySQL**, **Redis**, **Paytm Payment Gateway**, **Resilience4j**, **Java Mail**, and **Apache Kafka**.

The platform demonstrates dynamic service discovery, edge routing with centralized JWT security and token blacklisting, dynamic catalog filtering with JPA Specifications, distributed caching with cache eviction, declarative inter-service communication via OpenFeign with Resilience4j fault tolerance, Paytm cryptographic payment handling, asynchronous notification dispatch, and independent per-service database schemas.

---

## 1. Executive Summary & Architecture Overview

The system is engineered following the **Database-per-Service**, **API Gateway**, and **Circuit Breaker** patterns. All client interactions enter through a single entry point—Spring Cloud Gateway—which performs edge authentication, token validation, and header mutation before forwarding requests to internal services via Eureka service discovery.

```
                                      [ Client / Frontend ]
                                                │
                                                │ HTTP Requests (Bearer JWT)
                                                ▼
                           ┌──────────────────────────────────────────┐
                           │    Spring Cloud API Gateway (:8000)      │
                           │  - Edge Routing (lb://)                  │
                           │  - Global JWT Authentication Filter      │
                           │  - Header Mutation (X-User-Id, Role)     │
                           │  - Whitelist (Auth, Products, Paytm Wh)  │
                           └────────────────────┬─────────────────────┘
                                                │
       ┌────────────────────┬───────────────────┼───────────────────┬───────────────────┐
       ▼                    ▼                   ▼                   ▼                   ▼
┌──────────────┐     ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ User Service │     │Product Service│   │Order Service │    │Payment Service│   │Notification  │
│   (:8083)    │     │   (:8081)    │    │   (:8082)    │    │   (:8084)    │    │Service (:8085│
│- Auth/Refresh│     │- Catalog CRUD│    │- Place Order │    │- Paytm GW    │    │- Java Mail   │
│- Redis BlkLst│     │- JPA Spec Srch    │- Stock Check │    │- HMAC-SHA256 │    │- HTML Receipt│
│- RBAC Admin  │     │- Redis Cache │    │- Resilience4j│    │- Idempotency │    │- Kafka Events│
└──────┬───────┘     └──────┬───────┘    └──────┬───────┘    └──────┬───────┘    └──────┬───────┘
       │                    │                   │                   │                   │
  Redis Cache          Redis Cache        Feign │ Stock Check Feign │ Confirmed         │ Feign / Kafka
(localhost:6379)     (localhost:6379)     Client│ & Deduct    Client│ Status Update     │ Notifications
       │                    │                   ▼                   ▼                   ▼
  MySQL: user_db     MySQL: product_db_m  MySQL: order_db_m  MySQL: payment_db   MySQL: notification_db
══════════════════════════════════════════════════════════════════════════════════════════════════════
                                Netflix Eureka Server (:8761)
                     (Service Discovery & Registry for all microservices)
```

---

## 2. Microservices Directory

| Service | Port | Database / Cache | Technologies Used | Primary Responsibility |
| :--- | :---: | :--- | :--- | :--- |
| **`eureka-server`** | `8761` | None | Spring Cloud Eureka Server | Centralized Service Registry, instance heartbeat tracking, and dynamic resolution. |
| **`api-gateway-service`** | `8000` | None | Spring Cloud Gateway WebFlux, JJWT 0.12 | Edge reverse proxy, token verification, header mutation, route load balancing (`lb://`). |
| **`user-service`** | `8083` | MySQL (`user_db`), Redis (`users`, blacklist) | Spring Security, JJWT 0.12, Spring Data JPA, Redis | Registration, BCrypt password hashing, access (15m) + refresh (7d) tokens, Redis blacklist logout, RBAC `@PreAuthorize`. |
| **`product-service`** | `8081` | MySQL (`product_db_m`), Redis (`products`, `productList`) | Spring Data JPA `JpaSpecificationExecutor`, Spring Cache, Redis | Product catalog, dynamic JPA Specification search (`name`, `category`, `price`, `stock`), Redis cache-aside, stock check & deduction. |
| **`order-service`** | `8082` | MySQL (`order_db_m`) | Spring Cloud OpenFeign, Resilience4j, Spring Data JPA | Order placement, OpenFeign pre-order stock validation & stock deduction, Resilience4j circuit breakers and retries, notification triggers. |
| **`payment-service`** | `8084` | MySQL (`payment_db`) | Spring Cloud OpenFeign, Resilience4j, Paytm HMAC-SHA256 | Paytm Payment Gateway sandbox integration, HMAC-SHA256 signature generation & verification, webhook idempotency deduplication, order confirmation. |
| **`notification-service`** | `8085` | MySQL (`notification_db`) | `spring-boot-starter-mail`, Spring Kafka, Spring Data JPA | Formats & dispatches HTML order receipts and payment confirmations via JavaMailSender, audit logging, Kafka consumer with standalone fallback. |

---

## 3. Core Architectural Patterns & Features

### 1. Database-Per-Service Pattern
Each business microservice owns its private MySQL schema (`user_db`, `product_db_m`, `order_db_m`, `payment_db`, `notification_db`). No service directly accesses another service's database, ensuring zero tight coupling and true domain independence.

### 2. Edge API Gateway & JWT Security
- All incoming client traffic enters through **Spring Cloud Gateway** (`:8000`).
- **Whitelisted Endpoints:**
  - `/api/auth/**` (registration, login, refresh)
  - `GET /api/products/**` (public browsing)
  - `/api/payments/verify` (Paytm webhook callback)
  - `/eureka/**`, `/actuator/**`
- **Secured Endpoints:** Intercepted by `JwtAuthenticationFilter`. Validates HMAC-SHA256 signature and expiration; rejects invalid tokens with `401 Unauthorized`.
- **Header Mutation:** Unpacks JWT claims and mutates downstream request headers:
  - `X-User-Id: <userId>`
  - `X-User-Role: <role>`
  - `X-User-Name: <username>`

### 3. User Service: Refresh Tokens, Blacklisting & RBAC
- **Token Dual Lifecycle:** Short-lived access tokens (15 minutes) and long-lived refresh tokens (7 days).
- **Redis Token Blacklisting:** On `POST /api/auth/logout`, the current access token is written to Redis with a TTL matching its remaining validity. Subsequent requests using blacklisted tokens are immediately rejected.
- **Role-Based Access Control (RBAC):** Users are assigned `ROLE_USER` or `ROLE_ADMIN`. Endpoints under `/api/admin/**` are strictly guarded by `@PreAuthorize("hasRole('ADMIN')")`.

### 4. Dynamic Filtering with Spring Data JPA Specifications
- `product-service` implements `ProductSpecification` extending `JpaSpecificationExecutor<Product>`.
- Supports dynamic query composition at runtime:
  - `name`: Case-insensitive `LIKE %name%`
  - `category`: Exact match
  - `minPrice` / `maxPrice`: Price range boundaries (`>=`, `<=`)
  - `inStock`: Filters records where `stockQuantity > 0`
- Available via `GET /api/products/search?name=laptop&category=Electronics&minPrice=500&inStock=true`.

### 5. OpenFeign Pre-Order Stock Validation & Deductions
- Before creating an order, `order-service` communicates with `product-service` via `@FeignClient(name = "PRODUCT-SERVICE")`:
  1. `checkStock(productId, quantity)`: Verifies inventory availability. If stock is insufficient, throws `IllegalArgumentException` and returns `HTTP 400 Bad Request ("Insufficient stock for product ID: X")`.
  2. `deductStock(productId, quantity)`: Atomically decrements inventory in MySQL and flushes Redis caches.

### 6. Resilience4j Fault Tolerance Suite
- Implemented in `order-service` and `payment-service` across critical inter-service calls:
  - `@CircuitBreaker(name = "productService", fallbackMethod = "productFallback")`: Trips to `OPEN` state when failure rate exceeds 50%, preventing cascading downtime.
  - `@Retry(name = "productService")`: Automatically retries transient network glitches up to 3 times with exponential backoff.
  - `@Bulkhead(name = "productService")`: Isolates concurrent threads to prevent thread pool exhaustion.
  - Actuator metrics exposed at `/actuator/circuitbreakers` and `/actuator/health`.

### 7. Paytm Payment Gateway Integration
- **Sandbox Integration:** Integrates with Paytm's payment APIs.
- **HMAC-SHA256 Checksum:** Cryptographic signature generation and verification via `PaytmChecksumUtil` using secret merchant keys.
- **Idempotency Deduplication:** Webhook callback deduplicates transactions against the database. If a transaction has already been recorded as `SUCCESS`, duplicate callbacks are returned idempotently without double-crediting or re-triggering events.
- **Order State Transition:** Upon valid payment, `payment-service` calls `order-service` via OpenFeign to update order status to `CONFIRMED`.

### 8. Notification Service: Java Mail & Kafka
- Dispatches styled HTML receipts for order confirmations and payment successes via `JavaMailSender`.
- Persists notification audit logs in `notification_db`.
- Supports Kafka event-driven consumers (`order-events`, `payment-events`) with `@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true")`, allowing standalone local JVM execution without requiring a running Kafka broker.

---

## 4. REST API Reference (via API Gateway :8000)

### Authentication & Users (`user-service`)
| Method | Endpoint | Auth Required | Description |
| :--- | :--- | :---: | :--- |
| `POST` | `/api/auth/register` | No | Register a new user (`ROLE_USER` by default) |
| `POST` | `/api/auth/login` | No | Validate credentials; returns access & refresh tokens |
| `POST` | `/api/auth/refresh` | No | Exchange refresh token for fresh access token |
| `POST` | `/api/auth/logout` | Yes (Bearer) | Blacklists access token in Redis and removes refresh token |
| `GET` | `/api/auth/validate` | Yes (Bearer) | Validates active JWT and returns identity |
| `GET` | `/api/users/me` | Yes (Bearer) | Returns authenticated user profile |
| `GET` | `/api/admin/users` | Yes (`ROLE_ADMIN`) | Admin-only endpoint listing all users |
| `DELETE` | `/api/admin/users/{id}` | Yes (`ROLE_ADMIN`) | Admin-only user deletion |

### Products (`product-service`)
| Method | Endpoint | Auth Required | Description |
| :--- | :--- | :---: | :--- |
| `GET` | `/api/products` | No | List all products (Cached in Redis: `productList`) |
| `GET` | `/api/products/{id}` | No | Get product by ID (Cached in Redis: `products::{id}`) |
| `GET` | `/api/products/search` | No | Dynamic filter search with JPA Specification |
| `GET` | `/api/products/{id}/check-stock` | Yes (Bearer) | Check if product has sufficient stock |
| `POST` | `/api/products/{id}/deduct-stock` | Yes (Bearer) | Deduct stock and evict Redis cache |
| `POST` | `/api/products` | Yes (Bearer) | Create product (Evicts Redis cache) |
| `PUT` | `/api/products/{id}` | Yes (Bearer) | Update product (Evicts Redis cache) |
| `DELETE` | `/api/products/{id}` | Yes (Bearer) | Delete product (Evicts Redis cache) |

### Orders (`order-service`)
| Method | Endpoint | Auth Required | Description |
| :--- | :--- | :---: | :--- |
| `POST` | `/api/orders/placeOrder` | Yes (Bearer) | Pre-order stock validation via Feign, deduction, and creation |
| `GET` | `/api/orders` | Yes (Bearer) | List orders for authenticated user |
| `GET` | `/api/orders/{orderId}` | Yes (Bearer) | Get order details by ID |
| `PUT` | `/api/orders/{orderId}/status` | Yes (Bearer) | Update order status (called by Payment Service) |

### Payments (`payment-service`)
| Method | Endpoint | Auth Required | Description |
| :--- | :--- | :---: | :--- |
| `POST` | `/api/payments/initiate` | Yes (Bearer) | Initiates Paytm payment and generates HMAC-SHA256 checksum |
| `POST` | `/api/payments/verify` | No (Paytm Webhook) | Verifies Paytm callback signature, enforces idempotency, confirms order |
| `POST` | `/api/payments/process` | Yes (Bearer) | Direct/simulated payment processing |
| `GET` | `/api/payments/order/{orderId}` | Yes (Bearer) | Fetch payment record by order ID |

### Notifications (`notification-service`)
| Method | Endpoint | Auth Required | Description |
| :--- | :--- | :---: | :--- |
| `POST` | `/api/notifications/order-confirmation` | Yes (Bearer) | Dispatches HTML order confirmation email |
| `POST` | `/api/notifications/payment-success` | Yes (Bearer) | Dispatches HTML payment receipt email |
| `GET` | `/api/notifications/history` | Yes (Bearer) | View notification audit history |

---

## 5. End-to-End Purchase Flow

```
1. Authentication:
   POST /api/auth/login ──► User Service validates BCrypt hash ──► Returns Access (15m) + Refresh (7d) Tokens

2. Product Discovery:
   GET /api/products/search?category=Electronics&minPrice=100&inStock=true
   └── Handled by Product Service via JPA Specification & Redis Cache

3. Order Placement:
   POST /api/orders/placeOrder (productId=1, quantity=2)
   ├── Gateway validates JWT, forwards X-User-Id header to Order Service
   ├── Order Service calls Product Service via OpenFeign (checkStock)
   │   └── If stock < quantity: Throws 400 Bad Request ("Insufficient stock")
   ├── Order Service calls Product Service via OpenFeign (deductStock)
   │   └── Decrements inventory in MySQL, evicts Redis cache
   └── Order is created in order_db_m with status = "PENDING"

4. Payment Initiation:
   POST /api/payments/initiate (orderId=101, amount=200.00)
   ├── Payment Service computes HMAC-SHA256 checksum with Paytm merchant key
   ├── Records payment in payment_db with status = "PENDING"
   └── Returns checksum, MID, and parameters for payment gateway redirection

5. Payment Verification & Confirmation (Paytm Webhook):
   POST /api/payments/verify (ORDERID=101, TXNID=..., STATUS=TXN_SUCCESS, CHECKSUMHASH=...)
   ├── Validates HMAC-SHA256 signature using PaytmChecksumUtil
   ├── Idempotency Check: Verifies payment hasn't already been processed
   ├── Updates payment status to "SUCCESS" in payment_db
   ├── OpenFeign Call: Invokes Order Service to update order status to "CONFIRMED"
   └── OpenFeign Call: Invokes Notification Service to dispatch HTML payment receipt
```

---

## 6. How to Run Locally

### Prerequisites
- **Java 17+**
- **MySQL** running on `localhost:3306` with credentials `root` / `root`
- **Redis** running on `localhost:6379`

### 1. Initialize MySQL Schemas
```sql
CREATE DATABASE IF NOT EXISTS user_db;
CREATE DATABASE IF NOT EXISTS product_db_m;
CREATE DATABASE IF NOT EXISTS order_db_m;
CREATE DATABASE IF NOT EXISTS payment_db;
CREATE DATABASE IF NOT EXISTS notification_db;
```

### 2. Start Services in Order
Open a terminal for each microservice and execute using the Maven wrapper:

```powershell
# 1. Service Discovery (Port 8761)
cd eureka-server; .\mvnw.cmd spring-boot:run

# 2. Product Service (Port 8081)
cd product-service; .\mvnw.cmd spring-boot:run

# 3. Order Service (Port 8082)
cd order-service; .\mvnw.cmd spring-boot:run

# 4. User Service (Port 8083)
cd user-service; .\mvnw.cmd spring-boot:run

# 5. Payment Service (Port 8084)
cd payment-service; .\mvnw.cmd spring-boot:run

# 6. Notification Service (Port 8085)
cd notification-service; .\mvnw.cmd spring-boot:run

# 7. API Gateway (Port 8000)
cd api-gateway-service; .\mvnw.cmd spring-boot:run
```

Once started:
- Eureka Registry Dashboard: [http://localhost:8761](http://localhost:8761)
- API Gateway: [http://localhost:8000](http://localhost:8000)
- Actuator Circuit Breaker Status: [http://localhost:8082/actuator/circuitbreakers](http://localhost:8082/actuator/circuitbreakers)
