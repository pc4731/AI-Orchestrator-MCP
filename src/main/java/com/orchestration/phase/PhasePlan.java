package com.orchestration.phase;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An ordered roadmap that turns one big project into a sequence of shippable phases, each with a
 * tracked status. It renders to — and parses back from — a single Markdown checklist committed in
 * the project repo (see {@link PhasePlanStore}), so a brand-new session can read exactly what is
 * already DONE and what is still PENDING instead of guessing.
 *
 * <p>Plaintext checklist on purpose: human-readable, diffable in Git, and machine-parseable. The
 * checkbox marker carries the status — {@code [ ]} pending, {@code [>]} in progress, {@code [x]}
 * done — so the file doubles as a progress report a person can skim.
 *
 * <p>{@code autonomous} records the user's choice (made at the start of phase 1) for how to advance:
 * {@code true} rolls straight through every phase; {@code false} pauses for the user's approval
 * before each new phase.
 */
public record PhasePlan(String goal, boolean autonomous, List<Phase> phases) {

    /** A phase's lifecycle state. The marker in the rendered checklist encodes this. */
    public enum Status {
        PENDING(' '),
        IN_PROGRESS('>'),
        DONE('x');

        private final char marker;

        Status(char marker) {
            this.marker = marker;
        }

        char marker() {
            return marker;
        }

        static Status fromMarker(char c) {
            return switch (c) {
                case 'x', 'X' -> DONE;
                case '>' -> IN_PROGRESS;
                default -> PENDING;
            };
        }
    }

    /** One unit of work in the roadmap. {@code number} is 1-based and matches the rendered list. */
    public record Phase(int number, String title, String description, Status status) {
        public Phase {
            title = title == null ? "" : title.strip();
            description = description == null ? "" : description.strip();
            status = status == null ? Status.PENDING : status;
        }

        public Phase withStatus(Status newStatus) {
            return new Phase(number, title, description, newStatus);
        }

        /** "Title — description" (or just the title when there is no description). */
        public String headline() {
            return description.isBlank() ? title : title + TITLE_DESC_SEP + description;
        }
    }

    private static final String TITLE_DESC_SEP = " — "; // " — "
    private static final Pattern PHASE_LINE =
            Pattern.compile("^- \\[(.)\\]\\s*(\\d+)\\.\\s*(.*)$");
    private static final Pattern GOAL_LINE = Pattern.compile("(?i)^goal:\\s*(.*)$");
    private static final Pattern MODE_LINE = Pattern.compile("(?i)^mode:\\s*(.*)$");

    public PhasePlan {
        Objects.requireNonNull(phases, "phases");
        goal = goal == null ? "" : goal.strip();
        phases = List.copyOf(phases);
    }

    /** Convenience: a plan in the default (paused) mode — the user's choice is set later via
     *  {@link #withAutonomous}. Keeps existing two-argument call sites working. */
    public PhasePlan(String goal, List<Phase> phases) {
        this(goal, false, phases);
    }

    /** Build a fresh plan from ordered (title, description) pairs: phase 1 IN_PROGRESS, rest PENDING. */
    public static PhasePlan of(String goal, List<Phase> phases) {
        return new PhasePlan(goal, phases);
    }

    /** The first phase not yet DONE — the one a continuation run should build next. */
    public Optional<Phase> nextPending() {
        return phases.stream().filter(p -> p.status() != Status.DONE).findFirst();
    }

    /** The phase currently marked IN_PROGRESS, if any. */
    public Optional<Phase> inProgress() {
        return phases.stream().filter(p -> p.status() == Status.IN_PROGRESS).findFirst();
    }

    public boolean allDone() {
        return !phases.isEmpty() && phases.stream().allMatch(p -> p.status() == Status.DONE);
    }

    public long doneCount() {
        return phases.stream().filter(p -> p.status() == Status.DONE).count();
    }

    /** A new plan with the given phase number set to {@code status} (mode + other phases unchanged). */
    public PhasePlan withStatus(int number, Status status) {
        List<Phase> updated = new ArrayList<>(phases.size());
        for (Phase p : phases) {
            updated.add(p.number() == number ? p.withStatus(status) : p);
        }
        return new PhasePlan(goal, autonomous, updated);
    }

    /** A copy with the advance mode set (true = roll through all phases; false = pause before each). */
    public PhasePlan withAutonomous(boolean value) {
        return new PhasePlan(goal, value, phases);
    }

    /** A one-line progress summary, e.g. "Phases: 1/3 done (next: Auth)". */
    public String summary() {
        if (phases.isEmpty()) {
            return "No phases planned.";
        }
        String next = nextPending().map(Phase::title).orElse("all phases complete");
        return "Phases: " + doneCount() + "/" + phases.size() + " done (next: " + next + ")";
    }

    /** Render to the committed Markdown checklist. {@link #parse} is its exact inverse. */
    public String render() {
        StringBuilder sb = new StringBuilder("# Phase Plan\n\n");
        if (!goal.isBlank()) {
            sb.append("Goal: ").append(goal).append("\n\n");
        }
        sb.append("Mode: ").append(autonomous ? "autonomous" : "paused").append("\n\n");
        for (Phase p : phases) {
            sb.append("- [").append(p.status().marker()).append("] ")
                    .append(p.number()).append(". ").append(p.headline()).append('\n');
        }
        return sb.toString();
    }

    /** Parse the committed checklist back into a plan. Unparseable lines are ignored. A missing
     *  Mode line defaults to paused (the safe choice — never auto-advance without an explicit yes). */
    public static PhasePlan parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return new PhasePlan("", List.of());
        }
        String goal = "";
        boolean autonomous = false;
        List<Phase> phases = new ArrayList<>();
        for (String raw : markdown.split("\n")) {
            String line = raw.strip();
            Matcher goalMatch = GOAL_LINE.matcher(line);
            if (goal.isBlank() && goalMatch.matches()) {
                goal = goalMatch.group(1).strip();
                continue;
            }
            Matcher modeMatch = MODE_LINE.matcher(line);
            if (modeMatch.matches()) {
                autonomous = modeMatch.group(1).strip().toLowerCase().startsWith("auto");
                continue;
            }
            Matcher phaseMatch = PHASE_LINE.matcher(line);
            if (phaseMatch.matches()) {
                Status status = Status.fromMarker(phaseMatch.group(1).charAt(0));
                int number = Integer.parseInt(phaseMatch.group(2));
                String rest = phaseMatch.group(3).strip();
                String title = rest;
                String description = "";
                int sep = rest.indexOf(TITLE_DESC_SEP);
                if (sep >= 0) {
                    title = rest.substring(0, sep).strip();
                    description = rest.substring(sep + TITLE_DESC_SEP.length()).strip();
                }
                phases.add(new Phase(number, title, description, status));
            }
        }
        return new PhasePlan(goal, autonomous, phases);
    }

    /** Grounding text handed to the team: the roadmap with statuses so every agent sees the big
     *  picture and which slice is in flight. Kept compact. */
    public String groundingText() {
        StringBuilder sb = new StringBuilder("Phase roadmap for this project (build ONLY the "
                + "in-progress phase; the rest are tracked and built in later runs):\n");
        for (Phase p : phases) {
            sb.append("  ").append(switch (p.status()) {
                case DONE -> "[done]";
                case IN_PROGRESS -> "[NOW]";
                case PENDING -> "[later]";
            }).append(' ').append(p.number()).append(". ").append(p.headline()).append('\n');
        }
        return sb.toString().strip();
    }

    public static PhasePlan empty() {
        return new PhasePlan("", List.of());
    }
}
