package com.streaming.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of a match, built by applying events in order.
 * Returned by GET /stream.
 */
public record MatchState(
        String matchId,
        MatchStatus status,
        Map<String, Integer> score,
        List<MatchEvent> recentEvents,
        Instant updatedAt) {

    public static final int MAX_RECENT_EVENTS = 20;

    /** Defensive copies keep the record truly immutable (safe to share across threads). */
    public MatchState {
        score = Map.copyOf(score);
        recentEvents = List.copyOf(recentEvents);
    }

    public static MatchState initial(String matchId) {
        return new MatchState(matchId, MatchStatus.SCHEDULED, Map.of(), List.of(), Instant.EPOCH);
    }

    /** Returns a new state with the event applied; this instance is never modified. */
    public MatchState apply(MatchEvent event) {
        Map<String, Integer> newScore = new HashMap<>(score);
        if (event.type() == MatchEventType.GOAL && event.team() != null) {
            newScore.merge(event.team(), 1, Integer::sum);
        }

        MatchStatus newStatus = switch (event.type()) {
            case KICKOFF -> MatchStatus.LIVE;
            case HALF_TIME -> MatchStatus.HALF_TIME;
            case FULL_TIME -> MatchStatus.FINISHED;
            default -> status == MatchStatus.SCHEDULED ? MatchStatus.LIVE : status;
        };

        // Newest first, capped to MAX_RECENT_EVENTS.
        List<MatchEvent> events = new ArrayList<>(recentEvents.size() + 1);
        events.add(event);
        events.addAll(recentEvents);
        if (events.size() > MAX_RECENT_EVENTS) {
            events = events.subList(0, MAX_RECENT_EVENTS);
        }

        return new MatchState(matchId, newStatus, newScore, events, event.timestamp());
    }
}
