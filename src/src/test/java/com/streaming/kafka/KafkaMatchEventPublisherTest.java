package com.streaming.kafka;

import com.streaming.EventPublishException;
import com.streaming.domain.MatchEvent;
import com.streaming.domain.MatchEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the failure paths of the Kafka adapter (KafkaTemplate is mocked). */
class KafkaMatchEventPublisherTest {

    private static final String TOPIC = "match-events";

    private KafkaTemplate<String, String> kafkaTemplate;
    private KafkaMatchEventPublisher publisher;
    private final MatchEvent event = new MatchEvent(null, "m1", MatchEventType.KICKOFF, 0, null, null, null);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        publisher = new KafkaMatchEventPublisher(
                kafkaTemplate, JsonMapper.builder().build(), TOPIC, Duration.ofMillis(200));
    }

    @Test
    @DisplayName("sends with key = matchId to the configured topic")
    void usesMatchIdAsKey() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(new CompletableFuture<>()); // never completes -> timeout, irrelevant here

        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(EventPublishException.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq("m1"), anyString());
    }

    @Test
    @DisplayName("broker down: synchronous KafkaException from send() becomes EventPublishException (503)")
    void synchronousSendFailure() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new KafkaException("Send failed"));

        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(EventPublishException.class)
                .hasCauseInstanceOf(KafkaException.class);
    }

    @Test
    @DisplayName("broker rejects the record: failed future becomes EventPublishException")
    void asynchronousSendFailure() {
        CompletableFuture<SendResult<String, String>> failed =
                CompletableFuture.failedFuture(new org.apache.kafka.common.errors.TimeoutException("boom"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failed);

        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(EventPublishException.class);
    }

    @Test
    @DisplayName("no ack within the send timeout becomes EventPublishException")
    void ackTimeout() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(new CompletableFuture<>()); // never completes

        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(EventPublishException.class)
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
    }
}
