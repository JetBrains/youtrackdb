# Machine-local setup

This document describes local setup for Slate model routing and YouTrack access.
The repository does not need machine-local model overrides to enable routing.

## Model routing

Slate uses logical models. A logical model is a provider-free name that selects a configured
physical model and fixed effort. The `router.models` value is an object with optional `include`,
`add`, `replace`, and `exclude` lists. An omitted `include` starts with the six shipped models.
An explicit empty `include` starts with no ordinary models. See `model-routing.md` in the
installed `ytdb-slate` package for the full configuration rules.

Check that the repository configuration parses and uses the new object form:

```bash
node -e '
const c = require("./.pi/slate.json")
const m = c.router?.models
if (!m || Array.isArray(m) || typeof m !== "object") {
  throw new Error("router.models must be an object")
}
if (m.include !== undefined && !Array.isArray(m.include)) {
  throw new Error("router.models.include must be an array")
}
if (m.include?.length === 0 && !m.add?.length) {
  throw new Error("no ordinary model selected")
}
console.log("router model policy present")
'
```

Start a new Pi session after changing the configuration. Run `/slate effective` to check the
effective model pool and any router configuration errors. The local command above checks the
repository file only. It does not validate merged home settings or model credentials.

## YouTrack access for worker threads

Model Context Protocol (MCP) connects tools to YouTrack. Pi is the agent application.
Slate starts worker threads in Pi. Each worker gets an `mcp` gateway tool through
`pi-mcp-adapter`. The gateway finds and calls YouTrack tools when a worker requests them.

The orchestrator is the parent session that starts workers. Slate removes the gateway tool
from the orchestrator at session start. Later adapter actions can add it back.
JetBrains/ytdb-slate#441 tracks this limit.

The repository's `.pi/mcp.json` stores adapter behavior settings only. Keep the YouTrack
server address in your personal `~/.pi/agent/mcp.json`. Keep the token in a separate local
file. Neither the address nor the token belongs in the repository.

In YouTrack, open your avatar, then select Profile > Account Security > Tokens. Select New
token and give it the YouTrack scope. Create owner-only directories and files before editing
them:

```bash
(
  umask 077
  mkdir -p ~/.config/youtrack ~/.pi/agent
  chmod 700 ~/.config/youtrack ~/.pi/agent
  touch ~/.config/youtrack/mcp-auth-header ~/.pi/agent/mcp.json
  chmod 600 ~/.config/youtrack/mcp-auth-header ~/.pi/agent/mcp.json
)
```

Edit `~/.config/youtrack/mcp-auth-header` in an editor. Put this single line in that file,
with your own permanent token in place of the placeholder:

```text
Bearer <your permanent token>
```

Do not put the token in a shell command. Put this configuration in `~/.pi/agent/mcp.json`:

```json
{
  "mcpServers": {
    "youtrack": {
      "url": "https://youtrack.jetbrains.com/mcp",
      "headers": {
        "Authorization": "!cat ~/.config/youtrack/mcp-auth-header"
      }
    }
  }
}
```

The adapter runs the `!` command and uses its entire output as the header value. It does not
add `Bearer` to that output. Keep both personal files private. Do not commit either file.

Keep this server lazy. Do not set `lifecycle` to `eager` or `keep-alive`. Do not enable
`directTools` for this server. These options add startup connections or tools outside this
setup. Restart Pi after saving the file. Without this file, a worker still sees the gateway,
but the gateway reports no server.

The `/mcp` panel and `/mcp` subcommands can change the tracked `.pi/mcp.json`.
These subcommands include `/mcp disable <server>`, `/mcp enable <server>`, and
`/mcp jev setup`. Those changes appear in `git status`. Do not commit them.
