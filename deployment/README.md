# Deployment — ALB Configuration and Startup Scripts

## AWS Infrastructure Overview

```
[Local Client]
      |
   ALB (6650A2)
   DNS: 6650A2-476604144.us-west-2.elb.amazonaws.com
   Port 80, HTTP
      |
  ----+----
  |       |
EC2 A   EC2 B
:8080   :8080
  |
Consumer :8081
  |
SQS x20
```

---

## ALB Configuration

### Load Balancer Settings

| Parameter | Value |
|---|---|
| Name | 6650A2 |
| Type | Application Load Balancer |
| Scheme | Internet-facing |
| IP address type | IPv4 |
| VPC | vpc-05c28d6a57031288f |
| Availability zones | us-west-2b |
| Security group | sg-0f053cb296e0c248e (6650A2) |
| Idle timeout | 300 seconds |

### ALB Security Group (sg-0f053cb296e0c248e)

Inbound rules:

| Type | Protocol | Port | Source |
|---|---|---|---|
| HTTP | TCP | 80 | 0.0.0.0/0 |

### Listener

| Port | Protocol | Default action |
|---|---|---|
| 80 | HTTP | Forward to target group |

### Target Group

| Parameter | Value |
|---|---|
| Name | 6650A2-targets |
| Protocol | HTTP |
| Port | 8080 |
| Health check path | /health |
| Health check interval | 30 seconds |
| Health check timeout | 5 seconds |
| Healthy threshold | 2 |
| Unhealthy threshold | 3 |
| Stickiness | Enabled |
| Stickiness type | Load balancer generated cookie |
| Stickiness duration | 86400 seconds (1 day) |

### Registered Targets

| Instance | Port | Status |
|---|---|---|
| EC2 A (i-08a83a5cbfd868ccf) | 8080 | Healthy |
| EC2 B (i-0xxxxxxxxxxxxxxxxx) | 8080 | Healthy |

---

## EC2 Configuration

### EC2 A — Server-v2 + Consumer

| Parameter | Value |
|---|---|
| Instance type | t3.micro |
| Region | us-west-2b |
| Public IP | 54.184.109.66 |
| Private IP | 172.31.25.72 |
| IAM Role | Attached (SQS read/write permissions) |
| Security groups | launch-wizard-1, A2-Ec2 |

EC2 A Security Group — A2-Ec2 inbound rules:

| Type | Protocol | Port range | Source |
|---|---|---|---|
| Custom TCP | TCP | 8080-8084 | sg-0f053cb296e0c248e (ALB) |

### EC2 B — Server-v2

| Parameter | Value |
|---|---|
| Instance type | t3.micro |
| Region | us-west-2b |
| Public IP | 54.190.22.194 |
| Private IP | 172.31.24.104 |
| IAM Role | Attached (SQS read/write permissions) |

EC2 B Security Group inbound rules:

| Type | Protocol | Port range | Source |
|---|---|---|---|
| Custom TCP | TCP | 8080 | sg-0f053cb296e0c248e (ALB) |
| Custom TCP | TCP | 8080 | 172.31.25.72/32 (EC2 A, for Consumer broadcast) |

---

## SQS Queue Setup

20 FIFO queues created manually via AWS Console.

| Parameter | Value |
|---|---|
| Queue names | chatflow-room-01.fifo to chatflow-room-20.fifo |
| Type | FIFO |
| Visibility timeout | 120 seconds |
| Message retention | 1 hour |
| Content-based deduplication | Disabled |

AWS CLI command to verify all queues exist:

```bash
aws sqs list-queues \
  --region us-west-2 \
  --queue-name-prefix chatflow
```

AWS CLI command to purge all queues before a test run:

```bash
for i in $(seq -f "%02g" 1 20); do
  aws sqs purge-queue \
    --queue-url https://sqs.us-west-2.amazonaws.com/449126751631/chatflow-room-${i}.fifo \
    --region us-west-2
done
```

AWS CLI command to adjust visibility timeout:

```bash
for i in $(seq -f "%02g" 1 20); do
  aws sqs set-queue-attributes \
    --queue-url https://sqs.us-west-2.amazonaws.com/449126751631/chatflow-room-${i}.fifo \
    --attributes VisibilityTimeout=120 \
    --region us-west-2
done
```

---

## Startup Scripts

### Start all processes on EC2 A (using tmux)

```bash
# Window 1: Server-v2
tmux new-session -d -s server 'java -jar ~/server-v2.jar'

# Window 2: Consumer
tmux new-session -d -s consumer 'java -jar ~/consumer.jar'
```

### Start Server-v2 on EC2 B

```bash
tmux new-session -d -s server 'java -jar ~/server-v2.jar'
```

### Verify all services are healthy

```bash
# EC2 A server
curl http://localhost:8080/health

# EC2 A consumer
curl http://localhost:8081/health

# EC2 B server (from EC2 A using private IP)
curl http://172.31.24.104:8080/health

# ALB (from local machine)
curl http://6650A2-476604144.us-west-2.elb.amazonaws.com/health
```

### Run load test (from local machine)

```bash
# Single instance test (direct to EC2 A)
java -jar client-v2/target/client-part1-1.0.0.jar ws://54.184.109.66:8080

# Load balanced test (via ALB)
java -jar client-v2/target/client-part1-1.0.0.jar ws://6650A2-476604144.us-west-2.elb.amazonaws.com
```

### Stop all processes

```bash
# EC2 A
pkill -f server-v2.jar
pkill -f consumer.jar

# EC2 B
pkill -f server-v2.jar
```
