#!/usr/bin/env bash
# One-shot installer: builds the orchestration MCP server and registers it with Claude Code.
# Your Claude Code becomes the brain for every agent — no Anthropic API key, no cost.
#
# Usage:
#   ./install.sh
#
# Requirements: a JDK (to build), git, and Claude Code (the `claude` CLI).
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAME="agent-orchestration"

echo "==> Building the MCP server (first build downloads Gradle + JDK 21; be patient)…"
"$DIR/gradlew" -p "$DIR" bootJar --console=plain

JAR="$(ls "$DIR"/build/libs/*.jar 2>/dev/null | head -1 || true)"
if [[ -z "${JAR:-}" ]]; then
  echo "!! Build did not produce a jar in build/libs. Aborting." >&2
  exit 1
fi
echo "==> Built: $JAR"

if ! command -v claude >/dev/null 2>&1; then
  cat >&2 <<EOF
!! The 'claude' CLI (Claude Code) was not found on your PATH.
   Install Claude Code, then run:
     claude mcp add $NAME -- "$DIR/mcp-server.sh"
EOF
  exit 1
fi

# Re-register cleanly (ignore error if it wasn't there).
claude mcp remove "$NAME" >/dev/null 2>&1 || true
claude mcp add "$NAME" -- "$DIR/mcp-server.sh"

echo
echo "==> Done. Verify with:  claude mcp list"
echo "==> Then open a NEW Claude Code session in any folder and say:"
echo "       \"Use the $NAME MCP: call orchestrate_start with my feature request, then"
echo "        loop orchestrate_next / orchestrate_submit, acting as each agent, until DONE.\""
echo "    Generated code is committed under: $DIR/data/repo"
