#!/usr/bin/env bash
# Launches the agent-orchestration MCP server (stdio JSON-RPC) for Claude Code.
# Claude Code is the brain for every agent — no ANTHROPIC_API_KEY required.
#
# Register with:
#   claude mcp add agent-orchestration -- /absolute/path/to/mcp-server.sh
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_NAME="agent-orchestration-0.1.0-SNAPSHOT.jar"

# Prefer the committed prebuilt jar (dist/), then a local build (build/libs/); build only if neither.
if [[ -f "$DIR/dist/$JAR_NAME" ]]; then
  JAR="$DIR/dist/$JAR_NAME"
elif [[ -f "$DIR/build/libs/$JAR_NAME" ]]; then
  JAR="$DIR/build/libs/$JAR_NAME"
else
  "$DIR/gradlew" -p "$DIR" bootJar --console=plain 1>&2
  JAR="$DIR/build/libs/$JAR_NAME"
fi

# Find a Java 21+ runtime. The jar targets Java 21, so a bare JDK 17 on PATH will fail.
# Order: JAVA_HOME → macOS java_home -v 21 → a Gradle-provisioned toolchain JDK → PATH java.
is_java21() { [[ -x "$1" ]] && "$1" -version 2>&1 | grep -qE '"(2[1-9]|[3-9][0-9])'; }

JAVA_BIN=""
if is_java21 "${JAVA_HOME:-}/bin/java"; then
  JAVA_BIN="$JAVA_HOME/bin/java"
elif command -v /usr/libexec/java_home >/dev/null 2>&1 && H="$(/usr/libexec/java_home -v 21 2>/dev/null)" && is_java21 "$H/bin/java"; then
  JAVA_BIN="$H/bin/java"
else
  for c in "$HOME"/.gradle/jdks/*/*/Contents/Home/bin/java "$HOME"/.gradle/jdks/*/*/bin/java; do
    if is_java21 "$c"; then JAVA_BIN="$c"; break; fi
  done
fi
if [[ -z "$JAVA_BIN" ]]; then
  if is_java21 "$(command -v java 2>/dev/null)"; then
    JAVA_BIN="java"
  else
    echo "[mcp] ERROR: a Java 21+ runtime is required but none was found." >&2
    echo "[mcp] Install Temurin/OpenJDK 21 (e.g. 'brew install openjdk@21') or set JAVA_HOME." >&2
    exit 1
  fi
fi

# stdout MUST stay clean for the MCP protocol; the app routes logs to stderr (logback-mcp.xml).
exec "$JAVA_BIN" -jar "$JAR" --spring.profiles.active=mcp
