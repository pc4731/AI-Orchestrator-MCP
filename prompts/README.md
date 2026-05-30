# Agent system prompts

One Markdown file per agent (referenced by `config/agents.yml` via `prompt-file`). Prompts are
externalised here so they can be edited without code changes and marked cacheable by the LLM client.

These files are authored in the agent-implementation phase. Expected files:

- `team-lead.md`
- `backend-architect.md`
- `frontend-architect.md`
- `ui-designer.md`
- `backend-developer.md`
- `frontend-developer.md`
- `qa-engineer.md`
- `dba.md`
- `security-reviewer.md`
