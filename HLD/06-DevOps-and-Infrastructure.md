# Topic 39: Deployment Strategies

> Blue-green switches all traffic instantly; canary gradually increases new version %; rolling updates replace instances one by one. Feature flags toggle without deploy.

> **Interview Tip:** Choose based on risk — "I'd use canary for user-facing changes to catch issues with 5% traffic before full rollout, blue-green for infrastructure changes needing instant rollback."

---

## The 4 Strategies

```
┌──────────────────────────────────────────────────────────────────────────┐
│                      DEPLOYMENT STRATEGIES                                │
│                                                                          │
│  ┌──────────────────────────┐    ┌──────────────────────────┐          │
│  │  BLUE-GREEN DEPLOYMENT   │    │  CANARY DEPLOYMENT       │          │
│  │                          │    │                           │          │
│  │  ┌──────────┐            │    │     ┌──────────┐         │          │
│  │  │Load      │            │    │     │Load      │         │          │
│  │  │Balancer  │            │    │     │Balancer  │         │          │
│  │  └─────┬────┘            │    │     └────┬─────┘         │          │
│  │    ┌───┴───┐             │    │     ┌────┴────┐          │          │
│  │    ▼       ▼             │    │     ▼         ▼          │          │
│  │  ┌─────┐ ┌─────┐        │    │  ┌────────┐ ┌──────┐    │          │
│  │  │BLUE │ │GREEN│        │    │  │Stable  │ │Canary│    │          │
│  │  │(v1) │ │(v2) │        │    │  │ (v1)   │ │(v2)  │    │          │
│  │  │100% │ │ 0%  │        │    │  │ 95%    │ │ 5%   │    │          │
│  │  └─────┘ └─────┘        │    │  └────────┘ └──────┘    │          │
│  │                          │    │                           │          │
│  │  Switch all traffic      │    │  Gradually increase       │          │
│  │  instantly.              │    │  canary %.                │          │
│  │  Rollback: instant       │    │  Rollback: fast           │          │
│  │  Risk: medium            │    │  Risk: low                │          │
│  │  Resources: 2× infra     │    │  Resources: minimal extra │          │
│  └──────────────────────────┘    └──────────────────────────┘          │
│                                                                          │
│  ┌──────────────────────────┐    ┌──────────────────────────┐          │
│  │  ROLLING UPDATE          │    │  FEATURE FLAGS            │          │
│  │                          │    │                           │          │
│  │  ┌──┐ ┌──┐ ┌──┐ ┌──┐   │    │  Toggle features on/off  │          │
│  │  │v2│ │v2│ │v1│ │v1│   │    │  without deploy.          │          │
│  │  └──┘ └──┘ └──┘ └──┘   │    │                           │          │
│  │                          │    │  if (featureFlag          │          │
│  │  Replace one at a time.  │    │    .enabled("new_feat"))  │          │
│  │  No extra infrastructure.│    │                           │          │
│  │  Rollback: slow (re-roll)│    │  LaunchDarkly, Split.io, │          │
│  │  Risk: medium            │    │  Unleash, Secrets Manager │          │
│  └──────────────────────────┘    └──────────────────────────┘          │
│                                                                          │
│  ┌───────────────┬───────────┬───────────┬───────────┐                │
│  │  Strategy      │  Rollback │  Risk     │  Resources│                │
│  ├───────────────┼───────────┼───────────┼───────────┤                │
│  │  Blue-Green    │  Instant  │  Medium   │  2× infra │                │
│  │  Canary        │  Fast     │  Low      │  Minimal  │                │
│  │  Rolling       │  Slow     │  Medium   │  None     │                │
│  │  Feature Flag  │  Instant  │  Low      │  None     │                │
│  └───────────────┴───────────┴───────────┴───────────┘                │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Deployment Strategies In My CXP Projects

### CXP Uses: Rolling Update (Primary) + Feature Flags (Complementary)

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — DEPLOYMENT STRATEGY MAP                                   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  ECS SERVICES: Rolling Update (default ECS deployment)            │  │
│  │                                                                   │  │
│  │  cxp-events, cxp-event-registration, expviewsnikeapp, Rise GTS  │  │
│  │  → ECS replaces tasks one at a time (rolling).                   │  │
│  │  → ALB drains old task, health-checks new task, then routes.    │  │
│  │  → Zero downtime (always N-1 tasks serving during rollout).     │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  FEATURE FLAGS: Secrets Manager + S3 Config (runtime toggles)     │  │
│  │                                                                   │  │
│  │  cacheBasedBotProtectionFlag → toggle bot protection without     │  │
│  │  redeploying. Read from Secrets Manager at runtime.              │  │
│  │  blockedEvents → block specific events via S3 config.            │  │
│  │  activitiesFeatureFlag → toggle activity features.               │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  TERRAFORM: Manual Approval Gate (not automated rollout)          │  │
│  │                                                                   │  │
│  │  Jenkins pipeline: Init → Plan → CONFIRM APPLY → Apply          │  │
│  │  Human reviews the plan before infrastructure changes.           │  │
│  │  No blue-green or canary for infra — manual confirmation.        │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  STATIC FRONTEND: CloudFront Invalidation (instant switch)        │  │
│  │                                                                   │  │
│  │  rapid-retail-insights-host: S3 deploy + CloudFront invalidation.│  │
│  │  Content-hashed JS/CSS = new URL per deploy (no cache conflict). │  │
│  │  index.html invalidated → users get new version immediately.    │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: ECS Rolling Update — How It Works Step by Step

**Services:** All CXP Spring Boot microservices
**Pattern:** ECS default rolling deployment — replace tasks one at a time

```
┌──────────────────────────────────────────────────────────────────────┐
│  ECS Rolling Update — Zero-Downtime Deploy                           │
│                                                                      │
│  Current: 4 tasks running v1                                        │
│  Goal: Deploy v2 to all tasks                                       │
│                                                                      │
│  Step 1: ECS launches Task 5 (v2)                                   │
│  ┌──┐ ┌──┐ ┌──┐ ┌──┐ ┌──┐                                        │
│  │v1│ │v1│ │v1│ │v1│ │v2│ ← new task starting                     │
│  └──┘ └──┘ └──┘ └──┘ └──┘                                        │
│  ALB health check: GET /actuator/health on v2 Task 5               │
│                                                                      │
│  Step 2: Task 5 passes health check → registered with ALB          │
│  ┌──┐ ┌──┐ ┌──┐ ┌──┐ ┌──┐                                        │
│  │v1│ │v1│ │v1│ │v1│ │v2│ ← now receiving traffic                 │
│  └──┘ └──┘ └──┘ └──┘ └──┘                                        │
│                                                                      │
│  Step 3: ECS drains Task 1 (v1) — ALB stops sending new requests   │
│  ┌──┐ ┌──┐ ┌──┐ ┌──┐ ┌──┐                                        │
│  │v1│ │v1│ │v1│ │v1│ │v2│                                        │
│  │💤│ └──┘ └──┘ └──┘ └──┘  ← draining (finishing in-flight reqs)  │
│                                                                      │
│  Step 4: Task 1 terminated. Task 6 (v2) launched.                   │
│  ┌──┐ ┌──┐ ┌──┐ ┌──┐ ┌──┐                                        │
│  │v2│ │v1│ │v1│ │v1│ │v2│  ← 2 of 4 updated                      │
│  └──┘ └──┘ └──┘ └──┘ └──┘                                        │
│                                                                      │
│  ... repeat for Task 2, Task 3, Task 4 ...                         │
│                                                                      │
│  Final: All 4 tasks running v2                                      │
│  ┌──┐ ┌──┐ ┌──┐ ┌──┐                                              │
│  │v2│ │v2│ │v2│ │v2│                                              │
│  └──┘ └──┘ └──┘ └──┘                                              │
│                                                                      │
│  ZERO DOWNTIME:                                                     │
│  At every step, at least 3 tasks are serving traffic.               │
│  New task receives traffic ONLY after passing health check.         │
│  Old task serves in-flight requests before termination.             │
│                                                                      │
│  ROLLBACK:                                                          │
│  If v2 tasks fail health checks → ECS stops rolling out.           │
│  v1 tasks still running → service continues on v1.                 │
│  Fix the issue → redeploy. No manual rollback needed.              │
└──────────────────────────────────────────────────────────────────────┘
```

**Why rolling update works for CXP:**
- **Stateless services** (Topic 19): any task can handle any request. No session migration needed between v1 and v2 tasks.
- **Backward-compatible APIs**: v1 and v2 serve the same endpoints during rollout. Clients don't know which version they hit.
- **Health checks** (Topic 36): ALB verifies v2 is healthy before routing traffic. NPE readiness probe ensures Spring context is fully loaded.

**Interview answer:**
> "We use ECS rolling updates: tasks are replaced one at a time. The new v2 task must pass the ALB health check (`/actuator/health`) before receiving traffic. The old v1 task is drained (finishes in-flight requests) before termination. At every point during rollout, at least N-1 tasks are serving — zero downtime. This works because our services are stateless: any task can handle any request, no session migration needed. If v2 fails health checks, ECS stops the rollout and v1 tasks continue serving. Rollback is automatic."

---

### Example 2: Feature Flags — Deploy Without Deploying

**Service:** `cxp-event-registration`
**Pattern:** Runtime toggles via Secrets Manager — change behavior without redeployment

```
┌──────────────────────────────────────────────────────────────────────┐
│  Feature Flags — Runtime Behavior Change                             │
│                                                                      │
│  TRADITIONAL DEPLOY:                  FEATURE FLAG:                 │
│  Code change → build → test →        Secrets Manager update →      │
│  Jenkins → deploy → verify           Service reads on next          │
│  (15-30 minutes)                     scheduled check (~59 min)     │
│                                      (or restart for immediate)     │
│                                                                      │
│  CXP FEATURE FLAGS:                                                │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  FLAG 1: cacheBasedBotProtectionFlag                        │    │
│  │  Source: Secrets Manager (CXP Common Secret)                │    │
│  │  Read by: CXPCommonSecretService @Scheduled                 │    │
│  │  Effect: Enables/disables Redis bot protection              │    │
│  │  Use case: Redis causing issues → toggle OFF instantly      │    │
│  │            without code deploy.                              │    │
│  │                                                              │    │
│  │  FLAG 2: blockedEvents                                       │    │
│  │  Source: Secrets Manager (DynamicVariableConfig)             │    │
│  │  Effect: Blocks specific event IDs from registration        │    │
│  │  Use case: Problematic event causing errors → block it      │    │
│  │            without code change.                              │    │
│  │                                                              │    │
│  │  FLAG 3: activitiesFeatureFlag                               │    │
│  │  Source: S3 feature-flag.json                                │    │
│  │  Effect: Toggles activity registration features             │    │
│  │  Use case: Gradual feature rollout per marketplace.         │    │
│  │                                                              │    │
│  │  FLAG 4: refreshCache                                        │    │
│  │  Source: CXPConfigService secret                             │    │
│  │  Effect: Triggers cache refresh via Spring ApplicationEvent │    │
│  │  Use case: Force cache rebuild after Eventtia data fix.     │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  THIS IS A MANUAL CIRCUIT BREAKER (Topic 17):                      │
│  cacheBasedBotProtectionFlag = false → skip ALL Redis logic.       │
│  Equivalent to opening a circuit breaker on Redis.                 │
│  But controlled by on-call engineer, not automatic threshold.      │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// Constants.java — default OFF, toggled via Secrets Manager
public static boolean cacheBasedBotProtectionFlag = false;

// CXPCommonSecretService.java — reads flag from Secrets Manager
CXPFeatureFlag cxpFeatureFlag = objectMapper.readValue(secret, CXPFeatureFlag.class);
setCacheBasedBotProtection(cxpFeatureFlag.isCacheBasedBotProtection());
// No redeployment needed — secret value change takes effect on next read

// EventRegistrationService.java — code branches on flag
if (cacheBasedBotProtectionFlag) {
    // Redis bot protection logic (can be toggled OFF during incidents)
}
```

---

### Example 3: Terraform — Manual Approval Gate

**Component:** `cxp-infrastructure`
**Pattern:** Jenkins pipeline with human confirmation before infrastructure changes

```
┌──────────────────────────────────────────────────────────────────────┐
│  Terraform Deployment — Manual Approval                              │
│                                                                      │
│  Jenkins pipeline stages:                                           │
│                                                                      │
│  ┌──────┐  ┌──────┐  ┌─────────────┐  ┌───────┐  ┌───────────┐ │
│  │ Init │─▶│ Plan │─▶│ CONFIRM     │─▶│ Apply │─▶│ Security  │ │
│  │      │  │      │  │ APPLY       │  │       │  │ Scan      │ │
│  │      │  │(shows│  │             │  │(makes │  │(ScanAt    │ │
│  │      │  │ diff)│  │ ⏸ HUMAN    │  │ infra │  │ Source +  │ │
│  │      │  │      │  │   REVIEWS   │  │ change│  │ Quality   │ │
│  │      │  │      │  │   THE PLAN  │  │  s)   │  │ Gate)     │ │
│  └──────┘  └──────┘  └─────────────┘  └───────┘  └───────────┘ │
│                              ▲                                       │
│                              │                                       │
│                    Engineer reviews:                                 │
│                    "This will create 1 DynamoDB table,              │
│                     modify 2 IAM policies, add 3 Route53 records." │
│                    → Approve or Reject                              │
│                                                                      │
│  WHY MANUAL APPROVAL (not automatic):                               │
│  Infrastructure changes can be DESTRUCTIVE:                         │
│  - Terraform destroy (⚠️ DESTROY parameter in Jenkinsfile)          │
│  - Modifying security groups (could open/close ports)               │
│  - Changing DynamoDB capacity (could throttle production)           │
│  - Route53 record changes (could break DNS for all users)          │
│                                                                      │
│  CONTRAST WITH APP DEPLOY:                                          │
│  App (ECS): Automatic rolling update. Safe — stateless, reversible.│
│  Infra (Terraform): Manual approval. Risky — stateful, potentially │
│  destructive. Different risk profile = different deploy strategy.   │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual Jenkinsfile:**

```groovy
// Jenkinsfile — manual approval gate
// Stages: Init → Plan → Confirm Apply → Apply → ScanAtSource → Scan → Quality Gate
// Parameters: Deploy_Environment, DESTROY (⚠️ destroys all resources)
```

---

### Example 4: Static Frontend — S3 + CloudFront Invalidation

**Service:** `rapid-retail-insights-host` (React SPA)
**Pattern:** Effectively blue-green at the CDN layer

```
┌──────────────────────────────────────────────────────────────────────┐
│  Static Frontend Deployment — CDN Blue-Green                         │
│                                                                      │
│  v1 deployed:                                                       │
│  S3: index.html → references main.abc123.js (content-hashed)      │
│  CloudFront: caches index.html + main.abc123.js                    │
│                                                                      │
│  v2 deploy:                                                         │
│  1. Webpack builds: main.xyz789.js (NEW hash — new URL)           │
│  2. Upload to S3: main.xyz789.js + updated index.html             │
│  3. CloudFront invalidation: clear cached index.html               │
│  4. Next user request: gets NEW index.html → loads main.xyz789.js │
│                                                                      │
│  WHY THIS IS EFFECTIVELY BLUE-GREEN:                                │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  "BLUE" = old index.html referencing main.abc123.js         │    │
│  │  "GREEN" = new index.html referencing main.xyz789.js        │    │
│  │                                                              │    │
│  │  The "switch" = CloudFront invalidation of index.html.      │    │
│  │  Old JS bundle (abc123) stays in S3/CDN (no conflict).      │    │
│  │  New JS bundle (xyz789) is a DIFFERENT URL.                  │    │
│  │  Users in mid-session: still using abc123 (no break).       │    │
│  │  New page loads: get xyz789 (new version).                  │    │
│  │                                                              │    │
│  │  ROLLBACK: re-upload old index.html → invalidate again.     │    │
│  │  Old JS bundle is still in S3. Instant rollback.            │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 5: Multi-Region Deployment — Independent Per Region

```
┌──────────────────────────────────────────────────────────────────────┐
│  Multi-Region Deployment Strategy                                    │
│                                                                      │
│  Each region deploys INDEPENDENTLY:                                 │
│                                                                      │
│  Terraform environments (from Jenkinsfile):                         │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  aws-dev-us-east        ← dev first (feature branches)       │  │
│  │  aws-dev-us-west        ← dev second (verify multi-region)   │  │
│  │  aws-prod-us-east       ← prod first (main/hotfix branch)    │  │
│  │  aws-prod-us-west       ← prod second                       │  │
│  │                                                               │  │
│  │  aws-passplay-dev-us-east  ← NPE dev                        │  │
│  │  aws-passplay-dev-us-west                                    │  │
│  │  aws-passplay-prod-us-east ← NPE prod                       │  │
│  │  aws-passplay-prod-us-west                                   │  │
│  │                                                               │  │
│  │  nsp3-dev               ← Kafka sinks dev                   │  │
│  │  nsp3-prod              ← Kafka sinks prod                   │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  DEPLOYMENT ORDER:                                                  │
│  1. Deploy to us-east (primary traffic region)                     │
│  2. Verify health checks pass + smoke test                         │
│  3. Deploy to us-west (secondary region)                           │
│  4. Verify cross-region health                                     │
│                                                                      │
│  IF us-east DEPLOY FAILS:                                          │
│  us-west is still on v1 (untouched) → Route53 failover to us-west│
│  → Users served from us-west while us-east is fixed.              │
│  This is a natural CANARY across regions:                          │
│  Deploy to one region first = testing with ~60% of production.    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### What CXP Doesn't Use (and When I Would)

```
┌──────────────────────────────────────────────────────────────────────┐
│  STRATEGIES CXP DOESN'T USE                                         │
│                                                                      │
│  BLUE-GREEN (full traffic switch):                                  │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  We don't use it because:                                    │    │
│  │  - ECS rolling update achieves zero-downtime without 2× infra│   │
│  │  - Stateless services don't need "all at once" switching     │    │
│  │  - Rolling update is simpler (ECS default, no extra config)  │    │
│  │                                                              │    │
│  │  When I'd use it:                                            │    │
│  │  - Database migration that changes schema (can't run v1+v2   │    │
│  │    simultaneously against different schemas)                 │    │
│  │  - Major API breaking change (v1 and v2 incompatible)        │    │
│  │  - Need instant rollback guarantee (1 ALB switch vs gradual) │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  CANARY (% traffic split):                                          │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  We don't use it because:                                    │    │
│  │  - Requires weighted target groups or service mesh (Istio)   │    │
│  │  - ECS doesn't natively support % traffic splitting          │    │
│  │  - Our rolling update + health checks catch bad deploys      │    │
│  │                                                              │    │
│  │  When I'd use it:                                            │    │
│  │  - High-risk changes to user-facing registration flow        │    │
│  │  - ML model updates (A/B test: 5% canary vs 95% stable)     │    │
│  │  - Performance-sensitive changes (compare latency metrics)   │    │
│  │  - Would implement with: AWS App Mesh or ALB weighted TG     │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  HOWEVER: Our multi-region deployment IS a natural canary:         │
│  Deploy to us-east first → if broken, us-west still serves v1.   │
│  ~60% of traffic sees v2 first. Not % configurable, but achieves  │
│  the same risk reduction as canary.                                │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Deployment Strategies Across CXP

| Component | Strategy | Rollback | Zero Downtime? | Automation |
|-----------|----------|----------|---------------|-----------|
| **ECS Services** | Rolling update (1 task at a time) | Automatic (health check fails → stop rollout) | Yes (N-1 tasks always serving) | Fully automated (Jenkins → ECS) |
| **Feature flags** | Runtime toggle (no deploy) | Instant (flip flag back) | Yes (no deployment involved) | Manual (Secrets Manager update) |
| **Terraform infra** | Plan + manual approval + apply | Terraform plan to revert | N/A (infra change, not traffic) | Semi-automated (human approval gate) |
| **Static frontend** | S3 upload + CloudFront invalidation | Re-upload old index.html | Yes (content-hashed URLs) | Automated (Jenkins → S3 → invalidation) |
| **Multi-region** | Sequential (us-east first, then us-west) | us-west still on v1 = natural canary | Yes (Route53 serves both) | Per-region deployment pipelines |
| **Kafka sinks** | Terraform apply (sink config change) | Revert Terraform | Brief blip during sink reconfiguration | Semi-automated (Terraform pipeline) |

---

## Common Interview Follow-ups

### Q: "Why rolling update over blue-green for your services?"

> "Three reasons: (1) **Cost** — blue-green requires 2× infrastructure running simultaneously. Rolling update reuses the same target group, launching one extra task temporarily. (2) **Simplicity** — ECS rolling update is the default behavior, zero extra configuration. Blue-green requires maintaining two separate environments and a traffic switch mechanism. (3) **Statelessness** — our services are stateless (Topic 19), so running v1 and v2 simultaneously during rollout is safe. Any task handles any request. Blue-green's 'all-at-once switch' advantage doesn't add value when individual tasks are interchangeable."

### Q: "How do you handle database schema changes during deployment?"

> "Our platform doesn't have a traditional SQL database to migrate (Topic 2 — all NoSQL). But if we did: (1) **Backward-compatible changes** — add new columns/fields, don't remove old ones. Both v1 and v2 work with the schema during rolling update. (2) **Multi-step migration** — Step 1: deploy v2 that reads both old+new schema. Step 2: migrate data. Step 3: deploy v3 that only reads new schema. Step 4: drop old columns. (3) **DynamoDB schema changes** — DynamoDB is schema-less per item, so 'migration' is just adding new attributes. Old items without the attribute return null — v2 code handles this gracefully."

### Q: "Feature flags vs canary — when do you use each?"

> "Feature flags for **behavioral changes**: new business logic, modified validation rules, toggling a cache strategy. These change WHAT the code does, not HOW MUCH traffic sees it. All users see the same version, but the feature is on/off. Our `cacheBasedBotProtectionFlag` is exactly this — all traffic goes through the same code, but bot protection is toggled on/off. Canary for **risky deployments**: new version of the entire service, not just a feature. 5% of traffic tests the new version while 95% stays safe. We achieve a form of this naturally by deploying to us-east first: ~60% of traffic sees v2 while us-west stays on v1."

### Q: "What happens if a deploy breaks the registration flow during a sneaker launch?"

> "Multiple layers of protection: (1) **Health checks** — if v2 fails `/actuator/health`, ECS stops the rollout. v1 tasks continue serving. (2) **Feature flags** — if the issue is in bot protection, toggle `cacheBasedBotProtectionFlag` OFF via Secrets Manager. Instant fix, no redeploy. (3) **Multi-region** — if us-east deploy breaks, Route53 health check fails → all traffic goes to us-west (still running v1). (4) **Rollback** — redeploy v1 via Jenkins. Rolling update replaces v2 tasks with v1 tasks. (5) **CDN cache** — Akamai serves cached event pages even if backend is degraded. Users browsing events are unaffected; only new registrations impacted."

---
---

# Topic 40: Containers & Kubernetes

> Containers share OS kernel (lightweight, fast); VMs have full guest OS (heavy, isolated). Kubernetes orchestrates containers with auto-scaling, self-healing, and rolling updates.

> **Interview Tip:** Know K8s primitives — "Pods are smallest unit, Deployments manage replicas, Services expose them, ConfigMaps/Secrets handle configuration."

---

## VMs vs Containers

```
┌──────────────────────────────────────────────────────────────────────────┐
│              VIRTUAL MACHINES vs CONTAINERS                               │
│                                                                          │
│  ┌──────────────────────────┐    ┌──────────────────────────────────┐  │
│  │   VIRTUAL MACHINES        │    │   CONTAINERS (Docker)            │  │
│  │                           │    │                                   │  │
│  │  ┌─────┐ ┌─────┐        │    │  ┌─────┐ ┌─────┐ ┌─────┐       │  │
│  │  │App A│ │App B│        │    │  │App A│ │App B│ │App C│       │  │
│  │  ├─────┤ ├─────┤        │    │  ├─────┤ ├─────┤ ├─────┤       │  │
│  │  │Bins/│ │Bins/│        │    │  │Bins/│ │Bins/│ │Bins/│       │  │
│  │  │Libs │ │Libs │        │    │  │Libs │ │Libs │ │Libs │       │  │
│  │  ├─────┤ ├─────┤        │    │  └─────┘ └─────┘ └─────┘       │  │
│  │  │Guest│ │Guest│        │    │  ┌──────────────────────────┐   │  │
│  │  │ OS  │ │ OS  │ Heavy  │    │  │Container Runtime (Docker)│   │  │
│  │  └─────┘ └─────┘ GB size│    │  └──────────────────────────┘   │  │
│  │  ┌──────────────┐Min boot│    │  ┌──────────────────────────┐   │  │
│  │  │  Hypervisor  │        │    │  │  Host OS (shared kernel) │   │  │
│  │  └──────────────┘        │    │  └──────────────────────────┘   │  │
│  │  ┌──────────────┐        │    │                                   │  │
│  │  │   Host OS    │        │    │  [+] Lightweight, MB size         │  │
│  │  └──────────────┘        │    │  [+] Seconds to start             │  │
│  └──────────────────────────┘    │  [+] Share OS kernel              │  │
│                                   └──────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────┬──────────────────┬──────────────────┐               │
│  │              │  VM               │  Container        │               │
│  ├──────────────┼──────────────────┼──────────────────┤               │
│  │  Size         │  GBs              │  MBs              │               │
│  │  Boot time    │  Minutes          │  Seconds          │               │
│  │  Isolation    │  Full (guest OS)  │  Process-level    │               │
│  │  Density      │  ~10 per host     │  ~100 per host    │               │
│  │  Overhead     │  High (full OS)   │  Low (shared kern)│               │
│  └──────────────┴──────────────────┴──────────────────┘               │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Kubernetes (K8s) — Container Orchestration

```
┌──────────────────────────────────────────────────────────────────────┐
│  KUBERNETES — Container Orchestration                                │
│                                                                      │
│  ┌──────────────────────┐  ┌────────────┐  ┌────────────┐         │
│  │   Control Plane       │  │ Worker     │  │ Worker     │         │
│  │                       │  │ Node 1     │  │ Node 2     │         │
│  │  ┌────────┐┌────────┐│  │ ┌───┐ ┌───┐│  │ ┌───┐ ┌───┐│         │
│  │  │API     ││Schedlr ││  │ │Pod│ │Pod││  │ │Pod│ │Pod││         │
│  │  │Server  ││        ││  │ └───┘ └───┘│  │ └───┘ └───┘│         │
│  │  └────────┘└────────┘│  │            │  │            │         │
│  │  ┌────────┐┌────────┐│  │  kubelet   │  │  kubelet   │         │
│  │  │ etcd   ││Contrlr ││  │            │  │            │         │
│  │  └────────┘└────────┘│  └────────────┘  └────────────┘         │
│  └──────────────────────┘                                           │
│                                                                      │
│  Features:                                                          │
│  • Auto-scaling (HPA: scale pods on CPU/memory/custom metrics)     │
│  • Self-healing (restart failed pods, replace unhealthy nodes)     │
│  • Rolling updates (zero-downtime deployments)                     │
│  • Service discovery (DNS-based, internal load balancing)          │
│  • Load balancing (distribute traffic across pods)                 │
│  • Config management (ConfigMaps, Secrets)                         │
│                                                                      │
│  KEY PRIMITIVES:                                                    │
│  Pod          — smallest deployable unit (1+ containers)           │
│  Deployment   — manages replica sets, rolling updates              │
│  Service      — stable network endpoint for pods (ClusterIP, LB)   │
│  ConfigMap    — non-sensitive configuration                        │
│  Secret       — sensitive data (passwords, tokens)                 │
│  Ingress      — HTTP routing rules (path-based, host-based)       │
│  HPA          — Horizontal Pod Autoscaler                          │
│  Namespace    — logical cluster isolation                          │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Containers & Kubernetes In My CXP Projects

### CXP Uses: Docker Containers on ECS (Fargate) + NPE (Kubernetes)

Our platform runs on **two container orchestration platforms**: AWS ECS (for some services) and NPE/Kubernetes (Nike's internal platform for newer deployments). Both run Docker containers.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — CONTAINER ARCHITECTURE                                    │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  DOCKER IMAGES (same for both platforms)                          │  │
│  │                                                                   │  │
│  │  Source: Artifactory (Nike Docker registry)                      │  │
│  │  Images:                                                         │  │
│  │  • artifactory.nike.com:9002/cxp/cxp-events                     │  │
│  │  • artifactory.nike.com:9002/cxp/cxp-event-registration         │  │
│  │  • expviewsnikeapp (Docker image from CloudFormation)            │  │
│  │  • Rise GTS (Docker image from CloudFormation)                   │  │
│  │                                                                   │  │
│  │  Each image: Spring Boot fat JAR + JVM + dependencies           │  │
│  │  Size: ~200-400 MB per image                                    │  │
│  │  Build: Jenkins → Docker build → push to Artifactory            │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────┐  ┌──────────────────────────┐          │
│  │  PLATFORM 1: NPE (K8s)   │  │  PLATFORM 2: ECS (Fargate)│          │
│  │                           │  │                           │          │
│  │  cxp-events              │  │  expviewsnikeapp          │          │
│  │  cxp-event-registration  │  │  Rise GTS                 │          │
│  │                           │  │                           │          │
│  │  Orchestration: K8s      │  │  Orchestration: ECS       │          │
│  │  Config: NPE component   │  │  Config: CloudFormation   │          │
│  │  YAML (K8s-native)       │  │  task definitions         │          │
│  │  Health: liveness +      │  │  Health: ALB target group  │          │
│  │  readiness probes        │  │  health checks             │          │
│  │  Scaling: K8s HPA        │  │  Scaling: ECS auto-scaling │          │
│  │  Discovery: K8s DNS      │  │  Discovery: ALB + Route53  │          │
│  │  Ingress: NPE ingress    │  │  Ingress: ALB listener     │          │
│  │  Secrets: NPE secrets    │  │  Secrets: Secrets Manager   │          │
│  └──────────────────────────┘  └──────────────────────────┘          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: NPE Component YAML — Kubernetes in CXP

**Services:** cxp-events, cxp-event-registration
**Platform:** NPE (Nike Platform Experience) — Kubernetes under the hood

The NPE component YAML maps directly to Kubernetes primitives:

```
┌──────────────────────────────────────────────────────────────────────┐
│  NPE YAML → Kubernetes Primitive Mapping                             │
│                                                                      │
│  NPE YAML (what we write):        K8s Equivalent (what NPE creates):│
│  ─────────────────────────        ──────────────────────────────── │
│                                                                      │
│  container:                        Pod spec:                        │
│    image: artifactory.nike.com     containers:                      │
│      :9002/cxp/cxp-events           - image: artifactory...        │
│    httpTrafficPort: 8080              containerPort: 8080           │
│                                                                      │
│  routing:                          Ingress + Service:               │
│    paths:                          rules:                           │
│      prefix:                         - path: /community/events/v1  │
│        - /community/events/v1        backend: cxp-events-service   │
│        - /community/event_seats*                                   │
│                                                                      │
│  health:                           Pod probes:                      │
│    liveness:                       livenessProbe:                   │
│      httpGet:                        httpGet:                       │
│        path: /events_health/v1         path: /events_health/v1     │
│        port: 8080                      port: 8080                  │
│    readiness:                      readinessProbe:                  │
│      httpGet:                        httpGet:                       │
│        path: /events_health/v1         path: /events_health/v1     │
│        port: 8080                      port: 8080                  │
│                                                                      │
│  NPE ABSTRACTS away:                                               │
│  - Deployment/ReplicaSet creation                                  │
│  - Service creation (ClusterIP)                                    │
│  - Ingress controller configuration                                │
│  - TLS certificate provisioning                                    │
│  - Resource limits / requests                                      │
│  - Namespace isolation                                             │
│                                                                      │
│  We write ~50 lines of NPE YAML.                                  │
│  NPE generates ~500 lines of K8s manifests.                       │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual NPE component YAML:**

```yaml
# NPEInfrastructure/npe/prod/component-us-west-2.yaml
container:
  image: artifactory.nike.com:9002/cxp/cxp-events
  httpTrafficPort: 8080

routing:
  host:
    enabled: true
  paths:
    prefix:
      - path: /community/events/v1
      - path: /community/event_seats_status/v1
      - path: /community/event_summaries/v1
      - path: /community/groups/v1
      - path: /community/events_health/v1

health:
  liveness:
    httpGet:
      path: /community/events_health/v1
      port: 8080
  readiness:
    httpGet:
      path: /community/events_health/v1
      port: 8080
```

---

### Example 2: ECS Task Definition — Container Config Without K8s

**Services:** expviewsnikeapp, Rise GTS
**Platform:** AWS ECS (Fargate) — no Kubernetes, AWS-native orchestration

```
┌──────────────────────────────────────────────────────────────────────┐
│  ECS Task Definition — Kubernetes Equivalent Mapping                 │
│                                                                      │
│  ECS Concept           K8s Equivalent          CXP Usage            │
│  ───────────           ──────────────          ─────────            │
│  Task Definition   →   Pod spec                Container image,     │
│                                                 ports, env vars     │
│                                                                      │
│  ECS Service       →   Deployment              Desired count,       │
│                                                 rolling update,     │
│                                                 load balancer       │
│                                                                      │
│  ALB Target Group  →   Service + Ingress       Health checks,       │
│                                                 path routing        │
│                                                                      │
│  Auto Scaling      →   HPA                     CPU-based scaling    │
│                                                                      │
│  Secrets Manager   →   K8s Secrets             API keys, config     │
│                                                                      │
│  CloudWatch Logs   →   K8s logging (Fluentd)   Container stdout     │
│                                                                      │
│  ECR/Artifactory   →   Container Registry      Docker images        │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual CloudFormation:**

```yaml
# expviewsnikeapp_ecs_config.yaml — ECS Task Definition
AppTaskDefinition:
  Type: AWS::ECS::TaskDefinition
  Properties:
    ContainerDefinitions:
      - Name: !Join ["-", [!Ref AppName, "container"]]
        Image: !Ref DockerImage         # Docker image from Artifactory
        PortMappings:
          - HostPort: !Ref ContainerPort    # 8080
            ContainerPort: !Ref ContainerPort
        Environment:                    # Like K8s ConfigMap
          - Name: ElasticSearchEndpoint
            Value: !Ref ElasticSearchEndpoint
          - Name: ES_ENDPOINT
            Value: !Ref ElasticSearchEndpoint

# ECS Service — like K8s Deployment
AppService:
  Type: AWS::ECS::Service
  Properties:
    DesiredCount: !Ref DesiredCount     # Like K8s replicas
    LoadBalancers:                      # Like K8s Service + Ingress
      - ContainerPort: !Ref ContainerPort
        TargetGroupArn: !Ref AppTargetGroup
```

---

### Example 3: Container Lifecycle in CXP

```
┌──────────────────────────────────────────────────────────────────────┐
│  Container Lifecycle — From Build to Running                         │
│                                                                      │
│  1. BUILD:                                                          │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Jenkins pipeline:                                          │    │
│  │  → gradle build (compile Spring Boot fat JAR)               │    │
│  │  → docker build (Dockerfile: JRE + fat JAR + config)        │    │
│  │  → docker push artifactory.nike.com:9002/cxp/cxp-events    │    │
│  │  → Security scan (ScanAtSource: static, SCA, secret, IaC)  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  2. DEPLOY:                                                         │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  NPE path: Jenkins → NPE API → K8s Deployment updated      │    │
│  │  ECS path: Jenkins → CloudFormation → ECS Service updated   │    │
│  │  Both: Rolling update (Topic 39) — one container at a time  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  3. RUN:                                                            │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Container starts → JVM boots → Spring context initializes  │    │
│  │  → Caffeine caches warm (InternalEventsCacheService)        │    │
│  │  → Eventtia auth token fetched                              │    │
│  │  → health endpoint responds 200                             │    │
│  │  → ALB/NPE adds to service (receives traffic)              │    │
│  │  → Lettuce connects to Redis (single multiplexed connection)│    │
│  │  → DynamoDB client initialized (region-based)               │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  4. SCALE:                                                          │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  CloudWatch/K8s detects: CPU > 70% for 3 minutes            │    │
│  │  → Launch new container from same image                     │    │
│  │  → New container goes through RUN lifecycle                 │    │
│  │  → Health check passes → receives traffic                   │    │
│  │  → Scale-in: CPU < 30% → drain + terminate container       │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  5. SELF-HEAL:                                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Container crashes (OOM, deadlock, unhandled exception)     │    │
│  │  NPE: liveness probe fails → K8s restarts container         │    │
│  │  ECS: health check fails → ECS replaces task                │    │
│  │  → New container from same image → automatic recovery       │    │
│  │  No manual intervention. No pager alert for single crash.  │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 4: etcd — K8s Cluster State via Raft Consensus

```
┌──────────────────────────────────────────────────────────────────────┐
│  etcd in NPE — Where Our Deployment State Lives                      │
│                                                                      │
│  When we deploy cxp-events:                                        │
│                                                                      │
│  1. NPE API receives our component YAML                            │
│  2. K8s API server writes desired state to etcd:                   │
│     "cxp-events: 4 replicas, image: cxp-events:v2.0,              │
│      health: /events_health/v1, routing: /community/events/*"      │
│                                                                      │
│  3. etcd Raft consensus (Topic 25):                                │
│     etcd-1 (leader) → replicates to etcd-2 → ACK                  │
│     etcd-1 (leader) → replicates to etcd-3 → ACK                  │
│     Committed: deployment state is durable.                        │
│                                                                      │
│  4. K8s scheduler reads desired state from etcd:                   │
│     "Need 4 pods of cxp-events → schedule on worker nodes"        │
│                                                                      │
│  5. kubelet on each node:                                          │
│     → pulls image from Artifactory                                 │
│     → starts container                                             │
│     → reports status back to API server → stored in etcd           │
│                                                                      │
│  IF CONTROL PLANE NODE DIES:                                       │
│  etcd Raft election → new leader → cluster state preserved.       │
│  Our cxp-events pods keep running (data plane unaffected).         │
│  New deployments wait until control plane recovers.               │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 5: Why NPE (Not Raw K8s, Not Just ECS)

```
┌──────────────────────────────────────────────────────────────────────┐
│  WHY NPE (Nike Platform Experience)                                  │
│                                                                      │
│  Raw Kubernetes:          NPE:                    ECS:              │
│  ─────────────────       ─────                   ────              │
│  Write: Deployment,      Write: 50 lines of     Write: CloudForm- │
│  Service, Ingress,       NPE component YAML.    ation template.   │
│  ConfigMap, HPA,         NPE handles the rest.  AWS manages infra.│
│  PDB, Network Policy,                                              │
│  ServiceAccount, RBAC                                               │
│  = ~500 lines YAML.                                                │
│                                                                      │
│  WHAT NPE PROVIDES (beyond raw K8s):                               │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ✓ Custom domain management (any.v1.events.*.origins.nike)  │    │
│  │  ✓ TLS certificate provisioning (private CA)                │    │
│  │  ✓ OSCAR token integration (service-to-service auth)        │    │
│  │  ✓ CrowdStrike security agent (container security)          │    │
│  │  ✓ Splunk log forwarding (automatic via Kinesis)            │    │
│  │  ✓ Nike identity integration (OKTA, consumer JWT)           │    │
│  │  ✓ Multi-region deployment (us-east + us-west components)   │    │
│  │  ✓ Ingress with Nike DNS conventions                        │    │
│  │  ✓ Data classification labels (restricted-usage)            │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  NPE = Kubernetes + Nike enterprise standards.                     │
│  We get K8s power (auto-scale, self-heal, rolling updates)         │
│  without K8s operational burden (cluster management, upgrades,     │
│  networking, security policies).                                   │
│                                                                      │
│  WHY SOME SERVICES ON ECS (not NPE):                              │
│  expviewsnikeapp and Rise GTS pre-date NPE migration.             │
│  They use CloudFormation + ECS (legacy pattern).                   │
│  Migration to NPE is incremental — cxp-events and cxp-reg moved  │
│  first, others will follow.                                        │
└──────────────────────────────────────────────────────────────────────┘
```

**From the NPE service config — Nike platform features:**

```yaml
# NPEService/prod/711620779129_npe_service_us_west.yaml
name: "cxp"
platform:
  platformType: experience
  name: npe-platform
  dataClassification: restricted-usage    # Nike data classification
  workloadEnvironment: prod
ingress:
  private:
    certificateAuthority: private         # Nike internal CA
  public:
    enabled: true
    dns:
      customDomains:                       # Nike DNS naming convention
        - any.v1.events.community.global.prod.origins.nike
        - aws-us-west-2.v1.events.community.global.prod.origins.nike
```

---

## Summary: Containers & Orchestration in CXP

| Aspect | NPE (K8s) Services | ECS Services |
|--------|-------------------|-------------|
| **Services** | cxp-events, cxp-event-registration | expviewsnikeapp, Rise GTS |
| **Container runtime** | Docker (K8s CRI) | Docker (ECS agent) |
| **Image registry** | Artifactory (nike.com:9002) | Artifactory / ECR |
| **Config** | NPE component YAML (~50 lines) | CloudFormation (~200 lines) |
| **Health checks** | Liveness + readiness probes (K8s native) | ALB target group health check |
| **Scaling** | K8s HPA (CPU/memory) | ECS auto-scaling (CloudWatch) |
| **Self-healing** | Pod restart on liveness failure | Task replacement on health failure |
| **Rolling updates** | K8s Deployment rolling strategy | ECS rolling update (default) |
| **Service discovery** | K8s DNS + NPE ingress | ALB + Route53 |
| **Secrets** | NPE secrets (K8s Secrets) | AWS Secrets Manager |
| **Logging** | stdout → Kinesis → Splunk | stdout → Docker → Kinesis → Splunk |
| **Networking** | NPE ingress + private CA TLS | ALB HTTPS + security groups |

---

## Common Interview Follow-ups

### Q: "ECS vs Kubernetes — when would you choose each?"

> "ECS when: (1) AWS-native simplicity — no cluster management, deeply integrated with ALB/CloudWatch/IAM, (2) small team — ECS is simpler to operate than K8s, (3) already on AWS — ECS is 'free' (no control plane cost with Fargate). Kubernetes when: (1) multi-cloud or hybrid — K8s runs anywhere, (2) complex networking needs — K8s Network Policies, Service Mesh, (3) enterprise platform team manages K8s — you just write YAML. Our CXP platform uses both: NPE/K8s for newer services (cxp-events, cxp-reg) because Nike's platform team manages the K8s cluster. ECS for older services (expviewsnikeapp, Rise GTS) that pre-date the NPE migration."

### Q: "What's in your Docker image?"

> "A Spring Boot fat JAR on a JRE base image. The Dockerfile: (1) base image with JRE 11/17, (2) COPY the fat JAR (~100MB), (3) set JVM options (heap size, GC settings), (4) EXPOSE port 8080, (5) ENTRYPOINT java -jar app.jar. Total image size: ~200-400MB. We don't install OS packages or run multiple processes — one container = one Spring Boot process. CrowdStrike security agent is injected by NPE as a sidecar, not baked into our image."

### Q: "How does auto-scaling work with containers?"

> "Two levels: (1) **Pod/task scaling** — K8s HPA or ECS auto-scaling monitors CPU utilization. CPU > 70% for 3 minutes → launch new container from the same Docker image. Container starts (~30 seconds for Spring Boot), passes health check, receives traffic. CPU < 30% for 15 minutes → drain and terminate one container. (2) **Node scaling** — if worker nodes are full (can't schedule new pods), the K8s cluster autoscaler adds new EC2 instances. With ECS Fargate, node scaling is transparent — Fargate always has capacity. Our sneaker launch scaling: 2 containers (normal) → 8 containers (peak) → 2 containers (after launch). All automatic."

### Q: "Containers are stateless — where does state go?"

> "Externalized to managed services (Topic 19): Redis for cache/idempotency, DynamoDB for retry queue, Eventtia for registrations, S3 for webhooks, Elasticsearch for search. Containers are disposable — kill any container, replace it with a fresh one from the same image, and the service continues. No local disk state, no in-memory sessions, no local databases. This is what enables auto-scaling and self-healing: the orchestrator can freely create/destroy containers because they carry no state."

---

## The Complete HLD — 40 Topics, 6 Files

```
┌──────────────────────────────────────────────────────────────────────┐
│  HLD INTERVIEW PREPARATION — FINAL INDEX                             │
│                                                                      │
│  01-CAP-Theorem.md                    (Topics 1-10)                 │
│  02-Caching-and-Performance.md        (Topics 11-19)                │
│  03-Messaging-and-Communication.md    (Topics 20-25)                │
│  04-Reliability-and-Resilience.md     (Topics 26-30)                │
│  05-Security-and-Auth.md              (Topics 31-38)                │
│  06-DevOps-and-Infrastructure.md      (Topics 39-40)                │
│                                                                      │
│  40 topics × real CXP code examples × interview answers             │
│  Total: ~20,000+ lines of HLD interview preparation                │
└──────────────────────────────────────────────────────────────────────┘
```

---
---

# Topic 41: DNS (Domain Name System)

> Translates domains to IPs through recursive resolution: browser → resolver → root → TLD → authoritative nameserver. Record types: A (IPv4), AAAA (IPv6), CNAME (alias), MX (mail).

> **Interview Tip:** Explain caching — "DNS responses have TTL; I'd set low TTL (60s) during migrations for faster propagation, higher (3600s) normally to reduce lookup latency."

---

## How DNS Resolution Works

```
┌──────────────────────────────────────────────────────────────────────┐
│  DNS RESOLUTION FLOW                                                 │
│                                                                      │
│  Translates human-readable domains (nike.com)                       │
│  to IP addresses (142.250.185.78)                                   │
│                                                                      │
│  ┌────────┐ 1 ┌──────────┐ 2 ┌──────────┐ 3 ┌──────────┐        │
│  │Browser │──▶│Local DNS │──▶│Root DNS  │──▶│TLD DNS   │        │
│  │example │   │Resolver/ │   │13 servers│   │.com, .org│        │
│  │.com?   │   │Cache     │   │          │   │          │        │
│  └────────┘   └──────────┘   └──────────┘   └──────────┘        │
│                                                 │                  │
│                                              4  ▼                  │
│                                            ┌──────────┐           │
│                                            │Authoritat│ 5 ┌─────┐│
│                                            │ive NS    │──▶│ IP  ││
│                                            │example   │   │Addr ││
│                                            │.com NS   │   │     ││
│                                            └──────────┘   └─────┘│
│                                                                      │
│               IP cached, returned to browser                        │
│                                                                      │
│  DNS RECORD TYPES:                                                  │
│  ┌───────────┬────────────┬──────────┬──────────┬───────────┐     │
│  │ A Record  │AAAA Record │  CNAME   │MX Record │ NS Record │     │
│  │ Domain →  │ Domain →   │ Alias →  │ Mail     │ Name      │     │
│  │ IPv4      │ IPv6       │ Domain   │ servers  │ servers   │     │
│  └───────────┴────────────┴──────────┴──────────┴───────────┘     │
└──────────────────────────────────────────────────────────────────────┘
```

---

## DNS In My CXP Projects — Real Examples

### CXP's DNS Architecture — Route53 as Authoritative DNS

Route53 is the **authoritative DNS** for all CXP service domains. It answers "where is this service?" for every request that reaches our platform.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP DNS ARCHITECTURE                                                     │
│                                                                          │
│  USER REQUEST: "any.v1.events.community.global.prod.origins.nike"       │
│                                                                          │
│  1. Browser → ISP Resolver → "I don't know origins.nike, ask root"     │
│  2. Root DNS → ".nike TLD? Ask Nike's NS servers"                       │
│  3. Nike NS → "origins.nike? That's managed by Route53"                │
│  4. Route53 (Authoritative for CXP):                                    │
│     ┌────────────────────────────────────────────────────────────┐     │
│     │  Query: any.v1.events.community.global.prod.origins.nike   │     │
│     │  Record type: CNAME                                         │     │
│     │  Routing policy: LATENCY                                    │     │
│     │                                                              │     │
│     │  Route53 checks:                                             │     │
│     │  • Latency from resolver to us-east-1: 5ms                  │     │
│     │  • Latency from resolver to us-west-2: 60ms                 │     │
│     │  • Health check for us-east-1: HEALTHY ✓                    │     │
│     │  • Health check for us-west-2: HEALTHY ✓                    │     │
│     │                                                              │     │
│     │  Returns: aws-us-east-1.v1.events.community...              │     │
│     │  (CNAME → lowest latency healthy region)                    │     │
│     └────────────────────────────────────────────────────────────┘     │
│  5. Browser resolves CNAME → ALB IP → connects to cxp-events          │
│                                                                          │
│  TOTAL DNS RESOLUTION: ~50-100ms (first request, uncached)              │
│  CACHED: ~0ms (subsequent requests within TTL)                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### The Complete DNS Record Map

```
┌──────────────────────────────────────────────────────────────────────┐
│  CXP DNS RECORDS (from Terraform route53_locals.tf)                  │
│                                                                      │
│  PER SERVICE, PER REGION — two records:                             │
│                                                                      │
│  RECORD 1: Global endpoint (latency-routed)                        │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Name:    any.v1.events.community.global.prod.origins.nike  │    │
│  │  Type:    CNAME                                              │    │
│  │  Value:   aws-us-east-1.v1.events.community...              │    │
│  │  TTL:     300 seconds (5 minutes)                            │    │
│  │  Routing: LATENCY (Route53 picks lowest latency region)     │    │
│  │  Health:  Attached health check (failover if unhealthy)     │    │
│  │                                                              │    │
│  │  Purpose: USER-FACING. Clients resolve this domain.          │    │
│  │  Route53 returns the NEAREST HEALTHY region's CNAME.         │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  RECORD 2: Regional endpoint (simple CNAME to ALB)                 │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Name:    aws-us-east-1.v1.events.community...              │    │
│  │  Type:    CNAME                                              │    │
│  │  Value:   cxp-alb-us-east-1.elb.amazonaws.com              │    │
│  │  TTL:     300 seconds                                        │    │
│  │  Routing: SIMPLE (direct CNAME, no routing logic)           │    │
│  │                                                              │    │
│  │  Purpose: REGION-SPECIFIC. Resolved by the "any.*" CNAME.  │    │
│  │  Points directly to the ALB in that region.                 │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  RECORD 3: ACM validation (certificate DNS validation)             │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Name:    _acme-challenge.aws-us-east-1.v1.events...       │    │
│  │  Type:    CNAME                                              │    │
│  │  Value:   ACM validation value                               │    │
│  │  Routing: SIMPLE                                             │    │
│  │                                                              │    │
│  │  Purpose: Prove domain ownership for TLS certificate.       │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  SERVICES WITH DNS RECORDS:                                        │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  events            → /community/events/v1                   │    │
│  │  event-registrations → /community/event_registrations/v1   │    │
│  │  groups             → /community/groups/v1                  │    │
│  │  calendar-url       → /community/calendar_url/v1            │    │
│  │  event-summaries    → /community/event_summaries/v1         │    │
│  │  events-health      → /community/events_health/v1           │    │
│  │  reg-health         → /community/reg_health/v1              │    │
│  │  events-health-us-east → Route53 regional health check      │    │
│  │  events-health-us-west → Route53 regional health check      │    │
│  │  reg-health-us-east    → Route53 regional health check      │    │
│  │  reg-health-us-west    → Route53 regional health check      │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  TOTAL: ~20+ DNS records across 2 regions for CXP services.       │
│  All auto-generated by Terraform from var.route53_services list.   │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual Terraform:**

```hcl
// route53_locals.tf — auto-generates DNS records per service
route53_service_records = flatten([
  for svc in var.route53_services : [
    {
      record_name    = "any.v1.${svc.name}.${local.r53_cfg.domain_suffix}"
      record_value   = "aws-${local.r53_region}.v1.${svc.name}.${domain}"
      ttl            = 300                    // 5 minute TTL
      routing_policy = "LATENCY"              // Route53 picks best region
      health_check   = svc.health_check_name  // failover if unhealthy
    },
    {
      record_name    = "aws-${local.r53_region}.v1.${svc.name}.${domain}"
      record_value   = local.r53_ext_target   // ALB DNS name
      ttl            = 300
      routing_policy = "SIMPLE"               // direct CNAME
    },
    {
      record_name    = "_acme-challenge.aws-${local.r53_region}.v1.${svc.name}.${domain}"
      routing_policy = "SIMPLE"               // ACM cert validation
    }
  ]
])

// route53.tf — creates the actual Route53 records
resource "aws_route53_record" "cname_record" {
  zone_id         = var.hosted_zone_id
  name            = var.record_name
  type            = "CNAME"
  ttl             = var.ttl
  records         = [var.record_value]

  dynamic "latency_routing_policy" {
    for_each = var.routing_policy == "LATENCY" ? [1] : []
    content {
      region = var.region
    }
  }
  health_check_id = var.routing_policy == "LATENCY" ? var.health_check_id : null
}
```

---

### DNS TTL Strategy

```
┌──────────────────────────────────────────────────────────────────────┐
│  DNS TTL STRATEGY IN CXP                                             │
│                                                                      │
│  CXP TTL: 300 seconds (5 minutes) for all records.                 │
│                                                                      │
│  WHY 300 SECONDS:                                                   │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Too short (30s):                                           │    │
│  │  + Faster failover (Route53 change visible in 30s)          │    │
│  │  - More DNS queries (every client re-resolves every 30s)    │    │
│  │  - Higher Route53 cost ($0.40/million queries)              │    │
│  │  - Higher latency (DNS lookup on every request after 30s)   │    │
│  │                                                              │    │
│  │  Too long (3600s):                                          │    │
│  │  + Fewer DNS queries (cheaper, lower latency)               │    │
│  │  - Slow failover (clients cache old IP for up to 1 hour!)  │    │
│  │  - Regional failover takes 60+ minutes to propagate         │    │
│  │                                                              │    │
│  │  300s is the balance:                                        │    │
│  │  + Regional failover propagates within 5 minutes            │    │
│  │  + Reasonable DNS query volume                              │    │
│  │  + Akamai CDN resolves more frequently than browsers,       │    │
│  │    so CDN-served requests failover faster than 5 min        │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  WHEN TO CHANGE TTL:                                                │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Before migration: lower to 60s (changes propagate in 1 min)│   │
│  │  During migration: switch DNS records                        │    │
│  │  After migration: raise back to 300s (reduce query volume)  │    │
│  │                                                              │    │
│  │  Before sneaker launch: lower to 60s (faster failover       │    │
│  │  during high-traffic period)                                 │    │
│  │  After launch: raise back to 300s                           │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### DNS Record Types Used in CXP

```
┌──────────────────────────────────────────────────────────────────────┐
│  DNS RECORD TYPES IN CXP                                             │
│                                                                      │
│  ┌────────────┬──────────────────────────────────────────────────┐ │
│  │  Type       │  CXP Usage                                       │ │
│  ├────────────┼──────────────────────────────────────────────────┤ │
│  │  CNAME      │  PRIMARY record type for CXP.                    │ │
│  │  (Alias →   │  any.v1.events... → aws-us-east-1.v1.events...  │ │
│  │   Domain)   │  aws-us-east-1.v1.events... → cxp-alb.elb...    │ │
│  │             │  Two-hop CNAME chain: global → regional → ALB.  │ │
│  ├────────────┼──────────────────────────────────────────────────┤ │
│  │  A Record   │  CloudFront distributions (static frontend).     │ │
│  │  (Domain →  │  rapid-retail-insights-host → CloudFront IP.     │ │
│  │   IPv4)     │  Route53 Alias A record (not regular A record). │ │
│  ├────────────┼──────────────────────────────────────────────────┤ │
│  │  CNAME      │  ACM certificate validation.                     │ │
│  │  (_acme-    │  _acme-challenge.aws-us-east-1.v1.events... →   │ │
│  │   challenge)│  ACM validation value (proves domain ownership). │ │
│  ├────────────┼──────────────────────────────────────────────────┤ │
│  │  NS Record  │  Nike's nameservers delegate to Route53.         │ │
│  │  (Name      │  origins.nike NS → Route53 nameservers.          │ │
│  │   servers)  │  Not managed in CXP Terraform (Nike DNS team).  │ │
│  ├────────────┼──────────────────────────────────────────────────┤ │
│  │  NOT USED:  │  A (direct IP) — we use CNAME to ALB DNS names  │ │
│  │             │  (ALB IPs change, CNAME always resolves).        │ │
│  │             │  MX — no mail servers in CXP.                    │ │
│  │             │  AAAA — no IPv6 configured.                      │ │
│  └────────────┴──────────────────────────────────────────────────┘ │
│                                                                      │
│  WHY CNAME TO ALB (not A record):                                  │
│  ALB IPs change dynamically (AWS manages the pool).                │
│  A record to a specific IP → breaks when ALB IP changes.          │
│  CNAME to ALB DNS name → always resolves to current ALB IPs.     │
│  Route53 Alias records solve the CNAME-at-apex limitation.        │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Route53 Health Checks — DNS-Level Failover

```
┌──────────────────────────────────────────────────────────────────────┐
│  Route53 Health Checks — DNS Failover                                │
│                                                                      │
│  ATTACHED TO: Latency-routed CNAME records.                        │
│  PURPOSE: If a region is unhealthy, Route53 stops returning it.    │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  aws_route53_health_check:                                  │    │
│  │    fqdn:              events health endpoint FQDN            │    │
│  │    port:              443 (HTTPS)                            │    │
│  │    type:              HTTPS                                  │    │
│  │    resource_path:     /community/events_health_us_east/v1   │    │
│  │    failure_threshold: N consecutive failures → UNHEALTHY    │    │
│  │    request_interval:  30 seconds                            │    │
│  │    regions:           checked from multiple AWS regions      │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  FAILOVER SEQUENCE:                                                 │
│  1. Route53 health checkers (in us-east, us-west, eu-west) ping   │
│     /events_health_us_east/v1 every 30 seconds.                    │
│  2. N consecutive failures → Route53 marks us-east-1 UNHEALTHY.   │
│  3. DNS query for "any.v1.events..." → Route53 SKIPS us-east-1.  │
│  4. Returns aws-us-west-2.v1.events... instead.                    │
│  5. ALL new DNS resolutions go to us-west-2.                      │
│  6. Existing cached DNS entries expire after TTL (300s max).       │
│                                                                      │
│  RECOVERY:                                                          │
│  us-east-1 health check passes → Route53 includes it again.       │
│  Latency routing resumes (East Coast users back to us-east-1).    │
│  Automatic. Zero manual intervention.                              │
└──────────────────────────────────────────────────────────────────────┘
```

---

### DNS Resolution Chain — Full Path for a CXP Request

```
┌──────────────────────────────────────────────────────────────────────┐
│  FULL DNS RESOLUTION: User in NYC → cxp-events                      │
│                                                                      │
│  Step 1: Browser checks its DNS cache.                              │
│  → Miss (first visit or TTL expired).                               │
│                                                                      │
│  Step 2: OS resolver checks /etc/hosts, then system DNS cache.     │
│  → Miss.                                                            │
│                                                                      │
│  Step 3: ISP recursive resolver:                                    │
│  "any.v1.events.community.global.prod.origins.nike"                │
│  → Ask root DNS: "Who handles .nike?"                              │
│  → Root: "Nike's NS servers handle .nike"                          │
│  → Ask Nike NS: "Who handles origins.nike?"                        │
│  → Nike NS: "Route53 nameservers: ns-xxx.awsdns-xxx.com"          │
│                                                                      │
│  Step 4: ISP resolver asks Route53:                                 │
│  → Route53 latency check: NYC resolver → us-east-1 = 5ms ✓       │
│  → Route53 health check: us-east-1 = HEALTHY ✓                    │
│  → Returns CNAME: aws-us-east-1.v1.events...community...           │
│  → TTL: 300 seconds                                                │
│                                                                      │
│  Step 5: ISP resolver resolves the CNAME:                          │
│  "aws-us-east-1.v1.events.community.global.prod.origins.nike"     │
│  → Route53 SIMPLE record → CNAME: cxp-alb.us-east-1.elb.amaz...  │
│                                                                      │
│  Step 6: ISP resolver resolves ALB DNS:                            │
│  "cxp-alb.us-east-1.elb.amazonaws.com"                            │
│  → A records: 54.23.x.x, 54.23.y.y (multiple ALB IPs)            │
│                                                                      │
│  Step 7: Browser connects to 54.23.x.x (ALB IP).                  │
│  → TLS handshake with Akamai PoP (or direct to ALB).              │
│  → HTTP request: GET /community/events/v1/73067                    │
│                                                                      │
│  TOTAL DNS HOPS: root → .nike TLD → Route53 (3 records resolved)  │
│  CACHED: Steps 3-6 cached by ISP resolver for 300 seconds.        │
│  Next request from same network: ~0ms DNS (cached).                │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: DNS Across CXP

| DNS Feature | Implementation | Purpose |
|------------|---------------|---------|
| **Authoritative DNS** | Route53 (Nike-hosted zone) | Serves all CXP domain queries |
| **Record type** | CNAME (primary) | `any.*` → `aws-region.*` → ALB DNS name |
| **Routing policy** | LATENCY (global endpoints) | Route users to nearest healthy region |
| **Routing policy** | SIMPLE (regional endpoints) | Direct CNAME to ALB |
| **Health checks** | HTTPS to `/events_health_us_east/v1` | Trigger failover on region failure |
| **TTL** | 300 seconds (5 minutes) | Balance: fast failover vs query cost |
| **Cert validation** | `_acme-challenge` CNAME | Prove domain ownership for ACM TLS certs |
| **Static frontend** | Route53 Alias A record → CloudFront | SPA hosting (rapid-retail-insights-host) |
| **Record generation** | Terraform `route53_locals.tf` | Auto-generates ~20 records per region |
| **Naming convention** | `any.v1.{service}.community.global.{env}.origins.nike` | Structured, discoverable, env-aware |

---

## Common Interview Follow-ups

### Q: "Why CNAME instead of A records for your services?"

> "ALB IP addresses change dynamically — AWS manages the pool. An A record pointing to a specific IP would break when AWS rotates the ALB IP. A CNAME pointing to the ALB's DNS name (`cxp-alb.us-east-1.elb.amazonaws.com`) always resolves to current IPs because AWS manages the A records for the ALB DNS name. We use a two-hop CNAME chain: `any.v1.events...` → `aws-us-east-1.v1.events...` → `cxp-alb.elb.amazonaws.com`. The first hop provides latency-based routing, the second provides the direct ALB pointer."

### Q: "What happens during the 5-minute TTL window after failover?"

> "Some clients will still resolve to the old (failed) region for up to 300 seconds. Their requests hit the failed ALB → get 503 → browser retries or shows error. Mitigations: (1) Akamai CDN sits in front — CDN PoPs resolve DNS more frequently (shorter internal TTL), so CDN-served traffic fails over faster. (2) Browsers retry on 503 — the retry may get fresh DNS. (3) For critical events (sneaker launches), we could temporarily lower TTL to 60 seconds before the event, reducing the stale DNS window to 1 minute. After the event, raise back to 300s."

### Q: "How do you manage DNS records for 10+ services across 2 regions?"

> "Terraform generates them automatically from a service list. `route53_locals.tf` loops over `var.route53_services` and generates 3 records per service per region (global CNAME, regional CNAME, ACM validation). Adding a new service = adding one entry to the service list. Terraform creates all 3 records in both regions automatically. This eliminates manual DNS management errors — the most common cause of DNS outages is human misconfiguration."

### Q: "DNS vs service discovery — how do they relate?"

> "DNS IS our primary service discovery mechanism (Topic 27). Route53 answers 'where is cxp-events?' at the global level (which region). Inside K8s, Kubernetes DNS answers at the pod level (which pod). ALB answers at the service level (which path → which target group). All three are DNS-based discovery at different scopes. We don't use Consul or Eureka because DNS-based discovery at each layer gives us everything we need — and it's the same protocol (DNS) that the internet already understands."

---
---

# Topic 42: Forward vs Reverse Proxy

> Forward proxy sits in front of clients (VPN, privacy); reverse proxy sits in front of servers (load balancing, SSL termination, caching, security).

> **Interview Tip:** List reverse proxy functions — "NGINX as reverse proxy handles SSL termination, load balancing, caching static content, rate limiting, and hides backend topology from clients."

---

## The Two Types

```
┌──────────────────────────────────────────────────────────────────────────┐
│              FORWARD vs REVERSE PROXY                                     │
│                                                                          │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐    │
│  │    FORWARD PROXY              │  │    REVERSE PROXY              │    │
│  │  Client-side proxy (sits in   │  │  Server-side proxy (sits in  │    │
│  │  front of clients)            │  │  front of servers)           │    │
│  │                               │  │                               │    │
│  │  ┌──────┐  ┌───────┐  ┌───┐ │  │  ┌──────┐ ┌───────┐ ┌─────┐│    │
│  │  │Client│─▶│Forward│─▶│Web│ │  │  │Client│▶│Reverse│▶│Svc 1││    │
│  │  └──────┘  │Proxy  │  │   │ │  │  └──────┘ │Proxy  │ ├─────┤│    │
│  │            └───────┘  └───┘ │  │            │       │ │Svc 2││    │
│  │                               │  │            │       │ ├─────┤│    │
│  │  Use: Privacy, bypass         │  │            └───────┘ │Svc 3││    │
│  │  geo-blocks, caching          │  │                      └─────┘│    │
│  │  VPN, corporate proxy, Squid  │  │  Use: Load balance, SSL     │    │
│  │                               │  │  termination, cache          │    │
│  │  Client KNOWS about the proxy.│  │  NGINX, HAProxy, AWS ALB,   │    │
│  │  Server doesn't know proxy    │  │  Cloudflare                  │    │
│  │  exists.                      │  │                               │    │
│  │                               │  │  Client doesn't know backend │    │
│  └──────────────────────────────┘  │  topology. Server sees proxy, │    │
│                                     │  not client directly.         │    │
│                                     └──────────────────────────────┘    │
│                                                                          │
│  REVERSE PROXY FUNCTIONS:                                               │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐         │
│  │   Load     │ │    SSL     │ │  Caching   │ │  Security  │         │
│  │ Balancing  │ │Termination │ │  static    │ │  Hide      │         │
│  │ Distribute │ │Handle HTTPS│ │  content   │ │  backend,  │         │
│  │ traffic    │ │            │ │            │ │  WAF       │         │
│  └────────────┘ └────────────┘ └────────────┘ └────────────┘         │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐                        │
│  │Compression │ │   Rate     │ │  A/B       │                        │
│  │            │ │  Limiting  │ │  Testing   │                        │
│  └────────────┘ └────────────┘ └────────────┘                        │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Reverse Proxy In My CXP Projects

### CXP Has TWO Reverse Proxies — Stacked

Our platform doesn't use NGINX. Instead, we have **Akamai CDN as the outer reverse proxy** and **AWS ALB as the inner reverse proxy**, each handling different functions.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP — TWO-LAYER REVERSE PROXY                                           │
│                                                                          │
│  Client (browser/mobile)                                                │
│       │                                                                  │
│       ▼                                                                  │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  REVERSE PROXY 1: Akamai CDN (outer / edge)                      │  │
│  │                                                                   │  │
│  │  Functions:                                                      │  │
│  │  ✓ SSL termination (TLS 1.3 from browser)                       │  │
│  │  ✓ DDoS protection / WAF (block attacks at the edge)            │  │
│  │  ✓ Response caching (Edge-Cache-Tag, 1-60 min TTL)              │  │
│  │  ✓ Geographic routing (nearest PoP via anycast)                 │  │
│  │  ✓ Compression (gzip/brotli responses)                          │  │
│  │  ✓ Hides backend (client sees nike.com, not AWS infra)          │  │
│  │                                                                   │  │
│  │  Client sees: nike.com/experiences/event/73067                   │  │
│  │  Client does NOT see: cxp-alb.us-east-1.elb.amazonaws.com      │  │
│  └─────────────────────────────┬────────────────────────────────────┘  │
│                                │                                        │
│                                ▼                                        │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  REVERSE PROXY 2: AWS ALB (inner / regional)                      │  │
│  │                                                                   │  │
│  │  Functions:                                                      │  │
│  │  ✓ SSL termination (HTTPS from Akamai → HTTP to containers)     │  │
│  │  ✓ Path-based routing (/events/* → cxp-events TG)               │  │
│  │  ✓ Load balancing (round-robin across healthy ECS tasks)        │  │
│  │  ✓ Health checks (/actuator/health every 10s)                   │  │
│  │  ✓ Connection draining (graceful shutdown on task removal)      │  │
│  │  ✓ Hides containers (Akamai sees ALB, not individual task IPs)  │  │
│  │                                                                   │  │
│  │  Akamai sees: cxp-alb.us-east-1.elb.amazonaws.com               │  │
│  │  Akamai does NOT see: 10.0.3.47:8080 (individual ECS task IP)   │  │
│  └─────────────────────────────┬────────────────────────────────────┘  │
│                                │                                        │
│                                ▼                                        │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  BACKEND: ECS Tasks / NPE Pods (the actual services)             │  │
│  │                                                                   │  │
│  │  cxp-events:8080   cxp-reg:8080   expviews:8080   rise-gts:8080│  │
│  │  (plain HTTP — no proxy responsibilities)                        │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  WHAT THE CLIENT SEES:                                                  │
│  nike.com → single, clean domain. No idea about:                       │
│  - 250+ Akamai PoPs worldwide                                          │
│  - 2 AWS regions (us-east, us-west)                                    │
│  - 4 microservices behind one ALB                                      │
│  - 2-8 ECS tasks per service                                           │
│  - Redis, DynamoDB, Elasticsearch, Kafka behind the services           │
│  The reverse proxy layers ABSTRACT all backend complexity.             │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Function-by-Function: Which Proxy Does What

```
┌──────────────────────────────────────────────────────────────────────┐
│  REVERSE PROXY FUNCTION SPLIT                                        │
│                                                                      │
│  ┌──────────────────────┬──────────────┬──────────────┐            │
│  │  Function             │  Akamai (L1) │  ALB (L2)    │            │
│  ├──────────────────────┼──────────────┼──────────────┤            │
│  │  SSL termination      │  ✓ (edge TLS │  ✓ (HTTPS    │            │
│  │                       │  from browser)│  from Akamai)│            │
│  │                       │              │  → HTTP:8080  │            │
│  ├──────────────────────┼──────────────┼──────────────┤            │
│  │  Load balancing       │  ✓ (across   │  ✓ (across   │            │
│  │                       │  PoPs via    │  ECS tasks   │            │
│  │                       │  anycast)    │  round-robin)│            │
│  ├──────────────────────┼──────────────┼──────────────┤            │
│  │  Response caching     │  ✓ (60m/1m   │  ✗ (ALB      │            │
│  │                       │  per resource)│  doesn't     │            │
│  │                       │              │  cache)       │            │
│  ├──────────────────────┼──────────────┼──────────────┤            │
│  │  Path-based routing   │  ✗ (routes   │  ✓ (/events  │            │
│  │                       │  to ALB,     │  → TG1,      │            │
│  │                       │  not per-svc)│  /reg → TG2) │            │
│  ├──────────────────────┼──────────────┼──────────────┤            │
│  │  DDoS / WAF           │  ✓ (edge WAF │  ✗ (no WAF   │            │
│  │                       │  rules, bot  │  on ALB)      │            │
│  │                       │  detection)  │              │            │
│  ├──────────────────────┼──────────────┼──────────────┤            │
│  │  Health checks        │  ✗ (Akamai   │  ✓ (/actuator│            │
│  │                       │  doesn't     │  /health     │            │
│  │                       │  check tasks)│  per task)   │            │
│  ├──────────────────────┼──────────────┼──────────────┤            │
│  │  Hide backend topology│  ✓ (client   │  ✓ (Akamai   │            │
│  │                       │  sees nike   │  sees ALB,    │            │
│  │                       │  .com only)  │  not tasks)  │            │
│  ├──────────────────────┼──────────────┼──────────────┤            │
│  │  Compression          │  ✓ (gzip/    │  ✗            │            │
│  │                       │  brotli)     │              │            │
│  ├──────────────────────┼──────────────┼──────────────┤            │
│  │  Request rate limiting│  ✓ (IP-based │  ✗ (ALB has   │            │
│  │                       │  WAF rules)  │  no rate     │            │
│  │                       │              │  limiting)   │            │
│  ├──────────────────────┼──────────────┼──────────────┤            │
│  │  Connection draining  │  ✗            │  ✓ (drains   │            │
│  │                       │              │  before task │            │
│  │                       │              │  removal)    │            │
│  └──────────────────────┴──────────────┴──────────────┘            │
│                                                                      │
│  COMBINED: Akamai + ALB together provide ALL reverse proxy functions│
│  that NGINX would provide as a single component.                   │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "Our platform uses a two-layer reverse proxy. Akamai CDN is the outer proxy: SSL termination from the browser, DDoS/WAF protection, response caching (60-minute TTL for event pages, 1-minute for seat counts), and compression. AWS ALB is the inner proxy: SSL termination from Akamai, path-based routing to 4 microservices, round-robin load balancing across healthy ECS tasks, and health checks. Together they provide everything NGINX would — but distributed across a managed CDN and a managed load balancer, with no single NGINX instance to operate. The client sees only `nike.com` — completely unaware of 250+ CDN PoPs, 2 AWS regions, 4 services, and 2-8 containers per service behind the proxies."

---

### NPE Ingress — Kubernetes-Level Reverse Proxy

For NPE-hosted services, there's a **third proxy layer**: the K8s Ingress controller.

```
┌──────────────────────────────────────────────────────────────────────┐
│  NPE INGRESS — Kubernetes Reverse Proxy                              │
│                                                                      │
│  Client → Akamai → ALB → NPE Ingress → Pod                        │
│                            ▲                                        │
│                            │ This is the K8s reverse proxy          │
│                                                                      │
│  NPE Ingress handles:                                               │
│  ✓ Path prefix routing (/community/events/v1 → cxp-events pods)   │
│  ✓ TLS termination (private CA certificates)                       │
│  ✓ Load balancing across pods (K8s Service)                        │
│  ✓ Health-aware routing (only to pods passing readiness probe)     │
│                                                                      │
│  Defined in NPE component YAML:                                    │
│  routing:                                                           │
│    paths:                                                           │
│      prefix:                                                        │
│        - path: /community/events/v1         ← path-based routing   │
│        - path: /community/event_seats_status/v1                    │
│        - path: /community/events_health/v1                         │
│                                                                      │
│  THREE REVERSE PROXIES DEEP:                                        │
│  Akamai (edge caching + WAF)                                       │
│    → ALB (regional routing + health checks)                        │
│      → NPE Ingress (K8s pod routing + liveness/readiness)          │
│        → Pod (cxp-events container, port 8080)                     │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Forward Proxy in CXP — Not Used (But Know When You Would)

```
┌──────────────────────────────────────────────────────────────────────┐
│  FORWARD PROXY — Not in CXP (When You'd Use One)                    │
│                                                                      │
│  CXP has NO forward proxy. Our services call external APIs          │
│  (Eventtia, NCP, Pairwise) directly via HTTPS.                     │
│                                                                      │
│  WHEN YOU'D ADD A FORWARD PROXY:                                    │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  1. CORPORATE EGRESS CONTROL                                │    │
│  │     All outbound traffic goes through a proxy.              │    │
│  │     Block calls to unauthorized domains.                    │    │
│  │     Log all external API calls for compliance.              │    │
│  │     Example: Squid proxy on EC2 in the VPC.                 │    │
│  │                                                              │    │
│  │  2. OUTBOUND IP WHITELISTING                                │    │
│  │     External API requires static IP (Eventtia firewall).    │    │
│  │     ECS tasks have dynamic IPs → can't whitelist.           │    │
│  │     NAT Gateway provides static outbound IP.                │    │
│  │     (NAT Gateway is essentially a forward proxy for IPs.)   │    │
│  │                                                              │    │
│  │  3. CACHING EXTERNAL API RESPONSES                          │    │
│  │     Proxy caches Eventtia responses → reduces external calls.│   │
│  │     We use Redis/Caffeine for this instead (in-app cache).  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  OUR EQUIVALENT OF FORWARD PROXY FUNCTIONS:                        │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Egress control: VPC security groups + IAM policies         │    │
│  │  Static IP: NAT Gateway in the VPC                          │    │
│  │  Response caching: Redis + Caffeine (in-app)                │    │
│  │  External API logging: Splunk (all HTTP calls logged)       │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Proxies Across CXP

| Layer | Component | Type | Functions |
|-------|-----------|------|-----------|
| **Edge** | Akamai CDN | Reverse proxy (outer) | SSL termination, DDoS/WAF, response caching, compression, geo-routing, hide backend |
| **Regional** | AWS ALB | Reverse proxy (inner) | SSL termination, path-based routing, load balancing, health checks, connection draining |
| **Platform** | NPE Ingress | Reverse proxy (K8s) | Path routing, pod-level LB, liveness/readiness, private TLS |
| **Outbound** | NAT Gateway | Forward proxy (implicit) | Static outbound IP for external API calls |
| **Not used** | NGINX / HAProxy | N/A | Replaced by managed services (Akamai + ALB + NPE) |

---

## Common Interview Follow-ups

### Q: "Why not NGINX instead of Akamai + ALB?"

> "Three reasons: (1) **Global edge caching** — NGINX on EC2 caches in one location. Akamai caches at 250+ PoPs worldwide. A user in Tokyo gets a cached response from a local PoP (~15ms) vs NGINX in us-east-1 (~200ms). (2) **DDoS protection** — NGINX requires manual WAF configuration and can be overwhelmed by volumetric attacks. Akamai absorbs attacks at the edge before traffic reaches AWS. (3) **Operational overhead** — NGINX requires us to manage instances, scaling, patching, TLS cert rotation. Akamai and ALB are managed services with 99.99% SLA. We'd use NGINX if we needed custom request transformation (header rewriting, body modification) that ALB can't do — but our API design doesn't require it."

### Q: "How does the reverse proxy hide your backend?"

> "The client sees `nike.com/experiences/event/73067`. They have no idea that: (1) Akamai has 250+ edge locations caching this response, (2) behind Akamai is an ALB in us-east-1 or us-west-2, (3) the ALB routes `/community/events/*` to one service and `/community/event_registrations/*` to another, (4) each service has 2-8 containers running on ECS/NPE, (5) behind the services are Redis, DynamoDB, Elasticsearch, Kafka, and Eventtia. The reverse proxy abstracts this entire topology into one clean URL. If we refactor from 4 services to 10, or migrate from ECS to Kubernetes, or add a third AWS region — the client URL doesn't change."

### Q: "What's the difference between a reverse proxy and an API gateway?"

> "Reverse proxy operates at the HTTP level — route requests, terminate SSL, cache responses. API gateway adds API-specific logic — JWT validation, rate limiting per API key, usage plans, request transformation, SDK generation. Our ALB is a reverse proxy (path routing, SSL, health checks). Our in-service auth (AAAConfig, @JwtScope) adds API gateway logic. Together, ALB + in-service logic = the equivalent of an API gateway like Kong (Topic 28). The distinction blurs in practice — Kong IS a reverse proxy with API gateway plugins. Our distributed approach separates these responsibilities across Akamai (edge proxy) + ALB (routing proxy) + application code (API logic)."

---
---

# Topic 43: Bloom Filters

> Space-efficient probabilistic structure: "definitely not in set" or "possibly in set." False positives possible, false negatives impossible. O(k) lookup.

> **Interview Tip:** Apply practically — "Before querying database for username availability, I'd check bloom filter first — if it says 'not present,' skip the DB query entirely."

---

## How Bloom Filters Work

```
┌──────────────────────────────────────────────────────────────────────┐
│  BLOOM FILTER                                                        │
│                                                                      │
│  Space-efficient probabilistic data structure to test if element     │
│  is "possibly in set" or "definitely not."                          │
│  False positives possible, false negatives impossible.              │
│                                                                      │
│  HOW IT WORKS:                                                      │
│                                                                      │
│  Bit Array:  [0][1][0][1][1][0][1][0]                              │
│               0  1  2  3  4  5  6  7                                │
│                                                                      │
│  Add "apple":                                                       │
│    h1("apple") = 1  → set bit 1                                    │
│    h2("apple") = 4  → set bit 4                                    │
│    h3("apple") = 6  → set bit 6                                    │
│                                                                      │
│  Check "banana": h1=2, h2=4, h3=7                                  │
│    Bit 7 is 0 → DEFINITELY NOT IN SET ✓ (no false negative)       │
│                                                                      │
│  Check "grape": h1=1, h2=4, h3=6                                   │
│    All bits are 1 → POSSIBLY IN SET (maybe false positive!)        │
│    "grape" was never added, but its hash bits overlap with "apple" │
│                                                                      │
│  KEY PROPERTIES:                                                    │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  "Not in set"    → GUARANTEED correct (no false negatives)  │    │
│  │  "In set"        → PROBABLY correct (false positives exist) │    │
│  │  False positive rate → tunable by increasing bit array size │    │
│  │  Cannot delete    → bits shared across elements              │    │
│  │  O(k) lookup     → k = number of hash functions (constant)  │    │
│  │  Space: bits, not full data → 10× smaller than a hash set  │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## When to Use Bloom Filters

```
┌──────────────────────────────────────────────────────────────────────┐
│  USE CASES                          │  TRADEOFFS                    │
│                                      │                               │
│  - Cache: Check if key exists        │  [+] Very space efficient     │
│    before DB query.                  │      (bits vs full data)      │
│  - Spam filter: Is email in          │  [+] O(k) lookup — constant  │
│    spam list?                        │      time                     │
│  - Web crawler: Already visited      │  [-] Cannot delete elements  │
│    this URL?                         │  [-] False positives (tunable │
│  - CDN: Is this content cached?      │      with size)              │
│  - Database: Is this key in this     │                               │
│    SSTable? (Cassandra, LevelDB)     │                               │
│                                      │                               │
│  Used by: Chrome safe browsing,      │  FORMULA:                    │
│  Medium, Cassandra, Redis,           │  bits = -n×ln(p) / (ln2)²   │
│  Bitcoin, Akamai                     │  n = items, p = false pos rate│
└──────────────────────────────────────────────────────────────────────┘
```

---

## Bloom Filters In My CXP Projects — Where They Run (and Where They Should)

### CXP Doesn't Use Bloom Filters Directly — But They're Inside Our Stack

Our application code doesn't implement bloom filters, but they run **inside the managed services we use**:

```
┌──────────────────────────────────────────────────────────────────────────┐
│  BLOOM FILTERS IN CXP'S INFRASTRUCTURE (under the hood)                   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  1. DynamoDB — Internal bloom filters per SSTable                 │  │
│  │                                                                   │  │
│  │  DynamoDB storage engine uses bloom filters on each SSTable:     │  │
│  │  GET key "73067_uuid-1234"                                       │  │
│  │  → Bloom filter: "Is this key POSSIBLY in this SSTable?"         │  │
│  │  → "Definitely not" → skip this SSTable entirely (no disk read) │  │
│  │  → "Possibly yes" → read SSTable to confirm                     │  │
│  │                                                                   │  │
│  │  Without bloom filter: every GET reads EVERY SSTable → slow.    │  │
│  │  With bloom filter: skip 90%+ of SSTables → fast.               │  │
│  │  This is WHY DynamoDB single-digit ms reads are possible.       │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  2. Elasticsearch — Bloom filters on term dictionaries           │  │
│  │                                                                   │  │
│  │  ES uses bloom filters (optionally) to check if a term exists   │  │
│  │  in a segment's term dictionary before seeking on disk.          │  │
│  │  Search for "Nike running Portland" across 5 shards:            │  │
│  │  → Each shard checks bloom filter: "Does 'Portland' exist       │  │
│  │    in this segment?" → skip segments where it doesn't.         │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  3. Akamai CDN — Content fingerprinting                          │  │
│  │                                                                   │  │
│  │  CDN PoPs may use bloom filters to quickly check:               │  │
│  │  "Is this URL possibly cached on this PoP?"                     │  │
│  │  → "Definitely not" → forward to origin immediately             │  │
│  │  → "Possibly" → check actual cache storage                      │  │
│  │  Avoids expensive disk lookups for URLs never cached.           │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Where CXP SHOULD Use Bloom Filters (Improvement Opportunities)

```
┌──────────────────────────────────────────────────────────────────────┐
│  OPPORTUNITY 1: Email Drop Deduplication                             │
│                                                                      │
│  CURRENT: Reconciliation tab queries Athena for ALL registrations   │
│  then queries Splunk for ALL deliveries, compares both sets.        │
│  Cost: Athena scans full table ($5/TB), Splunk searches all logs.  │
│                                                                      │
│  WITH BLOOM FILTER:                                                 │
│  Build bloom filter from Splunk delivery results (upmIds who       │
│  received email). Then for each Athena registration:               │
│  → Check bloom filter: "Did this user receive email?"              │
│  → "Definitely not" → add to missing list (guaranteed correct)     │
│  → "Possibly yes" → skip (might be false positive, but acceptable  │
│    since we're looking for MISSING users, not delivered ones)      │
│                                                                      │
│  Benefit: Avoids O(n²) set comparison. O(n) with constant lookup.  │
│  Space: 150 users × ~10 bits = ~200 bytes (vs full hash set).     │
│  For CXP's scale (~150 users per event), hash set is fine.        │
│  Bloom filter shines at 1M+ users (10MB vs 100MB hash set).       │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  OPPORTUNITY 2: Redis Cache Miss Prevention                          │
│                                                                      │
│  CURRENT: Every request checks Redis for pairwise ID.               │
│  Cache miss → call Partner API → populate Redis.                   │
│  For a BRAND NEW USER (never registered): Redis miss is guaranteed.│
│  Partner API call is wasted if we KNOW this user never had a       │
│  pairwise ID cached.                                               │
│                                                                      │
│  WITH BLOOM FILTER:                                                 │
│  Maintain bloom filter of all upmIds that have been cached before. │
│  New request for upmId "uuid-9999":                                │
│  → Bloom filter: "Has uuid-9999 ever been cached?"                 │
│  → "Definitely not" → skip Redis entirely, go to Partner API.      │
│  → "Possibly yes" → check Redis (might be cached).                 │
│                                                                      │
│  Benefit: Skip 1 Redis round-trip (~1ms) for guaranteed-new users. │
│  For CXP's scale: minimal benefit (Redis is already <1ms).        │
│  For a platform with 100M users and expensive cache misses: major. │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  OPPORTUNITY 3: Idempotency Pre-Check (Redis Alternative)            │
│                                                                      │
│  CURRENT: Redis stores idempotency keys with TTL.                  │
│  Every registration checks Redis: "Has this user+event registered?" │
│                                                                      │
│  WHAT IF REDIS IS DOWN:                                             │
│  Fallback: proceed to Eventtia (which catches duplicates via 422). │
│                                                                      │
│  WITH BLOOM FILTER (local, in-memory, per-task):                   │
│  Each ECS task maintains a local bloom filter of recent keys.      │
│  Even when Redis is down:                                          │
│  → Bloom filter: "Has uuid-1234_73067 been seen in last 5 min?"   │
│  → "Definitely not" → proceed (new registration).                  │
│  → "Possibly yes" → likely duplicate → reject immediately.         │
│                                                                      │
│  Tradeoff: Local per-task (not shared across tasks).               │
│  Bot hitting different tasks wouldn't be caught (only per-task).   │
│  But: better than NO idempotency check when Redis is down.        │
│  Space: 10,000 keys × 10 bits/key = ~12 KB per task.              │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Bloom Filter vs CXP's Existing Patterns

```
┌──────────────────────────────────────────────────────────────────────┐
│  BLOOM FILTER vs WHAT CXP USES TODAY                                 │
│                                                                      │
│  ┌────────────────────┬────────────────┬───────────────────────┐   │
│  │  Problem            │  CXP Today     │  With Bloom Filter     │   │
│  ├────────────────────┼────────────────┼───────────────────────┤   │
│  │  Duplicate request  │  Redis key     │  Bloom filter: O(k)   │   │
│  │  detection          │  lookup: O(1)  │  Space: 12KB vs 1MB   │   │
│  │                     │  (shared across│  (local per-task, no   │   │
│  │                     │  all tasks)    │  Redis dependency)     │   │
│  │                     │                │  Trade: false positives│   │
│  │                     │                │  (reject valid request │   │
│  │                     │                │  ~0.1% of time)        │   │
│  ├────────────────────┼────────────────┼───────────────────────┤   │
│  │  "Has this user     │  Athena query  │  Bloom filter: O(k)   │   │
│  │  been processed?"   │  O(n) scan     │  Pre-built from       │   │
│  │                     │  $5/TB cost    │  previous results.     │   │
│  │                     │                │  Skip Athena for       │   │
│  │                     │                │  "definitely not."     │   │
│  ├────────────────────┼────────────────┼───────────────────────┤   │
│  │  "Is URL cached?"   │  CDN cache     │  Bloom filter avoids  │   │
│  │  (Akamai internal)  │  lookup (disk) │  disk seek for         │   │
│  │                     │                │  definitely-not-cached │   │
│  │                     │                │  URLs.                 │   │
│  └────────────────────┴────────────────┴───────────────────────┘   │
│                                                                      │
│  VERDICT FOR CXP:                                                   │
│  Our scale (~10K registrations per sneaker launch) doesn't justify │
│  bloom filters over Redis/Athena. Redis is already <1ms.          │
│  At 1M+ operations/second or 100M+ unique keys: bloom filters     │
│  become essential (Cassandra, Chrome safe browsing, Bitcoin).      │
│  Understanding bloom filters matters for DESIGN INTERVIEWS even    │
│  when your current project doesn't need them.                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Bloom Filters and CXP

| Aspect | Details |
|--------|---------|
| **Used directly in CXP code?** | No — our scale doesn't require it |
| **Running inside our stack?** | Yes — DynamoDB SSTable filters, Elasticsearch segment filters, possibly Akamai |
| **Best opportunity** | Local per-task idempotency backup when Redis is down (~12KB bloom filter) |
| **Why not needed now** | Redis idempotency is <1ms, shared across tasks, and has Eventtia 422 as backup |
| **When it becomes essential** | 1M+ unique keys, 100K+ lookups/sec, or when saving a DB round-trip matters (Cassandra, Chrome) |

---

## Common Interview Follow-ups

### Q: "False positives in a bloom filter — isn't that a problem?"

> "It depends on the consequence. For our idempotency check, a false positive means rejecting a legitimate first-time registration (~0.1% with proper sizing). That's unacceptable for user-facing registration — which is why we use Redis (exact match, no false positives) instead of a bloom filter. But for a 'should I check the database?' pre-filter, a false positive just means an unnecessary DB query — harmless. The rule: use bloom filters when 'definitely not' saves expensive work, and 'possibly yes' just falls through to the real check."

### Q: "How do you size a bloom filter?"

> "Formula: `bits = -(n × ln(p)) / (ln(2))²` where n = expected items, p = desired false positive rate. For 10,000 registrations with 1% false positive rate: bits = -(10000 × ln(0.01)) / (ln(2))² ≈ 96,000 bits = 12 KB. Number of hash functions: k = (m/n) × ln(2) ≈ 7. So: 12 KB of memory, 7 hash computations per lookup, 1% false positive rate, O(1) check. Compare: a HashSet of 10,000 UUIDs = ~500 KB. Bloom filter is 40× smaller."

### Q: "Can you delete from a bloom filter?"

> "Standard bloom filters don't support deletion because bits are shared across multiple elements — clearing a bit for element A might invalidate element B. Solutions: (1) **Counting bloom filter** — each position is a counter (4 bits) instead of a single bit. Increment on add, decrement on remove. 4× the space but supports deletion. (2) **Cuckoo filter** — alternative data structure that supports deletion with similar space efficiency. (3) **Rebuild** — for our TTL-based use case, rebuild the bloom filter every N minutes from the current key set. Old keys naturally disappear."

### Q: "Where do bloom filters fit in the system design interview?"

> "Three classic scenarios: (1) **Duplicate detection at scale** — 'Check if this URL was crawled among 1 billion URLs.' Bloom filter: 1 GB instead of 40 GB hash set. (2) **Cache penetration prevention** — 'Attackers query for non-existent keys, bypassing cache, hammering DB.' Bloom filter: if key is 'definitely not' in the DB, return 404 without querying. (3) **Distributed counting** — 'How many unique users visited today?' HyperLogLog (probabilistic cousin) — but bloom filter for 'has THIS specific user visited?'. In our CXP context: DynamoDB and Elasticsearch use bloom filters internally, which is why they're fast. Understanding this explains system design performance characteristics."

---
---

# Topic 44: Geohashing & Quadtrees

> Geohash encodes lat/lng to string (same prefix = nearby); quadtree recursively divides space into quadrants. Both enable efficient proximity searches.

> **Interview Tip:** Choose appropriately — "For Uber driver matching, I'd use geohash with Redis GEORADIUS for O(1) nearby lookups; quadtree for variable-density data like game maps."

---

## The Two Approaches

```
┌──────────────────────────────────────────────────────────────────────────┐
│              GEOHASHING & QUADTREES                                       │
│  Encode geographic coordinates into short strings for proximity searches │
│                                                                          │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐    │
│  │         GEOHASH               │  │         QUADTREE              │    │
│  │                               │  │                               │    │
│  │  lat: 37.7749, lng: -122.4194│  │  Recursively divide into     │    │
│  │  → "9q8yyk8"                 │  │  4 quadrants.                 │    │
│  │                               │  │                               │    │
│  │  ┌───┬───┬───┐              │  │  ┌──┬──┐                     │    │
│  │  │ 9q│ 9r│ 9x│              │  │  │• │  │  Subdivide only     │    │
│  │  ├───┼───┼───┤              │  │  ├──┼──┤  where needed        │    │
│  │  │9q8│   │   │              │  │  │  │ •│  (adaptive).         │    │
│  │  │ y •   │   │              │  │  └──┴──┘                     │    │
│  │  └───┴───┴───┘              │  │                               │    │
│  │                               │  │  [+] Dynamic, handles        │    │
│  │  Longer hash = more precise  │  │      clusters well           │    │
│  │  Same prefix = nearby        │  │  [-] More complex to         │    │
│  │  "9q8y*" = all in same cell  │  │      implement               │    │
│  │                               │  │                               │    │
│  │  [+] Single index, prefix    │  │  Used by: game maps,          │    │
│  │      search                  │  │  collision detection,         │    │
│  │  [-] Edge cases at cell      │  │  spatial databases            │    │
│  │      boundaries              │  │                               │    │
│  └──────────────────────────────┘  └──────────────────────────────┘    │
│                                                                          │
│  USE CASES:                                                             │
│  Uber/Lyft: Find nearby drivers      Redis: GEOADD, GEORADIUS         │
│  Yelp: Restaurants near me           PostGIS, Elasticsearch geo        │
│  Tinder: Matches in radius           Gaming: Players in region         │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## How Each Works

### Geohash — Encode Location to String

```
┌──────────────────────────────────────────────────────────────────────┐
│  GEOHASH — Encoding lat/lng to a string                              │
│                                                                      │
│  Input:  lat = 37.7749, lng = -122.4194 (San Francisco)            │
│  Output: "9q8yyk8" (7-character geohash)                            │
│                                                                      │
│  HOW IT WORKS:                                                      │
│  1. Divide world in half by longitude (left/right = 0/1)           │
│  2. Divide in half by latitude (bottom/top = 0/1)                  │
│  3. Interleave bits: lon-bit, lat-bit, lon-bit, lat-bit...         │
│  4. Convert binary to base-32 string                               │
│                                                                      │
│  PRECISION:                                                         │
│  ┌──────────┬────────────────┬────────────────────┐               │
│  │  Length   │  Cell Size     │  Example            │               │
│  ├──────────┼────────────────┼────────────────────┤               │
│  │  1 char  │  ~5000 km      │  "9" = western US   │               │
│  │  3 chars │  ~78 km        │  "9q8" = Bay Area    │               │
│  │  5 chars │  ~2.4 km       │  "9q8yy" = SF city  │               │
│  │  7 chars │  ~76 m         │  "9q8yyk8" = block   │               │
│  │  9 chars │  ~2.4 m        │  precise location    │               │
│  └──────────┴────────────────┴────────────────────┘               │
│                                                                      │
│  KEY PROPERTY: Same prefix = nearby.                                │
│  "9q8yyk8" and "9q8yyk9" are adjacent cells.                      │
│  → Prefix search in database: WHERE geohash LIKE '9q8yy%'         │
│  → Returns everything within ~2.4 km. O(1) index lookup.          │
│                                                                      │
│  EDGE CASE: Two points across a cell boundary may have different   │
│  prefixes despite being 10 meters apart.                           │
│  Solution: Search the target cell + 8 neighboring cells.           │
└──────────────────────────────────────────────────────────────────────┘
```

### Quadtree — Recursive Spatial Partitioning

```
┌──────────────────────────────────────────────────────────────────────┐
│  QUADTREE — Divide space recursively                                 │
│                                                                      │
│  Start with entire map as one cell.                                 │
│  If cell contains > N points: subdivide into 4 quadrants.          │
│  Repeat until each cell has ≤ N points.                             │
│                                                                      │
│     ┌─────────────────┐     ┌────────┬────────┐                    │
│     │ • •             │     │ •  •   │        │                    │
│     │     •           │ →   ├────┬───┤        │                    │
│     │           •     │     │    │ • │   •    │                    │
│     │              •  │     │    │   │        │                    │
│     └─────────────────┘     └────┴───┴────────┘                    │
│      Too many points           Subdivided where dense              │
│                                                                      │
│  ADVANTAGES:                                                        │
│  ✓ Adapts to data density (NYC = deep tree, Sahara = shallow)     │
│  ✓ Efficient range queries (prune entire subtrees)                 │
│  ✓ Dynamic: insert/delete points, tree rebalances                 │
│                                                                      │
│  DISADVANTAGES:                                                     │
│  ✗ More complex to implement than geohash                          │
│  ✗ Tree structure harder to store in relational DB                 │
│  ✗ Not a single-column index (unlike geohash string)              │
│                                                                      │
│  BEST FOR: Variable-density data (cities vs rural),                │
│  game collision detection, spatial databases (PostGIS).            │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Geospatial Search In My CXP Projects

### Elasticsearch `geo_point` — How CXP Does Location-Based Event Search

**Service:** `expviewsnikeapp`
**Index:** `pg_eventcard`
**Field:** `location_geo_point` (type: `geo_point`)

Our event search supports "Nike events near me" using Elasticsearch's built-in geo-distance queries — which internally use a variant of geohashing (BKD tree on encoded geo coordinates).

```
┌──────────────────────────────────────────────────────────────────────┐
│  CXP EVENT SEARCH — Geo-Distance Queries                            │
│                                                                      │
│  USER QUERY: "Show me Nike events near Portland, OR"                │
│  lat: 45.5152, lng: -122.6784                                      │
│                                                                      │
│  ELASTICSEARCH DOES:                                                │
│  1. Encode query point to internal geo format (BKD tree range)     │
│  2. Search pg_eventcard index for documents where                  │
│     location_geo_point is within N km of query point              │
│  3. Sort by distance (nearest first)                               │
│  4. Return matching events with distance                           │
│                                                                      │
│  UNDER THE HOOD (how ES stores geo_point):                         │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ES encodes lat/lng as a single long value using a          │    │
│  │  space-filling curve (Morton code / Z-order curve).         │    │
│  │  This is conceptually similar to geohashing:                │    │
│  │  - Interleave lat/lng bits                                  │    │
│  │  - Store as a single numeric value                          │    │
│  │  - Points nearby in 2D space are nearby in 1D index         │    │
│  │  - BKD tree indexes the encoded values for range queries    │    │
│  │                                                              │    │
│  │  The BKD tree is like a quadtree for on-disk storage:       │    │
│  │  - Binary tree that recursively partitions the space        │    │
│  │  - Efficient for range queries ("all points in this box")   │    │
│  │  - Block-based for disk I/O efficiency                      │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  QUERY TYPES SUPPORTED:                                             │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  geo_distance:  "Events within 10km of Portland"            │    │
│  │  geo_bounding_box: "Events in this rectangle on the map"    │    │
│  │  geo_polygon: "Events within this custom area"              │    │
│  │  geo_shape: "Events within this complex geometry"           │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual Elasticsearch mapping:**

```json
// pg_eventcard/mappings.json — geo_point field
{
  "mappings": {
    "properties": {
      "location_geo_point": {
        "type": "geo_point"       // lat/lng stored as BKD-indexed geo
      },
      "category_name": { "type": "text" },
      "date_utc_event_end": { "type": "date" },
      "event_language": { "type": "text", "fields": { "keyword": { "type": "keyword" } } }
    }
  }
}
```

**From the actual Java code — location-based query building:**

```java
// SearchQueryHelper.java — building geo + filter queries
public QueryBuilder buildCardsByLocationQuery(
        FilterScope filterScope, String city, String province,
        String country, String lang, String locale) {

    BoolQueryBuilder rootQuery = QueryBuilders.boolQuery();
    BoolQueryBuilder physicalEventQuery = QueryBuilders.boolQuery();
    BoolQueryBuilder virtualEventQuery = QueryBuilders.boolQuery();

    // Date range: events ending AFTER now (future events only)
    physicalEventQuery.must(QueryBuilders.rangeQuery("date_utc_event_end")
        .gt("now").lt(dateBefore).timeZone("UTC"));

    // Event type: physical events (not virtual)
    physicalEventQuery.must(QueryBuilders.termQuery(
        "event_validation_type_id", PHYSICAL_EVENT_MAGIC_NUMBER));

    // Language filter
    TermQueryBuilder langTerm = QueryBuilders.termQuery(
        "event_language.keyword", lang);

    // Location: filtered by city/province/country
    // The geo_point field enables distance-based queries on top of this
    // ES handles the spatial indexing via BKD tree internally
}
```

**Interview answer:**
> "Our event search uses Elasticsearch's `geo_point` field type on the `pg_eventcard` index. When a user searches for 'Nike events near Portland,' ES internally encodes the lat/lng using a space-filling curve (similar to geohashing) and stores it in a BKD tree (similar to a quadtree). The BKD tree enables efficient range queries: 'all events within this bounding box' prunes 90%+ of documents without checking each one. We combine geo-distance with boolean filters — date range (future events only), language, and event type (physical vs virtual) — in a single `BoolQuery` that ES parallelizes across 5 shards."

---

### Akamai CDN — Geo-Routing as Implicit Geospatial

```
┌──────────────────────────────────────────────────────────────────────┐
│  Akamai PoP Selection — Geospatial Routing Without Geohash          │
│                                                                      │
│  When a user requests nike.com/experiences/event/73067:             │
│                                                                      │
│  Akamai uses ANYCAST DNS to route to the nearest PoP.              │
│  This is geospatial routing without explicit geohashing:           │
│                                                                      │
│  User in Tokyo (lat: 35.68, lng: 139.69)                          │
│  → Anycast DNS → Tokyo PoP (geographically closest)               │
│  → If Tokyo PoP has cached content → serve immediately (~15ms)    │
│  → If not → fetch from origin (us-east-1) → cache → serve         │
│                                                                      │
│  This is the CDN equivalent of "find nearest server" —             │
│  the same problem Uber solves with geohashing for drivers.         │
│  Akamai solves it with BGP anycast routing at the network layer.  │
│                                                                      │
│  Route53 latency-based routing is ALSO geospatial:                 │
│  "Which AWS region is closest to this user?"                       │
│  → Measured by network latency, not geographic distance            │
│  → But strongly correlated (closer = lower latency usually)       │
└──────────────────────────────────────────────────────────────────────┘
```

---

### When CXP Would Need Explicit Geohashing

```
┌──────────────────────────────────────────────────────────────────────┐
│  SCENARIOS WHERE CXP WOULD NEED GEOHASHING                          │
│                                                                      │
│  SCENARIO 1: "Show events within 5km of my current location"       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Current: ES geo_point handles this natively.                │    │
│  │  No explicit geohash needed — ES BKD tree is sufficient.    │    │
│  │                                                              │    │
│  │  If we needed this in REDIS (not ES):                        │    │
│  │  Redis GEOADD events 45.5152 -122.6784 "event-73067"       │    │
│  │  Redis GEORADIUS events 45.5152 -122.6784 5 km               │    │
│  │  → Returns all events within 5km, sorted by distance.       │    │
│  │  Redis uses geohash internally for this (52-bit geohash).   │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  SCENARIO 2: "Real-time event check-in — find who's near venue"   │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Attendees send their lat/lng every 30 seconds.              │    │
│  │  Server needs: "Who is within 100m of the event venue?"      │    │
│  │                                                              │    │
│  │  Approach: Redis GEOADD + GEORADIUS                          │    │
│  │  → GEOADD attendees {lat} {lng} "user-uuid-1234"            │    │
│  │  → GEORADIUS attendees {venue_lat} {venue_lng} 100 m         │    │
│  │  → Returns all users within 100m of venue.                  │    │
│  │                                                              │    │
│  │  Redis geohash: O(log(N) + M) — N = total users, M = nearby│    │
│  │  For 10,000 attendees: ~1ms to find all within 100m.        │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  SCENARIO 3: "Marketplace-based event filtering"                   │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Current: Filter by marketplace string (US, ASTLA, PH, MX). │    │
│  │  This is a DISCRETE geo filter, not continuous distance.     │    │
│  │                                                              │    │
│  │  Geohash alternative: encode event location as geohash,     │    │
│  │  user location as geohash, find events with matching prefix.│    │
│  │  → More granular than marketplace (city-level instead of     │    │
│  │    country-level). But marketplace filter is simpler for     │    │
│  │    our use case (Nike organizes events by marketplace).      │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Geospatial in CXP

| Component | Geospatial Technique | How It's Used |
|-----------|---------------------|-------------|
| **Elasticsearch** `geo_point` | BKD tree (geohash-like encoding) | Event search: "Nike events near Portland" — distance queries across 5 shards |
| **Akamai CDN** | Anycast routing (network-level geo) | Route users to nearest PoP (~15ms local vs ~200ms cross-continent) |
| **Route53** | Latency-based routing (measured, not geo) | Route users to nearest healthy AWS region |
| **Marketplace filter** | Discrete string filter (US, ASTLA, etc.) | Country-level event filtering (simpler than continuous geo) |
| **Not used: Redis GEORADIUS** | Would use if: real-time proximity (check-in, nearby attendees) | CXP doesn't need real-time attendee tracking |
| **Not used: Explicit geohash** | Would use if: storing locations in DynamoDB (no native geo support) | ES handles geo natively; DynamoDB stores key-value, not geo queries |

---

## Common Interview Follow-ups

### Q: "Geohash vs quadtree — when do you pick each?"

> "**Geohash** when: you need to store spatial data in a system that only supports string indexing (DynamoDB, Redis, relational DB). Geohash is a single string column — prefix search gives you proximity. Redis GEOADD/GEORADIUS uses geohash internally. Great for: 'find all drivers within 5km' (Uber), 'find nearby restaurants' (Yelp). **Quadtree** when: data density varies wildly (NYC has 10,000 restaurants, rural Montana has 5). Quadtree subdivides dense areas more deeply, saving memory in sparse areas. Great for: game collision detection, spatial databases (PostGIS). Our Elasticsearch uses a **BKD tree** — a block-based variant that combines quadtree-like recursive partitioning with disk-efficient storage."

### Q: "How does Elasticsearch's geo_point compare to PostGIS?"

> "ES `geo_point` is optimized for search: fast geo-distance queries combined with text search and aggregations. PostGIS is optimized for spatial analysis: complex geometry operations (polygon intersection, buffer zones, spatial joins). For 'find Nike events near Portland with keyword running' — ES is better (combines geo + text search). For 'find all events within this exact city polygon boundary' — PostGIS is better (complex geometry). Our CXP event search is a discovery use case (search + filter + sort by distance) — ES is the right fit."

### Q: "How would you design a real-time 'nearby attendees' feature?"

> "Redis GEORADIUS. Each attendee sends lat/lng every 30 seconds → `GEOADD attendees {lat} {lng} 'user-uuid'`. To find who's near the event venue: `GEORADIUS attendees {venue_lat} {venue_lng} 100 m WITHCOORD COUNT 100`. Redis stores locations as 52-bit geohash internally → O(log N + M) query time. For 10,000 attendees: ~1ms. TTL the entries at 60 seconds so stale locations auto-expire. This is separate from our Elasticsearch event search — Redis for real-time transient locations, ES for persistent event catalog."

---
---

# Topic 45: UUID vs Auto-Increment IDs

> Auto-increment is compact and sequential but predictable and requires coordination. UUID is globally unique and distributed but large with poor index locality. ULID/Snowflake combine benefits.

> **Interview Tip:** Know when to use each — "Auto-increment for internal PKs, UUIDs for public-facing IDs (prevents enumeration attacks), Snowflake for distributed systems needing sortable unique IDs."

---

## The Three Approaches

```
┌──────────────────────────────────────────────────────────────────────────┐
│              UUID vs AUTO-INCREMENT IDs                                    │
│                                                                          │
│  ┌──────────────────────────┐  ┌──────────────────────────────────┐    │
│  │   AUTO-INCREMENT          │  │   UUID (v4 random)               │    │
│  │   1, 2, 3, 4, 5, 6...    │  │   550e8400-e29b-41d4-a716-...   │    │
│  │                           │  │                                   │    │
│  │  [+] Small, 4-8 bytes    │  │  [+] Globally unique, no         │    │
│  │  [+] Human readable      │  │      coordination                │    │
│  │  [+] Sequential, good    │  │  [+] Generate anywhere           │    │
│  │      for indexing         │  │      (distributed)               │    │
│  │                           │  │  [+] Not guessable               │    │
│  │  [-] Predictable          │  │                                   │    │
│  │      (security risk)      │  │  [-] Large, 16 bytes (128 bits) │    │
│  │  [-] Single point of     │  │  [-] Random = poor index          │    │
│  │      generation           │  │      locality                    │    │
│  └──────────────────────────┘  └──────────────────────────────────┘    │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  BEST OF BOTH WORLDS                                              │  │
│  │                                                                   │  │
│  │  ULID (Universally Unique Lexicographically Sortable ID)         │  │
│  │  01ARZ3NDEKTSV4RRFFQ69G5FAV                                     │  │
│  │  Timestamp prefix + random suffix = sortable + unique            │  │
│  │                                                                   │  │
│  │  Twitter Snowflake ID                                            │  │
│  │  1382971839198212096                                              │  │
│  │  64-bit: timestamp | worker | sequence                           │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Comparison

```
┌──────────────────────────────────────────────────────────────────────┐
│  ┌────────────────┬──────────┬──────────┬───────────┬────────────┐ │
│  │                │ Auto-Inc │ UUID v4  │ ULID      │ Snowflake  │ │
│  ├────────────────┼──────────┼──────────┼───────────┼────────────┤ │
│  │  Size           │ 4-8 bytes│ 16 bytes │ 16 bytes  │ 8 bytes    │ │
│  │  Sortable       │ ✓ (seq)  │ ✗ (random│ ✓ (time)  │ ✓ (time)   │ │
│  │  Distributed    │ ✗ (coord)│ ✓        │ ✓         │ ✓          │ │
│  │  Guessable      │ ✓ (bad)  │ ✗ (good) │ Partial   │ Partial    │ │
│  │  Index locality │ ✓ (great)│ ✗ (poor) │ ✓ (good)  │ ✓ (good)   │ │
│  │  Coordination   │ Required │ None     │ None      │ Worker ID  │ │
│  │  Human readable │ ✓        │ ✗        │ ✗         │ ✗          │ │
│  └────────────────┴──────────┴──────────┴───────────┴────────────┘ │
│                                                                      │
│  WHEN TO USE:                                                       │
│  Auto-increment: Internal PKs, simple monolithic apps               │
│  UUID v4: Public-facing IDs, distributed systems, API resources    │
│  ULID: Need UUID uniqueness + time-sortable (log entries, events)  │
│  Snowflake: High-throughput distributed systems (Twitter, Discord) │
└──────────────────────────────────────────────────────────────────────┘
```

---

## IDs In My CXP Projects — Real Examples

### The CXP ID Map

Our platform uses **multiple ID strategies** across different domains — each chosen for specific requirements.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — ID STRATEGY MAP                                          │
│                                                                          │
│  ┌──────────────────┬──────────────┬──────────────────────────────────┐│
│  │  Entity           │  ID Type     │  Example + Why                    ││
│  ├──────────────────┼──────────────┼──────────────────────────────────┤│
│  │  Nike Member      │  UUID        │  upmId: "550e8400-e29b-41d4-   ││
│  │  (user identity)  │  (UPM ID)    │  a716-446655440000"             ││
│  │                   │              │  Distributed (Nike-wide),        ││
│  │                   │              │  not guessable, privacy-safe.   ││
│  ├──────────────────┼──────────────┼──────────────────────────────────┤│
│  │  Eventtia Event   │  Numeric     │  eventId: 73067                  ││
│  │                   │  (auto-inc)  │  Sequential, Eventtia-generated. ││
│  │                   │              │  PUBLIC: shown in URLs.          ││
│  │                   │              │  Guessable (security concern).   ││
│  ├──────────────────┼──────────────┼──────────────────────────────────┤│
│  │  DynamoDB key     │  Composite   │  eventId_upmId:                  ││
│  │  (unprocessed     │  string      │  "73067_550e8400-e29b-..."       ││
│  │  registration)    │              │  Business key = idempotency key. ││
│  │                   │              │  NOT auto-generated.             ││
│  ├──────────────────┼──────────────┼──────────────────────────────────┤│
│  │  Redis keys       │  Composite   │  "{upmId}_{eventId}_suffix"     ││
│  │  (idempotency)    │  string      │  "550e8400..._73067_success_    ││
│  │                   │              │  response"                       ││
│  │                   │              │  Derived from business entities. ││
│  ├──────────────────┼──────────────┼──────────────────────────────────┤│
│  │  Splunk trace     │  UUID        │  traceId: "abc-123-def-456"     ││
│  │  (distributed     │              │  Propagated across services for  ││
│  │  tracing)         │              │  log correlation.                ││
│  ├──────────────────┼──────────────┼──────────────────────────────────┤│
│  │  NCP notification │  UUID        │  notificationId: "uuid-..."      ││
│  │                   │              │  Unique per email notification.  ││
│  ├──────────────────┼──────────────┼──────────────────────────────────┤│
│  │  Pairwise ID      │  UUID        │  Privacy-preserving consumer    ││
│  │  (privacy)        │              │  identity mapping. NOT the same ││
│  │                   │              │  as upmId.                       ││
│  ├──────────────────┼──────────────┼──────────────────────────────────┤│
│  │  ES document _id  │  Eventtia    │  "73067" (event_id as string).  ││
│  │                   │  event ID    │  Determines shard routing:      ││
│  │                   │              │  hash("73067") % 5 = shard 2.  ││
│  ├──────────────────┼──────────────┼──────────────────────────────────┤│
│  │  S3 object key    │  Path-based  │  "applications/data-ingest/     ││
│  │  (Rise GTS input) │              │  eventtia-events/73067/abc.json"││
│  │                   │              │  Hierarchical, not a single ID. ││
│  └──────────────────┴──────────────┴──────────────────────────────────┘│
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: UPM ID (UUID) — Distributed User Identity

**Entity:** Nike Member (user across all Nike services)
**ID type:** UUID v4
**Used in:** Every authenticated API call (JWT PRN claim), Redis keys, DynamoDB keys, Splunk logs

```
┌──────────────────────────────────────────────────────────────────────┐
│  UPM ID — Why UUID for User Identity                                 │
│                                                                      │
│  upmId = "550e8400-e29b-41d4-a716-446655440000"                    │
│                                                                      │
│  WHY UUID (not auto-increment):                                     │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  1. DISTRIBUTED GENERATION                                   │    │
│  │     100+ Nike services create users independently.           │    │
│  │     No central "user_id sequence" to coordinate.             │    │
│  │     UUID generated at account creation — globally unique.    │    │
│  │                                                              │    │
│  │  2. NOT GUESSABLE (security)                                 │    │
│  │     Auto-increment user_id = 12345 → attacker tries 12346.  │    │
│  │     UUID = "550e8400-..." → can't enumerate other users.    │    │
│  │     Critical for registration API: attacker can't register   │    │
│  │     on behalf of other users by guessing their ID.          │    │
│  │                                                              │    │
│  │  3. PRIVACY-SAFE IN LOGS                                     │    │
│  │     "upmId=550e8400..." in Splunk logs doesn't reveal       │    │
│  │     personal info. Auto-increment "userId=42" is indexable  │    │
│  │     (42nd user ever — you know roughly when they joined).   │    │
│  │                                                              │    │
│  │  4. CROSS-SERVICE IDENTITY                                   │    │
│  │     Same UUID in: JWT token, Redis key, DynamoDB key,       │    │
│  │     Splunk logs, Athena queries, Eventtia registration.     │    │
│  │     One ID across ALL systems — no mapping needed.          │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  TRADEOFFS ACCEPTED:                                                │
│  - 16 bytes per ID (vs 4 bytes for auto-increment int)             │
│  - Not sortable by creation time (UUID v4 is random)               │
│  - Poor index locality in B-tree (random distribution)             │
│  - Not human-readable (can't say "user 42" in conversation)       │
│                                                                      │
│  These tradeoffs are acceptable because user IDs are LOOKUP keys   │
│  (GET by exact ID), not RANGE SCAN keys (list users 40-50).       │
│  DynamoDB hash key: O(1) lookup regardless of ID format.          │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — UUID used everywhere:**

```java
// JWT PRN claim — UUID user identity
// "prn": "550e8400-e29b-41d4-a716-446655440000"
// Extracted by AAAConfig.java on every authenticated request

// Redis idempotency key — UUID in the key
String idempotencyKey = upmId + "_" + eventId;
// "550e8400-e29b-41d4-a716-446655440000_73067"

// DynamoDB partition key — UUID in the composite key
@DynamoDbPartitionKey
public String getEventId_upmId() { return eventId_upmId; }
// "73067_550e8400-e29b-41d4-a716-446655440000"

// Splunk queries — search by UUID
"upmId=(?<upmId>[^,\\s]+)"  // regex extracts UUID from logs
```

---

### Example 2: Eventtia Event ID (Numeric) — The Security Concern

**Entity:** Nike CXP Event
**ID type:** Auto-increment integer
**Used in:** URLs, Splunk queries, Athena queries, API calls

```
┌──────────────────────────────────────────────────────────────────────┐
│  Eventtia Event ID — Auto-Increment in a Public URL                  │
│                                                                      │
│  eventId = 73067                                                    │
│  URL: nike.com/experiences/event/73067                              │
│                                                                      │
│  THE SECURITY PROBLEM (from TaskInternal.md):                       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Event IDs are SEQUENTIAL and in the URL.                    │    │
│  │                                                              │    │
│  │  Attacker sees: /event/73067                                │    │
│  │  Attacker tries: /event/73068, /event/73069, /event/73070  │    │
│  │  → Can enumerate ALL events, including INTERNAL ones.       │    │
│  │                                                              │    │
│  │  Internal Nike employee events (not listed on landing page)  │    │
│  │  are accessible via deep link if you guess the event ID.    │    │
│  │  GET /community/events/v1/{event_id} is @Unsecured.         │    │
│  │                                                              │    │
│  │  WITH UUID EVENT IDs:                                        │    │
│  │  URL: /event/550e8400-e29b-41d4-a716-446655440000           │    │
│  │  Attacker can't enumerate — 2^122 possible values.          │    │
│  │  Guessing is computationally infeasible.                    │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  WHY EVENTTIA USES AUTO-INCREMENT:                                  │
│  - Eventtia is an external SaaS — we don't control their ID scheme │
│  - Auto-increment is standard for relational database PKs           │
│  - Simpler for their internal operations (event 73067 is readable) │
│  - Event IDs are "public" by design in Eventtia's model            │
│                                                                      │
│  CXP's MITIGATION:                                                  │
│  - Internal events on separate domain with OKTA auth (proposed)    │
│  - Event landing page doesn't list internal events                 │
│  - Registration requires consumer JWT (@AccessValidator)            │
│  - Event VIEWING is public, but registering requires auth          │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 3: Composite String Key — Business Key as ID

**Entity:** Unprocessed registration (DynamoDB)
**ID type:** Composite string `eventId_upmId`
**Pattern:** Business key IS the primary key — NOT auto-generated

```
┌──────────────────────────────────────────────────────────────────────┐
│  Composite Key — Business-Derived ID (Not Generated)                 │
│                                                                      │
│  Key: "73067_550e8400-e29b-41d4-a716-446655440000"                 │
│        │      │                                                     │
│        │      └── UUID (user identity — globally unique)           │
│        └── Numeric (event ID — Eventtia-assigned)                  │
│                                                                      │
│  WHY COMPOSITE (not UUID for the DynamoDB record):                 │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  1. NATURAL IDEMPOTENCY (Topic 26)                          │    │
│  │     Same user + same event = same key = overwrite, not dup. │    │
│  │     UUID PK: each putItem creates a NEW record (duplicates!)│    │
│  │     Composite PK: each putItem OVERWRITES (idempotent).     │    │
│  │                                                              │    │
│  │  2. BUSINESS MEANING                                         │    │
│  │     "73067_uuid-1234" tells you: event 73067, user uuid-1234│    │
│  │     Random UUID tells you: nothing (need a lookup to decode) │    │
│  │                                                              │    │
│  │  3. DETERMINISTIC                                            │    │
│  │     Any service can reconstruct the key from business data.  │    │
│  │     No need to store or pass a generated ID between services.│    │
│  │     Redis key matches DynamoDB key matches Splunk correlation.│   │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  SAME PATTERN IN REDIS:                                             │
│  "{upmId}_{eventId}_failure_counter"                               │
│  "{upmId}_{eventId}_success_response"                              │
│  "{upmId}_pairwise_key"                                            │
│                                                                      │
│  ALL derived from business entities — not auto-generated.          │
│  This is WHY our idempotency works across all layers:              │
│  Same business event → same key → same behavior everywhere.       │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "Our DynamoDB key is a composite string `eventId_upmId` — not auto-generated. This is deliberate: the business key IS the idempotency key. Same user registering for the same event produces the same DynamoDB key, so `putItem` overwrites instead of creating a duplicate. If we used a UUID primary key, every registration attempt would create a new record — breaking idempotency. The same composite pattern flows through Redis (`{upmId}_{eventId}_success_response`) and Splunk queries (`upmId AND eventId`). All derived from business entities, no generated IDs to pass between services."

---

### Example 4: Pairwise ID — UUID for Privacy

**Entity:** Consumer identity mapping
**ID type:** UUID (privacy-preserving)
**Purpose:** Prevent tracking across Nike services

```
┌──────────────────────────────────────────────────────────────────────┐
│  Pairwise ID — UUID as Privacy Mechanism                             │
│                                                                      │
│  Problem: If every Nike service uses the same upmId,               │
│  correlating user activity across services is trivial.              │
│  "upmId-1234 bought shoes AND registered for an event AND          │
│   browsed the sale page" — full profile built from one ID.         │
│                                                                      │
│  Solution: PAIRWISE ID — a different UUID per service pair.        │
│  CXP gets pairwiseId "aaa-bbb" for user upmId "xxx-yyy".          │
│  Nike Running Club gets a DIFFERENT pairwiseId for the same user.  │
│  Can't correlate across services without the mapping.              │
│                                                                      │
│  STORED IN REDIS:                                                   │
│  Key: "{upmId}_pairwise_key"                                      │
│  Value: PairWiseIdDetails { pairwiseId: "aaa-bbb-ccc", ... }     │
│  TTL: 30 days (cached to avoid Partner API call per request)       │
│                                                                      │
│  WHY UUID FOR PAIRWISE:                                             │
│  - Must be globally unique (no collision across services)          │
│  - Must not be derivable from upmId (privacy requirement)         │
│  - Must not be sequential (can't enumerate users)                  │
│  - UUID v4 satisfies all three: random, unique, non-sequential     │
└──────────────────────────────────────────────────────────────────────┘
```

---

### When CXP Would Need Snowflake/ULID IDs

```
┌──────────────────────────────────────────────────────────────────────┐
│  WHEN SNOWFLAKE/ULID WOULD HELP CXP                                 │
│                                                                      │
│  CXP doesn't need Snowflake/ULID because:                         │
│  - DynamoDB uses hash key (no index locality benefit from sorting) │
│  - Redis uses exact key lookup (no range scan by time)             │
│  - Eventtia generates event IDs (we don't control the scheme)      │
│  - Nike platform generates upmIds (we don't control the scheme)    │
│                                                                      │
│  WHEN WE'D NEED TIME-SORTABLE IDs:                                 │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  SCENARIO: Activity feed / timeline                         │    │
│  │  "Show me all CXP activity in chronological order."         │    │
│  │                                                              │    │
│  │  UUID v4: random order — need secondary index on timestamp.  │    │
│  │  ULID: "01ARZ3NDEKTSV4RRFFQ69G5FAV" — lexicographically    │    │
│  │  sortable by time. Range scan: ULID > "01ARZ..." returns    │    │
│  │  all records after that timestamp. Single index needed.     │    │
│  │                                                              │    │
│  │  Snowflake: 64-bit = timestamp(41) + worker(10) + seq(12).  │    │
│  │  Fits in a BIGINT column. Sortable. 4096 IDs/ms per worker. │    │
│  │  Great for high-throughput event streaming.                  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  SCENARIO: DynamoDB with sort key (time-ordered queries)    │    │
│  │  Current: PK = "eventId_upmId" (no sort key).              │    │
│  │  If we needed: "all registrations for event 73067 sorted    │    │
│  │  by time" → PK = eventId, SK = ULID (time-sortable).       │    │
│  │  DynamoDB Query: PK = 73067, SK > "01ARZ..." → range scan │    │
│  │  in time order. UUID v4 as SK would require full scan+sort. │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: ID Strategies Across CXP

| Entity | ID Type | Format | Why This Choice |
|--------|---------|--------|----------------|
| **Nike Member** | UUID v4 | `550e8400-e29b-41d4-...` | Distributed generation, not guessable, privacy-safe in logs |
| **Eventtia Event** | Auto-increment | `73067` | Eventtia's choice (external SaaS); human-readable but guessable |
| **DynamoDB record** | Composite string | `73067_uuid-1234` | Business key = idempotency key; deterministic, not generated |
| **Redis key** | Composite string | `{upmId}_{eventId}_suffix` | Matches DynamoDB pattern; derived from business entities |
| **Pairwise ID** | UUID v4 | `aaa-bbb-ccc-ddd` | Privacy: different ID per service pair; not derivable from upmId |
| **Trace ID** | UUID | `abc-123-def-456` | Distributed tracing; unique per request flow |
| **Notification ID** | UUID | NCP-generated | Unique per email notification; deduplication key |
| **ES document _id** | Event ID (string) | `"73067"` | Determines shard routing: `hash("73067") % 5` |
| **S3 object key** | Path-based | `apps/data-ingest/73067/abc.json` | Hierarchical; includes event ID for partitioning |

---

## Common Interview Follow-ups

### Q: "Event IDs in URLs are guessable — how would you fix it?"

> "Three options: (1) **Public slug instead of ID:** `/event/nike-run-portland-2026` — human-readable, not guessable, but requires slug uniqueness management. (2) **UUID event IDs:** `/event/550e8400-...` — not guessable, but ugly URLs and breaks existing bookmarks. (3) **Authorization on the endpoint:** keep numeric IDs but add `@AccessValidator` to check if the event is public or if the user is authorized (Nike employee for internal events). Option 3 is what our architecture proposes — separate internal events on an OKTA-authenticated domain. The ID format doesn't change; the authorization layer gates access."

### Q: "UUID v4 has poor B-tree index locality. Does that matter for DynamoDB?"

> "No — DynamoDB uses hash-based partitioning, not B-tree indexes. Our partition key `eventId_upmId` is hashed to determine which partition stores the item. The hash function distributes keys evenly regardless of whether the input is sequential or random. UUID's random distribution is actually GOOD for DynamoDB — it prevents hot partitions (Topic 8). In a B-tree database (PostgreSQL), UUID v4 PKs cause page splits and poor cache locality. In DynamoDB, it's a non-issue. This is one reason our all-NoSQL architecture (Topic 2) works well with UUID-based identities."

### Q: "Why composite string key instead of separate partition + sort key?"

> "Our DynamoDB table has ONE access pattern: exact key lookup (get/put/delete by `eventId_upmId`). A composite STRING key gives us O(1) lookup. Separating into partition key (`eventId`) + sort key (`upmId`) would enable a SECOND access pattern: 'all unprocessed registrations for event 73067' via DynamoDB Query. We don't need this — batch reprocessing does a full Scan (rare operation). The composite string is simpler, naturally idempotent (same key = overwrite), and matches our Redis key pattern exactly. If we later needed per-event queries, we'd add a GSI with eventId as the partition key rather than redesigning the base table."

---
---

# Topic 46: Batch vs Stream Processing

> Batch processes large volumes periodically (high throughput, high latency); stream processes continuously (low latency, more complex). Lambda architecture combines both.

> **Interview Tip:** Match to use case — "Daily reports use batch (Spark); fraud detection needs stream (Flink) for sub-second response; recommendation system might use Lambda with both."

---

## The Two Models

```
┌──────────────────────────────────────────────────────────────────────────┐
│              BATCH vs STREAM PROCESSING                                   │
│                                                                          │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐    │
│  │   BATCH PROCESSING            │  │   STREAM PROCESSING          │    │
│  │   Process large volumes       │  │   Process data continuously  │    │
│  │   at once.                    │  │   as it arrives.             │    │
│  │                               │  │                               │    │
│  │  ┌──┐┌──┐  ┌─────────┐      │  │  ●●● ──▶ ┌──────────┐──▶●● │    │
│  │  │  ││  │─▶│ Batch   │──▶Res│  │  Events   │ Stream   │Output │    │
│  │  └──┘└──┘  │ Job     │      │  │           │Processor │       │    │
│  │  Data Lake  │Scheduled│      │  │           │Real-time │       │    │
│  │             └─────────┘      │  │           └──────────┘       │    │
│  │                               │  │                               │    │
│  │  [+] High throughput,         │  │  [+] Low latency              │    │
│  │      cost efficient           │  │      (seconds/ms)             │    │
│  │  [-] High latency             │  │  [-] More complex,            │    │
│  │      (hours/days)             │  │      resource intensive       │    │
│  └──────────────────────────────┘  └──────────────────────────────┘    │
│                                                                          │
│  Batch: Hadoop, Spark, AWS EMR     Stream: Kafka Streams, Flink,       │
│                                     Spark Streaming                     │
│                                                                          │
│  Batch: ETL, Reports, ML Training  Stream: Fraud detection,            │
│                                     Monitoring, Recommendations         │
│                                                                          │
│  HYBRID (Lambda Architecture): Batch + Stream combined.                │
│  Stream for real-time view, Batch for complete/accurate view.          │
│  Results merged at query time.                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Batch vs Stream Processing In My CXP Projects

### CXP Uses BOTH — Stream for the Email Pipeline, Batch for Recovery

Our platform is a real-world example of using **stream processing for real-time operations** and **batch processing for investigation and recovery**.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — BATCH vs STREAM MAP                                      │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  STREAM PROCESSING (real-time, event-driven)                      │  │
│  │                                                                   │  │
│  │  Eventtia webhook → Kafka/NSP3 → Rise GTS → NCP → Email         │  │
│  │  Latency: seconds to minutes                                     │  │
│  │  Trigger: each registration event processed individually         │  │
│  │  Technology: Kafka Connect sinks (HTTP Push, S3, Purge)         │  │
│  │                                                                   │  │
│  │  Also stream: SQS → Rise GTS (S3 event notification → transform)│  │
│  │  Also stream: Kafka purge sink → Akamai cache invalidation      │  │
│  │  Also stream: @Scheduled refreshes (polling = micro-batch)      │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  BATCH PROCESSING (periodic, high-volume)                         │  │
│  │                                                                   │  │
│  │  email-drop-recovery: Athena queries scan entire Partner Hub     │  │
│  │  Latency: 30 seconds to 2 minutes per query                     │  │
│  │  Trigger: human clicks "Search" or "Investigate"                │  │
│  │  Technology: Athena (S3 scan), Splunk saved searches             │  │
│  │                                                                   │  │
│  │  Also batch: DynamoDB Scan for unprocessed registrations         │  │
│  │  Also batch: Splunk Trend tab (30 daily aggregation queries)    │  │
│  │  Also batch: Reconciliation (compare Athena vs Splunk sets)     │  │
│  │  Also batch: @Scheduled cache rebuild (every 15 min / weekly)   │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: Stream Processing — Email Delivery Pipeline

**Technology:** Kafka/NSP3 sinks + SQS + Rise GTS
**Pattern:** Each event processed individually as it arrives

```
┌──────────────────────────────────────────────────────────────────────┐
│  STREAM: Registration → Email (Real-Time)                            │
│                                                                      │
│  User registers (T=0s)                                              │
│       │                                                              │
│       ▼ (event produced to Kafka — stream)                          │
│  Kafka/NSP3: event arrives on partnerhub_notification_stream        │
│       │                                                              │
│       ├── HTTP Push Sink → Rise GTS (T=1-2s)                       │
│       │   → Transform → POST to NCP (T=3-5s)                       │
│       │   → CRS renders email (T=5-10s)                            │
│       │   → SendGrid delivers (T=10-60s)                           │
│       │                                                              │
│       ├── S3 Sink → Partner Hub archive (T=1-5s)                   │
│       │   → JSON stored immutably in S3                            │
│       │                                                              │
│       └── Purge Sink → cxp-events /purge-cache (T=1-3s)           │
│           → Akamai edge cache invalidated                          │
│                                                                      │
│  STREAM CHARACTERISTICS:                                            │
│  ✓ Each event processed INDIVIDUALLY (not batched)                 │
│  ✓ Processing starts IMMEDIATELY when event arrives                │
│  ✓ Multiple consumers process the SAME event in parallel           │
│  ✓ Latency: seconds (not hours)                                    │
│  ✓ Continuous: runs 24/7, not scheduled                            │
│                                                                      │
│  THIS IS STREAM PROCESSING because:                                │
│  - Input: continuous event stream (Kafka topic)                    │
│  - Processing: per-event (one at a time)                           │
│  - Output: immediate (email sent, cache purged, data archived)     │
│  - No "wait for all data" — process as it arrives                  │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 2: Batch Processing — Email Drop Recovery

**Technology:** Athena (S3 scan) + Splunk aggregate queries
**Pattern:** Process entire dataset at once, on-demand

```
┌──────────────────────────────────────────────────────────────────────┐
│  BATCH: Recovery Dashboard Queries (On-Demand Bulk)                  │
│                                                                      │
│  Operator clicks "Search" for last 24 hours:                        │
│       │                                                              │
│       ▼                                                              │
│  BATCH QUERY 1: Splunk — scan ALL NCP logs for 24 hours            │
│  "search index=dockerlogs* UserEmailNotAvailable earliest=-24h"    │
│  → Scans millions of log lines → returns ~50-500 dropped emails   │
│  → Duration: ~30 seconds                                           │
│                                                                      │
│  BATCH QUERY 2: Splunk — count total events processed              │
│  "search index=dockerlogs-hc appname=ncp-ingest-api earliest=-24h │
│   | stats count as arrived"                                        │
│  → Aggregates across entire 24-hour window                         │
│  → Duration: ~10 seconds                                           │
│                                                                      │
│  BATCH QUERY 3: Athena — resolve event IDs from Partner Hub        │
│  "SELECT event.id, event.name FROM partner_hub                     │
│   WHERE attendee.upm_id IN (...50 upmIds...)"                     │
│  → Scans entire Athena table (no partition pruning)                │
│  → Duration: ~30-60 seconds                                       │
│                                                                      │
│  BATCH CHARACTERISTICS:                                             │
│  ✓ Process ENTIRE dataset at once (24h of logs, full S3 table)    │
│  ✓ High latency (30-120 seconds per query)                        │
│  ✓ High throughput (millions of records processed)                 │
│  ✓ Triggered ON-DEMAND (human clicks button, not continuous)       │
│  ✓ Results: complete snapshot (all drops in the window)            │
│                                                                      │
│  THIS IS BATCH PROCESSING because:                                 │
│  - Input: bounded dataset (24h window, not continuous stream)      │
│  - Processing: entire dataset at once (not per-event)              │
│  - Output: aggregate results (drop count, trend, missing users)    │
│  - Latency: seconds-minutes (acceptable for investigation)        │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — batch query examples:**

```python
# queries.py — batch Splunk queries (scan full time window)
"dropped_emails": f'''search index=dockerlogs* sourcetype=log4j
    "UserEmailNotAvailable" {time_clause}
    | rex field=_raw "upmId=(?<upmId>[^,\\s]+)"
    | dedup upmId, eventType
    | table _time, upmId, marketplace, eventType ...'''

# Athena batch query — scan full table
q_count = f"""SELECT COUNT(*) as total
    FROM "{ATHENA_DATABASE}".{ATHENA_TABLE}
    WHERE event.id = {event_id} AND action = 'confirmed'"""
```

---

### Example 3: The Hybrid — Stream + Batch in One Pipeline

CXP's email pipeline is a **Lambda-like architecture**: stream processing for real-time delivery, batch processing for quality assurance.

```
┌──────────────────────────────────────────────────────────────────────┐
│  CXP LAMBDA-LIKE ARCHITECTURE                                        │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐│
│  │  SPEED LAYER (Stream):                                          ││
│  │  Kafka → Rise GTS → NCP → CRS → SendGrid → User inbox        ││
│  │  Latency: seconds. Processes each registration immediately.    ││
│  │  May have gaps: ~2-5% emails dropped (MemberHub race condition)││
│  └────────────────────────────────────────────────────────────────┘│
│                          │                                          │
│                          │ Both produce data that feeds the         │
│                          │ batch layer for quality assurance.       │
│                          ▼                                          │
│  ┌────────────────────────────────────────────────────────────────┐│
│  │  BATCH LAYER:                                                    ││
│  │  email-drop-recovery dashboard                                  ││
│  │                                                                  ││
│  │  Monitor tab: "What dropped in the last 24 hours?" (batch scan)││
│  │  Trend tab: "Daily drop rate over 30 days" (30 batch queries)  ││
│  │  Reconciliation: "Who registered vs who got email?" (batch join)││
│  │  Investigate: "Where did event 73067 fail?" (5 batch queries)  ││
│  │                                                                  ││
│  │  Latency: 30-120 seconds. Processes entire datasets.           ││
│  │  COMPLETE: catches every gap the stream layer missed.          ││
│  └────────────────────────────────────────────────────────────────┘│
│                          │                                          │
│                          │ Batch layer detects stream layer gaps.   │
│                          │ Triggers reprocessing (compensating).    │
│                          ▼                                          │
│  ┌────────────────────────────────────────────────────────────────┐│
│  │  SERVING LAYER (Merge):                                          ││
│  │  Stream: user got email (real-time confirmation)               ││
│  │  Batch: user DIDN'T get email (recovery dashboard detected it) ││
│  │  Action: Reprocess via RISE API (replay the event)             ││
│  │  Result: User eventually gets email (stream + batch combined)  ││
│  └────────────────────────────────────────────────────────────────┘│
│                                                                      │
│  LAMBDA PATTERN: Stream for speed, Batch for completeness.         │
│  The batch layer is the "safety net" for the stream layer.         │
│  Together: >99.5% email delivery with <1 second registration.     │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "Our platform is a natural Lambda architecture. The stream layer (Kafka → Rise GTS → NCP → email) processes each registration event in real-time — seconds to deliver a confirmation email. But the stream layer has a ~2-5% gap (MemberHub race condition). The batch layer (email-drop-recovery dashboard) scans Splunk and Athena periodically to detect what the stream layer missed — complete but higher latency. When the batch layer finds a gap, it triggers reprocessing through the stream layer again. Stream for speed (~2 seconds), batch for completeness (~30-second scan), together for >99.5% delivery."

---

### Example 4: Micro-Batch — @Scheduled Polling Patterns

CXP also uses **micro-batch** patterns — periodic polling that sits between pure stream and pure batch.

```
┌──────────────────────────────────────────────────────────────────────┐
│  MICRO-BATCH PATTERNS IN CXP                                        │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  @Scheduled(fixedRate = 900000) — Internal Events Cache     │    │
│  │  Every 15 minutes:                                          │    │
│  │  → Fetch ALL internal events from Eventtia (batch)          │    │
│  │  → invalidateAll() → rebuild Caffeine cache                 │    │
│  │  This is MICRO-BATCH: periodic full refresh.                │    │
│  │  Not pure stream (not per-event).                           │    │
│  │  Not pure batch (not daily, runs every 15 min).             │    │
│  ├────────────────────────────────────────────────────────────┤    │
│  │  @Scheduled(fixedRate = 3540000) — Token Refresh             │    │
│  │  Every 59 minutes: refresh Eventtia auth token.             │    │
│  │  Micro-batch: one bulk operation on a schedule.             │    │
│  ├────────────────────────────────────────────────────────────┤    │
│  │  @Scheduled(cron = "0 0 5 * * MON") — Translation Refresh  │    │
│  │  Weekly: refresh Bodega translations from S3.               │    │
│  │  Pure batch: weekly schedule, full dataset reload.          │    │
│  ├────────────────────────────────────────────────────────────┤    │
│  │  DynamoDB Scan — Unprocessed Reprocessing                    │    │
│  │  On-demand: POST /community/reprocess_regns/v1              │    │
│  │  Scans ALL unprocessed items → retries each.                │    │
│  │  Pure batch: full table scan, bulk reprocessing.            │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 5: Rise GTS — Stream Processor That Handles Both

Rise GTS is a **stream processor** that also accepts **batch-like HTTP POST** triggers.

```
┌──────────────────────────────────────────────────────────────────────┐
│  Rise GTS — Dual-Mode Processing                                     │
│                                                                      │
│  MODE 1: STREAM (SQS-driven)                                       │
│  S3 event → SQS → @SqsListener → transform → publish              │
│  Each message processed individually. Continuous.                   │
│  deletionPolicy = ON_SUCCESS (message gone after processing).      │
│                                                                      │
│  MODE 2: BATCH/ON-DEMAND (HTTP-driven)                              │
│  NSP3 HTTP Push sink → POST /data/transform/v1 → transform         │
│  Recovery dashboard → POST /data/transform/v1 → reprocess          │
│  Each request is synchronous. Triggered by external event.         │
│                                                                      │
│  SAME TRANSFORM LOGIC, DIFFERENT TRIGGERS:                         │
│  The transform code is identical regardless of how it's invoked.   │
│  SQS trigger = stream mode (continuous, async).                    │
│  HTTP trigger = on-demand mode (request-response, sync).           │
│  This is good design: processing logic decoupled from trigger.     │
│                                                                      │
│  Rise GTS's 500-thread ForkJoinPool (Topic 18):                    │
│  In stream mode: handles parallel transforms of multiple SQS msgs. │
│  In HTTP mode: handles concurrent requests from multiple sinks.    │
│  Same pool serves both modes.                                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Batch vs Stream Across CXP

| Operation | Type | Technology | Latency | Trigger |
|-----------|------|-----------|---------|---------|
| **Email delivery** | Stream | Kafka → Rise GTS → NCP | Seconds | Each registration event |
| **S3 archival** | Stream | Kafka S3 Sink | Seconds | Each registration event |
| **CDN cache purge** | Stream | Kafka Purge Sink | Seconds | Each event update |
| **SQS → transform** | Stream | SQS → Rise GTS @SqsListener | Seconds | Each S3 notification |
| **Drop monitoring** | Batch | Splunk search (24h window) | 30-60s | Human clicks "Search" |
| **Trend analysis** | Batch | 30× Splunk queries (daily agg) | 60-120s | Human clicks "Load Trend" |
| **Reconciliation** | Batch | Athena + Splunk (full compare) | 60-120s | Human clicks "Reconcile" |
| **Investigation** | Batch | 5× parallel Splunk + Athena | 30-120s | Human clicks "Investigate" |
| **DynamoDB reprocess** | Batch | Full table Scan + retry each | Minutes | Admin triggers endpoint |
| **Cache refresh** | Micro-batch | @Scheduled (15 min / weekly) | Seconds | Timer-triggered |
| **Token refresh** | Micro-batch | @Scheduled (59 min) | ~1s | Timer-triggered |
| **Email reprocess** | On-demand | Recovery dashboard → RISE API | Seconds per item | Human clicks "Reprocess" |

---

## Common Interview Follow-ups

### Q: "Why not use Spark/Flink for CXP's stream processing?"

> "Our stream processing is simple: one event → transform → publish. No windowing, no aggregations, no joins, no complex event processing (CEP). Kafka Connect sinks (HTTP Push, S3) handle the fan-out. Rise GTS is a stateless HTTP transform service, not a stream processor framework. Spark Streaming or Flink would be overkill — they shine when you need: windowed aggregations ('average drop rate over 5-minute windows'), stream-stream joins ('join registration events with delivery events in real-time'), or complex event patterns ('detect 3 consecutive drops for the same user'). If we needed real-time drop detection (not batch), Flink would be the right choice."

### Q: "Your batch layer (recovery dashboard) runs manually. Shouldn't it be automated?"

> "It should — and that's an improvement opportunity. Currently, an operator opens the dashboard and clicks 'Search.' Automated version: (1) Splunk saved search runs hourly: 'count drops in last hour.' (2) If drop rate > 5%, Splunk alert fires → Slack notification. (3) Automated Lambda function queries Athena for affected users, calls RISE API to reprocess. (4) Recovery dashboard becomes a monitoring dashboard, not an investigation tool. This converts the batch layer from human-triggered to timer-triggered — still batch (hourly scan), but automated."

### Q: "How does Lambda architecture differ from what you described?"

> "Classic Lambda has three explicit layers: batch layer (recomputes complete views periodically), speed layer (processes new events for real-time views), and serving layer (merges both views at query time). Our CXP is Lambda-like but not formal: the stream layer (Kafka pipeline) IS the speed layer. The S3 archival IS the batch layer input. The recovery dashboard IS the serving/merge layer — it detects gaps between stream output (Splunk delivery logs) and batch input (Athena registrations), then re-triggers the stream layer to fix them. The difference: formal Lambda runs batch and stream in parallel continuously. We run batch on-demand for investigation, not continuously for dual-view computation."

---
---

# Topic 47: Data Lake vs Data Warehouse

> Data lake stores raw data in any format (schema on read); data warehouse stores structured, modeled data (schema on write). Lakehouse combines both approaches.

> **Interview Tip:** Explain the evolution — "Raw events land in S3 data lake, ETL transforms to warehouse (Snowflake) for BI; modern approach uses Delta Lake for both workloads."

---

## The Core Difference

```
┌──────────────────────────────────────────────────────────────────────────┐
│              DATA LAKE vs DATA WAREHOUSE                                  │
│                                                                          │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐    │
│  │       DATA LAKE               │  │      DATA WAREHOUSE          │    │
│  │  Store everything,            │  │  Structured, schema on write │    │
│  │  schema on read.              │  │                               │    │
│  │                               │  │  ┌──────┐┌──────┐┌────────┐ │    │
│  │  ┌────┐┌────┐┌─────┐        │  │  │Sales ││Users ││Products│ │    │
│  │  │JSON││CSV ││Video│        │  │  │id,   ││id,   ││id,name,│ │    │
│  │  └────┘└────┘└─────┘        │  │  │date, ││name, ││price   │ │    │
│  │  ┌────┐┌────┐               │  │  │amt   ││email │└────────┘ │    │
│  │  │Logs││More│               │  │  └──────┘└──────┘           │    │
│  │  └────┘└────┘               │  │                               │    │
│  │                               │  │  Cleaned, transformed,       │    │
│  │  Raw, unprocessed data.       │  │  modeled. Optimized for      │    │
│  │  Any format: structured,      │  │  analytics (OLAP).           │    │
│  │  semi, unstructured.          │  │                               │    │
│  │                               │  │  [+] Fast queries, BI-ready  │    │
│  │  [+] Flexible, cheap (S3)    │  │  [-] Schema changes expensive │    │
│  │  [-] Slow queries, can       │  │                               │    │
│  │      become "swamp"           │  │  Snowflake, BigQuery,        │    │
│  │                               │  │  Redshift. Columnar, MPP.    │    │
│  │  S3, HDFS, Azure Data Lake.  │  │                               │    │
│  │  Spark, Presto for queries.  │  │                               │    │
│  └──────────────────────────────┘  └──────────────────────────────┘    │
│                                                                          │
│  LAKEHOUSE (Modern — best of both):                                     │
│  Databricks, Delta Lake, Apache Iceberg.                                │
│  Store raw data like a lake + query like a warehouse.                   │
│  ACID transactions on S3/HDFS. Schema enforcement optional.             │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Data Lake vs Data Warehouse In My CXP Projects

### CXP Has a Data Lake — S3 + Athena (No Formal Data Warehouse)

Our Partner Hub S3 bucket is a **data lake**: raw JSON webhooks stored as-is, schema applied at query time by Athena. We don't have a formal data warehouse (Snowflake/Redshift).

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP DATA ARCHITECTURE                                                    │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  DATA LAKE: S3 Partner Hub                                        │  │
│  │                                                                   │  │
│  │  s3://partner-hub-notification-response-data-prod/                │  │
│  │  ├── year=2025/month=11/uuid-abc.json     (raw Eventtia webhook)│  │
│  │  ├── year=2025/month=12/uuid-def.json                           │  │
│  │  ├── year=2026/month=01/uuid-ghi.json                           │  │
│  │  ├── year=2026/month=04/uuid-xyz.json     (today's events)     │  │
│  │  └── ...thousands of JSON files                                  │  │
│  │                                                                   │  │
│  │  Properties:                                                     │  │
│  │  ✓ Raw, unprocessed JSON (exactly as Eventtia sent it)          │  │
│  │  ✓ Schema on read (Athena interprets at query time)             │  │
│  │  ✓ Any format (JSON today, could add Parquet/CSV)               │  │
│  │  ✓ Cheap: $0.023/GB/month (terabytes affordable)               │  │
│  │  ✓ Append-only (immutable — great for audit trail)              │  │
│  │  ✗ Slow queries (full scan without partitioning)                │  │
│  │  ✗ No schema enforcement (bad JSON = query error at read time)  │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  QUERY ENGINE: Athena (SQL on the data lake)                      │  │
│  │                                                                   │  │
│  │  Athena = serverless SQL engine that reads S3 directly.          │  │
│  │  No data loading, no ETL, no warehouse infrastructure.           │  │
│  │  Schema defined in Glue Catalog (schema-on-read).                │  │
│  │                                                                   │  │
│  │  SELECT attendee.upm_id, event.id, action                       │  │
│  │  FROM partner_hub_notification_response_data_prod                │  │
│  │  WHERE event.id = 73067 AND action = 'confirmed'                │  │
│  │                                                                   │  │
│  │  Athena reads S3 JSON → applies SQL → returns results.          │  │
│  │  Cost: $5/TB scanned. No idle cost (serverless).                │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  OTHER DATA STORES (not data lake / not warehouse):               │  │
│  │                                                                   │  │
│  │  Splunk: Operational logs (search + time-series). Not a lake     │  │
│  │  or warehouse — it's a log analytics platform.                   │  │
│  │                                                                   │  │
│  │  Elasticsearch: Search index (inverted index for full-text).     │  │
│  │  Not a lake or warehouse — it's a search engine.                 │  │
│  │                                                                   │  │
│  │  DynamoDB: Key-value store (operational data).                   │  │
│  │  Not a lake or warehouse — it's an OLTP database.                │  │
│  │                                                                   │  │
│  │  Redis: Cache (in-memory, TTL-bounded).                          │  │
│  │  Not a lake or warehouse — it's a cache layer.                   │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: S3 Partner Hub — A Real Data Lake

```
┌──────────────────────────────────────────────────────────────────────┐
│  PARTNER HUB S3 — Data Lake Properties                               │
│                                                                      │
│  DATA LAKE PROPERTY          CXP S3 IMPLEMENTATION                  │
│  ────────────────────        ──────────────────────                  │
│  Store everything:           Every Eventtia webhook stored as-is.   │
│                              Confirmed, cancelled, pre-event,       │
│                              post-event — all actions, all events.  │
│                                                                      │
│  Schema on read:             Athena Glue table defines schema:      │
│                              attendee.upm_id, event.id,             │
│                              event.name, action, event_date_ms.     │
│                              JSON fields parsed at QUERY time.      │
│                              If Eventtia adds new fields → old      │
│                              queries still work (ignore new fields).│
│                                                                      │
│  Any format:                 Currently JSON. Could add Parquet for  │
│                              cost optimization (columnar = less     │
│                              data scanned per query).               │
│                                                                      │
│  Cheap storage:              $0.023/GB. Terabytes of webhooks =    │
│                              ~$20/month. RDS equivalent = $500+.   │
│                                                                      │
│  Immutable:                  S3 objects are append-only. Never      │
│                              updated or deleted (audit trail).      │
│                              "$path" column gives exact S3 path    │
│                              for event replay (reprocessing).       │
│                                                                      │
│  Can become "swamp":         Without partitioning, every Athena    │
│                              query scans ALL files. Current CXP    │
│                              queries don't partition-prune →       │
│                              scanning full table every time.        │
│                              Optimization: add year/month partition.│
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — schema-on-read Athena queries:**

```python
# athena_client.py — schema defined implicitly by query, not by table DDL
ATHENA_DATABASE = 'partnerhub-data-crawler-info'
ATHENA_TABLE = 'partner_hub_notification_response_data_prod'

# Schema applied at read time — JSON parsed by Athena:
query = f"""
    SELECT attendee.upm_id AS upm_id,          -- nested JSON field
           event.id AS event_id,                 -- nested JSON field
           event.name AS event_name,             -- nested JSON field
           event.marketplace AS event_marketplace -- nested JSON field
    FROM "{ATHENA_DATABASE}".{ATHENA_TABLE}
    WHERE attendee.upm_id IN ({quoted})
    AND action IN ('confirmed', 'pending', 'cancel', 'pre_event', 'post_event')
    ORDER BY event_date_ms DESC
"""
# No schema migration, no ALTER TABLE. Athena reads raw JSON from S3.
```

---

### Example 2: Why CXP Doesn't Need a Data Warehouse

```
┌──────────────────────────────────────────────────────────────────────┐
│  WHY NO DATA WAREHOUSE (Snowflake/Redshift)                          │
│                                                                      │
│  CXP DATA USAGE PATTERNS:                                           │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Use case              │  Tool      │  Warehouse needed?    │    │
│  ├────────────────────────┼────────────┼───────────────────────┤    │
│  │  "Who dropped emails?" │  Splunk    │  No (log search)      │    │
│  │  "What did Eventtia    │  Athena    │  No (S3 scan)         │    │
│  │   send for event X?"   │  (S3 lake) │                       │    │
│  │  "Drop rate trend?"    │  Splunk    │  No (time-series agg) │    │
│  │  "Search Nike events"  │  Elastic-  │  No (search engine)   │    │
│  │                        │  search    │                       │    │
│  │  "Daily registration   │  Athena    │  No (S3 lake scan)    │    │
│  │   count per event?"    │            │                       │    │
│  └────────────────────────┴────────────┴───────────────────────┘    │
│                                                                      │
│  WHEN WE'D NEED A WAREHOUSE:                                       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  "Revenue per event per marketplace per quarter"            │    │
│  │  → Complex OLAP: JOINs across events, registrations,       │    │
│  │    financials, demographics. Multi-dimensional analysis.    │    │
│  │  → Athena can do it but slowly (full S3 scan each time).   │    │
│  │  → Warehouse: pre-modeled star schema, columnar storage,   │    │
│  │    materialized views = sub-second dashboards.              │    │
│  │                                                              │    │
│  │  "BI dashboard for CXP leadership — real-time metrics"      │    │
│  │  → Warehouse + BI tool (Looker, Tableau, Power BI).         │    │
│  │  → Pre-aggregated tables for instant dashboard loads.       │    │
│  │                                                              │    │
│  │  "ML model: predict which events will have email drops"     │    │
│  │  → Feature engineering from historical data in warehouse.   │    │
│  │  → Train on cleaned, structured features.                   │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  CURRENT CXP NEED: Investigation + recovery (ad-hoc queries).     │
│  Data lake (S3 + Athena) is sufficient for ad-hoc SQL on raw data.│
│  Warehouse would be overkill until we need BI dashboards or ML.   │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 3: The Evolution Path — Lake → Lakehouse → Warehouse

```
┌──────────────────────────────────────────────────────────────────────┐
│  CXP DATA EVOLUTION PATH                                             │
│                                                                      │
│  STAGE 1 (current): DATA LAKE                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Eventtia → Kafka → S3 (raw JSON)                           │    │
│  │  Query: Athena (schema-on-read, $5/TB scanned)              │    │
│  │  Users: Operations team (investigation, recovery)           │    │
│  │  Pro: Zero infrastructure, cheapest storage, flexible.      │    │
│  │  Con: Slow queries, no schema enforcement, no aggregations. │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  STAGE 2 (optimization): LAKEHOUSE                                  │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Same S3 data → convert JSON to Parquet (columnar format).  │    │
│  │  Add Hive partitioning: year=2026/month=04/                 │    │
│  │  Query: Athena (same SQL, 10x cheaper — only reads columns │    │
│  │  and partitions needed).                                    │    │
│  │  Optional: Delta Lake / Iceberg for ACID on S3.             │    │
│  │                                                              │    │
│  │  COST SAVINGS:                                               │    │
│  │  Current: scan 100 GB JSON → $0.50 per query.              │    │
│  │  Parquet: scan 5 GB columnar → $0.025 per query (20x less).│    │
│  │  + Partitioning: scan 1 month → $0.002 per query.           │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  STAGE 3 (if needed): DATA WAREHOUSE                                │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ETL: S3 (lake) → dbt/Spark → Snowflake/Redshift (warehouse)│   │
│  │  Modeled: Star schema (fact_registrations, dim_events,      │    │
│  │  dim_users, dim_marketplace).                                │    │
│  │  Queries: Sub-second for BI dashboards.                     │    │
│  │  Users: Business analysts, leadership, ML team.             │    │
│  │                                                              │    │
│  │  ADDED COMPLEXITY:                                           │    │
│  │  - ETL pipeline to maintain (S3 → transform → warehouse)   │    │
│  │  - Schema migrations when Eventtia adds fields              │    │
│  │  - Warehouse cost ($$$$ for Snowflake compute time)         │    │
│  │  - Data freshness: warehouse lags behind lake by hours      │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### How Other CXP Components Map to Lake/Warehouse Concepts

```
┌──────────────────────────────────────────────────────────────────────┐
│  CXP DATA STORES — Classified by Lake/Warehouse/Neither              │
│                                                                      │
│  ┌──────────────────────┬───────────────┬────────────────────────┐ │
│  │  Component            │  Classification│  Reasoning              │ │
│  ├──────────────────────┼───────────────┼────────────────────────┤ │
│  │  S3 Partner Hub       │  DATA LAKE    │  Raw JSON, schema-on-  │ │
│  │                       │               │  read via Athena. Cheap.│ │
│  ├──────────────────────┼───────────────┼────────────────────────┤ │
│  │  S3 Bodega            │  DATA LAKE    │  Raw translation files. │ │
│  │  translations         │  (config)     │  JSON/properties format.│ │
│  ├──────────────────────┼───────────────┼────────────────────────┤ │
│  │  Splunk               │  LOG          │  Operational logs. Not  │ │
│  │                       │  ANALYTICS    │  a lake (optimized for  │ │
│  │                       │               │  search, not storage).  │ │
│  │                       │               │  Splunk indexes ≠ S3.   │ │
│  ├──────────────────────┼───────────────┼────────────────────────┤ │
│  │  Elasticsearch        │  SEARCH       │  Inverted index for     │ │
│  │                       │  ENGINE       │  discovery. Not a lake  │ │
│  │                       │               │  or warehouse.          │ │
│  ├──────────────────────┼───────────────┼────────────────────────┤ │
│  │  DynamoDB             │  OLTP         │  Operational key-value. │ │
│  │                       │  DATABASE     │  Transactional, not     │ │
│  │                       │               │  analytical.            │ │
│  ├──────────────────────┼───────────────┼────────────────────────┤ │
│  │  Eventtia             │  EXTERNAL     │  SaaS operational DB.   │ │
│  │                       │  OLTP         │  Source of truth for    │ │
│  │                       │               │  events/registrations.  │ │
│  ├──────────────────────┼───────────────┼────────────────────────┤ │
│  │  Not present          │  DATA         │  No Snowflake/Redshift. │ │
│  │                       │  WAREHOUSE    │  Not needed for current │ │
│  │                       │               │  investigation use case.│ │
│  └──────────────────────┴───────────────┴────────────────────────┘ │
│                                                                      │
│  THE CQRS PATTERN AS LAKE + OPERATIONAL:                            │
│  Eventtia (OLTP / write-optimized)                                  │
│  → Kafka → S3 (data lake / raw archive)                             │
│  → Kafka → Elasticsearch (search engine / read-optimized)           │
│  This is a mini data platform: OLTP source of truth → lake for     │
│  audit → search engine for queries. Missing piece: warehouse        │
│  for analytics.                                                     │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "Our Partner Hub S3 bucket is a data lake: raw Eventtia webhooks stored as JSON, schema applied at query time by Athena. We chose lake over warehouse because our use case is investigation — ad-hoc SQL on raw data ('which users didn't get email for event 73067?'). Athena costs $5/TB scanned with zero infrastructure. If we needed BI dashboards for CXP leadership, I'd add a warehouse layer: ETL from S3 (JSON) → Parquet conversion → Snowflake with a star schema (fact_registrations, dim_events, dim_marketplace). But today, the lake is sufficient and 100× cheaper than maintaining a warehouse for investigation queries that run a few times per day."

---

## Summary: Data Lake vs Warehouse in CXP

| Aspect | CXP Data Lake (S3) | Hypothetical CXP Warehouse |
|--------|-------------------|--------------------------|
| **Storage** | S3 ($0.023/GB) | Snowflake compute ($$$) |
| **Format** | Raw JSON (as Eventtia sends it) | Star schema (fact + dimension tables) |
| **Schema** | On read (Athena Glue Catalog) | On write (DDL, migrations required) |
| **Query engine** | Athena (serverless, $5/TB) | Snowflake (provisioned or serverless) |
| **Latency** | 10-60 seconds (S3 scan) | Sub-second (columnar, pre-aggregated) |
| **Users** | Operations team (investigation) | Business analysts, leadership, ML |
| **Freshness** | Real-time (Kafka S3 sink writes immediately) | Hours behind (ETL batch pipeline) |
| **Use case** | "Which users missed email?" (ad-hoc) | "Revenue per marketplace per quarter" (BI) |
| **Infrastructure** | Zero (S3 + Athena = serverless) | ETL pipeline + warehouse cluster |

---

## Common Interview Follow-ups

### Q: "When would you convert from data lake to lakehouse?"

> "When query costs or performance become a problem. Our Athena queries scan raw JSON — every query reads every field of every file. Converting to Parquet (columnar) means Athena only reads the columns referenced in the query — `SELECT event.id, action` reads 2 columns instead of 20. Adding Hive partitioning (`year=2026/month=04/`) means Athena skips old data entirely. Together: 20x-100x cost reduction. This IS the lakehouse pattern: same S3 storage, but with columnar format + partitioning + optionally Delta Lake/Iceberg for ACID transactions. No separate warehouse needed."

### Q: "Data lake vs data swamp — how do you prevent it?"

> "A data lake becomes a swamp when nobody knows what's in it or how to query it. Prevention: (1) **Glue Catalog** — our Athena table definition documents the schema (field names, types, nested structure). Anyone can `DESCRIBE table` to understand the data. (2) **Consistent naming** — S3 paths follow a predictable pattern. (3) **Source of truth documentation** — our `Event-Email-Delivery-Investigation-Workflow.md` documents which S3 data maps to which pipeline stage. (4) **Governance** — we query this data regularly (recovery dashboard), so stale/broken data is caught quickly. A lake becomes a swamp when it's write-only. Our lake is write + read regularly."

### Q: "How does your S3 data lake connect to the stream processing pipeline?"

> "The Kafka S3 Sink writes every event to S3 in real-time (within 5 seconds, batched by `batch_count=1, batch_frequency=5`). This means the data lake is **nearly real-time** — not a nightly ETL dump. The same Kafka stream that feeds Rise GTS (stream processing for email) ALSO feeds S3 (data lake for investigation). One event, two paths: stream for action, lake for record-keeping. This is the EDA fan-out pattern (Topic 30) applied to data architecture."

---
---

# Topic 48: Back-of-the-Envelope Calculations

> Estimate QPS, storage, and bandwidth from DAU and usage patterns. Know powers of 2, latency numbers, and common throughput benchmarks.

> **Interview Tip:** Practice the framework — "100M DAU × 10 reads/day = 1B reads/day ÷ 86400 sec ≈ 12K QPS. At 5KB/response, that's 60MB/s bandwidth."

---

## The Reference Numbers

```
┌──────────────────────────────────────────────────────────────────────┐
│  BACK-OF-THE-ENVELOPE REFERENCE SHEET                                │
│                                                                      │
│  POWERS OF 2:              LATENCY NUMBERS:                         │
│  2^10 = 1 Thousand (KB)   L1 cache:        1 ns                    │
│  2^20 = 1 Million (MB)    L2 cache:        4 ns                    │
│  2^30 = 1 Billion (GB)    RAM:             100 ns                   │
│  2^40 = 1 Trillion (TB)   SSD read:        100 μs                  │
│  2^50 = 1 Quadrillion (PB) HDD seek:       10 ms                   │
│                            Network round:   50 ms (same region)     │
│  1 char = 1 byte           Cross-region:    150 ms                  │
│  1 int = 4 bytes                                                    │
│  1 long/double = 8 bytes   ns = nanosecond, μs = microsecond       │
│                            ms = millisecond                         │
│                                                                      │
│  COMMON ESTIMATES:                                                  │
│  QPS (single server):     Storage per day:                          │
│  Web: 1K-10K req/s        1M users × 1KB = 1 GB                   │
│  DB: 10K-50K q/s          1M images × 200KB = 200 GB              │
│  Cache: 100K+ ops/s       1M videos × 5MB = 5 TB                  │
│                                                                      │
│  TIME:                                                              │
│  1 day = 86,400 seconds ≈ 100K seconds (round for estimation)     │
│  1 month ≈ 2.5M seconds                                           │
│  1 year ≈ 30M seconds                                               │
└──────────────────────────────────────────────────────────────────────┘
```

---

## The Estimation Framework

```
┌──────────────────────────────────────────────────────────────────────┐
│  3-STEP ESTIMATION FRAMEWORK                                         │
│                                                                      │
│  Step 1: QPS (Queries Per Second)                                   │
│  ────────────────────────────────                                   │
│  DAU × actions/user/day ÷ 86,400 = average QPS                    │
│  Peak QPS ≈ 2-3× average QPS                                       │
│                                                                      │
│  Step 2: STORAGE                                                    │
│  ────────────────                                                   │
│  Daily new records × size per record = daily storage                │
│  Daily storage × 365 × retention years = total storage             │
│                                                                      │
│  Step 3: BANDWIDTH                                                  │
│  ──────────────────                                                 │
│  QPS × response size = bandwidth (bytes/sec)                       │
│  Inbound: QPS × request size                                       │
│  Outbound: QPS × response size                                     │
└──────────────────────────────────────────────────────────────────────┘
```

---

## CXP Platform — Real Estimations

### Estimation 1: CXP Event Registration — QPS

```
┌──────────────────────────────────────────────────────────────────────┐
│  CXP REGISTRATION QPS                                                │
│                                                                      │
│  NORMAL DAY:                                                        │
│  Active Nike events: ~50 events globally                           │
│  Registrations per event per day: ~100                              │
│  Total registrations/day: 50 × 100 = 5,000                        │
│  QPS: 5,000 ÷ 86,400 ≈ 0.06 QPS (very low!)                     │
│  → 1 ECS task handles this trivially.                              │
│                                                                      │
│  SNEAKER LAUNCH (peak):                                             │
│  1 popular event: 10,000 registrations in 60 seconds               │
│  QPS: 10,000 ÷ 60 = ~167 QPS                                     │
│  → 167 QPS ÷ ~50 QPS per task ≈ 4 tasks needed                   │
│  → Auto-scale from 2 to 8 tasks (4× headroom for safety)          │
│                                                                      │
│  READS (event detail pages — much higher):                         │
│  Users browsing events: ~100,000 page views/day                    │
│  QPS: 100,000 ÷ 86,400 ≈ 1.2 QPS to origin                      │
│  BUT: Akamai CDN serves 95% → only 5% reaches origin              │
│  Actual origin QPS: ~0.06 QPS                                      │
│  Peak (sneaker launch browsing): ~50 QPS to origin (CDN absorbs)  │
│                                                                      │
│  TOTAL CXP PLATFORM QPS:                                           │
│  ┌────────────────────┬──────────────┬──────────────────┐         │
│  │  Operation          │  Normal QPS  │  Peak QPS         │         │
│  ├────────────────────┼──────────────┼──────────────────┤         │
│  │  Registration POST  │  0.06        │  167 (launch)     │         │
│  │  Event page GET     │  0.06 origin │  50 origin        │         │
│  │  (after CDN)        │  1.2 total   │  1000 total (CDN) │         │
│  │  Seat check GET     │  0.1         │  200               │         │
│  │  Search (ES)        │  0.5         │  10                │         │
│  └────────────────────┴──────────────┴──────────────────┘         │
│                                                                      │
│  INSIGHT: CXP is LOW QPS but SPIKY.                                │
│  Normal: <5 QPS total. A single server handles everything.         │
│  Peak: ~500 QPS for 60 seconds. Need auto-scaling + CDN.          │
│  This is WHY we use auto-scaling (2→8 tasks) not fixed capacity.  │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Estimation 2: CXP Storage

```
┌──────────────────────────────────────────────────────────────────────┐
│  CXP STORAGE ESTIMATION                                              │
│                                                                      │
│  S3 PARTNER HUB (data lake):                                       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Registrations per day: ~5,000 (normal) to 15,000 (launch) │    │
│  │  Webhook JSON size: ~2 KB per event                         │    │
│  │  Daily storage: 10,000 × 2 KB = 20 MB/day                  │    │
│  │  Monthly: 20 MB × 30 = 600 MB/month                        │    │
│  │  Yearly: 600 MB × 12 = 7.2 GB/year                         │    │
│  │  Cost: 7.2 GB × $0.023/GB = $0.17/year                     │    │
│  │                                                              │    │
│  │  With ALL action types (confirmed + cancel + pre + post):    │    │
│  │  ~4× registrations = 40,000 events/day                      │    │
│  │  Daily: 40,000 × 2 KB = 80 MB/day                          │    │
│  │  Yearly: ~29 GB/year. Cost: ~$0.67/year.                   │    │
│  │  EXTREMELY cheap. S3 data lake is nearly free for CXP.     │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  DYNAMODB (unprocessed queue):                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Failed registrations: ~2-5% of total = 200-500/day         │    │
│  │  Item size: ~1 KB (payload + metadata)                      │    │
│  │  Items deleted after reprocessing (not growing indefinitely) │    │
│  │  Max items at any time: ~1000 (reprocessed within days)     │    │
│  │  Storage: 1000 × 1 KB = 1 MB (trivial)                     │    │
│  │  Cost: DynamoDB PAY_PER_REQUEST — pennies/month.            │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  REDIS (cache):                                                     │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Idempotency keys: ~10,000/day × ~500 bytes = 5 MB/day     │    │
│  │  TTL: 60 min → max in cache at once: ~500 keys = 250 KB    │    │
│  │  Pairwise cache: ~10,000 users × 200 bytes = 2 MB          │    │
│  │  TTL: 30 days → max: ~100,000 keys = 20 MB                 │    │
│  │  Total Redis: ~25 MB. Smallest ElastiCache node (13 GB)    │    │
│  │  is 500× larger than needed. We're paying for HA, not size. │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  ELASTICSEARCH (search index):                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Total Nike events: ~5,000 (active + archived)              │    │
│  │  Event card document: ~5 KB (name, date, location, desc)   │    │
│  │  Index size: 5,000 × 5 KB = 25 MB (primary shards)        │    │
│  │  With 1 replica: 50 MB total.                              │    │
│  │  5 primary shards × 50 MB = WAY under the 20-40 GB/shard  │    │
│  │  recommendation. We could use 1 shard (but 5 for parallelism)│   │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  SPLUNK (logs):                                                     │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  4 services × ~10 log lines/request × 200 bytes/line        │    │
│  │  Normal: 5,000 requests/day × 4 × 10 × 200 = 40 MB/day    │    │
│  │  Peak: 50,000 requests/day × 4 × 10 × 200 = 400 MB/day    │    │
│  │  Monthly: ~1-12 GB. Retention: 90 days. ~36-360 GB.        │    │
│  │  Nike pays for Splunk enterprise — CXP is a small fraction. │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Estimation 3: CXP Bandwidth

```
┌──────────────────────────────────────────────────────────────────────┐
│  CXP BANDWIDTH ESTIMATION                                            │
│                                                                      │
│  INBOUND (requests to CXP):                                        │
│  Registration POST: body ~1 KB × 167 QPS (peak) = 167 KB/s        │
│  Event page GET: headers ~500 bytes × 50 QPS (origin) = 25 KB/s   │
│  Total inbound (peak): ~200 KB/s ≈ 0.2 MB/s                      │
│  → Trivial. A single network interface handles 1 Gbps = 125 MB/s. │
│                                                                      │
│  OUTBOUND (responses from CXP):                                    │
│  Registration response: ~500 bytes × 167 QPS = 83 KB/s            │
│  Event page JSON: ~10 KB × 50 QPS (origin) = 500 KB/s             │
│  CDN-served pages: ~10 KB × 1000 QPS = 10 MB/s (from Akamai)     │
│  Total outbound origin (peak): ~600 KB/s ≈ 0.6 MB/s              │
│  Total outbound CDN (peak): ~10 MB/s (Akamai handles this)        │
│                                                                      │
│  INTER-SERVICE:                                                     │
│  cxp-reg → Eventtia: ~2 KB req × 167 QPS = 334 KB/s              │
│  cxp-reg → Redis: ~200 bytes × 500 QPS = 100 KB/s                 │
│  Kafka → Rise GTS: ~2 KB × 10 events/sec = 20 KB/s               │
│                                                                      │
│  TOTAL: CXP is a LOW-BANDWIDTH system.                             │
│  Even at peak, total bandwidth < 15 MB/s.                          │
│  Network is NEVER the bottleneck for CXP.                          │
│  The bottleneck is LATENCY (Eventtia API call ~200ms),             │
│  not throughput.                                                    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Estimation 4: Latency Budget — Where Time Goes

```
┌──────────────────────────────────────────────────────────────────────┐
│  CXP LATENCY BUDGET — Registration Flow                              │
│                                                                      │
│  USER CLICKS "REGISTER" → SEES "REGISTERED!" (total: ~250ms)      │
│                                                                      │
│  ┌────────────────────────────────────────────────────┬──────────┐│
│  │  Step                                               │ Latency  ││
│  ├────────────────────────────────────────────────────┼──────────┤│
│  │  Akamai → ALB (TLS + proxy)                        │  ~10 ms  ││
│  │  ALB → ECS task (path routing)                     │  ~2 ms   ││
│  │  JWT validation (AAAConfig, cached public keys)    │  ~1 ms   ││
│  │  Redis GET idempotency check                       │  ~1 ms   ││
│  │  Eventtia API call (external, cross-internet)      │  ~200 ms ││
│  │  Redis SET success response (async, non-blocking)  │  0 ms *  ││
│  │  Return response to user                           │  ~5 ms   ││
│  │  LAMS registration (async, non-blocking)           │  0 ms *  ││
│  │  Akamai purge (async, non-blocking)                │  0 ms *  ││
│  ├────────────────────────────────────────────────────┼──────────┤│
│  │  TOTAL USER-PERCEIVED LATENCY                      │  ~220 ms ││
│  │  * async = happens after response is sent           │          ││
│  └────────────────────────────────────────────────────┴──────────┘│
│                                                                      │
│  BOTTLENECK: Eventtia API (~200ms = 91% of total latency).        │
│  Everything else combined: ~20ms.                                   │
│  Optimization: the ONLY way to significantly reduce registration   │
│  latency is to make Eventtia faster (their side) or cache          │
│  Eventtia responses (but can't cache a write operation).           │
│                                                                      │
│  COMPARISON: Event page GET (with CDN cache hit):                  │
│  User → Akamai PoP → cached response: ~15 ms total.              │
│  90× faster than registration because NO origin call needed.       │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Estimation 5: Infrastructure Sizing

```
┌──────────────────────────────────────────────────────────────────────┐
│  CXP INFRASTRUCTURE SIZING — From Calculations to Config             │
│                                                                      │
│  ECS TASKS:                                                         │
│  Peak QPS: ~500 total (all services combined)                      │
│  Per-task capacity: ~50-100 QPS (Spring Boot on 2 vCPU)            │
│  Tasks needed: 500 ÷ 75 ≈ 7 tasks (round to 8 for safety)        │
│  Normal: 2 tasks per service × 4 services = 8 tasks                │
│  Peak: 4-8 tasks per service = 16-32 tasks                         │
│  Auto-scaling handles the 4× spike.                                │
│                                                                      │
│  REDIS:                                                             │
│  Data size: ~25 MB. Smallest node (r6g.large, 13 GB) is 500× more.│
│  We pay for: HA (Multi-AZ), not memory.                            │
│  Nodes: 1 primary + 3 replicas (for read distribution + failover). │
│                                                                      │
│  DYNAMODB:                                                          │
│  PAY_PER_REQUEST: no capacity planning needed.                     │
│  Normal: ~0.06 WCU (trivial). Sneaker launch: ~167 WCU.           │
│  DynamoDB auto-handles. No sizing needed.                          │
│                                                                      │
│  ELASTICSEARCH:                                                     │
│  Data: ~50 MB (5000 events × 5 KB × 2 copies).                    │
│  5 shards is oversized. 1-2 shards would suffice.                 │
│  We use 5 for search parallelism (Topic 8), not data volume.      │
│                                                                      │
│  S3:                                                                │
│  ~30 GB/year. No sizing. Unlimited storage. Auto-scales.           │
│                                                                      │
│  CDN (Akamai):                                                      │
│  95% cache hit rate. Origin sees 5% of traffic.                    │
│  250+ PoPs handle the distribution. No sizing from us.             │
│                                                                      │
│  KEY INSIGHT: CXP is a SMALL system by Big Tech standards.         │
│  Twitter: 300K QPS. Netflix: 100K QPS. Google: 100M+ QPS.         │
│  CXP: <500 QPS peak. The architecture (multi-region, CDN,         │
│  auto-scaling, Kafka) is designed for SPIKY traffic, not           │
│  sustained high volume. We'd be over-engineered for steady         │
│  500 QPS — but sneaker launches demand burst capacity.             │
└──────────────────────────────────────────────────────────────────────┘
```

---

## The Estimation Interview Template

```
┌──────────────────────────────────────────────────────────────────────┐
│  INTERVIEW ESTIMATION TEMPLATE (use for any system)                  │
│                                                                      │
│  1. CLARIFY REQUIREMENTS                                            │
│     "How many daily active users?"                                  │
│     "Read-heavy or write-heavy?"                                    │
│     "What's the peak-to-average ratio?"                             │
│                                                                      │
│  2. ESTIMATE QPS                                                    │
│     Average: DAU × actions/user/day ÷ 86,400                      │
│     Peak: average × 2-3 (or higher for spiky like CXP)            │
│     Read QPS vs Write QPS (usually 10:1 for most apps)             │
│                                                                      │
│  3. ESTIMATE STORAGE                                                │
│     Per record: count fields × size per field                      │
│     Daily: records/day × record size                               │
│     Yearly: daily × 365 × retention (with replication factor)      │
│                                                                      │
│  4. ESTIMATE BANDWIDTH                                              │
│     Inbound: write QPS × request size                              │
│     Outbound: read QPS × response size                             │
│     CDN absorption: outbound × (1 - cache_hit_rate)               │
│                                                                      │
│  5. SIZE INFRASTRUCTURE                                             │
│     Servers: peak QPS ÷ per-server QPS capacity                   │
│     Database: storage + QPS requirements                           │
│     Cache: working set size + QPS (Redis ~100K ops/s per node)     │
│     CDN: hit rate determines origin load                           │
│                                                                      │
│  6. IDENTIFY BOTTLENECKS                                            │
│     Is it CPU-bound (compute) → more/bigger servers?               │
│     Is it I/O-bound (disk) → SSD, caching?                        │
│     Is it network-bound → CDN, compression?                       │
│     Is it latency-bound → caching, async, CDN edge?              │
│     CXP: LATENCY-bound (Eventtia 200ms API call).                │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: CXP by the Numbers

| Metric | Normal | Peak (Sneaker Launch) |
|--------|--------|----------------------|
| **Registration QPS** | 0.06 | 167 |
| **Event page QPS (origin)** | 0.06 | 50 |
| **Event page QPS (total + CDN)** | 1.2 | 1,000 |
| **S3 storage/year** | ~30 GB | ~30 GB (same — storage doesn't spike) |
| **DynamoDB items** | ~100 | ~1,000 |
| **Redis memory** | ~25 MB | ~25 MB (TTL keeps it bounded) |
| **ES index size** | ~50 MB | ~50 MB (events don't change on launch day) |
| **Bandwidth (origin)** | 0.1 MB/s | 0.6 MB/s |
| **Bandwidth (CDN)** | 1 MB/s | 10 MB/s |
| **Registration latency** | ~220 ms | ~300 ms (Eventtia under load) |
| **Event page latency (CDN hit)** | ~15 ms | ~15 ms (CDN absorbs) |
| **ECS tasks (per service)** | 2 | 8 (auto-scaled) |

---

## Common Interview Follow-ups

### Q: "Walk me through estimating a system like CXP from scratch."

> "Step 1: Nike has ~300M members globally. CXP events: ~1% engage = 3M potential users. Active registrants: ~0.1% = 300K/year. Step 2: 300K registrations/year ÷ 365 = ~800/day average. Sneaker launches: 10K in 60 seconds = 167 QPS peak. Step 3: 800 registrations × 2KB = 1.6 MB/day storage. Step 4: 167 QPS × 10KB response = 1.7 MB/s bandwidth. Step 5: 167 QPS ÷ 75 QPS/task = 3 tasks minimum, 8 for safety. Step 6: Bottleneck is Eventtia latency (200ms), not throughput — CDN absorbs reads, DynamoDB auto-scales writes."

### Q: "Your system handles only 167 QPS peak — why such complex architecture?"

> "The complexity serves three purposes beyond raw QPS: (1) **Spiky traffic** — 0.06 QPS normal to 167 QPS peak is a 2,800× spike in 60 seconds. Fixed infrastructure wastes money 99.9% of the time. Auto-scaling + CDN handles the spike economically. (2) **Multi-region availability** — sneaker launches are globally simultaneous. Route53 + DynamoDB Global Tables serve us-east and us-west users with <100ms latency. (3) **Email delivery pipeline** — the 6-service Kafka pipeline exists for async email delivery, not for QPS. The architecture complexity is driven by reliability requirements (>99.5% email delivery) and burst capacity, not sustained throughput."

### Q: "How would your numbers change at 100× scale?"

> "At 100× (16,700 QPS peak): (1) **Registration:** 16,700 QPS ÷ 75/task = 223 tasks. ECS auto-scaling handles this but we'd need reserved capacity. (2) **Redis:** Still ~25 MB data but 16,700 ops/sec needs Redis Cluster (multiple primaries with hash slots). Single primary maxes at ~100K ops/s — we'd have headroom. (3) **DynamoDB:** PAY_PER_REQUEST handles 16,700 WCU automatically. No change needed. (4) **Eventtia:** This IS the bottleneck. 16,700 concurrent calls × 200ms = 3,340 connections held simultaneously. Eventtia may throttle. Solution: queue registrations and process at Eventtia's rate. (5) **CDN:** 100K page views/min — Akamai handles this trivially (designed for millions). (6) **ES:** Search QPS increases proportionally but 5 shards handle 1000+ QPS easily."

---

## The Complete HLD — 48 Topics, 6 Files

```
┌──────────────────────────────────────────────────────────────────────┐
│  HLD INTERVIEW PREPARATION — COMPLETE (48 Topics)                    │
│                                                                      │
│  01-CAP-Theorem.md                    Topics 1-10   (~4,779 lines) │
│  02-Caching-and-Performance.md        Topics 11-19  (~4,472 lines) │
│  03-Messaging-and-Communication.md    Topics 20-25  (~2,598 lines) │
│  04-Reliability-and-Resilience.md     Topics 26-30  (~2,418 lines) │
│  05-Security-and-Auth.md              Topics 31-38  (~3,191 lines) │
│  06-DevOps-and-Infrastructure.md      Topics 39-48  (~3,700 lines) │
│                                                                      │
│  TOTAL: ~21,158+ lines of HLD interview preparation                │
│  Every topic: theory + CXP code examples + interview answers       │
│  All 48 topics interconnected via real Nike CXP platform            │
└──────────────────────────────────────────────────────────────────────┘
```

---
---

# Topic 49: SLA, SLO, SLI

> SLI is what you measure (latency, error rate); SLO is what you target internally (p99 < 200ms); SLA is what you promise contractually (99.9% uptime with penalties).

> **Interview Tip:** Connect to nines — "99.9% availability means 8.76 hours downtime/year; for critical services targeting 99.99%, I'd need multi-region active-active with automated failover."

---

## The Three Concepts

```
┌──────────────────────────────────────────────────────────────────────┐
│  SLA, SLO, SLI                                                       │
│                                                                      │
│  ┌───────────────┐    ┌───────────────────┐    ┌─────────────────┐ │
│  │     SLI        │    │      SLO           │    │      SLA        │ │
│  │  Service Level │───▶│  Service Level     │───▶│  Service Level  │ │
│  │  INDICATOR     │    │  OBJECTIVE         │    │  AGREEMENT      │ │
│  │                │    │                    │    │                 │ │
│  │  What you      │    │  What you TARGET   │    │  What you       │ │
│  │  MEASURE.      │    │  (internal).       │    │  PROMISE        │ │
│  │                │    │                    │    │  (contract).    │ │
│  │  Quantitative  │    │  Goals for SLIs:   │    │  Legal          │ │
│  │  metrics:      │    │  - p99 latency     │    │  commitment:    │ │
│  │  - Request     │    │    < 200ms         │    │  - 99.9% uptime │ │
│  │    latency p99 │    │  - Error rate      │    │    guaranteed   │ │
│  │  - Error rate %│    │    < 0.1%          │    │  - Penalties if │ │
│  │  - Throughput  │    │  - 99.9%           │    │    breached     │ │
│  │    (req/sec)   │    │    availability    │    │  - Usually <    │ │
│  │                │    │                    │    │    SLO buffer   │ │
│  └───────────────┘    └───────────────────┘    └─────────────────┘ │
│                                                                      │
│  AVAILABILITY NINES:                                                │
│  ┌──────────────┬──────────────┬──────────────┬──────────────────┐│
│  │ Availability  │ Downtime/Year│ Downtime/Month│ Use Case        ││
│  ├──────────────┼──────────────┼──────────────┼──────────────────┤│
│  │ 99% (two 9s) │ 3.65 days    │ 7.3 hours    │ Internal tools  ││
│  │ 99.9% (three)│ 8.76 hours   │ 43.8 min     │ SaaS products   ││
│  │ 99.99% (four)│ 52.6 min     │ 4.38 min     │ E-commerce      ││
│  │ 99.999% (5)  │ 5.26 min     │ 26.3 sec     │ Critical infra  ││
│  └──────────────┴──────────────┴──────────────┴──────────────────┘│
│                                                                      │
│  RELATIONSHIP: SLI measures reality. SLO sets the goal.            │
│  SLA promises to customers. SLO > SLA (internal buffer).           │
│  SLI < SLO → something is wrong. SLI < SLA → contractual breach. │
└──────────────────────────────────────────────────────────────────────┘
```

---

## SLA/SLO/SLI In My CXP Projects

### CXP's Service Level Map

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP SLI / SLO / SLA                                                     │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  SLIs — What We MEASURE                                           │  │
│  │                                                                   │  │
│  │  ┌──────────────────────┬──────────────────────────────────┐    │  │
│  │  │  SLI                  │  How We Measure It                │    │  │
│  │  ├──────────────────────┼──────────────────────────────────┤    │  │
│  │  │  Availability         │  ALB HealthyHostCount > 0,        │    │  │
│  │  │                       │  Route53 health checks passing    │    │  │
│  │  │  Registration latency │  ALB TargetResponseTime (p50,p95) │    │  │
│  │  │  Event page latency   │  Akamai edge response time        │    │  │
│  │  │  Error rate            │  ALB HTTPCode_Target_5XX count    │    │  │
│  │  │  Email delivery rate   │  Splunk: sent/total × 100%       │    │  │
│  │  │  Email drop rate       │  Splunk: dropped/total × 100%    │    │  │
│  │  │  Cache hit rate        │  Akamai cache hit % (CDN)        │    │  │
│  │  │  DynamoDB throttle     │  CloudWatch ThrottledRequests     │    │  │
│  │  └──────────────────────┴──────────────────────────────────┘    │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  SLOs — What We TARGET (internal team goals)                      │  │
│  │                                                                   │  │
│  │  ┌──────────────────────┬──────────────────────────────────┐    │  │
│  │  │  SLO                  │  Target                          │    │  │
│  │  ├──────────────────────┼──────────────────────────────────┤    │  │
│  │  │  Availability         │  99.95% (≈4.38 hours/year)       │    │  │
│  │  │  Registration p95     │  < 500ms (Eventtia latency-bound)│    │  │
│  │  │  Event page p95 (CDN) │  < 50ms (edge-served)            │    │  │
│  │  │  Error rate (5xx)     │  < 0.1% of requests              │    │  │
│  │  │  Email delivery rate  │  > 97% (accepting 2-3% drop rate)│    │  │
│  │  │  Failover RTO         │  < 60 seconds (Route53 failover) │    │  │
│  │  │  Cache hit rate (CDN) │  > 90% (Akamai edge caching)     │    │  │
│  │  │  DynamoDB throttle    │  0 (zero throttled requests)      │    │  │
│  │  └──────────────────────┴──────────────────────────────────┘    │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  SLAs — What We PROMISE (managed service SLAs we depend on)       │  │
│  │                                                                   │  │
│  │  CXP itself doesn't have an external SLA to customers.           │  │
│  │  But we DEPEND ON SLAs from AWS managed services:                │  │
│  │                                                                   │  │
│  │  ┌──────────────────────┬──────────────────────────────────┐    │  │
│  │  │  AWS Service          │  SLA                             │    │  │
│  │  ├──────────────────────┼──────────────────────────────────┤    │  │
│  │  │  ALB                  │  99.99% (4 nines)                │    │  │
│  │  │  DynamoDB             │  99.999% (5 nines, Global Table) │    │  │
│  │  │  S3                   │  99.99% availability,             │    │  │
│  │  │                       │  99.999999999% durability (11 9s)│    │  │
│  │  │  ElastiCache (Redis)  │  99.99% (Multi-AZ)               │    │  │
│  │  │  Route53              │  100% (SLA guarantees 100%!)      │    │  │
│  │  │  ECS Fargate          │  99.99%                           │    │  │
│  │  │  Akamai CDN           │  99.99% (enterprise SLA)          │    │  │
│  │  │  Eventtia (external)  │  Unknown (no SLA published to us) │    │  │
│  │  └──────────────────────┴──────────────────────────────────┘    │  │
│  │                                                                   │  │
│  │  COMPOSITE SLA CALCULATION:                                      │  │
│  │  Our availability ≤ product of all dependency SLAs.             │  │
│  │  ALB × DynamoDB × S3 × Redis × Route53 × ECS × Akamai         │  │
│  │  = 0.9999 × 0.99999 × 0.9999 × 0.9999 × 1.0 × 0.9999 × 0.9999│ │
│  │  = ~99.95% (≈ 4.38 hours downtime/year)                        │  │
│  │                                                                   │  │
│  │  BUT: Eventtia has no published SLA → our weakest link.         │  │
│  │  If Eventtia has 99.9% uptime → our effective SLA drops to      │  │
│  │  ~99.85% (≈ 13 hours downtime/year).                            │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### How SLIs Are Measured in CXP

```
┌──────────────────────────────────────────────────────────────────────┐
│  CXP SLI MEASUREMENT — Where Each Metric Comes From                  │
│                                                                      │
│  SLI: AVAILABILITY                                                  │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Measured by: Route53 health check + ALB HealthyHostCount   │    │
│  │  Route53: GET /events_health_us_east/v1 every 30 seconds.  │    │
│  │  If fails → region marked down → failover triggered.       │    │
│  │  ALB: /actuator/health every 10 seconds per task.           │    │
│  │  If fails → task removed from target group.                 │    │
│  │  Availability = (total_time - downtime) / total_time × 100% │   │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  SLI: REGISTRATION LATENCY                                          │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Measured by: ALB TargetResponseTime metric (CloudWatch).   │    │
│  │  p50: ~200ms (median — most requests hit Eventtia ~200ms). │    │
│  │  p95: ~400ms (95th percentile — slow Eventtia responses).  │    │
│  │  p99: ~800ms (99th — timeout edge cases, Redis slow path). │    │
│  │  Breakdown (Topic 48):                                      │    │
│  │  Eventtia API: 200ms (91% of total latency).               │    │
│  │  Everything else: ~20ms combined.                           │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  SLI: EMAIL DELIVERY RATE                                           │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Measured by: Splunk queries (recovery dashboard Trend tab).│    │
│  │  Formula: (arrived - dropped) / arrived × 100%              │    │
│  │  Normal: ~97-98% delivery rate (2-3% drops).               │    │
│  │  Degraded: <95% (spike in drops — investigate).            │    │
│  │  This is the SLI the recovery dashboard was BUILT to track. │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  SLI: ERROR RATE                                                    │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Measured by: ALB HTTPCode_Target_5XX (CloudWatch).         │    │
│  │  Normal: <0.1% of requests return 5xx.                      │    │
│  │  Alert threshold: >1% for 5 minutes → CloudWatch alarm.    │    │
│  │  Excludes: 4xx (client errors like 422 "already registered" │    │
│  │  are EXPECTED, not failures).                                │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### How Architecture Decisions Map to SLO Targets

```
┌──────────────────────────────────────────────────────────────────────┐
│  ARCHITECTURE DECISION → SLO IT ENABLES                              │
│                                                                      │
│  ┌──────────────────────────────┬────────────────────────────────┐ │
│  │  Architecture Decision        │  SLO It Supports               │ │
│  ├──────────────────────────────┼────────────────────────────────┤ │
│  │  Multi-region active-active   │  Availability: 99.95%          │ │
│  │  (Topic 37: us-east + us-west │  RTO: <60s (automatic failover)│ │
│  │  both serving traffic)        │                                │ │
│  ├──────────────────────────────┼────────────────────────────────┤ │
│  │  Akamai CDN caching           │  Event page p95: <50ms         │ │
│  │  (Topic 13: 95% cache hit)    │  Cache hit rate: >90%         │ │
│  ├──────────────────────────────┼────────────────────────────────┤ │
│  │  ECS auto-scaling 2→8 tasks  │  Registration p95: <500ms      │ │
│  │  (Topic 15: horizontal)       │  during sneaker launches       │ │
│  ├──────────────────────────────┼────────────────────────────────┤ │
│  │  Redis idempotency + fallback │  Error rate: <0.1%             │ │
│  │  (Topic 17: circuit breaker)  │  (try-catch → graceful degrade)│ │
│  ├──────────────────────────────┼────────────────────────────────┤ │
│  │  Kafka async email pipeline   │  Email delivery: >97%          │ │
│  │  + recovery dashboard         │  (stream for speed, batch for  │ │
│  │  (Topics 20, 46: stream+batch)│  completeness)                │ │
│  ├──────────────────────────────┼────────────────────────────────┤ │
│  │  DynamoDB PAY_PER_REQUEST    │  DynamoDB throttle: 0           │ │
│  │  (Topic 8: auto-scaling)      │  (auto-partitions on demand)   │ │
│  ├──────────────────────────────┼────────────────────────────────┤ │
│  │  health.redis.enabled=false   │  Availability not affected by  │ │
│  │  (Topic 36: health check)     │  cache dependency failures     │ │
│  └──────────────────────────────┴────────────────────────────────┘ │
│                                                                      │
│  EVERY architecture decision in the 48 previous topics serves       │
│  at least one SLO. The SLOs ARE the "why" behind the architecture. │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Error Budget — How Much Failure Can We Afford?

```
┌──────────────────────────────────────────────────────────────────────┐
│  ERROR BUDGET — The SRE Approach                                     │
│                                                                      │
│  If SLO = 99.95% availability per month:                           │
│  Error budget = 100% - 99.95% = 0.05%                              │
│  Monthly budget: 0.05% × 43,200 min/month = 21.6 minutes          │
│                                                                      │
│  WHAT CONSUMES ERROR BUDGET IN CXP:                                │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Event                        │ Budget consumed            │    │
│  ├────────────────────────────────┼────────────────────────────┤    │
│  │  Redis failover (~30s)         │ 0.5 minutes               │    │
│  │  Bad deploy (rollback ~5 min)  │ 5 minutes                 │    │
│  │  Regional failover (~60s)      │ 1 minute                  │    │
│  │  Eventtia outage (30 min)      │ 30 minutes ← EXCEEDS!    │    │
│  └────────────────────────────────┴────────────────────────────┘    │
│                                                                      │
│  INSIGHT: A single 30-minute Eventtia outage consumes MORE than    │
│  our entire monthly error budget. Eventtia is our SLO's biggest    │
│  risk — and it's an EXTERNAL dependency we don't control.          │
│                                                                      │
│  ERROR BUDGET POLICY:                                               │
│  Budget remaining → deploy aggressively (ship features).           │
│  Budget nearly exhausted → freeze deployments, focus on stability. │
│  Budget exceeded → incident review, invest in reliability.         │
│                                                                      │
│  CXP IMPLICATION:                                                   │
│  If Eventtia has 2 outages/month (30 min each) = 60 minutes.      │
│  Our error budget: 21.6 minutes. EXCEEDED by Eventtia alone.      │
│  Options: (1) Lower SLO to 99.9% (43.8 min/month budget).        │
│  (2) Build Eventtia fallback (cache last-known-good responses).   │
│  (3) Negotiate Eventtia SLA with penalties.                        │
└──────────────────────────────────────────────────────────────────────┘
```

---

### The PagerDuty Connection — Alerting on SLI Thresholds

```
┌──────────────────────────────────────────────────────────────────────┐
│  ALERTING ON SLI BREACHES                                            │
│                                                                      │
│  ┌──────────────────────┬──────────────┬─────────────────────────┐│
│  │  SLI Breach            │  Alert Target │  Response              ││
│  ├──────────────────────┼──────────────┼─────────────────────────┤│
│  │  5xx rate > 1%         │  CloudWatch   │  Auto-scale ECS tasks ││
│  │  for 5 minutes         │  Alarm        │  + page on-call if    ││
│  │                        │               │  sustained             ││
│  ├──────────────────────┼──────────────┼─────────────────────────┤│
│  │  Route53 health check  │  Route53      │  Auto-failover region.││
│  │  fails N times         │  (automatic)  │  Zero human action.   ││
│  ├──────────────────────┼──────────────┼─────────────────────────┤│
│  │  Email drop rate > 5%  │  Splunk saved │  Page on-call to open ││
│  │  in last hour          │  search       │  recovery dashboard.  ││
│  ├──────────────────────┼──────────────┼─────────────────────────┤│
│  │  DynamoDB throttle > 0 │  CloudWatch   │  Investigate hot key  ││
│  │                        │  Alarm        │  or capacity issue.   ││
│  ├──────────────────────┼──────────────┼─────────────────────────┤│
│  │  SQS DLQ depth > 0    │  CloudWatch   │  Investigate failed   ││
│  │                        │  Alarm        │  transforms in Rise.  ││
│  └──────────────────────┴──────────────┴─────────────────────────┘│
│                                                                      │
│  PagerDuty escalation: nikeb2c.pagerduty.com #PNJHHME             │
│  Team: CSK - CXP Super Koders                                      │
│  Slack: #cxp-events-support                                        │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "We measure SLIs through CloudWatch (ALB latency, 5xx rate, ECS CPU, DynamoDB throttling), Route53 (region health checks), Splunk (email delivery rate, drop trends), and Akamai (cache hit rate). Our SLO targets: 99.95% availability via multi-region active-active, <500ms registration p95, >97% email delivery rate, <0.1% error rate. We don't have a formal customer-facing SLA, but we depend on AWS SLAs (ALB 99.99%, DynamoDB 99.999%, S3 99.99%). The composite SLA is ~99.95% — but our weakest link is Eventtia (external, no published SLA). A single 30-minute Eventtia outage exceeds our monthly error budget of 21.6 minutes."

---

## Summary: SLA/SLO/SLI Across CXP

| Concept | CXP Implementation | Key Numbers |
|---------|-------------------|-------------|
| **SLIs measured** | ALB metrics, Route53 health, Splunk queries, Akamai stats | Latency p95, 5xx rate, drop rate, cache hit % |
| **SLO targets** | Internal team goals | 99.95% availability, <500ms reg latency, >97% email delivery |
| **SLA dependencies** | AWS managed service SLAs | ALB 99.99%, DynamoDB 99.999%, S3 11 nines durability |
| **Composite SLA** | Product of all dependency SLAs | ~99.95% without Eventtia; ~99.85% with Eventtia |
| **Error budget** | 0.05% per month = 21.6 minutes | One Eventtia outage (30 min) exceeds entire budget |
| **Weakest link** | Eventtia (external SaaS, no SLA) | Single point of failure for registration |
| **Alerting** | CloudWatch + Route53 + Splunk → PagerDuty | Auto-failover (Route53), auto-scale (ECS), page human (drops) |

---

## Common Interview Follow-ups

### Q: "How do you go from 99.9% to 99.99%?"

> "Each additional nine requires exponential investment: (1) **99.9% → 99.99%:** Downtime drops from 8.76 hours/year to 52 minutes. Need: multi-region active-active (we have this), automated failover under 1 minute (we have this via Route53), zero-downtime deployments (we have this via ECS rolling updates). (2) **99.99% → 99.999%:** Downtime drops to 5 minutes/year. Need: eliminate ALL external dependencies without SLAs, or build fallbacks for each. Our Eventtia dependency is the blocker — a single 30-minute outage exceeds the entire annual budget. Solution: cache last-known-good Eventtia responses, accept stale event data during outage, queue registrations for later."

### Q: "Your email delivery SLO is 97% — isn't that low?"

> "97% is a deliberate tradeoff. We CHOSE availability over consistency (Topic 1 — CAP theorem). Synchronous email delivery would give 99.9%+ but registration latency would be 30+ seconds (waiting for MemberHub sync). We chose <1 second registration with 97% email delivery + recovery dashboard to fix the 3% gap. The SLO reflects the architecture decision: async pipeline = fast registration = occasional drops = compensating mechanism. If the business required 99.9% email delivery, we'd need to: (1) wait for MemberHub sync before returning success (slower), OR (2) add a retry queue with exponential backoff in NCP (more complex), OR (3) pre-cache user emails before registration (requires architecture change)."

### Q: "How does error budget work in practice?"

> "Every incident consumes error budget. If our SLO is 99.95% monthly (21.6 minutes budget): (1) Redis failover: 30 seconds consumed → 21.1 min remaining. (2) Bad deploy + rollback: 5 minutes → 16.1 min remaining. (3) Eventtia outage: 30 minutes → BUDGET EXCEEDED. When budget is exceeded: freeze feature deployments, focus engineering time on reliability improvements (add circuit breaker on Eventtia, build cached fallback, negotiate Eventtia SLA). When budget is healthy: deploy aggressively, ship features. Error budget turns reliability from a subjective 'be careful' into a quantitative 'you have 16 minutes of risk remaining this month.'"

---
---

# Topic 50: System Design Interview Framework

> 6 steps: (1) Clarify requirements, (2) Estimate scale, (3) High-level design, (4) Deep dive components, (5) Address scaling/bottlenecks, (6) Wrap up.

> **Interview Tip:** Drive the conversation — "I always start by clarifying functional and non-functional requirements, then estimate QPS and storage before drawing any boxes."

---

## The 6-Step Framework

```
┌──────────────────────────────────────────────────────────────────────┐
│  SYSTEM DESIGN INTERVIEW FRAMEWORK                                   │
│                                                                      │
│  ┌───────────────────────────┐  ┌───────────────────────────┐     │
│  │ 1. CLARIFY REQUIREMENTS   │  │ 2. ESTIMATE SCALE          │     │
│  │    (5 min)                 │  │    (5 min)                  │     │
│  │                            │  │                             │     │
│  │ - Functional: What         │  │ - QPS: requests per second │     │
│  │   features? Who uses it?  │  │ - Storage: daily/yearly     │     │
│  │ - Non-functional: Scale?  │  │   data growth               │     │
│  │   Latency? Availability?  │  │ - Back-of-envelope:         │     │
│  │                            │  │   servers, bandwidth        │     │
│  │ Ask: DAU, read/write       │  │                             │     │
│  │ ratio, data size, regions  │  │                             │     │
│  └───────────────────────────┘  └───────────────────────────┘     │
│                                                                      │
│  ┌───────────────────────────┐  ┌───────────────────────────┐     │
│  │ 3. HIGH-LEVEL DESIGN      │  │ 4. DEEP DIVE               │     │
│  │    (10 min)                │  │    (15 min)                 │     │
│  │                            │  │                             │     │
│  │ - Draw main components    │  │ - Database schema + choice  │     │
│  │   (boxes + arrows)         │  │   (SQL vs NoSQL)           │     │
│  │ - API design: endpoints,  │  │ - Caching strategy, data    │     │
│  │   request/response         │  │   partitioning              │     │
│  │                            │  │ - Discuss tradeoffs for     │     │
│  │ Client → LB → API →       │  │   each decision             │     │
│  │ Service → DB/Cache         │  │                             │     │
│  └───────────────────────────┘  └───────────────────────────┘     │
│                                                                      │
│  ┌───────────────────────────┐  ┌───────────────────────────┐     │
│  │ 5. SCALING & BOTTLENECKS  │  │ 6. WRAP UP                  │     │
│  │    (5 min)                 │  │    (5 min)                  │     │
│  │                            │  │                             │     │
│  │ - Single points of failure?│  │ - Summarize key decisions  │     │
│  │ - How to scale each        │  │ - Discuss monitoring,       │     │
│  │   component?               │  │   alerting                  │     │
│  │ - Replication, sharding,  │  │ - Future improvements,      │     │
│  │   caching, CDN, async      │  │   known limitations         │     │
│  └───────────────────────────┘  └───────────────────────────┘     │
│                                                                      │
│  PRO TIPS:                                                          │
│  Drive the conversation    Think out loud      Draw diagrams        │
│  Don't wait for hints      Show your reasoning Visual > verbal      │
│  Discuss tradeoffs         No perfect answer   Ask questions        │
│  Clarify ambiguity                                                  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## The Framework Applied: "Design Nike CXP Event Registration"

Here's how I'd walk through a system design interview using my CXP platform as the example — demonstrating all 50 HLD topics naturally.

### Step 1: Clarify Requirements (5 min)

```
FUNCTIONAL REQUIREMENTS:
"Let me confirm what we're building..."

✓ Users browse Nike events (landing page, event detail page)
✓ Users register for events (POST with authentication)
✓ Users cancel registration (DELETE)
✓ System sends confirmation email after registration
✓ Operations team monitors email delivery health
✓ System recovers dropped emails

NON-FUNCTIONAL REQUIREMENTS:
"Before designing, I need to understand scale and constraints..."

Q: "How many daily active users?"
→ ~300K Nike members engage with events yearly. ~1K/day normal.

Q: "Read-heavy or write-heavy?"
→ Read-heavy (100:1). Millions browse events, thousands register.

Q: "Latency requirements?"
→ Registration: <500ms. Event page: <100ms. Email: eventual (minutes OK).

Q: "Availability target?"
→ 99.95%. Sneaker launches are critical — can't be down.

Q: "Multi-region?"
→ Yes. US + international users. Two AWS regions.

Q: "Peak traffic pattern?"
→ SPIKY. Normal: 0.06 QPS. Sneaker launch: 167 QPS (2800× spike).
```

**Topics demonstrated:** Requirements gathering is the foundation. No topic number — this is the meta-skill.

---

### Step 2: Estimate Scale (5 min)

```
QPS:
  Registration (write): 10K/day ÷ 86400 = 0.12 QPS normal, 167 QPS peak
  Event pages (read): 100K/day ÷ 86400 = 1.2 QPS → CDN absorbs 95%
  → Origin: 0.06 QPS normal, 50 QPS peak

STORAGE:
  S3 webhooks: 10K events/day × 2KB = 20 MB/day, ~7 GB/year
  DynamoDB: ~1K items max (retry queue, transient)
  Redis: ~25 MB (TTL-bounded cache)
  Elasticsearch: ~50 MB (5K event cards)

BANDWIDTH:
  Origin outbound: 167 QPS × 10KB = 1.7 MB/s peak
  CDN outbound: 1000 QPS × 10KB = 10 MB/s (Akamai handles)

INFRASTRUCTURE:
  ECS: 167 QPS ÷ 75/task = 3 tasks min, 8 for safety
  Redis: 25 MB data, smallest node is 500× enough
  DynamoDB: PAY_PER_REQUEST (auto-scales)

BOTTLENECK: Latency (Eventtia 200ms), not throughput.
```

**Topics demonstrated:** Topic 48 (Back-of-Envelope)

---

### Step 3: High-Level Design (10 min)

```
"Let me draw the architecture..."

┌────────┐   ┌────────┐   ┌────────┐   ┌──────────────┐
│Browser │──▶│Akamai  │──▶│  ALB   │──▶│ cxp-events   │──▶ Eventtia
│        │   │CDN     │   │(L7 path│   │ cxp-reg      │──▶ Redis
└────────┘   │(cache, │   │routing)│   │ expviews     │──▶ DynamoDB
             │SSL,WAF)│   └────────┘   │ Rise GTS     │──▶ ES
             └────────┘                └──────────────┘
                                             │
                                        Kafka/NSP3
                                        (event stream)
                                             │
                                    ┌────────┼────────┐
                                    ▼        ▼        ▼
                              Rise GTS    S3 sink   Purge sink
                              (email)   (archive)  (CDN cache)

API DESIGN:
  GET  /community/events/v1/{id}          → Event detail (public)
  GET  /community/events/v1               → Landing page (public)
  POST /community/event_registrations/v1  → Register (auth required)
  DELETE /community/event_registrations   → Cancel (auth required)
  GET  /community/attendee_status/v1      → Status (auth required)
```

**Topics demonstrated:** Topic 14 (Load Balancing), Topic 13 (CDN), Topic 22 (REST), Topic 28 (API Gateway), Topic 29 (Microservices), Topic 30 (EDA)

---

### Step 4: Deep Dive (15 min)

```
DATABASE CHOICES (Topic 2, 4):
"Each service owns its data store, chosen for its access pattern..."

  Registration → DynamoDB (key-value, auto-scales for spikes)
  Search → Elasticsearch (inverted index, full-text + geo)
  Cache → Redis (sub-ms reads, idempotency, TTL)
  Audit → S3 + Athena (data lake, cheap, schema-on-read)
  Source of truth → Eventtia (external, relational, ACID)
  No SQL database needed — all NoSQL.

CACHING STRATEGY (Topics 11-12):
"Multi-layer: CDN → JVM → Redis..."

  L1: Akamai CDN (60min event pages, 1min seats, tag-based purge)
  L2: Caffeine JVM (60-day translations, 15-min event refresh)
  L3: Redis (60-min success response, 30-day pairwise, 1-min counter)

CONSISTENCY MODEL (Topics 1, 3):
"We chose AP over CP..."

  Registration: synchronous to Eventtia (strong consistency for seats)
  Email delivery: async via Kafka (eventually consistent — 2-5% drops)
  Cache: eventual (Redis async replication, CDN TTL-based staleness)
  Compensation: recovery dashboard detects + fixes gaps

AUTHENTICATION (Topic 31):
"JWT stateless auth with three security levels..."

  @Unsecured: event pages (public, CDN-cacheable)
  @AccessValidator: registration (consumer JWT from accounts.nike.com)
  @JwtScope: service-to-service (OSCAR tokens with specific scopes)
```

**Topics demonstrated:** Topics 1-5 (Database), 11-12 (Caching), 22 (REST), 26 (Idempotency), 31 (Auth)

---

### Step 5: Scaling & Bottlenecks (5 min)

```
SINGLE POINTS OF FAILURE:
  Eventtia (external SaaS) → DynamoDB queue for deferred retry
  Redis → management.health.redis.enabled=false + try-catch fallback
  Single region → multi-region active-active (Route53 failover)

SCALING STRATEGY:
  Reads: CDN absorbs 95% (Topic 13). ES replica shards (Topic 7).
  Writes: ECS auto-scale 2→8 tasks (Topic 15). DynamoDB auto-partition.
  Spikes: 2800× burst → auto-scaling + CDN + Kafka buffering.

WHAT I'D IMPROVE:
  1. Circuit breaker on Eventtia (Topic 17) — stop calling during outage
  2. Athena partitioning (Topic 10) — 20× cheaper queries
  3. Formal distributed tracing (Topic 35) — OpenTelemetry across Kafka
  4. Canary deployments (Topic 39) — % traffic split for risky changes
```

**Topics demonstrated:** Topics 7, 8, 10, 13, 15, 17, 35, 37, 39

---

### Step 6: Wrap Up (5 min)

```
KEY DECISIONS SUMMARY:
  ✓ Microservices (4 services) for independent scaling
  ✓ Event-driven (Kafka) for loose coupling + fan-out
  ✓ AP over CP for registration speed (<1s vs 30s)
  ✓ Multi-region active-active for 99.95% availability
  ✓ Polyglot persistence (5 databases, each purpose-fit)
  ✓ Stateless services (JWT auth, external state in Redis/DynamoDB)

MONITORING:
  SLIs: ALB latency, 5xx rate, email drop rate (Splunk), cache hit rate
  SLOs: 99.95% availability, <500ms registration, >97% email delivery
  Alerting: CloudWatch alarms → auto-scale, Route53 → auto-failover,
            Splunk → page on-call for email drops

KNOWN LIMITATIONS:
  - Eventtia dependency (no SLA, single point for registration)
  - 2-5% email drop rate (MemberHub race condition)
  - No formal circuit breaker (try-catch fallbacks instead)
  - Elasticsearch not cross-region (cold start on region failover)

FUTURE: Circuit breaker, Athena partitioning, OpenTelemetry tracing,
        automated recovery (not human-triggered dashboard).
```

**Topics demonstrated:** Topics 29, 30, 1, 37, 4, 19, 34, 49

---

## Topic Map: Which Topics to Use When

```
┌──────────────────────────────────────────────────────────────────────┐
│  TOPIC CHEAT SHEET — When Each Topic Comes Up in Interview           │
│                                                                      │
│  "What database would you use?"                                     │
│  → Topics 1-5: CAP, SQL/NoSQL, ACID/BASE, DB Selection, Indexing  │
│                                                                      │
│  "How would you scale this?"                                        │
│  → Topics 7-10: Replicas, Sharding, Consistent Hashing, Partitioning│
│  → Topics 15-16: Vertical/Horizontal Scaling, Rate Limiting        │
│                                                                      │
│  "How do you handle caching?"                                       │
│  → Topics 11-13: Caching Patterns, Eviction, CDN                  │
│                                                                      │
│  "How do services communicate?"                                     │
│  → Topics 20-23: Queues/Streams, Sync/Async, REST/GraphQL/gRPC    │
│                                                                      │
│  "How do you handle failures?"                                      │
│  → Topics 17, 24, 26, 37: Circuit Breaker, Sagas, Idempotency,    │
│    Failover                                                         │
│                                                                      │
│  "How do you deploy and monitor?"                                   │
│  → Topics 34-36, 39-40, 49: Observability, Health Checks,         │
│    Deployment, Containers, SLO                                      │
│                                                                      │
│  "How do you secure this?"                                          │
│  → Topics 31-33: Auth, TLS, Encryption                            │
│                                                                      │
│  "Estimate the numbers."                                            │
│  → Topic 48: Back-of-Envelope Calculations                        │
│                                                                      │
│  "Walk me through the design."                                      │
│  → Topic 50: This framework.                                       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## The 50-Topic Master Index

```
┌──────────────────────────────────────────────────────────────────────┐
│  HLD INTERVIEW PREPARATION — 50 TOPICS COMPLETE                      │
│                                                                      │
│  01-CAP-Theorem.md (Topics 1-10)         ~4,779 lines              │
│  ├── 1.  CAP Theorem           14. Load Balancing                  │
│  ├── 2.  SQL vs NoSQL          15. Vertical/Horizontal Scaling     │
│  ├── 3.  ACID vs BASE          16. Rate Limiting                   │
│  ├── 4.  Database Selection    17. Circuit Breaker                 │
│  ├── 5.  Database Indexing     18. Connection Pooling              │
│  ├── 6.  Database Replication  19. Stateless vs Stateful           │
│  ├── 7.  Read Replicas                                             │
│  ├── 8.  Sharding Strategies   03-Messaging.md (Topics 20-25)     │
│  ├── 9.  Consistent Hashing    ├── 20. Message Queue vs Stream    │
│  └── 10. Data Partitioning     ├── 21. Sync vs Async              │
│                                 ├── 22. REST vs GraphQL vs gRPC   │
│  02-Caching.md (Topics 11-19)  ├── 23. Real-Time Communication   │
│  ├── 11. Caching Patterns      ├── 24. Distributed Transactions  │
│  ├── 12. Cache Eviction        └── 25. Consensus Algorithms      │
│  ├── 13. CDN                                                       │
│                                 04-Reliability.md (Topics 26-30)  │
│  05-Security.md (Topics 31-38) ├── 26. Idempotency               │
│  ├── 31. AuthN vs AuthZ       ├── 27. Service Discovery          │
│  ├── 32. SSL/TLS & HTTPS      ├── 28. API Gateway                │
│  ├── 33. Encryption Types      ├── 29. Monolith vs Microservices │
│  ├── 34. Observability         └── 30. Event-Driven Architecture │
│  ├── 35. Distributed Tracing                                      │
│  ├── 36. Health Checks         06-DevOps.md (Topics 39-50)       │
│  ├── 37. Failover & Redundancy ├── 39. Deployment Strategies     │
│  └── 38. Disaster Recovery     ├── 40. Containers & Kubernetes   │
│                                 ├── 41. DNS                       │
│                                 ├── 42. Forward vs Reverse Proxy  │
│                                 ├── 43. Bloom Filters             │
│                                 ├── 44. Geohashing & Quadtrees   │
│                                 ├── 45. UUID vs Auto-Increment   │
│                                 ├── 46. Batch vs Stream Processing│
│                                 ├── 47. Data Lake vs Warehouse   │
│                                 ├── 48. Back-of-Envelope Calc    │
│                                 ├── 49. SLA, SLO, SLI            │
│                                 └── 50. Interview Framework      │
│                                                                      │
│  TOTAL: 50 topics × CXP code examples × interview answers          │
│  6 files, ~23,000+ lines                                           │
│  Every topic connected to real Nike CXP platform                   │
└──────────────────────────────────────────────────────────────────────┘
```