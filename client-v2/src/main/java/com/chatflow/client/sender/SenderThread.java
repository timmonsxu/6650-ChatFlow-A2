package com.chatflow.client.sender;

import com.chatflow.client.connection.ConnectionManager;
import com.chatflow.client.connection.ConnectionManager.ChatWebSocketClient;
import com.chatflow.client.metrics.MetricsCollector;
import com.chatflow.client.model.ChatMessage;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Each SenderThread owns:
 *   - a fixed roomId
 *   - its own dedicated WebSocket session connected to /chat/{roomId}
 *   - its own sub-queue that only contains messages for its roomId
 *
 * No filtering or re-queuing is needed since MessageGenerator routes
 * messages directly into the correct sub-queue.
 */
public class SenderThread implements Runnable {

    private static final int MAX_RETRIES = 5;
    private static final long RESPONSE_TIMEOUT_MS = 5000;

    private final BlockingQueue<ChatMessage> roomQueue;
    private final ConnectionManager connectionManager;
    private final MetricsCollector metrics;
    private final int roomId;
    private final int maxMessages;       // -1 = drain until queue empty
    private final AtomicInteger sharedCounter;

    public SenderThread(BlockingQueue<ChatMessage> roomQueue,
                        ConnectionManager connectionManager,
                        MetricsCollector metrics,
                        int roomId,
                        int maxMessages,
                        AtomicInteger sharedCounter) {
        this.roomQueue = roomQueue;
        this.connectionManager = connectionManager;
        this.metrics = metrics;
        this.roomId = roomId;
        this.maxMessages = maxMessages;
        this.sharedCounter = sharedCounter;
    }

    @Override
    public void run() {
        ChatWebSocketClient client = null;
        int sent = 0;

        try {
            client = connectionManager.createConnection(roomId);

            while (shouldContinue(sent)) {
                ChatMessage msg = roomQueue.poll(2, TimeUnit.SECONDS);
                if (msg == null) break; // sub-queue exhausted
                sent += sendWithRetry(client, msg);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[Sender-room" + roomId + "] Error: " + e.getMessage());
        } finally {
            if (client != null && client.isOpen()) {
                client.close();
            }
        }
    }

    private boolean shouldContinue(int localSent) {
        if (maxMessages > 0 && localSent >= maxMessages) return false;
        if (sharedCounter != null && sharedCounter.get() <= 0) return false;
        return true;
    }

    /**
     * Sends with up to MAX_RETRIES retries using exponential backoff.
     * Returns 1 on success, 0 after all retries exhausted.
     * server-v2 acks with {"status":"RECEIVED","messageId":"..."}.
     */
    private int sendWithRetry(ChatWebSocketClient client, ChatMessage msg) {
        String json = msg.toJson();

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                if (!client.isOpen()) {
                    client = connectionManager.reconnect(client);
                }

                String response = client.sendAndWait(json, RESPONSE_TIMEOUT_MS);
                if (response != null && response.contains("RECEIVED")) {
                    metrics.recordSuccess();
                    if (sharedCounter != null) sharedCounter.decrementAndGet();
                    return 1;
                }

                if (response != null) {
                    System.err.println("[Sender-room" + roomId + "] Server error: " + response);
                }

            } catch (Exception e) {
                // will retry
            }

            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep((long) (10 * Math.pow(2, attempt)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        metrics.recordFailure();
        return 0;
    }
}
