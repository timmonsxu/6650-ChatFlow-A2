package com.chatflow.server.controller;

import com.chatflow.server.handler.ChatWebSocketHandler;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Internal REST endpoint called by the Consumer to trigger WebSocket broadcast.
 * Not exposed through ALB — Consumer calls each Server directly via localhost.
 *
 * POST /internal/broadcast/{roomId}
 * Body: QueueMessage JSON
 * Returns: 200 OK immediately — actual broadcast is submitted to a dedicated
 * thread pool and executed asynchronously, so Tomcat threads are not held
 * waiting for broadcast completion. This prevents Consumer HTTP requests from
 * competing with WebSocket message handling threads in the Tomcat thread pool.
 */
@RestController
@RequestMapping("/internal")
public class InternalBroadcastController {

    private static final Logger log = LoggerFactory.getLogger(InternalBroadcastController.class);
    private static final int BROADCAST_THREADS = 40;
    // Bounded queue: when full, new broadcast tasks are silently dropped.
    // This prevents unbounded memory growth during long load tests (500K messages)
    // where Consumer submits tasks faster than they can be executed.
    private static final int BROADCAST_QUEUE_SIZE = 2_000;

    private final ChatWebSocketHandler wsHandler;
    private final AtomicLong droppedBroadcasts = new AtomicLong(0);
    private final ExecutorService broadcastExecutor = new ThreadPoolExecutor(
            BROADCAST_THREADS, BROADCAST_THREADS,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(BROADCAST_QUEUE_SIZE),
            new ThreadPoolExecutor.DiscardPolicy());

    public InternalBroadcastController(ChatWebSocketHandler wsHandler) {
        this.wsHandler = wsHandler;
    }

    @PostMapping("/broadcast/{roomId}")
    public ResponseEntity<Map<String, Object>> broadcast(
            @PathVariable String roomId,
            @RequestBody String messageJson) {

        // Submit broadcast to dedicated thread pool and return immediately.
        // Tomcat thread is released right away — no waiting for session writes.
        broadcastExecutor.submit(() -> {
            try {
                wsHandler.broadcastToRoom(roomId, messageJson);
            } catch (Exception e) {
                log.warn("Async broadcast failed for room {}: {}", roomId, e.getMessage());
            }
        });

        return ResponseEntity.ok(Map.of("status", "OK", "roomId", roomId));
    }

    @PreDestroy
    public void shutdown() {
        broadcastExecutor.shutdown();
        try {
            broadcastExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
