package com.streaming.kafka;

import com.streaming.EventPublishException;
import com.streaming.MatchEventPublisher;
import com.streaming.domain.MatchEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Kafka adapter: serializes the event to JSON and sends it to the match-events topic
 * with key = matchId, so all events of a match land in the same partition (ordering per match).
 */
@Component
public class KafkaMatchEventPublisher implements MatchEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaMatchEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final String topic;
    private final Duration sendTimeout;

    public KafkaMatchEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            JsonMapper jsonMapper,
            @Value("${streaming.kafka.topic}") String topic,
            @Value("${streaming.kafka.send-timeout:5s}") Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.topic = topic;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void publish(MatchEvent event) {
        String payload = jsonMapper.writeValueAsString(event);
        try {
            // send() is asynchronous; get() waits for the broker ack so the client
            // only receives 202 once the event is safely stored in Kafka.
            SendResult<String, String> result = kafkaTemplate
                    .send(topic, event.matchId(), payload)
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);

            log.debug("Published event {} for match {} to {}-{}@{}",
                    event.eventId(), event.matchId(), topic,
                    result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException(event, e);
        } catch (ExecutionException | TimeoutException e) {
            // Broker rejected the record, or no ack within sendTimeout.
            throw new EventPublishException(event, e);
        } catch (org.springframework.kafka.KafkaException | org.apache.kafka.common.KafkaException e) {
            // Thrown synchronously by send(): e.g. metadata not available within max.block.ms
            // (broker down). KafkaTemplate rethrows already-failed sends as "Send failed".
            throw new EventPublishException(event, e);
        }
    }
}
