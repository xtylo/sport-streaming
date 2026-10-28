package com.streaming.support;

import com.streaming.MatchEventPublisher;
import com.streaming.StreamingService;
import com.streaming.domain.MatchEvent;

/**
 * Test double for web-layer tests: instead of going through Kafka,
 * hands the event straight to the StreamingService (what the Kafka consumer will do).
 */
public class LoopbackMatchEventPublisher implements MatchEventPublisher {

    private final StreamingService streamingService;

    public LoopbackMatchEventPublisher(StreamingService streamingService) {
        this.streamingService = streamingService;
    }

    @Override
    public void publish(MatchEvent event) {
        streamingService.onEventReceived(event);
    }
}
