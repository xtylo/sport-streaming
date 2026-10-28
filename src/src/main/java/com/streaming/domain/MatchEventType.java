package com.streaming.domain;

/** Kinds of events that can happen during a live match. */
public enum MatchEventType {
    KICKOFF(false),
    GOAL(true),
    YELLOW_CARD(true),
    RED_CARD(true),
    SUBSTITUTION(true),
    HALF_TIME(false),
    FULL_TIME(false);

    private final boolean requiresTeam;

    MatchEventType(boolean requiresTeam) {
        this.requiresTeam = requiresTeam;
    }

    /** Whether events of this type must say which team they belong to. */
    public boolean requiresTeam() {
        return requiresTeam;
    }
}
