package com.chatflow.server.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Incoming message from the WebSocket client.
 * roomId is included in the payload so server-v2 can route to the correct SQS queue.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatMessage {
    private String userId;
    private String username;
    private String message;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant timestamp;
    private MessageType messageType;
    private int roomId;

    public ChatMessage() {}

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public MessageType getMessageType() { return messageType; }
    public void setMessageType(MessageType messageType) { this.messageType = messageType; }

    public int getRoomId() { return roomId; }
    public void setRoomId(int roomId) { this.roomId = roomId; }
}
