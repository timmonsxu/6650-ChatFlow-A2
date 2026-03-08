# ChatFlow Consumer

Standalone Spring Boot application that polls AWS SQS FIFO queues and triggers
WebSocket broadcast on all Server-v2 instances in parallel. Runs alongside
Server-v2 on EC2 A, communicating with servers via internal HTTP calls.

## Architecture

```
SqsConsumerService (20 polling threads, one per room)
    |
    | Long-poll SQS (waitTimeSeconds=20, maxMessages=10)
    | chatflow-room-01.fifo ... chatflow-room-20.fifo
    |
    v
processMessage(roomId, sqsMessage)
    |
    | 1. Parse roomId from message body
    | 2. BroadcastClient.broadcast(roomId, messageJson)
    |       fires parallel HTTP calls to ALL Server instances
    |       via CompletableFuture.sendAsync()
    |       waits for all calls with CompletableFuture.allOf().join()
    | 3. Delete message from SQS (after broadcast attempts complete)
    |
BroadcastClient
    |--- POST http://localhost:8080/internal/broadcast/{roomId}
    |--- POST http://172.31.24.104:8080/internal/broadcast/{roomId}  (EC2 B)
         (all calls parallel — total time = max, not sum)
```

## Key Design Decisions

**One thread per room:** 20 polling threads map 1:1 to 20 SQS queues. Each thread
owns its queue exclusively, eliminating SQS message visibility contention and
providing clean partitioning.

**Long polling:** `waitTimeSeconds=20` minimizes empty responses and reduces SQS
API calls, saving cost and reducing CPU idle spinning.

**Parallel broadcast to multiple servers:** In Part 3 with multiple Server instances,
`BroadcastClient` uses `CompletableFuture.sendAsync()` to call all servers
simultaneously. Total broadcast time = max(individual call times) instead of
sum(individual call times). Adding more servers does not increase Consumer
processing time per message.

**Fast-fail for offline servers:** HTTP connect timeout is 200ms. If a Server
instance is not running (e.g., during incremental deployment), the call fails
within 200ms and Consumer continues processing. The next available Server still
receives the broadcast call.

**Best-effort delivery:** If broadcast HTTP calls fail or SQS delete fails,
the message may be reprocessed after the visibility timeout (120s). No
client-side deduplication is implemented — acceptable for load-test purposes.

**Configurable server list:** `app.broadcast.server-urls` is a comma-separated
list of Server base URLs. To scale from 1 to 4 servers, only this property
needs to change — no code modifications required.

## Module Structure

```
src/main/java/com/chatflow/consumer/
  ConsumerApplication.java        Spring Boot entry point
  SqsConsumerService.java         Core polling service — thread pool, poll loop,
                                  message processing, SQS delete, metrics
  BroadcastClient.java            Parallel HTTP client — fires sendAsync() to all
                                  known Server instances simultaneously
  HealthController.java           GET /health — returns status + consumed count
```

## Configuration (application.properties)

| Property | Default | Description |
|---|---|---|
| server.port | 8081 | Consumer HTTP port (health endpoint) |
| app.sqs.region | us-west-2 | AWS region |
| app.sqs.account-id | 449126751631 | AWS account ID |
| app.sqs.queue-name-prefix | chatflow-room- | Prefix for SQS queue names |
| app.consumer.threads | 20 | Number of SQS polling threads |
| app.broadcast.server-urls | http://localhost:8080 | Comma-separated Server base URLs |

## Scaling Server URLs

Update `app.broadcast.server-urls` to match the number of running Server instances:

```
# Part 1: single server on EC2 A
app.broadcast.server-urls=http://localhost:8080

# Part 3: two servers on separate EC2s
app.broadcast.server-urls=http://localhost:8080,http://172.31.24.104:8080

# Part 3: four servers (same EC2, different ports)
app.broadcast.server-urls=http://localhost:8080,http://localhost:8082,http://localhost:8083,http://localhost:8084
```

Servers not yet started are safely skipped — connect timeout is 200ms so
Consumer threads are not blocked waiting for unreachable servers.

## Building and Deploying

```bash
# Build
mvn clean package

# Deploy to EC2 A
scp target/consumer-1.0.0.jar ec2-user@<EC2_A_IP>:~/consumer.jar

# Run on EC2 A (IAM Role with SQS permissions must be attached)
java -jar consumer.jar
```

## IAM Requirements

The EC2 instance must have an IAM Role with the following SQS permissions:

```
sqs:ReceiveMessage
sqs:DeleteMessage
sqs:GetQueueUrl
```

Resource: `arn:aws:sqs:us-west-2:449126751631:chatflow-room-*.fifo`

## Health Check and Metrics

```bash
curl http://localhost:8081/health
# {
#   "status": "UP",
#   "timestamp": "...",
#   "messagesConsumed": 12345,
#   "broadcastCalls": 12345
# }
```

`messagesConsumed` increments after each successful SQS delete.
`broadcastCalls` increments after each `BroadcastClient.broadcast()` call,
regardless of individual server success/failure.
