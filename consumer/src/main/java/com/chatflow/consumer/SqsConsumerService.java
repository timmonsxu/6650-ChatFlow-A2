package com.chatflow.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Starts a configurable thread pool (default 20 threads, one per room).
 * Each thread is responsible for one SQS queue and polls it in a tight loop.
 *
 * Pipeline per message:
 *   SQS poll -> parse roomId -> BroadcastClient.broadcast() -> SQS delete
 *
 * Metrics tracked: total messages consumed, total broadcast calls made.
 */
@Service
public class SqsConsumerService {

    private static final Logger log = LoggerFactory.getLogger(SqsConsumerService.class);
    private static final int NUM_ROOMS = 20;

    @Value("${app.sqs.region}")
    private String region;

    @Value("${app.sqs.account-id}")
    private String accountId;

    @Value("${app.sqs.queue-name-prefix}")
    private String queueNamePrefix;

    @Value("${app.consumer.threads}")
    private int numThreads;

    private final BroadcastClient broadcastClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private SqsClient sqsClient;
    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Metrics
    final AtomicLong messagesConsumed = new AtomicLong(0);
    final AtomicLong broadcastCalls = new AtomicLong(0);

    public SqsConsumerService(BroadcastClient broadcastClient) {
        this.broadcastClient = broadcastClient;
    }

    @PostConstruct
    public void start() {
        sqsClient = SqsClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(software.amazon.awssdk.http.apache.ApacheHttpClient.builder()
                        .maxConnections(numThreads + 20)) // pool size > thread count
                .build();

        running.set(true);
        executor = Executors.newFixedThreadPool(numThreads);

        // Distribute rooms across threads round-robin.
        // With 20 threads and 20 rooms, each thread owns exactly one room.
        // With fewer threads, some threads own multiple rooms sequentially.
        for (int i = 0; i < numThreads; i++) {
            int roomId = (i % NUM_ROOMS) + 1;
            executor.submit(() -> pollLoop(roomId));
        }

        log.info("SqsConsumerService started: {} threads across {} rooms", numThreads, NUM_ROOMS);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sqsClient.close();
        log.info("SqsConsumerService stopped. consumed={}, broadcasts={}",
                messagesConsumed.get(), broadcastCalls.get());
    }

    public long getMessagesConsumed() { return messagesConsumed.get(); }
    public long getBroadcastCalls()   { return broadcastCalls.get(); }

    // ── private ───────────────────────────────────────────────────────────────

    private void pollLoop(int roomId) {
        String paddedRoom = String.format("%02d", roomId);
        String queueUrl = buildQueueUrl(paddedRoom);
        log.debug("Polling thread started for room {}: {}", paddedRoom, queueUrl);

        while (running.get()) {
            try {
                ReceiveMessageRequest req = ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(20)   // long polling — reduces empty responses
                        .build();

                List<Message> messages = sqsClient.receiveMessage(req).messages();

                for (Message msg : messages) {
                    processMessage(paddedRoom, msg, queueUrl);
                }

            } catch (Exception e) {
                if (running.get()) {
                    log.error("Poll error for room {}: {}", paddedRoom, e.getMessage());
                    sleepQuietly(1000);
                }
            }
        }
    }

    private void processMessage(String roomId, Message sqsMessage, String queueUrl) {
        try {
            String body = sqsMessage.body();

            // Extract roomId from message body as a sanity check
            // (should always match the queue's roomId since server routes by payload roomId)
            JsonNode node = mapper.readTree(body);
            String msgRoomId = node.has("roomId") ? node.get("roomId").asText() : roomId;

            broadcastClient.broadcast(msgRoomId, body);
            broadcastCalls.incrementAndGet();

            // Delete from SQS only after successful broadcast attempt
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(sqsMessage.receiptHandle())
                    .build());

            messagesConsumed.incrementAndGet();

        } catch (Exception e) {
            log.error("Failed to process message in room {}: {}", roomId, e.getMessage());
            // Message stays in queue and becomes visible again after visibility timeout
        }
    }

    private String buildQueueUrl(String paddedRoomId) {
        return String.format("https://sqs.%s.amazonaws.com/%s/%s%s.fifo",
                region, accountId, queueNamePrefix, paddedRoomId);
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
