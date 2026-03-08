package com.chatflow.consumer;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {

    private final SqsConsumerService consumerService;

    public HealthController(SqsConsumerService consumerService) {
        this.consumerService = consumerService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "timestamp", Instant.now().toString(),
                "messagesConsumed", consumerService.getMessagesConsumed(),
                "broadcastCalls", consumerService.getBroadcastCalls()
        );
    }
}
