package com.chatflow.client;

import com.chatflow.client.connection.ConnectionManager;
import com.chatflow.client.generator.MessageGenerator;
import com.chatflow.client.metrics.MetricsCollector;
import com.chatflow.client.model.ChatMessage;
import com.chatflow.client.sender.SenderThread;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatClient {

    private static final String SERVER_URL = "ws://54.184.109.66:8080";
    private static final int TOTAL_MESSAGES = 500_000;
    private static final int NUM_ROOMS = 20;
    // Each room gets ~25K messages total; queue capacity per room keeps memory bounded
    private static final int QUEUE_CAPACITY_PER_ROOM = 2_000;

    // Warmup: 32 threads, round-robin across 20 rooms
    private static final int WARMUP_THREADS = 40;
    private static final int WARMUP_MESSAGES_PER_THREAD = 1000;
    private static final int WARMUP_TOTAL = WARMUP_THREADS * WARMUP_MESSAGES_PER_THREAD;

    // Main: 256 threads, round-robin across 20 rooms (~12-13 threads per room)
    private static final int MAIN_THREADS = 120;

    public static void main(String[] args) throws Exception {
        String serverUrl = args.length > 0 ? args[0] : SERVER_URL;

        System.out.println("============================================");
        System.out.println("  ChatFlow Load Test Client - Part 1 (A2)");
        System.out.println("  Server: " + serverUrl);
        System.out.println("  Total messages: " + TOTAL_MESSAGES);
        System.out.println("  Rooms: " + NUM_ROOMS);
        System.out.println("  Warmup: " + WARMUP_THREADS + " threads x " + WARMUP_MESSAGES_PER_THREAD + " msgs");
        System.out.println("  Main:   " + MAIN_THREADS + " threads, " + MAIN_THREADS + " sessions");
        System.out.println("============================================");

        // One sub-queue per room; MessageGenerator routes directly into the correct queue
        Map<Integer, BlockingQueue<ChatMessage>> roomQueues = new HashMap<>();
        for (int r = 1; r <= NUM_ROOMS; r++) {
            roomQueues.put(r, new LinkedBlockingQueue<>(QUEUE_CAPACITY_PER_ROOM));
        }

        MetricsCollector warmupMetrics = new MetricsCollector();
        MetricsCollector mainMetrics = new MetricsCollector();

        // Single generator thread routes messages into per-room sub-queues
        Thread generatorThread = new Thread(
                new MessageGenerator(roomQueues, TOTAL_MESSAGES), "msg-generator");
        generatorThread.start();

        // Give generator a head start to pre-fill queues
        Thread.sleep(500);

        // ============ Warmup Phase ============
        System.out.println("\n>>> Warmup Phase starting...");

        ConnectionManager warmupConnMgr = new ConnectionManager(serverUrl, warmupMetrics);
        AtomicInteger warmupCounter = new AtomicInteger(WARMUP_TOTAL);
        ExecutorService warmupExecutor = Executors.newFixedThreadPool(WARMUP_THREADS);
        long warmupStart = System.currentTimeMillis();

        for (int i = 0; i < WARMUP_THREADS; i++) {
            int roomId = (i % NUM_ROOMS) + 1;
            warmupExecutor.submit(new SenderThread(
                    roomQueues.get(roomId), warmupConnMgr, warmupMetrics,
                    roomId, WARMUP_MESSAGES_PER_THREAD, warmupCounter));
        }

        warmupExecutor.shutdown();
        warmupExecutor.awaitTermination(10, TimeUnit.MINUTES);

        long warmupEnd = System.currentTimeMillis();
        warmupMetrics.printReport("Warmup Phase", warmupStart, warmupEnd);

        // ============ Main Phase ============
        int remaining = TOTAL_MESSAGES - (int) warmupMetrics.getSuccessCount();
        System.out.println("\n>>> Main Phase: " + MAIN_THREADS + " threads ("
                + MAIN_THREADS + " sessions), " + remaining + " remaining messages");

        ConnectionManager mainConnMgr = new ConnectionManager(serverUrl, mainMetrics);
        ExecutorService mainExecutor = Executors.newFixedThreadPool(MAIN_THREADS);
        long mainStart = System.currentTimeMillis();

        for (int i = 0; i < MAIN_THREADS; i++) {
            int roomId = (i % NUM_ROOMS) + 1;
            mainExecutor.submit(new SenderThread(
                    roomQueues.get(roomId), mainConnMgr, mainMetrics,
                    roomId, -1, null));
        }

        mainExecutor.shutdown();
        mainExecutor.awaitTermination(30, TimeUnit.MINUTES);

        long mainEnd = System.currentTimeMillis();
        mainMetrics.printReport("Main Phase", mainStart, mainEnd);

        generatorThread.join();

        // ============ Overall Summary ============
        long totalSuccess = warmupMetrics.getSuccessCount() + mainMetrics.getSuccessCount();
        long totalFail = warmupMetrics.getFailCount() + mainMetrics.getFailCount();
        double totalTimeSec = (mainEnd - warmupStart) / 1000.0;

        System.out.println();
        System.out.println("========================================");
        System.out.println("  Overall Summary");
        System.out.println("========================================");
        System.out.printf("  Total successful    : %,d%n", totalSuccess);
        System.out.printf("  Total failed        : %,d%n", totalFail);
        System.out.printf("  Total wall time     : %.2f seconds%n", totalTimeSec);
        System.out.printf("  Overall throughput  : %,.0f msg/s%n", totalSuccess / totalTimeSec);
        System.out.println("========================================");
    }
}
