# Client Part 1 - Local Testing & Optimization Log

## Test Environment
- **Server**: Spring Boot WebSocket, running on localhost:8080
- **Client**: Pure Java + Java-WebSocket 1.5.4
- **Machine**: Local development machine (Bellevue, WA)
- **Messages**: 500,000 total

---

## Optimization 1: Eliminate Per-Message Room Reconnection

### Problem
First run showed **3,791 connections** for only 4,000 messages (warmup phase). Each sender thread was reconnecting every time it got a message with a different `roomId`.

**Root cause**: `SenderThread.run()` checked if the current message's roomId matched the WebSocket connection's room, and reconnected if different. Since roomId is random 1-20, almost every message triggered a reconnect.

```
// BEFORE (SenderThread.java)
while (shouldContinue(sent)) {
    ChatMessage msg = queue.poll(1, TimeUnit.SECONDS);
    if (msg == null) break;

    // This caused a reconnect for almost every message!
    if (!client.getURI().getPath().endsWith("/" + msg.getRoomId())) {
        client.closeBlocking();
        client = connectionManager.createConnection(msg.getRoomId());
    }

    sent += sendWithRetry(client, msg);
}
```

### Fix
Each sender thread connects to one random room at startup and reuses that connection for ALL messages. In A1, the server is an echo server — room is just a URL path parameter and doesn't affect message handling. The message's `roomId` field is still randomized in the payload.

```
// AFTER (SenderThread.java)
int connRoom = ThreadLocalRandom.current().nextInt(1, 21);
client = connectionManager.createConnection(connRoom);

while (shouldContinue(sent)) {
    ChatMessage msg = queue.poll(2, TimeUnit.SECONDS);
    if (msg == null) break;
    sent += sendWithRetry(client, msg);
}
```

### Result
| Metric | Before | After |
|--------|--------|-------|
| Connections (warmup, 4 threads) | 3,791 | 4 |
| Warmup throughput | 1,988 msg/s | 13,029 msg/s |

**~6.5x throughput improvement** just by eliminating unnecessary reconnections.

---

## Optimization 2: Command-Line Server URL Override

### Problem
Had to edit `ChatClient.java` source code and recompile every time we switched between localhost and EC2 testing.

### Fix
Accept server URL as optional command-line argument, falling back to the default EC2 address.

```java
// ChatClient.java
String serverUrl = args.length > 0 ? args[0] : SERVER_URL;
```

Usage:
```bash
# Local testing
java -jar target/client-part1-1.0.0.jar ws://localhost:8080

# EC2 (uses default)
java -jar target/client-part1-1.0.0.jar
```

---

## Optimization 3: Accurate Remaining Message Count

### Problem
`remaining` was calculated as `TOTAL_MESSAGES - WARMUP_TOTAL` (hardcoded). If any warmup messages failed, the count would be wrong.

### Fix
Use actual warmup success count:

```java
// BEFORE
int remaining = TOTAL_MESSAGES - WARMUP_TOTAL;

// AFTER
int remaining = TOTAL_MESSAGES - (int) warmupMetrics.getSuccessCount();
```

---

## Final Local Test Results (500K messages, localhost)

```
============================================
  ChatFlow Load Test Client - Part 1
  Server: ws://localhost:8080
  Total messages: 500000
  Warmup: 32 threads × 1000 msgs
  Main:   128 threads
============================================

Warmup Phase Results:
  Successful messages : 32,000
  Throughput          : 40,973 msg/s
  Total connections   : 32

Main Phase Results:
  Successful messages : 468,000
  Throughput          : 90,417 msg/s
  Total connections   : 128

Overall Summary:
  Total successful    : 500,000
  Total failed        : 0
  Total wall time     : 5.98 seconds
  Overall throughput  : 83,640 msg/s
```

---

---

## EC2 Thread Count Benchmarking (t3.micro, 2 vCPU, 1GB RAM)

| Threads | Throughput (msg/s) | Little's Law Prediction | Actual/Predicted | Notes |
|---------|-------------------|------------------------|-----------------|-------|
| 128 | 6,291 | 6,400 | 98% | Near-perfect match |
| 256 | 11,801 | 12,800 | 92% | Still efficient |
| 384 | 14,734 | 19,200 | 77% | Server starting to saturate |
| 512 | 19,823 | 25,600 | 77% | Sweet spot |
| 640 | 20,435 | 32,000 | 64% | Diminishing returns |
| 768 | N/A | 38,400 | — | Server connection refused |

**Decision: 512 threads for Main Phase** — best throughput before server saturation.

Server saturation explanation: t3.micro has 2 vCPUs. Beyond ~500 concurrent WebSocket connections, Tomcat thread pool and CPU become bottlenecks. Adding more client threads only increases contention without improving throughput.

---

## Key Takeaways

1. **Connection reuse** was the single biggest performance win. In distributed systems, connection establishment (TCP handshake + WebSocket upgrade) is expensive. Reusing persistent connections is critical for throughput — this is exactly why protocols like HTTP/2, gRPC, and WebSocket exist.

2. **Little's Law accurately predicts throughput** when the server is not saturated (128-256 threads, 92-98% match). Deviation from prediction indicates server-side bottleneck.

3. **Benchmarking beats guessing.** The theoretical optimal thread count from Little's Law is a starting point, but real performance depends on server capacity, network conditions, and resource limits.
