@echo off
REM Launches the agent-orchestration MCP server (stdio JSON-RPC) for Claude Code on Windows.
REM Claude Code is the brain for every agent - no ANTHROPIC_API_KEY required.
REM
REM Register with:
REM   claude mcp add agent-orchestration -- C:\path\to\mcp-server.cmd
setlocal
set "DIR=%~dp0"
set "JAR_NAME=agent-orchestration-0.1.0-SNAPSHOT.jar"

REM Prefer the committed prebuilt jar (dist\), then a local build (build\libs\); build if neither.
if exist "%DIR%dist\%JAR_NAME%" (
  set "JAR=%DIR%dist\%JAR_NAME%"
) else if exist "%DIR%build\libs\%JAR_NAME%" (
  set "JAR=%DIR%build\libs\%JAR_NAME%"
) else (
  call "%DIR%gradlew.bat" -p "%DIR%" bootJar --console=plain 1>&2
  set "JAR=%DIR%build\libs\%JAR_NAME%"
)

REM The mcp profile also serves a read-only dashboard. Override the port if 8090 is taken by
REM setting AO_DASHBOARD_PORT before launching.
if "%AO_DASHBOARD_PORT%"=="" set "AO_DASHBOARD_PORT=8090"

REM Use JAVA_HOME's java if set, else PATH java (must be Java 21+; the jar targets Java 21).
set "JAVA_BIN=java"
if defined JAVA_HOME set "JAVA_BIN=%JAVA_HOME%\bin\java"

REM stdout MUST stay clean for the MCP protocol; the app routes logs to stderr (logback-mcp.xml).
"%JAVA_BIN%" -jar "%JAR%" --spring.profiles.active=mcp --server.port=%AO_DASHBOARD_PORT%
