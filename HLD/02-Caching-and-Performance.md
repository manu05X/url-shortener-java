# Topic 11: Caching Patterns

> Cache-aside loads lazily on miss; write-through keeps cache consistent; write-behind batches for speed; read-through simplifies app logic.

> **Interview Tip:** Explain your choice — "I'd use cache-aside with Redis for user profiles since we read often but write rarely, and a cache miss is acceptable on first load."

---

## The 4 Caching Patterns

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        CACHING PATTERNS                                   │
│                                                                          │
│  ┌──────────────────────────┐    ┌──────────────────────────┐           │
│  │  CACHE-ASIDE             │    │  WRITE-THROUGH            │           │
│  │  (Lazy Loading)          │    │                           │           │
│  │                          │    │  App ──write──▶ Cache     │           │
│  │  App ──1.check──▶ Cache  │    │                  │        │           │
│  │   │                │     │    │             2.write       │           │
│  │   │  2.miss        │     │    │                  ▼        │           │
│  │   ▼           3.fetch    │    │              Database     │           │
│  │  Database ─────────┘     │    │                           │           │
│  │        4.populate cache  │    │  Both updated             │           │
│  │                          │    │  synchronously            │           │
│  │  [+] Only requested data │    │                           │           │
│  │      cached              │    │  [+] Cache always         │           │
│  │  [-] Cache miss penalty  │    │      consistent           │           │
│  │                          │    │  [-] Write latency higher │           │
│  └──────────────────────────┘    └──────────────────────────┘           │
│                                                                          │
│  ┌──────────────────────────┐    ┌──────────────────────────┐           │
│  │  WRITE-BEHIND            │    │  READ-THROUGH             │           │
│  │  (Write-Back)            │    │                           │           │
│  │                          │    │  App ──1.read──▶ Cache    │           │
│  │  App ──1.write──▶ Cache  │    │                   │       │           │
│  │                   │      │    │              2.fetch      │           │
│  │         2.async batch    │    │                   ▼       │           │
│  │                   ▼      │    │               Database    │           │
│  │              Database    │    │                           │           │
│  │                          │    │  Cache handles DB         │           │
│  │  [+] Fast writes,        │    │  interaction              │           │
│  │      batching            │    │                           │           │
│  │  [-] Risk of data loss   │    │  [+] Simpler app logic   │           │
│  │                          │    │  [-] Cache library        │           │
│  │                          │    │      dependency           │           │
│  └──────────────────────────┘    └──────────────────────────┘           │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## When to Use Each Pattern

| Pattern | Best For | Tradeoff | Example |
|---------|---------|----------|---------|
| **Cache-Aside** | Read-heavy with infrequent writes. App controls cache lifecycle. | First request always misses (cold start). App must manage cache explicitly. | User profiles, product details, pairwise IDs |
| **Write-Through** | Data that must NEVER be stale in cache. Strong consistency required. | Write latency doubles (write to cache + DB synchronously). | Shopping cart, account balance |
| **Write-Behind** | Write-heavy with tolerance for brief inconsistency. Batching reduces DB load. | Data loss risk if cache crashes before DB flush. | Analytics counters, view counts, log aggregation |
| **Read-Through** | Simplify app logic — cache auto-populates on miss. App never talks to DB directly. | Cache library becomes a dependency. Complex cache-DB contract. | Guava LoadingCache, Spring @Cacheable |

---

## Caching Patterns In My CXP Projects — Real Examples

### The CXP Caching Architecture

Our platform uses **all 4 patterns** across different layers:

```
┌──────────────────────────────────────────────────────────────────────────┐
│              CXP PLATFORM — CACHING ARCHITECTURE                          │
│                                                                          │
│  LAYER 1: CDN (Akamai) — Edge-Cache-Tag + TTL                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Pattern: CACHE-ASIDE (pull-based)                                │  │
│  │  Event detail page: 60 min TTL (cache-maxage=60m)                │  │
│  │  Seat availability: 1 min TTL (cache-maxage=1m)                  │  │
│  │  Invalidation: Tag-based purge via Eventtia webhook              │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  LAYER 2: In-Memory (Caffeine/Guava) — App-level JVM cache             │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Pattern: READ-THROUGH (Guava LoadingCache) + REFRESH            │  │
│  │  Landing view: 5 min refreshAfterWrite                           │  │
│  │  City/translation cache: 60 day expireAfterWrite (Caffeine)     │  │
│  │  Internal events: 2h TTL + 15 min scheduled refresh              │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  LAYER 3: Redis (ElastiCache) — Distributed shared cache               │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Pattern: CACHE-ASIDE (pairwise IDs)                              │  │
│  │  Pattern: WRITE-BEHIND (idempotency success response)            │  │
│  │  Pairwise ID: 30 day TTL                                         │  │
│  │  Success response: 60 min TTL                                    │  │
│  │  Failure counter: 1 min TTL                                      │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  LAYER 4: Elasticsearch — Read-optimized query store                    │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Pattern: Materialized view (CQRS, not traditional cache)        │  │
│  │  Populated externally (ETL/streaming), not by the app            │  │
│  │  App is read-only client — no cache-miss fallback to Eventtia   │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: Redis Cache-Aside — Pairwise ID Lookup

**Service:** `cxp-event-registration`
**Pattern:** Classic **cache-aside (lazy loading)**
**TTL:** 30 days

```
┌──────────────────────────────────────────────────────────────────────┐
│  CACHE-ASIDE: Pairwise ID Lookup                                     │
│                                                                      │
│  Step 1: App checks Redis                                           │
│  Step 2: Cache HIT → return immediately                             │
│  Step 2: Cache MISS → call Partner API → populate Redis             │
│                                                                      │
│  ┌─────────┐    1. GET           ┌─────────┐                       │
│  │  cxp-   │──────────────────▶│  Redis   │                       │
│  │  event- │                    │          │                       │
│  │  reg    │◀── 2a. HIT ───────│  TTL:    │                       │
│  │         │    (return cached) │  30 days │                       │
│  │         │                    └─────────┘                       │
│  │         │                                                       │
│  │         │── 2b. MISS ──▶ Partner API ── 3. response ──┐       │
│  │         │                                              │       │
│  │         │◀────────── return value ────────────────────┘       │
│  │         │                                                       │
│  │         │── 4. SET with TTL ──▶ Redis (populated for next call)│
│  └─────────┘                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// EventRegistrationService.java — textbook cache-aside
public Mono<PairWiseIdDetails> getPairWiseIdFromCache(String upmId) {
    try {
        // Step 1: Check cache
        var pairwise = redisTemplate.opsForValue().get(upmId + PAIRWISE_KEY_SUFFIX);
        if (!Objects.isNull(pairwise)) {
            // Step 2a: Cache HIT — return immediately
            return Mono.just(objectMapper.convertValue(pairwise, PairWiseIdDetails.class));
        }
    } catch (Exception e) {
        log.error("Redis exception, defaulting to partner API", e);
    }
    // Step 2b: Cache MISS — fetch from source + populate cache
    return getPairWiseAndSetCache(upmId);
}

public Mono<PairWiseIdDetails> getPairWiseAndSetCache(String upmId) {
    return getPairWiseId(upmId)   // Step 3: Call Partner API
            .map(pairWiseIdDetails -> {
                try {
                    // Step 4: Populate cache with 30-day TTL
                    redisTemplate.opsForValue().set(
                        upmId + PAIRWISE_KEY_SUFFIX,
                        pairWiseIdDetails,
                        Duration.ofDays(30)
                    );
                } catch (Exception e) {
                    log.error("Redis exception", e);
                }
                return pairWiseIdDetails;
            });
}
```

**Why cache-aside here:**
- Pairwise IDs are read ~50x for every write (read-heavy).
- A cache miss on first load is acceptable (user waits for Partner API once, then cached for 30 days).
- The app controls exactly what's cached and when.
- If Redis is down, the app gracefully falls back to Partner API (catch block).

**Interview answer:**
> "For pairwise ID lookups, we use cache-aside with Redis. The app checks Redis first — on a hit, we return in <1ms. On a miss, we call the Partner API, return the result, and populate Redis with a 30-day TTL. This is textbook cache-aside: only requested data is cached, and the app explicitly manages the cache lifecycle. If Redis is down, the catch block falls back to the Partner API — degraded performance but no failure."

---

### Example 2: Redis Write-Behind — Registration Idempotency

**Service:** `cxp-event-registration`
**Pattern:** **Write-behind (write-back)** — write to source first, cache populated asynchronously
**TTL:** Success response = 60 min, Failure counter = 1 min

```
┌──────────────────────────────────────────────────────────────────────┐
│  WRITE-BEHIND: Registration Idempotency                              │
│                                                                      │
│  Step 1: User registers → app calls Eventtia (source of truth)      │
│  Step 2: Eventtia returns success                                   │
│  Step 3: App returns success to user immediately                    │
│  Step 4: CompletableFuture.runAsync() writes to Redis (background)  │
│                                                                      │
│  ┌─────────┐   1. register   ┌──────────┐                          │
│  │  User    │──────────────▶│  Eventtia │                          │
│  │          │◀── 2. success ─│  (source  │                          │
│  │          │                │  of truth)│                          │
│  └─────────┘                └──────────┘                          │
│       ▲                                                             │
│       │ 3. return success                                           │
│       │    (client unblocked)                                       │
│  ┌─────────┐                                                        │
│  │  App     │                                                        │
│  │          │── 4. async ──▶ Redis.SET(response, TTL=60min)         │
│  └─────────┘    (background,                                        │
│                  non-blocking)                                       │
│                                                                      │
│  NEXT REQUEST with same idempotencyKey:                             │
│  App checks Redis → HIT → returns cached success response          │
│  (never calls Eventtia again for this user+event combo)             │
│                                                                      │
│  WHY WRITE-BEHIND (not write-through):                              │
│  - Client response is NOT blocked by Redis write                    │
│  - If Redis write fails, client still got success                   │
│  - Worst case: next duplicate request calls Eventtia again          │
│    (Eventtia returns 422 "already registered" — safe)               │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// EventRegistrationService.java — async cache write (write-behind)
// After Eventtia returns success:
if (cacheBasedBotProtectionFlag) {
    // Non-blocking: cache populated in background thread
    CompletableFuture.runAsync(() ->
        registrationCacheService.addRegistrationRequestSuccessResponseToCache(
            idempotencyKey, eventRegistrationResponse
        )
    );
}
return eventRegistrationResponse;  // returned BEFORE cache write completes
```

```java
// RegistrationCacheService.java — the actual cache write
void addRegistrationRequestSuccessResponseToCache(
        String idempotencyKey, EventRegistrationResponse value) {
    redisTemplate.opsForValue().set(
        idempotencyKey + SUCCESS_RESPONSE_SUFFIX,
        GSON.toJson(value),
        Duration.ofMinutes(60)   // cached for 1 hour
    );
}
```

**Why write-behind here:**
- Registration latency is critical during sneaker launches — can't add Redis write to the critical path.
- `CompletableFuture.runAsync()` decouples the cache write from the user response.
- If the async write fails, the impact is minimal — next duplicate request just calls Eventtia again (which is idempotent).

**Interview answer:**
> "For registration idempotency, we use write-behind: the app calls Eventtia first (source of truth), returns success to the user immediately, then asynchronously writes the success response to Redis via `CompletableFuture.runAsync()`. The user's response is never blocked by the cache write. If the async Redis write fails, the worst case is the next duplicate request calls Eventtia again — Eventtia returns 422 'already registered', which is safe. We chose write-behind over write-through because registration latency during sneaker launches must be <100ms, and adding a synchronous Redis write would add 1-2ms to every response."

---

### Example 3: Caffeine Read-Through — Landing View Cache

**Service:** `expviewsnikeapp`
**Pattern:** **Read-through** via Guava `LoadingCache`
**TTL:** 5 minute refresh

```
┌──────────────────────────────────────────────────────────────────────┐
│  READ-THROUGH: Guava LoadingCache for Landing View                   │
│                                                                      │
│  App ──1.read──▶ LoadingCache ──2.miss──▶ getLandingViewData()      │
│   ▲                   │                         │                   │
│   │                   │ HIT                     │ (calls Eventtia + │
│   │                   ▼                         │  Elasticsearch)   │
│   └──── return cached LandingView               │                   │
│                                                  │                   │
│   LoadingCache handles everything:               ▼                   │
│   - On miss: calls load() automatically     Cache populated         │
│   - On hit: returns cached value            automatically           │
│   - After 5 min: background refresh                                 │
│                                                                      │
│  App code is SIMPLE — just cache.get(key):                          │
│  No explicit "check cache → miss → fetch → populate" logic.         │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// ExperienceLandingViewService.java — read-through via LoadingCache
private LoadingCache<String, LandingView> loadingCache =
    CacheBuilder.newBuilder()
        .refreshAfterWrite(5, TimeUnit.MINUTES)  // background refresh after 5 min
        .build(new CacheLoader<String, LandingView>() {
            @Override
            public LandingView load(String country) throws Exception {
                return getLandingViewData(country);
                // LoadingCache calls this automatically on miss
            }
        });
```

**Why read-through here:**
- Landing page data changes infrequently (event listings update a few times per day).
- `refreshAfterWrite` keeps data fresh in the background — users never see stale AND never wait for a cold miss.
- The app just calls `loadingCache.get("US")` — no cache management logic.

**Interview answer:**
> "For the event landing view, we use read-through via Guava's `LoadingCache`. The app just calls `cache.get(country)` — no manual cache-aside logic. On a miss, `LoadingCache` automatically calls our `load()` method to fetch from Eventtia + Elasticsearch. After 5 minutes, `refreshAfterWrite` triggers a background refresh so the next read gets fresh data without waiting. This simplifies the app code — no explicit check-miss-fetch-populate flow — while keeping landing pages responsive with <5 min staleness."

---

### Example 4: Akamai CDN — Cache-Aside at the Edge

**Service:** `cxp-events` → Akamai CDN
**Pattern:** **Cache-aside** (pull-based) with TTL + tag-based invalidation
**TTLs:** Event pages = 60 min, Seat availability = 1 min

```
┌──────────────────────────────────────────────────────────────────────┐
│  CDN CACHE-ASIDE: Akamai Edge Cache                                  │
│                                                                      │
│  User request → Akamai PoP                                          │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  1. Check edge cache for URL                                │    │
│  │     HIT → return cached response (sub-ms)                   │    │
│  │     MISS → forward to origin (cxp-events backend)           │    │
│  │                                                              │    │
│  │  2. Origin responds with:                                    │    │
│  │     Edge-Control: cache-maxage=60m                           │    │
│  │     Edge-Cache-Tag: edp_73067                                │    │
│  │                                                              │    │
│  │  3. Akamai caches response at edge for 60 minutes           │    │
│  │                                                              │    │
│  │  4. INVALIDATION (on event update):                          │    │
│  │     Eventtia webhook → PurgeCacheController                  │    │
│  │     → 3 async purge calls:                                   │    │
│  │       - purge tag "edp_73067"      (event detail page)       │    │
│  │       - purge tag "edp_seats_73067" (seat availability)      │    │
│  │       - purge tag "edp_rq_73067"    (registration questions) │    │
│  │                                                              │    │
│  │  5. Next request → cache MISS → fresh data from origin      │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  TTL STRATEGY:                                                      │
│  ┌───────────────────────┬────────────┬──────────────────────┐     │
│  │  Resource              │  TTL       │  Why                  │     │
│  ├───────────────────────┼────────────┼──────────────────────┤     │
│  │  Event detail page    │  60 min    │  Rarely changes;      │     │
│  │                       │            │  purge on update       │     │
│  │  Seat availability    │  1 min     │  Changes frequently;  │     │
│  │                       │            │  short TTL = fresher   │     │
│  │  Landing page (ELP)   │  60 min    │  Rarely changes       │     │
│  │  Registration Qs      │  60 min    │  Rarely changes       │     │
│  │  Downstream (browser) │  5 min     │  downstream-ttl=5m    │     │
│  └───────────────────────┴────────────┴──────────────────────┘     │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — response headers that control caching:**

```java
// AkamaiCacheHeaderBuilder.java — sets both edge cache and browser cache
httpHeaders.add(HTTP_HEADER_EDGE_CONTROL,
    "!no-store,downstream-ttl=" + DOWNSTREAM_TTL      // browser: 5 min
    + ",!bypass-cache, cache-maxage=" + cacheTimeout); // edge: 60 min
httpHeaders.add(HTTP_HEADER_EDGE_CACHE_TAG, tag);      // tag for purging
```

**From the actual code — tag-based purge when Eventtia triggers an update:**

```java
// AkamaiCacheService.java — purge 3 tags per event update
public boolean purgeCache(EventtiaEventTriggerRequest request, Map<String, String> context) {
    String eventId = request.getEventtiaEventTriggerData().getId();
    CompletableFuture.runAsync(() -> apiCachePurging(eventId, "edp_" + eventId));
    CompletableFuture.runAsync(() -> apiCachePurging(eventId, "edp_seats_" + eventId));
    CompletableFuture.runAsync(() -> apiCachePurging(eventId, "edp_rq_" + eventId));
    return true;
}
```

**From the registration service — seat cache purge on capacity error:**

```java
// EventRegistrationService.java — invalidate seat cache when event is full
if (EventtiaErrorCodeDeterminer.requiresCacheInvalidation(specificErrorCode)) {
    log.warn("Capacity limit reached (errorCode={}), purging cache, eventId={}", specificErrorCode, eventId);
    CompletableFuture.runAsync(() -> akamaiCacheService.seatsAPICachePurging(eventId));
}
```

**Interview answer:**
> "Our CDN caching uses cache-aside with two-tier TTLs and tag-based invalidation. Event pages cache at the edge for 60 minutes — acceptable because events rarely change. Seat availability caches for only 1 minute since it changes with every registration. When Eventtia sends an update webhook, our PurgeCacheController fires 3 async tag-based purges — clearing the event page, seat data, and registration questions at all 250+ CDN PoPs simultaneously. For the seats API specifically, we also purge from the registration service when Eventtia returns a capacity error — a reactive invalidation that keeps the seat count accurate within seconds."

---

### Example 5: Caffeine — Scheduled Refresh (Internal Events)

**Service:** `cxp-events`
**Pattern:** **Scheduled refresh** (invalidate-all + repopulate on a timer)
**TTL:** 2 hours, with 15-minute refresh interval

```
┌──────────────────────────────────────────────────────────────────────┐
│  SCHEDULED REFRESH: Internal Events Cache                            │
│                                                                      │
│  This is NOT cache-aside (no lazy loading on miss).                 │
│  This is NOT read-through (no CacheLoader).                         │
│  This is a PROACTIVE REFRESH pattern:                               │
│                                                                      │
│  Every 15 minutes (@Scheduled):                                     │
│  1. Fetch ALL internal events from Eventtia API                     │
│  2. invalidateAll() — wipe the cache                                │
│  3. Put each event into cache                                       │
│                                                                      │
│  ┌──────────┐    @Scheduled(15min)    ┌──────────┐                 │
│  │ Caffeine │◀── full rebuild ───────│ Eventtia │                 │
│  │ Cache    │    invalidateAll()      │ API      │                 │
│  │ (500 max)│    + put each event     │          │                 │
│  └──────────┘                         └──────────┘                 │
│       ▲                                                             │
│       │ read (always HIT after first refresh)                      │
│       │                                                             │
│  ┌──────────┐                                                       │
│  │  App     │  Never misses (pre-populated).                       │
│  │  Request │  Never stale for > 15 minutes.                       │
│  └──────────┘                                                       │
│                                                                      │
│  WHEN TO USE THIS PATTERN:                                          │
│  - Dataset is small enough to fit entirely in memory (500 events)   │
│  - ALL items accessed roughly equally (no hot/cold split)           │
│  - You want ZERO cache misses (every read is a HIT)                │
│  - Acceptable staleness = refresh interval (15 minutes)             │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// InternalEventsCacheService.java
this.internalEventCache = Caffeine.newBuilder()
    .expireAfterWrite(Duration.ofHours(2))
    .maximumSize(500)
    .build();

@Scheduled(fixedRate = 900000)  // 15 minutes
public void refreshInternalEventsCache() {
    eventtiaEventsLandingPageDetailsApi.getEventLandingPageDetails(...)
        .subscribe(eventtiaPage -> {
            List<Event> events = mapper.mapWithoutShowOnLandingPage(eventtiaPage);
            internalEventCache.invalidateAll();  // wipe everything
            for (Event event : events) {
                internalEventCache.put(event.getId(), event);  // rebuild
            }
        });
}
```

---

## Summary: All 5 Cache Layers in CXP

| Layer | Technology | Pattern | TTL | Staleness | Use Case |
|-------|-----------|---------|-----|-----------|----------|
| **L1: CDN** | Akamai (250+ PoPs) | Cache-aside (pull) + tag purge | 1-60 min | Minutes (TTL-bounded) | Event pages, seat counts for millions of users |
| **L2: JVM** | Caffeine / Guava | Read-through + scheduled refresh | 5 min - 60 days | Seconds to minutes | Translations, city data, landing views, internal events |
| **L3: Distributed** | Redis (ElastiCache) | Cache-aside + write-behind | 1 min - 30 days | Sub-millisecond (replication lag) | Pairwise IDs, idempotency, registration responses |
| **L4: Search** | Elasticsearch | CQRS materialized view | N/A (async sync) | 1-5 seconds | Event search and discovery |
| **L5: Object** | S3 | Immutable store (no caching needed) | N/A | Strongly consistent | Webhook audit trail (source of truth) |

---

## Cache Invalidation — "The Two Hard Problems"

> "There are only two hard things in Computer Science: cache invalidation and naming things." — Phil Karlton

Our platform uses **4 different invalidation strategies**:

```
┌──────────────────────────────────────────────────────────────────────┐
│  INVALIDATION STRATEGIES IN CXP                                      │
│                                                                      │
│  1. TTL EXPIRY (time-based)                                         │
│     Redis: key auto-expires after TTL                               │
│     Caffeine: expireAfterWrite(60 days)                             │
│     CDN: cache-maxage=60m                                           │
│     → Simple, predictable, no coordination needed                   │
│     → Staleness = up to TTL duration                                │
│                                                                      │
│  2. TAG-BASED PURGE (event-driven)                                  │
│     Akamai: purge by Edge-Cache-Tag on Eventtia webhook             │
│     → Targeted: only purge the updated event's cache                │
│     → Fast: 250+ PoPs purged in seconds                             │
│     → Requires webhook integration (Eventtia → PurgeCacheController)│
│                                                                      │
│  3. REACTIVE INVALIDATION (on error)                                │
│     Registration service: Eventtia returns 422 ACTIVITY_FULL        │
│     → seatsAPICachePurging(eventId) fires async                     │
│     → Akamai cache for that event's seats cleared                   │
│     → Next user sees accurate seat count                            │
│                                                                      │
│  4. FULL REBUILD (scheduled)                                        │
│     InternalEventsCacheService: invalidateAll() + re-populate       │
│     → Wipe and rebuild every 15 minutes                             │
│     → Simple but only works for small, bounded datasets             │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Common Interview Follow-ups

### Q: "Why cache-aside over write-through for pairwise IDs?"

> "Pairwise IDs are owned by an external Partner API — we don't control writes to the source. Write-through requires intercepting every write to the source and mirroring it to cache. Since the Partner API writes independently, we can't hook into their write path. Cache-aside is the natural fit: we check Redis on read, and if it's a miss, we fetch from the source and populate. The 30-day TTL means most reads are cache hits after the first call."

### Q: "What if Redis goes down? How does your system handle it?"

> "Every Redis operation is wrapped in try-catch with graceful fallback:
> - **Pairwise cache miss:** Falls back to Partner API (slower but functional).
> - **Idempotency cache miss:** Registration proceeds to Eventtia. Duplicate risk slightly increases, but Eventtia's own duplicate check (422 response) catches it.
> - **Success response cache miss:** Next request calls Eventtia again — Eventtia returns 'already registered'.
> 
> Redis is a performance optimization, not a correctness requirement. The system is designed to work without it — just slower."

### Q: "How do you prevent thundering herd on cache expiry?"

> "Three strategies in our platform:
> 1. **Staggered TTLs:** Different cache layers expire at different times (CDN: 60 min, JVM: 5 min, Redis: 30 days) — they don't all expire simultaneously.
> 2. **Background refresh:** Guava `refreshAfterWrite` refreshes in the background before expiry — the stale value is served while fresh data loads.
> 3. **Tag-based purge vs TTL expiry:** CDN cache is invalidated by tag-based purge (one event at a time), not by global TTL expiry (all events at once). A purge affects one event's data, not the entire cache."

### Q: "When would you use write-through instead of write-behind?"

> "When data loss from a cache crash is unacceptable. Our registration idempotency uses write-behind because losing the cached success response only means the next duplicate request hits Eventtia again — safe because Eventtia is idempotent. If we were caching payment confirmations, I'd use write-through — losing a payment confirmation could cause a double charge. Write-through adds ~1-2ms latency but guarantees the cache and source are always in sync."

### Q: "How do you decide TTL values?"

> "Based on two factors: how often the data changes, and how bad stale data is.
>
> | Data | Change Frequency | Stale Impact | TTL |
> |------|-----------------|-------------|-----|
> | Seat count | Every registration | User tries to register for full event | **1 min** + reactive purge |
> | Event details | Few times/day | Wrong date/location shown | **60 min** + tag purge |
> | Pairwise ID | Never (immutable) | N/A | **30 days** |
> | City/translations | Rarely | Minor UI issue | **60 days** |
> | Idempotency counter | Per-request | Duplicate allowed through | **1 min** |
>
> Shortest TTLs for data where staleness = user-facing errors. Longest TTLs for immutable or rarely-changing reference data."

---
---

# Topic 12: Cache Eviction Policies

> LRU evicts least recently used (good for recency), LFU evicts least frequently used (good for popularity), TTL guarantees freshness.

> **Interview Tip:** Match policy to access pattern — "For session data I'd use LRU since recent sessions matter most; for CDN assets I'd use LFU to keep popular files cached."

---

## Why Eviction Matters

Cache memory is **finite**. When it's full and a new item needs to be stored, the cache must decide **which existing item to remove**. The eviction policy determines this — and the wrong policy tanks your cache hit rate.

```
┌──────────────────────────────────────────────────────────────────────┐
│  THE EVICTION PROBLEM                                                │
│                                                                      │
│  Cache capacity: 1000 items                                         │
│  Cache is FULL (1000/1000)                                          │
│  New item arrives → must evict ONE existing item                    │
│                                                                      │
│  GOOD eviction: Remove the item least likely to be needed again     │
│                 → Next requests HIT cache → fast                    │
│                                                                      │
│  BAD eviction:  Remove an item that's about to be requested         │
│                 → Next request MISSES cache → slow (fetch from DB)  │
│                                                                      │
│  Goal: MAXIMIZE cache hit rate with limited memory.                 │
└──────────────────────────────────────────────────────────────────────┘
```

---

## The 4 Eviction Policies

```
┌──────────────────────────────────────────────────────────────────────────┐
│                     CACHE EVICTION POLICIES                               │
│                                                                          │
│  When cache is full, which item should be removed?                      │
│                                                                          │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐    │
│  │  LRU — Least Recently Used   │  │  LFU — Least Frequently Used │    │
│  │                               │  │                               │    │
│  │  Evict item not accessed      │  │  Evict item with lowest       │    │
│  │  for the longest time.        │  │  access count.                │    │
│  │                               │  │                               │    │
│  │  ┌───┐┌───┐┌───┐┌───┐┌───┐  │  │  ┌────┐┌────┐┌────┐┌────┐   │    │
│  │  │ A ││ B ││ C ││ D ││ E │  │  │  │ A  ││ B  ││ C  ││ D  │   │    │
│  │  │new││   ││   ││   ││old│  │  │  │100x││50x ││10x ││ 2x │   │    │
│  │  └───┘└───┘└───┘└───┘└─┬─┘  │  │  └────┘└────┘└────┘└──┬─┘   │    │
│  │                     EVICT    │  │                      EVICT    │    │
│  │                               │  │                               │    │
│  │  [+] Simple, good for         │  │  [+] Keeps popular items      │    │
│  │      recency patterns         │  │      longer                   │    │
│  │  Best for: Session data,      │  │  Best for: CDN, static        │    │
│  │  user preferences             │  │  assets                       │    │
│  └──────────────────────────────┘  └──────────────────────────────┘    │
│                                                                          │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐    │
│  │  FIFO — First In, First Out  │  │  TTL — Time To Live          │    │
│  │                               │  │                               │    │
│  │  Evict oldest item            │  │  Evict when expiration time   │    │
│  │  regardless of usage.         │  │  is reached.                  │    │
│  │                               │  │                               │    │
│  │  ┌───┐┌───┐┌───┐┌─────┐     │  │  ┌─────┐┌─────┐┌─────┐      │    │
│  │  │ D ││ C ││ B ││  A  │     │  │  │  A  ││  B  ││  C  │      │    │
│  │  │new││   ││   ││oldest│     │  │  │60s  ││30s  ││ 0s  │      │    │
│  │  └───┘└───┘└───┘└──┬──┘     │  │  └─────┘└─────┘└──┬──┘      │    │
│  │                  EVICT       │  │                 EXPIRED       │    │
│  │                               │  │                               │    │
│  │  [+] Simplest to implement    │  │  [+] Guarantees freshness     │    │
│  │  Best for: Simple queues,     │  │  Best for: Auth tokens,       │    │
│  │  buffers                      │  │  API responses                │    │
│  └──────────────────────────────┘  └──────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## How Each Policy Works

### LRU — Least Recently Used

```
Cache capacity: 4 items

Operations:
  GET A   →  Cache: [A]
  GET B   →  Cache: [B, A]
  GET C   →  Cache: [C, B, A]
  GET D   →  Cache: [D, C, B, A]     ← full
  GET A   →  Cache: [A, D, C, B]     ← A moves to front (recently used)
  GET E   →  Cache: [E, A, D, C]     ← B evicted (least recently used)
                                 ▲
                                 │
                          B was accessed longest ago → evicted

Implementation: Doubly-linked list + HashMap → O(1) get/put/evict
Redis default: allkeys-lru (approximated LRU using random sampling)
```

### LFU — Least Frequently Used

```
Cache capacity: 4 items

Operations:
  GET A ×100  →  A: freq=100
  GET B ×50   →  B: freq=50
  GET C ×10   →  C: freq=10
  GET D ×2    →  D: freq=2       ← full
  GET E       →  D evicted (freq=2, lowest frequency)
                 Cache: [A(100), B(50), C(10), E(1)]

Problem: "frequency pollution"
  Old item accessed 1000x last month, 0x this month → still cached.
  Solution: Decay/aging — reduce frequencies over time.

Caffeine uses W-TinyLFU: a modern LFU variant that combines
recency (window) + frequency (TinyLFU) for best hit rate.
```

### FIFO — First In, First Out

```
Cache capacity: 4 items

Operations:
  PUT A  →  Cache: [A]
  PUT B  →  Cache: [A, B]
  PUT C  →  Cache: [A, B, C]
  PUT D  →  Cache: [A, B, C, D]     ← full
  PUT E  →  Cache: [B, C, D, E]     ← A evicted (first in)
                     ▲
                     A was added first → evicted first
                     Even if A is the most popular item!

Simple but often suboptimal. Used when insertion order = relevance
(logs, event streams, message queues).
```

### TTL — Time To Live

```
Item stored with expiration timestamp:

  T=0s:   SET "user:event" value TTL=60s   → expires at T=60s
  T=30s:  GET "user:event" → HIT (30s remaining)
  T=60s:  GET "user:event" → MISS (expired, evicted)

TTL doesn't care about access frequency or recency.
Item is evicted at the EXACT time regardless of how popular it is.

Use when: Data has a known freshness window.
  Auth tokens: TTL = token expiry
  Seat counts: TTL = 1 minute (changes frequently)
  Pairwise IDs: TTL = 30 days (rarely changes)
```

---

## Policy Combinations (Real Systems Use Multiple)

Most production systems **combine** policies — TTL for freshness PLUS LRU/LFU for capacity management:

```
┌──────────────────────────────────────────────────────────────────────┐
│  COMBINED POLICIES                                                    │
│                                                                      │
│  An item is evicted when EITHER condition is met:                   │
│                                                                      │
│  1. TTL expires       → guaranteed freshness                        │
│  2. Cache is full     → LRU/LFU picks which item to remove         │
│                                                                      │
│  Example: Redis with maxmemory-policy = allkeys-lru + TTL per key   │
│                                                                      │
│  Key "user:event" TTL=60min                                         │
│  → If accessed within 60min: stays (TTL not expired)                │
│  → If cache hits maxmemory: LRU may evict it early                 │
│  → If 60min passes: TTL evicts regardless of LRU position          │
│                                                                      │
│  TTL = FLOOR on freshness (item never older than TTL)               │
│  LRU = CEILING on memory (cache never exceeds maxmemory)            │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Eviction Policies In My CXP Projects — Real Examples

### The CXP Eviction Map

```
┌──────────────────────────────────────────────────────────────────────────┐
│              CXP PLATFORM — EVICTION POLICY MAP                           │
│                                                                          │
│  ┌──────────────┐  Policy: TTL (primary) + LRU (maxmemory fallback)     │
│  │  Redis        │  Failure counter:  TTL = 1 min                        │
│  │  ElastiCache  │  Success response: TTL = 60 min                       │
│  │               │  Pairwise ID:      TTL = 30 days                      │
│  │               │  maxmemory-policy: allkeys-lru (when memory full)    │
│  └──────────────┘                                                        │
│                                                                          │
│  ┌──────────────┐  Policy: W-TinyLFU (Caffeine default) + TTL           │
│  │  Caffeine     │  City cache:        TTL = 60 days,  maxSize varies   │
│  │  (JVM)        │  Translation cache: TTL = 60 days                     │
│  │               │  Internal events:   TTL = 2 hours,  maxSize = 500    │
│  │               │  Caffeine auto-uses W-TinyLFU for size eviction      │
│  └──────────────┘                                                        │
│                                                                          │
│  ┌──────────────┐  Policy: TTL (refreshAfterWrite) + LRU (maxSize)      │
│  │  Guava        │  Landing view:  refresh = 5 min                       │
│  │  LoadingCache │  Response cache: access = 5 min, write = 15 min      │
│  │               │  Store views:    write = 24 hours, maxSize = 8000    │
│  └──────────────┘                                                        │
│                                                                          │
│  ┌──────────────┐  Policy: TTL (cache-maxage) per resource type         │
│  │  Akamai CDN   │  Event pages:   TTL = 60 min                         │
│  │               │  Seat counts:   TTL = 1 min                           │
│  │               │  + Tag purge:   reactive invalidation on update       │
│  │               │  + LRU:         CDN evicts least popular URLs when   │
│  │               │                 edge disk is full                     │
│  └──────────────┘                                                        │
│                                                                          │
│  ┌──────────────┐  Policy: Time-based bucket tiering                    │
│  │  Splunk       │  Hot (today):   SSD, fast access                      │
│  │               │  Warm (7d):     SSD → HDD migration                   │
│  │               │  Cold (30d):    HDD, slower                           │
│  │               │  Frozen (90d+): S3, cheapest                          │
│  │               │  This is FIFO-like: oldest buckets roll to cold tier  │
│  └──────────────┘                                                        │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: Redis — TTL + LRU Combined

**Service:** `cxp-event-registration`
**Primary policy:** TTL per key (explicit expiry)
**Fallback policy:** `allkeys-lru` when ElastiCache hits `maxmemory`

```
┌──────────────────────────────────────────────────────────────────────┐
│  Redis TTL + LRU in Registration Service                             │
│                                                                      │
│  NORMAL OPERATION (cache not full):                                 │
│  TTL alone decides eviction.                                        │
│                                                                      │
│  Key: "user123_event456_failure_counter"                            │
│  SET at T=0, TTL=1min → EXPIRES at T=60s                           │
│  Purpose: prevent rapid-fire duplicate registrations.               │
│  After 1 min: user CAN retry (counter expired).                    │
│                                                                      │
│  Key: "user123_event456_success_response"                           │
│  SET at T=0, TTL=60min → EXPIRES at T=3600s                        │
│  Purpose: return cached success on duplicate request.               │
│  After 60 min: next request goes to Eventtia again.                │
│                                                                      │
│  Key: "uuid-5678_pairwise_key"                                     │
│  SET at T=0, TTL=30days → EXPIRES at T=2,592,000s                  │
│  Purpose: avoid calling Partner API on every request.               │
│  After 30 days: next request refreshes from Partner API.            │
│                                                                      │
│  UNDER MEMORY PRESSURE (cache full):                                │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ElastiCache maxmemory-policy = allkeys-lru               │    │
│  │                                                            │    │
│  │  When maxmemory is reached:                                │    │
│  │  Redis evicts the LEAST RECENTLY USED key — regardless    │    │
│  │  of remaining TTL.                                         │    │
│  │                                                            │    │
│  │  A pairwise key with 29 days remaining TTL                │    │
│  │  COULD be evicted if it hasn't been accessed recently     │    │
│  │  and memory is full.                                       │    │
│  │                                                            │    │
│  │  This is fine: our code handles Redis misses gracefully   │    │
│  │  (falls back to Partner API or Eventtia).                 │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  WHY TTL + LRU (not TTL + LFU):                                    │
│  Our keys are per-user — each key is accessed 1-5 times then       │
│  expires. There's no "popular" key with 1000x accesses.            │
│  LFU would give no advantage over LRU for this access pattern.    │
│  LRU is simpler and Redis's default approximation is efficient.    │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — TTL set on every write:**

```java
// RegistrationCacheService.java — TTL is the PRIMARY eviction mechanism

// 1 minute TTL — short-lived bot protection counter
redisTemplate.opsForValue().set(
    idempotencyKey + FAILURE_COUNTER_SUFFIX,
    value,
    Duration.ofMinutes(1)         // evicted after 1 minute
);

// 60 minute TTL — cached success response for idempotency
redisTemplate.opsForValue().set(
    idempotencyKey + SUCCESS_RESPONSE_SUFFIX,
    GSON.toJson(value),
    Duration.ofMinutes(60)        // evicted after 1 hour
);

// 30 day TTL — long-lived pairwise ID cache
redisTemplate.opsForValue().set(
    upmId + PAIRWISE_KEY_SUFFIX,
    pairWiseIdDetails,
    Duration.ofDays(30)           // evicted after 30 days
);
```

**Interview answer:**
> "Our Redis cache uses TTL as the primary eviction policy — each key type has a TTL matched to its freshness requirement: 1 minute for idempotency counters (short-lived protection), 60 minutes for success responses (longer-lived deduplication), and 30 days for pairwise IDs (rarely change). Under memory pressure, ElastiCache falls back to `allkeys-lru` — evicting the least recently accessed key regardless of remaining TTL. This is safe because every Redis consumer gracefully handles misses with a fallback to the source API."

---

### Example 2: Caffeine — W-TinyLFU (Best-in-Class Eviction)

**Service:** `cxp-events`
**Policy:** Caffeine's default **W-TinyLFU** (Window Tiny Least Frequently Used)

```
┌──────────────────────────────────────────────────────────────────────┐
│  Caffeine W-TinyLFU — How It Works                                   │
│                                                                      │
│  W-TinyLFU combines the best of LRU and LFU:                       │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │           WINDOW              MAIN CACHE                     │  │
│  │         (small LRU)        (Segmented LRU)                   │  │
│  │                                                              │  │
│  │  New items enter ──▶ Window ──admission──▶ Main Cache       │  │
│  │  the window first.    (1%)  filter (TinyLFU)  (99%)         │  │
│  │                                                              │  │
│  │  The TinyLFU filter compares:                               │  │
│  │  "Is the NEW item more valuable than the item it would      │  │
│  │   evict from Main Cache?"                                    │  │
│  │                                                              │  │
│  │  If new item has HIGHER frequency → admitted to Main         │  │
│  │  If new item has LOWER frequency  → evicted from Window     │  │
│  │                                                              │  │
│  │  Result: Main Cache keeps the most FREQUENTLY + RECENTLY    │  │
│  │  accessed items. Beats both pure LRU and pure LFU.          │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  WHY CAFFEINE FOR CXP:                                              │
│  - City cache (200 cities, some accessed 100x/day, others 1x/week) │
│  - W-TinyLFU keeps NYC, LA, London cached (high freq)              │
│  - Evicts obscure cities accessed once (low freq)                   │
│  - Pure LRU would evict NYC if it happened to be accessed           │
│    1 second before an obscure city. W-TinyLFU doesn't.             │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// AppConfig.java — Caffeine with TTL (W-TinyLFU is the default size policy)
Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
    .expireAfterWrite(60, TimeUnit.DAYS);  // TTL: 60 days
CaffeineCacheManager cacheManager = new CaffeineCacheManager();
cacheManager.setCacheNames(Arrays.asList(
    "cityCache",            // ~200 cities, access skewed to top 20
    "customFieldCache",     // event custom fields
    "cxpTranslationCache",  // UI translations by locale
    "allowedCountryCache"   // ~30 countries
));

// Internal events — explicit maxSize triggers W-TinyLFU eviction
this.internalEventCache = Caffeine.newBuilder()
    .expireAfterWrite(Duration.ofHours(2))
    .maximumSize(500)           // when >500 events, W-TinyLFU picks victim
    .recordStats()              // hit/miss tracking for monitoring
    .build();
```

**Interview answer:**
> "Our Caffeine caches use W-TinyLFU — a modern eviction policy that combines frequency AND recency. For our city cache with ~200 entries, popular cities like NYC and London stay cached because they're accessed frequently, while obscure cities are evicted on capacity pressure. Pure LRU would evict NYC if it happened to be accessed 1 second before a rare city. W-TinyLFU considers frequency history, so high-traffic cities survive temporary access gaps. Caffeine benchmarks show W-TinyLFU achieves near-optimal hit rates — outperforming both LRU and LFU alone."

---

### Example 3: Guava LoadingCache — TTL + LRU

**Service:** `expviewsnikeapp` (landing view), `rise-generic-transform-service` (response cache)

```
┌──────────────────────────────────────────────────────────────────────┐
│  Guava Cache Eviction — Multiple Strategies                          │
│                                                                      │
│  Landing View Cache:                                                │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  refreshAfterWrite(5 min)    ← TTL-like (background refresh)│    │
│  │  No maxSize set              ← grows unbounded (small data) │    │
│  │  Keys: country codes (~30)   ← bounded by domain            │    │
│  │                                                              │    │
│  │  Eviction: purely time-based. After 5 min, next access      │    │
│  │  triggers background refresh. Stale value served during     │    │
│  │  refresh (no cache miss penalty).                           │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  Rise GTS Response Cache:                                           │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  expireAfterAccess(5 min)    ← LRU-like (idle timeout)     │    │
│  │  expireAfterWrite(15 min)    ← TTL (absolute freshness)    │    │
│  │  maximumSize(1000)           ← LRU eviction when full      │    │
│  │                                                              │    │
│  │  THREE eviction triggers:                                   │    │
│  │  1. Not accessed for 5 min  → evict (idle)                  │    │
│  │  2. Written 15 min ago      → evict (stale)                 │    │
│  │  3. Cache has 1000 entries  → LRU evicts least recent       │    │
│  │                                                              │    │
│  │  Whichever comes FIRST wins.                                │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  Store Views Cache:                                                 │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  expireAfterWrite(24 hours)  ← TTL (daily refresh)         │    │
│  │  maximumSize(8000)           ← LRU eviction when full      │    │
│  │                                                              │    │
│  │  Store data changes rarely → 24h TTL is fine.               │    │
│  │  8000 stores = bounded domain → maxSize rarely hit.         │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// GetFromService.java (rise-generic-transform-service)
// Triple eviction: idle + TTL + size
private static final Cache<String, Map<String, Object>> RESPONSE_CACHE =
    CacheBuilder.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)    // idle timeout (LRU-like)
        .expireAfterWrite(15, TimeUnit.MINUTES)     // absolute TTL
        .maximumSize(1000)                           // LRU when full
        .build();

// Store views: TTL + size
private static final Cache<String, Map<String, Object>> STOREVIEWS_CACHE =
    CacheBuilder.newBuilder()
        .expireAfterWrite(24, TimeUnit.HOURS)       // daily refresh
        .maximumSize(8000)                           // LRU when full
        .build();
```

---

### Example 4: Akamai CDN — TTL + LRU at the Edge

**Service:** `cxp-events` → Akamai CDN

```
┌──────────────────────────────────────────────────────────────────────┐
│  CDN Eviction: TTL + Edge Disk LRU                                   │
│                                                                      │
│  TWO EVICTION LAYERS:                                               │
│                                                                      │
│  Layer 1: TTL (cache-maxage from origin)                            │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Event page /event/73067  → cache-maxage=60m               │    │
│  │  After 60 min: edge evicts, next request fetches from origin│    │
│  │                                                              │    │
│  │  Seat count /seats/73067  → cache-maxage=1m                 │    │
│  │  After 1 min: edge evicts, fresh seat count on next request │    │
│  │                                                              │    │
│  │  + Tag purge: immediate eviction on Eventtia webhook        │    │
│  │    (overrides TTL — item evicted before TTL expires)         │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  Layer 2: Edge disk LRU (when PoP storage is full)                  │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Each CDN PoP has limited disk/memory.                      │    │
│  │  When full: Akamai evicts least recently requested URLs.    │    │
│  │                                                              │    │
│  │  Popular event page (sneaker launch) → accessed 10K/min     │    │
│  │  → stays cached (recently used)                              │    │
│  │                                                              │    │
│  │  Old archived event page → accessed 1/week                   │    │
│  │  → evicted under memory pressure (LRU victim)               │    │
│  │  → next rare request pulls from origin (acceptable)          │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  For CDN, LFU would also work well (keep popular URLs),            │
│  but LRU is simpler and handles burst traffic patterns better       │
│  (a trending event accessed 1000x in the last minute stays cached). │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 5: Splunk — FIFO (Time-Based Bucket Tiering)

**Service:** All CXP services → Splunk

```
┌──────────────────────────────────────────────────────────────────────┐
│  Splunk Eviction: FIFO by Time Bucket                                │
│                                                                      │
│  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐      │
│  │  Hot   │→│  Warm  │→│  Warm  │→│  Cold  │→│ Frozen │      │
│  │ Today  │  │  -1 day│  │  -7 day│  │ -30 day│  │ -90 day│      │
│  │ (SSD)  │  │  (SSD) │  │  (HDD) │  │ (HDD)  │  │  (S3)  │      │
│  └────────┘  └────────┘  └────────┘  └────────┘  └────────┘      │
│                                                                      │
│  Oldest buckets automatically roll to cheaper/slower tiers.         │
│  This is FIFO: first data in → first data moved to cold → frozen.  │
│                                                                      │
│  Not LRU: Even if you search 90-day-old data today,                │
│  it doesn't move back to "hot". It stays in frozen tier.            │
│  FIFO is correct here because log data value is TIME-correlated —  │
│  recent logs are almost always more valuable than old logs.         │
│                                                                      │
│  From our email-drop-recovery queries:                              │
│  - "earliest=-24h" → searches Hot + Warm (fast, SSD)              │
│  - "earliest=-30d" → also searches Cold (slower, HDD)             │
│  - Without earliest → searches ALL tiers (expensive)               │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Eviction Policies Across CXP

| Component | Primary Policy | Secondary Policy | Why This Combination |
|-----------|---------------|-----------------|---------------------|
| **Redis** | TTL (1 min - 30 days) | allkeys-lru (on maxmemory) | Per-user keys with defined freshness; LRU handles memory overflow |
| **Caffeine** | TTL (2h - 60 days) | W-TinyLFU (on maxSize) | Keeps popular cities/translations; evicts obscure entries |
| **Guava (Rise GTS)** | TTL (5 min - 24h) + idle timeout | LRU (on maxSize) | Triple trigger: stale, idle, or full |
| **Akamai CDN** | TTL (1-60 min) + tag purge | LRU (on edge disk full) | Freshness guaranteed by TTL; capacity managed by LRU |
| **Splunk** | FIFO (time-based bucket roll) | N/A | Log data value = recency; oldest data → coldest tier |

---

## Common Interview Follow-ups

### Q: "LRU vs LFU — when would you pick each?"

> "**LRU** when access patterns are recency-based: session data, idempotency keys, recent search results. Our Redis registration cache is LRU because each key is per-user — accessed a few times then never again. Recency is the best predictor of future access.
>
> **LFU** when access patterns are popularity-based: CDN assets, reference data, translation caches. Our Caffeine city cache benefits from LFU because NYC is always popular — even if it wasn't accessed in the last second, it will be accessed in the next second. LFU (via W-TinyLFU) keeps NYC cached through temporary access gaps.
>
> **Rule of thumb:** If a few items dominate traffic → LFU. If every item is equally likely to be accessed next → LRU."

### Q: "What's the problem with pure LFU?"

> "Frequency pollution. An item accessed 10,000 times last month but 0 times this month still has the highest frequency count — it never gets evicted even though it's no longer relevant. Solutions: (1) decay counters over time, (2) use W-TinyLFU like Caffeine which combines frequency with a recency window, (3) add a TTL floor so even high-frequency items expire eventually. Our Caffeine caches use W-TinyLFU + TTL together."

### Q: "Why not just use TTL for everything?"

> "TTL alone wastes memory. A key with 29 days of TTL remaining might never be accessed again — but TTL keeps it in memory until day 30. LRU/LFU would evict it sooner when memory is needed for more active keys. That's why we combine: TTL for correctness (data never older than X), LRU/LFU for efficiency (memory used for the most valuable items). In our Redis cache, a pairwise key for a user who registered once and never came back would stay 30 days with TTL alone. With `allkeys-lru`, if memory is tight, Redis evicts it early to make room for active users."

### Q: "How do you monitor cache effectiveness?"

> "Three metrics:
> 1. **Hit rate:** `hits / (hits + misses)` — target >90% for production caches. Caffeine's `.recordStats()` exposes this. Low hit rate = wrong TTL or wrong eviction policy.
> 2. **Eviction count:** How often items are evicted before TTL. High eviction = cache too small or too many unique keys.
> 3. **Memory usage:** Redis `INFO memory` shows used vs max. If at maxmemory constantly, LRU is working hard — consider increasing memory or reducing TTLs."

---
---

# Topic 13: CDN (Content Delivery Network)

> Distribute static content to edge servers worldwide, reducing latency from 300ms to 15ms by serving from the nearest location.

> **Interview Tip:** Always mention CDN for static assets — "I'd put images, JS, and CSS on CloudFront with a 24-hour TTL to reduce origin load and improve global latency."

---

## What Is a CDN?

A network of **edge servers distributed globally** that cache content close to users. Instead of every request traveling to a single origin server, users are served from the **nearest edge location**.

```
┌──────────────────────────────────────────────────────────────────────┐
│  WITHOUT CDN                           WITH CDN                      │
│                                                                      │
│       ┌────────┐                            ┌────────┐              │
│       │ Origin │                            │ Origin │              │
│       │US-East │                            │US-East │              │
│       └───┬────┘                            └───┬────┘              │
│      ╱    │    ╲                                │                    │
│     ╱     │     ╲                          (only cache misses)      │
│    ╱      │      ╲                              │                    │
│   ▼       ▼       ▼                    ┌────────┼────────┐          │
│  EU      US     Asia              ┌────┴──┐ ┌──┴───┐ ┌──┴────┐    │
│  200ms   20ms   300ms             │Edge EU│ │Edge  │ │Edge   │    │
│                                   │Frank- │ │US    │ │Asia   │    │
│  Every user hits origin.          │furt   │ │Virgin│ │Tokyo  │    │
│  Far users = high latency.        └───┬───┘ └──┬───┘ └───┬───┘    │
│                                       │        │         │          │
│                                       ▼        ▼         ▼          │
│                                      EU       US       Asia         │
│                                      10ms     5ms      15ms         │
│                                                                      │
│  LATENCY REDUCTION:                                                 │
│  EU:   200ms → 10ms  (95% faster)                                  │
│  Asia: 300ms → 15ms  (95% faster)                                  │
│  US:   20ms  → 5ms   (75% faster)                                  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## What CDN Caches

```
┌──────────────────────────────────────────────────────────────────────┐
│  WHAT CDN CACHES                                                     │
│                                                                      │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐│
│  │ Static   │ │ Video/   │ │ API      │ │ HTML     │ │ Downloads││
│  │ Assets   │ │ Audio    │ │ Responses│ │ Pages    │ │          ││
│  │          │ │          │ │          │ │          │ │ Software,││
│  │ JS, CSS, │ │ Streaming│ │ With     │ │ Static/  │ │ PDFs,    ││
│  │ Images   │ │ media    │ │ cache    │ │ semi-    │ │ fonts    ││
│  │          │ │          │ │ headers  │ │ static   │ │          ││
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘│
│                                                                      │
│  CAN cache:      Static files, public API responses, HTML pages     │
│  SHOULD NOT:     Personalized content, authenticated API responses, │
│                  real-time data (live seat counts)                   │
│  WITH CARE:      Semi-static APIs (event details — cache + purge)   │
└──────────────────────────────────────────────────────────────────────┘
```

---

## CDN Request Flow

```
  User in Tokyo requests: nike.com/experiences/event/73067

  Step 1: DNS resolves nike.com to nearest Akamai PoP (Tokyo)
  Step 2: Tokyo PoP checks local cache

  ┌─────────────────────────────────────────────────────────┐
  │  CACHE HIT (95% of requests)                             │
  │                                                          │
  │  User ──▶ Tokyo PoP ──▶ return cached page              │
  │           (edge server)                                  │
  │                                                          │
  │  Latency: ~15ms (local network)                         │
  │  Origin load: ZERO                                       │
  └─────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────┐
  │  CACHE MISS (5% of requests)                             │
  │                                                          │
  │  User ──▶ Tokyo PoP ──▶ MISS ──▶ Origin (us-east-1)   │
  │                                     │                    │
  │                          ◀──── response + cache headers  │
  │                          │                                │
  │                    Tokyo PoP stores response              │
  │                    (cache-maxage=60m)                     │
  │                          │                                │
  │              ◀──── return to user                         │
  │                                                          │
  │  Latency: ~200ms (cross-Pacific + origin processing)    │
  │  Origin load: ONE request (then cached for 60 min)       │
  └─────────────────────────────────────────────────────────┘
```

---

## CDN In My CXP Projects — Real Examples

### The CXP CDN Architecture

Our platform uses CDN at **two levels**: Akamai for dynamic API responses and CloudFormation/S3 for static frontend assets.

```
┌──────────────────────────────────────────────────────────────────────────┐
│              CXP PLATFORM — CDN ARCHITECTURE                              │
│                                                                          │
│  LEVEL 1: Akamai CDN (API responses — dynamic content)                  │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                                                                  │  │
│  │  nike.com/experiences/event/73067                                │  │
│  │       │                                                          │  │
│  │       ▼                                                          │  │
│  │  ┌──────────┐    HIT     ┌───────────────┐                     │  │
│  │  │  Akamai  │◀──────────│  Edge Cache    │                     │  │
│  │  │  Edge    │            │  cache-maxage  │                     │  │
│  │  │  (250+   │    MISS    │  Edge-Cache-Tag│                     │  │
│  │  │   PoPs)  │──────────▶│               │                     │  │
│  │  └──────────┘            └───────┬───────┘                     │  │
│  │                                  │                               │  │
│  │                                  ▼                               │  │
│  │                          ┌───────────────┐                      │  │
│  │                          │  cxp-events   │                      │  │
│  │                          │  (Origin)     │                      │  │
│  │                          │  Spring Boot  │                      │  │
│  │                          └───────────────┘                      │  │
│  │                                                                  │  │
│  │  Caches: Event detail pages, seat counts, landing pages,        │  │
│  │          group details, registration questions                   │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  LEVEL 2: CloudFront/S3 (Static assets — JS, CSS, HTML)                │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                                                                  │  │
│  │  rapid-retail-insights-host (React SPA)                         │  │
│  │       │                                                          │  │
│  │       ▼                                                          │  │
│  │  ┌──────────┐           ┌───────────────┐                      │  │
│  │  │CloudFront│◀─────────│  S3 Bucket     │                      │  │
│  │  │  CDN     │           │  (Static host) │                      │  │
│  │  └──────────┘           └───────────────┘                      │  │
│  │                                                                  │  │
│  │  Caches: index.html, JS bundles, CSS, images, fonts            │  │
│  │  TTL: Long (hashed filenames → cache forever until new deploy) │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: Akamai — Dynamic API Response Caching

**Service:** `cxp-events` (Spring Boot)
**What's cached:** Event detail pages, seat availability, landing pages, registration questions
**Provider:** Akamai (Nike's enterprise CDN)

```
┌──────────────────────────────────────────────────────────────────────┐
│  Akamai Dynamic Content Caching — CXP Events                        │
│                                                                      │
│  RESOURCE-SPECIFIC TTLs:                                            │
│  ┌──────────────────────┬──────────┬──────────────────────────┐    │
│  │  Resource             │  Edge    │  Browser                  │    │
│  │                       │  TTL     │  TTL                      │    │
│  ├──────────────────────┼──────────┼──────────────────────────┤    │
│  │  Event detail page   │  60 min  │  5 min (downstream-ttl)   │    │
│  │  Event landing page  │  60 min  │  5 min                    │    │
│  │  Group detail page   │  60 min  │  5 min                    │    │
│  │  Registration Qs     │  60 min  │  5 min                    │    │
│  │  Seat availability   │  1 min   │  5 min                    │    │
│  └──────────────────────┴──────────┴──────────────────────────┘    │
│                                                                      │
│  TWO-TIER TTL STRATEGY:                                             │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │                                                            │    │
│  │  Edge-Control: cache-maxage=60m   ← Akamai edge caches   │    │
│  │                downstream-ttl=5m  ← User's browser caches │    │
│  │                                                            │    │
│  │  Why different?                                            │    │
│  │  Edge: 60 min — we can PURGE edge cache instantly on       │    │
│  │        update (tag-based purge). Long TTL = high hit rate. │    │
│  │  Browser: 5 min — we CANNOT purge a user's browser cache.  │    │
│  │        Short TTL = browser refreshes from edge frequently. │    │
│  │                                                            │    │
│  │  Result: User always gets fast response from edge (60m),   │    │
│  │  and edge freshness is guaranteed by purge mechanism.      │    │
│  │  Browser staleness is bounded to 5 min max.                │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — setting CDN headers per resource:**

```java
// AkamaiCacheHeaderBuilder.java — two-tier TTL in one header
httpHeaders.add(HTTP_HEADER_EDGE_CONTROL,
    "!no-store,"
    + "downstream-ttl=" + DOWNSTREAM_TTL      // browser: 5 min
    + ",!bypass-cache,"
    + " cache-maxage=" + cacheTimeout);        // edge: 60 min (or 1 min for seats)

// Edge-Cache-Tag for targeted purging
httpHeaders.add(HTTP_HEADER_EDGE_CACHE_TAG, tag);  // e.g., "edp_73067"
```

```java
// EventsController.java — event detail page → 60 min CDN cache
return eventtiaEventService.getEventDetailsPage(...)
    .map(event -> ResponseEntity.ok()
        .headers(AkamaiCacheHeaderBuilder.build(
            event.getId(),
            edpAkamaiCachingTimeout,           // "60m"
            AKAMAI_TAG_IDENTIFIER_EDP          // "edp_73067"
        ))
        .body(event));
```

```java
// EventSeatsController.java — seat availability → 1 min CDN cache
// Seats change frequently, so short TTL
AkamaiCacheHeaderBuilder.build(
    eventId,
    edpSeatsAkamaiCachingTimeout,              // "1m"
    AKAMAI_TAG_IDENTIFIER_EDP_SEATS            // "edp_seats_73067"
)
```

**Interview answer:**
> "We use Akamai CDN to cache dynamic API responses — not just static files. Event detail pages cache at the edge for 60 minutes with `cache-maxage=60m`, while seat availability caches for only 1 minute because it changes with every registration. We set a separate `downstream-ttl=5m` for the user's browser — shorter because we can't purge browser caches, but we CAN purge Akamai's edge cache instantly via tag-based purge. This two-tier TTL gives us high edge hit rates (~95%) while keeping browser staleness bounded to 5 minutes."

---

### Example 2: Akamai Tag-Based Cache Invalidation

**Service:** `cxp-events` — PurgeCacheController
**Trigger:** Eventtia sends webhook when event data changes

```
┌──────────────────────────────────────────────────────────────────────┐
│  CDN INVALIDATION — Tag-Based Purge                                  │
│                                                                      │
│  Eventtia updates event 73067 (e.g., address changed)               │
│       │                                                              │
│       ▼                                                              │
│  POST /purge-cache { dataType: "Event", id: "73067" }              │
│       │                                                              │
│       ▼                                                              │
│  PurgeCacheController → AkamaiCacheService.purgeCache()             │
│       │                                                              │
│       ├──▶ async: purge tag "edp_73067"        (event detail page) │
│       ├──▶ async: purge tag "edp_seats_73067"  (seat availability) │
│       └──▶ async: purge tag "edp_rq_73067"     (reg questions)     │
│                                                                      │
│  All 3 purges fire in parallel (CompletableFuture.runAsync)         │
│                                                                      │
│  WHAT HAPPENS AT THE EDGE:                                          │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Tokyo PoP:   cached /event/73067 → tag "edp_73067"       │    │
│  │               purge received → cache entry deleted          │    │
│  │                                                             │    │
│  │  London PoP:  cached /event/73067 → tag "edp_73067"       │    │
│  │               purge received → cache entry deleted          │    │
│  │                                                             │    │
│  │  NYC PoP:     not cached (TTL expired earlier)             │    │
│  │               purge = no-op                                 │    │
│  │                                                             │    │
│  │  ALL 250+ PoPs: purge propagated within seconds            │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  Next user request at any PoP:                                      │
│  → Cache MISS → fetch fresh data from origin → cache new response  │
│                                                                      │
│  TAG ADVANTAGE over URL purge:                                      │
│  One event page has MULTIPLE URLs:                                  │
│  /event/73067?lang=en, /event/73067?lang=es, /event/73067?lang=fr  │
│  Tag purge "edp_73067" clears ALL variants with one API call.      │
│  URL purge would require listing every URL variant.                 │
└──────────────────────────────────────────────────────────────────────┘
```

**Reactive purge — triggered by registration errors:**

```java
// EventRegistrationService.java — purge seats when event is full
if (EventtiaErrorCodeDeterminer.requiresCacheInvalidation(specificErrorCode)) {
    CompletableFuture.runAsync(() ->
        akamaiCacheService.seatsAPICachePurging(eventId)
    );
}
// User tries to register → Eventtia says "ACTIVITY_FULL" (422)
// → We purge the seats cache so the next user sees accurate count
// → Without this, users would see "3 spots left" when it's actually full
```

**Interview answer:**
> "We use tag-based CDN purging for surgical cache invalidation. When Eventtia updates an event, a webhook triggers 3 parallel purges — event page, seats, and registration questions — each identified by a unique tag like `edp_73067`. This clears all URL variants (different languages, query params) with one API call per tag, across all 250+ PoPs within seconds. We also have reactive purging: when a registration returns 422 ACTIVITY_FULL, we immediately purge the seats cache so the next user sees the accurate seat count instead of a stale 'spots available' message."

---

### Example 3: CloudFront + S3 — Static SPA Hosting

**Service:** `rapid-retail-insights-host` (React SPA)
**What's cached:** index.html, JS bundles, CSS, images
**Deployed via:** CloudFormation → S3 + CloudFront

```
┌──────────────────────────────────────────────────────────────────────┐
│  Static SPA Hosting — CloudFront + S3                                │
│                                                                      │
│  ┌────────┐     ┌────────────┐     ┌────────────┐                  │
│  │ User   │────▶│ CloudFront │────▶│  S3 Bucket │                  │
│  │ Browser│◀────│  CDN       │◀────│  (Origin)  │                  │
│  └────────┘     └────────────┘     └────────────┘                  │
│                                                                      │
│  CACHE STRATEGY FOR SPAs:                                           │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  FILE TYPE         │  TTL          │  WHY                   │    │
│  ├────────────────────┼───────────────┼────────────────────────┤    │
│  │  index.html        │  Short (5 min)│  Entry point; must     │    │
│  │                    │  or no-cache  │  always serve latest    │    │
│  │                    │               │  version                │    │
│  ├────────────────────┼───────────────┼────────────────────────┤    │
│  │  main.abc123.js    │  1 year       │  Content-hashed name;  │    │
│  │  style.def456.css  │  (immutable)  │  new deploy = new hash │    │
│  │                    │               │  = new URL → no stale  │    │
│  ├────────────────────┼───────────────┼────────────────────────┤    │
│  │  images/logo.png   │  1 year       │  Rarely changes;       │    │
│  │  fonts/nike.woff2  │  (immutable)  │  hashed or versioned   │    │
│  └────────────────────┴───────────────┴────────────────────────┘    │
│                                                                      │
│  The key insight: Webpack hashes JS/CSS filenames on every build.   │
│  main.abc123.js → main.xyz789.js on next deploy.                   │
│  Since the URL changes, the old cached file is never served.        │
│  index.html (which references the new JS URL) must not be cached   │
│  long — otherwise users get the old index.html pointing to the     │
│  old JS bundle.                                                     │
└──────────────────────────────────────────────────────────────────────┘
```

**From the project structure:**

```
rapid-retail-insights-host/
├── cloudformation/          # CloudFront + S3 infrastructure
├── public/index.html        # Entry point (short TTL / no-cache)
├── src/                     # React components
├── webpack.config.js        # Hash-based bundle filenames
└── package.json
```

---

### Example 4: CDN Failure Scenarios and Mitigations

```
┌──────────────────────────────────────────────────────────────────────┐
│  CDN FAILURE SCENARIOS IN CXP                                        │
│                                                                      │
│  SCENARIO 1: Thundering Herd (mass cache expiry)                    │
│  ─────────────────────────────────────────────────                  │
│  Problem: 10,000 users request /event/73067.                        │
│           CDN TTL just expired. All 10,000 requests hit origin.     │
│                                                                      │
│  Our mitigation:                                                    │
│  - Akamai "request collapsing": when multiple requests arrive      │
│    for the same URL during a cache miss, Akamai sends only ONE     │
│    request to origin and serves the response to all waiting users.  │
│  - Origin also has Caffeine JVM cache (2nd layer defense).         │
│                                                                      │
│  SCENARIO 2: Origin Down                                            │
│  ────────────────────────                                           │
│  Problem: cxp-events backend is unreachable.                        │
│                                                                      │
│  Akamai behavior:                                                   │
│  - If cached content exists (even expired): serve stale content     │
│    with "stale-while-revalidate" behavior.                          │
│  - If no cached content: return 502/503 to user.                    │
│  - Our Edge-Control: "!no-store" ensures content IS stored at edge. │
│                                                                      │
│  SCENARIO 3: Stale Seats After CDN Purge Fails                     │
│  ──────────────────────────────────────────────                     │
│  Problem: Purge API fails → edge still shows "5 spots left"        │
│           when event is actually full.                               │
│                                                                      │
│  Our mitigation:                                                    │
│  - Seats TTL = 1 min (short). Even without purge, stale for ≤1min. │
│  - Registration API catches the real error from Eventtia (422).     │
│  - Frontend shows error: "Registration failed — event is full."    │
│  - seatsAPICachePurging has @Retryable with 200ms backoff.         │
└──────────────────────────────────────────────────────────────────────┘
```

---

## CDN Decision Framework

```
┌──────────────────────────────────────────────────────────────────────┐
│  WHAT TO PUT ON CDN                                                  │
│                                                                      │
│  ✅ ALWAYS cache:                                                    │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Static assets (JS, CSS, images, fonts)                     │    │
│  │  → Long TTL (1 year) with content-hashed filenames          │    │
│  │  → Our SPA bundles: main.abc123.js                          │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  ✅ CACHE with short TTL + purge:                                    │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Semi-static API responses (event pages, product details)   │    │
│  │  → Medium TTL (1-60 min) with tag-based invalidation        │    │
│  │  → Our event detail pages: cache-maxage=60m + tag purge     │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  ⚠️ CACHE carefully:                                                 │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Fast-changing data (seat counts, stock levels)             │    │
│  │  → Very short TTL (1 min) + reactive purge on errors        │    │
│  │  → Our seat availability: cache-maxage=1m + 422 purge       │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  ❌ NEVER cache:                                                     │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  User-specific data (profile, cart, auth tokens)            │    │
│  │  POST/PUT/DELETE responses (write operations)               │    │
│  │  Real-time data (WebSocket, SSE streams)                    │    │
│  │  → Our registration API: no CDN (writes to Eventtia)        │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: CDN Usage Across CXP

| Layer | Provider | Content Type | TTL | Invalidation | Hit Rate |
|-------|---------|-------------|-----|-------------|----------|
| **API responses** | Akamai (250+ PoPs) | Event pages, landing pages | 60 min | Tag-based purge on Eventtia webhook | ~95% |
| **Seat availability** | Akamai | JSON seat counts | 1 min | Reactive purge on 422 + short TTL | ~80% |
| **Static SPA** | CloudFront + S3 | JS, CSS, HTML, images | 1 year (hashed) / no-cache (index.html) | New deploy = new hashed filenames | ~99% |
| **MFE remotes** | CloudFront + S3 | Module Federation JS bundles | Content-hashed | New build = new hash | ~99% |

---

## Common Interview Follow-ups

### Q: "Why Akamai for APIs and not just CloudFront?"

> "Nike uses Akamai enterprise-wide. Akamai offers features critical for our use case: Edge-Cache-Tag (purge by semantic tag, not just URL), Edge-Control headers (separate edge vs browser TTLs), request collapsing (prevent thundering herd), and 250+ PoPs for global coverage. CloudFront works well for static S3 hosting (our React SPA), but Akamai's tag-based purge is essential for dynamic API caching where one event update must invalidate multiple URL variants across languages and query parameters."

### Q: "How do you cache API responses without serving personalized data to the wrong user?"

> "Our event detail pages are NOT personalized — every user sees the same event name, date, and location. That's why they're safe to cache at the edge. Registration status IS personalized — it's never cached at the CDN layer. The registration API calls go directly to the backend (through CDN but not cached). The rule: if the response is the same for every user, cache it. If it varies by user, don't. Our Akamai config excludes authenticated API endpoints from caching."

### Q: "What if you need to serve different content per region?"

> "Two approaches in our platform:
> 1. **URL-based:** Different URLs per locale (`/event/73067?lang=en` vs `?lang=es`). Each URL is a separate cache entry. Tag-based purge clears all variants at once.
> 2. **Geo-routing:** Akamai routes users to the nearest PoP automatically. If we needed region-specific content (US vs EU pricing), we'd use Akamai's `Vary: X-Akamai-Edgescape-Country` header to cache separate variants per country. Our landing page already does this — the ELP tag key includes country code, language, and city."

### Q: "How do you handle cache warming for a new event launch?"

> "For a sneaker launch where millions of users will hit the event page simultaneously:
> 1. **Pre-warm:** Before the launch time, we could trigger a request from each major region to populate the edge cache. Our current setup relies on the first user per PoP triggering the cache fill.
> 2. **Request collapsing:** Even without pre-warming, Akamai collapses thousands of simultaneous cache misses into one origin request. The first request fills the cache; the other 9,999 wait and receive the same response.
> 3. **Short seat TTL:** Seats cache at 1 min so availability updates quickly during the launch rush."

---
---

# Topic 14: Load Balancing

> Distribute traffic across servers using round-robin, weighted, least-connections, or IP-hash algorithms; L4 for speed, L7 for flexibility.

> **Interview Tip:** Specify the algorithm — "I'd use least-connections for our API servers since request times vary, and L7 ALB to route /api to backend and /static to CDN."

---

## What Is Load Balancing?

Distributing incoming traffic across multiple servers so no single server becomes a bottleneck. The load balancer sits between clients and servers, deciding which server handles each request.

```
┌──────────────────────────────────────────────────────────────────────┐
│                        LOAD BALANCING                                │
│                                                                      │
│  ┌────────┐                                                         │
│  │Client 1│──┐                                                      │
│  └────────┘  │     ┌──────────────┐     ┌──────────┐               │
│              ├────▶│    Load      │────▶│ Server 1 │ Healthy       │
│  ┌────────┐  │     │   Balancer   │     │          │               │
│  │Client 2│──┤     │    L4/L7    │────▶│ Server 2 │ Healthy       │
│  └────────┘  │     │              │     │          │               │
│              │     │  Health      │────▶│ Server 3 │ Healthy       │
│  ┌────────┐  │     │  Checks     │     │          │               │
│  │Client 3│──┘     │  /health    │     └──────────┘               │
│  └────────┘        │  every 10s  │                                 │
│                    └──────────────┘                                 │
│                                                                      │
│  WITHOUT load balancer: One server handles everything → bottleneck  │
│  WITH load balancer: Traffic spread across N servers → N× capacity  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## L4 vs L7 Load Balancing

```
┌──────────────────────────────────────────────────────────────────────┐
│                                                                      │
│  L4 (Transport Layer)              L7 (Application Layer)           │
│  ─────────────────────             ──────────────────────           │
│                                                                      │
│  Routes based on:                  Routes based on:                  │
│  - IP address                      - URL path (/api, /static)      │
│  - TCP/UDP port                    - HTTP headers (Host, Cookie)    │
│  - Protocol (TCP/UDP)              - HTTP method (GET, POST)        │
│                                    - Query parameters               │
│                                    - SSL termination                │
│                                                                      │
│  CANNOT see:                       CAN see:                         │
│  - URL path                        - Full HTTP request              │
│  - HTTP headers                    - Cookies (session affinity)     │
│  - Request content                 - Content type                   │
│                                                                      │
│  Speed: FASTER (no parsing)        Speed: SLOWER (HTTP parsing)     │
│  Flexibility: LESS                 Flexibility: MORE                │
│                                                                      │
│  AWS: NLB (Network LB)            AWS: ALB (Application LB)        │
│  Use: TCP/UDP traffic,             Use: HTTP APIs, microservices,   │
│  gaming, IoT, raw speed            path-based routing, SSL offload  │
│                                                                      │
│  CXP: Not used                     CXP: ALB for ALL services ✓     │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Load Balancing Algorithms

```
┌──────────────────────────────────────────────────────────────────────┐
│                  LOAD BALANCING ALGORITHMS                            │
│                                                                      │
│  ┌────────────────┐  ┌────────────────┐                             │
│  │  ROUND ROBIN   │  │   WEIGHTED     │                             │
│  │                │  │                │                             │
│  │  1→2→3→1→2→3   │  │  S1:5, S2:3,  │                             │
│  │                │  │  S3:2 ratio    │                             │
│  │  Simple, equal │  │                │                             │
│  │  distribution  │  │  For different │                             │
│  │                │  │  server        │                             │
│  │  ✓ Default for │  │  capacities    │                             │
│  │    most LBs    │  │                │                             │
│  │  ✗ Ignores     │  │  ✓ Mix of old/ │                             │
│  │    server load │  │    new hardware│                             │
│  └────────────────┘  └────────────────┘                             │
│                                                                      │
│  ┌────────────────┐  ┌────────────────┐                             │
│  │  LEAST         │  │   IP HASH      │                             │
│  │  CONNECTIONS   │  │                │                             │
│  │                │  │  hash(client   │                             │
│  │  Route to      │  │  IP) % servers │                             │
│  │  least busy    │  │                │                             │
│  │  server        │  │  Session       │                             │
│  │                │  │  affinity /    │                             │
│  │  ✓ Best for    │  │  sticky        │                             │
│  │    varying     │  │                │                             │
│  │    request     │  │  ✓ Same user   │                             │
│  │    times       │  │    → same      │                             │
│  │  ✗ Requires    │  │    server      │                             │
│  │    tracking    │  │  ✗ Uneven if   │                             │
│  │    connections │  │    IPs skewed  │                             │
│  └────────────────┘  └────────────────┘                             │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Load Balancing In My CXP Projects — Real Examples

### The CXP Load Balancing Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│              CXP PLATFORM — LOAD BALANCING ARCHITECTURE                   │
│                                                                          │
│                          ┌─────────────────┐                            │
│                          │    Internet      │                            │
│                          └────────┬────────┘                            │
│                                   │                                      │
│                          ┌────────▼────────┐                            │
│                          │  Akamai CDN     │  Layer: Edge               │
│                          │  (250+ PoPs)    │  Global geo-distribution   │
│                          └────────┬────────┘                            │
│                                   │                                      │
│                          ┌────────▼────────┐                            │
│                          │  Route53 DNS    │  Layer: DNS                │
│                          │  Latency-based  │  us-east-1 vs us-west-2   │
│                          │  routing        │  based on user's location  │
│                          └───────┬┬────────┘                            │
│                              ┌───┘└───┐                                 │
│                              ▼        ▼                                 │
│               ┌──────────────────┐  ┌──────────────────┐               │
│               │  us-east-1       │  │  us-west-2       │               │
│               │                  │  │                  │               │
│               │  ┌────────────┐  │  │  ┌────────────┐  │               │
│               │  │  ALB (L7)  │  │  │  │  ALB (L7)  │  │               │
│               │  │ cxp-alb    │  │  │  │ cxp-alb    │  │               │
│               │  └─────┬──────┘  │  │  └─────┬──────┘  │               │
│               │        │         │  │        │         │               │
│               │   Path-based     │  │   Path-based     │               │
│               │   routing:       │  │   routing:       │               │
│               │        │         │  │        │         │               │
│               │   /community/    │  │   /community/    │               │
│               │   events/* ──▶   │  │   events/* ──▶   │               │
│               │   cxp-events TG  │  │   cxp-events TG  │               │
│               │        │         │  │        │         │               │
│               │   /community/    │  │   /community/    │               │
│               │   event_reg* ──▶ │  │   event_reg* ──▶ │               │
│               │   cxp-reg TG     │  │   cxp-reg TG     │               │
│               │        │         │  │        │         │               │
│               │   ┌────▼────┐    │  │   ┌────▼────┐    │               │
│               │   │ECS Tasks│    │  │   │ECS Tasks│    │               │
│               │   │(N tasks)│    │  │   │(N tasks)│    │               │
│               │   └─────────┘    │  │   └─────────┘    │               │
│               └──────────────────┘  └──────────────────┘               │
└──────────────────────────────────────────────────────────────────────────┘
```

**Three layers of load balancing, each solving a different problem:**

| Layer | Technology | Algorithm | What It Balances |
|-------|-----------|-----------|-----------------|
| **Edge** | Akamai CDN | Geo-proximity (anycast) | Routes users to nearest PoP globally |
| **DNS** | Route53 latency-based | Latency measurement | Routes to nearest AWS region (us-east vs us-west) |
| **Application** | AWS ALB (L7) | Round-robin (default) + path-based routing | Distributes requests across ECS tasks within a region |

---

### Example 1: ALB — L7 Path-Based Routing

**Service:** All CXP microservices share ONE ALB (`cxp-alb`)
**Pattern:** Path-based listener rules route different API paths to different ECS services

```
┌──────────────────────────────────────────────────────────────────────┐
│  ALB Path-Based Routing — One ALB, Multiple Services                 │
│                                                                      │
│  ALL requests enter through cxp-alb on port 443 (HTTPS)            │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  HTTPS Listener (port 443)                                    │  │
│  │                                                               │  │
│  │  Rule 1: /community/events/*                                  │  │
│  │          /community/event_seats_status/*                      │  │
│  │          /community/event_summaries/*                         │  │
│  │          /community/groups/*                                  │  │
│  │          /community/calendar_url/*                            │  │
│  │          → Forward to: cxp-events Target Group               │  │
│  │                                                               │  │
│  │  Rule 2: /community/event_registrations/*                     │  │
│  │          /community/attendee_status/*                         │  │
│  │          → Forward to: cxp-event-registration Target Group   │  │
│  │                                                               │  │
│  │  Rule 3: /engage/experience_cards*                            │  │
│  │          /engage/experience_nikeapp_*                         │  │
│  │          → Forward to: expviewsnikeapp Target Group          │  │
│  │                                                               │  │
│  │  Rule 4: /data/transform/v1*                                  │  │
│  │          → Forward to: rise-generic-transform Target Group   │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  WHY L7 ALB (not L4 NLB):                                          │
│  - We need PATH-BASED routing (one ALB for 4 services)             │
│  - We need HTTPS termination (SSL offloaded at ALB)                │
│  - We need HEALTH CHECKS per path (/actuator/health)              │
│  - NLB can't inspect HTTP paths — it routes by IP:port only       │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual Terraform — ALB listener rules:**

```hcl
// cxp-events/terraform/module/main.tf

// Shared ALB for all CXP services
data "aws_lb" "selected" {
  name = "cxp-alb"
}

// HTTPS listener (443)
data "aws_alb_listener" "https" {
  load_balancer_arn = data.aws_lb.selected.arn
  port              = 443
}

// Target group for cxp-events ECS tasks
resource "aws_alb_target_group" "tg" {
  name        = "cxp-events-tg"
  port        = 8080              // container port
  protocol    = "HTTP"            // ALB→container is HTTP (SSL terminated at ALB)
  target_type = "ip"              // ECS Fargate tasks registered by IP
  health_check {
    path    = "/actuator/health"  // Spring Boot actuator
    matcher = "200"
  }
}

// Path-based routing: /community/events/* → cxp-events
resource "aws_alb_listener_rule" "alb_listener_rule1" {
  listener_arn = data.aws_alb_listener.https.arn
  action {
    type             = "forward"
    target_group_arn = aws_alb_target_group.tg.arn
  }
  condition {
    path_pattern {
      values = [
        "/community/events/*",
        "/community/event_seats_status/*",
        "/community/event_summaries/*",
        "/community/groups/*"
      ]
    }
  }
}
```

**Interview answer:**
> "All CXP microservices share a single ALB (`cxp-alb`) with L7 path-based routing. The HTTPS listener on port 443 has rules that route `/community/events/*` to the cxp-events target group, `/community/event_registrations/*` to the registration service, and `/engage/experience_*` to expviewsnikeapp. Each target group health-checks against `/actuator/health` and only routes to healthy ECS tasks. We use L7 ALB instead of L4 NLB because we need path-based routing, HTTPS termination, and per-service health checks — all HTTP-layer features."

---

### Example 2: Health Checks — Three Levels

```
┌──────────────────────────────────────────────────────────────────────┐
│  HEALTH CHECK ARCHITECTURE — Three Levels                            │
│                                                                      │
│  LEVEL 1: ALB → Target Group Health Check                           │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Target: /actuator/health (Spring Boot actuator)            │    │
│  │  Port:   8080 (cxp-events, cxp-registration, Rise GTS)     │    │
│  │          8077 (expviewsnikeapp — separate management port)  │    │
│  │  Interval: 10 seconds                                       │    │
│  │  Matcher: HTTP 200                                          │    │
│  │                                                              │    │
│  │  If UNHEALTHY: ALB stops routing to that ECS task.          │    │
│  │  If ALL tasks unhealthy: ALB returns 503 to client.         │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  LEVEL 2: NPE Kubernetes — Liveness & Readiness Probes             │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  cxp-events:                                                │    │
│  │    liveness:  GET /community/events_health/v1 :8080         │    │
│  │    readiness: GET /community/events_health/v1 :8080         │    │
│  │                                                              │    │
│  │  cxp-event-registration:                                    │    │
│  │    liveness:  GET /community/reg_health/v1 :8080            │    │
│  │    readiness: GET /community/reg_health/v1 :8080            │    │
│  │                                                              │    │
│  │  Liveness fail → container RESTARTED                        │    │
│  │  Readiness fail → container removed from service (no traffic)│    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  LEVEL 3: Route53 — Region-Level Health Check                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Route53 health check pings the custom health endpoint      │    │
│  │  for each region:                                           │    │
│  │  - /community/events_health_us_east/v1 → us-east-1         │    │
│  │  - /community/events_health_us_west/v1 → us-west-2         │    │
│  │                                                              │    │
│  │  If us-east-1 health check FAILS:                           │    │
│  │  Route53 stops routing traffic to us-east-1.                │    │
│  │  ALL traffic goes to us-west-2 (failover).                  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  FLOW: Route53 picks healthy REGION                                 │
│        → ALB picks healthy SERVICE (path routing)                   │
│        → Target group picks healthy TASK (round-robin)              │
│        → NPE restarts unhealthy CONTAINERS (liveness)               │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — three health endpoints per service:**

```java
// HealthCheckController.java (cxp-events)
// Three paths: generic, us-east specific, us-west specific
@RequestMapping(value = {
    "/community/events_health/v1",         // ALB + NPE probes
    "/community/events_health_us_east/v1", // Route53 health check (us-east)
    "/community/events_health_us_west/v1"  // Route53 health check (us-west)
})
public class HealthCheckController {
    @GetMapping
    @Unsecured    // no auth required for health checks
    public Mono<ResponseEntity<HealthCheckResponse>> checkHealth() { ... }
}
```

```properties
# application.properties — actuator health for ALB
management.endpoints.web.exposure.include=info,env,health
management.endpoints.web.base-path=/actuator

# Redis health disabled (Redis down shouldn't mark service unhealthy)
management.health.redis.enabled=false
```

**Interview answer:**
> "We have three levels of health checking. ALB checks `/actuator/health` every 10 seconds and removes unhealthy ECS tasks from the target group. NPE's Kubernetes liveness probe checks `/community/events_health/v1` and restarts containers that fail. Route53 health checks ping region-specific endpoints (`events_health_us_east/v1`) — if an entire region is unhealthy, Route53 fails over all traffic to the other region. A critical design choice: `management.health.redis.enabled=false` because a Redis outage shouldn't mark the service as unhealthy — Redis is a cache, not a dependency."

---

### Example 3: Route53 Latency-Based Routing — DNS-Level Load Balancing

**Service:** All CXP services
**Pattern:** DNS resolves to the nearest healthy AWS region

```
┌──────────────────────────────────────────────────────────────────────┐
│  Route53 Latency-Based Routing                                       │
│                                                                      │
│  DNS query: any.v1.events.community.global.prod.origins.nike        │
│                                                                      │
│  Route53 checks:                                                    │
│  1. Measure latency from user's DNS resolver to each region        │
│  2. Check health of each region (Route53 health checks)            │
│  3. Return CNAME to lowest-latency HEALTHY region                  │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  User in New York:                                          │    │
│  │  Latency to us-east-1: 5ms   ← closest, HEALTHY            │    │
│  │  Latency to us-west-2: 60ms                                 │    │
│  │  → Resolves to: aws-us-east-1.v1.events...                  │    │
│  │                                                              │    │
│  │  User in Los Angeles:                                       │    │
│  │  Latency to us-east-1: 55ms                                 │    │
│  │  Latency to us-west-2: 8ms   ← closest, HEALTHY            │    │
│  │  → Resolves to: aws-us-west-2.v1.events...                  │    │
│  │                                                              │    │
│  │  us-east-1 goes DOWN:                                       │    │
│  │  Route53 health check fails → removes us-east-1             │    │
│  │  ALL users → aws-us-west-2.v1.events... (automatic failover)│    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  TWO RECORDS PER SERVICE:                                           │
│  1. "any.v1.events..." → LATENCY routing → picks best region      │
│  2. "aws-us-east-1.v1.events..." → SIMPLE routing → direct to ALB │
│                                                                      │
│  "any.*" is the user-facing domain (latency-routed).               │
│  "aws-<region>.*" is the region-specific target (simple CNAME).    │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual Terraform — latency-based + simple records:**

```hcl
// route53_locals.tf — generates two records per service
route53_service_records = flatten([
  for svc in var.route53_services : [
    {
      // User-facing: latency-routed to best region
      record_name    = "any.v1.${svc.name}.${local.r53_cfg.domain_suffix}"
      record_value   = "aws-${local.r53_region}.v1.${svc.name}.${domain}"
      routing_policy = "LATENCY"        // Route53 picks lowest latency
      health_check   = svc.health_check_name
    },
    {
      // Region-specific: direct CNAME to ALB
      record_name    = "aws-${local.r53_region}.v1.${svc.name}.${domain}"
      record_value   = local.r53_ext_target   // ALB DNS name
      routing_policy = "SIMPLE"
    }
  ]
])
```

---

### Example 4: NPE Platform — Kubernetes-Style Path Routing

For services deployed on NPE (Nike Platform Experience), routing is defined in component YAML:

```
┌──────────────────────────────────────────────────────────────────────┐
│  NPE Path Routing (Kubernetes Ingress equivalent)                    │
│                                                                      │
│  cxp-events component-us-west-2.yaml:                               │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  container:                                                 │    │
│  │    image: artifactory.nike.com:9002/cxp/cxp-events          │    │
│  │    httpTrafficPort: 8080                                    │    │
│  │                                                              │    │
│  │  routing:                                                   │    │
│  │    paths:                                                   │    │
│  │      prefix:                                                │    │
│  │        - path: /community/events/v1                         │    │
│  │        - path: /community/event_seats_status/v1             │    │
│  │        - path: /community/event_summaries/v1                │    │
│  │        - path: /community/groups/v1                         │    │
│  │        - path: /community/events_health/v1                  │    │
│  │                                                              │    │
│  │  health:                                                    │    │
│  │    liveness:                                                │    │
│  │      httpGet:                                               │    │
│  │        path: /community/events_health/v1                    │    │
│  │        port: 8080                                           │    │
│  │    readiness:                                               │    │
│  │      httpGet:                                               │    │
│  │        path: /community/events_health/v1                    │    │
│  │        port: 8080                                           │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  NPE handles:                                                       │
│  - Ingress routing (similar to Kubernetes Ingress/Service)          │
│  - TLS termination (custom certificate authority)                   │
│  - Health-based load balancing (liveness/readiness)                 │
│  - Auto-scaling based on CPU/memory                                 │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Load Balancing Across CXP

| Layer | Technology | Type | Algorithm | What It Routes |
|-------|-----------|------|-----------|---------------|
| **Edge** | Akamai CDN | Anycast | Geo-proximity | Users → nearest PoP globally |
| **DNS** | Route53 | DNS-level | Latency-based | Users → nearest healthy AWS region |
| **Application** | AWS ALB (`cxp-alb`) | L7 | Round-robin + path-based rules | Requests → correct microservice target group |
| **Container** | ALB Target Group | L7 | Round-robin across registered IPs | Requests → healthy ECS task |
| **Platform** | NPE (Kubernetes-style) | L7 | Path prefix routing + health probes | Requests → healthy containers with liveness/readiness |

| Health Check Level | Endpoint | Interval | Failure Action |
|---|---|---|---|
| **ALB → ECS Task** | `/actuator/health` | 10s | Remove task from target group |
| **NPE Liveness** | `/community/events_health/v1` | Configurable | Restart container |
| **NPE Readiness** | `/community/events_health/v1` | Configurable | Stop routing to container |
| **Route53 → Region** | `/community/events_health_us_east/v1` | 30s | Failover entire region |

---

## Common Interview Follow-ups

### Q: "Why one shared ALB instead of one ALB per service?"

> "Cost and simplicity. An ALB costs ~$16/month plus $0.008 per LCU-hour. With 4 microservices sharing one ALB via path-based listener rules, we pay for 1 ALB instead of 4. The tradeoff: a misconfigured listener rule could route traffic to the wrong service. In our case, path patterns are distinct (`/community/events/*` vs `/community/event_registrations/*`), so there's no overlap risk. If services were on different domains, separate ALBs would make sense."

### Q: "Why round-robin instead of least-connections?"

> "ALB default is round-robin, which works well when requests have similar processing times. Our event detail API (`/community/events/*`) has consistent ~50ms response times — round-robin distributes evenly. If we had a mix of fast requests (GET event) and slow requests (POST registration with Eventtia call), least-connections would be better because slow requests would naturally shift to less-loaded tasks. ALB supports least-outstanding-requests algorithm as an option if we needed it."

### Q: "How does your system handle a region failover?"

> "Route53 latency-based routing with health checks handles it automatically. Each region has a health check endpoint (`events_health_us_east/v1`). If all ECS tasks in us-east-1 fail, the ALB returns 503, the health check reports unhealthy, and Route53 stops resolving to us-east-1 within ~30 seconds. All traffic shifts to us-west-2. When us-east-1 recovers, Route53 health check passes again and traffic redistributes by latency. Zero manual intervention."

### Q: "Why disable Redis health in the actuator?"

> "Redis is a performance optimization, not a correctness dependency (Topic 11 — every Redis consumer has a fallback). If Redis goes down and actuator reports unhealthy, ALB would remove ALL ECS tasks from the target group — taking down the entire service. That's worse than running without cache. By disabling `management.health.redis.enabled`, a Redis outage degrades performance (cache misses) but doesn't cause a full service outage. Same principle applies to Elasticsearch health in Rise GTS."

---
---

# Topic 15: Vertical vs Horizontal Scaling

> Vertical (scale up) means bigger machines with limits; horizontal (scale out) means more machines with unlimited potential but distributed complexity.

> **Interview Tip:** Show you understand tradeoffs — "I'd start with vertical scaling for the database since it's simpler, but design stateless services for horizontal scaling from day one."

---

## The Core Difference

```
┌──────────────────────────────────────────────────────────────────────┐
│                                                                      │
│  VERTICAL SCALING                    HORIZONTAL SCALING              │
│  (Scale Up / Down)                   (Scale Out / In)                │
│                                                                      │
│  ┌──────┐    ┌──────────┐           ┌──┐    ┌──┐┌──┐┌──┐┌──┐      │
│  │4 CPU │    │ 16 CPU   │           │S1│    │S1││S2││S3││S4│      │
│  │8 GB  │ ──▶│ 64 GB    │           │  │ ──▶│  ││  ││  ││  │      │
│  │      │    │ Bigger!  │           └──┘    └──┘└──┘└──┘└──┘      │
│  └──────┘    └──────────┘                   Add more machines!      │
│                                                                      │
│  [+] Simple — no code changes       [+] Unlimited scale             │
│  [+] No distributed complexity      [+] Fault tolerant — no SPOF   │
│  [+] ACID transactions easy         [+] Cost-effective (commodity)  │
│                                                                      │
│  [-] Hardware limits (ceiling)       [-] Distributed complexity     │
│  [-] Single point of failure         [-] Data consistency challenges│
│  [-] Downtime during upgrade         [-] Requires load balancer     │
│                                                                      │
│  Best for: Databases, quick          Best for: Web servers,         │
│  fixes, early stage                  microservices, at scale        │
└──────────────────────────────────────────────────────────────────────┘
```

---

## The Scaling Decision Framework

```
┌──────────────────────────────────────────────────────────────────────┐
│  WHEN TO USE EACH                                                    │
│                                                                      │
│  START with vertical scaling when:                                  │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ✓ Early stage — simplicity > scale                        │    │
│  │  ✓ Relational database — ACID needs single node             │    │
│  │  ✓ Quick fix — traffic spike, need more CPU now             │    │
│  │  ✓ Stateful services — in-memory state hard to distribute   │    │
│  │  ✓ Cost < $10K/month — one big server cheaper than cluster  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  SWITCH to horizontal scaling when:                                 │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ✓ Hitting hardware ceiling (largest instance isn't enough) │    │
│  │  ✓ Need fault tolerance (single machine = SPOF)             │    │
│  │  ✓ Traffic is spiky (scale out for peaks, scale in to save) │    │
│  │  ✓ Multi-region deployment needed                           │    │
│  │  ✓ Service is stateless (no in-memory state to share)       │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  DESIGN for horizontal from day one, even if you start vertical:   │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ✓ Make services STATELESS (externalize state to Redis/DB)  │    │
│  │  ✓ Use external session stores (not in-memory sessions)     │    │
│  │  ✓ Put a load balancer in front (ALB) even for 1 instance  │    │
│  │  ✓ Use managed databases that can add replicas (ElastiCache)│    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Scaling In My CXP Projects — Real Examples

### The CXP Scaling Map

Every component in our platform uses a deliberate scaling strategy. Most are horizontal — the platform was designed for sneaker launch traffic spikes.

```
┌──────────────────────────────────────────────────────────────────────────┐
│              CXP PLATFORM — SCALING STRATEGY MAP                          │
│                                                                          │
│  HORIZONTAL SCALING (Scale Out):                                        │
│  ───────────────────────────────                                        │
│  ┌──────────────────┐  ECS tasks: 2 → N (auto-scale on CPU/memory)     │
│  │  cxp-events       │  Stateless: no in-memory state.                  │
│  │  cxp-event-reg    │  All state externalized to Redis, DynamoDB,      │
│  │  expviewsnikeapp  │  Eventtia. Any task can handle any request.      │
│  │  Rise GTS         │  Load balanced via ALB round-robin.              │
│  └──────────────────┘                                                    │
│                                                                          │
│  ┌──────────────────┐  Partitions: auto-split based on throughput.      │
│  │  DynamoDB         │  10K writes/sec → 10 partitions automatically.   │
│  │                   │  No manual intervention. Serverless scaling.      │
│  └──────────────────┘                                                    │
│                                                                          │
│  ┌──────────────────┐  Shards: 5 primary + 5 replica (fixed at create) │
│  │  Elasticsearch    │  Horizontal READ scaling via replica shards.     │
│  │                   │  Horizontal WRITE scaling via primary shards.    │
│  └──────────────────┘                                                    │
│                                                                          │
│  ┌──────────────────┐  PoPs: 250+ edge locations globally.             │
│  │  Akamai CDN       │  Each PoP handles its region's traffic.         │
│  │                   │  Origin load nearly zero (~5% of requests).      │
│  └──────────────────┘                                                    │
│                                                                          │
│  ┌──────────────────┐  Global Table: us-east-1 + us-west-2.            │
│  │  DynamoDB         │  Multi-region = horizontal geo-scaling.          │
│  │  (cross-region)   │  Each region handles its own traffic.            │
│  └──────────────────┘                                                    │
│                                                                          │
│  VERTICAL SCALING (Scale Up):                                           │
│  ────────────────────────────                                           │
│  ┌──────────────────┐  Cluster node size: increase instance type.      │
│  │  Redis            │  ElastiCache: r6g.large → r6g.xlarge.           │
│  │  (ElastiCache)    │  Read replicas (horizontal) for reads.          │
│  │                   │  But PRIMARY is single-node → vertical for writes│
│  └──────────────────┘                                                    │
│                                                                          │
│  ┌──────────────────┐  Likely single-primary relational DB.            │
│  │  Eventtia         │  Seat transactions need single-node ACID.        │
│  │  (External DB)    │  Vertical scaling for write throughput.          │
│  └──────────────────┘                                                    │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: ECS Services — Horizontal Scaling (Stateless by Design)

**Services:** cxp-events, cxp-event-registration, expviewsnikeapp, Rise GTS
**Scaling:** 2 → N ECS tasks, auto-scaled by CPU/memory

```
┌──────────────────────────────────────────────────────────────────────┐
│  Stateless Services → Horizontal Scaling                             │
│                                                                      │
│  WHY THESE SERVICES CAN SCALE HORIZONTALLY:                        │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  STATELESS CHECKLIST                        cxp-events     │    │
│  │                                                             │    │
│  │  ✓ No in-memory user sessions?              YES — no       │    │
│  │    (sessions in Redis, not JVM)             HttpSession     │    │
│  │                                                             │    │
│  │  ✓ No local file storage?                   YES — logs to  │    │
│  │    (files on S3, not local disk)            Splunk, data    │    │
│  │                                             to Eventtia     │    │
│  │                                                             │    │
│  │  ✓ No in-memory cache that must be shared?  YES — Redis    │    │
│  │    (cache in Redis/Caffeine, not shared)    for shared,     │    │
│  │                                             Caffeine for    │    │
│  │                                             local (ok to    │    │
│  │                                             have per-task)  │    │
│  │                                                             │    │
│  │  ✓ Any task can handle any request?         YES — ALB      │    │
│  │    (no session affinity needed)             round-robins    │    │
│  │                                             freely          │    │
│  │                                                             │    │
│  │  ✓ Task can be killed and replaced?         YES — ECS      │    │
│  │    (no state lost on task death)            replaces tasks  │    │
│  │                                             from image      │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  SCALING IN ACTION (sneaker launch):                                │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Normal traffic:   2 ECS tasks × 8080 port                  │    │
│  │  ┌──────┐ ┌──────┐                                         │    │
│  │  │Task 1│ │Task 2│        CPU: 30%                          │    │
│  │  └──────┘ └──────┘                                         │    │
│  │                                                              │    │
│  │  Sneaker launch:   Auto-scale to 8 ECS tasks                │    │
│  │  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐                      │    │
│  │  │Task 1│ │Task 2│ │Task 3│ │Task 4│   CPU: 70%            │    │
│  │  └──────┘ └──────┘ └──────┘ └──────┘                      │    │
│  │  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐                      │    │
│  │  │Task 5│ │Task 6│ │Task 7│ │Task 8│   ALB distributes     │    │
│  │  └──────┘ └──────┘ └──────┘ └──────┘   across all 8        │    │
│  │                                                              │    │
│  │  After launch:     Scale back to 2 tasks (save cost)        │    │
│  │  ┌──────┐ ┌──────┐                                         │    │
│  │  │Task 1│ │Task 2│        CPU: 25%                          │    │
│  │  └──────┘ └──────┘                                         │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  COST ADVANTAGE:                                                    │
│  Vertical: 1 large instance running 24/7 = $$$ always               │
│  Horizontal: N small instances, scale in when idle = $ most of time │
└──────────────────────────────────────────────────────────────────────┘
```

**What makes cxp-event-registration stateless (from actual code):**

```java
// All state externalized — NOTHING in JVM memory across requests:

// Session/idempotency state → Redis (external)
redisTemplate.opsForValue().set(idempotencyKey + SUFFIX, value, Duration.ofMinutes(60));

// Failed registration queue → DynamoDB (external)
dynamoDbTable.putItem(request);

// Pairwise ID cache → Redis (external)
redisTemplate.opsForValue().set(upmId + PAIRWISE_KEY_SUFFIX, details, Duration.ofDays(30));

// User registration → Eventtia API (external)
eventtiaRegistrationApi.registerAttendee(token, eventId, request);

// Caffeine cache → local per-task (each task has its own copy — OK)
// Not shared between tasks. Cache miss just calls source again.
```

**Interview answer:**
> "All our Spring Boot services are stateless by design — no in-memory sessions, no local file storage, no shared JVM state. Registration idempotency goes to Redis, failed registrations to DynamoDB, user data to Eventtia. Any ECS task can handle any request because the ALB round-robins without session affinity. This lets us auto-scale from 2 tasks during normal traffic to 8+ tasks during a sneaker launch, then scale back to save cost. Caffeine caches are local per task — each task builds its own cache, which is fine because cache misses just hit the source."

---

### Example 2: DynamoDB — Automatic Horizontal Scaling (Serverless)

**Service:** `cxp-event-registration`
**Table:** `unprocessed-registration-requests`
**Scaling:** `PAY_PER_REQUEST` = fully automatic

```
┌──────────────────────────────────────────────────────────────────────┐
│  DynamoDB — Serverless Horizontal Scaling                            │
│                                                                      │
│  PAY_PER_REQUEST billing mode = DynamoDB auto-scales everything:    │
│                                                                      │
│  Normal day:                                                        │
│  ┌─────────────────┐                                                │
│  │  1 partition     │   100 writes/sec                              │
│  │  (auto-managed)  │   Cost: $0.25 per million writes              │
│  └─────────────────┘                                                │
│                                                                      │
│  Sneaker launch:                                                    │
│  ┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐               │
│  │ P0    │ │ P1    │ │ P2    │ │ P3    │ │ P4    │               │
│  │ 2K w/s│ │ 2K w/s│ │ 2K w/s│ │ 2K w/s│ │ 2K w/s│               │
│  └───────┘ └───────┘ └───────┘ └───────┘ └───────┘               │
│  5 partitions, 10K writes/sec total. Auto-created.                  │
│  Cost: still $0.25 per million writes (pay per request)             │
│                                                                      │
│  After launch:                                                      │
│  Partitions remain but throughput drops.                            │
│  Cost drops to near zero (pay only for actual requests).            │
│                                                                      │
│  WHY NOT VERTICAL for DynamoDB?                                     │
│  DynamoDB doesn't HAVE instance sizes — it's serverless.            │
│  There's no "scale up" option. It's horizontal-only by design.     │
│  This is the ideal model: you never think about capacity.           │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 3: Redis ElastiCache — Vertical (Writes) + Horizontal (Reads)

**Service:** `cxp-event-registration`
**Scaling:** Primary scales vertically; read replicas scale horizontally

```
┌──────────────────────────────────────────────────────────────────────┐
│  Redis — BOTH Scaling Types Together                                 │
│                                                                      │
│  WRITES → Vertical scaling (single primary)                         │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Primary node handles ALL writes.                            │   │
│  │  To handle more writes: upgrade instance type.               │   │
│  │                                                              │   │
│  │  r6g.large (2 vCPU, 13 GB)  → handles ~100K writes/sec     │   │
│  │  r6g.xlarge (4 vCPU, 26 GB) → handles ~200K writes/sec     │   │
│  │  r6g.2xlarge (8 vCPU, 52 GB)→ handles ~400K writes/sec     │   │
│  │                                                              │   │
│  │  Ceiling: r6g.16xlarge is the largest. After that, need     │   │
│  │  Redis Cluster (horizontal sharding — 16384 hash slots).    │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  READS → Horizontal scaling (add replicas)                          │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Read replicas handle read traffic.                          │   │
│  │  To handle more reads: add more replicas.                    │   │
│  │                                                              │   │
│  │  1 replica  → 2× read capacity (primary + 1 replica)       │   │
│  │  3 replicas → 4× read capacity (primary + 3 replicas)      │   │
│  │  5 replicas → 6× read capacity (primary + 5 replicas)      │   │
│  │                                                              │   │
│  │  Max: 5 replicas per replication group in ElastiCache.      │   │
│  │  Beyond that: Redis Cluster (horizontal write scaling too). │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  OUR SETUP:                                                         │
│  1 Primary (vertical sized) + 3 Read Replicas (horizontal)         │
│  ReadFrom.REPLICA_PREFERRED → reads go to replicas                  │
│  Writes always go to primary                                        │
│                                                                      │
│  This is HYBRID SCALING — vertical where we must (single-writer     │
│  primary), horizontal where we can (read replicas).                 │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "Redis is our best example of hybrid scaling. The primary node scales vertically — we can upgrade from r6g.large to r6g.xlarge for more write throughput. But for reads, we scale horizontally by adding replicas. With `ReadFrom.REPLICA_PREFERRED`, our 3 read replicas handle ~98% of traffic while the primary handles only writes. If we ever exceed even the largest Redis instance's write capacity, we'd move to Redis Cluster mode which shards across multiple primaries using 16384 hash slots — full horizontal scaling for both reads and writes."

---

### Example 4: Elasticsearch — Fixed Horizontal at Creation, Vertical per Node

**Service:** `expviewsnikeapp`
**Index:** `pg_eventcard` — 5 primary shards

```
┌──────────────────────────────────────────────────────────────────────┐
│  Elasticsearch — Both Scaling Types, Different Axes                   │
│                                                                      │
│  HORIZONTAL (fixed at index creation):                              │
│  5 primary shards = 5 parallel search workers                       │
│  Cannot change without reindexing.                                  │
│  Adding replica shards = horizontal read scaling.                   │
│                                                                      │
│  VERTICAL (per data node):                                          │
│  Each data node can be upgraded to larger instance type:            │
│  - More RAM = larger JVM heap = more data cached in memory          │
│  - More CPU = faster query processing per shard                     │
│  - More disk = more data per node before needing new nodes          │
│                                                                      │
│  SCALING PATH:                                                      │
│  1. Start: 3 data nodes, 5 shards, 1 replica = 10 total shards    │
│  2. Read-heavy: add replica shards (horizontal read scaling)        │
│  3. Node overloaded: upgrade node instance (vertical)               │
│  4. Data growing: add more data nodes (horizontal capacity)         │
│  5. Shard limit: reindex with more primary shards (painful)         │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 5: S3 + Athena — Infinite Horizontal, Zero Vertical

```
┌──────────────────────────────────────────────────────────────────────┐
│  S3 — Unlimited Horizontal Scaling (Serverless)                      │
│                                                                      │
│  S3 has no concept of "instance size" — it's infinitely horizontal. │
│                                                                      │
│  Write scaling: S3 automatically partitions by key prefix.          │
│  3,500 PUT requests/sec per prefix → S3 creates more partitions.   │
│                                                                      │
│  Storage scaling: No limit. Petabytes without any config change.    │
│                                                                      │
│  Athena query scaling: Serverless. More data = more scan workers    │
│  automatically. Pay per TB scanned, not per "instance."             │
│                                                                      │
│  This is the IDEAL horizontal scaling model:                        │
│  - No capacity planning                                             │
│  - No instance selection                                            │
│  - No scaling events to manage                                      │
│  - Cost scales linearly with usage                                  │
│                                                                      │
│  CXP example: Partner Hub stores every webhook from Eventtia.       │
│  Whether it's 1,000 or 10,000,000 webhooks — S3 handles it.       │
│  Cost goes from $0.02/month to $20/month. No architecture change.  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Scaling Strategies Across CXP

| Component | Vertical Scaling | Horizontal Scaling | Primary Strategy |
|-----------|-----------------|-------------------|-----------------|
| **ECS Services** (cxp-events, reg, expviews, GTS) | Increase task CPU/memory | Add more ECS tasks (auto-scale) | **Horizontal** — stateless, ALB-distributed |
| **DynamoDB** | N/A (serverless) | Auto-partition on throughput | **Horizontal** — fully automatic |
| **Redis Primary** | Upgrade instance type (r6g.large→xlarge) | N/A (single writer) | **Vertical** for writes |
| **Redis Replicas** | N/A | Add read replicas (1→5) | **Horizontal** for reads |
| **Elasticsearch Nodes** | Upgrade node instance | Add data nodes + replica shards | **Both** — vertical per node, horizontal across cluster |
| **S3 + Athena** | N/A (serverless) | Auto-partition, unlimited | **Horizontal** — infinite, serverless |
| **Akamai CDN** | N/A | 250+ PoPs globally | **Horizontal** — geo-distributed edge |
| **Eventtia (external)** | Likely upgrade DB instance | Unknown | Probably **vertical** (single relational DB) |

---

## The Stateless Principle — Key to Horizontal Scaling

```
┌──────────────────────────────────────────────────────────────────────┐
│  STATELESS = HORIZONTALLY SCALABLE                                   │
│                                                                      │
│  STATEFUL service (CANNOT scale horizontally easily):               │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  // BAD: user session stored in JVM memory                  │    │
│  │  HttpSession session = request.getSession();                │    │
│  │  session.setAttribute("cart", cart);                        │    │
│  │                                                              │    │
│  │  Problem: Request 1 → Task A (session created)              │    │
│  │           Request 2 → Task B (session NOT found!)           │    │
│  │  ALB routed to different task. State is lost.               │    │
│  │  Fix: sticky sessions (but defeats load balancing purpose)  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  STATELESS service (CAN scale horizontally freely):                 │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  // GOOD: state externalized to Redis                       │    │
│  │  redisTemplate.opsForValue().set(                           │    │
│  │      idempotencyKey + SUFFIX, value, Duration.ofMinutes(60) │    │
│  │  );                                                         │    │
│  │                                                              │    │
│  │  Request 1 → Task A (writes to Redis)                      │    │
│  │  Request 2 → Task B (reads from Redis — state found!)      │    │
│  │  ALB routes freely. Any task works. Zero sticky sessions.   │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  OUR CXP SERVICES: All externalize state to:                       │
│  - Redis (idempotency, cache, pairwise IDs)                        │
│  - DynamoDB (unprocessed registrations)                             │
│  - Eventtia (registrations, events — source of truth)              │
│  - S3 (webhook payloads, transforms)                               │
│  → Any ECS task can be killed or added without data loss.          │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Common Interview Follow-ups

### Q: "Why not just use the biggest server?"

> "Three problems: (1) **Hardware ceiling** — the largest AWS instance (u-24tb1.metal, 448 vCPUs, 24 TB RAM) costs ~$200/hour and is the maximum. If you need more, there's no bigger box. (2) **Single point of failure** — one server down = entire service down. Our multi-task ECS setup survives individual task failures. (3) **Cost inefficiency** — a 16-vCPU server runs 24/7 even at 3 AM when traffic is near zero. Horizontal scaling lets us run 2 tasks at night and 8 during a sneaker launch — paying only for what we use."

### Q: "When is vertical scaling the RIGHT choice?"

> "When the component requires a single-writer primary — like our Redis primary node or Eventtia's relational database. ACID transactions across multiple items need a single coordinator. You can't split a `BEGIN TRANSACTION; UPDATE seats; INSERT registration; COMMIT;` across two machines without distributed transactions (2PC), which is slow and complex. For these components, we scale vertically (bigger instance) and add horizontal read replicas to offload reads."

### Q: "How do you handle the Caffeine cache during horizontal scaling?"

> "Each ECS task has its own Caffeine cache — they're NOT shared. When we scale from 2 to 8 tasks, the 6 new tasks start with empty Caffeine caches. This causes a brief spike of cache misses (cold start), but each task warms up within minutes as requests flow through. We accept this because: (1) Caffeine is L2 cache — Redis (L3) catches most misses, (2) the warming period is short, (3) sharing JVM cache across tasks would require a distributed cache protocol (complexity) for minimal benefit. The independently-cached model is simpler and more resilient."

### Q: "How does auto-scaling work for your ECS services?"

> "ECS auto-scaling monitors CloudWatch metrics — typically CPU utilization or request count. When average CPU across tasks exceeds 70% for 3 minutes, ECS launches new tasks from the same Docker image. The new task registers with the ALB target group and starts receiving traffic after passing the health check. Scale-in happens when CPU drops below 30% for 15 minutes — ECS drains connections from one task and terminates it. The ALB health check ensures no traffic goes to starting/stopping tasks."

---
---

# Topic 16: Rate Limiting

> Protect systems with token bucket (allows bursts), leaky bucket (smooth output), or sliding window (no edge bursts) algorithms.

> **Interview Tip:** Be specific — "I'd implement rate limiting at the API gateway using token bucket with 100 requests/minute per user, stored in Redis for distributed limiting."

---

## Why Rate Limiting?

Protect your system from abuse, ensure fair usage, prevent DDoS, and control costs. Returns **HTTP 429 (Too Many Requests)** when limit is exceeded.

```
┌──────────────────────────────────────────────────────────────────────┐
│  WITHOUT RATE LIMITING                  WITH RATE LIMITING           │
│                                                                      │
│  Bot sends 10,000 requests/sec         Bot sends 10,000 req/sec    │
│       │                                      │                       │
│       ▼                                      ▼                       │
│  ┌──────────┐                          ┌──────────┐                │
│  │  Server   │ ← overwhelmed,          │  Rate    │ ← allows 100/s │
│  │  crashes  │   all users affected    │  Limiter │   rejects rest  │
│  └──────────┘                          └────┬─────┘                │
│                                              │                       │
│                                         ┌────┴────┐                 │
│                                         ▼         ▼                 │
│                                    100 req/s   9,900 req/s          │
│                                    → Server    → HTTP 429           │
│                                    (healthy)   (rejected)           │
└──────────────────────────────────────────────────────────────────────┘
```

---

## The 4 Rate Limiting Algorithms

```
┌──────────────────────────────────────────────────────────────────────────┐
│                      RATE LIMITING ALGORITHMS                             │
│                                                                          │
│  ┌──────────────────────────┐  ┌──────────────────────────┐            │
│  │  TOKEN BUCKET            │  │  LEAKY BUCKET             │            │
│  │                          │  │                           │            │
│  │  Bucket holds N tokens.  │  │  Queue fills up.          │            │
│  │  Each request takes 1.   │  │  Leaks at constant rate.  │            │
│  │  Tokens refill at rate R.│  │                           │            │
│  │                          │  │  ┌─────────┐  fixed      │            │
│  │  ●●●●● (5 tokens)       │  │  │ Queue   │──rate──▶    │            │
│  │  +1/sec refill           │  │  │ fills   │  leaks      │            │
│  │                          │  │  └─────────┘             │            │
│  │  [+] Allows bursts up    │  │                           │            │
│  │      to bucket size      │  │  [+] Smooth, constant     │            │
│  │  Used by: AWS, Stripe,   │  │      output rate          │            │
│  │  most APIs               │  │  Used by: Traffic shaping, │            │
│  │                          │  │  NGINX                    │            │
│  └──────────────────────────┘  └──────────────────────────┘            │
│                                                                          │
│  ┌──────────────────────────┐  ┌──────────────────────────┐            │
│  │  FIXED WINDOW            │  │  SLIDING WINDOW LOG /     │            │
│  │                          │  │  COUNTER                  │            │
│  │  Count per time window.  │  │                           │            │
│  │                          │  │  Weighted average of       │            │
│  │  ┌────────┐ ┌────────┐  │  │  current + previous       │            │
│  │  │00:00-  │ │01:00-  │  │  │  window.                  │            │
│  │  │01:00   │ │02:00   │  │  │                           │            │
│  │  │100 req │ │100 req │  │  │  ──────▶ window slides    │            │
│  │  │max     │ │max     │  │  │                           │            │
│  │  └────────┘ └────────┘  │  │  [+] No burst at edges,   │            │
│  │                          │  │      smooth limiting      │            │
│  │  [+] Simple to implement │  │  Used by: Redis rate      │            │
│  │  [-] Burst at window     │  │  limiters, Kong           │            │
│  │      edges (2× in 1 sec) │  │                           │            │
│  └──────────────────────────┘  └──────────────────────────┘            │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## How Each Algorithm Works

### Token Bucket (Most Common)

```
Bucket: 5 tokens max, refills 1 token/second

T=0s:  Bucket = [●●●●●]  (5 tokens)
T=0s:  Request 1 → takes token → [●●●●○]  ✓ allowed
T=0s:  Request 2 → takes token → [●●●○○]  ✓ allowed
T=0s:  Request 3 → takes token → [●●○○○]  ✓ allowed
T=0s:  Request 4 → takes token → [●○○○○]  ✓ allowed
T=0s:  Request 5 → takes token → [○○○○○]  ✓ allowed (BURST of 5!)
T=0s:  Request 6 → no tokens   → HTTP 429  ✗ rejected
T=1s:  +1 token refilled        → [●○○○○]
T=1s:  Request 7 → takes token → [○○○○○]  ✓ allowed

KEY INSIGHT: Allows bursts up to bucket size, then throttles to refill rate.
AWS API Gateway, Stripe, GitHub API all use token bucket.
```

### Fixed Window — The Edge Burst Problem

```
Window: 100 requests per minute

T=00:59  Window 1: 99 requests used (1 remaining)
T=00:59  Request 100 → ✓ allowed (window 1 full)
T=01:00  NEW WINDOW starts, counter resets to 0
T=01:00  Request 1-100 → all ✓ allowed

PROBLEM: 100 requests at 00:59 + 100 requests at 01:00
         = 200 requests in 2 seconds, despite "100/minute" limit!

This is the "edge burst" problem — 2× the limit at window boundaries.
```

### Sliding Window — Fixes the Edge Problem

```
Window: 100 requests per minute, sliding

At T=01:30 (30 seconds into new window):
  Previous window (00:00-01:00): 80 requests
  Current window (01:00-02:00): 40 requests so far

  Weighted count = (previous × overlap%) + current
                 = (80 × 0.5) + 40
                 = 40 + 40 = 80

  80 < 100 → ✓ allowed

No edge burst possible — the window smoothly slides over time.
```

---

## Where to Implement Rate Limiting

```
┌──────────────────────────────────────────────────────────────────────┐
│  IMPLEMENTATION LAYERS                                               │
│                                                                      │
│  1. CLIENT-SIDE                                                     │
│     Prevent accidental spam. Debounce button clicks.                │
│     → Our frontend: disables "Register" button after click         │
│                                                                      │
│  2. CDN / EDGE (Akamai, CloudFront)                                │
│     Block DDoS before traffic reaches your servers.                 │
│     → Akamai WAF rules (not in our IaC, managed by Nike infra)    │
│                                                                      │
│  3. API GATEWAY / LOAD BALANCER                                     │
│     Centralized limiting before hitting services.                   │
│     → Could be ALB + WAF, or API Gateway throttling               │
│                                                                      │
│  4. APPLICATION (our primary layer)                                 │
│     Per-user or per-resource limits with business logic.            │
│     → Our Redis-based bot protection (per user+event)              │
│                                                                      │
│  5. DATABASE                                                        │
│     Connection limits, query timeouts.                              │
│     → Splunk MAX_RESULTS=10000, SQS VisibilityTimeout              │
│                                                                      │
│  Use REDIS for distributed rate limiting across instances.          │
│  Single-instance counters break when you scale horizontally.        │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Rate Limiting In My CXP Projects — Real Examples

### The CXP Rate Limiting Map

```
┌──────────────────────────────────────────────────────────────────────────┐
│              CXP PLATFORM — RATE LIMITING LAYERS                          │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  LAYER 1: Edge (Akamai WAF)                                      │  │
│  │  DDoS protection, IP-based blocking. Managed by Nike platform.   │  │
│  │  Not in our IaC — handled at organizational level.               │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  LAYER 2: Application (Redis-based bot protection)               │  │
│  │  Per-user+event counter. Threshold = 5 failed attempts.          │  │
│  │  Exceeds threshold → HTTP 429 Too Many Requests.                │  │
│  │  This is a FIXED WINDOW rate limiter per registration attempt.  │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  LAYER 3: OAuth Scopes (OSCAR tokens)                            │  │
│  │  Access control per API. Only authorized clients can call        │  │
│  │  specific endpoints. Scopes: read, create, delete per resource.  │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  LAYER 4: Queue-Level Throttling (SQS)                           │  │
│  │  VisibilityTimeout=3600s, maxReceiveCount=3, DLQ.                │  │
│  │  Controls processing rate and prevents infinite retry loops.     │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  LAYER 5: Client-Side (Retry with Backoff)                       │  │
│  │  Exponential backoff on HTTP clients. Self-throttling.           │  │
│  │  Prevents cascading retries from overwhelming downstream.       │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: Redis Bot Protection — Fixed Window Rate Limiter

**Service:** `cxp-event-registration`
**Algorithm:** Fixed window counter per user+event (threshold = 5)
**Storage:** Redis (distributed across all ECS tasks)

```
┌──────────────────────────────────────────────────────────────────────┐
│  Redis-Based Bot Protection — How It Works                           │
│                                                                      │
│  KEY: "{upmId}_{eventId}_failure_counter"                           │
│  TTL: 1 minute (window resets after 1 min)                          │
│  THRESHOLD: 5 failed attempts                                       │
│  RESPONSE: HTTP 429 Too Many Requests                               │
│                                                                      │
│  Request flow:                                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                                                              │   │
│  │  Request 1: GET counter → null (first attempt)               │   │
│  │             SET counter = 1, TTL = 1 min                     │   │
│  │             → proceed to Eventtia ✓                          │   │
│  │             → Eventtia fails (422) → counter stays at 1      │   │
│  │                                                              │   │
│  │  Request 2: GET counter → 1 (below threshold of 5)          │   │
│  │             SET counter = 2 (async increment)                │   │
│  │             → proceed to Eventtia ✓                          │   │
│  │                                                              │   │
│  │  Request 3: GET counter → 2 → proceed ✓                     │   │
│  │  Request 4: GET counter → 3 → proceed ✓                     │   │
│  │  Request 5: GET counter → 4 → proceed ✓                     │   │
│  │                                                              │   │
│  │  Request 6: GET counter → 5 → EXCEEDS THRESHOLD             │   │
│  │             → RegistrationDeniedException                    │   │
│  │             → HTTP 429 "Too Many Requests" ✗                 │   │
│  │                                                              │   │
│  │  After 1 min: TTL expires → counter deleted → user can retry │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  WHY THIS IS A RATE LIMITER (not just idempotency):                │
│  - Per-user: key includes upmId (each user has own counter)        │
│  - Per-resource: key includes eventId (per event, not global)      │
│  - Time-windowed: TTL = 1 min (counter resets after window)        │
│  - Threshold-based: 5 attempts max per window                      │
│  - Distributed: Redis shared across all ECS tasks (not per-task)   │
│  - Returns 429: standard rate limit response code                  │
│                                                                      │
│  THIS IS A FIXED WINDOW algorithm:                                  │
│  Window = 1 minute (TTL), Max = 5 requests, Key = user+event      │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// RegistrationCacheService.java — the rate limiter logic
boolean validateDuplicateRegistrationRequest(String idempotencyKey) {
    Integer counter = (Integer) redisTemplate.opsForValue()
        .get(idempotencyKey + FAILURE_COUNTER_SUFFIX);

    if (!Objects.isNull(counter)) {
        if (counter > REGISTRATION_FAILED_REQUEST_THRESHOLD) {  // threshold = 5
            return true;  // → caller throws RegistrationDeniedException → 429
        }
        CompletableFuture.runAsync(() ->
            addRegistrationRequestFailureCounterToCache(
                idempotencyKey, counter + 1));  // increment async
        return false;
    }
    // First attempt: initialize counter at 1
    CompletableFuture.runAsync(() ->
        addRegistrationRequestFailureCounterToCache(idempotencyKey, 1));
    return false;
}
```

```java
// CXPGlobalExceptionHandler.java — returns 429
@ExceptionHandler(RegistrationDeniedException.class)
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)  // HTTP 429
public Mono<ErrorResponse> handleRegistrationDeniedException(...) {
    return Mono.just(ErrorResponse.builder()
        .status(HttpStatus.TOO_MANY_REQUESTS.value())
        .message(Arrays.asList(TOO_MANY_REQUESTS))
        .errorCode(TOO_MANY_REQUESTS)
        .build());
}
```

```java
// Feature flag — controlled via Secrets Manager
public static boolean cacheBasedBotProtectionFlag = false;
// Toggled per-environment without redeployment
```

**Interview answer:**
> "Our registration service implements a Redis-based fixed window rate limiter. Each user+event combination gets a counter key with 1-minute TTL. If a user sends more than 5 requests in a minute (bot behavior during sneaker launches), we return HTTP 429. The counter lives in Redis so it's shared across all ECS tasks — a bot can't bypass it by hitting different tasks. The feature is toggle-controlled via Secrets Manager, so we can enable/disable without redeployment. The 1-minute TTL acts as the window reset — after 1 minute, the counter expires and the user can retry."

---

### Example 2: SQS — Queue-Level Throttling with DLQ

**Service:** `rise-generic-transform-service`
**Algorithm:** Built-in SQS message throttling (visibility timeout + max receive + DLQ)

```
┌──────────────────────────────────────────────────────────────────────┐
│  SQS Throttling — Preventing Infinite Retry Storms                   │
│                                                                      │
│  ┌──────────┐    poll     ┌──────────┐    max 3     ┌──────────┐  │
│  │  S3 event │──────────▶│   SQS    │──attempts──▶│   DLQ    │  │
│  │  (new     │            │  Queue   │             │  (Dead   │  │
│  │  webhook) │            │          │             │  Letter  │  │
│  └──────────┘            └────┬─────┘             │  Queue)  │  │
│                                │                    └──────────┘  │
│                                ▼                                  │
│                          ┌──────────┐                            │
│                          │ Rise GTS │                            │
│                          │ Consumer │                            │
│                          └──────────┘                            │
│                                                                      │
│  THROTTLING CONTROLS:                                               │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  VisibilityTimeout: 3600s (1 hour)                          │    │
│  │  → After Rise GTS picks up a message, it's hidden from     │    │
│  │    other consumers for 1 hour. If processing fails,         │    │
│  │    message reappears after 1 hour (not immediately).        │    │
│  │  → This is a LEAKY BUCKET for retries: 1 retry per hour.   │    │
│  │                                                              │    │
│  │  maxReceiveCount: 3                                          │    │
│  │  → After 3 failed processing attempts, message moves to DLQ.│    │
│  │  → Prevents infinite retry loops consuming resources.       │    │
│  │  → DLQ messages reviewed manually or by alert.              │    │
│  │                                                              │    │
│  │  MessageRetentionPeriod: 345600s (4 days)                    │    │
│  │  → Messages expire after 4 days if never processed.         │    │
│  │  → Prevents unbounded queue growth.                          │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  WITHOUT THESE CONTROLS:                                            │
│  A poison message (unparseable webhook) would retry infinitely,    │
│  consuming CPU, filling logs, and blocking other valid messages.    │
│                                                                      │
│  WITH THESE CONTROLS:                                               │
│  Attempt 1: fail → hidden for 1 hour                               │
│  Attempt 2: fail → hidden for 1 hour                               │
│  Attempt 3: fail → moved to DLQ (quarantined)                      │
│  Other messages proceed unblocked.                                  │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual CloudFormation:**

```yaml
# store-integration-generic-transform-pipeline-queues.yaml
CreatePipelineAptosQueue:
  Type: AWS::SQS::Queue
  Properties:
    VisibilityTimeout: 3600          # 1 hour between retry attempts
    MessageRetentionPeriod: 345600   # 4 days max
    RedrivePolicy:
      deadLetterTargetArn: !GetAtt CreatePipelineAptosQueueDLQ.Arn
      maxReceiveCount: 3             # max 3 attempts before DLQ
```

---

### Example 3: Exponential Backoff — Client-Side Self-Throttling

**Services:** Rise GTS (HTTP client), expviewsnikeapp (registration client), cxp-event-registration (pairwise API)

```
┌──────────────────────────────────────────────────────────────────────┐
│  Exponential Backoff — Self-Throttling on Failure                    │
│                                                                      │
│  Instead of retrying immediately (hammering a failing service),     │
│  each retry waits exponentially longer:                             │
│                                                                      │
│  Rise GTS HttpClient:                                               │
│  Attempt 1: fail → wait 100ms × 2^1 = 200ms                       │
│  Attempt 2: fail → wait 100ms × 2^2 = 400ms                       │
│  Attempt 3: fail → wait 100ms × 2^3 = 800ms                       │
│  Attempt 4: fail → wait 100ms × 2^4 = 1600ms                      │
│  Attempt 5: fail → wait 100ms × 2^5 = 3200ms                      │
│  Attempt 6: fail → give up, throw exception                        │
│  Total wait: ~6.2 seconds (vs 0ms if no backoff)                   │
│                                                                      │
│  Pairwise API (WebFlux Retry.backoff):                              │
│  Attempt 1: fail → wait 100ms                                      │
│  Attempt 2: fail → wait 200ms                                      │
│  Attempt 3: fail → wait max 1 second                               │
│  maxBackoff caps prevent runaway waits.                             │
│                                                                      │
│  WHY BACKOFF IS RATE LIMITING:                                      │
│  Without backoff: 8 ECS tasks × 6 retries = 48 requests in <1 sec │
│  With backoff:    8 ECS tasks × 6 retries = 48 requests over 6 sec │
│  → 8× reduction in request rate to a failing downstream service.   │
│  → Gives the downstream service TIME to recover.                   │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// Rise GTS — manual exponential backoff
private static final int MAX_RETRY_ATTEMPTS = 6;
// application.properties: http.retry.delay.ms=100, http.retry.backoff.rate=2

for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; ++attempt) {
    try {
        return responseEntity.getBody();
    } catch (HttpStatusCodeException e) { ... }
    Thread.sleep((long) (delayInMS * Math.pow(backOffRate, attempt)));
    // 100 × 2^1, 100 × 2^2, 100 × 2^3, ...
}
```

```java
// Pairwise API — WebFlux reactive backoff
partnerConsumerMapperPairwiseApi.getPairwiseId(...)
    .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
        .maxBackoff(Duration.ofSeconds(1))
        .filter(ex -> /* only retry on specific errors */)
        .doBeforeRetry(signal ->
            log.warn("Retrying pairwise API, attempt={}/{}",
                signal.totalRetries() + 1, 3))
    );
```

```java
// Registration client — Spring @Retryable with configurable backoff
@Retryable(
    maxAttemptsExpression = "#{${service.retry.times}}",           // 3
    value = RetryableAPIException.class,
    backoff = @Backoff(
        delayExpression = "#{${service.retry.durationbetween.millis}}",  // 5000ms
        multiplierExpression = "#{${service.retry.backoff.multiplier.seconds}}"
    )
)
```

---

### Example 4: Splunk Query Cap — Result-Level Limiting

**Service:** `cxp-email-drop-recovery`

```
┌──────────────────────────────────────────────────────────────────────┐
│  Splunk MAX_RESULTS — Preventing Runaway Queries                     │
│                                                                      │
│  MAX_RESULTS = 10000                                                │
│                                                                      │
│  Every Splunk query caps results at 10,000 rows.                    │
│  Without this: a broad query (earliest=-30d) could return millions  │
│  of rows, consuming memory and crashing the Python process.         │
│                                                                      │
│  This is a FIXED WINDOW limiter on query output:                    │
│  Window = 1 query, Max = 10,000 results.                            │
│                                                                      │
│  In email-drop-recovery, 10K is sufficient because:                 │
│  - Daily drops are typically 50-500 (well under 10K)                │
│  - Investigation queries are per-event (typically <1000 results)    │
│  - Reconciliation compares two datasets (both capped)               │
│                                                                      │
│  sleep 1  # avoid rate limiting                                     │
│  (In cxp_email_drop_monitor.py — 1 second pause between            │
│   Splunk API calls to respect Splunk's own rate limits)             │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Rate Limiting Across CXP

| Layer | Mechanism | Algorithm | Limit | Action on Exceed |
|-------|----------|-----------|-------|-----------------|
| **Edge** | Akamai WAF | Token bucket (likely) | IP-based, managed by Nike | Block request at edge |
| **Application** | Redis bot protection | Fixed window (1 min TTL) | 5 attempts per user+event | HTTP 429 Too Many Requests |
| **Queue** | SQS VisibilityTimeout + DLQ | Leaky bucket (1 retry/hour) | 3 attempts max | Move to Dead Letter Queue |
| **Client** | Exponential backoff | Self-throttling | 3-6 retries, 100ms-5s delay | Give up, throw exception |
| **Query** | Splunk MAX_RESULTS | Fixed cap | 10,000 results per query | Truncate results |
| **API Access** | OSCAR scopes | ACL (not rate limit) | Per-scope authorization | HTTP 401/403 |

---

## Common Interview Follow-ups

### Q: "Why Redis for rate limiting instead of local counters?"

> "Horizontal scaling breaks local counters. If we have 8 ECS tasks and a bot sends requests round-robin via ALB, each task would see only 1/8th of the bot's traffic — never hitting the threshold. With Redis, all 8 tasks share one counter per user+event. The bot's 40 requests across 8 tasks all increment the same Redis key, hitting the threshold of 5 correctly. Redis adds <1ms latency — negligible compared to the registration call to Eventtia."

### Q: "Your rate limiter uses fixed window — what about the edge burst problem?"

> "Our 1-minute fixed window with threshold 5 could theoretically allow 10 requests in 2 seconds (5 at T=0:59, 5 at T=1:00). In practice this is acceptable because: (1) our threshold is 5, not 100 — a 10-request burst doesn't hurt, (2) Eventtia has its own duplicate check (returns 422), and (3) the counter is for bot protection, not strict rate control. If we needed precise rate limiting (e.g., for a public API), I'd upgrade to sliding window log using Redis sorted sets — `ZADD` with timestamps, count entries in the last 60 seconds."

### Q: "How would you implement token bucket with Redis?"

> "Two Redis operations per request:
> 1. `GET bucket:{userId}` → returns `{tokens, last_refill_timestamp}`
> 2. Calculate tokens to add since last refill: `elapsed_seconds × refill_rate`
> 3. If tokens > 0: decrement and `SET` → allow request
> 4. If tokens = 0: reject with 429
>
> For atomicity, wrap in a Lua script (Redis executes Lua atomically). This prevents race conditions where two concurrent requests both see tokens=1 and both proceed. Our current implementation avoids this complexity because the failure counter pattern doesn't need token-level precision."

### Q: "What about rate limiting for your email-drop-recovery reprocessing?"

> "The RISE API reprocessing in our recovery tool self-throttles with a 1-second sleep between Splunk API calls (`sleep 1 # avoid rate limiting`). For the RISE reprocess endpoint, we process items sequentially — one at a time — to avoid overwhelming the downstream transform service. If we needed to batch-reprocess thousands of emails, I'd implement a token bucket pattern: process N items per minute, refilling tokens at a rate the downstream service can handle."

---
---

# Topic 17: Circuit Breaker

> Prevent cascading failures by failing fast when a downstream service is unhealthy — closed (normal), open (reject all), half-open (testing).

> **Interview Tip:** Pair with other patterns — "I'd wrap external API calls in a circuit breaker with 5 failures threshold, 30-second timeout, and return cached data as fallback."

---

## What Is a Circuit Breaker?

A **state machine** that monitors calls to a downstream service and **stops sending requests** when that service is failing — preventing cascading failures, thread exhaustion, and wasted resources.

```
┌──────────────────────────────────────────────────────────────────────┐
│  CIRCUIT BREAKER — State Machine                                     │
│                                                                      │
│  Prevent cascading failures when a downstream service is unhealthy. │
│  Stop hammering a failing service — fail fast, recover gracefully.  │
│                                                                      │
│         Failures exceed                    Timeout                   │
│         threshold                          expires                   │
│   ┌──────────┐         ┌──────────┐         ┌──────────┐           │
│   │  CLOSED  │────────▶│   OPEN   │────────▶│  HALF-   │           │
│   │ (Normal) │         │(Fail Fast)│         │  OPEN    │           │
│   │          │◀────────│          │         │(Testing) │           │
│   └──────────┘ Success │          │◀────────└──────────┘           │
│                service └──────────┘  Still                          │
│                recovered              failing                       │
│                                                                      │
│  CLOSED:                                                            │
│  - Requests flow normally                                           │
│  - Track failure count                                              │
│  - Reset count on success                                           │
│                                                                      │
│  OPEN:                                                              │
│  - Reject ALL requests immediately (no downstream call)             │
│  - Return fallback/error                                            │
│  - Wait for timeout period                                          │
│                                                                      │
│  HALF-OPEN:                                                         │
│  - Allow limited test requests through                              │
│  - If success → go CLOSED (service recovered)                       │
│  - If failure → go OPEN (still broken)                              │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Why Circuit Breakers Matter

```
┌──────────────────────────────────────────────────────────────────────┐
│  WITHOUT CIRCUIT BREAKER                                             │
│                                                                      │
│  Eventtia is down (500 errors)                                      │
│                                                                      │
│  cxp-event-registration:                                            │
│  Request 1 → call Eventtia → wait 10s → timeout → error            │
│  Request 2 → call Eventtia → wait 10s → timeout → error            │
│  Request 3 → call Eventtia → wait 10s → timeout → error            │
│  ...                                                                │
│  Request 1000 → call Eventtia → wait 10s → timeout → error         │
│                                                                      │
│  RESULT: 1000 threads blocked for 10s each.                         │
│  Thread pool exhausted → service cannot handle ANY requests.        │
│  ALB health check fails → ECS marks task unhealthy → restarts.     │
│  Cascading failure: one service's outage takes down another.        │
│                                                                      │
│  WITH CIRCUIT BREAKER                                                │
│                                                                      │
│  Request 1-5: call Eventtia → fail → circuit tracks failures       │
│  Request 6: CIRCUIT OPENS → immediate 503 (no Eventtia call)       │
│  Request 7-1000: all get immediate 503 (0ms, no thread blocked)    │
│                                                                      │
│  RESULT: 5 threads blocked (not 1000). Service stays healthy.      │
│  After 30s: circuit goes HALF-OPEN → test 1 request to Eventtia.  │
│  If Eventtia is back → CLOSED. If still down → OPEN again.        │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Circuit Breaker In My CXP Projects — Real Examples

### The Reality: No Formal Circuit Breaker, But Circuit Breaker BEHAVIOR

Our CXP services **do not use Resilience4j or a formal circuit breaker library** for API calls. However, they implement **circuit breaker-like behavior** through a combination of patterns: retry + fallback, try-catch degradation, feature flags as manual circuit breakers, and health check isolation.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — RESILIENCE PATTERNS (Circuit Breaker-Adjacent)           │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  PATTERN 1: Try-Catch Fallback (Redis)                            │  │
│  │  Every Redis call → catch → return null → service continues      │  │
│  │  Effect: Redis failure is INVISIBLE to the user.                 │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  PATTERN 2: onErrorResume Fallback (Eventtia)                     │  │
│  │  Eventtia API fails → return empty landing page / empty list     │  │
│  │  Effect: Page loads with "no events" instead of crashing.        │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  PATTERN 3: @Retryable + @Recover (Cache Purge)                   │  │
│  │  Retry 2×, then @Recover gives up gracefully (returns false).    │  │
│  │  Effect: Cache stays stale but service doesn't crash.            │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  PATTERN 4: Feature Flag as Manual Circuit Breaker                │  │
│  │  cacheBasedBotProtectionFlag → toggle off via Secrets Manager.   │  │
│  │  Effect: Disable entire bot protection if Redis is causing issues.│  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  PATTERN 5: Health Check Isolation                                │  │
│  │  management.health.redis.enabled=false                           │  │
│  │  management.health.elasticsearch.enabled=false                   │  │
│  │  Effect: Dependency failure doesn't trigger ALB to kill service. │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  PATTERN 6: Timeouts (Prevent Infinite Hangs)                     │  │
│  │  Redis: 2s command timeout                                        │  │
│  │  Retrofit: 10s connect + 10s read                                 │  │
│  │  WebClient: 10s connect + 10s read/write                         │  │
│  │  Okta: 5s connection timeout                                      │  │
│  │  Effect: Failing call releases thread in bounded time.           │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  MISSING: A formal OPEN/HALF-OPEN/CLOSED state machine.               │
│  We retry every request to Eventtia even during an outage.             │
│  A circuit breaker would STOP calling Eventtia after N failures.       │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: Redis — Try-Catch as Implicit Circuit "Always Closed"

**Service:** `cxp-event-registration`
**Pattern:** Every Redis call wrapped in try-catch, returns null/false on failure

```
┌──────────────────────────────────────────────────────────────────────┐
│  Redis Fallback — "Always Closed" Circuit                            │
│                                                                      │
│  FORMAL CIRCUIT BREAKER:                                            │
│  CLOSED → 5 failures → OPEN → reject all → wait → HALF-OPEN       │
│                                                                      │
│  WHAT WE DO INSTEAD:                                                │
│  Every call: try Redis → catch → fallback → proceed without cache  │
│  The "circuit" is ALWAYS CLOSED — we always try Redis.              │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────┐       │
│  │  Request 1: Redis GET → success ✓                        │       │
│  │  Request 2: Redis GET → success ✓                        │       │
│  │  Request 3: Redis GET → FAIL → catch → return null       │       │
│  │             → proceed to Partner API (fallback) ✓        │       │
│  │  Request 4: Redis GET → FAIL → catch → return null       │       │
│  │             → proceed to Partner API (fallback) ✓        │       │
│  │  Request 5: Redis GET → FAIL → catch → return null       │       │
│  │             ...still trying Redis every time...          │       │
│  │                                                          │       │
│  │  With a REAL circuit breaker:                            │       │
│  │  Request 3-5: fail → circuit OPENS                       │       │
│  │  Request 6+: skip Redis entirely (0ms) → go to fallback │       │
│  │  Saves: 3 × 2s timeout = 6 seconds of wasted waiting    │       │
│  └─────────────────────────────────────────────────────────┘       │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — EVERY Redis call has try-catch fallback:**

```java
// RegistrationCacheService.java — pairwise ID lookup
EventRegistrationResponse getRegistrationRequestIdempotencyValueFromCache(String idempotencyKey) {
    try {
        return GSON.fromJson(
            (String) redisTemplate.opsForValue().get(idempotencyKey + SUCCESS_RESPONSE_SUFFIX),
            EventRegistrationResponse.class);
    } catch (Exception e) {
        log.error("Redis exception :: idempotencyKey :: " + idempotencyKey, e);
        return null;   // FALLBACK: return null → caller proceeds without cache
    }
}

// EventRegistrationService.java — pairwise cache
public Mono<PairWiseIdDetails> getPairWiseIdFromCache(String upmId) {
    try {
        var pairwise = redisTemplate.opsForValue().get(upmId + PAIRWISE_KEY_SUFFIX);
        if (!Objects.isNull(pairwise)) {
            return Mono.just(objectMapper.convertValue(pairwise, PairWiseIdDetails.class));
        }
    } catch (Exception e) {
        log.error("Redis exception, defaulting to partner API", e);
        // FALLBACK: skip cache, go directly to Partner API
    }
    return getPairWiseAndSetCache(upmId);
}
```

**What a formal circuit breaker would add:**

```java
// HYPOTHETICAL: Resilience4j circuit breaker on Redis
@CircuitBreaker(name = "redis", fallbackMethod = "redisFallback")
public Object getFromRedis(String key) {
    return redisTemplate.opsForValue().get(key);
}

// After 5 failures in 60 seconds → circuit OPENS
// Next 30 seconds: all calls skip Redis entirely (0ms)
// After 30s: HALF-OPEN → test 1 call → if success → CLOSED
public Object redisFallback(String key, Exception e) {
    return null;  // same fallback, but Redis not even called
}
```

---

### Example 2: Eventtia — onErrorResume as Graceful Degradation

**Service:** `cxp-events`
**Pattern:** Reactive `onErrorResume` returns empty data structures on Eventtia API failure

```
┌──────────────────────────────────────────────────────────────────────┐
│  Eventtia Fallback — Graceful Degradation                            │
│                                                                      │
│  WHAT HAPPENS WHEN EVENTTIA IS DOWN:                                │
│                                                                      │
│  Landing Page (/community/events/v1):                               │
│  Eventtia API → 500 → onErrorResume → return EMPTY landing page    │
│  User sees: "No events available" (not a 500 error page)            │
│                                                                      │
│  City Categories:                                                   │
│  Eventtia API → 500 → onErrorResume → return empty countries list  │
│  User sees: no city filter options (not a crash)                    │
│                                                                      │
│  Pagination (multiple pages):                                       │
│  Page 1 → success ✓                                                │
│  Page 2 → fails → onErrorResume → Mono.empty() (skip this page)   │
│  Page 3 → success ✓                                                │
│  User sees: partial results (pages 1+3) instead of total failure   │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// EventtiaEventService.java — landing page fallback
Mono<EventtiaEventsLandingPage> landingPage =
    eventtiaEventsLandingPageDetailsApi.getEventLandingPageDetails(token, params, page, size)
        .onErrorResume(ex -> {
            log.error("Error calling eventtiaEventsLandingPageDetailsApi, status={}, response={}",
                ex.getMessage(), ApiErrorResponseFetcher.fetchErrorResponse(ex));
            return Mono.just(new EventtiaEventsLandingPage());  // EMPTY fallback
        })
        .subscribeOn(Schedulers.boundedElastic());

// City categories fallback
Mono<EventtiaEventSummary> summary =
    eventtiaCityCategoriesApi.getCityDetails(...)
        .onErrorResume(ex -> {
            log.error("Error calling eventtiaCityCategoriesApi");
            return Mono.just(EventtiaEventSummary.builder()
                .countries(new ArrayList<>()).build());  // EMPTY fallback
        });

// Pagination — skip failed pages
.flatMap(i -> getEventsLandingPage(context, params, String.valueOf(i), pageSize)
    .onErrorResume(error -> Mono.empty()))   // SKIP this page, continue
```

**What's missing (and when I'd add it):**
> "These `onErrorResume` blocks handle individual failures gracefully, but they don't track failure rates or stop calling Eventtia during a prolonged outage. If Eventtia is down for 10 minutes and we're getting 1000 requests/minute, that's 10,000 failed Eventtia calls — each waiting for the 10-second timeout. A circuit breaker would detect the outage after 5 failures, stop calling Eventtia for 30 seconds, and return the empty fallback immediately. I'd add Resilience4j `@CircuitBreaker` on the Eventtia API client as the next resilience improvement."

---

### Example 3: @Retryable + @Recover — Retry Then Give Up

**Service:** `cxp-event-registration` (Akamai cache purge)
**Pattern:** Retry 2 attempts with 200ms backoff, then @Recover returns false

```
┌──────────────────────────────────────────────────────────────────────┐
│  @Retryable + @Recover — Bounded Retry with Graceful Failure        │
│                                                                      │
│  Attempt 1: purge Akamai cache → fail (AkamaiPurgingException)     │
│             wait 200ms                                              │
│  Attempt 2: purge Akamai cache → fail                              │
│             → @Recover kicks in → return false (give up)            │
│                                                                      │
│  This is like a circuit breaker with:                               │
│  - Threshold = 2 failures                                           │
│  - No OPEN state (just gives up on this operation)                  │
│  - No HALF-OPEN (next call starts fresh with 2 retries)             │
│                                                                      │
│  The @Recover ensures the service NEVER crashes due to cache purge  │
│  failure. The cache stays stale (TTL will expire it naturally).     │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// AkamaiCacheService.java — retry + recover
@Retryable(retryFor = {AkamaiPurgingException.class},
           backoff = @Backoff(delay = 200),
           maxAttempts = 2)
protected Boolean seatsAPICachePurging(String eventId) {
    // ... call Akamai purge API ...
    throw new AkamaiPurgingException("Error purging cache for eventId=" + eventId);
}

@Recover
public Boolean recoverySeatsAPICachePurging(AkamaiPurgingException ex, String eventId) {
    log.info("Error while purging cache for eventId={} :: {}", eventId, ex.getMessage());
    return false;   // GIVE UP — cache stays stale, TTL will handle it
}
```

```java
// RegistrationCacheService.java — retry + recover for Redis eviction
@Retryable(retryFor = {CachePurgeException.class},
           backoff = @Backoff(delay = 200),
           maxAttempts = 2)
boolean evictCacheBasedOnCancellationRequest(String idempotencyKey) {
    // ... delete Redis keys ...
    throw new CachePurgeException("Error purging cache");
}

@Recover
public Boolean recoveryEvictCacheBasedOnCancellationRequest(
        CachePurgeException ex, String idempotencyKey) {
    log.info("Error evicting cache for idempotencyKey={}", idempotencyKey);
    return false;   // GIVE UP — keys will expire via TTL
}
```

---

### Example 4: Feature Flags — Manual Circuit Breaker

**Service:** `cxp-event-registration`
**Pattern:** `cacheBasedBotProtectionFlag` toggled via Secrets Manager without redeployment

```
┌──────────────────────────────────────────────────────────────────────┐
│  Feature Flag as Manual Circuit Breaker                              │
│                                                                      │
│  NORMAL (flag = true):                                              │
│  Registration → check Redis (bot protection) → call Eventtia       │
│                                                                      │
│  REDIS CAUSING ISSUES (flag toggled to false via Secrets Manager):  │
│  Registration → SKIP Redis entirely → call Eventtia directly       │
│                                                                      │
│  This is a MANUAL circuit breaker:                                  │
│  - CLOSED = flag true (Redis calls enabled)                         │
│  - OPEN = flag false (Redis calls bypassed)                         │
│  - No HALF-OPEN (human decides when to re-enable)                  │
│                                                                      │
│  ADVANTAGE: Precise control. On-call engineer toggles during        │
│  incident without code deploy.                                      │
│  DISADVANTAGE: Requires human intervention. Not automatic.          │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// Constants.java — default OFF
public static boolean cacheBasedBotProtectionFlag = false;

// CXPCommonSecretService.java — toggled via Secrets Manager
CXPFeatureFlag cxpFeatureFlag = objectMapper.readValue(secret, CXPFeatureFlag.class);
setCacheBasedBotProtection(cxpFeatureFlag.isCacheBasedBotProtection());
// No redeployment needed — secret value change takes effect on next read

// EventRegistrationService.java — conditional bypass
if (cacheBasedBotProtectionFlag) {
    // Redis-based bot protection logic
    Object cachedResponse = registrationCacheService
        .getRegistrationRequestIdempotencyValueFromCache(idempotencyKey);
    // ...
} else {
    // BYPASS: skip Redis entirely, go straight to Eventtia
}
```

---

### Example 5: Timeouts — Preventing Infinite Hangs

Timeouts are a **prerequisite** for circuit breakers — without them, a failing call hangs forever and no failure is ever detected.

```
┌──────────────────────────────────────────────────────────────────────┐
│  TIMEOUT MAP ACROSS CXP                                              │
│                                                                      │
│  ┌──────────────────────┬──────────────┬─────────────────────────┐ │
│  │  Component            │  Timeout     │  Why This Value         │ │
│  ├──────────────────────┼──────────────┼─────────────────────────┤ │
│  │  Redis command        │  2 seconds   │  Cache miss > 2s is    │ │
│  │                       │              │  worse than skipping   │ │
│  │                       │              │  cache entirely        │ │
│  ├──────────────────────┼──────────────┼─────────────────────────┤ │
│  │  Okta OAuth           │  5 seconds   │  Auth token fetch;     │ │
│  │                       │              │  must complete before  │ │
│  │                       │              │  request times out     │ │
│  ├──────────────────────┼──────────────┼─────────────────────────┤ │
│  │  Retrofit (Eventtia,  │  10s connect │  External APIs can be  │ │
│  │  Akamai, Partner API) │  10s read    │  slow; 10s is generous │ │
│  │                       │              │  but bounded            │ │
│  ├──────────────────────┼──────────────┼─────────────────────────┤ │
│  │  WebClient (expviews) │  10s connect │  Same reasoning as     │ │
│  │                       │  10s read    │  Retrofit               │ │
│  │                       │  10s write   │                         │ │
│  ├──────────────────────┼──────────────┼─────────────────────────┤ │
│  │  RestTemplate         │  3s connect  │  Internal calls should │ │
│  │  (expviewsnikeapp)    │  55s socket  │  be fast; socket       │ │
│  │                       │  60s request │  timeout covers ES     │ │
│  │                       │              │  slow queries           │ │
│  └──────────────────────┴──────────────┴─────────────────────────┘ │
│                                                                      │
│  WITHOUT TIMEOUTS:                                                  │
│  Eventtia hangs → thread blocked indefinitely → thread pool full   │
│  → all requests queue → health check fails → service dies          │
│                                                                      │
│  WITH TIMEOUTS:                                                     │
│  Eventtia hangs → 10s timeout → exception → fallback/retry         │
│  → thread freed → other requests continue normally                 │
└──────────────────────────────────────────────────────────────────────┘
```

---

## What I'd Add: Formal Circuit Breaker with Resilience4j

If I were improving the CXP platform's resilience, here's what I'd implement:

```
┌──────────────────────────────────────────────────────────────────────┐
│  PROPOSED: Resilience4j Circuit Breaker on Eventtia API              │
│                                                                      │
│  CircuitBreakerConfig:                                              │
│    failureRateThreshold: 50%      (open if 50% of calls fail)      │
│    slidingWindowSize: 10          (last 10 calls tracked)           │
│    waitDurationInOpenState: 30s   (30s before trying again)         │
│    permittedCallsInHalfOpen: 3    (test with 3 calls)              │
│    slowCallRateThreshold: 80%     (count slow calls as failures)   │
│    slowCallDurationThreshold: 5s  (calls >5s = "slow")             │
│                                                                      │
│  NORMAL (CLOSED):                                                   │
│  Registration → circuit breaker → Eventtia API → success           │
│                                                                      │
│  EVENTTIA DOWN (OPEN after 5/10 failures):                          │
│  Registration → circuit breaker → IMMEDIATE 503                    │
│  → No Eventtia call (0ms instead of 10s timeout)                   │
│  → Fallback: "Registration service temporarily unavailable"        │
│  → Save to DynamoDB unprocessed queue for later retry              │
│                                                                      │
│  TESTING RECOVERY (HALF-OPEN after 30s):                            │
│  Registration → circuit breaker → test 3 calls to Eventtia         │
│  → If 2/3 succeed → CLOSED (back to normal)                       │
│  → If 2/3 fail → OPEN again (wait another 30s)                    │
│                                                                      │
│  BENEFIT: During a 10-minute Eventtia outage:                       │
│  Without CB: 10,000 failed calls × 10s timeout = wasted resources  │
│  With CB:    5 failed calls → open → 0 calls for 30s → test 3     │
│              Total: ~25 calls instead of 10,000                     │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Resilience Patterns Across CXP

| Pattern | Implementation | State Machine? | Automatic? |
|---------|---------------|---------------|-----------|
| **Redis try-catch fallback** | Every Redis call wrapped in try-catch → return null | No (always tries) | Yes (code-level) |
| **Eventtia onErrorResume** | Reactive fallback → empty data structures | No (always tries) | Yes (code-level) |
| **@Retryable + @Recover** | 2 retries → give up gracefully | Partial (bounded retry) | Yes |
| **Feature flag toggle** | `cacheBasedBotProtectionFlag` via Secrets Manager | Manual OPEN/CLOSED | No (human toggles) |
| **Health check isolation** | `management.health.redis.enabled=false` | N/A (ALB-level) | Yes |
| **Timeouts** | 2s Redis, 10s Retrofit, 10s WebClient | N/A (prerequisite) | Yes |
| **Formal circuit breaker** | NOT implemented (Hystrix enabled in Rise GTS for metrics only) | Full 3-state | Would be automatic |

---

## Common Interview Follow-ups

### Q: "Your services don't have formal circuit breakers. Isn't that a risk?"

> "It's a calculated tradeoff. Our services mitigate the worst cascading failure scenarios through timeouts (bounded wait), try-catch fallbacks (graceful degradation), and feature flags (manual kill switch). The gap is during prolonged downstream outages — without a circuit breaker, every request still attempts the failing call and waits for the timeout. For Eventtia specifically, the 10-second Retrofit timeout means 1000 requests during a 10-minute outage waste 10,000 seconds of thread time. A Resilience4j circuit breaker would detect the outage in 5 calls, stop calling for 30 seconds, and test recovery periodically — reducing wasted calls from 10,000 to ~25."

### Q: "How would you implement the fallback when the circuit is open?"

> "Three options depending on the endpoint:
> 1. **Event detail page** (GET): Return Akamai-cached version. Even if the cache is stale, showing last-known event data is better than a 503. This is our CDN acting as a circuit breaker fallback.
> 2. **Registration** (POST): Save the request to DynamoDB unprocessed queue and return 'Registration received, confirmation pending.' Reprocess when Eventtia recovers. This is the existing retry queue pattern.
> 3. **Seat check** (GET): Return last-known seat count from Redis cache with a 'data may be stale' warning. Better than 'service unavailable.'"

### Q: "Circuit breaker vs retry — when do you use each?"

> "**Retry** handles transient failures (network blip, temporary 500). Our @Retryable with exponential backoff handles this — try 2-3 times over a few seconds. **Circuit breaker** handles sustained failures (service down for minutes). After N retries all fail, the circuit opens and stops trying entirely. They work together: retry handles the small stuff, circuit breaker handles the big stuff. Our platform has retry (exponential backoff everywhere) but lacks circuit breaker (the 'stop trying' mechanism for prolonged outages)."

### Q: "How does the circuit breaker interact with your health checks?"

> "They serve complementary purposes at different levels:
> - **Circuit breaker** (application level): stops calling ONE failing dependency. Other dependencies still work.
> - **Health check** (infrastructure level): ALB checks if the ENTIRE service is healthy. If the service itself is down, ALB removes it.
>
> Our `management.health.redis.enabled=false` is the link: we tell the health check 'don't fail because Redis is down' because we have try-catch fallbacks for Redis. If we added a Resilience4j circuit breaker on Eventtia, we'd similarly NOT include Eventtia in the health check — because the circuit breaker handles Eventtia failures gracefully with fallbacks."

---
---

# Topic 18: Connection Pooling

> Reuse database connections instead of creating new ones per request — reduces latency and prevents resource exhaustion.

> **Interview Tip:** Mention specific configs — "I'd configure HikariCP with min=5, max=20 connections, matching our expected concurrency, with 30-second idle timeout."

---

## The Problem: Connections Are Expensive

Creating a new connection for every request involves TCP handshake, TLS negotiation, authentication, and memory allocation. At scale, this destroys performance.

```
┌──────────────────────────────────────────────────────────────────────┐
│                       CONNECTION POOLING                             │
│                                                                      │
│  PROBLEM: Creating a new DB connection is expensive                 │
│  (TCP handshake, auth, memory)                                      │
│  Without pooling: new connection per request = slow, exhaustion     │
│                                                                      │
│  WITHOUT POOL:                     WITH CONNECTION POOL:            │
│                                                                      │
│  ┌─────┐  new conn  ┌────────┐    ┌─────┐         ┌──────┐ ┌────┐│
│  │Req 1│────────────▶│Database│    │Req 1│──┐      │ Pool │─│ DB ││
│  └─────┘             │        │    └─────┘  │      │ ■■■■ │ │10  ││
│  ┌─────┐  new conn   │Over-   │    ┌─────┐  ├─────▶│ ■■   │ │conn││
│  │Req 2│────────────▶│whelmed │    │Req 2│──┤      │Reuse!│ │    ││
│  └─────┘             │100+    │    └─────┘  │      └──────┘ └────┘│
│  ┌─────┐  new conn   │conns   │    ┌─────┐  │                     │
│  │Req 3│────────────▶│        │    │Req 3│──┘                     │
│  └─────┘             └────────┘    └─────┘                         │
│                                                                      │
│  Cost per new connection:          Cost with pool:                  │
│  ~5-50ms (TCP + TLS + auth)        ~0.1ms (grab from pool)        │
│  100 connections × 50ms = 5s       100 requests reuse 10 conns     │
│  wasted per second                 → 50x faster                    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Pool Configuration Parameters

```
┌──────────────────────────────────────────────────────────────────────┐
│  POOL CONFIGURATION                                                  │
│                                                                      │
│  ┌───────────────────┬───────────────────────────────────────────┐  │
│  │  Parameter         │  What It Controls                         │  │
│  ├───────────────────┼───────────────────────────────────────────┤  │
│  │  Min size          │  Keep warm connections ready              │  │
│  │  (e.g., 5)         │  Avoids cold-start on first requests     │  │
│  ├───────────────────┼───────────────────────────────────────────┤  │
│  │  Max size          │  Prevent DB overload                     │  │
│  │  (e.g., 20)        │  Requests queue when pool exhausted     │  │
│  ├───────────────────┼───────────────────────────────────────────┤  │
│  │  Idle timeout      │  Close unused connections                │  │
│  │  (e.g., 30s)       │  Free resources when traffic drops      │  │
│  ├───────────────────┼───────────────────────────────────────────┤  │
│  │  Max lifetime      │  Replace old connections                 │  │
│  │  (e.g., 30 min)    │  Prevent stale/leaked connections       │  │
│  ├───────────────────┼───────────────────────────────────────────┤  │
│  │  Connection timeout│  Max wait for a connection from pool     │  │
│  │  (e.g., 5s)        │  Fail fast when pool is exhausted       │  │
│  └───────────────────┴───────────────────────────────────────────┘  │
│                                                                      │
│  FORMULA: max_connections = (cpu_cores × 2) + spindle_count         │
│  (For SSDs, spindle_count = 0, so max ≈ cores × 2)                 │
│                                                                      │
│  TOOLS: HikariCP (Java), pgBouncer (PostgreSQL), PgPool             │
│                                                                      │
│  WARNING: Too many connections can HURT performance!                │
│  100 connections × context switching overhead > 20 connections       │
│  doing actual work. More connections ≠ more throughput.             │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Types of Connection Pooling

```
┌──────────────────────────────────────────────────────────────────────┐
│  CONNECTION POOLING TYPES                                            │
│                                                                      │
│  1. DATABASE CONNECTION POOL (HikariCP, pgBouncer)                  │
│     Application ──▶ Pool ──▶ PostgreSQL/MySQL                       │
│     Pool holds N open TCP connections to the DB.                    │
│     Requests borrow a connection, use it, return it.                │
│                                                                      │
│  2. HTTP CONNECTION POOL (Apache HttpClient, OkHttp, Netty)         │
│     Application ──▶ Pool ──▶ External API (Eventtia, Partner API)   │
│     Pool holds N open TCP/TLS connections to the API host.          │
│     Avoids TCP handshake + TLS negotiation per request.             │
│                                                                      │
│  3. REDIS CONNECTION (Lettuce multiplexed / Jedis pool)             │
│     Application ──▶ Connection ──▶ Redis                            │
│     Lettuce: SINGLE multiplexed connection (one TCP, many commands) │
│     Jedis: Traditional pool (N connections, borrow/return)          │
│                                                                      │
│  4. THREAD POOL (ExecutorService, ForkJoinPool)                     │
│     Not a "connection" pool, but same pattern.                      │
│     Pool of worker threads reused across tasks.                     │
│     Avoids creating/destroying threads per request.                 │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Connection Pooling In My CXP Projects

### The CXP Connection Pool Map

Our platform relies heavily on **library defaults** — which is a deliberate choice in a cloud-native, horizontally-scaled architecture. Instead of tuning one big pool on one big server, we run many small ECS tasks each with default pools.

```
┌──────────────────────────────────────────────────────────────────────────┐
│              CXP PLATFORM — CONNECTION POOL MAP                           │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  REDIS (Lettuce) — Single Multiplexed Connection                  │  │
│  │  No pool needed. One TCP connection handles thousands of          │  │
│  │  concurrent commands via pipelining.                              │  │
│  │  Command timeout: 2 seconds.                                     │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  HTTP (Apache/OkHttp/Netty) — Library Default Pools               │  │
│  │  Apache HttpClient: ~20 max total, 2 per route (defaults)       │  │
│  │  OkHttp (Retrofit): 5 idle connections, 5 min keep-alive        │  │
│  │  Reactor Netty (WebClient): 500 max, elastic                    │  │
│  │  No explicit tuning in our code.                                 │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Elasticsearch — Library Default Pool                             │  │
│  │  RestHighLevelClient: Apache HttpAsync defaults                  │  │
│  │  No explicit maxConnTotal/maxConnPerRoute.                       │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  DynamoDB / SQS — AWS SDK Default Pools                           │  │
│  │  AWS SDK v2 default: ~50 max connections per client.             │  │
│  │  No explicit tuning in our DynamoDbConfig.                       │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  THREAD POOLS                                                     │  │
│  │  Rise GTS: ForkJoinPool(500) for parallel transforms.           │  │
│  │  Others: ForkJoinPool.commonPool() + Schedulers.boundedElastic() │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: Redis Lettuce — Multiplexed Connection (No Pool Needed)

**Service:** `cxp-event-registration`
**Pattern:** Lettuce uses a **single multiplexed TCP connection** — NOT a connection pool

```
┌──────────────────────────────────────────────────────────────────────┐
│  Lettuce vs Jedis — Two Different Redis Connection Models            │
│                                                                      │
│  JEDIS (traditional pool):           LETTUCE (our choice):          │
│  ┌─────┐  borrow   ┌──────┐         ┌─────┐         ┌──────┐      │
│  │Req 1│──────────▶│Pool  │         │Req 1│──┐      │Single│      │
│  └─────┘  return   │ C1   │         └─────┘  │      │ TCP  │      │
│  ┌─────┐──────────▶│ C2   │──▶Redis ┌─────┐  ├─────▶│ Conn │──▶Redis
│  │Req 2│  borrow   │ C3   │         │Req 2│──┤      │      │      │
│  └─────┘  return   │ C4   │         └─────┘  │      │Multi-│      │
│  ┌─────┐──────────▶│ C5   │         ┌─────┐  │      │plexed│      │
│  │Req 3│           └──────┘         │Req 3│──┘      └──────┘      │
│  └─────┘                            └─────┘                         │
│                                                                      │
│  Jedis: N connections, one per                                      │
│  concurrent request. Pool manages                                   │
│  borrow/return. Blocking if full.   Lettuce: ONE connection,        │
│                                     thousands of concurrent         │
│  Problem: 8 ECS tasks × 20 pool    commands via Redis protocol     │
│  = 160 connections to Redis.        pipelining (non-blocking).      │
│  ElastiCache limit: ~65K, but       8 ECS tasks × 1 conn = 8      │
│  too many = context switching.      connections total. Minimal.     │
│                                                                      │
│  WHY LETTUCE FOR CXP:                                               │
│  - Spring Boot default since 2.x                                    │
│  - Non-blocking (works with WebFlux reactive stack)                 │
│  - No pool exhaustion risk (single conn, infinite concurrency)     │
│  - Simpler config (no min/max/idle to tune)                        │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// ReactiveRedisConfig.java — NO connection pool configured
var clientConfig = LettuceClientConfiguration.builder()
        .commandTimeout(Duration.ofSeconds(2L))        // timeout per command
        .readFrom(ReadFrom.REPLICA_PREFERRED)           // read routing
        .build();
// No .pool() or LettucePoolingClientConfiguration
// → Lettuce uses SINGLE multiplexed connection (default)

var configuration = new RedisStaticMasterReplicaConfiguration(
    primaryHostAndPort.getHost(), primaryHostAndPort.getPort());
replicas.forEach(replica ->
    configuration.addNode(replicaHostAndPort.getHost(), replicaHostAndPort.getPort()));

return new LettuceConnectionFactory(configuration, clientConfig);
```

**Interview answer:**
> "Our Redis connection uses Lettuce's single multiplexed TCP connection — not a pool. One TCP connection handles thousands of concurrent commands via Redis protocol pipelining. This means 8 ECS tasks create only 8 connections to Redis total, not 160 (8 tasks × 20-connection pool). Lettuce is the Spring Boot default since 2.x and fits our WebFlux reactive stack perfectly — non-blocking I/O, no pool exhaustion risk. If we ever needed connection pooling (for Jedis or blocking operations), we'd configure `LettucePoolingClientConfiguration` with `GenericObjectPoolConfig` — but our workload doesn't require it."

---

### Example 2: HTTP Client Pools — Library Defaults

**Services:** All CXP services calling external APIs (Eventtia, Partner API, Akamai, OSCAR)

```
┌──────────────────────────────────────────────────────────────────────┐
│  HTTP Connection Pools — Default Settings Across CXP                 │
│                                                                      │
│  ┌────────────────────┬──────────────────────┬───────────────────┐ │
│  │  Library            │  Default Pool         │  Used By          │ │
│  ├────────────────────┼──────────────────────┼───────────────────┤ │
│  │  Apache HttpClient  │  max 20 total         │  expviewsnikeapp  │ │
│  │  (RestTemplate)     │  max 2 per route      │  (RestTemplate)   │ │
│  │                     │  keep-alive: reuse    │                   │ │
│  ├────────────────────┼──────────────────────┼───────────────────┤ │
│  │  OkHttp             │  5 idle connections   │  cxp-event-reg   │ │
│  │  (Retrofit)         │  5 min keep-alive     │  cxp-events      │ │
│  │                     │  no max total limit   │  (Retrofit APIs)  │ │
│  ├────────────────────┼──────────────────────┼───────────────────┤ │
│  │  Reactor Netty      │  500 max connections  │  expviewsnikeapp  │ │
│  │  (WebClient)        │  elastic per host     │  (WebClient)      │ │
│  │                     │  45s idle timeout     │                   │ │
│  ├────────────────────┼──────────────────────┼───────────────────┤ │
│  │  AWS SDK v2         │  ~50 max connections  │  DynamoDB, SQS,  │ │
│  │  (HTTP client)      │  per client           │  Athena           │ │
│  └────────────────────┴──────────────────────┴───────────────────┘ │
│                                                                      │
│  WHY DEFAULTS ARE OK FOR CXP:                                       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Traditional approach: 1 big server, 1 big pool (HikariCP  │    │
│  │  max=50). Tune the pool to match server capacity.           │    │
│  │                                                              │    │
│  │  Our approach: 8 small ECS tasks, each with default pool.   │    │
│  │  8 tasks × 20 connections each = 160 total to Eventtia.    │    │
│  │  If we need more throughput → add more tasks (horizontal).  │    │
│  │  No pool tuning needed — scaling handles it.               │    │
│  │                                                              │    │
│  │  WHEN TO TUNE:                                               │    │
│  │  - If a SINGLE task needs >20 concurrent API calls           │    │
│  │  - If connection setup latency is significant (TLS to slow   │    │
│  │    external API)                                             │    │
│  │  - If you see "connection pool exhausted" errors in logs     │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — Apache HttpClient (no explicit pool):**

```java
// RestTemplateConfiguration.java — pool is implicitly created by HttpClientBuilder
CloseableHttpClient client = HttpClientBuilder
    .create()
    .setDefaultRequestConfig(config)   // timeouts only
    .build();
// HttpClientBuilder.create() internally creates PoolingHttpClientConnectionManager
// with defaults: maxTotal=20, defaultMaxPerRoute=2
// No .setMaxConnTotal() or .setMaxConnPerRoute() overrides
```

**From the actual code — OkHttp/Retrofit (no explicit pool):**

```java
// RetrofitUtil.java — no ConnectionPool configuration
OkHttpClient.Builder builder = new OkHttpClient.Builder()
    .connectTimeout(config.connectionTimeout, TimeUnit.SECONDS)
    .readTimeout(config.readerTimeout, TimeUnit.SECONDS)
    .writeTimeout(config.WRITER_TIMEOUT, TimeUnit.SECONDS);
// OkHttp default: ConnectionPool(5 idle, 5 min keep-alive)
// No .connectionPool(new ConnectionPool(...)) override
```

---

### Example 3: Thread Pools — Explicit and Implicit

**Service:** Rise GTS (explicit), all others (implicit defaults)

```
┌──────────────────────────────────────────────────────────────────────┐
│  Thread Pools in CXP — The Connection Pool for CPU Work              │
│                                                                      │
│  ┌──────────────────────┬──────────────┬─────────────────────────┐ │
│  │  Pool                 │  Size        │  Used For                │ │
│  ├──────────────────────┼──────────────┼─────────────────────────┤ │
│  │  ForkJoinPool(500)    │  500 threads │  Rise GTS: parallel     │ │
│  │  (Explicit)           │              │  transform & publish    │ │
│  ├──────────────────────┼──────────────┼─────────────────────────┤ │
│  │  ForkJoinPool         │  CPU cores   │  CompletableFuture      │ │
│  │  .commonPool()        │  (default)   │  .runAsync() in         │ │
│  │  (Implicit)           │              │  cxp-events, cxp-reg    │ │
│  │                       │              │  (cache writes, purges) │ │
│  ├──────────────────────┼──────────────┼─────────────────────────┤ │
│  │  Schedulers           │  Elastic     │  WebFlux blocking calls │ │
│  │  .boundedElastic()    │  (10 × cores │  in cxp-events,        │ │
│  │  (Implicit)           │   max 100K)  │  cxp-reg               │ │
│  ├──────────────────────┼──────────────┼─────────────────────────┤ │
│  │  SQS Listener         │  Max 5 msgs  │  Rise GTS: SQS message │ │
│  │  Container             │  concurrent  │  processing             │ │
│  └──────────────────────┴──────────────┴─────────────────────────┘ │
│                                                                      │
│  Rise GTS uses 500 threads because each transform involves:        │
│  - S3 read (I/O bound, thread blocks)                              │
│  - HTTP call to NCP/NSPv2 (I/O bound)                              │
│  - S3 write (I/O bound)                                            │
│  500 threads keeps I/O-bound work concurrent while CPUs are idle.  │
│                                                                      │
│  CompletableFuture.runAsync() uses commonPool (CPU cores) because: │
│  - Tasks are small (Redis SET, Akamai purge HTTP call)             │
│  - Fire-and-forget (caller doesn't wait for result)                │
│  - If commonPool is full, tasks queue (acceptable for non-critical │
│    cache writes)                                                    │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// Rise GTS — explicit 500-thread pool for I/O-heavy transforms
private static final int MAX_THREADS = 500;

@Bean
public ExecutorService executorService() {
    return new ExecutorServiceWithTracing(new ForkJoinPool(MAX_THREADS));
}

// Used for parallel publish after unbatching transforms:
.map(result -> Pair.of(result, executorService.submit(() -> {
    validateAndPublish(transformation, result);
    return true;
})))
```

```java
// cxp-event-registration — fire-and-forget on default pool
CompletableFuture.runAsync(() ->
    registrationCacheService.addRegistrationRequestSuccessResponseToCache(
        idempotencyKey, response));
// Uses ForkJoinPool.commonPool() — no explicit executor
// Fine for non-critical cache write that caller doesn't await
```

---

### Example 4: Why No HikariCP (No Relational Database)

```
┌──────────────────────────────────────────────────────────────────────┐
│  WHY NO DATABASE CONNECTION POOL IN CXP                              │
│                                                                      │
│  The most common connection pool topic (HikariCP, pgBouncer) is     │
│  for RELATIONAL databases. CXP has NO relational database:          │
│                                                                      │
│  ┌───────────────────┬────────────────────────────────────────┐    │
│  │  Technology        │  Connection Model                      │    │
│  ├───────────────────┼────────────────────────────────────────┤    │
│  │  Redis (Lettuce)   │  Single multiplexed connection (no pool)│   │
│  │  DynamoDB (SDK)    │  HTTP-based, SDK manages pool internally│   │
│  │  Elasticsearch     │  HTTP-based, RestClient manages pool    │   │
│  │  SQS (SDK)         │  HTTP-based, SDK manages pool internally│   │
│  │  S3 (SDK)          │  HTTP-based, SDK manages pool internally│   │
│  │  Eventtia (HTTP)   │  Retrofit/OkHttp connection pool        │   │
│  └───────────────────┴────────────────────────────────────────┘    │
│                                                                      │
│  ALL our connections are HTTP-based (REST APIs) or Redis —          │
│  not JDBC. The HTTP client libraries (OkHttp, Apache, Netty)       │
│  handle connection reuse internally.                                │
│                                                                      │
│  IF we had PostgreSQL, we'd use:                                    │
│  HikariCP (Spring Boot default): min=5, max=20, idle-timeout=30s   │
│  Formula: max = (cores × 2) + effective_spindle_count              │
│  For ECS task with 2 vCPU: max = (2×2) + 0 = 4 connections        │
│  8 tasks × 4 connections = 32 total PostgreSQL connections          │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Connection Pooling Across CXP

| Component | Pool Type | Pool Size | Managed By | Explicit Config? |
|-----------|----------|-----------|-----------|-----------------|
| **Redis** | Multiplexed single conn | 1 per factory | Lettuce (Netty) | No (default, correct for Lettuce) |
| **Eventtia API** | HTTP connection pool | 5 idle (OkHttp) | OkHttp/Retrofit | No (library defaults) |
| **Partner API** | HTTP connection pool | 5 idle (OkHttp) | OkHttp/Retrofit | No (library defaults) |
| **Elasticsearch** | HTTP connection pool | Apache defaults | RestHighLevelClient | No (library defaults) |
| **DynamoDB** | HTTP connection pool | ~50 max (SDK) | AWS SDK v2 | No (SDK defaults) |
| **SQS** | HTTP connection pool | SDK default | AWS SDK v1 | No (SDK defaults) |
| **Rise GTS threads** | ForkJoinPool | 500 threads | Explicit bean | **Yes** — `new ForkJoinPool(500)` |
| **Async cache ops** | ForkJoinPool | CPU cores | `commonPool()` | No (JVM default) |
| **WebFlux blocking** | Elastic scheduler | 10×cores, max 100K | `Schedulers.boundedElastic()` | No (framework default) |

---

## Common Interview Follow-ups

### Q: "Why don't you explicitly configure connection pool sizes?"

> "Our architecture favors horizontal scaling over pool tuning. Instead of 1 server with a 50-connection pool, we run 8 ECS tasks each with default pools. If we need more throughput, we add tasks (horizontal scaling from Topic 15), not bigger pools. This approach has three advantages: (1) no pool tuning per environment, (2) each task is small and disposable, (3) connection count scales linearly with tasks. The only explicit pool is Rise GTS's 500-thread ForkJoinPool because S3/HTTP I/O-bound transforms need high concurrency within a single task."

### Q: "When WOULD you tune connection pools?"

> "Three signals: (1) **Connection timeout errors** in logs — 'unable to acquire connection within timeout' means pool max is too small. (2) **High connection creation rate** — if CloudWatch shows frequent TCP connection establishes to the same host, the pool isn't keeping connections alive long enough. (3) **Database connection limit hit** — if we added PostgreSQL with a 100-connection limit, we'd MUST tune: 8 tasks × 20 default = 160, exceeding the limit. I'd set max=10 per task to stay under 80 total (20 headroom for connection churn)."

### Q: "What's the advantage of Lettuce multiplexing over Jedis pooling?"

> "Lettuce sends multiple commands over ONE TCP connection using Redis pipelining — no borrow/return overhead, no pool exhaustion risk, no connection limit concern. Jedis uses a traditional pool: borrow connection → execute command → return connection. With 8 ECS tasks and Jedis pool of 20, that's 160 TCP connections to Redis. With Lettuce, it's 8 total. Lettuce also works natively with WebFlux (non-blocking I/O), while Jedis is blocking. The tradeoff: Lettuce's single connection can become a bottleneck for extremely high-throughput scenarios (>100K ops/sec per task), but our registration service is well under that."

### Q: "If you added PostgreSQL to the platform, how would you size the pool?"

> "Using HikariCP (Spring Boot default):
> - **Formula:** `max_pool = (cores × 2) + effective_spindle_count`
> - **For our ECS tasks (2 vCPU each):** max = (2×2) + 0 = 4 per task
> - **8 tasks total:** 8 × 4 = 32 connections to PostgreSQL
> - **PostgreSQL default max:** 100 connections → 32 is well under limit
> - **Settings:** `minimumIdle=2` (warm connections ready), `maximumPoolSize=4`, `idleTimeout=30s`, `connectionTimeout=5s` (fail fast if pool exhausted), `maxLifetime=30min` (prevent stale connections)
>
> If we scaled to 20 tasks: 20 × 4 = 80 connections. Getting close to PostgreSQL's 100 limit. At that point, I'd add pgBouncer as a connection multiplexer between ECS tasks and PostgreSQL — pgBouncer holds a smaller pool to the DB and multiplexes requests from all tasks."

---
---

# Topic 19: Stateless vs Stateful

> Stateless servers don't store session data (easy to scale, any server works); stateful servers remember state (lower latency, harder to scale).

> **Interview Tip:** Design for stateless — "I'd make all API servers stateless, storing sessions in Redis, so we can auto-scale and any server can handle any request."

---

## The Core Difference

```
┌──────────────────────────────────────────────────────────────────────┐
│                                                                      │
│  STATELESS                            STATEFUL                      │
│  Server doesn't remember              Server remembers client       │
│  previous requests.                   session/state.                │
│                                                                      │
│  ┌────────┐  any    ┌────────┐       ┌────────┐ STICKY ┌────────┐ │
│  │ Client │──server─▶│Server 1│       │ Client │───────▶│Server 1│ │
│  │        │         ├────────┤       │        │  must   │Session │ │
│  │        │         │Server 2│       │        │  go to  │ Data   │ │
│  │        │         ├────────┤       └────────┘  same   └────────┘ │
│  └────────┘  ┌──────│Server 3│                  server   Server 2  │
│              │      └────────┘                          (idle)     │
│              ▼                                                      │
│        ┌──────────┐                                                │
│        │ External │                                                │
│        │  State   │                                                │
│        │ Redis/DB │                                                │
│        └──────────┘                                                │
│                                                                      │
│  [+] Easy horizontal scaling       [+] No external state store     │
│  [+] Any server can handle         [+] Lower latency (local data) │
│      any request                   [+] Simpler for WebSocket/      │
│  [+] Simple load balancing              gaming                     │
│  [+] Easy to replace failed                                        │
│      servers                       [-] Sticky sessions required    │
│                                    [-] Server failure = lost        │
│  [-] Need external state store         sessions                    │
│  [-] More network calls            [-] Harder to scale             │
│                                                                      │
│  Best for: REST APIs,              Best for: WebSocket, gaming,    │
│  microservices, web servers         real-time apps                  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## The Statelessness Test

A service is stateless if it passes ALL of these checks:

```
┌──────────────────────────────────────────────────────────────────────┐
│  STATELESSNESS CHECKLIST                                             │
│                                                                      │
│  ✓  No in-memory user sessions (no HttpSession, no session map)    │
│  ✓  No local file writes that other requests depend on              │
│  ✓  No in-memory caches that MUST be shared across instances       │
│  ✓  No thread-local state that persists across requests            │
│  ✓  Any instance can handle any request (no sticky sessions)       │
│  ✓  Instance can be killed and replaced without data loss          │
│  ✓  Two identical requests can hit different instances and get      │
│     the same result                                                 │
│                                                                      │
│  If ANY check fails → your service is STATEFUL (or partly so).     │
│  Stateful doesn't mean broken — it means harder to scale.          │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Stateless vs Stateful In My CXP Projects

### The CXP Platform — Stateless by Design

Every CXP microservice is **stateless**. All persistent state is externalized to managed services (Redis, DynamoDB, Eventtia, S3). This is the foundation that enables horizontal scaling, auto-recovery, and zero-downtime deployments.

```
┌──────────────────────────────────────────────────────────────────────────┐
│              CXP PLATFORM — WHERE STATE LIVES                             │
│                                                                          │
│  STATELESS (application layer):         STATEFUL (data layer):          │
│  ──────────────────────────────         ────────────────────────        │
│                                                                          │
│  ┌──────────────────┐                   ┌──────────────────┐           │
│  │  cxp-events       │──state──────────▶│  Eventtia API    │           │
│  │  (ECS Task ×N)    │  events, seats   │  (source of truth)│           │
│  └──────────────────┘                   └──────────────────┘           │
│                                                                          │
│  ┌──────────────────┐                   ┌──────────────────┐           │
│  │  cxp-event-       │──idempotency───▶│  Redis            │           │
│  │  registration     │  cache          │  (ElastiCache)    │           │
│  │  (ECS Task ×N)    │──queue─────────▶│  DynamoDB          │           │
│  │                   │──pairwise──────▶│  Redis             │           │
│  │                   │──registration──▶│  Eventtia API      │           │
│  └──────────────────┘                   └──────────────────┘           │
│                                                                          │
│  ┌──────────────────┐                   ┌──────────────────┐           │
│  │  expviewsnikeapp  │──search────────▶│  Elasticsearch    │           │
│  │  (ECS Task ×N)    │                  │  (AWS managed)    │           │
│  └──────────────────┘                   └──────────────────┘           │
│                                                                          │
│  ┌──────────────────┐                   ┌──────────────────┐           │
│  │  Rise GTS         │──input/output──▶│  S3 + SQS         │           │
│  │  (ECS Task ×N)    │──publish──────▶│  NSPv2/Kafka       │           │
│  └──────────────────┘                   └──────────────────┘           │
│                                                                          │
│  LEFT SIDE: Stateless. Can be killed, replaced, scaled freely.         │
│  RIGHT SIDE: Stateful. Managed services that persist data.             │
│  The line between them = external API calls + Redis/DB operations.      │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: cxp-event-registration — The Statelessness Proof

Let me walk through the checklist against the actual code:

```
┌──────────────────────────────────────────────────────────────────────┐
│  STATELESSNESS PROOF: cxp-event-registration                         │
│                                                                      │
│  CHECK                          │  EVIDENCE FROM CODE               │
│  ───────────────────────────────┼──────────────────────────────────│
│  ✓ No in-memory sessions        │  No HttpSession, no @Session-    │
│                                  │  Scoped beans. JWT bearer token  │
│                                  │  carries auth (validated per     │
│                                  │  request by AAAConfig.java).     │
│  ───────────────────────────────┼──────────────────────────────────│
│  ✓ No local file writes         │  Logs go to Splunk (stdout→      │
│                                  │  Docker→Kinesis→Splunk). No      │
│                                  │  local file I/O.                 │
│  ───────────────────────────────┼──────────────────────────────────│
│  ✓ No shared in-memory cache    │  All cache in Redis (external).  │
│                                  │  No static Map<> or ConcurrentHash│
│                                  │  Map holding user data.           │
│  ───────────────────────────────┼──────────────────────────────────│
│  ✓ No thread-local state         │  ThreadContext used for logging  │
│    across requests               │  only (cleared per request via   │
│                                  │  ThreadContext.putAll(context)). │
│  ───────────────────────────────┼──────────────────────────────────│
│  ✓ Any task handles any request  │  ALB round-robins without sticky │
│                                  │  sessions. No session affinity.  │
│  ───────────────────────────────┼──────────────────────────────────│
│  ✓ Task can be killed safely     │  ECS replaces from Docker image. │
│                                  │  In-flight CompletableFuture     │
│                                  │  (cache write) lost = acceptable │
│                                  │  (Topic 11: write-behind tradeoff)│
│  ───────────────────────────────┼──────────────────────────────────│
│  ✓ Same request, different task  │  Both tasks read same Redis key, │
│    = same result                 │  call same Eventtia API, write   │
│                                  │  same DynamoDB table.            │
└──────────────────────────────────┘──────────────────────────────────┘
```

**Authentication is stateless (JWT, not sessions):**

```java
// AAAConfig.java — JWT validated per-request, not session-based
// Token is in the Authorization header: "Bearer <JWT>"
// Server validates signature using public keys from S3
// No server-side session created — the token IS the session

// JwtScope annotation validates OAuth scope per endpoint:
@JwtScope(EVENTS_PURGE_CACHE_DELETE_SCOPE)
public ResponseEntity<Void> purgeCache(...) { ... }
```

**Every piece of state externalized:**

```java
// IDEMPOTENCY → Redis (not JVM memory)
redisTemplate.opsForValue().set(idempotencyKey + SUFFIX, value, Duration.ofMinutes(60));

// FAILED REGISTRATIONS → DynamoDB (not local queue)
dynamoDbTable.putItem(request);

// PAIRWISE IDS → Redis (not in-memory map)
redisTemplate.opsForValue().set(upmId + PAIRWISE_KEY_SUFFIX, details, Duration.ofDays(30));

// USER REGISTRATION → Eventtia API (not local DB)
eventtiaRegistrationApi.registerAttendee(token, eventId, request);

// FEATURE FLAG → Secrets Manager (not config file)
CXPFeatureFlag flag = objectMapper.readValue(secret, CXPFeatureFlag.class);
```

---

### Example 2: Caffeine Cache — Stateful Component Inside a Stateless Service

**Service:** `cxp-events`
**The nuance:** Caffeine caches ARE in-memory state, but designed to be **independently rebuildable per task**.

```
┌──────────────────────────────────────────────────────────────────────┐
│  Caffeine Cache — Stateful or Stateless?                             │
│                                                                      │
│  TECHNICALLY: Caffeine is IN-MEMORY state → stateful characteristic │
│  PRACTICALLY: Each task has its OWN cache → no shared state         │
│                                                                      │
│  Task 1: Caffeine { NYC→data, London→data, Tokyo→data }            │
│  Task 2: Caffeine { NYC→data, Paris→data }  (different cache!)     │
│  Task 3: Caffeine { }  (just started, cache empty)                  │
│                                                                      │
│  WHY THIS IS STILL "STATELESS ENOUGH":                              │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  1. Cache is OPTIONAL — on miss, service calls source API   │    │
│  │  2. Cache is REBUILDABLE — new task warms up in minutes     │    │
│  │  3. Cache is NOT SHARED — no cross-task consistency needed  │    │
│  │  4. Cache loss ≠ data loss — it's an optimization, not      │    │
│  │     a data store                                             │    │
│  │  5. Different tasks having different cache content is OK —  │    │
│  │     they return the same CORRECT data (just maybe slower    │    │
│  │     on a miss)                                               │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  InternalEventsCacheService REBUILDS every 15 minutes:              │
│  @Scheduled(fixedRate = 900000)                                     │
│  public void refreshInternalEventsCache() {                         │
│      internalEventCache.invalidateAll();                            │
│      // fetch ALL events from Eventtia → rebuild cache             │
│  }                                                                   │
│  → Even a new task with empty cache is fully warmed within 15 min.  │
│                                                                      │
│  CONTRAST WITH TRULY STATEFUL:                                      │
│  If Caffeine held USER-SPECIFIC data (shopping cart, session):      │
│  → Killing Task 1 = losing User A's cart (data loss!)              │
│  → Task 2 doesn't have User A's data (inconsistency!)              │
│  → Would need sticky sessions → defeats load balancing              │
│                                                                      │
│  Our Caffeine holds REFERENCE DATA (cities, translations, events)  │
│  → Same for all users. Any task can rebuild it. No user data lost. │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 3: Rise GTS — Stateless with a Large Thread Pool

**Service:** `rise-generic-transform-service`
**Pattern:** Stateless processing worker with 500-thread pool

```
┌──────────────────────────────────────────────────────────────────────┐
│  Rise GTS — Stateless Event Processing                               │
│                                                                      │
│  Each message from SQS is processed independently:                  │
│                                                                      │
│  SQS message arrives                                                │
│       │                                                              │
│       ▼                                                              │
│  1. Parse S3EventNotification from SQS payload                      │
│  2. Read JSON from S3 (input)                                       │
│  3. Transform JSON (CPU work)                                       │
│  4. Write result to S3 and/or POST to NCP/NSPv2 (output)          │
│  5. Delete SQS message (acknowledge)                                │
│                                                                      │
│  STATELESSNESS:                                                     │
│  - No state carried between messages                                │
│  - Input = S3 object (external)                                     │
│  - Output = S3 + HTTP POST (external)                               │
│  - Config loaded from S3/classpath JSON files (external)            │
│  - Guava cache (RESPONSE_CACHE, STOREVIEWS_CACHE) = optional,      │
│    rebuildable on miss                                              │
│  - 500-thread pool is a RESOURCE, not state                        │
│                                                                      │
│  If ECS kills this task mid-transform:                              │
│  - SQS message becomes visible again after VisibilityTimeout       │
│  - Another task picks it up and reprocesses                         │
│  - ZERO data loss (SQS = durable queue + at-least-once delivery)   │
│                                                                      │
│  This is the IDEAL stateless worker pattern:                        │
│  Input queue (SQS) → stateless processor → output store (S3/HTTP)  │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 4: What WOULD Be Stateful (and How We Avoid It)

```
┌──────────────────────────────────────────────────────────────────────┐
│  STATEFUL ANTI-PATTERNS WE AVOIDED                                   │
│                                                                      │
│  ANTI-PATTERN 1: In-memory session store                            │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  // BAD: session stored in JVM                              │    │
│  │  HttpSession session = request.getSession();                │    │
│  │  session.setAttribute("registrationId", "12345");           │    │
│  │  // Request 2 hits different task → session NOT found!      │    │
│  │                                                              │    │
│  │  // GOOD: what we do — JWT token carries identity           │    │
│  │  String upmId = extractFromJwt(token); // stateless         │    │
│  │  // Any task can validate this token identically            │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  ANTI-PATTERN 2: Local file as database                             │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  // BAD: writing to local disk                              │    │
│  │  Files.write(Paths.get("/tmp/registrations.json"), data);   │    │
│  │  // Task dies → data lost. Other tasks can't read it.       │    │
│  │                                                              │    │
│  │  // GOOD: what we do — write to DynamoDB                    │    │
│  │  dynamoDbTable.putItem(request); // external, durable       │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  ANTI-PATTERN 3: Shared in-memory counter                           │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  // BAD: static counter across requests                     │    │
│  │  private static AtomicInteger failureCount = new AtomicInt();│   │
│  │  failureCount.incrementAndGet();                            │    │
│  │  // Each task has its own counter → inaccurate total!       │    │
│  │                                                              │    │
│  │  // GOOD: what we do — counter in Redis                     │    │
│  │  redisTemplate.opsForValue().set(key + FAILURE_COUNTER_SUFFIX, │ │
│  │      counter + 1, Duration.ofMinutes(1));                   │    │
│  │  // All tasks share one counter → accurate across cluster   │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  ANTI-PATTERN 4: WebSocket with in-memory connection map            │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  // BAD (if we had real-time features):                     │    │
│  │  Map<String, WebSocketSession> sessions = new HashMap<>();  │    │
│  │  // Task restart → all WebSocket connections lost            │    │
│  │                                                              │    │
│  │  // GOOD alternative: use managed WebSocket service          │    │
│  │  // (API Gateway WebSocket, AWS AppSync, Pusher)             │    │
│  │  // CXP doesn't need WebSocket → not applicable.            │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 5: The One "Stateful" Component — `cacheBasedBotProtectionFlag`

```
┌──────────────────────────────────────────────────────────────────────┐
│  THE EDGE CASE: Static Mutable Field                                 │
│                                                                      │
│  public static boolean cacheBasedBotProtectionFlag = false;         │
│                                                                      │
│  This IS in-JVM state that varies per task. If Secrets Manager      │
│  updates the flag, tasks read it at different times:                │
│                                                                      │
│  Task 1: flag = true  (read secret at T=0)                          │
│  Task 2: flag = false (hasn't read secret yet)                      │
│  Task 3: flag = true  (read secret at T=5s)                         │
│                                                                      │
│  For a brief window, tasks behave differently.                      │
│  This is a MINOR stateful leak:                                     │
│  - It's configuration state, not user state                         │
│  - It converges quickly (all tasks read within seconds)             │
│  - Different flag values don't cause data corruption                │
│  - Worst case: some requests bypass bot protection briefly          │
│                                                                      │
│  FULLY STATELESS ALTERNATIVE:                                       │
│  Read the flag from Redis on every request (adds ~1ms latency).    │
│  Or use a feature flag service (LaunchDarkly, AWS AppConfig)        │
│  that pushes changes to all instances simultaneously.              │
│                                                                      │
│  We accept the brief inconsistency because:                        │
│  - Bot protection is defense-in-depth (Eventtia also checks)       │
│  - Flag changes are rare (toggled during incidents, not per-request)│
│  - The 5-second convergence window is operationally acceptable     │
└──────────────────────────────────────────────────────────────────────┘
```

---

## How Statelessness Enables Key CXP Features

```
┌──────────────────────────────────────────────────────────────────────┐
│  WHAT STATELESSNESS UNLOCKS                                          │
│                                                                      │
│  1. AUTO-SCALING (Topic 15)                                         │
│     2 tasks → 8 tasks during sneaker launch → 2 tasks after.       │
│     New tasks start fresh — no state to warm up (except cache).     │
│     Old tasks killed — no state lost (data in Redis/DynamoDB).      │
│                                                                      │
│  2. ZERO-DOWNTIME DEPLOYMENTS                                       │
│     Rolling update: Task A (v1) killed, Task B (v2) starts.        │
│     No session migration needed.                                    │
│     ALB drains connections from old task, routes to new task.       │
│     Users don't notice the switch.                                  │
│                                                                      │
│  3. MULTI-REGION FAILOVER (Topic 14)                                │
│     Route53 switches from us-east-1 to us-west-2.                  │
│     us-west-2 tasks access same Redis, DynamoDB, Eventtia.          │
│     No session state stuck in us-east-1 tasks.                     │
│                                                                      │
│  4. SIMPLE LOAD BALANCING (Topic 14)                                │
│     ALB uses round-robin — no sticky sessions needed.               │
│     Any task handles any request. Optimal distribution.             │
│                                                                      │
│  5. FAULT TOLERANCE                                                  │
│     ECS task crashes → ALB routes to surviving tasks.               │
│     No user data lost. Replacement task starts in ~30 seconds.     │
│     SQS messages reappear for another task (Rise GTS).             │
│                                                                      │
│  6. COST OPTIMIZATION                                               │
│     Scale to zero in non-peak hours (if using Fargate Spot).       │
│     No state to preserve when scaling down.                        │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Stateless vs Stateful Across CXP

| Component | Stateless? | State Location | What Enables |
|-----------|-----------|---------------|-------------|
| **cxp-events** (ECS) | Yes | Eventtia API, Caffeine (rebuildable) | Auto-scale, rolling deploy, multi-region |
| **cxp-event-registration** (ECS) | Yes | Redis, DynamoDB, Eventtia | Auto-scale, ALB round-robin, fault tolerance |
| **expviewsnikeapp** (ECS) | Yes | Elasticsearch, Guava (rebuildable) | Auto-scale, rolling deploy |
| **Rise GTS** (ECS) | Yes | S3, SQS, NSPv2 | Kill-safe (SQS redelivers), parallel tasks |
| **Redis** (ElastiCache) | Stateful | In-memory + replication | Managed by AWS, persistent across task restarts |
| **DynamoDB** | Stateful | Disk + 3 AZ replication | Managed by AWS, durable, auto-scaled |
| **Elasticsearch** | Stateful | Disk + replica shards | Managed by AWS, persistent |
| **S3** | Stateful | Object store + 3 AZ | Managed by AWS, 11 nines durability |

**Pattern:** Stateless application layer + Stateful managed data layer. We write no state management code — AWS manages the stateful parts.

---

## Common Interview Follow-ups

### Q: "What if you need WebSocket or real-time features?"

> "WebSocket connections are inherently stateful — the TCP connection binds a client to a specific server. If CXP needed live seat count updates, I'd use AWS API Gateway WebSocket APIs (managed, serverless) or AWS AppSync (GraphQL subscriptions) instead of building WebSocket handling into our ECS tasks. This keeps our services stateless while the managed service handles the stateful WebSocket connections. Alternatively, Server-Sent Events (SSE) from behind a load balancer can work if each event is self-contained (no server-side session)."

### Q: "How do you handle user authentication without sessions?"

> "JWT bearer tokens. The user authenticates via `accounts.nike.com` (OAuth2 flow), receives a signed JWT containing their `prn` (user ID) and scopes. Every API request includes the JWT in the `Authorization: Bearer` header. Our `AAAConfig.java` validates the JWT signature using public keys cached from S3 — no server-side session lookup needed. The token IS the session. Any ECS task can validate any user's token identically. Token expiry handles logout (no server-side session to invalidate)."

### Q: "What about the Caffeine cache inconsistency between tasks?"

> "Each task has its own Caffeine cache, so different tasks may return slightly different cached data at any given moment. This is acceptable because: (1) the cached data is reference data (cities, translations, event listings), not user-specific data, (2) all tasks return CORRECT data (just some return it from cache, others from source), (3) the `@Scheduled` refresh ensures all caches converge within 15 minutes. If we needed cross-task cache consistency, we'd replace Caffeine with Redis. But the operational simplicity of per-task Caffeine (no cache coordination protocol) outweighs the brief inconsistency for reference data."

### Q: "What happens to in-flight CompletableFuture.runAsync tasks when a task is killed?"

> "They're lost. When ECS kills a task, any pending async cache writes (Redis SET, Akamai purge) in `CompletableFuture.runAsync()` are abandoned. This is acceptable because: (1) cache writes are fire-and-forget optimizations — the primary operation (Eventtia registration) already succeeded, (2) Redis TTL handles cleanup even if the write never happens, (3) Akamai cache expires via TTL anyway. The user already got their success response — the lost async write only means the NEXT identical request will miss cache and call Eventtia again (which is safe and idempotent)."
