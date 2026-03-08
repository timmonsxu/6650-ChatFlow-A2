package com.chatflow.client.model;

import com.google.gson.Gson;
import java.time.Instant;

public class ChatMessage {
    private static final Gson GSON = new Gson();

    private final String userId;
    private final String username;
    private final String message;
    private final String timestamp;
    private final String messageType;
    private final int roomId;

    public ChatMessage(String userId, String username, String message,
                       String messageType, int roomId) {
        this.userId = userId;
        this.username = username;
        this.message = message;
        this.timestamp = Instant.now().toString();
        this.messageType = messageType;
        this.roomId = roomId;
    }

    /**
     * Serializes to JSON including roomId, so server-v2 knows which SQS queue to publish to.
     */
    public String toJson() {
        return GSON.toJson(new JsonPayload(userId, username, message, timestamp, messageType, roomId));
    }

    public int getRoomId() { return roomId; }
    public String getMessageType() { return messageType; }
    public String getUserId() { return userId; }
    public String getUsername() { return username; }

    private static class JsonPayload {
        final String userId;
        final String username;
        final String message;
        final String timestamp;
        final String messageType;
        final int roomId;

        JsonPayload(String userId, String username, String message,
                    String timestamp, String messageType, int roomId) {
            this.userId = userId;
            this.username = username;
            this.message = message;
            this.timestamp = timestamp;
            this.messageType = messageType;
            this.roomId = roomId;
        }
    }
}
