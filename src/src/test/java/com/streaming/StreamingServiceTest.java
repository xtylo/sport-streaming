package com.streaming;

import com.streaming.domain.MatchEvent;
import com.streaming.domain.MatchEventType;
import com.streaming.domain.MatchState;
import com.streaming.domain.MatchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Plain unit tests: the service is created with `new`, no Spring context. */
class StreamingServiceTest {

    private StreamingService service;

    @BeforeEach
    void setUp() {
        service = new StreamingService();
    }

    private static MatchEvent goal(String matchId, String team) {
        return new MatchEvent(null, matchId, MatchEventType.GOAL, 10, team, null, null);
    }

    @Test
    @DisplayName("receiving an event updates the snapshot")
    void eventUpdatesSnapshot() {
        MatchState state = service.onEventReceived(goal("m1", "Tigres"));

        assertThat(state.score()).containsEntry("Tigres", 1);
        assertThat(service.snapshot("m1")).contains(state);
        assertThat(service.snapshot()).containsExactly(state);
    }

    @Test
    @DisplayName("events for different matches are kept separately")
    void matchesAreIndependent() {
        service.onEventReceived(goal("m1", "Tigres"));
        service.onEventReceived(goal("m2", "America"));

        assertThat(service.snapshot()).hasSize(2);
        assertThat(service.snapshot("m1").orElseThrow().score()).containsOnlyKeys("Tigres");
        assertThat(service.snapshot("m2").orElseThrow().status()).isEqualTo(MatchStatus.LIVE);
    }

    @Test
    @DisplayName("unknown match returns an empty snapshot")
    void unknownMatch() {
        assertThat(service.snapshot("nope")).isEmpty();
    }

    @Test
    @DisplayName("subscribing registers an SSE client")
    void subscribeRegistersClient() {
        assertThat(service.activeSubscribers()).isZero();

        service.subscribe("m1");
        service.subscribe("m1");
        service.subscribe("m2");

        assertThat(service.activeSubscribers()).isEqualTo(3);
    }

    @Test
    @DisplayName("broadcasting to subscribers does not throw (adapted from the README example)")
    void broadcastDoesNotThrow() {
        service.subscribe("m1");

        assertThatCode(() -> service.onEventReceived(goal("m1", "Tigres"))).doesNotThrowAnyException();
    }
}
