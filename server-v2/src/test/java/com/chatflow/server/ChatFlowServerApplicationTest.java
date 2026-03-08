package com.chatflow.server;

import com.chatflow.server.sqs.SqsPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Verifies that the Spring context starts cleanly with SqsPublisher mocked.
 * SqsPublisher's @PostConstruct would otherwise attempt to connect to AWS.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatFlowServerApplicationTest {

    @MockBean
    private SqsPublisher sqsPublisher;

    @Test
    void contextLoads() {
        // If Spring context starts without errors, this test passes
    }
}
