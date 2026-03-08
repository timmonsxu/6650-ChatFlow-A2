package com.chatflow.client;

import com.chatflow.client.generator.MessageGenerator;
import com.chatflow.client.model.ChatMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

class MessageGeneratorTest {

    /** Builds a map of 20 sub-queues with the given capacity each. */
    private Map<Integer, BlockingQueue<ChatMessage>> makeRoomQueues(int capacityEach) {
        Map<Integer, BlockingQueue<ChatMessage>> queues = new HashMap<>();
        for (int r = 1; r <= 20; r++) {
            queues.put(r, new LinkedBlockingQueue<>(capacityEach));
        }
        return queues;
    }

    @Test
    void generatesCorrectTotalNumberOfMessages() throws InterruptedException {
        int total = 1000;
        Map<Integer, BlockingQueue<ChatMessage>> queues = makeRoomQueues(total);
        MessageGenerator gen = new MessageGenerator(queues, total);

        Thread t = new Thread(gen);
        t.start();
        t.join(10_000);

        int sum = queues.values().stream().mapToInt(BlockingQueue::size).sum();
        assertEquals(total, sum, "Total messages across all sub-queues should equal totalMessages");
    }

    @Test
    void eachMessageLandsInCorrectSubQueue() throws InterruptedException {
        int total = 2000;
        Map<Integer, BlockingQueue<ChatMessage>> queues = makeRoomQueues(total);
        MessageGenerator gen = new MessageGenerator(queues, total);

        Thread t = new Thread(gen);
        t.start();
        t.join(10_000);

        for (int roomId = 1; roomId <= 20; roomId++) {
            for (ChatMessage msg : queues.get(roomId)) {
                assertEquals(roomId, msg.getRoomId(),
                        "Message in sub-queue " + roomId + " must have matching roomId");
            }
        }
    }

    @Test
    void generatedMessagesHaveValidFields() throws InterruptedException {
        int total = 200;
        Map<Integer, BlockingQueue<ChatMessage>> queues = makeRoomQueues(total);
        MessageGenerator gen = new MessageGenerator(queues, total);

        Thread t = new Thread(gen);
        t.start();
        t.join(5_000);

        for (int roomId = 1; roomId <= 20; roomId++) {
            for (ChatMessage msg : queues.get(roomId)) {
                String json = msg.toJson();
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

                int userId = Integer.parseInt(obj.get("userId").getAsString());
                assertTrue(userId >= 1 && userId <= 100000, "userId out of range: " + userId);

                assertEquals("user" + userId, obj.get("username").getAsString());
                assertFalse(obj.get("message").getAsString().isEmpty());

                String type = obj.get("messageType").getAsString();
                assertTrue(type.equals("TEXT") || type.equals("JOIN") || type.equals("LEAVE"),
                        "Invalid messageType: " + type);

                assertEquals(roomId, obj.get("roomId").getAsInt());
            }
        }
    }

    @Test
    void messageTypeDistribution_approximately90_5_5() throws InterruptedException {
        int total = 50_000;
        Map<Integer, BlockingQueue<ChatMessage>> queues = makeRoomQueues(total);
        MessageGenerator gen = new MessageGenerator(queues, total);

        Thread t = new Thread(gen);
        t.start();
        t.join(15_000);

        Map<String, Integer> counts = new HashMap<>();
        for (BlockingQueue<ChatMessage> q : queues.values()) {
            for (ChatMessage msg : q) {
                counts.merge(msg.getMessageType(), 1, Integer::sum);
            }
        }

        int text  = counts.getOrDefault("TEXT", 0);
        int join  = counts.getOrDefault("JOIN", 0);
        int leave = counts.getOrDefault("LEAVE", 0);

        double textPct  = (double) text  / total * 100;
        double joinPct  = (double) join  / total * 100;
        double leavePct = (double) leave / total * 100;

        assertTrue(textPct  > 87 && textPct  < 93, "TEXT should be ~90%, got "  + String.format("%.1f%%", textPct));
        assertTrue(joinPct  > 3  && joinPct  < 7,  "JOIN should be ~5%, got "   + String.format("%.1f%%", joinPct));
        assertTrue(leavePct > 3  && leavePct < 7,  "LEAVE should be ~5%, got "  + String.format("%.1f%%", leavePct));
    }

    @Test
    void roomIdDistribution_coversAllRooms() throws InterruptedException {
        int total = 10_000;
        Map<Integer, BlockingQueue<ChatMessage>> queues = makeRoomQueues(total);
        MessageGenerator gen = new MessageGenerator(queues, total);

        Thread t = new Thread(gen);
        t.start();
        t.join(10_000);

        // Every sub-queue should have received at least some messages
        for (int roomId = 1; roomId <= 20; roomId++) {
            assertTrue(queues.get(roomId).size() > 0,
                    "Sub-queue for room " + roomId + " should have received messages");
        }
    }
}
