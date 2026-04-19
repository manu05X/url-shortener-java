# Topic 31: Authentication vs Authorization

> Authentication verifies identity ("who are you?"); authorization determines permissions ("what can you do?"). JWT provides stateless auth tokens.

> **Interview Tip:** Know OAuth 2.0 flow — "User authenticates with auth server, app receives authorization code, exchanges for access token, uses token for API calls."

---

## The Core Difference

```
┌──────────────────────────────────────────────────────────────────────┐
│          AUTHENTICATION vs AUTHORIZATION                             │
│                                                                      │
│  ┌────────────────────────────┐  ┌────────────────────────────┐    │
│  │     AUTHENTICATION         │  │     AUTHORIZATION          │    │
│  │     "Who are you?"         │  │     "What can you do?"     │    │
│  │                            │  │                            │    │
│  │  Verifies identity of      │  │  Determines permissions /  │    │
│  │  user/service.             │  │  access level.             │    │
│  │                            │  │                            │    │
│  │  Methods:                  │  │  Methods:                  │    │
│  │  - Username/Password       │  │  - RBAC (Role-Based       │    │
│  │  - OAuth 2.0 / OpenID     │  │    Access Control)         │    │
│  │    Connect                 │  │  - ABAC (Attribute-Based)  │    │
│  │  - API Keys, Certificates │  │  - ACL (Access Control     │    │
│  │  - Biometrics              │  │    Lists)                  │    │
│  │                            │  │                            │    │
│  │  Happens FIRST.            │  │  Happens AFTER authn.      │    │
│  │  "Prove you are who you    │  │  "OK, you're uuid-1234.   │    │
│  │   claim to be."            │  │   Can you access THIS      │    │
│  │                            │  │   resource?"               │    │
│  └────────────────────────────┘  └────────────────────────────┘    │
│                                                                      │
│  ANALOGY:                                                           │
│  Authentication = showing your ID at the building entrance          │
│  Authorization = your keycard only opens certain floors/rooms       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## JWT (JSON Web Token)

```
┌──────────────────────────────────────────────────────────────────────┐
│  JWT — Stateless, Self-Contained, Verifiable                         │
│                                                                      │
│  ┌──────────┐ . ┌──────────┐ . ┌──────────┐                       │
│  │  HEADER  │   │ PAYLOAD  │   │SIGNATURE │                       │
│  │  alg,typ │   │ user_id, │   │ HMAC/RSA │                       │
│  │          │   │ roles,   │   │          │                       │
│  │          │   │ exp      │   │          │                       │
│  └──────────┘   └──────────┘   └──────────┘                       │
│                                                                      │
│  Header:    { "alg": "RS256", "typ": "JWT" }                       │
│  Payload:   { "sub": "uuid-1234", "prn": "upmid-5678",             │
│               "iss": "oauth2acc", "exp": 1699903600,                │
│               "scp": ["promotional_events:...::read:"] }            │
│  Signature: HMACSHA256(base64(header) + "." + base64(payload),     │
│             secret)                                                  │
│                                                                      │
│  WHY JWT:                                                           │
│  ✓ Stateless — server doesn't store sessions. Token IS the session.│
│  ✓ Self-contained — user ID, roles, expiry all in the token.       │
│  ✓ Verifiable — signature proves token wasn't tampered with.       │
│  ✓ Scalable — any server can validate (no shared session store).   │
│                                                                      │
│  TRADEOFFS:                                                         │
│  ✗ Can't revoke mid-flight (token valid until exp).                │
│  ✗ Payload visible (base64 encoded, not encrypted).                │
│  ✗ Size — JWT is larger than a session ID cookie.                  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## OAuth 2.0 Flow (Authorization Code)

```
┌──────────────────────────────────────────────────────────────────────┐
│  OAuth 2.0 — Authorization Code Flow                                 │
│                                                                      │
│  ┌──────┐  1.login  ┌──────────┐  2.code  ┌─────┐                 │
│  │ User │──────────▶│Auth Server│────────▶│ App │                 │
│  └──────┘           └──────────┘          └──┬──┘                 │
│                                               │                    │
│                      ┌──────────┐  3.exchange │                    │
│                      │Auth Server│◀───────────┘                    │
│                      └─────┬────┘  (code for token)               │
│                            │                                       │
│                     4.token│                                       │
│                            ▼                                       │
│                      ┌──────┐   5.API call   ┌─────┐             │
│                      │ App  │───────────────▶│ API │             │
│                      │      │  Bearer <token> │     │             │
│                      └──────┘                 └─────┘             │
│                                                                      │
│  Step 1: User clicks "Login with Nike" → browser redirects to      │
│          accounts.nike.com (Auth Server)                            │
│  Step 2: User enters credentials → Auth Server returns auth code   │
│  Step 3: App exchanges auth code for access token (server-side)    │
│  Step 4: Auth Server returns JWT access token                      │
│  Step 5: App includes token in API calls: Authorization: Bearer <JWT>│
└──────────────────────────────────────────────────────────────────────┘
```

---

## Auth In My CXP Projects — Real Examples

### The CXP Platform — Dual Authentication Model

Our platform has **two completely different authentication flows**: one for Nike members (consumers) and one for internal Nike services.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — DUAL AUTHENTICATION MODEL                                │
│                                                                          │
│  FLOW 1: CONSUMER AUTHENTICATION (user-facing)                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                                                                   │  │
│  │  User ──login──▶ accounts.nike.com ──JWT──▶ nike.com frontend   │  │
│  │                   (OAuth 2.0)              │                     │  │
│  │                                            │ Authorization:      │  │
│  │                                            │ Bearer <consumer JWT>│ │
│  │                                            ▼                     │  │
│  │                                   cxp-event-registration         │  │
│  │                                   AAAConfig validates JWT:       │  │
│  │                                   • Signature (public keys S3)   │  │
│  │                                   • Expiry (ValidationRuleJwtTime)│ │
│  │                                   • Issuer: "oauth2acc"           │  │
│  │                                   • Extract PRN (user ID)        │  │
│  │                                                                   │  │
│  │  TOKEN PAYLOAD:                                                  │  │
│  │  {                                                               │  │
│  │    "iss": "oauth2acc",           ← accounts.nike.com issued it  │  │
│  │    "prn": "upmid-1234-5678",    ← user identity (Nike Member)  │  │
│  │    "exp": 1699903600,            ← expiry timestamp             │  │
│  │    "scp": ["promotional_events:community.experiences.events:    │  │
│  │             :read:"]             ← what they can access          │  │
│  │  }                                                               │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  FLOW 2: SERVICE-TO-SERVICE AUTHENTICATION (internal)                   │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                                                                   │  │
│  │  NSP3 sink ──OSCAR token──▶ Rise GTS /data/transform/v1        │  │
│  │  cxp-reg  ──OSCAR token──▶ Akamai Purge API                    │  │
│  │  cxp-reg  ──OSCAR token──▶ Partner Consumer Mapper (Pairwise)   │  │
│  │                                                                   │  │
│  │  OSCAR TOKEN PAYLOAD:                                            │  │
│  │  {                                                               │  │
│  │    "iss": "oscar-issuer",        ← Nike OSCAR service issued it │  │
│  │    "sub": "cxp-event-reg",       ← calling service identity     │  │
│  │    "scp": ["developer_enablement:cdn.services.cache_purge:      │  │
│  │             :create:"]           ← specific scope for this API  │  │
│  │    "exp": 1699903600             ← short-lived                  │  │
│  │  }                                                               │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: Consumer JWT — Authentication + Authorization in One Token

**Service:** `cxp-event-registration`
**Authentication:** JWT signature validation via `AAAConfig.java`
**Authorization:** `@AccessValidator` (requires valid token) + PRN extraction (user identity)

```
┌──────────────────────────────────────────────────────────────────────┐
│  Consumer Authentication Flow — Step by Step                         │
│                                                                      │
│  1. USER AUTHENTICATES with accounts.nike.com (OAuth 2.0):         │
│     Browser → accounts.nike.com → login → JWT issued                │
│                                                                      │
│  2. FRONTEND sends request with JWT:                                │
│     POST /community/event_registrations/v1                          │
│     Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.eyJpc3M...         │
│                                                                      │
│  3. AAAConfig AUTHENTICATES the token:                              │
│     ┌────────────────────────────────────────────────────────┐    │
│     │  a. Fetch public keys from S3:                          │    │
│     │     https://s3.amazonaws.com/publickeys.foundation-     │    │
│     │     prod.nikecloud.com/keys/                            │    │
│     │     (cached with TTL — not fetched per request)         │    │
│     │                                                          │    │
│     │  b. Validate JWT signature:                              │    │
│     │     NikeSimpleJwtJWSValidator verifies the signature    │    │
│     │     using RSA public key. If tampered → REJECT.         │    │
│     │                                                          │    │
│     │  c. Check expiry:                                        │    │
│     │     ValidationRuleJwtTime checks "exp" claim.           │    │
│     │     If expired → REJECT (401 Unauthorized).             │    │
│     │                                                          │    │
│     │  d. Verify issuer:                                       │    │
│     │     "iss" must be "oauth2acc" (accounts.nike.com).       │    │
│     │     If wrong issuer → REJECT.                           │    │
│     └────────────────────────────────────────────────────────┘    │
│                                                                      │
│  4. AccessValidatorAspect AUTHORIZES the request:                  │
│     ┌────────────────────────────────────────────────────────┐    │
│     │  @Before AOP advice on every @AccessValidator method:    │    │
│     │  - JWT valid? → extract PRN (user ID: "upmid-1234")     │    │
│     │  - PRN available? → set in request context               │    │
│     │  - User can only register THEMSELVES (PRN = their own)   │    │
│     │  - Cannot register on behalf of another user             │    │
│     └────────────────────────────────────────────────────────┘    │
│                                                                      │
│  5. CONTROLLER executes with authenticated + authorized user:      │
│     registerEventUser(context, eventId, request, upmId, jwt)       │
│     upmId = extracted from JWT PRN claim                           │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// AAAConfig.java — AUTHENTICATION: validate JWT signature + expiry
// Public keys fetched from S3, cached
// NikeSimpleJwtJWSValidator validates RSA signature
// ValidationRuleJwtTime checks token expiry
// Issuer: "oauth2acc" (accounts.nike.com)

// AccessValidatorAspect.java — AUTHORIZATION: check @AccessValidator
@Before("@annotation(AccessValidator)")
public void validate(JoinPoint joinPoint) {
    // If JWT invalid → throw UnauthorizedException (401)
    // If valid → extract PRN → set in context for controller
}

// EventRegistrationController.java — three security levels:

@PostMapping
@AccessValidator   // AUTHENTICATED + AUTHORIZED (valid consumer JWT)
public Mono<ResponseEntity<EventRegistrationResponse>> registerEventUser(...)

@GetMapping("/{event_id}")
@Unsecured         // PUBLIC — no authentication required
public Mono<ResponseEntity<Event>> getEventDetailPage(...)

@PostMapping("purge-cache")
@JwtScope(EVENTS_PURGE_CACHE_DELETE_SCOPE)  // SERVICE scope (OSCAR token)
public ResponseEntity<Void> purgeCache(...)
```

**Interview answer:**
> "Our consumer authentication follows OAuth 2.0: users authenticate with accounts.nike.com and receive a JWT. Our backend validates the JWT signature using RSA public keys cached from S3, checks expiry, and verifies the issuer is 'oauth2acc'. The PRN claim (user ID) extracted from the JWT becomes the identity for authorization — a user can only register themselves, not others. We have three security levels: `@Unsecured` for public endpoints like event detail pages, `@AccessValidator` for consumer-authenticated endpoints like registration, and `@JwtScope` for service-to-service calls with specific OSCAR scopes."

---

### Example 2: OSCAR — Service-to-Service Authentication

**Service:** cxp-event-registration → Akamai, Pairwise, LAMS
**Pattern:** Each service-to-service call requires an OSCAR token with specific scope

```
┌──────────────────────────────────────────────────────────────────────┐
│  OSCAR Service-to-Service Authentication                             │
│                                                                      │
│  OSCAR is Nike's internal OAuth2 token provider for service identity.│
│  Each API call between Nike services requires a scoped OSCAR token. │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  cxp-event-registration wants to purge Akamai cache:         │  │
│  │                                                               │  │
│  │  1. Request OSCAR token:                                      │  │
│  │     oscarConfig.generateOscarToken(                           │  │
│  │       "developer_enablement:cdn.services.cache_purge::create:"│  │
│  │     )                                                         │  │
│  │     → OSCAR validates: "Is cxp-reg allowed this scope?"      │  │
│  │     → Returns signed JWT with the scope                       │  │
│  │                                                               │  │
│  │  2. Call Akamai API with OSCAR token:                         │  │
│  │     Authorization: Bearer <oscar-jwt>                         │  │
│  │     POST /akamai/purge { tags: ["edp_73067"] }               │  │
│  │                                                               │  │
│  │  3. Akamai validates:                                         │  │
│  │     - Token signature (OSCAR public keys)                     │  │
│  │     - Token scope includes "cache_purge::create:"             │  │
│  │     - Token not expired                                       │  │
│  │     → Allows the purge operation                              │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  SCOPE MATRIX (who can call what):                                  │
│  ┌────────────────────────┬──────────────────────────────────────┐│
│  │  Caller               │  OSCAR Scope                          ││
│  ├────────────────────────┼──────────────────────────────────────┤│
│  │  cxp-reg → Akamai     │  cdn.services.cache_purge::create:   ││
│  │  cxp-reg → Pairwise   │  partner.consumer::create:           ││
│  │  NSP3 → Rise GTS      │  (via Kafka Connect OSCAR config)    ││
│  │  Rise GTS → NSPv2     │  rop.nsp.publisher::create:          ││
│  │  NSP3 → cxp-events    │  events_cache::delete: (purge-cache) ││
│  └────────────────────────┴──────────────────────────────────────┘│
│                                                                      │
│  AUTHENTICATION: OSCAR token proves "I am cxp-event-registration" │
│  AUTHORIZATION: scope proves "I'm allowed to purge Akamai cache"  │
│  BOTH in one token. Service identity + permissions.                │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// Constants.java — OSCAR scopes (authorization policies)
public static final String AKAMAI_PURGE_CACHE_CREATE_SCOPE =
    "developer_enablement:cdn.services.cache_purge::create:";
public static final String PARTNER_CONSUMER_MAPPER_CREATE_SCOPE =
    "membership:partner.consumer::create:";
public static final String EVENTS_PURGE_CACHE_DELETE_SCOPE =
    "promotional_events:community.experiences.events_cache::delete:";

// AkamaiCacheService.java — generate scoped OSCAR token per call
PurgeResponse purgeResponse = akamaiCachePurgeApi.purgeAkamaiCache(
    oscarConfig.generateOscarToken(AKAMAI_PURGE_CACHE_CREATE_SCOPE),
    akamaiPurgeRequest
).execute().body();

// PurgeCacheController.java — verify caller has correct scope
@PostMapping("purge-cache")
@JwtScope(EVENTS_PURGE_CACHE_DELETE_SCOPE)   // AUTHORIZATION check
public ResponseEntity<Void> purgeCache(...) { ... }
```

---

### Example 3: The Three Security Levels — Public, Consumer, Service

```
┌──────────────────────────────────────────────────────────────────────┐
│  THREE SECURITY LEVELS IN CXP                                        │
│                                                                      │
│  LEVEL 1: @Unsecured (Public — no auth required)                    │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  GET /community/events/v1/{id}        Event detail page     │    │
│  │  GET /community/events/v1             Event landing page    │    │
│  │  GET /community/events_health/v1      Health check          │    │
│  │  GET /community/groups/v1/{id}        Group detail page     │    │
│  │                                                              │    │
│  │  WHO: Anyone on the internet (no login required).            │    │
│  │  WHY: Event information is public — you can browse without  │    │
│  │  logging in. CDN caches these responses (cacheable because   │    │
│  │  not user-specific).                                         │    │
│  │  RISK: Event IDs are guessable (Topic: deep link problem).  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  LEVEL 2: @AccessValidator (Consumer — valid Nike member JWT)       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  POST /community/event_registrations/v1   Register          │    │
│  │  DELETE /community/event_registrations     Cancel            │    │
│  │  PATCH /community/event_registrations/v1   Activity reg     │    │
│  │  GET /community/attendee_status/v1        Status check      │    │
│  │                                                              │    │
│  │  WHO: Logged-in Nike members only.                           │    │
│  │  HOW: JWT from accounts.nike.com validated by AAAConfig.    │    │
│  │  PRN: User can only act on THEIR OWN registration.          │    │
│  │  NOT CACHEABLE: Response is user-specific.                  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  LEVEL 3: @JwtScope (Service — OSCAR token with specific scope)    │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  POST /community/events/v1/purge-cache    CDN cache purge   │    │
│  │  POST /data/transform/v1                  Rise GTS transform│    │
│  │                                                              │    │
│  │  WHO: Internal Nike services with correct OSCAR scope only. │    │
│  │  HOW: OSCAR JWT validated by @JwtScope annotation.          │    │
│  │  Scope: Only callers with "events_cache::delete:" scope     │    │
│  │  can call purge-cache. Rise GTS requires transform scope.   │    │
│  │  NOT USER-FACING: Called by Kafka sinks, not browsers.      │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 4: Consumer vs OSCAR — JWT Token Comparison

```
┌──────────────────────────────────────────────────────────────────────┐
│  JWT TOKEN COMPARISON                                                │
│                                                                      │
│  CONSUMER TOKEN (accounts.nike.com)   OSCAR TOKEN (Nike internal)  │
│  ───────────────────────────────────  ────────────────────────────  │
│                                                                      │
│  {                                    {                              │
│    "iss": "oauth2acc",                  "iss": "oscar-issuer",      │
│    "sub": "...",                         "sub": "cxp-event-reg",    │
│    "prn": "upmid-1234-5678",            "scp": [                   │
│    "iat": 1699900000,                      "cdn.cache_purge:       │
│    "exp": 1699903600,                       :create:"              │
│    "scp": [                                ],                       │
│      "promotional_events:..."            "exp": 1699903600          │
│    ]                                   }                            │
│  }                                                                  │
│                                                                      │
│  ┌──────────────┬─────────────────┬──────────────────────────┐    │
│  │              │ Consumer JWT     │ OSCAR JWT                │    │
│  ├──────────────┼─────────────────┼──────────────────────────┤    │
│  │ Issuer       │ accounts.nike   │ OSCAR service            │    │
│  │ Identity     │ Nike Member     │ Nike service (cxp-reg)   │    │
│  │ ID claim     │ PRN (user UUID) │ sub (service name)       │    │
│  │ Validated by │ AAAConfig.java  │ @JwtScope annotation     │    │
│  │ Public keys  │ S3 bucket       │ OSCAR JWKS endpoint      │    │
│  │ Used for     │ User-facing APIs│ Service-to-service calls │    │
│  │ Cached?      │ No (per-user)   │ Yes (short-lived, reused)│    │
│  └──────────────┴─────────────────┴──────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 5: The Deep Link Security Issue — AuthN Without AuthZ

From `TaskInternal.md` — a real security discussion in your CXP platform:

```
┌──────────────────────────────────────────────────────────────────────┐
│  THE DEEP LINK PROBLEM — Missing Authorization                       │
│                                                                      │
│  GET /community/events/v1/{event_id} is @Unsecured (public).       │
│                                                                      │
│  PROBLEM: Internal-only Nike events (employee events) are           │
│  accessible to ANYONE who knows/guesses the event_id.               │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Nike Employee gets link: nike.com/experiences/event/INT-123│    │
│  │  → Can view event details ✓ (intended)                      │    │
│  │                                                              │    │
│  │  External user obtains link (forwarded, guessed, leaked)    │    │
│  │  → Can ALSO view event details ⚠️ (unintended!)             │    │
│  │  → Event info exposed even though not on landing page       │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  ROOT CAUSE:                                                        │
│  Authentication: Not required (@Unsecured) ← event pages are public│
│  Authorization: No check for "is this an internal event?"           │
│  → Authentication alone doesn't solve this. You need AUTHORIZATION │
│    to check: "Is this event public? Is the user a Nike employee?"  │
│                                                                      │
│  PROPOSED SOLUTION (from your architecture docs):                   │
│  Separate deployment for internal events:                           │
│  - External: www.nike.com → Consumer JWT (accounts.nike.com)        │
│  - Internal: cxp-events.internal.nike → OKTA JWT (nike.okta.com)   │
│  → ALL internal event endpoints require OKTA authentication        │
│  → Deep links to internal events fail without OKTA login            │
│  → Clean separation: AuthN model matches event visibility           │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "We have a real-world example of authentication vs authorization gaps. Our event detail page is `@Unsecured` — public access because event information should be browsable without login. But internal Nike employee events are also served by the same endpoint. The authentication layer (no auth required for GET) doesn't enforce the authorization rule (internal events should only be visible to Nike employees). The proposed fix is a separate internal deployment using OKTA SSO instead of consumer OAuth — changing the authentication mechanism to match the authorization requirement. This shows why you need both: authentication tells you WHO, authorization tells you WHAT they can access."

---

### Example 6: Stateless Auth — Why JWT Enables Horizontal Scaling

```
┌──────────────────────────────────────────────────────────────────────┐
│  JWT = Stateless Auth = Horizontal Scaling                           │
│                                                                      │
│  SESSION-BASED (stateful):          JWT-BASED (stateless — CXP):   │
│  ┌──────────┐   ┌────────┐         ┌──────────┐                   │
│  │ Server 1 │   │Session │         │ Server 1 │  No session store! │
│  │ session  │───│ Store  │         │ validate │  Token carries     │
│  │ lookup   │   │(Redis) │         │ JWT sig  │  all identity info. │
│  └──────────┘   └────────┘         └──────────┘                   │
│  ┌──────────┐        │             ┌──────────┐                   │
│  │ Server 2 │────────┘             │ Server 2 │  Same public key  │
│  │ session  │                      │ validate │  validates token  │
│  │ lookup   │                      │ JWT sig  │  independently.   │
│  └──────────┘                      └──────────┘                   │
│                                                                      │
│  Session-based: Every request       JWT-based: Any server can       │
│  looks up session in shared store.  validate independently.         │
│  Session store = shared state       Public key cached from S3.      │
│  = scaling bottleneck.              No shared state needed.         │
│                                                                      │
│  CXP: 8 ECS tasks, no shared session store.                       │
│  ALB round-robins freely (Topic 19 — stateless).                   │
│  JWT is WHY we can be stateless.                                   │
│  Public keys cached in each task's JVM — no Redis lookup per auth. │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Auth Across CXP

| Aspect | Consumer (User-Facing) | Service-to-Service |
|--------|----------------------|-------------------|
| **Authentication** | JWT from accounts.nike.com (OAuth 2.0) | OSCAR JWT (Nike internal OAuth) |
| **Issuer** | `oauth2acc` | OSCAR issuer |
| **Identity** | PRN (Nike Member UUID) | Service name (cxp-event-reg) |
| **Validation** | AAAConfig.java (RSA public keys from S3) | @JwtScope (OSCAR JWKS) |
| **Authorization** | @AccessValidator (has valid token?) + PRN extraction | @JwtScope (has correct scope?) |
| **Public access** | @Unsecured (no token required) | N/A (services always need tokens) |
| **Annotation** | `@AccessValidator` | `@JwtScope(SCOPE_CONSTANT)` |
| **Revocation** | Token expires (exp claim) | Token expires (short-lived) |
| **Scaling impact** | Stateless — any task validates | Stateless — any task validates |

---

## Common Interview Follow-ups

### Q: "How do you handle token expiry and refresh?"

> "Consumer JWTs from accounts.nike.com have an expiry claim (`exp`). When the token expires, the frontend redirects to accounts.nike.com for re-authentication — no refresh token flow in our current implementation. For OSCAR service tokens, we refresh on a schedule: `@Scheduled(fixedRate = 3540000)` — every 59 minutes (before the 60-minute expiry). The token is cached in-memory and reused across requests until the next refresh. This avoids requesting a new OSCAR token per API call."

### Q: "What's the difference between OAuth 2.0 and OpenID Connect?"

> "OAuth 2.0 is for AUTHORIZATION — it grants access tokens for API calls. OpenID Connect (OIDC) is a layer on TOP of OAuth 2.0 for AUTHENTICATION — it adds an ID token that proves who the user is. Our consumer flow uses both: OAuth 2.0 for the access token (authorization to call CXP APIs) and the JWT payload carries identity information (authentication — PRN user ID). OSCAR tokens are pure OAuth 2.0 — service identity, not user identity."

### Q: "Can you revoke a JWT before it expires?"

> "Not without additional infrastructure. JWTs are self-contained — once issued, they're valid until `exp`. To revoke early, you'd need: (1) a token blacklist (check Redis on every request — adds latency, defeats statelessness), or (2) very short token lifetimes (5 minutes) with frequent refresh — reduces the window of misuse. Our approach: consumer tokens have reasonable expiry (~1 hour). If an account is compromised, accounts.nike.com can stop issuing new tokens, and existing tokens expire within the hour. For OSCAR service tokens, 60-minute expiry limits the blast radius."

### Q: "Why validate JWT in each service instead of at the API gateway?"

> "Three reasons: (1) Different endpoints need different auth — `@Unsecured` for public event pages, `@AccessValidator` for consumer registration, `@JwtScope` for service purge calls. A gateway would need a complex routing table mapping paths to auth policies. (2) The service needs the PRN (user ID) from the JWT to know WHICH user is registering — the gateway would need to extract it and forward it anyway. (3) Auth logic is in a shared Nike library (AAAConfig) — no code duplication. The service validates AND uses the token in one place, avoiding a two-hop auth flow."

---
---

# Topic 32: SSL/TLS & HTTPS

> TLS encrypts data in transit using asymmetric handshake then symmetric session encryption. mTLS adds client certificates for service-to-service auth.

> **Interview Tip:** Explain the handshake — "Client sends hello, server returns certificate + public key, client encrypts session key, both use symmetric AES for fast encrypted communication."

---

## What Is TLS?

**Transport Layer Security** encrypts data between client and server so no one in the middle can read or tamper with it. HTTPS = HTTP + TLS.

```
┌──────────────────────────────────────────────────────────────────────┐
│  SSL/TLS & HTTPS                                                     │
│                                                                      │
│  Encrypts data in transit between client and server —               │
│  prevents eavesdropping and tampering.                              │
│  HTTPS = HTTP + TLS (TLS is the modern successor to SSL)            │
│                                                                      │
│  TLS HANDSHAKE (Simplified):                                        │
│                                                                      │
│  Client                                                Server       │
│    │  1. ClientHello (supported ciphers, random)    │              │
│    │────────────────────────────────────────────────▶│              │
│    │                                                 │              │
│    │  2. ServerHello + Certificate + Public Key      │              │
│    │◀────────────────────────────────────────────────│              │
│    │                                                 │              │
│    │  3. Key Exchange (encrypted with server's       │              │
│    │     public key)                                 │              │
│    │────────────────────────────────────────────────▶│              │
│    │                                                 │              │
│    │  4. Encrypted session using shared symmetric key│              │
│    │◀───────────────────────────────────────────────▶│              │
│    │                                                 │              │
│    Session encrypted with fast symmetric encryption (AES)           │
│                                                                      │
│  Step 1-3: ASYMMETRIC (slow, RSA/ECDSA — used only for handshake) │
│  Step 4:   SYMMETRIC (fast, AES — used for all data transfer)      │
│                                                                      │
│  WHY TWO TYPES:                                                     │
│  Asymmetric: Secure key exchange (no shared secret needed upfront) │
│  Symmetric: Fast bulk encryption (100-1000x faster than RSA)       │
│  TLS uses asymmetric to AGREE on a symmetric key, then switches.  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Certificate Chain & mTLS

```
┌──────────────────────────────────────────────────────────────────────┐
│                                                                      │
│  CERTIFICATE CHAIN                    mTLS (Mutual TLS)             │
│  ┌─────────┐                                                        │
│  │ Root CA │ (trusted by browsers)    Both client AND server         │
│  └────┬────┘                          present certificates.          │
│       │                                                              │
│  ┌────┴──────────┐                    Standard TLS:                 │
│  │Intermediate CA│                    Client verifies server cert ✓ │
│  └────┬──────────┘                    Server doesn't verify client  │
│       │                                                              │
│  ┌────┴──────────────┐                mTLS:                         │
│  │ Your Certificate  │                Client verifies server cert ✓ │
│  │ (nike.com)        │                Server verifies client cert ✓ │
│  └───────────────────┘                                              │
│                                       Used for:                     │
│  Browser trusts Root CA →             Service-to-service auth       │
│  Root CA signed Intermediate →        (service mesh — Istio/Linkerd)│
│  Intermediate signed your cert →      Zero-trust networks           │
│  Therefore browser trusts nike.com                                  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## TLS In My CXP Projects — Real Examples

### The CXP TLS Architecture — Encryption at Every Hop

Our platform has **three TLS termination points** — traffic is encrypted at every stage from the user's browser to the container.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — TLS ENCRYPTION MAP                                       │
│                                                                          │
│  User's Browser                                                         │
│       │                                                                  │
│       │ TLS 1.3 (HTTPS)                                                │
│       │ Certificate: *.nike.com (Akamai-managed)                        │
│       ▼                                                                  │
│  ┌──────────────────┐  TLS TERMINATION POINT 1: Akamai Edge           │
│  │  Akamai CDN PoP  │  Decrypts HTTPS from browser.                    │
│  │  (Tokyo, London,  │  Inspects HTTP for caching/WAF.                  │
│  │   NYC, etc.)      │  Re-encrypts for origin.                         │
│  └────────┬─────────┘                                                    │
│           │                                                              │
│           │ TLS (HTTPS) — re-encrypted for transit to AWS               │
│           │ Certificate: *.origins.nike (internal cert)                   │
│           ▼                                                              │
│  ┌──────────────────┐  TLS TERMINATION POINT 2: ALB                    │
│  │  AWS ALB          │  Decrypts HTTPS from Akamai.                     │
│  │  (cxp-alb)        │  Certificate: ACM-managed for CXP domains.      │
│  │  HTTPS:443        │  Routes to target group by path.                 │
│  └────────┬─────────┘                                                    │
│           │                                                              │
│           │ HTTP (plain) — internal VPC, no encryption                   │
│           │ Port: 8080                                                   │
│           ▼                                                              │
│  ┌──────────────────┐  NO TLS: Container receives plain HTTP           │
│  │  ECS Task         │  Internal VPC traffic — encrypted by VPC         │
│  │  (cxp-events,     │  network isolation, not TLS.                     │
│  │   cxp-reg, etc.)  │  Port: 8080 (httpTrafficPort)                   │
│  │  server.port=8080 │                                                   │
│  └──────────────────┘                                                    │
│                                                                          │
│  TWO-HOP TLS:                                                           │
│  Browser ──TLS──▶ Akamai ──TLS──▶ ALB ──HTTP──▶ Container             │
│                                                                          │
│  WHY NOT TLS ALL THE WAY TO CONTAINER?                                  │
│  - ALB → Container is within the SAME VPC (private network)            │
│  - Adding TLS to container = certificate management per container      │
│  - Performance cost: TLS handshake per container connection            │
│  - Security: VPC security groups restrict traffic to ALB only          │
│  - Industry standard: terminate TLS at the load balancer               │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: ACM Certificates — Managed TLS for CXP Domains

**Where:** `cxp-infrastructure` → Terraform ACM modules
**Pattern:** AWS Certificate Manager provisions and auto-renews TLS certificates

```
┌──────────────────────────────────────────────────────────────────────┐
│  ACM Certificate Management                                          │
│                                                                      │
│  CXP uses TWO sets of certificates:                                 │
│                                                                      │
│  1. AWS (Main) — for ALB HTTPS listeners:                          │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Terraform module: terraform/aws/modules/acm/               │    │
│  │                                                              │    │
│  │  Certificates for:                                           │    │
│  │  - aws-us-east-1.v1.events.community.global.prod.origins.nike│   │
│  │  - aws-us-east-1.v1.event-registrations.community...        │    │
│  │  - (one per service per region)                              │    │
│  │                                                              │    │
│  │  Validation: DNS validation via Route53 CNAME                │    │
│  │  Renewal: Automatic (ACM handles it)                        │    │
│  │  Used by: ALB HTTPS:443 listener                            │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  2. AWS Passplay (NPE) — for NPE ingress:                         │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Terraform module: terraform/awsPassplay/modules/acm/       │    │
│  │                                                              │    │
│  │  Certificates for NPE custom domains.                       │    │
│  │  Validation: Route53 DNS validation records.                │    │
│  │  Used by: NPE ingress TLS termination.                     │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  AUTO-RENEWAL:                                                      │
│  ACM certificates auto-renew before expiry.                        │
│  No manual cert rotation. No downtime for renewal.                 │
│  Contrast: manual cert rotation requires deployment + coordination.│
└──────────────────────────────────────────────────────────────────────┘
```

**From the Terraform — ACM module and DNS validation:**

```hcl
// terraform/aws/modules/acm/ — certificate with DNS validation
// ACM provisions TLS cert for CXP domains
// Route53 creates validation CNAME records automatically
// Certificate attached to ALB HTTPS listener

// terraform/awsPassplay/modules/acm/ — NPE certificates
// Same pattern for NPE-hosted services
// Certificate authority: private (Nike internal CA)
```

**From the NPE config — private certificate authority:**

```yaml
# NPEService/prod/711620779129_npe_service_us_west.yaml
ingress:
  private:
    certificateAuthority: private    # Nike internal CA, not public
  public:
    enabled: true
    dns:
      customDomains:
        - any.v1.events.community.global.prod.origins.nike
```

---

### Example 2: Akamai Edge TLS — First Termination Point

**Component:** Akamai CDN (250+ PoPs)
**Pattern:** TLS terminates at the edge, closest to the user

```
┌──────────────────────────────────────────────────────────────────────┐
│  Akamai Edge TLS                                                     │
│                                                                      │
│  User in Tokyo:                                                     │
│  Browser ──TLS 1.3──▶ Tokyo Akamai PoP                             │
│                        │                                             │
│  TLS handshake: ~10ms (Tokyo PoP is local)                          │
│  vs ~200ms if handshake went to us-east-1 origin                    │
│                                                                      │
│  WHAT AKAMAI DOES WITH DECRYPTED TRAFFIC:                          │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  1. Check Edge-Cache-Tag → CACHE HIT? Return cached (95%)   │    │
│  │  2. WAF inspection (DDoS rules, bot detection)              │    │
│  │  3. CACHE MISS → re-encrypt → forward to ALB origin         │    │
│  │  4. Set Edge-Control headers on response                    │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  Akamai → ALB connection:                                          │
│  Re-encrypted with internal certificate for transit.                │
│  The ALB has its own ACM certificate to terminate this second hop. │
│                                                                      │
│  PERFORMANCE BENEFIT:                                               │
│  TLS handshake at edge = ~10ms (local PoP)                         │
│  TLS handshake at origin = ~200ms (cross-Pacific round-trip)       │
│  Edge TLS saves ~190ms on EVERY new connection.                    │
│                                                                      │
│  This is why Akamai (edge TLS) is the first line, not ALB.        │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — Edge-Control headers (set AFTER TLS decryption):**

```java
// AkamaiCacheHeaderBuilder.java — only possible because Akamai decrypted HTTPS
httpHeaders.add(HTTP_HEADER_EDGE_CONTROL,
    "!no-store,downstream-ttl=5m,!bypass-cache,cache-maxage=" + cacheTimeout);
// Akamai inspects these headers AFTER TLS termination
// Browser never sees Edge-Control (stripped before response)
```

---

### Example 3: ALB HTTPS:443 — Second Termination Point

**Component:** AWS ALB (`cxp-alb`)
**Pattern:** ALB terminates HTTPS, forwards HTTP:8080 to containers

```
┌──────────────────────────────────────────────────────────────────────┐
│  ALB TLS Termination                                                 │
│                                                                      │
│  ALB receives HTTPS from Akamai (or direct if CDN bypass):         │
│                                                                      │
│  HTTPS:443 (encrypted)    HTTP:8080 (plain, internal VPC)          │
│  Akamai ──TLS──▶ ALB ──────plain HTTP──▶ ECS Task                  │
│                   │                       │                         │
│                   │ ACM Certificate       │ server.port=8080        │
│                   │ (auto-renewed)        │ No TLS config needed   │
│                   │                       │ in Spring Boot          │
│                                                                      │
│  ALB HTTPS LISTENER (from Terraform):                              │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  data "aws_alb_listener" "https" {                          │    │
│  │    load_balancer_arn = data.aws_lb.selected.arn              │    │
│  │    port              = 443    ← HTTPS                       │    │
│  │  }                                                           │    │
│  │                                                              │    │
│  │  Target group health check: HTTP (not HTTPS) on port 8080   │    │
│  │  resource "aws_alb_target_group" "tg" {                     │    │
│  │    port     = 8080            ← plain HTTP to container     │    │
│  │    protocol = "HTTP"          ← not HTTPS                   │    │
│  │  }                                                           │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  SECURITY OF ALB → CONTAINER (plain HTTP):                         │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Q: "Isn't plain HTTP between ALB and container insecure?"  │    │
│  │                                                              │    │
│  │  A: Mitigated by:                                           │    │
│  │  1. VPC isolation — traffic never leaves the VPC            │    │
│  │  2. Security groups — only ALB can reach container port     │    │
│  │  3. Private subnets — containers not internet-accessible    │    │
│  │  4. AWS network encryption — VPC traffic encrypted at       │    │
│  │     the hardware/hypervisor level (transparent)             │    │
│  │                                                              │    │
│  │  For maximum security (compliance): add TLS to container    │    │
│  │  using self-signed cert or NPE's private CA.               │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 4: NPE Private Certificate Authority — Internal TLS

**Component:** NPE (Nike Platform Experience) / Kubernetes
**Pattern:** Private CA for internal service ingress

```
┌──────────────────────────────────────────────────────────────────────┐
│  NPE Private Certificate Authority                                   │
│                                                                      │
│  NPE services use a PRIVATE certificate authority (not public CA):  │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ingress:                                                   │    │
│  │    private:                                                 │    │
│  │      certificateAuthority: private    ← Nike internal CA   │    │
│  │    public:                                                  │    │
│  │      enabled: true                                          │    │
│  │      dns:                                                   │    │
│  │        customDomains:                                       │    │
│  │          - any.v1.events.community.global.prod.origins.nike │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  TWO INGRESS TYPES:                                                 │
│  ┌──────────────────────┬──────────────────────────────────────┐  │
│  │  Public Ingress       │  Private Ingress                     │  │
│  ├──────────────────────┼──────────────────────────────────────┤  │
│  │  Cert: Public CA      │  Cert: Nike private CA               │  │
│  │  (ACM / Let's Encrypt)│  (internal PKI)                      │  │
│  │  Used by: browsers,   │  Used by: internal Nike services     │  │
│  │  Akamai CDN           │  (service-to-service calls)          │  │
│  │  Trusted by: everyone │  Trusted by: Nike services only      │  │
│  │  Validates: nike.com  │  Validates: *.internal.nike           │  │
│  └──────────────────────┴──────────────────────────────────────┘  │
│                                                                      │
│  WHY PRIVATE CA:                                                    │
│  - Internal services don't need public trust (no browser access)   │
│  - Private CA avoids public CA costs and rate limits               │
│  - Certificates can have internal domain names                     │
│  - Nike controls the entire certificate lifecycle                  │
│  - Enables mTLS between internal services (if configured)          │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 5: JWT Public Keys over HTTPS — TLS Protecting Auth

**Component:** `AAAConfig.java` fetches RSA public keys from S3 over HTTPS
**Pattern:** TLS protects the authentication infrastructure itself

```
┌──────────────────────────────────────────────────────────────────────┐
│  TLS Protecting the Auth System                                      │
│                                                                      │
│  JWT validation requires public keys to verify signatures.          │
│  These keys are fetched over HTTPS from S3:                         │
│                                                                      │
│  aaa.keys.url = https://s3.amazonaws.com/publickeys.foundation-     │
│                 prod.nikecloud.com/keys/                             │
│                                                                      │
│  WHY HTTPS MATTERS HERE:                                            │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  If this URL were HTTP (not HTTPS):                         │    │
│  │  Attacker intercepts → serves FAKE public keys              │    │
│  │  → Service validates attacker's JWT with fake keys          │    │
│  │  → Attacker gets authenticated as ANY user                  │    │
│  │  → Complete auth bypass!                                     │    │
│  │                                                              │    │
│  │  With HTTPS:                                                │    │
│  │  TLS ensures public keys come from the REAL S3 bucket.      │    │
│  │  Man-in-the-middle can't substitute fake keys.              │    │
│  │  JWT signature validation is trustworthy.                   │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  Similarly, OSCAR token validation uses HTTPS JWKS endpoints:      │
│  Okta: https://nike.okta.com/oauth2/default/v1/keys                │
│  → Fetched over TLS to prevent key substitution attacks.           │
│                                                                      │
│  ELASTICSEARCH uses AWS SigV4 over HTTPS:                          │
│  ES_ENDPOINT = https://search-dev-pg-events-....es.amazonaws.com   │
│  → TLS encrypts search queries containing user data.               │
│  → SigV4 authenticates the service to AWS ES.                      │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```properties
# application.properties — all key URLs use HTTPS
aaa.keys.url=https://s3.amazonaws.com/publickeys.foundation-prod.nikecloud.com/keys/
```

```java
// ExperienceViewsNikeAppConfiguration.java — ES over HTTPS + SigV4
AWS4Signer signer = new AWS4Signer();
signer.setServiceName("es");
String endpoint = System.getenv("ES_ENDPOINT");
// endpoint = "https://search-dev-pg-events-....es.amazonaws.com"
// HTTPS + AWS SigV4 = encrypted AND authenticated
```

---

### Example 6: Secrets Manager & KMS — Encryption at Rest

TLS covers encryption **in transit**. CXP also encrypts **at rest**:

```
┌──────────────────────────────────────────────────────────────────────┐
│  ENCRYPTION AT REST — Complementing TLS (in transit)                 │
│                                                                      │
│  ┌──────────────────────┬──────────────────────────────────────┐   │
│  │  Data Store           │  Encryption at Rest                  │   │
│  ├──────────────────────┼──────────────────────────────────────┤   │
│  │  Secrets Manager      │  KMS customer-managed key            │   │
│  │  (Eventtia creds,     │  (cxp-infrastructure/terraform/aws/ │   │
│  │   OSCAR config)       │   modules/kms + secretManager)      │   │
│  ├──────────────────────┼──────────────────────────────────────┤   │
│  │  DynamoDB             │  AWS-managed encryption (default)    │   │
│  │  (unprocessed queue)  │  AES-256 at rest                    │   │
│  ├──────────────────────┼──────────────────────────────────────┤   │
│  │  S3 (Partner Hub)     │  Server-side encryption (SSE-S3)    │   │
│  │                       │  AES-256 at rest                    │   │
│  ├──────────────────────┼──────────────────────────────────────┤   │
│  │  Redis (ElastiCache)  │  Encryption at rest (optional)      │   │
│  │                       │  + in-transit encryption (TLS)      │   │
│  ├──────────────────────┼──────────────────────────────────────┤   │
│  │  Elasticsearch        │  AWS-managed encryption at rest     │   │
│  └──────────────────────┴──────────────────────────────────────┘   │
│                                                                      │
│  FROM TERRAFORM — KMS keys for Secrets Manager:                     │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  modules/kms — Customer-managed KMS key for CXP secrets     │    │
│  │  modules/secretManager — Secrets encrypted with KMS key     │    │
│  │  Stores: Eventtia API credentials, feature flags, config    │    │
│  │  Cross-account access: IAM roles for other AWS accounts     │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  COMPLETE ENCRYPTION PICTURE:                                       │
│  In transit: TLS (Akamai → ALB → HTTPS for external APIs)         │
│  At rest: KMS/AES-256 (DynamoDB, S3, Secrets Manager, ES)         │
│  Both: Redis ElastiCache supports TLS in-transit + AES at-rest    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: TLS Across CXP

| Hop | From → To | TLS? | Certificate | Managed By |
|-----|----------|------|-------------|-----------|
| **Hop 1** | Browser → Akamai PoP | TLS 1.3 | *.nike.com (public CA) | Akamai |
| **Hop 2** | Akamai → ALB | TLS | *.origins.nike (internal) | ACM + Akamai |
| **Hop 3** | ALB → ECS Container | Plain HTTP | N/A (VPC isolation) | Security groups |
| **External** | cxp-reg → Eventtia API | TLS | dashboard.eventtia.com (public CA) | Eventtia |
| **External** | cxp-reg → Pairwise API | TLS | Nike internal (OSCAR) | Nike platform |
| **External** | Rise GTS → NCP API | TLS | api.nike.com (public CA) | Nike platform |
| **Internal** | Service → S3/DynamoDB | TLS | AWS SDK default | AWS |
| **Internal** | Service → Redis | Configurable | ElastiCache in-transit TLS | AWS |
| **Internal** | Service → Elasticsearch | TLS + SigV4 | AWS ES endpoint cert | AWS |
| **Auth keys** | Service → S3 public keys | TLS (critical!) | s3.amazonaws.com (public CA) | AWS |
| **NPE** | Ingress → Service | Private CA TLS | Nike private PKI | Nike platform |

---

## Common Interview Follow-ups

### Q: "Why terminate TLS at the ALB instead of at the container?"

> "Three reasons: (1) **Operational simplicity** — ACM auto-renews certificates for the ALB. Per-container certs require certificate distribution, rotation, and monitoring for every ECS task. With 8 tasks × 4 services = 32 certificates to manage. (2) **Performance** — TLS handshake is CPU-intensive. ALB is purpose-built hardware for TLS termination. Our Spring Boot containers focus on business logic, not crypto. (3) **Security is maintained** — ALB to container traffic stays within the VPC, restricted by security groups. Only the ALB can reach port 8080. AWS VPC provides hardware-level network encryption. For compliance-heavy environments (PCI-DSS, HIPAA), we'd add container-level TLS using NPE's private CA."

### Q: "How does your two-hop TLS architecture affect latency?"

> "Minimal impact. Akamai terminates TLS at the nearest PoP (10ms handshake vs 200ms to origin). The Akamai→ALB TLS adds ~5ms (within AWS network, pre-established connections with keep-alive). Total TLS overhead: ~15ms on the first request. Subsequent requests reuse the TLS session (session resumption) — near-zero overhead. Without Akamai edge TLS, every new user connection would TLS-handshake all the way to ALB in us-east-1 — adding 200ms+ for users in Asia/Europe."

### Q: "When would you use mTLS?"

> "When you need to verify BOTH sides of a connection — not just 'is this the real server?' but also 'is this an authorized client?' In our platform, service-to-service auth is handled by OSCAR JWT tokens rather than mTLS. Both achieve the same goal (service identity verification), but JWT is easier to implement (no certificate per service, no client cert rotation). I'd use mTLS if we adopted a service mesh (Istio/Linkerd) where mTLS is automatic — the sidecar proxy handles certificate issuance, rotation, and verification transparently. mTLS + service mesh = zero-trust networking without application code changes."

### Q: "What about encryption at rest vs in transit?"

> "We do both. In transit: TLS at every external hop (browser→Akamai→ALB, service→Eventtia, service→AWS APIs). At rest: KMS-encrypted Secrets Manager for credentials, AES-256 for DynamoDB and S3, AWS-managed encryption for Elasticsearch. The KMS key in our Terraform (`modules/kms`) is customer-managed — we control the key policy, including cross-account access for other Nike AWS accounts. No credential or user data sits unencrypted, whether moving or stored."

---
---

# Topic 33: Encryption Types

> Symmetric uses same key (fast, for bulk data); asymmetric uses public/private key pair (no key exchange problem). Encrypt at rest (storage) and in transit (network).

> **Interview Tip:** Connect to real systems — "I'd use TLS for in-transit encryption, S3 SSE-KMS for at-rest, with keys managed in AWS KMS for rotation and audit."

---

## The Two Encryption Types

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        ENCRYPTION TYPES                                   │
│                                                                          │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐    │
│  │   SYMMETRIC ENCRYPTION       │  │   ASYMMETRIC ENCRYPTION      │    │
│  │   Same key to encrypt        │  │   Public key encrypts,       │    │
│  │   and decrypt.               │  │   Private key decrypts.      │    │
│  │                              │  │                               │    │
│  │  Plain ──▶ Encrypt ──▶ Cipher│  │  Plain ──▶ Encrypt ──▶ Cipher│    │
│  │            Key: ABC          │  │         PUBLIC key            │    │
│  │                              │  │                               │    │
│  │  Plain ◀── Decrypt ◀── Cipher│  │  Plain ◀── Decrypt ◀── Cipher│    │
│  │            Key: ABC          │  │         PRIVATE key           │    │
│  │                              │  │                               │    │
│  │  [+] Fast, efficient         │  │  [+] No key exchange needed  │    │
│  │      for bulk data           │  │      (public key is public!) │    │
│  │  [-] Key distribution        │  │  [-] Slow (100-1000x slower  │    │
│  │      problem: how to share   │  │      than symmetric)         │    │
│  │      the secret key safely?  │  │                               │    │
│  │                              │  │  Algorithms: RSA, ECDSA,     │    │
│  │  Algorithms: AES-256,        │  │  Ed25519                     │    │
│  │  ChaCha20                    │  │                               │    │
│  └──────────────────────────────┘  └──────────────────────────────┘    │
│                                                                          │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐    │
│  │   ENCRYPTION AT REST         │  │   ENCRYPTION IN TRANSIT       │    │
│  │                              │  │                               │    │
│  │  Data encrypted when stored. │  │  Data encrypted during        │    │
│  │                              │  │  transmission.                │    │
│  │  - Database encryption (TDE) │  │  - TLS/HTTPS for web traffic │    │
│  │  - Disk encryption (LUKS)    │  │  - VPN tunnels               │    │
│  │  - S3 SSE (Server-Side)      │  │  - SSH for remote access     │    │
│  │                              │  │                               │    │
│  │  Protects against:           │  │  Protects against:           │    │
│  │  Physical theft,             │  │  MITM attacks,               │    │
│  │  unauthorized access         │  │  eavesdropping               │    │
│  └──────────────────────────────┘  └──────────────────────────────┘    │
│                                                                          │
│  REAL SYSTEMS USE BOTH TOGETHER:                                        │
│  TLS handshake: ASYMMETRIC (RSA key exchange — slow, once)             │
│  TLS session:   SYMMETRIC (AES data encryption — fast, continuous)     │
│  Storage:       SYMMETRIC (AES-256 at rest — fast for bulk data)       │
│  Key management: ASYMMETRIC (KMS envelope encryption)                  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## How Symmetric + Asymmetric Work Together

```
┌──────────────────────────────────────────────────────────────────────┐
│  TLS = Asymmetric (handshake) + Symmetric (session)                  │
│                                                                      │
│  Client                                            Server           │
│    │                                                 │              │
│    │  1. ASYMMETRIC: Exchange a symmetric key safely │              │
│    │     Client encrypts session key with server's   │              │
│    │     PUBLIC key. Only server can decrypt with     │              │
│    │     PRIVATE key.                                 │              │
│    │──────── {session_key}encrypted_with_RSA ────────▶│              │
│    │                                                 │              │
│    │  2. SYMMETRIC: Use the shared session key for data│             │
│    │     AES-256 encrypts all traffic. Fast.          │              │
│    │◀════════════ AES encrypted data ════════════════▶│              │
│    │                                                 │              │
│                                                                      │
│  WHY NOT ASYMMETRIC FOR EVERYTHING?                                 │
│  RSA encrypt 1 MB: ~5000ms                                          │
│  AES encrypt 1 MB: ~0.5ms                                           │
│  RSA is 10,000× SLOWER. Unusable for bulk data.                    │
│  Use RSA to safely share an AES key, then AES for the data.       │
│                                                                      │
│  KMS = Asymmetric (envelope) + Symmetric (data)                     │
│                                                                      │
│  KMS Master Key (asymmetric, never leaves KMS)                     │
│    │                                                                │
│    │ encrypts → Data Encryption Key (DEK) — symmetric, AES-256    │
│    │                                                                │
│    DEK encrypts → Your actual data (S3 objects, DynamoDB items)    │
│                                                                      │
│  This is ENVELOPE ENCRYPTION: master key wraps data key,           │
│  data key encrypts the data. Master key never touches raw data.    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Encryption In My CXP Projects — Complete Map

### Every Data Store, Every Hop — Encrypted

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — COMPLETE ENCRYPTION MAP                                   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  ENCRYPTION IN TRANSIT (TLS/HTTPS — symmetric AES session)       │  │
│  │                                                                   │  │
│  │  Browser ──TLS 1.3──▶ Akamai ──TLS──▶ ALB ──HTTP──▶ Container │  │
│  │  Container ──TLS──▶ Eventtia API (dashboard.eventtia.com)       │  │
│  │  Container ──TLS──▶ S3 (aws-sdk HTTPS default)                  │  │
│  │  Container ──TLS──▶ DynamoDB (aws-sdk HTTPS default)            │  │
│  │  Container ──TLS──▶ SQS (aws-sdk HTTPS default)                 │  │
│  │  Container ──TLS+SigV4──▶ Elasticsearch (HTTPS + AWS auth)     │  │
│  │  Container ──TLS──▶ Redis ElastiCache (in-transit encryption)   │  │
│  │  Container ──TLS──▶ Akamai Purge API                            │  │
│  │  Container ──TLS──▶ Pairwise/LAMS/NCP APIs                     │  │
│  │  Container ──TLS──▶ S3 public keys (JWT validation keys)       │  │
│  │  Container ──TLS──▶ OSCAR token endpoint                        │  │
│  │                                                                   │  │
│  │  ALL external communication is TLS. The ONLY plain HTTP is      │  │
│  │  ALB → Container (within VPC, secured by security groups).      │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  ENCRYPTION AT REST (AES-256 — symmetric, managed by AWS)        │  │
│  │                                                                   │  │
│  │  ┌─────────────────────┬───────────────┬──────────────────────┐ │  │
│  │  │  Data Store          │  Encryption   │  Key Management      │ │  │
│  │  ├─────────────────────┼───────────────┼──────────────────────┤ │  │
│  │  │  Secrets Manager     │  AES-256      │  KMS customer-managed│ │  │
│  │  │  (Eventtia creds,    │  (envelope)   │  key (CXP-owned)     │ │  │
│  │  │   feature flags)     │               │  Cross-account policy│ │  │
│  │  ├─────────────────────┼───────────────┼──────────────────────┤ │  │
│  │  │  S3 (Partner Hub,    │  SSE-S3       │  AWS-managed keys    │ │  │
│  │  │   Bodega, feature    │  AES-256      │  (automatic rotation)│ │  │
│  │  │   flags)             │               │                      │ │  │
│  │  ├─────────────────────┼───────────────┼──────────────────────┤ │  │
│  │  │  DynamoDB            │  AES-256      │  AWS-managed keys    │ │  │
│  │  │  (unprocessed queue) │  (default on) │  (automatic)         │ │  │
│  │  ├─────────────────────┼───────────────┼──────────────────────┤ │  │
│  │  │  Elasticsearch       │  AES-256      │  AWS-managed keys    │ │  │
│  │  │  (event search index)│  (domain-level)│ (automatic)         │ │  │
│  │  ├─────────────────────┼───────────────┼──────────────────────┤ │  │
│  │  │  Redis ElastiCache   │  Optional     │  AWS-managed or KMS │ │  │
│  │  │  (cache data)        │  AES-256      │                      │ │  │
│  │  ├─────────────────────┼───────────────┼──────────────────────┤ │  │
│  │  │  SQS                 │  SSE-SQS      │  AWS-managed keys    │ │  │
│  │  │  (message payloads)  │  AES-256      │  (automatic)         │ │  │
│  │  └─────────────────────┴───────────────┴──────────────────────┘ │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  ASYMMETRIC ENCRYPTION IN CXP (key exchange + signatures)        │  │
│  │                                                                   │  │
│  │  JWT Signature:                                                  │  │
│  │  accounts.nike.com SIGNS tokens with RSA PRIVATE key.            │  │
│  │  AAAConfig.java VERIFIES with RSA PUBLIC key from S3.           │  │
│  │  → Asymmetric: only accounts.nike.com can sign,                 │  │
│  │    anyone with public key can verify (no secret sharing).       │  │
│  │                                                                   │  │
│  │  TLS Handshake:                                                  │  │
│  │  Akamai/ALB certificate contains RSA PUBLIC key.                │  │
│  │  Client encrypts session key with public key.                   │  │
│  │  Server decrypts with PRIVATE key.                              │  │
│  │  → Asymmetric key exchange, then symmetric AES session.         │  │
│  │                                                                   │  │
│  │  KMS Envelope Encryption:                                        │  │
│  │  KMS master key (RSA) wraps data encryption key (AES).          │  │
│  │  Data key encrypts Secrets Manager values.                      │  │
│  │  → Asymmetric wrapping of symmetric data key.                   │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: KMS + Secrets Manager — Envelope Encryption

**Where:** `cxp-infrastructure` → `terraform/aws/modules/kms` + `modules/secretManager`
**Pattern:** KMS master key (asymmetric) wraps data encryption key (symmetric AES)

```
┌──────────────────────────────────────────────────────────────────────┐
│  KMS Envelope Encryption for CXP Secrets                             │
│                                                                      │
│  WHAT WE STORE IN SECRETS MANAGER:                                  │
│  - Eventtia API credentials (username, password, API key)           │
│  - CXP feature flags (cacheBasedBotProtection, blocked events)     │
│  - NPE service configuration                                       │
│                                                                      │
│  HOW ENVELOPE ENCRYPTION WORKS:                                     │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │                                                              │    │
│  │  KMS Master Key (CMK — Customer Managed Key)                │    │
│  │  Lives INSIDE KMS hardware. NEVER leaves KMS.                │    │
│  │       │                                                      │    │
│  │       │ wraps (encrypts)                                     │    │
│  │       ▼                                                      │    │
│  │  Data Encryption Key (DEK) — AES-256                        │    │
│  │  Generated by KMS. Sent to Secrets Manager encrypted.        │    │
│  │       │                                                      │    │
│  │       │ encrypts                                             │    │
│  │       ▼                                                      │    │
│  │  Secret Value: { "eventtiaApiKey": "abc123..." }             │    │
│  │  Stored encrypted. Readable only by services with IAM        │    │
│  │  permission to call KMS Decrypt.                             │    │
│  │                                                              │    │
│  │  TO READ THE SECRET:                                         │    │
│  │  1. Service calls Secrets Manager: "Give me CXP-secrets"    │    │
│  │  2. Secrets Manager sends encrypted DEK to KMS              │    │
│  │  3. KMS decrypts DEK using master key (inside KMS hardware) │    │
│  │  4. Secrets Manager uses DEK to decrypt secret value        │    │
│  │  5. Returns plaintext to service                            │    │
│  │                                                              │    │
│  │  SECURITY:                                                   │    │
│  │  - Master key NEVER leaves KMS → can't be stolen            │    │
│  │  - DEK is always encrypted at rest → useless without KMS   │    │
│  │  - IAM controls WHO can call KMS Decrypt → audit trail      │    │
│  │  - Key rotation: KMS can rotate master key automatically    │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

**From the Terraform:**

```hcl
// terraform/aws/modules/kms/ — Customer Managed Key
// Encrypts Secrets Manager values for CXP
// Key policy: cross-account access for dev + prod AWS accounts
// Rotation: configurable (AWS auto-rotates annually)

// terraform/aws/modules/secretManager/ — Encrypted secrets
// Stores: Eventtia credentials, feature flags, config
// Encryption: KMS CMK from the kms module
// Access: IAM roles for ECS tasks (cxp-events, cxp-reg)
```

**From the application code — reading encrypted secrets:**

```java
// CXPCommonSecretService.java — reads decrypted secret at runtime
// AWS SDK handles KMS decryption transparently
CXPFeatureFlag cxpFeatureFlag = objectMapper.readValue(secret, CXPFeatureFlag.class);
setCacheBasedBotProtection(cxpFeatureFlag.isCacheBasedBotProtection());
// The "secret" string is already decrypted by AWS SDK + KMS
// App code never sees encryption details — fully transparent
```

**Interview answer:**
> "We use KMS envelope encryption for Secrets Manager. A KMS Customer Managed Key wraps a data encryption key (AES-256), which encrypts our Eventtia API credentials and feature flags. The master key never leaves KMS hardware — it's impossible to extract. Our Terraform provisions the KMS key with a cross-account policy so both dev and prod accounts can access it. Our application code doesn't handle encryption directly — the AWS SDK transparently calls KMS Decrypt when reading secrets. Key rotation is automatic."

---

### Example 2: JWT Signatures — Asymmetric Encryption for Authentication

**Where:** `AAAConfig.java` + S3 public keys
**Pattern:** RSA asymmetric — accounts.nike.com signs with private key, our services verify with public key

```
┌──────────────────────────────────────────────────────────────────────┐
│  JWT Signature — Asymmetric Encryption in Authentication             │
│                                                                      │
│  accounts.nike.com                     cxp-event-registration       │
│  (has PRIVATE key)                     (has PUBLIC key from S3)     │
│                                                                      │
│  1. SIGN: Create JWT payload, sign with RSA PRIVATE key:           │
│     header.payload → RSA_SIGN(private_key) → signature             │
│     JWT = header.payload.signature                                  │
│                                                                      │
│  2. SEND: JWT travels in Authorization header over TLS.             │
│                                                                      │
│  3. VERIFY: cxp-reg validates with RSA PUBLIC key:                  │
│     RSA_VERIFY(public_key, header.payload, signature) → true/false │
│                                                                      │
│  WHY ASYMMETRIC FOR JWT:                                            │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  - accounts.nike.com SIGNS tokens (needs private key).      │    │
│  │  - CXP services VERIFY tokens (need only public key).       │    │
│  │  - Public key is... public. Safe to store in S3.            │    │
│  │  - No secret sharing between accounts.nike.com and CXP.    │    │
│  │  - 100+ services can verify with the SAME public key.       │    │
│  │  - Private key stays with accounts.nike.com ONLY.           │    │
│  │                                                              │    │
│  │  If we used SYMMETRIC (HMAC-SHA256):                        │    │
│  │  - Both signer AND verifier need the SAME secret key.       │    │
│  │  - Secret key in 100+ services = 100 points of compromise. │    │
│  │  - One leaked service → all tokens can be forged.           │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  ALGORITHM CHOICE: RS256 (RSA + SHA-256)                           │
│  - RSA 2048-bit key pair                                           │
│  - SHA-256 hash of the payload                                     │
│  - Signature ~256 bytes                                            │
│  - Verification: ~0.1ms (public key operation — fast)              │
│  - Signing: ~1ms (private key operation — slower, done by auth svc)│
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 3: S3 Server-Side Encryption — Transparent AES-256

**Where:** Partner Hub S3 bucket, Bodega translations, feature flags
**Pattern:** SSE-S3 — AWS encrypts on write, decrypts on read (transparent)

```
┌──────────────────────────────────────────────────────────────────────┐
│  S3 Server-Side Encryption                                           │
│                                                                      │
│  WRITE: Rise GTS / Kafka S3 Sink writes webhook JSON to S3.       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  1. Object arrives at S3 API (over TLS — in-transit ✓)      │    │
│  │  2. S3 generates AES-256 data key (per object or per batch) │    │
│  │  3. S3 encrypts object with data key                        │    │
│  │  4. S3 stores encrypted object + encrypted data key          │    │
│  │  5. Original plaintext NEVER stored on disk                  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  READ: email-drop-recovery queries via Athena, or reprocess.py     │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  1. Read request arrives (IAM authorized)                    │    │
│  │  2. S3 decrypts data key                                    │    │
│  │  3. S3 decrypts object with data key                        │    │
│  │  4. Returns plaintext over TLS                              │    │
│  │  5. Application code sees PLAINTEXT — encryption invisible  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  THREE SSE OPTIONS:                                                 │
│  ┌───────────┬──────────────────────────────────────────────────┐ │
│  │  SSE-S3   │  AWS manages keys entirely. Simplest. Our S3.    │ │
│  │  SSE-KMS  │  KMS manages master key. Audit trail in CloudTrail│ │
│  │           │  Key rotation control. Our Secrets Manager uses.  │ │
│  │  SSE-C    │  Customer provides key per request. Maximum       │ │
│  │           │  control but complex. We don't use this.          │ │
│  └───────────┴──────────────────────────────────────────────────┘ │
│                                                                      │
│  OUR CXP:                                                           │
│  S3 buckets: SSE-S3 (simplest, automatic, sufficient for webhooks) │
│  Secrets Manager: SSE-KMS (customer-managed key for credentials)    │
│  DynamoDB: AWS-managed encryption (default, automatic)             │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 4: No Credentials in Code — Zero Plaintext Secrets

**Principle:** CXP stores **zero credentials** in source code or application properties.

```
┌──────────────────────────────────────────────────────────────────────┐
│  CREDENTIALS MANAGEMENT — No Plaintext Secrets in Code               │
│                                                                      │
│  ┌───────────────────────┬──────────────────────────────────────┐  │
│  │  Credential            │  How It's Managed                    │  │
│  ├───────────────────────┼──────────────────────────────────────┤  │
│  │  Eventtia API key      │  Secrets Manager (KMS encrypted)     │  │
│  │  OSCAR client config   │  Secrets Manager (KMS encrypted)     │  │
│  │  Feature flags         │  Secrets Manager (KMS encrypted)     │  │
│  │  AWS credentials       │  IAM roles (no keys — STS temp creds)│  │
│  │  Splunk JWT token      │  Environment variable (runtime only) │  │
│  │  Redis host/port       │  application-{env}.properties        │  │
│  │                        │  (not secret — just endpoint config) │  │
│  │  ES endpoint           │  CloudFormation parameter / env var  │  │
│  └───────────────────────┴──────────────────────────────────────┘  │
│                                                                      │
│  FROM email-drop-recovery README:                                   │
│  "No credentials stored in code — all tokens come from              │
│   environment variables or ~/.aws/credentials"                      │
│                                                                      │
│  PRINCIPLES:                                                        │
│  1. Secrets → Secrets Manager (encrypted at rest with KMS)         │
│  2. AWS access → IAM roles (no access keys in code)                │
│  3. Endpoints → application properties (not sensitive)             │
│  4. Runtime tokens → environment variables (not committed)         │
│  5. Git → never contains credentials (gitignore + scanning)        │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Encryption Types Across CXP

| Encryption Type | Algorithm | Where Used | Key Management |
|----------------|-----------|-----------|---------------|
| **Symmetric (AES-256)** | AES-256-GCM | TLS session data, S3 at-rest, DynamoDB at-rest, SQS at-rest | AWS-managed or KMS CMK |
| **Asymmetric (RSA)** | RSA-2048/RS256 | JWT signing (accounts.nike.com), TLS handshake (cert exchange), KMS envelope wrapping | Private key: accounts.nike.com / KMS HSM. Public key: S3 / ACM |
| **Asymmetric (ECDSA)** | ECDSA | TLS 1.3 key exchange (modern, faster than RSA) | Akamai / ACM certificates |
| **Envelope** | RSA wraps AES | Secrets Manager values | KMS CMK wraps DEK, DEK encrypts data |
| **At rest** | AES-256 | S3 (SSE-S3), DynamoDB (default), Elasticsearch, Redis (optional), Secrets Manager (SSE-KMS) | Automatic rotation (AWS-managed) or CMK (CXP-managed) |
| **In transit** | TLS 1.2/1.3 (RSA handshake + AES session) | Every external HTTP call, browser→Akamai, Akamai→ALB, service→AWS APIs | ACM certificates (auto-renew) |

---

## Common Interview Follow-ups

### Q: "When would you use SSE-KMS vs SSE-S3?"

> "SSE-S3 for general data (webhooks, translations, feature flag files) — simplest, no key management, automatic. SSE-KMS for sensitive data (credentials, PII, financial) — gives you CloudTrail audit logs of every key usage, key rotation control, and the ability to revoke access by disabling the key. Our Secrets Manager uses KMS because credential access must be auditable. Our Partner Hub S3 uses SSE-S3 because webhook payloads don't require per-access audit trails."

### Q: "Why RS256 (RSA) for JWT instead of HS256 (HMAC)?"

> "HS256 is symmetric — the SAME secret key signs AND verifies. If any of our 4+ services is compromised, the attacker can forge JWTs for any user. RS256 is asymmetric — accounts.nike.com holds the private key (signs), our services hold only the public key (verify). A compromised service can verify tokens but can't forge new ones. With 100+ Nike services verifying the same JWT, RSA's asymmetric model is essential — one leaked public key is harmless, one leaked symmetric key is catastrophic."

### Q: "How does key rotation work without downtime?"

> "Two mechanisms in our platform: (1) KMS automatic rotation creates a new key version annually. Old versions remain for decryption of old data. New encryptions use the new version. Zero downtime — old ciphertext still decryptable. (2) ACM certificate auto-renewal replaces certificates before expiry. ALB picks up the new cert automatically. No config change, no deployment. For JWT public keys, accounts.nike.com rotates keys by publishing new public keys to S3. Our AAAConfig caches keys with TTL — next refresh picks up new keys. Brief overlap period where both old and new keys are valid (key rollover)."

### Q: "What about encrypting data in the application layer?"

> "We don't do application-layer encryption — we rely on AWS-managed encryption at rest and TLS in transit. Application-layer encryption (encrypting before sending to S3, for example) adds a layer for compliance-heavy scenarios (PCI-DSS field-level encryption, client-side encryption for zero-knowledge). For CXP, the data sensitivity (event registrations, email addresses) is adequately protected by AWS-managed encryption. If we stored payment card data, I'd add application-layer encryption using AWS Encryption SDK with KMS CMK — encrypt the card number in the app before it ever reaches any storage layer."

---
---

# Topic 34: Observability (Logs, Metrics, Traces)

> Three pillars: Logs (discrete events), Metrics (numeric measurements over time), Traces (request flow across services). Combine for full visibility.

> **Interview Tip:** Show you understand the triad — "Metrics tell me something's wrong, traces show where in the call chain, logs explain why it happened."

---

## The Three Pillars

```
┌──────────────────────────────────────────────────────────────────────────┐
│                    OBSERVABILITY PILLARS                                   │
│                                                                          │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐        │
│  │     LOGS         │  │     METRICS      │  │     TRACES      │        │
│  │                  │  │                  │  │                  │        │
│  │  Discrete events │  │  Numeric         │  │  Request flow    │        │
│  │  with context.   │  │  measurements    │  │  across services.│        │
│  │                  │  │  over time.      │  │                  │        │
│  │  [2024-01-15     │  │  ┌────────┐     │  │  API GW ──▶     │        │
│  │   10:23:45]      │  │  │ cpu_use│     │  │  Order Svc ──▶  │        │
│  │  ERROR           │  │  │ over   │     │  │  Payment ──▶    │        │
│  │  PaymentService: │  │  │ time   │     │  │  (end-to-end)   │        │
│  │  Failed to       │  │  └────────┘     │  │                  │        │
│  │  process         │  │                  │  │                  │        │
│  │  order_id=12345  │  │  [+] Aggregated, │  │  [+] End-to-end  │        │
│  │                  │  │      cheap to    │  │      visibility  │        │
│  │  [+] Detailed    │  │      store       │  │  [-] Instrument- │        │
│  │      context,    │  │  [-] No context  │  │      ation       │        │
│  │      debugging   │  │      on WHY      │  │      overhead    │        │
│  │  [-] High volume,│  │                  │  │                  │        │
│  │      expensive   │  │  Prometheus,     │  │  Jaeger, Zipkin, │        │
│  │                  │  │  Datadog,        │  │  X-Ray           │        │
│  │  ELK, Splunk,    │  │  CloudWatch      │  │                  │        │
│  │  CloudWatch Logs │  │                  │  │                  │        │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘        │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  ALERTING                                                         │  │
│  │  Set thresholds on metrics → trigger alerts → notify team        │  │
│  │  PagerDuty, OpsGenie, Slack webhooks                             │  │
│  │  SLOs: 99.9% availability = 8.76h downtime/year                  │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  DASHBOARDS & VISUALIZATION                                       │  │
│  │  Combine metrics, logs, traces into unified view                 │  │
│  │  Grafana, Kibana, Datadog Dashboards                             │  │
│  │  RED metrics: Rate, Errors, Duration                             │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## How the Three Pillars Work Together

```
┌──────────────────────────────────────────────────────────────────────┐
│  INCIDENT INVESTIGATION FLOW                                         │
│                                                                      │
│  1. METRICS tell you SOMETHING is wrong:                            │
│     "Email drop rate jumped from 2% to 15% in the last hour."      │
│     Dashboard shows spike. Alert fires.                             │
│                                                                      │
│  2. TRACES show WHERE in the call chain:                            │
│     "Registration → Kafka → Rise GTS → NCP ← fails here"          │
│     Trace ID follows the request across 6 services.                 │
│     NCP stage shows RED (failures detected).                        │
│                                                                      │
│  3. LOGS explain WHY it happened:                                   │
│     "NCP assembly log: UserEmailNotAvailable, upmId=uuid-1234"     │
│     "MemberHub sync delay: email not found for new user."           │
│     Root cause identified.                                          │
│                                                                      │
│  METRICS → "something's wrong" (detect)                             │
│  TRACES  → "where in the pipeline" (locate)                        │
│  LOGS    → "why it happened" (diagnose)                             │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Observability In My CXP Projects — Real Examples

### The CXP Observability Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — OBSERVABILITY MAP                                         │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  PILLAR 1: LOGS (Splunk)                                          │  │
│  │                                                                   │  │
│  │  Spring Boot (Log4j2) → stdout → Docker → Kinesis → Splunk      │  │
│  │                                                                   │  │
│  │  Indexes:                                                        │  │
│  │  • dockerlogs*          — general container logs                  │  │
│  │  • dockerlogs-gold      — production email delivery logs          │  │
│  │  • app*                 — application service logs                │  │
│  │  • dockerlogs-hc / app-hc — NCP ingest logs                     │  │
│  │                                                                   │  │
│  │  Search: SPL queries (rex, spath, stats, dedup, join)           │  │
│  │  Retention: Hot → Warm → Cold → Frozen (SSD→HDD→S3)           │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  PILLAR 2: METRICS (Splunk + CloudWatch + Dashboard)              │  │
│  │                                                                   │  │
│  │  Splunk metrics:                                                 │  │
│  │  • Email drop rate (daily, 3-day moving average — Trend tab)    │  │
│  │  • NCP arrived vs dropped count                                  │  │
│  │  • CRS rendering success vs failure count                       │  │
│  │                                                                   │  │
│  │  CloudWatch metrics:                                             │  │
│  │  • ECS CPU/memory utilization → auto-scaling triggers           │  │
│  │  • ALB request count, latency, 5xx rate                         │  │
│  │  • DynamoDB consumed capacity (WCU/RCU)                         │  │
│  │  • SQS queue depth, age of oldest message                      │  │
│  │                                                                   │  │
│  │  Dashboard (email-drop-recovery):                                │  │
│  │  • Summary cards: Total, Sent, Dropped, Drop Rate              │  │
│  │  • Bar chart: daily drops with moving average line              │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  PILLAR 3: TRACES (Distributed Tracing via IDs)                   │  │
│  │                                                                   │  │
│  │  Trace ID: follows a registration through 6 services:           │  │
│  │  cxp-reg → Eventtia → Kafka → Rise GTS → NCP → CRS            │  │
│  │                                                                   │  │
│  │  Implementation: Not Jaeger/Zipkin, but manual trace correlation │  │
│  │  using Splunk field extraction across indexes:                   │  │
│  │  • traceId field in NCP logs                                    │  │
│  │  • eventId + upmId correlation across Athena + Splunk           │  │
│  │  • Investigation tab: 5 parallel queries correlate by event ID  │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  ALERTING                                                         │  │
│  │  • PagerDuty: service escalation policy (on-call rotation)      │  │
│  │  • Route53 health checks: region-level alerts (auto-failover)   │  │
│  │  • CloudWatch Alarms: ECS CPU > 80%, ALB 5xx > threshold       │  │
│  │  • Splunk saved searches: drop rate exceeds threshold           │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: LOGS — Splunk as the Central Log Platform

**Services:** All CXP services → Splunk (centralized)
**Pipeline:** Spring Boot Log4j2 → stdout → Docker → Kinesis Firehose → Splunk

```
┌──────────────────────────────────────────────────────────────────────┐
│  Log Pipeline — From Application to Splunk                           │
│                                                                      │
│  ┌────────────┐   ┌────────┐   ┌──────────┐   ┌──────────┐       │
│  │Spring Boot │──▶│ Docker │──▶│ Kinesis  │──▶│ Splunk   │       │
│  │Log4j2      │   │ stdout │   │ Firehose │   │ Indexer  │       │
│  │log.info()  │   │        │   │          │   │          │       │
│  └────────────┘   └────────┘   └──────────┘   └──────────┘       │
│                                                                      │
│  LOG LEVELS USED IN CXP:                                            │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  log.info():  Business events                               │    │
│  │  "Returning cached response for idempotencyKey {}"          │    │
│  │  "Seats API Cache Purging success for eventId={}"           │    │
│  │  "UPM ID --> uuid-1234"                                     │    │
│  │                                                              │    │
│  │  log.warn():  Degraded but functioning                      │    │
│  │  "Capacity limit reached, purging cache, eventId={}"        │    │
│  │  "Retrying pairwise API, attempt={}/{}"                     │    │
│  │                                                              │    │
│  │  log.error(): Failures needing attention                    │    │
│  │  "Redis exception while getting :: idempotencyKey :: {}"    │    │
│  │  "Error calling eventtiaEventsLandingPageDetailsApi"        │    │
│  │  "Akamai Purge failed, eventId={}, tag={}"                  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  STRUCTURED LOG FIELDS (extracted by Splunk):                       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  NCP assembly logs (the core of email-drop investigation):  │    │
│  │  • upmId = user identity (extracted via rex)                │    │
│  │  • eventType = registration type                            │    │
│  │  • errorType = "UserEmailNotAvailable"                      │    │
│  │  • marketplace = US, ASTLA, PH, MX                          │    │
│  │  • commId, threadId, notificationId, ruleId, traceId       │    │
│  │                                                              │    │
│  │  CRS rendering logs:                                        │    │
│  │  • line.destination = email address                         │    │
│  │  • line.upmid = user identity                               │    │
│  │  • line.notification_class = event_confirmation             │    │
│  │  • line.error.variableNames = missing template variables    │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — structured logging:**

```java
// EventRegistrationService.java — business event logging
log.info("Returning cached response for idempotencyKey {}", idempotencyKey);
log.info("Duplicate request for idempotencyKey {}", idempotencyKey);
log.warn("Capacity limit reached (errorCode={}), purging cache, eventId={}",
    specificErrorCode, eventId);
log.error("Redis exception while getting :: idempotencyKey :: " + idempotencyKey, e);
```

```python
# queries.py — Splunk queries that SEARCH these logs
"dropped_emails": f'''search index=dockerlogs* sourcetype=log4j
    "UserEmailNotAvailable" {time_clause}
    | rex field=_raw "upmId=(?<upmId>[^,\\s]+)"
    | rex field=_raw "marketplace=(?<marketplace>[^,\\s]+)"
    | dedup upmId, eventType
    | table _time, upmId, marketplace, eventType, emailType ...'''
```

---

### Example 2: METRICS — Splunk Aggregations + CloudWatch

**Service:** `cxp-email-drop-recovery` Trend tab + CloudWatch
**Pattern:** Splunk `stats count` as metrics, CloudWatch for infrastructure

```
┌──────────────────────────────────────────────────────────────────────┐
│  Metrics in CXP — Two Sources                                        │
│                                                                      │
│  SOURCE 1: Splunk (application/business metrics)                    │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Email Drop Rate (RED metric: Error rate):                  │    │
│  │  Total events processed: stats count as arrived              │    │
│  │  Successfully sent:      stats count as sent                 │    │
│  │  Dropped (no email):     stats count as dropped              │    │
│  │  Drop Rate = dropped / arrived × 100%                       │    │
│  │                                                              │    │
│  │  Trend Tab: Daily drop counts over 7/14/30 days             │    │
│  │  Bar chart + 3-day moving average line.                     │    │
│  │  "Sudden spike = popular event launched or new issue."      │    │
│  │  "Gradual increase = systemic problem worsening."           │    │
│  │  "Downward trend = fixes are working."                      │    │
│  │                                                              │    │
│  │  Pipeline Health (per-stage counts — Investigate tab):      │    │
│  │  Stage 1: Athena confirmed registrations → COUNT            │    │
│  │  Stage 2: Rise GTS processed → stats count                  │    │
│  │  Stage 3: CRS rendered → stats dc(line.upmid)              │    │
│  │  Stage 4: Email delivered → stats dc(line.upmid)           │    │
│  │  Drop-off between stages = metric of pipeline health.       │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  SOURCE 2: CloudWatch (infrastructure metrics)                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ECS:                                                        │    │
│  │  • CPUUtilization → auto-scaling trigger (>70% = scale out) │    │
│  │  • MemoryUtilization → detect memory leaks                  │    │
│  │  • RunningTaskCount → verify task count matches desired     │    │
│  │                                                              │    │
│  │  ALB:                                                        │    │
│  │  • RequestCount → traffic volume                            │    │
│  │  • TargetResponseTime → latency (p50, p95, p99)            │    │
│  │  • HTTPCode_Target_5XX → error rate                         │    │
│  │  • HealthyHostCount → how many tasks are serving            │    │
│  │                                                              │    │
│  │  DynamoDB:                                                   │    │
│  │  • ConsumedWriteCapacityUnits → write throughput            │    │
│  │  • ThrottledRequests → capacity issues                      │    │
│  │                                                              │    │
│  │  SQS:                                                        │    │
│  │  • ApproximateNumberOfMessages → queue depth                │    │
│  │  • ApproximateAgeOfOldestMessage → processing lag           │    │
│  │  • NumberOfMessagesInDLQ → failed messages                  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  RED METRICS (Rate, Errors, Duration) for CXP:                     │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Rate:     ALB RequestCount per service (via path filter)   │    │
│  │  Errors:   ALB 5xx count + Splunk drop count                │    │
│  │  Duration: ALB TargetResponseTime (p95, p99)                │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — metrics via Splunk queries:**

```python
# queries.py — metrics queries
"ncp_arrived": f'''search (index=dockerlogs-hc OR index=app-hc)
    AND (appname=ncp-ingest-api OR appname=ncp-integration-layer-processor)
    "Received eventPayload=" *CXP* {time_clause}
    | stats count as arrived''',

"ncp_dropped": f'''search ... *CXP* errorType=* {time_clause}
    | stats count as dropped''',

# Dashboard calculates: drop_rate = dropped / arrived × 100%
```

---

### Example 3: TRACES — Manual Distributed Tracing via Event ID

**Service:** `cxp-email-drop-recovery` Investigation tab
**Pattern:** Correlate across 5 data sources using event ID as the trace key

```
┌──────────────────────────────────────────────────────────────────────┐
│  Distributed Tracing — Investigation Tab                             │
│                                                                      │
│  NOT using Jaeger/Zipkin/X-Ray (formal tracing).                   │
│  INSTEAD: Manual correlation using eventId across 5 data sources.  │
│                                                                      │
│  User enters Event ID: 73067                                       │
│  Dashboard traces the event through the ENTIRE pipeline:           │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │  STAGE 1: Athena (Partner Hub — Source of Truth)              │  │
│  │  Query: SELECT COUNT(*) WHERE event.id = 73067               │  │
│  │         AND action = 'confirmed'                              │  │
│  │  Result: 150 confirmed registrations                         │  │
│  │  Status: 🟢 (Eventtia sent the data)                        │  │
│  ├─────────────────────────────────────────────────────────────┤  │
│  │  STAGE 2: Splunk (Rise GTS Transform)                        │  │
│  │  Query: index=app* app=risegenerictransformservice "73067"   │  │
│  │  Result: 150 transform logs                                  │  │
│  │  Status: 🟢 (Nike system processed all)                     │  │
│  ├─────────────────────────────────────────────────────────────┤  │
│  │  STAGE 2b: Splunk (NCP Ingest)                               │  │
│  │  Query: index=dockerlogs-hc appname=ncp-ingest-api "73067"  │  │
│  │  Result: 148 ingested                                        │  │
│  │  Status: 🟡 (2 missing — possible NCP drop)                 │  │
│  ├─────────────────────────────────────────────────────────────┤  │
│  │  STAGE 3: Splunk (CRS Email Rendering)                       │  │
│  │  Query: index=dockerlogs-gold source=crs-emailrendering*     │  │
│  │         "73067" | stats dc(line.upmid)                       │  │
│  │  Result: 145 unique users rendered                           │  │
│  │  Status: 🔴 (3 rendering failures — missing variables?)     │  │
│  ├─────────────────────────────────────────────────────────────┤  │
│  │  STAGE 4: Splunk (Email Delivery)                            │  │
│  │  Query: index=dockerlogs-gold sourcetype=crs-email* success  │  │
│  │         "73067" | stats dc(line.upmid)                       │  │
│  │  Result: 143 unique users delivered                          │  │
│  │  Status: 🔴 (2 delivered but not rendered? Check CRS errors) │  │
│  └─────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  PIPELINE VISUALIZATION:                                            │
│  150 (Athena) → 150 (Rise) → 148 (NCP) → 145 (CRS) → 143 (Email)│
│  🟢 ─────────── 🟢 ─────── 🟡 ──────── 🔴 ──────── 🔴           │
│                                                                      │
│  DROP-OFF ANALYSIS:                                                 │
│  Athena→Rise: 0 lost (Kafka delivered all ✓)                      │
│  Rise→NCP: 2 lost (NCP ingest issue — check NCP logs)             │
│  NCP→CRS: 3 lost (rendering failures — missing template vars)     │
│  CRS→Email: 2 lost (delivery failures — check SendGrid)           │
│  Total: 7 users didn't receive email out of 150 (4.7% drop rate)  │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — parallel trace queries:**

```python
# server.py — Investigation: 5 parallel queries = distributed trace
sids = {}
# Stage 1: Athena (source of truth)
q_count = f"""SELECT COUNT(*) as total FROM "{ATHENA_DATABASE}".{ATHENA_TABLE}
    WHERE event.id = {event_id} AND action = 'confirmed'"""

# Stage 2: Rise GTS (Splunk)
sids['rise'] = client.start_search(
    f'search index=app* app=risegenerictransformservice "{event_id}" "confirmed" | stats count')

# Stage 2b: NCP Ingest (Splunk)
sids['ncp'] = client.start_search(
    f'search index=dockerlogs-hc appname=ncp-ingest-api "{event_id}" | stats count as ingested')

# Stage 3: CRS Rendering (Splunk)
sids['render'] = client.start_search(
    f'search index=dockerlogs-gold source="crs-emailrenderingservice-prod" "{event_id}"'
    f' | stats dc(line.upmid) as unique_users')

# Stage 4: Email Delivery (Splunk)
sids['email'] = client.start_search(
    f'search index=dockerlogs-gold sourcetype=crs-email* success "{event_id}"'
    f' | stats dc(line.upmid) as delivered_users')

# All 5 run in PARALLEL (scatter-gather) — total wait = max(all queries)
```

---

### Example 4: ALERTING — PagerDuty + Route53 + CloudWatch

```
┌──────────────────────────────────────────────────────────────────────┐
│  Alerting in CXP — Multi-Level                                       │
│                                                                      │
│  LEVEL 1: INFRASTRUCTURE (CloudWatch → Auto-action)                 │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ECS CPU > 70% for 3 min → auto-scale out (add tasks)      │    │
│  │  ECS CPU < 30% for 15 min → auto-scale in (remove tasks)   │    │
│  │  ALB 5xx > 10/min → CloudWatch Alarm → SNS notification    │    │
│  │  DynamoDB ThrottledRequests > 0 → capacity alarm            │    │
│  │  SQS DLQ depth > 0 → dead letter alarm (failed transforms) │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  LEVEL 2: REGION HEALTH (Route53 → Auto-failover)                  │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Health check: GET /community/events_health_us_east/v1      │    │
│  │  Interval: 30 seconds, from multiple Route53 checker regions│    │
│  │  Threshold: failure_threshold failures → UNHEALTHY           │    │
│  │  Action: Route53 stops routing to this region (auto-failover)│   │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  LEVEL 3: BUSINESS METRICS (Splunk → Human investigation)          │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Splunk saved search: email drop rate > 5% in last hour     │    │
│  │  → Splunk alert → email/Slack notification                  │    │
│  │  → On-call engineer opens recovery dashboard                │    │
│  │  → Investigates via Trend + Investigate tabs                │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  LEVEL 4: ESCALATION (PagerDuty)                                   │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  PagerDuty escalation policy: PNJHHME                       │    │
│  │  nikeb2c.pagerduty.com/escalation_policies#PNJHHME         │    │
│  │  Team: CSK - CXP Super Koders                              │    │
│  │  Contact: Lst-CXP.Engineering@nike.com                     │    │
│  │  Slack: #cxp-events-support                                 │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual cxp-infrastructure README:**

```
Contact:
- Team: CSK - CXP Super Koders
- Email: Lst-CXP.Engineering@nike.com
- PagerDuty: https://nikeb2c.pagerduty.com/escalation_policies#PNJHHME
```

---

### Example 5: The Recovery Dashboard — Observability as a Product

The `cxp-email-drop-recovery` dashboard IS an observability product — it combines all three pillars into one investigation tool.

```
┌──────────────────────────────────────────────────────────────────────┐
│  email-drop-recovery Dashboard — All Three Pillars Combined          │
│                                                                      │
│  TAB 1: Email Drop Monitor (METRICS + LOGS)                        │
│  • Summary cards: Total, Sent, Dropped, Drop Rate (metrics)        │
│  • Table: individual dropped emails with details (logs)             │
│  • Filters: by UPM ID, Event ID, Email Type, Marketplace           │
│                                                                      │
│  TAB 2: Investigate Event (TRACES)                                  │
│  • 5-stage pipeline visualization (distributed trace)               │
│  • Green/Yellow/Red indicators per stage                            │
│  • Drop-off analysis between stages                                 │
│                                                                      │
│  TAB 3: Rendering Failures (LOGS)                                   │
│  • Missing template variables (structured log fields)               │
│  • Which users/events affected                                      │
│                                                                      │
│  TAB 4: Trend (METRICS)                                             │
│  • Daily drop count bar chart (7/14/30 days)                       │
│  • 3-day moving average trendline                                   │
│  • Pattern recognition: spike vs gradual vs improving              │
│                                                                      │
│  TAB 5: Reconciliation (TRACES + LOGS)                              │
│  • Athena (who registered) vs Splunk (who got email)                │
│  • Delivery coverage bar                                            │
│  • Missing users table (export as CSV)                              │
│                                                                      │
│  THIS IS CUSTOM OBSERVABILITY:                                      │
│  Not Grafana, not Datadog, not Kibana.                             │
│  Purpose-built for the CXP email delivery domain.                  │
│  Combines Splunk (logs) + Athena (source of truth) + Chart.js      │
│  (visualization) into one investigation workflow.                   │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Observability Across CXP

| Pillar | Technology | What It Provides | CXP Examples |
|--------|-----------|-----------------|-------------|
| **Logs** | Splunk (via Kinesis) | Detailed context, debugging | NCP drop reasons, CRS rendering errors, Redis exceptions, registration flow |
| **Metrics** | Splunk aggregations + CloudWatch | Trends, thresholds, alerting | Drop rate %, pipeline stage counts, ECS CPU, ALB latency, SQS depth |
| **Traces** | Manual correlation (event ID across 5 sources) | End-to-end pipeline visibility | Investigation tab: Athena→Rise→NCP→CRS→Email per event |
| **Alerting** | CloudWatch Alarms + Route53 + PagerDuty | Automatic + human response | Auto-scale, auto-failover, on-call notification |
| **Dashboard** | email-drop-recovery (custom Python + Chart.js) | Domain-specific investigation | 5 tabs combining all three pillars for email delivery |

---

## Common Interview Follow-ups

### Q: "Why Splunk instead of ELK or Datadog?"

> "Nike's enterprise standard is Splunk — it's already deployed, indexed, and secured for all Nike services. We inherit log ingestion (Kinesis pipeline), search (SPL), alerting (saved searches), and retention management (hot/warm/cold tiering). Building on the existing platform means zero operational overhead for log infrastructure. If starting fresh, I'd consider Datadog (unified logs+metrics+traces in one platform) or ELK (open-source, cheaper at scale). The key decision factor is what your org already runs."

### Q: "You don't use Jaeger/Zipkin for tracing. Isn't that a gap?"

> "Yes — formal distributed tracing would improve our investigation speed. Currently, tracing event 73067 through the pipeline requires 5 separate Splunk + Athena queries that I built into the Investigation tab. With Jaeger, we'd have a single trace ID propagated through all 6 services — one click to see the entire call chain with latency per hop. The gap exists because our pipeline is event-driven (Kafka sinks, not HTTP call chains) — traditional trace propagation (HTTP headers) doesn't work across Kafka. I'd implement Kafka-header-based trace propagation: producer adds trace ID to Kafka message header, each consumer extracts it and logs it. Then Splunk correlation by trace ID gives us trace-like visibility without Jaeger infrastructure."

### Q: "How do you know if your services are healthy without a formal metrics dashboard?"

> "Three signals: (1) **ALB health checks** every 10 seconds — `/actuator/health` returns 200. If not, ALB removes the task (automatic). (2) **Route53 health checks** every 30 seconds — `/community/events_health/v1` checks upstream dependencies. If not, Route53 fails over the region (automatic). (3) **CloudWatch metrics** — ECS CPU, ALB request count, 5xx rate, DynamoDB throttling. Alarms fire on threshold breach. For business health, the recovery dashboard's Trend tab is our 'is the email pipeline healthy?' metric. A formal Grafana dashboard with RED metrics (Rate, Errors, Duration) per service would be the next improvement."

### Q: "How does the Trend tab calculate the 3-day moving average?"

> "The Trend tab runs a Splunk query per day (e.g., 30 queries for a 30-day trend), each returning the drop count for that day. The Python server calculates the 3-day moving average in code: `avg = (day[i] + day[i-1] + day[i-2]) / 3`. Chart.js renders the bar chart (daily drops) with a line overlay (moving average). The moving average smooths out daily variance so you can see the real trend: 'Is the problem getting worse, better, or stable?' A sudden spike means a specific event caused issues. A gradual increase means a systemic problem. A downward trend means our fixes are working."

---
---

# Topic 35: Distributed Tracing

> Track requests across service boundaries using trace IDs and spans. Essential for debugging microservices latency and failures.

> **Interview Tip:** Mention context propagation — "Each service passes trace ID in headers; I'd use OpenTelemetry for vendor-neutral instrumentation with Jaeger for visualization."

---

## The Problem

A single user request crosses **multiple services**. When something is slow or fails, traditional per-service logs can't show you the full picture.

```
┌──────────────────────────────────────────────────────────────────────┐
│  PROBLEM: Request spans multiple services — where did it fail?       │
│  Traditional logging can't correlate events across service boundaries│
│                                                                      │
│  TRACE ANATOMY:                                                     │
│                                                                      │
│  Trace ID: abc-123  (unique ID for entire request flow)             │
│                                                                      │
│  ┌──────────────────────────────────────────────────────┐  Each    │
│  │ Span: API Gateway (parent) — 250ms total             │  span    │
│  │  ┌──────────────────────────────────────────┐        │  has:    │
│  │  │ Span: Order Service — 180ms               │        │ -Operation│
│  │  │  ┌────────────┐  ┌───────────────────┐   │        │  name   │
│  │  │  │Span: DB    │  │Span: Payment API  │   │        │ -Start/ │
│  │  │  │Query — 50ms│  │— 100ms            │   │        │  end    │
│  │  │  └────────────┘  └───────────────────┘   │        │ -Parent │
│  │  └──────────────────────────────────────────┘        │  span ID│
│  └──────────────────────────────────────────────────────┘ -Tags   │
│                                                                      │
│  CONTEXT PROPAGATION:                                               │
│  Service A ──▶ Service B ──▶ Service C                              │
│  Headers: X-Trace-ID, X-Span-ID passed along                       │
│                                                                      │
│  TOOLS: Jaeger (CNCF), Zipkin (Twitter), AWS X-Ray, OpenTelemetry  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Distributed Tracing In My CXP Projects

### CXP's Approach: Manual Trace Correlation (Not Jaeger/Zipkin)

Our platform doesn't use a formal tracing tool. Instead, we built **manual distributed tracing** using event ID and trace ID fields correlated across Splunk indexes and Athena — implemented in the email-drop-recovery Investigation tab.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP TRACING — TWO APPROACHES                                            │
│                                                                          │
│  APPROACH 1: Wingtips (Rise GTS — library-based tracing)                │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Rise GTS uses Nike's Wingtips library for trace propagation:    │  │
│  │  - WingtipsSpringUtil.createTracingEnabledRestTemplate()         │  │
│  │  - ExecutorServiceWithTracing (propagates trace across threads)  │  │
│  │  - Hystrix tracing hooks (wingtips.hystrix.* properties)        │  │
│  │                                                                   │  │
│  │  Trace ID propagated via HTTP headers on outbound calls.         │  │
│  │  Logged to Splunk → searchable by trace ID.                     │  │
│  │  Limited to Rise GTS boundaries (not end-to-end CXP).          │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  APPROACH 2: Manual Correlation (email-drop-recovery — custom)          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  The Investigation tab IS a manual distributed trace:             │  │
│  │  - "Trace key" = event ID (e.g., 73067)                          │  │
│  │  - 5 parallel queries across 5 different data sources            │  │
│  │  - Each query searches its source for the same event ID          │  │
│  │  - Results assembled into a pipeline visualization               │  │
│  │  - Green/Yellow/Red per stage = trace-like span status           │  │
│  │                                                                   │  │
│  │  This covers the FULL end-to-end pipeline:                       │  │
│  │  Athena → Rise GTS → NCP → CRS → Email Delivery                │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

### The Investigation Tab — Manual Trace in Action

```
┌──────────────────────────────────────────────────────────────────────┐
│  Investigation Tab = Distributed Trace for Event 73067               │
│                                                                      │
│  "Trace ID" = event_id = 73067                                     │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │ Span 1: Athena (Partner Hub) — 150 confirmed registrations   │  │
│  │ Duration: ~10s (Athena query time)    Status: 🟢              │  │
│  │  ┌──────────────────────────────────────────────────────┐    │  │
│  │  │ Span 2: Splunk (Rise GTS) — 150 transform logs       │    │  │
│  │  │ Duration: ~5s (Splunk search)      Status: 🟢         │    │  │
│  │  │  ┌──────────────────────────────────────────────┐     │    │  │
│  │  │  │ Span 3: Splunk (NCP Ingest) — 148 ingested   │     │    │  │
│  │  │  │ Duration: ~5s                  Status: 🟡      │     │    │  │
│  │  │  │  ┌─────────────────────────────────────┐      │     │    │  │
│  │  │  │  │ Span 4: Splunk (CRS) — 145 rendered  │      │     │    │  │
│  │  │  │  │ Duration: ~8s        Status: 🔴       │      │     │    │  │
│  │  │  │  │  ┌────────────────────────────┐       │      │     │    │  │
│  │  │  │  │  │ Span 5: Splunk (Delivery)  │       │      │     │    │  │
│  │  │  │  │  │ 143 delivered  Status: 🔴  │       │      │     │    │  │
│  │  │  │  │  └────────────────────────────┘       │      │     │    │  │
│  │  │  │  └─────────────────────────────────────┘      │     │    │  │
│  │  │  └──────────────────────────────────────────────┘     │    │  │
│  │  └──────────────────────────────────────────────────────┘    │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  TRACE SUMMARY:                                                     │
│  150 → 150 → 148 → 145 → 143                                      │
│  7 users lost across the pipeline. Where?                           │
│  NCP: 2 dropped (email not available — MemberHub race condition)   │
│  CRS: 3 failed (MissingRequiredVariablesError — template issue)    │
│  Delivery: 2 failed (SendGrid delivery issue)                      │
│                                                                      │
│  FORMAL TRACE WOULD SHOW:                                           │
│  Same info but per-REQUEST (one user's journey), not per-EVENT.    │
│  Our manual trace is per-EVENT (150 users' aggregate journey).     │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — the "trace" implementation:**

```python
# server.py — 5 spans of a manual distributed trace
sids = {}
# Span 1: Source of truth (Athena)
athena_result = run_athena_query(q_count)

# Span 2: Transform stage (Splunk — Rise GTS index)
sids['rise'] = client.start_search(
    f'search index=app* app=risegenerictransformservice "{event_id}"')

# Span 3: Ingest stage (Splunk — NCP index)
sids['ncp'] = client.start_search(
    f'search index=dockerlogs-hc appname=ncp-ingest-api "{event_id}"')

# Span 4: Render stage (Splunk — CRS index)
sids['render'] = client.start_search(
    f'search index=dockerlogs-gold source="crs-emailrenderingservice-prod" "{event_id}"')

# Span 5: Delivery stage (Splunk — email delivery index)
sids['email'] = client.start_search(
    f'search index=dockerlogs-gold sourcetype=crs-email* success "{event_id}"')

# All spans run in PARALLEL — total trace time = max(all queries)
# Results assembled into pipeline visualization with status indicators
```

### Wingtips — Library-Based Tracing in Rise GTS

```java
// Rise GTS — Wingtips trace propagation across threads and HTTP calls

// RestTemplate with trace headers automatically propagated:
@Bean
public RestTemplate restTemplate() {
    return WingtipsSpringUtil.createTracingEnabledRestTemplate();
    // Outbound HTTP calls carry X-Trace-ID, X-Span-ID headers
}

// Thread pool with trace propagation (ForkJoinPool → child threads inherit trace):
@Bean
public ExecutorService executorService() {
    return new ExecutorServiceWithTracing(new ForkJoinPool(MAX_THREADS));
    // Each parallel transform task inherits the parent trace context
}

// application.properties — Wingtips + Hystrix tracing integration
// wingtips.hystrix.* properties enable trace propagation through circuit breakers
```

### What Formal Tracing Would Add

```
┌──────────────────────────────────────────────────────────────────────┐
│  CXP WITH OPENTELEMETRY + JAEGER (hypothetical improvement)         │
│                                                                      │
│  CURRENT (manual):                    WITH OPENTELEMETRY:           │
│  ─────────────────                    ───────────────────           │
│  5 Splunk queries per investigation   1 trace ID → full waterfall  │
│  Aggregate per-event (150 users)      Per-request (1 user's journey)│
│  ~30-120 seconds to investigate       ~1 second to view trace      │
│  No latency per-hop breakdown         Exact ms per service hop     │
│  Manual correlation by event ID       Automatic trace propagation  │
│                                                                      │
│  IMPLEMENTATION:                                                    │
│  1. Add OpenTelemetry SDK to each Spring Boot service               │
│  2. Auto-instrument: RestTemplate, WebClient, Redis, DynamoDB      │
│  3. Propagate trace context via HTTP headers (W3C Trace Context)   │
│  4. For Kafka: add trace ID to message headers (producer)          │
│     Extract trace ID from message headers (consumer)               │
│  5. Export spans to Jaeger or AWS X-Ray                            │
│                                                                      │
│  CHALLENGE FOR CXP:                                                 │
│  Our pipeline is EVENT-DRIVEN (Kafka sinks), not HTTP call chains. │
│  Standard HTTP header propagation breaks at the Kafka boundary.    │
│  Need: Kafka-header-based trace propagation (not automatic).       │
│                                                                      │
│  KEEP: The Investigation tab (aggregate per-event view).           │
│  ADD: Per-request tracing for individual failure debugging.        │
│  Both are valuable for different investigation scenarios.          │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "Our platform uses manual distributed tracing via the email-drop-recovery Investigation tab — 5 parallel queries across Athena and Splunk, correlated by event ID, showing a pipeline waterfall with green/yellow/red per stage. Rise GTS uses Nike's Wingtips library for automatic HTTP header trace propagation and thread-pool trace inheritance. The gap: our Kafka-driven pipeline breaks standard HTTP trace propagation at the NSP3 boundary. If I were adding formal tracing, I'd use OpenTelemetry with Kafka-header-based context propagation — the producer adds trace ID to the Kafka message header, each consumer sink extracts it. The Investigation tab stays for aggregate per-event analysis; formal tracing adds per-request debugging."

---
---

# Topic 36: Health Checks & Heartbeats

> Liveness checks if process is running; readiness checks if it can serve traffic; startup probe for slow-starting apps. Failed checks remove instances from load balancer.

> **Interview Tip:** Distinguish them — "/healthz for liveness (just 200 OK), /ready for readiness (checks DB connection, cache availability) — Kubernetes uses both to manage pod lifecycle."

---

## The Three Types

```
┌──────────────────────────────────────────────────────────────────────┐
│  HEALTH CHECK TYPES                                                  │
│                                                                      │
│  ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐   │
│  │    LIVENESS       │ │    READINESS      │ │    STARTUP        │   │
│  │   "Are you alive?"│ │  "Can you serve?" │ │  "Are you ready?" │   │
│  │                   │ │                   │ │                   │   │
│  │  Checks: process  │ │  Checks: can      │ │  Checks: has the  │   │
│  │  is running,      │ │  handle requests? │ │  app finished     │   │
│  │  not deadlocked.  │ │  Dependencies     │ │  initializing?    │   │
│  │                   │ │  available?       │ │                   │   │
│  │  If fails:        │ │  If fails:        │ │  If fails:        │   │
│  │  RESTART container│ │  STOP sending     │ │  KEEP waiting     │   │
│  │                   │ │  traffic (but     │ │  (don't restart   │   │
│  │  Endpoint:        │ │  don't restart)   │ │  yet)             │   │
│  │  /healthz         │ │  /ready           │ │  /startup         │   │
│  │  GET → 200 OK     │ │  GET → 200 OK     │ │  GET → 200 OK     │   │
│  └──────────────────┘ └──────────────────┘ └──────────────────┘   │
│                                                                      │
│  LIFECYCLE:                                                         │
│  Container starts → Startup probe passes → Readiness probe passes  │
│                     → traffic starts flowing → Liveness monitored   │
│                                                                      │
│  Liveness fails → Kubernetes KILLS and RESTARTS the container      │
│  Readiness fails → Kubernetes REMOVES from service (no traffic)    │
│  Startup fails → Kubernetes WAITS (doesn't kill prematurely)       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Health Checks In My CXP Projects — Three Levels

Our platform has **three independent levels** of health checking, each with different scope and failure response.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — THREE LEVELS OF HEALTH CHECKS                            │
│                                                                          │
│  LEVEL 1: ALB Target Group Health Check                                 │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Endpoint: /actuator/health                                       │  │
│  │  Port: 8080 (cxp-events, cxp-reg, Rise GTS)                     │  │
│  │        8077 (expviewsnikeapp — separate management port)         │  │
│  │  Interval: 10 seconds                                            │  │
│  │  Matcher: HTTP 200                                                │  │
│  │  Scope: "Is THIS task healthy?"                                  │  │
│  │  If fails: ALB removes task from target group (no traffic)       │  │
│  │  If all fail: ALB returns 503 to clients                         │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  LEVEL 2: NPE Kubernetes Liveness + Readiness Probes                    │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Endpoint: /community/events_health/v1 (custom, not actuator)    │  │
│  │  Port: 8080                                                       │  │
│  │                                                                   │  │
│  │  Liveness:  "Is the process alive?"                              │  │
│  │  If fails → Kubernetes RESTARTS the container                    │  │
│  │                                                                   │  │
│  │  Readiness: "Can it serve traffic?"                              │  │
│  │  If fails → Kubernetes REMOVES from service (no traffic routed)  │  │
│  │  Container stays running. May recover (e.g., dependency back up).│  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  LEVEL 3: Route53 Region Health Check                                   │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Endpoint: /community/events_health_us_east/v1 (region-specific) │  │
│  │  Interval: 30 seconds (from multiple Route53 checker regions)    │  │
│  │  Scope: "Is the ENTIRE REGION healthy?"                          │  │
│  │  If fails: Route53 stops routing ALL traffic to this region      │  │
│  │  → Automatic failover to the other region                        │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

### The Three Endpoints — Different Scopes, Different Responses

```
┌──────────────────────────────────────────────────────────────────────┐
│  THREE HEALTH ENDPOINTS PER CXP SERVICE                              │
│                                                                      │
│  ┌────────────────────────────────────┬──────────┬────────────────┐│
│  │  Endpoint                           │  Checked │  Failure Action││
│  │                                     │  By      │                ││
│  ├────────────────────────────────────┼──────────┼────────────────┤│
│  │  /actuator/health                   │  ALB     │  Remove task   ││
│  │  Spring Boot actuator. Reports     │  (10s)   │  from target   ││
│  │  UP/DOWN based on enabled health    │          │  group.        ││
│  │  indicators.                        │          │                ││
│  ├────────────────────────────────────┼──────────┼────────────────┤│
│  │  /community/events_health/v1       │  NPE K8s │  Liveness fail:││
│  │  Custom controller. Can include    │  probes  │  restart pod.  ││
│  │  deeper dependency checks.         │          │  Readiness fail:│
│  │                                     │          │  stop traffic. ││
│  ├────────────────────────────────────┼──────────┼────────────────┤│
│  │  /community/events_health_us_east/v1│ Route53 │  Failover      ││
│  │  Region-specific. Same logic but   │  (30s)   │  entire region ││
│  │  separate URL for per-region       │          │  to us-west-2. ││
│  │  health tracking.                  │          │                ││
│  └────────────────────────────────────┴──────────┴────────────────┘│
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — three health endpoints:**

```java
// HealthCheckController.java (cxp-events)
@RequestMapping(value = {
    "/community/events_health/v1",           // NPE liveness + readiness
    "/community/events_health_us_east/v1",   // Route53 us-east health check
    "/community/events_health_us_west/v1"    // Route53 us-west health check
})
public class HealthCheckController {
    @GetMapping
    @Unsecured    // no JWT required — health checks must be public
    public Mono<ResponseEntity<HealthCheckResponse>> checkHealth() {
        // Returns 200 if service can handle requests
    }
}
```

```properties
# application.properties — actuator health for ALB
management.endpoints.web.exposure.include=info,env,health
management.endpoints.web.base-path=/actuator
management.endpoint.health.show-details=always
```

```yaml
# NPE component YAML — K8s probes pointing to custom health endpoint
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

### The Critical Design Decision: Excluding Dependencies from Health

```
┌──────────────────────────────────────────────────────────────────────┐
│  WHAT TO INCLUDE (AND EXCLUDE) IN HEALTH CHECKS                      │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  management.health.redis.enabled=false                      │    │
│  │  management.health.elasticsearch.enabled=false              │    │
│  │                                                              │    │
│  │  WHY DISABLED:                                               │    │
│  │  Redis is a CACHE, not a dependency. If Redis is down:      │    │
│  │  - try-catch fallback returns null → service still works    │    │
│  │  - Users get slightly slower responses (cache miss)          │    │
│  │                                                              │    │
│  │  If Redis health was ENABLED in actuator:                    │    │
│  │  - Redis down → /actuator/health returns 503                │    │
│  │  - ALB removes ALL tasks from target group                   │    │
│  │  - SERVICE IS COMPLETELY DOWN (because a cache failed!)     │    │
│  │  - This is WORSE than running without cache.                 │    │
│  │                                                              │    │
│  │  RULE: Only include dependencies that are REQUIRED for the   │    │
│  │  service to function. If you have a fallback → exclude it.  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  WHAT SHOULD BE IN HEALTH CHECK:                                    │
│  ✓ JVM is running (implicit — endpoint responds)                   │
│  ✓ Server port is bound (implicit — HTTP response received)        │
│  ✓ Thread pool not exhausted (implicit — request handled)          │
│  ✗ Redis (optional cache — has fallback)                           │
│  ✗ Elasticsearch (optional search — has fallback)                  │
│  ✗ Eventtia (external — can't control its health)                  │
│  ? DynamoDB (could argue either way — write-path dependency)       │
│                                                                      │
│  expviewsnikeapp: separate management port (8077) for health:      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  server.port=8080              ← app traffic                │    │
│  │  management.server.port=8077   ← health + actuator only    │    │
│  │                                                              │    │
│  │  WHY SEPARATE PORT:                                         │    │
│  │  Health checks bypass the application's request processing  │    │
│  │  pipeline. If the app is overwhelmed (thread pool full),    │    │
│  │  health check still responds on management port.            │    │
│  │  ALB checks port 8077 — always reachable even under load.  │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

### Health Check Cascade — How a Single Task Failure Flows

```
┌──────────────────────────────────────────────────────────────────────┐
│  HEALTH CHECK CASCADE                                                │
│                                                                      │
│  SCENARIO: ECS Task 3 (cxp-events) has a memory leak.              │
│                                                                      │
│  T=0s:   Task 3 starts responding slowly (GC pressure).            │
│  T=10s:  ALB health check: GET /actuator/health → 200 (still OK). │
│  T=20s:  ALB health check: GET /actuator/health → timeout!         │
│  T=30s:  ALB health check: GET /actuator/health → timeout!         │
│          ALB: "Task 3 is unhealthy" → removes from target group.   │
│          Tasks 1, 2, 4 absorb Task 3's traffic.                    │
│                                                                      │
│  T=30s:  NPE liveness: GET /events_health/v1 → timeout!           │
│  T=60s:  NPE liveness: still failing.                               │
│          NPE: "Pod is dead" → RESTARTS the container.              │
│          New container starts fresh (memory leak cleared).         │
│                                                                      │
│  T=90s:  New container passes readiness probe.                     │
│          NPE adds pod back to service.                             │
│          ALB health check passes → task added to target group.     │
│          Traffic flows to all 4 tasks again.                       │
│                                                                      │
│  FULL RECOVERY: ~90 seconds. Automatic. No human intervention.    │
│                                                                      │
│  IF ALL TASKS IN us-east-1 FAIL:                                   │
│  T=30s:  All tasks removed from ALB → ALB returns 503.            │
│  T=60s:  Route53 health check: GET /events_health_us_east/v1 → 503│
│  T=90s:  Route53: "us-east-1 is unhealthy" → failover.           │
│          ALL traffic routed to us-west-2 (automatic).              │
│  T=120s: us-east-1 tasks restarted by NPE → recover.             │
│          Route53 health check passes → traffic redistributed.     │
│                                                                      │
│  THREE LEVELS, THREE SCOPES:                                       │
│  ALB: per-TASK recovery (~30s)                                     │
│  NPE: per-CONTAINER restart (~60s)                                 │
│  Route53: per-REGION failover (~90s)                               │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual Terraform — Route53 health check:**

```hcl
// terraform/aws/modules/route53-health-check/health_check.tf
resource "aws_route53_health_check" "health_check" {
  fqdn              = var.fqdn               // events health endpoint
  port              = var.port               // 443 (HTTPS)
  type              = var.type               // "HTTPS"
  resource_path     = var.resource_path      // "/events_health_us_east/v1"
  failure_threshold = var.failure_threshold  // N failures → unhealthy
  request_interval  = var.request_interval   // 30 seconds
  regions           = var.regions            // checked from multiple AWS regions
}
```

---

## Summary: Health Checks & Tracing Across CXP

| Feature | Implementation | Scope | Failure Response |
|---------|---------------|-------|-----------------|
| **ALB Health** | `/actuator/health` (10s interval) | Per-task | Remove from target group |
| **NPE Liveness** | `/community/events_health/v1` | Per-container | Restart container |
| **NPE Readiness** | `/community/events_health/v1` | Per-container | Stop routing traffic |
| **Route53 Health** | `/events_health_us_east/v1` (30s interval) | Per-region | Failover all traffic to other region |
| **Redis health** | `management.health.redis.enabled=false` | Excluded | N/A — Redis down ≠ service down |
| **Distributed Trace** | Investigation tab (5 parallel queries) | Per-event (aggregate) | Manual investigation via dashboard |
| **Library Trace** | Wingtips (Rise GTS only) | Per-request within Rise GTS | Trace ID in Splunk logs |

---

## Common Interview Follow-ups

### Q: "Liveness vs readiness — when would each fail differently?"

> "In our platform: if Redis goes down, READINESS should still pass (we have fallbacks) and LIVENESS should still pass (the process is running). If the JVM is deadlocked, LIVENESS fails (process unresponsive) → restart. If we had a critical dependency like a database, READINESS would fail (can't serve correctly) while LIVENESS passes (process is alive) — Kubernetes stops traffic but doesn't restart, giving the database time to recover. Our `management.health.redis.enabled=false` ensures Redis outages don't trigger either probe. The health endpoint returns 200 as long as the Spring Boot app is responsive."

### Q: "Why a custom health endpoint instead of just /actuator/health?"

> "Two reasons: (1) NPE liveness probes point to `/community/events_health/v1` — a custom endpoint that can include deeper checks (Eventtia reachability, specific business logic). (2) Route53 needs region-specific URLs (`events_health_us_east/v1` vs `events_health_us_west/v1`) to track each region independently. Spring actuator's `/actuator/health` is generic — it can't distinguish which region it's being checked from. The custom endpoints give us per-region health monitoring that drives Route53 failover decisions."

### Q: "Why not use Jaeger/X-Ray for distributed tracing?"

> "Our pipeline is event-driven (Kafka sinks), not HTTP call chains. Standard trace propagation (HTTP headers) breaks at the Kafka boundary — when NSP3 picks up an event from the Kafka stream and POSTs it to Rise GTS, there's no upstream HTTP request to propagate headers from. Rise GTS does use Wingtips for its internal HTTP calls, but end-to-end tracing across the full pipeline (Eventtia → Kafka → Rise GTS → NCP → CRS) would require Kafka-header-based trace propagation. Our Investigation tab solves this by correlating event ID across all 5 data sources — it's a custom trace tool for an event-driven architecture where standard HTTP tracing doesn't apply."

### Q: "How do you avoid health check false positives during deployments?"

> "Rolling deployments: ECS replaces one task at a time. The old task is drained (ALB sends no new requests) before termination. The new task must pass the ALB health check before receiving traffic. During this window, remaining tasks handle all traffic — users see zero downtime. NPE readiness probes ensure the new pod is fully started (Spring context loaded, caches warming) before it receives traffic. Without readiness probes, a half-started container could receive requests and return 500s — which looks like a service outage."

---
---

# Topic 37: Failover & Redundancy

> Active-passive has standby taking over on failure; active-active has both serving traffic. Multi-region provides geographic redundancy.

> **Interview Tip:** Match to requirements — "For 99.99% availability, I'd use active-active across two regions with global DNS failover; for simpler needs, active-passive with automated promotion."

---

## The Two Patterns

```
┌──────────────────────────────────────────────────────────────────────────┐
│                    FAILOVER & REDUNDANCY                                  │
│                                                                          │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐    │
│  │  ACTIVE-PASSIVE (Hot Standby)│  │  ACTIVE-ACTIVE               │    │
│  │                              │  │                               │    │
│  │  ┌─────────┐sync┌─────────┐│  │  ┌─────────┐sync┌─────────┐ │    │
│  │  │ Primary │────▶│ Standby ││  │  │ Node 1  │◀──▶│ Node 2  │ │    │
│  │  │ ACTIVE  │     │ PASSIVE ││  │  │ ACTIVE  │    │ ACTIVE  │ │    │
│  │  └─────────┘     └─────────┘│  │  └─────────┘    └─────────┘ │    │
│  │                              │  │                               │    │
│  │  Standby takes over if       │  │  Both serve traffic, load    │    │
│  │  primary fails.              │  │  shared.                     │    │
│  │                              │  │                               │    │
│  │  [+] Simple, no conflict    │  │  [+] Better resource          │    │
│  │  [-] Standby resources idle │  │      utilization              │    │
│  │                              │  │  [-] Conflict resolution      │    │
│  │                              │  │      needed                  │    │
│  └──────────────────────────────┘  └──────────────────────────────┘    │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  MULTI-REGION REDUNDANCY                                          │  │
│  │                                                                   │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │  │
│  │  │  US-EAST      │  │  EU-WEST      │  │  AP-SOUTH    │          │  │
│  │  │  (Primary)    │  │               │  │              │          │  │
│  │  │ ┌───┐ ┌──┐   │  │ ┌───┐ ┌──┐   │  │ ┌───┐ ┌──┐  │          │  │
│  │  │ │App│ │DB│   │  │ │App│ │DB│   │  │ │App│ │DB│  │          │  │
│  │  │ └───┘ └──┘   │  │ └───┘ └──┘   │  │ └───┘ └──┘  │          │  │
│  │  └──────────────┘  └──────────────┘  └──────────────┘          │  │
│  │                                                                   │  │
│  │  Global DNS routes users to nearest healthy region.              │  │
│  │  Async replication between regions.                              │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Failover & Redundancy In My CXP Projects

### The CXP Platform — Component-by-Component Redundancy

Every component uses a **different failover pattern** based on its consistency requirements and operational model.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — FAILOVER PATTERN PER COMPONENT                           │
│                                                                          │
│  ┌──────────────┬──────────────┬─────────────────────────────────────┐ │
│  │  Component    │  Pattern     │  How It Works                       │ │
│  ├──────────────┼──────────────┼─────────────────────────────────────┤ │
│  │  DynamoDB     │  ACTIVE-     │  Both us-east-1 and us-west-2      │ │
│  │  Global Table │  ACTIVE      │  accept reads AND writes. Async    │ │
│  │              │  (multi-     │  replication ~1s. Last-writer-wins. │ │
│  │              │  leader)     │  No failover needed — both active. │ │
│  ├──────────────┼──────────────┼─────────────────────────────────────┤ │
│  │  ECS Tasks    │  ACTIVE-     │  Multiple tasks per region. ALB    │ │
│  │  (per region) │  ACTIVE      │  round-robins across all healthy   │ │
│  │              │  (within     │  tasks. Task dies → ALB removes    │ │
│  │              │  region)     │  it → others absorb traffic.       │ │
│  ├──────────────┼──────────────┼─────────────────────────────────────┤ │
│  │  ECS Tasks    │  ACTIVE-     │  Both regions run tasks. Route53   │ │
│  │  (cross-     │  ACTIVE      │  latency routing serves users from │ │
│  │   region)    │  (multi-     │  nearest region. Region failure →  │ │
│  │              │  region)     │  Route53 failover to other region. │ │
│  ├──────────────┼──────────────┼─────────────────────────────────────┤ │
│  │  Redis        │  ACTIVE-     │  Primary handles writes.           │ │
│  │  ElastiCache  │  PASSIVE     │  Replicas handle reads.            │ │
│  │              │  (for writes)│  Primary dies → ElastiCache auto-  │ │
│  │              │              │  promotes a replica (~30s).        │ │
│  ├──────────────┼──────────────┼─────────────────────────────────────┤ │
│  │  Kafka/NSP3   │  ACTIVE-     │  Partition leader handles writes.  │ │
│  │  Partitions   │  PASSIVE     │  ISR replicas are standby.         │ │
│  │              │  (per        │  Leader dies → Zookeeper/KRaft     │ │
│  │              │  partition)  │  elects new leader from ISR.       │ │
│  ├──────────────┼──────────────┼─────────────────────────────────────┤ │
│  │  S3           │  ACTIVE-     │  3 AZ replication (synchronous).  │ │
│  │              │  ACTIVE      │  Any AZ serves reads/writes.       │ │
│  │              │  (within     │  AZ failure → other AZs handle.   │ │
│  │              │  region)     │  11 nines durability.              │ │
│  ├──────────────┼──────────────┼─────────────────────────────────────┤ │
│  │  Akamai CDN   │  ACTIVE-     │  250+ PoPs all active. PoP dies   │ │
│  │              │  ACTIVE      │  → DNS routes to next nearest PoP. │ │
│  │              │  (global)    │  Anycast IP = automatic failover.  │ │
│  ├──────────────┼──────────────┼─────────────────────────────────────┤ │
│  │  Elasticsearch│  ACTIVE-     │  Primary shard handles writes.     │ │
│  │  Shards       │  PASSIVE     │  Replica shards are standby.       │ │
│  │              │  (per shard) │  Primary fails → replica promoted. │ │
│  └──────────────┴──────────────┴─────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: Multi-Region Active-Active — The Full Picture

**Scope:** us-east-1 + us-west-2 — both regions serve production traffic simultaneously

```
┌──────────────────────────────────────────────────────────────────────┐
│  CXP Multi-Region Active-Active                                      │
│                                                                      │
│  NORMAL OPERATION (both regions active):                            │
│                                                                      │
│  ┌──────────────────────────┐  ┌──────────────────────────┐       │
│  │  us-east-1                │  │  us-west-2                │       │
│  │                           │  │                           │       │
│  │  ALB → ECS tasks (×4)    │  │  ALB → ECS tasks (×4)    │       │
│  │  Redis (Primary+Replicas) │  │  Redis (Primary+Replicas) │       │
│  │  DynamoDB (leader)        │◀▶│  DynamoDB (leader)        │       │
│  │                           │  │                           │       │
│  └─────────────┬─────────────┘  └─────────────┬─────────────┘       │
│                │                               │                    │
│           East Coast users              West Coast / APAC users     │
│           (Route53: low latency)        (Route53: low latency)      │
│                                                                      │
│  FAILOVER (us-east-1 goes down):                                    │
│                                                                      │
│  ┌──────────────────────────┐  ┌──────────────────────────┐       │
│  │  us-east-1 ❌             │  │  us-west-2 ✅             │       │
│  │  (health check failing)   │  │  (absorbs ALL traffic)   │       │
│  │                           │  │                           │       │
│  │  Route53: UNHEALTHY       │  │  ALB → ECS tasks (×8)    │       │
│  │  → stops routing here     │  │  (auto-scaled up)        │       │
│  └──────────────────────────┘  └──────────────────────────┘       │
│                                         ▲                          │
│                                         │                          │
│                              ALL users (East + West + APAC)        │
│                              Route53 sends everyone to us-west-2   │
│                                                                      │
│  RECOVERY (us-east-1 back online):                                  │
│  Route53 health check passes → traffic redistributes by latency.   │
│  DynamoDB Global Table auto-syncs missed writes.                   │
│  No manual intervention at any step.                                │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual Terraform — latency-based routing with health checks:**

```hcl
// Route53 — active-active with health-based failover
resource "aws_route53_record" "cname_record" {
  name    = var.record_name  // "any.v1.events..."
  type    = "CNAME"
  records = [var.record_value]  // regional ALB DNS

  dynamic "latency_routing_policy" {
    for_each = var.routing_policy == "LATENCY" ? [1] : []
    content {
      region = var.region  // us-east-1 or us-west-2
    }
  }
  health_check_id = var.health_check_id  // Route53 health check
  // If health check fails → Route53 stops returning this record
  // → ALL traffic goes to the other region's record
}
```

**Interview answer:**
> "Our platform is active-active across us-east-1 and us-west-2. Both regions serve production traffic simultaneously — Route53 latency-based routing sends users to the nearest region. DynamoDB Global Tables accept writes in both regions with last-writer-wins conflict resolution. If us-east-1 goes down, Route53 health checks detect the failure in ~30 seconds and stop routing traffic there — us-west-2 absorbs everything. ECS auto-scales to handle the doubled load. When us-east-1 recovers, Route53 redistributes traffic by latency. Zero manual intervention, zero data loss (DynamoDB syncs after recovery)."

---

### Example 2: Redis — Active-Passive with Automatic Promotion

**Component:** ElastiCache Redis (Primary + 3 Read Replicas)
**Pattern:** Active-passive for writes, active-active for reads

```
┌──────────────────────────────────────────────────────────────────────┐
│  Redis Failover — Active-Passive with Auto-Promotion                 │
│                                                                      │
│  NORMAL:                                                            │
│  ┌──────────┐  async  ┌────────┐ ┌────────┐ ┌────────┐           │
│  │ PRIMARY  │────────▶│ R1     │ │ R2     │ │ R3     │           │
│  │ (writes) │ replicate│ (read) │ │ (read) │ │ (read) │           │
│  └──────────┘         └────────┘ └────────┘ └────────┘           │
│       ▲                    ▲         ▲         ▲                  │
│    writes              reads distributed via REPLICA_PREFERRED     │
│                                                                      │
│  PRIMARY CRASHES:                                                   │
│  ┌──────────┐         ┌────────┐ ┌────────┐ ┌────────┐           │
│  │ PRIMARY  │ ❌      │ R1     │ │ R2     │ │ R3     │           │
│  │ (dead)   │         │        │ │ (most  │ │        │           │
│  └──────────┘         │        │ │ caught │ │        │           │
│                        └────────┘ │  up)   │ └────────┘           │
│                                   └────┬───┘                      │
│                                        │                          │
│  T=0s:   Primary stops responding.                                │
│  T=10s:  ElastiCache detects failure (sentinel-like quorum).      │
│  T=20s:  R2 selected (most caught-up replica) → promoted.        │
│  T=30s:  R1, R3 reconfigure to follow new primary (R2).          │
│  T=30s:  DNS endpoint updated → app auto-reconnects.              │
│                                                                      │
│  ┌──────────┐         ┌────────┐ ┌────────┐ ┌────────┐           │
│  │ (old     │         │ R1     │ │NEW     │ │ R3     │           │
│  │  primary │         │(follows│ │PRIMARY │ │(follows│           │
│  │  gone)   │         │ R2)    │ │ (R2)   │ │ R2)    │           │
│  └──────────┘         └────────┘ └────────┘ └────────┘           │
│                                                                      │
│  IMPACT ON CXP:                                                     │
│  - ~30 seconds of write unavailability                              │
│  - Reads may fail briefly (replica reconfiguration)                 │
│  - try-catch fallbacks activate (Topic 17):                        │
│    Redis miss → call Partner API directly (pairwise)               │
│    Redis miss → call Eventtia directly (idempotency bypassed,     │
│    Eventtia's own 422 catches duplicates)                          │
│  - management.health.redis.enabled=false prevents ALB from         │
│    killing the service during the 30-second failover window        │
│                                                                      │
│  DATA LOSS RISK:                                                    │
│  Async replication → writes ACK'd by primary but not yet           │
│  replicated to R2 are LOST when primary crashes.                   │
│  For CXP: lost cache entries = next request misses cache and       │
│  calls source API. Acceptable for a cache layer.                   │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 3: Kafka/NSP3 — Active-Passive Per Partition with ISR

```
┌──────────────────────────────────────────────────────────────────────┐
│  Kafka Partition Failover                                            │
│                                                                      │
│  Partition 0: Leader = Broker A, ISR = {A, B, C}                   │
│                                                                      │
│  NORMAL: All writes go to Broker A (leader).                       │
│  Broker A replicates to B and C (ISR members).                     │
│                                                                      │
│  BROKER A DIES:                                                     │
│  1. Zookeeper/KRaft detects heartbeat loss.                        │
│  2. Controller selects Broker B (in ISR, most caught-up).          │
│  3. Broker B becomes new leader for Partition 0.                   │
│  4. Producers and consumers redirect to Broker B.                  │
│  5. No data loss (B had all committed messages from ISR).          │
│                                                                      │
│  FOR CXP:                                                           │
│  - NSP3 sinks (HTTP Push, S3, Purge) auto-reconnect to new leader.│
│  - Brief blip (~seconds) during leader election.                   │
│  - No email drops caused by Kafka broker failure.                  │
│  - Messages produced during election wait in producer buffer.      │
│                                                                      │
│  ISR vs REPLICATION:                                                │
│  acks=all: message committed only when ALL ISR members replicate.  │
│  → Zero data loss on leader failure (any ISR member has all data). │
│  acks=1: message committed when ONLY leader writes.                │
│  → Possible data loss if leader dies before replication.           │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 4: The Full Failover Cascade — Regional Outage Scenario

```
┌──────────────────────────────────────────────────────────────────────┐
│  SCENARIO: us-east-1 Availability Zone A goes down                   │
│                                                                      │
│  T=0s:    AZ-A in us-east-1 becomes unreachable.                   │
│                                                                      │
│  LAYER 1: ECS TASKS (seconds)                                       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Tasks in AZ-A fail health checks.                          │    │
│  │  ALB removes AZ-A tasks from target group.                  │    │
│  │  AZ-B and AZ-C tasks absorb traffic.                        │    │
│  │  ECS auto-scaling launches replacement tasks in AZ-B/C.     │    │
│  │  Impact: ~10-30 seconds. Automatic.                         │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  LAYER 2: REDIS (30 seconds)                                        │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  If Redis primary was in AZ-A → primary lost.               │    │
│  │  ElastiCache Multi-AZ promotes replica in AZ-B/C (~30s).   │    │
│  │  During failover: try-catch fallbacks serve requests.       │    │
│  │  After failover: normal caching resumes.                    │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  LAYER 3: DYNAMODB (0 seconds — transparent)                       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  DynamoDB replicates across 3 AZs synchronously.            │    │
│  │  AZ-A down → 2 remaining AZs continue (quorum = 2/3).     │    │
│  │  No failover needed. Reads and writes continue.             │    │
│  │  Zero impact on unprocessed registration queue.             │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  LAYER 4: S3 (0 seconds — transparent)                              │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  S3 replicates across 3+ AZs synchronously.                 │    │
│  │  AZ-A down → other AZs serve requests.                     │    │
│  │  Partner Hub webhooks still readable and writable.          │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  NET IMPACT: ~30 seconds of degraded Redis caching.                │
│  Everything else continues without interruption.                    │
│  Users might see slightly slower responses (cache misses).         │
│  Zero data loss. Zero registration failures.                       │
│                                                                      │
│  ────────────────────────────────────────────────────────────────  │
│                                                                      │
│  WORSE SCENARIO: ENTIRE us-east-1 goes down                        │
│                                                                      │
│  T=0s:    All us-east-1 services unreachable.                      │
│  T=30s:   Route53 health checks: events_health_us_east → FAIL.    │
│  T=30s:   Route53 stops routing to us-east-1.                     │
│  T=30s:   ALL traffic routes to us-west-2 (auto-failover).       │
│  T=60s:   us-west-2 ECS auto-scales (2→8 tasks) to handle load.  │
│  T=60s:   DynamoDB Global Table — us-west-2 has full data.        │
│                                                                      │
│  NET IMPACT: ~60 seconds of degraded service.                      │
│  us-west-2 handles all traffic. Slightly higher latency for       │
│  East Coast users (cross-country vs local). Zero data loss.       │
│                                                                      │
│  RECOVERY: us-east-1 comes back online.                            │
│  Route53 health check passes → traffic redistributes by latency.  │
│  DynamoDB Global Table syncs any writes made during the outage.   │
│  No manual intervention required.                                  │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 5: What's NOT Redundant (and Why)

```
┌──────────────────────────────────────────────────────────────────────┐
│  GAPS IN CXP REDUNDANCY                                              │
│                                                                      │
│  ┌──────────────┬────────────────┬──────────────────────────────┐ │
│  │  Component    │  Redundancy    │  Gap / Risk                   │ │
│  ├──────────────┼────────────────┼──────────────────────────────┤ │
│  │  Redis        │  Multi-AZ      │  NOT cross-region. Each      │ │
│  │  ElastiCache  │  (within       │  region has its own Redis.   │ │
│  │              │  region)       │  Regional failover = cold    │ │
│  │              │                │  cache in other region.      │ │
│  ├──────────────┼────────────────┼──────────────────────────────┤ │
│  │  Elasticsearch│  Replica shards│  NOT cross-region. Single    │ │
│  │              │  (within       │  cluster in us-east-1.       │ │
│  │              │  cluster)      │  Region failover = no search │ │
│  │              │                │  until ES rebuilt in us-west. │ │
│  ├──────────────┼────────────────┼──────────────────────────────┤ │
│  │  Eventtia     │  External SaaS │  SINGLE PROVIDER. If Eventtia│ │
│  │              │  (not our      │  is down, registration fails. │ │
│  │              │  control)      │  No failover possible.       │ │
│  │              │                │  Mitigation: DynamoDB queue   │ │
│  │              │                │  for deferred retry.          │ │
│  ├──────────────┼────────────────┼──────────────────────────────┤ │
│  │  email-drop-  │  NONE          │  Single instance, runs       │ │
│  │  recovery     │  (localhost)   │  locally. If laptop dies,    │ │
│  │              │                │  restart on another machine.  │ │
│  │              │                │  Acceptable for an ops tool.  │ │
│  └──────────────┴────────────────┴──────────────────────────────┘ │
│                                                                      │
│  IMPROVEMENT OPPORTUNITIES:                                         │
│  1. Cross-region Redis: ElastiCache Global Datastore (adds ~ms     │
│     latency for cross-region sync, but warm cache on failover).    │
│  2. Cross-region ES: Second cluster in us-west-2 with index        │
│     replication (operational complexity vs cold-start risk).       │
│  3. Multi-provider for Eventtia: not practical (single SaaS).     │
│     Mitigation: longer DynamoDB queue TTL + larger batch reprocess.│
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Failover & Redundancy Across CXP

| Component | Pattern | Regions | AZs | Failover Time | Data Loss Risk |
|-----------|---------|---------|-----|--------------|---------------|
| **ECS Tasks** | Active-Active | 2 (us-east + us-west) | 3 per region | ~10s (ALB) / ~30s (Route53) | None (stateless) |
| **DynamoDB** | Active-Active (Global Table) | 2 | 3 per region | 0s (both write) | None (sync intra-region) |
| **DynamoDB** (cross-region) | Active-Active (multi-leader) | 2 | N/A | 0s (both active) | ~1s replication lag (LWW) |
| **Redis** | Active-Passive (Multi-AZ) | 1 | 3 | ~30s (auto-promote) | Unreplicated writes lost (cache, acceptable) |
| **Kafka/NSP3** | Active-Passive (per partition) | 1 | ISR across brokers | ~seconds (leader election) | None if acks=all |
| **S3** | Active-Active (3 AZ sync) | 1 | 3+ | 0s (transparent) | None (11 nines) |
| **Akamai CDN** | Active-Active (global) | All | 250+ PoPs | 0s (anycast) | None (cache repull) |
| **Elasticsearch** | Active-Passive (per shard) | 1 | Across data nodes | ~seconds (master election) | None (replica has data) |

---

## Common Interview Follow-ups

### Q: "Active-active vs active-passive — how do you choose?"

> "Active-active when: both nodes are doing useful work (serving traffic), and you can handle conflict resolution. Our DynamoDB Global Table is active-active because the composite key `eventId_upmId` makes conflicts nearly impossible, and last-writer-wins is acceptable for a retry queue. Active-passive when: writes must go to one place (Redis primary for cache coherence, Kafka partition leader for ordering). The key question: 'Can I safely accept writes in two places simultaneously?' If yes → active-active. If conflicts are dangerous → active-passive."

### Q: "What's your RPO and RTO?"

> "**RPO (Recovery Point Objective)** — how much data can we lose:
> - DynamoDB: ~1 second (cross-region async replication lag)
> - Redis: seconds of unreplicated cache writes (acceptable — it's cache)
> - S3: 0 (synchronous 3-AZ replication)
> - Kafka: 0 if acks=all (ISR replication)
>
> **RTO (Recovery Time Objective)** — how long until service is back:
> - Single task failure: ~10-30 seconds (ALB health check + ECS replacement)
> - Redis failover: ~30 seconds (auto-promotion)
> - Full region failure: ~60 seconds (Route53 failover + ECS auto-scale)
>
> For our event registration platform, these are well within acceptable limits — users experience at most a brief slowdown, never complete unavailability."

### Q: "What happens to in-flight requests during failover?"

> "Depends on the layer: (1) **ALB task removal:** ALB drains connections — in-flight requests complete on the dying task, new requests go to healthy tasks. Zero dropped requests. (2) **Redis failover:** In-flight Redis operations get connection errors — our try-catch returns null, the request continues without cache. (3) **Route53 failover:** DNS TTL means some clients still hit the old region for up to 5 minutes. The old ALB returns 503, client retries, eventually gets new DNS. Akamai CDN resolves DNS more frequently, so most users switch faster. (4) **Kafka leader election:** Producers buffer messages during election, deliver when new leader is available."

---
---

# Topic 38: Disaster Recovery (RPO/RTO)

> RPO is maximum acceptable data loss (how often to backup); RTO is maximum acceptable downtime (how fast to recover). Lower values = higher cost.

> **Interview Tip:** Quantify tradeoffs — "For payment data, I'd target RPO of 0 (synchronous replication) and RTO of 5 minutes (hot standby); for analytics, 24-hour RPO with backup/restore is acceptable."

---

## RPO and RTO Explained

```
┌──────────────────────────────────────────────────────────────────────┐
│  DISASTER RECOVERY (RPO / RTO)                                       │
│                                                                      │
│     RPO = 3 hours              RTO = 2 hours                       │
│  ◀──────────────────▶       ◀──────────────────▶                   │
│                                                                      │
│  Last Backup      DISASTER           Recovered                     │
│  12:00 PM    ●    3:00 PM    ●       5:00 PM    ●                 │
│              │               │                   │                  │
│                                                                      │
│  ┌────────────────────────────┐  ┌────────────────────────────┐   │
│  │  RPO (Recovery Point       │  │  RTO (Recovery Time        │   │
│  │  Objective)                │  │  Objective)                │   │
│  │                            │  │                            │   │
│  │  Maximum acceptable        │  │  Maximum acceptable        │   │
│  │  DATA LOSS.                │  │  DOWNTIME.                 │   │
│  │                            │  │                            │   │
│  │  "How much data can        │  │  "How fast must we         │   │
│  │  we afford to lose?"       │  │  recover?"                 │   │
│  │                            │  │                            │   │
│  │  Lower RPO = more          │  │  Lower RTO = hot standby,  │   │
│  │  frequent backups          │  │  automation                │   │
│  └────────────────────────────┘  └────────────────────────────┘   │
│                                                                      │
│  DR STRATEGIES (Cost vs Speed):                                     │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────────────┐       │
│  │ Backup/  │ │ Pilot    │ │ Warm     │ │  Multi-Site    │       │
│  │ Restore  │ │ Light    │ │ Standby  │ │  Active        │       │
│  │          │ │          │ │          │ │                │       │
│  │ RTO:     │ │ RTO:     │ │ RTO:     │ │ RTO:           │       │
│  │ hours-   │ │ 10s of   │ │ minutes  │ │ real-time      │       │
│  │ days     │ │ mins     │ │          │ │                │       │
│  └──────────┘ └──────────┘ └──────────┘ └────────────────┘       │
│  cheapest ◀───────────────────────────────────────▶ most expensive │
└──────────────────────────────────────────────────────────────────────┘
```

---

## The 4 DR Strategies

```
┌──────────────────────────────────────────────────────────────────────┐
│  STRATEGY 1: BACKUP / RESTORE                                        │
│  RTO: hours to days  |  RPO: hours (last backup)  |  Cost: $       │
│  ─────────────────────────────────────────────────────────────────  │
│  Take periodic snapshots. On disaster, restore to new infra.       │
│  Example: nightly S3 backup → restore to new region.               │
│  Slowest recovery but cheapest. Good for non-critical data.        │
│                                                                      │
│  STRATEGY 2: PILOT LIGHT                                            │
│  RTO: 10s of minutes  |  RPO: minutes  |  Cost: $$                 │
│  ─────────────────────────────────────────────────────────────────  │
│  Core infrastructure running in DR region (DB replicas, minimal    │
│  compute). On disaster, scale up compute. Data already there.      │
│  Example: DB replica running, ECS at 0 tasks → scale to 4.        │
│                                                                      │
│  STRATEGY 3: WARM STANDBY                                           │
│  RTO: minutes  |  RPO: seconds  |  Cost: $$$                       │
│  ─────────────────────────────────────────────────────────────────  │
│  Full infra running at reduced scale in DR region. On disaster,    │
│  scale up to full production capacity. Already serving some traffic.│
│  Example: DR region running 1 ECS task → scale to 4.              │
│                                                                      │
│  STRATEGY 4: MULTI-SITE ACTIVE (Active-Active)                     │
│  RTO: real-time  |  RPO: ~seconds  |  Cost: $$$$                   │
│  ─────────────────────────────────────────────────────────────────  │
│  Both regions serve production traffic simultaneously. On disaster, │
│  the surviving region absorbs all traffic. No "recovery" needed.   │
│  Example: CXP us-east-1 + us-west-2 both active.                  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Disaster Recovery In My CXP Projects

### CXP Uses Strategy 4: Multi-Site Active (Most Expensive, Best RTO)

Our platform runs the **most aggressive DR strategy** — active-active multi-region. Both regions serve production traffic at all times. There's no "disaster recovery" in the traditional sense — the surviving region simply absorbs the failed region's traffic.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP DR STRATEGY: MULTI-SITE ACTIVE                                      │
│                                                                          │
│  NORMAL (both regions active):                                          │
│  ┌──────────────────┐        ┌──────────────────┐                      │
│  │  us-east-1        │        │  us-west-2        │                      │
│  │  ECS: 4 tasks     │        │  ECS: 4 tasks     │                      │
│  │  ALB: active      │        │  ALB: active      │                      │
│  │  Redis: primary   │        │  Redis: primary   │                      │
│  │  DynamoDB: leader │◀─sync─▶│  DynamoDB: leader │                      │
│  │  Traffic: ~60%    │        │  Traffic: ~40%    │                      │
│  └──────────────────┘        └──────────────────┘                      │
│                                                                          │
│  DISASTER (us-east-1 down):                                             │
│  ┌──────────────────┐        ┌──────────────────┐                      │
│  │  us-east-1 ❌     │        │  us-west-2 ✅     │                      │
│  │  (completely down)│        │  ECS: 4→8 tasks  │                      │
│  │                   │        │  (auto-scaled)   │                      │
│  │  Route53: stops   │        │  Traffic: 100%   │                      │
│  │  routing here     │        │  DynamoDB: full   │                      │
│  └──────────────────┘        │  data (active-   │                      │
│                               │  active sync)    │                      │
│                               └──────────────────┘                      │
│                                                                          │
│  RTO: ~60 seconds (Route53 failover + ECS auto-scale)                  │
│  RPO: ~1 second (DynamoDB async replication lag)                       │
│  Manual intervention: ZERO                                              │
│  Data loss: ~1 second of DynamoDB writes (Global Table async lag)      │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### RPO and RTO Per CXP Component

```
┌──────────────────────────────────────────────────────────────────────┐
│  RPO / RTO PER CXP COMPONENT                                        │
│                                                                      │
│  ┌──────────────────┬──────────┬──────────┬──────────────────────┐ │
│  │  Component        │  RPO     │  RTO     │  DR Strategy          │ │
│  ├──────────────────┼──────────┼──────────┼──────────────────────┤ │
│  │  DynamoDB         │  ~1 sec  │  0 sec   │  Multi-Site Active    │ │
│  │  (Global Table)   │  (async  │  (both   │  Both regions write.  │ │
│  │                   │  repl)   │  active) │  No failover needed. │ │
│  ├──────────────────┼──────────┼──────────┼──────────────────────┤ │
│  │  S3 (Partner Hub) │  0 sec   │  0 sec   │  Multi-AZ sync.      │ │
│  │                   │  (sync   │  (3 AZ   │  11 nines durability. │ │
│  │                   │  3 AZ)   │  active) │  Transparent failover.│ │
│  ├──────────────────┼──────────┼──────────┼──────────────────────┤ │
│  │  ECS Tasks        │  0 sec   │  ~60 sec │  Multi-Site Active.   │ │
│  │  (stateless)      │  (no     │  (Route53│  Both regions run     │ │
│  │                   │  state   │  failover│  tasks. Auto-scale on │ │
│  │                   │  to lose)│  + scale)│  failover.            │ │
│  ├──────────────────┼──────────┼──────────┼──────────────────────┤ │
│  │  Redis            │  seconds │  ~30 sec │  Warm Standby (per    │ │
│  │  (ElastiCache)    │  (async  │  (auto-  │  region). Replica     │ │
│  │                   │  repl    │  promote)│  promoted to primary. │ │
│  │                   │  lag)    │          │  Cold cache cross-    │ │
│  │                   │          │          │  region.              │ │
│  ├──────────────────┼──────────┼──────────┼──────────────────────┤ │
│  │  Kafka/NSP3       │  0 sec   │  ~seconds│  Pilot Light.         │ │
│  │  (per partition)  │  (ISR    │  (leader │  ISR replicas always  │ │
│  │                   │  sync)   │  election│  running. New leader  │ │
│  │                   │          │  )       │  elected from ISR.    │ │
│  ├──────────────────┼──────────┼──────────┼──────────────────────┤ │
│  │  Elasticsearch    │  0 sec   │  ~seconds│  Warm Standby (within │ │
│  │  (per shard)      │  (sync   │  (master │  region). Replica     │ │
│  │                   │  replica)│  election│  shards promoted.    │ │
│  │                   │          │  )       │  NOT cross-region.   │ │
│  ├──────────────────┼──────────┼──────────┼──────────────────────┤ │
│  │  Secrets Manager  │  0 sec   │  0 sec   │  AWS managed.         │ │
│  │  (KMS encrypted)  │  (AWS    │  (AWS    │  Multi-AZ, replicated.│ │
│  │                   │  managed)│  managed)│  Always available.    │ │
│  ├──────────────────┼──────────┼──────────┼──────────────────────┤ │
│  │  Akamai CDN       │  0 sec   │  0 sec   │  Multi-Site Active    │ │
│  │  (250+ PoPs)      │  (cache  │  (anycast│  (global). PoP fails  │ │
│  │                   │  repull) │  routing)│  → next PoP serves.  │ │
│  ├──────────────────┼──────────┼──────────┼──────────────────────┤ │
│  │  Eventtia         │  N/A     │  N/A     │  External SaaS.       │ │
│  │  (external)       │  (their  │  (their  │  No control over DR.  │ │
│  │                   │  problem)│  problem)│  Mitigation: DynamoDB │ │
│  │                   │          │          │  queue for retry.     │ │
│  └──────────────────┴──────────┴──────────┴──────────────────────┘ │
│                                                                      │
│  AGGREGATE CXP DR:                                                  │
│  RPO: ~1 second (DynamoDB cross-region replication is the bottleneck)│
│  RTO: ~60 seconds (Route53 detection + ECS auto-scale)             │
│  Strategy: Multi-Site Active (most expensive, best recovery)       │
└──────────────────────────────────────────────────────────────────────┘
```

---

### The Automated DR Sequence — No Human Intervention

```
┌──────────────────────────────────────────────────────────────────────┐
│  DISASTER RECOVERY SEQUENCE — FULLY AUTOMATED                        │
│                                                                      │
│  T=0s:     us-east-1 AZ-A goes down (network partition).           │
│                                                                      │
│  T=0-10s:  ALB detects failed health checks on AZ-A tasks.         │
│            ALB removes AZ-A tasks. AZ-B/C tasks absorb traffic.    │
│            USER IMPACT: None (ALB handles within region).          │
│                                                                      │
│  T=10s:    ECS launches replacement tasks in AZ-B/C.               │
│            DynamoDB: 0 impact (3-AZ quorum, 2/3 still respond).   │
│            S3: 0 impact (3-AZ sync replication continues).         │
│            USER IMPACT: None.                                       │
│                                                                      │
│  T=30s:    If Redis primary was in AZ-A:                           │
│            ElastiCache promotes replica (~30s).                     │
│            During promotion: try-catch fallbacks active.           │
│            USER IMPACT: Cache misses → slightly slower (~200ms     │
│            instead of ~50ms). Service still functional.            │
│                                                                      │
│  ── SCENARIO ESCALATION: ENTIRE us-east-1 goes down ──            │
│                                                                      │
│  T=30s:    Route53 health check: /events_health_us_east/v1 → FAIL.│
│  T=60s:    Route53: "us-east-1 UNHEALTHY" → stops routing there.  │
│            ALL traffic → us-west-2.                                │
│                                                                      │
│  T=60s:    us-west-2 ECS: auto-scales 4→8 tasks (CloudWatch alarm)│
│            DynamoDB Global Table: us-west-2 has full data.         │
│            us-west-2 Redis: warm (its own cache, not us-east's).  │
│            USER IMPACT: ~10-20s of higher latency for East Coast   │
│            users (now hitting West Coast). No data loss.           │
│                                                                      │
│  T=120s:   us-west-2 running at full capacity. Stable.             │
│            All users served. No operator action needed.            │
│                                                                      │
│  ── RECOVERY: us-east-1 comes back online ──                       │
│                                                                      │
│  T=??:     us-east-1 services restart. Health checks start passing.│
│            Route53: "us-east-1 HEALTHY" → resumes routing.        │
│            DynamoDB Global Table: syncs writes made during outage.  │
│            Traffic redistributes by latency (East users → East).   │
│            Redis: cold cache, warms up over minutes.               │
│            FULL RECOVERY: automatic. Zero human action.            │
└──────────────────────────────────────────────────────────────────────┘
```

---

### What Data Could Be Lost (RPO Analysis)

```
┌──────────────────────────────────────────────────────────────────────┐
│  RPO ANALYSIS — What's at Risk During the ~1 Second Window          │
│                                                                      │
│  DynamoDB Global Table has ~1 second async replication lag.         │
│  If us-east-1 dies, writes in that 1-second window are lost.       │
│                                                                      │
│  WHAT THOSE WRITES CONTAIN:                                        │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Table: unprocessed-registration-requests                    │    │
│  │  Content: Failed registrations pending retry.                │    │
│  │                                                              │    │
│  │  IF LOST: A few failed registration retry entries disappear. │    │
│  │  IMPACT: Those users' registrations stay "unprocessed."      │    │
│  │  They would need to register again (Eventtia is the source   │    │
│  │  of truth, not DynamoDB). Eventtia's state is unaffected.   │    │
│  │                                                              │    │
│  │  SEVERITY: LOW. DynamoDB is a retry queue, not the primary   │    │
│  │  registration database. The real registration lives in       │    │
│  │  Eventtia. Losing a retry queue entry = one user might need  │    │
│  │  to re-register manually. Not catastrophic.                  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  OTHER DATA AT RISK:                                                │
│  Redis: Cache data (TTL-bounded, rebuilt from source on miss).     │
│  → Zero business impact from losing cached data.                   │
│                                                                      │
│  NOT at risk:                                                       │
│  S3: Synchronous 3-AZ replication. 0 RPO within region.           │
│  Kafka: ISR replication. 0 RPO if acks=all.                       │
│  Eventtia: External. Not affected by our infrastructure disaster.  │
│  Elasticsearch: Single-region, but replica shards have all data.   │
│                                                                      │
│  BOTTOM LINE:                                                       │
│  RPO ~1 second affects ONLY a retry queue (DynamoDB).              │
│  The source of truth (Eventtia) and audit trail (S3) have 0 RPO.  │
│  The actual user impact of losing 1 second of retry queue data    │
│  is approximately zero.                                            │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: DR Across CXP

| DR Metric | CXP Value | Industry Context |
|-----------|-----------|-----------------|
| **RPO (data loss)** | ~1 second (DynamoDB cross-region lag) | Banking: 0. E-commerce: minutes. Analytics: hours. |
| **RTO (downtime)** | ~60 seconds (Route53 + auto-scale) | Banking: minutes. E-commerce: minutes. Analytics: hours. |
| **DR strategy** | Multi-Site Active (Strategy 4) | Most expensive, best recovery. Both regions always active. |
| **Automation** | Fully automated (zero manual steps) | Route53 health checks → auto-failover → ECS auto-scale |
| **Data at risk** | Retry queue entries (non-critical) | Source of truth (Eventtia) and audit trail (S3) have 0 RPO |
| **Cost** | 2× infrastructure (both regions running) | Justified by sneaker launch traffic needs (both regions serve peak load) |

---

## Common Interview Follow-ups

### Q: "Your multi-site active DR costs 2× — how do you justify it?"

> "The cost is justified by TRAFFIC NEEDS, not just DR. During sneaker launches, we need both regions serving traffic simultaneously to handle peak load — users from East Coast hit us-east-1, West Coast/APAC hit us-west-2. The DR capability is a FREE BONUS of active-active architecture we need for performance anyway. If CXP only needed one region's worth of capacity, I'd downgrade to Warm Standby: us-west-2 running minimal tasks (1 per service), scaling up only on failover. This cuts cost by ~40% but increases RTO from 60 seconds to ~5 minutes."

### Q: "How do you TEST disaster recovery?"

> "Three approaches: (1) **Route53 health check testing:** Temporarily fail the health endpoint in one region — verify Route53 stops routing and the other region absorbs traffic. (2) **Chaos engineering:** Kill ECS tasks randomly (Netflix Chaos Monkey approach) — verify ALB removes them and replacement tasks launch. (3) **Game day:** Schedule a full regional failover test — disable us-east-1 ALB, verify us-west-2 handles all traffic, measure actual RTO. Without testing, DR is theoretical. The first real disaster will expose every untested assumption."

### Q: "What about Eventtia going down — that's your biggest risk?"

> "Eventtia is our single external dependency with no failover. If Eventtia is down: (1) Registration API returns errors to users (Eventtia 500 → our 502/503). (2) New registrations impossible. (3) Event detail pages may serve stale Akamai cache (CDN fallback). (4) Email pipeline stalls (no new webhooks from Eventtia). Mitigation: our DynamoDB unprocessed queue saves failed registration attempts. When Eventtia recovers, batch reprocessing retries them. The recovery dashboard detects the gap. Deeper mitigation would require building our own event management database — replacing Eventtia's function — which is a multi-quarter project, not a DR fix."

### Q: "How does RPO differ for the email pipeline specifically?"

> "The email pipeline RPO is effectively 0 because of S3 archival. Every Eventtia webhook is archived to S3 via the Kafka S3 sink — synchronous within the region, 11 nines durability. Even if every other system fails, the S3 archive has the complete record of every registration event. The recovery dashboard can replay any missed email by fetching the original S3 payload and re-POSTing to Rise GTS. The RPO for 'registration data' is 0. The RPO for 'email delivery' is unbounded (could be hours until someone notices and reprocesses) — but the DATA to recover from is never lost."