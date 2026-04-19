# Topic 26: Idempotency

> Same request executed multiple times produces the same result — critical for safe retries in distributed systems with network failures.

> **Interview Tip:** Always design for it — "I'd require an idempotency key header for payment APIs, stored in Redis with 24-hour TTL, returning cached result on duplicate requests."

---

## What Is Idempotency?

An operation is **idempotent** if executing it once or N times produces the **same result**. In distributed systems where networks fail and clients retry, idempotency prevents duplicate side effects.

```
┌──────────────────────────────────────────────────────────────────────┐
│                         IDEMPOTENCY                                   │
│                                                                      │
│  Same request executed multiple times = same result as executing once│
│  Critical for handling retries, network failures, duplicate requests │
│                                                                      │
│  WITHOUT IDEMPOTENCY:             WITH IDEMPOTENCY KEY:             │
│                                                                      │
│  Client ──charge $100──▶ Payment  Client ──key=abc, $100──▶ Payment│
│  Client ──charge $100──▶ Payment  Client ──key=abc, $100──▶ Payment│
│  (retry: network timeout)         (retry: same key)                 │
│                                                                      │
│  Result: Charged $200!            Result: Charged $100              │
│  Network timeout caused           Server recognizes duplicate       │
│  retry = duplicate charge         by key, returns cached result     │
└──────────────────────────────────────────────────────────────────────┘
```

---

## HTTP Methods and Natural Idempotency

```
┌──────────────────────────────────────────────────────────────────────┐
│  HTTP METHOD IDEMPOTENCY                                             │
│                                                                      │
│  ┌──────────┬─────────────┬──────────────────────────────────────┐ │
│  │  Method   │  Idempotent? │  Why                                │ │
│  ├──────────┼─────────────┼──────────────────────────────────────┤ │
│  │  GET      │  ✓ Yes       │  Reading doesn't change state.      │ │
│  │           │              │  GET /event/73067 always returns    │ │
│  │           │              │  the same event.                    │ │
│  ├──────────┼─────────────┼──────────────────────────────────────┤ │
│  │  PUT      │  ✓ Yes       │  "Set X to 5." Doing it twice =    │ │
│  │           │              │  X is still 5. Absolute update.     │ │
│  ├──────────┼─────────────┼──────────────────────────────────────┤ │
│  │  DELETE   │  ✓ Yes       │  "Delete event 73067." Second call  │ │
│  │           │              │  → already deleted → same result.   │ │
│  ├──────────┼─────────────┼──────────────────────────────────────┤ │
│  │  POST     │  ✗ No        │  "Create a registration." Calling   │ │
│  │           │              │  twice creates TWO registrations.   │ │
│  │           │              │  POST is NOT naturally idempotent.  │ │
│  │           │              │  NEEDS an idempotency key!          │ │
│  ├──────────┼─────────────┼──────────────────────────────────────┤ │
│  │  PATCH    │  ✗ Sometimes │  "Increment counter by 1" is NOT   │ │
│  │           │              │  idempotent (1→2→3). "Set counter   │ │
│  │           │              │  to 5" IS idempotent.               │ │
│  └──────────┴─────────────┴──────────────────────────────────────┘ │
│                                                                      │
│  RULE: GET, PUT, DELETE are naturally idempotent.                   │
│        POST requires an idempotency key to be safe.                │
│        Design APIs to be idempotent wherever possible.             │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Three Implementation Patterns

```
┌──────────────────────────────────────────────────────────────────────┐
│  IMPLEMENTATION PATTERNS                                             │
│                                                                      │
│  ┌──────────────────────┐  ┌──────────────────────┐                │
│  │  IDEMPOTENCY KEY     │  │  DATABASE CONSTRAINT  │                │
│  │                      │  │                       │                │
│  │  Client sends unique │  │  UNIQUE constraint    │                │
│  │  key per request.    │  │  on txn_id.           │                │
│  │  Server stores key + │  │  INSERT fails on      │                │
│  │  result in Redis.    │  │  duplicate.            │                │
│  │                      │  │                       │                │
│  │  Used by: Stripe,    │  │  Simple but limited.  │                │
│  │  AWS, our CXP        │  │  Only prevents        │                │
│  │  registration         │  │  duplicate inserts.   │                │
│  └──────────────────────┘  └──────────────────────┘                │
│                                                                      │
│  ┌──────────────────────┐                                          │
│  │  NATURAL IDEMPOTENCY │                                          │
│  │                      │                                          │
│  │  GET, PUT, DELETE are│                                          │
│  │  idempotent.         │                                          │
│  │  POST is not (needs  │                                          │
│  │  key).               │                                          │
│  │                      │                                          │
│  │  Design APIs to be   │                                          │
│  │  idempotent.         │                                          │
│  └──────────────────────┘                                          │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Idempotency In My CXP Projects — Real Examples

### The CXP Platform — Idempotency at Every Layer

Our platform has **6 layers of idempotency** — from the frontend button click to the deepest SQS consumer. This defense-in-depth approach ensures no duplicate registrations even in the worst failure scenarios.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — IDEMPOTENCY DEFENSE-IN-DEPTH                             │
│                                                                          │
│  LAYER 1: Frontend (button disable)                                     │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  User clicks "Register" → button disabled immediately.           │  │
│  │  Prevents accidental double-click.                               │  │
│  │  Weakest layer: user can refresh page, bypass with dev tools.   │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  LAYER 2: Redis Idempotency Key (application)                           │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Key: "{upmId}_{eventId}" — unique per user+event combination.  │  │
│  │  Success response cached for 60 min. Duplicate → return cached. │  │
│  │  Failure counter (threshold 5) → HTTP 429 on rapid-fire bots.   │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  LAYER 3: Eventtia Duplicate Check (external service)                   │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Eventtia returns 422 "This email is already registered."        │  │
│  │  Even if Redis misses, Eventtia catches the duplicate.          │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  LAYER 4: DynamoDB Composite Key (database constraint)                  │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  PK: "eventId_upmId" — unique per user+event.                   │  │
│  │  putItem with same key = overwrite (not duplicate).              │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  LAYER 5: SQS ON_SUCCESS Deletion (message processing)                  │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Message deleted only after successful processing.               │  │
│  │  If reprocessed (visibility timeout), same transform output.    │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  LAYER 6: Rise GTS Deterministic Transform (pure function)              │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Same input JSON → same output JSON. No randomness/timestamps   │  │
│  │  in the transform. Reprocessing produces identical result.      │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: Redis Idempotency Key — The Primary Mechanism

**Service:** `cxp-event-registration`
**Key:** `{upmId}_{eventId}` (unique per user + event)
**Pattern:** Check cache → hit = return cached response, miss = proceed + cache result

```
┌──────────────────────────────────────────────────────────────────────┐
│  Redis Idempotency — Full Flow                                       │
│                                                                      │
│  REQUEST 1 (original):                                              │
│  ────────────────────                                               │
│  POST /community/event_registrations/v1                             │
│  User: uuid-1234, Event: 73067                                     │
│  idempotencyKey = "uuid-1234_73067"                                 │
│                                                                      │
│  1. Redis GET "uuid-1234_73067_success_response" → null (MISS)     │
│  2. Redis GET "uuid-1234_73067_failure_counter" → null (first try) │
│  3. SET failure_counter = 1, TTL = 1 min (async)                   │
│  4. Call Eventtia → 200 success                                     │
│  5. SET success_response = {response JSON}, TTL = 60 min (async)   │
│  6. Return 200 to user                                              │
│                                                                      │
│  REQUEST 2 (duplicate — user double-clicked):                       │
│  ──────────────────────────────────────────                         │
│  POST /community/event_registrations/v1  (same user, same event)   │
│  idempotencyKey = "uuid-1234_73067"  (same key)                    │
│                                                                      │
│  1. Redis GET "uuid-1234_73067_success_response" → {cached JSON}   │
│  2. Return cached 200 response immediately                         │
│  3. Eventtia NEVER called (saved ~200ms + prevented duplicate reg) │
│                                                                      │
│  REQUEST 3 (bot — rapid-fire 6th attempt):                          │
│  ──────────────────────────────────────────                         │
│  POST /community/event_registrations/v1  (same key, 6th time)     │
│                                                                      │
│  1. Redis GET success_response → null (previous attempts failed)   │
│  2. Redis GET failure_counter → 5 (exceeded threshold!)            │
│  3. Return HTTP 429 "Too Many Requests"                            │
│  4. Eventtia NEVER called (bot blocked)                            │
│                                                                      │
│  AFTER 60 MINUTES: success_response TTL expires.                   │
│  User CAN register for the same event again if they cancelled.     │
│  But Eventtia also checks → 422 "already registered" (Layer 3).   │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — the complete idempotency flow:**

```java
// EventRegistrationService.java — idempotency check + cache
public Mono<EventRegistrationResponse> registerEventUser(...) {
    String idempotencyKey = upmId + "_" + eventId;

    if (cacheBasedBotProtectionFlag) {
        // CHECK 1: Cached success response?
        Object cachedResponse = registrationCacheService
            .getRegistrationRequestIdempotencyValueFromCache(idempotencyKey);
        if (!Objects.isNull(cachedResponse)) {
            log.info("Returning cached response for idempotencyKey {}", idempotencyKey);
            return Mono.just(cachedResponse);  // IDEMPOTENT: same result as first call
        }

        // CHECK 2: Failure counter exceeded?
        if (registrationCacheService.validateDuplicateRegistrationRequest(idempotencyKey)) {
            log.info("Duplicate request for idempotencyKey {}", idempotencyKey);
            return Mono.error(new RegistrationDeniedException(...)); // 429
        }
    }

    // PROCEED: Call Eventtia (first genuine attempt)
    return eventRegistration(context, eventId, request, upmId, idempotencyKey);
}

// On success → cache the result for future duplicate requests:
if (cacheBasedBotProtectionFlag) {
    CompletableFuture.runAsync(() ->
        registrationCacheService.addRegistrationRequestSuccessResponseToCache(
            idempotencyKey, eventRegistrationResponse));
}
```

```java
// RegistrationCacheService.java — the idempotency key store
void addRegistrationRequestSuccessResponseToCache(String idempotencyKey,
        EventRegistrationResponse value) {
    redisTemplate.opsForValue().set(
        idempotencyKey + SUCCESS_RESPONSE_SUFFIX,
        GSON.toJson(value),
        Duration.ofMinutes(60)    // cached for 1 hour
    );
}

EventRegistrationResponse getRegistrationRequestIdempotencyValueFromCache(String key) {
    try {
        return GSON.fromJson(
            (String) redisTemplate.opsForValue().get(key + SUCCESS_RESPONSE_SUFFIX),
            EventRegistrationResponse.class);
    } catch (Exception e) {
        log.error("Redis exception", e);
        return null;   // fallback: let the request proceed (Eventtia is Layer 3)
    }
}
```

**Interview answer:**
> "Our registration API uses a Redis-based idempotency key pattern. The key is `{userId}_{eventId}` — unique per user+event. On the first successful registration, we cache the response JSON in Redis with 60-minute TTL. Any duplicate request with the same key returns the cached response instantly — Eventtia is never called twice. For bot protection, we also track a failure counter: after 5 failed attempts in 1 minute, we return 429. The idempotency key is composite (user + event), not a client-generated UUID, because the business rule is 'one registration per user per event' — the key naturally encodes this constraint."

---

### Example 2: Eventtia 422 — External Service Idempotency (Defense Layer 3)

**Service:** Eventtia (external SaaS)
**Pattern:** Eventtia rejects duplicate registrations with 422 status

```
┌──────────────────────────────────────────────────────────────────────┐
│  Eventtia as Idempotency Backstop                                    │
│                                                                      │
│  SCENARIO: Redis is down. Our idempotency check fails (Layer 2).   │
│  Two identical requests reach Eventtia.                              │
│                                                                      │
│  Request 1 → Eventtia: "Register uuid-1234 for event 73067"        │
│  Eventtia: Creates registration → 200 OK                           │
│                                                                      │
│  Request 2 → Eventtia: "Register uuid-1234 for event 73067"        │
│  Eventtia: "This email is already registered" → 422                 │
│                                                                      │
│  Our code catches the 422:                                          │
│  EventtiaErrorCodeDeterminer → errorCode = "DUPLICATE_EMAIL"       │
│  Return appropriate error to user.                                  │
│                                                                      │
│  RESULT: No double registration even when Redis is down.            │
│  Eventtia's own database constraint prevents the duplicate.         │
│                                                                      │
│  THIS IS DEFENSE-IN-DEPTH:                                          │
│  Layer 2 (Redis) catches 99% of duplicates (fast, <1ms).           │
│  Layer 3 (Eventtia) catches the 1% that slip through.              │
│  Two independent idempotency mechanisms = no duplicates ever.      │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — error code handling for duplicates:**

```java
// EventtiaErrorCodeDeterminer.java — recognizes duplicate as idempotent scenario
// 422 with "already registered" → DUPLICATE_EMAIL error code
// Frontend shows "You're already registered" (not a server error)
```

---

### Example 3: DynamoDB Composite Key — Natural Idempotency (Layer 4)

**Service:** `cxp-event-registration`
**Table:** `unprocessed-registration-requests`
**Key:** `eventId_upmId` (composite string)

```
┌──────────────────────────────────────────────────────────────────────┐
│  DynamoDB Key as Natural Idempotency                                 │
│                                                                      │
│  PutItem with key "73067_uuid-1234":                                │
│                                                                      │
│  First write:  PK = "73067_uuid-1234" → { payload: {...} }         │
│  Second write: PK = "73067_uuid-1234" → { payload: {...} }         │
│  → OVERWRITES the first item (same key = same item)                │
│  → NOT a duplicate. Table has 1 item, not 2.                       │
│                                                                      │
│  This is NATURAL IDEMPOTENCY through key design:                   │
│  - Same user+event always maps to same partition key                │
│  - putItem is a PUT (idempotent), not a POST (non-idempotent)     │
│  - Batch reprocessing can safely re-putItem without duplicates     │
│                                                                      │
│  CONTRAST with auto-generated IDs:                                  │
│  PK = UUID.randomUUID() → "abc-123" (first write)                 │
│  PK = UUID.randomUUID() → "def-456" (second write = DUPLICATE!)   │
│  Auto-generated keys are NOT idempotent.                           │
│                                                                      │
│  OUR DESIGN CHOICE:                                                 │
│  Using "eventId_upmId" as the key makes the business constraint    │
│  (one entry per user+event) AND idempotency the SAME thing.       │
└──────────────────────────────────────────────────────────────────────┘
```

```java
// UnprocessedRegistrationRequest.java — key IS the idempotency guarantee
@DynamoDbBean
public class UnprocessedRegistrationRequest {
    @DynamoDbPartitionKey
    public String getEventId_upmId() { return eventId_upmId; }
    // "73067_uuid-1234" — same user+event = same key = overwrite, not duplicate
}
```

---

### Example 4: SQS + Rise GTS — Message Processing Idempotency (Layer 5+6)

**Service:** `rise-generic-transform-service`
**Pattern:** `ON_SUCCESS` deletion + deterministic transform

```
┌──────────────────────────────────────────────────────────────────────┐
│  SQS + Transform Idempotency                                        │
│                                                                      │
│  SCENARIO: Rise GTS processes an S3 event, crashes mid-way.        │
│  SQS makes message visible again → another task picks it up.       │
│                                                                      │
│  First processing:                                                  │
│  1. SQS delivers message: "S3 file s3://bucket/key123"             │
│  2. Rise GTS reads S3 file → transforms → POSTs to NCP             │
│  3. Task CRASHES before deleting SQS message                        │
│  4. SQS message becomes visible after VisibilityTimeout (1 hour)   │
│                                                                      │
│  Second processing (redelivery):                                    │
│  1. SQS delivers SAME message: "S3 file s3://bucket/key123"        │
│  2. Rise GTS reads SAME S3 file → transforms → SAME output         │
│  3. POSTs SAME payload to NCP                                       │
│  4. Deletes SQS message ✓                                           │
│                                                                      │
│  WHY THIS IS IDEMPOTENT:                                            │
│  - Same S3 file → same JSON input (S3 objects are immutable)       │
│  - Same input → same transform output (deterministic function)     │
│  - NCP receiving the same payload twice → sends 1 email            │
│    (NCP has its own deduplication by notification ID)               │
│                                                                      │
│  IDEMPOTENCY CHAIN:                                                 │
│  SQS (at-least-once) → S3 (immutable input) → Transform (pure     │
│  function) → NCP (dedup by notification ID)                        │
│  Every link in the chain is idempotent.                             │
└──────────────────────────────────────────────────────────────────────┘
```

```java
// TransformationService.java — idempotent by design
@SqsListener(value = "${sqs.queue}",
             deletionPolicy = SqsMessageDeletionPolicy.ON_SUCCESS)
public void ingestFromSQS(final String sqsEventString) {
    // Parse S3 event notification
    S3EventNotification s3Event = JsonUtils.readValue(sqsEventString, ...);
    s3Event.getRecords().forEach(record -> {
        String bucket = record.getS3().getBucket().getName();
        String key = record.getS3().getObject().getKey();
        getPayLoadAndProcess(bucket, key);
        // Same bucket+key → same S3 object → same transform → same output
        // Processing this twice = same result (idempotent)
    });
}
```

---

### Example 5: Email Recovery Reprocessing — Idempotent by Design

**Service:** `cxp-email-drop-recovery`
**Pattern:** Re-POSTing the same S3 payload to Rise GTS is safe

```
┌──────────────────────────────────────────────────────────────────────┐
│  Recovery Reprocessing — Idempotency Enables Safe Retry              │
│                                                                      │
│  SCENARIO: Email was dropped. Operator clicks "Reprocess Selected." │
│                                                                      │
│  1. Dashboard fetches original S3 payload via Athena "$path"        │
│  2. POST payload to Rise GTS /data/transform/v1                    │
│  3. Rise GTS transforms → NCP → CRS → email sent ✓                │
│                                                                      │
│  OPERATOR CLICKS "REPROCESS" AGAIN (accidentally):                  │
│  1. Same S3 payload → same transform output                        │
│  2. Same POST to NCP → NCP deduplicates by notification ID         │
│  3. Email NOT sent twice (or if sent, user gets same email again   │
│     — annoying but not harmful)                                     │
│                                                                      │
│  WHY THIS IS SAFE:                                                  │
│  - S3 payload is immutable (same input every time)                 │
│  - Transform is deterministic (same input → same output)           │
│  - Content-Type header is the same each time:                      │
│    "application/vnd.nike.eventtia-events+json;charset=UTF-8"       │
│  - RISE GTS processes it identically each time                     │
│                                                                      │
│  WITHOUT IDEMPOTENCY:                                               │
│  Reprocessing would be DANGEROUS — could send 5 duplicate emails   │
│  or create 5 duplicate registrations. Operators would be afraid    │
│  to click "Reprocess." The tool would be useless.                  │
│                                                                      │
│  WITH IDEMPOTENCY:                                                  │
│  "Reprocess" is safe to click any number of times.                 │
│  Worst case: user gets a duplicate email (cosmetic, not harmful).  │
│  This makes the recovery tool TRUSTWORTHY and actually usable.     │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```python
# reprocess.py — safe to call multiple times (idempotent)
# Same S3 path → same payload → same transform → same result
response = urllib.request.urlopen(urllib.request.Request(
    RISE_GTS_URL,
    data=json.dumps(payload).encode(),
    headers={
        'Content-Type': RISE_CONTENT_TYPE,    # same every time
        'Authorization': f'Bearer {oscar_token}'
    }
))
```

---

### Example 6: Cancellation — Idempotent DELETE + Cache Eviction

**Service:** `cxp-event-registration`
**Pattern:** DELETE is naturally idempotent + Redis cache eviction

```
┌──────────────────────────────────────────────────────────────────────┐
│  Cancellation Idempotency                                            │
│                                                                      │
│  DELETE /community/event_registrations?eventId=73067                │
│                                                                      │
│  Call 1: Eventtia cancels registration → 200 OK                    │
│          Redis: evict idempotency keys for this user+event          │
│                                                                      │
│  Call 2: Eventtia: "Registration not found" → still returns 200    │
│          Redis: keys already evicted → no-op                       │
│          (or keys expired via TTL → also no-op)                    │
│                                                                      │
│  DELETE is naturally idempotent: cancelling an already-cancelled    │
│  registration produces the same result (registration doesn't exist).│
│                                                                      │
│  Redis eviction is also idempotent:                                │
│  getAndDelete("key") when key doesn't exist → returns null → OK   │
└──────────────────────────────────────────────────────────────────────┘
```

```java
// EventRegistrationService.java — cancel + evict cache (both idempotent)
public Mono<Void> cancelUserRegistration(...) {
    if (cacheBasedBotProtectionFlag) {
        CompletableFuture.runAsync(() ->
            registrationCacheService.evictCacheBasedOnCancellationRequest(idempotencyKey));
    }
    // DELETE to Eventtia (idempotent — cancelling twice = same result)
    return eventCancellation(context, eventId, upmId);
}

// RegistrationCacheService.java — eviction is idempotent
boolean evictCacheBasedOnCancellationRequest(String idempotencyKey) {
    redisTemplate.opsForValue().getAndDelete(idempotencyKey + FAILURE_COUNTER_SUFFIX);
    redisTemplate.opsForValue().getAndDelete(idempotencyKey + SUCCESS_RESPONSE_SUFFIX);
    // If keys don't exist → getAndDelete returns null → no error → idempotent
    return true;
}
```

---

## Idempotency Design Principles

```
┌──────────────────────────────────────────────────────────────────────┐
│  IDEMPOTENCY DESIGN PRINCIPLES                                       │
│                                                                      │
│  1. USE NATURAL KEYS (not auto-generated IDs)                       │
│     ✓ "eventId_upmId" — same input = same key = overwrite          │
│     ✗ UUID.randomUUID() — every call generates new ID = duplicate  │
│                                                                      │
│  2. MAKE TRANSFORMS DETERMINISTIC                                   │
│     ✓ Same JSON input → same JSON output (Rise GTS)                │
│     ✗ Adding timestamp/random-ID in transform → different output   │
│                                                                      │
│  3. CACHE RESULTS, NOT JUST STATUS                                  │
│     ✓ Cache full response JSON → return exact same response        │
│     ✗ Cache only "processed=true" → client gets different response │
│                                                                      │
│  4. USE TTL TO BOUND IDEMPOTENCY WINDOW                            │
│     ✓ 60 min TTL → "same request within 1 hour = duplicate"       │
│     ✗ No TTL → idempotency key lives forever → wasted memory      │
│     ✗ Too short TTL → key expires → duplicate sneaks through       │
│                                                                      │
│  5. DEFENSE-IN-DEPTH (multiple layers)                              │
│     ✓ Redis (fast) + Eventtia 422 (backstop) + DynamoDB key        │
│     ✗ Relying on single layer → fails when that layer is down      │
│                                                                      │
│  6. MAKE SIDE EFFECTS IDEMPOTENT TOO                                │
│     ✓ Cache eviction: getAndDelete on missing key → no error       │
│     ✓ Akamai purge: purging an already-purged tag → no-op         │
│     ✗ Sending email on every retry → user gets 5 emails           │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Idempotency Across CXP

| Layer | Mechanism | Key | Scope | Catches |
|-------|----------|-----|-------|---------|
| **L1: Frontend** | Button disable | N/A | Single click | Accidental double-click |
| **L2: Redis** | Idempotency key + cached response | `{upmId}_{eventId}` | 60 min TTL | 99% of duplicates (fast, <1ms) |
| **L2b: Redis** | Failure counter + 429 | `{upmId}_{eventId}_failure_counter` | 1 min TTL | Bot rapid-fire attacks |
| **L3: Eventtia** | 422 "already registered" | Internal DB constraint | Permanent | Redis-down duplicates |
| **L4: DynamoDB** | Composite partition key | `eventId_upmId` | Permanent | Overwrite, not duplicate |
| **L5: SQS** | `ON_SUCCESS` deletion | Message ID | Until processed | Crashed-consumer retries |
| **L6: Rise GTS** | Deterministic transform | Same input → same output | Stateless | Reprocessed messages |
| **Recovery** | Same S3 payload → same result | S3 path | Immutable | Safe "Reprocess" button |
| **Cancel** | DELETE is naturally idempotent | Event ID | N/A | Double-cancel = no-op |

---

## Common Interview Follow-ups

### Q: "Why not use a client-generated idempotency key (like Stripe's Idempotency-Key header)?"

> "Stripe requires clients to generate a UUID and pass it as `Idempotency-Key` header. This works for payment APIs where the client controls the retry logic. For our registration API, the idempotency constraint IS the business rule: one registration per user per event. So we derive the key server-side from `{upmId}_{eventId}` — the client doesn't need to generate anything. If the same user registers for the same event from a different device or browser session, we still catch the duplicate because the key is derived from business identity, not a client-generated token."

### Q: "What happens during the 1ms gap between Redis check and Eventtia call?"

> "Two requests arrive simultaneously. Both check Redis → both miss (key doesn't exist yet). Both call Eventtia. Eventtia processes the first, returns 200. The second hits Eventtia's own duplicate check → returns 422 'already registered.' This is why Layer 3 (Eventtia) exists: it handles the race condition that Layer 2 (Redis) can't. The async `CompletableFuture.runAsync` that caches the first result means subsequent duplicates (after the race window) are caught by Redis. The race window is ~200ms (Eventtia call duration) — during which only Eventtia's constraint protects us."

### Q: "How do you test idempotency?"

> "Three test strategies:
> 1. **Unit test:** Call `registerEventUser()` twice with same `upmId + eventId`. Assert second call returns cached response without calling Eventtia.
> 2. **Integration test:** Send two concurrent POST requests to the registration endpoint. Assert only one Eventtia registration created (verify via Athena count query).
> 3. **Load test:** During sneaker launch simulation, fire 100 requests for the same user+event. Assert exactly 1 registration in Eventtia, Redis failure counter reaches threshold, subsequent requests get 429."

### Q: "What if Redis is down AND Eventtia is slow — could you get duplicates?"

> "Theoretically, if Redis is down (Layer 2 fails) AND two requests arrive within the Eventtia processing window (~200ms), both could create registrations before Eventtia's duplicate check runs. In practice, Eventtia's database-level UNIQUE constraint prevents this — the second INSERT fails atomically. The absolute worst case with all layers compromised: user gets two confirmation emails for one registration slot. Not harmful, just cosmetic. The seat count is always correct because Eventtia decrements atomically."

---
---

# Topic 27: Service Discovery

> In dynamic environments, services register themselves and discover others through a registry instead of hardcoded addresses.

> **Interview Tip:** Mention your approach — "In Kubernetes I'd rely on DNS-based discovery; for VM-based systems, I'd use Consul with health checks to route only to healthy instances."

---

## The Problem

In a microservice architecture, service instances are **dynamic** — they scale up/down, get replaced on failure, deploy to new IPs. Hardcoding addresses breaks instantly.

```
┌──────────────────────────────────────────────────────────────────────┐
│  THE PROBLEM: How does Service A find Service B's address?           │
│                                                                      │
│  IPs change with scaling, failures, deployments —                   │
│  hardcoding doesn't work.                                           │
│                                                                      │
│  HARDCODED (broken):               DYNAMIC (service discovery):     │
│                                                                      │
│  // Bad: IP changes on redeploy    // Good: name resolves to        │
│  String url = "http://10.0.1.42:   //        current IPs            │
│    8080/api";                      String url = "http://cxp-events  │
│  // Fails when task 42 dies         //   .service.internal/api";    │
│                                     // DNS resolves to healthy IPs  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Discovery Patterns

```
┌──────────────────────────────────────────────────────────────────────────┐
│                      SERVICE DISCOVERY PATTERNS                           │
│                                                                          │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐    │
│  │  CLIENT-SIDE DISCOVERY       │  │  SERVER-SIDE DISCOVERY        │    │
│  │                              │  │                               │    │
│  │  Client queries registry,   │  │  Load balancer queries        │    │
│  │  picks instance.            │  │  registry.                    │    │
│  │                              │  │                               │    │
│  │  ┌────────┐  1.query  ┌───┐ │  │  ┌────────┐       ┌────┐    │    │
│  │  │ Client │──────────▶│Reg│ │  │  │ Client │──────▶│ LB │    │    │
│  │  │        │  2.list   │   │ │  │  │        │  1.call│    │    │    │
│  │  │        │◀──────────│   │ │  │  └────────┘       │    │    │    │
│  │  │        │           └───┘ │  │                    │    │    │    │
│  │  │        │  3.direct       │  │              2.route│    │    │    │
│  │  │        │──────────▶SvcB  │  │                    ▼    │    │    │
│  │  └────────┘                 │  │              SvcB.1 SvcB.2  │    │
│  │                              │  │                               │    │
│  │  [+] Client controls LB    │  │  [+] Client is simple, just   │    │
│  │  [-] Client must know       │  │      calls LB                 │    │
│  │      registry protocol      │  │  [-] LB is extra hop          │    │
│  │                              │  │                               │    │
│  │  Consul, Eureka (Netflix)   │  │  AWS ALB, Kubernetes Service  │    │
│  └──────────────────────────────┘  └──────────────────────────────┘    │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  SERVICE REGISTRY                                                 │  │
│  │  Services register on startup, deregister on shutdown.           │  │
│  │  Health checks remove unhealthy instances.                       │  │
│  │  Tools: Consul, etcd, Zookeeper, Eureka, AWS Cloud Map, K8s DNS │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  DNS-BASED DISCOVERY (Kubernetes)                                 │  │
│  │  service-name.namespace.svc.cluster.local resolves to service IPs│  │
│  │  Simple, built-in, but no health-aware routing                   │  │
│  │  (use Service Mesh for that)                                     │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Service Discovery In My CXP Projects

### The CXP Platform — Multi-Layer Discovery

Our platform uses **three layers of service discovery**, each solving a different scope: global (Route53 DNS), platform (NPE/Kubernetes), and application (ALB path routing).

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — SERVICE DISCOVERY ARCHITECTURE                            │
│                                                                          │
│  LAYER 1: Global DNS (Route53)                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  "Where is the CXP events service?"                               │  │
│  │                                                                   │  │
│  │  any.v1.events.community.global.prod.origins.nike                │  │
│  │       │                                                           │  │
│  │       ▼  Route53 latency-based routing                           │  │
│  │  aws-us-east-1.v1.events.community.global.prod.origins.nike     │  │
│  │  OR                                                               │  │
│  │  aws-us-west-2.v1.events.community.global.prod.origins.nike     │  │
│  │                                                                   │  │
│  │  DNS resolves to the nearest healthy region's ALB.               │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  LAYER 2: Platform (NPE / Kubernetes)                                   │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  "Which pods are running cxp-events?"                             │  │
│  │                                                                   │  │
│  │  NPE handles: Pod scheduling, DNS within cluster,                │  │
│  │  liveness/readiness probes, automatic deregistration on failure. │  │
│  │                                                                   │  │
│  │  container.httpTrafficPort: 8080                                 │  │
│  │  routing.paths.prefix: /community/events/v1                      │  │
│  │  health.liveness: /community/events_health/v1                    │  │
│  │                                                                   │  │
│  │  K8s DNS: cxp-events.cxp-namespace.svc.cluster.local            │  │
│  │  → resolves to healthy pod IPs                                   │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  LAYER 3: Application (ALB path-based routing)                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  "Which SERVICE handles this URL?"                                │  │
│  │                                                                   │  │
│  │  /community/events/*             → cxp-events target group      │  │
│  │  /community/event_registrations/* → cxp-reg target group        │  │
│  │  /engage/experience_*            → expviewsnikeapp target group │  │
│  │  /data/transform/v1*             → Rise GTS target group        │  │
│  │                                                                   │  │
│  │  ALB is the SERVER-SIDE DISCOVERY pattern:                       │  │
│  │  Client calls ALB → ALB routes to correct service → ALB picks   │  │
│  │  healthy task (round-robin within target group).                 │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  LAYER 4: External Services (DNS + OSCAR tokens)                        │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  "How do we find Eventtia, Pairwise, LAMS, Akamai?"             │  │
│  │                                                                   │  │
│  │  Static DNS names configured in application properties:          │  │
│  │  - Eventtia: dashboard.eventtia.com                              │  │
│  │  - Pairwise: partner-consumer-mapper API URL                     │  │
│  │  - Akamai: purge API URL                                         │  │
│  │  - NCP: api.nike.com/ncp/ingest/v1                               │  │
│  │                                                                   │  │
│  │  Not dynamic discovery — these are stable, managed endpoints.    │  │
│  │  OSCAR tokens handle authentication, not discovery.              │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: Route53 — DNS-Based Global Discovery

**Scope:** Global — which AWS REGION should handle this request?
**Pattern:** DNS resolves `any.*` domain to the best regional ALB

```
┌──────────────────────────────────────────────────────────────────────┐
│  Route53 DNS Discovery — Global Region Selection                     │
│                                                                      │
│  SERVICE NAME CONVENTION:                                           │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  any.v1.events.community.global.prod.origins.nike          │    │
│  │  │   │  │      │         │      │     │                    │    │
│  │  │   │  │      │         │      │     └── Nike domain      │    │
│  │  │   │  │      │         │      └── Environment             │    │
│  │  │   │  │      │         └── Scope (global routing)         │    │
│  │  │   │  │      └── Domain (community platform)              │    │
│  │  │   │  └── Service name (events)                           │    │
│  │  │   └── API version (v1)                                    │    │
│  │  └── "any" = Route53 picks best region                       │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  DISCOVERY FLOW:                                                    │
│  1. Client resolves: any.v1.events.community.global.prod.origins.nike│
│  2. Route53 checks latency from client's DNS resolver to each region│
│  3. Route53 checks health check for each region                     │
│  4. Returns CNAME: aws-us-east-1.v1.events... (lowest latency,     │
│     healthy)                                                        │
│  5. aws-us-east-1.v1.events... resolves to ALB IP in us-east-1    │
│  6. Client connects to ALB                                         │
│                                                                      │
│  TWO RECORDS PER SERVICE (from Terraform):                          │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  "any.v1.events..."                                         │    │
│  │  → routing_policy = "LATENCY"                               │    │
│  │  → health_check = events health check                       │    │
│  │  → Route53 picks best region                                │    │
│  │                                                              │    │
│  │  "aws-us-east-1.v1.events..."                               │    │
│  │  → routing_policy = "SIMPLE"                                │    │
│  │  → CNAME to ALB DNS name                                    │    │
│  │  → Direct regional endpoint                                 │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  FAILOVER:                                                          │
│  us-east-1 health check fails → Route53 stops returning it        │
│  → ALL traffic goes to aws-us-west-2.v1.events... (auto-failover) │
│  → No code changes, no config changes, DNS handles it              │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual Terraform:**

```hcl
// route53_locals.tf — auto-generates discovery records per service
route53_service_records = flatten([
  for svc in var.route53_services : [
    {
      // Global discovery endpoint (latency-routed)
      record_name    = "any.v1.${svc.name}.${local.r53_cfg.domain_suffix}"
      record_value   = "aws-${local.r53_region}.v1.${svc.name}.${domain}"
      routing_policy = "LATENCY"
      health_check   = svc.health_check_name
    },
    {
      // Regional direct endpoint (simple CNAME to ALB)
      record_name    = "aws-${local.r53_region}.v1.${svc.name}.${domain}"
      record_value   = local.r53_ext_target   // ALB DNS name
      routing_policy = "SIMPLE"
    }
  ]
])
```

**From the NPE service config — custom domains that Route53 resolves:**

```yaml
# NPEService/prod/711620779129_npe_service_us_west.yaml
ingress:
  public:
    dns:
      customDomains:
        - any.v1.events.community.global.prod.origins.nike
        - aws-us-west-2.v1.events.community.global.prod.origins.nike
        - any.v1.event-registrations.community.global.prod.origins.nike
        - aws-us-west-2.v1.event-registrations.community.global.prod.origins.nike
```

**Interview answer:**
> "We use DNS-based service discovery at the global level. Every CXP service has an `any.v1.{service}.community.global.prod.origins.nike` domain that Route53 resolves via latency-based routing to the nearest healthy region. Terraform auto-generates two records per service per region: a latency-routed `any.*` record (client-facing) and a simple CNAME `aws-{region}.*` record (direct regional access). Health checks ensure Route53 never routes to an unhealthy region. This is server-side discovery — the client just resolves a DNS name, and Route53 + ALB handle the rest."

---

### Example 2: NPE/Kubernetes — Platform-Level Discovery

**Scope:** Within a region — which PODs are running this service?
**Pattern:** Kubernetes DNS + liveness/readiness probes

```
┌──────────────────────────────────────────────────────────────────────┐
│  NPE/Kubernetes Discovery — Pod-Level                                │
│                                                                      │
│  NPE component YAML defines:                                       │
│  1. What container image to run                                     │
│  2. What port it listens on                                         │
│  3. What URL paths it handles                                       │
│  4. How to check if it's healthy                                    │
│                                                                      │
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
│  KUBERNETES DISCOVERY LIFECYCLE:                                    │
│                                                                      │
│  1. REGISTER: Pod starts → passes readiness probe                  │
│     → K8s adds pod IP to Service endpoint list                     │
│     → DNS (cxp-events.namespace.svc.cluster.local) includes new IP │
│                                                                      │
│  2. DISCOVER: Request arrives → K8s Service routes to healthy pod   │
│     → Internal DNS resolution or kube-proxy iptables routing       │
│                                                                      │
│  3. DEREGISTER: Pod fails readiness probe                           │
│     → K8s removes pod IP from endpoint list                        │
│     → No more traffic routed to failing pod                        │
│                                                                      │
│  4. RESTART: Pod fails liveness probe                               │
│     → K8s kills and restarts the container                         │
│     → New container must pass readiness before receiving traffic    │
│                                                                      │
│  NO SERVICE REGISTRY TO MANAGE.                                    │
│  K8s IS the registry. Health probes ARE the registration protocol. │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 3: ALB Target Groups — Path-Based Service Discovery

**Scope:** Within a region — which SERVICE handles this URL path?
**Pattern:** ALB listener rules as a service directory

```
┌──────────────────────────────────────────────────────────────────────┐
│  ALB as Service Directory                                            │
│                                                                      │
│  ONE ALB (cxp-alb) routes to FOUR services by URL path:            │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  HTTPS:443 Listener                                           │  │
│  │                                                               │  │
│  │  URL Path                        → Target Group → Tasks      │  │
│  │  ──────────────────────────────   ────────────   ──────      │  │
│  │  /community/events/*              → cxp-events    → 2-8 tasks│  │
│  │  /community/event_seats_status/*  → cxp-events    → (same)   │  │
│  │  /community/groups/*              → cxp-events    → (same)   │  │
│  │  /community/event_registrations/* → cxp-reg       → 2-8 tasks│  │
│  │  /community/attendee_status/*     → cxp-reg       → (same)   │  │
│  │  /engage/experience_*             → expviewsnikeapp→ N tasks  │  │
│  │  /data/transform/v1*              → rise-gts      → N tasks  │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  THIS IS SERVER-SIDE DISCOVERY:                                     │
│  - Client doesn't know about individual services or tasks          │
│  - Client sends request to ALB URL → ALB routes by path           │
│  - ALB health checks (/actuator/health) remove unhealthy tasks    │
│  - ECS auto-scaling adds/removes tasks → ALB auto-discovers them  │
│                                                                      │
│  TARGET GROUP REGISTRATION:                                         │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ECS launches task (new IP: 10.0.3.47)                      │    │
│  │  → ECS registers 10.0.3.47:8080 with cxp-events TG         │    │
│  │  → ALB health check: GET /actuator/health → 200 ✓          │    │
│  │  → ALB starts routing traffic to 10.0.3.47                  │    │
│  │                                                              │    │
│  │  ECS kills task (IP 10.0.3.42 terminated)                   │    │
│  │  → ALB drains connections from 10.0.3.42                   │    │
│  │  → ECS deregisters 10.0.3.42 from target group              │    │
│  │  → ALB stops routing to dead IP                              │    │
│  │                                                              │    │
│  │  FULLY AUTOMATIC. No manual registration/deregistration.    │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual Terraform:**

```hcl
// cxp-events/terraform/module/main.tf — automatic target group registration
resource "aws_ecs_service" "service" {
  load_balancer {
    target_group_arn = aws_alb_target_group.tg.arn
    container_name   = "${var.prefix}-${var.service}"
    container_port   = var.port  // 8080
  }
  // ECS automatically registers/deregisters task IPs with the target group
}

resource "aws_alb_target_group" "tg" {
  target_type = "ip"   // ECS Fargate tasks registered by IP
  health_check {
    path    = "/actuator/health"
    matcher = "200"
  }
}
```

---

### Example 4: External Services — Static DNS (Not Dynamic Discovery)

**Scope:** CXP → external Nike/third-party services
**Pattern:** Configured URLs in application properties (NOT dynamic discovery)

```
┌──────────────────────────────────────────────────────────────────────┐
│  External Service "Discovery" — Actually Static Config               │
│                                                                      │
│  ┌──────────────────────┬──────────────────────────────────────┐   │
│  │  Service              │  How We "Discover" It                │   │
│  ├──────────────────────┼──────────────────────────────────────┤   │
│  │  Eventtia API         │  URL in application.properties       │   │
│  │                       │  (per-environment: dev, prod)        │   │
│  │  Pairwise API         │  URL in Retrofit config              │   │
│  │  Akamai Purge API     │  URL in OSCAR scope config           │   │
│  │  NCP Ingest API       │  URL in Rise GTS config JSON         │   │
│  │  LAMS API             │  URL in application.properties       │   │
│  │  S3/DynamoDB/SQS      │  AWS SDK auto-discovers via region   │   │
│  │  Elasticsearch        │  ES_ENDPOINT env var (CloudFormation)│   │
│  │  Redis ElastiCache    │  spring.redis.primary property       │   │
│  └──────────────────────┴──────────────────────────────────────┘   │
│                                                                      │
│  THESE ARE NOT "DISCOVERED" — they're configured.                  │
│  The URLs are stable, managed by Nike platform teams.               │
│  They don't change on every deployment.                            │
│                                                                      │
│  SEMI-DYNAMIC:                                                      │
│  - AWS SDK discovers DynamoDB/SQS/S3 endpoints from region config  │
│  - Elasticsearch endpoint injected via CloudFormation parameter     │
│  - Redis primary/replica hosts from environment-specific properties │
│                                                                      │
│  WHY NO DYNAMIC DISCOVERY FOR EXTERNAL SERVICES:                   │
│  - Eventtia is a SaaS platform — stable URL, no discovery needed   │
│  - Nike internal APIs (NCP, Pairwise) use stable DNS names         │
│  - AWS services have stable regional endpoints                      │
│  - Dynamic discovery adds complexity for stable endpoints          │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — static configuration per environment:**

```properties
# application-prod-us.properties — external service URLs
# These are CONFIGURED, not DISCOVERED
eventtia.api.url=https://dashboard.eventtia.com
akamai.purge.url=https://purge.api.nike.com
```

```java
// ExperienceViewsNikeAppConfiguration.java — ES endpoint from env var
String endpoint = System.getenv("ES_ENDPOINT");
// Injected by CloudFormation at deploy time, not discovered at runtime
```

```yaml
# Rise GTS config — NCP URL in JSON config file
"url": "https://api.nike.com/ncp/ingest/v1"
# Static per environment, loaded from classpath
```

---

### Example 5: OSCAR Tokens — Discovery of "Who Can Call What"

**Scope:** Service-to-service authentication (authorization discovery)
**Pattern:** OSCAR scopes define which services can access which APIs

```
┌──────────────────────────────────────────────────────────────────────┐
│  OSCAR — Service Identity and Access Discovery                       │
│                                                                      │
│  Service discovery answers: "WHERE is Service B?"                   │
│  OSCAR answers: "CAN Service A call Service B?"                     │
│                                                                      │
│  cxp-event-registration wants to call Akamai Purge API:            │
│                                                                      │
│  1. cxp-reg requests OSCAR token with scope:                       │
│     "developer_enablement:cdn.services.cache_purge::create:"       │
│                                                                      │
│  2. OSCAR validates: "Is cxp-reg authorized for this scope?"       │
│     → YES → returns signed JWT token                               │
│                                                                      │
│  3. cxp-reg calls Akamai Purge API with Bearer token               │
│                                                                      │
│  4. Akamai verifies token scope → allows the purge                  │
│                                                                      │
│  SCOPES ACT AS A SERVICE DIRECTORY FOR AUTHORIZATION:              │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  "promotional_events:community.experiences.events::read:"   │    │
│  │  → cxp-events can READ event data                           │    │
│  │                                                              │    │
│  │  "membership:partner.consumer::create:"                     │    │
│  │  → cxp-reg can CREATE pairwise mappings                     │    │
│  │                                                              │    │
│  │  "developer_enablement:cdn.services.cache_purge::create:"   │    │
│  │  → cxp-reg can PURGE Akamai cache                           │    │
│  │                                                              │    │
│  │  "retail:rop.nsp.publisher::create:"                        │    │
│  │  → Rise GTS can PUBLISH to NSPv2/Kafka topics               │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  Every inter-service call has an OSCAR scope.                      │
│  This is like a service mesh authorization policy.                 │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Service Discovery Across CXP

| Layer | Scope | Mechanism | Pattern | Dynamic? |
|-------|-------|-----------|---------|----------|
| **Route53 DNS** | Global (which region) | Latency-based routing + health checks | Server-side (DNS) | Yes — auto-failover between regions |
| **NPE/Kubernetes** | Cluster (which pod) | K8s DNS + liveness/readiness probes | Server-side (K8s Service) | Yes — pods auto-register/deregister |
| **ALB Target Group** | Region (which service + which task) | Path-based listener rules + health checks | Server-side (ALB) | Yes — ECS auto-registers task IPs |
| **External APIs** | Cross-org (Eventtia, NCP, etc.) | Static URLs in application.properties | Configuration (not discovery) | No — stable endpoints |
| **AWS SDK** | AWS services (DynamoDB, S3, SQS) | SDK auto-resolves from region config | Client-side (SDK built-in) | Semi — regional endpoints stable |
| **OSCAR Scopes** | Authorization (who can call what) | OAuth2 scopes per service pair | Token-based policy | Yes — scopes updated without deploy |

---

## Common Interview Follow-ups

### Q: "Why not use Consul or Eureka for service discovery?"

> "We don't need them because our platform has server-side discovery built in at every layer. Route53 handles global routing, NPE/Kubernetes handles pod-level discovery with DNS and health probes, and ALB handles path-based routing to target groups. Consul/Eureka add value when you have VM-based services that can't rely on Kubernetes DNS or ALB. Since all our services run on NPE (Kubernetes) or ECS with ALB, we get discovery for free. Adding Consul would be another system to operate with no incremental benefit."

### Q: "What happens when a new ECS task starts? How does traffic reach it?"

> "Automatic registration in 4 steps:
> 1. ECS launches the task from the Docker image (new IP: 10.0.3.47)
> 2. ECS registers the IP:port with the ALB target group
> 3. ALB sends health check: `GET /actuator/health` → waits for 200
> 4. Health check passes → ALB starts routing traffic to the new task
>
> The entire process takes ~30 seconds. During this time, existing tasks handle all traffic. No manual registration, no config change, no DNS update. When the task is terminated, ECS drains connections (ALB sends no new requests) and deregisters the IP. This is why our stateless design (Topic 19) matters — the new task doesn't need to inherit any state from the old one."

### Q: "How does your platform handle the 'stale DNS cache' problem?"

> "Route53 records have TTL=300 seconds (5 minutes). If a region fails over, clients with cached DNS entries might hit the old region for up to 5 minutes. Mitigations: (1) Akamai CDN sits in front — CDN PoPs resolve DNS more frequently than end-user browsers, (2) the ALB in the failed region returns 503 → clients retry → eventually get fresh DNS, (3) for critical paths, we could lower TTL to 60 seconds (trade: more DNS queries = higher cost). In practice, the CDN layer absorbs most traffic, so stale browser DNS has minimal impact."

### Q: "How does this compare to a service mesh (Istio, Linkerd)?"

> "A service mesh adds a sidecar proxy to every pod for discovery, load balancing, mTLS, and observability. Our platform achieves similar outcomes differently: NPE handles discovery and health, ALB handles load balancing and TLS termination, OSCAR handles service-to-service auth, and Splunk handles observability. A service mesh would consolidate these into one system — potentially simpler operations but adds sidecar overhead (~50MB RAM, ~5ms latency per hop). For our scale, the current approach works well. I'd consider Istio if we had 50+ microservices with complex routing policies (canary deploys, fault injection, traffic splitting)."

---
---

# Topic 28: API Gateway

> Single entry point that handles authentication, rate limiting, routing, and SSL termination — shields internal services from direct exposure.

> **Interview Tip:** Explain the responsibilities — "I'd put Kong as API gateway to handle JWT validation, rate limiting per API key, and route /users to User Service, /orders to Order Service."

---

## What Is an API Gateway?

A **single entry point** for all client requests. Instead of clients calling each microservice directly, they call the gateway, which handles cross-cutting concerns and routes to the correct backend.

```
┌──────────────────────────────────────────────────────────────────────┐
│                          API GATEWAY                                 │
│                                                                      │
│  Single entry point for all client requests — routes, transforms,   │
│  and secures API traffic.                                           │
│                                                                      │
│  ┌────────┐                                                         │
│  │Web App │──┐                                    ┌──────────┐┌──┐│
│  └────────┘  │     ┌──────────────────┐          │User      ││DB││
│              ├────▶│   API GATEWAY    │─────────▶│Service   │└──┘│
│  ┌────────┐  │     │                  │          └──────────┘     │
│  │Mobile  │──┤     │ • Authentication │          ┌──────────┐┌──┐│
│  └────────┘  │     │ • Rate Limiting  │─────────▶│Order     ││DB││
│              │     │ • Request Routing│          │Service   │└──┘│
│  ┌────────┐  │     │ • Load Balancing │          └──────────┘     │
│  │Partner │──┘     │ • SSL Termination│          ┌──────────┐┌──┐│
│  └────────┘        └──────────────────┘─────────▶│Product   ││DB││
│                                                   │Service   │└──┘│
│                                                   └──────────┘     │
│                                                                      │
│  KEY RESPONSIBILITIES:                                              │
│  ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌──────────┐ ┌───────┐│
│  │ Authenti- │ │   Rate    │ │  Request  │ │ Response │ │Circuit││
│  │ cation    │ │  Limiting │ │  Routing  │ │  Cache   │ │Breaker││
│  │ JWT,OAuth │ │  Protect  │ │ /users →  │ │ Reduce   │ │ Fail  ││
│  │ API Keys  │ │  abuse    │ │ User Svc  │ │ backend  │ │ fast  ││
│  └───────────┘ └───────────┘ └───────────┘ └──────────┘ └───────┘│
│                                                                      │
│  Popular: Kong, AWS API Gateway, NGINX, Apigee, Traefik            │
│  Can become bottleneck — scale horizontally, use caching            │
└──────────────────────────────────────────────────────────────────────┘
```

---

## API Gateway Responsibilities

```
┌──────────────────────────────────────────────────────────────────────┐
│  WHAT AN API GATEWAY DOES                                            │
│                                                                      │
│  1. AUTHENTICATION & AUTHORIZATION                                  │
│     Validate JWT tokens, API keys, OAuth scopes.                    │
│     Reject unauthorized requests BEFORE they reach services.        │
│                                                                      │
│  2. RATE LIMITING & THROTTLING                                      │
│     Enforce per-user, per-API, per-IP rate limits.                  │
│     Return 429 when exceeded.                                       │
│                                                                      │
│  3. REQUEST ROUTING                                                 │
│     Route /community/events/* → cxp-events service.                │
│     Route /community/event_registrations/* → cxp-reg service.      │
│     Path-based, header-based, or host-based routing.                │
│                                                                      │
│  4. SSL/TLS TERMINATION                                             │
│     Handle HTTPS encryption/decryption at the gateway.              │
│     Backend services communicate over plain HTTP (faster).          │
│                                                                      │
│  5. RESPONSE CACHING                                                │
│     Cache GET responses to reduce backend load.                     │
│     Return cached response for identical requests.                  │
│                                                                      │
│  6. REQUEST/RESPONSE TRANSFORMATION                                 │
│     Add/remove headers, transform payloads, protocol translation.  │
│                                                                      │
│  7. CIRCUIT BREAKER                                                 │
│     Fail fast when backend is down. Return cached/error response.  │
│                                                                      │
│  8. OBSERVABILITY                                                   │
│     Log all requests, collect metrics, trace distributed calls.     │
└──────────────────────────────────────────────────────────────────────┘
```

---

## API Gateway In My CXP Projects

### The CXP Platform — Distributed API Gateway (Not a Single Box)

Our platform doesn't use a traditional API Gateway product (no Kong, no AWS API Gateway). Instead, gateway responsibilities are **distributed across multiple layers**, each handling specific concerns.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — DISTRIBUTED API GATEWAY                                   │
│                                                                          │
│  Traditional API Gateway:          CXP's Distributed Approach:          │
│  ┌──────────────────┐             ┌──────────────────────────────────┐ │
│  │   ONE Gateway     │             │  Responsibility split across:    │ │
│  │   does everything │             │                                  │ │
│  │   (Kong, Apigee)  │             │  Akamai CDN    → Caching, DDoS │ │
│  └──────────────────┘             │  Route53       → Geo-routing    │ │
│                                    │  ALB           → Path routing,  │ │
│                                    │                  SSL termination│ │
│                                    │  AAAConfig.java→ JWT validation │ │
│                                    │  @JwtScope     → Scope authz   │ │
│                                    │  Redis counter → Rate limiting  │ │
│                                    │  OSCAR tokens  → Service auth   │ │
│                                    └──────────────────────────────────┘ │
│                                                                          │
│  SAME RESPONSIBILITIES, different implementation.                       │
└──────────────────────────────────────────────────────────────────────────┘
```

### The Full Request Flow — Gateway Responsibilities at Each Layer

```
┌──────────────────────────────────────────────────────────────────────────┐
│  FULL REQUEST FLOW — API GATEWAY RESPONSIBILITIES                         │
│                                                                          │
│  User: POST /community/event_registrations/v1                           │
│  Header: Authorization: Bearer <JWT>                                    │
│  Body: { eventId: 73067, ... }                                          │
│                                                                          │
│  LAYER 1: AKAMAI CDN (Edge Gateway)                                     │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  ✓ DDoS protection (WAF rules — managed by Nike platform)       │  │
│  │  ✓ SSL termination (edge TLS → backend HTTP/HTTPS)              │  │
│  │  ✓ Response caching (GET requests: cache-maxage per resource)   │  │
│  │  ✗ NOT for POST registration (not cacheable, passes through)    │  │
│  │  ✓ Geographic routing (nearest PoP)                              │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│       │                                                                  │
│       ▼                                                                  │
│  LAYER 2: ROUTE53 (DNS Gateway)                                         │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  ✓ Latency-based routing (nearest healthy region)                │  │
│  │  ✓ Health check failover (us-east-1 down → us-west-2)          │  │
│  │  ✓ Service discovery via DNS naming convention                   │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│       │                                                                  │
│       ▼                                                                  │
│  LAYER 3: ALB — cxp-alb (Application Gateway)                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  ✓ SSL termination (HTTPS:443 → HTTP:8080 to containers)       │  │
│  │  ✓ Path-based routing (/community/event_registrations/*         │  │
│  │    → cxp-event-registration target group)                        │  │
│  │  ✓ Load balancing (round-robin across healthy ECS tasks)        │  │
│  │  ✓ Health checks (/actuator/health every 10s)                   │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│       │                                                                  │
│       ▼                                                                  │
│  LAYER 4: APPLICATION (In-Service Gateway Logic)                        │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  ✓ JWT authentication (AAAConfig.java validates token signature) │  │
│  │  ✓ Scope authorization (@JwtScope, @AccessValidator)             │  │
│  │  ✓ Rate limiting (Redis failure counter + 429)                   │  │
│  │  ✓ Idempotency (Redis cached response for duplicates)           │  │
│  │  ✓ Request validation (Spring Bean Validation)                  │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│       │                                                                  │
│       ▼                                                                  │
│  BUSINESS LOGIC: Call Eventtia, write to Redis/DynamoDB, etc.           │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: ALB as Path-Based API Router

**Component:** `cxp-alb` (shared ALB)
**Gateway role:** Request routing + SSL termination + health-based load balancing

```
┌──────────────────────────────────────────────────────────────────────┐
│  ALB as API Gateway — Path-Based Routing                             │
│                                                                      │
│  ONE ALB serves as the API router for ALL CXP microservices:       │
│                                                                      │
│  Client                                                             │
│    │                                                                │
│    ▼                                                                │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │  ALB (cxp-alb) — HTTPS:443                                    │ │
│  │                                                                │ │
│  │  Path Rule                    → Service (Target Group)        │ │
│  │  ─────────────────────────    ─────────────────────────       │ │
│  │  /community/events/*          → cxp-events (2-8 tasks)       │ │
│  │  /community/event_seats_*     → cxp-events (same)            │ │
│  │  /community/groups/*          → cxp-events (same)            │ │
│  │  /community/event_reg*        → cxp-event-reg (2-8 tasks)   │ │
│  │  /community/attendee_*        → cxp-event-reg (same)        │ │
│  │  /engage/experience_*         → expviewsnikeapp (N tasks)    │ │
│  │  /data/transform/v1*          → rise-gts (N tasks)           │ │
│  │                                                                │ │
│  │  SSL: Terminates HTTPS at ALB.                                │ │
│  │       ALB → containers over HTTP:8080 (internal, no TLS)     │ │
│  │  ACM: Certificate from AWS Certificate Manager                │ │
│  │                                                                │ │
│  │  EQUIVALENT TO: Kong route config or API Gateway resource     │ │
│  └───────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  WHY ALB INSTEAD OF AWS API GATEWAY:                                │
│  - ALB is simpler for path-based routing (no Lambda overhead)      │
│  - ALB natively integrates with ECS target groups (auto-register)  │
│  - ALB is ~$16/month vs API Gateway $3.50/million requests        │
│  - We don't need API Gateway features: usage plans, API keys,     │
│    SDK generation, request transformation                          │
│  - Our auth is in-service (AAAConfig), not at the gateway          │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual Terraform:**

```hcl
// ALB listener rules = API Gateway route definitions
resource "aws_alb_listener_rule" "alb_listener_rule1" {
  listener_arn = data.aws_alb_listener.https.arn   // HTTPS:443
  action {
    type             = "forward"
    target_group_arn = aws_alb_target_group.tg.arn  // cxp-events tasks
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

---

### Example 2: AAAConfig — In-Service Authentication Gateway

**Component:** `AAAConfig.java` (in cxp-events and cxp-event-registration)
**Gateway role:** JWT validation — the first check inside every secured endpoint

```
┌──────────────────────────────────────────────────────────────────────┐
│  AAAConfig — JWT Authentication (In-Service Gateway)                 │
│                                                                      │
│  Traditional API Gateway:          Our Approach:                    │
│  Gateway validates JWT →           ALB passes request through →    │
│  forwards to service               Service validates JWT itself     │
│                                                                      │
│  JWT VALIDATION FLOW:                                               │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  1. Request arrives with: Authorization: Bearer <JWT>       │    │
│  │                                                              │    │
│  │  2. AAAConfig.java:                                         │    │
│  │     - Fetch public keys from S3 (cached with TTL)           │    │
│  │       URL: s3://publickeys.foundation-prod.nikecloud.com    │    │
│  │     - Validate JWT signature using NikeSimpleJwtJWSValidator │    │
│  │     - Check token expiry (ValidationRuleJwtTime)            │    │
│  │     - Verify issuer: "oauth2acc" (consumer tokens)          │    │
│  │     - Extract PRN (user ID) from JWT payload                │    │
│  │                                                              │    │
│  │  3. AccessValidatorAspect.java:                             │    │
│  │     - @Before AOP advice on @AccessValidator endpoints       │    │
│  │     - If validation fails → throw UnauthorizedException     │    │
│  │     - If passes → controller executes with user context     │    │
│  │                                                              │    │
│  │  4. @JwtScope annotation:                                    │    │
│  │     - Checks specific OAuth scopes for service-to-service   │    │
│  │     @JwtScope(EVENTS_PURGE_CACHE_DELETE_SCOPE)              │    │
│  │     → Only callers with "events_cache::delete:" scope       │    │
│  │       can call /purge-cache                                  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  TWO AUTH MODELS:                                                   │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  CONSUMER (user-facing):                                    │    │
│  │  Token from accounts.nike.com (OAuth2 consumer flow)        │    │
│  │  Validated by AAAConfig → PRN extracted for user identity   │    │
│  │  Used by: /event_registrations, /attendee_status            │    │
│  │                                                              │    │
│  │  SERVICE-TO-SERVICE (internal):                              │    │
│  │  OSCAR token with specific scopes                           │    │
│  │  Validated by @JwtScope annotation                          │    │
│  │  Used by: /purge-cache (NSP3 sink calling cxp-events)      │    │
│  │           /data/transform/v1 (NSP3 sink calling Rise GTS)  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  @Unsecured — public endpoints (no auth required):                 │
│  GET /community/events/v1/{id}     → event detail page (public)   │
│  GET /community/events/v1          → landing page (public)        │
│  GET /community/events_health/v1   → health check (public)        │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// AAAConfig.java — JWT validation config
// Public keys fetched from S3, cached with TTL
// Token issuer: "oauth2acc" for consumer tokens
// NikeSimpleJwtJWSValidator validates signature
// ValidationRuleJwtTime checks expiry

// AccessValidatorAspect.java — @Before AOP gate
@Before("@annotation(AccessValidator)")
public void validate(JoinPoint joinPoint) {
    // Validates JWT, throws UnauthorizedException on failure
}

// Two endpoint types:
@PostMapping
@AccessValidator   // SECURED — requires valid consumer JWT
public Mono<ResponseEntity<EventRegistrationResponse>> registerEventUser(...)

@GetMapping("/{event_id}")
@Unsecured         // PUBLIC — no JWT required (event details are public)
public Mono<ResponseEntity<Event>> getEventDetailPage(...)

@PostMapping("purge-cache")
@JwtScope(EVENTS_PURGE_CACHE_DELETE_SCOPE)  // SERVICE-TO-SERVICE scope
public ResponseEntity<Void> purgeCache(...)
```

**Interview answer:**
> "Authentication is handled in-service rather than at a central API gateway. Each service has `AAAConfig.java` that validates JWT tokens using public keys cached from S3. We have three security levels: `@Unsecured` for public endpoints like event detail pages, `@AccessValidator` for consumer-authenticated endpoints like registration, and `@JwtScope` for service-to-service calls with specific OSCAR scopes. This keeps auth logic close to the business logic — the service knows which endpoints are public vs secured, not an external gateway."

---

### Example 3: Akamai as Edge API Gateway

**Component:** Akamai CDN (250+ PoPs globally)
**Gateway role:** DDoS protection, response caching, SSL, geographic routing

```
┌──────────────────────────────────────────────────────────────────────┐
│  Akamai as Edge API Gateway                                          │
│                                                                      │
│  GATEWAY RESPONSIBILITIES AKAMAI HANDLES:                           │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ✓ DDoS / WAF protection                                    │    │
│  │    IP-based rate limiting, bot detection, WAF rules          │    │
│  │    Blocks attacks before they reach AWS infrastructure       │    │
│  │                                                              │    │
│  │  ✓ SSL/TLS termination (edge)                                │    │
│  │    User's browser ←TLS→ Akamai PoP ←TLS→ ALB               │    │
│  │    Two TLS hops, but edge termination = lower latency       │    │
│  │                                                              │    │
│  │  ✓ Response caching (GET requests)                           │    │
│  │    Edge-Control: cache-maxage=60m (event pages)             │    │
│  │    Edge-Control: cache-maxage=1m (seat counts)              │    │
│  │    Tag-based purge on event updates                         │    │
│  │    95% cache hit ratio = 95% of traffic never reaches ALB   │    │
│  │                                                              │    │
│  │  ✓ Geographic routing                                        │    │
│  │    User in Tokyo → Tokyo PoP → us-west-2 origin             │    │
│  │    User in London → London PoP → us-east-1 origin           │    │
│  │                                                              │    │
│  │  ✗ NOT handling: JWT validation, rate limiting per user,     │    │
│  │    request transformation, circuit breaking                  │    │
│  │    (these are handled in-service)                            │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### How CXP's Distributed Gateway Maps to Traditional Gateway Features

```
┌──────────────────────────────────────────────────────────────────────┐
│  TRADITIONAL GATEWAY vs CXP DISTRIBUTED GATEWAY                     │
│                                                                      │
│  Gateway Feature       │ Traditional (Kong)  │ CXP Implementation   │
│  ──────────────────────┼─────────────────────┼────────────────────  │
│  DDoS protection       │ Kong plugin          │ Akamai WAF (edge)   │
│  SSL termination       │ Kong TLS             │ Akamai (edge) +     │
│                        │                      │ ALB (regional)      │
│  Response caching      │ Kong cache plugin    │ Akamai Edge-Cache   │
│                        │                      │ (60m/1m TTL)        │
│  Geographic routing    │ Kong + DNS           │ Route53 latency     │
│  Path-based routing    │ Kong routes          │ ALB listener rules  │
│  Load balancing        │ Kong upstream        │ ALB target groups   │
│  JWT authentication    │ Kong JWT plugin      │ AAAConfig.java      │
│                        │                      │ (in-service)        │
│  Scope authorization   │ Kong ACL plugin      │ @JwtScope, @Access- │
│                        │                      │ Validator (in-svc)  │
│  Rate limiting (user)  │ Kong rate-limit      │ Redis counter +     │
│                        │ plugin               │ 429 (in-service)    │
│  Rate limiting (IP)    │ Kong IP restriction  │ Akamai WAF (edge)   │
│  Health checks         │ Kong health checks   │ ALB + NPE probes    │
│  Service discovery     │ Kong + Consul        │ Route53 + ALB + K8s │
│  Circuit breaker       │ Kong circuit-breaker │ try-catch fallbacks  │
│                        │                      │ (in-service, T17)   │
│  Request transform     │ Kong transformer     │ Not needed           │
│  API versioning        │ Kong path prefix     │ URL: /v1/ in path   │
│  Request logging       │ Kong logging         │ Splunk via Kinesis   │
│  Metrics               │ Kong Prometheus      │ CloudWatch + Splunk  │
│  ──────────────────────┼─────────────────────┼────────────────────  │
│  Single point of       │ YES (Kong is SPOF   │ NO — each layer is   │
│  failure?              │ unless clustered)    │ independent + managed│
└──────────────────────────────────────────────────────────────────────┘
```

---

### Why Distributed Gateway (Not a Single API Gateway Product)

```
┌──────────────────────────────────────────────────────────────────────┐
│  WHY CXP DOESN'T USE KONG / AWS API GATEWAY                         │
│                                                                      │
│  1. NIKE ALREADY HAS AKAMAI (enterprise CDN)                       │
│     DDoS, WAF, caching, SSL all handled at the edge.               │
│     Adding Kong = duplicate SSL termination + caching layer.        │
│                                                                      │
│  2. ALB IS CHEAPER AND SIMPLER FOR PATH ROUTING                    │
│     ALB: ~$16/month. API Gateway: $3.50/million requests.          │
│     At our traffic: ALB is 5-10× cheaper.                          │
│     ALB natively integrates with ECS (auto-register tasks).        │
│                                                                      │
│  3. AUTH IS BUSINESS LOGIC                                          │
│     Some endpoints are @Unsecured (public event pages).             │
│     Some need consumer JWT (@AccessValidator).                      │
│     Some need service JWT (@JwtScope with OSCAR).                   │
│     This auth logic is tightly coupled to the API design —         │
│     better in the service than in a generic gateway.                │
│                                                                      │
│  4. NO SINGLE POINT OF FAILURE                                      │
│     Kong/API Gateway = single chokepoint for ALL traffic.           │
│     If Kong goes down, ALL services are unreachable.                │
│     Our approach: Akamai is managed (99.99% SLA), ALB is managed   │
│     (99.99%), auth is per-service (no shared failure).              │
│                                                                      │
│  WHEN WE'D ADD A DEDICATED API GATEWAY:                            │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  - If Nike exposed CXP APIs to external PARTNERS              │    │
│  │    (need: API keys, usage plans, throttling per partner,     │    │
│  │     developer portal, SDK generation)                        │    │
│  │  - If we needed request/response TRANSFORMATION               │    │
│  │    (JSON→XML, header injection, payload mapping)             │    │
│  │  - If we had 50+ microservices and needed centralized        │    │
│  │    auth policy management                                    │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: API Gateway Responsibilities Across CXP

| Responsibility | Component | Layer | How |
|---------------|-----------|-------|-----|
| **DDoS protection** | Akamai WAF | Edge | IP-based rules, bot detection (managed) |
| **SSL termination** | Akamai + ALB | Edge + Regional | Two-hop TLS: client→Akamai, Akamai→ALB |
| **Response caching** | Akamai | Edge | Edge-Control headers, 1-60 min TTL, tag purge |
| **Geographic routing** | Route53 | DNS | Latency-based to nearest healthy region |
| **Path-based routing** | ALB listener rules | Regional | `/community/events/*` → cxp-events TG |
| **Load balancing** | ALB target groups | Regional | Round-robin across healthy ECS tasks |
| **JWT authentication** | AAAConfig.java | In-service | Public keys from S3, signature validation |
| **Scope authorization** | @JwtScope, @AccessValidator | In-service | Consumer JWT vs OSCAR service token |
| **Rate limiting (user)** | Redis counter + 429 | In-service | 5 attempts/min per user+event (Topic 16) |
| **Rate limiting (IP)** | Akamai WAF | Edge | Managed by Nike platform team |
| **Health checks** | ALB + NPE probes | Regional + Platform | /actuator/health + /events_health/v1 |
| **Service discovery** | Route53 + ALB + K8s | All layers | DNS → path routing → pod selection (Topic 27) |
| **Observability** | Splunk via Kinesis | All services | Stdout → Docker → Kinesis → Splunk |

---

## Common Interview Follow-ups

### Q: "Isn't putting auth in the service less secure than at a gateway?"

> "Not inherently. A gateway centralizes auth but doesn't eliminate it — the service still needs to know the user's identity (extracted from JWT). In our case, AAAConfig.java validates the JWT signature using public keys from S3 and extracts the PRN (user ID). This is the same validation Kong's JWT plugin would do. The advantage of in-service auth: each service controls which endpoints are public (`@Unsecured`) vs secured (`@AccessValidator`) vs service-scoped (`@JwtScope`). With a centralized gateway, we'd need to maintain a routing table mapping paths to auth policies outside the service — one more thing to keep in sync on every API change."

### Q: "What if ALB goes down?"

> "ALB is a managed AWS service with 99.99% SLA — AWS runs it across multiple AZs within a region. If one ALB AZ is unreachable, ALB automatically routes through other AZs. If the ENTIRE ALB in us-east-1 goes down (extremely rare), Route53 health checks detect the failure and failover all traffic to us-west-2's ALB within ~30 seconds. This is more resilient than a self-managed Kong cluster, which we'd need to operate, scale, and monitor ourselves."

### Q: "How would you add API key management for external partners?"

> "I'd add AWS API Gateway (HTTP API type) as a thin layer between Akamai and ALB — specifically for partner-facing endpoints. API Gateway would handle: (1) API key validation and usage plans, (2) per-partner rate limiting (1000 req/hour for Partner A, 5000 for Partner B), (3) a developer portal for key management. Internal traffic (nike.com → cxp-events) would continue going directly through ALB — no API Gateway overhead for Nike's own frontends. This is the BFF (Backend for Frontend) pattern: different gateway config for different client types."

### Q: "Your approach has auth in every service — doesn't that mean code duplication?"

> "The auth logic (`AAAConfig.java`, `AccessValidatorAspect.java`) is in a shared Nike library that all Spring Boot services import — not duplicated. Each service adds `@AccessValidator` or `@Unsecured` annotations to its controllers, but the validation logic is the same library across cxp-events, cxp-event-registration, and expviewsnikeapp. This is equivalent to Kong applying the same JWT plugin to different routes — same validation, different policies per endpoint."

---
---

# Topic 29: Monolith vs Microservices

> Monolith is simpler to start but harder to scale; microservices offer independent scaling and deployment but add distributed complexity.

> **Interview Tip:** Don't default to microservices — "I'd start with a modular monolith for faster iteration, then extract services as we identify scaling bottlenecks and clear domain boundaries."

---

## The Core Tradeoff

```
┌──────────────────────────────────────────────────────────────────────────┐
│              MONOLITH vs MICROSERVICES                                     │
│                                                                          │
│  ┌──────────────────────────┐    ┌──────────────────────────────────┐  │
│  │       MONOLITH           │    │        MICROSERVICES              │  │
│  │  Single deployable unit  │    │  Independent deployable services  │  │
│  │                          │    │                                   │  │
│  │  ┌──────────────────┐   │    │  ┌───────┐ -- ┌───────┐         │  │
│  │  │   Application    │   │    │  │ User  │    │ Order │         │  │
│  │  │ ┌─────┐┌──────┐ │   │    │  │Service│    │Service│         │  │
│  │  │ │Users││Orders│ │   │    │  └───┬───┘    └───┬───┘         │  │
│  │  │ └─────┘└──────┘ │   │    │      │            │              │  │
│  │  │ ┌────────────┐  │   │    │   ┌──┴──┐      ┌──┴──┐          │  │
│  │  │ │ Products   │  │   │    │   │User │      │Order│          │  │
│  │  │ └────────────┘  │   │    │   │ DB  │      │ DB  │          │  │
│  │  │ ┌────────────┐  │   │    │   └─────┘      └─────┘          │  │
│  │  │ │ Shared DB  │  │   │    │                                   │  │
│  │  │ └────────────┘  │   │    │  ┌──────────────────────────┐   │  │
│  │  └──────────────────┘   │    │  │  Message Bus (Kafka/SQS) │   │  │
│  │         │               │    │  └──────────────────────────┘   │  │
│  │    ┌────┴────┐          │    │  ┌──────────────────────────┐   │  │
│  │    │Database │          │    │  │      API Gateway          │   │  │
│  │    └─────────┘          │    │  └──────────────────────────┘   │  │
│  │                          │    │                                   │  │
│  │  [+] Simple to develop   │    │  [+] Scale services independently│  │
│  │  [+] Easy to test e2e    │    │  [+] Tech diversity per service  │  │
│  │  [+] Single deployment   │    │  [+] Fault isolation             │  │
│  │                          │    │                                   │  │
│  │  [-] Harder to scale     │    │  [-] Distributed complexity      │  │
│  │      specific parts      │    │  [-] Network latency, debugging  │  │
│  │  [-] One bug can crash   │    │      hard                        │  │
│  │      everything          │    │                                   │  │
│  └──────────────────────────┘    └──────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## The Spectrum (Not Binary)

```
┌──────────────────────────────────────────────────────────────────────┐
│  THE ARCHITECTURE SPECTRUM                                           │
│                                                                      │
│  Monolith          Modular          Microservices      Nano/         │
│  (1 deploy)        Monolith         (N deploys)        Serverless    │
│  ◄─────────────────────────────────────────────────────────────────▶│
│                                                                      │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐      ┌──────────┐    │
│  │ All code │   │ Modules  │   │ Services │      │ Functions│    │
│  │ in 1 app │   │ in 1 app │   │ in N apps│      │ per      │    │
│  │ 1 DB     │   │ clean    │   │ N DBs    │      │ endpoint │    │
│  │          │   │ boundaries│   │ API calls│      │          │    │
│  └──────────┘   └──────────┘   └──────────┘      └──────────┘    │
│                                                                      │
│  Simplest                                           Most complex    │
│  Least scalable                                     Most scalable   │
│                                                                      │
│  CXP: Microservices with 4 services + shared ALB + Kafka bus       │
│  email-drop-recovery: Monolith (single Python app)                  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## My CXP Platform — Microservices Architecture

### The 4 CXP Microservices

Our platform is a clear microservices architecture with **4 independently deployable services**, each owning its own data stores, connected via Kafka and REST.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — MICROSERVICES MAP                                         │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐│
│  │                     CLIENTS                                          ││
│  │  nike.com (browser)    NRC App (mobile)    NSP3 sinks (internal)   ││
│  └────────────────────────────┬──────────────────────────────────────┘│
│                               │                                        │
│                     ┌─────────▼─────────┐                             │
│                     │ cxp-alb (ALB)     │  Shared entry point         │
│                     │ Path-based routing │                             │
│                     └────┬──────┬───┬───┘                             │
│                     ┌────┘      │   └────┐                            │
│                     ▼           ▼        ▼                            │
│  ┌─────────────────────┐ ┌──────────────────┐ ┌────────────────────┐│
│  │   cxp-events        │ │cxp-event-        │ │  expviewsnikeapp   ││
│  │   (Spring Boot)     │ │registration      │ │  (Spring Boot)     ││
│  │                     │ │(Spring Boot)     │ │                    ││
│  │  Event details,     │ │                  │ │  Event search,     ││
│  │  landing pages,     │ │  Registration,   │ │  discovery,        ││
│  │  groups, seats,     │ │  cancellation,   │ │  landing views     ││
│  │  calendar URLs      │ │  attendee status │ │  (Nike App)        ││
│  │                     │ │                  │ │                    ││
│  │  Data: Eventtia API │ │  Data: Eventtia, │ │  Data: Elastic-    ││
│  │  Cache: Caffeine,   │ │  Redis, DynamoDB │ │  search, Guava     ││
│  │  Akamai CDN         │ │                  │ │  cache             ││
│  └─────────────────────┘ └──────────────────┘ └────────────────────┘│
│                                                                       │
│  ┌─────────────────────┐                                             │
│  │  rise-generic-       │  (Shared transform service, not CXP-only) │
│  │  transform-service   │  Processes webhooks for MANY Nike teams.  │
│  │  (Spring Boot)       │  CXP is one of many consumers.            │
│  │                      │                                            │
│  │  Data: S3, SQS,      │                                            │
│  │  NSPv2/Kafka          │                                            │
│  └─────────────────────┘                                             │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  MESSAGE BUS: NSP3/Kafka (event streaming between services)      │ │
│  │  partnerhub_notification_stream → Rise GTS, S3 sink, Purge sink │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  SHARED INFRA: ALB, Route53, Akamai CDN, Splunk, OSCAR         │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### How CXP's Architecture Demonstrates Microservice Principles

```
┌──────────────────────────────────────────────────────────────────────┐
│  MICROSERVICE PRINCIPLE         │  HOW CXP IMPLEMENTS IT            │
│  ───────────────────────────────┼──────────────────────────────────│
│                                                                      │
│  1. SINGLE RESPONSIBILITY       │  cxp-events: READ event data     │
│     Each service does one       │  cxp-reg: WRITE registrations    │
│     thing well.                 │  expviews: SEARCH events (mobile)│
│                                 │  Rise GTS: TRANSFORM data         │
│  ───────────────────────────────┼──────────────────────────────────│
│                                                                      │
│  2. OWN YOUR DATA               │  cxp-events: Eventtia API +      │
│     Each service has its own    │    Caffeine cache                 │
│     data store. No shared DB.   │  cxp-reg: Redis + DynamoDB       │
│                                 │  expviews: Elasticsearch          │
│                                 │  Rise GTS: S3 + SQS              │
│  ───────────────────────────────┼──────────────────────────────────│
│                                                                      │
│  3. INDEPENDENT DEPLOYMENT      │  Each service has own:           │
│     Deploy one service without  │  - Jenkins pipeline              │
│     touching others.            │  - ECS task definition           │
│                                 │  - NPE component YAML            │
│                                 │  - Docker image in Artifactory   │
│  ───────────────────────────────┼──────────────────────────────────│
│                                                                      │
│  4. INDEPENDENT SCALING         │  cxp-reg: 2→8 tasks during      │
│     Scale each service based    │    sneaker launch (write-heavy)  │
│     on its own traffic.         │  cxp-events: 2 tasks (CDN       │
│                                 │    absorbs 95% of reads)         │
│                                 │  expviews: N tasks (ES handles   │
│                                 │    the search load)              │
│  ───────────────────────────────┼──────────────────────────────────│
│                                                                      │
│  5. COMMUNICATE VIA APIs        │  Sync: REST over HTTP (Eventtia, │
│     Services don't share code   │    Pairwise, NCP)                │
│     or memory — only APIs.      │  Async: Kafka/NSP3 (webhooks),  │
│                                 │    SQS (S3 event notifications)  │
│  ───────────────────────────────┼──────────────────────────────────│
│                                                                      │
│  6. FAULT ISOLATION             │  cxp-events down → registration  │
│     One service failing doesn't │    still works (different service)│
│     take down others.           │  Redis down → cxp-reg degrades  │
│                                 │    gracefully (try-catch fallback)│
│                                 │  Rise GTS down → Kafka buffers  │
│                                 │    messages (replay when back)   │
│  ───────────────────────────────┼──────────────────────────────────│
│                                                                      │
│  7. TECH DIVERSITY              │  cxp-events: Spring Boot + WebFlux│
│     Use the right tech per      │  cxp-reg: Spring Boot + WebFlux  │
│     service.                    │  expviews: Spring Boot + MVC     │
│                                 │  Rise GTS: Spring Boot + Hystrix │
│                                 │  email-recovery: Python (no FW)  │
└──────────────────────────────────┘──────────────────────────────────┘
```

---

### The email-drop-recovery Tool — A Monolith Inside a Microservice Platform

Interesting contrast: the recovery dashboard is a **monolith** — single Python file, no framework, handles everything from HTTP serving to database queries.

```
┌──────────────────────────────────────────────────────────────────────┐
│  email-drop-recovery — Monolith by Choice                            │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  server.py (single file, ~645 lines)                          │  │
│  │                                                               │  │
│  │  • HTTP server (Python standard library, no Flask/Django)     │  │
│  │  • Route dispatch (URL parsing, manual routing)               │  │
│  │  • Splunk client integration (REST API calls)                 │  │
│  │  • Athena client integration (boto3 queries)                  │  │
│  │  • Reprocess logic (S3 fetch → Rise GTS POST)                │  │
│  │  • HTML template serving (templates/index.html)              │  │
│  │  • JSON API endpoints (for dashboard AJAX calls)              │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  WHY MONOLITH IS CORRECT HERE:                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ✓ Single user (operations team, not millions of users)      │    │
│  │  ✓ Runs locally (localhost:8050, not on AWS)                 │    │
│  │  ✓ No scaling needed (one person uses it at a time)          │    │
│  │  ✓ Fast to develop and modify (one file, no build system)    │    │
│  │  ✓ Easy to understand (new team member reads one file)       │    │
│  │  ✓ No deployment pipeline needed (run with python3 server.py)│    │
│  │                                                              │    │
│  │  Microservices here would be OVERENGINEERING:                │    │
│  │  - "Splunk Query Service" + "Athena Query Service" +         │    │
│  │    "Reprocess Service" + "Dashboard UI Service"              │    │
│  │  - 4 services for 1 user = waste of complexity.              │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  HOWEVER, the monolith has MODULAR STRUCTURE:                      │
│  ├── server.py          # HTTP handler + route dispatch            │
│  ├── splunk_client.py   # Splunk REST API wrapper                  │
│  ├── athena_client.py   # Athena query helper                      │
│  ├── queries.py         # Splunk SPL query builders                │
│  ├── reprocess.py       # RISE API reprocessing logic              │
│  └── templates/index.html  # Dashboard UI                          │
│                                                                      │
│  This is a MODULAR MONOLITH — clear separation of concerns         │
│  within a single deployable. Best of both worlds for this use case.│
└──────────────────────────────────────────────────────────────────────┘
```

---

### The Cost of Microservices — What CXP Pays For

```
┌──────────────────────────────────────────────────────────────────────┐
│  DISTRIBUTED COMPLEXITY WE MANAGE IN CXP                             │
│                                                                      │
│  CHALLENGE                  │  HOW WE HANDLE IT                     │
│  ──────────────────────────┼────────────────────────────────────── │
│  Distributed transactions   │  Saga choreography via Kafka + DLQ + │
│  (no shared ACID DB)        │  recovery dashboard (Topic 24)       │
│  ──────────────────────────┼────────────────────────────────────── │
│  Service discovery          │  Route53 + ALB + K8s DNS              │
│  (dynamic IPs)              │  (Topic 27)                           │
│  ──────────────────────────┼────────────────────────────────────── │
│  Data consistency           │  Eventual consistency + compensating  │
│  (each service owns its DB) │  mechanisms (Topic 1 CAP, Topic 26)  │
│  ──────────────────────────┼────────────────────────────────────── │
│  Network failures           │  Retry + backoff + try-catch fallback│
│  (inter-service calls fail) │  + @Retryable + @Recover (Topic 17) │
│  ──────────────────────────┼────────────────────────────────────── │
│  Distributed debugging      │  Splunk centralized logging, trace   │
│  (where did it fail?)       │  IDs, Investigation tab (5 sources)  │
│  ──────────────────────────┼────────────────────────────────────── │
│  Deployment coordination    │  Independent Jenkins pipelines per   │
│  (4 services, 2 regions)    │  service; NPE handles rollout       │
│  ──────────────────────────┼────────────────────────────────────── │
│  Config management          │  Secrets Manager + S3 feature flags  │
│  (env-specific settings)    │  per service, per environment        │
│  ──────────────────────────┼────────────────────────────────────── │
│  Monitoring                 │  Splunk alerts per service, Route53  │
│  (4 services × 2 regions)   │  health checks, ALB health checks   │
│  ──────────────────────────┼────────────────────────────────────── │
│  Email drop (2-5%)          │  THIS IS A DIRECT COST of async     │
│                             │  microservice communication. A       │
│                             │  monolith with sync pipeline would   │
│                             │  have 0% drops (but 30s latency).   │
└──────────────────────────────────────────────────────────────────────┘
```

---

### When CXP's Architecture Would Differ

```
┌──────────────────────────────────────────────────────────────────────┐
│  IF CXP WERE STARTING TODAY — WHAT I'D DO DIFFERENTLY               │
│                                                                      │
│  START: Modular monolith (2 services, not 4)                        │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Service 1: cxp-backend                                     │    │
│  │  Contains: Events module + Registration module               │    │
│  │  Why combined: Same team, same Eventtia dependency,          │    │
│  │  same deployment cadence, low traffic initially.             │    │
│  │                                                              │    │
│  │  Service 2: rise-generic-transform-service                   │    │
│  │  Keep separate: different team, shared across Nike,          │    │
│  │  different scaling needs (SQS-driven, not user-facing).      │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  EXTRACT when these signals appear:                                 │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Signal 1: Registration needs 8 tasks during sneaker launch  │    │
│  │  but Events needs only 2 (CDN absorbs reads).                │    │
│  │  → Extract registration as separate service for              │    │
│  │    independent scaling.                                      │    │
│  │                                                              │    │
│  │  Signal 2: Different teams want different deploy cadences.   │    │
│  │  Events team deploys weekly, Registration team deploys daily.│    │
│  │  → Extract to avoid blocking each other.                     │    │
│  │                                                              │    │
│  │  Signal 3: Registration needs Redis + DynamoDB but Events    │    │
│  │  only needs Caffeine + CDN. Different data stores.           │    │
│  │  → Natural domain boundary → separate service.               │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  KEEP as monolith: email-drop-recovery (always, it's an ops tool). │
│  EXTRACT expviewsnikeapp: only if mobile app team is separate.     │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Monolith vs Microservices in CXP

| Component | Architecture | Why This Choice |
|-----------|-------------|----------------|
| **cxp-events** | Microservice | Reads scale via CDN; owned by CXP team; clear domain (event data) |
| **cxp-event-registration** | Microservice | Writes scale independently (2→8 tasks); different data stores (Redis, DynamoDB); different deploy cadence |
| **expviewsnikeapp** | Microservice | Different client (Nike App); different data store (Elasticsearch); potentially different team |
| **rise-generic-transform-service** | Microservice (shared) | Different team; serves many Nike teams; SQS-driven (not user-facing) |
| **email-drop-recovery** | Monolith | Single user tool; runs locally; no scaling needed; fast to develop |
| **rapid-retail-insights-host** | Micro-frontend | React SPA; independent deploy; Module Federation for MFE remotes |
| **cxp-infrastructure** | Shared IaC | Terraform monorepo for all CXP infra; single team manages |
| **cxp-api-contracts** | Shared contracts | OpenAPI specs shared across services; contract-first development |

---

## Common Interview Follow-ups

### Q: "When would you choose monolith over microservices?"

> "Three signals for monolith: (1) Small team (<5 engineers) — microservice overhead exceeds the benefit when one team owns everything, (2) Early stage product — you don't know your domain boundaries yet; splitting too early creates wrong boundaries that are expensive to fix, (3) Low traffic — if one server handles all your load, the complexity of distributed systems is pure overhead. Our email-drop-recovery tool is a monolith because it has one user, runs locally, and needs zero scaling. Splitting it into 4 microservices would add deployment complexity with no benefit."

### Q: "How do you decide service boundaries?"

> "Three criteria from our CXP experience:
> 1. **Domain boundary:** cxp-events owns 'read event data,' cxp-registration owns 'write registrations.' These are different business domains with different data stores and different consistency requirements (CDN-cached reads vs ACID writes).
> 2. **Scaling boundary:** During sneaker launches, registration needs 8 tasks while events needs only 2 (CDN absorbs 95% of reads). If they were one service, we'd over-provision events to scale registration.
> 3. **Team boundary:** Rise GTS is owned by a different Nike team and serves many consumers besides CXP. If it were inside our monolith, their deploy cadence would be coupled to ours."

### Q: "What problems has microservices caused that a monolith wouldn't?"

> "The 2-5% email drop rate. In a monolith, registration + email sending would be a single synchronous transaction — register user, send email, return success. Zero drops. In our microservice architecture, registration (cxp-event-registration) succeeds synchronously, but email flows asynchronously through 4 services (Kafka → Rise GTS → NCP → CRS). The MemberHub race condition (Topic 1) drops ~2-5% of emails because NCP runs before MemberHub syncs. We built an entire recovery dashboard to compensate for a problem that wouldn't exist in a monolith. The tradeoff: microservices give us <1 second registration latency. A synchronous monolith would take 30+ seconds."

### Q: "How do you handle shared libraries across microservices?"

> "Nike's shared libraries (AAAConfig for auth, OSCAR client for tokens, RetrofitUtil for HTTP clients) are published as Maven artifacts. Each microservice declares the dependency in `build.gradle` and gets the same auth/HTTP logic without code duplication. The risk: a library version upgrade must be coordinated across 4 services. We mitigate by: (1) backward-compatible library changes only, (2) each service pins its own version and upgrades at its own pace, (3) CI/CD runs tests against the service's pinned version, not the latest."

---
---

# Topic 30: Event-Driven Architecture

> Services communicate through events for loose coupling — producers don't know consumers; enables event sourcing and CQRS patterns.

> **Interview Tip:** Connect patterns — "When order is created, I'd publish OrderCreated event to Kafka; Inventory, Email, and Analytics services consume independently, so adding new consumers needs no producer changes."

---

## What Is Event-Driven Architecture?

Services communicate by **producing and consuming events** through an event bus. The producer publishes an event ("something happened") and doesn't know or care who consumes it. This is **loose coupling at its best**.

```
┌──────────────────────────────────────────────────────────────────────┐
│  EVENT-DRIVEN ARCHITECTURE                                           │
│                                                                      │
│  Producer doesn't know/care who consumes the event.                 │
│                                                                      │
│  ┌───────────┐    ┌───────────────┐    ┌─────────────────┐        │
│  │  Order     │    │  OrderCreated │    │  Event Bus      │        │
│  │  Service   │───▶│  event        │───▶│  Kafka /        │        │
│  │ (Producer) │    │               │    │  EventBridge    │        │
│  └───────────┘    └───────────────┘    │  SNS+SQS        │        │
│                                         └──┬──┬──┬────────┘        │
│                                            │  │  │                  │
│                                            ▼  ▼  ▼    (Consumers)  │
│                                    ┌──────┐┌──────┐┌──────────┐   │
│                                    │Invent-││Email ││Analytics │   │
│                                    │ory Svc││ Svc  ││  Svc     │   │
│                                    │Reserve││Send  ││Track     │   │
│                                    │stock  ││conf  ││metrics   │   │
│                                    └──────┘└──────┘└──────────┘   │
│                                                                      │
│  ADD a 4th consumer (Loyalty Service)?                              │
│  → Just subscribe to the event bus. ZERO changes to Order Service. │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Key Patterns Within EDA

```
┌──────────────────────────────────────────────────────────────────────┐
│  THREE PATTERNS UNDER THE EDA UMBRELLA                               │
│                                                                      │
│  1. EVENT NOTIFICATION                                              │
│     "Something happened." Thin event, consumers fetch details.      │
│     Event: { type: "RegistrationCreated", eventId: 73067 }         │
│     Consumer fetches full data from source if needed.               │
│                                                                      │
│  2. EVENT SOURCING                                                  │
│     Store EVENTS as source of truth, not current state.             │
│     Rebuild state by replaying events.                              │
│     E1(created) → E2(confirmed) → E3(email_sent) → Current State  │
│                                                                      │
│  3. CQRS (Command Query Responsibility Segregation)                 │
│     Separate WRITE model from READ model.                           │
│     Commands (create, update) → normalized write store.             │
│     Queries (search, list) → denormalized read store.              │
│     Events sync the two models asynchronously.                     │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Event-Driven Architecture In My CXP Projects

### The CXP Platform — Full EDA Implementation

Our platform is a **textbook event-driven architecture**. The Eventtia registration produces an event (webhook), and 4+ independent consumers react without any coupling to the producer.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — EVENT-DRIVEN ARCHITECTURE                                 │
│                                                                          │
│  PRODUCER (doesn't know about consumers):                               │
│  ┌──────────────────────┐                                               │
│  │  Eventtia             │  "User registered for event 73067"           │
│  │  (external SaaS)      │  Publishes webhook → Partner Hub → Kafka    │
│  │                       │  Eventtia has NO IDEA who consumes this.     │
│  └──────────┬───────────┘                                               │
│             │ webhook (event)                                            │
│             ▼                                                            │
│  ┌──────────────────────┐                                               │
│  │  NSP3 / Kafka         │  Event Bus                                   │
│  │  (partnerhub_         │  Retains events for replay.                  │
│  │   notification_stream)│  Multiple consumers read independently.      │
│  └──┬──┬──┬──┬──────────┘                                               │
│     │  │  │  │                                                           │
│     │  │  │  └─── CONSUMER 4: NSP3 Purge Sink                          │
│     │  │  │       → POST /purge-cache → Akamai CDN invalidation        │
│     │  │  │       "CDN cache for event 73067 cleared"                   │
│     │  │  │                                                              │
│     │  │  └────── CONSUMER 3: NSP3 S3 Sink                              │
│     │  │          → Write JSON to S3 Partner Hub bucket                  │
│     │  │          "Webhook archived for audit/investigation"            │
│     │  │                                                                 │
│     │  └───────── CONSUMER 2: NSP3 HTTP Sink (post-event/pre-event)    │
│     │             → POST to Rise GTS with different Content-Type        │
│     │             "Transform for post-event/pre-event notifications"    │
│     │                                                                    │
│     └──────────── CONSUMER 1: NSP3 HTTP Sink (registration)            │
│                   → POST to Rise GTS /data/transform/v1                 │
│                   → Rise GTS → NCP → CRS → SendGrid → User's email    │
│                   "Confirmation email triggered"                        │
│                                                                          │
│  LOOSE COUPLING PROOF:                                                  │
│  • Eventtia doesn't know Rise GTS exists.                              │
│  • Rise GTS doesn't know Akamai purge exists.                         │
│  • S3 archival runs independently of email delivery.                    │
│  • Adding Consumer 5 (analytics) = 1 Terraform resource, 0 producer    │
│    changes, 0 changes to existing consumers.                            │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: CQRS — Separate Read and Write Models

Our platform implements **CQRS** — the write model (Eventtia) and read model (Elasticsearch) are completely separate databases, synced via events.

```
┌──────────────────────────────────────────────────────────────────────┐
│  CQRS IN CXP — Write to Eventtia, Read from Elasticsearch           │
│                                                                      │
│  WRITE SIDE (Commands):            READ SIDE (Queries):             │
│  ──────────────────────            ─────────────────────            │
│                                                                      │
│  POST /event_registrations/v1     GET /experience_nikeapp_           │
│  (register user)                   landing_view/v1 (search events)  │
│       │                                    │                         │
│       ▼                                    ▼                         │
│  cxp-event-registration            expviewsnikeapp                  │
│       │                                    │                         │
│       ▼                                    ▼                         │
│  ┌──────────────┐                 ┌──────────────┐                 │
│  │  Eventtia    │   ──events──▶  │ Elasticsearch │                 │
│  │  (Relational │   (async sync   │ (Inverted     │                 │
│  │   DB, ACID)  │    via Kafka)   │  Index)       │                 │
│  │              │                 │              │                 │
│  │  Optimized   │                 │  Optimized   │                 │
│  │  for WRITES: │                 │  for READS:  │                 │
│  │  • Seat locks│                 │  • Full-text │                 │
│  │  • FK checks │                 │  • Geo-dist  │                 │
│  │  • ACID txns │                 │  • Relevance │                 │
│  └──────────────┘                 └──────────────┘                 │
│                                                                      │
│  THE EVENT that syncs them:                                         │
│  Eventtia updates event → webhook → Kafka → indexer → Elasticsearch│
│  This is EVENTUAL CONSISTENCY: ~1-5 second delay between write     │
│  and read model convergence.                                        │
│                                                                      │
│  WHY CQRS:                                                          │
│  • Reads outnumber writes ~100:1 (search vs registration)          │
│  • Read model (ES) optimized for search (inverted index, geo)      │
│  • Write model (Eventtia) optimized for ACID (seat decrements)     │
│  • Each model scales independently                                  │
│  • Different query languages: SQL writes, ES query DSL reads       │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "Our platform is CQRS by design. Writes go to Eventtia (relational DB with ACID transactions for seat management). Reads come from Elasticsearch (inverted index for full-text search, geo-distance, relevance scoring). Events sync the two models asynchronously via Kafka with ~1-5 second lag. This lets us optimize each independently: the write store handles seat locks and foreign key checks, while the read store handles 'show me Nike running events near Portland' with BoolQuery across 5 shards. Neither database compromises for the other's access pattern."

---

### Example 2: Event Sourcing (Partial) — S3 as Event Store

Our Partner Hub S3 bucket is a **partial implementation of event sourcing** — every Eventtia webhook is stored as an immutable event, and we can reconstruct pipeline state by replaying them.

```
┌──────────────────────────────────────────────────────────────────────┐
│  EVENT SOURCING (Partial) — Partner Hub S3                           │
│                                                                      │
│  Traditional state storage:                                         │
│  ┌───────────────────────────────────────────────────┐             │
│  │  registrations table:                              │             │
│  │  | user_id | event_id | status    | updated_at  | │             │
│  │  | uuid-1  | 73067    | confirmed | 2026-04-13  | │             │
│  │  Only current state. History lost.                 │             │
│  └───────────────────────────────────────────────────┘             │
│                                                                      │
│  Event sourcing (what S3 stores):                                   │
│  ┌───────────────────────────────────────────────────┐             │
│  │  s3://partner-hub/event-73067/uuid-1/             │             │
│  │  ├── confirmed_2026-04-10.json  (E1: registered)  │             │
│  │  ├── pre_event_2026-04-12.json  (E2: pre-event)   │             │
│  │  ├── cancel_2026-04-13.json     (E3: cancelled)   │             │
│  │  └── confirmed_2026-04-13.json  (E4: re-registered)│            │
│  │  Full history preserved. State = replay all events. │            │
│  └───────────────────────────────────────────────────┘             │
│                                                                      │
│  REBUILD STATE FROM EVENTS:                                         │
│  E1 (confirmed) → user is registered                               │
│  E2 (pre_event) → pre-event notification sent                      │
│  E3 (cancel) → user cancelled                                      │
│  E4 (confirmed) → user re-registered                               │
│  Current state: REGISTERED (last event wins)                        │
│                                                                      │
│  HOW WE USE THIS:                                                   │
│  Recovery dashboard queries Athena:                                 │
│  SELECT * FROM partner_hub WHERE event.id = 73067                   │
│    AND action = 'confirmed' ORDER BY event_date_ms DESC            │
│  → Returns ALL registration events for this event                  │
│  → Can see: who registered, who cancelled, who re-registered       │
│  → Can REPLAY: fetch any event's S3 path → re-POST to Rise GTS    │
│                                                                      │
│  NOT FULL EVENT SOURCING (differences):                             │
│  ✓ Events stored immutably in S3 (append-only)                     │
│  ✓ State reconstructable by replaying events                       │
│  ✓ Full audit trail (who did what, when)                           │
│  ✗ State not DERIVED from events (Eventtia is source of truth,     │
│    not S3). S3 is a parallel event log, not THE state store.       │
│  ✗ No projection rebuilding (can't rebuild Eventtia from S3 alone) │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — event replay for recovery:**

```python
# reprocess.py — replay a stored event
# 1. Query S3 path for the original event
q = f'''SELECT "$path" AS s3path FROM "{ATHENA_DATABASE}".{ATHENA_TABLE}
    WHERE attendee.upm_id = '{upmid}' AND action = 'confirmed'
    ORDER BY event_date_ms DESC LIMIT 1'''

# 2. Fetch the original event from S3
s3_response = s3.get_object(Bucket=bucket, Key=key)
payload = json.loads(s3_response['Body'].read())

# 3. Replay: re-POST the event to Rise GTS
# Same event → same transform → same email → user gets confirmation
urllib.request.urlopen(urllib.request.Request(RISE_GTS_URL,
    data=json.dumps(payload).encode(),
    headers={'Content-Type': RISE_CONTENT_TYPE}))
```

---

### Example 3: Event Notification — Thin Events + Fetch Details

Our NSP3 Kafka sinks use **event notification** — the Kafka message contains the full webhook payload, but some consumers only use the event ID and fetch additional details from their own data store.

```
┌──────────────────────────────────────────────────────────────────────┐
│  Event Notification Pattern in CXP                                   │
│                                                                      │
│  KAFKA EVENT (full payload from Eventtia webhook):                  │
│  {                                                                  │
│    "action": "confirmed",                                           │
│    "event": { "id": 73067, "name": "Nike Run Portland" },          │
│    "attendee": { "upm_id": "uuid-1234", "email": "..." },          │
│    "notification": { "subject": "Registration Confirmed" }          │
│  }                                                                  │
│                                                                      │
│  CONSUMER 1 (Rise GTS): Uses FULL payload.                         │
│  → Transforms the entire JSON → sends to NCP for email.            │
│  → Doesn't fetch anything extra.                                   │
│                                                                      │
│  CONSUMER 2 (S3 Sink): Stores FULL payload as-is.                  │
│  → Writes raw JSON to S3. No processing.                           │
│                                                                      │
│  CONSUMER 3 (Purge Sink): Uses only event.id.                      │
│  → Extracts event ID → POSTs to /purge-cache                      │
│  → Thin event usage: only needs "which event changed?"             │
│                                                                      │
│  CONSUMER 4 (hypothetical Analytics): Would use event.id +         │
│  attendee.upm_id + action.                                         │
│  → Count registrations per event per marketplace.                  │
│  → Doesn't need full notification payload.                         │
│                                                                      │
│  KEY INSIGHT: Same event, different consumers use different fields. │
│  Fat event (full payload) avoids extra fetches for most consumers. │
│  Thin consumers just ignore fields they don't need.                │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 4: Spring ApplicationEventPublisher — In-Process EDA

**Service:** `cxp-events`
**Pattern:** In-process events for cache coordination (not distributed)

```
┌──────────────────────────────────────────────────────────────────────┐
│  In-Process EDA — Spring Events                                      │
│                                                                      │
│  This is EDA within a SINGLE JVM (not across services):            │
│                                                                      │
│  PRODUCER: CXPConfigService                                        │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  @Scheduled: read config from Secrets Manager every N min   │    │
│  │  If refreshCache == "true":                                 │    │
│  │    applicationEventPublisher.publishEvent(                  │    │
│  │      new CacheRefreshEvent("Refreshing cache")              │    │
│  │    );                                                        │    │
│  │  // Publisher doesn't know WHO listens.                     │    │
│  │  // Could be 0 listeners or 10 listeners.                  │    │
│  └────────────────────────────────────────────────────────────┘    │
│       │ event                                                       │
│       ▼                                                             │
│  CONSUMER: EventListener                                            │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  @EventListener(CacheRefreshEvent.class)                    │    │
│  │  public void cacheRefreshEvent() {                          │    │
│  │    customFieldService.getAllowedCountriesFromSecretManager();│    │
│  │    customFieldService.loadCustomFieldDetails();              │    │
│  │  }                                                           │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  SAME EDA PRINCIPLES:                                               │
│  ✓ Producer doesn't know consumer (publishEvent, not direct call) │
│  ✓ Adding new listener = no producer change                        │
│  ✓ Event carries intent ("refresh cache"), not implementation      │
│  ✗ Not distributed (single JVM only)                               │
│  ✗ No event persistence (event lost if not consumed immediately)   │
└──────────────────────────────────────────────────────────────────────┘
```

---

### How All 30 HLD Topics Connect Through EDA

Event-Driven Architecture is the **unifying thread** across nearly every topic we've covered:

```
┌──────────────────────────────────────────────────────────────────────┐
│  EDA AS THE CONNECTING THREAD ACROSS ALL 30 TOPICS                   │
│                                                                      │
│  Topic 1 (CAP):        EDA enables AP — events eventually          │
│                         consistent across services.                 │
│  Topic 2 (SQL/NoSQL):  CQRS uses different DBs for read/write.    │
│  Topic 3 (ACID/BASE):  Event pipeline is BASE (eventual).          │
│  Topic 6 (Replication): Kafka replicates events across brokers.    │
│  Topic 8 (Sharding):   Kafka partitions = event sharding.          │
│  Topic 9 (Consistent Hash): Kafka partition assignment.             │
│  Topic 11 (Caching):   Cache purge triggered BY events.           │
│  Topic 14 (Load Balancing): Events distributed across consumers.   │
│  Topic 16 (Rate Limiting): SQS VisibilityTimeout throttles events. │
│  Topic 17 (Circuit Breaker): Kafka buffers when consumers fail.    │
│  Topic 19 (Stateless): Events carry all context (no shared state). │
│  Topic 20 (Queue vs Stream): Kafka stream + SQS queue in pipeline. │
│  Topic 21 (Sync/Async): User sync, email async via events.        │
│  Topic 24 (Distributed Txn): Saga choreography via events.         │
│  Topic 25 (Consensus): Kafka uses ZAB for partition leaders.       │
│  Topic 26 (Idempotency): Events are safe to replay.               │
│  Topic 27 (Discovery): Events route via Kafka sinks, not DNS.     │
│  Topic 29 (Microservices): Events decouple service boundaries.     │
│                                                                      │
│  EDA is not one pattern — it's the architectural style that         │
│  ENABLES all these other patterns to work together.                │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: EDA Patterns Across CXP

| Pattern | CXP Implementation | Producer | Consumers | Coupling |
|---------|-------------------|----------|-----------|---------|
| **Event Notification** | Kafka HTTP Push sinks | Eventtia webhook | Rise GTS, Purge sink | Zero — Eventtia doesn't know consumers |
| **CQRS** | Eventtia (write) + Elasticsearch (read) | Registration service | Search service (expviews) | Events sync write→read model |
| **Event Sourcing (partial)** | S3 Partner Hub stores every webhook | Kafka S3 sink | Recovery dashboard (replay) | Events are immutable, replayable |
| **In-Process Events** | Spring ApplicationEventPublisher | CXPConfigService | EventListener (cache refresh) | Loose coupling within JVM |
| **Saga Choreography** | 6-step registration pipeline | Each service publishes completion event | Next service in chain | No orchestrator — events drive flow |
| **Fan-Out** | One Kafka stream → 4 sinks | One producer (Eventtia) | 4 independent consumers | Add consumer = 1 Terraform resource |

---

## Common Interview Follow-ups

### Q: "What's the difference between event-driven and request-driven?"

> "Request-driven: Service A CALLS Service B and WAITS for response. A knows B exists. Tight coupling.
> Event-driven: Service A PUBLISHES an event. Doesn't know who consumes it. B subscribes independently.
>
> CXP uses both: Registration is request-driven (cxp-reg calls Eventtia synchronously — user waits). Email pipeline is event-driven (Eventtia publishes webhook, 4 consumers react independently — user doesn't wait). The user-facing path is request-driven for immediate feedback. The background path is event-driven for loose coupling and scalability."

### Q: "How do you ensure event ordering in your EDA?"

> "Kafka guarantees ordering within a partition. Our NSP3 stream partitions by event ID, so all events for event 73067 (confirmed, cancel, pre_event) arrive in order to the same consumer. Across different events, ordering doesn't matter — event 73067 and event 74001 can be processed in any order. If we needed global ordering (all events across all IDs), we'd use a single partition — but that kills parallelism. Per-event ordering is the right tradeoff for registration workflows."

### Q: "What happens when you add a new consumer to the Kafka stream?"

> "One Terraform resource:
> ```hcl
> resource 'nsp3_sink' 'cxp-analytics-sink' {
>   streams = [var.nsp3_partnerhub_notification_stream_id]
>   config = { ... }
> }
> ```
> The new consumer starts reading from the stream independently. Zero changes to Eventtia (producer). Zero changes to existing consumers (Rise GTS, S3 sink, purge sink). The new consumer can even replay historical events from the stream's retention window. This is the fundamental benefit of EDA: adding capabilities without modifying existing components."

### Q: "Event sourcing vs traditional CRUD — when would you choose event sourcing?"

> "Event sourcing when: (1) audit trail is critical (financial transactions, compliance), (2) you need to answer 'what happened and when' not just 'what's the current state,' (3) you need to replay events to rebuild state or create new projections. Our S3 Partner Hub is a partial event source — we store every webhook and can replay them for recovery. But Eventtia (not S3) is the actual source of truth for current registration state. Full event sourcing would mean deriving Eventtia's state FROM S3 events — which we don't do because Eventtia is an external SaaS we don't control."

---

## The Complete HLD — 30 Topics, 4 Files

```
┌──────────────────────────────────────────────────────────────────────┐
│  HLD INTERVIEW PREPARATION — COMPLETE INDEX                          │
│                                                                      │
│  01-CAP-Theorem.md (Topics 1-10)                                    │
│  ├── 1.  CAP Theorem                                                │
│  ├── 2.  SQL vs NoSQL                                               │
│  ├── 3.  ACID vs BASE                                               │
│  ├── 4.  Database Selection                                         │
│  ├── 5.  Database Indexing                                          │
│  ├── 6.  Database Replication                                       │
│  ├── 7.  Read Replicas                                              │
│  ├── 8.  Sharding Strategies                                        │
│  ├── 9.  Consistent Hashing                                         │
│  └── 10. Data Partitioning                                          │
│                                                                      │
│  02-Caching-and-Performance.md (Topics 11-19)                       │
│  ├── 11. Caching Patterns                                           │
│  ├── 12. Cache Eviction Policies                                    │
│  ├── 13. CDN (Content Delivery Network)                             │
│  ├── 14. Load Balancing                                             │
│  ├── 15. Vertical vs Horizontal Scaling                             │
│  ├── 16. Rate Limiting                                              │
│  ├── 17. Circuit Breaker                                            │
│  ├── 18. Connection Pooling                                         │
│  └── 19. Stateless vs Stateful                                      │
│                                                                      │
│  03-Messaging-and-Communication.md (Topics 20-25)                   │
│  ├── 20. Message Queue vs Event Stream                              │
│  ├── 21. Synchronous vs Asynchronous Communication                  │
│  ├── 22. REST vs GraphQL vs gRPC                                    │
│  ├── 23. Real-Time Communication                                    │
│  ├── 24. Distributed Transactions                                   │
│  └── 25. Consensus Algorithms                                       │
│                                                                      │
│  04-Reliability-and-Resilience.md (Topics 26-30)                    │
│  ├── 26. Idempotency                                                │
│  ├── 27. Service Discovery                                          │
│  ├── 28. API Gateway                                                │
│  ├── 29. Monolith vs Microservices                                  │
│  └── 30. Event-Driven Architecture                                  │
│                                                                      │
│  ALL 30 topics connected to CXP project code with real examples.   │
└──────────────────────────────────────────────────────────────────────┘
```