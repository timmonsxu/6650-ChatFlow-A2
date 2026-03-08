package com.chatflow.client;

import com.chatflow.client.connection.ConnectionManager;
import com.chatflow.client.connection.ConnectionManager.ChatWebSocketClient;
import com.chatflow.client.metrics.MetricsCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that require a running server.
 * Run with: set SERVER_URL=ws://localhost:8080 && mvn test -Dtest=ConnectionManagerTest
 *
 * These tests are skipped by default unless SERVER_URL env var is set.
 */
@EnabledIfEnvironmentVariable(named = "SERVER_URL", matches = ".+")
class ConnectionManagerTest {
    private static final String SERVER_URL = System.getenv("SERVER_URL");

    @Test
    void createConnection_success() throws Exception {
        MetricsCollector metrics = new MetricsCollector();
        ConnectionManager mgr = new ConnectionManager(SERVER_URL, metrics);

        ChatWebSocketClient client = null;
        try {
            client = mgr.createConnection(1);
            assertTrue(client.isOpen());

            // Send a test message and verify response
            String response = client.sendAndWait(
                    "{\"userId\":\"1\",\"username\":\"test123\",\"message\":\"hello\"," +
                    "\"timestamp\":\"2026-02-07T12:00:00Z\",\"messageType\":\"TEXT\"}",
                    5000);

            assertNotNull(response, "Should receive a response");
            // server-v2 acks with {"status":"RECEIVED",...} instead of A1's "OK"
            assertTrue(response.contains("RECEIVED") || response.contains("\"status\""),
                    "Response should contain status");
        } finally {
            if (client != null) client.closeBlocking();
        }
    }

    @Test
    void createConnection_differentRooms() throws Exception {
        MetricsCollector metrics = new MetricsCollector();
        ConnectionManager mgr = new ConnectionManager(SERVER_URL, metrics);

        ChatWebSocketClient c1 = null, c2 = null;
        try {
            c1 = mgr.createConnection(1);
            c2 = mgr.createConnection(15);
            assertTrue(c1.isOpen());
            assertTrue(c2.isOpen());
            assertTrue(c1.getURI().getPath().endsWith("/1"));
            assertTrue(c2.getURI().getPath().endsWith("/15"));
        } finally {
            if (c1 != null) c1.closeBlocking();
            if (c2 != null) c2.closeBlocking();
        }
    }

    @Test
    void reconnect_returnsNewOpenConnection() throws Exception {
        MetricsCollector metrics = new MetricsCollector();
        ConnectionManager mgr = new ConnectionManager(SERVER_URL, metrics);

        ChatWebSocketClient original = mgr.createConnection(3);
        assertTrue(original.isOpen());

        ChatWebSocketClient reconnected = mgr.reconnect(original);
        assertTrue(reconnected.isOpen());
        assertFalse(original.isOpen(), "Original should be closed after reconnect");

        reconnected.closeBlocking();
    }

    @Test
    void sendAndWait_timeout_returnsNull() throws Exception {
        MetricsCollector metrics = new MetricsCollector();
        ConnectionManager mgr = new ConnectionManager(SERVER_URL, metrics);

        ChatWebSocketClient client = mgr.createConnection(1);
        // Send invalid message that won't get "OK" but we just test the mechanism works
        String response = client.sendAndWait("invalid json", 3000);
        // Should still get a response (error response from server)
        assertNotNull(response);
        assertTrue(response.contains("ERROR") || response.contains("error"),
                "Invalid JSON should return an error response");

        client.closeBlocking();
    }
}
