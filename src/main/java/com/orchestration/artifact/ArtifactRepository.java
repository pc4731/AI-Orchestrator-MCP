package com.orchestration.artifact;

import java.util.List;
import java.util.Optional;

/**
 * Persistent, versioned storage of all agent artifacts (architecture docs, code, designs, test
 * reports), backed by a real Git repository.
 *
 * <p>Every agent action that produces output is committed, which gives free versioning, diffs,
 * rollback and an audit trail, and lets QA check out and run real code. Artifacts are associated
 * with the originating task id.
 */
public interface ArtifactRepository {

    /** Apply a set of file changes and commit them as one atomic revision. */
    CommitId write(WriteRequest request);

    /** Read the current content of a file at the given repository-relative path. */
    Optional<String> read(String path);

    /** List repository-relative paths under a prefix. */
    List<String> list(String pathPrefix);

    record WriteRequest(
            String taskId,
            String authorAgent,   // attribution for the commit (agent role/id)
            String message,       // commit message
            List<FileChange> changes
    ) {
        public WriteRequest {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }
    }

    record FileChange(String path, String content) {}

    record CommitId(String sha) {}
}
