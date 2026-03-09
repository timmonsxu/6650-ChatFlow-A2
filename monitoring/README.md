# Monitoring — CloudWatch Metrics and SQS Monitoring

Monitoring is performed via AWS Console (CloudWatch and SQS Console).
This document describes which metrics to observe, where to find them,
and what healthy vs. unhealthy patterns look like.

---

## SQS Queue Monitoring

### Where to find it

AWS Console → SQS → select a queue → Monitoring tab.

Key metrics available directly in the SQS Console:

| Metric | What it shows |
|---|---|
| NumberOfMessagesSent | Messages published by Server-v2 per minute |
| NumberOfMessagesReceived | Messages polled by Consumer per minute |
| NumberOfMessagesDeleted | Messages successfully processed and deleted per minute |
| ApproximateNumberOfMessagesVisible | Current queue depth (messages waiting to be consumed) |
| ApproximateNumberOfMessagesNotVisible | In-flight messages (received but not yet deleted) |
| ApproximateAgeOfOldestMessage | Seconds since the oldest unprocessed message was sent |

### AWS CLI — check queue depth for all 20 rooms

```bash
for i in $(seq -f "%02g" 1 20); do
  echo -n "room-${i}: "
  aws sqs get-queue-attributes \
    --queue-url https://sqs.us-west-2.amazonaws.com/449126751631/chatflow-room-${i}.fifo \
    --attribute-names ApproximateNumberOfMessagesVisible \
    --region us-west-2 \
    --query 'Attributes.ApproximateNumberOfMessagesVisible' \
    --output text
done
```

### AWS CLI — check message rates (last 5 minutes)

```bash
aws cloudwatch get-metric-statistics \
  --namespace AWS/SQS \
  --metric-name NumberOfMessagesSent \
  --dimensions Name=QueueName,Value=chatflow-room-01.fifo \
  --start-time $(date -u -d '5 minutes ago' +%Y-%m-%dT%H:%M:%SZ) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%SZ) \
  --period 60 \
  --statistics Sum \
  --region us-west-2
```

### Good vs. Bad Queue Depth Profile

**Good profile (stable plateau):**
Queue depth rises during the test as Producer outpaces Consumer, then
rapidly drains to zero after the client stops. The depth stays bounded
and does not grow unboundedly.

```
Queue depth
  ^
  |    __________
  |   /          \___
  |  /
  +-----------------------> Time
  client running    client stops
```

**Bad profile (sawtooth or unbounded growth):**
Queue depth oscillates wildly or grows continuously without stabilizing.
Indicates Consumer is consistently too slow or failing to delete messages.

```
Queue depth
  ^
  |              /
  |         /   /
  |    /   /   /
  |   / \ / \ /
  +-----------------------> Time
```

---

## Consumer Health Monitoring

Consumer exposes a lightweight health endpoint with cumulative metrics:

```bash
curl http://localhost:8081/health
```

Response:

```json
{
  "status": "UP",
  "timestamp": "2026-03-08T23:00:00Z",
  "messagesConsumed": 87234,
  "broadcastCalls": 87234
}
```

`messagesConsumed` should increase continuously during a test run.
If it stops increasing while queue depth keeps growing, Consumer has stalled.

---

## ALB Monitoring

### Where to find it

AWS Console → EC2 → Load Balancers → select ALB → Monitoring tab.

Key metrics:

| Metric | What it shows |
|---|---|
| RequestCount | Total HTTP requests handled by ALB |
| ActiveConnectionCount | Current open WebSocket connections |
| NewConnectionCount | New connections per minute |
| TargetResponseTime | Average response time from Server-v2 |
| HTTPCode_Target_2XX_Count | Successful responses from targets |
| HTTPCode_Target_5XX_Count | Error responses from targets |
| HealthyHostCount | Number of healthy targets (should equal running instances) |
| UnHealthyHostCount | Number of unhealthy targets (should be 0) |

### Verifying even traffic distribution

In AWS Console → Target Groups → Monitoring, the `RequestCount` metric
can be broken down per target. In a healthy 2-instance setup, each target
should receive approximately 50% of requests.

---

## EC2 Instance Monitoring

### CloudWatch metrics (via AWS Console → EC2 → Monitoring)

| Metric | Healthy range | Warning sign |
|---|---|---|
| CPUUtilization | < 80% | Sustained > 90% causes GC delays |
| NetworkIn | Varies | Sudden drop may indicate Server crash |
| NetworkOut | Varies | Should correlate with broadcast activity |

### Manual check on EC2

```bash
# CPU and memory snapshot
top -bn1 | head -20

# Memory usage
free -m

# Open file descriptors
cat /proc/sys/fs/file-nr

# Check Java process is running
ps aux | grep java

# Check ports are listening
ss -tlnp | grep -E '8080|8081|8082'
```

---

## End-to-End Test Verification Checklist

Before each test run:

```bash
# 1. Verify all SQS queues are empty
for i in $(seq -f "%02g" 1 20); do
  count=$(aws sqs get-queue-attributes \
    --queue-url https://sqs.us-west-2.amazonaws.com/449126751631/chatflow-room-${i}.fifo \
    --attribute-names ApproximateNumberOfMessagesVisible \
    --region us-west-2 \
    --query 'Attributes.ApproximateNumberOfMessagesVisible' \
    --output text)
  if [ "$count" != "0" ]; then
    echo "WARNING: room-${i} has ${count} messages"
  fi
done

# 2. Verify Server-v2 health on all instances
curl -s http://localhost:8080/health | python3 -m json.tool
curl -s http://172.31.24.104:8080/health | python3 -m json.tool

# 3. Verify Consumer health
curl -s http://localhost:8081/health | python3 -m json.tool

# 4. Verify ALB routes correctly
curl -s http://6650A2-476604144.us-west-2.elb.amazonaws.com/health | python3 -m json.tool
```

After each test run:

```bash
# Check Consumer consumed all messages
curl http://localhost:8081/health
# messagesConsumed should eventually equal total messages sent

# Verify queues drained to zero
for i in $(seq -f "%02g" 1 20); do
  echo -n "room-${i}: "
  aws sqs get-queue-attributes \
    --queue-url https://sqs.us-west-2.amazonaws.com/449126751631/chatflow-room-${i}.fifo \
    --attribute-names ApproximateNumberOfMessagesVisible \
    --region us-west-2 \
    --query 'Attributes.ApproximateNumberOfMessagesVisible' \
    --output text
done
```
