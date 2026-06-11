package com.orchestration.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Detects a project's toolchain(s) from the files actually on disk and derives the real test
 * command — so the orchestrator never hands a Python or Node project a hardcoded
 * {@code ./gradlew test} definition-of-done (the single most repeated friction across run
 * retrospectives).
 *
 * <p>Detection looks at the workspace root and its first-level subdirectories (the common
 * {@code backend/}+{@code frontend/} split), so a mixed-stack project yields a composite command
 * that runs every stack's tests. Detection is cheap (a handful of file existence checks) and is
 * re-run on use, because a fresh workspace gains its marker files as the build progresses.
 */
public final class StackDetector {

    /** One detected toolchain: where it lives (workspace-relative, "" = root) and how to test it. */
    public record Detection(String subdir, List<String> command, String stack) {}

    /** Subdirectories that can never contain the project's own stack markers. */
    private static final Set<String> IGNORED_DIRS = Set.of(
            "node_modules", ".venv", "venv", "env", "__pycache__", ".git", ".gradle", ".idea",
            "build", "dist", "out", "target", "coverage", "vendor", ".next", ".vite", ".cache");

    private StackDetector() {}

    /** All toolchains found at the root and one level down (root first, then subdirs sorted). */
    public static List<Detection> detect(Path projectDir) {
        List<Detection> detections = new ArrayList<>();
        if (projectDir == null || !Files.isDirectory(projectDir)) {
            return detections;
        }
        detectIn(projectDir, "").ifPresent(detections::add);
        try (Stream<Path> children = Files.list(projectDir)) {
            children.filter(Files::isDirectory)
                    .filter(d -> !IGNORED_DIRS.contains(d.getFileName().toString())
                            && !d.getFileName().toString().startsWith("."))
                    .sorted()
                    .forEach(d -> detectIn(d, d.getFileName().toString()).ifPresent(detections::add));
        } catch (IOException e) {
            // partial detection is fine; never let detection break a task
        }
        return detections;
    }

    /**
     * The single command that tests everything detected, or empty when nothing was recognized
     * (the caller falls back to its configured default). One root toolchain runs as-is; multiple
     * toolchains compose into one shell command so the whole project gates green together.
     */
    public static Optional<List<String>> testCommand(Path projectDir) {
        List<Detection> detections = detect(projectDir);
        if (detections.isEmpty()) {
            return Optional.empty();
        }
        if (detections.size() == 1 && detections.get(0).subdir().isEmpty()) {
            return Optional.of(detections.get(0).command());
        }
        StringBuilder sh = new StringBuilder();
        for (Detection d : detections) {
            if (sh.length() > 0) {
                sh.append(" && ");
            }
            sh.append("( ");
            if (!d.subdir().isEmpty()) {
                sh.append("cd ").append(d.subdir()).append(" && ");
            }
            sh.append(String.join(" ", d.command())).append(" )");
        }
        return Optional.of(List.of("bash", "-lc", sh.toString()));
    }

    /** Human-readable description of what was detected, for the definition-of-done text. */
    public static String describe(List<Detection> detections) {
        StringBuilder sb = new StringBuilder();
        for (Detection d : detections) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(d.stack()).append(d.subdir().isEmpty() ? "" : " in " + d.subdir() + "/")
                    .append(": `").append(String.join(" ", d.command())).append('`');
        }
        return sb.toString();
    }

    private static Optional<Detection> detectIn(Path dir, String subdir) {
        if (Files.exists(dir.resolve("gradlew"))) {
            return Optional.of(new Detection(subdir, List.of("./gradlew", "test"), "gradle"));
        }
        if (Files.exists(dir.resolve("build.gradle")) || Files.exists(dir.resolve("build.gradle.kts"))) {
            return Optional.of(new Detection(subdir, List.of("gradle", "test"), "gradle"));
        }
        if (Files.exists(dir.resolve("pom.xml"))) {
            return Optional.of(new Detection(subdir, List.of("mvn", "-q", "test"), "maven"));
        }
        if (Files.exists(dir.resolve("pyproject.toml")) || Files.exists(dir.resolve("pytest.ini"))
                || Files.exists(dir.resolve("setup.py")) || Files.exists(dir.resolve("requirements.txt"))) {
            return Optional.of(new Detection(subdir, List.of("python3", "-m", "pytest"), "python"));
        }
        if (hasNpmTestScript(dir.resolve("package.json"))) {
            return Optional.of(new Detection(subdir, List.of("npm", "test"), "node"));
        }
        if (Files.exists(dir.resolve("Cargo.toml"))) {
            return Optional.of(new Detection(subdir, List.of("cargo", "test"), "rust"));
        }
        if (Files.exists(dir.resolve("go.mod"))) {
            return Optional.of(new Detection(subdir, List.of("go", "test", "./..."), "go"));
        }
        return Optional.empty();
    }

    /** A package.json counts only when it declares a real test script (not npm's default stub). */
    private static boolean hasNpmTestScript(Path packageJson) {
        if (!Files.isRegularFile(packageJson)) {
            return false;
        }
        try {
            String body = Files.readString(packageJson).toLowerCase(Locale.ROOT);
            return body.contains("\"scripts\"") && body.matches("(?s).*\"test\"\\s*:.*")
                    && !body.contains("no test specified");
        } catch (IOException e) {
            return false;
        }
    }
}
