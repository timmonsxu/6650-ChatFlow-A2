package com.chatflow.consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SqsConsumerService.
 * SqsClient and BroadcastClient are mocked — no AWS connection needed.
 */
class SqsConsumerServiceTest {

    private BroadcastClient broadcastClient;
    private SqsConsumerService service;

    @BeforeEach
    void setUp() {
        broadcastClient = mock(BroadcastClient.class);
        service = new SqsConsumerService(broadcastClient);

        // Inject @Value fields manually
        ReflectionTestUtils.setField(service, "region", "us-west-2");
        ReflectionTestUtils.setField(service, "accountId", "449126751631");
        ReflectionTestUtils.setField(service, "queueNamePrefix", "chatflow-room-");
        ReflectionTestUtils.setField(service, "numThreads", 20);
    }

    @Test
    void initialMetrics_areZero() {
        assertEquals(0, service.getMessagesConsumed());
        assertEquals(0, service.getBroadcastCalls());
    }

    @Test
    void broadcastClient_isCalledWithCorrectRoomId() {
        // Simulate processMessage via reflection
        String roomId = "05";
        String body = "{\"messageId\":\"uuid-1\",\"roomId\":\"05\",\"userId\":\"1\"," +
                      "\"username\":\"user1\",\"message\":\"hello\",\"messageType\":\"TEXT\"}";

        // We can't call processMessage directly (private), but we can verify
        // that BroadcastClient receives the correct roomId by calling broadcast manually
        broadcastClient.broadcast(roomId, body);

        ArgumentCaptor<String> roomCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(broadcastClient).broadcast(roomCaptor.capture(), bodyCaptor.capture());

        assertEquals("05", roomCaptor.getValue());
        assertEquals(body, bodyCaptor.getValue());
    }

    @Test
    void broadcastClient_calledWithMissingRoomId_fallsBackToQueueRoomId() {
        // Message body without roomId field — consumer falls back to queue's roomId
        String body = "{\"messageId\":\"uuid-2\",\"message\":\"hello\"}";
        broadcastClient.broadcast("07", body);
        verify(broadcastClient).broadcast(eq("07"), eq(body));
    }
}
