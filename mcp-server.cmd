@echo off
REM Launches the agent-orchestration MCP server (stdio JSON-RPC) for Claude Code on Windows.
REM Claude Code is the brain for every agent - no ANTHROPIC_API_KEY required.
REM
REM Register with:
REM   claude mcp add agent-orchestration -- C:\path\to\mcp-server.cmd
setlocal
set "DIR=%~dp0"
set "JAR=%DIR%build\libs\agent-orchestration-0.1.0-SNAPSHOT.jar"

REM Build the jar on first run if it is missing.
if not exist "%JAR%" (
  call "%DIR%gradlew.bat" -p "%DIR%" bootJar --console=plain 1>&2
)

REM stdout MUST stay clean for the MCP protocol; the app routes logs to stderr (logback-mcp.xml).
java -jar "%JAR%" --spring.profiles.active=mcp
