package com.chatflow.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Verifies Spring context loads cleanly.
 * SqsConsumerService is mocked so @PostConstruct does not attempt to connect to AWS.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsumerApplicationTest {

    @MockBean
    private SqsConsumerService sqsConsumerService;

    @Test
    void contextLoads() {
        // If Spring context starts without errors, this test passes
    }
}
