package com.chatflow.server.handler;

import com.chatflow.server.model.ChatMessage;
import com.chatflow.server.model.MessageType;
import com.chatflow.server.model.QueueMessage;
import com.chatflow.server.sqs.SqsPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    // ConcurrentWebSocketSessionDecorator config:
    // sendTimeLimit   — applies per-send; 30s gives slow clients (high RTT) enough time
    // bufferSizeLimit — 512KB per session; overflow uses DROP strategy so slow consumers
    //                   don't cause session termination (critical for load-test correctness)
    private static final int SEND_TIME_LIMIT_MS  = 30_000;    // 30 seconds
    private static final int BUFFER_SIZE_LIMIT   = 512 * 1024; // 512 KB

    private final ObjectMapper mapper;
    private final SqsPublisher sqsPublisher;

    @Value("${app.server-id}")
    private String serverId;

    /**
     * sessionId -> thread-safe decorated session.
     * All writes go through the decorator, which serialises concurrent sends
     * internally — no manual synchronized blocks needed anywhere.
     */
    private final ConcurrentHashMap<String, ConcurrentWebSocketSessionDecorator> decoratedSessions
            = new ConcurrentHashMap<>();

    /**
     * roomId (e.g. "05") -> list of decorated sessions currently in that room.
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ConcurrentWebSocketSessionDecorator>> roomSessions
            = new ConcurrentHashMap<>();

    /**
     * sessionId -> roomId for fast lookup on close/leave.
     */
    private final ConcurrentHashMap<String, String> sessionRoomIndex = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(SqsPublisher sqsPublisher) {
        this.sqsPublisher = sqsPublisher;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Wrap raw session in ConcurrentWebSocketSessionDecorator immediately.
        // All subsequent operations use the decorated version so concurrent writes
        // (ack from WebSocket thread + broadcast from HTTP thread) are serialised
        // without any manual locking.
        ConcurrentWebSocketSessionDecorator decorated =
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT,
                        ConcurrentWebSocketSessionDecorator.OverflowStrategy.DROP);
        decoratedSessions.put(session.getId(), decorated);
        log.debug("Connection opened: session={}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ConcurrentWebSocketSessionDecorator decorated = decoratedSessions.remove(session.getId());
        if (decorated != null) {
            removeSession(decorated);
        }
        log.debug("Connection closed: session={}, status={}", session.getId(), status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // Always work with the decorated session
        ConcurrentWebSocketSessionDecorator decorated = decoratedSessions.get(session.getId());
        if (decorated == null || !decorated.isOpen()) return;

        String payload = message.getPayload();

        // Parse
        ChatMessage chatMsg;
        try {
            chatMsg = mapper.readValue(payload, ChatMessage.class);
        } catch (Exception e) {
            sendError(decorated, "Invalid JSON format: " + e.getMessage());
            return;
        }

        // Validate
        List<String> errors = validate(chatMsg);
        if (!errors.isEmpty()) {
            sendError(decorated, "Validation failed: " + String.join("; ", errors));
            return;
        }

        String roomId = String.format("%02d", chatMsg.getRoomId());

        // Session room membership (relaxed semantic mode)
        if (chatMsg.getMessageType() == MessageType.LEAVE) {
            removeSession(decorated);
        } else {
            registerSession(decorated, roomId);
        }

        // Build queue message
        String clientIp = extractClientIp(session);
        QueueMessage queueMsg = QueueMessage.from(chatMsg, serverId, clientIp);

        // Send RECEIVED ack immediately — before SQS publish.
        // Async publish happens in background; client does not wait for SQS confirmation.
        String ack = mapper.writeValueAsString(Map.of(
                "status", "RECEIVED",
                "messageId", queueMsg.getMessageId()
        ));
        decorated.sendMessage(new TextMessage(ack));

        // Fire-and-forget SQS publish
        sqsPublisher.publishAsync(queueMsg);
    }

    /**
     * Called by InternalBroadcastController to push a message to all sessions in a room.
     * ConcurrentWebSocketSessionDecorator handles thread safety — no synchronized needed.
     */
    public void broadcastToRoom(String roomId, String messageJson) {
        CopyOnWriteArrayList<ConcurrentWebSocketSessionDecorator> sessions = roomSessions.get(roomId);
        if (sessions == null || sessions.isEmpty()) {
            log.debug("broadcastToRoom: no sessions for room {}", roomId);
            return;
        }
        TextMessage textMessage = new TextMessage(messageJson);
        for (ConcurrentWebSocketSessionDecorator s : sessions) {
            try {
                if (s.isOpen()) {
                    s.sendMessage(textMessage);
                }
            } catch (Exception e) {
                log.warn("Failed to broadcast to session {}: {}", s.getId(), e.getMessage());
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Transport error: session={}, error={}", session.getId(), exception.getMessage());
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private void registerSession(ConcurrentWebSocketSessionDecorator session, String roomId) {
        String currentRoom = sessionRoomIndex.get(session.getId());
        if (roomId.equals(currentRoom)) return;

        if (currentRoom != null) {
            CopyOnWriteArrayList<ConcurrentWebSocketSessionDecorator> old = roomSessions.get(currentRoom);
            if (old != null) old.remove(session);
        }

        roomSessions.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).add(session);
        sessionRoomIndex.put(session.getId(), roomId);
        log.debug("Session {} registered in room {}", session.getId(), roomId);
    }

    private void removeSession(ConcurrentWebSocketSessionDecorator session) {
        String roomId = sessionRoomIndex.remove(session.getId());
        if (roomId != null) {
            CopyOnWriteArrayList<ConcurrentWebSocketSessionDecorator> sessions = roomSessions.get(roomId);
            if (sessions != null) sessions.remove(session);
            log.debug("Session {} removed from room {}", session.getId(), roomId);
        }
    }

    private List<String> validate(ChatMessage msg) {
        List<String> errors = new ArrayList<>();

        if (msg.getUserId() == null || msg.getUserId().isBlank()) {
            errors.add("userId is required");
        } else {
            try {
                int id = Integer.parseInt(msg.getUserId());
                if (id < 1 || id > 100000) errors.add("userId must be between 1 and 100000");
            } catch (NumberFormatException e) {
                errors.add("userId must be a numeric string");
            }
        }

        if (msg.getUsername() == null || !msg.getUsername().matches("^[a-zA-Z0-9]{3,20}$")) {
            errors.add("username must be 3-20 alphanumeric characters");
        }

        if (msg.getMessage() == null || msg.getMessage().isEmpty() || msg.getMessage().length() > 500) {
            errors.add("message must be 1-500 characters");
        }

        if (msg.getTimestamp() == null) {
            errors.add("timestamp is required and must be valid ISO-8601");
        }

        if (msg.getMessageType() == null) {
            errors.add("messageType must be one of: TEXT, JOIN, LEAVE");
        }

        if (msg.getRoomId() < 1 || msg.getRoomId() > 20) {
            errors.add("roomId must be between 1 and 20");
        }

        return errors;
    }

    private void sendError(ConcurrentWebSocketSessionDecorator session, String errorMsg) throws Exception {
        String json = mapper.writeValueAsString(Map.of("status", "ERROR", "error", errorMsg));
        session.sendMessage(new TextMessage(json));
    }

    private String extractClientIp(WebSocketSession session) {
        try {
            return session.getRemoteAddress() != null
                    ? session.getRemoteAddress().getAddress().getHostAddress()
                    : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
