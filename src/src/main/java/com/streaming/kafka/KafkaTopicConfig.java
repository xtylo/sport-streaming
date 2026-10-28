package com.streaming.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the match-events topic. On startup Spring's KafkaAdmin creates it
 * if it does not exist (auto-creation is disabled on the broker on purpose).
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic matchEventsTopic(
            @Value("${streaming.kafka.topic}") String topic,
            @Value("${streaming.kafka.partitions:6}") int partitions,
            @Value("${streaming.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }
}
