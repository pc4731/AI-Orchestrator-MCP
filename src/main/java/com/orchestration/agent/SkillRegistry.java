package com.orchestration.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
     * Resolve a role's explicit skills PLUS any approved, learned skill committed for that role at
     * {@code <dir>/learned/<ROLE>.md}. The learned file is how the approval loop makes an agent better:
     * once promoted, it is auto-attached to every future run of that role with no agents.yml edit.
     */
    public String resolveForRole(AgentRole role, List<String> explicitNames) {
        List<String> all = new ArrayList<>(explicitNames == null ? List.of() : explicitNames);
        if (role != null && get(learnedName(role)).isPresent()) {
            all.add(learnedName(role));
        }
        all.addAll(attachedNames(role)); // approved, researched domain skills attached to this role
        return resolve(all);
    }

    /**
     * Persist an approved, researched skill: write its content as a named skill file and attach it to
     * each given role so {@link #resolveForRole} folds it into that role's prompt — from the next agent
     * created onward, including later in the SAME run. The file persists, so a future run that needs
     * the same domain reuses it without re-researching. Returns the slug the skill was saved under.
     */
    public String addSynthesizedSkill(String name, String content, java.util.Collection<AgentRole> roles) {
        String slug = slugify(name);
        if (slug.isBlank() || content == null || content.isBlank()) {
            return "";
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(slug + ".md"), content.strip() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new SkillWriteException("Failed to write synthesized skill " + slug, e);
        }
        if (roles != null) {
            for (AgentRole role : roles) {
                attach(role, slug);
            }
        }
        return slug;
    }

    /** Record (idempotently) that {@code skillName} is attached to {@code role} at
     *  {@code <dir>/attachments/<ROLE>.txt} — one skill name per line. */
    private void attach(AgentRole role, String skillName) {
        if (role == null || skillName == null || skillName.isBlank()) {
            return;
        }
        Path path = dir.resolve("attachments").resolve(role.name() + ".txt");
        try {
            List<String> names = attachedNames(role);
            if (names.contains(skillName)) {
                return; // already attached
            }
            List<String> updated = new ArrayList<>(names);
            updated.add(skillName);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.write(path, updated, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new SkillWriteException("Failed to attach skill " + skillName + " to " + role, e);
        }
    }

    /** Skill names attached to a role via {@link #addSynthesizedSkill}. */
    public List<String> attachedNames(AgentRole role) {
        if (role == null) {
            return List.of();
        }
        Path path = dir.resolve("attachments").resolve(role.name() + ".txt");
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            List<String> names = new ArrayList<>();
            for (String line : Files.readAllLines(path)) {
                String n = line.strip();
                if (!n.isBlank() && !names.contains(n)) {
                    names.add(n);
                }
            }
            return names;
        } catch (IOException e) {
            return List.of();
        }
    }

    /** A filesystem-safe skill slug derived from a free-text name. */
    static String slugify(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }

    /** Append an approved lesson to the role's learned-skill file (creating it). The git-tracked file
     *  IS the audit trail and the portability mechanism — a reviewed diff that travels with the repo. */
    public void promoteLearned(AgentRole role, String lessonMarkdown) {
        Objects.requireNonNull(role, "role");
        if (lessonMarkdown == null || lessonMarkdown.isBlank()) {
            return;
        }
        Path path = dir.resolve("learned").resolve(role.name() + ".md");
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            String entry = (Files.exists(path) ? "\n\n" : "") + lessonMarkdown.strip() + "\n";
            Files.writeString(path, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new SkillWriteException("Failed to write learned skill for " + role, e);
        }
    }

    private static String learnedName(AgentRole role) {
        return "learned/" + role.name();
    }

    /** Whether an approved, learned skill exists for this role. */
    public boolean hasLearned(AgentRole role) {
        return role != null && get(learnedName(role)).isPresent();
    }

    /** Size (chars) of a role's learned skill — used by the decay check to flag bloated roles. */
    public int learnedSize(AgentRole role) {
        return get(learnedName(role)).map(String::length).orElse(0);
    }

    /** Remove a role's learned skill entirely (it reverts to base behavior). Used by gated pruning. */
    public void clearLearned(AgentRole role) {
        Objects.requireNonNull(role, "role");
        try {
            Files.deleteIfExists(dir.resolve("learned").resolve(role.name() + ".md"));
        } catch (IOException e) {
            throw new SkillWriteException("Failed to clear learned skill for " + role, e);
        }
    }

    /** All learned skills (role → content), for exporting a portable lessons pack. */
    public Map<AgentRole, String> learnedSkills() {
        Path learnedDir = dir.resolve("learned");
        Map<AgentRole, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(learnedDir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(learnedDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        String roleName = fileName.substring(0, fileName.length() - ".md".length());
                        try {
                            out.put(AgentRole.valueOf(roleName), Files.readString(p).strip());
                        } catch (IllegalArgumentException | IOException ignored) {
                            // skip files that don't map to a known role or can't be read
                        }
                    });
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    /** Thrown when an approved lesson can't be persisted to its learned-skill file. */
    public static class SkillWriteException extends RuntimeException {
        public SkillWriteException(String message, Throwable cause) {
            super(message, cause);
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
