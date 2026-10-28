package com.streaming.domain;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Validates the Bean Validation rules of MatchEvent without starting Spring. */
class MatchEventValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static Set<String> invalidFields(Set<ConstraintViolation<MatchEvent>> violations) {
        return violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    @DisplayName("a complete GOAL event is valid")
    void validGoal() {
        var event = new MatchEvent(null, "m1", MatchEventType.GOAL, 23, "Tigres", "Header", null);

        assertThat(validator.validate(event)).isEmpty();
    }

    @Test
    @DisplayName("KICKOFF does not require a team")
    void kickoffWithoutTeamIsValid() {
        var event = new MatchEvent(null, "m1", MatchEventType.KICKOFF, 0, null, null, null);

        assertThat(validator.validate(event)).isEmpty();
    }

    @Test
    @DisplayName("GOAL without a team is rejected")
    void goalWithoutTeam() {
        var event = new MatchEvent(null, "m1", MatchEventType.GOAL, 23, null, null, null);

        assertThat(invalidFields(validator.validate(event))).containsExactly("teamPresentWhenRequired");
    }

    @Test
    @DisplayName("blank matchId, missing type and out-of-range minute are rejected")
    void invalidFields() {
        var event = new MatchEvent(null, " ", null, 200, null, null, null);

        assertThat(invalidFields(validator.validate(event)))
                .containsExactlyInAnyOrder("matchId", "type", "minute");
    }

    @Test
    @DisplayName("eventId and timestamp get server-side defaults")
    void defaults() {
        var event = new MatchEvent(null, "m1", MatchEventType.KICKOFF, 0, null, null, null);

        assertThat(event.eventId()).isNotNull();
        assertThat(event.timestamp()).isNotNull();
    }
}
