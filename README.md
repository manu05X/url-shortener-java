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
