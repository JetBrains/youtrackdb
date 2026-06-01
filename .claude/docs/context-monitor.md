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

Calibrated for Claude Opus 4.8 on the 1M context window, against the GraphWalks long-context benchmark. GraphWalks measures multi-hop reasoning over the whole context, the closest published proxy for agentic Claude Code work (which chains file paths, decisions, and conventions rather than retrieving one buried fact). The earlier Opus 4.7 bands were set against NIAH-style spot retrieval; every band moves up one notch here because Opus 4.8 degrades about half as fast on GraphWalks.

The Opus 4.8 System Card (May 28, 2026, §8.9) reports long context only via GraphWalks, split into 256K and 1M subsets. The 256K point sits inside the old info→warning band, so the bands are fit to measured data rather than extrapolated from it.

GraphWalks F1:

| Subset | 4.8 @256K | 4.8 @1M | 4.7 @256K | 4.7 @1M |
|---|---|---|---|---|
| BFS (hard) | 85.9 | 68.1 | 76.9 | 40.3 |
| Parents | 99.3 | 83.3 | 93.6 | 56.6 |

- **Slope halved.** On the hard BFS subset, Opus 4.7 loses 4.9 F1 points per 100K tokens across the 256K→1M span; Opus 4.8 loses 2.4, about half the decay rate, and scores higher at every measured point. Extrapolating Opus 4.7's steeper slope, Opus 4.8 at the full 1M window lands near Opus 4.7's quality at ~400K — the old critical line. The whole Opus 4.8 window is about as reliable as Opus 4.7's first 400K, so each band moves up one notch (info 20→25%, warning 30→40%, critical 40→50%). The one-band shift is conservative against what the slope-halving alone would justify.
- **No retrieval-precision cliff.** A local multi-needle probe (10 distinct buried keys, exact-match grading, question placed after the haystack) scored 60/60 on both Opus 4.8 and 4.7 out to 82% of the window. Exact distinct-key recall, the analog of remembering a specific file path or config value, stays saturated well past the bands, so there is no separate retrieval cliff below the reasoning curve. This is why critical sits at 50% rather than a more cautious 45%. Scope: the probe covered distinct-key recall, not MRCR-style ordinal-instance disambiguation, at one trial per depth.
- **Auto-compaction** triggers around 83–95% in Claude Code, by which point quality has already degraded badly. Critical (50%) fires far enough below that point to save and restart a session cleanly.

Sources: Claude Opus 4.8 System Card, May 28, 2026 (§8.9 Long context: GraphWalks); local multi-needle retrieval probe (60/60 exact match across 408K / 640K / 819K, both models), recorded in YTDB-1035.

## Configuration

- Statusline script: `${CLAUDE_PROJECT_DIR}/.claude/scripts/statusline-command.sh`
- Context file: `/tmp/claude-code-context-usage-<claude_pid>.txt`
- Wired via: `${CLAUDE_PROJECT_DIR}/.claude/settings.json` (under `statusLine`)
