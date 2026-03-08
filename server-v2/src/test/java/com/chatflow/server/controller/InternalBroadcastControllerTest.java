package com.chatflow.server.controller;

import com.chatflow.server.handler.ChatWebSocketHandler;
import com.chatflow.server.sqs.SqsPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for InternalBroadcastController.
 * SqsPublisher is mocked so no AWS connection is needed.
 * ChatWebSocketHandler.broadcastToRoom() is verified via MockMvc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class InternalBroadcastControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SqsPublisher sqsPublisher;

    @Autowired
    private ChatWebSocketHandler wsHandler;

    @Test
    void broadcastEndpoint_returnsOk() throws Exception {
        String body = "{\"messageId\":\"uuid-1\",\"roomId\":\"05\",\"userId\":\"1\"," +
                      "\"username\":\"user1\",\"message\":\"hello\",\"messageType\":\"TEXT\"}";

        mockMvc.perform(post("/internal/broadcast/05")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.roomId").value("05"));
    }

    @Test
    void broadcastEndpoint_emptyRoom_stillReturnsOk() throws Exception {
        // Room 19 has no sessions — should return OK with no error
        mockMvc.perform(post("/internal/broadcast/19")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messageId\":\"uuid-2\",\"roomId\":\"19\",\"message\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));
    }

    @Test
    void broadcastEndpoint_differentRooms_usesCorrectRoomId() throws Exception {
        for (int roomId = 1; roomId <= 20; roomId++) {
            String paddedRoom = String.format("%02d", roomId);
            mockMvc.perform(post("/internal/broadcast/" + paddedRoom)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"roomId\":\"" + paddedRoom + "\",\"message\":\"test\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roomId").value(paddedRoom));
        }
    }
}
