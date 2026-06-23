package com.orchestration.phase;

import com.orchestration.phase.PhasePlan.Phase;
import com.orchestration.phase.PhasePlan.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhasePlanTest {

    private PhasePlan sample() {
        return new PhasePlan("Build a notes app", List.of(
                new Phase(1, "Foundation", "schema + core domain", Status.DONE),
                new Phase(2, "Auth", "login & sessions", Status.IN_PROGRESS),
                new Phase(3, "Sharing", "invite links", Status.PENDING)));
    }

    @Test
    void renderThenParseRoundTrips() {
        PhasePlan original = sample();
        PhasePlan parsed = PhasePlan.parse(original.render());

        assertEquals(original.goal(), parsed.goal());
        assertEquals(original.phases(), parsed.phases());
    }

    @Test
    void parseReadsTheStatusMarkers() {
        PhasePlan plan = PhasePlan.parse("""
                # Phase Plan

                Goal: do the thing

                - [x] 1. Done phase — finished
                - [>] 2. Active phase — in flight
                - [ ] 3. Future phase — later
                """);

        assertEquals(Status.DONE, plan.phases().get(0).status());
        assertEquals(Status.IN_PROGRESS, plan.phases().get(1).status());
        assertEquals(Status.PENDING, plan.phases().get(2).status());
        assertEquals("Active phase", plan.phases().get(1).title());
        assertEquals("in flight", plan.phases().get(1).description());
    }

    @Test
    void nextPendingSkipsDoneAndPrefersInProgress() {
        // The first not-done phase is the one a continuation run should pick up.
        assertEquals(2, sample().nextPending().orElseThrow().number());
    }

    @Test
    void withStatusAdvancesOnlyTheTargetPhase() {
        PhasePlan advanced = sample().withStatus(2, Status.DONE);

        assertEquals(Status.DONE, advanced.phases().get(1).status());
        assertEquals(3, advanced.nextPending().orElseThrow().number());
        assertEquals(2, advanced.doneCount());
    }

    @Test
    void allDoneOnlyWhenEveryPhaseIsDone() {
        assertFalse(sample().allDone());
        PhasePlan finished = sample().withStatus(2, Status.DONE).withStatus(3, Status.DONE);
        assertTrue(finished.allDone());
    }

    @Test
    void headlineFallsBackToTitleWhenNoDescription() {
        assertEquals("Solo", new Phase(1, "Solo", "", Status.PENDING).headline());
        assertEquals("A — b", new Phase(1, "A", "b", Status.PENDING).headline());
    }

    @Test
    void renderParsePreservesTheAdvanceMode() {
        PhasePlan auto = new PhasePlan("g", true, List.of(new Phase(1, "A", "", Status.PENDING)));
        assertTrue(PhasePlan.parse(auto.render()).autonomous(), "autonomous mode round-trips");

        PhasePlan paused = new PhasePlan("g", false, List.of(new Phase(1, "A", "", Status.PENDING)));
        assertFalse(PhasePlan.parse(paused.render()).autonomous(), "paused mode round-trips");

        // A legacy roadmap with no Mode line defaults to paused (never auto-advance without a yes).
        assertFalse(PhasePlan.parse("# Phase Plan\n\n- [ ] 1. A").autonomous());
    }

    @Test
    void withStatusAndWithAutonomousPreserveTheOtherField() {
        PhasePlan plan = new PhasePlan("g", true, List.of(
                new Phase(1, "A", "", Status.IN_PROGRESS), new Phase(2, "B", "", Status.PENDING)));
        assertTrue(plan.withStatus(1, Status.DONE).autonomous(), "withStatus keeps the mode");
        assertEquals("g", plan.withAutonomous(false).goal(), "withAutonomous keeps the goal");
        assertFalse(plan.withAutonomous(false).autonomous());
    }

    @Test
    void parseToleratesEmptyOrJunk() {
        assertTrue(PhasePlan.parse("").phases().isEmpty());
        assertTrue(PhasePlan.parse("just some prose\nno checklist here").phases().isEmpty());
    }
}
