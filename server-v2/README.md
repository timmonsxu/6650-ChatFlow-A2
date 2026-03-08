# ChatFlow Server v2

Spring Boot WebSocket server with AWS SQS integration. Receives messages from clients,
validates them, publishes to SQS asynchronously, and exposes an internal broadcast
endpoint for the Consumer to trigger WebSocket push delivery.

## Architecture

```
Client (WebSocket)
    |
    | ws://host/chat/{roomId}
    |
ChatWebSocketHandler
    |--- validate message
    |--- register session in roomSessions map (implicit JOIN on first message)
    |--- send RECEIVED ack to client immediately
    |--- publishAsync() to SqsPublisher (fire-and-forget)

SqsPublisher (background, 20 threads)
    |--- builds QueueMessage with UUID messageId, serverId, clientIp
    |--- sends to chatflow-room-{roomId}.fifo
         MessageGroupId = roomId  (ordering per room)
         MessageDeduplicationId = messageId UUID

InternalBroadcastController
    |--- POST /internal/broadcast/{roomId}
    |--- returns HTTP 200 immediately
    |--- submits broadcastToRoom to broadcastExecutor (40 threads, bounded queue)

broadcastExecutor
    |--- calls ChatWebSocketHandler.broadcastToRoom(roomId, messageJson)
    |--- pushes message to all ConcurrentWebSocketSessionDecorator instances in room
         OverflowStrategy.DROP — never terminates session on buffer overflow
```

## Key Design Decisions

**Async SQS publish:** `SqsPublisher.publishAsync()` submits the SQS call to a
background thread pool. The client-facing latency path only includes parse + validate
+ send ack. SQS confirmation (~10-30ms) does not block the client.

**ConcurrentWebSocketSessionDecorator:** All sessions are wrapped immediately in
`afterConnectionEstablished`. This serializes concurrent writes from two sources:
(1) Tomcat WebSocket thread sending ack, (2) broadcastExecutor thread pushing
broadcast. No manual `synchronized` blocks needed anywhere.

**Overflow strategy DROP:** In load-test context, clients do not need to receive
broadcasts — they only need the RECEIVED ack. Using DROP instead of TERMINATE
ensures session stability even when clients cannot consume broadcasts fast enough
(common when client is remote with high RTT).

**Bounded broadcastExecutor queue:** Uses `ArrayBlockingQueue(2000)` with
`DiscardPolicy` to prevent unbounded memory growth during long-running tests where
Consumer submits tasks faster than they can execute.

**Relaxed MessageType semantics:** The first message from any session triggers an
implicit JOIN (session added to roomSessions). LEAVE removes the session. This
avoids load-test failures from the random 90/5/5 TEXT/JOIN/LEAVE message distribution
where TEXT often arrives before JOIN.

**Session room membership:** `afterConnectionEstablished` does NOT register the
session. Registration happens on the first `handleTextMessage` call, using the
payload roomId (not the URL roomId). This ensures the session is always in the
correct room for broadcast routing.

## Module Structure

```
src/main/java/com/chatflow/server/
  ChatFlowServerApplication.java       Spring Boot entry point
  config/
    WebSocketConfig.java               Registers /chat/{roomId} WebSocket endpoint
  controller/
    HealthController.java              GET /health — returns status + serverId
    InternalBroadcastController.java   POST /internal/broadcast/{roomId}
  handler/
    ChatWebSocketHandler.java          Core WebSocket handler — validation, session
                                       management, ack, broadcast
  model/
    ChatMessage.java                   Incoming WS message (includes roomId field)
    MessageType.java                   Enum: TEXT, JOIN, LEAVE
    QueueMessage.java                  SQS message format per assignment spec
  sqs/
    SqsPublisher.java                  Async SQS publish with background thread pool
```

## Configuration (application.properties)

| Property | Default | Description |
|---|---|---|
| server.port | 8080 | HTTP/WebSocket port |
| server.tomcat.threads.max | 500 | Tomcat thread pool size |
| server.tomcat.threads.min-spare | 50 | Minimum spare threads |
| app.server-id | server-8080 | Server identity tag in QueueMessage |
| app.sqs.region | us-west-2 | AWS region |
| app.sqs.account-id | 449126751631 | AWS account ID |
| app.sqs.queue-name-prefix | chatflow-room- | Prefix for SQS queue names |

## Running Multiple Instances

Override port and server-id at startup to run multiple instances on the same machine:

```bash
# Instance A (default)
java -jar server-v2.jar

# Instance B
java -Dserver.port=8082 -Dapp.server-id=server-8082 -jar server-v2.jar

# Instance C
java -Dserver.port=8083 -Dapp.server-id=server-8083 -jar server-v2.jar

# Instance D
java -Dserver.port=8084 -Dapp.server-id=server-8084 -jar server-v2.jar
```

## Building and Deploying

```bash
# Build
mvn clean package

# Deploy to EC2
scp target/server-v2-1.0.0.jar ec2-user@<EC2_IP>:~/server-v2.jar

# Run on EC2 (IAM Role with SQS permissions must be attached to the instance)
java -jar server-v2.jar
```

## IAM Requirements

The EC2 instance must have an IAM Role with the following SQS permissions:

```
sqs:SendMessage
sqs:GetQueueUrl
```

Resource: `arn:aws:sqs:us-west-2:449126751631:chatflow-room-*.fifo`

## Health Check

```bash
curl http://localhost:8080/health
# {"status":"UP","serverId":"server-8080","timestamp":"..."}
```
