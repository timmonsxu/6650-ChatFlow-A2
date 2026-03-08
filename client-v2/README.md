# ChatFlow Client - Part 1

## Prerequisites
- Java 17+
- Maven 3.8+

## Build
```bash
cd client-part1
mvn clean package
```

## Run
```bash
java -jar target/client-part1-1.0.0.jar
```

## Configuration
Edit `ChatClient.java` constants to tune:
- `SERVER_URL` - WebSocket server address
- `TOTAL_MESSAGES` - Total messages to send (default: 500,000)
- `WARMUP_THREADS` - Threads in warmup phase (default: 32)
- `WARMUP_MESSAGES_PER_THREAD` - Messages per warmup thread (default: 1000)
- `MAIN_THREADS` - Threads in main phase (default: 128, tune based on Little's Law)

## Architecture
```
[MessageGenerator] → BlockingQueue(10000) → [N SenderThreads] → WS → EC2 Server
```

- **MessageGenerator**: Single thread, produces 500K messages with random data
- **SenderThread**: Each holds one persistent WebSocket connection, sends synchronously
- **ConnectionManager**: Handles connection creation and reconnection
- **MetricsCollector**: Thread-safe counters using AtomicLong/AtomicInteger
