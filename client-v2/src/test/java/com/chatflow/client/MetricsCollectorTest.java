package com.chatflow.client;

import com.chatflow.client.metrics.MetricsCollector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetricsCollectorTest {

    @Test
    void countersStartAtZero() {
        MetricsCollector m = new MetricsCollector();
        assertEquals(0, m.getSuccessCount());
        assertEquals(0, m.getFailCount());
    }

    @Test
    void recordSuccess_increments() {
        MetricsCollector m = new MetricsCollector();
        m.recordSuccess();
        m.recordSuccess();
        m.recordSuccess();
        assertEquals(3, m.getSuccessCount());
    }

    @Test
    void recordFailure_increments() {
        MetricsCollector m = new MetricsCollector();
        m.recordFailure();
        m.recordFailure();
        assertEquals(2, m.getFailCount());
    }

    @Test
    void reset_clearsAll() {
        MetricsCollector m = new MetricsCollector();
        m.recordSuccess();
        m.recordSuccess();
        m.recordFailure();
        m.recordConnection();
        m.recordReconnection();
        m.reset();

        assertEquals(0, m.getSuccessCount());
        assertEquals(0, m.getFailCount());
    }

    @Test
    void threadSafety_concurrentIncrements() throws InterruptedException {
        MetricsCollector m = new MetricsCollector();
        int threadsCount = 10;
        int perThread = 10_000;

        Thread[] threads = new Thread[threadsCount];
        for (int i = 0; i < threadsCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    m.recordSuccess();
                }
            });
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        assertEquals((long) threadsCount * perThread, m.getSuccessCount());
    }

    @Test
    void printReport_doesNotThrow() {
        MetricsCollector m = new MetricsCollector();
        m.recordSuccess();
        // Just make sure it doesn't crash
        assertDoesNotThrow(() ->
                m.printReport("Test Phase", System.currentTimeMillis() - 1000, System.currentTimeMillis()));
    }
}
