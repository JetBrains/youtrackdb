#!/usr/bin/env python3
"""Validation runner for `.claude/scripts/session-stats.py`.

Running this script is the validation: it imports the stats helper as a module
and exercises the worktree-project additions — CLI parsing of `--worktree` /
`--worktree-cost-file`, the rendered cost line with and without the `wt:`
figure, the recursive project aggregation (sessions + sub-agents, deduped by
(msg_id, requestId)), the atomic plain-text publish file, and `main`'s
end-to-end behaviour (file written only in worktree mode, `wt:` present/absent
on the line accordingly).

Invocation (from repo root):

    python3 .claude/scripts/tests/test_session_stats.py

Exit code 0: every test case passed. Exit code 1: one or more failed; each
failure prints a clear message naming the test case.

Runner shape mirrors `.claude/scripts/tests/test_measure_read_share.py`
(stand-alone, no pytest collection, exit-code semantics, single file). Pytest
is not installed on the project's CI image; the stand-alone runner keeps the
test executable on any Python 3 host.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import sys
import tempfile
import traceback
from pathlib import Path
from typing import Callable, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT_PATH = REPO_ROOT / ".claude" / "scripts" / "session-stats.py"


# ---------------------------------------------------------------------------
# Module loader. The dash in the filename blocks `import`, so load directly.
# ---------------------------------------------------------------------------


def load_module():
    spec = importlib.util.spec_from_file_location("session_stats", str(SCRIPT_PATH))
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Failed to load module spec for {SCRIPT_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules["session_stats"] = module
    spec.loader.exec_module(module)
    return module


MODULE = load_module()

# Use the offline fallback price table exclusively so costs are deterministic
# regardless of network / on-disk LiteLLM cache state. claude-opus-4-7 in the
# fallback table is $5.00 / 1M input tokens, which the fixtures rely on.
MODULE._LIVE_PRICES = {}
MODEL = "claude-opus-4-7"
USD_PER_1M_IN = MODULE.FALLBACK_PRICES[MODEL]["in"]  # 5.0


# ---------------------------------------------------------------------------
# Fixture builders.
# ---------------------------------------------------------------------------


def _assistant_usage(
    msg_id: str,
    req_id: str,
    *,
    ts: str = "2026-06-01T12:00:00.000Z",
    model: str = MODEL,
    in_t: int = 0,
    out_t: int = 0,
    read_t: int = 0,
    w5: int = 0,
    w1: int = 0,
) -> dict:
    """An assistant record carrying a usage block, the shape session-stats reads.

    Both `message.id` and top-level `requestId` are present because the helper
    drops any record missing either (it cannot dedup it safely)."""
    usage = {
        "input_tokens": in_t,
        "output_tokens": out_t,
        "cache_read_input_tokens": read_t,
        "cache_creation": {
            "ephemeral_5m_input_tokens": w5,
            "ephemeral_1h_input_tokens": w1,
        },
    }
    return {
        "type": "assistant",
        "timestamp": ts,
        "requestId": req_id,
        "message": {"id": msg_id, "model": model, "usage": usage},
    }


def write_jsonl(path: Path, records: List[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        for rec in records:
            fh.write(json.dumps(rec) + "\n")


def _totals(cost: float, **toks) -> dict:
    """A totals dict shaped like `_zero()` with a chosen cost + token fields."""
    base = MODULE._zero()
    base["cost"] = cost
    base.update(toks)
    return base


@contextlib.contextmanager
def _patched(**attrs):
    """Temporarily set MODULE attributes (e.g. CACHE_DIR, totals fns), restore."""
    saved = {k: getattr(MODULE, k) for k in attrs}
    try:
        for k, v in attrs.items():
            setattr(MODULE, k, v)
        yield
    finally:
        for k, v in saved.items():
            setattr(MODULE, k, v)


@contextlib.contextmanager
def _env(name: str, value: Optional[str]):
    """Temporarily set (value != None) or unset (value is None) an env var."""
    had = name in os.environ
    prev = os.environ.get(name)
    try:
        if value is None:
            os.environ.pop(name, None)
        else:
            os.environ[name] = value
        yield
    finally:
        if had:
            os.environ[name] = prev  # type: ignore[arg-type]
        else:
            os.environ.pop(name, None)


def _approx(a: float, b: float, tol: float = 1e-9) -> bool:
    return abs(a - b) < tol


# ---------------------------------------------------------------------------
# Test runner.
# ---------------------------------------------------------------------------


_FAILURES: List[Tuple[str, str]] = []


def run_test(name: str, fn: Callable[[], None]) -> None:
    try:
        fn()
        print(f"  PASS  {name}")
    except AssertionError as exc:
        print(f"  FAIL  {name}: {exc}", file=sys.stderr)
        _FAILURES.append((name, str(exc)))
    except Exception:
        tb = traceback.format_exc()
        print(f"  ERROR {name}:\n{tb}", file=sys.stderr)
        _FAILURES.append((name, tb))


# ---------------------------------------------------------------------------
# parse_args.
# ---------------------------------------------------------------------------


def test_parse_args_plain() -> None:
    """No flags: worktree off, no cost file."""
    transcript, show, wt_file = MODULE.parse_args(["t.jsonl"])
    assert transcript == "t.jsonl", transcript
    assert show is False and wt_file is None, (show, wt_file)


def test_parse_args_worktree_flag() -> None:
    """`--worktree` turns the figure on without requesting a file."""
    _, show, wt_file = MODULE.parse_args(["t.jsonl", "--worktree"])
    assert show is True and wt_file is None, (show, wt_file)


def test_parse_args_cost_file_implies_worktree() -> None:
    """`--worktree-cost-file PATH` captures the path and implies `--worktree`."""
    _, show, wt_file = MODULE.parse_args(
        ["t.jsonl", "--worktree-cost-file", "/tmp/wt.txt"]
    )
    assert show is True, "cost file should imply worktree display"
    assert wt_file == "/tmp/wt.txt", wt_file


def test_parse_args_cost_file_missing_value() -> None:
    """A trailing `--worktree-cost-file` with no path does not crash or set it."""
    _, show, wt_file = MODULE.parse_args(["t.jsonl", "--worktree-cost-file"])
    assert wt_file is None, wt_file
    assert show is False, show


# ---------------------------------------------------------------------------
# format_stats_line.
# ---------------------------------------------------------------------------


def test_format_line_no_worktree_exact() -> None:
    """Without a project total the line shows `(day:$… mo:$…)` — today's spend
    immediately before the month figure, no worktree figure."""
    sess = _totals(0.123, **{"in": 1200, "out": 8000, "read": 230000, "w5": 40000, "w1": 0})
    day = _totals(2.34)
    month = _totals(4.56)
    line = MODULE.format_stats_line(sess, day, month, proj=None, no_color=True)
    expected = (
        "$0.123 (day:$2.34 mo:$4.56)  in:1.2K out:8.0K read:230K w5m:40K w1h:0  "
        "r/5m:5.8x r/1h:-"
    )
    assert line == expected, f"\n got: {line}\nwant: {expected}"


def test_format_line_with_worktree_exact() -> None:
    """A project total inserts `wt:$…` at the front of the parenthetical, ahead
    of the always-present `day:$…` and the `mo:$…` figure."""
    sess = _totals(0.123, **{"in": 1200, "out": 8000, "read": 230000, "w5": 40000, "w1": 0})
    day = _totals(2.34)
    month = _totals(4.56)
    proj = _totals(1.85)
    line = MODULE.format_stats_line(sess, day, month, proj=proj, no_color=True)
    expected = (
        "$0.123 (wt:$1.85 day:$2.34 mo:$4.56)  in:1.2K out:8.0K read:230K w5m:40K w1h:0  "
        "r/5m:5.8x r/1h:-"
    )
    assert line == expected, f"\n got: {line}\nwant: {expected}"


def test_format_line_no_color_has_no_ansi() -> None:
    """no_color=True strips every ANSI escape even if NO_COLOR is unset."""
    with _env("NO_COLOR", None):
        line = MODULE.format_stats_line(
            _totals(1.0), _totals(2.0), _totals(3.0), proj=_totals(4.0), no_color=True
        )
    assert "\033" not in line, "expected no ANSI escapes under no_color"


def test_format_line_colours_when_enabled() -> None:
    """With colour on, the worktree figure uses the distinct bright-blue code
    (34), the new today figure uses bright white (37), and the existing
    session/month codes are still present."""
    with _env("NO_COLOR", None):
        line = MODULE.format_stats_line(
            _totals(1.0), _totals(2.0), _totals(3.0), proj=_totals(4.0), no_color=False
        )
    assert "\033[1;34m" in line, "worktree figure should be bright blue"
    assert "\033[1;36m" in line, "session figure should be bright cyan"
    assert "\033[1;37m" in line, "today figure should be bright white"
    assert "\033[1;35m" in line, "month figure should be bright magenta"


def test_format_line_no_color_env_respected() -> None:
    """NO_COLOR in the environment disables colour even with no_color=False."""
    with _env("NO_COLOR", "1"):
        line = MODULE.format_stats_line(
            _totals(1.0), _totals(2.0), _totals(3.0), proj=_totals(4.0), no_color=False
        )
    assert "\033" not in line, "NO_COLOR env should strip ANSI"


# ---------------------------------------------------------------------------
# project_totals.
# ---------------------------------------------------------------------------


def test_project_totals_aggregates_sessions_and_subagents() -> None:
    """Every *.jsonl under the project dir is summed — top-level session files
    and nested subagents/agent-*.jsonl — with (msg_id, requestId) dedup so a
    record duplicated into a sub-agent transcript is counted once."""
    with tempfile.TemporaryDirectory() as tmp:
        cache = Path(tmp) / "cache"
        proj = Path(tmp) / "project"
        # Session A and a record duplicated into its sub-agent transcript.
        write_jsonl(proj / "sessionA.jsonl", [_assistant_usage("m1", "r1", in_t=1_000_000)])
        write_jsonl(proj / "sessionB.jsonl", [_assistant_usage("m2", "r2", in_t=1_000_000)])
        write_jsonl(
            proj / "sessionA" / "subagents" / "agent-1.jsonl",
            [
                _assistant_usage("m1", "r1", in_t=1_000_000),  # duplicate of A
                _assistant_usage("m3", "r3", in_t=1_000_000),  # unique
            ],
        )
        with _patched(CACHE_DIR=cache):
            out = MODULE.project_totals(str(proj / "sessionA.jsonl"))
        # m1, m2, m3 distinct -> 3 * 1M input tokens.
        assert out["in"] == 3_000_000, out["in"]
        assert _approx(out["cost"], 3 * USD_PER_1M_IN), out["cost"]


def test_project_totals_missing_dir_is_zero() -> None:
    """A transcript path whose parent is not a directory yields zeroed totals."""
    out = MODULE.project_totals("/no/such/dir/x.jsonl")
    assert out == MODULE._zero(), out


def test_project_totals_nonexistent_transcript_does_not_scan_parent() -> None:
    """A non-existent transcript whose *parent* directory exists must yield zero
    without recursively scanning that parent. Regression for a footgun where
    project_totals only checked parent.is_dir(): a path like /tmp/nonexistent.jsonl
    would have rglob'd all of /tmp. A decoy .jsonl carrying real usage sits in the
    parent; if the parent were scanned the total would be non-zero, so asserting
    zero proves the parent was never walked."""
    with tempfile.TemporaryDirectory() as tmp:
        cache = Path(tmp) / "cache"
        proj = Path(tmp) / "project"
        proj.mkdir()
        write_jsonl(proj / "decoy.jsonl", [_assistant_usage("m1", "r1", in_t=1_000_000)])
        with _patched(CACHE_DIR=cache):
            out = MODULE.project_totals(str(proj / "nonexistent.jsonl"))
        assert out == MODULE._zero(), out


def test_streaming_snapshots_in_one_file_keep_max_output() -> None:
    """Three records sharing one (msg_id, requestId) — the streaming snapshot
    sequence output_tokens = 1, 1, 276 — must collapse to the largest (276), the
    completed turn, not the first partial (1) and not their sum (278). A second,
    distinct turn (output_tokens = 100) confirms unrelated keys are untouched.
    Regression for first-occurrence dedup, which kept a mid-stream partial and
    undercounted output — the priciest token."""
    with tempfile.TemporaryDirectory() as tmp:
        cache = Path(tmp) / "cache"
        proj = Path(tmp) / "project"
        write_jsonl(
            proj / "session.jsonl",
            [
                _assistant_usage("m1", "r1", out_t=1),
                _assistant_usage("m1", "r1", out_t=1),
                _assistant_usage("m1", "r1", out_t=276),
                _assistant_usage("m2", "r2", out_t=100),
            ],
        )
        with _patched(CACHE_DIR=cache):
            out = MODULE.project_totals(str(proj / "session.jsonl"))
        # m1 -> 276 (final snapshot), m2 -> 100; first-wins would give 101, no
        # dedup would give 378.
        assert out["out"] == 376, out["out"]
        out_price = MODULE.FALLBACK_PRICES[MODEL]["out"]  # $25 / 1M
        assert _approx(out["cost"], 376 * out_price / 1_000_000), out["cost"]


def test_streaming_final_snapshot_wins_across_cache_reads() -> None:
    """A streaming turn's partial snapshot can be consumed in one render and its
    final snapshot in a later render, because the incremental byte-offset cache
    splits them: aggregate_file persists the partial, then re-reads only the
    appended bytes next time. The final (larger-output) snapshot must win on the
    second read. Regression for first-occurrence dedup, which locked in the
    cached partial and ignored the completed count forever."""
    with tempfile.TemporaryDirectory() as tmp:
        cache = Path(tmp) / "cache"
        f = Path(tmp) / "session.jsonl"
        # Render 1: only the partial snapshot exists yet.
        write_jsonl(f, [_assistant_usage("m1", "r1", out_t=1)])
        with _patched(CACHE_DIR=cache):
            recs1 = MODULE.aggregate_file(f)
            assert recs1["m1:r1"][3] == 1, recs1["m1:r1"]  # payload[3] = output
            # Render 2: the final snapshot is appended (file grows), so the
            # next aggregate_file reads only the new line and merges it in.
            with f.open("a", encoding="utf-8") as fh:
                fh.write(json.dumps(_assistant_usage("m1", "r1", out_t=276)) + "\n")
            recs2 = MODULE.aggregate_file(f)
        assert recs2["m1:r1"][3] == 276, recs2["m1:r1"]


# ---------------------------------------------------------------------------
# calendar_totals.
# ---------------------------------------------------------------------------


def test_calendar_totals_buckets_today_and_month() -> None:
    """calendar_totals returns (today, this-month) totals in a single walk,
    bucketing per record by its own date (not file mtime). With a fixed `now` of
    2026-06-15 and three records in one file — dated today, earlier this month,
    and a prior month — the day bucket gets only today's record while the month
    bucket gets today + the earlier-this-month record (prior month excluded).
    The file's mtime is forced past the month-start so the pre-filter, which is
    keyed off the injected month start, never drops it regardless of run time."""
    import datetime as dt

    now = dt.datetime(2026, 6, 15, 12, 0, 0, tzinfo=dt.timezone.utc)
    with tempfile.TemporaryDirectory() as tmp:
        cache = Path(tmp) / "cache"
        projects = Path(tmp) / "projects"
        session = projects / "proj-a" / "session.jsonl"
        write_jsonl(
            session,
            [
                _assistant_usage("m1", "r1", ts="2026-06-15T12:00:00.000Z", in_t=1_000_000),
                _assistant_usage("m2", "r2", ts="2026-06-03T09:00:00.000Z", in_t=1_000_000),
                _assistant_usage("m3", "r3", ts="2026-05-20T09:00:00.000Z", in_t=1_000_000),
            ],
        )
        # Pin mtime just after the injected month start so the mtime pre-filter
        # (st_mtime < month_start_ts) keeps the file no matter when this runs.
        month_start_ts = dt.datetime(2026, 6, 1, tzinfo=dt.timezone.utc).timestamp()
        os.utime(session, (month_start_ts + 10, month_start_ts + 10))
        with _patched(CACHE_DIR=cache, PROJECTS_ROOT=projects):
            day, month = MODULE.calendar_totals(now=now)
        # Day bucket: only the 2026-06-15 record.
        assert day["in"] == 1_000_000, day["in"]
        assert _approx(day["cost"], 1 * USD_PER_1M_IN), day["cost"]
        # Month bucket: 2026-06-15 + 2026-06-03; the 2026-05-20 record is excluded.
        assert month["in"] == 2_000_000, month["in"]
        assert _approx(month["cost"], 2 * USD_PER_1M_IN), month["cost"]


def test_calendar_totals_missing_projects_root_is_zero_pair() -> None:
    """When the projects root is not a directory, calendar_totals yields a pair
    of zeroed totals rather than raising — the statusline must never break."""
    with tempfile.TemporaryDirectory() as tmp:
        missing = Path(tmp) / "does-not-exist"
        with _patched(PROJECTS_ROOT=missing):
            day, month = MODULE.calendar_totals()
        assert day == MODULE._zero(), day
        assert month == MODULE._zero(), month


# ---------------------------------------------------------------------------
# _atomic_write_text.
# ---------------------------------------------------------------------------


def test_atomic_write_text_writes_and_overwrites() -> None:
    """The helper writes the exact bytes and overwrites a pre-existing file."""
    with tempfile.TemporaryDirectory() as tmp:
        target = Path(tmp) / "wt.txt"
        MODULE._atomic_write_text(target, "wt_cost: $1.85")
        assert target.read_text() == "wt_cost: $1.85"
        MODULE._atomic_write_text(target, "wt_cost: $2.00")
        assert target.read_text() == "wt_cost: $2.00"
        # No stray per-pid temp file left behind.
        leftovers = [p.name for p in Path(tmp).iterdir() if p.name != "wt.txt"]
        assert leftovers == [], leftovers


# ---------------------------------------------------------------------------
# main (orchestration). Totals functions are stubbed for determinism / speed.
# ---------------------------------------------------------------------------


def test_main_worktree_writes_cost_file_and_shows_wt() -> None:
    """`main` in worktree mode prints the `wt:` and `day:` figures and publishes
    the cost file containing exactly `wt_cost: $<proj cost>`. `calendar_totals`
    is stubbed to return the (today, month) pair main now unpacks."""
    with tempfile.TemporaryDirectory() as tmp:
        wt_file = Path(tmp) / "claude-code-worktree-cost-1234.txt"
        buf = io.StringIO()
        with _patched(
            session_totals=lambda _t: _totals(0.123),
            calendar_totals=lambda: (_totals(2.34), _totals(4.56)),
            project_totals=lambda _t: _totals(1.85),
        ), _env("NO_COLOR", "1"), contextlib.redirect_stdout(buf):
            rc = MODULE.main(["t.jsonl", "--worktree", "--worktree-cost-file", str(wt_file)])
        assert rc == 0, rc
        assert wt_file.read_text() == "wt_cost: $1.85", wt_file.read_text()
        assert "wt:$1.85" in buf.getvalue(), buf.getvalue()
        assert "day:$2.34" in buf.getvalue(), buf.getvalue()


def test_main_no_worktree_omits_wt_and_writes_no_file() -> None:
    """Without worktree flags `main` omits `wt:`, still shows the always-present
    `day:` figure, and writes no publish file."""
    with tempfile.TemporaryDirectory() as tmp:
        wt_file = Path(tmp) / "should-not-exist.txt"
        buf = io.StringIO()
        with _patched(
            session_totals=lambda _t: _totals(0.123),
            calendar_totals=lambda: (_totals(2.34), _totals(4.56)),
            project_totals=lambda _t: _totals(1.85),
        ), _env("NO_COLOR", "1"), contextlib.redirect_stdout(buf):
            rc = MODULE.main(["t.jsonl"])
        assert rc == 0, rc
        assert "wt:" not in buf.getvalue(), buf.getvalue()
        assert "day:$2.34" in buf.getvalue(), buf.getvalue()
        assert not wt_file.exists(), "no cost file should be written without the flag"


def test_main_no_transcript_arg_is_noop() -> None:
    """Empty / missing transcript arg exits 0 with no output."""
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        assert MODULE.main([]) == 0
        assert MODULE.main([""]) == 0
    assert buf.getvalue() == "", repr(buf.getvalue())


# ---------------------------------------------------------------------------
# Runner.
# ---------------------------------------------------------------------------


def main() -> int:
    tests: List[Tuple[str, Callable[[], None]]] = [
        ("parse_args plain", test_parse_args_plain),
        ("parse_args --worktree", test_parse_args_worktree_flag),
        ("parse_args cost-file implies worktree", test_parse_args_cost_file_implies_worktree),
        ("parse_args cost-file missing value", test_parse_args_cost_file_missing_value),
        ("format line no worktree (exact)", test_format_line_no_worktree_exact),
        ("format line with worktree (exact)", test_format_line_with_worktree_exact),
        ("format line no_color strips ANSI", test_format_line_no_color_has_no_ansi),
        ("format line colours when enabled", test_format_line_colours_when_enabled),
        ("format line NO_COLOR env respected", test_format_line_no_color_env_respected),
        ("project_totals sessions + subagents deduped", test_project_totals_aggregates_sessions_and_subagents),
        ("project_totals missing dir -> zero", test_project_totals_missing_dir_is_zero),
        ("project_totals nonexistent file w/ existing parent -> zero", test_project_totals_nonexistent_transcript_does_not_scan_parent),
        ("streaming snapshots in one file keep max output", test_streaming_snapshots_in_one_file_keep_max_output),
        ("streaming final snapshot wins across cache reads", test_streaming_final_snapshot_wins_across_cache_reads),
        ("calendar_totals buckets today + month", test_calendar_totals_buckets_today_and_month),
        ("calendar_totals missing projects root -> zero pair", test_calendar_totals_missing_projects_root_is_zero_pair),
        ("atomic_write_text writes + overwrites", test_atomic_write_text_writes_and_overwrites),
        ("main worktree writes file + shows wt", test_main_worktree_writes_cost_file_and_shows_wt),
        ("main no worktree omits wt + no file", test_main_no_worktree_omits_wt_and_writes_no_file),
        ("main no transcript arg is no-op", test_main_no_transcript_arg_is_noop),
    ]
    print(f"Running {len(tests)} test(s) for session-stats.py\n")
    for name, fn in tests:
        run_test(name, fn)
    print()
    if _FAILURES:
        print(f"FAILED — {len(_FAILURES)} test(s) failed:", file=sys.stderr)
        for name, _ in _FAILURES:
            print(f"  - {name}", file=sys.stderr)
        return 1
    print(f"OK — {len(tests)} test(s) passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
