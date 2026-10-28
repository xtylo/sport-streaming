package com.streaming.kafka;

import com.streaming.StreamingService;
import com.streaming.domain.MatchEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes match-events and feeds the in-memory state + SSE subscribers of THIS instance.
 * <p>
 * Fan-out (see docs/adr/0001): every instance uses its OWN consumer group, so every pod
 * receives every event and can push it to the SSE clients connected to it. With a shared
 * group, Kafka would split the partitions between pods and each pod would see only part
 * of the matches.
 * <p>
 * The group id is random per startup and auto-offset-reset=earliest, so a new or restarted
 * instance replays the topic and rebuilds the in-memory match state before serving clients
 * (event-sourcing style). The topic retention bounds how much is replayed.
 */
@Component
public class MatchEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(MatchEventConsumer.class);

    private final StreamingService streamingService;
    private final JsonMapper jsonMapper;

    public MatchEventConsumer(StreamingService streamingService, JsonMapper jsonMapper) {
        this.streamingService = streamingService;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(
            id = "match-events-fanout",
            topics = "${streaming.kafka.topic}",
            groupId = "streaming-#{T(java.util.UUID).randomUUID()}")
    public void onMessage(ConsumerRecord<String, String> record) {
        MatchEvent event;
        try {
            event = jsonMapper.readValue(record.value(), MatchEvent.class);
        } catch (JacksonException e) {
            // Poison message: log and skip it instead of blocking the partition with retries.
            log.error("Skipping unreadable record {}-{}@{}: {}",
                    record.topic(), record.partition(), record.offset(), e.getOriginalMessage());
            return;
        }

        log.debug("Consumed event {} for match {} from partition {} offset {}",
                event.eventId(), event.matchId(), record.partition(), record.offset());
        streamingService.onEventReceived(event);
    }
}
