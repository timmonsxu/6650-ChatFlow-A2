# CS6650 Assignment 2 - Implementation Plan

## Architecture Overview

```
[Load Test Client (1 program, N threads)]
               |
              ALB  (Part 3)
           /  |  |  \
     [S1][S2][S3][S4]   server-v2, ports 8080/8082/8083/8084
           \  |  |  /
           [SQS x20]    one standard queue per room: chatflow-room-1 ~ chatflow-room-20
               |
          [Consumer]    port 8081, same EC2
               |
    POST /internal/broadcast/{roomId}
               |
     back to whichever Server holds the sessions
```

All processes run on the same EC2 instance, on different ports. Part 3 scales Server
instances by adding more ports behind ALB; Consumer stays as a single instance.

---

## Key Design Decisions

**MessageType handling (relaxed semantic mode)**
A1's MessageGenerator produces 90% TEXT, 5% JOIN, 5% LEAVE in random order, meaning
TEXT messages often arrive before JOIN. Strict enforcement would cause massive load-test
failures and distort throughput numbers, which is not the goal of this assignment.
Decision: Server-v2 treats the first message from any session as an implicit JOIN.
A LEAVE message removes the session from the room map. afterConnectionClosed() serves
as a safety net to clean up sessions that disconnect without sending LEAVE.

**Room alignment**
A WebSocket session can only belong to one room in the server's roomSessions map.
If a session sends messages with random roomIds, its room membership becomes unstable
and broadcast delivery breaks. The only clean solution is: one thread = one session =
one fixed roomId for all messages that thread sends.
Decision: each SenderThread creates its own dedicated WebSocket session connected to
/chat/{roomId}, and only generates messages whose payload roomId matches that same
roomId. N threads = N sessions. Server-v2 maintains
ConcurrentHashMap<String, Set<WebSocketSession>> roomSessions where each room maps
to multiple sessions (one per thread assigned to that room). This eliminates all
concurrency issues on the session write path since no two threads share a session.

**SQS over RabbitMQ**
Server runs on EC2 in us-west-2. SQS is in the same region, accessible via IAM Role
(no credentials in code), zero infrastructure to manage, and failure rate is negligible.
Decision: 20 FIFO SQS queues named chatflow-room-1.fifo through chatflow-room-20.fifo.
FIFO queues guarantee ordering within a MessageGroupId. We will use the roomId as the
MessageGroupId so messages within a room are delivered in order. Each message also needs
a MessageDeduplicationId; we use the UUID generated for messageId.

**Consumer broadcast via internal REST**
Consumer is a separate Spring Boot process and does not hold WebSocket sessions.
Server-v2 holds the sessions. Decision: Consumer calls Server-v2's internal REST
endpoint POST /internal/broadcast/{roomId} with the message body. Server-v2 then
pushes to all sessions in that room. In Part 3, Consumer calls all known Server
addresses; each Server broadcasts only the sessions it holds.

**Single Consumer instance**
Consumer is not the throughput bottleneck (SQS poll + one HTTP call per message).
Running multiple Consumer instances would require coordinating which Consumer owns
which queues. Decision: one Consumer process, configurable thread pool for polling.

---

## Repository Structure Changes

```
6650-ChatFlow-A2/
  client-part1/       (keep, modify SenderThread + MessageGenerator)
  client-part2/       (keep, same modifications as client-part1)
  server-v2/          (new module, copied from server/ then modified)
  consumer/           (new Spring Boot module)
  deployment/         (new, ALB config + startup scripts)
  monitoring/         (new, CloudWatch / SQS metrics notes)
  results/            (keep, add A2 test results)
  plan.md             (this file)
```

The original server/ folder is kept intact as the A1 record. All A2 server work goes
into server-v2/.

---

## Step-by-Step Implementation Plan

### Step 0 - AWS Setup (before writing any code)

1. Create 20 SQS FIFO queues in us-west-2:
   names chatflow-room-1.fifo through chatflow-room-20.fifo,
   visibility timeout 30s, message retention 1 hour.
   Content-based deduplication can be disabled since we supply MessageDeduplicationId explicitly.
2. Attach an IAM Role to the EC2 instance with policy:
   sqs:SendMessage, sqs:ReceiveMessage, sqs:DeleteMessage, sqs:GetQueueUrl
   on resource arn:aws:sqs:us-west-2:*:chatflow-room-*.
3. Note all 20 queue URLs; they go into server-v2 and consumer config files.

---

### Step 1 - client-part1: Fix Room Alignment

Files to change: SenderThread.java, MessageGenerator.java, ChatMessage.java, ChatClient.java

**ChatMessage.java**
Add roomId to the JsonPayload inner class so the server receives it in the message body.
Server-v2 uses payload roomId to decide which SQS queue to publish to.

**MessageGenerator.java**
Keep existing random roomId (1-20) and random messageType (90% TEXT / 5% JOIN / 5% LEAVE)
logic unchanged. MessageGenerator is not aware of thread-to-room assignment; it produces
a mixed stream. The per-thread roomId filtering is handled by SenderThread.

**SenderThread.java**
Each SenderThread is constructed with a fixed roomId. It creates its own dedicated
WebSocket session connected to /chat/{roomId}. It only takes messages from the queue
whose payload roomId matches its own roomId, discarding (re-queuing or skipping) others.
Success check changes from response.contains("OK") to response.contains("RECEIVED")
to match server-v2's ack format.

**ChatClient.java**
Warmup phase: 32 threads, each gets a roomId via (index % 20) + 1.
Main phase: 512 threads, each gets a roomId via (index % 20) + 1.
All threads share one BlockingQueue. Each SenderThread filters by its own roomId.
N threads total = N WebSocket sessions total. Server sees ~25-26 sessions per room
(512 / 20) during main phase.

---

### Step 2 - server-v2: Copy server/ and Modify

Copy the entire server/ directory to server-v2/. Changes are confined to:

**ChatWebSocketHandler.java** (main changes)

Add a SqsPublisher dependency (new class, see below).

Change handleTextMessage logic:
```
receive message
  -> parse + validate (same as A1)
  -> determine effective messageType:
       if session not yet in rooms map -> treat as implicit JOIN, add to rooms map
       if messageType == LEAVE         -> remove from rooms map
       else                            -> session already in room, proceed
  -> publish QueueMessage to SQS (via SqsPublisher)
  -> send ack: {"status": "RECEIVED", "messageId": "<uuid>"}
```

Add new REST handler method for internal broadcast (or put it in a separate
InternalBroadcastController):
```
POST /internal/broadcast/{roomId}
body: QueueMessage JSON
action: find all sessions in rooms.get(roomId), send message text to each
```

**New file: model/QueueMessage.java**
Matches the format required by the assignment:
```java
String messageId   // UUID generated by server
String roomId
String userId
String username
String message
String timestamp
String messageType
String serverId    // e.g. "server-8080", set at startup via application.properties
String clientIp
```

**New file: sqs/SqsPublisher.java**
Wraps the AWS SQS v2 SDK. At startup, loads all 20 queue URLs from config.
Provides: void publish(QueueMessage msg) which calls sendMessage to the correct queue.
Uses the default credential chain (picks up EC2 IAM Role automatically, no keys in code).

**application.properties additions**
```
server.port=8080
app.server-id=server-8080
app.sqs.queue-base-url=https://sqs.us-west-2.amazonaws.com/<account>/chatflow-room-
app.sqs.region=us-west-2
```

**pom.xml additions**
Add AWS SDK v2 BOM and the sqs dependency:
```xml
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>sqs</artifactId>
</dependency>
```

---

### Step 3 - consumer: New Spring Boot Module

Create a new Maven project under consumer/. This is a standalone Spring Boot app
with no WebSocket server of its own. It only polls SQS and calls Server-v2's
internal broadcast endpoint.

**SqsConsumerService.java**
At startup, spawn a configurable thread pool (default 20 threads).
Assign rooms to threads: with 20 threads and 20 rooms, each thread owns one queue.
Each thread runs a loop:
```
while running:
  poll SQS (long polling, waitTimeSeconds=20, maxMessages=10)
  for each message:
    call BroadcastClient.broadcast(roomId, queueMessage)
    delete message from SQS
```

**BroadcastClient.java**
Holds a list of Server-v2 base URLs (loaded from config).
For each URL, calls POST {baseUrl}/internal/broadcast/{roomId}.
Uses a simple Java HttpClient (JDK 11+), no extra dependency needed.
In Part 3, the list has 4 entries; in Part 1, just one entry.

**application.properties**
```
server.port=8081
app.sqs.queue-base-url=https://sqs.us-west-2.amazonaws.com/<account>/chatflow-room-
app.sqs.region=us-west-2
app.consumer.threads=20
app.broadcast.server-urls=http://localhost:8080
```

**Health endpoint**
Add a simple GET /health returning 200 so the process is easy to monitor.

---

### Step 4 - Single Instance Testing (Assignment Part 1 + Part 2)

Deployment on EC2:
- Start server-v2 on port 8080.
- Start consumer on port 8081 with broadcast url = http://localhost:8080.
- Run client-part1 pointing at ws://ec2-ip:8080, collect throughput metrics.
- Run client-part2 pointing at same URL, collect latency metrics.

Tune consumer thread count (10, 20, 40, 80) and observe:
- SQS queue depth (via AWS Console or CloudWatch).
- Consumer throughput vs producer throughput.
- Aim for stable queue depth (good profile = plateau, bad = sawtooth).

---

### Step 5 - Load Balancing Setup (Assignment Part 3)

On the same EC2, start additional Server-v2 instances on different ports:
- Instance A: port 8080 (app.server-id=server-8080)
- Instance B: port 8082 (app.server-id=server-8082)
- Instance C: port 8083 (app.server-id=server-8083)
- Instance D: port 8084 (app.server-id=server-8084)

Update consumer application.properties:
```
app.broadcast.server-urls=http://localhost:8080,http://localhost:8082,http://localhost:8083,http://localhost:8084
```

Consumer will call all four broadcast endpoints for every message; each Server
broadcasts only to sessions it holds, so sessions without a match silently no-op.

AWS ALB configuration (in deployment/ folder):
- Create Target Group, register EC2 on ports 8080, 8082, 8083, 8084.
- Enable sticky sessions (LB Cookie, duration 1 day) so a Client thread always
  routes to the same Server instance.
- Health check path: /health, interval 30s.
- Client points at the ALB DNS instead of the EC2 IP directly.

Test scenarios:
1. Single instance (no ALB): 500K messages, baseline throughput.
2. 2 instances (8080 + 8082) behind ALB: 500K messages.
3. 4 instances (8080 + 8082 + 8083 + 8084) behind ALB: 500K messages, then 1M.

---

### Step 6 - Monitoring and Results

Collect for each test run:
- Client output: total runtime, throughput (msg/s), failures, retries.
- SQS CloudWatch metrics: NumberOfMessagesSent, NumberOfMessagesReceived,
  ApproximateNumberOfMessagesVisible (queue depth).
- EC2 CloudWatch: CPU utilization per instance.

Screenshots required for submission:
- Client terminal output (Part 1 and Part 2 format).
- SQS console showing queue depth over time (stable plateau = good).
- ALB console showing request distribution across targets.

---

## File Change Summary

| File | Action | Reason |
|---|---|---|
| server/ | Keep as-is | A1 record |
| server-v2/ | New (copy of server/) | A2 server |
| server-v2 ChatWebSocketHandler | Modify | Replace echo with SQS publish + implicit JOIN logic |
| server-v2 QueueMessage | New | SQS message format |
| server-v2 SqsPublisher | New | AWS SDK SQS wrapper |
| server-v2 InternalBroadcastController | New | REST endpoint for Consumer to call |
| server-v2 pom.xml | Modify | Add AWS SDK v2 dependency |
| consumer/ | New module | SQS polling + broadcast |
| client-part1 MessageGenerator | No change | Random roomId + messageType distribution preserved |
| client-part1 ChatMessage | Modify | Include roomId in JSON payload |
| client-part1 SenderThread | Modify | Fixed roomId per thread, own session, filter queue by roomId, RECEIVED ack |
| client-part1 ChatClient | Modify | Thread-to-room assignment via index % 20, shared BlockingQueue |
| client-part2 | Modify | Same changes as client-part1 |
| deployment/ | New | ALB config, startup scripts |
| monitoring/ | New | CloudWatch notes and SQS metric queries |

---

## Open Questions (resolved)

- RabbitMQ vs SQS: **SQS** (same AWS region, IAM Role, no infra to manage)
- Consumer location: **same EC2, port 8081**
- Part 3 multiple servers: **same EC2, ports 8080/8082/8083/8084**
- MessageType enforcement: **relaxed - implicit JOIN on first message**
- Room alignment: **each SenderThread has its own dedicated session + fixed roomId; server roomSessions maps roomId -> Set of sessions (one per thread)
- FIFO vs Standard SQS: **FIFO** (required by assignment spec)
