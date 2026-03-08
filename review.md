# A2 Debug Review — High Concurrency Troubleshooting Log

A complete record of every issue encountered from the start of A2 load testing through final system stabilization, including the reasoning, solution chosen, and outcome for each round.

---

## Issue 1: SenderThread Misidentifying Broadcast Messages as Server Errors

### Symptom
Warmup phase flooded with:
```
[Sender-room5] Server error: {"messageId":"...","roomId":"05","userId":"..."}
```

### Analysis
In A2, every sent message produces two server-side pushes:
1. A RECEIVED ack sent immediately by Server after SQS publish
2. The full message pushed back by Server after Consumer calls the broadcast endpoint

A1's `sendAndWait` used `CountDownLatch(1)`, which unblocked on the first response. If the broadcast message arrived before the ack, or a previous message's broadcast arrived during the next `sendAndWait` window, `lastResponse` was overwritten with the broadcast payload — which doesn't contain "RECEIVED" — causing a false server error.

### Solution
Replaced `CountDownLatch` in `ConnectionManager.ChatWebSocketClient` with a `LinkedBlockingQueue`. `sendAndWait` now drains the queue in a loop, accepts only the response containing "RECEIVED", and silently discards broadcast messages.

### Result
✅ Server errors eliminated. Warmup progressed normally into Main Phase.

---

## Issue 2: `TEXT_PARTIAL_WRITING` — Concurrent Writes to the Same WebSocket Session

### Symptom
Server logged heavily:
```
The remote endpoint was in state [TEXT_PARTIAL_WRITING] which is an invalid state for called method
```
Client reported `Connection timed out: connect`. Sessions were forcibly closed en masse.

### Analysis
Two threads were concurrently writing to the same WebSocket session:
- The Tomcat WebSocket thread sending the RECEIVED ack inside `handleTextMessage`
- A Tomcat HTTP thread processing the Consumer's broadcast request inside `broadcastToRoom`

Spring's WebSocket `sendMessage()` is not thread-safe. Concurrent writes caused state machine conflicts. Tomcat's `ExceptionWebSocketHandlerDecorator` caught the exception and forcibly closed the session.

### Attempted Fix (incomplete)
Added `synchronized(s)` only inside `broadcastToRoom`, but forgot the ack send line inside `handleTextMessage`. The problem persisted.

### Correct Solution
Replaced all manual `synchronized` blocks with `ConcurrentWebSocketSessionDecorator`. The raw session is wrapped immediately in `afterConnectionEstablished`. All subsequent writes go through the Decorator, which serializes sends internally.

### Result
✅ `TEXT_PARTIAL_WRITING` eliminated. Sessions no longer forcibly closed.

---

## Issue 3: Low Throughput (Warmup ~300 msg/s vs A1's ~800 msg/s)

### Symptom
Warmup throughput dropped from A1's ~800 msg/s to ~300 msg/s. Queue depth spiked to 8k+ during Main Phase.

### Analysis
Two compounding causes:

**Cause 1 — Synchronous SQS publish blocking ack:**
`sqsPublisher.publish()` was a blocking call. Every message waited for SQS confirmation (~10-30ms) before the ack could be sent to the Client. A1 had no such step; latency was just network RTT.

**Cause 2 — Consumer throughput too low to keep up with Producer:**
Consumer's 20 threads processed messages serially: poll SQS → HTTP broadcast → delete. The broadcast HTTP call was slow due to residual lock contention from Issue 2. Consumer throughput was far below Producer throughput, causing continuous queue buildup.

### Solution
Two changes applied together:
1. `SqsPublisher.publish()` replaced with `publishAsync()` — SQS call submitted to a 20-thread background pool; Server returns ack immediately without waiting for SQS confirmation.
2. `InternalBroadcastController` returns HTTP 200 immediately and submits `broadcastToRoom` to a dedicated 40-thread `broadcastExecutor`. Tomcat threads are no longer held waiting for broadcast completion.

### Result
✅ Warmup throughput improved from 300 to 600+ msg/s.
⚠️ Main Phase with 512 threads still crashed. Moved to next issue.

---

## Issue 4: Tomcat Thread Pool Saturation (crash with 512 threads)

### Symptom
After 512 SenderThreads started in Main Phase, Consumer began reporting:
```
Broadcast to http://localhost:8080/internal/broadcast/xx failed: HTTP connect timed out
```
Server reported `Broken pipe`, then eventually `Connection pool shut down`.

### Analysis
512 concurrent WebSocket connections sending messages at high frequency, combined with 20 Consumer HTTP broadcast requests, exceeded Tomcat's default 200-thread limit. With the thread pool saturated, Consumer's HTTP requests could not even complete the TCP handshake. Server failed to accept new connections for an extended period, triggering Spring's shutdown hook. `@PreDestroy` callbacks executed sequentially, closing `SqsClient`, after which background threads still trying to publish reported `Connection pool shut down`.

### Solution
Reduced Main Phase thread count from 512 to 128, keeping it below the Tomcat default thread limit.

### Result
✅ 100K messages completed successfully. Main Phase: 1,844 msg/s.
⚠️ 500K still failed. Moved to next issue.

---

## Issue 5: `ConcurrentWebSocketSessionDecorator` Overflow Terminating Sessions

### Symptom
Server logged:
```
Send time 5032 (ms) for session '...' exceeded the allowed limit 5000
Message will not be sent because the WebSocket session has been closed
```
Consumer then started reporting `request timed out` again and the system crashed.

### Analysis
Client was running locally; Server was in us-west-2 with RTT ~20-40ms. Consumer was consuming SQS messages faster than the Client could consume broadcast messages. The session's TCP send buffer backed up, causing writes to block.

`ConcurrentWebSocketSessionDecorator`'s default overflow strategy is `TERMINATE`: when a single send exceeds `SEND_TIME_LIMIT_MS` (set to 5 seconds), the session is forcibly closed. This caused SenderThread connections to drop, Client reconnections flooded the Server, Tomcat saturated again, Consumer timed out, and the system crashed.

### Solution
Changed the Decorator's overflow strategy from `TERMINATE` to `DROP`: when the buffer is full, new broadcast messages are silently discarded without closing the session. Also:
- `SEND_TIME_LIMIT_MS` increased from 5s to 30s
- `BUFFER_SIZE_LIMIT` increased from 64KB to 512KB

In a load test context, the Client doesn't need to receive broadcasts — it only needs the RECEIVED ack. Dropping broadcasts preserves session stability without affecting throughput metrics.

### Result
✅ 100K messages completed. 0 failures. Main Phase: 1,844 msg/s.
⚠️ 500K still crashed after ~7 minutes. Moved to next issue.

---

## Issue 6: Unbounded broadcastExecutor Queue Causing Memory Leak

### Symptom
500K test crashed after ~7 minutes. Consumer reported `request timed out`, Server reported `Connection pool shut down`. 100K completed fine in ~85 seconds without triggering this.

### Analysis
`InternalBroadcastController` submitted tasks to `broadcastExecutor` (`Executors.newFixedThreadPool(40)`), which internally uses an **unbounded `LinkedBlockingQueue`**.

In the 500K scenario, Consumer submitted broadcast tasks faster than 40 threads could execute them. Hundreds of thousands of task objects accumulated in the queue, filling the JVM heap, causing GC pressure to spike. Server response times degraded, Tomcat threads got delayed by GC pauses, Consumer HTTP calls timed out, and the system crashed.

100K completed quickly enough that queue buildup never reached the critical threshold.

### Solution
Replaced `Executors.newFixedThreadPool` with an explicit `ThreadPoolExecutor` using a bounded `ArrayBlockingQueue(2000)` and `DiscardPolicy`: when the queue is full, new tasks are silently dropped without throwing exceptions or blocking.

### Result
✅ Combined with the Tomcat thread count fix below, 200K messages completed successfully.

---

## Issue 7: Tomcat Thread Count Insufficient for Long-Running Tests

### Symptom
500K test crashed after some time with the same symptoms as Issue 4, but later in the run.

### Analysis
Although Main Phase had only 128 threads — theoretically below Tomcat's default 200-thread limit — additional background threads competed for CPU:
- SqsPublisher: 20 async threads
- broadcastExecutor: 40 threads

On a t3.micro with 2 vCPUs, these background threads consumed CPU cycles, causing Tomcat worker threads to take longer to process each request. Over time, Tomcat's effective throughput degraded below the incoming request rate, Consumer HTTP calls started timing out, and the system eventually crashed.

### Solution
Explicitly configured Tomcat's thread pool in `application.properties`:
```
server.tomcat.threads.max=400
server.tomcat.threads.min-spare=50
```

### Result
✅ 200K messages completed stably. 300K+ still failed on single EC2.

---

## Issue 8: Increasing Consumer Threads to 80 Made Things Worse

### Symptom
Consumer threads increased from 20 to 80 to improve queue depth. Queue depth reached 5k (worse than before) and the system crashed faster.

### Analysis
The root cause was the "more threads = worse performance" phenomenon on a 2-vCPU machine. Total active threads at 80 Consumer threads: Consumer 80 + broadcastExecutor 80 + SqsPublisher 20 + Tomcat ~200 = ~380 threads competing for 2 vCPUs. Context switch overhead dominated, leaving less actual CPU time for business logic. Consumer throughput dropped rather than increased.

The key insight: on resource-constrained hardware, the optimal thread count is approximately `vCPUs × (1 + wait_time / compute_time)`. Beyond this sweet spot, adding threads causes degradation.

### Solution
Reverted to Consumer 20 threads + broadcastExecutor 40 threads, which had been empirically validated as the optimal configuration for this hardware.

### Result
✅ System stabilized again at 200K messages.

---

## Issue 9: Two-Server Single EC2 Setup Increased Queue Depth

### Symptom
Running two Server instances on the same EC2 at ports 8080 and 8082 resulted in higher queue depth than single-instance, not lower.

### Analysis
Two separate JVM processes on the same EC2 meant more total threads competing for the same 2 vCPUs. Additionally, Consumer was still only calling localhost:8080 (configuration not updated), so all broadcast load still hit one instance. Even after fixing the Consumer config, each broadcast call hit both servers sequentially, doubling Consumer processing time per message.

### Solution
Provisioned a second EC2 instance (EC2 B) so each Server process has its own dedicated 2 vCPUs. Updated Consumer config to point to both EC2 private IPs. Registered both EC2 instances in the ALB Target Group.

### Result
✅ True CPU isolation achieved. Moved to next issue.

---

## Issue 10: Consumer Broadcasting to Multiple Servers Sequentially (Serial Calls)

### Symptom
With two EC2s, queue depth was higher than single EC2, even though Client throughput improved.

### Analysis
`BroadcastClient.broadcast()` iterated over server URLs in a `for` loop — serial execution. With 2 servers, total Consumer processing time per message = time(EC2 A call) + time(EC2 B call). With 4 servers it would be 4× the single-server time. Producer speed increased (more Server capacity) but Consumer speed halved (more serial calls), causing net queue depth increase.

### Solution
Rewrote `broadcast()` to use `CompletableFuture` with `sendAsync()`:
- All HTTP calls fired simultaneously
- `CompletableFuture.allOf(...).join()` waits for all to complete
- Total time = max(individual call times) instead of sum(individual call times)
- Adding more servers no longer increases Consumer processing time per message

### Result
✅ System stabilized with 2 EC2s. Throughput improved. Queue depth under control.

---

## Issue 11: EC2 B Missing IAM Role — SQS Publish Failures

### Symptom
EC2 B's Server logged:
```
Unable to load credentials from any of the providers in the chain
```
SQS publishes failed on EC2 B. Some queues were empty because messages destined for those queues never got published.

### Analysis
EC2 B was newly provisioned and had no IAM Role attached. EC2 A had an IAM Role with SQS permissions, which the AWS SDK's default credential chain picked up automatically via IMDS. EC2 B had no such role, so credential resolution failed for all SQS operations.

### Solution
Attached the same IAM Role to EC2 B via AWS Console: EC2 → Actions → Security → Modify IAM role.

### Result
✅ SQS publishes on EC2 B began working. Full two-EC2 pipeline operational.

---

## Final Architecture

```
[Load Test Client — local machine, 128 threads]
               |
        ALB (port 80, Sticky Session enabled)
           /          \
    [EC2 A]           [EC2 B]
  Server-v2           Server-v2
  port 8080           port 8080
  Consumer
  port 8081
       |
  [SQS x20 FIFO queues]
  chatflow-room-01.fifo ~ chatflow-room-20.fifo
       |
  Consumer polls all 20 queues (20 threads)
  Broadcasts to EC2 A and EC2 B IN PARALLEL
  via CompletableFuture.sendAsync()
```

---

## Performance Summary

| Configuration | Messages | Main Phase Throughput | Outcome |
|---|---|---|---|
| A1 baseline | 500K | ~8,000 msg/s | ✅ Pass |
| A2 initial (sync SQS, no async) | 500K | ~300 msg/s | ❌ Queue overflow |
| A2 + async SQS + async broadcast | 100K | 1,844 msg/s | ✅ Pass |
| A2 + async SQS + async broadcast | 500K | — | ❌ Crash at ~7 min |
| A2 + bounded queue + Tomcat tuning | 200K | 2,190 msg/s | ✅ Pass |
| A2 + 2 EC2s + parallel broadcast | 500K | ~2,000+ msg/s | ✅ Pass |

---

## Key Engineering Lessons

**1. Async decoupling is the primary lever for throughput improvement.**
Removing SQS publish and broadcast execution from the client-facing request path reduced per-message latency from ~100ms back toward RTT levels. The Client only waits for: network RTT + parse + validate + enqueue async task.

**2. Every thread pool must be bounded.**
An unbounded `LinkedBlockingQueue` will cause memory exhaustion under sustained high load. Always use `ArrayBlockingQueue` with a defined capacity and an explicit rejection policy (`DiscardPolicy` for fire-and-forget tasks, `CallerRunsPolicy` for back-pressure).

**3. Load test semantics differ from production semantics.**
In production, `TERMINATE` on `ConcurrentWebSocketSessionDecorator` overflow is correct — slow consumers should be evicted. In a load test, the Client only needs the ack; it doesn't consume broadcasts. Using `DROP` prevents broadcast backpressure from destroying the send path used for acks.

**4. Shared thread pools create hidden resource contention.**
Producer (WebSocket message handling) and Consumer (broadcast HTTP calls) sharing Tomcat's thread pool caused Consumer to starve under load. The fix was a dedicated `broadcastExecutor` isolated from Tomcat.

**5. On resource-constrained hardware, more threads can be worse.**
On a 2-vCPU t3.micro, doubling thread count increased context switch overhead enough to reduce effective throughput. The optimal thread count must be empirically validated, not assumed to scale linearly.

**6. Parallel outbound calls are essential when fanning out to multiple backends.**
Serial broadcast calls to N servers multiply Consumer processing time by N. Parallel calls with `CompletableFuture.sendAsync()` keep Consumer processing time constant regardless of server count — total time equals the slowest call, not the sum of all calls.

**7. New infrastructure (EC2, IAM) must be fully configured before testing.**
EC2 B had no IAM Role, causing all SQS publishes to silently fail. Infrastructure provisioning checklist: IAM Role, Security Group inbound rules for ALB and cross-EC2 traffic, health check port accessibility.
