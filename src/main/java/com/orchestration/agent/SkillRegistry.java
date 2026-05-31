package com.orchestration.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Loads pluggable <b>skills</b> — reusable capability/guidance modules that can be attached to any
 * agent. A skill is a Markdown file in the skills directory (e.g. {@code config/skills/rest-api.md});
 * an agent opts in by listing the skill name in its {@code agents.yml} entry. Resolved skill text is
 * appended to that agent's system prompt, so it works identically for LLM-backed agents and for the
 * MCP brain (Claude Code) — both receive the same enriched persona.
 *
 * <p>The registry is tolerant: unknown or unreadable skills are skipped (not fatal), so a typo in
 * config degrades gracefully rather than breaking a run.
 */
public class SkillRegistry {

    private final Path dir;

    public SkillRegistry(Path dir) {
        this.dir = dir;
    }

    /** A registry with no skills (used by tests and when the skills directory is absent). */
    public static SkillRegistry empty() {
        return new SkillRegistry(Path.of("__no_skills__"));
    }

    /** Return a single skill's content by name ({@code .md} extension optional). */
    public Optional<String> get(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Path path = dir.resolve(name.endsWith(".md") ? name : name + ".md");
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(path).strip());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Concatenate the named skills into one prompt section, each under a {@code ## Skill: <name>}
     * heading. Missing skills are silently skipped. Returns an empty string when nothing resolves.
     */
    public String resolve(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            get(name).ifPresent(content ->
                    sb.append("## Skill: ").append(name).append('\n').append(content).append("\n\n"));
        }
        String resolved = sb.toString().strip();
        return resolved.isEmpty() ? "" : "# Attached skills\n\n" + resolved;
    }

    /** List the skill names available in the directory (filenames without {@code .md}). */
    public List<String> available() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = new ArrayList<>();
            files.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .forEach(p -> {
                        String f = p.getFileName().toString();
                        names.add(f.substring(0, f.length() - ".md".length()));
                    });
            names.sort(String::compareTo);
            return List.copyOf(names);
        } catch (IOException e) {
            return List.of();
        }
    }
}
