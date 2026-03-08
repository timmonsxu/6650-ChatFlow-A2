package com.chatflow.server.sqs;

import com.chatflow.server.model.QueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Publishes QueueMessages to the correct FIFO SQS queue.
 *
 * publishAsync() fires-and-forgets: the caller (handleTextMessage) returns
 * the RECEIVED ack to the client immediately without waiting for SQS confirmation.
 * This removes the SQS round-trip (~10-30ms) from the client-facing latency path.
 *
 * Trade-off: in the rare event of SQS failure, the client already received RECEIVED
 * but the message is lost. Acceptable for load-test purposes.
 */
@Component
public class SqsPublisher {

    private static final Logger log = LoggerFactory.getLogger(SqsPublisher.class);
    private static final int ASYNC_THREAD_POOL_SIZE = 20;

    @Value("${app.sqs.region}")
    private String region;

    @Value("${app.sqs.account-id}")
    private String accountId;

    @Value("${app.sqs.queue-name-prefix}")
    private String queueNamePrefix;

    private SqsClient sqsClient;
    private ExecutorService publishExecutor;
    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        sqsClient = SqsClient.builder()
                .region(Region.of(region))
                .build();
        publishExecutor = Executors.newFixedThreadPool(ASYNC_THREAD_POOL_SIZE);
        log.info("SqsPublisher initialised: region={}, accountId={}, prefix={}, asyncThreads={}",
                region, accountId, queueNamePrefix, ASYNC_THREAD_POOL_SIZE);
    }

    @PreDestroy
    public void shutdown() {
        publishExecutor.shutdown();
        try {
            publishExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sqsClient.close();
    }

    /**
     * Submits SQS publish to a background thread pool and returns immediately.
     * The caller can send the RECEIVED ack to the client without waiting.
     */
    public void publishAsync(QueueMessage msg) {
        publishExecutor.submit(() -> doPublish(msg));
    }

    private void doPublish(QueueMessage msg) {
        String queueUrl = buildQueueUrl(msg.getRoomId());
        try {
            String body = mapper.writeValueAsString(msg);
            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .messageGroupId(msg.getRoomId())
                    .messageDeduplicationId(msg.getMessageId())
                    .build();
            sqsClient.sendMessage(request);
        } catch (Exception e) {
            log.error("Async SQS publish failed for room {}: {}", msg.getRoomId(), e.getMessage());
        }
    }

    private String buildQueueUrl(String roomId) {
        return String.format("https://sqs.%s.amazonaws.com/%s/%s%s.fifo",
                region, accountId, queueNamePrefix, roomId);
    }
}
