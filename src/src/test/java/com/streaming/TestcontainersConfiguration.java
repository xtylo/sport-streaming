package com.streaming;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Starts a real Kafka broker in Docker for tests that load the full application context.
 * <p>
 * {@code @ServiceConnection} makes Spring Boot point spring.kafka.bootstrap-servers at the
 * container automatically: no port juggling, no docker compose needed to run the tests.
 * The container lives as long as the (cached) Spring test context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Same image as docker-compose.yml, so tests and local dev run the same broker. */
    static final String KAFKA_IMAGE = "apache/kafka:4.1.0";

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(KAFKA_IMAGE);
    }
}
