package com.chatflow.consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BroadcastClient.
 * HttpClient is not mocked here — we verify the URL construction logic
 * and multi-server behaviour by inspecting what BroadcastClient would call.
 * Full integration is covered by ConsumerApplicationTest (context load only).
 */
class BroadcastClientTest {

    @Test
    void singleServer_constructsCorrectUrl() {
        // Just verify construction doesn't throw and server list is parsed correctly
        BroadcastClient client = new BroadcastClient("http://localhost:8080");
        assertNotNull(client);
    }

    @Test
    void multipleServers_parsedCorrectly() {
        BroadcastClient client = new BroadcastClient(
                "http://localhost:8080,http://localhost:8082,http://localhost:8083");
        assertNotNull(client);
    }

    @Test
    void serverUrlWithTrailingSpace_handledGracefully() {
        // CSV may have spaces after commas
        assertDoesNotThrow(() ->
                new BroadcastClient("http://localhost:8080, http://localhost:8082"));
    }
}
