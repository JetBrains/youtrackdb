#!/usr/bin/env python3
"""Compute current-session, today, and calendar-month cost/token stats for the
Claude Code statusline.

Output (single line, second line of the statusline):

  $0.123 (day:$2.34 mo:$4.56)  in:1.2K out:8.0K read:230K w5m:40K w1h:0  r/5m:5.7x r/1h:-

The `day:` figure is today's spend across every project — the calendar-day
analogue of `mo:` — and is always shown. When invoked with `--worktree` (the
statusline adds it whenever the cwd is a linked git worktree) worktree-scoped
figures are inserted at the front of the parenthetical: `wt:` (the current
worktree project's all-time spend) immediately followed by its split into
`[main:` (top-level orchestrator / main-session spend) and `sub:` (sub-agent
spend) that together sum to `wt:`, and then `wtday:` (today's slice of `wt:`):

  $0.123 (wt:$1.85 [main:$1.20 sub:$0.65] wtday:$0.40 day:$2.34 mo:$4.56)  in:1.2K out:8.0K read:230K …

The main/sub split is exact: a sub-agent's records live only under
<session>/subagents/, never in a top-level session file, so the two record
sets are disjoint and partition the worktree total.

The current session aggregates the orchestrator transcript plus every
sub-agent transcript under <transcript-stem>/subagents/. The worktree-project
figures aggregate *every* session transcript that lives next to the current
one — i.e. all sessions under ~/.claude/projects/<cwd-slug>/. Because a git
worktree has its own cwd, it maps to its own project directory, so `wt:` is the
cumulative spend of the current worktree across every session run in it, and
`wtday:` is the same aggregation keeping only records dated today (UTC). The
monthly figure sums every JSONL transcript anywhere under ~/.claude/projects
whose records fall in the current calendar month.

With `--worktree-cost-file PATH` the all-time worktree-project cost (the `wt:`
figure, not the `wtday:` slice) is also written to PATH (the statusline points
this at /tmp/claude-code-worktree-cost-<pid>.txt, mirroring the context-usage
file) so the model can read the running spend on the current worktree on
demand. The file carries the same main/sub split as the statusline:

  wt_cost: $1.85 (main: $1.20 sub: $0.65)

Two non-obvious mechanics, both required to match ccusage:

  1. **Record-timestamp bucketing** (not file mtime). A file modified in May
     can contain April records, and vice versa, so the daily and monthly
     buckets are each decided per record using its own `timestamp` field.

  2. **(message.id, requestId) dedup, max-output wins.** ~40% of records appear
     in multiple transcripts (sub-agent transcripts duplicate their parent's
     records, and forked sessions share message IDs). Without dedup the cost is
     inflated several-fold. Within a single transcript the same key also recurs
     as streaming snapshots whose output_tokens grows from a partial value to
     the final count; collapsing a key to its *largest*-output snapshot matches
     ccusage, which reports the completed turn. Keeping the first snapshot
     instead undercut output — the priciest token — by ~15% over a month.

Per-file totals are cached under ~/.cache/claude-code-stats/transcripts/<sha1>.json
keyed by (mtime, size). Each cache entry stores a {record_id: bucket_data}
map; both the in-file and cross-file merge keep the larger-output payload per
key (see `_keep_larger_output`). Max is order-independent, so a key's partial
and final snapshots reconcile correctly even when they fall in different read
chunks across renders (the incremental byte-offset cache) or in different files.
"""
from __future__ import annotations

import datetime as _dt
import hashlib
import json
import os
import pathlib
import sys
import time
import urllib.request

# Live pricing is fetched from LiteLLM's model_prices table (the same source
# ccusage uses), cached on disk for 24 h, and refreshed lazily. The hardcoded
# table below is the offline fallback when the network is unreachable and no
# cache has been written yet — keep it in rough sync with current Anthropic
# pricing so cold-start estimates aren't wildly off.
#
# 1 h cache write = 2.0× input price (Anthropic published ratio); LiteLLM
# only publishes the 5 m write rate, so 1 h is derived as 2.0× input.
# read = 0.1× input, write_5m = 1.25× input — the same Anthropic ratios.
FALLBACK_PRICES = {
    "claude-fable-5":            {"in": 10.0, "out": 50.0, "read": 1.00, "write_5m": 12.50, "write_1h": 20.0},
    "claude-opus-4-8":           {"in":  5.0, "out": 25.0, "read": 0.50, "write_5m":  6.25, "write_1h": 10.0},
    "claude-opus-4-7":           {"in":  5.0, "out": 25.0, "read": 0.50, "write_5m":  6.25, "write_1h": 10.0},
    "claude-opus-4-6":           {"in":  5.0, "out": 25.0, "read": 0.50, "write_5m":  6.25, "write_1h": 10.0},
    "claude-sonnet-4-6":         {"in":  3.0, "out": 15.0, "read": 0.30, "write_5m":  3.75, "write_1h":  6.0},
    "claude-haiku-4-5":          {"in":  1.0, "out":  5.0, "read": 0.10, "write_5m":  1.25, "write_1h":  2.0},
    "claude-haiku-4-5-20251001": {"in":  1.0, "out":  5.0, "read": 0.10, "write_5m":  1.25, "write_1h":  2.0},
}
DEFAULT_MODEL = "claude-opus-4-8"

LITELLM_URL = (
    "https://raw.githubusercontent.com/BerriAI/litellm/main/"
    "model_prices_and_context_window.json"
)
PRICING_CACHE_FILE = pathlib.Path.home() / ".cache" / "claude-code-stats" / "litellm-prices.json"
PRICING_FAILED_MARKER = PRICING_CACHE_FILE.with_suffix(".failed")
PRICING_TTL_SECONDS = 24 * 3600
PRICING_RETRY_AFTER_FAILURE = 300  # don't pound the network if offline
PRICING_FETCH_TIMEOUT = 2.0

# Per-user cache (not /tmp): avoids any cross-user races on a shared host
# and sidesteps the symlink-following / torn-write hazards of a shared
# CACHE_DIR. The /tmp cost is repaid by writing predictable JSON keyed by
# transcript-path SHA1, which would let any local user pre-create entries
# (or symlinks) for another user's session.
CACHE_DIR = pathlib.Path.home() / ".cache" / "claude-code-stats" / "transcripts"
# Bump on layout / dedup changes; older cache files are silently re-read
# from scratch and the stale entries are unlinked lazily by _load_cache.
# v7: dedup keeps the max-output_tokens snapshot (was first-occurrence); caches
# written under v6 hold partial-output payloads and must be rebuilt.
# v8: payload index 0 holds the full record date `ts[:10]` (was the month
# `ts[:7]`) so one cache feeds both the daily and monthly buckets; v7 caches
# store only the month and must be rebuilt.
CACHE_VERSION = 8
PROJECTS_ROOT = pathlib.Path.home() / ".claude" / "projects"


def _zero():
    return {"in": 0, "out": 0, "read": 0, "w5": 0, "w1": 0, "cost": 0.0}


def _parse_litellm(data):
    """Extract per-1M pricing for Claude models from a LiteLLM dump.

    LiteLLM stores costs per token (e.g. 5e-06 for $5/M input). We keep only
    keys starting with `claude-` (no provider prefix) so prefix matches don't
    accidentally hit Bedrock/Vertex/etc. variants. 1 h cache write isn't
    published, so we derive it as 2× input price.
    """
    out = {}
    if not isinstance(data, dict):
        return out
    for name, entry in data.items():
        if not isinstance(entry, dict) or not isinstance(name, str):
            continue
        if not name.startswith("claude-"):
            continue
        in_t = entry.get("input_cost_per_token")
        out_t = entry.get("output_cost_per_token")
        if in_t is None or out_t is None:
            continue
        read_t = entry.get("cache_read_input_token_cost", in_t * 0.10)
        w5_t = entry.get("cache_creation_input_token_cost", in_t * 1.25)
        out[name] = {
            "in":       in_t  * 1_000_000,
            "out":      out_t * 1_000_000,
            "read":     read_t * 1_000_000,
            "write_5m": w5_t  * 1_000_000,
            "write_1h": in_t  * 2.0 * 1_000_000,
        }
    return out


def _can_attempt_pricing_fetch():
    try:
        return time.time() - PRICING_FAILED_MARKER.stat().st_mtime > PRICING_RETRY_AFTER_FAILURE
    except OSError:
        return True


def _mark_pricing_fetch_failed():
    try:
        PRICING_FAILED_MARKER.parent.mkdir(parents=True, exist_ok=True)
        PRICING_FAILED_MARKER.touch()
    except OSError:
        pass


def _clear_pricing_fetch_failed():
    try:
        PRICING_FAILED_MARKER.unlink()
    except OSError:
        pass


def _fetch_litellm_prices():
    """Return parsed Claude pricing from LiteLLM, or None on failure."""
    try:
        req = urllib.request.Request(
            LITELLM_URL, headers={"User-Agent": "claude-code-stats/1.0"}
        )
        with urllib.request.urlopen(req, timeout=PRICING_FETCH_TIMEOUT) as resp:
            # Cap defensively. The live LiteLLM JSON is ~150 KB; a multi-MB
            # response from a compromised upstream or a transient HTML error
            # page would otherwise inflate the helper's RSS for every render
            # until the cache is refreshed.
            data = json.loads(resp.read(2 * 1024 * 1024))
    except (OSError, ValueError, TimeoutError):
        # OSError covers urllib.error.URLError + ssl.SSLError + socket-level
        # failures; ValueError covers json.JSONDecodeError; TimeoutError covers
        # the urlopen timeout. All non-fatal — fall through to cache/fallback.
        return None
    parsed = _parse_litellm(data)
    return parsed or None


def _atomic_write_json(target, payload):
    """Write `payload` as JSON to `target` atomically.

    Two non-obvious requirements:

      1. **Per-process temp filename.** A shared `<target>.tmp` would let two
         concurrent statusline renders open the same inode with O_TRUNC and
         interleave bytes — the resulting cache file is torn JSON which the
         next read silently treats as a cache miss. Recovery is automatic
         but defeats the incremental-aggregation optimisation.

      2. **O_NOFOLLOW + 0600.** Even though the cache directory is per-user
         under ~/.cache, defence in depth: if someone ever creates a symlink
         at `<target>.<pid>.tmp` pointing at a sensitive file, the open
         refuses to follow it.

    Caller is responsible for ensuring `target.parent` exists.
    """
    tmp = target.parent / f"{target.name}.{os.getpid()}.tmp"
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW
    fd = os.open(tmp, flags, 0o600)
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(payload, f)
    except Exception:
        try:
            tmp.unlink()
        except OSError:
            pass
        raise
    os.replace(tmp, target)


def _atomic_write_text(target, text):
    """Atomically write plain `text` to `target` (per-process temp + O_NOFOLLOW
    0600), mirroring `_atomic_write_json`'s safety properties.

    Used for the worktree-cost publish file. The target lives in /tmp, which is
    world-writable, so the same defences apply: a per-pid temp name avoids torn
    interleaving between two concurrent renders, O_NOFOLLOW refuses to open a
    symlink planted at the temp path, and `os.replace` renames over any symlink
    at the target without following it. Caller ensures `target.parent` exists.
    """
    tmp = target.parent / f"{target.name}.{os.getpid()}.tmp"
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW
    fd = os.open(tmp, flags, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(text)
    except Exception:
        try:
            tmp.unlink()
        except OSError:
            pass
        raise
    os.replace(tmp, target)


def _save_pricing_cache(parsed):
    try:
        PRICING_CACHE_FILE.parent.mkdir(parents=True, exist_ok=True)
        _atomic_write_json(PRICING_CACHE_FILE, parsed)
    except OSError:
        pass


def _load_pricing_cache():
    try:
        return json.loads(PRICING_CACHE_FILE.read_text())
    except (OSError, ValueError):
        return None


def _load_pricing():
    """Resolve live Claude pricing with a 24 h disk cache.

    Order of preference: fresh cache → successful refresh → stale cache →
    hardcoded fallback. A failure marker suppresses re-attempts for
    PRICING_RETRY_AFTER_FAILURE seconds so an offline machine doesn't add a
    fetch-timeout pause to every statusline render.
    """
    cached = _load_pricing_cache()
    try:
        age = time.time() - PRICING_CACHE_FILE.stat().st_mtime
    except OSError:
        age = float("inf")

    if cached and age < PRICING_TTL_SECONDS:
        return cached

    if _can_attempt_pricing_fetch():
        fetched = _fetch_litellm_prices()
        if fetched is not None:
            _save_pricing_cache(fetched)
            _clear_pricing_fetch_failed()
            return fetched
        _mark_pricing_fetch_failed()

    return cached or {}


# Resolve once per process. The statusline always spawns a fresh interpreter,
# so this is effectively per-call but avoids re-reading the cache for every
# record.
_LIVE_PRICES = _load_pricing()


_UNKNOWN_PRICE = {"in": 0.0, "out": 0.0, "read": 0.0, "write_5m": 0.0, "write_1h": 0.0}


def _model_pricing(m):
    """Per-1M price dict for a model id. Tries live LiteLLM data, then the
    hardcoded fallback, with longest-prefix matching for dated SKUs.

    Returns a zero-cost price for unknown / missing model ids rather than
    silently charging the most-expensive default — a future model SKU we
    haven't catalogued yet would otherwise be priced as Opus on every
    record. Tokens still accumulate; only the cost contribution is zero."""
    if not m:
        return _UNKNOWN_PRICE
    if m in _LIVE_PRICES:
        return _LIVE_PRICES[m]
    if m in FALLBACK_PRICES:
        return FALLBACK_PRICES[m]
    # Longest-prefix family match (e.g. claude-opus-4-7-20260416 → claude-opus-4-7).
    for k in sorted(_LIVE_PRICES.keys(), key=len, reverse=True):
        if m.startswith(k):
            return _LIVE_PRICES[k]
    for k in sorted(FALLBACK_PRICES.keys(), key=len, reverse=True):
        if m.startswith(k):
            return FALLBACK_PRICES[k]
    return _UNKNOWN_PRICE


def _record_from_line(raw):
    """Parse one assistant-turn line into (key, [date, model, in, out, read, w5, w1]).

    The cache stores raw tokens + model id so a future price update takes
    effect on the next render without requiring per-file cache invalidation.
    Cost is computed at sum time via _sum_records.
    """
    try:
        obj = json.loads(raw)
    except ValueError:
        return None
    if obj.get("type") != "assistant":
        return None
    msg = obj.get("message") or {}
    usage = msg.get("usage") or {}
    if not usage:
        return None
    msg_id = msg.get("id")
    req_id = obj.get("requestId")
    if not msg_id or not req_id:
        # Without a stable id we cannot dedup safely — drop rather than
        # double-count.
        return None
    ts = obj.get("timestamp") or ""
    # Full record date `YYYY-MM-DD`: the month bucket slices `date[:7]` and the
    # day bucket compares the whole string. A short / malformed timestamp yields
    # "" which matches no current day or month (same as the old month logic).
    date = ts[:10] if len(ts) >= 10 else ""
    model = msg.get("model") or DEFAULT_MODEL
    # `… or 0` (without the explicit default) handles both missing keys and
    # explicit nulls in one expression — the API emits `null` for some
    # token fields rather than omitting them.
    in_t = usage.get("input_tokens") or 0
    out_t = usage.get("output_tokens") or 0
    read_t = usage.get("cache_read_input_tokens") or 0
    cc = usage.get("cache_creation") or {}
    w5 = cc.get("ephemeral_5m_input_tokens") or 0
    w1 = cc.get("ephemeral_1h_input_tokens") or 0
    if not (w5 or w1):
        # Older turns only emit the top-level total; treat as 5 m.
        w5 = usage.get("cache_creation_input_tokens") or 0
    return f"{msg_id}:{req_id}", [date, model, in_t, out_t, read_t, w5, w1]


def _cache_path_for(path):
    key = hashlib.sha1(str(path).encode("utf-8")).hexdigest()
    return CACHE_DIR / f"{key}.json"


def _load_cache(cache_file):
    try:
        data = json.loads(cache_file.read_text())
    except (OSError, ValueError):
        return None
    if data.get("v") != CACHE_VERSION:
        # Unlink stale entries lazily as we encounter them so a CACHE_VERSION
        # bump doesn't accumulate orphans forever in the per-user cache dir.
        try:
            cache_file.unlink()
        except OSError:
            pass
        return None
    return data


def _store_cache(cache_file, payload):
    try:
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        _atomic_write_json(cache_file, payload)
    except OSError:
        pass


def _keep_larger_output(records, rid, payload):
    """Merge `payload` into `records[rid]`, keeping the snapshot whose
    output_tokens (payload index 3) is larger.

    Claude Code writes a streaming assistant turn as several records sharing one
    (msg_id, requestId): output_tokens grows from a partial value (often 1) to
    the final count, while the other token fields are fixed at request time. The
    completed record is also byte-copied across transcripts (orchestrator +
    sub-agent + forked sessions). ccusage reports the final (largest) snapshot,
    so we keep the max-output payload to match its monthly figure. Keeping the
    *first* snapshot instead — the previous behaviour — locked in the partial
    output count and undercut the priciest token by ~15% over a month.

    Using max (not first- or last-seen) is order-independent, so the result is
    correct whether the partial and final snapshots arrive in the same read, in
    different read chunks across renders (the incremental byte-offset cache may
    already hold the partial when the final arrives), or in different files.
    """
    prev = records.get(rid)
    if prev is None or payload[3] > prev[3]:
        records[rid] = payload


def aggregate_file(path):
    """Return {record_id: [date, model, in, out, read, w5, w1]} for one file.

    Cost is recomputed at sum time by `_sum_records` against the current
    price table, so a price refresh takes effect on the next render without
    requiring per-file cache invalidation.
    """
    try:
        st = path.stat()
    except OSError:
        return {}
    cache_file = _cache_path_for(path)
    cached = _load_cache(cache_file) or {}

    if cached.get("mtime") == st.st_mtime and cached.get("size") == st.st_size:
        return cached.get("rs") or {}

    prev_offset = cached.get("offset", 0) or 0
    records = cached.get("rs") or {}
    if st.st_size < prev_offset:
        # File shrank (rotation/truncation) — re-read from start.
        prev_offset = 0
        records = {}

    try:
        with path.open("rb") as f:
            f.seek(prev_offset)
            chunk = f.read()
        # Re-stat after the read so the persisted (mtime, size) reflects
        # bytes we actually consumed. The file may have grown between the
        # initial stat and the read, in which case the pre-read snapshot
        # would record `size < offset` and a future call could land on a
        # spurious truncation reset (see the `st.st_size < prev_offset`
        # branch above).
        st_after = path.stat()
    except OSError:
        return records

    last_nl = chunk.rfind(b"\n")
    if last_nl < 0:
        # Nothing complete to consume; persist the post-read stat so future
        # calls skip the read until the file actually changes.
        _store_cache(cache_file, {
            "v": CACHE_VERSION,
            "mtime": st_after.st_mtime,
            "size": st_after.st_size,
            "offset": prev_offset,
            "rs": records,
        })
        return records

    consumed_bytes = last_nl + 1
    for raw in chunk[:consumed_bytes].splitlines():
        if not raw.strip():
            continue
        rec = _record_from_line(raw)
        if rec is None:
            continue
        rid, payload = rec
        # Keep the largest-output snapshot of each (msg_id, requestId) so the
        # partial counts emitted mid-stream don't undercount output. See
        # _keep_larger_output for why max (not first/last) is the right rule.
        _keep_larger_output(records, rid, payload)

    _store_cache(cache_file, {
        "v": CACHE_VERSION,
        "mtime": st_after.st_mtime,
        "size": st_after.st_size,
        "offset": prev_offset + consumed_bytes,
        "rs": records,
    })
    return records


def _sum_records(records):
    """Sum a {record_id: [date, model, in, out, read, w5, w1]} dict applying current prices."""
    out = _zero()
    # Pricing-dict cache, since one model dominates a typical aggregation pass.
    price_cache = {}
    for v in records.values():
        # v = [date, model, in, out, read, w5, w1]
        model = v[1]
        in_t, out_t, read_t, w5, w1 = v[2], v[3], v[4], v[5], v[6]
        out["in"] += in_t
        out["out"] += out_t
        out["read"] += read_t
        out["w5"] += w5
        out["w1"] += w1
        p = price_cache.get(model)
        if p is None:
            p = _model_pricing(model)
            price_cache[model] = p
        out["cost"] += (
            in_t * p["in"]
            + out_t * p["out"]
            + read_t * p["read"]
            + w5 * p["write_5m"]
            + w1 * p["write_1h"]
        ) / 1_000_000
    return out


def session_totals(transcript_path):
    """Aggregate orchestrator + sub-agent transcripts, deduped by record id."""
    p = pathlib.Path(transcript_path).expanduser()
    if not p.exists():
        return _zero()
    merged = {}
    for k, v in aggregate_file(p).items():
        _keep_larger_output(merged, k, v)
    sub_dir = p.parent / p.stem / "subagents"
    if sub_dir.is_dir():
        for sub in sub_dir.glob("agent-*.jsonl"):
            for k, v in aggregate_file(sub).items():
                _keep_larger_output(merged, k, v)  # max-output dedup
    return _sum_records(merged)


def project_totals(transcript_path, now=None):
    """Aggregate every transcript in the current project's directory into
    (all_time, today, main, sub) deduped totals.

    The project directory is the parent of the session transcript —
    ~/.claude/projects/<cwd-slug>/. A recursive glob picks up both the
    top-level <uuid>.jsonl session files and the nested
    <uuid>/subagents/agent-*.jsonl sub-agent files; the (msg_id, requestId)
    dedup via `_keep_larger_output` collapses records shared across them.

    Because a git worktree has its own cwd, it gets its own project directory,
    so summing this directory yields the cumulative cost of the current
    worktree across every session ever run in it — not just the live one.

    Returns `(all_time, today, main, sub)`:
      - `all_time` sums every record (the `wt:` figure).
      - `today` keeps only records whose own date (payload index 0, `ts[:10]`)
        equals the current UTC day (the `wtday:` figure), mirroring the daily
        bucket in `calendar_totals`.
      - `main` / `sub` split `all_time` by where each record lives: a file
        directly under the project dir is a top-level (orchestrator / main)
        session, while a file under a `subagents/` dir at any depth (including
        the nested `subagents/workflows/wf_*/` layout for workflow-spawned
        agents) is a sub-agent transcript. A sub-agent's records never appear in
        a top-level file (verified: zero (msg_id, requestId) overlap across
        sampled projects), so the two record sets are disjoint and
        `main['cost'] + sub['cost']` equals `all_time['cost']` by construction.

    All four are produced in one directory walk so a statusline render does not
    re-stat every transcript more than once. There is no mtime pre-filter here
    (unlike `calendar_totals`): the all-time figure must read every file
    regardless, so there is nothing to skip.

    `now` defaults to the current UTC instant; it is injectable so the
    today-bucketing can be exercised against a fixed date in tests. Records are
    `Z`-suffixed UTC, so `cur_day` is derived in UTC too.
    """
    p = pathlib.Path(transcript_path).expanduser()
    # Mirror session_totals: short-circuit a non-existent transcript before the
    # rglob below. Without this, a path whose *parent* exists (e.g. a stale or
    # relative path resolving to /tmp/x.jsonl) would recursively scan that whole
    # parent directory for *.jsonl files.
    if not p.exists():
        return _zero(), _zero(), _zero(), _zero()
    proj_dir = p.parent
    if not proj_dir.is_dir():
        return _zero(), _zero(), _zero(), _zero()
    if now is None:
        now = _dt.datetime.now(_dt.timezone.utc)
    cur_day = f"{now.year:04d}-{now.month:02d}-{now.day:02d}"
    all_merged = {}
    day_merged = {}
    sub_keys = set()
    for jsonl in proj_dir.rglob("*.jsonl"):
        # A sub-agent transcript lives somewhere under a `subagents/` dir:
        # directly at <proj>/<uuid>/subagents/agent-*.jsonl, or nested deeper at
        # <proj>/<uuid>/subagents/workflows/wf_*/agent-*.jsonl for workflow-spawned
        # agents. Test for `subagents` anywhere in the path below the project dir
        # (not just the immediate parent), so both depths count as sub; everything
        # else is a top-level (orchestrator / main) session file.
        is_sub = "subagents" in jsonl.relative_to(proj_dir).parts
        for k, v in aggregate_file(jsonl).items():
            _keep_larger_output(all_merged, k, v)  # max-output dedup
            if is_sub:
                sub_keys.add(k)
            if v[0] == cur_day:
                _keep_larger_output(day_merged, k, v)
    # Partition the deduped all-time records into sub (key seen in any subagent
    # file) and main (everything else). Partitioning the already-deduped set
    # (rather than summing two independently-merged buckets) keeps
    # main['cost'] + sub['cost'] == all_time['cost'] unconditionally, even if a
    # record were duplicated across a top-level and a sub-agent file. (It isn't
    # in practice; verified zero (msg_id, requestId) overlap.) The partition
    # makes the invariant hold regardless.
    main_merged = {k: v for k, v in all_merged.items() if k not in sub_keys}
    sub_merged = {k: v for k, v in all_merged.items() if k in sub_keys}
    return (
        _sum_records(all_merged),
        _sum_records(day_merged),
        _sum_records(main_merged),
        _sum_records(sub_merged),
    )


def calendar_totals(now=None):
    """Sum records across all projects into (today, this-month) totals.

    Returns a `(day, month)` pair of totals dicts. Both are produced in a single
    walk of ~/.claude/projects: a second full `rglob` purely for the daily figure
    would re-stat every transcript and re-read every per-file cache entry on each
    statusline render, so we bucket each record into both accumulators as we go.

    Bucketing is per record (not file mtime): one file can hold records from more
    than one day, so each record's own date (payload index 0, `ts[:10]`) decides
    its bucket. The month bucket compares `date[:7]`; the day bucket compares the
    whole `YYYY-MM-DD`. Claude Code emits `timestamp` as a `Z`-suffixed UTC
    ISO-8601 string, so `cur_day` / `cur_month` are derived in UTC too —
    otherwise records near a midnight or month boundary fall on the wrong side.

    The month-start mtime pre-filter bounds both buckets: today lies within the
    current month, so a file untouched since before the month began cannot hold a
    current-month *or* current-day record (append always bumps mtime).

    `now` defaults to the current UTC instant; it is injectable so the
    calendar-bucketing logic can be exercised against fixed dates in tests.
    """
    if not PROJECTS_ROOT.is_dir():
        return _zero(), _zero()
    if now is None:
        now = _dt.datetime.now(_dt.timezone.utc)
    cur_month = f"{now.year:04d}-{now.month:02d}"
    cur_day = f"{now.year:04d}-{now.month:02d}-{now.day:02d}"
    month_start_ts = _dt.datetime(
        now.year, now.month, 1, tzinfo=_dt.timezone.utc
    ).timestamp()
    month_merged = {}
    day_merged = {}
    for proj in PROJECTS_ROOT.iterdir():
        if not proj.is_dir():
            continue
        for jsonl in proj.rglob("*.jsonl"):
            try:
                # Files last touched before the month start cannot contain
                # current-month records (append always bumps mtime).
                if jsonl.stat().st_mtime < month_start_ts:
                    continue
            except OSError:
                continue
            for k, v in aggregate_file(jsonl).items():
                if v[0][:7] != cur_month:
                    continue
                _keep_larger_output(month_merged, k, v)  # max-output dedup
                if v[0] == cur_day:
                    _keep_larger_output(day_merged, k, v)
    return _sum_records(day_merged), _sum_records(month_merged)


def fmt_tokens(n):
    if n >= 1_000_000:
        return f"{n / 1_000_000:.1f}M"
    if n >= 10_000:
        return f"{n // 1000}K"
    if n >= 1_000:
        return f"{n / 1_000:.1f}K"
    return str(int(n))


def fmt_ratio(read, write):
    if not write:
        return "-"
    return f"{read / write:.1f}x"


def parse_args(argv):
    """Parse the helper's CLI into (transcript, show_worktree, wt_cost_file).

    Positional arg 0 is the transcript path. `--worktree` turns on the `wt:`
    figure; `--worktree-cost-file PATH` additionally publishes the cost to
    PATH and implies `--worktree` (asking to write the file means we need it).
    """
    transcript = argv[0] if argv else ""
    show_worktree = "--worktree" in argv
    wt_cost_file = None
    if "--worktree-cost-file" in argv:
        i = argv.index("--worktree-cost-file")
        if i + 1 < len(argv):
            wt_cost_file = argv[i + 1]
    if wt_cost_file:
        show_worktree = True
    return transcript, show_worktree, wt_cost_file


def format_stats_line(sess, day, month, proj=None, proj_day=None,
                      proj_main=None, proj_sub=None, no_color=False):
    """Render the second statusline row.

    The `day:$X` figure (today's spend across all projects) always appears; when
    `proj` is given (worktree mode) the worktree-scoped figures are inserted
    ahead of it: `wt:$X` (the project's all-time spend), immediately followed by
    its `[main:$X sub:$X]` split (`proj_main` / `proj_sub`, the top-level and
    sub-agent shares of `wt:`, which sum to it), then `wtday:$X` (`proj_day`,
    today's slice of `wt:`). `proj_day` / `proj_main` / `proj_sub` each default
    to a zeroed total when omitted so callers that pass only `proj` still render.

    Distinct colours so the cost figures are tellable apart at a glance:
    bright cyan = current session (active), bright blue = this worktree's
    project all-time, dim blue = this worktree's project today plus the
    main/sub split (same hue as `wt:` to group the worktree figures; dimmer =
    the narrower / derived windows), bright white = today across all projects,
    bright magenta = calendar month across all projects. Deliberately avoiding
    red / yellow / green — those are reserved for the context-fill bar in
    statusline-command.sh (critical / warning / safe). Honour NO_COLOR for
    non-TTY consumers.
    """
    if no_color or os.environ.get("NO_COLOR"):
        sess_open = sess_close = mo_open = mo_close = wt_open = wt_close = ""
        day_open = day_close = wtd_open = wtd_close = ""
    else:
        sess_open = "\033[1;36m"   # bright cyan    — current session
        wt_open = "\033[1;34m"     # bright blue    — this worktree's project, all-time
        wtd_open = "\033[2;34m"    # dim blue       — this worktree's project, today + main/sub split
        day_open = "\033[1;37m"    # bright white   — today, all projects
        mo_open = "\033[1;35m"     # bright magenta — calendar month, all projects
        sess_close = mo_close = wt_close = day_close = wtd_close = "\033[0m"

    day_str = f"day:{day_open}${day['cost']:.2f}{day_close}"
    mo_str = f"mo:{mo_open}${month['cost']:.2f}{mo_close}"
    if proj is not None:
        # proj/proj_day/proj_main/proj_sub always travel together from main();
        # fall back to a zeroed total if a caller passes proj alone so we never
        # index None.
        pd = proj_day if proj_day is not None else _zero()
        pm = proj_main if proj_main is not None else _zero()
        ps = proj_sub if proj_sub is not None else _zero()
        wt_str = f"wt:{wt_open}${proj['cost']:.2f}{wt_close}"
        split_str = (
            f"[main:{wtd_open}${pm['cost']:.2f}{wtd_close} "
            f"sub:{wtd_open}${ps['cost']:.2f}{wtd_close}]"
        )
        wtd_str = f"wtday:{wtd_open}${pd['cost']:.2f}{wtd_close}"
        scope = f"({wt_str} {split_str} {wtd_str} {day_str} {mo_str})"
    else:
        scope = f"({day_str} {mo_str})"

    return (
        f"{sess_open}${sess['cost']:.3f}{sess_close} "
        f"{scope}  "
        f"in:{fmt_tokens(sess['in'])} out:{fmt_tokens(sess['out'])} "
        f"read:{fmt_tokens(sess['read'])} w5m:{fmt_tokens(sess['w5'])} "
        f"w1h:{fmt_tokens(sess['w1'])}  "
        f"r/5m:{fmt_ratio(sess['read'], sess['w5'])} "
        f"r/1h:{fmt_ratio(sess['read'], sess['w1'])}"
    )


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if not argv or not argv[0]:
        return 0
    transcript, show_worktree, wt_cost_file = parse_args(argv)
    try:
        sess = session_totals(transcript)
        day, month = calendar_totals()
        if show_worktree:
            proj, proj_day, proj_main, proj_sub = project_totals(transcript)
        else:
            proj = proj_day = proj_main = proj_sub = None
    except Exception as exc:  # noqa: BLE001 — never break the statusline
        print(f"stats error: {exc}", file=sys.stderr)
        return 0

    # Publish the worktree-project cost to a per-session file for on-demand
    # reading by the model (mirrors the context-usage file the statusline
    # writes). The published figure is the all-time `wt:` cost with its
    # main/sub split; the today slice (`wtday:`) is statusline-only.
    # Best-effort — a write failure must never break the statusline.
    if wt_cost_file and proj is not None:
        try:
            _atomic_write_text(
                pathlib.Path(wt_cost_file),
                f"wt_cost: ${proj['cost']:.2f} "
                f"(main: ${proj_main['cost']:.2f} sub: ${proj_sub['cost']:.2f})",
            )
        except Exception:  # noqa: BLE001 — publish is best-effort; never break the statusline
            pass

    print(format_stats_line(sess, day, month, proj, proj_day, proj_main, proj_sub))
    return 0


if __name__ == "__main__":
    sys.exit(main())
