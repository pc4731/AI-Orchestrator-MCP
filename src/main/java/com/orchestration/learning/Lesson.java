package com.orchestration.learning;

/**
 * A proposed, evidence-backed improvement mined from a finished run. It is a PROPOSAL, never applied
 * automatically: only when the user approves it does it become a learned skill that changes how the
 * named role behaves on future projects. The {@code evidence} is the receipt (the real failure /
 * finding / question that justifies it); {@code recurrence} grows each time the same pattern recurs.
 */
public record Lesson(
        String id,
        String projectId,
        String role,        // AgentRole name this lesson applies to
        String category,    // BUILD_FIX | REVIEW_FIX | CLARIFICATION
        String lesson,      // the durable, reusable guidance (becomes the learned skill)
        String evidence,    // excerpt proving it: failure output, review finding, or user question
        int recurrence,     // how many times this pattern has been observed across runs
        String status,      // PENDING | APPROVED | REJECTED
        String createdAt) {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    public static final String BUILD_FIX = "BUILD_FIX";
    public static final String REVIEW_FIX = "REVIEW_FIX";
    public static final String CLARIFICATION = "CLARIFICATION";
    public static final String IMPORTED = "IMPORTED"; // staged from an imported lessons pack

    /** Identity for dedup across runs: role + category + a normalized key from the evidence. Two
     *  proposals with the same signature are the same lesson recurring, not a new one. */
    public String signature() {
        String key = (evidence == null ? "" : evidence).toLowerCase().replaceAll("\\s+", " ").strip();
        if (key.length() > 80) {
            key = key.substring(0, 80);
        }
        return role + "|" + category + "|" + key;
    }

    public Lesson withRecurrence(int newRecurrence) {
        return new Lesson(id, projectId, role, category, lesson, evidence, newRecurrence, status, createdAt);
    }

    public Lesson withStatus(String newStatus) {
        return new Lesson(id, projectId, role, category, lesson, evidence, recurrence, newStatus, createdAt);
    }

    public Lesson withLesson(String newLesson) {
        return new Lesson(id, projectId, role, category, newLesson, evidence, recurrence, status, createdAt);
    }
}
