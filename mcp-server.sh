#!/usr/bin/env bash
# Launches the agent-orchestration MCP server (stdio JSON-RPC) for Claude Code.
# Claude Code is the brain for every agent — no ANTHROPIC_API_KEY required.
#
# Register with:
#   claude mcp add agent-orchestration -- /absolute/path/to/mcp-server.sh
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$DIR/build/libs/agent-orchestration-0.1.0-SNAPSHOT.jar"

# Build the jar on first run if it is missing.
if [[ ! -f "$JAR" ]]; then
  "$DIR/gradlew" -p "$DIR" bootJar --console=plain 1>&2
fi

# Pin a JDK 21 the Gradle toolchain already provisioned, falling back to PATH java.
JAVA_BIN="java"
for c in "$HOME"/.gradle/jdks/*/*/Contents/Home/bin/java "$HOME"/.gradle/jdks/*/*/bin/java; do
  [[ -x "$c" ]] && JAVA_BIN="$c" && break
done

# stdout MUST stay clean for the MCP protocol; the app routes logs to stderr (logback-mcp.xml).
exec "$JAVA_BIN" -jar "$JAR" --spring.profiles.active=mcp
