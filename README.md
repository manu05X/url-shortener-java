# URL Shortener Service

A REST API service that shortens long URLs into short, shareable links. Built with Java 17 and Spring Boot.

## Architecture

```
┌──────────────────────────────────────────────┐
│              REST Controllers                │
│  (UrlShortener, Redirect, Metrics)           │
├──────────────────────────────────────────────┤
│              Service Layer                   │
│  (Shortening logic, domain extraction)       │
├──────────────────────────────────────────────┤
│              Repository Layer                │
│  (In-memory ConcurrentHashMap storage)       │
├──────────────────────────────────────────────┤
│              Model + DTOs                    │
│  (UrlMapping, Request/Response objects)      │
└──────────────────────────────────────────────┘
```

**Design decisions:**
- **Layered architecture** — Controller, Service, Repository are separated for testability and maintainability.
- **ConcurrentHashMap** — Thread-safe in-memory storage; two maps provide O(1) lookups in both directions (short code → URL and URL → short code).
- **SHA-256 hashing** — Deterministic short code generation ensures the same URL always produces the same short code.
- **Multi-stage Dockerfile** — Keeps the final image small by only including the JRE and the built JAR.

## Prerequisites

- Java 17+
- Maven 3.8+
- Docker (optional, for containerized deployment)

## Running Locally

```bash
# Build and run tests
mvn clean test

# Start the application
mvn spring-boot:run
```

The server starts at `http://localhost:8080`.

## Running with Docker

```bash
# Build the image
docker build -t url-shortener .

# Run the container
docker run -p 8080:8080 url-shortener
```

## API Endpoints

### 1. Shorten a URL

```
POST /api/shorten
Content-Type: application/json

{
  "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
}
```

**Response (201 Created):**
```json
{
  "originalUrl": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
  "shortUrl": "http://localhost:8080/eKBoQv"
}
```

Submitting the same URL again returns the same short URL.

### 2. Redirect

```
GET /{shortCode}
```

Returns **302 Found** with a `Location` header pointing to the original URL. The browser follows this redirect automatically.

### 3. Top Domains (Metrics)

```
GET /api/metrics/top-domains
```

**Response (200 OK):**
```json
[
  { "domain": "udemy.com", "count": 6 },
  { "domain": "youtube.com", "count": 4 },
  { "domain": "wikipedia.org", "count": 2 }
]
```

Returns the top 3 most-shortened domain names, sorted by count descending.

## Project Structure

```
src/main/java/com/urlshortener/
├── UrlShortenerApplication.java   # Entry point
├── controller/
│   ├── UrlShortenerController.java  # POST /api/shorten
│   ├── RedirectController.java      # GET /{shortCode}
│   ├── MetricsController.java       # GET /api/metrics/top-domains
│   └── GlobalExceptionHandler.java  # Centralized error handling
├── dto/
│   ├── ShortenRequest.java
│   ├── ShortenResponse.java
│   ├── DomainMetric.java
│   └── ErrorResponse.java
├── model/
│   └── UrlMapping.java              # Core domain object
├── repository/
│   └── UrlRepository.java           # In-memory storage
└── service/
    └── UrlShortenerService.java     # Business logic

src/test/java/com/urlshortener/
├── controller/
│   ├── UrlShortenerControllerTest.java  # Integration tests
│   ├── RedirectControllerTest.java
│   └── MetricsControllerTest.java
├── repository/
│   └── UrlRepositoryTest.java           # Unit tests
└── service/
    └── UrlShortenerServiceTest.java     # Unit tests
```

## Tests

31 tests covering:
- **Repository layer** — CRUD operations, edge cases (9 tests)
- **Service layer** — Shortening, deduplication, metrics, domain extraction (14 tests)
- **Controller layer** — HTTP status codes, JSON responses, redirect headers, validation (8 tests)

```bash
mvn test
```

---

## Interview Q&A

### 1. Architecture & Design Decisions

**Q1: Why did you choose a layered architecture (Controller → Service → Repository)? What are the alternatives?**

Each layer has a single responsibility: Controllers handle HTTP, Services contain business logic, and the Repository manages storage. This means I can swap the in-memory store for a database by only changing the Repository — the Service and Controller don't care. Alternatives include a hexagonal (ports & adapters) architecture or a simple single-class approach, but layered is the right balance of simplicity and separation for this scope.

**Q2: Why did you separate DTOs from the domain model? Why not just return `UrlMapping` directly from the API?**

DTOs decouple the API contract from the internal model. If I add fields to `UrlMapping` later (e.g., click count, expiry date), I don't want them leaking into the API response unless I choose to. It also lets the request and response have different shapes — `ShortenRequest` only has a `url` field, while `ShortenResponse` has both `originalUrl` and `shortUrl`.

**Q3: Why Spring Boot? Could you have done this without a framework?**

Spring Boot gives an embedded Tomcat server, dependency injection, validation, and test infrastructure out of the box. Without it, I'd need to manually set up an HTTP server, wire dependencies, parse JSON, and build a test harness — all boilerplate that doesn't add business value.

**Q4: How would you change the architecture if this needed to handle millions of requests per second?**

I'd add a distributed cache (Redis) as the primary store, put the service behind a load balancer with multiple instances, use a CDN for redirect responses, and potentially pre-generate short codes in batches to avoid hashing under load. I'd also consider a dedicated counter service (like Twitter's Snowflake) for generating unique IDs.

**Q5: Why did you put the redirect endpoint in a separate controller from the shorten endpoint?**

They serve different concerns: `UrlShortenerController` is an API controller under `/api/` that returns JSON, while `RedirectController` serves end-user browser requests at the root path and returns HTTP redirects. Separating them keeps each controller focused and makes routing intent clear.

**Q6: What is Dependency Injection and where are you using it?**

Dependency Injection means objects receive their dependencies from outside rather than creating them. In this project, Spring automatically creates `UrlRepository` and injects it into `UrlShortenerService` via the constructor, and injects the service into the controllers. This makes testing easy — I can pass a real or mock repository without changing the service code.

**Q7: What does `@RestController` vs `@Controller` mean?**

`@Controller` returns view names (for HTML templates). `@RestController` is `@Controller` + `@ResponseBody` — it automatically serializes return values to JSON. Since this is a REST API, `@RestController` is the right choice.

**Q8: Why do you have a `GlobalExceptionHandler`? What happens without it?**

Without it, Spring returns its default error page with a stack trace, which leaks internal details and looks inconsistent. The `GlobalExceptionHandler` catches validation errors and unexpected exceptions, wrapping them in a consistent `ErrorResponse` JSON format with proper HTTP status codes.

---

### 2. URL Shortening Algorithm

**Q9: How does your shortening algorithm work? Walk me through step by step.**

1. Check if the URL was already shortened (return existing code if yes).
2. Hash the URL using SHA-256 — this produces 32 bytes.
3. Convert each byte to a Base62 character (a-z, A-Z, 0-9) by taking `byte % 62`.
4. Take the first 6 characters as the short code.
5. If that code already exists for a *different* URL (collision), append `#1`, `#2`, etc. to the URL and re-hash until we get a unique code.

**Q10: Why SHA-256? Why not MD5, a random string, or a counter/sequence?**

- **SHA-256 over MD5**: MD5 has known collision vulnerabilities. SHA-256 is more collision-resistant.
- **Over random strings**: Random strings aren't deterministic — the same URL would get different codes each time, violating the idempotency requirement.
- **Over a counter/sequence**: Counters are predictable (users could guess other URLs) and require synchronization across multiple instances. Hashing is stateless and deterministic.

**Q11: What is Base62 encoding? Why 62 characters and not Base64?**

Base62 uses `a-z`, `A-Z`, `0-9` — all URL-safe characters. Base64 adds `+` and `/` which have special meaning in URLs and would need percent-encoding, making the short URL longer and uglier.

**Q12: How many unique short codes can you generate with 6 characters of Base62?**

62^6 = 56,800,235,584 — roughly 56.8 billion unique codes. That's more than enough for most use cases.

**Q13: What happens if two different URLs produce the same short code (collision)? How do you handle it?**

The `generateShortCode` method checks if the code already exists. If it does, it appends `#1` to the original URL and re-hashes, then `#2`, etc., until it finds an unused code. In practice, collisions are extremely rare with SHA-256 and 6 characters.

**Q14: Why is the short code length configurable via `application.properties`?**

It allows tuning the trade-off between URL shortness and collision probability without changing code. In development you might use 4 characters; in production with billions of URLs, you might use 8.

**Q15: Is your hashing approach deterministic? Why does that matter?**

Yes — SHA-256 always produces the same output for the same input. This is critical for the requirement "same URL returns same short code." Without determinism, we'd need to always check the database first, and we couldn't guarantee idempotency without a lookup.

**Q16: What's the time complexity of your shortening operation?**

O(1) amortized. The hash computation is constant time, and both `ConcurrentHashMap.get()` and `put()` are O(1). In the worst case with collisions, it's O(k) where k is the number of collisions, but k is practically always 0 or 1.

---

### 3. In-Memory Storage

**Q17: Why `ConcurrentHashMap` instead of `HashMap`?**

`HashMap` is not thread-safe. If two requests arrive simultaneously, they could corrupt the map's internal structure (e.g., infinite loops during resize in older Java versions, or lost updates). `ConcurrentHashMap` uses fine-grained locking (lock striping) so multiple threads can read/write safely and concurrently.

**Q18: What happens if two threads try to shorten the same URL at the exact same time? Is there a race condition?**

There's a minor race condition: both threads could pass the "check if exists" step before either writes. Both would generate the same short code (deterministic hashing) and both would write the same mapping, so the end result is correct — but we do redundant work. To fix this properly, I could use `ConcurrentHashMap.computeIfAbsent()` for atomic check-and-insert.

**Q19: Why do you use TWO maps instead of one?**

One map (`shortCode → UrlMapping`) supports redirect lookups. The other (`originalUrl → shortCode`) supports the "return same code for same URL" requirement. Without the second map, I'd need to scan all values to find if a URL was already shortened — O(n) instead of O(1). The trade-off is double the memory usage.

**Q20: What happens to all the data when the application restarts?**

Everything is lost — that's the nature of in-memory storage. To fix this, I'd persist to a database (PostgreSQL for durability, Redis for speed, or both). For a quick win, I could serialize the maps to a file on shutdown and reload on startup.

**Q21: How would you replace in-memory storage with a database? What would you change?**

Only the Repository layer. I'd create a `UrlMapping` JPA entity with `@Entity` and `@Table` annotations, replace `UrlRepository` with a Spring Data JPA interface extending `JpaRepository`, and add a `spring-boot-starter-data-jpa` dependency. The Service and Controller layers wouldn't change at all — that's the benefit of layered architecture.

**Q22: What database would you choose for a production URL shortener?**

Redis for the primary read path (redirect lookups need to be sub-millisecond), backed by PostgreSQL for durability and analytics queries. Redis handles the hot path; PostgreSQL is the source of truth.

**Q23: What is the memory limit of this approach?**

Each `UrlMapping` is roughly 200-500 bytes depending on URL length. With 1GB of heap, you could store roughly 2-5 million mappings. For more, you'd need to increase heap (`-Xmx`) or move to external storage.

---

### 4. REST API Design

**Q24: Why HTTP 201 (Created) for the shorten endpoint instead of 200 (OK)?**

201 semantically means "a new resource was created," which is exactly what happens when we create a new short URL mapping. It follows REST conventions — POST that creates a resource should return 201.

**Q25: Why HTTP 302 (Found) for redirect instead of 301 (Moved Permanently)?**

301 tells browsers and proxies to cache the redirect permanently. Once cached, the browser will never hit our server again for that short code — we lose the ability to track clicks, change the destination, or disable the link. 302 means "temporary redirect," so the browser always comes back to us.

**Q26: What happens if someone sends a POST with an empty body? How do you handle validation?**

The `@NotBlank` annotation on `ShortenRequest.url` combined with `@Valid` on the controller parameter triggers Spring's validation. An empty or null URL returns a 400 Bad Request with a clear error message via the `GlobalExceptionHandler`.

**Q27: Why do you validate that the URL has `http://` or `https://`?**

Without scheme validation, someone could submit `javascript:alert('xss')` or `ftp://malware.com` as a URL. We restrict to HTTP/HTTPS to prevent XSS attacks and ensure the redirect goes to a web page.

**Q28: What HTTP methods does each endpoint use and why?**

- `POST /api/shorten` — POST because we're creating a new resource (a URL mapping).
- `GET /{shortCode}` — GET because we're retrieving/accessing a resource (the original URL).
- `GET /api/metrics/top-domains` — GET because we're reading data without side effects.
This follows REST conventions: GET for reads, POST for creates.

**Q29: How would you add rate limiting to prevent abuse?**

I'd use Spring's `@RateLimiter` with Resilience4j, or a token bucket algorithm backed by Redis. Rate limit by IP address for anonymous users, or by API key for authenticated users. Return HTTP 429 (Too Many Requests) when the limit is exceeded.

**Q30: How would you add authentication? Should shortening require a login?**

I'd add Spring Security with JWT tokens. Shortening should require authentication (to track who created which links and enable abuse prevention), but redirects should be public (anyone with the short link should be able to use it).

**Q31: What would you add if you needed to support custom short codes (e.g., `localhost:8080/my-brand`)?**

Add an optional `customCode` field to `ShortenRequest`. In the service, check if the custom code is already taken (return 409 Conflict if so), validate it against allowed characters, and save it. Fall back to auto-generation if no custom code is provided.

---

### 5. Metrics API

**Q32: How does your top-3 domains calculation work? What's the time complexity?**

It streams all URL mappings, extracts the domain from each, groups them by domain with a count, sorts by count descending, and takes the top 3. Time complexity is O(n log n) where n is the number of stored URLs, due to the sort.

**Q33: Why do you strip `www.` from domains?**

`www.youtube.com` and `youtube.com` are the same site. Without stripping, they'd be counted separately, giving misleading metrics. The `www.` prefix is a legacy convention with no semantic difference.

**Q34: What happens if there are fewer than 3 domains?**

The `.limit(3)` on the stream handles this gracefully — if there are only 2 domains, it returns 2. If there are none, it returns an empty list. This is tested in `getTopDomainsShouldReturnFewerThan3WhenLessDomains`.

**Q35: How would you make metrics faster if there were millions of URLs?**

Instead of recalculating from scratch every time, maintain a running `ConcurrentHashMap<String, AtomicLong>` of domain counts. Increment it whenever a URL is shortened. The metrics endpoint then just sorts this pre-computed map — O(k log k) where k is the number of unique domains, instead of O(n) where n is total URLs.

**Q36: Could you use a heap/priority queue instead of sorting?**

Yes — a min-heap of size 3 would give O(n log 3) = O(n) instead of O(n log n). For each domain count, if it's larger than the heap's minimum, replace it. After processing all domains, the heap contains the top 3. This is more efficient when n is large and k (top-k) is small.

---

### 6. Testing

**Q37: What's the difference between your unit tests and integration tests?**

Unit tests (`UrlRepositoryTest`, `UrlShortenerServiceTest`) test a single class in isolation with real dependencies (no Spring context). Integration tests (`*ControllerTest`) use `@SpringBootTest` to boot the full application context and test the entire request/response flow through all layers using `MockMvc`.

**Q38: Why do you call `urlRepository.clear()` in `@BeforeEach`?**

Tests share the same Spring application context (for performance). Without clearing, URLs shortened in one test would still exist in the next test, making tests order-dependent and flaky. `clear()` ensures each test starts with a clean slate.

**Q39: What is `MockMvc`? Why use it instead of starting a real server?**

`MockMvc` simulates HTTP requests without starting a real Tomcat server. It's faster (no port binding, no network overhead), runs in the same JVM (easier debugging), and still tests the full Spring MVC pipeline (filters, serialization, validation, exception handling).

**Q40: How do you test that the redirect actually works?**

In `RedirectControllerTest`, I first shorten a URL via POST, extract the short code from the response, then send a GET request to `/{shortCode}`. I assert the response has HTTP 302 status and a `Location` header pointing to the original URL.

**Q41: Why did you add `.andExpect(status().isCreated())` in the metrics test helper?**

Without it, if the shorten endpoint fails during test setup, the test silently continues with no URLs stored. The metrics assertion would then fail with "expected 3 items but got 0" — a misleading error. The status check makes the test fail immediately at the actual point of failure.

**Q42: What's the test coverage? Are there any edge cases you didn't test?**

31 tests cover the happy paths, error cases (invalid URL, blank URL, non-existent short code), idempotency, and metrics edge cases. Missing coverage includes: concurrent access testing, extremely long URLs, URLs with unicode characters, and performance/load testing.

**Q43: How would you add load/performance testing?**

I'd use JMeter or Gatling to simulate thousands of concurrent users. Key metrics to measure: p50/p95/p99 latency for shorten and redirect, throughput (requests/second), and error rate under load. I'd also check for memory leaks by monitoring heap usage over time.

**Q44: What is `@SpringBootTest`? How is it different from a plain `@Test`?**

`@SpringBootTest` boots the entire Spring application context — it creates all beans, wires dependencies, and sets up the web layer. A plain `@Test` runs in isolation with no Spring context. `@SpringBootTest` is slower but tests real wiring; plain `@Test` is fast but requires manual setup or mocks.

---

### 7. Docker

**Q45: Why a multi-stage Dockerfile? What's the benefit?**

The build stage needs Maven + full JDK (~800MB). The runtime stage only needs the JRE (~200MB). Multi-stage builds discard the build tools, producing a final image that's 4x smaller. This means faster deployments, less storage, and a smaller attack surface.

**Q46: Why do you copy `pom.xml` and run `dependency:go-offline` before copying `src/`?**

Docker caches each layer. Dependencies change rarely, but source code changes frequently. By downloading dependencies in a separate layer (keyed on `pom.xml`), Docker reuses the cached dependency layer when only source code changes. This makes rebuilds go from minutes to seconds.

**Q47: What's the difference between `ENTRYPOINT` and `CMD`?**

`ENTRYPOINT` defines the executable that always runs. `CMD` provides default arguments that can be overridden. Using `ENTRYPOINT ["java", "-jar", "app.jar"]` means the container always runs our app. If I used `CMD` instead, someone could accidentally override it with `docker run url-shortener /bin/bash`.

**Q48: Why `eclipse-temurin` as the base image?**

Eclipse Temurin (formerly AdoptOpenJDK) is the most widely used, community-supported, TCK-certified OpenJDK distribution. It's free, well-maintained, and available for all platforms. Alternatives include Amazon Corretto, Azul Zulu, or Oracle's official images.

**Q49: How would you make the Docker image even smaller?**

Use `eclipse-temurin:17-jre-alpine` (~100MB instead of ~200MB) for an Alpine Linux base. For maximum reduction, use GraalVM Native Image to compile to a native binary (~50MB, sub-second startup) — though this requires more build configuration.

**Q50: How would you deploy this to production?**

I'd push the Docker image to a container registry (ECR/Docker Hub), deploy to Kubernetes with a Deployment (multiple replicas for availability), a Service for load balancing, and a HorizontalPodAutoscaler for auto-scaling. Add an Ingress for HTTPS termination and a PersistentVolumeClaim if using file-based storage.

---

### 8. Concurrency & Thread Safety

**Q51: Is your service thread-safe? What could go wrong under concurrent access?**

The Repository layer is thread-safe because `ConcurrentHashMap` handles concurrent reads and writes. However, the Service layer has a check-then-act pattern in `shortenUrl()` (check if URL exists, then save) that isn't atomic. Two threads could both pass the check and both save — but since the hash is deterministic, they'd save the same mapping, so the result is still correct.

**Q52: There's a potential race condition in `shortenUrl()` — can you spot it?**

Between `findShortCodeByOriginalUrl()` returning empty and `save()`, another thread could insert the same URL. Both threads would generate the same short code and both would call `save()`. Since `ConcurrentHashMap.put()` is atomic and both write the same key-value pair, the end state is correct — but we do redundant work. A proper fix would use `computeIfAbsent()` for atomic check-and-insert.

**Q53: What are virtual threads (Project Loom)? Would they help here?**

Virtual threads (Java 21+) are lightweight threads managed by the JVM instead of the OS. They help when you have many blocking I/O operations (database calls, HTTP requests). For this in-memory service, the benefit is minimal since `ConcurrentHashMap` operations are non-blocking. But if we added a database, virtual threads would let us handle thousands of concurrent requests without a large thread pool.

**Q54: How would you handle thousands of concurrent shortening requests?**

The current design already handles this well — `ConcurrentHashMap` supports high concurrency with lock striping. For higher scale, I'd add connection pooling (if using a database), use async/non-blocking I/O (Spring WebFlux), and horizontally scale with multiple instances behind a load balancer.

---

### 9. Production Readiness

**Q55: What's missing to make this production-ready?**

Persistent storage (database), HTTPS, authentication/authorization, rate limiting, input sanitization (prevent malicious URLs), monitoring/alerting (Spring Actuator + Prometheus + Grafana), structured logging, health checks, graceful shutdown, and URL expiration.

**Q56: How would you add logging? Where would you log?**

I'd use SLF4J (already included with Spring Boot) with structured JSON logging. Log at INFO level for every shorten and redirect request (for analytics), WARN for validation failures, and ERROR for unexpected exceptions. In production, ship logs to ELK stack or CloudWatch.

**Q57: How would you monitor this service?**

Add `spring-boot-starter-actuator` for health checks and metrics. Expose Prometheus metrics endpoint, scrape with Prometheus, and visualize with Grafana. Key metrics: request rate, latency percentiles, error rate, JVM heap usage, and total URLs stored.

**Q58: How would you handle the service going down?**

Run multiple replicas behind a load balancer. Add health check endpoints (`/actuator/health`) so the load balancer can detect and route around unhealthy instances. Use Kubernetes liveness and readiness probes for automatic restart and traffic management.

**Q59: What if two instances of this service are running behind a load balancer? Does in-memory storage still work?**

No — each instance has its own `ConcurrentHashMap`. A URL shortened on instance A wouldn't be found on instance B. You'd need shared storage (Redis or a database) that all instances can access. This is why in-memory storage is only suitable for single-instance deployments.

**Q60: How would you handle URL expiration (links that expire after 30 days)?**

Add a `createdAt` and `expiresAt` field to `UrlMapping`. In the redirect endpoint, check if the current time is past `expiresAt` and return 410 (Gone) if so. Run a scheduled task (`@Scheduled`) to periodically clean up expired entries. With a database, use TTL indexes (MongoDB) or scheduled deletion queries.

---

### 10. Java-Specific Questions

**Q61: Why are the fields in `UrlMapping` marked `final`?**

`final` makes the object immutable after construction — the URL and short code can never change. Immutable objects are inherently thread-safe (no synchronization needed), easier to reason about, and safe to use as hash map keys.

**Q62: Why did you override `equals()` and `hashCode()` in `UrlMapping`?**

Java's default `equals()` compares object references (memory addresses). We need value equality — two `UrlMapping` objects with the same URL and short code should be considered equal. `hashCode()` must be overridden whenever `equals()` is, per the Java contract, to ensure correct behavior in hash-based collections.

**Q63: What does `@Valid` do on the request body parameter?**

`@Valid` triggers JSR-380 Bean Validation. Spring inspects the deserialized object for constraint annotations (`@NotBlank`, `@Size`, `@Email`, etc.) and throws `MethodArgumentNotValidException` if any constraint is violated — before the controller method body executes.

**Q64: What's the difference between `Optional.ofNullable()` and `Optional.of()`?**

`Optional.of(value)` throws `NullPointerException` if value is null. `Optional.ofNullable(value)` returns `Optional.empty()` if value is null. Since `ConcurrentHashMap.get()` returns null for missing keys, I use `ofNullable()` to safely wrap the result.

**Q65: What does `& 0xFF` do in your hash function?**

Java bytes are signed (-128 to 127). `& 0xFF` converts to unsigned (0 to 255) by masking the sign bit. Without it, negative bytes would produce negative indices when doing `% 62`, causing `StringIndexOutOfBoundsException`.

**Q66: Why `URI.create()` instead of `new URL()`?**

`java.net.URL.equals()` performs DNS resolution to compare hosts — it's blocking, slow, and produces inconsistent results depending on network state. `URI` is purely syntactic (no network calls), making it safe and fast for parsing.

---

### 11. Tricky / Curveball Questions

**Q67: What if someone shortens a URL that is already short (e.g., `http://localhost:8080/abc123`)?**

The system would treat it as any other URL and create a short code for it — a "double-shortened" URL. On redirect, it would redirect to `localhost:8080/abc123`, which would then redirect again to the original URL. To prevent this, I could check if the URL's host matches our own domain and reject it.

**Q68: What if the original URL contains special characters or is extremely long (10,000 chars)?**

SHA-256 handles any input length and produces a fixed 32-byte hash, so the algorithm works fine. However, extremely long URLs consume more memory in the maps. I'd add a max URL length validation (e.g., 2048 characters, which is the practical browser limit) in the controller.

**Q69: Can your short codes ever contain offensive words? How would you prevent that?**

Yes — random-looking Base62 strings can accidentally spell words. To prevent this, maintain a blocklist of offensive strings and check generated codes against it. If a code matches, regenerate using the collision resolution mechanism (append `#1` and re-hash).

**Q70: What if someone uses this to shorten malicious/phishing URLs? How would you handle abuse?**

Add URL scanning using Google Safe Browsing API or VirusTotal before accepting a URL. Implement rate limiting per IP/user. Add a reporting mechanism so users can flag malicious short links. Maintain a blocklist of known malicious domains.

**Q71: How would you migrate from in-memory to a database without downtime?**

Use the Strangler Fig pattern: (1) Add a database-backed repository alongside the in-memory one. (2) Write to both (dual-write). (3) Read from in-memory first, fall back to database. (4) Backfill existing in-memory data to the database. (5) Switch reads to database-first. (6) Remove the in-memory store. Feature flags control each step.
