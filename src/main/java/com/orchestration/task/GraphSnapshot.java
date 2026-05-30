package com.orchestration.task;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * A serialisable, self-contained snapshot of a {@link TaskGraph} (tasks, their states, and their
 * dependency edges). This is the structural payload of a {@code MemoryStore.Checkpoint}, enabling
 * a project to be rebuilt and resumed exactly where it stopped.
 *
 * <p>Kept as plain strings/enusms-as-names rather than the domain value types so the on-disk format
 * is stable and decoupled from internal class shapes. Agent partial-outputs and task metadata are
 * not captured yet — they are added when later steps (memory + bug-loop) need them.
 */
public record GraphSnapshot(List<TaskNode> nodes) implements Serializable {

    public GraphSnapshot {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /** Serialise this snapshot to the bytes stored in a {@code MemoryStore.Checkpoint}. */
    public byte[] toBytes() {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(this);
            oos.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reconstruct a snapshot from checkpoint bytes. */
    public static GraphSnapshot fromBytes(byte[] data) {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (GraphSnapshot) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Failed to read GraphSnapshot", e);
        }
    }

    public record TaskNode(
            String id,
            String title,
            String description,
            String assignedRole,        // AgentRole name, or null
            String state,               // WorkflowState name
            List<String> dependsOn,     // prerequisite task ids
            long createdAtEpochMilli
    ) implements Serializable {
        public TaskNode {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }
    }
}
