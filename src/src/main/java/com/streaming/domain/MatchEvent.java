package com.streaming.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable event published for a live match (goal, card, kickoff...).
 * This is the message that travels through Kafka and is pushed to SSE clients.
 */
public record MatchEvent(
        UUID eventId,
        @NotBlank @Size(max = 64) String matchId,
        @NotNull MatchEventType type,
        @NotNull @Min(0) @Max(130) Integer minute,
        @Size(max = 64) String team,
        @Size(max = 500) String description,
        Instant timestamp) {

    /** Compact constructor: server-side defaults for optional fields. */
    public MatchEvent {
        if (eventId == null) {
            eventId = UUID.randomUUID();
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    /** Cross-field rule: goals, cards and substitutions must name a team. */
    @JsonIgnore
    @AssertTrue(message = "team is required for GOAL, YELLOW_CARD, RED_CARD and SUBSTITUTION events")
    public boolean isTeamPresentWhenRequired() {
        return type == null || !type.requiresTeam() || (team != null && !team.isBlank());
    }
}
