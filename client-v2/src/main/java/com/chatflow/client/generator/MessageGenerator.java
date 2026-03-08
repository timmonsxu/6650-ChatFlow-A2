package com.chatflow.client.generator;

import com.chatflow.client.model.ChatMessage;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates messages and routes each one directly into the sub-queue for its roomId.
 * messageType distribution is preserved: 90% TEXT, 5% JOIN, 5% LEAVE.
 * roomId is random 1-20; the correct sub-queue is selected by roomId.
 */
public class MessageGenerator implements Runnable {

    private static final String[] MESSAGE_POOL = {
        "Hello everyone!", "How's it going?", "Good morning!", "Good evening!",
        "Anyone here?", "What's the latest news?", "Great discussion today!",
        "I agree with that point.", "Can someone help me?", "Thanks for the info!",
        "Let me check on that.", "Interesting perspective!", "Welcome to the chat!",
        "Bye for now!", "See you later!", "That's a great idea!",
        "I'll get back to you.", "Working on it now.", "Almost done!",
        "Let's schedule a meeting.", "Please review my PR.", "Build is passing!",
        "Found a bug.", "Fixed the issue!", "Deploying to production.",
        "Tests are green.", "Need a code review.", "Updated the documentation.",
        "Sprint planning tomorrow.", "Standup in 5 minutes.", "Happy Friday!",
        "Monday blues.", "Coffee break?", "Lunch anyone?",
        "New feature shipped!", "Rollback needed.", "Monitoring looks good.",
        "Alert resolved.", "On call this week.", "Incident report filed.",
        "Performance improved!", "Memory usage is high.", "CPU spike detected.",
        "Scaling up instances.", "Cache invalidated.", "Database migration done.",
        "API latency is low.", "Throughput increased!", "Load test passed!",
        "Ready for launch!", "System all green!"
    };

    private final Map<Integer, BlockingQueue<ChatMessage>> roomQueues;
    private final int totalMessages;

    /**
     * @param roomQueues    map of roomId (1-20) to its dedicated BlockingQueue
     * @param totalMessages total number of messages to generate across all rooms
     */
    public MessageGenerator(Map<Integer, BlockingQueue<ChatMessage>> roomQueues, int totalMessages) {
        this.roomQueues = roomQueues;
        this.totalMessages = totalMessages;
    }

    @Override
    public void run() {
        try {
            ThreadLocalRandom rand = ThreadLocalRandom.current();
            for (int i = 0; i < totalMessages; i++) {
                int userId = rand.nextInt(1, 100001);
                String username = "user" + userId;
                String message = MESSAGE_POOL[rand.nextInt(MESSAGE_POOL.length)];
                int roomId = rand.nextInt(1, 21);
                String messageType = pickMessageType(rand);

                ChatMessage msg = new ChatMessage(
                        String.valueOf(userId), username, message, messageType, roomId);

                // Put directly into the sub-queue for this roomId — no filtering needed
                roomQueues.get(roomId).put(msg);
            }
            System.out.println("[Generator] All " + totalMessages + " messages generated.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[Generator] Interrupted.");
        }
    }

    private String pickMessageType(ThreadLocalRandom rand) {
        int roll = rand.nextInt(100);
        if (roll < 90) return "TEXT";
        if (roll < 95) return "JOIN";
        return "LEAVE";
    }
}
