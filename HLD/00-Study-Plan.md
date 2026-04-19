# HLD Interview Preparation — Study Plan

## What You Have

| File | Topics | Lines | Size |
|------|--------|-------|------|
| `01-CAP-Theorem.md` | 1-10 (Database & Distributed Systems) | 4,778 | 351 KB |
| `02-Caching-and-Performance.md` | 11-19 (Caching, CDN, LB, Scaling, Resilience) | 4,471 | 330 KB |
| `03-Messaging-and-Communication.md` | 20-25 (Messaging, APIs, Transactions, Consensus) | 2,597 | 199 KB |
| `04-Reliability-and-Resilience.md` | 26-30 (Idempotency, Discovery, Gateway, Microservices, EDA) | 2,417 | 189 KB |
| `05-Security-and-Auth.md` | 31-38 (Auth, TLS, Encryption, Observability, Tracing, Health, Failover, DR) | 3,190 | 261 KB |
| `06-DevOps-and-Infrastructure.md` | 39-50 (Deploy, K8s, DNS, Proxy, Bloom, Geo, IDs, Batch/Stream, Lake, Estimation, SLO, Framework) | 4,382 | 353 KB |
| **TOTAL** | **50 topics** | **21,835 lines** | **1.7 MB** |

Equivalent to a **~350-page technical book** — entirely personalized to your Nike CXP projects.

---

## Recommended Timeframe: 3-4 Weeks (2-3 hours/day)

---

### Week 1: Foundation Layer (Topics 1-19)

| Day | Topics | File | Focus | Time |
|-----|--------|------|-------|------|
| Day 1 | 1-3 (CAP, SQL/NoSQL, ACID/BASE) | `01-CAP-Theorem.md` | Core theory + CXP examples. These come up in EVERY interview. | 2h |
| Day 2 | 4-5 (DB Selection, Indexing) | `01-CAP-Theorem.md` | Practice explaining "why DynamoDB not PostgreSQL" and "why `eventId_upmId` as partition key" | 2h |
| Day 3 | 6-8 (Replication, Read Replicas, Sharding) | `01-CAP-Theorem.md` | Focus on Redis single-leader, DynamoDB multi-leader, ES shard routing | 2h |
| Day 4 | 9-10 (Consistent Hashing, Partitioning) | `01-CAP-Theorem.md` | DynamoDB auto-split + CQRS vertical partitioning | 2h |
| Day 5 | 11-13 (Caching Patterns, Eviction, CDN) | `02-Caching.md` | Most-asked topic. Practice: cache-aside (pairwise), write-behind (idempotency), Akamai TTL strategy | 3h |
| Day 6 | 14-16 (Load Balancing, Scaling, Rate Limiting) | `02-Caching.md` | ALB path routing, stateless auto-scaling 2→8, Redis bot protection | 2h |
| Day 7 | 17-19 (Circuit Breaker, Connection Pool, Stateless) | `02-Caching.md` | Review + practice explaining try-catch fallbacks as circuit breaker behavior | 2h |

**Week 1 Goal:** You can answer ANY database, caching, or scaling question using CXP examples.

---

### Week 2: Communication & Reliability Layer (Topics 20-30)

| Day | Topics | File | Focus | Time |
|-----|--------|------|-------|------|
| Day 8 | 20-21 (Queue vs Stream, Sync/Async) | `03-Messaging.md` | SQS vs Kafka in your pipeline. The registration hybrid (sync user, async email) | 2h |
| Day 9 | 22-23 (REST/GraphQL/gRPC, Real-Time) | `03-Messaging.md` | Why 100% REST. Content negotiation (`vnd.nike.*`). No WebSocket and why | 2h |
| Day 10 | 24-25 (Distributed Transactions, Consensus) | `03-Messaging.md` | Saga choreography (6-step registration). Raft/Paxos in DynamoDB/Kafka | 2h |
| Day 11 | 26-27 (Idempotency, Service Discovery) | `04-Reliability.md` | 6 layers of idempotency. Route53 + ALB + K8s DNS discovery | 3h |
| Day 12 | 28-29 (API Gateway, Monolith vs Microservices) | `04-Reliability.md` | Distributed gateway (Akamai+ALB+in-service auth). email-drop-recovery as justified monolith | 2h |
| Day 13 | 30 (Event-Driven Architecture) | `04-Reliability.md` | Capstone: CQRS + Event Sourcing + Fan-out. How all 30 topics connect | 2h |
| Day 14 | **REVIEW WEEK 1+2** | All files | Practice explaining each topic in 2 minutes. Record yourself. | 3h |

**Week 2 Goal:** You can design a full microservice architecture with messaging, explain tradeoffs, and handle distributed system questions.

---

### Week 3: Security, Observability & Infrastructure (Topics 31-45)

| Day | Topics | File | Focus | Time |
|-----|--------|------|-------|------|
| Day 15 | 31-33 (Auth, TLS, Encryption) | `05-Security.md` | JWT dual model (consumer vs OSCAR). Two-hop TLS. KMS envelope encryption | 2h |
| Day 16 | 34-36 (Observability, Tracing, Health Checks) | `05-Security.md` | Three pillars. Investigation tab as manual trace. Three health check levels | 2h |
| Day 17 | 37-38 (Failover, Disaster Recovery) | `05-Security.md` | Active-active multi-region. RPO ~1s, RTO ~60s. Error budget | 2h |
| Day 18 | 39-40 (Deployment, Containers/K8s) | `06-DevOps.md` | Rolling update + feature flags. NPE YAML → K8s primitives | 2h |
| Day 19 | 41-43 (DNS, Proxies, Bloom Filters) | `06-DevOps.md` | Route53 naming convention. Two-layer reverse proxy. Bloom filters in DynamoDB/ES | 2h |
| Day 20 | 44-45 (Geohash, UUID/IDs) | `06-DevOps.md` | ES geo_point. UUID for users, composite key for idempotency, auto-increment security risk | 2h |
| Day 21 | **REVIEW WEEK 3** | `05-06` files | Practice security + infra questions. "How do you secure this?" "How do you deploy?" | 3h |

**Week 3 Goal:** You can handle security, observability, and infrastructure questions confidently.

---

### Week 4: Mastery & Mock Interviews (Topics 46-50 + Practice)

| Day | Focus | Time |
|-----|-------|------|
| Day 22 | Topics 46-49 (Batch/Stream, Data Lake, Estimation, SLO) | 2h |
| Day 23 | Topic 50 (Interview Framework) — practice the 6-step walkthrough with CXP as your example | 2h |
| Day 24 | **Mock Interview 1:** "Design an event registration system" — use the framework, hit 15+ topics naturally | 3h |
| Day 25 | **Mock Interview 2:** "Design a notification/email delivery system" — different angle, same CXP knowledge | 3h |
| Day 26 | **Weak spots:** Re-read topics you stumbled on during mocks | 2h |
| Day 27 | **Mock Interview 3:** Generic system design ("Design Uber/Twitter/URL shortener") — apply CXP patterns to new problems | 3h |
| Day 28 | **Final review:** Read ONLY the "Killer Interview Line" from each topic (50 one-liners) | 2h |

**Week 4 Goal:** You can execute a full 45-minute system design interview confidently, naturally weaving in 15-20 topics.

---

## How to Study Each Topic (The Method)

For each topic, spend 20-30 minutes:

### Step 1: Read the theory section (5 min)
Understand the concept, not memorize. Focus on the ASCII diagrams and comparison tables.

### Step 2: Read the CXP example (10 min)
This is YOUR story. You lived this code. Connect every concept to your real project:
- "In my project, we used DynamoDB because..."
- "Our email pipeline is a real Saga choreography..."
- "We chose AP over CP, which caused the 2-5% email drop rate..."

### Step 3: Practice the "Killer Interview Line" OUT LOUD (5 min)
Say it to a mirror or record on your phone. Should be 30-60 seconds, natural, not rehearsed. Every topic has one at the end — look for the blockquote starting with "Our platform..." or "We use..."

### Step 4: Read the follow-up Q&A (5 min)
These are the EXACT follow-ups interviewers ask. Have 2-3 sentence answers ready. They appear at the end of every topic as "Common Interview Follow-ups."

### Step 5: Connect to other topics (5 min)
Interviewers love when you connect concepts:
- "This relates to our CAP choice because..."
- "This is why we needed the recovery dashboard from Topic 34..."
- "The idempotency from Topic 26 is what makes this saga safe..."

---

## Priority Tiers (If You Have Less Time)

### 2 Weeks Available — Focus on TOP 20 topics

**Must-know (asked in 90% of interviews):**

| # | Topic | Why Critical |
|---|-------|-------------|
| 1 | CAP Theorem | Foundation for every database discussion |
| 2 | SQL vs NoSQL | "Why did you choose this database?" |
| 4 | Database Selection | Polyglot persistence explanation |
| 5 | Database Indexing | Query optimization discussion |
| 8 | Sharding | "How do you scale writes?" |
| 11 | Caching Patterns | Most common optimization topic |
| 13 | CDN | "How do you reduce latency globally?" |
| 14 | Load Balancing | Every architecture has one |
| 15 | Scaling | "How would you handle 10x traffic?" |
| 20 | Queue vs Stream | Async communication always asked |
| 21 | Sync vs Async | "Why async for email but sync for registration?" |
| 22 | REST vs GraphQL vs gRPC | API design discussion |
| 26 | Idempotency | Shows distributed systems maturity |
| 29 | Monolith vs Microservices | Architecture philosophy |
| 31 | Authentication | Security is always asked |
| 37 | Failover | "What if X goes down?" |
| 48 | Estimation | Asked in first 5 minutes |
| 50 | Interview Framework | Structure your entire answer |

**High-value (asked in 50%+ of interviews):**

3, 6, 7, 16, 17, 24, 28, 30, 34, 39, 49

### 1 Week Available — Focus on TOP 10 topics

| Priority | Topic | Why It's Essential |
|----------|-------|-------------------|
| 1 | **Topic 50** (Framework) | Structure your ENTIRE answer |
| 2 | **Topic 48** (Estimation) | Asked in first 5 minutes of every interview |
| 3 | **Topics 1-2** (CAP, SQL/NoSQL) | Foundation for all database discussions |
| 4 | **Topic 11** (Caching) | Most common optimization question |
| 5 | **Topic 29** (Microservices) | Architecture discussion starter |
| 6 | **Topic 20** (Queue vs Stream) | Async communication is always asked |
| 7 | **Topic 15** (Scaling) | "How would you scale this?" |
| 8 | **Topic 26** (Idempotency) | Shows distributed systems maturity |
| 9 | **Topic 37** (Failover) | "What if X goes down?" |
| 10 | **Topic 31** (Auth) | Security is always asked |

### 3 Days Available — Emergency Prep

| Day | What to Do | Time |
|-----|-----------|------|
| Day 1 | Read Topic 50 (Framework) + Topic 48 (Estimation). Draw CXP architecture from memory. | 3h |
| Day 2 | Read Topics 1, 2, 11, 15, 20, 29. Practice the "Killer Interview Line" for each OUT LOUD. | 3h |
| Day 3 | Mock interview: "Design an event registration system." Time yourself 45 minutes. Note weak spots. Re-read those topics. | 3h |

---

## Practice Techniques

### Technique 1: The 2-Minute Drill
Pick any topic. Explain it in 2 minutes using YOUR CXP project. Time yourself. If you can't explain it in 2 minutes, you don't know it well enough.

### Technique 2: The "Why" Chain
For every architecture decision, go 3-4 levels deep:
- "Why DynamoDB?" → "Because key-value access pattern"
- "Why not PostgreSQL?" → "No JOINs needed, auto-scales for sneaker launches"
- "What's the tradeoff?" → "No cross-item transactions, eventual consistency cross-region"
- "How do you handle that?" → "Idempotent operations + recovery dashboard"

### Technique 3: The Whiteboard Walk
Draw the CXP architecture from memory on paper:
1. Draw: Akamai → ALB → 4 services → databases
2. Label every arrow with the protocol (REST, Kafka, SQS)
3. Label every box with the database choice and WHY
4. Add the email pipeline (Kafka → Rise GTS → NCP → CRS → SendGrid)
5. If you can draw it in 5 minutes from memory, you're ready

### Technique 4: Mock with a Friend
Have someone ask: "Design an event registration system for Nike."
- Practice the full 45-minute interview using the 6-step framework from Topic 50
- They should interrupt with: "Why not PostgreSQL?" / "What if Redis goes down?" / "How do you handle duplicates?"
- After: identify which topics you struggled with, re-read those

### Technique 5: The Tradeoff Matrix
For any design decision, always state:
- What you CHOSE
- What you REJECTED
- WHY (the tradeoff)
- What you LOSE (be honest)

Example: "We chose AP over CP for registration. This means the email pipeline is eventually consistent — 2-5% of emails drop because of the MemberHub race condition. We compensate with the recovery dashboard. The alternative (CP = synchronous pipeline) would mean 30-second registration times and 20% user abandonment."

---

## The Day Before the Interview

1. **Re-read Topic 50** (Interview Framework) — internalize the 6 steps
2. **Re-read your 50 "Killer Interview Lines"** — one per topic, 30 seconds each
3. **Draw the CXP architecture once** from memory — verify you can draw it cleanly
4. **Review your estimation numbers** — CXP: 167 QPS peak, 7 GB/year S3, ~220ms registration latency
5. **Sleep well** — system design interviews test thinking, not memorization

---

## Quick Reference: Topic-to-Question Map

When the interviewer asks... use these topics:

| Interviewer Question | Topics to Use |
|---------------------|--------------|
| "What database would you use?" | 1-5 (CAP, SQL/NoSQL, ACID/BASE, DB Selection, Indexing) |
| "How would you scale this?" | 7-10, 15-16 (Replicas, Sharding, Scaling, Rate Limiting) |
| "How do you handle caching?" | 11-13 (Caching Patterns, Eviction, CDN) |
| "How do services communicate?" | 20-23 (Queues/Streams, Sync/Async, REST/GraphQL) |
| "How do you handle failures?" | 17, 24, 26, 37 (Circuit Breaker, Sagas, Idempotency, Failover) |
| "How do you deploy and monitor?" | 34-36, 39-40, 49 (Observability, Health Checks, Deployment, K8s, SLO) |
| "How do you secure this?" | 31-33 (Auth, TLS, Encryption) |
| "Estimate the numbers." | 48 (Back-of-Envelope Calculations) |
| "What if X goes down?" | 17, 37-38 (Circuit Breaker, Failover, DR) |
| "Walk me through the design." | 50 (Interview Framework) |
| "Why microservices?" | 29-30 (Monolith vs Microservices, EDA) |
| "How do you prevent duplicates?" | 26 (Idempotency — 6 layers) |
| "How does the data flow?" | 20-21, 30, 46 (Messaging, Sync/Async, EDA, Batch/Stream) |

---

## Your Competitive Advantage

Most candidates study generic theory from YouTube/books. You have:

1. **Real code examples** — every topic maps to actual Java, Python, Terraform, and YAML in your CXP projects
2. **Real tradeoffs** — the 2-5% email drop rate, the deep link security issue, the Eventtia dependency
3. **Real numbers** — 167 QPS peak, 7 GB/year, 220ms latency, 2800× spike ratio
4. **Real architecture decisions** — why DynamoDB not PostgreSQL, why no circuit breaker library, why rolling update not blue-green
5. **Connected narrative** — all 50 topics tie into ONE platform story, not isolated concepts

When you say "In my project, we chose AP over CP, which caused a 2-5% email drop rate that we compensate with a recovery dashboard I built" — that's 10× more convincing than "CAP theorem says you can only pick two."

**You're not memorizing theory. You're telling YOUR story.**

Good luck!