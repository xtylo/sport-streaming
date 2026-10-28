package com.streaming.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MatchStateTest {

    private static MatchEvent event(MatchEventType type, int minute, String team) {
        return new MatchEvent(UUID.randomUUID(), "m1", type, minute, team, null, Instant.now());
    }

    @Test
    @DisplayName("initial state is SCHEDULED with no score")
    void initialState() {
        MatchState state = MatchState.initial("m1");

        assertThat(state.status()).isEqualTo(MatchStatus.SCHEDULED);
        assertThat(state.score()).isEmpty();
        assertThat(state.recentEvents()).isEmpty();
    }

    @Test
    @DisplayName("KICKOFF moves the match to LIVE")
    void kickoffMakesMatchLive() {
        MatchState state = MatchState.initial("m1").apply(event(MatchEventType.KICKOFF, 0, null));

        assertThat(state.status()).isEqualTo(MatchStatus.LIVE);
    }

    @Test
    @DisplayName("GOAL increments the score of the scoring team")
    void goalUpdatesScore() {
        MatchState state = MatchState.initial("m1")
                .apply(event(MatchEventType.KICKOFF, 0, null))
                .apply(event(MatchEventType.GOAL, 10, "Tigres"))
                .apply(event(MatchEventType.GOAL, 55, "Rayados"))
                .apply(event(MatchEventType.GOAL, 80, "Tigres"));

        assertThat(state.score())
                .containsEntry("Tigres", 2)
                .containsEntry("Rayados", 1);
    }

    @Test
    @DisplayName("HALF_TIME and FULL_TIME update the status")
    void halfTimeAndFullTime() {
        MatchState halfTime = MatchState.initial("m1")
                .apply(event(MatchEventType.KICKOFF, 0, null))
                .apply(event(MatchEventType.HALF_TIME, 45, null));
        assertThat(halfTime.status()).isEqualTo(MatchStatus.HALF_TIME);

        MatchState finished = halfTime
                .apply(event(MatchEventType.KICKOFF, 46, null))
                .apply(event(MatchEventType.FULL_TIME, 90, null));
        assertThat(finished.status()).isEqualTo(MatchStatus.FINISHED);
    }

    @Test
    @DisplayName("recent events are newest first and capped")
    void recentEventsAreCapped() {
        MatchState state = MatchState.initial("m1");
        for (int minute = 1; minute <= MatchState.MAX_RECENT_EVENTS + 5; minute++) {
            state = state.apply(event(MatchEventType.YELLOW_CARD, minute, "Tigres"));
        }

        assertThat(state.recentEvents()).hasSize(MatchState.MAX_RECENT_EVENTS);
        assertThat(state.recentEvents().getFirst().minute()).isEqualTo(MatchState.MAX_RECENT_EVENTS + 5);
    }

    @Test
    @DisplayName("apply never mutates the original state")
    void applyIsImmutable() {
        MatchState original = MatchState.initial("m1");

        original.apply(event(MatchEventType.GOAL, 10, "Tigres"));

        assertThat(original.score()).isEmpty();
        assertThat(original.recentEvents()).isEmpty();
    }
}
