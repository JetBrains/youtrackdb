# Issue: `_store_cache` rewrites the entire `rs` map on every transcript append

## Where
`.claude/scripts/session-stats.py`
- `_store_cache()` — calls `_atomic_write_json(cache_file, payload)`
- `aggregate_file()` — both `_store_cache` callsites pass `payload["rs"] = records` (the full record map)

## Problem
`aggregate_file` does an incremental tail-read (good — only the new bytes are parsed), but then rewrites the whole `rs` dict to disk via `json.dump`. For a long session whose transcript already has thousands of turns, every newly-appended turn forces a multi-MB JSON encode + atomic-replace cycle — even though only the appended record is new.

## Why it matters
Cost grows linearly with session length. A 5K-turn session pays ~10–30 ms of `json.dumps` per render, on top of fsync/replace I/O. The active orchestrator transcript is exactly the file that grows fastest, so this is paid every user turn.

## Direction (for the investigator)
Append-only delta format: keep a small JSON header (`{v, mtime, size, offset, count}`) plus a separate `<sha1>.records` JSONL where new `[rid, payload]` pairs are appended on each invalidation. Full rewrite only on rotation or compaction (e.g., when count exceeds 2× distinct count). Header file stays small enough that a per-render rewrite is free.

## Acceptance signal
Per-render `_store_cache` cost on the active orchestrator transcript stops scaling with total session length and stays bounded to O(new records since last render).
