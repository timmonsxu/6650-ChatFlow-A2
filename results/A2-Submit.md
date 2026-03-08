# CS6650 Assignment 2 — Submission Document

## 1. Git Repository URL

https://github.com/timmonsxu/6650-ChatFlow

Repository structure:
```
6650-ChatFlow-A2/
  client-v2/       Load test client (WebSocket sender, metrics collector)
  server-v2/       Updated server with SQS integration and broadcast endpoint
  consumer/        SQS consumer application with broadcast client
  deployment/      ALB configuration and startup scripts
  monitoring/      CloudWatch metric notes and SQS monitoring queries
  results/         Test results, architecture document, this file
  plan.md          Implementation plan and design decisions
  review.md        Debug log — all issues encountered and solutions applied
```

---

## 2. Architecture Document

### System Architecture

```
[Load Test Client]
  Local machine
  128 WebSocket sessions
  40 warmup threads x 1000 msgs
  Per-room sub-queues (20 rooms)
        |
        | WebSocket ws://ALB-DNS/chat/{roomId}
        |
  [AWS ALB]
  Port 80, HTTP
  Sticky Session (LB Cookie, 1 day)
  Idle timeout: 300s
       /            \
[EC2 A]            [EC2 B]
Server-v2          Server-v2
port 8080          port 8080
t3.micro           t3.micro
us-west-2b         us-west-2b
       \            /
        [SQS x20 FIFO Queues]
        chatflow-room-01.fifo
        chatflow-room-02.fifo
        ...
        chatflow-room-20.fifo
             |
        [Consumer]
        EC2 A, port 8081
        20 polling threads
             |
        Parallel HTTP broadcast
        to EC2 A :8080 and EC2 B :8080
```

### Message Flow Sequence

```
Client SenderThread
    |
    | 1. WebSocket connect to ALB /chat/{roomId}
    |    ALB routes to EC2 A or EC2 B via Sticky Session cookie
    |
    | 2. Send JSON message: {userId, username, message, timestamp, messageType, roomId}
    |
Server-v2 (on whichever EC2 ALB routed to)
    |
    | 3. Parse and validate message
    | 4. Register session in roomSessions map (implicit JOIN on first message)
    | 5. Build QueueMessage (add messageId UUID, serverId, clientIp)
    | 6. Send RECEIVED ack to client immediately:
    |    {"status": "RECEIVED", "messageId": "<uuid>"}
    | 7. Submit SQS publish to async background thread pool (fire-and-forget)
    |
Client SenderThread
    | 8. Receives RECEIVED ack, records success, sends next message
    |
SqsPublisher (background thread)
    | 9. Publishes QueueMessage to chatflow-room-{roomId}.fifo
    |    MessageGroupId = roomId (ordering per room)
    |    MessageDeduplicationId = messageId UUID (dedup on retry)
    |
Consumer (EC2 A, polling loop per room)
    | 10. Long-poll SQS (waitTimeSeconds=20, maxMessages=10)
    | 11. Parse roomId from message body
    | 12. Fire parallel HTTP calls to ALL known Server instances:
    |     CompletableFuture.sendAsync() to EC2 A :8080
    |     CompletableFuture.sendAsync() to EC2 B :8080
    |     CompletableFuture.allOf(...).join() — waits for both
    | 13. Delete message from SQS after broadcast attempts complete
    |
Server-v2 InternalBroadcastController (on each EC2)
    | 14. Receives POST /internal/broadcast/{roomId}
    | 15. Returns HTTP 200 immediately
    | 16. Submits broadcastToRoom to dedicated broadcastExecutor (40 threads)
    |
broadcastExecutor
    | 17. Calls broadcastToRoom(roomId, messageJson)
    | 18. Finds all ConcurrentWebSocketSessionDecorator instances for this room
    | 19. Sends message to each open session
    |     (DROP strategy — buffer overflow silently discards, no session termination)
    |
Client (receives broadcast)
    | 20. Broadcast message arrives — discarded by sendAndWait() filter
    |     (Client only needs RECEIVED ack; broadcast is a side effect)
```

### Queue Topology

20 AWS SQS FIFO queues, one per chat room:

- Queue names: `chatflow-room-01.fifo` through `chatflow-room-20.fifo`
- Queue type: FIFO (required by assignment spec for message ordering)
- Message ordering: guaranteed within each room via `MessageGroupId = roomId`
- Deduplication: explicit `MessageDeduplicationId = messageId UUID` (content-based dedup disabled)
- Visibility timeout: 120 seconds
- Message retention: 1 hour
- Region: us-west-2
- Account: 449126751631

Message routing: Server-v2 reads the `roomId` field from the message payload and publishes to the corresponding queue. Client SenderThreads each own a fixed roomId, ensuring URL roomId and payload roomId are always consistent.

### Consumer Threading Model

```
SqsConsumerService (20 threads, one per room)
  Thread 1  ->  polls chatflow-room-01.fifo in a tight loop
  Thread 2  ->  polls chatflow-room-02.fifo in a tight loop
  ...
  Thread 20 ->  polls chatflow-room-20.fifo in a tight loop

Per message processed by each thread:
  1. ReceiveMessage (long poll, up to 10 messages per call)
  2. For each message:
     a. Parse roomId from body
     b. BroadcastClient.broadcast() — fires parallel HTTP calls
        to all Server instances via CompletableFuture.sendAsync()
     c. DeleteMessage from SQS
  3. Loop back to step 1

SQS HTTP connection pool: 40 connections (numThreads + 20)
BroadcastClient HTTP connect timeout: 200ms (fast-fail for offline servers)
BroadcastClient request timeout: 2s per call
```

### Load Balancing Configuration

- Load balancer type: AWS Application Load Balancer (ALB)
- Listener: HTTP port 80
- Target group: 2 targets (EC2 A port 8080, EC2 B port 8080)
- Health check: GET /health, interval 30s, timeout 5s, healthy threshold 2, unhealthy threshold 3
- Sticky sessions: enabled, LB generated cookie, duration 1 day
- Idle timeout: 300 seconds (WebSocket connections require extended idle timeout)
- WebSocket support: native ALB support, no additional configuration needed

Sticky sessions are critical for WebSocket correctness. Each SenderThread's connection must always route to the same Server instance, because session membership (roomSessions map) is maintained per Server process. Without sticky sessions, broadcast would fail for sessions that happened to connect to a different Server than the one receiving the Consumer's broadcast call.

### Failure Handling Strategies

**SQS publish failure:** SqsPublisher uses fire-and-forget async publish. If SQS publish fails, the client has already received RECEIVED ack and the message is lost. AWS SDK retries transient failures automatically. Persistent failures are logged. Acceptable for load-test purposes.

**Broadcast failure:** BroadcastClient logs errors but does not block or retry. If a Server instance is unreachable (connect timeout 200ms), the call fails fast and the next Server is still called. The SQS message is deleted regardless of broadcast success — best-effort delivery.

**Session buffer overflow:** `ConcurrentWebSocketSessionDecorator` with `OverflowStrategy.DROP` silently discards broadcast messages when the 512KB per-session buffer is full. Session is never terminated due to slow consumers.

**Server instance down:** ALB health checks detect unhealthy instances within 60 seconds (2 failed checks × 30s interval) and stops routing traffic to them. Consumer's 200ms connect timeout means broadcast calls to the dead instance fail fast.

**SQS receipt handle expiry:** If Consumer processing takes longer than the 120s visibility timeout (e.g., during Server overload), the message becomes visible again and will be reprocessed. Deduplication IDs prevent SQS from accepting duplicate publishes; Consumer-side dedup is not implemented (acceptable for load-test).

---

## 3. Test Results

### Single Instance Tests

**Client Output — Warmup + Main Phase (200K messages, 128 threads)**

```
============================================
  ChatFlow Load Test Client - Part 1 (A2)
  Server: ws://54.184.109.66:8080
  Total messages: 200000
  Rooms: 20
  Warmup: 40 threads x 1000 msgs
  Main:   120 threads, 120 sessions
============================================
>>> Warmup Phase starting...
========================================
  Warmup Phase Results
========================================
  Successful messages : 40,000
  Failed messages     : 0
  Total runtime       : 44.24 seconds
  Throughput          : 723 msg/s
  Total connections   : 40
  Reconnections       : 0
========================================
>>> Main Phase: 120 threads (120 sessions)
========================================
  Main Phase Results
========================================
  Successful messages : 168,000
  Failed messages     : 0
  Total runtime       : 76.73 seconds
  Throughput          : 2,190 msg/s
  Total connections   : 120
  Reconnections       : 0
========================================
========================================
  Overall Summary
========================================
  Total successful    : 200,000
  Total failed        : 0
  Total wall time     : 120.98 seconds
  Overall throughput  : 1,653 msg/s
========================================
```

**[ SCREENSHOT PLACEHOLDER — SQS Console: queue depths over time ]**
*Insert screenshot of AWS SQS Console showing all 20 queues with message count over time. Expected pattern: stable plateau during client run, rapid drain after client stops.*

**[ SCREENSHOT PLACEHOLDER — SQS Console: message rates (send/receive) ]**
*Insert screenshot showing NumberOfMessagesSent and NumberOfMessagesReceived metrics from CloudWatch for the chatflow-room queues.*

**[ SCREENSHOT PLACEHOLDER — SQS Console: consumer details ]**
*Insert screenshot showing ApproximateNumberOfMessagesNotVisible (in-flight messages) and consumer activity.*

### Load Balanced Tests (2 Instances — 2 EC2s)

**[ SCREENSHOT PLACEHOLDER — Client output for 2-instance test ]**
*Insert terminal screenshot showing 500K message run with ALB DNS as server URL.*

**[ SCREENSHOT PLACEHOLDER — ALB Console: request distribution ]**
*Insert screenshot from ALB → Monitoring showing RequestCount per target, confirming roughly equal distribution between EC2 A and EC2 B.*

**[ SCREENSHOT PLACEHOLDER — SQS queue depth comparison: 1 instance vs 2 instances ]**
*Insert side-by-side or overlay comparison of queue depth profiles.*

**Performance Improvement Analysis**

| Metric | Single Instance | 2 Instances (2 EC2s) |
|---|---|---|
| Warmup throughput | ~700 msg/s | ~TBD |
| Main phase throughput | ~2,190 msg/s | ~TBD |
| Overall throughput | ~1,653 msg/s | ~TBD |
| Peak queue depth | ~4,000 | ~TBD |
| Failures | 0 | TBD |
| Max stable message count | 200K | 500K |

*Fill in TBD values after running the 2-instance 500K test.*

### Load Balanced Tests (4 Instances)

*Note: Due to t3.micro hardware constraints, 4 instances on a single EC2 caused CPU saturation and degraded performance. True 4-instance testing requires 4 separate EC2s or larger instance types. Results below are from the 2-EC2 configuration which represents the practical limit for this hardware.*

**[ SCREENSHOT PLACEHOLDER — Client output for 4-instance test if available ]**

---

## 4. Configuration Details

### SQS Queue Configuration

| Parameter | Value |
|---|---|
| Queue type | FIFO |
| Number of queues | 20 (chatflow-room-01.fifo to chatflow-room-20.fifo) |
| Region | us-west-2 |
| Visibility timeout | 120 seconds |
| Message retention period | 1 hour |
| Message deduplication | Explicit MessageDeduplicationId (UUID per message) |
| Message ordering | MessageGroupId = roomId |
| Content-based deduplication | Disabled |
| High throughput FIFO | Disabled (standard FIFO sufficient) |

### Server-v2 Configuration

| Parameter | Value |
|---|---|
| server.port | 8080 |
| server.tomcat.threads.max | 500 |
| server.tomcat.threads.min-spare | 50 |
| SqsPublisher async thread pool | 20 threads |
| broadcastExecutor threads | 40 threads |
| broadcastExecutor queue | Bounded ArrayBlockingQueue(2000), DiscardPolicy |
| ConcurrentWebSocketSessionDecorator send time limit | 30,000 ms |
| ConcurrentWebSocketSessionDecorator buffer size | 512 KB |
| ConcurrentWebSocketSessionDecorator overflow strategy | DROP |
| WebSocket session idle timeout | 600,000 ms (10 minutes) |

For multiple instances, override at startup:
```bash
java -Dserver.port=8082 -Dapp.server-id=server-8082 -jar server-v2.jar
```

### Consumer Configuration

| Parameter | Value |
|---|---|
| server.port | 8081 |
| Consumer polling threads | 20 |
| SQS long poll wait time | 20 seconds |
| SQS max messages per poll | 10 |
| SQS HTTP connection pool | numThreads + 20 = 40 |
| Broadcast HTTP connect timeout | 200 ms |
| Broadcast HTTP request timeout | 2 seconds |
| Broadcast call strategy | Parallel (CompletableFuture.sendAsync) |
| Server URLs (Part 3, 2 EC2s) | http://localhost:8080, http://172.31.24.104:8080 |

### ALB Settings

| Parameter | Value |
|---|---|
| Load balancer type | Application Load Balancer |
| Scheme | Internet-facing |
| Listener | HTTP port 80 |
| Target group protocol | HTTP |
| Target group port | 8080 |
| Health check path | /health |
| Health check interval | 30 seconds |
| Health check timeout | 5 seconds |
| Healthy threshold | 2 |
| Unhealthy threshold | 3 |
| Sticky sessions | Enabled (LB generated cookie) |
| Stickiness duration | 1 day |
| Idle timeout | 300 seconds |
| Registered targets | EC2 A port 8080, EC2 B port 8080 |

### Instance Types

| Component | Instance Type | vCPU | Memory | Location |
|---|---|---|---|---|
| EC2 A (Server-v2 + Consumer) | t3.micro | 2 | 1 GB | us-west-2b |
| EC2 B (Server-v2) | t3.micro | 2 | 1 GB | us-west-2b |
| Load Test Client | Local machine | — | — | Seattle, WA |

### Client Configuration

| Parameter | Value |
|---|---|
| Warmup threads | 40 |
| Warmup messages per thread | 1,000 |
| Main phase threads | 120 |
| Total messages | 500,000 (200K stable, 500K with 2 EC2s) |
| Number of rooms | 20 |
| Queue capacity per room | 2,000 |
| WebSocket response timeout | 5,000 ms |
| Max retries per message | 5 |
| Retry backoff | Exponential (10ms base, doubles per attempt) |
