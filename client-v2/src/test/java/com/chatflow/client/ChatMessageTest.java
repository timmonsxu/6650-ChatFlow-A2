package com.chatflow.client;

import com.chatflow.client.model.ChatMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatMessageTest {

    @Test
    void toJson_containsAllRequiredFields() {
        ChatMessage msg = new ChatMessage("123", "user123", "hello", "TEXT", 5);
        String json = msg.toJson();
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

        assertEquals("123", obj.get("userId").getAsString());
        assertEquals("user123", obj.get("username").getAsString());
        assertEquals("hello", obj.get("message").getAsString());
        assertEquals("TEXT", obj.get("messageType").getAsString());
        assertNotNull(obj.get("timestamp").getAsString());
        // roomId is serialized for server-side routing in this assignment version
        assertEquals(5, obj.get("roomId").getAsInt());
    }

    @Test
    void toJson_validTimestampFormat() {
        ChatMessage msg = new ChatMessage("1", "abc", "hi", "JOIN", 1);
        String json = msg.toJson();
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

        String ts = obj.get("timestamp").getAsString();
        // Should be ISO-8601 format like "2026-02-09T22:00:00Z"
        assertTrue(ts.contains("T"), "Timestamp should be ISO-8601 format");
    }

    @Test
    void toJson_allMessageTypes() {
        for (String type : new String[]{"TEXT", "JOIN", "LEAVE"}) {
            ChatMessage msg = new ChatMessage("1", "abc", "hi", type, 1);
            String json = msg.toJson();
            assertTrue(json.contains("\"" + type + "\""));
        }
    }

    @Test
    void getRoomId_returnsCorrectValue() {
        ChatMessage msg = new ChatMessage("1", "abc", "hi", "TEXT", 17);
        assertEquals(17, msg.getRoomId());
    }

    @Test
    void getMessageType_returnsCorrectValue() {
        ChatMessage msg = new ChatMessage("1", "abc", "hi", "LEAVE", 1);
        assertEquals("LEAVE", msg.getMessageType());
    }
}
