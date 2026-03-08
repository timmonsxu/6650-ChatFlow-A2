package com.chatflow.client.metrics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MetricsCollector {

    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong failCount = new AtomicLong(0);
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicInteger reconnections = new AtomicInteger(0);

    public void recordSuccess() { successCount.incrementAndGet(); }
    public void recordFailure() { failCount.incrementAndGet(); }
    public void recordConnection() { totalConnections.incrementAndGet(); }
    public void recordReconnection() { reconnections.incrementAndGet(); }

    public long getSuccessCount() { return successCount.get(); }
    public long getFailCount() { return failCount.get(); }

    public void printReport(String phase, long startTime, long endTime) {
        long success = successCount.get();
        long fail = failCount.get();
        double durationSec = (endTime - startTime) / 1000.0;
        double throughput = success / durationSec;

        System.out.println();
        System.out.println("========================================");
        System.out.println("  " + phase + " Results");
        System.out.println("========================================");
        System.out.printf("  Successful messages : %,d%n", success);
        System.out.printf("  Failed messages     : %,d%n", fail);
        System.out.printf("  Total runtime       : %.2f seconds%n", durationSec);
        System.out.printf("  Throughput          : %,.0f msg/s%n", throughput);
        System.out.printf("  Total connections   : %d%n", totalConnections.get());
        System.out.printf("  Reconnections       : %d%n", reconnections.get());
        System.out.println("========================================");
    }

    public void reset() {
        successCount.set(0);
        failCount.set(0);
        totalConnections.set(0);
        reconnections.set(0);
    }
}
