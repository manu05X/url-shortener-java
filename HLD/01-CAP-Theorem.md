# Topic 1: CAP Theorem

> Every distributed system must choose two of three: **Consistency**, **Availability**, or **Partition Tolerance** — understand the tradeoffs before designing.

---

## What Is CAP?

In any distributed system (data or services spread across multiple nodes/servers), you can only guarantee **two out of three** properties simultaneously:

| Property | Meaning |
|----------|---------|
| **C — Consistency** | Every read returns the most recent write. All nodes see the same data at the same time. |
| **A — Availability** | Every request receives a response (success or failure) — the system never refuses to answer. |
| **P — Partition Tolerance** | The system continues to operate even when network communication between nodes is lost. |

### Why Only Two?

Network partitions **are inevitable** in any distributed system (cables fail, datacenters lose connectivity, cloud AZs become unreachable). Since P is non-negotiable in real-world systems, the actual choice is:

```
Since P is always required:

┌─────────────────────────────────────────────────────────┐
│                    PARTITION HAPPENS                      │
│                                                          │
│   Option A: Stay CONSISTENT (CP)                         │
│   → Refuse requests until partition heals                │
│   → Users may see errors / timeouts                      │
│   → But data is NEVER wrong                              │
│                                                          │
│   Option B: Stay AVAILABLE (AP)                          │
│   → Keep serving requests on both sides of partition     │
│   → Users always get a response                          │
│   → But data may be STALE or CONFLICTING                 │
└─────────────────────────────────────────────────────────┘
```

---

## Interview Line

> "Since network partitions are inevitable, I'm choosing between **CP** (consistency) for banking/inventory or **AP** (availability) for social feeds/analytics."

### When to pick what:

| Pick CP When | Pick AP When |
|-------------|-------------|
| Wrong data = money lost (banking, payments) | Stale data is tolerable (social feeds) |
| Inventory counts must be exact (e-commerce stock) | User experience > perfect accuracy (search results) |
| Double-booking is unacceptable (event seats) | System must never go down (DNS, CDN, logging) |
| Regulatory compliance requires accuracy | Analytics can reconcile later |

---

## CAP In My CXP Projects — Real Examples

### The CXP Platform Architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         CXP PLATFORM — CAP MAP                               │
│                                                                              │
│   ┌─────────────┐     ┌─────────────┐     ┌──────────────┐                  │
│   │   Akamai    │     │ cxp-events  │     │ cxp-event-   │                  │
│   │   CDN       │────▶│ (Spring Boot)│────▶│ registration │                  │
│   │   [AP]      │     │             │     │ (Spring Boot)│                  │
│   └─────────────┘     └──────┬──────┘     └──────┬───────┘                  │
│                              │                    │                           │
│                    ┌─────────┴─────────┐         │                           │
│                    ▼                   ▼         ▼                           │
│            ┌──────────────┐   ┌──────────────┐  ┌──────────────┐            │
│            │  ElastiCache │   │   Eventtia   │  │  DynamoDB    │            │
│            │  (Redis)     │   │  (External)  │  │ Global Table │            │
│            │  [AP cache]  │   │  [AP]        │  │ [AP cross-   │            │
│            └──────────────┘   └──────┬───────┘  │  region]     │            │
│                                      │          └──────────────┘            │
│                                      ▼                                      │
│            ┌──────────────┐   ┌──────────────┐  ┌──────────────┐            │
│            │  Partner Hub │   │  NSP3/Kafka  │  │   Splunk     │            │
│            │  (S3+Athena) │   │  Streaming   │  │   Logging    │            │
│            │  [CP reads]  │   │  [AP]        │  │   [AP]       │            │
│            └──────────────┘   └──────┬───────┘  └──────────────┘            │
│                                      ▼                                      │
│                               ┌──────────────┐  ┌──────────────┐            │
│                               │  Rise GTS    │  │  NCP + CRS   │            │
│                               │  Transform   │──▶  Email       │            │
│                               │  [AP]        │  │  Rendering   │            │
│                               └──────────────┘  └──────────────┘            │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: DynamoDB Global Tables — AP (cross-region)

**Where:** `cxp-infrastructure` → `terraform/aws/modules/dynamodb`
**What it stores:** Unprocessed registration requests
**Regions:** us-east-1 (primary) + us-west-2 (secondary)

```
                    us-east-1                          us-west-2
               ┌─────────────────┐              ┌─────────────────┐
               │   DynamoDB      │   eventual    │   DynamoDB      │
  User A ────▶ │   (Write here)  │──replication──│   (Replica)     │ ◀──── User B
               │                 │     ~1sec     │                 │
               └─────────────────┘              └─────────────────┘
```

**CAP choice: AP across regions**
- A user registering in us-east-1 will have their data replicated to us-west-2, but with a slight delay (~1 second).
- If the network between regions is partitioned, **both regions continue accepting writes** (availability preserved).
- After partition heals, DynamoDB uses **last-writer-wins** conflict resolution.
- This is AP because: a registration should never be refused just because a region is unreachable.

**Why not CP here?**
- If we chose CP, a network partition between regions would cause registration failures for users routed to the secondary region.
- For event registration, **accepting the request** and reconciling later is better than **rejecting the user**.

**Interview answer:**
> "We use DynamoDB Global Tables which are AP across regions — eventual consistency between us-east-1 and us-west-2. We chose this because refusing a registration during a partition is worse than having a brief replication delay. DynamoDB does support strongly consistent reads within a single region, so for local operations it behaves like CP."

---

### Example 2: ElastiCache Redis — AP (caching layer)

**Where:** `cxp-infrastructure` → `terraform/awsPassplay/modules/elasticache`
**What it caches:** Event details, seat availability, translations (Bodega)

```
  User request
       │
       ▼
  ┌──────────┐    Cache HIT     ┌─────────────────┐
  │ cxp-     │──────(fast)─────▶│ Redis Primary    │
  │ events   │                  │                  │──replication──▶ Read Replicas
  │          │◀────────────────│                  │
  └──────────┘    Cache MISS    └─────────────────┘
       │                                │
       │                                │ stale for
       ▼                                │ brief period
  ┌──────────┐                          ▼
  │ Eventtia │            Seats might show "3 left"
  │ API      │            when actually "2 left"
  └──────────┘
```

**CAP choice: AP**
- Redis read replicas may serve slightly stale data (a seat count might be off by 1 for milliseconds).
- The system **never refuses** to show event details just because the cache is stale.
- If the seat count is wrong, the registration API catches it with a 409 error and **invalidates the cache** via `seatsAPICachePurging(eventId)`.

**Why AP is correct here:**
- Showing "3 spots left" when it's actually "2 spots left" is acceptable — the registration endpoint catches the real error.
- Refusing to show the event page because cache is resyncing would kill the user experience.

**Interview answer:**
> "Our caching layer is AP — we serve potentially stale seat counts from Redis to keep the event page responsive. The actual consistency is enforced at the registration layer — when Eventtia returns a 409 (EVENT_FULL or ACTIVITY_FULL), we invalidate the cache and return the error to the frontend. This gives us the best of both worlds: fast reads and accurate writes."

---

### Example 3: The Email Drop Race Condition — CAP in Action

**Where:** `cxp-email-drop-recovery` project — the entire reason this tool exists
**The problem:** NCP drops confirmation emails because MemberHub hasn't synced the user's email yet.

```
  ┌────────────────────────────────────────────────────────────────┐
  │                 THE RACE CONDITION (AP tradeoff)                │
  │                                                                │
  │  T=0s   User creates Nike account                             │
  │  T=0s   User immediately registers for CXP event              │
  │         ↓                                                      │
  │  T=1s   Registration succeeds (AP — available immediately)     │
  │         ↓                                                      │
  │  T=2s   Eventtia webhook → Partner Hub → Rise → NCP            │
  │         ↓                                                      │
  │  T=3s   NCP asks MemberHub: "What is this user's email?"       │
  │         ↓                                                      │
  │  T=3s   MemberHub: "I don't have it yet!" (NOT YET SYNCED)    │
  │         ↓                                                      │
  │  T=3s   NCP DROPS the email ❌                                 │
  │                                                                │
  │  T=30s  MemberHub finally syncs the email ✓                    │
  │         (Too late — NCP already gave up)                        │
  └────────────────────────────────────────────────────────────────┘
```

**This is a textbook Consistency vs Availability tradeoff:**

| If system chose CP | If system chose AP (current) |
|---|---|
| Block registration until MemberHub confirms email is synced | Accept registration immediately, send email async |
| User waits 30+ seconds on "Registering..." spinner | User sees "Registered!" instantly |
| Email always arrives (data consistent) | Email sometimes dropped (data eventually consistent) |
| Terrible UX, users abandon registration | Great UX, but ~2-5% email drop rate |

**The CXP team chose AP** — accept registrations immediately and deal with email drops via the recovery tool.

**Interview answer:**
> "This is a real CAP tradeoff I worked on. Our event registration pipeline chose availability — we accept the registration instantly even though the email notification system (NCP) might not have the user's email yet due to a MemberHub sync delay. The tradeoff is a ~2-5% email drop rate, which we handle through a recovery dashboard I built that detects drops via Splunk + Athena and re-triggers delivery via RISE API. If we had chosen consistency (wait for MemberHub sync), registration would take 30+ seconds and users would abandon."

---

### Example 4: Partner Hub S3 + Athena — CP (Source of Truth)

**Where:** Athena queries in `cxp-email-drop-recovery` + `Event-Email-Delivery-Investigation-Workflow`
**Table:** `partnerhub-data-crawler-info.partner_hub_notification_response_data_prod`

```
  Eventtia ──webhook──▶ Partner Hub (S3) ◀──query── Athena
                            │
                       SOURCE OF TRUTH
                     (strongly consistent)
                            │
                   "If it's not in Athena,
                    Eventtia never sent it."
```

**CAP choice: CP (for reads)**
- S3 provides strong read-after-write consistency — once a webhook is written, any subsequent Athena query will see it.
- If S3 is partitioned/unavailable, Athena queries **fail** rather than return stale data.
- This is correct because Partner Hub is the **source of truth** — returning stale or incomplete data would lead to wrong root cause analysis.

**Interview answer:**
> "Our source of truth for the email pipeline is Partner Hub data in S3, queried via Athena. This is a CP choice — if Athena can't reach S3 or the data hasn't landed yet, the query returns empty rather than stale results. We accept this because for investigation and reconciliation, accuracy matters more than speed. A false 'Eventtia never sent it' conclusion due to stale data would send us chasing the wrong team."

---

### Example 5: Akamai CDN — AP (Edge Caching)

**Where:** CDN layer in front of cxp-events
**What it caches:** Event detail pages, static assets

**CAP choice: AP**
- Akamai serves cached content even if the origin (cxp-events backend) is down.
- A user in Tokyo might see a cached event page that's 5 minutes old while the origin in us-east-1 has updated data.
- Cache invalidation happens via **NSP3 Purge Sink** (Kafka-driven).

**Interview answer:**
> "Our CDN layer is purely AP — we serve cached event pages even if the origin is unreachable. This means users in different geos might see slightly different event data for up to 5 minutes, but they always see *something*. Cache purge is event-driven through our NSP3 Kafka streaming platform."

---

### Example 6: NSP3/Kafka Streaming — AP (Event Pipeline)

**Where:** `cxp-infrastructure` → `terraform/nsp3/modules/nsp3Sink`
**Sinks:** P1-3 (registration events), P4 (ad-hoc), P6 (post-event), S3 (archival), Purge (cache)

**CAP choice: AP**
- Kafka prioritizes availability — producers can write messages even if some brokers are down.
- Messages are durably stored and replayed if a consumer (Rise GTS, NCP) was temporarily unavailable.
- Ordering is guaranteed within a partition, but not globally — eventual consistency.

---

## Summary: CAP Choices Across CXP Platform

| Component | Technology | CAP Choice | Rationale |
|-----------|-----------|------------|-----------|
| Registration data (cross-region) | DynamoDB Global Tables | **AP** | Never refuse a registration |
| Event detail cache | ElastiCache Redis | **AP** | Fast reads, catch errors at write time |
| Notification pipeline | NSP3/Kafka | **AP** | Buffer messages, replay on failure |
| Email delivery | NCP + MemberHub | **AP** | Accept registration immediately, recover drops later |
| Source of truth (investigation) | S3 + Athena | **CP** | Accuracy matters for root cause analysis |
| CDN layer | Akamai | **AP** | Always serve content, even if stale |
| Registration API (single region) | DynamoDB strong reads | **CP** | Exact seat count at write time |

---

## Common Interview Follow-ups

### Q: "Can a system be both CP and AP at different layers?"
> **Yes — and CXP does exactly this.** Our read path is AP (CDN + Redis cache for fast event pages), but our write path is CP within a single region (DynamoDB strongly consistent reads for seat checks during registration). The investigation/reconciliation layer (Athena) is CP because accuracy matters more than speed for debugging.

### Q: "What happens when your AP system gives wrong data?"
> **We built compensating mechanisms.** The email-drop-recovery dashboard detects when the AP tradeoff causes a failure (NCP dropping emails), and we compensate by re-triggering delivery via RISE API. The reconciliation tab compares Athena (source of truth) against Splunk (delivery logs) to find exactly who was missed.

### Q: "Why not just make everything CP?"
> **Cost in user experience and throughput.** If our registration was CP (wait for MemberHub sync + email confirmation before returning success), users would wait 30+ seconds. Nike events like sneaker launches have thousands of concurrent registrations — a CP registration path would create a bottleneck and users would abandon.

### Q: "Is CAP still relevant with modern databases?"
> **CAP is a simplification.** Real systems exist on a spectrum — DynamoDB lets you choose consistency per-read (eventual vs strong). The real framework is PACELC: during Partition, choose A or C; Else (no partition), choose Latency or Consistency. Our system chooses PA/EL (partition → available, else → low latency) for reads and PA/EC for writes.

---

## PACELC Extension (Bonus for Senior-Level Interviews)

CAP only describes behavior **during a partition**. PACELC extends it:

```
IF Partition → choose A or C
ELSE         → choose L (Latency) or C (Consistency)
```

| CXP Component | During Partition | Else (Normal) | PACELC |
|---------------|-----------------|---------------|--------|
| DynamoDB Global Tables | Available (AP) | Low Latency (eventual reads) | PA/EL |
| Redis Cache | Available (AP) | Low Latency (cached reads) | PA/EL |
| S3 + Athena | Consistent (CP) | Consistent (strong reads) | PC/EC |
| Registration Write Path | Consistent (CP) | Consistent (strong reads) | PC/EC |

---
---

# Topic 2: SQL vs NoSQL

> SQL offers ACID transactions and complex queries; NoSQL trades structure for horizontal scale and flexible schemas.

> **Interview Tip:** Don't default to one — explain "I'd use PostgreSQL for the order service needing transactions, but DynamoDB for the session store needing fast key-value lookups."

---

## The Core Tradeoff

| Dimension | SQL (Relational) | NoSQL |
|-----------|-----------------|-------|
| **Schema** | Fixed schema, migrations required | Flexible / schema-less |
| **Scaling** | Vertical (bigger server) | Horizontal (more servers) |
| **Transactions** | Full ACID across tables | Limited (single-item or single-partition) |
| **Queries** | Complex JOINs, aggregations, subqueries | Simple key-based lookups, denormalized |
| **Consistency** | Strong by default | Configurable (eventual or strong) |
| **Best for** | Complex relationships, reporting, financial data | High throughput, flexible data, massive scale |

---

## NoSQL Categories (with CXP examples)

```
┌───────────────────────────────────────────────────────────────────────┐
│                       NoSQL CATEGORIES                                │
├──────────────┬─────────────────┬──────────────┬──────────────────────┤
│  Key-Value   │   Document      │  Column      │  Search Engine       │
│              │                 │  Family      │                      │
│  DynamoDB ✓  │  MongoDB        │  Cassandra   │  Elasticsearch ✓     │
│  Redis    ✓  │  CouchDB        │  HBase       │  OpenSearch          │
│  Memcached   │  Firestore      │  ScyllaDB    │  Solr                │
│              │                 │              │                      │
│  O(1) lookup │  Nested JSON    │  Wide rows,  │  Full-text search,   │
│  by key      │  flexible shape │  time series │  fuzzy matching,     │
│              │                 │              │  faceted queries     │
└──────────────┴─────────────────┴──────────────┴──────────────────────┘

Plus: Object Store (S3 ✓) — not a "database" but stores data at scale
      SQL-on-Object-Store (Athena ✓) — SQL queries over S3 files
```

---

## My CXP Platform — All NoSQL, Zero SQL

The entire CXP platform has **no relational database**. Every service uses purpose-fit NoSQL stores:

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                    CXP DATABASE ARCHITECTURE                                      │
│                                                                                  │
│  ┌────────────────────┐          ┌──────────────────────┐                        │
│  │   cxp-events       │          │ cxp-event-registration│                        │
│  │   (Spring Boot)    │          │  (Spring Boot)        │                        │
│  │                    │          │                       │                        │
│  │  No database       │          │  ┌─────────────────┐  │                        │
│  │  (API gateway to   │          │  │ Redis           │  │ Idempotency cache,     │
│  │   Eventtia)        │          │  │ (ElastiCache)   │  │ pairwise IDs,          │
│  │                    │          │  └─────────────────┘  │ duplicate prevention   │
│  │  Akamai CDN caches │          │  ┌─────────────────┐  │                        │
│  │  API responses     │          │  │ DynamoDB        │  │ Unprocessed            │
│  └────────────────────┘          │  │ (Global Table)  │  │ registration queue     │
│                                  │  └─────────────────┘  │                        │
│                                  └──────────────────────┘                        │
│                                                                                  │
│  ┌────────────────────┐          ┌──────────────────────┐                        │
│  │  expviewsnikeapp   │          │ rise-generic-         │                        │
│  │  (Spring Boot)     │          │ transform-service     │                        │
│  │                    │          │  (Spring Boot)        │                        │
│  │  ┌─────────────────┐          │  ┌─────────────────┐  │                        │
│  │  │ Elasticsearch   │          │  │ S3              │  │ Read input payloads,   │
│  │  │ (AWS managed)   │          │  │ (Object Store)  │  │ write transformed      │
│  │  └─────────────────┘          │  └─────────────────┘  │ output                 │
│  │  Event discovery,  │          │  ┌─────────────────┐  │                        │
│  │  search, landing   │          │  │ SQS             │  │ S3 event notifications │
│  │  page queries      │          │  │ (Queue)         │  │ trigger transforms     │
│  └────────────────────┘          │  └─────────────────┘  │                        │
│                                  │  ┌─────────────────┐  │                        │
│  ┌────────────────────┐          │  │ NSPv2/Kafka     │  │ Publish transformed    │
│  │  Partner Hub       │          │  │ (Streaming)     │  │ events downstream      │
│  │                    │          │  └─────────────────┘  │                        │
│  │  S3 (raw webhooks) │          └──────────────────────┘                        │
│  │  + Athena (SQL     │                                                          │
│  │    queries on S3)  │                                                          │
│  └────────────────────┘                                                          │
└──────────────────────────────────────────────────────────────────────────────────┘
```

---

## Detailed Breakdown: Why Each Technology Was Chosen

### 1. DynamoDB — For Unprocessed Registration Queue

**Service:** `cxp-event-registration`
**Table:** `unprocessed_registration_requests`
**Access pattern:** Simple CRUD by partition key (request ID)

```
┌─────────────────────────────────────────────────────────────────┐
│                    WHY DynamoDB (not SQL)?                        │
├──────────────────────┬──────────────────────────────────────────┤
│  Access pattern      │  Single-key lookups: get/put/delete by   │
│                      │  partition key. No JOINs needed.          │
├──────────────────────┼──────────────────────────────────────────┤
│  Scale               │  Nike events (sneaker launches) cause     │
│                      │  massive concurrent registration spikes.  │
│                      │  DynamoDB auto-scales horizontally.       │
├──────────────────────┼──────────────────────────────────────────┤
│  Multi-region        │  Global Table replicates to us-east-1    │
│                      │  + us-west-2 automatically.               │
├──────────────────────┼──────────────────────────────────────────┤
│  Serverless          │  No server to manage. Pay per request.   │
│                      │  Zero operational overhead.               │
├──────────────────────┼──────────────────────────────────────────┤
│  Why NOT SQL?        │  A registration request is a flat record  │
│                      │  with no relationships. SQL JOINs,        │
│                      │  foreign keys, and complex queries would  │
│                      │  add overhead with no benefit.            │
└──────────────────────┴──────────────────────────────────────────┘
```

**From the actual code:**

```java
// UnprocessedRegistrationService.java
dynamoDbTable.putItem(request);                // Write
dynamoDbTable.getItem(Key.builder()...);       // Read by key
dynamoDbTable.deleteItem(Key.builder()...);    // Delete by key
dynamoDbTable.scan(...);                       // Full scan for batch reprocessing
```

**Interview answer:**
> "We use DynamoDB for the unprocessed registration queue because the access pattern is pure key-value — write a failed registration, read it back by ID, delete after reprocessing. DynamoDB gives us auto-scaling for spike traffic during sneaker launches and multi-region replication via Global Tables. A PostgreSQL RDS instance would need manual scaling, replica management, and we'd be paying for SQL features we don't use."

---

### 2. Redis (ElastiCache) — For Caching & Idempotency

**Service:** `cxp-event-registration`
**Config:** Primary + read replicas, `ReadFrom.REPLICA_PREFERRED`

```
┌─────────────────────────────────────────────────────────────────┐
│                    WHY Redis (not SQL cache)?                     │
├──────────────────────┬──────────────────────────────────────────┤
│  Use Case 1:         │  IDEMPOTENCY — prevent duplicate          │
│  RegistrationCache   │  registrations within a time window.      │
│  Service             │  Key = "userId:eventId", Value = status   │
│                      │  TTL = auto-expire after N minutes        │
├──────────────────────┼──────────────────────────────────────────┤
│  Use Case 2:         │  PAIRWISE ID CACHE — map consumer IDs    │
│  Pairwise IDs        │  to Pairwise IDs (privacy-preserving).   │
│                      │  Sub-millisecond lookups required.        │
├──────────────────────┼──────────────────────────────────────────┤
│  Why not SQL?        │  Caches need sub-millisecond reads.       │
│                      │  SQL disk I/O would be 10-100x slower.    │
│                      │  TTL expiration is native in Redis.       │
│                      │  No persistence needed — cache is          │
│                      │  rebuilt from source on miss.             │
├──────────────────────┼──────────────────────────────────────────┤
│  Read replicas       │  `ReadFrom.REPLICA_PREFERRED` — reads     │
│                      │  distributed across replicas for          │
│                      │  throughput. Writes go to primary only.   │
└──────────────────────┴──────────────────────────────────────────┘
```

**The idempotency pattern (critical for event registration):**

```
User clicks "Register" twice quickly:

  Request 1                             Request 2 (duplicate)
      │                                       │
      ▼                                       ▼
  Redis: GET "user123:event456"         Redis: GET "user123:event456"
      │                                       │
      ▼                                       ▼
  NOT FOUND → proceed                   FOUND → return "already processing"
      │                                       │
      ▼                                       ▼
  Redis: SET "user123:event456"         409 Conflict response
  TTL = 5 minutes
      │
      ▼
  Call Eventtia API → Register
      │
      ▼
  Redis: DELETE "user123:event456"
```

**Interview answer:**
> "We use Redis for idempotency and pairwise ID caching. When a user clicks Register, we SET a key with TTL in Redis. If a duplicate request arrives before the first completes, Redis tells us immediately — preventing double-registrations without a database lock. The latency budget is <5ms and we handle thousands of concurrent registrations during sneaker launches."

---

### 3. Elasticsearch — For Event Search & Discovery

**Service:** `expviewsnikeapp`
**Cluster:** AWS managed Elasticsearch (pg-elasticsearch-cluster)

```
┌─────────────────────────────────────────────────────────────────┐
│                WHY Elasticsearch (not SQL LIKE)?                  │
├──────────────────────┬──────────────────────────────────────────┤
│  Search features     │  Full-text search across event names,     │
│                      │  descriptions, locations. Fuzzy matching, │
│                      │  relevance scoring, faceted filters.      │
├──────────────────────┼──────────────────────────────────────────┤
│  Why not SQL?        │  SQL LIKE '%running%' does full table     │
│                      │  scan — O(n). ES inverted index is O(1).  │
│                      │  SQL can't do relevance scoring, fuzzy    │
│                      │  match, or faceted aggregations natively. │
├──────────────────────┼──────────────────────────────────────────┤
│  Data flow           │  Events indexed from Eventtia → ES.       │
│                      │  ES is NOT source of truth (Eventtia is). │
│                      │  This is the CQRS pattern.               │
└──────────────────────┴──────────────────────────────────────────┘
```

**Interview answer:**
> "For event discovery — 'show me Nike events near me this weekend' — we use Elasticsearch. A SQL `WHERE name LIKE '%running%'` does a full table scan; Elasticsearch does it in O(1) via inverted index. Elasticsearch is not our source of truth — Eventtia is — we use it as a read-optimized search layer. This is the CQRS pattern: writes go to Eventtia, reads come from Elasticsearch."

---

### 4. S3 + Athena — SQL-on-NoSQL (Source of Truth)

**Service:** Partner Hub (webhook storage) + email-drop-recovery
**Table:** `partnerhub-data-crawler-info.partner_hub_notification_response_data_prod`

```
┌─────────────────────────────────────────────────────────────────┐
│              WHY S3 + Athena (not a SQL database)?               │
├──────────────────────┬──────────────────────────────────────────┤
│  Data shape          │  Raw webhook JSON from Eventtia.          │
│                      │  Semi-structured, schema evolves.         │
├──────────────────────┼──────────────────────────────────────────┤
│  Volume              │  Every registration across all events,    │
│                      │  all marketplaces, all time. TB-scale.    │
├──────────────────────┼──────────────────────────────────────────┤
│  Cost                │  S3 = $0.023/GB/month.                    │
│                      │  RDS PostgreSQL = $0.10-0.50/hour + EBS.  │
│                      │  For TB-scale audit data, S3 is 100x      │
│                      │  cheaper.                                 │
├──────────────────────┼──────────────────────────────────────────┤
│  Query engine        │  Athena = serverless SQL on S3.           │
│                      │  Pay per query ($5/TB scanned).           │
│                      │  Best of both worlds.                     │
└──────────────────────┴──────────────────────────────────────────┘
```

**Actual Athena query from the investigation workflow:**

```sql
SELECT attendee.upm_id, event.id, action, event_date_ms
FROM "partnerhub-data-crawler-info".partner_hub_notification_response_data_prod
WHERE event.id = 73067
AND action = 'confirmed'
ORDER BY event_date_ms DESC
```

---

### 5. S3 + SQS — For Rise Generic Transform Service

**Pattern:** Event-driven pipeline (S3 → SQS → Transform → S3/NSPv2)

```
  ┌─────────────┐    S3 Event      ┌─────────┐    Transform    ┌──────────┐
  │  Partner Hub │───notification──▶│   SQS   │───────────────▶│ Rise GTS │
  │  (S3 input)  │                 │  Queue  │                │          │
  └─────────────┘                  └─────────┘                │  Reads   │
                                                              │  from S3,│
                                                              │  writes  │──▶ NSPv2/Kafka
                                                              │  to S3   │
                                                              └──────────┘
```

**Why S3+SQS not SQL:** Transform inputs are JSON blobs of varying shape per event type. No relational queries needed — each transform is independent. S3 gives unlimited storage; SQS gives at-least-once delivery.

---

## When I WOULD Use SQL

```
┌────────────────────────────────────────────────────────────────────────────┐
│                    DECISION FRAMEWORK                                      │
│                                                                            │
│  "I'd use SQL for..."              "I'd use NoSQL for..."                 │
│                                                                            │
│  ✦ Payment/billing service          ✦ Session/cache store (Redis)         │
│    → ACID transactions prevent      → Sub-ms reads, auto-expire          │
│      double charges                                                       │
│                                                                            │
│  ✦ User account management          ✦ Event registration queue (DynamoDB)│
│    → Relationships: user → roles    → Key-value CRUD, massive spikes     │
│      → permissions → orgs                                                 │
│                                                                            │
│  ✦ Inventory/seat management        ✦ Search/discovery (Elasticsearch)   │
│    → Exact counts with SELECT       → Full-text, fuzzy, relevance        │
│      FOR UPDATE                                                           │
│                                                                            │
│  ✦ Financial reporting              ✦ Audit trail/logs (S3 + Athena)     │
│    → Complex aggregations,          → Append-only, TB scale, cheap       │
│      JOINs, GROUP BY                                                      │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## The Seat Availability Problem — Where SQL Might Help

If WE owned the seat inventory (hypothetical), SQL would be better:

```sql
-- SQL approach: atomic seat decrement
BEGIN TRANSACTION;
  SELECT available_seats FROM events WHERE id = 73067 FOR UPDATE;
  UPDATE events SET available_seats = available_seats - 1 WHERE id = 73067;
  INSERT INTO registrations (user_id, event_id) VALUES ('user123', 73067);
COMMIT;
```

DynamoDB alternative (optimistic locking):

```
UpdateItem(
  Key: { eventId: "73067" },
  UpdateExpression: "SET seats = seats - 1",
  ConditionExpression: "seats > 0"
)
// Throws ConditionalCheckFailedException if seats = 0
// But registration insert is a SEPARATE operation — no atomicity
```

**Interview answer:**
> "Seat inventory is where SQL shines — atomic decrement + constraint check + registration insert in one transaction. DynamoDB can do conditional writes for single items, but can't atomically update seats AND create a registration across two tables. In our case, Eventtia owns this in their relational database, so we delegate via API."

---

## SQL vs NoSQL Summary Per CXP Service

| Service | Storage | Type | Why This, Not The Other |
|---------|---------|------|------------------------|
| **cxp-event-registration** | DynamoDB | Key-Value NoSQL | Simple CRUD by key, auto-scales for spikes, multi-region |
| **cxp-event-registration** | Redis (ElastiCache) | In-Memory NoSQL | Sub-ms idempotency checks, TTL expiry, cache-aside pattern |
| **expviewsnikeapp** | Elasticsearch | Search NoSQL | Full-text search, relevance scoring, faceted filters |
| **rise-generic-transform-service** | S3 + SQS | Object Store + Queue | Variable JSON shapes, unlimited scale, event-driven pipeline |
| **Partner Hub** | S3 + Athena | Object Store + SQL Engine | TB-scale audit data, cheap storage, SQL queries on demand |
| **cxp-events** | None (API proxy) | N/A | Delegates to Eventtia; CDN caches responses |
| Eventtia (external) | Likely PostgreSQL | Relational SQL | Event config, seat inventory, attendee relationships |

---
---

# Topic 3: ACID vs BASE

> ACID guarantees strong consistency for banking systems; BASE accepts eventual consistency for availability at massive scale.

> **Interview Tip:** Mention this when justifying database choice — "Payment processing needs ACID guarantees, but user activity feeds can tolerate eventual consistency with BASE."

---

## What Are ACID and BASE?

These are two **consistency models** that describe how a database behaves during and after operations. They directly connect to the CAP theorem (Topic 1) — ACID leans toward CP, BASE leans toward AP.

```
┌──────────────────────────────────────┐    ┌──────────────────────────────────────┐
│             ACID                      │    │             BASE                      │
│     Strong Consistency Model          │    │     Eventual Consistency Model        │
│                                      │    │                                      │
│  A — Atomicity                       │    │  BA — Basically Available             │
│      All or nothing. Transaction     │    │       System always responds,         │
│      fully completes or fully        │    │       even if data is stale.          │
│      rolls back. No partial state.   │    │                                      │
│                                      │    │  S  — Soft State                     │
│  C — Consistency                     │    │       Data may change over time       │
│      Data always valid. Constraints  │    │       without new input (replicas     │
│      enforced (FK, unique, checks).  │    │       catching up).                  │
│                                      │    │                                      │
│  I — Isolation                       │    │  E  — Eventually Consistent           │
│      Concurrent transactions don't   │    │       Given enough time, all nodes    │
│      see each other's uncommitted    │    │       will converge to same state.    │
│      changes.                        │    │                                      │
│  D — Durability                      │    │                                      │
│      Once committed, data survives   │    │                                      │
│      crashes, power failures.        │    │                                      │
│                                      │    │                                      │
│  USE FOR:                            │    │  USE FOR:                            │
│  Banking, Financial Systems,         │    │  Social Media, Analytics,            │
│  Inventory, Payments                 │    │  Caching, Logging, Search            │
│  PostgreSQL, MySQL, Oracle           │    │  Cassandra, DynamoDB, MongoDB        │
└──────────────────────────────────────┘    └──────────────────────────────────────┘
```

---

## How ACID vs BASE Connects to CAP

```
┌─────────────────────────────────────────────────────────────────────┐
│                    THE RELATIONSHIP                                   │
│                                                                      │
│   CAP Theorem                    Consistency Model                   │
│   ───────────                    ──────────────────                  │
│                                                                      │
│   CP (Consistency               ACID                                 │
│    + Partition Tolerance)  ────▶ Strong consistency                  │
│                                  Transactions guaranteed             │
│                                  May sacrifice availability          │
│                                                                      │
│   AP (Availability              BASE                                 │
│    + Partition Tolerance)  ────▶ Eventual consistency                │
│                                  Always responds                     │
│                                  Data converges over time            │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  SPECTRUM (not binary):                                      │   │
│   │                                                              │   │
│   │  ACID ◄──────────────────────────────────────────────▶ BASE │   │
│   │  Strong    Linearizable  Causal   Session   Eventual   Weak │   │
│   │                                                              │   │
│   │  PostgreSQL   DynamoDB    Redis   Elasticsearch  Akamai CDN │   │
│   │  (default)    (strong     (async  (near-real     (cached    │   │
│   │               read opt)   repl)    time index)    content)  │   │
│   └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## ACID vs BASE In My CXP Projects

### The Complete Picture

Every data store in our CXP platform sits somewhere on the ACID-BASE spectrum. **None are pure ACID** (we have no relational database). **Most are BASE** with compensating mechanisms.

```
┌──────────────────────────────────────────────────────────────────────────┐
│              CXP PLATFORM — ACID vs BASE MAP                              │
│                                                                          │
│  ACID side                                                 BASE side    │
│  (Strong)                                                 (Eventual)    │
│  ◄────────────────────────────────────────────────────────────────────▶ │
│                                                                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐ │
│  │ Athena   │  │ DynamoDB │  │  Redis   │  │  Elastic │  │  Akamai  │ │
│  │ (S3)     │  │ (strong  │  │ Primary  │  │  search  │  │  CDN     │ │
│  │          │  │  reads)  │  │  writes  │  │          │  │          │ │
│  │ Strong   │  │ Strong   │  │ Strong   │  │ Near-    │  │ Stale    │ │
│  │ read-    │  │ within   │  │ for      │  │ real-    │  │ for      │ │
│  │ after-   │  │ single   │  │ single   │  │ time     │  │ minutes  │ │
│  │ write    │  │ region   │  │ key ops  │  │ indexing │  │          │ │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘ │
│       │              │             │              │              │      │
│       ▼              ▼             ▼              ▼              ▼      │
│  Source of      Registration   Idempotency   Event search    Cached    │
│  truth for      queue (local)  cache         & discovery     event     │
│  investigation                                               pages     │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: Redis Idempotency — BASE with ACID-Like Behavior

**The problem:** User double-clicks "Register" — two identical requests hit the server within milliseconds.

```
┌─────────────────────────────────────────────────────────────────────────┐
│  IF WE USED ACID (SQL):                                                  │
│                                                                          │
│  BEGIN TRANSACTION;                                                      │
│    SELECT * FROM registrations                                           │
│      WHERE user_id = 'user123' AND event_id = 73067                     │
│      FOR UPDATE;                      ← Row-level lock acquired          │
│                                                                          │
│    -- Request 2 BLOCKS here, waiting for lock                            │
│                                                                          │
│    INSERT INTO registrations (user_id, event_id) ...;                    │
│  COMMIT;                              ← Lock released                    │
│                                                                          │
│  -- Request 2 now executes, finds existing row → rejects                 │
│                                                                          │
│  ✓ Correct behavior                                                      │
│  ✗ 10-50ms per operation (disk I/O + lock wait)                          │
│  ✗ Lock contention under high concurrency                                │
│  ✗ Doesn't scale horizontally                                            │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│  WHAT WE ACTUALLY USE — BASE (Redis):                                    │
│                                                                          │
│  SET "user123:event456" "processing" NX EX 300                           │
│      │                    │           │   │                              │
│      │                    │           │   └── Expire in 300 seconds      │
│      │                    │           └────── Only set if NOT EXISTS     │
│      │                    └────────────────── Value                      │
│      └─────────────────────────────────────── Key                       │
│                                                                          │
│  Request 1: SET → OK (key didn't exist) → proceed to register           │
│  Request 2: SET → nil (key exists)       → return 409 Conflict          │
│                                                                          │
│  ✓ Correct behavior (same result as ACID)                                │
│  ✓ <1ms per operation (in-memory)                                        │
│  ✓ No lock contention                                                    │
│  ✓ Scales horizontally with read replicas                                │
│                                                                          │
│  ✗ Soft state: key auto-expires (TTL). If server crashes between         │
│    SET and Eventtia call, key expires and user COULD re-register.        │
│    Eventtia catches this with its own duplicate check (defense in depth) │
└─────────────────────────────────────────────────────────────────────────┘
```

**Key insight:** Redis gives us ACID-*like* atomicity for single operations (`SET NX` is atomic) but with BASE characteristics (soft state via TTL, no multi-key transactions, no durability guarantee).

**Interview answer:**
> "For our registration idempotency, we use Redis SET NX which is atomic for a single key — giving us ACID-like atomicity without SQL. The tradeoff is soft state: if the server crashes between setting the Redis key and completing the Eventtia call, the key expires via TTL and the user could theoretically re-register. We accept this because Eventtia has its own duplicate check — defense in depth. This BASE approach handles 1000x more concurrent registrations than a SQL FOR UPDATE lock."

---

### Example 2: DynamoDB — BASE Cross-Region, ACID-Like Single-Region

DynamoDB offers **tunable consistency** — a perfect example of the ACID-BASE spectrum:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  SINGLE-REGION (ACID-like behavior):                                     │
│                                                                          │
│  // Write an unprocessed registration                                    │
│  dynamoDbTable.putItem(request);                                         │
│                                                                          │
│  // Immediately read it back with strong consistency                     │
│  GetItemRequest.builder()                                                │
│      .consistentRead(true)     ← ACID-like: guaranteed to see write     │
│      .build();                                                           │
│                                                                          │
│  DynamoDB guarantees:                                                    │
│  ✓ Atomic single-item writes                                             │
│  ✓ Strongly consistent reads (if requested)                              │
│  ✓ Durable (replicated across 3 AZs within region)                       │
│  ✗ No multi-item ACID transactions (limited to 100 items max)            │
│                                                                          │
│  Verdict: ACID for single items, BASE for cross-item operations          │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│  CROSS-REGION GLOBAL TABLE (BASE behavior):                              │
│                                                                          │
│          us-east-1                         us-west-2                     │
│     ┌────────────────┐              ┌────────────────┐                  │
│     │  Write here     │   ~1 sec    │  Replica lags   │                  │
│     │  at T=0         │──eventual──▶│  sees write     │                  │
│     │                 │  replication │  at T=1         │                  │
│     └────────────────┘              └────────────────┘                  │
│                                                                          │
│  BASE properties:                                                        │
│  BA — Both regions accept reads/writes (basically available)             │
│  S  — Replica state is "soft" — changes without local input             │
│  E  — After ~1 second, both regions converge (eventually consistent)     │
│                                                                          │
│  Conflict resolution: Last-writer-wins (timestamp-based)                 │
└─────────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "DynamoDB sits in the middle of the ACID-BASE spectrum. Within a single region, single-item operations are ACID — atomic writes, strongly consistent reads if requested, durable across 3 AZs. But our Global Table replication to us-west-2 is BASE — eventually consistent with ~1 second lag and last-writer-wins conflict resolution. We accept this because an unprocessed registration queue doesn't need cross-region ACID — if both regions accept a write for the same user, the reprocessing logic handles it idempotently."

---

### Example 3: The Email Pipeline — BASE End-to-End

The entire email delivery pipeline is a textbook BASE system:

```
┌─────────────────────────────────────────────────────────────────────────┐
│              EMAIL PIPELINE — BASE PROPERTIES                            │
│                                                                          │
│  User registers                                                          │
│       │                                                                  │
│       ▼                                                                  │
│  ┌──────────────────┐   BA: Always accepts registration                  │
│  │ cxp-event-       │       (never blocks user)                          │
│  │ registration     │                                                    │
│  └────────┬─────────┘                                                    │
│           │                                                              │
│           ▼                                                              │
│  ┌──────────────────┐   S: Eventtia state is "soft" — webhook            │
│  │ Eventtia         │      may not fire immediately                      │
│  │ (webhook)        │                                                    │
│  └────────┬─────────┘                                                    │
│           │                                                              │
│           ▼                                                              │
│  ┌──────────────────┐   S: Partner Hub data appears eventually           │
│  │ Partner Hub (S3) │      (not instant after registration)              │
│  └────────┬─────────┘                                                    │
│           │                                                              │
│           ▼                                                              │
│  ┌──────────────────┐   S: Transform runs async, may be delayed          │
│  │ Rise GTS         │                                                    │
│  └────────┬─────────┘                                                    │
│           │                                                              │
│           ▼                                                              │
│  ┌──────────────────┐   S: MemberHub email sync is delayed               │
│  │ NCP → CRS →      │      (race condition: ~2-5% drop rate)            │
│  │ SendGrid         │                                                    │
│  └────────┬─────────┘                                                    │
│           │                                                              │
│           ▼                                                              │
│  ┌──────────────────┐   E: Eventually, email is delivered                │
│  │ User inbox       │      (or recovered via reprocessing)               │
│  └──────────────────┘                                                    │
│                                                                          │
│  TOTAL END-TO-END LATENCY: seconds to minutes                            │
│  CONSISTENCY GUARANTEE: Eventually consistent                            │
│  COMPENSATING MECHANISM: cxp-email-drop-recovery dashboard               │
└─────────────────────────────────────────────────────────────────────────┘
```

**Why not ACID for the email pipeline?**

| ACID Pipeline (hypothetical) | BASE Pipeline (actual) |
|---|---|
| Registration blocks until email is confirmed delivered | Registration returns instantly |
| User waits 30-60 seconds | User waits <2 seconds |
| Zero email drops | ~2-5% drops (recovered automatically) |
| Single point of failure (if any step fails, user sees error) | Failures isolated to notification layer |
| Cannot scale — each registration holds resources for 60s | Massively scalable — fire-and-forget |

**Interview answer:**
> "Our entire email pipeline is BASE. The user registers (Basically Available — never blocked), state flows through Eventtia → S3 → Rise → NCP asynchronously (Soft State — each stage may be delayed), and the email Eventually arrives. The ~2-5% drop rate from the MemberHub race condition is our BASE tradeoff — we accept eventual consistency in email delivery because the alternative (ACID-style synchronous pipeline where registration blocks until email is confirmed) would mean 30-60 second registration times and a single point of failure across 6 services."

---

### Example 4: Elasticsearch Indexing — BASE

```
  Eventtia (source of truth)           Elasticsearch (search layer)
  ┌──────────────────────┐            ┌──────────────────────┐
  │ Event updated at T=0 │            │ Index updated at T=5 │
  │ (address changed)    │   ~5 sec   │ (address still old)  │
  │                      │───delay───▶│                      │
  └──────────────────────┘            └──────────────────────┘

  For 5 seconds, search results show stale address.
  This is BASE: Eventually Consistent.
  Acceptable because: wrong address on search page ≠ wrong address on confirmation email.
```

---

### Example 5: Akamai CDN — Extreme BASE

```
  Origin (cxp-events)                  CDN Edge (Tokyo)
  ┌──────────────────────┐            ┌──────────────────────┐
  │ Event status: FULL   │            │ Event status: OPEN   │
  │ (updated 2 min ago)  │   TTL=5m   │ (cached version)     │
  │                      │───stale───▶│                      │
  └──────────────────────┘            └──────────────────────┘

  For up to 5 minutes, users in Tokyo see "OPEN" when event is "FULL".
  This is the most extreme BASE in our system.
  Acceptable because: registration endpoint (not CDN) enforces the real check.
  Purge via NSP3 Kafka Purge Sink reduces staleness.
```

---

## Where ACID Would Be Required (Interview Discussion)

Even though CXP is all-BASE, I can articulate when ACID is non-negotiable:

```
┌────────────────────────────────────────────────────────────────────────────┐
│  SCENARIO                    │  WHY ACID IS REQUIRED                       │
├──────────────────────────────┼────────────────────────────────────────────┤
│  Payment processing          │  Debit $100 from user, credit $100 to      │
│                              │  merchant. If one fails, BOTH must roll     │
│                              │  back. Partial state = lost money.          │
├──────────────────────────────┼────────────────────────────────────────────┤
│  Seat inventory (if we       │  Decrement seats AND create registration   │
│  owned it, not Eventtia)     │  atomically. Partial state = overselling.  │
├──────────────────────────────┼────────────────────────────────────────────┤
│  User account creation       │  Create user + profile + preferences +     │
│                              │  default settings atomically. Partial      │
│                              │  state = broken account.                   │
├──────────────────────────────┼────────────────────────────────────────────┤
│  Order placement             │  Reserve inventory + create order + charge │
│                              │  payment. All or nothing.                  │
└──────────────────────────────┴────────────────────────────────────────────┘
```

**Interview answer for when they ask "when would you choose ACID?":**
> "If CXP owned the seat inventory instead of Eventtia, I'd use PostgreSQL with ACID transactions. The operation 'decrement available seats AND insert registration AND update waitlist' must be atomic — if the seat decrement succeeds but registration insert fails, you've lost a seat. DynamoDB's conditional writes work for single-item checks, but multi-table atomicity requires ACID. Similarly, if we processed payments for premium events, I'd never use DynamoDB — partial payment states could mean charging users without registering them."

---

## Summary: ACID vs BASE Across CXP

| Component | Consistency Model | Properties Used | Tradeoff Accepted |
|-----------|------------------|-----------------|-------------------|
| **Redis idempotency** | BASE (ACID-like single-op) | Atomic SET NX, Soft state (TTL) | Key expires on crash; Eventtia is backup check |
| **DynamoDB (single region)** | ACID-like | Atomic writes, strong reads, 3-AZ durable | No multi-item transactions |
| **DynamoDB (Global Table)** | BASE | Eventually consistent cross-region | ~1s replication lag, last-writer-wins |
| **Elasticsearch** | BASE | Eventually consistent index | ~5s stale search results |
| **Email pipeline** | BASE end-to-end | Async, fire-and-forget | ~2-5% email drop rate, recovered via dashboard |
| **S3 + Athena** | Strong consistency | Read-after-write guaranteed | Query latency (seconds), not real-time |
| **Akamai CDN** | Extreme BASE | Stale content for TTL duration | Up to 5 min stale; purge via Kafka |

---

## Common Interview Follow-ups

### Q: "Your system drops 2-5% of emails. Isn't that a problem?"

> "It's a conscious BASE tradeoff. The alternative — an ACID-style synchronous pipeline — would block registration for 30-60 seconds and create cascading failures across 6 services. Instead, we accept eventual consistency (most emails arrive within minutes) and built a compensating mechanism: the email-drop-recovery dashboard detects gaps via Splunk + Athena reconciliation and re-triggers delivery via RISE API. The net result is >99.5% email delivery with <2 second registration time."

### Q: "How do you prevent data corruption without ACID?"

> "Three strategies:
> 1. **Idempotent operations** — every service can safely re-process the same message (Redis dedup, Eventtia duplicate check, DynamoDB conditional writes).
> 2. **Compensating transactions** — when the async pipeline fails, the recovery dashboard detects and fixes gaps (this is the Saga pattern without a coordinator).
> 3. **Source of truth separation** — Eventtia is authoritative for registrations, Partner Hub (S3) for webhook delivery, Splunk for pipeline execution. Each service trusts ONE source, not multiple."

### Q: "Can you convert a BASE system to ACID?"

> "You can add ACID-like guarantees to specific operations within a BASE system:
> - Redis `SET NX` gives atomic check-and-set (single-key ACID).
> - DynamoDB `TransactWriteItems` gives multi-item ACID (up to 100 items).
> - The Saga pattern gives distributed ACID-like behavior with compensating rollbacks.
> But making the ENTIRE pipeline ACID would require synchronous, blocking calls across 6 services — which kills availability and throughput. The right approach is ACID where correctness is critical (seat counts, payments) and BASE everywhere else."

---
---

# Topic 4: Database Selection

> Match your database to your data: relational for transactions, document for flexibility, graph for relationships, time-series for metrics.

> **Interview Tip:** Discuss multiple databases — "I'd use PostgreSQL for users, Redis for cache, Elasticsearch for search, and InfluxDB for metrics."

---

## The 7 Database Categories

Every database falls into one of these categories. The right choice depends on **data shape**, **access pattern**, and **scale requirements** — not personal preference.

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          DATABASE SELECTION GUIDE                                │
│                                                                                 │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐   │
│  │  RELATIONAL   │  │   DOCUMENT    │  │  KEY-VALUE    │  │ WIDE-COLUMN   │   │
│  │               │  │               │  │               │  │               │   │
│  │ Structured    │  │ Flexible      │  │ Simple,       │  │ Massive scale │   │
│  │ data, ACID    │  │ schema, JSON  │  │ ultra-fast    │  │ sparse data   │   │
│  │               │  │               │  │               │  │               │   │
│  │ - Transactions│  │ - Nested obj  │  │ - O(1) lookup │  │ - Time-series │   │
│  │ - Complex     │  │ - Schema      │  │ - Caching     │  │ - High write  │   │
│  │   queries     │  │   evolution   │  │   layer       │  │   volume      │   │
│  │ - Data        │  │ - Horizontal  │  │ - Session     │  │ - Column      │   │
│  │   integrity   │  │   scale       │  │   storage     │  │   families    │   │
│  │               │  │               │  │               │  │               │   │
│  │ PostgreSQL    │  │ MongoDB       │  │ Redis     ✓   │  │ Cassandra     │   │
│  │ MySQL         │  │ CouchDB       │  │ Memcached     │  │ HBase         │   │
│  │               │  │               │  │               │  │               │   │
│  │ Banking,      │  │ CMS, User     │  │ Cache,        │  │ IoT,          │   │
│  │ E-commerce    │  │ profiles      │  │ Sessions      │  │ Messaging     │   │
│  └───────────────┘  └───────────────┘  └───────────────┘  └───────────────┘   │
│                                                                                 │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐                       │
│  │    GRAPH      │  │  TIME-SERIES  │  │ SEARCH ENGINE │                       │
│  │               │  │               │  │               │                       │
│  │ Relationships │  │ Temporal data │  │ Full-text     │                       │
│  │ first         │  │ optimized     │  │ search        │                       │
│  │               │  │               │  │               │                       │
│  │ - Connected   │  │ - Metrics/    │  │ - Inverted    │                       │
│  │   data        │  │   events      │  │   index       │                       │
│  │ - Traversal   │  │ - Auto-agg    │  │ - Fuzzy       │                       │
│  │   queries     │  │ - Data        │  │   matching    │                       │
│  │ - Pattern     │  │   retention   │  │ - Relevance   │                       │
│  │   matching    │  │               │  │   scoring     │                       │
│  │               │  │               │  │               │                       │
│  │ Neo4j         │  │ InfluxDB      │  │ Elastic-  ✓   │                       │
│  │ Neptune       │  │ TimescaleDB   │  │ search        │                       │
│  │               │  │               │  │ Solr          │                       │
│  │ Social,       │  │ Monitoring,   │  │ Search,       │                       │
│  │ Recommendations│ │ Analytics     │  │ Logging       │                       │
│  └───────────────┘  └───────────────┘  └───────────────┘                       │
│                                                                                 │
│  Plus: OBJECT STORE (S3 ✓) + QUERY ENGINE (Athena ✓)                           │
│        Not a "database" but stores and queries data at TB/PB scale              │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Decision Flowchart

```
What does your data look like?
│
├── Structured rows & columns, relationships between entities?
│   ├── Need ACID transactions across multiple tables?
│   │   └── ✅ RELATIONAL (PostgreSQL, MySQL)
│   └── Relationships ARE the data? (friends-of-friends, recommendations)
│       └── ✅ GRAPH (Neo4j, Neptune)
│
├── Semi-structured / nested JSON, schema changes frequently?
│   └── ✅ DOCUMENT (MongoDB, CouchDB)
│
├── Simple key → value, need sub-millisecond reads?
│   ├── Caching / session / idempotency?
│   │   └── ✅ KEY-VALUE IN-MEMORY (Redis, Memcached)
│   └── Durable key-value at scale?
│       └── ✅ KEY-VALUE PERSISTENT (DynamoDB, Riak)
│
├── Time-stamped metrics, events, logs?
│   ├── Need auto-aggregation, retention policies, downsampling?
│   │   └── ✅ TIME-SERIES (InfluxDB, TimescaleDB, Prometheus)
│   └── Just need to search/filter logs?
│       └── ✅ SEARCH ENGINE (Elasticsearch)
│
├── Full-text search with relevance scoring, fuzzy matching?
│   └── ✅ SEARCH ENGINE (Elasticsearch, Solr, OpenSearch)
│
├── Massive write volume, sparse columns, wide rows?
│   └── ✅ WIDE-COLUMN (Cassandra, HBase, ScyllaDB)
│
└── TB/PB of raw files, queried infrequently?
    └── ✅ OBJECT STORE + QUERY ENGINE (S3 + Athena)
```

---

## My CXP Platform — Database Selection In Practice

Our platform uses **5 different database categories** — each chosen for a specific data pattern. This is polyglot persistence: the right database for each job.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│           CXP PLATFORM — DATABASE SELECTION MAP                               │
│                                                                              │
│  ┌─────────────┐                                                             │
│  │   USER      │                                                             │
│  │   REQUEST   │                                                             │
│  └──────┬──────┘                                                             │
│         │                                                                    │
│         ▼                                                                    │
│  ┌─────────────┐  "What events are near me?"                                │
│  │ SEARCH      │  ─────────────────────────▶  Elasticsearch                 │
│  │ ENGINE      │  Full-text, geo, relevance    (expviewsnikeapp)            │
│  └─────────────┘                                                             │
│         │                                                                    │
│         ▼                                                                    │
│  ┌─────────────┐  "Show me event details"                                   │
│  │ KEY-VALUE   │  ─────────────────────────▶  Redis (ElastiCache)           │
│  │ (Cache)     │  Sub-ms cached response       (cxp-event-registration)     │
│  └─────────────┘                                                             │
│         │ cache miss                                                         │
│         ▼                                                                    │
│  ┌─────────────┐  "Register me for this event"                              │
│  │ RELATIONAL  │  ─────────────────────────▶  Eventtia (external)           │
│  │ (External)  │  ACID seat decrement           (likely PostgreSQL)         │
│  └─────────────┘                                                             │
│         │                                                                    │
│         ▼                                                                    │
│  ┌─────────────┐  "Store failed registration for retry"                     │
│  │ KEY-VALUE   │  ─────────────────────────▶  DynamoDB Global Table         │
│  │ (Durable)   │  Key-value CRUD, auto-scale   (cxp-event-registration)    │
│  └─────────────┘                                                             │
│         │                                                                    │
│         ▼                                                                    │
│  ┌─────────────┐  "Store webhook + transform data"                          │
│  │ OBJECT      │  ─────────────────────────▶  S3 + SQS + Athena            │
│  │ STORE       │  TB-scale, serverless SQL     (Partner Hub, Rise GTS)      │
│  └─────────────┘                                                             │
│         │                                                                    │
│         ▼                                                                    │
│  ┌─────────────┐  "Track pipeline health & drops"                           │
│  │ SEARCH/     │  ─────────────────────────▶  Splunk                        │
│  │ TIME-SERIES │  Log search + time-based      (all services)               │
│  │ (Hybrid)    │  trend analysis                                            │
│  └─────────────┘                                                             │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Detailed Selection Rationale Per Service

### 1. Elasticsearch — SEARCH ENGINE category

**Service:** `expviewsnikeapp`
**Data:** Event names, descriptions, locations, dates, categories
**Access pattern:** "Nike running events in Portland this weekend"

| Why Elasticsearch | Why NOT alternatives |
|---|---|
| Inverted index → O(1) text search | PostgreSQL `LIKE '%running%'` → O(n) full table scan |
| Relevance scoring (BM25) | DynamoDB → no full-text search at all |
| Geo-distance filters | MongoDB text search → weaker relevance tuning |
| Faceted aggregations (filter by city, category, date) | Redis → no search capabilities |
| Near-real-time indexing (~1s) | Solr → similar capability but less ecosystem |

**When to use Search Engine:**
- User-facing search boxes
- Autocomplete / typeahead
- Log analysis and filtering
- Any query where relevance ranking matters

---

### 2. Redis — KEY-VALUE (In-Memory) category

**Service:** `cxp-event-registration`
**Data:** Idempotency keys, pairwise ID mappings, cached responses
**Access pattern:** GET/SET by exact key, with TTL auto-expiry

| Why Redis | Why NOT alternatives |
|---|---|
| Sub-millisecond reads (in-memory) | PostgreSQL → 5-50ms (disk I/O) |
| Native TTL expiry (no cleanup cron) | DynamoDB → TTL exists but has ~48h delay |
| Atomic SET NX (perfect for idempotency) | Memcached → no replication, no persistence |
| Read replicas (`REPLICA_PREFERRED`) | MongoDB → 2-10ms, overkill for key-value |
| Data structures (sets, sorted sets, hashes) | Application-level cache → no shared state across instances |

**When to use Key-Value (In-Memory):**
- Caching (cache-aside, write-through)
- Session storage
- Rate limiting / deduplication
- Leaderboards (sorted sets)
- Pub/sub messaging

---

### 3. DynamoDB — KEY-VALUE (Durable) category

**Service:** `cxp-event-registration`
**Data:** Unprocessed registration requests
**Access pattern:** PUT/GET/DELETE by partition key, occasional full scan

| Why DynamoDB | Why NOT alternatives |
|---|---|
| Serverless, zero ops | RDS → server management, patching, scaling |
| Auto-scales to any throughput | Redis → not durable (data lost on restart) |
| Global Tables (multi-region) | MongoDB → manual sharding for multi-region |
| Pay per request (no idle cost) | Cassandra → operational complexity |
| Single-digit ms latency | PostgreSQL → vertical scaling limits |
| Conditional writes (optimistic locking) | S3 → no key-value CRUD semantics |

**When to use Key-Value (Durable):**
- Simple CRUD with known access patterns
- High write throughput with auto-scaling
- Session/state storage requiring durability
- Queue-like patterns (write, read, delete)
- Multi-region replication needed

---

### 4. S3 + Athena — OBJECT STORE + QUERY ENGINE category

**Service:** Partner Hub (webhook storage), Rise GTS (transform I/O)
**Data:** Raw JSON webhook payloads, transformed event data
**Access pattern:** Append-only writes, infrequent SQL queries for investigation

| Why S3 + Athena | Why NOT alternatives |
|---|---|
| $0.023/GB/month storage | RDS → $0.10-0.50/hour + EBS ($100s/month for TBs) |
| Serverless SQL ($5/TB scanned) | DynamoDB → no SQL queries, no JOINs |
| Schema-on-read (no migrations) | PostgreSQL → schema changes = downtime risk |
| Unlimited scale (petabytes) | Elasticsearch → expensive for cold storage |
| Columnar format support (Parquet) | MongoDB → not cost-effective at TB scale |

**When to use Object Store + Query Engine:**
- Audit trails and compliance logs
- Data lake / analytics warehouse
- Infrequently queried historical data
- Semi-structured data with evolving schema
- Cost-sensitive TB/PB storage

---

### 5. Splunk — SEARCH + TIME-SERIES (Hybrid) category

**Service:** All CXP services (centralized logging)
**Data:** Application logs with timestamps
**Access pattern:** Time-range search + keyword filtering + aggregations

| Why Splunk | Why NOT alternatives |
|---|---|
| Real-time log ingestion | Elasticsearch → similar but self-managed |
| SPL query language (powerful) | CloudWatch → limited query capability |
| Built-in alerting and dashboards | InfluxDB → metrics only, not log search |
| Correlation across services | Athena → too slow for real-time investigation |
| Enterprise-grade (Nike standard) | Prometheus → metrics, not logs |

Splunk acts as **both** a search engine (keyword search across logs) and a time-series store (trend analysis, the Trend tab in email-drop-recovery). This hybrid role is why our email-drop-recovery dashboard queries Splunk for:
- **Search pattern:** `MissingRequiredVariablesError cxp` (search engine behavior)
- **Time-series pattern:** Daily drop counts over 30 days with moving average (time-series behavior)

---

### 6. Eventtia (External) — RELATIONAL category

**Service:** External SaaS platform
**Data:** Events, attendees, activities, seats, registrations
**Access pattern:** Complex relationships between entities + ACID transactions

```
  ┌──────────┐     ┌──────────────┐     ┌──────────────┐
  │  Event   │────▶│  Activities  │────▶│   Tickets    │
  │          │     │  (workshops) │     │  (seats)     │
  └──────┬───┘     └──────────────┘     └──────────────┘
         │
         │         ┌──────────────┐     ┌──────────────┐
         └────────▶│  Attendees   │────▶│ Registrations│
                   │  (users)     │     │  (bookings)  │
                   └──────────────┘     └──────────────┘

  Registering for an event requires:
  1. Check event exists (Events table)
  2. Check activity has seats (Tickets table)
  3. Check user not already registered (Registrations table)
  4. Decrement seats (Tickets table)
  5. Create registration (Registrations table)
  → Steps 4+5 MUST be atomic → ACID transaction → Relational DB
```

**Why relational for Eventtia:** These entities have deep relationships (event → activities → tickets → registrations → attendees). A single registration touches 3+ tables atomically. This is exactly where SQL shines — and why we delegate to Eventtia rather than building our own.

---

## Categories NOT Used in CXP (and When You Would)

### Document DB (MongoDB, CouchDB) — NOT used

**When you'd use it:**
- User profiles with varying fields per user
- CMS content with nested/embedded documents
- Product catalogs where each product has different attributes
- Rapid prototyping where schema is unknown

**Why not in CXP:**
- Our JSON data goes to S3 (cheaper for TB scale) or Elasticsearch (better search).
- DynamoDB handles our key-value needs with less operational overhead than MongoDB.

### Graph DB (Neo4j, Neptune) — NOT used

**When you'd use it:**
- Social networks (friends-of-friends queries)
- Recommendation engines ("users who liked X also liked Y")
- Fraud detection (find suspicious transaction patterns)
- Knowledge graphs, org charts, dependency trees

**Why not in CXP:**
- Event registrations don't have deep graph relationships.
- The event → attendee relationship is simple (one-to-many), not graph-shaped.
- If Nike needed "suggest events based on what similar users attended," Neptune would be the right choice.

### Wide-Column (Cassandra, HBase) — NOT used

**When you'd use it:**
- IoT sensor data (millions of devices, billions of rows)
- Chat messaging (write-heavy, partition by conversation)
- Time-series at extreme scale (when InfluxDB can't keep up)

**Why not in CXP:**
- Our write volume doesn't justify Cassandra's operational complexity.
- DynamoDB gives us similar horizontal scale with zero ops.

### Time-Series DB (InfluxDB, TimescaleDB) — NOT directly used

**When you'd use it:**
- Infrastructure metrics (CPU, memory, latency)
- Application performance monitoring
- IoT sensor readings
- Financial tick data

**What CXP uses instead:**
- Splunk covers our time-series needs (daily drop trends, pipeline latency).
- If we needed dedicated metrics with downsampling and retention policies, InfluxDB or Prometheus + Grafana would be the choice.

---

## The Polyglot Persistence Pattern

Using multiple databases in one system is called **polyglot persistence**. CXP is a textbook example:

```
┌──────────────────────────────────────────────────────────────────────┐
│              POLYGLOT PERSISTENCE — CXP PLATFORM                      │
│                                                                      │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐    │
│  │ Elastic-   │  │ Redis      │  │ DynamoDB   │  │ S3+Athena  │    │
│  │ search     │  │            │  │            │  │            │    │
│  │            │  │ Cache &    │  │ Durable    │  │ Data lake  │    │
│  │ Search &   │  │ Idempot-  │  │ Key-Value  │  │ & SQL      │    │
│  │ Discovery  │  │ ency      │  │ Queue      │  │ Queries    │    │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘    │
│        │               │               │               │            │
│        └───────────────┴───────────────┴───────────────┘            │
│                                │                                     │
│                    ┌───────────┴───────────┐                        │
│                    │   APPLICATION LAYER   │                        │
│                    │   (Spring Boot        │                        │
│                    │    microservices)      │                        │
│                    └───────────────────────┘                        │
│                                                                      │
│  BENEFITS:                         COSTS:                           │
│  ✓ Best performance per use case   ✗ Operational complexity         │
│  ✓ Independent scaling             ✗ Data consistency challenges    │
│  ✓ Right cost per workload         ✗ Team needs broader DB skills   │
│  ✓ Failure isolation               ✗ More monitoring/alerting       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Interview: "Design the Database Layer for an Event Platform"

Here's how to walk through database selection in an interview using the CXP platform as your example:

```
Step 1: Identify the data entities
─────────────────────────────────
Events, Activities, Attendees, Registrations, Email notifications,
Webhooks, Transform payloads, Application logs

Step 2: Classify each by access pattern
────────────────────────────────────────
┌──────────────────────┬──────────────────────┬───────────────────────┐
│  Entity              │  Access Pattern       │  DB Category          │
├──────────────────────┼──────────────────────┼───────────────────────┤
│  Events + Attendees  │  Complex relations,   │  RELATIONAL           │
│  + Registrations     │  ACID seat decrement  │  (Eventtia/PostgreSQL)│
├──────────────────────┼──────────────────────┼───────────────────────┤
│  Event search/       │  Full-text, geo,      │  SEARCH ENGINE        │
│  discovery           │  relevance scoring    │  (Elasticsearch)      │
├──────────────────────┼──────────────────────┼───────────────────────┤
│  Idempotency keys    │  SET/GET by key,      │  KEY-VALUE (memory)   │
│  + cached responses  │  sub-ms, TTL expiry   │  (Redis)              │
├──────────────────────┼──────────────────────┼───────────────────────┤
│  Failed registrations│  CRUD by key,         │  KEY-VALUE (durable)  │
│  for retry           │  auto-scale, durable  │  (DynamoDB)           │
├──────────────────────┼──────────────────────┼───────────────────────┤
│  Webhook payloads    │  Append-only, TB      │  OBJECT STORE         │
│  + audit trail       │  scale, rare queries  │  (S3 + Athena)        │
├──────────────────────┼──────────────────────┼───────────────────────┤
│  Application logs    │  Time-range search,   │  SEARCH + TIME-SERIES │
│  + metrics           │  keyword filter, agg  │  (Splunk)             │
└──────────────────────┴──────────────────────┴───────────────────────┘

Step 3: Justify each choice with tradeoffs
───────────────────────────────────────────
(See detailed rationale above for each)

Step 4: Address consistency across stores
─────────────────────────────────────────
"We use eventual consistency with compensating mechanisms:
 - Source of truth per domain (Eventtia for registrations, S3 for webhooks)
 - Idempotent operations at every layer
 - Reconciliation dashboard to detect and fix gaps"
```

---

## Common Interview Follow-ups

### Q: "Isn't using 5 databases overkill? Why not just PostgreSQL for everything?"

> "PostgreSQL is excellent, but it would be a compromise at every layer:
> - **Search:** `LIKE '%keyword%'` → full table scan vs Elasticsearch O(1)
> - **Cache:** 5-50ms disk reads vs Redis <1ms in-memory
> - **Scale:** Single-primary bottleneck vs DynamoDB auto-scale
> - **Cost:** $500+/month for TB audit data vs S3 at $12/month
> - **Logs:** No built-in log ingestion/alerting vs Splunk real-time
>
> Each database we chose eliminates a category of problems PostgreSQL would struggle with. The operational cost of running 5 databases is offset by not building workarounds for PostgreSQL's limitations."

### Q: "How do you decide when to add a new database vs stretching an existing one?"

> "Three signals:
> 1. **Access pattern mismatch** — if you're building complex workarounds (like caching PostgreSQL results in application memory), you need a dedicated cache.
> 2. **Scale ceiling** — if your current DB can't handle the throughput without expensive vertical scaling, consider a horizontally scalable alternative.
> 3. **Cost curve** — if storage costs are growing linearly with data that's rarely accessed, move cold data to cheaper storage (S3)."

### Q: "What about a single document DB like MongoDB for everything?"

> "MongoDB is versatile but has specific weaknesses:
> - **No full-text relevance scoring** like Elasticsearch (MongoDB text search is basic)
> - **No in-memory speed** like Redis (MongoDB reads hit disk)
> - **No ACID across collections** by default (added in 4.0+ but with performance cost)
> - **Not cost-effective for cold data** at TB scale (S3 is 100x cheaper)
>
> MongoDB would work well as the **primary datastore** for event metadata (replacing Eventtia's DB), but you'd still need Redis for caching, Elasticsearch for search, and S3 for archival."

### Q: "If you were starting from scratch, would you make the same choices?"

> "Mostly yes, with one change: I'd consider **OpenSearch** (AWS-managed Elasticsearch fork) instead of self-managed Elasticsearch for lower ops burden. I'd also evaluate **DynamoDB Streams** to replace the SQS trigger pattern in Rise GTS — it would simplify the S3 → SQS → Transform flow into DynamoDB → Stream → Transform. The core polyglot persistence pattern stays the same because each database solves a fundamentally different problem."

---
---

# Topic 5: Database Indexing

> B-trees for range queries, hash indexes for exact lookups, composite indexes for multi-column filters — but every index slows writes.

> **Interview Tip:** When discussing query optimization, mention "I'd add a composite index on (user_id, created_at) for the common query pattern, but limit indexes on write-heavy tables."

---

## What Is an Index?

A data structure that **speeds up reads at the cost of writes and storage**. Without an index, every query does a full table scan — O(n). With an index, lookups drop to O(log n) or O(1).

```
WITHOUT INDEX:                          WITH INDEX:
─────────────                           ──────────

Query: WHERE user_id = 'abc'            Query: WHERE user_id = 'abc'

┌──────────────────────┐                ┌──────────────────────┐
│  Row 1  ← scan       │                │  Index: user_id      │
│  Row 2  ← scan       │                │  ┌─────────┐        │
│  Row 3  ← scan       │                │  │ 'abc' ──┼──▶ Row 5 (direct jump)
│  Row 4  ← scan       │                │  │ 'def' ──┼──▶ Row 2 │
│  Row 5  ← FOUND!     │                │  │ 'xyz' ──┼──▶ Row 1 │
│  Row 6  ← scan       │                │  └─────────┘        │
│  ...                  │                └──────────────────────┘
│  Row N  ← scan       │
└──────────────────────┘                Time: O(log n) or O(1)
                                        Cost: extra storage + slower writes
Time: O(n) — checks every row           (index must be updated on every INSERT/UPDATE)
```

---

## The 6 Index Types

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         INDEX TYPES                                       │
│                                                                          │
│  ┌────────────────────┐  ┌────────────────────┐  ┌────────────────────┐ │
│  │   B-TREE INDEX     │  │   HASH INDEX       │  │  INVERTED INDEX    │ │
│  │   Default in most  │  │   Exact match only │  │  Full-text search  │ │
│  │   databases        │  │                    │  │                    │ │
│  │                    │  │  [+] O(1) exact    │  │  [+] Word→document │ │
│  │  [+] Range queries │  │      lookups       │  │      mapping       │ │
│  │      (BETWEEN,<,>) │  │  [+] Equality      │  │  [+] Text search,  │ │
│  │  [+] Sorting       │  │      comparisons   │  │      tokenization  │ │
│  │      (ORDER BY)    │  │  [-] No range      │  │  [+] Relevance     │ │
│  │  [+] Prefix match  │  │      queries       │  │      scoring       │ │
│  │      (LIKE 'abc%') │  │                    │  │                    │ │
│  │                    │  │  Memory tables,     │  │  Elasticsearch,    │ │
│  │  O(log n) for all  │  │  key-value lookups  │  │  PostgreSQL FTS    │ │
│  └────────────────────┘  └────────────────────┘  └────────────────────┘ │
│                                                                          │
│  ┌────────────────────┐  ┌────────────────────┐  ┌────────────────────┐ │
│  │  COMPOSITE INDEX   │  │  COVERING INDEX    │  │  PARTIAL INDEX     │ │
│  │                    │  │                    │  │                    │ │
│  │  Multi-column:     │  │  Index contains    │  │  Index subset with │ │
│  │  (a, b, c)         │  │  all query columns │  │  WHERE clause      │ │
│  │                    │  │                    │  │                    │ │
│  │  Leftmost prefix   │  │  No table lookup   │  │  Smaller, more     │ │
│  │  rule applies      │  │  needed            │  │  efficient         │ │
│  └────────────────────┘  └────────────────────┘  └────────────────────┘ │
│                                                                          │
│  TRADEOFF: Faster reads vs slower writes vs more storage                │
│  RULE: Index columns in WHERE, JOIN, ORDER BY clauses                   │
│  TOOL: Use EXPLAIN to analyze query plans                               │
│  CAUTION: Avoid over-indexing (maintenance cost on every write)         │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## How Each Index Type Works

### B-Tree Index (default in PostgreSQL, MySQL)

```
                        [M]
                       /   \
                   [D,H]   [Q,T]
                  / | \    / | \
               [A,C][E,G][N,P][R,S][U,Z]
                              ↑
                     WHERE name = 'P'
                     → 3 hops: root → branch → leaf
                     → O(log n)

Supports:
  WHERE age > 25          ✓ (range scan on leaf nodes)
  WHERE name = 'Nike'     ✓ (exact match via traversal)
  ORDER BY created_at     ✓ (leaves are sorted, scan in order)
  WHERE name LIKE 'Nik%'  ✓ (prefix = range scan on 'Nik' to 'Nil')
  WHERE name LIKE '%ike'  ✗ (suffix = full scan, index useless)
```

### Hash Index (DynamoDB partition key, Redis key lookup)

```
  Key: "event456_user123"
           │
           ▼
  hash("event456_user123") = bucket 7
           │
           ▼
  ┌─────────────────┐
  │  Bucket 7       │
  │  → Row pointer  │──▶ Data
  └─────────────────┘

  O(1) lookup — constant time regardless of table size.
  But: cannot do range queries (hash destroys ordering).
```

### Inverted Index (Elasticsearch)

```
  Document 1: "Nike running event in Portland"
  Document 2: "Nike basketball event in Chicago"
  Document 3: "Adidas running shoes sale"

  INVERTED INDEX:
  ┌──────────┬──────────────────┐
  │  Token   │  Document IDs    │
  ├──────────┼──────────────────┤
  │  nike    │  [1, 2]          │ ← query "nike" → O(1) → docs 1,2
  │  running │  [1, 3]          │ ← query "running" → O(1) → docs 1,3
  │  event   │  [1, 2]          │
  │  portland│  [1]             │
  │  chicago │  [2]             │
  │  adidas  │  [3]             │
  └──────────┴──────────────────┘

  Query "nike running" → intersection of [1,2] ∩ [1,3] = [1]
  → Document 1 matches. Scored by TF-IDF / BM25 for relevance.
```

### Composite Index — Leftmost Prefix Rule

```
  CREATE INDEX idx ON registrations (event_id, user_id, created_at);

  This ONE index supports these queries:
  ✓ WHERE event_id = 73067
  ✓ WHERE event_id = 73067 AND user_id = 'abc'
  ✓ WHERE event_id = 73067 AND user_id = 'abc' AND created_at > '2026-01-01'
  ✓ WHERE event_id = 73067 ORDER BY user_id

  But NOT these (leftmost prefix violated):
  ✗ WHERE user_id = 'abc'                    (skipped event_id)
  ✗ WHERE created_at > '2026-01-01'          (skipped event_id + user_id)
  ✗ WHERE user_id = 'abc' AND created_at ... (skipped event_id)
```

---

## Indexing In My CXP Projects — Real Examples

### 1. DynamoDB — HASH INDEX (Partition Key)

**Table:** `unprocessed-registration-requests`
**Partition Key:** `eventId_upmId` (composite string — e.g., `"73067_uuid-1234-5678"`)

```
┌─────────────────────────────────────────────────────────────────────┐
│  DynamoDB Indexing Model                                             │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────┐       │
│  │  Partition Key: "eventId_upmId"                          │       │
│  │                                                          │       │
│  │  "73067_uuid-1111"  ──▶  { payload, timestamp, ... }    │       │
│  │  "73067_uuid-2222"  ──▶  { payload, timestamp, ... }    │       │
│  │  "74001_uuid-3333"  ──▶  { payload, timestamp, ... }    │       │
│  │                                                          │       │
│  │  Access: O(1) by exact key                               │       │
│  │  No sort key → no range queries within a partition       │       │
│  │  No GSI/LSI → no secondary access patterns               │       │
│  └─────────────────────────────────────────────────────────┘       │
│                                                                     │
│  DESIGN CHOICE: Composite string key instead of partition + sort    │
│                                                                     │
│  Option A (what we use):              Option B (alternative):       │
│  PK: "73067_uuid-1234"               PK: "73067"  SK: "uuid-1234" │
│  → O(1) exact lookup                 → Range query: all users for  │
│  → Cannot query "all users            event 73067 (Query operation)│
│    for event 73067"                  → Costs more RCUs for scans   │
│                                                                     │
│  We chose Option A because the ONLY access patterns are:           │
│  1. PUT (save failed registration)                                  │
│  2. GET by exact key (check if exists)                              │
│  3. DELETE by exact key (after reprocessing)                        │
│  4. SCAN all (batch reprocessing — rare)                            │
│                                                                     │
│  No need for "get all users for event X" → no sort key needed.     │
└─────────────────────────────────────────────────────────────────────┘
```

**From the Terraform:**

```hcl
resource "aws_dynamodb_table" "unprocessed_registration_requests" {
  hash_key     = var.partition_key    # "eventId_upmId"
  billing_mode = "PAY_PER_REQUEST"
  # No range_key → hash-only index
  # No global_secondary_index block → no GSIs
}
```

**From the Java model:**

```java
@DynamoDbBean
public class UnprocessedRegistrationRequest {
    private String eventId_upmId;   // composite key: "73067_uuid-1234"

    @DynamoDbPartitionKey            // hash index
    public String getEventId_upmId() { return eventId_upmId; }
    // No @DynamoDbSortKey → single-key design
}
```

**Interview answer:**
> "Our DynamoDB table uses a single hash key — a composite string `eventId_upmId`. This gives O(1) lookups by exact key. We deliberately chose NOT to split it into partition key + sort key because our only access patterns are put/get/delete by exact key. A partition+sort design would enable 'query all users for event X' but we never need that — batch reprocessing does a full scan which is cheaper than maintaining a sort key index for a rare operation."

---

### 2. Elasticsearch — INVERTED INDEX + Multi-Field Mappings

**Indices:** `pg_eventcard`, `pg_registration_flat`, `event_extra_field`, `event_extra_field_value`

The `pg_eventcard` index uses **multi-field mappings** — the same field indexed two ways:

```
┌──────────────────────────────────────────────────────────────────────┐
│  Elasticsearch Multi-Field Mapping (from mappings.json)              │
│                                                                      │
│  "category_name": {                                                  │
│    "type": "text",           ← INVERTED INDEX (full-text search)    │
│    "fields": {                  Tokenized: "Nike Running" → ["nike", │
│      "keyword": {               "running"]. Supports fuzzy/partial.  │
│        "type": "keyword",    ← HASH-LIKE INDEX (exact match)        │
│        "ignore_above": 256      Not tokenized. "Nike Running" stored│
│      }                          as-is. For aggregations & filtering. │
│    }                                                                 │
│  }                                                                   │
│                                                                      │
│  Query full-text:  match("category_name", "running")  → uses text   │
│  Query exact:      term("category_name.keyword", "Nike Running")     │
│                    → uses keyword                                    │
│                                                                      │
│  Other field types in pg_eventcard:                                  │
│  ┌───────────────────┬────────────┬──────────────────────────────┐  │
│  │  Field            │  Type      │  Index Type / Purpose        │  │
│  ├───────────────────┼────────────┼──────────────────────────────┤  │
│  │  event_id         │  long      │  BKD tree (numeric range)    │  │
│  │  category_id      │  long      │  BKD tree (numeric filter)   │  │
│  │  date_utc_event_  │  date      │  BKD tree (range: gt "now")  │  │
│  │  end              │            │                              │  │
│  │  event_language   │  text +    │  Inverted (search) +         │  │
│  │                   │  keyword   │  keyword (filter/agg)        │  │
│  │  location_geo_    │  geo_point │  BKD tree (geo-distance)     │  │
│  │  point            │            │                              │  │
│  │  is_featured      │  boolean   │  Term index (true/false)     │  │
│  └───────────────────┴────────────┴──────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
```

**How these indexes are used in actual queries:**

```java
// SearchQueryHelper.java — real query patterns

// HASH-LIKE lookup (term query on keyword field)
QueryBuilders.termQuery("event_id", Long.valueOf(id));

// RANGE query on date (BKD tree index)
QueryBuilders.rangeQuery("date_utc_event_end").gt("now").lt(dateBefore).timeZone("UTC");

// EXACT MATCH on keyword subfield (hash-like)
QueryBuilders.termQuery("event_language.keyword", lang);
QueryBuilders.termQuery("event_validation_type_id", PHYSICAL_EVENT_MAGIC_NUMBER);

// BOOL QUERY (combines multiple index lookups)
BoolQueryBuilder rootQuery = QueryBuilders.boolQuery();
rootQuery.must(dateRangeQuery);       // uses BKD tree
rootQuery.must(languageTermQuery);    // uses keyword index
rootQuery.should(geoDistanceQuery);   // uses geo_point index
```

**Covering index equivalent — `_source` filtering:**

```java
// ElasticSearchRepository.java
// Only fetch needed fields → similar to a covering index (no full doc read)
if (ArrayUtils.isNotEmpty(includeFields)) {
    sourceBuilder.fetchSource(includeFields, null);
}
```

**Interview answer:**
> "Our Elasticsearch index `pg_eventcard` uses multi-field mappings — `category_name` is indexed as both `text` (inverted index for full-text search) and `keyword` (exact match for filtering and aggregations). Date fields use BKD trees for range queries like 'events ending after now'. The `geo_point` field enables distance-based filtering. We also do `_source` filtering to only fetch needed fields — similar to a covering index in SQL — which reduces network I/O. The BoolQuery combines these index types: a date range scan AND a language keyword filter AND a geo-distance filter, all executed in parallel across shards."

---

### 3. Redis — HASH INDEX (Key-Based O(1) Lookups)

Redis keys act as hash indexes — every lookup is O(1) by exact key.

```
┌──────────────────────────────────────────────────────────────────────┐
│  Redis Key Patterns in cxp-event-registration                        │
│                                                                      │
│  KEY PATTERN                        VALUE            TTL             │
│  ────────────────────────────────   ──────────────   ───────         │
│  {idempotencyKey}_failure_counter   Integer (count)  1 minute        │
│  {idempotencyKey}_success_response  JSON (response)  60 minutes      │
│  {upmId}_pairwise_key              String (pairId)  30 days         │
│  {eventId}_seats_key               JSON (seats)     varies          │
│  {eventId}_event_key               JSON (event)     varies          │
│                                                                      │
│  All lookups are O(1) — Redis is essentially a giant hash table.    │
│  No secondary indexes. No range queries. No full-text search.       │
│  Trade: blazing speed for single-key access only.                   │
│                                                                      │
│  WHY SUFFIX CONVENTION?                                              │
│  Same entity needs multiple cached values:                           │
│  - "req-abc_failure_counter"   → how many retries failed            │
│  - "req-abc_success_response"  → cached successful response         │
│  This is like a COMPOSITE KEY without a composite index.            │
│  Deletion requires knowing all suffixes (evictCacheBasedOn...):     │
│    redisTemplate.delete(key + FAILURE_COUNTER_SUFFIX);              │
│    redisTemplate.delete(key + SUCCESS_RESPONSE_SUFFIX);             │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// RegistrationCacheService.java
// TTL = built-in "partial index" — only recent data is indexed
redisTemplate.opsForValue().set(
    idempotencyKey + FAILURE_COUNTER_SUFFIX,
    value,
    Duration.ofMinutes(1)    // auto-expires: like a partial index on "last 1 minute"
);

// Pairwise cache — 30-day TTL
redisTemplate.opsForValue().set(
    upmId + PAIRWISE_KEY_SUFFIX,
    pairWiseIdDetails,
    Duration.ofDays(30)      // long-lived cache
);
```

**Interview answer:**
> "Redis uses an in-memory hash table — every key lookup is O(1). Our key naming convention uses suffixes (`_failure_counter`, `_success_response`, `_pairwise_key`) to store multiple values per entity, similar to columns in a composite index. TTL acts as a natural 'partial index' — only relevant data stays in memory. We don't need secondary indexes because we never query Redis by anything other than the exact key."

---

### 4. Athena/S3 — Partition Pruning (B-Tree Equivalent for Data Lakes)

Athena queries over S3 data. Without partitioning, every query scans the ENTIRE dataset.

```
┌──────────────────────────────────────────────────────────────────────┐
│  Athena Indexing Strategy                                            │
│                                                                      │
│  S3 data is NOT indexed like a database.                             │
│  Instead, Athena relies on:                                          │
│                                                                      │
│  1. PARTITION PRUNING (Hive-style S3 layout)                        │
│     s3://partnerhub-data/year=2026/month=04/day=13/data.json        │
│     → Query with WHERE year=2026 AND month=04                        │
│     → Athena skips all other year/month folders                      │
│     → Like a B-tree range scan but at the file system level         │
│                                                                      │
│  2. COLUMNAR FORMAT (Parquet/ORC)                                    │
│     Each column stored separately → query only reads needed columns  │
│     → Like a covering index (no full-row read)                       │
│                                                                      │
│  3. PREDICATE PUSHDOWN                                               │
│     WHERE event.id = 73067 → filter applied during scan, not after  │
│     → Reduces data read from S3                                      │
│                                                                      │
│  OUR QUERIES filter on:                                              │
│  - event.id (nested field) → predicate pushdown                     │
│  - action ('confirmed', 'cancel') → predicate pushdown              │
│  - attendee.upm_id (for specific user lookup) → predicate pushdown  │
│  - ORDER BY event_date_ms → full sort of filtered results           │
└──────────────────────────────────────────────────────────────────────┘
```

**Actual query from `server.py` — no partition pruning, relies on predicate pushdown:**

```sql
SELECT COUNT(*) as total,
       MIN(event.name) as event_name, MIN(event.marketplace) as marketplace
FROM "partnerhub-data-crawler-info".partner_hub_notification_response_data_prod
WHERE event.id = 73067 AND action = 'confirmed'
-- Athena scans all files, applies predicate during scan
-- Cost: $5/TB scanned → expensive without partitioning
```

**Optimization opportunity (interview discussion):**
> "If I were optimizing our Athena queries, I'd add Hive-style partitioning by `year/month` on the S3 data. Currently, every query scans the entire table. With partitioning, a query for this month's data would scan ~1/12th of the files. Combined with Parquet columnar format, we'd reduce scan costs by 90%+. This is the data lake equivalent of adding a B-tree index."

---

### 5. Splunk — Index-Based Search Optimization

Splunk has its own indexing model that maps to database index concepts:

```
┌──────────────────────────────────────────────────────────────────────┐
│  Splunk Indexing Model → Database Index Equivalents                   │
│                                                                      │
│  SPLUNK CONCEPT           │  DB EQUIVALENT        │  PURPOSE         │
│  ─────────────────────────┼───────────────────────┼────────────────  │
│  index=dockerlogs-gold    │  TABLE / PARTITION     │  Narrow search   │
│                           │                       │  to specific     │
│                           │                       │  data set        │
│  ─────────────────────────┼───────────────────────┼────────────────  │
│  sourcetype=crs-email*    │  PARTIAL INDEX         │  Filter to       │
│  source="crs-email..."    │  (WHERE clause)       │  specific log    │
│                           │                       │  format/source   │
│  ─────────────────────────┼───────────────────────┼────────────────  │
│  earliest=-30d latest=now │  B-TREE RANGE SCAN    │  Time-bounded    │
│                           │  (WHERE ts BETWEEN)   │  search          │
│  ─────────────────────────┼───────────────────────┼────────────────  │
│  | spath "line.upmid"     │  COLUMN PROJECTION    │  Extract only    │
│                           │  (SELECT col)         │  needed fields   │
│  ─────────────────────────┼───────────────────────┼────────────────  │
│  | dedup upmId, eventType │  DISTINCT / UNIQUE    │  Remove          │
│                           │  INDEX                │  duplicates      │
│  ─────────────────────────┼───────────────────────┼────────────────  │
│  | stats count by field   │  GROUP BY with        │  Aggregate       │
│                           │  INDEX SCAN           │  results         │
└──────────────────────────────────────────────────────────────────────┘
```

**From `queries.py` — layered index narrowing:**

```python
# Layer 1: index= (like selecting the right TABLE)
# Layer 2: sourcetype= (like a PARTIAL INDEX within that table)
# Layer 3: keyword match (like a WHERE clause)
# Layer 4: earliest/latest (like a B-TREE RANGE on timestamp)

"dropped_emails": f'''
    search index=dockerlogs* sourcetype=log4j "UserEmailNotAvailable" {time_clause}
    | rex field=_raw "upmId=(?<upmId>[^,\\s]+)"
    | dedup upmId, eventType
    | table _time, upmId, marketplace, eventType, emailType ...'''
```

**Interview answer:**
> "In Splunk, `index=` is equivalent to selecting the right table, `sourcetype=` is like a partial index that narrows to a specific log format, and `earliest/latest` is a time-based range scan. Our queries always specify all three — this is like having a composite index on (index, sourcetype, timestamp). Without the `index=` narrowing, Splunk would search across all data — the equivalent of a full table scan."

---

## The Write-Speed Tradeoff

Every index slows writes. Here's how this manifests across our CXP platform:

```
┌──────────────────────────────────────────────────────────────────────┐
│  INDEX WRITE COST IN CXP                                             │
│                                                                      │
│  Technology     │ Write Pattern            │ Index Cost              │
│  ───────────────┼──────────────────────────┼─────────────────────── │
│  DynamoDB       │ 1000s of concurrent      │ Single hash key =      │
│  (registration  │ registrations during     │ minimal write cost.    │
│  queue)         │ sneaker launches         │ NO secondary indexes   │
│                 │                          │ → fast writes.         │
│                 │                          │ Adding a GSI would     │
│                 │                          │ double write cost.     │
│  ───────────────┼──────────────────────────┼─────────────────────── │
│  Redis          │ SET per registration     │ Zero index overhead —  │
│  (idempotency)  │ request (~1000/sec)      │ hash table only.      │
│                 │                          │ TTL cleanup is O(1).   │
│  ───────────────┼──────────────────────────┼─────────────────────── │
│  Elasticsearch  │ Event data indexed when  │ HEAVY index cost —     │
│  (event search) │ events are created/      │ text tokenization,     │
│                 │ updated (low frequency)  │ inverted index update, │
│                 │                          │ multi-field mapping.   │
│                 │                          │ Acceptable because     │
│                 │                          │ writes are rare vs     │
│                 │                          │ reads (search-heavy).  │
│  ───────────────┼──────────────────────────┼─────────────────────── │
│  S3 + Athena    │ Webhook JSON appended    │ Zero index cost on     │
│  (Partner Hub)  │ to S3 (append-only)      │ write. Index cost is   │
│                 │                          │ paid at QUERY time     │
│                 │                          │ (scan cost).           │
│  ───────────────┼──────────────────────────┼─────────────────────── │
│  Splunk         │ Continuous log ingestion │ Splunk indexes during  │
│  (logs)         │ from all services        │ ingestion (tsidx).     │
│                 │                          │ Write-heavy by design. │
│                 │                          │ Optimized for append.  │
└──────────────────────────────────────────────────────────────────────┘

KEY INSIGHT:
  - WRITE-HEAVY tables (DynamoDB, Redis) → minimal indexes
  - READ-HEAVY tables (Elasticsearch) → rich multi-field indexes
  - APPEND-ONLY stores (S3) → no write-time index cost; pay at query time
```

**Interview answer:**
> "We deliberately keep DynamoDB at a single hash key with no secondary indexes because it's our write-heavy path — thousands of concurrent registrations during sneaker launches. Adding a GSI would double write throughput costs. In contrast, Elasticsearch has rich multi-field mappings (text + keyword + geo_point + date) because it's read-heavy — events are indexed infrequently but searched constantly. S3 has zero write-time index cost because it's append-only; the 'indexing cost' is paid at query time by Athena scanning files."

---

## Common Interview Follow-ups

### Q: "When would you add a GSI to DynamoDB?"

> "If we needed a new access pattern — for example, 'find all unprocessed registrations for a specific event' — I'd add a GSI with `eventId` as the partition key. Currently we don't need this because batch reprocessing does a full scan (rare operation, < once/day). The tradeoff: a GSI doubles write cost (every put writes to both the base table and the GSI). For our write-heavy sneaker launch traffic, that's a significant cost increase for a rarely-used query pattern."

### Q: "How do you handle the leftmost prefix rule in composite indexes?"

> "In our DynamoDB table, we use a composite STRING key (`eventId_upmId`) instead of partition+sort. This avoids the leftmost prefix problem entirely — there's only one key to match. In Elasticsearch, the equivalent is using `BoolQuery` with independent `must/should` clauses — each field has its own index, so there's no column ordering dependency. The leftmost prefix rule specifically applies to B-tree composite indexes in SQL databases, which we don't use."

### Q: "Your Athena queries seem expensive. How would you optimize?"

> "Three optimizations:
> 1. **Hive-style partitioning** — organize S3 data as `year=2026/month=04/` so Athena only scans relevant months. For a 'last 7 days' query, this reduces scan volume by ~97%.
> 2. **Columnar format (Parquet)** — convert JSON to Parquet. Athena reads only needed columns instead of full documents. Reduces scan costs by 60-80%.
> 3. **Predicate pushdown** — already in use. Athena applies `WHERE event.id = X` during the scan, not after. But without partitioning, it still reads every file to apply the predicate."

### Q: "What's the difference between indexing in Elasticsearch vs a SQL database?"

> "In SQL, you create indexes AFTER defining the schema — `CREATE INDEX idx ON table(col)`. In Elasticsearch, the index IS the schema — the mapping defines how each field is indexed at ingestion time. SQL gives you B-tree by default; Elasticsearch gives you inverted index by default. The biggest difference: Elasticsearch indexes EVERY field by default (which is why it's fast for search but expensive on writes), while SQL indexes nothing by default (which is why you must explicitly add indexes for query patterns)."

---
---

# Topic 6: Database Replication

> Single-leader for simplicity, multi-leader for geo-distribution, leaderless for maximum availability — pick based on consistency needs.

> **Interview Tip:** Connect to requirements — "For a global app, I'd use multi-leader replication across regions to reduce write latency, accepting conflict resolution complexity."

---

## What Is Replication?

Keeping **copies of the same data on multiple nodes** so that if one node dies, another can serve the data. Every distributed database uses replication — the question is **how**.

```
WHY REPLICATE?
─────────────
1. AVAILABILITY   — if Node A dies, Node B serves traffic (no downtime)
2. READ SCALING   — distribute reads across replicas (more throughput)
3. LOW LATENCY    — serve data from the geographically nearest node
4. DURABILITY     — data survives hardware failure (multiple copies)
```

---

## The 3 Replication Topologies

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    REPLICATION TOPOLOGIES                                     │
│                                                                             │
│  ┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐ │
│  │    SINGLE-LEADER    │  │    MULTI-LEADER      │  │     LEADERLESS      │ │
│  │    (Master-Slave)   │  │    (Master-Master)   │  │    (Peer-to-Peer)   │ │
│  │                     │  │                      │  │                     │ │
│  │    ┌────────┐       │  │  ┌────────┐sync┌────────┐  ┌───┐ ┌───┐ ┌───┐│ │
│  │    │ LEADER │       │  │  │Leader 1│◄──▶│Leader 2│  │ N1│─│ N2│─│ N3││ │
│  │    │ Writes │       │  │  └───┬────┘    └───┬────┘  └─┬─┘ └─┬─┘ └─┬─┘│ │
│  │    └───┬────┘       │  │      │              │        └─────┴─────┘   │ │
│  │   ┌────┼────┐       │  │   ┌──┴──┐      ┌──┴──┐                     │ │
│  │   ▼    ▼    ▼       │  │   ▼     ▼      ▼     ▼   W + R > N for    │ │
│  │  ┌──┐ ┌──┐ ┌──┐    │  │ ┌──┐  ┌──┐  ┌──┐  ┌──┐  consistency      │ │
│  │  │F1│ │F2│ │F3│    │  │ │F1│  │F2│  │F3│  │F4│                     │ │
│  │  └──┘ └──┘ └──┘    │  │ └──┘  └──┘  └──┘  └──┘                     │ │
│  │                     │  │                      │                       │ │
│  │ [+] Simple,         │  │ [+] Multi-region     │  [+] High availability│ │
│  │     consistent      │  │     writes           │  [+] No single point  │ │
│  │ [+] No write        │  │ [+] Better           │      of failure       │ │
│  │     conflicts       │  │     availability     │  [-] Eventual         │ │
│  │ [-] Single point    │  │ [-] Conflict          │      consistency     │ │
│  │     of failure      │  │     resolution        │                      │ │
│  │                     │  │     needed            │  Cassandra,          │ │
│  │ MySQL, PostgreSQL   │  │ CouchDB, Galera      │  DynamoDB            │ │
│  └─────────────────────┘  └─────────────────────┘  └─────────────────────┘ │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │          SYNCHRONOUS vs ASYNCHRONOUS REPLICATION                    │   │
│  │                                                                     │   │
│  │  Synchronous:                 Asynchronous:                        │   │
│  │  Wait for replica ACK         Commit immediately, replicate later  │   │
│  │  before commit                                                     │   │
│  │                                                                     │   │
│  │  Strong consistency,          Lower latency, possible data loss    │   │
│  │  higher latency               if leader dies before replication    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Deep Dive: Each Topology

### Single-Leader (Master-Slave)

```
  ALL WRITES go to one node. Replicas receive a COPY.

  Client Write ──▶ LEADER ──async──▶ Follower 1
                     │──────async──▶ Follower 2
                     │──────async──▶ Follower 3

  Client Read  ──▶ Any node (leader OR follower)

  Consistency:   Strong (if reading from leader)
                 Eventual (if reading from followers — replication lag)

  Failure modes:
  ┌──────────────────────────────────────────────────────────┐
  │  Leader dies →  Promote a follower to new leader         │
  │                 (manual or automatic failover)            │
  │                 Writes blocked during failover window     │
  │                                                          │
  │  Follower dies → Other followers continue serving reads  │
  │                  No impact on writes                      │
  └──────────────────────────────────────────────────────────┘
```

### Multi-Leader (Master-Master)

```
  WRITES can go to ANY leader. Leaders sync with each other.

  Region A (us-east-1)              Region B (us-west-2)
  ┌──────────────┐                 ┌──────────────┐
  │  Leader A     │◄──async sync──▶│  Leader B     │
  │  (accepts     │                │  (accepts     │
  │   writes)     │                │   writes)     │
  └──────┬───────┘                └──────┬───────┘
         │                                │
    ┌────┴────┐                      ┌────┴────┐
    ▼         ▼                      ▼         ▼
  ┌───┐    ┌───┐                  ┌───┐    ┌───┐
  │ F1│    │ F2│                  │ F3│    │ F4│
  └───┘    └───┘                  └───┘    └───┘

  THE CONFLICT PROBLEM:
  T=0: User A in us-east writes: event.seats = 5
  T=0: User B in us-west writes: event.seats = 4
  T=1: Leaders sync — which value wins?

  Conflict resolution strategies:
  1. Last-writer-wins (LWW) — timestamp decides (DynamoDB Global Tables)
  2. Custom merge — application-level logic
  3. CRDT — conflict-free replicated data types (auto-merge)
```

### Leaderless (Peer-to-Peer)

```
  NO designated leader. ANY node accepts writes.
  Quorum-based: write to W nodes, read from R nodes.

  ┌───┐     ┌───┐     ┌───┐
  │ N1│─────│ N2│─────│ N3│      N = 3 nodes
  └─┬─┘     └─┬─┘     └─┬─┘
    │         │         │
    └─────────┴─────────┘

  Write to W=2 of 3 nodes     Read from R=2 of 3 nodes
  W + R > N  (2+2 > 3)  →  AT LEAST ONE node has the latest write
                            →  Guaranteed to read fresh data

  If W + R ≤ N → eventual consistency (faster but stale reads possible)

  Failure: Any node can die. Remaining nodes continue.
  No failover needed. No "leader election". Maximum availability.
```

---

## Synchronous vs Asynchronous Replication

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│  SYNCHRONOUS                        ASYNCHRONOUS                   │
│  ────────────                       ─────────────                  │
│                                                                     │
│  Client──Write──▶Leader             Client──Write──▶Leader         │
│                    │                                  │             │
│              ┌─────┤                            ┌─────┤             │
│              ▼     ▼                            ▼     │             │
│           Replica  Replica                   Replica  │             │
│              │     │                            │     │             │
│              ▼     ▼                            │     ▼             │
│           ACK!   ACK!                           │   ACK to client  │
│              │     │                            │   (immediately)   │
│              ▼     ▼                            ▼                   │
│           ACK to client                     Replica catches up     │
│           (after ALL replicas confirm)      eventually              │
│                                                                     │
│  Latency:  HIGHER (wait for replicas)   Latency:  LOWER            │
│  Durability: STRONG (data on N nodes)   Durability: WEAKER          │
│  Data loss: NONE                        Data loss: POSSIBLE         │
│             (if leader dies,                (if leader dies before  │
│              replicas have the data)         replica catches up)    │
│                                                                     │
│  Use for: Financial transactions        Use for: Most applications │
│           Compliance requirements                 where speed > safety│
└─────────────────────────────────────────────────────────────────────┘
```

---

## Replication In My CXP Projects — Real Examples

### The Complete Replication Map

```
┌──────────────────────────────────────────────────────────────────────────┐
│              CXP PLATFORM — REPLICATION TOPOLOGY MAP                      │
│                                                                          │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                    us-east-1 (Primary)                             │  │
│  │                                                                   │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐           │  │
│  │  │  DynamoDB     │  │  Redis       │  │  Elastic-    │           │  │
│  │  │  (Leader)     │  │  Primary     │  │  search      │           │  │
│  │  │              │  │  (Leader)    │  │  (Primary    │           │  │
│  │  │  MULTI-LEADER│  │              │  │   Shards)    │           │  │
│  │  │  across      │  │  SINGLE-     │  │              │           │  │
│  │  │  regions     │  │  LEADER      │  │  SINGLE-     │           │  │
│  │  └──────┬───────┘  │  within      │  │  LEADER      │           │  │
│  │         │          │  cluster     │  │  per shard   │           │  │
│  │         │          └──────┬───────┘  └──────────────┘           │  │
│  │         │                 │                                      │  │
│  │         │          ┌──────┴───────┐                              │  │
│  │         │          │ Read Replicas│                              │  │
│  │         │          │ (Followers)  │                              │  │
│  │         │          └──────────────┘                              │  │
│  └─────────┼─────────────────────────────────────────────────────────┘  │
│            │ async replication                                          │
│            │ (~1 second)                                                │
│            ▼                                                            │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                    us-west-2 (Secondary)                           │  │
│  │                                                                   │  │
│  │  ┌──────────────┐                                                │  │
│  │  │  DynamoDB     │  (Redis and Elasticsearch are NOT              │  │
│  │  │  (Leader)     │   replicated cross-region in our setup)       │  │
│  │  │              │                                                │  │
│  │  │  Accepts      │                                                │  │
│  │  │  writes       │                                                │  │
│  │  │  independently│                                                │  │
│  │  └──────────────┘                                                │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │  EDGE LAYER (Global)                                              │  │
│  │                                                                   │  │
│  │  ┌──────────────┐                                                │  │
│  │  │  Akamai CDN  │  Cached copies at 250+ edge locations          │  │
│  │  │  (Replicas   │  Each PoP is like a read-only replica          │  │
│  │  │   everywhere)│  TTL-based staleness (not replication lag)     │  │
│  │  └──────────────┘                                                │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: Redis ElastiCache — SINGLE-LEADER (within cluster)

**Where:** `cxp-infrastructure` → `terraform/awsPassplay/modules/elasticache`
**Config:** 1 Primary + N Read Replicas, `ReadFrom.REPLICA_PREFERRED`
**Replication:** Asynchronous

```
┌──────────────────────────────────────────────────────────────────────┐
│  Redis Replication in cxp-event-registration                         │
│                                                                      │
│                    ┌──────────────┐                                  │
│                    │  PRIMARY     │ ◀── ALL writes go here           │
│                    │  (Leader)    │                                  │
│                    └──────┬───────┘                                  │
│                    async  │  async                                   │
│                   ┌───────┼───────┐                                  │
│                   ▼       ▼       ▼                                  │
│              ┌────────┐┌────────┐┌────────┐                         │
│              │Replica ││Replica ││Replica │ ◀── Reads go here       │
│              │  1     ││  2     ││  3     │     (REPLICA_PREFERRED)  │
│              └────────┘└────────┘└────────┘                         │
│                                                                      │
│  WRITE PATH (idempotency SET):                                      │
│  Client → Primary → ACK (synchronous within primary)                │
│         → Primary replicates to replicas (asynchronous)             │
│                                                                      │
│  READ PATH (idempotency GET):                                       │
│  Client → Replica (preferred) → return cached value                 │
│         → If replica down, fallback to Primary                      │
│                                                                      │
│  REPLICATION LAG IMPACT:                                             │
│  T=0ms: SET "user:event" on Primary                                │
│  T=0ms: Primary ACKs client → registration proceeds                │
│  T=1ms: Replica 1 receives the key (async replication)              │
│                                                                      │
│  Worst case: Request 1 writes to Primary, Request 2 reads from      │
│  a Replica before replication → Replica says "key doesn't exist"    │
│  → Duplicate registration attempt                                   │
│                                                                      │
│  MITIGATION: Idempotency writes AND reads go to Primary for         │
│  critical paths. REPLICA_PREFERRED is for non-critical cached data. │
│  Eventtia also has its own duplicate check (defense in depth).      │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// ReactiveRedisConfig.java
// Single-leader: one primary, multiple replicas
RedisStaticMasterReplicaConfiguration config =
    new RedisStaticMasterReplicaConfiguration(primaryHost, primaryPort);
for (String replica : replicaHosts) {
    config.addNode(replica, replicaPort);
}

// Read from replicas (followers) for throughput
lettuceClientConfiguration.readFrom(ReadFrom.REPLICA_PREFERRED);
```

**Interview answer:**
> "Our Redis cluster uses single-leader replication — one primary for writes, multiple replicas for reads. We configure `REPLICA_PREFERRED` so reads distribute across replicas for throughput. Replication is asynchronous, which means there's a sub-millisecond window where a replica might not have the latest write. For idempotency checks, this could theoretically cause a duplicate — but Eventtia has its own duplicate check as defense in depth. We chose async over sync replication because the write latency for registration idempotency must be <1ms, and waiting for replica ACKs would double that."

---

### Example 2: DynamoDB Global Tables — MULTI-LEADER (cross-region)

**Where:** `cxp-infrastructure` → `terraform/aws/modules/dynamodb`
**Regions:** us-east-1 + us-west-2
**Replication:** Asynchronous, last-writer-wins conflict resolution

```
┌──────────────────────────────────────────────────────────────────────┐
│  DynamoDB Global Table — Multi-Leader Replication                     │
│                                                                      │
│      us-east-1                              us-west-2                │
│  ┌─────────────────┐                  ┌─────────────────┐           │
│  │   LEADER A       │   async sync    │   LEADER B       │           │
│  │   (accepts       │◄──── ~1 sec ───▶│   (accepts       │           │
│  │    writes)       │  bi-directional │    writes)       │           │
│  │                  │                  │                  │           │
│  │  Internal:       │                  │  Internal:       │           │
│  │  3 AZ replicas   │                  │  3 AZ replicas   │           │
│  │  (synchronous)   │                  │  (synchronous)   │           │
│  └─────────────────┘                  └─────────────────┘           │
│         ▲                                      ▲                     │
│         │                                      │                     │
│   Users routed                           Users routed               │
│   via Route53                            via Route53                │
│   latency-based                          latency-based              │
│   routing                                routing                    │
│                                                                      │
│  CONFLICT SCENARIO:                                                  │
│  ─────────────────                                                  │
│  T=0: us-east writes: PK="73067_uuid1", payload=A                  │
│  T=0: us-west writes: PK="73067_uuid1", payload=B                  │
│  T=1: Sync happens → CONFLICT on same key!                         │
│                                                                      │
│  Resolution: LAST-WRITER-WINS (LWW)                                │
│  DynamoDB uses item-level timestamps.                               │
│  Whichever write has the later timestamp wins.                      │
│  The other write is silently discarded.                             │
│                                                                      │
│  WHY THIS IS SAFE FOR US:                                           │
│  Our partition key is "eventId_upmId" — unique per user+event.      │
│  Two different users writing different keys = NO conflict.          │
│  Same user in both regions simultaneously = extremely rare,         │
│  and the registration is idempotent anyway.                         │
└──────────────────────────────────────────────────────────────────────┘
```

**Why multi-leader, not single-leader?**

| Single-Leader (1 region writes) | Multi-Leader (both regions write) |
|---|---|
| User in us-west writes to us-east (cross-region latency ~60ms) | User in us-west writes to local us-west leader (~5ms) |
| If us-east is down, NO writes accepted | If us-east is down, us-west continues accepting writes |
| Simple — no conflicts possible | Conflict resolution needed (LWW) |
| Good for: banking, inventory | Good for: registration queue, session store |

**Interview answer:**
> "Our DynamoDB Global Table uses multi-leader replication — both us-east-1 and us-west-2 accept writes independently. This reduces write latency from ~60ms (cross-region) to ~5ms (local). Conflict resolution is last-writer-wins based on timestamps. This is safe because our partition key includes both `eventId` and `upmId`, so two different users never conflict. Same-user conflicts are handled by idempotent reprocessing logic. If we needed strict consistency (like seat inventory), we'd use single-leader — but for an unprocessed registration queue, availability beats consistency."

---

### Example 3: Elasticsearch — SINGLE-LEADER Per Shard

**Where:** `expviewsnikeapp` → `pg_eventcard` index
**Model:** Each shard has one primary + N replicas. Writes go to primary shard only.

```
┌──────────────────────────────────────────────────────────────────────┐
│  Elasticsearch Shard Replication                                     │
│                                                                      │
│  Index: pg_eventcard (5 primary shards, 1 replica each)             │
│                                                                      │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │
│  │ Shard 0     │  │ Shard 1     │  │ Shard 2     │  ...            │
│  │ (Primary)   │  │ (Primary)   │  │ (Primary)   │                 │
│  │   │         │  │   │         │  │   │         │                 │
│  │   ▼         │  │   ▼         │  │   ▼         │                 │
│  │ Shard 0     │  │ Shard 1     │  │ Shard 2     │                 │
│  │ (Replica)   │  │ (Replica)   │  │ (Replica)   │                 │
│  └─────────────┘  └─────────────┘  └─────────────┘                 │
│                                                                      │
│  Document routing: hash(event_id) % num_shards → target shard       │
│  Write: goes to PRIMARY shard → replicates to REPLICA (sync by     │
│         default — wait_for_active_shards=1)                          │
│  Read: can go to primary OR replica (distributed across nodes)      │
│                                                                      │
│  This is SINGLE-LEADER PER SHARD — each shard has one primary       │
│  that accepts writes, but the INDEX as a whole distributes writes   │
│  across multiple primary shards (horizontal write scaling).          │
│                                                                      │
│  REPLICATION LAG (near-real-time):                                  │
│  Write → Primary shard → Replica shard (sync) → index refresh (~1s) │
│  Search sees new data after refresh interval (default 1 second).    │
│  This 1-second lag is why ES is "near-real-time", not real-time.   │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "Elasticsearch uses single-leader replication per shard. Each shard has one primary that accepts writes, with synchronous replication to replicas. But the index distributes documents across multiple shards using a hash of the document ID, so writes scale horizontally across shards. The tradeoff is the 1-second refresh interval — after a write, searches won't see the new document for up to 1 second. For event search, this is fine — a 1-second delay between an event being created and appearing in search results is invisible to users."

---

### Example 4: Akamai CDN — SINGLE-LEADER with Massive Read Replicas

**Where:** CDN layer in front of cxp-events
**Model:** Origin (cxp-events in us-east-1) is the leader. 250+ CDN PoPs are read replicas.

```
┌──────────────────────────────────────────────────────────────────────┐
│  Akamai CDN — Replication at the Edge                                │
│                                                                      │
│              ┌──────────────┐                                       │
│              │   ORIGIN     │  (cxp-events backend)                  │
│              │   (Leader)   │  Single source of truth                │
│              └──────┬───────┘                                       │
│                     │                                                │
│        ┌────────────┼────────────┐                                  │
│        ▼            ▼            ▼                                   │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐                            │
│  │  Tokyo   │ │ London   │ │ Sao Paulo│  ... 250+ PoPs             │
│  │  PoP     │ │  PoP     │ │  PoP     │                            │
│  │(Replica) │ │(Replica) │ │(Replica) │                            │
│  └──────────┘ └──────────┘ └──────────┘                            │
│                                                                      │
│  Replication: PULL-based (not push)                                 │
│  - PoP doesn't have data → pulls from origin (cache miss)          │
│  - PoP has data → serves cached copy (cache hit)                   │
│  - TTL expires → next request pulls fresh copy                      │
│                                                                      │
│  Invalidation: PUSH-based via NSP3 Kafka Purge Sink                │
│  - Event updated → Kafka message → Akamai purge API                │
│  - PoPs evict stale content → next request pulls fresh             │
│                                                                      │
│  This is single-leader (origin) with 250+ read replicas (PoPs).    │
│  "Replication lag" = TTL duration (minutes, not milliseconds).      │
│  Maximum read scaling: millions of concurrent users served.         │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 5: S3 — Internal Replication (Invisible but Critical)

**Where:** Partner Hub data, Rise GTS payloads, bodega translations
**Model:** Single-region S3 replicates across 3+ AZs automatically

```
┌──────────────────────────────────────────────────────────────────────┐
│  S3 Internal Replication                                             │
│                                                                      │
│  When you PUT an object to S3:                                      │
│                                                                      │
│  Client ──PUT──▶ S3 endpoint                                       │
│                     │                                                │
│                     ├──▶ AZ-1 (synchronous)                         │
│                     ├──▶ AZ-2 (synchronous)                         │
│                     └──▶ AZ-3 (synchronous)                         │
│                                                                      │
│                  ACK returned ONLY after 3+ AZ writes succeed        │
│                                                                      │
│  This is SYNCHRONOUS replication across AZs:                        │
│  - 99.999999999% (11 nines) durability                              │
│  - Strong read-after-write consistency                               │
│  - You never manage replicas — AWS handles it                        │
│                                                                      │
│  WHY THIS MATTERS FOR OUR SOURCE OF TRUTH:                          │
│  Partner Hub writes a webhook JSON to S3 → immediately queryable    │
│  via Athena. No replication lag. No eventual consistency.            │
│  This is why we trust S3+Athena as the source of truth (CP).       │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 6: The Email Pipeline — Replication Across the Entire Chain

The email delivery pipeline spans multiple replication topologies:

```
┌──────────────────────────────────────────────────────────────────────┐
│  END-TO-END REPLICATION IN THE EMAIL PIPELINE                        │
│                                                                      │
│  STAGE           │ REPLICATION      │ SYNC/ASYNC  │ TOPOLOGY        │
│  ────────────────┼──────────────────┼─────────────┼──────────────── │
│  Eventtia DB     │ Internal (their  │ Sync (ACID) │ Single-leader   │
│  (registration)  │ managed DB)      │             │ (likely)        │
│  ────────────────┼──────────────────┼─────────────┼──────────────── │
│  Partner Hub S3  │ 3 AZ within      │ Synchronous │ Internal S3     │
│  (webhook)       │ region           │             │ replication     │
│  ────────────────┼──────────────────┼─────────────┼──────────────── │
│  Kafka/NSPv2     │ Topic across     │ Sync within │ Single-leader   │
│  (streaming)     │ broker replicas  │ ISR set     │ per partition   │
│  ────────────────┼──────────────────┼─────────────┼──────────────── │
│  Rise GTS        │ Stateless ECS    │ N/A         │ No data to      │
│  (transform)     │ tasks            │             │ replicate       │
│  ────────────────┼──────────────────┼─────────────┼──────────────── │
│  NCP/CRS         │ Stateless        │ N/A         │ No data to      │
│  (render/send)   │ services         │             │ replicate       │
│  ────────────────┼──────────────────┼─────────────┼──────────────── │
│  Splunk          │ Indexer cluster   │ Sync within │ Single-leader   │
│  (logs)          │ (search factor)  │ cluster     │ per bucket      │
│  ────────────────┼──────────────────┼─────────────┼──────────────── │
│  DynamoDB        │ 3 AZ + cross-    │ Sync (AZ),  │ Multi-leader   │
│  (retry queue)   │ region Global    │ Async       │ (cross-region)  │
│                  │ Table            │ (region)    │                 │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Replication Across CXP Platform

| Component | Topology | Sync/Async | Conflict Resolution | Why This Choice |
|-----------|----------|-----------|-------------------|----------------|
| **Redis ElastiCache** | Single-Leader | Async | N/A (single writer) | Sub-ms writes; replicas for read throughput |
| **DynamoDB (intra-region)** | Single-Leader | Sync (3 AZs) | N/A | Strong consistency within region |
| **DynamoDB (Global Table)** | Multi-Leader | Async (~1s) | Last-writer-wins (LWW) | Both regions accept writes; low latency |
| **Elasticsearch** | Single-Leader per shard | Sync to replicas | N/A (single writer per shard) | Write scaling via shard distribution |
| **Akamai CDN** | Single-Leader + pull replicas | TTL-based | N/A (origin is authority) | Millions of reads from 250+ edge PoPs |
| **S3** | Internal sync 3+ AZs | Synchronous | N/A (single write path) | 11 nines durability; strong consistency |
| **Kafka/NSPv2** | Single-Leader per partition | Sync within ISR | N/A (single writer per partition) | Ordered delivery within partition |

---

## Common Interview Follow-ups

### Q: "When would you switch from single-leader to multi-leader?"

> "When write latency from remote regions becomes unacceptable. In our platform, DynamoDB Global Tables use multi-leader because a user in us-west-2 writing to a single leader in us-east-1 would add ~60ms of cross-region latency to every registration write. With multi-leader, local writes are ~5ms. The tradeoff — conflict resolution complexity — is manageable because our key design (`eventId_upmId`) makes cross-region conflicts on the same key nearly impossible."

### Q: "How do you handle replication lag in Redis?"

> "We use `REPLICA_PREFERRED` for non-critical reads (cached event data, pairwise IDs) where a sub-millisecond stale value is acceptable. For idempotency checks — where reading stale data could cause a duplicate registration — we can configure the critical path to read from the primary. Additionally, Eventtia enforces its own duplicate check, so even if Redis lag causes a duplicate attempt, Eventtia returns a 422 'already registered' error."

### Q: "What happens if your DynamoDB leader in us-east-1 goes down?"

> "With Global Tables (multi-leader), there's no single leader to go down. us-west-2 continues accepting writes independently. When us-east-1 recovers, DynamoDB automatically syncs the missed writes. This is the key advantage of multi-leader over single-leader for cross-region setups — zero failover window. Within a single region, DynamoDB replicates synchronously across 3 AZs, so losing one AZ has no impact."

### Q: "Leaderless replication (Cassandra/DynamoDB) — when would you use quorum reads?"

> "When you need read-your-writes consistency without going to a leader. In a quorum system with N=3, W=2, R=2 — writing to 2 nodes and reading from 2 nodes guarantees at least one node has the latest write (since 2+2 > 3). We'd use this for a shopping cart or session store where the same user must always see their own latest action. DynamoDB's 'strongly consistent reads' option is essentially a quorum read — it goes to the leader partition rather than any replica."

### Q: "How is CDN replication different from database replication?"

> "CDN replication is pull-based and TTL-driven, not push-based and continuous. A database leader pushes changes to replicas immediately. A CDN PoP only fetches data when a user requests it AND the cached copy has expired. This means CDN 'replication lag' is bounded by TTL (minutes), not network latency (milliseconds). The advantage is zero push overhead — the origin doesn't need to know about 250+ PoPs. The disadvantage is staleness: users in different cities see different versions of the same page until TTL expires or a Kafka-driven purge invalidates the cache."

---
---

# Topic 7: Read Replicas

> Scale read-heavy workloads by routing queries to replica copies while all writes go to the primary database.

> **Interview Tip:** Quantify the benefit — "With a 10:1 read-to-write ratio, adding 3 read replicas would reduce primary load by 75% and improve read latency."

---

## What Are Read Replicas?

Read replicas are **copies of the primary database that only serve read (SELECT) operations**. All writes (INSERT/UPDATE/DELETE) go to the primary. The primary asynchronously replicates data to the replicas.

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         READ REPLICAS                                     │
│                                                                          │
│  Copies of primary database to scale READ operations.                    │
│  Writes go to primary, reads distributed across replicas.               │
│                                                                          │
│                    ┌───────────────┐                                     │
│                    │  Application  │                                     │
│                    └───────┬───────┘                                     │
│                   WRITES   │   READS                                     │
│                     │      │      │                                      │
│                     ▼      │      ▼                                      │
│              ┌──────────┐  │  ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│              │ PRIMARY  │  │  │Replica 1 │ │Replica 2 │ │Replica 3 │   │
│              │ (Leader/ │──┼─▶│Read Only │ │Read Only │ │Read Only │   │
│              │  Master) │async│          │ │          │ │          │   │
│              └──────────┘  │  └──────────┘ └──────────┘ └──────────┘   │
│                            │                                            │
│  BENEFITS:                           CONSIDERATIONS:                    │
│  [+] Scale read throughput           [-] Replication lag (eventual      │
│      (add more replicas)                 consistency)                   │
│  [+] Reduce primary load            [-] Read-after-write: may need     │
│  [+] Geographic distribution             to read from primary          │
│      (lower latency)                                                    │
│                                      AWS RDS, Cloud SQL, Aurora all     │
│                                      support this pattern.              │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## The Math: Why Read Replicas Work

```
BEFORE: 1 Primary handles ALL traffic
─────────────────────────────────────
  Total requests:  10,000/sec
  Writes:           1,000/sec  (10%)
  Reads:            9,000/sec  (90%)
  Primary load:    10,000/sec  ← BOTTLENECK

AFTER: 1 Primary + 3 Read Replicas
────────────────────────────────────
  Writes → Primary only:     1,000/sec
  Reads  → 3 replicas:       3,000/sec each (distributed)
  Primary load:              1,000/sec  ← 90% reduction!

  ┌────────────────────────────────────────────────────────┐
  │  READ-WRITE RATIO    │  REPLICAS  │  PRIMARY LOAD      │
  │  (R:W)               │  NEEDED   │  REDUCTION          │
  ├──────────────────────┼───────────┼────────────────────┤
  │  10:1 (typical web)  │  3        │  ~75% of reads      │
  │                      │           │  offloaded           │
  │  100:1 (read-heavy)  │  5        │  ~95% of reads      │
  │                      │           │  offloaded           │
  │  1:1 (balanced)      │  3        │  ~37% total          │
  │                      │           │  reduction           │
  │  1:10 (write-heavy)  │  replicas │  minimal benefit     │
  │                      │  don't    │  (writes are the     │
  │                      │  help     │   bottleneck)        │
  └──────────────────────┴───────────┴────────────────────┘

  KEY INSIGHT: Read replicas only help read-heavy workloads.
  If your bottleneck is writes, you need sharding or partitioning instead.
```

---

## The Replication Lag Problem

The biggest tradeoff with read replicas: **data on replicas is slightly behind the primary**.

```
┌──────────────────────────────────────────────────────────────────────┐
│  THE REPLICATION LAG TIMELINE                                        │
│                                                                      │
│  T=0ms:  Client writes "seats = 4" to PRIMARY                      │
│  T=0ms:  PRIMARY ACKs → client sees success                        │
│  T=1ms:  PRIMARY starts async replication to replicas               │
│  T=1ms:  Client reads from REPLICA → sees "seats = 5" (STALE!)     │
│  T=3ms:  Replica receives update → now shows "seats = 4"           │
│                                                                      │
│  Between T=0 and T=3ms, the client reads stale data.               │
│  This is the "read-after-write consistency" problem.                │
│                                                                      │
│  SOLUTIONS:                                                          │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  1. Read-your-writes: After a WRITE, read from PRIMARY        │ │
│  │     (not replica) for that user's subsequent requests.         │ │
│  │                                                                │ │
│  │  2. Monotonic reads: Pin a user to one replica so they         │ │
│  │     never see data go "backwards" (seeing newer then older).   │ │
│  │                                                                │ │
│  │  3. Causal consistency: If operation B depends on A,           │ │
│  │     ensure B reads from a replica that has seen A.             │ │
│  │                                                                │ │
│  │  4. Accept eventual consistency: For non-critical reads,       │ │
│  │     stale data is fine (search results, analytics, feeds).     │ │
│  └────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Read Replicas In My CXP Projects — Real Examples

### The CXP Read Replica Architecture

Every data store in our platform uses some form of read replica pattern:

```
┌──────────────────────────────────────────────────────────────────────────┐
│              CXP PLATFORM — READ REPLICA MAP                              │
│                                                                          │
│  APPLICATION LAYER                                                       │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  cxp-events         cxp-event-registration    expviewsnikeapp   │   │
│  └──────┬───────────────────────┬────────────────────────┬─────────┘   │
│         │                       │                        │              │
│   READS │               WRITES  │  READS           READS │              │
│         ▼                  ▼    ▼    ▼                   ▼              │
│  ┌──────────────┐  ┌──────────────────────┐  ┌──────────────────┐     │
│  │  Akamai CDN  │  │  Redis ElastiCache   │  │  Elasticsearch   │     │
│  │  250+ PoPs   │  │                      │  │                  │     │
│  │  (read-only  │  │  ┌────────┐          │  │  ┌────────────┐  │     │
│  │   copies)    │  │  │PRIMARY │──async──▶│  │  │Primary     │  │     │
│  │              │  │  │(writes)│  ┌──────┐│  │  │Shard       │  │     │
│  │  R:W ratio   │  │  └────────┘  │ R1   ││  │  │  │         │  │     │
│  │  ~1000:1     │  │              │(read)││  │  │  ▼         │  │     │
│  │              │  │              ├──────┤│  │  │Replica     │  │     │
│  │  primary     │  │              │ R2   ││  │  │Shard       │  │     │
│  │  load: ~0%   │  │              │(read)││  │  └────────────┘  │     │
│  └──────────────┘  │              ├──────┤│  │                  │     │
│                    │              │ R3   ││  │  R:W ~100:1      │     │
│                    │              │(read)││  └──────────────────┘     │
│                    │              └──────┘│                           │
│                    │                      │                           │
│                    │  R:W ~50:1           │                           │
│                    └──────────────────────┘                           │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  DynamoDB: No read replicas (in the traditional sense).      │   │
│  │  Within a region, reads go to any of 3 AZ replicas.          │   │
│  │  Cross-region Global Table is multi-leader, not read-replica. │   │
│  │  R:W ~1:5 (write-heavy queue) → read replicas wouldn't help. │   │
│  └──────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: Redis ElastiCache — Classic Read Replica Pattern

**Service:** `cxp-event-registration`
**Ratio:** Estimated ~50:1 read-to-write (many users check event details / idempotency, few register)

```
┌──────────────────────────────────────────────────────────────────────┐
│  Redis Read Replica — How It Works in Our Registration Service       │
│                                                                      │
│  WRITE PATH (registration attempt):                                 │
│  ───────────────────────────────────                                │
│  1. User clicks "Register"                                          │
│  2. Service writes to Redis PRIMARY:                                │
│     SET "user123:event456_success_response" → JSON → TTL 60min      │
│  3. PRIMARY ACKs → service proceeds                                 │
│  4. PRIMARY async-replicates to R1, R2, R3                          │
│                                                                      │
│  READ PATH (subsequent page loads, status checks):                  │
│  ──────────────────────────────────────────────────                  │
│  1. User refreshes page or checks registration status               │
│  2. Service reads from REPLICA (REPLICA_PREFERRED):                 │
│     GET "user123:event456_success_response"                         │
│  3. Replica returns cached response                                 │
│  4. PRIMARY never touched → primary stays free for writes           │
│                                                                      │
│  LOAD DISTRIBUTION:                                                  │
│  ┌──────────┬────────────┬──────────────────────────────────┐      │
│  │  Node    │  Traffic   │  Operations                       │      │
│  ├──────────┼────────────┼──────────────────────────────────┤      │
│  │  PRIMARY │  ~2%       │  SET (idempotency), SET (cache),  │      │
│  │          │            │  DELETE (eviction)                │      │
│  │  R1      │  ~33%      │  GET (cache hits, status checks)  │      │
│  │  R2      │  ~33%      │  GET (cache hits, status checks)  │      │
│  │  R3      │  ~33%      │  GET (cache hits, status checks)  │      │
│  └──────────┴────────────┴──────────────────────────────────┘      │
│                                                                      │
│  Without replicas: PRIMARY handles 100% → bottleneck at ~50K ops/s  │
│  With 3 replicas:  PRIMARY handles ~2%  → writes only, never        │
│                    saturated. Total read capacity = 3x.             │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — read routing:**

```java
// ReactiveRedisConfig.java
// REPLICA_PREFERRED: reads go to replicas first, fall back to primary
lettuceClientConfiguration.readFrom(ReadFrom.REPLICA_PREFERRED);

// Other options the framework provides:
// ReadFrom.MASTER          → all reads to primary (strong consistency)
// ReadFrom.REPLICA         → only replicas (fail if all replicas down)
// ReadFrom.REPLICA_PREFERRED → replicas first, primary fallback
// ReadFrom.NEAREST         → lowest latency node (primary or replica)
```

**Read-after-write handling in practice:**

```java
// RegistrationCacheService.java — WRITE then immediate READ
// Step 1: Write success response to PRIMARY
redisTemplate.opsForValue().set(
    idempotencyKey + SUCCESS_RESPONSE_SUFFIX,
    GSON.toJson(value),
    Duration.ofMinutes(60)
);

// Step 2: Next request tries to GET from REPLICA
// POTENTIAL ISSUE: Replica might not have the key yet (replication lag)
// ACTUAL RISK: Very low (<1ms lag for Redis async replication)
// MITIGATION: If replica misses, REPLICA_PREFERRED falls back to primary
```

**Interview answer:**
> "Our Redis ElastiCache uses 3 read replicas with `REPLICA_PREFERRED` routing. With an estimated 50:1 read-to-write ratio, this means the primary handles only ~2% of total traffic — just the writes. The 3 replicas share the 98% read load equally. Replication lag is sub-millisecond, so stale reads are practically non-existent. For the rare case where a replica hasn't caught up, `REPLICA_PREFERRED` automatically falls back to the primary. Without replicas, our primary would be the bottleneck during sneaker launch traffic."

---

### Example 2: Elasticsearch — Replica Shards as Read Replicas

**Service:** `expviewsnikeapp`
**Ratio:** ~100:1 (thousands search events, few events created/updated)

```
┌──────────────────────────────────────────────────────────────────────┐
│  Elasticsearch Read Replicas (Replica Shards)                        │
│                                                                      │
│  Index: pg_eventcard                                                │
│  Shards: 5 primary + 5 replica = 10 total shards                   │
│                                                                      │
│  Node A (Data Node)           Node B (Data Node)                    │
│  ┌──────────┐ ┌──────────┐   ┌──────────┐ ┌──────────┐            │
│  │ Shard 0  │ │ Shard 1  │   │ Shard 0  │ │ Shard 2  │            │
│  │ PRIMARY  │ │ PRIMARY  │   │ REPLICA  │ │ PRIMARY  │            │
│  └──────────┘ └──────────┘   └──────────┘ └──────────┘            │
│                                                                      │
│  Node C (Data Node)                                                 │
│  ┌──────────┐ ┌──────────┐                                         │
│  │ Shard 1  │ │ Shard 2  │                                         │
│  │ REPLICA  │ │ REPLICA  │                                         │
│  └──────────┘ └──────────┘                                         │
│                                                                      │
│  SEARCH REQUEST: "Nike running events in Portland"                  │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  1. Coordinating node receives query                       │    │
│  │  2. Sends query to ALL shards (primary OR replica — either │    │
│  │     can serve the read)                                    │    │
│  │  3. Each shard searches its local inverted index            │    │
│  │  4. Results merged, scored, returned                        │    │
│  │                                                             │    │
│  │  With replicas: search can hit Shard 0 PRIMARY on Node A   │    │
│  │  OR Shard 0 REPLICA on Node B → doubles read capacity      │    │
│  │  per shard.                                                 │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  READ SCALING MATH:                                                 │
│  Without replicas: 5 primary shards = 5 parallel search workers    │
│  With 1 replica:   10 shards (5P + 5R) = 10 parallel workers      │
│  With 2 replicas:  15 shards (5P + 10R) = 15 parallel workers     │
│  Each additional replica DOUBLES per-shard read capacity.           │
│                                                                      │
│  WRITE IMPACT:                                                      │
│  Every document write goes to primary shard → then SYNC to replica. │
│  More replicas = more write overhead (each write multiplied).       │
│  Acceptable for events (low write frequency, high search frequency).│
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — search uses primary OR replica transparently:**

```java
// ElasticSearchRepository.java
// ES automatically routes searches to primary OR replica shard
SearchRequest searchRequest = new SearchRequest();
searchRequest.indices("pg_eventcard");
// No preference specified → ES coordinator balances across all copies
// This is the read replica pattern built into ES at the shard level
```

**Interview answer:**
> "Elasticsearch has read replicas built into its shard model. Our `pg_eventcard` index has 5 primary shards and 5 replica shards. Every search query can hit either the primary or replica copy of each shard — effectively doubling our read throughput. Since events are searched thousands of times per minute but only updated a few times per day, the 100:1 read-to-write ratio makes replica shards extremely cost-effective. Each additional replica doubles per-shard read capacity at the cost of extra write overhead during indexing — a tradeoff that's negligible for our low-write workload."

---

### Example 3: Akamai CDN — Read Replicas at Extreme Scale

**Service:** Event pages served via cxp-events
**Ratio:** ~1000:1 or higher (millions view event pages, origin rarely updated)

```
┌──────────────────────────────────────────────────────────────────────┐
│  CDN as Read Replica — The Extreme Case                              │
│                                                                      │
│  Traditional read replica:           CDN read replica:              │
│  ┌────────┐                          ┌────────┐                    │
│  │PRIMARY │──push──▶ Replicas        │ORIGIN  │                    │
│  └────────┘         (3-5 nodes)      └────────┘                    │
│                                           │                         │
│                                           │  pull (on cache miss)   │
│                                           ▼                         │
│                                      250+ PoPs worldwide            │
│                                      (read-only copies)            │
│                                                                      │
│  SCALING COMPARISON:                                                │
│  ┌───────────────────┬──────────────┬──────────────┐               │
│  │                   │ DB Replicas  │ CDN PoPs     │               │
│  ├───────────────────┼──────────────┼──────────────┤               │
│  │ Replica count     │ 3-5          │ 250+         │               │
│  │ Replication mode  │ Push (async) │ Pull (on-    │               │
│  │                   │              │ demand)      │               │
│  │ Staleness         │ <1ms - 1s    │ TTL (minutes)│               │
│  │ Data freshness    │ Near real-   │ Eventually   │               │
│  │                   │ time         │ fresh        │               │
│  │ Read scaling      │ 3-5x         │ 1000x+       │               │
│  │ Cost per replica  │ $$$          │ $0 (included │               │
│  │                   │ (full DB)    │ in CDN)      │               │
│  │ Origin load       │ Reduced by   │ Reduced by   │               │
│  │                   │ 60-80%       │ 95-99%       │               │
│  └───────────────────┴──────────────┴──────────────┘               │
│                                                                      │
│  For cxp-events: CDN cache hit ratio ~95%                           │
│  Meaning: only 5% of requests reach the origin (cxp-events backend) │
│  The other 95% are served from the nearest CDN PoP.                │
│                                                                      │
│  Invalidation via NSP3 Kafka Purge Sink:                            │
│  Event updated → Kafka message → Akamai purge API → PoPs evict     │
│  This is like a "forced replication" — pushing freshness to replicas │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "Our CDN layer is read replicas taken to the extreme — 250+ edge locations, each serving cached copies of event pages. With a ~95% cache hit ratio, only 5% of traffic reaches the origin. That's equivalent to having 20 read replicas in terms of origin load reduction, but at 1/10th the cost because CDN PoPs don't store full database copies — just the HTTP responses. The tradeoff is higher staleness (TTL-based, minutes) compared to database replicas (sub-millisecond). We mitigate this with Kafka-driven cache purges for time-sensitive updates."

---

### Example 4: DynamoDB — Why Read Replicas DON'T Help Here

**Service:** `cxp-event-registration` (unprocessed registration queue)
**Ratio:** ~1:5 write-heavy (more writes than reads)

```
┌──────────────────────────────────────────────────────────────────────┐
│  DynamoDB — Read Replicas Would NOT Help                             │
│                                                                      │
│  Access pattern:                                                    │
│  1. PUT registration (write)     ← ~80% of operations               │
│  2. GET registration (read)      ← ~5% (check if exists)            │
│  3. DELETE registration (write)  ← ~10% (after reprocessing)         │
│  4. SCAN all (read)              ← ~5% (batch reprocessing)          │
│                                                                      │
│  WRITE-HEAVY → Read replicas don't help!                            │
│                                                                      │
│  What DynamoDB does instead:                                        │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  HORIZONTAL PARTITIONING (auto-sharding)                       │ │
│  │                                                                │ │
│  │  DynamoDB automatically splits data across partitions          │ │
│  │  based on the partition key hash. Each partition handles       │ │
│  │  ~1000 WCU (write capacity units).                            │ │
│  │                                                                │ │
│  │  10,000 writes/sec → DynamoDB creates ~10 partitions          │ │
│  │  Each partition replicated across 3 AZs (sync).               │ │
│  │                                                                │ │
│  │  This is WRITE scaling (sharding), not READ scaling (replicas).│ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  LESSON: Don't add read replicas to a write-heavy workload.         │
│  Instead, use partitioning/sharding to distribute writes.           │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "Our DynamoDB table is write-heavy — ~80% of operations are PUT/DELETE. Read replicas wouldn't help because the bottleneck is writes, not reads. DynamoDB handles this with automatic horizontal partitioning — it splits data across partitions based on the hash key, each partition handling ~1000 writes/second. At 10,000 writes/sec during a sneaker launch, DynamoDB creates ~10 partitions automatically. This is write scaling via sharding, not read scaling via replicas. The right tool for the right bottleneck."

---

## When to Use Read Replicas vs Other Scaling Strategies

```
┌──────────────────────────────────────────────────────────────────────┐
│  DECISION: HOW TO SCALE YOUR DATABASE                                │
│                                                                      │
│  What's your bottleneck?                                            │
│  │                                                                  │
│  ├── READS are slow / primary overloaded                            │
│  │   ├── Read-to-write ratio > 5:1?                                │
│  │   │   └── ✅ ADD READ REPLICAS                                   │
│  │   │       Redis: add replicas, use REPLICA_PREFERRED             │
│  │   │       ES: increase replica shard count                       │
│  │   │       SQL: RDS read replicas                                 │
│  │   │       CDN: enable caching for static/semi-static content     │
│  │   │                                                              │
│  │   └── Same data queried repeatedly?                              │
│  │       └── ✅ ADD CACHE LAYER (Redis/Memcached in front of DB)    │
│  │                                                                  │
│  ├── WRITES are slow / primary overloaded                           │
│  │   ├── Single key bottleneck (hot partition)?                     │
│  │   │   └── ✅ SHARD/PARTITION (DynamoDB auto-sharding)            │
│  │   │                                                              │
│  │   ├── Cross-region write latency too high?                       │
│  │   │   └── ✅ MULTI-LEADER (DynamoDB Global Tables)               │
│  │   │                                                              │
│  │   └── Single table too large?                                    │
│  │       └── ✅ HORIZONTAL SHARDING (split by key range)            │
│  │                                                                  │
│  └── BOTH reads and writes are bottlenecked                         │
│      └── ✅ CQRS: separate read store from write store              │
│          CXP example: Writes → Eventtia (relational)                │
│                        Reads  → Elasticsearch (search-optimized)    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## CXP Platform: The CQRS Pattern (Reads and Writes Separated)

Our platform doesn't just use read replicas — it uses **completely separate databases for reads vs writes**. This is the CQRS (Command Query Responsibility Segregation) pattern:

```
┌──────────────────────────────────────────────────────────────────────┐
│  CQRS IN CXP — Reads and Writes Use Different Databases             │
│                                                                      │
│  WRITE SIDE (Commands)               READ SIDE (Queries)            │
│  ─────────────────────               ──────────────────             │
│                                                                      │
│  User registers for event            User searches for events       │
│         │                                    │                       │
│         ▼                                    ▼                       │
│  cxp-event-registration              expviewsnikeapp                │
│         │                                    │                       │
│         ▼                                    ▼                       │
│  ┌──────────────┐                    ┌──────────────┐              │
│  │  Eventtia    │                    │ Elasticsearch │              │
│  │  (Relational │  ──async sync──▶  │ (Inverted    │              │
│  │   Database)  │    (indexing)      │  Index)       │              │
│  │              │                    │              │              │
│  │  Optimized   │                    │  Optimized   │              │
│  │  for WRITES: │                    │  for READS:  │              │
│  │  - ACID txns │                    │  - Full-text │              │
│  │  - Seat locks│                    │  - Relevance │              │
│  │  - FK checks │                    │  - Geo-dist  │              │
│  └──────────────┘                    └──────────────┘              │
│                                                                      │
│  This is beyond read replicas — it's entirely different databases   │
│  with different data models, each optimized for its access pattern. │
│  Eventual consistency between write-side and read-side (~1-5 sec).  │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "Our platform goes beyond traditional read replicas — we use full CQRS. Writes go to Eventtia's relational database (ACID transactions for seat management), while reads come from Elasticsearch (inverted index for search). These aren't replicas of the same data structure — they're entirely different databases with different schemas, each optimized for its access pattern. Elasticsearch is asynchronously synced from Eventtia with ~1-5 second lag. This gives us the write guarantees of SQL and the read performance of a search engine without compromising either."

---

## Summary: Read Replica Patterns Across CXP

| Component | Read Replica Type | R:W Ratio | Load Reduction | Staleness |
|-----------|------------------|-----------|----------------|-----------|
| **Redis ElastiCache** | Database replicas (3 nodes) | ~50:1 | Primary handles ~2% of traffic | <1ms |
| **Elasticsearch** | Replica shards (per index) | ~100:1 | 2x read capacity per shard | ~1s (refresh interval) |
| **Akamai CDN** | Pull-based edge replicas (250+) | ~1000:1 | Origin handles ~5% of traffic | Minutes (TTL) |
| **Eventtia → ES** | CQRS (separate read DB) | N/A | Write DB has zero read load | ~1-5s (async sync) |
| **DynamoDB** | None (write-heavy) | ~1:5 | N/A — uses auto-sharding instead | N/A |

---

## Common Interview Follow-ups

### Q: "How many read replicas should I add?"

> "Start with the math: if your R:W ratio is 10:1 and you have 3 replicas, each replica handles ~3x the primary's read volume (9000/3 = 3000 reads each vs 1000 writes on primary). The formula:
> - **Replicas needed** = (target read throughput / per-node capacity) - 1
> - But diminishing returns apply — 3 replicas reduce primary load by ~75%, 5 replicas by ~83%, 10 replicas by ~91%. After 3-5, the cost of maintaining replicas (storage, replication bandwidth) often exceeds the benefit."

### Q: "What if a user writes data and immediately reads stale data from a replica?"

> "This is the read-after-write problem. Four solutions depending on severity:
> 1. **Route that user's reads to primary** for N seconds after a write (Redis: `ReadFrom.MASTER` for critical paths).
> 2. **Use sticky sessions** so the user always hits the same replica (monotonic reads).
> 3. **Include a version/timestamp** in the write response and reject stale reads.
> 4. **Accept it** — for our idempotency cache, `REPLICA_PREFERRED` falls back to primary on miss, and Eventtia has its own duplicate check as defense in depth."

### Q: "Read replicas vs caching — when do I use which?"

> "Use read replicas when you need the FULL dataset to be queryable (like Elasticsearch search across all events). Use caching when you need FAST access to frequently requested data (like Redis caching the top 100 event pages). In our platform, we use BOTH: Redis caches hot event data for <1ms reads, while Elasticsearch replica shards serve search queries across the full event catalog. They complement, not replace, each other."

### Q: "Can you promote a read replica to primary if the primary fails?"

> "Yes — this is the standard failover pattern. Redis Sentinel or ElastiCache Multi-AZ automatically promotes a replica to primary on failure. The tradeoff: any writes that were async-replicated but not yet received by the promoted replica are LOST. This is why critical data (like our Partner Hub webhooks) uses S3 with synchronous 3-AZ replication — no data loss on failover."

---
---

# Topic 8: Sharding Strategies

> Hash-based for even distribution, range-based for time-series, directory-based for flexibility — choose based on query patterns.

> **Interview Tip:** Always discuss the shard key — "I'd shard by user_id for even distribution and because most queries are user-scoped, avoiding cross-shard joins."

---

## What Is Sharding?

Splitting a single database into **multiple smaller databases (shards)**, each holding a subset of the data. Unlike read replicas (which copy ALL data), shards hold DIFFERENT data.

```
WITHOUT SHARDING:                    WITH SHARDING:
──────────────────                   ─────────────────

┌──────────────────┐                 ┌────────┐ ┌────────┐ ┌────────┐
│  ONE DATABASE    │                 │ Shard 0│ │ Shard 1│ │ Shard 2│
│                  │                 │        │ │        │ │        │
│  10 TB of data   │                 │ 3.3 TB │ │ 3.3 TB │ │ 3.3 TB │
│  50K writes/sec  │                 │ 17K w/s│ │ 17K w/s│ │ 17K w/s│
│  ← BOTTLENECK    │                 │        │ │        │ │        │
│                  │                 └────────┘ └────────┘ └────────┘
└──────────────────┘
                                     Each shard is an independent DB.
                                     Total capacity = sum of all shards.
                                     Scales WRITES (replicas only scale reads).
```

### Sharding vs Read Replicas — Different Problems

```
┌────────────────────────────────────────────────────────────────────┐
│                                                                    │
│  READ REPLICAS               SHARDING                             │
│  ──────────────              ────────                             │
│  Same data, many copies      Different data per node              │
│  Scales READS                Scales READS + WRITES                │
│  All replicas identical      Each shard has unique subset         │
│  Easy to add                 Hard to rebalance                    │
│  No cross-node joins         Cross-shard queries are expensive    │
│                                                                    │
│  Use when: read-heavy        Use when: data too large for one     │
│            workload                    node, OR write-heavy        │
└────────────────────────────────────────────────────────────────────┘
```

---

## The 4 Sharding Strategies

```
┌──────────────────────────────────────────────────────────────────────────┐
│                       SHARDING STRATEGIES                                │
│                                                                          │
│  ┌─────────────────────────────┐  ┌─────────────────────────────┐      │
│  │   HASH-BASED SHARDING       │  │   RANGE-BASED SHARDING      │      │
│  │                              │  │                              │      │
│  │  shard = hash(key) % N       │  │  Partition by key ranges     │      │
│  │                              │  │                              │      │
│  │  user_id:123 → hash → %4=3  │  │  A-M → Shard 1              │      │
│  │  ┌────┐ ┌────┐ ┌────┐ ┌────┐│  │  N-Z → Shard 2              │      │
│  │  │ S0 │ │ S1 │ │ S2 │ │ S3 ││  │  2023-2024 → Time Shard    │      │
│  │  └────┘ └────┘ └────┘ └────┘│  │                              │      │
│  │                              │  │  [+] Good for range queries  │      │
│  │  [+] Even distribution       │  │  [+] Time-series friendly    │      │
│  │  [+] Simple to implement     │  │  [-] Hot spots if uneven     │      │
│  │  [-] Resharding expensive    │  │      distribution            │      │
│  │      (rehash all keys)       │  │                              │      │
│  └─────────────────────────────┘  └─────────────────────────────┘      │
│                                                                          │
│  ┌─────────────────────────────┐  ┌─────────────────────────────┐      │
│  │  DIRECTORY-BASED SHARDING   │  │   GEO-BASED SHARDING        │      │
│  │                              │  │                              │      │
│  │  Lookup table maps keys     │  │  Partition by geographic     │      │
│  │  to shards                  │  │  location                    │      │
│  │                              │  │                              │      │
│  │  ┌─────────────┐            │  │  ┌────────┐ ┌──────┐ ┌────┐│      │
│  │  │ Lookup Table│──▶ S1,S2,S3│  │  │US-EAST │ │EU-   │ │AP- ││      │
│  │  │ user_1 → S1 │            │  │  │Americas│ │WEST  │ │SOUTH│      │
│  │  │ user_2 → S3 │            │  │  └────────┘ │Europe│ │Asia-││      │
│  │  │ user_3 → S2 │            │  │             └──────┘ │Pacif││      │
│  │  └─────────────┘            │  │                      └────┘│      │
│  │                              │  │                              │      │
│  │  [+] Flexible, easy to      │  │  [+] Low latency for users   │      │
│  │      rebalance              │  │  [+] Data residency (GDPR)   │      │
│  │  [-] Lookup table is single │  │  [-] Cross-region queries    │      │
│  │      point of failure       │  │      are complex             │      │
│  └─────────────────────────────┘  └─────────────────────────────┘      │
```

---

## How Each Strategy Works

### Hash-Based Sharding

```
  Key: "eventId_uuid-1234"
           │
           ▼
  hash("eventId_uuid-1234") = 2847193
           │
           ▼
  2847193 % 4 = 1  →  Shard 1
           │
           ▼
  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐
  │ Shard 0│ │►Shard 1│ │ Shard 2│ │ Shard 3│
  └────────┘ └────────┘ └────────┘ └────────┘

  PRO: Keys evenly distributed (good hash function = uniform spread)
  CON: Adding Shard 4 means rehashing ALL keys (2847193 % 5 ≠ % 4)
       → Solution: CONSISTENT HASHING (only ~1/N keys move)

  CANNOT do: Range queries (WHERE created_at BETWEEN X AND Y)
             Because adjacent dates may hash to different shards.
```

### Range-Based Sharding

```
  Key: event_date = "2026-04-13"
           │
           ▼
  Shard map:
    2025-01 to 2025-12  →  Shard 1 (archive)
    2026-01 to 2026-06  →  Shard 2 (current)
    2026-07 to 2026-12  →  Shard 3 (future)
           │
           ▼
  ┌────────┐ ┌────────┐ ┌────────┐
  │ Shard 1│ │►Shard 2│ │ Shard 3│
  │ archive│ │ current│ │ future │
  └────────┘ └────────┘ └────────┘

  PRO: Range queries are FAST (all April 2026 data on one shard)
  CON: HOT SPOT — Shard 2 (current) gets all the traffic.
       Shards 1 and 3 are idle.

  MITIGATION: Combine with hash within each range shard.
```

### Directory-Based Sharding

```
  Key: user_id = "uuid-5678"
           │
           ▼
  Lookup service (separate DB or cache):
    uuid-5678 → Shard 2
           │
           ▼
  ┌────────┐ ┌────────┐ ┌────────┐
  │ Shard 0│ │ Shard 1│ │►Shard 2│
  └────────┘ └────────┘ └────────┘

  PRO: Complete flexibility — move any user to any shard anytime
  CON: Lookup service = single point of failure + extra hop
       Every request requires lookup before hitting the shard

  USE WHEN: Rebalancing must be zero-downtime (move hot users to
            less-loaded shards without rehashing everything)
```

### Geo-Based Sharding

```
  Key: marketplace = "US" / "EU" / "APAC"
           │
           ▼
  Route by geography:
    US users  →  us-east-1 shard
    EU users  →  eu-west-1 shard
    APAC users → ap-southeast-1 shard
           │
           ▼
  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
  │ US-EAST     │ │ EU-WEST     │ │ AP-SOUTH    │
  │ Americas    │ │ Europe      │ │ Asia-Pacific│
  └─────────────┘ └─────────────┘ └─────────────┘

  PRO: Users always hit local shard (low latency)
       Data stays in region (GDPR compliance)
  CON: Cross-region queries need scatter-gather
       Uneven traffic (US may have 10x more than APAC)
```

---

## Choosing a Shard Key — The Most Important Decision

The shard key determines everything: data distribution, query efficiency, and hot spot risk.

```
┌──────────────────────────────────────────────────────────────────────┐
│  SHARD KEY SELECTION CRITERIA                                        │
│                                                                      │
│  1. HIGH CARDINALITY                                                │
│     Many unique values → even distribution across shards             │
│     ✓ user_id (millions of unique users)                             │
│     ✗ country (only ~200 countries → uneven shards)                  │
│                                                                      │
│  2. MATCHES QUERY PATTERN                                            │
│     Most queries filter by this key → single-shard queries          │
│     ✓ "WHERE user_id = X" if sharded by user_id                    │
│     ✗ "WHERE created_at > X" if sharded by user_id (cross-shard)   │
│                                                                      │
│  3. EVEN WRITE DISTRIBUTION                                         │
│     No single shard gets disproportionate writes                    │
│     ✓ hash(user_id) → uniform writes                                │
│     ✗ event_id (sneaker launch event gets 90% of writes)            │
│                                                                      │
│  4. AVOIDS CROSS-SHARD OPERATIONS                                   │
│     JOINs, aggregations across shards are expensive                 │
│     ✓ All data for one user on one shard                             │
│     ✗ User data on shard A, their orders on shard B                 │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Sharding In My CXP Projects — Real Examples

### The CXP Sharding Map

```
┌──────────────────────────────────────────────────────────────────────────┐
│              CXP PLATFORM — SHARDING MAP                                  │
│                                                                          │
│  ┌──────────────┐  Strategy: HASH-BASED (auto)                          │
│  │  DynamoDB     │  Shard key: hash(eventId_upmId)                      │
│  │  (auto-shard) │  DynamoDB manages partitions transparently.          │
│  └──────────────┘                                                        │
│                                                                          │
│  ┌──────────────┐  Strategy: HASH-BASED (explicit)                      │
│  │ Elasticsearch │  Shard key: hash(_id) % num_primary_shards           │
│  │  (5 shards)   │  5 primary shards across data nodes.                 │
│  └──────────────┘                                                        │
│                                                                          │
│  ┌──────────────┐  Strategy: GEO-BASED                                  │
│  │  DynamoDB     │  Shard by region: us-east-1 + us-west-2              │
│  │  Global Table │  Each region = independent leader (multi-leader).    │
│  └──────────────┘                                                        │
│                                                                          │
│  ┌──────────────┐  Strategy: RANGE-BASED (implicit)                     │
│  │  S3+Athena    │  Data organized by time: year/month/day folders.     │
│  │  Partner Hub  │  Athena scans only relevant date partitions.         │
│  └──────────────┘                                                        │
│                                                                          │
│  ┌──────────────┐  Strategy: HASH-BASED (Splunk tsidx)                  │
│  │  Splunk       │  Shard key: index name + time bucket.                │
│  │  (indexes)    │  Each index = its own bucket of data.                │
│  └──────────────┘                                                        │
│                                                                          │
│  ┌──────────────┐  Strategy: GEO-BASED (CDN PoPs)                       │
│  │  Akamai CDN   │  Each PoP caches data for its region's users.       │
│  └──────────────┘                                                        │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: DynamoDB — Automatic Hash-Based Sharding

**Service:** `cxp-event-registration`
**Table:** `unprocessed-registration-requests`
**Shard key:** `eventId_upmId` (composite string, hashed by DynamoDB internally)

```
┌──────────────────────────────────────────────────────────────────────┐
│  DynamoDB Auto-Sharding (Partitioning)                               │
│                                                                      │
│  You write: PK = "73067_uuid-1234"                                  │
│  DynamoDB does:                                                     │
│    1. hash("73067_uuid-1234") = 0x7A3F...                           │
│    2. Maps hash to partition range → Partition 3                    │
│    3. Writes to Partition 3                                          │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────┐       │
│  │  Partition 0       Partition 1       Partition 2        │       │
│  │  hash: 0x00-0x3F   hash: 0x40-0x7F   hash: 0x80-0xBF  │       │
│  │  ┌──────────┐      ┌──────────┐      ┌──────────┐     │       │
│  │  │ 500 items│      │ 480 items│      │ 510 items│     │       │
│  │  │ 1K WCU   │      │ 1K WCU   │      │ 1K WCU   │     │       │
│  │  └──────────┘      └──────────┘      └──────────┘     │       │
│  └─────────────────────────────────────────────────────────┘       │
│                                                                      │
│  AUTO-SCALING:                                                      │
│  - <1000 writes/sec → 1 partition handles all traffic               │
│  - 10,000 writes/sec (sneaker launch) → DynamoDB splits to ~10     │
│    partitions automatically                                         │
│  - After traffic drops → partitions remain (no merge-back)          │
│                                                                      │
│  WHY OUR SHARD KEY IS GOOD:                                        │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Key: "eventId_upmId" = "73067_uuid-1234"                  │    │
│  │                                                              │    │
│  │  ✓ High cardinality — millions of unique user+event combos  │    │
│  │  ✓ Even distribution — hash spreads across partitions       │    │
│  │  ✓ No hot partition — even during a sneaker launch,         │    │
│  │    different users hash to different partitions              │    │
│  │                                                              │    │
│  │  WHAT IF we had used just "eventId" as the key?             │    │
│  │  ✗ All 10,000 registrations for event 73067 would go to    │    │
│  │    ONE partition → HOT PARTITION → throttling!              │    │
│  │    This is the #1 DynamoDB anti-pattern.                    │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

**From the Terraform — PAY_PER_REQUEST enables auto-scaling:**

```hcl
# dynamodb.tf — billing_mode determines scaling behavior
resource "aws_dynamodb_table" "unprocessed_registration_requests" {
  billing_mode = "PAY_PER_REQUEST"   # auto-scales partitions on demand
  hash_key     = "eventId_upmId"      # composite key → good distribution
}
```

**The hot partition problem (what we avoided):**

```
BAD shard key: just "eventId"

  Sneaker launch event 73067:
  10,000 registrations in 60 seconds
  ALL hash to the SAME partition → 10K WCU on 1 partition
  Partition limit: ~1000 WCU → THROTTLED!
  9,000 writes REJECTED → users see errors

GOOD shard key: "eventId_upmId" (what we use)

  Same 10,000 registrations:
  "73067_uuid-0001" → hash → Partition 2
  "73067_uuid-0002" → hash → Partition 7
  "73067_uuid-0003" → hash → Partition 1
  ...evenly spread across ALL partitions
  No single partition exceeds limit → zero throttling
```

**Interview answer:**
> "Our DynamoDB table uses `eventId_upmId` as the partition key — a composite of event ID and user ID. This is critical because during a sneaker launch, 10,000 users register for the same event simultaneously. If we had used just `eventId`, all writes would hash to one partition and get throttled at ~1000 WCU. By including `upmId` in the key, each user's write hashes to a different partition, distributing the load evenly. DynamoDB auto-scales partitions behind the scenes — we set `PAY_PER_REQUEST` billing mode and DynamoDB handles the rest."

---

### Example 2: Elasticsearch — Hash-Based Shard Routing

**Service:** `expviewsnikeapp`
**Index:** `pg_eventcard` — 5 primary shards
**Shard key:** `hash(_id) % 5`

```
┌──────────────────────────────────────────────────────────────────────┐
│  Elasticsearch Shard Routing                                         │
│                                                                      │
│  Document: { "event_id": 73067, "name": "Nike Run Portland" }      │
│  _id = "73067" (or auto-generated)                                  │
│                                                                      │
│  Routing formula: shard = hash(_id) % num_primary_shards            │
│  hash("73067") % 5 = 2  →  Shard 2                                 │
│                                                                      │
│  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐          │
│  │Shard 0 │ │Shard 1 │ │►Shard 2│ │Shard 3 │ │Shard 4 │          │
│  │        │ │        │ │ 73067  │ │        │ │        │          │
│  │ ~20%   │ │ ~20%   │ │ ~20%   │ │ ~20%   │ │ ~20%   │          │
│  └────────┘ └────────┘ └────────┘ └────────┘ └────────┘          │
│                                                                      │
│  SEARCH QUERY: "Nike running events near Portland"                  │
│                                                                      │
│  1. Query hits coordinating node                                    │
│  2. Coordinator sends query to ALL 5 shards (scatter)               │
│  3. Each shard searches its local inverted index                     │
│  4. Results returned to coordinator (gather)                        │
│  5. Coordinator merges, scores, returns top N                       │
│                                                                      │
│  This is SCATTER-GATHER — every search touches all shards.          │
│  Acceptable because each shard's search is parallel + fast.         │
│                                                                      │
│  SHARD COUNT MATTERS:                                                │
│  - Too few shards (1): no parallelism, single node bottleneck      │
│  - Too many shards (100): overhead per shard, wasteful for small    │
│    datasets                                                         │
│  - Rule of thumb: ~20-40 GB per shard for optimal performance      │
│  - Our 5 shards: appropriate for ~100-200 GB of event data         │
│                                                                      │
│  CANNOT CHANGE shard count after creation!                          │
│  Must reindex to a new index with different shard count.            │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — search touches all shards:**

```java
// ElasticSearchRepository.java
SearchRequest searchRequest = new SearchRequest();
searchRequest.indices("pg_eventcard");
// No routing specified → query goes to ALL 5 shards (scatter-gather)
// ES coordinator merges results from all shards

// Custom routing example (NOT used in our code, but useful to know):
// searchRequest.routing("US");  ← would only hit the shard containing US data
```

**Interview answer:**
> "Our Elasticsearch index uses hash-based sharding with 5 primary shards. Documents are routed to shards via `hash(_id) % 5`. Search queries use scatter-gather — they hit all 5 shards in parallel and the coordinator merges results. This is fine for event search because each shard's inverted index lookup is O(1) and parallelism across 5 shards actually improves latency. The key design decision was shard count — 5 shards at ~20-40 GB each is appropriate for our event catalog size. We can't change this without reindexing, so we sized it for 3-5 years of growth."

---

### Example 3: DynamoDB Global Tables — Geo-Based Sharding

**Service:** `cxp-event-registration`
**Regions:** us-east-1 (Americas) + us-west-2 (West Coast / APAC)

```
┌──────────────────────────────────────────────────────────────────────┐
│  DynamoDB Global Tables — Geo-Based Sharding                         │
│                                                                      │
│  Route53 latency-based routing:                                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  User in New York      → Route53 → us-east-1 DynamoDB       │  │
│  │  User in Los Angeles   → Route53 → us-west-2 DynamoDB       │  │
│  │  User in Tokyo         → Route53 → us-west-2 DynamoDB       │  │
│  │  User in London        → Route53 → us-east-1 DynamoDB       │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  Each region has the FULL dataset (multi-leader replication):       │
│                                                                      │
│  us-east-1                           us-west-2                      │
│  ┌─────────────────┐                ┌─────────────────┐            │
│  │  Full table      │   async sync  │  Full table      │            │
│  │  hash-sharded    │◄────~1s─────▶│  hash-sharded    │            │
│  │  internally      │               │  internally      │            │
│  └─────────────────┘                └─────────────────┘            │
│                                                                      │
│  This is GEO-BASED routing + HASH-BASED sharding within each       │
│  region. Two levels of sharding working together:                   │
│                                                                      │
│  Level 1: Geo-routing → picks the REGION (us-east vs us-west)      │
│  Level 2: Hash-sharding → picks the PARTITION within that region   │
│                                                                      │
│  UNLIKE pure geo-sharding (where EU data stays in EU only):        │
│  Both regions have ALL data. Geo-routing is for LATENCY, not       │
│  data residency. This is the multi-leader pattern from Topic 6     │
│  combined with automatic hash-based sharding.                      │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "Our DynamoDB Global Tables combine two sharding strategies. At the macro level, Route53 latency-based routing geo-shards traffic — East Coast users hit us-east-1, West Coast and APAC users hit us-west-2. At the micro level, within each region, DynamoDB hash-shards data across partitions using our `eventId_upmId` key. Both regions have the full dataset via multi-leader replication. This is different from pure geo-sharding where data stays in one region — we replicate everywhere for availability and use geo-routing purely for latency reduction."

---

### Example 4: S3 + Athena — Range-Based Sharding (Partitioning)

**Service:** Partner Hub data, queried by `cxp-email-drop-recovery`
**Strategy:** Time-based folder partitioning in S3

```
┌──────────────────────────────────────────────────────────────────────┐
│  S3 Range-Based Sharding (Hive-Style Partitioning)                   │
│                                                                      │
│  S3 bucket layout:                                                  │
│  s3://partnerhub-data/                                              │
│    ├── year=2025/                                                   │
│    │   ├── month=11/    ← old data (rarely queried)                │
│    │   └── month=12/                                                │
│    ├── year=2026/                                                   │
│    │   ├── month=01/                                                │
│    │   ├── month=02/                                                │
│    │   ├── month=03/                                                │
│    │   └── month=04/    ← current data (frequently queried)        │
│    └── ...                                                          │
│                                                                      │
│  Athena query WITHOUT partition awareness:                          │
│  SELECT * FROM partner_hub WHERE event.id = 73067                   │
│  → Scans ALL folders (all years, all months) → EXPENSIVE           │
│  → Cost: $5 per TB scanned × full dataset                          │
│                                                                      │
│  Athena query WITH partition pruning:                               │
│  SELECT * FROM partner_hub                                          │
│  WHERE year = '2026' AND month = '04' AND event.id = 73067         │
│  → Scans ONLY year=2026/month=04/ → CHEAP                          │
│  → Cost: $5 per TB scanned × 1/24th of data (1 month of 2 years)  │
│                                                                      │
│  This is RANGE-BASED SHARDING at the storage level:                │
│  Each folder = a "shard" of data by time range.                    │
│  Athena "shard-prunes" by skipping irrelevant folders.             │
│                                                                      │
│  HOT SHARD PROBLEM:                                                 │
│  Current month's partition gets ALL the writes.                     │
│  Old partitions are read-only (cold storage).                       │
│  This is acceptable because S3 has unlimited write throughput —     │
│  no partition limit like DynamoDB.                                  │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — queries that COULD benefit from partition pruning:**

```python
# server.py — currently queries full table (no partition filter)
q_count = f"""SELECT COUNT(*) as total
    FROM "{ATHENA_DATABASE}".{ATHENA_TABLE}
    WHERE event.id = {event_id} AND action = 'confirmed'"""
# This scans ALL partitions → optimization opportunity

# With partition pruning (optimization):
# WHERE year = '2026' AND month = '04'
#   AND event.id = {event_id} AND action = 'confirmed'
# Would scan ~1/24th of the data
```

**Interview answer:**
> "Our Partner Hub data in S3 uses implicit range-based sharding — data is organized into year/month folders. Athena can partition-prune by skipping irrelevant time ranges. Currently, our investigation queries don't include partition filters and scan the full dataset. If I were optimizing, I'd add `WHERE year = '2026' AND month = '04'` to reduce scan volume by 95%. This is the data lake equivalent of range-based sharding — the S3 folder structure IS the shard boundary, and Athena knows to skip folders that don't match the filter."

---

### Example 5: Splunk — Index-Based Sharding

**Service:** All CXP services → centralized Splunk
**Strategy:** Logical sharding by index name + time-based bucketing

```
┌──────────────────────────────────────────────────────────────────────┐
│  Splunk Index Sharding                                               │
│                                                                      │
│  Splunk indexes = logical shards:                                   │
│  ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐      │
│  │ dockerlogs*     │ │ dockerlogs-gold │ │ app*            │      │
│  │                 │ │                 │ │                 │      │
│  │ General Docker  │ │ Production-only │ │ Application     │      │
│  │ container logs  │ │ delivery logs   │ │ service logs    │      │
│  └────────┬────────┘ └────────┬────────┘ └────────┬────────┘      │
│           │                   │                    │               │
│           ▼                   ▼                    ▼               │
│  Within each index: TIME-BASED BUCKETS                             │
│  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐                       │
│  │ Hot │ │Warm │ │Warm │ │Cold │ │Frozen│                       │
│  │today│ │yest │ │-2d  │ │-30d │ │-90d  │                       │
│  │SSD  │ │SSD  │ │HDD  │ │HDD  │ │S3    │                       │
│  └─────┘ └─────┘ └─────┘ └─────┘ └─────┘                       │
│                                                                      │
│  This is TWO-LEVEL SHARDING:                                       │
│  Level 1: BY CATEGORY (index= filter → skip entire indexes)        │
│  Level 2: BY TIME (earliest/latest → skip old/future buckets)      │
│                                                                      │
│  Our queries ALWAYS specify both:                                   │
│  search index=dockerlogs-gold sourcetype=crs-email* earliest=-30d  │
│  → Skips: all non-gold indexes + all buckets older than 30 days    │
│  → Searches: only matching index + relevant time buckets           │
└──────────────────────────────────────────────────────────────────────┘
```

**From `queries.py`:**

```python
# Two-level shard selection in every query:
# Level 1: index= (category shard)
# Level 2: earliest/latest (time shard)
"dropped_emails": f'''search index=dockerlogs* sourcetype=log4j
    "UserEmailNotAvailable" {time_clause}
    ...'''
```

---

## The Resharding Problem

The hardest part of sharding: **what happens when you need more shards?**

```
┌──────────────────────────────────────────────────────────────────────┐
│  RESHARDING SCENARIOS                                                │
│                                                                      │
│  HASH-BASED: hash(key) % 4 → hash(key) % 5                        │
│  ──────────                                                         │
│  ~80% of keys change shard assignment → massive data migration      │
│  Solution: CONSISTENT HASHING (only ~1/N keys move)                │
│  Our DynamoDB: handled automatically (transparent partition split)  │
│                                                                      │
│  RANGE-BASED: add a new time range                                  │
│  ───────────                                                        │
│  Easy — just create a new folder/partition for the new range        │
│  No existing data moves. New writes go to new partition.            │
│  Our S3: new month = new folder. Zero migration.                   │
│                                                                      │
│  ELASTICSEARCH: change shard count from 5 to 10                     │
│  ─────────────                                                      │
│  IMPOSSIBLE in-place. Must create new index with 10 shards,        │
│  reindex ALL documents, then switch the alias.                      │
│  Our ES: sized at 5 shards for 3-5 years of growth to avoid this.  │
│                                                                      │
│  DIRECTORY-BASED: move user_123 from shard 2 to shard 5            │
│  ────────────────                                                   │
│  Update lookup table + migrate data. Zero-downtime possible.        │
│  Most flexible but requires maintaining the directory.              │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Sharding Strategies Across CXP

| Component | Strategy | Shard Key | Why This Strategy |
|-----------|----------|-----------|------------------|
| **DynamoDB** (within region) | Hash-based (auto) | `eventId_upmId` | Even distribution; avoids hot partition during sneaker launches |
| **DynamoDB** (cross-region) | Geo-based | Route53 latency routing | Low latency for users; full replication between regions |
| **Elasticsearch** | Hash-based (explicit) | `hash(_id) % 5` | Parallel search across 5 shards; scatter-gather |
| **S3 + Athena** | Range-based (time) | `year/month/` folders | Partition pruning skips old data; cheap cold storage |
| **Splunk** | Category + time | `index=` + `earliest/latest` | Two-level pruning: skip wrong indexes AND wrong time buckets |
| **Akamai CDN** | Geo-based | User's geographic location | Nearest PoP serves cached content; lowest latency |

---

## Common Interview Follow-ups

### Q: "Why not shard by just eventId in DynamoDB?"

> "A sneaker launch pushes 10,000+ registrations for a single event in under a minute. If we sharded by `eventId` alone, all those writes would hash to the same partition — exceeding the ~1000 WCU limit and causing throttling. By using `eventId_upmId`, each user-event combination hashes to a different partition, spreading the load evenly. This is the classic hot partition anti-pattern that breaks DynamoDB at scale."

### Q: "How do you handle cross-shard queries?"

> "Depends on the database:
> - **Elasticsearch:** Scatter-gather is built in — every search hits all shards in parallel. Acceptable because shard-level search is O(1) via inverted index.
> - **DynamoDB:** Scan (touches all partitions) is expensive — we avoid it except for rare batch reprocessing. Normal operations are single-key lookups (single partition).
> - **Athena/S3:** Partition pruning skips irrelevant shards. Queries that can't prune scan everything — this is where adding time-based partition filters would help.
> - **Splunk:** `index=` + `earliest/latest` prune aggressively. A query without these filters is effectively a full-table scan."

### Q: "When would you use consistent hashing instead of simple modulo?"

> "When you expect to ADD or REMOVE shards frequently. Simple `hash % N` rehashes ~100% of keys when N changes. Consistent hashing rehashes only ~1/N keys. For DynamoDB, this is handled automatically — partition splits move only the affected range. For a custom sharding layer (like sharding Redis across multiple instances), I'd use consistent hashing from the start to avoid painful migrations later."

### Q: "How do you avoid hot spots in range-based sharding?"

> "Range-based sharding naturally creates hot spots — the current time range gets all writes. Three mitigations:
> 1. **Use S3 or Kafka** where write throughput is unlimited (no partition limit).
> 2. **Add hash sub-sharding** within each range (e.g., `month=04/hash=0-3/`).
> 3. **Accept it** if the hot shard can handle the load (DynamoDB adaptive capacity automatically shifts throughput to hot partitions).
> For our S3 Partner Hub data, the hot spot is acceptable because S3 has unlimited write capacity — no throttling risk."

---
---

# Topic 9: Consistent Hashing

> Distribute data across nodes so adding or removing servers only redistributes K/N keys instead of rehashing everything.

> **Interview Tip:** Mention virtual nodes — "I'd use consistent hashing with 150 virtual nodes per server to ensure even distribution and smooth scaling."

---

## The Problem: Traditional Hashing Breaks on Scale

With simple modulo hashing (`hash(key) % N`), adding or removing a server rehashes **almost every key**.

```
┌──────────────────────────────────────────────────────────────────────┐
│  PROBLEM: Traditional Hash  →  server = hash(key) % N               │
│                                                                      │
│  4 servers: hash(key) % 4                                           │
│                                                                      │
│  Key "user_A" → hash = 14 → 14 % 4 = 2  → Server 2                │
│  Key "user_B" → hash = 27 → 27 % 4 = 3  → Server 3                │
│  Key "user_C" → hash = 11 → 11 % 4 = 3  → Server 3                │
│  Key "user_D" → hash = 20 → 20 % 4 = 0  → Server 0                │
│                                                                      │
│  NOW ADD Server 4 (scale up to 5 servers):                          │
│  hash(key) % 5                                                      │
│                                                                      │
│  Key "user_A" → hash = 14 → 14 % 5 = 4  → Server 4  ← MOVED!     │
│  Key "user_B" → hash = 27 → 27 % 5 = 2  → Server 2  ← MOVED!     │
│  Key "user_C" → hash = 11 → 11 % 5 = 1  → Server 1  ← MOVED!     │
│  Key "user_D" → hash = 20 → 20 % 5 = 0  → Server 0  (stayed)     │
│                                                                      │
│  RESULT: 3 out of 4 keys moved (75%)                                │
│  At scale: ~80% of ALL data must be migrated.                       │
│  With millions of keys → massive network traffic, downtime risk.    │
│                                                                      │
│  Add/remove server = REHASH ALL KEYS                                │
└──────────────────────────────────────────────────────────────────────┘
```

---

## The Solution: Hash Ring

Both servers AND keys are hashed onto a circular ring. Each key maps to the **next server clockwise** on the ring.

```
┌──────────────────────────────────────────────────────────────────────┐
│  SOLUTION: Consistent Hashing — Hash Ring                            │
│                                                                      │
│  Servers and keys mapped to the SAME ring (0 to 2^32).             │
│  Key maps to the next server clockwise.                             │
│  Add/remove server = move only K/N keys.                            │
│                                                                      │
│                         S1 (pos: 90°)                               │
│                        ╱                                            │
│               K1 (80°)•    •K_new (95°)                             │
│                      ╱        ╲                                     │
│                     ╱          ╲                                    │
│                    ╱            ╲                                   │
│                   ╱              ╲                                  │
│   K3 (270°)•────╱    HASH RING    ╲────• S2 (180°)                 │
│                 ╲                  ╱                                │
│                  ╲                ╱                                 │
│                   ╲              ╱                                  │
│                    ╲            ╱                                   │
│                     ╲          ╱                                    │
│                      ╲        ╱                                    │
│               S4 (300°)      ╱                                     │
│                        ╲    ╱                                      │
│                    K2 (200°)•                                       │
│                          S3 (250°)                                  │
│                                                                      │
│  Key mapping (walk clockwise to next server):                       │
│  K1 (80°)  → next server clockwise → S1 (90°)   ✓                 │
│  K2 (200°) → next server clockwise → S3 (250°)  ✓                 │
│  K3 (270°) → next server clockwise → S4 (300°)  ✓                 │
│                                                                      │
│  NOW ADD S5 at position 150°:                                       │
│                                                                      │
│  K1 (80°)  → S1 (90°)   ← unchanged                               │
│  K2 (200°) → S3 (250°)  ← unchanged                               │
│  K3 (270°) → S4 (300°)  ← unchanged                               │
│  Only keys between S1 (90°) and S5 (150°) move to S5.             │
│                                                                      │
│  RESULT: Only ~1/N of keys redistribute (20% with 5 servers)       │
│  vs ~80% with traditional hash(key) % N.                           │
└──────────────────────────────────────────────────────────────────────┘
```

### The Math

```
Traditional hashing:   Add 1 server to N → rehash ~(N-1)/N keys
                       4→5 servers: ~80% keys move
                       10→11 servers: ~91% keys move

Consistent hashing:    Add 1 server to N → rehash ~1/N keys (K/N)
                       4→5 servers: ~20% keys move
                       10→11 servers: ~9% keys move

At 1 billion keys:
  Traditional: 800 million keys to migrate  ← hours of downtime
  Consistent:  200 million keys to migrate  ← manageable
```

---

## Virtual Nodes: Fixing the Uneven Distribution Problem

With only 4 physical servers on the ring, some servers get more hash space than others (uneven load). Virtual nodes fix this.

```
┌──────────────────────────────────────────────────────────────────────┐
│  PROBLEM: 4 servers, uneven ring positions                           │
│                                                                      │
│          S1 (10°)                                                   │
│         ╱╲                                                          │
│        ╱  ╲                                                         │
│       ╱    ╲                                                        │
│  S4 (350°)  S2 (30°)                                               │
│         ╲  ╱                                                        │
│          ╲╱                                                         │
│       S3 (180°)                                                     │
│                                                                      │
│  S1 owns: 10° to 30° = 20° of ring  (5.5%)                        │
│  S2 owns: 30° to 180° = 150° of ring (41.7%)  ← overloaded!      │
│  S3 owns: 180° to 350° = 170° of ring (47.2%) ← overloaded!      │
│  S4 owns: 350° to 10° = 20° of ring  (5.5%)   ← underused        │
│                                                                      │
│  S2 and S3 handle 89% of all data. S1 and S4 handle 11%.          │
│  This defeats the purpose of sharding.                              │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  SOLUTION: Virtual Nodes (vnodes)                                    │
│                                                                      │
│  Each physical server gets MULTIPLE positions on the ring.          │
│  With 150 virtual nodes per server = 600 points on the ring.       │
│                                                                      │
│  Physical Server S1 → virtual positions: S1-a, S1-b, S1-c, ...    │
│  Physical Server S2 → virtual positions: S2-a, S2-b, S2-c, ...    │
│                                                                      │
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐          │
│  │ S1-a │ │ S1-b │ │ S1-c │ │ S2-a │ │ S2-b │ │ S2-c │ ...      │
│  └──────┘ └──────┘ └──────┘ └──────┘ └──────┘ └──────┘          │
│                                                                      │
│  With 150 vnodes per server:                                        │
│  S1 owns: ~25% of ring  (was 5.5%)                                │
│  S2 owns: ~25% of ring  (was 41.7%)                               │
│  S3 owns: ~25% of ring  (was 47.2%)                               │
│  S4 owns: ~25% of ring  (was 5.5%)                                │
│                                                                      │
│  Near-perfect distribution!                                         │
│                                                                      │
│  BONUS: When S3 is REMOVED, its 150 vnodes are scattered across   │
│  the ring. S1, S2, and S4 each absorb ~1/3 of S3's data.          │
│  Without vnodes, ONE neighbor absorbs ALL of S3's data.            │
│                                                                      │
│  More virtual nodes = more even distribution                        │
│  Industry standard: 100-200 vnodes per physical server.            │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Real-World Usage

```
┌──────────────────────────────────────────────────────────────────────┐
│  WHO USES CONSISTENT HASHING                                         │
│                                                                      │
│  ┌────────────────────┬──────────────────────────────────────────┐  │
│  │ Amazon DynamoDB     │ Partition routing — maps partition keys  │  │
│  │                     │ to storage nodes across AZs             │  │
│  ├────────────────────┼──────────────────────────────────────────┤  │
│  │ Apache Cassandra    │ Data distribution — token ring assigns  │  │
│  │                     │ data to nodes with vnodes               │  │
│  ├────────────────────┼──────────────────────────────────────────┤  │
│  │ Discord             │ Message routing — routes channels to    │  │
│  │                     │ server instances                        │  │
│  ├────────────────────┼──────────────────────────────────────────┤  │
│  │ Akamai CDN          │ Content distribution — maps URLs to     │  │
│  │                     │ edge servers                            │  │
│  ├────────────────────┼──────────────────────────────────────────┤  │
│  │ Memcached           │ Client-side consistent hashing across   │  │
│  │                     │ cache servers                           │  │
│  ├────────────────────┼──────────────────────────────────────────┤  │
│  │ Redis Cluster       │ Hash slots (16384 slots distributed     │  │
│  │                     │ across nodes — a variant of the idea)   │  │
│  └────────────────────┴──────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Consistent Hashing In My CXP Projects

### Where It's Used (Under the Hood)

Our platform doesn't implement consistent hashing directly — it's **built into the managed services** we use. But understanding it explains WHY these services scale so well.

```
┌──────────────────────────────────────────────────────────────────────────┐
│              CXP PLATFORM — CONSISTENT HASHING MAP                        │
│                                                                          │
│  ┌──────────────┐  DynamoDB uses consistent hashing internally to       │
│  │  DynamoDB     │  map partition keys to storage partitions.            │
│  │  (Managed)    │  When partitions split under load, only the keys     │
│  │               │  in the split range move — not all keys.             │
│  └──────────────┘                                                        │
│                                                                          │
│  ┌──────────────┐  Elasticsearch uses hash-based shard routing          │
│  │ Elasticsearch │  (hash(_id) % num_shards). This is simple modulo,   │
│  │  (5 shards)   │  NOT consistent hashing. That's why you CAN'T       │
│  │               │  change shard count without reindexing.              │
│  └──────────────┘                                                        │
│                                                                          │
│  ┌──────────────┐  Akamai CDN uses consistent hashing to route         │
│  │  Akamai CDN   │  URLs to edge servers. Adding a new PoP only        │
│  │  (250+ PoPs)  │  redistributes a fraction of cached content.        │
│  └──────────────┘                                                        │
│                                                                          │
│  ┌──────────────┐  Redis Cluster uses 16384 hash slots distributed     │
│  │  Redis        │  across nodes. Moving a node = migrating its hash   │
│  │  ElastiCache  │  slot range, not rehashing all keys.                │
│  └──────────────┘                                                        │
│                                                                          │
│  ┌──────────────┐  Kafka partitions use hash(key) % num_partitions.    │
│  │  NSPv2/Kafka  │  Simple modulo — changing partition count requires   │
│  │  (Streaming)  │  rebalancing consumers.                              │
│  └──────────────┘                                                        │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: DynamoDB — Consistent Hashing Behind Partition Splits

**Service:** `cxp-event-registration`
**Table:** `unprocessed-registration-requests`

```
┌──────────────────────────────────────────────────────────────────────┐
│  DynamoDB Partition Split — Consistent Hashing in Action             │
│                                                                      │
│  BEFORE (low traffic): 1 partition owns the full hash range         │
│  ┌──────────────────────────────────────────────────────┐          │
│  │  Partition 0:  hash range 0x00 ─────────────── 0xFF  │          │
│  │  All 500 items live here. 100 WCU.                   │          │
│  └──────────────────────────────────────────────────────┘          │
│                                                                      │
│  SNEAKER LAUNCH: 10,000 writes/sec hits the table                   │
│  DynamoDB detects: Partition 0 exceeds 1000 WCU → SPLIT            │
│                                                                      │
│  AFTER (auto-split): 2 partitions, each owns HALF the range        │
│  ┌─────────────────────────┐  ┌─────────────────────────┐         │
│  │  Partition 0:            │  │  Partition 1:            │         │
│  │  hash range 0x00 ── 0x7F│  │  hash range 0x80 ── 0xFF│         │
│  │  ~250 items moved here   │  │  ~250 items stay here    │         │
│  └─────────────────────────┘  └─────────────────────────┘         │
│                                                                      │
│  WHICH KEYS MOVED?                                                  │
│  Only keys with hash in 0x00-0x7F moved to new Partition 0.        │
│  Keys with hash in 0x80-0xFF stayed in (now) Partition 1.           │
│  ~50% of keys moved — NOT all keys.                                 │
│                                                                      │
│  This is consistent hashing:                                        │
│  - The hash ring was split at the midpoint                          │
│  - Only keys in the affected range redistributed                    │
│  - Application sees ZERO disruption (transparent to our code)       │
│                                                                      │
│  FURTHER SPLITS:                                                    │
│  10,000+ WCU sustained → DynamoDB splits again: 2→4→8 partitions  │
│  Each split only moves ~50% of ONE partition's keys.               │
│  Total keys moved across all splits = fraction of total.           │
│                                                                      │
│  OUR CODE NEVER CHANGES:                                            │
│  dynamoDbTable.putItem(request);  ← same API call                  │
│  DynamoDB SDK handles routing to the correct partition.             │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "DynamoDB uses consistent hashing internally for partition management. When our registration table gets hit with 10,000 writes/sec during a sneaker launch, DynamoDB auto-splits partitions. The key insight is that only keys in the affected hash range move to the new partition — not all keys. Our application code is completely unaware of splits — the DynamoDB SDK routes each request to the correct partition transparently. If DynamoDB used simple modulo hashing, a partition split would rehash every key and cause downtime."

---

### Example 2: Akamai CDN — Consistent Hashing for Content Routing

**Service:** CDN layer in front of cxp-events

```
┌──────────────────────────────────────────────────────────────────────┐
│  Akamai CDN — Consistent Hashing for URL-to-Server Mapping           │
│                                                                      │
│  URL: nike.com/experiences/event/73067                               │
│  hash(URL) → position on ring → mapped to nearest edge server       │
│                                                                      │
│  HASH RING (simplified):                                            │
│                                                                      │
│          Tokyo PoP                                                   │
│         ╱                                                           │
│   URL-A •     • URL-C                                               │
│        ╱        ╲                                                   │
│  London PoP      NYC PoP                                            │
│        ╲        ╱                                                   │
│   URL-B •     ╱                                                     │
│          ╲  ╱                                                       │
│        Sydney PoP                                                   │
│                                                                      │
│  URL-A → hash → closest to Tokyo PoP → cached there               │
│  URL-B → hash → closest to Sydney PoP → cached there              │
│  URL-C → hash → closest to NYC PoP → cached there                 │
│                                                                      │
│  ADD a new PoP in Singapore:                                        │
│  - Only URLs whose hash falls between Sydney and Singapore move     │
│  - Tokyo, London, NYC PoPs keep their cached content               │
│  - ~1/5 of URLs redistribute (1 of 5 servers added)               │
│                                                                      │
│  WITHOUT consistent hashing:                                        │
│  Adding Singapore PoP → hash(URL) % 5 instead of % 4              │
│  → ~80% of URLs remap to different PoPs                            │
│  → 80% cache MISS on first request → thundering herd to origin     │
│  → Origin (cxp-events) overloaded → potential downtime             │
│                                                                      │
│  WITH consistent hashing:                                           │
│  → ~20% of URLs remap → 20% cache miss → manageable               │
│  → Origin sees small traffic increase → no impact                  │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "Akamai uses consistent hashing to map URLs to edge servers. When Nike adds a new PoP, only ~1/N of cached URLs need to be re-fetched from origin, not ~80%. This is critical because a cache miss thundering herd — where 80% of requests suddenly miss cache and hit our cxp-events origin — could take down the backend. Consistent hashing makes CDN scaling smooth and safe."

---

### Example 3: Elasticsearch — Simple Modulo (NOT Consistent Hashing)

**Service:** `expviewsnikeapp`
**Index:** `pg_eventcard` — 5 primary shards

```
┌──────────────────────────────────────────────────────────────────────┐
│  Elasticsearch — DOES NOT Use Consistent Hashing                     │
│                                                                      │
│  Routing: shard = hash(_id) % num_primary_shards                   │
│                                                                      │
│  This is simple MODULO hashing:                                     │
│  hash("73067") % 5 = 2  → Shard 2                                  │
│                                                                      │
│  WHY THIS MATTERS:                                                  │
│  If we want to change from 5 shards to 10 shards:                  │
│  hash("73067") % 10 = 7  → Shard 7  ← DIFFERENT shard!            │
│                                                                      │
│  EVERY document would need to be reassigned.                        │
│  Elasticsearch requires FULL REINDEX to change shard count.         │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Elasticsearch reindex process:                             │    │
│  │  1. Create new index "pg_eventcard_v2" with 10 shards      │    │
│  │  2. Reindex ALL documents from v1 → v2                     │    │
│  │  3. Switch alias from v1 to v2                              │    │
│  │  4. Delete old index v1                                     │    │
│  │                                                             │    │
│  │  With 1 million events: ~10-30 minutes of reindexing       │    │
│  │  During reindex: both indexes exist (double storage)        │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  IF ES used consistent hashing:                                     │
│  Adding shard 6 would only move ~1/6 of documents.                 │
│  But ES chose simplicity (modulo) over flexibility (consistent).   │
│                                                                      │
│  LESSON: This is why we sized at 5 shards for 3-5 years of growth  │
│  — to avoid the painful reindex operation.                          │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "Elasticsearch does NOT use consistent hashing — it uses simple modulo (`hash(_id) % num_shards`). This means you can't change shard count without reindexing every document. That's why we carefully sized `pg_eventcard` at 5 shards with ~20-40 GB each, planning for 3-5 years of growth. If Elasticsearch used consistent hashing, we could add shards dynamically with only 1/N of documents migrating. This is a key architectural difference between DynamoDB (consistent hashing, elastic scaling) and Elasticsearch (modulo hashing, fixed shards)."

---

### Example 4: Redis Cluster — Hash Slots (Consistent Hashing Variant)

**Service:** `cxp-event-registration` (ElastiCache)

```
┌──────────────────────────────────────────────────────────────────────┐
│  Redis Cluster Hash Slots — A Consistent Hashing Variant             │
│                                                                      │
│  Redis Cluster divides the keyspace into 16,384 hash slots.        │
│  Each node owns a RANGE of slots.                                   │
│                                                                      │
│  slot = CRC16(key) % 16384                                          │
│                                                                      │
│  3 nodes:                                                           │
│  ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐      │
│  │  Node A          │ │  Node B          │ │  Node C          │      │
│  │  Slots 0-5460    │ │  Slots 5461-10922│ │  Slots 10923-16383│    │
│  └─────────────────┘ └─────────────────┘ └─────────────────┘      │
│                                                                      │
│  Key "user123_pairwise_key":                                        │
│  CRC16("user123_pairwise_key") % 16384 = 8234 → Node B             │
│                                                                      │
│  ADD Node D:                                                        │
│  Migrate slots 12000-16383 from Node C → Node D                    │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌────────────┐│
│  │  Node A       │ │  Node B       │ │  Node C       │ │  Node D    ││
│  │  0-5460       │ │  5461-10922   │ │  10923-11999  │ │  12000-    ││
│  │               │ │               │ │               │ │  16383     ││
│  └──────────────┘ └──────────────┘ └──────────────┘ └────────────┘│
│                                                                      │
│  Only keys in slots 12000-16383 moved (~27% of Node C's data).    │
│  Nodes A and B: ZERO disruption.                                   │
│  This is consistent hashing via slot ranges.                       │
│                                                                      │
│  OUR ELASTICACHE SETUP:                                             │
│  We use a replication group (Primary + Replicas), not Redis Cluster │
│  (multi-node sharding). But if we scaled beyond a single primary's │
│  capacity, ElastiCache Cluster Mode would use this hash slot model. │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Consistent Hashing vs Simple Modulo — When to Choose

```
┌──────────────────────────────────────────────────────────────────────┐
│  DECISION: Consistent Hashing vs Simple Modulo                       │
│                                                                      │
│  Use CONSISTENT HASHING when:                                       │
│  ✓ Nodes are added/removed frequently (auto-scaling)                │
│  ✓ Data migration during scaling must be minimal                    │
│  ✓ System can't afford downtime for resharding                     │
│  ✓ Cache systems where miss = expensive origin fetch                │
│  → DynamoDB, Cassandra, CDNs, Memcached, Redis Cluster             │
│                                                                      │
│  Use SIMPLE MODULO when:                                            │
│  ✓ Number of shards is fixed at creation time                       │
│  ✓ Simplicity > flexibility                                        │
│  ✓ Full reindex/rebalance is acceptable (planned maintenance)       │
│  ✓ Shard count changes are rare (yearly, not daily)                │
│  → Elasticsearch, Kafka partitions                                  │
│                                                                      │
│  IN OUR CXP PLATFORM:                                               │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  DynamoDB:      Consistent hashing ✓ (auto-partition split)│    │
│  │  Akamai CDN:    Consistent hashing ✓ (add PoPs smoothly)  │    │
│  │  Redis Cluster: Hash slots ✓ (slot range migration)        │    │
│  │  Elasticsearch: Simple modulo ✗ (fixed shards, reindex)    │    │
│  │  Kafka/NSPv2:   Simple modulo ✗ (fixed partitions)         │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Consistent Hashing Across CXP

| Component | Hashing Type | Scaling Behavior | Impact of Adding Nodes |
|-----------|-------------|-----------------|----------------------|
| **DynamoDB** | Consistent (internal) | Auto-split partitions on load | Only keys in split range move; zero app changes |
| **Akamai CDN** | Consistent (URL→PoP) | Add PoPs globally | Only ~1/N URLs re-cached; no thundering herd |
| **Redis Cluster** | Hash slots (16384) | Migrate slot ranges | Only affected slot range moves; other nodes untouched |
| **Elasticsearch** | Simple modulo | Fixed at index creation | Cannot change; full reindex required |
| **Kafka/NSPv2** | Simple modulo | Fixed partition count | Consumer rebalance; no data migration |

---

## Common Interview Follow-ups

### Q: "How many virtual nodes should you use?"

> "Industry standard is 100-200 virtual nodes per physical server. Fewer vnodes (10-20) can still leave 30-40% load imbalance. At 150 vnodes, distribution is within 5-10% of perfect even. More vnodes means more memory for the ring metadata, but at 150 vnodes × 100 servers = 15,000 ring entries, that's only a few KB — trivial. DynamoDB and Cassandra use vnodes extensively."

### Q: "What happens when a node fails in consistent hashing?"

> "The failed node's hash range is absorbed by its clockwise neighbor. With virtual nodes, this absorption is distributed across ALL remaining nodes (because the failed node had 150 vnodes scattered across the ring), preventing any single node from becoming overloaded. This is exactly what happens during a DynamoDB partition failure — traffic redistributes evenly and the SDKs retry transparently."

### Q: "Why doesn't Elasticsearch use consistent hashing?"

> "Elasticsearch prioritized simplicity and search performance over elastic scaling. With modulo hashing, the coordinating node can compute the target shard in O(1) without maintaining a ring or routing table. The tradeoff is inflexible shard count — but for search workloads where you size the index once and query it millions of times, this is acceptable. DynamoDB made the opposite tradeoff — elastic scaling matters more for a key-value store that auto-scales under variable load."

### Q: "How does consistent hashing relate to the CAP theorem?"

> "Consistent hashing is an implementation detail of HOW data is distributed — it doesn't directly determine CP vs AP. But it enables better availability: when a node fails, consistent hashing ensures minimal disruption (only 1/N keys affected), which makes AP systems more resilient. DynamoDB uses consistent hashing AND is AP across regions (Global Tables). Elasticsearch uses modulo hashing AND is CP per shard. The hashing strategy determines scaling smoothness, not consistency model."

---
---

# Topic 10: Data Partitioning

> Split rows horizontally (sharding) or columns vertically to scale beyond single-node limits.

> **Interview Tip:** Explain when to partition — "Once we exceed 1TB or 10K QPS on a single node, I'd introduce horizontal partitioning by tenant_id."

---

## What Is Data Partitioning?

Splitting large datasets across multiple nodes for **scalability and performance**. There are two fundamentally different approaches: split by **rows** or split by **columns**.

```
┌──────────────────────────────────────────────────────────────────────┐
│                      DATA PARTITIONING                                │
│                                                                      │
│  Split large datasets across multiple nodes                         │
│  for scalability and performance.                                   │
│                                                                      │
│  ┌────────────────────────────┐  ┌──────────────────────────────┐  │
│  │  HORIZONTAL PARTITIONING   │  │  VERTICAL PARTITIONING       │  │
│  │  (Sharding)                │  │                               │  │
│  │                            │  │  Split COLUMNS across tables  │  │
│  │  Split ROWS across         │  │                               │  │
│  │  partitions                │  │  Users                        │  │
│  │                            │  │  ┌──────────────────┐        │  │
│  │  Users Table               │  │  │ id, name, email, │        │  │
│  │  ┌──────────────┐         │  │  │ bio, avatar,     │        │  │
│  │  │ ID: 1-1000   │──▶S1    │  │  │ settings...      │        │  │
│  │  │ ID: 1001-2000│──▶S2    │  │  └────────┬─────────┘        │  │
│  │  │ ID: 2001-3000│──▶S3    │  │           │                   │  │
│  │  └──────────────┘         │  │     ┌─────┴─────┐            │  │
│  │                            │  │     ▼           ▼            │  │
│  │  [+] Unlimited horizontal  │  │  ┌────────┐ ┌──────────┐   │  │
│  │      scale                 │  │  │Users   │ │Users     │   │  │
│  │  [-] Cross-shard queries   │  │  │Core    │ │Profile   │   │  │
│  │      are complex           │  │  │id,name,│ │id,bio,   │   │  │
│  │                            │  │  │email   │ │avatar    │   │  │
│  └────────────────────────────┘  │  └────────┘ └──────────┘   │  │
│                                  │                               │  │
│                                  │  [+] Faster queries (fewer   │  │
│                                  │      columns per read)       │  │
│                                  │  [-] JOINs needed for full   │  │
│                                  │      record                  │  │
│                                  └──────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Horizontal vs Vertical — Side by Side

| Dimension | Horizontal (Sharding) | Vertical Partitioning |
|-----------|----------------------|----------------------|
| **What splits** | Rows — each partition has ALL columns but a SUBSET of rows | Columns — each partition has ALL rows but a SUBSET of columns |
| **Scale** | Unlimited — add more shards | Limited — eventually runs out of columns to split |
| **Query pattern** | Single-partition queries are fast; cross-partition queries are slow | Single-table queries are fast; JOINs across tables are slow |
| **Example** | Users 1-1000 on Shard A, Users 1001-2000 on Shard B | User core (id, name, email) in Table A, User profile (bio, avatar) in Table B |
| **When to use** | Data too large for one node, OR write-heavy | Read queries only need a few columns, OR hot columns vs cold columns |

---

## When to Partition

```
┌──────────────────────────────────────────────────────────────────────┐
│  WHEN TO INTRODUCE PARTITIONING                                      │
│                                                                      │
│  You DON'T need partitioning if:                                    │
│  - Data fits on one node (<500 GB)                                  │
│  - QPS is within single-node capacity (<5K reads, <1K writes)       │
│  - Queries are simple and latency is acceptable                     │
│  → Premature partitioning adds complexity for no benefit            │
│                                                                      │
│  You NEED horizontal partitioning (sharding) when:                  │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ✓ Data exceeds ~1 TB (single node storage limit)          │    │
│  │  ✓ Write QPS exceeds ~10K (single node throughput limit)   │    │
│  │  ✓ Read QPS exceeds capacity even WITH read replicas       │    │
│  │  ✓ Multi-region writes needed (geo-partitioning)           │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  You NEED vertical partitioning when:                               │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ✓ Table has many columns but queries only need a few       │    │
│  │  ✓ Some columns are read-heavy, others write-heavy         │    │
│  │  ✓ Hot data (frequently accessed) mixed with cold data      │    │
│  │  ✓ Large BLOBs (images, JSON) bloating row size            │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Choosing a Partition Key

The partition key determines EVERYTHING about data distribution and query efficiency.

```
┌──────────────────────────────────────────────────────────────────────┐
│  CHOOSING A PARTITION KEY                                            │
│                                                                      │
│  GOOD KEYS:                                                         │
│  ✓ user_id    — high cardinality, even distribution, matches        │
│                  "show me MY data" queries                           │
│  ✓ tenant_id  — natural isolation for multi-tenant SaaS             │
│  ✓ date       — time-series data, natural archival boundary         │
│  ✓ composite  — eventId_upmId (our DynamoDB key)                    │
│                                                                      │
│  BAD KEYS:                                                          │
│  ✗ country    — low cardinality (200 values), skewed distribution   │
│                  (US shard = 10x traffic of Luxembourg shard)        │
│  ✗ boolean    — only 2 values → 2 shards → useless                 │
│  ✗ status     — low cardinality, changes over time (row migration)  │
│  ✗ eventId    — hot partition during sneaker launches               │
│    alone                                                             │
│                                                                      │
│  RULE: Choose a key that distributes evenly AND aligns              │
│  with your access patterns (most queries filter by this key).       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Data Partitioning In My CXP Projects — Real Examples

### The CXP Partitioning Map

Every data store in our platform uses some form of partitioning:

```
┌──────────────────────────────────────────────────────────────────────────┐
│              CXP PLATFORM — PARTITIONING MAP                              │
│                                                                          │
│  HORIZONTAL PARTITIONING (Sharding — split ROWS):                       │
│  ───────────────────────────────────────────────                        │
│  ┌──────────────┐  Partition key: hash(eventId_upmId)                   │
│  │  DynamoDB     │  Each row = one unprocessed registration             │
│  │               │  Rows split across N auto-scaled partitions           │
│  └──────────────┘                                                        │
│                                                                          │
│  ┌──────────────┐  Partition key: hash(_id) % 5 shards                  │
│  │ Elasticsearch │  Each doc = one event card                            │
│  │               │  Docs split across 5 primary shards                   │
│  └──────────────┘                                                        │
│                                                                          │
│  ┌──────────────┐  Partition key: year/month/ folders                    │
│  │  S3 + Athena  │  Each file = one webhook payload                     │
│  │               │  Files split by time-based folder partitions          │
│  └──────────────┘                                                        │
│                                                                          │
│  VERTICAL PARTITIONING (split COLUMNS/CONCERNS):                        │
│  ──────────────────────────────────────────────                         │
│  ┌──────────────┐  Event core data → Eventtia (relational)             │
│  │  CQRS Split   │  Event search data → Elasticsearch (inverted index) │
│  │               │  Same "rows" but different columns per store         │
│  └──────────────┘                                                        │
│                                                                          │
│  ┌──────────────┐  Hot data: Redis (seats, idempotency — sub-ms)       │
│  │  Hot/Cold     │  Warm data: DynamoDB (recent registrations)          │
│  │  Split        │  Cold data: S3 (historical webhooks — cents/GB)     │
│  └──────────────┘                                                        │
│                                                                          │
│  ┌──────────────┐  Email pipeline separated into purpose-specific      │
│  │  Splunk       │  indexes (vertical by log category):                 │
│  │  Indexes      │  dockerlogs* | dockerlogs-gold | app*               │
│  └──────────────┘                                                        │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: DynamoDB — Horizontal Partitioning (Automatic)

**Service:** `cxp-event-registration`
**Table:** `unprocessed-registration-requests`
**Partition key:** `eventId_upmId`

```
┌──────────────────────────────────────────────────────────────────────┐
│  DynamoDB Horizontal Partitioning                                    │
│                                                                      │
│  BEFORE partitioning (small table):                                 │
│  ┌────────────────────────────────────────────────────────┐        │
│  │  Single Partition — all 500 items                       │        │
│  │  "73067_uuid-0001" → { payload... }                     │        │
│  │  "73067_uuid-0002" → { payload... }                     │        │
│  │  "74001_uuid-0003" → { payload... }                     │        │
│  │  ...                                                    │        │
│  └────────────────────────────────────────────────────────┘        │
│                                                                      │
│  AFTER partitioning (sneaker launch — auto-split):                  │
│  ┌───────────────────┐  ┌───────────────────┐                      │
│  │  Partition 0       │  │  Partition 1       │                      │
│  │  hash: 0x00-0x7F   │  │  hash: 0x80-0xFF   │                      │
│  │  ~250 items         │  │  ~250 items         │                      │
│  │  5K WCU capacity   │  │  5K WCU capacity   │                      │
│  └───────────────────┘  └───────────────────┘                      │
│                                                                      │
│  Each partition:                                                    │
│  - Has ALL columns (eventId_upmId, payload, timestamp, etc.)       │
│  - Has a SUBSET of rows (based on hash range)                       │
│  - Operates independently (own throughput capacity)                 │
│  - Replicated across 3 AZs (within-region durability)              │
│                                                                      │
│  WHY HORIZONTAL WORKS HERE:                                        │
│  - Every query is by exact partition key (single-partition access)  │
│  - No cross-partition JOINs needed                                  │
│  - Write-heavy workload (10K+ writes/sec) → needs write scaling    │
│  - Read replicas wouldn't help (Topic 7) → sharding does           │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual Terraform:**

```hcl
# Partition key = "eventId_upmId" (composite string)
# billing_mode = PAY_PER_REQUEST → auto-partitioning on demand
resource "aws_dynamodb_table" "unprocessed_registration_requests" {
  hash_key     = var.partition_key    # "eventId_upmId"
  billing_mode = "PAY_PER_REQUEST"
}
```

**From the Java model — partition key is the composite of event + user:**

```java
@DynamoDbBean
public class UnprocessedRegistrationRequest {
    @DynamoDbPartitionKey
    public String getEventId_upmId() { return eventId_upmId; }
    // "73067_uuid-1234" → hashed → routed to correct partition
}
```

---

### Example 2: Elasticsearch — Horizontal Partitioning (Fixed Shards)

**Service:** `expviewsnikeapp`
**Index:** `pg_eventcard` — 5 primary shards

```
┌──────────────────────────────────────────────────────────────────────┐
│  Elasticsearch Horizontal Partitioning                               │
│                                                                      │
│  Original data: 100,000 event documents                             │
│                                                                      │
│  ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐
│  │  Shard 0   │ │  Shard 1   │ │  Shard 2   │ │  Shard 3   │ │  Shard 4   │
│  │  ~20K docs │ │  ~20K docs │ │  ~20K docs │ │  ~20K docs │ │  ~20K docs │
│  │  ALL fields│ │  ALL fields│ │  ALL fields│ │  ALL fields│ │  ALL fields│
│  └───────────┘ └───────────┘ └───────────┘ └───────────┘ └───────────┘
│                                                                      │
│  Routing: hash("73067") % 5 = 2 → Shard 2                          │
│                                                                      │
│  Each shard has ALL columns (name, date, location, geo_point...)    │
│  but only ~20% of the ROWS (documents).                             │
│  Search queries scatter across all 5 shards in parallel.            │
│                                                                      │
│  But ES ALSO does a form of vertical partitioning internally:       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  _source (full document) — stored together                  │    │
│  │  Inverted index (text fields) — stored separately           │    │
│  │  Doc values (numeric/keyword) — columnar storage            │    │
│  │  Stored fields — compressed, on-disk                        │    │
│  │                                                              │    │
│  │  Search uses inverted index ONLY (doesn't read _source).    │    │
│  │  This is vertical partitioning at the storage engine level. │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 3: CQRS — Vertical Partitioning by Access Pattern

**Services:** Eventtia (writes) + Elasticsearch (reads) + Redis (cache)

This is the most impactful vertical partitioning in our platform: the SAME entity (an event) is split across **different stores based on which columns each access pattern needs**.

```
┌──────────────────────────────────────────────────────────────────────┐
│  VERTICAL PARTITIONING — CQRS in CXP                                 │
│                                                                      │
│  Original "Event" entity (all columns):                             │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  id, name, description, date_start, date_end, location,    │    │
│  │  geo_point, category, language, marketplace, address_line1, │    │
│  │  address_line2, max_capacity, current_seats, ticket_types,  │    │
│  │  activities[], attendees[], registration_status, streaming_  │    │
│  │  url, is_featured, is_virtual, organizer, created_at,       │    │
│  │  updated_at, eventtia_config, template_variables...          │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  Split by access pattern:                                           │
│                                                                      │
│  ┌────────────────────┐  WRITE-OPTIMIZED (Eventtia)                │
│  │  ALL columns        │  - ACID transactions for seat management   │
│  │  (source of truth)  │  - Relational JOINs (event→activities→     │
│  │                     │    tickets→attendees)                       │
│  │  Access: ~100 writes│  - Complex business logic                  │
│  │  per day            │                                            │
│  └────────────────────┘                                             │
│         │ async index                                                │
│         ▼                                                            │
│  ┌────────────────────┐  SEARCH-OPTIMIZED (Elasticsearch)          │
│  │  Search columns:    │  - Inverted index for full-text search     │
│  │  name, description, │  - geo_point for distance queries          │
│  │  date, location,    │  - BKD tree for date range filtering       │
│  │  geo_point, category│                                            │
│  │  language, is_feat  │  Access: ~10,000 searches per day         │
│  │                     │                                            │
│  │  NO: attendees[],   │  Doesn't store write-heavy columns.       │
│  │  seat counts, config│  Smaller docs = faster search.             │
│  └────────────────────┘                                             │
│         │                                                            │
│         ▼                                                            │
│  ┌────────────────────┐  CACHE-OPTIMIZED (Redis)                   │
│  │  Hot columns only:  │  - Sub-ms reads for event pages            │
│  │  seats, event_key,  │  - TTL auto-expiry                         │
│  │  registration status│                                            │
│  │                     │  Access: ~50,000 reads per day             │
│  │  NO: description,   │                                            │
│  │  attendees[], config│  Only the columns needed for quick         │
│  │                     │  page load and registration checks.        │
│  └────────────────────┘                                             │
│                                                                      │
│  RESULT: Same event entity, 3 stores, different columns per store.  │
│  Each store is optimized for its access pattern.                    │
│  This is vertical partitioning at the architecture level.           │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "Our event data is vertically partitioned across three stores. Eventtia holds all columns and handles transactional writes (seat decrements, registration). Elasticsearch holds only the search-relevant columns (name, description, geo_point, dates) — excluding bulky fields like attendee lists and config. Redis caches only the hottest columns (seat counts, registration status) for sub-ms reads. This vertical split means search queries scan smaller documents (faster), cache reads transfer less data (cheaper), and the write store isn't slowed down by search indexing overhead."

---

### Example 4: Hot/Cold Data Split — Vertical Partitioning by Temperature

**Services:** Redis (hot) → DynamoDB (warm) → S3 (cold)

```
┌──────────────────────────────────────────────────────────────────────┐
│  HOT / WARM / COLD DATA PARTITIONING                                 │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │  HOT (Redis)            WARM (DynamoDB)       COLD (S3+Athena)  ││
│  │  ┌──────────┐          ┌──────────┐          ┌──────────┐      ││
│  │  │ Sub-ms   │          │ Single-  │          │ Seconds  │      ││
│  │  │ reads    │          │ digit ms │          │ to query │      ││
│  │  │          │          │ reads    │          │          │      ││
│  │  │ Current  │          │ Recent   │          │ All-time │      ││
│  │  │ session  │          │ failed   │          │ webhook  │      ││
│  │  │ & cache  │          │ registr- │          │ history  │      ││
│  │  │ data     │          │ ations   │          │          │      ││
│  │  │          │          │          │          │          │      ││
│  │  │ TTL:     │          │ TTL:     │          │ Retained:│      ││
│  │  │ 1-60 min │          │ Days/    │          │ Years    │      ││
│  │  │          │          │ Weeks    │          │          │      ││
│  │  │ Cost:    │          │ Cost:    │          │ Cost:    │      ││
│  │  │ $$$/GB   │          │ $$/GB    │          │ ¢/GB     │      ││
│  │  └──────────┘          └──────────┘          └──────────┘      ││
│  │                                                                  ││
│  │  ◄── Fastest, most expensive ──── Cheapest, slowest ──────────▶││
│  └─────────────────────────────────────────────────────────────────┘│
│                                                                      │
│  DATA LIFECYCLE:                                                    │
│  1. User registers → Redis SET (hot, 60 min TTL)                   │
│  2. Registration fails → DynamoDB PUT (warm, days until reprocess) │
│  3. Eventtia webhook → S3 (cold, retained forever for audit)       │
│                                                                      │
│  Each tier stores the SAME kind of data (registrations) but at      │
│  different lifecycle stages with different access patterns.         │
│  This is vertical partitioning by data TEMPERATURE.                │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "We vertically partition by data temperature. Hot data (current session, idempotency) lives in Redis at $$$/GB but sub-ms latency with auto-TTL expiry. Warm data (unprocessed registrations pending retry) lives in DynamoDB at $$/GB with single-digit ms reads. Cold data (all-time webhook history) lives in S3 at cents/GB, queryable via Athena on demand. The same registration data flows through all three tiers during its lifecycle. Each tier is cost-optimized for its access frequency."

---

### Example 5: Splunk — Vertical Partitioning by Log Category

**Service:** All CXP services → centralized Splunk

```
┌──────────────────────────────────────────────────────────────────────┐
│  Splunk Vertical Partitioning — Indexes by Category                  │
│                                                                      │
│  ALL logs could go to one giant index. Instead, they're split       │
│  vertically by category (like splitting columns into tables):       │
│                                                                      │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐ │
│  │  dockerlogs*      │  │  dockerlogs-gold │  │  app*            │ │
│  │                   │  │                  │  │                  │ │
│  │  General container│  │  Production-only │  │  Application     │ │
│  │  logs from all    │  │  email delivery  │  │  service logs    │ │
│  │  services         │  │  and rendering   │  │  (Rise GTS,     │ │
│  │                   │  │  logs            │  │   NCP Ingest)    │ │
│  │  High volume,     │  │                  │  │                  │ │
│  │  noisy            │  │  Lower volume,   │  │  Structured,     │ │
│  │                   │  │  high signal     │  │  business logic  │ │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘ │
│                                                                      │
│  Within each index: HORIZONTAL partitioning by time buckets:        │
│  ┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐              │
│  │  Hot  │ │ Warm  │ │ Warm  │ │ Cold  │ │Frozen │              │
│  │ today │ │ -1d   │ │ -7d   │ │ -30d  │ │ -90d  │              │
│  │ (SSD) │ │ (SSD) │ │ (HDD) │ │ (HDD) │ │ (S3)  │              │
│  └───────┘ └───────┘ └───────┘ └───────┘ └───────┘              │
│                                                                      │
│  TWO-DIMENSIONAL PARTITIONING:                                      │
│  Vertical = by category (which index)                               │
│  Horizontal = by time (which bucket within that index)              │
│  Query: index=dockerlogs-gold earliest=-7d                          │
│  → Prunes vertically (skip dockerlogs*, app*)                      │
│  → Prunes horizontally (skip buckets older than 7 days)            │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 6: S3 + Athena — Time-Based Horizontal Partitioning

**Service:** Partner Hub webhook data

```
┌──────────────────────────────────────────────────────────────────────┐
│  S3 Horizontal Partitioning — Hive-Style Time Ranges                │
│                                                                      │
│  s3://partnerhub-data/                                              │
│  ├── year=2025/month=01/ ── 50,000 files ── rarely queried         │
│  ├── year=2025/month=02/ ── 48,000 files                           │
│  ├── ...                                                            │
│  ├── year=2026/month=03/ ── 62,000 files                           │
│  └── year=2026/month=04/ ── 15,000 files ── hot partition          │
│                                                                      │
│  ALL files have the SAME columns:                                   │
│  { attendee.upm_id, event.id, event.name, action, event_date_ms } │
│                                                                      │
│  But each FOLDER has a SUBSET of rows (time range).                │
│  This is horizontal partitioning by date range.                     │
│                                                                      │
│  Athena query with partition pruning:                               │
│  WHERE year='2026' AND month='04' AND event.id = 73067             │
│  → Scans ONLY the April 2026 folder                                │
│  → Skips all 15 other month-folders                                │
│  → ~94% cost reduction vs full scan                                 │
│                                                                      │
│  CURRENT STATE in our code:                                         │
│  Our queries DON'T include year/month filters yet →                │
│  scanning full table. OPTIMIZATION OPPORTUNITY.                     │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Horizontal vs Vertical: Decision Framework

```
┌──────────────────────────────────────────────────────────────────────┐
│  WHEN TO USE EACH                                                    │
│                                                                      │
│  HORIZONTAL PARTITIONING (split rows):                              │
│  │                                                                  │
│  ├── Data > 1 TB on single node?          → Horizontal             │
│  ├── Write QPS > 10K?                     → Horizontal             │
│  ├── Queries always filter by one key?    → Horizontal (by that key)│
│  └── Multi-region deployment needed?      → Horizontal (geo-based) │
│                                                                      │
│  VERTICAL PARTITIONING (split columns):                             │
│  │                                                                  │
│  ├── Reads need 5 of 50 columns?          → Vertical (split table) │
│  ├── Some columns are hot, others cold?   → Vertical (hot/cold)    │
│  ├── Reads and writes need different       → Vertical (CQRS)       │
│  │   optimizations?                                                 │
│  └── Large BLOBs bloating table?          → Vertical (BLOBs to S3) │
│                                                                      │
│  BOTH (common in production systems):                               │
│  │                                                                  │
│  ├── CXP: Vertical (CQRS) + Horizontal (DynamoDB auto-shard)      │
│  ├── CXP: Vertical (hot/cold) + Horizontal (S3 time partitions)   │
│  └── Splunk: Vertical (by index) + Horizontal (by time bucket)    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Partitioning Across CXP

| Component | Partitioning Type | Strategy | What Splits |
|-----------|------------------|----------|-------------|
| **DynamoDB** | Horizontal | Hash(eventId_upmId) → auto-split partitions | Rows split across partitions by hash range |
| **Elasticsearch** | Horizontal | hash(_id) % 5 primary shards | Documents split across 5 shards |
| **S3 + Athena** | Horizontal | year/month/ folder partitions | Files split by time range |
| **Splunk** | Horizontal + Vertical | Time buckets × index category | Rows by time, columns by log type |
| **CQRS (Eventtia→ES→Redis)** | Vertical | Access pattern split | Columns split: all (write), search (read), hot (cache) |
| **Hot/Cold tiers** | Vertical | Data temperature | Same data at different lifecycle stages |
| **DynamoDB Global Tables** | Horizontal (geo) | us-east-1 / us-west-2 | Full data replicated per region, routed by geo |

---

## Common Interview Follow-ups

### Q: "How is partitioning different from sharding?"

> "Sharding IS horizontal partitioning — the terms are interchangeable when splitting rows across multiple databases. 'Partitioning' is broader — it includes vertical partitioning (splitting columns) and single-database partitioning (like PostgreSQL table partitions on the same server). Sharding specifically implies data on DIFFERENT servers. In our DynamoDB table, each partition is on a different storage node — that's sharding. In Athena, S3 folder partitions are on the same service but different storage prefixes — that's partitioning without sharding."

### Q: "How do you handle queries that need data from multiple partitions?"

> "Depends on the system:
> - **DynamoDB:** Scan touches all partitions — we avoid this except for rare batch reprocessing. Normal operations are single-key (single partition).
> - **Elasticsearch:** Scatter-gather is built in — every search hits all shards in parallel. Coordinator merges results.
> - **Athena/S3:** WHERE clause with partition columns prunes irrelevant partitions. Without partition filters, full scan is expensive.
> - **CQRS (vertical):** If a query needs both search data AND seat counts, the application makes two calls — one to Elasticsearch, one to Redis — and merges in the application layer."

### Q: "When would you choose vertical over horizontal partitioning?"

> "When the problem is column-level, not row-level. Our CQRS split is vertical — Elasticsearch doesn't need attendee lists or event config, so storing them there wastes storage and slows search. Our hot/cold split is vertical — Redis doesn't need historical data, so keeping it there wastes expensive memory. Horizontal partitioning solves 'too many rows'; vertical partitioning solves 'too many columns per query' or 'different access patterns on the same entity.'"

### Q: "Can you combine both in one system?"

> "Yes — and we do. Our Splunk logging is two-dimensional: vertical partitioning by index category (dockerlogs-gold vs app*) and horizontal partitioning by time bucket within each index. Our overall platform combines vertical CQRS (Eventtia→ES→Redis) with horizontal auto-sharding (DynamoDB partitions). The most mature data architectures layer both types — vertical to separate concerns, horizontal to scale within each concern."
