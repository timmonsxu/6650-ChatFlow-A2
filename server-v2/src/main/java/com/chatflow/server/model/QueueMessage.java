package com.chatflow.server.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Message published to SQS. Matches the format required by the assignment spec.
 */
public class QueueMessage {
    private String messageId;
    private String roomId;
    private String userId;
    private String username;
    private String message;
    private String timestamp;
    private String messageType;
    private String serverId;
    private String clientIp;

    public QueueMessage() {}

    public static QueueMessage from(ChatMessage msg, String serverId, String clientIp) {
        QueueMessage q = new QueueMessage();
        q.messageId  = UUID.randomUUID().toString();
        q.roomId     = String.format("%02d", msg.getRoomId());
        q.userId     = msg.getUserId();
        q.username   = msg.getUsername();
        q.message    = msg.getMessage();
        q.timestamp  = msg.getTimestamp() != null ? msg.getTimestamp().toString() : Instant.now().toString();
        q.messageType = msg.getMessageType() != null ? msg.getMessageType().name() : "TEXT";
        q.serverId   = serverId;
        q.clientIp   = clientIp;
        return q;
    }

    public String getMessageId()  { return messageId; }
    public String getRoomId()     { return roomId; }
    public String getUserId()     { return userId; }
    public String getUsername()   { return username; }
    public String getMessage()    { return message; }
    public String getTimestamp()  { return timestamp; }
    public String getMessageType(){ return messageType; }
    public String getServerId()   { return serverId; }
    public String getClientIp()   { return clientIp; }

    public void setMessageId(String messageId)   { this.messageId = messageId; }
    public void setRoomId(String roomId)         { this.roomId = roomId; }
    public void setUserId(String userId)         { this.userId = userId; }
    public void setUsername(String username)     { this.username = username; }
    public void setMessage(String message)       { this.message = message; }
    public void setTimestamp(String timestamp)   { this.timestamp = timestamp; }
    public void setMessageType(String messageType){ this.messageType = messageType; }
    public void setServerId(String serverId)     { this.serverId = serverId; }
    public void setClientIp(String clientIp)     { this.clientIp = clientIp; }
}
