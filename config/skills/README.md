# Agent skills

Each `*.md` file here is a **skill** — a reusable block of guidance/capability you can attach to any
agent. An agent opts in by listing the skill's name (the filename without `.md`) under `skills:` in
`config/agents.yml`. The skill's content is appended to that agent's system prompt, so it works the
same whether the agent is backed by the Claude API or by Claude Code over MCP.

Example — give the UI Designer and Frontend Developer an accessibility skill:

```yaml
agents:
  definitions:
    ui-designer:
      role: UI_DESIGNER
      model: sonnet
      prompt-file: prompts/ui-designer.md
      capabilities: [DESIGN_UI]
      skills: [accessibility, modern-ui]
```

To add a skill: drop a new `my-skill.md` here and reference it by name. Unknown names are skipped
(not fatal), so a typo degrades gracefully. Change the directory via `skills.dir` in
`application.yml`.
