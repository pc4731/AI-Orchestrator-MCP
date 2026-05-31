# One-shot installer for Windows (PowerShell): builds the orchestration MCP server and
# registers it with Claude Code. Your Claude Code becomes the brain for every agent -
# no Anthropic API key, no cost.
#
# Usage (from the repo folder):
#   powershell -ExecutionPolicy Bypass -File .\install.ps1
#
# Requirements: a JDK (to build), git, and Claude Code (the `claude` CLI).
$ErrorActionPreference = "Stop"
$Dir = $PSScriptRoot
$Name = "agent-orchestration"

Write-Host "==> Building the MCP server (first build downloads Gradle + JDK 21; be patient)..."
& "$Dir\gradlew.bat" -p "$Dir" bootJar --console=plain

$jar = Get-ChildItem "$Dir\build\libs\*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jar) {
    Write-Error "Build did not produce a jar in build\libs. Aborting."
    exit 1
}
Write-Host "==> Built: $($jar.FullName)"

if (-not (Get-Command claude -ErrorAction SilentlyContinue)) {
    Write-Warning "The 'claude' CLI (Claude Code) was not found on your PATH."
    Write-Host  "Install Claude Code, then run:"
    Write-Host  "  claude mcp add $Name -- `"$Dir\mcp-server.cmd`""
    exit 1
}

# Re-register cleanly (ignore error if it wasn't there).
claude mcp remove $Name 2>$null | Out-Null
claude mcp add $Name -- "$Dir\mcp-server.cmd"

Write-Host ""
Write-Host "==> Done. Verify with:  claude mcp list"
Write-Host "==> Then open a NEW Claude Code session and say:"
Write-Host "       `"Use the $Name MCP: call orchestrate_start with my feature request, then"
Write-Host "        loop orchestrate_next / orchestrate_submit, acting as each agent, until DONE.`""
Write-Host "    Generated code is committed under: $Dir\data\repo"
