# Context Window Monitor — Implementation & Rationale

The level table itself lives in `CLAUDE.md` § Context Window Monitor (it's load-bearing). This file holds the implementation details and threshold rationale.

## How it works

- The statusline script (`${CLAUDE_PROJECT_DIR}/.claude/scripts/statusline-command.sh`) runs after every Claude response.
- It receives context usage data from Claude Code's JSON payload (`context_window.used_percentage`).
- It writes the current usage to a per-session file keyed by the root `claude` process PID.
- No hooks or `additionalContext` are used — zero conversation pollution.

## Session isolation

The file is keyed by the `claude` process PID. The statusline walks up the process tree to find the ancestor process named `claude`. The model's Bash tool is a direct child of that same process, so `$PPID` resolves to the same PID. This is safe with concurrent sessions — each gets its own file.

## Why these thresholds

Calibrated for Claude Opus 4.7 on the 1M context window using published long-context retrieval data plus community Claude Code reports:

- **Single-needle retrieval** holds at ~99% accuracy through 200K tokens, then drops to ~89% at 1M (Opus 4.7, NIAH-style benchmarks). Below 20% (≤200K) the model is essentially at full quality — no need to slow down.
- **Multi-needle retrieval** (which agentic Claude Code work resembles, since it must recall multiple file paths, decisions, and conventions) drops from ~96% at 200K to ~56% at 1M for Opus 4.7. The published "effective context for production multi-needle workloads" sits in the **200–400K** band — this maps directly to the warning (300K) → critical (400K) boundary.
- **Auto-compaction** triggers around 83–95% in Claude Code, but quality has already significantly degraded by then — the critical threshold fires well before that point so the session can be saved and restarted cleanly.
- **Self-reported degradation** (Claude observing its own behavior in long Claude Code sessions): noticeable circular reasoning and forgotten earlier decisions begin around 40–50% on Opus 4.6 with the 1M window. Opus 4.7 improves long-context tracking (Anthropic's own claim: "most consistent long-context performance of any model tested"), but the underlying multi-needle drop curve hasn't been eliminated, so the critical 40% line stays put.

Sources: GitHub issue [anthropics/claude-code#34685](https://github.com/anthropics/claude-code/issues/34685); [Claude Opus 4.7 launch notes](https://platform.claude.com/docs/en/about-claude/models/whats-new-claude-4-7); [Long-Context Retrieval 2026 (digitalapplied.com)](https://www.digitalapplied.com/blog/long-context-retrieval-needle-in-haystack-2026); community guides like spacecake.ai's Claude Code context management.

## Configuration

- Statusline script: `${CLAUDE_PROJECT_DIR}/.claude/scripts/statusline-command.sh`
- Context file: `/tmp/claude-code-context-usage-<claude_pid>.txt`
- Wired via: `${CLAUDE_PROJECT_DIR}/.claude/settings.json` (under `statusLine`)
