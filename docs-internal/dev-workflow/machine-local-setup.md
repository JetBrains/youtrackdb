# Machine-local setup

This document describes local setup for Slate model routing and YouTrack access.
The repository does not need machine-local model overrides to enable routing.

## Model routing

The project requires Pi 1.6.0 or later and configures `anthropic/claude-fable-5-2` at `high`
as Pi's startup default. Slate supplies the ordinary model pool and the worker-summary
compressor unless home Slate settings override them. Completed worker content goes to the
configured compressor, OpenAI by default.

For isolated checks, set both `HOME` and `PI_CODING_AGENT_DIR` to temporary locations, use a
temporary project, and pass no real credentials. Use the documented Pi package commands.
Report missing models as a runtime limitation, not a policy result. An isolated check does
not prove that personal settings or authenticated routes work.

## Personal settings

The active Pi agent directory is `~/.pi/agent` by default. If you set `PI_CODING_AGENT_DIR`,
use the absolute path to your private agent directory for this setup. The project keeps the
package notification defaults. Set personal channel preferences in `slate.json` in that
directory.

## YouTrack access for worker threads

Model Context Protocol (MCP) connects tools to YouTrack. Pi is the agent application.
Slate starts worker threads in Pi. Each worker loads `pi-mcp-adapter` through the project
worker extension whitelist. The adapter exposes an `mcp` gateway and can register cached
server tools as Pi deferred tools named `mcp__<server>__<tool>`, even with `directTools` and
`namespaceProxyTools` disabled. Use the gateway for discovery and calls. Do not assume direct
deferred names are callable in every worker. The gateway can reach enabled, accessible
servers other than YouTrack. Servers from project files require project trust and approval
under the default approval policy.

The orchestrator is the parent session that starts workers. Slate restricts its tool set at
startup and guards later calls against its orchestrator allowlist.

The repository's `.pi/mcp-adapter.json` stores adapter behavior settings only. Keep the
YouTrack server address in `mcp-adapter.json` in the active Pi agent directory. Keep the token
in a separate local file. Neither your server entry nor the token belongs in the repository.

In YouTrack, open your avatar, then select Profile > Account Security > Tokens. Select New
token and give it the YouTrack scope. Create owner-only directories and files before editing
them:

```bash
(
  umask 077
  agent_dir=${PI_CODING_AGENT_DIR:-"$HOME/.pi/agent"}
  mkdir -p "$HOME/.config/youtrack" "$agent_dir"
  chmod 700 "$HOME/.config/youtrack" "$agent_dir"
  touch "$HOME/.config/youtrack/mcp-auth-header" "$agent_dir/mcp-adapter.json"
  chmod 600 "$HOME/.config/youtrack/mcp-auth-header" "$agent_dir/mcp-adapter.json"
)
```

The adapter reads both personal `mcp.json` and `mcp-adapter.json`, and both project equivalents.
`hostConfigDiscovery: "off"` disables discovery from other applications, not these standard
sources. Personal `mcp-adapter.json` takes precedence over personal `mcp.json`. Project sources
rank above personal sources. Keep one authoritative `youtrack` entry in the personal adapter
file and preserve unrelated servers when consolidating duplicates.

Use normal merged mode for this setup. Leave `PI_MCP_CONFIG_MODE` unset. The value
`exclusive` skips the tracked adapter settings, including `semanticSearch: false`,
which disables Pi-backed semantic ranking of tool metadata.

The adapter replaces Pi's built-in MCP integration. On startup it can persist
`"-builtin:mcp"` in `settings.json` in the active Pi agent directory, once per adapter
version. It leaves an existing explicit built-in preference alone. Inspect this user-setting
side effect in the isolated profile when accepting the upgrade.

Edit `~/.config/youtrack/mcp-auth-header` in an editor. Put this single line in that file,
with your own permanent token in place of the placeholder:

```text
Bearer <your permanent token>
```

Do not put the token in a shell command. In `mcp-adapter.json` in the active Pi agent
directory, add or update the `youtrack` entry inside `mcpServers`. Create that object if
needed and preserve unrelated entries:

```json
{
  "mcpServers": {
    "youtrack": {
      "url": "https://youtrack.jetbrains.com/mcp",
      "roots": false,
      "headers": {
        "Authorization": "!cat ~/.config/youtrack/mcp-auth-header"
      }
    }
  }
}
```

The adapter trims the `!` command's standard output and uses it as the header value.
It rejects failed commands and empty values. It does not add `Bearer`. Setting
`roots: false` prevents the adapter from sharing the checkout path through MCP roots.
Keep both files private at mode 0600 and do not commit them.

Keep the default lazy lifecycle and leave `directTools` off for YouTrack. Another enabled
`eager` or `keep-alive` server can trigger startup discovery of uncached YouTrack metadata
and resolve its header even if no worker requests YouTrack. Restart Pi after saving. Expect
the worker gateway to report no servers only if the effective configuration contains none.
A configured server can instead be disabled or await approval.

Opening `/mcp-adapter` or `/mcp` can connect a lazy server with an empty metadata cache
and resolve its header before any tool call. Do not open the panel for an idle startup check
with real server settings. The adapter's enable, disable, and semantic setup commands can
change the tracked `.pi/mcp-adapter.json`. Inspect `git status` after panel changes. Do not
commit personal server entries or enable semantic search as an incidental setup change.

### Isolated adapter checks

Use the isolated setup above with a single local fixture server in the personal adapter
file. Keep it lazy and start with an empty metadata cache. Use a harmless tool with a known
result. Supply scripted tool calls to Pi through a test driver, not a live model. Log fixture
connections and tool invocations. For a command-backed header, use a dummy value and log
command execution without logging the value.

1. Start Pi and a worker without opening the panel or using the gateway. Idle startup passes
   only if neither startup connects to the fixture or runs its header command.
2. Call the fixture tool in the worker through `mcp`. Require the expected result and a logged
   tool invocation. Showing the gateway alone does not pass.
3. Check gateway exclusion in orchestrator mode. If the driver starts in tactical mode,
   enter orchestrator mode with `/slate on`. Confirm that MCP tools are absent from the active
   set on entry and that `mcp` is still absent when an ordinary tool turn starts.

   Submit a scripted `mcp` call with the same fixture arguments as the worker call. Require
   rejection. Pi can report `Tool mcp not found` before Slate checks the call, so this check
   does not require a Slate denial. Compare the fixture tool's invocation count immediately
   before and after the attempt. The count must not change.
4. Check Slate's guard separately in the isolated Pi SDK driver. In the orchestrator session,
   run `/mcp-adapter reconnect <server>`. Confirm that the adapter registers the fixture tool
   with deferred exposure.

   Use a test-only parent tool under a name Slate already allows, such as a fixture
   implementation of `read` supplied through SDK `customTools`. Give it one fixed operation:
   call the registered fixture tool by its exact name with valid arguments through Pi's
   `ctx.executeTool()`. Do not change the allowlist, activate the deferred tool, or call its
   implementation directly.

   Require the nested result to report `slate: orchestrator mode does not allow this tool.`
   A lookup or argument error does not pass. Compare the fixture tool's invocation count
   immediately before and after the attempt. The count must not change. A stock interactive
   session does not provide this test parent. If the driver cannot dispatch the nested call,
   record the guard check as unevaluated.
