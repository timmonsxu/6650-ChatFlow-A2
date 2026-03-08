package com.chatflow.client.connection;

import com.chatflow.client.metrics.MetricsCollector;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ConnectionManager {

    private final String serverBaseUrl;
    private final MetricsCollector metrics;

    public ConnectionManager(String serverBaseUrl, MetricsCollector metrics) {
        this.serverBaseUrl = serverBaseUrl;
        this.metrics = metrics;
    }

    /**
     * Creates a WebSocket connection to a specific room.
     * Blocks until connected or timeout.
     */
    public ChatWebSocketClient createConnection(int roomId) throws Exception {
        String url = serverBaseUrl + "/chat/" + roomId;
        ChatWebSocketClient client = new ChatWebSocketClient(new URI(url));
        client.connectBlocking(10, TimeUnit.SECONDS);

        if (!client.isOpen()) {
            throw new RuntimeException("Failed to connect to " + url);
        }

        metrics.recordConnection();
        return client;
    }

    /**
     * Reconnects an existing client to its original URI.
     * Returns a new client instance since Java-WebSocket doesn't support reconnecting.
     */
    public ChatWebSocketClient reconnect(ChatWebSocketClient oldClient) throws Exception {
        URI uri = oldClient.getURI();
        oldClient.closeBlocking();

        ChatWebSocketClient newClient = new ChatWebSocketClient(uri);
        newClient.connectBlocking(10, TimeUnit.SECONDS);

        if (!newClient.isOpen()) {
            throw new RuntimeException("Failed to reconnect to " + uri);
        }

        metrics.recordReconnection();
        metrics.recordConnection();
        return newClient;
    }

    /**
     * Custom WebSocket client that supports synchronous send-and-wait-for-response.
     *
     * In A2, each sent message produces two server pushes:
     *   1. RECEIVED ack  (from server-v2 immediately after SQS publish)
     *   2. broadcast message (from Consumer -> server-v2 -> client)
     * sendAndWait uses a queue and discards broadcast messages until the ack arrives.
     */
    public static class ChatWebSocketClient extends WebSocketClient {

        private final LinkedBlockingQueue<String> incomingMessages = new LinkedBlockingQueue<>();

        public ChatWebSocketClient(URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {}

        @Override
        public void onMessage(String message) {
            incomingMessages.offer(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {}

        @Override
        public void onError(Exception ex) {
            System.err.println("[WS Error] " + ex.getMessage());
        }

        /**
         * Send a message and wait for a RECEIVED ack.
         * Broadcast messages are discarded silently.
         * Returns the ack string, or null on timeout.
         */
        public String sendAndWait(String message, long timeoutMs) throws InterruptedException {
            incomingMessages.clear(); // discard stale broadcast messages from previous sends
            send(message);
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                String response = incomingMessages.poll(remaining, TimeUnit.MILLISECONDS);
                if (response == null) break;                    // timed out
                if (response.contains("RECEIVED")) return response; // got the ack
                // broadcast message — discard and keep waiting
            }
            return null;
        }
    }
}
