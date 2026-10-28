package com.streaming;

import com.streaming.domain.MatchEvent;

/**
 * Port for publishing match events to the event bus.
 * <p>
 * The REST controller depends on this interface, not on Kafka, so the web layer can be
 * tested without a broker and the transport could be swapped later.
 * The Kafka adapter is {@link com.streaming.kafka.KafkaMatchEventPublisher}.
 */
public interface MatchEventPublisher {

    /**
     * Publishes the event and waits for the broker acknowledgement.
     *
     * @throws EventPublishException if the event could not be published
     */
    void publish(MatchEvent event);
}
