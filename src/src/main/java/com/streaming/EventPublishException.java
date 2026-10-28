package com.streaming;

import com.streaming.domain.MatchEvent;

/** Thrown when a match event could not be handed over to the event bus. */
public class EventPublishException extends RuntimeException {

    public EventPublishException(MatchEvent event, Throwable cause) {
        super("Could not publish event " + event.eventId() + " for match " + event.matchId(), cause);
    }
}
