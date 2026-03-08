package com.chatflow.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Calls POST /internal/broadcast/{roomId} on every known Server-v2 instance IN PARALLEL.
 *
 * Previously, servers were called sequentially so total time = sum of all call times.
 * Now all calls are fired simultaneously, so total time = max of all call times.
 * This means adding more servers does NOT increase Consumer processing time per message.
 *
 * In Part 1: one server URL (localhost:8080).
 * In Part 3: two server URLs (localhost:8080, EC2-B:8080).
 */
@Component
public class BroadcastClient {

    private static final Logger log = LoggerFactory.getLogger(BroadcastClient.class);

    private final List<String> serverUrls;
    private final HttpClient httpClient;

    public BroadcastClient(@Value("${app.broadcast.server-urls}") String serverUrlsCsv) {
        this.serverUrls = Arrays.asList(serverUrlsCsv.split(","));
        // Short connect timeout so unavailable servers fail fast
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(200))
                .build();
        log.info("BroadcastClient initialised with servers: {}", serverUrls);
    }

    /**
     * Broadcasts to all servers IN PARALLEL using CompletableFuture.
     * All HTTP requests are sent simultaneously; we wait for all to complete.
     * Total time = max(individual call times) instead of sum(individual call times).
     *
     * @param roomId      zero-padded room id, e.g. "05"
     * @param messageJson raw JSON string of the QueueMessage
     */
    public void broadcast(String roomId, String messageJson) {
        List<CompletableFuture<Void>> futures = serverUrls.stream()
                .map(baseUrl -> {
                    String url = baseUrl.trim() + "/internal/broadcast/" + roomId;
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(2))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(messageJson))
                            .build();

                    // sendAsync returns immediately — all requests fire simultaneously
                    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                            .thenAccept(response -> {
                                if (response.statusCode() != 200) {
                                    log.warn("Broadcast to {} returned status {}: {}",
                                            url, response.statusCode(), response.body());
                                }
                            })
                            .exceptionally(e -> {
                                log.error("Broadcast to {} failed: {}", url, e.getMessage());
                                return null;
                            });
                })
                .toList();

        // Wait for all parallel calls to complete before returning
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
}
