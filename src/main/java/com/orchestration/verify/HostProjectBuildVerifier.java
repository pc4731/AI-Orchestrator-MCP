package com.orchestration.verify;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the build/test command directly on the host, in the project's own directory — the same way a
 * human would run it. Output is redirected to a temp file (so a chatty build can't deadlock on a full
 * pipe) and the process is killed if it exceeds the timeout.
 */
public class HostProjectBuildVerifier implements ProjectBuildVerifier {

    private static final int MAX_OUTPUT_CHARS = 8_000;

    @Override
    public Result verify(String projectDir, List<String> command, Duration timeout) {
        if (command == null || command.isEmpty()) {
            return new Result(false, true, -1, false, "no test command configured");
        }
        File logFile;
        try {
            logFile = File.createTempFile("orchestration-build-", ".log");
        } catch (IOException e) {
            return new Result(false, true, -1, false, "could not create build log: " + e.getMessage());
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(projectDir));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(logFile));
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                // The command itself couldn't be launched (e.g. the toolchain isn't installed). This is
                // an environment issue, not a code failure — signal couldNotStart so we don't block.
                return new Result(false, true, -1, false,
                        "could not run " + String.join(" ", command) + ": " + e.getMessage());
            }
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            String output = readTruncated(logFile);
            if (!finished) {
                process.destroyForcibly();
                return new Result(false, false, -1, true, output + "\n[build timed out]");
            }
            int code = process.exitValue();
            return new Result(code == 0, false, code, false, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, false, -1, false, "build interrupted");
        } finally {
            logFile.delete();
        }
    }

    private static String readTruncated(File logFile) {
        try {
            String s = Files.readString(logFile.toPath());
            return s.length() <= MAX_OUTPUT_CHARS ? s : s.substring(0, MAX_OUTPUT_CHARS) + "\n…[truncated]";
        } catch (IOException e) {
            return "";
        }
    }
}
