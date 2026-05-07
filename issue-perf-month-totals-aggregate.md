# Issue: `month_totals()` re-parses every active-month cache on every render

## Where
`.claude/scripts/session-stats.py`
- `month_totals()` — `def month_totals():` block (UTC-aware version)
- `aggregate_file()` — warm-path cache hit returns `cached.get("rs") or {}`
- `_load_cache()` — `json.loads(cache_file.read_text())`

## Problem
Every statusline render walks `~/.claude/projects/**/*.jsonl` for the current month, calls `aggregate_file(jsonl)`, and on the warm path that helper still has to deserialize the cache file's entire `rs` map (one entry per assistant turn) just to iterate and copy records into `merged`. There is no precomputed per-file monthly total — dedup runs over every record on every render.

## Why it matters
For a heavy `/execute-tracks` user there can be hundreds of active-month JSONLs and multi-thousand-turn transcripts. Per render this realistically deserializes 5–50 MB of JSON and merges 100K–1M records. Statusline runs on every user turn, so the cost is paid continuously. Casual users see no impact.

## Direction (for the investigator)
Persist a precomputed per-file monthly aggregate inside the cache (e.g. `"month_total": {in, out, read, w5, w1, by_model}`). Cross-file `(msg_id, requestId)` dedup mostly only matters between a parent transcript and its `subagents/agent-*.jsonl` (already handled by `session_totals`); for unrelated files in different sessions, summing precomputed aggregates is enough. Worth measuring first to confirm `_load_cache` + record-merge dominates, vs. the recursive `rglob`.

## Acceptance signal
Statusline second-line render p99 stays under ~50 ms for a user with ~500 active-month JSONLs.
