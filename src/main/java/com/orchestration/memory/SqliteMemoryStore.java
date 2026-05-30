package com.orchestration.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite-backed {@link MemoryStore} (single file, zero setup), accessed over JDBC.
 *
 * <p>Holds two things behind one store, since they share a project scope and backing file:
 * structured memory entries (decisions, scenario→action mappings, summaries, …) and full
 * orchestration checkpoints for resumption. Structured fields are persisted as JSON via Jackson.
 *
 * <p>A single JDBC connection is held and all access is serialised on an intrinsic lock — simple
 * and correct for SQLite, which is not built for heavy concurrent writers.
 */
public class SqliteMemoryStore implements MemoryStore, AutoCloseable {

    private final Connection connection;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object lock = new Object();

    public SqliteMemoryStore(String jdbcUrl) {
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            initSchema();
        } catch (SQLException e) {
            throw new MemoryStoreException("Failed to open SQLite store at " + jdbcUrl, e);
        }
    }

    /** Open (creating if needed) a file-backed store. */
    public static SqliteMemoryStore forFile(Path path) {
        return new SqliteMemoryStore("jdbc:sqlite:" + path.toAbsolutePath());
    }

    /** An ephemeral in-memory store (handy for tests). */
    public static SqliteMemoryStore inMemory() {
        return new SqliteMemoryStore("jdbc:sqlite::memory:");
    }

    private void initSchema() throws SQLException {
        try (PreparedStatement entries = connection.prepareStatement("""
                CREATE TABLE IF NOT EXISTS memory_entries (
                    key        TEXT PRIMARY KEY,
                    kind       TEXT NOT NULL,
                    project_id TEXT,
                    content    TEXT,
                    attributes TEXT,
                    created_at INTEGER NOT NULL
                )""");
             PreparedStatement checkpoints = connection.prepareStatement("""
                CREATE TABLE IF NOT EXISTS checkpoints (
                    id         TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    sequence   INTEGER NOT NULL,
                    state      BLOB NOT NULL,
                    metadata   TEXT,
                    created_at INTEGER NOT NULL
                )""")) {
            entries.execute();
            checkpoints.execute();
        }
    }

    // ------------------------------------------------------------------------
    // Structured memory
    // ------------------------------------------------------------------------

    @Override
    public void put(MemoryEntry entry) {
        Objects.requireNonNull(entry, "entry");
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT OR REPLACE INTO memory_entries (key, kind, project_id, content, attributes, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, entry.key());
                ps.setString(2, entry.kind().name());
                ps.setString(3, entry.projectId());
                ps.setString(4, entry.content());
                ps.setString(5, toJson(entry.attributes()));
                ps.setLong(6, entry.createdAt().toEpochMilli());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new MemoryStoreException("Failed to put memory entry " + entry.key(), e);
            }
        }
    }

    @Override
    public Optional<MemoryEntry> get(String key) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM memory_entries WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(readEntry(rs)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw new MemoryStoreException("Failed to get memory entry " + key, e);
            }
        }
    }

    @Override
    public List<MemoryEntry> query(Query query) {
        Objects.requireNonNull(query, "query");
        synchronized (lock) {
            StringBuilder sql = new StringBuilder("SELECT * FROM memory_entries WHERE 1=1");
            List<Object> params = new ArrayList<>();
            query.projectId().ifPresent(p -> {
                sql.append(" AND project_id = ?");
                params.add(p);
            });
            query.kind().ifPresent(k -> {
                sql.append(" AND kind = ?");
                params.add(k.name());
            });
            sql.append(" ORDER BY created_at DESC");

            try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                List<MemoryEntry> results = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        MemoryEntry entry = readEntry(rs);
                        if (matches(entry, query.match())) {
                            results.add(entry);
                        }
                    }
                }
                if (query.limit() > 0 && results.size() > query.limit()) {
                    return List.copyOf(results.subList(0, query.limit()));
                }
                return List.copyOf(results);
            } catch (SQLException e) {
                throw new MemoryStoreException("Failed to query memory entries", e);
            }
        }
    }

    @Override
    public void delete(String key) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM memory_entries WHERE key = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new MemoryStoreException("Failed to delete memory entry " + key, e);
            }
        }
    }

    // ------------------------------------------------------------------------
    // Checkpoints
    // ------------------------------------------------------------------------

    @Override
    public void saveCheckpoint(Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT OR REPLACE INTO checkpoints (id, project_id, sequence, state, metadata, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, checkpoint.id());
                ps.setString(2, checkpoint.projectId());
                ps.setLong(3, checkpoint.sequence());
                ps.setBytes(4, checkpoint.state());
                ps.setString(5, toJson(checkpoint.metadata()));
                ps.setLong(6, checkpoint.createdAt().toEpochMilli());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new MemoryStoreException("Failed to save checkpoint " + checkpoint.id(), e);
            }
        }
    }

    @Override
    public Optional<Checkpoint> latestCheckpoint(String projectId) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM checkpoints WHERE project_id = ? ORDER BY sequence DESC LIMIT 1")) {
                ps.setString(1, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(readCheckpoint(rs)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw new MemoryStoreException("Failed to load latest checkpoint for " + projectId, e);
            }
        }
    }

    @Override
    public List<Checkpoint> checkpoints(String projectId) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM checkpoints WHERE project_id = ? ORDER BY sequence ASC")) {
                ps.setString(1, projectId);
                List<Checkpoint> results = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        results.add(readCheckpoint(rs));
                    }
                }
                return List.copyOf(results);
            } catch (SQLException e) {
                throw new MemoryStoreException("Failed to list checkpoints for " + projectId, e);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new MemoryStoreException("Failed to close SQLite store", e);
            }
        }
    }

    // ------------------------------------------------------------------------
    // Row mapping / JSON helpers
    // ------------------------------------------------------------------------

    private MemoryEntry readEntry(ResultSet rs) throws SQLException {
        return new MemoryEntry(
                rs.getString("key"),
                Kind.valueOf(rs.getString("kind")),
                rs.getString("project_id"),
                rs.getString("content"),
                fromJson(rs.getString("attributes")),
                Instant.ofEpochMilli(rs.getLong("created_at")));
    }

    private Checkpoint readCheckpoint(ResultSet rs) throws SQLException {
        return new Checkpoint(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getLong("sequence"),
                rs.getBytes("state"),
                fromJson(rs.getString("metadata")),
                Instant.ofEpochMilli(rs.getLong("created_at")));
    }

    private boolean matches(MemoryEntry entry, Map<String, Object> match) {
        for (Map.Entry<String, Object> e : match.entrySet()) {
            if (!Objects.equals(entry.attributes().get(e.getKey()), e.getValue())) {
                return false;
            }
        }
        return true;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return mapper.writeValueAsString(map == null ? Map.of() : map);
        } catch (Exception e) {
            throw new MemoryStoreException("Failed to serialise attributes", e);
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new MemoryStoreException("Failed to deserialise attributes", e);
        }
    }
}
