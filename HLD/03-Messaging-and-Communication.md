# Topic 20: Message Queue vs Event Stream

> Queues (RabbitMQ, SQS) deliver once and delete; streams (Kafka, Kinesis) retain events for replay and multiple consumers.

> **Interview Tip:** Choose based on use case — "I'd use SQS for background job processing since each task needs one worker, but Kafka for event sourcing where multiple services need the same events."

---

## The Core Difference

```
┌──────────────────────────────────────────────────────────────────────────┐
│              MESSAGE QUEUE vs EVENT STREAM                                │
│                                                                          │
│  ┌────────────────────────────────┐  ┌────────────────────────────────┐│
│  │      MESSAGE QUEUE             │  │       EVENT STREAM             ││
│  │    RabbitMQ, SQS, ActiveMQ     │  │     Kafka, Kinesis, Pulsar     ││
│  │                                │  │                                ││
│  │  ┌────────┐ ┌─┬─┬─┬─┐ ┌────┐ │  │  ┌────────┐ ┌─┬─┬─┬─┐ ┌────┐││
│  │  │Producer│▶│ │ │ │ │▶│Cons│ │  │  │Producer│▶│1│2│3│4│▶│CA ││ │
│  │  └────────┘ └─┴─┴─┴─┘ └────┘ │  │  └────────┘ └─┴─┴─┴─┘ ├────┤││
│  │              Message deleted   │  │         Immutable log  │CB ││ │
│  │              after consume     │  │         events retained├────┘││
│  │                                │  │                                ││
│  │  - One consumer per message   │  │  - Multiple consumers read     ││
│  │  - Message deleted after       │  │    same data                   ││
│  │    processing                  │  │  - Events retained (days/      ││
│  │  - Point-to-point delivery    │  │    forever)                    ││
│  │                                │  │  - Replay from any offset     ││
│  │  Use: Task queues,             │  │  Use: Event sourcing,          ││
│  │  job processing                │  │  analytics, CDC                ││
│  └────────────────────────────────┘  └────────────────────────────────┘│
│                                                                          │
│  ┌───────────────────┬──────────────────┬──────────────────────┐      │
│  │  FEATURE           │  MESSAGE QUEUE   │  EVENT STREAM        │      │
│  ├───────────────────┼──────────────────┼──────────────────────┤      │
│  │  Delivery          │  One consumer    │  Multiple consumers  │      │
│  │                    │  per message     │  per event           │      │
│  │  Retention         │  Deleted after   │  Retained (days/     │      │
│  │                    │  consume         │  forever)            │      │
│  │  Replay            │  Not possible    │  From any offset     │      │
│  │  Ordering          │  Per-queue       │  Per-partition        │      │
│  │                    │  (FIFO)          │  (FIFO)              │      │
│  │  Best For          │  Task            │  Event sourcing,     │      │
│  │                    │  distribution    │  CDC                 │      │
│  └───────────────────┴──────────────────┴──────────────────────┘      │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## How Each Works

### Message Queue (SQS)

```
Producer → [ msg3 | msg2 | msg1 ] → Consumer A

  1. Producer sends message to queue
  2. Consumer polls queue, receives message
  3. Message becomes INVISIBLE (VisibilityTimeout)
  4. Consumer processes message
  5. Consumer deletes message (acknowledge)
  6. Message is GONE from queue forever

  If consumer FAILS:
  → Message becomes visible again after VisibilityTimeout
  → Another consumer picks it up (retry)
  → After maxReceiveCount failures → Dead Letter Queue

  KEY: Each message processed by EXACTLY ONE consumer.
  No other consumer ever sees it.
```

### Event Stream (Kafka)

```
Producer → [ event1 | event2 | event3 | event4 ] → Consumer A (offset 3)
                                                   → Consumer B (offset 2)
                                                   → Consumer C (offset 4)

  1. Producer appends event to the end of the log
  2. Event gets an OFFSET number (monotonically increasing)
  3. Consumer A reads from offset 3, Consumer B from offset 2
  4. Each consumer tracks its OWN offset (independent progress)
  5. Events are NOT deleted after reading — they stay in the log
  6. Retention: configurable (7 days, 30 days, or forever)

  KEY: Multiple consumers read the SAME events independently.
  Consumer A being slow doesn't affect Consumer B.
  Any consumer can "replay" by resetting to an earlier offset.
```

---

## When to Use Each

```
┌──────────────────────────────────────────────────────────────────────┐
│  DECISION FRAMEWORK                                                  │
│                                                                      │
│  Use MESSAGE QUEUE (SQS) when:                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ✓ Each task needs exactly ONE worker (job distribution)    │    │
│  │  ✓ Message should disappear after processing                │    │
│  │  ✓ Order doesn't matter (or use FIFO queue)                 │    │
│  │  ✓ Consumers are homogeneous (any worker can handle it)     │    │
│  │  ✓ Simple at-least-once delivery with DLQ for failures      │    │
│  │                                                              │    │
│  │  Examples: Image processing, email sending, data transforms │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  Use EVENT STREAM (Kafka) when:                                     │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ✓ Multiple services need the SAME event (fan-out)          │    │
│  │  ✓ Events must be retained for replay/audit                 │    │
│  │  ✓ Event ordering matters within a partition                │    │
│  │  ✓ New consumers can "catch up" from the beginning          │    │
│  │  ✓ Event sourcing — rebuild state from event log            │    │
│  │                                                              │    │
│  │  Examples: User activity feed, CDC, microservice events     │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Message Queue vs Event Stream In My CXP Projects

### The CXP Platform Uses BOTH

This is a key interview point: our platform uses **SQS (message queue)** for point-to-point task distribution AND **NSP3/Kafka (event stream)** for fan-out to multiple consumers from the same event source.

```
┌──────────────────────────────────────────────────────────────────────────┐
│              CXP PLATFORM — MESSAGING ARCHITECTURE                        │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  SOURCE: Eventtia (User registers for event)                      │  │
│  │                                                                   │  │
│  │  Eventtia sends webhook → Partner Hub                            │  │
│  │                              │                                    │  │
│  │                              ▼                                    │  │
│  │                    ┌─────────────────┐                           │  │
│  │                    │  NSP3 / KAFKA   │  ← EVENT STREAM          │  │
│  │                    │  (notification  │  One event, multiple      │  │
│  │                    │   stream)       │  consumers                │  │
│  │                    └────┬──┬──┬──────┘                           │  │
│  │                 ┌───────┘  │  └───────┐                          │  │
│  │                 ▼          ▼          ▼                           │  │
│  │          ┌──────────┐┌──────────┐┌──────────┐                   │  │
│  │          │HTTP Push ││ S3 Sink  ││ Purge    │                   │  │
│  │          │Sink →    ││ → Partner││ Sink →   │                   │  │
│  │          │Rise GTS  ││   Hub S3 ││ cxp-     │                   │  │
│  │          │(transform)│(archival) ││ events   │                   │  │
│  │          └─────┬────┘└──────────┘│(cache    │                   │  │
│  │                │                  │ purge)   │                   │  │
│  │                ▼                  └──────────┘                   │  │
│  │          ┌──────────┐                                            │  │
│  │          │  Rise GTS │                                            │  │
│  │          │  writes   │                                            │  │
│  │          │  output   │                                            │  │
│  │          │  to S3    │                                            │  │
│  │          └─────┬─────┘                                            │  │
│  │                │ S3 event notification                            │  │
│  │                ▼                                                  │  │
│  │          ┌──────────┐                                            │  │
│  │          │  SQS     │  ← MESSAGE QUEUE                           │  │
│  │          │  (data-  │  One message, one consumer                 │  │
│  │          │  ingest- │  Delete after processing                   │  │
│  │          │  async)  │                                            │  │
│  │          └─────┬────┘                                            │  │
│  │                │                                                  │  │
│  │                ▼                                                  │  │
│  │          ┌──────────┐                                            │  │
│  │          │  Rise GTS │  @SqsListener                             │  │
│  │          │  (async   │  Process S3 object                        │  │
│  │          │  ingest)  │  Publish to NCP/NSPv2                     │  │
│  │          └──────────┘                                            │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: NSP3/Kafka — Event Stream (One Event, Three Consumers)

**Where:** `cxp-infrastructure` → `terraform/nsp3/modules/nsp3Sink/`
**Stream:** `partnerhub_notification_stream` (Eventtia registration events)
**Consumers:** 3 independent sinks consuming the SAME stream

```
┌──────────────────────────────────────────────────────────────────────┐
│  NSP3 Kafka — Fan-Out Pattern (Event Stream)                         │
│                                                                      │
│  ONE Kafka stream: partnerhub_notification_stream                   │
│  THREE independent consumers (Kafka Connect sinks):                 │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  CONSUMER 1: HTTP Push Sink → Rise GTS                        │  │
│  │  Name: cxp-eventtia-event-p4-sink                             │  │
│  │  Filter: action == 'general' (ad-hoc events)                  │  │
│  │  Action: POST to /data/transform/v1                           │  │
│  │  Content-Type: application/vnd.nike.eventtia-ad-hoc-events    │  │
│  │                                                                │  │
│  │  CONSUMER 2: HTTP Push Sink → Rise GTS                        │  │
│  │  Name: cxp-eventtia-event-p1-3-sink                           │  │
│  │  Filter: action IN ('confirmed', 'cancel', 'pre_event', etc.) │  │
│  │  Action: POST to /data/transform/v1                           │  │
│  │  Content-Type: application/vnd.nike.eventtia-events            │  │
│  │                                                                │  │
│  │  CONSUMER 3: S3 Sink → Partner Hub                            │  │
│  │  Name: cxp-eventtia-event-s3-sink                             │  │
│  │  Filter: none (all events archived)                           │  │
│  │  Action: Write JSON to S3 bucket                              │  │
│  │  Format: JSON, batch_count=1, batch_frequency=5               │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  PLUS: Purge sink on a DIFFERENT stream (metadata stream):         │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  CONSUMER 4: HTTP Push Sink → cxp-events /purge-cache         │  │
│  │  Name: cxp-eventtia-purge-sink                                │  │
│  │  Stream: partnerhub_metadata_stream                           │  │
│  │  Filter: data_type == 'Event'                                 │  │
│  │  Action: POST to /community/events/v1/purge-cache             │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  WHY EVENT STREAM (not message queue) HERE:                        │
│  - ONE registration event needs to reach MULTIPLE systems:         │
│    Rise GTS (transform + email), S3 (audit trail), cache (purge)  │
│  - Each consumer processes independently at its own pace           │
│  - If Rise GTS is slow, S3 archival isn't blocked                 │
│  - If a NEW consumer is added (e.g., analytics), it can replay    │
│    all historical events from the stream                           │
│  - With SQS, we'd need 3 separate queues + 3 separate             │
│    webhook configs in Eventtia — tightly coupled                   │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual Terraform:**

```hcl
// nsp3Sink.tf — THREE sinks consuming the SAME stream
// This is the event stream pattern: fan-out to multiple consumers

// Consumer 1: HTTP Push to Rise GTS (ad-hoc events)
resource "nsp3_sink" "cxp-eventtia-event-p4-sink" {
  streams = [var.nsp3_partnerhub_notification_stream_id]  // SAME stream
  filter  = { condition : "$[?(@.action == 'general')]" }
  config  = {
    config_type  = "KafkaConnectHTTPPushSinkV2"
    request_url  = var.cxp_gts_url[var.environment]       // Rise GTS
  }
}

// Consumer 2: S3 archival (ALL events, no filter)
resource "nsp3_sink" "cxp-eventtia-event-s3-sink" {
  streams = [var.nsp3_partnerhub_notification_stream_id]  // SAME stream
  config  = {
    config_type  = "KafkaConnectS3Sink"
    bucket       = var.bucket_name[var.environment]        // Partner Hub S3
    format_type  = "JSON"
    batch_count  = 1
  }
}

// Consumer 3: Cache purge (metadata stream, filtered)
resource "nsp3_sink" "cxp-eventtia-purge-sink" {
  streams = [var.nsp3_partnerhub_metadata_stream_id]
  filter  = { condition : "$[?(@.data_type == 'Event')]" }
  config  = {
    config_type  = "KafkaConnectHTTPPushSinkV2"
    request_url  = var.cxp_akamai_cache_purge_url[var.environment]
  }
}
```

**Interview answer:**
> "Our Eventtia registration events flow through an NSP3 Kafka stream with three independent consumers reading the same data. Consumer 1 (HTTP Push sink) sends registrations to Rise GTS for email transformation. Consumer 2 (S3 sink) archives every event to Partner Hub for audit — this is our source of truth for investigations. Consumer 3 (Purge sink) triggers Akamai cache invalidation. Each consumer processes at its own pace — if Rise GTS is slow, S3 archival isn't blocked. If we add a fourth consumer (analytics), it can replay all historical events. With SQS, we'd need three separate queues and three webhook configs — tightly coupled and no replay capability."

---

### Example 2: SQS — Message Queue (One Message, One Worker)

**Service:** `rise-generic-transform-service`
**Queue:** `store-integration-data-ingest-async`
**Pattern:** S3 event notification → SQS → single consumer processes → delete

```
┌──────────────────────────────────────────────────────────────────────┐
│  SQS — Point-to-Point Task Distribution                              │
│                                                                      │
│  S3 new object → SQS notification → Rise GTS @SqsListener          │
│                                                                      │
│  ┌──────────┐    S3 event    ┌──────────────────┐                  │
│  │  S3      │───notification▶│  SQS             │                  │
│  │  (new    │                │  data-ingest-    │                  │
│  │  webhook │                │  async           │                  │
│  │  file)   │                └────────┬─────────┘                  │
│  └──────────┘                         │                             │
│                                       │ @SqsListener               │
│                                       ▼                             │
│                               ┌──────────────┐                     │
│                               │  Rise GTS    │                     │
│                               │  Task A      │  ← ONE task         │
│                               │              │     processes       │
│                               │  1. Parse S3 │     this message    │
│                               │     event    │                     │
│                               │  2. GET S3   │                     │
│                               │     object   │                     │
│                               │  3. Transform│                     │
│                               │  4. Publish  │                     │
│                               │     to NCP   │                     │
│                               │  5. Delete   │                     │
│                               │     SQS msg  │                     │
│                               └──────────────┘                     │
│                                                                      │
│  WHY MESSAGE QUEUE (not event stream) HERE:                        │
│  - Each S3 object needs to be processed EXACTLY ONCE               │
│  - No other service needs this S3 notification                     │
│  - Message should DISAPPEAR after successful transform             │
│  - Failed messages → DLQ for investigation                         │
│  - No replay needed — if we need to reprocess, we re-upload to S3  │
│  - Simple: producer (S3 notification) doesn't know about consumer  │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// TransformationService.java — SQS consumer
@SqsListener(value = "${sqs.queue}",
             deletionPolicy = SqsMessageDeletionPolicy.ON_SUCCESS)
public void ingestFromSQS(final String sqsEventString) {
    // Parse S3EventNotification (which S3 file was created)
    S3EventNotification s3Event = JsonUtils.readValue(sqsEventString, S3EventNotification.class);

    s3Event.getRecords().stream()
        .filter(Objects::nonNull)
        .map(S3EventNotification.S3EventNotificationRecord::getS3)
        .forEach(s3Entity -> {
            String bucket = s3Entity.getBucket().getName();
            String key = s3Entity.getObject().getKey();
            getPayLoadAndProcess(bucket, key);  // read from S3, transform, publish
        });
}
// deletionPolicy = ON_SUCCESS:
// If processing succeeds → SQS deletes the message (GONE forever)
// If processing throws exception → message stays, becomes visible
//   after VisibilityTimeout → retried up to maxReceiveCount → DLQ
```

**SQS queue configuration (CloudFormation):**

```yaml
# VisibilityTimeout: 3600s (1 hour) — message hidden while processing
# maxReceiveCount: 3 — after 3 failures, move to DLQ
# MessageRetentionPeriod: 345600s (4 days) — auto-delete if never processed
CreatePipelineAptosQueue:
  Type: AWS::SQS::Queue
  Properties:
    VisibilityTimeout: 3600
    MessageRetentionPeriod: 345600
    RedrivePolicy:
      deadLetterTargetArn: !GetAtt CreatePipelineAptosQueueDLQ.Arn
      maxReceiveCount: 3
```

**Interview answer:**
> "For S3 event processing, we use SQS because each S3 object needs exactly one worker to transform it. The `@SqsListener` with `ON_SUCCESS` deletion policy means the message is deleted only after successful processing — if the worker crashes mid-transform, the message reappears after VisibilityTimeout (1 hour) for another worker to retry. After 3 failures, it moves to the DLQ for investigation. We don't need Kafka here because no other service needs this S3 notification, there's no replay requirement, and task distribution semantics are simpler with SQS."

---

### Example 3: NSPv2 — Publishing to Kafka via REST API

**Service:** `rise-generic-transform-service`
**Pattern:** After transforming data, Rise GTS publishes to downstream Kafka topics via REST

```
┌──────────────────────────────────────────────────────────────────────┐
│  NSPv2 Publish — Kafka REST API                                      │
│                                                                      │
│  Rise GTS transforms registration data → publishes to Kafka topic  │
│                                                                      │
│  HTTP POST to Kafka REST endpoint:                                  │
│  URL: https://<nrtd-cluster>.ingest.na.nrtd.platforms.nike.com/rest │
│                                                                      │
│  Headers:                                                           │
│    Content-Type: application/json                                   │
│    Accept: application/vnd.kafka.v2+json                            │
│    Authorization: Bearer <OSCAR token>                              │
│                                                                      │
│  Body (Kafka REST "records" format):                                │
│  {                                                                  │
│    "records": [                                                     │
│      { "value": { ...transformed registration data... } }          │
│    ]                                                                │
│  }                                                                  │
│                                                                      │
│  WHY REST API TO KAFKA (not native Kafka producer):                 │
│  - Nike's Kafka infrastructure (NSPv2) exposes REST ingestion      │
│  - No Kafka client library dependency in Java app                   │
│  - OSCAR token-based auth (same as other Nike APIs)                 │
│  - Rise GTS is a generic transform service — it publishes to       │
│    many different target types (HTTP, S3, NSPv2) based on config   │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// PublishToNSPv2.java — Kafka REST format
public void publish(Map<String, Object> transformedData, TransformationMetadata transformation) {
    this.setNspV2Target(true);
    HttpClient.INSTANCE.get().post(this,
        Map.of("records", List.of(Map.of("value", transformedData))));
    // POST body: { "records": [{ "value": {...} }] }
}

// OscarTokenCreator.java — Kafka-specific Accept headers
if (request.isNspV2Target()) {
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.addAll(HttpHeaders.ACCEPT, List.of(
        "application/vnd.kafka.v2+json",
        "application/vnd.kafka+json",
        "application/json"
    ));
}
```

---

### Example 4: Spring ApplicationEventPublisher — In-Process Pub/Sub

**Service:** `cxp-events`
**Pattern:** Internal event for cache refresh (not a distributed message)

```
┌──────────────────────────────────────────────────────────────────────┐
│  Spring Events — In-Process Pub/Sub (Not Distributed)                │
│                                                                      │
│  CXPConfigService (publisher):                                      │
│  ┌────────────────────────────────────────────────────┐            │
│  │  @Scheduled: read config from Secrets Manager       │            │
│  │  If refreshCache == "true":                         │            │
│  │    applicationEventPublisher.publishEvent(           │            │
│  │      new CacheRefreshEvent("Refreshing cache")      │            │
│  │    );                                                │            │
│  └────────────────────────────────────────────────────┘            │
│                        │                                            │
│                        ▼                                            │
│  EventListener (subscriber):                                       │
│  ┌────────────────────────────────────────────────────┐            │
│  │  @EventListener(CacheRefreshEvent.class)            │            │
│  │  public void cacheRefreshEvent() {                  │            │
│  │    customFieldService.getAllowedCountriesFromSecret();│            │
│  │    customFieldService.loadCustomFieldDetails();      │            │
│  │  }                                                   │            │
│  └────────────────────────────────────────────────────┘            │
│                                                                      │
│  SCOPE: Within ONE JVM only. NOT distributed across ECS tasks.      │
│  Each task receives its own CacheRefreshEvent independently.        │
│  This is the Spring equivalent of the Observer pattern.             │
│                                                                      │
│  For DISTRIBUTED events across tasks: would need Redis Pub/Sub,     │
│  Kafka, or SQS.                                                    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Side-by-Side: SQS vs Kafka in CXP

| Dimension | SQS (Rise GTS ingest) | NSP3/Kafka (Eventtia events) |
|-----------|----------------------|------------------------------|
| **Purpose** | Process S3 objects one at a time | Fan out registration events to 3+ systems |
| **Consumers** | ONE consumer per message | THREE consumers per event (GTS, S3, Purge) |
| **After processing** | Message DELETED | Event RETAINED (configurable retention) |
| **Failure handling** | VisibilityTimeout + DLQ (maxReceiveCount=3) | Consumer retries from same offset; DLQ per sink |
| **Replay** | Not possible (message gone) | Replay from any offset (new consumer catches up) |
| **Ordering** | Per-queue (standard) or strict (FIFO) | Per-partition (events for same key stay ordered) |
| **Adding new consumer** | Need new queue + new S3 notification | Just add a new sink — reads same stream |
| **Config** | CloudFormation SQS Queue | Terraform nsp3_sink resource |

---

## The Complete Event Flow — Queue AND Stream Together

```
┌──────────────────────────────────────────────────────────────────────┐
│  END-TO-END: User Registers → Email Delivered                        │
│                                                                      │
│  User clicks "Register"                                             │
│       │                                                              │
│       ▼                                                              │
│  cxp-event-registration → Eventtia API (sync HTTP)                  │
│       │                                                              │
│       ▼                                                              │
│  Eventtia stores registration → sends webhook to Partner Hub        │
│       │                                                              │
│       ▼                                                              │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  KAFKA STREAM (NSP3 partnerhub_notification_stream)          │   │
│  │                                                              │   │
│  │  Event: { action: "confirmed", attendee: { upm_id: "..." }, │   │
│  │          event: { id: 73067, name: "Nike Run Portland" } }  │   │
│  │                                                              │   │
│  │  Consumer 1 ─── HTTP Push ──▶ Rise GTS /data/transform/v1  │   │
│  │  Consumer 2 ─── S3 Sink ───▶ Partner Hub S3 (audit/replay) │   │
│  │  Consumer 3 ─── HTTP Push ──▶ cxp-events /purge-cache      │   │
│  └─────────────────────────────────────────────────────────────┘   │
│       │ (Consumer 1 path continues)                                 │
│       ▼                                                              │
│  Rise GTS transforms → publishes to NCP Ingest API (HTTP)          │
│       │                                                              │
│       ▼                                                              │
│  NCP triggers CRS Email Rendering → SendGrid → User's inbox        │
│                                                                      │
│  ALSO: Rise GTS may write transformed output to S3                  │
│       │                                                              │
│       ▼                                                              │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  SQS (store-integration-data-ingest-async)                    │   │
│  │                                                              │   │
│  │  S3 event notification: { bucket: "...", key: "..." }        │   │
│  │                                                              │   │
│  │  ONE consumer: Rise GTS @SqsListener                        │   │
│  │  → Read S3 object → Transform → Publish to NSPv2/Kafka     │   │
│  │  → Delete SQS message on success                            │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  KAFKA handles the fan-out (1 event → 3 consumers).                │
│  SQS handles the task distribution (1 S3 object → 1 worker).      │
│  Both in the SAME pipeline, solving different problems.            │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Messaging Patterns Across CXP

| Component | Type | Technology | Pattern | Why |
|-----------|------|-----------|---------|-----|
| **Eventtia → CXP** | Event Stream | NSP3/Kafka | Fan-out to 3 sinks | One event, multiple consumers (GTS + S3 + Purge) |
| **S3 → Rise GTS** | Message Queue | SQS | Point-to-point task | Each S3 object needs exactly one worker |
| **Rise GTS → downstream** | Event Stream | NSPv2/Kafka REST | Publish to topic | Downstream consumers process independently |
| **Eventtia metadata → cache** | Event Stream | NSP3/Kafka | Filtered HTTP Push | Purge sink reacts to event updates |
| **Config → cache refresh** | In-process pub/sub | Spring Events | Observer pattern | JVM-local, not distributed |
| **Email drop recovery** | HTTP reprocess | RISE API | Manual trigger | Human initiates via dashboard |

---

## Common Interview Follow-ups

### Q: "Why not use Kafka for everything instead of SQS?"

> "Kafka is more powerful but more complex. For the S3 → Rise GTS ingest path, SQS is simpler because: (1) we need exactly-once-processing semantics — SQS `ON_SUCCESS` deletion gives us this natively, while Kafka requires consumer offset management, (2) only ONE consumer needs this message — Kafka's fan-out advantage is wasted, (3) SQS is serverless — no brokers to manage, no partition count to tune, (4) the DLQ pattern (3 retries then quarantine) is built in. Kafka would work, but SQS is the right tool for a simple point-to-point job queue."

### Q: "What happens if a Kafka consumer falls behind?"

> "Kafka retains events for the configured retention period. A slow consumer simply has a growing lag (difference between latest offset and consumer's current offset). When the consumer catches up, it processes all missed events in order. In our NSP3 setup, if Rise GTS goes down for an hour, the registration events accumulate in the stream. When GTS comes back, the HTTP Push sink replays all missed events — users eventually get their confirmation emails. The S3 sink continues independently, so the audit trail is never interrupted."

### Q: "How do you add a new consumer to the Eventtia event stream?"

> "Just add a new `nsp3_sink` resource in Terraform. For example, if analytics needs registration events:
> ```hcl
> resource 'nsp3_sink' 'cxp-analytics-sink' {
>   streams = [var.nsp3_partnerhub_notification_stream_id]
>   config = { config_type = 'KafkaConnectHTTPPushSinkV2', request_url = 'https://analytics-api...' }
> }
> ```
> The new sink reads from the same stream independently — no changes to Eventtia, no changes to existing sinks. It can even replay historical events from the stream's retention window. With SQS, adding a new consumer would require a new queue AND reconfiguring Eventtia to send to both queues — tightly coupled."

### Q: "How does the email-drop-recovery dashboard fit into this messaging architecture?"

> "The recovery dashboard is the **compensating mechanism** for when this messaging pipeline fails. It queries Athena (Consumer 2's S3 output — the audit trail) to find what Eventtia sent, and Splunk to find what actually reached the email provider. The gap = dropped emails. It then manually re-triggers delivery by fetching the original S3 payload and re-POSTing it to Rise GTS. This is essentially a human-initiated message replay — something that's trivial with an event stream (data is retained in S3) but impossible with a message queue (SQS messages are deleted after processing)."

---
---

# Topic 21: Synchronous vs Asynchronous Communication

> Synchronous blocks the caller until response arrives; asynchronous fires-and-forgets via message queues for better resilience and throughput.

> **Interview Tip:** Match to use case — "I'd use synchronous REST for user-facing reads needing immediate response, but async messaging for order processing where we can tolerate eventual consistency."

---

## The Core Difference

```
┌──────────────────────────────────────────────────────────────────────┐
│                                                                      │
│  SYNCHRONOUS                          ASYNCHRONOUS                  │
│  Caller waits for response            Fire and forget / callback    │
│  (blocking)                           (non-blocking)                │
│                                                                      │
│  Service A ──1.Request──▶ Service B   Service A ──1.Send──▶ Queue  │
│       │                      │              │                │      │
│       │   WAIT...            │         Continue              │      │
│       │                      │         working!          2.Poll     │
│       │                  Process                             │      │
│       │                      │                               ▼      │
│  ◀────── 2.Response ────────┘                          Service B   │
│                                                             │      │
│  Total time = Request +                              3.Callback    │
│  Processing + Response                               (optional)    │
│                                                                      │
│  [+] Simple to understand         [+] Non-blocking, better          │
│      and debug                        throughput                    │
│  [+] Immediate consistency        [+] Resilient to downstream      │
│                                       failures                     │
│  [-] Caller blocked waiting       [-] Complex error handling       │
│  [-] Cascading failures           [-] Eventual consistency         │
│      if B is slow                                                   │
└──────────────────────────────────────────────────────────────────────┘
```

---

## When to Use Each

```
┌──────────────────────────────────────────────────────────────────────┐
│  DECISION FRAMEWORK                                                  │
│                                                                      │
│  Use SYNCHRONOUS when:                                              │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ✓ User is WAITING for the response (API response on screen)│    │
│  │  ✓ You need the result to proceed (seat check before reg)  │    │
│  │  ✓ Strong consistency required (read-after-write)           │    │
│  │  ✓ Simple request-response (GET event details)              │    │
│  │  ✓ Error must be shown to the user immediately              │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  Use ASYNCHRONOUS when:                                             │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  ✓ User doesn't need to wait (email sent later, not now)   │    │
│  │  ✓ Operation is slow (transform, render, external API)      │    │
│  │  ✓ Multiple systems need to react (fan-out to N consumers)  │    │
│  │  ✓ Downstream failures shouldn't block the caller           │    │
│  │  ✓ Eventual consistency is acceptable                       │    │
│  │  ✓ Workload is spiky (queue absorbs bursts)                 │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  HYBRID (most common in real systems):                              │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Synchronous for the USER-FACING call                       │    │
│  │  → return "Registration successful" immediately             │    │
│  │                                                              │    │
│  │  Asynchronous for BACKGROUND work triggered by that call    │    │
│  │  → send confirmation email, update analytics, purge cache   │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Sync vs Async In My CXP Projects

### The CXP Platform — Hybrid Architecture

Our registration pipeline is the perfect example: the **user-facing registration is synchronous** (user waits for "Registered!"), but everything downstream — email, archival, cache purge — is **asynchronous**.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — SYNC vs ASYNC MAP                                        │
│                                                                          │
│  SYNCHRONOUS (user-facing, blocking):                                   │
│  ──────────────────────────────────                                     │
│  ┌──────┐ REST  ┌───────────┐ REST  ┌──────────┐                      │
│  │ User │──────▶│cxp-event- │──────▶│ Eventtia │                      │
│  │      │◀──────│registration│◀──────│   API    │                      │
│  └──────┘ 200OK └───────────┘ 200/422└──────────┘                      │
│  User WAITS until Eventtia responds (~100-500ms)                        │
│                                                                          │
│  ┌──────┐ REST  ┌───────────┐ REST  ┌──────────┐                      │
│  │ User │──────▶│cxp-events │──────▶│ Eventtia │                      │
│  │      │◀──────│           │◀──────│   API    │                      │
│  └──────┘ JSON  └───────────┘ JSON  └──────────┘                      │
│  User WAITS for event details page (~50-200ms)                          │
│                                                                          │
│  ┌──────┐ REST  ┌───────────┐ query ┌──────────┐                      │
│  │ User │──────▶│expviews-  │──────▶│ Elastic- │                      │
│  │      │◀──────│nikeapp    │◀──────│ search   │                      │
│  └──────┘ JSON  └───────────┘ results└──────────┘                     │
│  User WAITS for search results (~30-100ms)                              │
│                                                                          │
│  ASYNCHRONOUS (background, non-blocking):                               │
│  ────────────────────────────────────                                   │
│  Registration succeeds → triggers ASYNC pipeline:                       │
│                                                                          │
│  Eventtia ──webhook──▶ Kafka/NSP3 ──▶ Rise GTS ──▶ NCP ──▶ Email     │
│  (seconds to minutes, user doesn't wait)                                │
│                                                                          │
│  Eventtia ──webhook──▶ Kafka/NSP3 ──▶ S3 Partner Hub (archival)       │
│  (fire-and-forget, no user interaction)                                 │
│                                                                          │
│  Eventtia ──webhook──▶ Kafka/NSP3 ──▶ cxp-events /purge-cache        │
│  (async cache invalidation, user never sees this)                       │
│                                                                          │
│  Redis cache write ──▶ CompletableFuture.runAsync()                    │
│  (fire-and-forget after successful registration)                        │
│                                                                          │
│  Akamai purge ──▶ CompletableFuture.runAsync() with @Retryable        │
│  (async, non-blocking to the user request)                              │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: Registration — Sync for User, Async for Everything Else

**Service:** `cxp-event-registration`
**Pattern:** Synchronous REST call to Eventtia (user waits), then async fire-and-forget for cache, LAMS, and email pipeline

```
┌──────────────────────────────────────────────────────────────────────┐
│  Registration Flow — The Hybrid                                      │
│                                                                      │
│  T=0ms   User clicks "Register"                                    │
│          │                                                          │
│          ▼  SYNC: User's thread is blocked                          │
│  T=0ms   cxp-event-registration receives request                   │
│          │                                                          │
│          ├──▶ SYNC: Check Redis idempotency cache (~1ms)            │
│          │                                                          │
│          ├──▶ ASYNC: CompletableFuture.runAsync()                   │
│          │    → LAMS registration (legal waiver — fire-and-forget)  │
│          │    User doesn't wait for this.                           │
│          │                                                          │
│          ▼  SYNC: Call Eventtia /attendees/register (~200ms)        │
│  T=200ms Eventtia returns 200 (success)                             │
│          │                                                          │
│          ├──▶ ASYNC: CompletableFuture.runAsync()                   │
│          │    → Redis SET success response (cache — fire-and-forget)│
│          │                                                          │
│          ▼  SYNC: Return 200 to user                                │
│  T=210ms User sees "Registered!" ← total sync latency ~210ms       │
│                                                                      │
│  T=1s    ASYNC (user already gone):                                 │
│          Eventtia webhook → Kafka → Rise GTS → NCP → Email         │
│          (minutes later, user receives confirmation email)          │
│                                                                      │
│  T=1s    ASYNC: Kafka purge sink → cxp-events /purge-cache         │
│          → Akamai edge cache invalidated                            │
│                                                                      │
│  T=1s    ASYNC: Kafka S3 sink → Partner Hub archival                │
│          → Webhook stored for future investigation                  │
│                                                                      │
│  SYNC BUDGET:  ~210ms (what the user waits for)                     │
│  ASYNC WORK:   ~minutes (what happens in the background)            │
│  RATIO:        ~1:100 (sync is <1% of total pipeline work)         │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — sync and async in the same method:**

```java
// EventRegistrationService.java
public Mono<EventRegistrationResponse> registerEventUser(...) {
    String idempotencyKey = upmId + "_" + eventId;

    // SYNC: Check Redis cache (blocking, ~1ms)
    Object cachedResponse = registrationCacheService
        .getRegistrationRequestIdempotencyValueFromCache(idempotencyKey);
    if (!Objects.isNull(cachedResponse)) {
        return Mono.just(cachedResponse);  // return cached result immediately
    }

    // ASYNC: LAMS legal waiver (fire-and-forget, user doesn't wait)
    CompletableFuture.runAsync(() ->
        lamsRegistration(upmId, eventId, eventRegistrationRequest, consumerJwt));

    // SYNC: Call Eventtia API (user waits for this, ~200ms)
    return eventRegistration(context, eventId, eventRegistrationRequest, upmId, idempotencyKey);
}

// Inside eventRegistration(), on success:
// ASYNC: Cache the success response (fire-and-forget)
CompletableFuture.runAsync(() ->
    registrationCacheService.addRegistrationRequestSuccessResponseToCache(
        idempotencyKey, eventRegistrationResponse));

// ASYNC: Purge Akamai seat cache if capacity error
CompletableFuture.runAsync(() ->
    akamaiCacheService.seatsAPICachePurging(eventId));

return eventRegistrationResponse;  // SYNC: return to user NOW
```

**Interview answer:**
> "Our registration is a hybrid: the user-facing call is synchronous — the user waits ~210ms for Eventtia to confirm the registration. But everything else is async. LAMS legal waiver registration fires via `CompletableFuture.runAsync()` before the Eventtia call — user doesn't wait for it. After Eventtia succeeds, we return immediately to the user, then asynchronously write to Redis cache and trigger Akamai purge. The entire email pipeline (Kafka → Rise GTS → NCP → SendGrid) runs minutes later. The user's perceived latency is ~210ms, but the total pipeline does minutes of work in the background."

---

### Example 2: The Email Pipeline — Fully Asynchronous (6 Services)

**Pipeline:** Eventtia → Kafka → Rise GTS → NCP → CRS → SendGrid
**Pattern:** Each hop is async — no service blocks waiting for the next

```
┌──────────────────────────────────────────────────────────────────────┐
│  Email Pipeline — Fully Async Chain                                  │
│                                                                      │
│  ┌──────────┐ webhook  ┌──────┐ push  ┌─────────┐ HTTP  ┌───────┐│
│  │ Eventtia │────────▶│Kafka │──────▶│Rise GTS │──────▶│ NCP   ││
│  │          │ async    │NSP3 │ async │         │ sync  │Ingest ││
│  └──────────┘ (fire&  └──────┘(push  └─────────┘(HTTP  └───┬───┘│
│               forget)          sink)              POST)     │    │
│                                                             │    │
│  ┌──────────┐                                          async│    │
│  │SendGrid  │◀─────── CRS Email Rendering ◀────────────────┘    │
│  │ (Email)  │  send    (template rendering)  trigger             │
│  └──────────┘                                                    │
│                                                                      │
│  EVERY HOP IS DECOUPLED:                                            │
│  - Eventtia doesn't know about Kafka (Partner Hub handles it)      │
│  - Kafka doesn't know about Rise GTS (sink config handles routing) │
│  - Rise GTS doesn't know about CRS (NCP routes the notification)  │
│  - If NCP is slow → Kafka buffers messages (no upstream impact)    │
│  - If Rise GTS is down → messages accumulate in Kafka, replay later│
│                                                                      │
│  THIS IS WHY THE EMAIL DROP HAPPENS:                                │
│  The async pipeline means NCP might run BEFORE MemberHub has        │
│  the user's email. Sync would guarantee order, but at the cost     │
│  of blocking the entire pipeline until MemberHub syncs (~30 seconds)│
│                                                                      │
│  TRADEOFF:                                                          │
│  SYNC pipeline: Registration takes 30+ seconds, email guaranteed   │
│  ASYNC pipeline: Registration takes <1 second, ~2-5% email drops   │
│  We chose ASYNC + compensating mechanism (recovery dashboard)      │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "Our email pipeline is fully asynchronous across 6 services. Eventtia fires a webhook (fire-and-forget), Kafka buffers and fan-outs, Rise GTS transforms, NCP triggers rendering, CRS renders, SendGrid delivers. No service blocks waiting for the next. This gives us resilience — if Rise GTS is down, Kafka buffers messages and replays when it recovers. But the async nature is exactly why emails get dropped: NCP runs before MemberHub syncs the user's email. A synchronous pipeline would guarantee email delivery but make registration take 30+ seconds. We chose async + a recovery dashboard to detect and fix the ~2-5% drops."

---

### Example 3: CompletableFuture.runAsync — Fire-and-Forget Within a Service

**Service:** `cxp-event-registration`, `cxp-events`
**Pattern:** Non-critical background tasks decoupled from the user response

```
┌──────────────────────────────────────────────────────────────────────┐
│  Fire-and-Forget Async Within a Service                              │
│                                                                      │
│  USER REQUEST THREAD:                                               │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  1. Validate JWT token (sync)                               │    │
│  │  2. Check Redis idempotency (sync)                          │    │
│  │  3. Call Eventtia API (sync — user waits)                   │    │
│  │  4. Return 200 to user ← response sent HERE                │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  BACKGROUND THREADS (after response is sent):                       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  CompletableFuture.runAsync(() ->                           │    │
│  │    registrationCacheService.addSuccessResponseToCache(...))  │    │
│  │  → Runs on ForkJoinPool.commonPool()                        │    │
│  │  → If fails: user already got success. Cache miss next time.│    │
│  │                                                              │    │
│  │  CompletableFuture.runAsync(() ->                           │    │
│  │    lamsRegistration(upmId, eventId, ...))                   │    │
│  │  → Legal waiver registration                                │    │
│  │  → If fails: logged but doesn't affect user registration   │    │
│  │                                                              │    │
│  │  CompletableFuture.runAsync(() ->                           │    │
│  │    akamaiCacheService.seatsAPICachePurging(eventId))        │    │
│  │  → CDN cache invalidation                                   │    │
│  │  → If fails: @Retryable catches it. Cache expires via TTL. │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  WHY ASYNC FOR THESE:                                               │
│  - None of these affect the user's registration success             │
│  - All have fallback/retry if they fail                             │
│  - Removing them from sync path saves ~5-10ms per request          │
│  - At 10,000 registrations: saves 50-100 seconds of thread time    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 4: Event Detail Page — Synchronous Chain

**Service:** `cxp-events` → Eventtia → Response (with CDN cache)
**Pattern:** Fully synchronous — user is waiting for the page

```
┌──────────────────────────────────────────────────────────────────────┐
│  Event Detail — Synchronous (User Waits)                             │
│                                                                      │
│  User visits: nike.com/experiences/event/73067                      │
│       │                                                              │
│       ▼                                                              │
│  Akamai CDN checks edge cache                                      │
│       │                                                              │
│       ├── CACHE HIT → return cached page (15ms) ← 95% of requests │
│       │                                                              │
│       └── CACHE MISS → forward to origin:                           │
│            │                                                         │
│            ▼  SYNC                                                  │
│       cxp-events → Eventtia API (sync, ~100ms)                     │
│            │                                                         │
│            ▼  SYNC                                                  │
│       cxp-events → Bodega translations (sync, ~20ms)               │
│            │                                                         │
│            ▼  SYNC                                                  │
│       Build response + Akamai headers → return to user (~200ms)    │
│                                                                      │
│  WHY SYNC HERE (not async):                                        │
│  - User is STARING AT A BLANK SCREEN — they need the data NOW     │
│  - Can't show "loading..." and fill in later (not a SPA with API) │
│  - The response IS the product — event name, date, location        │
│  - CDN caches the result, so origin is only hit 5% of the time    │
│                                                                      │
│  WebFlux helps: Even though it's sync FROM THE USER'S perspective, │
│  internally cxp-events uses Mono/Flux (reactive, non-blocking).    │
│  The ECS task's event loop thread is NOT blocked waiting for       │
│  Eventtia — it handles other requests while waiting.               │
│  This is async I/O for sync user experience.                       │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — reactive but user-perceived sync:**

```java
// EventtiaEventService.java — reactive chain (async I/O, sync response)
Mono<EventtiaEventsLandingPage> landingPage =
    eventtiaEventsLandingPageDetailsApi.getEventLandingPageDetails(token, params, page, size)
        .onErrorResume(ex -> Mono.just(new EventtiaEventsLandingPage()))
        .subscribeOn(Schedulers.boundedElastic());  // runs on elastic scheduler

// User's HTTP response waits for this Mono to complete.
// But the server thread is NOT blocked — WebFlux event loop
// handles other requests while waiting for Eventtia.
```

**Interview answer:**
> "Event detail pages are synchronous from the user's perspective — they wait for the data. But internally we use WebFlux reactive programming: the ECS task's event loop thread is NOT blocked while waiting for Eventtia to respond. It handles other requests concurrently. This is async I/O wrapped in a sync user experience. Combined with Akamai caching (95% hit rate), only 5% of requests actually make the synchronous call to Eventtia."

---

### Example 5: Investigate Event — Multi-Source Async Fan-Out, Sync Response

**Service:** `cxp-email-drop-recovery` (server.py)
**Pattern:** Fan-out async queries to 5+ data sources, gather results, return sync

```
┌──────────────────────────────────────────────────────────────────────┐
│  Investigation Tab — Async Fan-Out, Sync Gather                      │
│                                                                      │
│  User clicks "Investigate" for event 73067:                         │
│       │                                                              │
│       ▼  Fan out 5+ queries IN PARALLEL (async):                    │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐│
│  │ Athena   │ │ Splunk   │ │ Splunk   │ │ Splunk   │ │ Splunk   ││
│  │ (Partner │ │ (Rise    │ │ (CRS     │ │ (NCP     │ │ (Email   ││
│  │  Hub)    │ │  GTS)    │ │ Render)  │ │ Drops)   │ │ Delivery)││
│  └─────┬────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘│
│        │           │            │            │            │       │
│        └───────────┴────────────┴────────────┴────────────┘       │
│                              │                                     │
│                         GATHER all results                         │
│                              │                                     │
│                              ▼                                     │
│                    Return pipeline health                          │
│                    to user (sync response)                         │
│                                                                      │
│  Pattern: SCATTER-GATHER                                            │
│  - Fan out: async (all queries run simultaneously, ~60-120 sec)    │
│  - Gather: sync (wait for ALL to complete, then respond)           │
│  - Total time: max(all queries), NOT sum(all queries)              │
│  - If queries ran sequentially: 5 × 30s = 150s                    │
│  - In parallel: max(30s, 25s, 20s, 15s, 10s) = 30s               │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — parallel Splunk search jobs:**

```python
# server.py — start all searches simultaneously (async fan-out)
sids = {}
sids['athena'] = run_athena_query(q_count)          # async: Athena query
sids['rise']   = client.start_search(q_rise_gts)    # async: Splunk search job
sids['ncp']    = client.start_search(q_ncp_ingest)  # async: Splunk search job
sids['render'] = client.start_search(q_crs_render)  # async: Splunk search job
sids['email']  = client.start_search(q_delivery)    # async: Splunk search job

# All 5 queries running in parallel now.
# Wait for each to complete (sync gather):
for key, sid in sids.items():
    results[key] = client.wait_and_get_results(sid)  # blocks until done

# Return combined pipeline health to user
return json.dumps(pipeline_results)
```

---

## The Spectrum: Not Binary

Real systems mix sync and async at every level:

```
┌──────────────────────────────────────────────────────────────────────┐
│  THE SYNC-ASYNC SPECTRUM IN CXP                                      │
│                                                                      │
│  FULLY SYNC                                               FULLY ASYNC│
│  ◄──────────────────────────────────────────────────────────────────▶│
│                                                                      │
│  GET /event/73067     POST /register      Email pipeline   S3 sink  │
│  (user waits for     (user waits for     (6 services,     (archive, │
│   event details)      Eventtia 200,       minutes later)   nobody   │
│                       async for cache                      waits)   │
│                       + email pipeline)                              │
│                                                                      │
│  ┌─────────────┐   ┌──────────────────┐  ┌──────────────┐ ┌──────┐│
│  │  Sync HTTP   │   │  Sync HTTP +     │  │  Kafka/SQS   │ │ Fire ││
│  │  request-    │   │  Async fire-and- │  │  pipeline    │ │ and  ││
│  │  response    │   │  forget for      │  │  (eventual)  │ │forget││
│  │              │   │  background work │  │              │ │      ││
│  └─────────────┘   └──────────────────┘  └──────────────┘ └──────┘│
│                                                                      │
│  Sync USER experience + Async I/O internally (WebFlux)              │
│  = Best of both worlds                                              │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Sync vs Async Across CXP

| Operation | Sync/Async | Why | User Impact |
|-----------|-----------|-----|------------|
| **GET event details** | Sync | User waiting for page content | ~200ms (50ms with CDN cache) |
| **GET event search** | Sync | User waiting for search results | ~100ms |
| **POST register** (to Eventtia) | Sync | User needs "success" confirmation | ~200ms |
| **POST register** (LAMS waiver) | Async (fire-and-forget) | Legal waiver, user doesn't need to wait | 0ms to user |
| **Redis cache write** | Async (fire-and-forget) | Optimization, not critical path | 0ms to user |
| **Akamai cache purge** | Async (fire-and-forget) | Cache freshness, not user-facing | 0ms to user |
| **Email pipeline** (6 services) | Fully async (Kafka) | Minutes of work, user long gone | Minutes (eventual) |
| **S3 archival** | Fully async (Kafka sink) | Audit trail, no user interaction | Never seen by user |
| **CDN cache purge** | Async (Kafka purge sink) | Background invalidation | User sees fresh data ~seconds later |
| **Investigation queries** | Async fan-out + sync gather | 5 parallel queries, wait for all | ~30-120s (scatter-gather) |

---

## Common Interview Follow-ups

### Q: "The email drops at ~2-5% — isn't that a failure of async?"

> "It's a conscious tradeoff, not a failure. Async registration means the user gets 'Registered!' in <1 second instead of waiting 30+ seconds for the synchronous pipeline to confirm email delivery. The 2-5% drop rate is the cost of that speed. We compensate with the recovery dashboard (detect drops via Athena vs Splunk reconciliation, re-trigger via RISE API). The NET result: >99.5% email delivery with <1 second registration. A synchronous pipeline would give 100% email delivery but 30-second registration times — which means users ABANDON the registration entirely. We'd have 0% email drops but 20%+ registration abandonment."

### Q: "When would you switch from async to sync?"

> "When the user NEEDS the result to proceed. Example: our seat check before registration is synchronous — the user needs to know if seats are available BEFORE clicking Register. Making the seat check async would mean: 'Click Register → maybe the event is full → oops, undo.' That's worse UX than a 50ms synchronous seat check. The rule: if the user's NEXT ACTION depends on the result, make it sync. If the user can proceed without the result, make it async."

### Q: "How do you handle errors in fire-and-forget async calls?"

> "Three strategies depending on severity:
> 1. **Log and ignore** — LAMS registration failure is logged but doesn't affect the user. Legal team reviews logs periodically.
> 2. **Log and retry** — Akamai cache purge uses `@Retryable` with 200ms backoff, 2 attempts, then `@Recover` gives up. Cache expires via TTL anyway.
> 3. **Queue for later** — If the entire async pipeline fails (Kafka down), failed registrations are saved to DynamoDB unprocessed queue for batch reprocessing later.
>
> The key insight: `CompletableFuture.runAsync()` failures are invisible to the user. You MUST have monitoring (Splunk alerts) to detect silent failures in fire-and-forget paths."

### Q: "Why WebFlux reactive instead of traditional Spring MVC?"

> "Traditional Spring MVC blocks one thread per request — 200ms Eventtia call × 1000 concurrent users = 1000 threads blocked. WebFlux uses non-blocking I/O: the event loop thread sends the request to Eventtia and immediately handles another request while waiting. When Eventtia responds, a callback processes the result. With WebFlux, 1000 concurrent users need ~10 event loop threads (not 1000). This is async I/O giving us the throughput of async with the user experience of sync. Combined with `Schedulers.boundedElastic()` for blocking calls (Redis, external APIs), we get the best of both worlds."

---
---

# Topic 22: REST vs GraphQL vs gRPC

> REST is simple and cacheable; GraphQL fetches exactly what you need; gRPC offers binary speed and streaming for internal services.

> **Interview Tip:** Show you know tradeoffs — "REST for public APIs due to caching and simplicity, GraphQL for mobile apps to reduce round trips, gRPC for internal microservice communication where latency matters."

---

## The Three Protocols

```
┌──────────────────────────────────────────────────────────────────────────┐
│              REST vs GraphQL vs gRPC                                      │
│                                                                          │
│  ┌─────────────────────┐ ┌─────────────────────┐ ┌─────────────────────┐│
│  │       REST           │ │      GraphQL        │ │       gRPC          ││
│  │  HTTP + JSON         │ │  Query language,     │ │  HTTP/2 + Protobuf  ││
│  │  Resource-based      │ │  Single endpoint     │ │  Binary, typed      ││
│  │                      │ │                      │ │                     ││
│  │  GET /users/123      │ │  query {             │ │  service UserSvc {  ││
│  │  POST /users         │ │    user(id:123) {    │ │    rpc GetUser(Id)  ││
│  │  PUT /users/123      │ │      name, email     │ │    returns (User)   ││
│  │                      │ │    }                 │ │  }                  ││
│  │                      │ │  }                   │ │                     ││
│  │                      │ │                      │ │                     ││
│  │  [+] Simple,         │ │  [+] Fetch exactly   │ │  [+] Fast, binary   ││
│  │      cacheable,      │ │      what you need   │ │      streaming,     ││
│  │      standard        │ │  [+] Single request, │ │      type-safe      ││
│  │  [-] Over/under-     │ │      typed schema    │ │  [+] Bi-directional ││
│  │      fetching        │ │  [-] Complex caching │ │      streaming      ││
│  │  [-] Multiple round  │ │                      │ │  [-] Not browser-   ││
│  │      trips           │ │  Best: Mobile,       │ │      friendly       ││
│  │                      │ │  complex UIs         │ │                     ││
│  │  Best: Public APIs,  │ │                      │ │  Best: Microservice ││
│  │  CRUD                │ │                      │ │  internal comms     ││
│  └─────────────────────┘ └─────────────────────┘ └─────────────────────┘│
│                                                                          │
│  ┌───────────────────┬─────────────┬──────────────┬──────────────┐     │
│  │  Feature           │  REST       │  GraphQL     │  gRPC        │     │
│  ├───────────────────┼─────────────┼──────────────┼──────────────┤     │
│  │  Protocol          │  HTTP/1.1   │  HTTP        │  HTTP/2      │     │
│  │  Format            │  JSON/XML   │  JSON        │  Protobuf    │     │
│  │  Performance       │  Medium     │  Medium      │  Fast        │     │
│  │  Streaming         │  No         │  Subscriptions│ Bi-direction│     │
│  │  Browser Support   │  Excellent  │  Good        │  Limited     │     │
│  │  Caching           │  Easy (HTTP)│  Complex     │  None (HTTP) │     │
│  └───────────────────┴─────────────┴──────────────┴──────────────┘     │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## When to Use Each

```
┌──────────────────────────────────────────────────────────────────────┐
│  DECISION FRAMEWORK                                                  │
│                                                                      │
│  Use REST when:                                                     │
│  ✓ Public-facing APIs (standard, widely understood)                 │
│  ✓ CDN cacheability matters (GET cacheable by URL)                  │
│  ✓ Simple CRUD operations on resources                              │
│  ✓ Browser clients (native fetch/XMLHttpRequest support)            │
│  ✓ Hypermedia / discoverability needed                              │
│                                                                      │
│  Use GraphQL when:                                                  │
│  ✓ Mobile apps needing minimal data transfer (fetch ONLY needed    │
│    fields to reduce bandwidth on cellular)                          │
│  ✓ Complex nested data in one request (avoid N+1 round trips)      │
│  ✓ Multiple frontend teams needing different data from same API    │
│  ✓ Rapidly evolving schemas (no versioned URLs)                    │
│                                                                      │
│  Use gRPC when:                                                     │
│  ✓ Internal microservice-to-microservice communication             │
│  ✓ Ultra-low latency (binary Protobuf ~10x smaller than JSON)      │
│  ✓ Bi-directional streaming (real-time data feeds)                 │
│  ✓ Polyglot services (auto-generated clients in any language)      │
│  ✓ Strong contract enforcement (.proto as source of truth)         │
└──────────────────────────────────────────────────────────────────────┘
```

---

## REST vs GraphQL vs gRPC In My CXP Projects

### The CXP Platform — 100% REST (and Why)

Our platform uses **exclusively REST** for all API communication. No GraphQL, no gRPC. This is a deliberate architectural choice.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — ALL REST                                                  │
│                                                                          │
│  EXTERNAL (user-facing):                                                │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  nike.com (browser)                                               │  │
│  │    │                                                              │  │
│  │    ├── GET /community/events/v1/{id}          (event detail)     │  │
│  │    ├── GET /community/events/v1               (landing page)     │  │
│  │    │   Accept: application/vnd.nike.events-view+json             │  │
│  │    ├── GET /community/event_seats_status/v1   (seat availability)│  │
│  │    ├── POST /community/event_registrations/v1 (register)         │  │
│  │    ├── DELETE /community/event_registrations   (cancel)          │  │
│  │    └── GET /community/attendee_status/v1      (status check)    │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  INTERNAL (service-to-service):                                         │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  cxp-events → Eventtia API          (REST, JSON)                 │  │
│  │  cxp-reg → Eventtia API             (REST, JSON)                 │  │
│  │  cxp-reg → Pairwise API             (REST, JSON + OSCAR token)  │  │
│  │  cxp-reg → Akamai Purge API         (REST, JSON)                │  │
│  │  NSP3 sink → Rise GTS               (REST, vendored JSON)       │  │
│  │  Rise GTS → NCP Ingest API          (REST, JSON)                │  │
│  │  Rise GTS → NSPv2 Kafka REST        (REST, Kafka v2+json)       │  │
│  │  expviews → Elasticsearch           (REST, JSON)                │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  EVERYTHING IS REST. Internal + external. HTTP + JSON.                  │
└──────────────────────────────────────────────────────────────────────────┘
```

### Why REST Works for CXP (and When We'd Switch)

```
┌──────────────────────────────────────────────────────────────────────┐
│  WHY REST IS THE RIGHT CHOICE FOR CXP                                │
│                                                                      │
│  1. CDN CACHEABILITY                                                │
│     GET /community/events/v1/73067 is cacheable by URL.             │
│     Akamai caches REST responses with Edge-Cache-Tag.               │
│     GraphQL POST queries can't be cached by URL.                    │
│     → REST + CDN = 95% of requests never hit the origin.            │
│                                                                      │
│  2. SIMPLE RESOURCE MODEL                                           │
│     Events, registrations, seats — clean CRUD resources.            │
│     No deeply nested relationships requiring GraphQL's              │
│     "fetch exactly what you need" power.                            │
│                                                                      │
│  3. CONTENT NEGOTIATION (vendored types)                            │
│     Accept: application/vnd.nike.events-view+json                   │
│     Different representation of same resource (ELP vs EDP).         │
│     REST handles this natively via Accept headers.                  │
│                                                                      │
│  4. NIKE ECOSYSTEM                                                  │
│     All Nike services (NCP, OSCAR, Akamai, Pairwise) expose REST.  │
│     gRPC would require protocol translation at every boundary.      │
│     GraphQL would require a BFF layer over existing REST APIs.      │
│                                                                      │
│  5. BROWSER COMPATIBILITY                                           │
│     nike.com calls CXP APIs directly from the browser.              │
│     REST works with native fetch(). gRPC needs gRPC-Web proxy.     │
│                                                                      │
│  WHEN WE'D CONSIDER SWITCHING:                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  GraphQL: If Nike app (mobile) needed event details +       │    │
│  │  registration status + seat count + activities in ONE call. │    │
│  │  Currently requires 3 separate REST calls.                  │    │
│  │                                                              │    │
│  │  gRPC: If we replaced Eventtia with an internal service     │    │
│  │  and needed ultra-low latency for seat decrements during    │    │
│  │  sneaker launches. Protobuf is ~10x smaller than JSON.      │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

### Advanced REST: Content Negotiation with Vendored Media Types

Our platform uses **custom `application/vnd.nike.*` media types** — an advanced REST feature that most developers don't use:

```
┌──────────────────────────────────────────────────────────────────────┐
│  Vendored Media Types in CXP                                         │
│                                                                      │
│  SAME URL, DIFFERENT REPRESENTATION:                                │
│  GET /community/events/v1                                           │
│                                                                      │
│  Accept: application/vnd.nike.events-view+json                      │
│  → Returns: Event Landing Page (list of event cards)                │
│  → Akamai tag: ELP tag, 60min cache                                │
│                                                                      │
│  Accept: */* (or no Accept header)                                  │
│  → Returns: HTTP 406 Not Acceptable                                 │
│  → Forces clients to use the correct media type                     │
│                                                                      │
│  NSP3 → Rise GTS:                                                   │
│  Content-Type: application/vnd.nike.eventtia-events+json            │
│  → Rise GTS uses Content-Type to select the correct                 │
│    transform configuration (eventtia-events.json config)            │
│                                                                      │
│  Content-Type: application/vnd.nike.eventtia-ad-hoc-events+json     │
│  → Rise GTS selects a DIFFERENT transform config                    │
│                                                                      │
│  THIS IS REST-LEVEL CONTENT ROUTING:                                │
│  One URL (/data/transform/v1) handles 20+ different event types    │
│  by switching on Content-Type header. Similar to what GraphQL       │
│  achieves with query selection, but using REST standards.           │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// EventsController.java — content negotiation via Accept header
@GetMapping(
    headers = "Accept=" + HTTP_ELP_ACCEPT_HEADER,  // custom vendor type
    produces = HTTP_ELP_ACCEPT_HEADER)
public Mono<ResponseEntity<List<Event>>> getEventsLandingPage(...) { ... }

// Reject unsupported Accept types
@GetMapping(headers = "Accept=*/*")
public ResponseEntity<HttpStatus> toSupportOtherMediaTypesForELP() {
    return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build(); // 406
}

// Rise GTS — Content-Type drives transform selection
@PostMapping(consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
ResponseEntity<JsonNode> create(@RequestHeader Map<String, String> headers,
                                @RequestBody String input) {
    // Content-Type "application/vnd.nike.eventtia-events+json"
    // → selects eventtia-events.json transform config
    service.process(input, headers.get("content-type"));
}
```

**Interview answer:**
> "Our platform is 100% REST, and we use advanced REST features that solve problems people typically reach for GraphQL to fix. Content negotiation via vendored media types (`application/vnd.nike.events-view+json`) gives us different representations from the same URL — the event landing page list vs event detail. Rise GTS uses Content-Type routing: one endpoint (`/data/transform/v1`) handles 20+ event types by switching on the Content-Type header. CDN cacheability is the clincher — every GET is cacheable by URL with Akamai, which GraphQL POST queries can't do. If we needed mobile-specific optimization (one call for event + registration + seats), I'd add a BFF layer with GraphQL on top of the existing REST services."

---
---

# Topic 23: Real-Time Communication

> Polling wastes resources; long-polling holds connections; WebSocket enables true bi-directional real-time; SSE for server-push only scenarios.

> **Interview Tip:** Pick the right tool — "For chat I'd use WebSocket for bi-directional messaging; for live stock prices, SSE is simpler since it's one-way server push."

---

## The 4 Approaches

```
┌──────────────────────────────────────────────────────────────────────────┐
│              REAL-TIME COMMUNICATION PATTERNS                             │
│                                                                          │
│  ┌──────────────────────────┐  ┌──────────────────────────┐            │
│  │  POLLING (Short)         │  │  LONG-POLLING             │            │
│  │                          │  │                           │            │
│  │  Client asks repeatedly: │  │  Client asks once,        │            │
│  │  "Any updates?"          │  │  server holds connection  │            │
│  │  "Any updates?"          │  │  until data is available. │            │
│  │  "Any updates?"          │  │                           │            │
│  │                          │  │  Client ──▶ Server        │            │
│  │  Every 5 seconds:        │  │         (wait...)         │            │
│  │  GET /api/status         │  │         (wait...)         │            │
│  │  GET /api/status         │  │  Client ◀── data!         │            │
│  │  GET /api/status         │  │  Client ──▶ Server (again)│            │
│  │                          │  │                           │            │
│  │  [+] Simplest            │  │  [+] Fewer empty requests │            │
│  │  [-] Wastes bandwidth    │  │  [-] Server holds threads │            │
│  │  [-] Delayed updates     │  │  [-] Reconnection needed  │            │
│  └──────────────────────────┘  └──────────────────────────┘            │
│                                                                          │
│  ┌──────────────────────────┐  ┌──────────────────────────┐            │
│  │  WEBSOCKET               │  │  SSE (Server-Sent Events) │            │
│  │                          │  │                           │            │
│  │  Bi-directional,         │  │  Server-to-client only,   │            │
│  │  persistent TCP.         │  │  over HTTP.               │            │
│  │                          │  │                           │            │
│  │  Client ◀──▶ Server      │  │  Client ◀── Server        │            │
│  │  (both can send anytime) │  │  (server pushes only)     │            │
│  │                          │  │                           │            │
│  │  [+] True real-time      │  │  [+] Simple (just HTTP)   │            │
│  │  [+] Low latency         │  │  [+] Auto-reconnect       │            │
│  │  [+] Bi-directional      │  │  [+] Works with CDN/LB    │            │
│  │  [-] Complex (stateful)  │  │  [-] One-way only          │            │
│  │  [-] Harder to scale     │  │  [-] No binary data        │            │
│  │                          │  │                           │            │
│  │  Best: Chat, gaming,     │  │  Best: Live scores,        │            │
│  │  collaborative editing   │  │  notifications, dashboards │            │
│  └──────────────────────────┘  └──────────────────────────┘            │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Real-Time Communication In My CXP Projects

### The CXP Platform — No Real-Time Protocol (Polling + CDN)

Our platform uses **no WebSocket, no SSE, no long-polling**. Instead, we rely on **short TTL CDN caching + client-side page refresh** for "near real-time" updates. This is a deliberate design choice.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — REAL-TIME APPROACH                                        │
│                                                                          │
│  WHAT WE USE INSTEAD OF WEBSOCKET:                                      │
│                                                                          │
│  1. CDN SHORT TTL (seat availability)                                   │
│     Seats cache at Akamai for 1 minute (cache-maxage=1m).              │
│     User refreshes page → gets fresh seat count (max 1 min stale).     │
│     No persistent connection needed.                                    │
│                                                                          │
│  2. @SCHEDULED POLLING (server-side)                                    │
│     Internal event cache: refresh every 15 minutes.                    │
│     Eventtia auth token: refresh every 59 minutes.                     │
│     Secrets Manager config: periodic read.                              │
│     NOT user-facing — background server maintenance.                   │
│                                                                          │
│  3. PAGE-DRIVEN REFRESH (user-initiated)                                │
│     User clicks "Search" on email-drop-recovery dashboard.             │
│     Queries Splunk + Athena on demand. Not real-time streaming.        │
│     Results are point-in-time snapshots, not live feeds.               │
│                                                                          │
│  WHY NO WEBSOCKET:                                                      │
│  ┌────────────────────────────────────────────────────────────┐        │
│  │  - Events don't change in real-time (created days in advance)│        │
│  │  - Seat counts change only on registration (not streaming)  │        │
│  │  - No chat, no collaboration, no live updates needed        │        │
│  │  - CDN 1-min TTL gives "good enough" freshness              │        │
│  │  - WebSocket would add stateful complexity to our stateless │        │
│  │    ECS tasks (Topic 19) — breaking horizontal scaling       │        │
│  └────────────────────────────────────────────────────────────┘        │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: @Scheduled Polling — Server-Side Background Refresh

**Services:** cxp-events, cxp-event-registration
**Pattern:** Periodic polling of external services for cache/token refresh

```
┌──────────────────────────────────────────────────────────────────────┐
│  @Scheduled Polling — Background Maintenance                         │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Eventtia Auth Token Refresh:                                 │  │
│  │  @Scheduled(fixedRate = 3540000)  // every 59 minutes        │  │
│  │  public String getEventtiaServiceToken() { ... }             │  │
│  │  → Polls Eventtia for fresh OAuth token before expiry        │  │
│  │                                                               │  │
│  │  Internal Events Cache Refresh:                               │  │
│  │  @Scheduled(fixedRate = 900000)   // every 15 minutes        │  │
│  │  public void refreshInternalEventsCache() { ... }            │  │
│  │  → Polls Eventtia API for latest internal events             │  │
│  │  → Rebuilds Caffeine cache                                   │  │
│  │                                                               │  │
│  │  Translation Refresh:                                         │  │
│  │  @Scheduled(cron = "0 0 5 * * MON")  // Mondays at 5 AM     │  │
│  │  → Weekly refresh of Bodega translations from S3             │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  WHY POLLING (not push/webhook):                                    │
│  - Eventtia doesn't support token push (OAuth2 requires polling)   │
│  - Translation data doesn't have change events (S3 polling)        │
│  - Frequency matches data change rate (tokens=59min, cache=15min)  │
│                                                                      │
│  OVERHEAD:                                                          │
│  8 ECS tasks × 1 poll/15min = 32 Eventtia calls per hour          │
│  → Negligible compared to user traffic                              │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 2: Where We WOULD Use Each Real-Time Pattern

```
┌──────────────────────────────────────────────────────────────────────┐
│  HYPOTHETICAL: If CXP Needed Real-Time Features                      │
│                                                                      │
│  SCENARIO: Live seat count during sneaker launch                    │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  Option A: SSE (Server-Sent Events) ← I'd choose this      │    │
│  │  Server pushes seat count updates to all connected browsers. │    │
│  │  One-way (server→client) is all we need.                    │    │
│  │  Works with ALB (HTTP connection), no sticky sessions.       │    │
│  │  Auto-reconnect built into EventSource API.                 │    │
│  │                                                              │    │
│  │  Option B: Short polling (what we do now)                    │    │
│  │  Client polls GET /seats every 5 seconds.                   │    │
│  │  Simple but wastes bandwidth (99% of polls = "no change").  │    │
│  │                                                              │    │
│  │  Option C: WebSocket (overkill)                              │    │
│  │  Bi-directional not needed (client doesn't send seat data). │    │
│  │  Adds stateful connection → breaks horizontal scaling.      │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  SCENARIO: Live event chat during virtual events                    │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  WebSocket ← only option for bi-directional                  │    │
│  │  Users send AND receive messages.                            │    │
│  │  Would need: API Gateway WebSocket APIs (managed, serverless)│    │
│  │  or separate WebSocket service (not in stateless ECS tasks). │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  SCENARIO: Dashboard live updates (email-drop-recovery)             │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  SSE ← I'd choose this                                      │    │
│  │  Server pushes search progress (10%, 30%, 50%, done).       │    │
│  │  Currently: user clicks "Search" and waits 30-120 seconds.  │    │
│  │  With SSE: real-time progress bar + streaming results.      │    │
│  │  Simple to implement: Flux<ServerSentEvent> in WebFlux.     │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 3: Kafka/NSP3 — Real-Time for Servers, Not Clients

Our platform DOES have real-time event streaming — but between servers, not to browsers:

```
┌──────────────────────────────────────────────────────────────────────┐
│  Server-to-Server Real-Time (Kafka/NSP3)                             │
│                                                                      │
│  Eventtia webhook → Kafka stream → Rise GTS (seconds)               │
│  Eventtia webhook → Kafka stream → S3 archival (seconds)            │
│  Eventtia webhook → Kafka stream → Cache purge (seconds)            │
│                                                                      │
│  This IS real-time event processing between services.               │
│  But the BROWSER doesn't see this in real-time.                     │
│  The browser sees the result after it's processed                   │
│  (page refresh, CDN cache expiry).                                  │
│                                                                      │
│  THE GAP:                                                           │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Backend: Real-time (Kafka delivers events in seconds)       │   │
│  │  Frontend: Near-real-time (CDN TTL = 1-60 min)              │   │
│  │                                                              │   │
│  │  To close the gap: connect Kafka → SSE → browser             │   │
│  │  e.g., Kafka → Spring WebFlux Flux<ServerSentEvent> →       │   │
│  │       EventSource API in browser                             │   │
│  │  This would give true real-time from Eventtia to browser.   │   │
│  │  Not needed for current CXP (events don't change in         │   │
│  │  real-time), but would be valuable for live seat counts     │   │
│  │  during sneaker launches.                                    │   │
│  └─────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Communication Protocols Across CXP

| Communication Type | Protocol | Used In CXP | Why / Why Not |
|---|---|---|---|
| **Public APIs** | REST + JSON | Yes (all services) | CDN cacheable, browser-native, Nike standard |
| **Content routing** | REST vendored media types | Yes (`vnd.nike.*`) | Same URL, different representation — REST-native alternative to GraphQL |
| **Internal service calls** | REST + JSON | Yes (Eventtia, NCP, Pairwise, Akamai) | Simplicity, OSCAR token auth, universal support |
| **Event streaming** | Kafka REST (NSPv2) | Yes (Rise GTS → topics) | Kafka ingestion via HTTP, no native client needed |
| **Server-side polling** | @Scheduled | Yes (token, cache, config refresh) | No push available from source; frequency matches change rate |
| **CDN near-real-time** | Short TTL (1 min) | Yes (seat counts) | "Good enough" freshness without persistent connections |
| **GraphQL** | Not used | No | No deeply nested queries; CDN caching more valuable |
| **gRPC** | Not used | No | All services are REST; Nike ecosystem is REST; browser clients |
| **WebSocket** | Not used | No | No bi-directional real-time needed; would break stateless ECS |
| **SSE** | Not used | No | No server-push requirement; would add for live seat counts |

---

## Common Interview Follow-ups

### Q: "Why REST over GraphQL for the mobile Nike app?"

> "The Nike app calls our event APIs via REST through Akamai CDN. GraphQL would eliminate over-fetching (the app doesn't need every event field), but we'd lose CDN cacheability — GraphQL POST queries can't be cached by URL. With REST, Akamai caches `GET /community/events/v1/73067` for 60 minutes, serving 95% of requests at the edge. The 5% bandwidth overhead from over-fetching is cheaper than losing 95% cache hit rate. If mobile bandwidth became a real problem, I'd add a lightweight BFF (Backend for Frontend) that aggregates REST calls and returns a mobile-optimized JSON shape — still cacheable."

### Q: "When would you add gRPC to this platform?"

> "If we replaced Eventtia with an internal Nike service for seat management. During sneaker launches, the registration service calls Eventtia ~10,000 times in 60 seconds. Each REST call sends ~2KB JSON. With gRPC Protobuf, the same payload would be ~200 bytes (10x smaller) and parsed ~10x faster (binary vs text). At 10,000 calls, that's 20MB vs 2MB of network traffic and significant CPU savings on serialization. gRPC also provides generated type-safe clients (no manual model mapping) and connection multiplexing (one TCP connection for all calls). But it only makes sense for internal services — browsers can't use gRPC natively."

### Q: "How would you add live seat counts during a sneaker launch?"

> "SSE (Server-Sent Events). The browser opens a persistent connection via `EventSource` to a `/community/events/v1/{id}/live-seats` endpoint. On the server, a WebFlux `Flux<ServerSentEvent>` streams seat count changes. The flow: Registration succeeds → Kafka event → consumer updates a shared seat counter (Redis DECR) → SSE pushes new count to all connected browsers. SSE over WebSocket because: (1) one-way is sufficient (server→client), (2) works with ALB without sticky sessions, (3) auto-reconnects on connection drop, (4) simpler than WebSocket protocol. I'd use a separate SSE-serving service (not in the stateless cxp-events tasks) to isolate the persistent connections."

### Q: "Your email-drop-recovery dashboard makes users wait 30-120 seconds. How would you improve it?"

> "SSE with streaming results. Currently the Python server runs 5 Splunk/Athena queries sequentially and returns all results at once after the slowest query finishes. With SSE, I'd stream partial results as each query completes:
> - T=10s: Athena results arrive → push 'stage 1 complete' event
> - T=20s: Rise GTS Splunk results → push 'stage 2 complete' event  
> - T=30s: CRS render results → push 'stage 3 complete' event
>
> The browser updates the pipeline diagram progressively. User sees progress instead of a spinner for 2 minutes. In Python, this would be a `text/event-stream` response with `yield` for each stage. In Spring WebFlux, it would be `Flux<ServerSentEvent>.merge()` of multiple query Monos."

---
---

# Topic 24: Distributed Transactions

> 2PC provides strong consistency but blocks; Saga pattern chains local transactions with compensations for better availability.

> **Interview Tip:** Explain the tradeoff — "I'd avoid 2PC due to blocking and use Saga with orchestration for the order flow: reserve inventory, charge payment, if either fails, run compensating transactions."

---

## The Problem

In a microservice architecture, a single business operation spans **multiple services with separate databases**. If one step fails, how do you undo the others?

```
┌──────────────────────────────────────────────────────────────────────┐
│  THE DISTRIBUTED TRANSACTION PROBLEM                                 │
│                                                                      │
│  User registers for a Nike event. This touches 6 systems:          │
│                                                                      │
│  1. cxp-event-registration → accept request                        │
│  2. Eventtia → create registration + decrement seats                │
│  3. LAMS → legal waiver registration                                │
│  4. Kafka/NSP3 → publish event for downstream                      │
│  5. Rise GTS → transform data for email                            │
│  6. NCP/CRS → send confirmation email                              │
│                                                                      │
│  WHAT IF step 5 fails? Steps 1-4 already succeeded.                │
│  You can't "undo" a Kafka message or an Eventtia registration      │
│  with a simple ROLLBACK like a SQL database.                        │
│                                                                      │
│  Two approaches:                                                    │
│  A. TWO-PHASE COMMIT (2PC): Coordinate ALL services to commit      │
│     or rollback atomically. Strong consistency, but blocking.       │
│  B. SAGA PATTERN: Each service does local work + publishes event.   │
│     On failure, run compensating transactions to undo.              │
└──────────────────────────────────────────────────────────────────────┘
```

---

## The Two Approaches

```
┌──────────────────────────────────────────────────────────────────────────┐
│                     DISTRIBUTED TRANSACTIONS                              │
│                                                                          │
│  ┌────────────────────────────┐  ┌────────────────────────────────┐    │
│  │  TWO-PHASE COMMIT (2PC)    │  │  SAGA PATTERN                  │    │
│  │                            │  │                                 │    │
│  │  Coordinator ensures       │  │  Sequence of local              │    │
│  │  all-or-nothing.           │  │  transactions with              │    │
│  │                            │  │  compensations.                │    │
│  │  Phase 1: PREPARE          │  │                                 │    │
│  │  Coordinator asks all:     │  │  ┌──┐   ┌──┐   ┌──┐           │    │
│  │  "Can you commit?"         │  │  │T1│──▶│T2│──▶│T3│ FAIL      │    │
│  │  All vote YES/NO           │  │  └──┘   └──┘   └──┘           │    │
│  │                            │  │                 ████████        │    │
│  │  Phase 2: COMMIT           │  │  Compensate: C2, C1            │    │
│  │  If all YES: COMMIT        │  │                                 │    │
│  │  If any NO: ROLLBACK       │  │  [+] No locking, better        │    │
│  │                            │  │      availability              │    │
│  │  Blocking if coordinator   │  │  [-] Eventually consistent     │    │
│  │  fails.                    │  │  [-] Compensation logic needed │    │
│  └────────────────────────────┘  └────────────────────────────────┘    │
│                                                                          │
│  ┌────────────────────────────┐  ┌────────────────────────────────┐    │
│  │  SAGA: CHOREOGRAPHY        │  │  SAGA: ORCHESTRATION           │    │
│  │                            │  │                                 │    │
│  │  Services react to events  │  │  Central orchestrator           │    │
│  │  (decentralized).          │  │  controls flow.                │    │
│  │                            │  │                                 │    │
│  │  ┌─────┐ event ┌─────┐   │  │       ┌──────────────┐         │    │
│  │  │Order│──────▶│Paymt│   │  │       │ Orchestrator │         │    │
│  │  └─────┘       └──┬──┘   │  │       └──┬──┬──┬──┬──┘         │    │
│  │           event    │      │  │          │  │  │  │             │    │
│  │                    ▼      │  │          ▼  ▼  ▼  ▼             │    │
│  │              ┌──────────┐ │  │  Order Paymt Inv Ship           │    │
│  │              │Inventory │ │  │                                 │    │
│  │              └──────────┘ │  │  [+] Clear flow, easy to track │    │
│  │                            │  │  [-] Single point of failure   │    │
│  │  [+] Loose coupling       │  │                                 │    │
│  │  [-] Hard to track flow   │  │  Best: Complex flows,          │    │
│  │  Best: Simple flows,      │  │  many steps                    │    │
│  │  few steps                │  │                                 │    │
│  └────────────────────────────┘  └────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## How Each Works

### Two-Phase Commit (2PC)

```
Coordinator: "I need to register user + decrement seats + create waiver"

PHASE 1 — PREPARE:
  Coordinator → Eventtia:   "Can you register this user?"  → YES
  Coordinator → LAMS:       "Can you create this waiver?"  → YES
  Coordinator → NCP:        "Can you send this email?"     → YES
  (All participants LOCK their resources and wait)

PHASE 2 — COMMIT:
  All said YES → Coordinator: "COMMIT all!"
  Eventtia: commit registration ✓
  LAMS: commit waiver ✓
  NCP: commit email ✓

IF ANY SAID NO:
  Coordinator: "ROLLBACK all!"
  Everyone releases locks and undoes their work.

PROBLEM: What if the coordinator CRASHES between Phase 1 and Phase 2?
  All participants are LOCKED, waiting for a commit/rollback that never comes.
  This is the "blocking" problem of 2PC — resources stuck indefinitely.

PROBLEM: Network partition during Phase 2?
  Some participants commit, others don't → INCONSISTENCY.
  2PC can't handle network partitions safely (CAP theorem: CP only).
```

### Saga — Choreography (Event-Driven)

```
No coordinator. Each service publishes events; next service reacts.

Step 1: cxp-event-registration → calls Eventtia → SUCCESS
        publishes: "RegistrationCreated" event to Kafka

Step 2: Rise GTS → hears "RegistrationCreated" → transforms data
        publishes: "TransformComplete" event

Step 3: NCP → hears "TransformComplete" → renders + sends email
        publishes: "EmailSent" event

IF Step 3 FAILS (email dropped):
  NCP publishes: "EmailFailed" event
  Recovery dashboard detects the gap (compensating action)
  Reprocess via RISE API (manual compensation)

NO COORDINATOR. Each service only knows about its OWN event.
The "flow" emerges from event subscriptions.
```

### Saga — Orchestration (Central Controller)

```
Orchestrator service controls the sequence explicitly.

Orchestrator:
  1. Call Eventtia → register user → SUCCESS → proceed
  2. Call LAMS → create waiver → SUCCESS → proceed
  3. Publish to Kafka → trigger email pipeline → SUCCESS → done

IF Step 2 FAILS:
  Orchestrator: "LAMS failed. Run compensation."
  → Call Eventtia: cancel registration (compensating transaction)
  → Return error to user

The orchestrator KNOWS the full workflow.
Easy to debug (one place to see the state).
But: orchestrator is a single point of failure.
```

---

## Distributed Transactions In My CXP Projects

### The CXP Platform — Saga Choreography (Without Knowing It)

Our registration pipeline implements a **Saga pattern using choreography** — each service reacts to events from the previous step, with **compensating mechanisms** for failures. We don't use a formal Saga framework — the pattern emerges naturally from our event-driven architecture.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP REGISTRATION — SAGA CHOREOGRAPHY                                     │
│                                                                          │
│  ┌──────┐   ┌──────────┐   ┌──────┐   ┌────────┐   ┌─────┐   ┌─────┐│
│  │ T1   │──▶│   T2     │──▶│ T3   │──▶│  T4    │──▶│ T5  │──▶│ T6  ││
│  │cxp-  │   │Eventtia  │   │Kafka │   │Rise GTS│   │ NCP │   │ CRS ││
│  │reg   │   │register  │   │publish│   │transform│  │ingest│  │email││
│  └──────┘   └──────────┘   └──────┘   └────────┘   └─────┘   └─────┘│
│     │            │             │           │           │          │    │
│    sync         sync        event        event       sync      email  │
│  (user waits) (user waits) (webhook)  (Kafka sink) (HTTP)    (async) │
│                                                                       │
│  IF T5/T6 FAILS (email dropped):                                     │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  COMPENSATING ACTION (not automatic — manual via dashboard):   │  │
│  │                                                                │  │
│  │  1. email-drop-recovery detects gap (Athena vs Splunk)        │  │
│  │  2. Fetches original S3 payload (from T3's S3 sink)           │  │
│  │  3. Re-POSTs to Rise GTS (replay T4)                          │  │
│  │  4. Rise GTS → NCP → CRS → email (replay T5 + T6)            │  │
│  │                                                                │  │
│  │  This IS a compensating transaction:                           │  │
│  │  Not "undo registration" but "retry the failed step."         │  │
│  │  The user stays registered (T1+T2 succeeded, no rollback).    │  │
│  │  Only the email delivery is retried.                          │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  IF T2 FAILS (Eventtia rejects — event full):                           │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  COMPENSATION: Immediate, automatic.                            │  │
│  │  1. Eventtia returns 422 → cxp-reg catches the error          │  │
│  │  2. No Kafka event published (T3 never starts)                │  │
│  │  3. Akamai seat cache purged (async via CompletableFuture)    │  │
│  │  4. 422 returned to user: "Event is full"                     │  │
│  │                                                                │  │
│  │  No compensation needed for T1 (no side effects to undo).    │  │
│  │  Pipeline doesn't even start. Clean failure.                  │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  IF T2 SUCCEEDS but T1 needs retry (DynamoDB queue):                    │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  COMPENSATION: Deferred retry.                                  │  │
│  │  1. Registration fails mid-way (network error, timeout)        │  │
│  │  2. Request saved to DynamoDB unprocessed queue                │  │
│  │  3. Batch reprocessing picks it up later                      │  │
│  │  4. Retry the full registration flow                           │  │
│  │                                                                │  │
│  │  This is "forward compensation" — retry rather than undo.     │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: The Full Registration Saga — 6 Steps with Compensations

```
┌──────────────────────────────────────────────────────────────────────┐
│  REGISTRATION SAGA — Step by Step                                    │
│                                                                      │
│  STEP │ SERVICE      │ ACTION           │ IF FAILS                  │
│  ─────┼──────────────┼──────────────────┼──────────────────────────│
│  T1   │ cxp-event-   │ Validate JWT,    │ Return 401/400 to user.  │
│       │ registration │ check idempotency│ No side effects.          │
│       │              │ (Redis)          │                           │
│  ─────┼──────────────┼──────────────────┼──────────────────────────│
│  T2   │ Eventtia     │ Register user,   │ Return 422 to user.      │
│       │ (external)   │ decrement seats  │ Purge seat cache (async).│
│       │              │                  │ No compensation needed — │
│       │              │                  │ nothing was created.      │
│  ─────┼──────────────┼──────────────────┼──────────────────────────│
│  T2b  │ LAMS         │ Legal waiver     │ Log error. Registration  │
│       │ (async)      │ registration     │ still succeeds. Waiver   │
│       │              │                  │ handled manually later.  │
│  ─────┼──────────────┼──────────────────┼──────────────────────────│
│  T3   │ Kafka/NSP3   │ Webhook published│ Event not in Kafka →     │
│       │              │ to stream        │ Eventtia issue. Escalate │
│       │              │                  │ to Eventtia team.        │
│  ─────┼──────────────┼──────────────────┼──────────────────────────│
│  T4   │ Rise GTS     │ Transform for    │ SQS retry (3 attempts). │
│       │              │ NCP email        │ Then DLQ. Manual review. │
│  ─────┼──────────────┼──────────────────┼──────────────────────────│
│  T5   │ NCP → CRS    │ Render and send  │ Email dropped. Detected  │
│       │              │ confirmation     │ by recovery dashboard.   │
│       │              │ email            │ Reprocess via RISE API.  │
│  ─────┼──────────────┼──────────────────┼──────────────────────────│
│                                                                      │
│  KEY INSIGHT: We NEVER roll back the registration (T2).             │
│  If the email fails (T5), the user IS registered — they just       │
│  don't have the confirmation email yet. We compensate FORWARD      │
│  (retry the email) rather than BACKWARD (cancel registration).     │
│                                                                      │
│  This is a FORWARD-ONLY SAGA: no step undoes a previous step.      │
│  All compensations are retries, not rollbacks.                     │
└──────────────────────────────────────────────────────────────────────┘
```

**Interview answer:**
> "Our registration flow is a 6-step Saga using choreography. Each service reacts to events from the previous step — Eventtia webhook triggers Kafka, Kafka sinks trigger Rise GTS, Rise GTS triggers NCP. There's no central orchestrator. Compensations are forward-only: if the email fails, we retry it via the recovery dashboard — we never roll back the registration itself. This is deliberate: a user who registered but didn't get an email is better off than a user whose registration was rolled back because of an email system issue."

---

### Example 2: Why NOT Two-Phase Commit for CXP

```
┌──────────────────────────────────────────────────────────────────────┐
│  WHY 2PC DOESN'T WORK FOR CXP                                       │
│                                                                      │
│  2PC REQUIRES:                    CXP REALITY:                      │
│  ─────────────                    ─────────────                     │
│  All participants support         Eventtia is external SaaS.        │
│  the 2PC protocol.                It does NOT support 2PC.          │
│                                   Kafka does NOT support 2PC.       │
│                                   NCP does NOT support 2PC.         │
│                                                                      │
│  Participants can LOCK            Eventtia can't "prepare" a        │
│  resources during prepare.        registration and hold seats       │
│                                   locked while waiting for a commit.│
│                                                                      │
│  Coordinator manages all          6 services across 3+ teams.       │
│  participants.                    No single team owns all services. │
│                                                                      │
│  Low latency between              Eventtia is external (100ms+).    │
│  participants.                    Cross-team services (50ms+).      │
│                                   2PC with 200ms per phase =        │
│                                   400ms+ added latency.             │
│                                                                      │
│  RESULT: 2PC is technically impossible AND impractical for CXP.    │
│  Saga with choreography is the natural fit for an event-driven     │
│  microservice architecture with external dependencies.             │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 3: Choreography vs Orchestration in CXP

Our platform uses **choreography** (no central orchestrator). Here's why, and when orchestration would be better:

```
┌──────────────────────────────────────────────────────────────────────┐
│  CXP USES CHOREOGRAPHY                                               │
│                                                                      │
│  Eventtia ──webhook──▶ Kafka ──▶ Rise GTS ──▶ NCP ──▶ Email       │
│                          │                                           │
│                          └──▶ S3 Partner Hub (archival)             │
│                          └──▶ cxp-events /purge-cache              │
│                                                                      │
│  No service "orchestrates" the flow.                                │
│  Each service reacts to the event before it:                        │
│  - Kafka reacts to Eventtia webhook                                 │
│  - Rise GTS reacts to Kafka HTTP Push sink                          │
│  - NCP reacts to Rise GTS HTTP POST                                 │
│  - S3 sink reacts to same Kafka stream (parallel)                   │
│  - Purge sink reacts to metadata stream (parallel)                  │
│                                                                      │
│  WHY CHOREOGRAPHY WORKS HERE:                                       │
│  ✓ Simple linear flow (A → B → C → D)                              │
│  ✓ Services owned by different teams (no single owner to build     │
│    an orchestrator)                                                  │
│  ✓ Each step is idempotent (safe to retry)                         │
│  ✓ Fan-out is natural (Kafka stream → 3 sinks)                     │
│  ✓ No need for conditional branching (every registration follows   │
│    the same path)                                                   │
│                                                                      │
│  WHEN WE'D SWITCH TO ORCHESTRATION:                                │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  If we added PAID events:                                    │    │
│  │  1. Reserve seats → 2. Charge payment → 3. Confirm reg     │    │
│  │                                                              │    │
│  │  If payment fails, MUST undo seat reservation.              │    │
│  │  This requires an orchestrator that knows:                  │    │
│  │  "Payment failed → call Eventtia cancel → release seats"   │    │
│  │                                                              │    │
│  │  Choreography can't do this cleanly because:                │    │
│  │  - Payment service doesn't know about Eventtia              │    │
│  │  - "PaymentFailed" event needs someone to interpret it      │    │
│  │    and decide what to compensate                             │    │
│  │  - An orchestrator would hold the state machine:            │    │
│  │    RESERVED → CHARGED → CONFIRMED (or COMPENSATING)         │    │
│  └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 4: The Email-Drop-Recovery Dashboard — Compensating Transaction in Practice

The recovery dashboard is our **manual compensating transaction** for the email delivery saga:

```
┌──────────────────────────────────────────────────────────────────────┐
│  COMPENSATING TRANSACTION: Email Recovery                            │
│                                                                      │
│  NORMAL SAGA: T1 → T2 → T3 → T4 → T5 → T6 (email delivered) ✓   │
│                                                                      │
│  FAILED SAGA: T1 → T2 → T3 → T4 → T5 ✗ (email dropped)          │
│                                                                      │
│  COMPENSATION (not rollback — forward retry):                       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  1. DETECT: Recovery dashboard queries Athena (who           │    │
│  │     registered?) vs Splunk (who received email?).           │    │
│  │     Gap = dropped emails.                                    │    │
│  │                                                              │    │
│  │  2. FETCH: For each dropped email, query Athena for the     │    │
│  │     original S3 payload path ("$path" column).              │    │
│  │                                                              │    │
│  │  3. REPLAY: Read S3 payload → POST to Rise GTS              │    │
│  │     /data/transform/v1 (replay T4).                         │    │
│  │                                                              │    │
│  │  4. VERIFY: Query Splunk for successful delivery after      │    │
│  │     reprocessing. Confirm email was sent.                   │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  WHY FORWARD COMPENSATION (not backward rollback):                  │
│  - User IS registered in Eventtia (T2 succeeded). Correct state.   │
│  - Only the EMAIL is missing. The fix is to RESEND, not CANCEL.    │
│  - Rolling back registration would punish the user for a system    │
│    failure they didn't cause.                                       │
│                                                                      │
│  SAGA TERMINOLOGY:                                                  │
│  T2 (Eventtia register) = completed transaction (no undo needed)   │
│  T5 (email delivery) = failed transaction                          │
│  C5 (reprocess via RISE) = compensating transaction (forward retry)│
│                                                                      │
│  This is a SEMANTIC compensation — the business logic decides      │
│  "retry email" is better than "cancel registration."              │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code — the compensating transaction:**

```python
# reprocess.py — compensating transaction for failed email
# Step 1: Find the original S3 payload for the dropped email
q = f'''SELECT "$path" AS s3path
    FROM "{ATHENA_DATABASE}".{ATHENA_TABLE}
    WHERE attendee.upm_id = '{upmid}'
    AND action = 'confirmed'
    ORDER BY event_date_ms DESC LIMIT 1'''

# Step 2: Fetch original payload from S3
s3_response = s3.get_object(Bucket=bucket, Key=key)
payload = json.loads(s3_response['Body'].read())

# Step 3: Replay to Rise GTS (re-trigger T4 → T5 → T6)
response = urllib.request.urlopen(urllib.request.Request(
    RISE_GTS_URL,
    data=json.dumps(payload).encode(),
    headers={
        'Content-Type': 'application/vnd.nike.eventtia-events+json;charset=UTF-8',
        'Authorization': f'Bearer {oscar_token}'
    }
))
# Rise GTS transforms → NCP renders → CRS sends email
```

---

### Example 5: DynamoDB Unprocessed Queue — Deferred Compensation

```
┌──────────────────────────────────────────────────────────────────────┐
│  DEFERRED COMPENSATION: DynamoDB Unprocessed Registration Queue      │
│                                                                      │
│  SCENARIO: Registration call to Eventtia times out mid-flight.      │
│  User might or might not be registered (we don't know).             │
│                                                                      │
│  COMPENSATION FLOW:                                                 │
│  1. cxp-event-registration saves request to DynamoDB                │
│     PK: "eventId_upmId" → { payload, timestamp, status }          │
│                                                                      │
│  2. Batch reprocessing job runs later:                              │
│     POST /community/reprocess_regns/v1                              │
│     → Scans DynamoDB for unprocessed items                          │
│     → Retries each registration against Eventtia                   │
│     → If Eventtia says "already registered" (422) → safe           │
│     → If Eventtia says success → registration completed            │
│     → Delete from DynamoDB after successful processing             │
│                                                                      │
│  THIS IS A SAGA WITH DEFERRED COMPENSATION:                        │
│  - T1 (accept request) succeeded                                   │
│  - T2 (Eventtia) is UNKNOWN (timeout)                              │
│  - Compensation: save to queue, retry later                        │
│  - Eventtia's idempotency (422 "already registered") ensures       │
│    the retry is safe even if the original call actually succeeded  │
│                                                                      │
│  KEY PROPERTY: IDEMPOTENCY makes compensation safe.                │
│  We can retry T2 any number of times without creating duplicates.  │
└──────────────────────────────────────────────────────────────────────┘
```

**From the actual code:**

```java
// RegistrationProcessingController.java — deferred compensation trigger
@PostMapping
public Mono<ResponseEntity<ReprocessingResponse>> processUnprocessedRegistrations(...) {
    // Scans DynamoDB → retries each unprocessed registration
}

@DeleteMapping
public Mono<ResponseEntity<ReprocessingResponse>> processUnprocessedCancellations(...) {
    // Scans DynamoDB → retries each unprocessed cancellation
}

// UnprocessedRegistrationService.java — save to retry queue
dynamoDbTable.putItem(request);   // deferred for later compensation
dynamoDbTable.deleteItem(key);    // remove after successful retry
```

---

## Summary: Distributed Transaction Patterns in CXP

| Pattern | CXP Implementation | When It Triggers |
|---------|-------------------|-----------------|
| **Saga Choreography** | Eventtia webhook → Kafka → Rise GTS → NCP (event-driven chain) | Every registration (happy path) |
| **Forward Compensation** | email-drop-recovery reprocesses via RISE API | Email dropped (~2-5% of registrations) |
| **Deferred Compensation** | DynamoDB unprocessed queue + batch reprocessing | Eventtia timeout / network error |
| **Immediate Compensation** | Eventtia 422 → purge seat cache → return error to user | Event full / activity full |
| **Idempotent Retry** | Eventtia returns 422 "already registered" on duplicate | Safe retry from any compensation |
| **2PC** | NOT USED — external services don't support it | N/A |

---

## Common Interview Follow-ups

### Q: "Why not use a Saga orchestrator framework like Temporal or Camunda?"

> "Our saga is simple: linear flow, no conditional branching, all compensations are forward retries. A formal orchestrator adds value when you have complex flows with branching (if payment fails → cancel reservation → refund → notify user). Our flow is A → B → C → D with 'if D fails, retry D later.' The email-drop-recovery dashboard IS our lightweight orchestrator — it detects the failure and initiates compensation. If we added paid events with payment+refund logic, I'd introduce Temporal for the payment saga specifically."

### Q: "How do you ensure idempotency in your saga?"

> "Every step is idempotent by design:
> - **Eventtia:** Returns 422 'already registered' on duplicate (no double-booking)
> - **Redis idempotency cache:** Duplicate request detected before reaching Eventtia
> - **Kafka:** At-least-once delivery, but Rise GTS processes idempotently (same input → same output)
> - **SQS:** `ON_SUCCESS` deletion; if reprocessed, same transform result
> - **RISE reprocess:** Re-POSTing the same S3 payload produces the same email
>
> Idempotency is the foundation that makes saga compensation SAFE — we can retry any step without fear of duplicate side effects."

### Q: "What's the consistency model of your saga?"

> "Eventually consistent. At any point in time, a user might be 'registered in Eventtia but no email sent yet' (between T2 and T6). This is a valid intermediate state — not a bug. The system converges to 'registered + email received' within minutes (happy path) or hours (compensation path). We chose this over strong consistency (2PC) because: (1) Eventtia doesn't support 2PC, (2) blocking 6 services for one atomic commit would add 400ms+ latency, (3) eventual consistency with compensation gives >99.5% completion rate at <1 second user latency."

### Q: "How do you monitor saga failures?"

> "Four signals:
> 1. **Splunk alerts:** NCP drop rate exceeds threshold → email saga failing
> 2. **DynamoDB scan:** Unprocessed queue growing → registration saga failing
> 3. **Recovery dashboard trends:** Daily drop count increasing → systemic issue
> 4. **SQS DLQ depth:** Messages in DLQ → Rise GTS transform failing
>
> Each signal corresponds to a specific saga step failing. The recovery dashboard's Investigate tab traces a single registration through all 6 steps to pinpoint exactly where the saga broke."

---
---

# Topic 25: Consensus Algorithms

> Raft and Paxos help distributed nodes agree on values/leaders despite failures — essential for distributed databases and coordination services.

> **Interview Tip:** Know when it's used — "Raft consensus powers etcd which Kubernetes uses for cluster state, ensuring all nodes agree on configuration even during network partitions."

---

## The Problem

In a distributed system, multiple nodes must **agree on a single value or leader** even when some nodes crash or the network splits. Without consensus, you get split-brain: two nodes both think they're the leader, accepting conflicting writes.

```
┌──────────────────────────────────────────────────────────────────────┐
│  THE CONSENSUS PROBLEM                                               │
│                                                                      │
│  3 nodes need to agree: "Who is the leader?"                        │
│                                                                      │
│  WITHOUT CONSENSUS:                   WITH CONSENSUS:               │
│                                                                      │
│  ┌────┐  ┌────┐  ┌────┐             ┌────┐  ┌────┐  ┌────┐       │
│  │ N1 │  │ N2 │  │ N3 │             │ N1 │  │ N2 │  │ N3 │       │
│  │"I'm│  │"I'm│  │"I'm│             │Lead│  │Foll│  │Foll│       │
│  │lead│  │lead│  │lead│             │ er │  │ower│  │ower│       │
│  └────┘  └────┘  └────┘             └────┘  └────┘  └────┘       │
│  SPLIT BRAIN!                        CONSENSUS! One leader,        │
│  3 leaders = conflicting writes      2 followers agree.            │
│  = data corruption                   Writes go to leader only.     │
│                                                                      │
│  Used for:                                                          │
│  - Leader election (who's the primary?)                             │
│  - Distributed locks (who holds the lock?)                          │
│  - Replicated state machines (all nodes agree on state)            │
│  - Configuration management (all nodes see same config)            │
└──────────────────────────────────────────────────────────────────────┘
```

---

## The Three Algorithms

### Raft (Understandable Consensus)

```
┌──────────────────────────────────────────────────────────────────────┐
│  RAFT — Designed for Understandability                                │
│                                                                      │
│  NODE STATES:                                                       │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐                     │
│  │  LEADER   │    │ FOLLOWER │    │CANDIDATE │                     │
│  │ (green)   │    │ (blue)   │    │ (orange)  │                     │
│  └──────────┘    └──────────┘    └──────────┘                     │
│                                                                      │
│  HOW IT WORKS:                                                      │
│  ─────────────                                                      │
│  1. Leader sends HEARTBEATS to followers every ~150ms               │
│     Leader ──heartbeat──▶ Follower 1                               │
│     Leader ──heartbeat──▶ Follower 2                               │
│                                                                      │
│  2. No heartbeat? Follower becomes CANDIDATE                        │
│     Follower 1: "No heartbeat for 300ms... leader is dead!"        │
│     Follower 1 → transitions to CANDIDATE                          │
│                                                                      │
│  3. Candidate REQUESTS VOTES from others                            │
│     Candidate ──"Vote for me (term 2)"──▶ Follower 2              │
│     Candidate ──"Vote for me (term 2)"──▶ (dead leader)           │
│                                                                      │
│  4. Majority votes? Becomes NEW LEADER                              │
│     Follower 2 ──"YES, you have my vote"──▶ Candidate             │
│     2 out of 3 nodes agree → Candidate becomes Leader              │
│                                                                      │
│  LOG REPLICATION:                                                   │
│  Leader replicates log entries to followers.                        │
│  Entry COMMITTED when majority acknowledges.                       │
│                                                                      │
│  Leader: "Write X=5"                                               │
│  → replicate to Follower 1 → ACK ✓                                │
│  → replicate to Follower 2 → ACK ✓                                │
│  → 2/3 majority → COMMITTED (safe, survives leader crash)          │
│                                                                      │
│  Used by: etcd, Consul, CockroachDB, TiKV                         │
└──────────────────────────────────────────────────────────────────────┘
```

### Paxos (Original, Complex)

```
┌──────────────────────────────────────────────────────────────────────┐
│  PAXOS — The Original Consensus Algorithm                            │
│                                                                      │
│  ROLES:                                                             │
│  Proposer  — proposes values ("I suggest X=5")                      │
│  Acceptor  — votes on proposals ("I accept/reject")                 │
│  Learner   — learns decided value ("X=5 was chosen")               │
│                                                                      │
│  TWO PHASES:                                                        │
│  Phase 1 — PREPARE:                                                 │
│    Proposer → Acceptors: "Prepare proposal #N"                      │
│    Acceptors: "OK, I promise not to accept anything < #N"           │
│                                                                      │
│  Phase 2 — ACCEPT:                                                  │
│    Proposer → Acceptors: "Accept value X for proposal #N"           │
│    Acceptors: "Accepted" (if promise not violated)                  │
│    Majority accept → value CHOSEN                                   │
│                                                                      │
│  Complex! Raft was designed as a simpler alternative.               │
│  Used by: Google Spanner, Google Chubby (internal)                  │
└──────────────────────────────────────────────────────────────────────┘
```

### ZAB (Zookeeper Atomic Broadcast)

```
┌──────────────────────────────────────────────────────────────────────┐
│  ZAB — Used by Apache Zookeeper                                      │
│                                                                      │
│  Similar to Raft but optimized for:                                 │
│  - Primary-backup replication (not general consensus)               │
│  - High throughput writes (batched broadcasts)                      │
│  - FIFO ordering of messages                                        │
│                                                                      │
│  Used for: Kafka coordination (pre-KRaft), distributed config,     │
│  leader election, distributed locks, service discovery              │
│                                                                      │
│  Kafka is migrating from ZAB (Zookeeper) to Raft (KRaft)           │
│  for simpler operation and fewer moving parts.                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Comparison

```
┌──────────────────────────────────────────────────────────────────────┐
│  ALGORITHM COMPARISON                                                │
│                                                                      │
│  ┌──────────────┬──────────────┬──────────────┬──────────────┐     │
│  │              │  RAFT         │  PAXOS        │  ZAB          │     │
│  ├──────────────┼──────────────┼──────────────┼──────────────┤     │
│  │  Complexity   │  Simple       │  Complex      │  Medium       │     │
│  │  Leader       │  Single       │  No fixed     │  Single       │     │
│  │               │  strong leader│  leader       │  primary      │     │
│  │  Election     │  Majority     │  Majority     │  Majority     │     │
│  │               │  vote         │  proposals    │  vote         │     │
│  │  Log ordering │  Strict       │  Flexible     │  FIFO         │     │
│  │  Throughput   │  Good         │  Lower (more  │  High (batch) │     │
│  │               │               │  messages)    │               │     │
│  │  Used by      │  etcd, Consul │  Spanner,     │  Zookeeper,   │     │
│  │               │  CockroachDB  │  Chubby       │  Kafka (old)  │     │
│  └──────────────┴──────────────┴──────────────┴──────────────┘     │
│                                                                      │
│  INDUSTRY TREND: Raft is winning.                                   │
│  - Easier to implement and debug                                    │
│  - Kafka migrating from ZAB to KRaft (Raft-based)                  │
│  - etcd (Kubernetes) uses Raft                                      │
│  - Most new distributed systems choose Raft                         │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Consensus In My CXP Projects — Where It Runs Under the Hood

Our CXP services **don't implement consensus directly** — we use managed services that rely on consensus internally. Understanding where consensus runs helps explain WHY our managed services are reliable.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CXP PLATFORM — WHERE CONSENSUS RUNS (UNDER THE HOOD)                    │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  DynamoDB                                                         │  │
│  │  Consensus: PAXOS-based (internally)                              │  │
│  │  Where: Every write to DynamoDB is replicated across 3 AZs      │  │
│  │  using a Paxos-like protocol. Write is committed only after     │  │
│  │  majority (2 of 3) AZs acknowledge. This is why DynamoDB        │  │
│  │  strongly consistent reads always see the latest write.          │  │
│  │  Our table: unprocessed-registration-requests                    │  │
│  │  → Every putItem() is consensus-committed across 3 AZs.        │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Kafka / NSP3                                                     │  │
│  │  Consensus: ZAB (Zookeeper) → migrating to KRaft (Raft)         │  │
│  │  Where: Kafka broker leader election for each partition.         │  │
│  │  When an NSP3 partition leader dies, Zookeeper/KRaft elects a   │  │
│  │  new leader from the ISR (In-Sync Replicas) set.                │  │
│  │  Our streams: partnerhub_notification_stream                     │  │
│  │  → Partition leader elected via consensus. If broker dies,      │  │
│  │    new leader elected in seconds — producers/sinks auto-reconnect│  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Elasticsearch                                                    │  │
│  │  Consensus: Raft-like (Zen Discovery → custom implementation)    │  │
│  │  Where: Master node election. ES cluster needs a quorum of      │  │
│  │  master-eligible nodes to elect a master. The master assigns    │  │
│  │  primary shards, manages cluster state.                          │  │
│  │  Our cluster: pg-elasticsearch-cluster (expviewsnikeapp)        │  │
│  │  → If master node dies, remaining nodes elect a new master      │  │
│  │    via consensus. Shard assignments are redistributed.          │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Redis ElastiCache (Cluster Mode / Sentinel)                      │  │
│  │  Consensus: Raft-like (Redis Sentinel quorum)                    │  │
│  │  Where: When Redis primary fails, Sentinel nodes vote to elect  │  │
│  │  a new primary from replicas. Requires majority of Sentinels    │  │
│  │  to agree (quorum). ElastiCache Multi-AZ handles this.          │  │
│  │  Our setup: Primary + 3 read replicas                            │  │
│  │  → If primary fails, ElastiCache promotes a replica via         │  │
│  │    internal consensus. Our app sees brief failover (~30 sec).   │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  S3 (Internal)                                                    │  │
│  │  Consensus: Paxos-like (AWS internal)                             │  │
│  │  Where: S3 replicates every object across 3+ AZs synchronously. │  │
│  │  Strong read-after-write consistency since 2020 — achieved via  │  │
│  │  consensus on write path.                                        │  │
│  │  Our data: Partner Hub webhook payloads (source of truth)        │  │
│  │  → Every S3 PutObject is consensus-committed across AZs.       │  │
│  │    That's why Athena always sees the latest data.               │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  ECS / NPE (Kubernetes-based)                                     │  │
│  │  Consensus: Raft (via etcd inside Kubernetes)                    │  │
│  │  Where: NPE runs on Kubernetes. etcd stores cluster state       │  │
│  │  (pod assignments, service configs, secrets). Raft ensures all  │  │
│  │  etcd nodes agree on the cluster state.                          │  │
│  │  Our services: cxp-events, cxp-event-registration on NPE        │  │
│  │  → When we deploy, etcd records the new pod state via Raft.    │  │
│  │    Liveness/readiness probes + etcd consensus keep cluster      │  │
│  │    state consistent across control plane nodes.                 │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Example 1: DynamoDB — Paxos for 3-AZ Write Durability

```
┌──────────────────────────────────────────────────────────────────────┐
│  DynamoDB Write Path — Consensus Under the Hood                      │
│                                                                      │
│  Our code: dynamoDbTable.putItem(request);                          │
│                                                                      │
│  What actually happens:                                             │
│                                                                      │
│  1. SDK sends write to DynamoDB endpoint                            │
│  2. DynamoDB routes to the leader replica (partition)               │
│  3. Leader proposes write to 2 follower replicas (Paxos-like)      │
│                                                                      │
│     Leader (AZ-1) ──"Write X"──▶ Replica (AZ-2) → ACK ✓          │
│                    ──"Write X"──▶ Replica (AZ-3) → ACK ✓          │
│                                                                      │
│  4. Majority (2/3) acknowledge → write COMMITTED                   │
│  5. DynamoDB returns success to our SDK                             │
│                                                                      │
│  IF AZ-2 IS DOWN:                                                   │
│     Leader (AZ-1) ──"Write X"──▶ Replica (AZ-2) → ✗ (down)      │
│                    ──"Write X"──▶ Replica (AZ-3) → ACK ✓          │
│     2/3 still respond (AZ-1 + AZ-3) → COMMITTED ✓                 │
│     Write succeeds despite one AZ failure!                          │
│                                                                      │
│  IF LEADER (AZ-1) DIES:                                             │
│     DynamoDB internally elects new leader from AZ-2 or AZ-3        │
│     (Paxos-like consensus among storage nodes)                     │
│     New leader serves reads and accepts writes                     │
│     Our code doesn't change — SDK automatically routes to new leader│
│                                                                      │
│  THIS IS WHY:                                                       │
│  - DynamoDB has 99.999% availability SLA                            │
│  - Strongly consistent reads always see latest write                │
│  - Our unprocessed registration queue never loses data              │
│  - We don't manage replication — consensus handles it              │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 2: Kafka (NSP3) — ZAB for Partition Leader Election

```
┌──────────────────────────────────────────────────────────────────────┐
│  Kafka Partition Leader Election — ZAB Consensus                     │
│                                                                      │
│  NSP3 stream: partnerhub_notification_stream                        │
│  Partition 0: Leader = Broker A, ISR = {A, B, C}                   │
│                                                                      │
│  NORMAL OPERATION:                                                  │
│  HTTP Push Sink → writes to Partition 0 Leader (Broker A)          │
│  Broker A replicates to B and C (ISR)                              │
│  Message committed when majority ISR acknowledges                  │
│                                                                      │
│  BROKER A CRASHES:                                                  │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  1. Zookeeper detects Broker A is gone (heartbeat timeout)  │    │
│  │  2. ZAB consensus among Zookeeper nodes: "Broker A is dead" │    │
│  │  3. Kafka controller (elected via ZAB) picks new leader     │    │
│  │     from ISR: Broker B becomes new leader for Partition 0   │    │
│  │  4. Producers/sinks auto-reconnect to Broker B              │    │
│  │  5. No data lost (Broker B had all committed messages)      │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  FOR OUR CXP PIPELINE:                                              │
│  - Eventtia webhook → NSP3 → continues writing to new leader      │
│  - Rise GTS HTTP Push Sink → reconnects to new leader's broker    │
│  - S3 archival sink → reconnects to new leader                     │
│  - Brief blip (~seconds) during failover, then normal              │
│  - NO email drops caused by Kafka broker failure (messages durable) │
│                                                                      │
│  KAFKA KRAFT (new):                                                 │
│  Kafka is migrating from Zookeeper (ZAB) to KRaft (Raft).         │
│  KRaft embeds consensus directly in Kafka brokers — no separate    │
│  Zookeeper cluster needed. Simpler operation, fewer failure modes.  │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 3: Redis — Sentinel Quorum for Failover

```
┌──────────────────────────────────────────────────────────────────────┐
│  Redis Failover — Sentinel Consensus                                 │
│                                                                      │
│  Our setup: 1 Primary + 3 Read Replicas (ElastiCache Multi-AZ)    │
│                                                                      │
│  PRIMARY CRASHES:                                                   │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  1. ElastiCache (Sentinel-like) detects primary is down     │    │
│  │                                                              │    │
│  │  2. CONSENSUS: "Is the primary really dead?"                 │    │
│  │     Sentinel Node 1: "Yes, I can't reach it"               │    │
│  │     Sentinel Node 2: "Yes, I can't reach it"               │    │
│  │     Sentinel Node 3: "Yes, I can't reach it"               │    │
│  │     Quorum (3/3 agree) → primary confirmed dead            │    │
│  │                                                              │    │
│  │  3. CONSENSUS: "Which replica should be promoted?"           │    │
│  │     Sentinels vote → Replica 2 (most caught-up) wins       │    │
│  │     Replica 2 promoted to primary                           │    │
│  │                                                              │    │
│  │  4. Other replicas reconfigure to follow new primary        │    │
│  │  5. DNS endpoint updated → our app reconnects automatically  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  IMPACT ON CXP:                                                     │
│  - ~30 seconds of Redis unavailability during failover             │
│  - Our try-catch fallbacks activate (Topic 17): return null,       │
│    proceed without cache, call Partner API directly                 │
│  - After failover: normal operation resumes, cache rebuilds        │
│  - management.health.redis.enabled=false prevents ALB from         │
│    marking the ECS task unhealthy during Redis failover            │
│                                                                      │
│  WHY CONSENSUS MATTERS:                                             │
│  Without quorum: one Sentinel thinks primary is dead (network      │
│  partition), promotes a replica → TWO primaries (split-brain!)     │
│  → conflicting writes → data corruption.                           │
│  With quorum: MAJORITY must agree → no split-brain.               │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 4: Elasticsearch — Master Election for Cluster Health

```
┌──────────────────────────────────────────────────────────────────────┐
│  Elasticsearch Master Election                                       │
│                                                                      │
│  Our cluster: pg-elasticsearch-cluster (3+ nodes)                   │
│                                                                      │
│  MASTER NODE responsibilities:                                      │
│  - Assign primary/replica shards to data nodes                     │
│  - Track cluster state (which shard is where)                      │
│  - Handle index creation/deletion                                   │
│  - Detect failed nodes and reassign shards                         │
│                                                                      │
│  MASTER DIES:                                                       │
│  1. Remaining nodes detect missing heartbeat                       │
│  2. Election: master-eligible nodes vote                           │
│     minimum_master_nodes = (N/2) + 1 (quorum)                     │
│     For 3 nodes: need 2 votes to elect                             │
│  3. New master takes over cluster state management                 │
│  4. Shards on the dead node → reassigned to surviving nodes       │
│                                                                      │
│  FOR OUR expviewsnikeapp:                                          │
│  - Brief search degradation during master election (~seconds)      │
│  - Searches that hit shards on the dead node fail temporarily      │
│  - After rebalancing: all shards available on surviving nodes      │
│  - ElasticSearchRepository catches IOException → RuntimeException  │
│    → 500 to user → CDN serves stale cached version (graceful)     │
└──────────────────────────────────────────────────────────────────────┘
```

---

### Example 5: Kubernetes/NPE — Raft via etcd for Cluster State

```
┌──────────────────────────────────────────────────────────────────────┐
│  Kubernetes etcd — Raft Consensus for Our Deployments                │
│                                                                      │
│  When we deploy cxp-events v2.0:                                    │
│                                                                      │
│  1. kubectl/Jenkins applies new deployment spec                     │
│  2. API server writes to etcd: "cxp-events desired: v2.0, 4 pods" │
│  3. etcd RAFT: leader replicates to followers                      │
│     etcd-1 (leader) → etcd-2 (follower) → ACK                     │
│     etcd-1 (leader) → etcd-3 (follower) → ACK                     │
│     Majority committed → deployment state is DURABLE               │
│  4. Scheduler reads desired state from etcd                        │
│     → creates new pods, terminates old pods (rolling update)       │
│                                                                      │
│  IF etcd LEADER CRASHES:                                            │
│  → Raft election: etcd-2 or etcd-3 becomes new leader             │
│  → New leader has ALL committed state (Raft log replication)       │
│  → Kubernetes control plane continues without data loss            │
│  → Our CXP pods keep running (data plane unaffected)              │
│                                                                      │
│  NPE SPECIFICS:                                                     │
│  Our NPE component YAMLs define desired state:                     │
│  - container.image: artifactory.nike.com:9002/cxp/cxp-events      │
│  - routing.paths: /community/events/v1                              │
│  - health.liveness: /community/events_health/v1                    │
│  All stored in etcd via Raft consensus.                            │
│  Even if a control plane node dies, our desired state is safe.     │
└──────────────────────────────────────────────────────────────────────┘
```

---

## The Quorum Rule: Why Odd Numbers

```
┌──────────────────────────────────────────────────────────────────────┐
│  THE QUORUM RULE                                                     │
│                                                                      │
│  Quorum = majority = (N/2) + 1                                     │
│                                                                      │
│  ┌─────────┬─────────┬──────────────┬──────────────────┐          │
│  │  Nodes  │ Quorum  │  Can survive │  Example          │          │
│  ├─────────┼─────────┼──────────────┼──────────────────┤          │
│  │  1      │  1      │  0 failures  │  Single Redis     │          │
│  │  2      │  2      │  0 failures  │  Useless! Both    │          │
│  │         │         │  (same as 1) │  must be up.      │          │
│  │  3      │  2      │  1 failure   │  DynamoDB 3 AZs   │          │
│  │  5      │  3      │  2 failures  │  etcd, Zookeeper  │          │
│  │  7      │  4      │  3 failures  │  Large clusters   │          │
│  └─────────┴─────────┴──────────────┴──────────────────┘          │
│                                                                      │
│  WHY ODD NUMBERS:                                                   │
│  3 nodes: survive 1 failure (quorum = 2)                           │
│  4 nodes: survive 1 failure (quorum = 3) ← same as 3 nodes!      │
│  Adding the 4th node costs money but doesn't improve tolerance.    │
│  5 nodes: survive 2 failures (quorum = 3) ← one more than 3.     │
│                                                                      │
│  OUR CXP:                                                           │
│  DynamoDB: 3 AZs → survives 1 AZ failure (quorum = 2)            │
│  etcd (K8s): 3 or 5 nodes → survives 1-2 node failures           │
│  Zookeeper (Kafka): 3 nodes → survives 1 node failure             │
│  ElastiCache: Primary + 3 replicas = 4 nodes → survives 1 (write)│
└──────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Consensus Across CXP Infrastructure

| Component | Consensus Algorithm | Where It Runs | What It Decides | CXP Impact |
|-----------|-------------------|--------------|----------------|------------|
| **DynamoDB** | Paxos-like (internal) | 3 AZ replicas per partition | Write durability (2/3 ACK) | putItem() is durable after return |
| **Kafka/NSP3** | ZAB → KRaft (Raft) | Zookeeper/controller | Partition leader election | Sink failover in seconds, no message loss |
| **Elasticsearch** | Raft-like (Zen/custom) | Master-eligible nodes | Master election, shard assignment | Brief search degradation on master failure |
| **Redis** | Sentinel quorum | ElastiCache Multi-AZ | Primary failover election | ~30s downtime, try-catch fallbacks activate |
| **S3** | Paxos-like (internal) | 3+ AZ replicas | Write durability | Partner Hub source of truth is always consistent |
| **Kubernetes/NPE** | Raft (etcd) | Control plane nodes | Cluster state, pod assignments | Deployments survive control plane node failures |

---

## Common Interview Follow-ups

### Q: "Do you need to understand Raft to use DynamoDB?"

> "No — and that's the beauty of managed services. DynamoDB handles Paxos-based consensus internally. I never configure quorum sizes, election timeouts, or log replication. I call `putItem()` and it's durable. But understanding consensus explains WHY DynamoDB guarantees strong consistency for single-region reads, WHY it survives AZ failures, and WHY Global Tables use last-writer-wins (cross-region consensus is too slow for real-time). In an interview, this knowledge shows you understand what's happening under the abstraction."

### Q: "What's the split-brain problem and how does your platform handle it?"

> "Split-brain is when a network partition causes two nodes to both think they're the leader — accepting conflicting writes. Our platform handles this through consensus at every layer:
> - **DynamoDB:** 3-AZ Paxos ensures only one write leader per partition. If AZs can't communicate, the minority side rejects writes (CP within region).
> - **Redis:** Sentinel quorum requires majority vote before promoting a replica. A single Sentinel can't unilaterally promote — prevents split-brain.
> - **Kafka:** ISR (In-Sync Replicas) ensures new leader has all committed messages. A broker that was partitioned can't become leader unless it catches up.
> - **DynamoDB Global Tables:** Cross-region IS eventual (AP), so 'split-brain' is accepted — last-writer-wins resolves conflicts. This is the deliberate CAP tradeoff from Topic 1."

### Q: "Why does Kafka use 3 Zookeeper nodes, not 2?"

> "With 2 Zookeeper nodes, quorum requires both (2/2 = 2). If either node dies, there's no quorum — the ENTIRE Kafka cluster loses coordination. With 3 nodes, quorum is 2/3 — one node can die and the cluster keeps running. This is the fundamental quorum rule: N=2 is actually WORSE than N=1 for availability because it adds a dependency without adding fault tolerance. Always use odd numbers: 3 for basic fault tolerance, 5 for two-failure tolerance."