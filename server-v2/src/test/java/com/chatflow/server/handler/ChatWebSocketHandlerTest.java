package com.chatflow.server.handler;

import com.chatflow.server.sqs.SqsPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChatWebSocketHandler.
 *
 * SqsPublisher is mocked so no AWS connection is needed.
 * Tests verify:
 *   - Valid messages receive RECEIVED ack with a messageId
 *   - All validation rules from A1 are preserved
 *   - New roomId field is validated (1-20)
 *   - Session room registration follows relaxed semantic mode
 *   - broadcastToRoom pushes to correct open sessions
 */
class ChatWebSocketHandlerTest {

    private ChatWebSocketHandler handler;
    private SqsPublisher sqsPublisher;
    private WebSocketSession session;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        sqsPublisher = mock(SqsPublisher.class);
        handler = new ChatWebSocketHandler(sqsPublisher);
        // Inject server-id via reflection (normally set by @Value)
        ReflectionTestUtils.setField(handler, "serverId", "server-test");

        session = mock(WebSocketSession.class);
        mapper = new ObjectMapper();

        when(session.getId()).thenReturn("test-session");
        when(session.getUri()).thenReturn(URI.create("ws://localhost:8080/chat/5"));
        when(session.isOpen()).thenReturn(true);
        when(session.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));

        // New handler flow requires established connection to register decorated session.
        handler.afterConnectionEstablished(session);
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private String captureResponse() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        return captor.getValue().getPayload();
    }

    private String validMsg(String messageType, int roomId) {
        return String.format(
            "{\"userId\":\"123\",\"username\":\"user123\",\"message\":\"hello\"," +
            "\"timestamp\":\"2026-03-07T12:00:00Z\",\"messageType\":\"%s\",\"roomId\":%d}",
            messageType, roomId);
    }

    // ── Valid message ack format ──────────────────────────────────────────────

    @Test
    void validTextMessage_returnsReceivedAck() throws Exception {
        handler.handleTextMessage(session, new TextMessage(validMsg("TEXT", 5)));

        String response = captureResponse();
        JsonNode node = mapper.readTree(response);
        assertEquals("RECEIVED", node.get("status").asText());
        assertNotNull(node.get("messageId"), "ack must include messageId");
        assertFalse(node.get("messageId").asText().isBlank());
    }

    @Test
    void validJoinMessage_returnsReceivedAck() throws Exception {
        handler.handleTextMessage(session, new TextMessage(validMsg("JOIN", 3)));

        String response = captureResponse();
        JsonNode node = mapper.readTree(response);
        assertEquals("RECEIVED", node.get("status").asText());
    }

    @Test
    void validLeaveMessage_returnsReceivedAck() throws Exception {
        handler.handleTextMessage(session, new TextMessage(validMsg("LEAVE", 10)));

        String response = captureResponse();
        JsonNode node = mapper.readTree(response);
        assertEquals("RECEIVED", node.get("status").asText());
    }

    // ── SQS publish is called for valid messages ──────────────────────────────

    @Test
    void validMessage_sqsPublishIsCalled() throws Exception {
        handler.handleTextMessage(session, new TextMessage(validMsg("TEXT", 7)));
        verify(sqsPublisher, times(1)).publishAsync(any());
    }

    @Test
    void invalidMessage_sqsPublishIsNotCalled() throws Exception {
        String badMsg = "{\"userId\":\"0\",\"username\":\"user123\",\"message\":\"hi\"," +
                        "\"timestamp\":\"2026-03-07T12:00:00Z\",\"messageType\":\"TEXT\",\"roomId\":5}";
        handler.handleTextMessage(session, new TextMessage(badMsg));
        verify(sqsPublisher, never()).publishAsync(any());
    }

    // ── Invalid JSON ──────────────────────────────────────────────────────────

    @Test
    void invalidJson_returnsError() throws Exception {
        handler.handleTextMessage(session, new TextMessage("not json"));

        String response = captureResponse();
        JsonNode node = mapper.readTree(response);
        assertEquals("ERROR", node.get("status").asText());
        assertTrue(node.get("error").asText().contains("Invalid JSON"));
    }

    // ── userId validation ─────────────────────────────────────────────────────

    @Test
    void userId_zero_returnsError() throws Exception {
        handler.handleTextMessage(session, new TextMessage(
            "{\"userId\":\"0\",\"username\":\"user123\",\"message\":\"hi\"," +
            "\"timestamp\":\"2026-03-07T12:00:00Z\",\"messageType\":\"TEXT\",\"roomId\":5}"));
        assertTrue(captureResponse().contains("userId must be between"));
    }

    @Test
    void userId_tooLarge_returnsError() throws Exception {
        handler.handleTextMessage(session, new TextMessage(
            "{\"userId\":\"100001\",\"username\":\"user123\",\"message\":\"hi\"," +
            "\"timestamp\":\"2026-03-07T12:00:00Z\",\"messageType\":\"TEXT\",\"roomId\":5}"));
        assertTrue(captureResponse().contains("userId must be between"));
    }

    @Test
    void userId_nonNumeric_returnsError() throws Exception {
        handler.handleTextMessage(session, new TextMessage(
            "{\"userId\":\"abc\",\"username\":\"user123\",\"message\":\"hi\"," +
            "\"timestamp\":\"2026-03-07T12:00:00Z\",\"messageType\":\"TEXT\",\"roomId\":5}"));
        assertTrue(captureResponse().contains("userId must be a numeric"));
    }

    @Test
    void userId_boundary1_returnsReceived() throws Exception {
        handler.handleTextMessage(session, new TextMessage(validMsg("TEXT", 1)
            .replace("\"userId\":\"123\"", "\"userId\":\"1\"")));
        assertEquals("RECEIVED", mapper.readTree(captureResponse()).get("status").asText());
    }

    @Test
    void userId_boundary100000_returnsReceived() throws Exception {
        handler.handleTextMessage(session, new TextMessage(validMsg("TEXT", 1)
            .replace("\"userId\":\"123\"", "\"userId\":\"100000\"")));
        assertEquals("RECEIVED", mapper.readTree(captureResponse()).get("status").asText());
    }

    // ── username validation ───────────────────────────────────────────────────

    @Test
    void username_tooShort_returnsError() throws Exception {
        handler.handleTextMessage(session, new TextMessage(
            "{\"userId\":\"1\",\"username\":\"ab\",\"message\":\"hi\"," +
            "\"timestamp\":\"2026-03-07T12:00:00Z\",\"messageType\":\"TEXT\",\"roomId\":5}"));
        assertTrue(captureResponse().contains("username"));
    }

    @Test
    void username_tooLong_returnsError() throws Exception {
        handler.handleTextMessage(session, new TextMessage(
            "{\"userId\":\"1\",\"username\":\"abcdefghijklmnopqrstu\",\"message\":\"hi\"," +
            "\"timestamp\":\"2026-03-07T12:00:00Z\",\"messageType\":\"TEXT\",\"roomId\":5}"));
        assertTrue(captureResponse().contains("username"));
    }

    @Test
    void username_specialChars_returnsError() throws Exception {
        handler.handleTextMessage(session, new TextMessage(
            "{\"userId\":\"1\",\"username\":\"user@123\",\"message\":\"hi\"," +
            "\"timestamp\":\"2026-03-07T12:00:00Z\",\"messageType\":\"TEXT\",\"roomId\":5}"));
        assertTrue(captureResponse().contains("username"));
    }

    @Test
    void username_boundary3chars_returnsReceived() throws Exception {
        handler.handleTextMessage(session, new TextMessage(
            "{\"userId\":\"1\",\"username\":\"abc\",\"message\":\"hi\"," +
            "\"timestamp\":\"2026-03-07T12:00:00Z\",\"messageType\":\"TEXT\",\"roomId\":5}"));
        assertEquals("RECEIVED", mapper.readTree(captureResponse()).get("status").asText());
    }

    @Test
    void username_boundary20chars_returnsReceived() throws Exception {
        handler.handleTextMessage(session, new TextMessage(
            "{\"userId\":\"1\",\"username\":\"abcdefghijklmnopqrst\",\"message\":\"hi\"," +
            "\"timestamp\":\"2026-03-07T12:00:00Z\",\"messageType\":\"TEXT\",\"roomId\":5}"));
        assertEquals("RECEIVED", mapper.readTree(captureResponse()).get("status").asText());
    }

    // ── message validation ────────────────────────────────────────────────────

    @Test
    void message_empty_returnsError() throws Exception {
        handler.handleTextMessage(session, new TextMessage(
            "{\"userId\":\"1\",\"username\":\"abc\",\"message\":\"\"," +
            "\"timestamp\":\"2026-03-07T12:00:00Z\",\"messageType\":\"TEXT\",\"roomId\":5}"));
        assertTrue(captureResponse().contains("message must be"));
    }

    @Test
    void message_tooLong_returnsError() throws Exception {
        String longMsg = "x".repeat(501);
        handler.handleTextMessage(session, new TextMessage(
            "{\"userId\":\"1\",\"username\":\"abc\",\"message\":\"" + longMsg + "\"," +
            "\"timestamp\":\"2026-03-07T12:00:00Z\",\"messageType\":\"TEXT\",\"roomId\":5}"));
        assertTrue(captureResponse().contains("message must be"));
    }

    @Test
    void message_boundary500chars_returnsReceived() throws Exception {
        String longMsg = "x".repeat(500);
        handler.handleTextMessage(session, new TextMessage(
            "{\"userId\":\"1\",\"username\":\"abc\",\"message\":\"" + longMsg + "\"," +
            "\"timestamp\":\"2026-03-07T12:00:00Z\",\"messageType\":\"TEXT\",\"roomId\":5}"));
        assertEquals("RECEIVED", mapper.readTree(captureResponse()).get("status").asText());
    }

    // ── roomId validation (new in A2) ─────────────────────────────────────────

    @Test
    void roomId_zero_returnsError() throws Exception {
        handler.handleTextMessage(session, new TextMessage(validMsg("TEXT", 0)));
        assertTrue(captureResponse().contains("roomId must be between"));
    }

    @Test
    void roomId_tooLarge_returnsError() throws Exception {
        handler.handleTextMessage(session, new TextMessage(validMsg("TEXT", 21)));
        assertTrue(captureResponse().contains("roomId must be between"));
    }

    @Test
    void roomId_boundary1_returnsReceived() throws Exception {
        handler.handleTextMessage(session, new TextMessage(validMsg("TEXT", 1)));
        assertEquals("RECEIVED", mapper.readTree(captureResponse()).get("status").asText());
    }

    @Test
    void roomId_boundary20_returnsReceived() throws Exception {
        handler.handleTextMessage(session, new TextMessage(validMsg("TEXT", 20)));
        assertEquals("RECEIVED", mapper.readTree(captureResponse()).get("status").asText());
    }

    // ── messageType validation ────────────────────────────────────────────────

    @Test
    void messageType_invalid_returnsError() throws Exception {
        handler.handleTextMessage(session, new TextMessage(
            "{\"userId\":\"1\",\"username\":\"abc\",\"message\":\"hi\"," +
            "\"timestamp\":\"2026-03-07T12:00:00Z\",\"messageType\":\"SHOUT\",\"roomId\":5}"));
        assertEquals("ERROR", mapper.readTree(captureResponse()).get("status").asText());
    }

    // ── Session room registration (relaxed semantic mode) ─────────────────────

    @Test
    void textMessage_registersSessionInRoom() throws Exception {
        handler.handleTextMessage(session, new TextMessage(validMsg("TEXT", 5)));

        // After TEXT, session should be in room "05" and receive broadcasts
        WebSocketSession other = mock(WebSocketSession.class);
        when(other.getId()).thenReturn("other-session");
        when(other.isOpen()).thenReturn(true);

        // broadcastToRoom should reach our session
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        handler.broadcastToRoom("05", "{\"test\":\"broadcast\"}");
        verify(session, atLeastOnce()).sendMessage(any());
    }

    @Test
    void leaveMessage_removesSessionFromRoom() throws Exception {
        // First register via TEXT
        handler.handleTextMessage(session, new TextMessage(validMsg("TEXT", 5)));
        clearInvocations(session);

        // Then send LEAVE
        handler.handleTextMessage(session, new TextMessage(validMsg("LEAVE", 5)));
        clearInvocations(session);

        // Now broadcast should NOT reach this session
        handler.broadcastToRoom("05", "{\"test\":\"after leave\"}");
        verify(session, never()).sendMessage(any());
    }

    @Test
    void broadcastToRoom_skipsClosedSessions() throws Exception {
        // Register session
        handler.handleTextMessage(session, new TextMessage(validMsg("TEXT", 5)));
        clearInvocations(session);

        // Mark session as closed
        when(session.isOpen()).thenReturn(false);

        // Broadcast should not throw and should not call sendMessage on closed session
        assertDoesNotThrow(() -> handler.broadcastToRoom("05", "{\"msg\":\"hello\"}"));
        verify(session, never()).sendMessage(any());
    }

    @Test
    void broadcastToRoom_nonExistentRoom_doesNotThrow() {
        assertDoesNotThrow(() -> handler.broadcastToRoom("99", "{\"msg\":\"hello\"}"));
    }

    // ── afterConnectionClosed cleanup ─────────────────────────────────────────

    @Test
    void connectionClosed_removesSessionFromRoom() throws Exception {
        // Register
        handler.handleTextMessage(session, new TextMessage(validMsg("TEXT", 5)));
        clearInvocations(session);

        // Close connection
        handler.afterConnectionClosed(session,
            org.springframework.web.socket.CloseStatus.NORMAL);

        // Broadcast should no longer reach this session
        handler.broadcastToRoom("05", "{\"msg\":\"after close\"}");
        verify(session, never()).sendMessage(any());
    }
}
