# Issue: pricing path runs at module import on every render

## Where
`.claude/scripts/session-stats.py`
- `_LIVE_PRICES = _load_pricing()` — module-level, runs unconditionally on every interpreter start
- `_load_pricing()` / `_fetch_litellm_prices()` — disk read + (when stale) synchronous HTTPS

## Problem
The statusline spawns a fresh `python3` interpreter per render, so `_load_pricing()` runs every time — including when no records have been added since the last render and pricing isn't actually needed. On a flaky network or behind a captive portal where TCP connect succeeds but the response stalls, this adds up to a 2 s spike per render at the 5-minute back-off boundary.

## Why it matters
The cache-hit fast path (no transcripts changed since last render) should cost essentially nothing. Right now it still pays a `stat` + `read_text` + `json.loads` of the pricing cache on every render, and on stale-cache renders it pays a full HTTPS round-trip.

## Direction (for the investigator)
Two complementary moves:

1. Make `_LIVE_PRICES` lazy — invoke `_load_pricing()` from `_sum_records` only after detecting at least one record whose model id isn't in `FALLBACK_PRICES` (or only after at least one priced sum is actually requested).
2. For the refresh path, prefer returning stale cache + spawning a detached background refresh (e.g. `os.spawnvp` or a forked child that returns immediately) so a slow LiteLLM fetch never blocks the visible statusline.

## Acceptance signal
On a render where every transcript is unchanged and pricing cache is fresh, the helper completes in well under ~10 ms with no network calls. A stale-cache render does not block on the network.
