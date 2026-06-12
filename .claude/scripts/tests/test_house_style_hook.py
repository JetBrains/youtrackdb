#!/usr/bin/env python3
"""Validation runner for `.claude/hooks/house-style-write-reminder.sh`.

Running this script is the validation: it shells out to the hook with
synthesised hook-input JSON on stdin and asserts the parsed JSON output
matches expectations for tier matching, silent extensions, rate-limit
semantics, mixed-tier concatenation, blacklist coverage, malformed
input handling, captured-fixture replay, jq fallback, concurrency, and
the §1.5 anchor-drift guard.

Invocation (from repo root):

    python3 .claude/scripts/tests/test_house_style_hook.py

Exit code 0: every test case passed. Exit code 1: one or more failed;
each failure prints a clear message naming the test case + actual vs
expected.

Runner shape mirrors `.claude/scripts/tests/test_dsc_ai_tell.py`
(stand-alone, no pytest collection, exit-code semantics, single-file).

Per-test isolation:
- Each test wraps `subprocess.run` in `time.perf_counter()` and asserts
  elapsed ≤ 3 seconds (2 s headroom against the hook's 5 s production
  timeout; see Validation and Acceptance section of the track file).
- Each test points `TMPDIR` at a per-test `tempfile.TemporaryDirectory()`
  so state files at `${TMPDIR}/house-style-reminder-${session_id}.txt`
  do not leak between tests.
- The hook is invoked as `bash <hook>` so a missing executable bit on a
  fresh checkout cannot mask a real failure (the chmod 755 is defense
  in depth, not the only access path).
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parents[3]
HOOK = REPO_ROOT / ".claude" / "hooks" / "house-style-write-reminder.sh"
HOUSE_STYLE_MD = REPO_ROOT / ".claude" / "output-styles" / "house-style.md"
FIXTURES_DIR = REPO_ROOT / ".claude" / "scripts" / "tests" / "fixtures"

# Stable prefixes that uniquely identify the Tier-A and Tier-B reminder
# bodies emitted by the hook. The hook commits to these prefixes verbatim
# (see `.claude/hooks/house-style-write-reminder.sh` stage 7) so the test
# can pin a substring without re-asserting the full body each time. If
# the prefix drifts, the hook intent changed and the test must be
# updated alongside the hook.
TIER_A_PREFIX = "House style applies to this Markdown surface."
TIER_B_PREFIX = (
    "House style AI-tell subset applies to code comments and Javadoc "
    "on this Java/Kotlin surface."
)

# Hard wall-clock budget per hook invocation, from the track file's
# Validation and Acceptance "Hook latency" bullet.
TIME_BUDGET_S = 3.0

# Subprocess timeout — gives `subprocess.run` enough wall time to surface
# a hang as a TimeoutExpired rather than the test wall budget assertion
# triggering an opaque "elapsed > 3 s" message. The assertion below
# remains the load-bearing latency check.
SUBPROCESS_TIMEOUT_S = 10


def run_hook(
    stdin_json: str,
    tmpdir: str,
    cwd: Optional[str] = None,
    path_env: Optional[str] = None,
) -> Tuple[subprocess.CompletedProcess, float]:
    """Invoke the hook script with the given stdin payload.

    Returns (CompletedProcess, elapsed_seconds). Raises SystemExit on
    timeout — a hung hook is always a test failure, never a transient
    flake worth retrying inside the runner.
    """
    env = os.environ.copy()
    env["TMPDIR"] = tmpdir
    if path_env is not None:
        env["PATH"] = path_env
    start = time.perf_counter()
    try:
        result = subprocess.run(
            ["bash", str(HOOK)],
            input=stdin_json,
            capture_output=True,
            text=True,
            env=env,
            cwd=cwd,
            timeout=SUBPROCESS_TIMEOUT_S,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise SystemExit(
            f"Hook timed out after {SUBPROCESS_TIMEOUT_S}s on stdin: "
            f"{stdin_json[:200]}"
        ) from exc
    elapsed = time.perf_counter() - start
    return result, elapsed


def parse_hook_output(stdout: str) -> Optional[str]:
    """Parse hook stdout JSON and return additionalContext (or None).

    The hook may legitimately emit empty stdout on the silent path (no
    paths to remind about, blacklisted, rate-limited, malformed input).
    Empty stdout yields `None` so callers can distinguish "silent" from
    "fired with body B" cleanly.
    """
    stdout = stdout.strip()
    if not stdout:
        return None
    try:
        payload = json.loads(stdout)
    except json.JSONDecodeError as exc:
        raise AssertionError(
            f"Hook produced unparseable JSON: {exc}\nstdout: {stdout[:400]}"
        ) from exc
    return payload["hookSpecificOutput"]["additionalContext"]


def make_input(
    *,
    session_id: str,
    tool_name: str,
    file_path: Optional[str] = None,
    hunks: Optional[List[Dict[str, str]]] = None,
    extra: Optional[Dict[str, Any]] = None,
) -> str:
    """Build a hook-input JSON string mirroring the live PreToolUse shape."""
    tool_input: Dict[str, Any] = {}
    if file_path is not None:
        tool_input["file_path"] = file_path
    if hunks is not None:
        tool_input["hunks"] = hunks
    if extra is not None:
        tool_input.update(extra)
    payload = {
        "session_id": session_id,
        "transcript_path": f"/tmp/transcript-{session_id}.jsonl",
        "cwd": str(REPO_ROOT),
        "hook_event_name": "PreToolUse",
        "tool_name": tool_name,
        "tool_input": tool_input,
    }
    return json.dumps(payload)


def assert_silent(result: subprocess.CompletedProcess, label: str) -> List[str]:
    """Common assertion bundle for silent-path test cases."""
    failures: List[str] = []
    if result.returncode != 0:
        failures.append(
            f"{label}: expected exit 0, got {result.returncode}; "
            f"stderr={result.stderr[:200]!r}"
        )
    body = parse_hook_output(result.stdout) if result.stdout.strip() else None
    if body:
        failures.append(
            f"{label}: expected empty additionalContext, got "
            f"{body[:200]!r}"
        )
    return failures


def assert_within_budget(elapsed: float, label: str) -> List[str]:
    if elapsed > TIME_BUDGET_S:
        return [
            f"{label}: hook elapsed {elapsed:.3f}s > {TIME_BUDGET_S}s budget"
        ]
    return []


# ---------------------------------------------------------------------------
# Test cases
# ---------------------------------------------------------------------------
#
# Each test_NN function returns a list of failure strings (empty on pass).
# `main` aggregates failures and exits non-zero if any list is non-empty.
# Test numbering matches the 16-case roster the hook contract is built
# against (see `.claude/hooks/house-style-write-reminder.sh` header
# comment for the pipeline this exercises).


def test_01_tier_a_markdown() -> List[str]:
    """Tier-A markdown path → Tier-A reminder fires."""
    label = "test_01_tier_a_markdown"
    with tempfile.TemporaryDirectory() as tmpdir:
        payload = make_input(
            session_id="t01",
            tool_name="Write",
            file_path="/tmp/sample.md",
        )
        result, elapsed = run_hook(payload, tmpdir)
        failures = assert_within_budget(elapsed, label)
        body = parse_hook_output(result.stdout)
        if body is None or not body.startswith(TIER_A_PREFIX):
            failures.append(
                f"{label}: expected Tier-A reminder, got {body!r}"
            )
        if body and TIER_B_PREFIX in body:
            failures.append(
                f"{label}: Tier-B body leaked into Tier-A-only response"
            )
        return failures


def test_02_tier_b_java() -> List[str]:
    """Tier-B Java path → Tier-B reminder fires."""
    label = "test_02_tier_b_java"
    with tempfile.TemporaryDirectory() as tmpdir:
        payload = make_input(
            session_id="t02",
            tool_name="Edit",
            file_path="/tmp/Sample.java",
        )
        result, elapsed = run_hook(payload, tmpdir)
        failures = assert_within_budget(elapsed, label)
        body = parse_hook_output(result.stdout)
        if body is None or not body.startswith(TIER_B_PREFIX):
            failures.append(
                f"{label}: expected Tier-B reminder, got {body!r}"
            )
        if body and TIER_A_PREFIX in body:
            failures.append(
                f"{label}: Tier-A body leaked into Tier-B-only response"
            )
        return failures


def test_03_tier_b_kotlin() -> List[str]:
    """Tier-B Kotlin path → Tier-B reminder fires."""
    label = "test_03_tier_b_kotlin"
    with tempfile.TemporaryDirectory() as tmpdir:
        payload = make_input(
            session_id="t03",
            tool_name="Write",
            file_path="/tmp/Sample.kt",
        )
        result, elapsed = run_hook(payload, tmpdir)
        failures = assert_within_budget(elapsed, label)
        body = parse_hook_output(result.stdout)
        if body is None or not body.startswith(TIER_B_PREFIX):
            failures.append(
                f"{label}: expected Tier-B reminder on .kt, got {body!r}"
            )
        return failures


def test_04_silent_extensions() -> List[str]:
    """Silent extensions (.xml, Dockerfile, bare name) → exit 0, no body."""
    label = "test_04_silent_extensions"
    failures: List[str] = []
    cases = [
        ("/tmp/build.xml", ".xml"),
        ("/tmp/Dockerfile", "Dockerfile (no extension)"),
        ("/tmp/README", "bare name"),
    ]
    for idx, (path, desc) in enumerate(cases):
        with tempfile.TemporaryDirectory() as tmpdir:
            payload = make_input(
                session_id=f"t04-{idx}",
                tool_name="Write",
                file_path=path,
            )
            result, elapsed = run_hook(payload, tmpdir)
            failures.extend(assert_within_budget(elapsed, f"{label} [{desc}]"))
            failures.extend(assert_silent(result, f"{label} [{desc}]"))
    return failures


def test_05_rate_limit_same_tier_same_session() -> List[str]:
    """Same-tier-same-session second invocation → silent (rate-limit)."""
    label = "test_05_rate_limit_same_tier_same_session"
    failures: List[str] = []
    with tempfile.TemporaryDirectory() as tmpdir:
        # First invocation — Tier-A fires.
        first = make_input(
            session_id="t05",
            tool_name="Write",
            file_path="/tmp/first.md",
        )
        result1, elapsed1 = run_hook(first, tmpdir)
        failures.extend(assert_within_budget(elapsed1, f"{label} [first]"))
        body1 = parse_hook_output(result1.stdout)
        if body1 is None or not body1.startswith(TIER_A_PREFIX):
            failures.append(
                f"{label}: first call expected Tier-A reminder, got {body1!r}"
            )
        # Second invocation, same session, same tier — must stay silent.
        second = make_input(
            session_id="t05",
            tool_name="Edit",
            file_path="/tmp/second.md",
        )
        result2, elapsed2 = run_hook(second, tmpdir)
        failures.extend(assert_within_budget(elapsed2, f"{label} [second]"))
        failures.extend(
            assert_silent(result2, f"{label} [second (rate-limited)]")
        )
    return failures


def test_06_fresh_session_after_clear() -> List[str]:
    """Fresh `session_id` post-/clear → Tier-A reminder fires again."""
    label = "test_06_fresh_session_after_clear"
    failures: List[str] = []
    with tempfile.TemporaryDirectory() as tmpdir:
        first = make_input(
            session_id="t06-old",
            tool_name="Write",
            file_path="/tmp/before-clear.md",
        )
        run_hook(first, tmpdir)
        # New session_id simulates /clear creating a fresh logical session
        # — the state file at house-style-reminder-${session_id}.txt is
        # keyed by session_id, so a new session must observe empty state.
        second = make_input(
            session_id="t06-new",
            tool_name="Write",
            file_path="/tmp/after-clear.md",
        )
        result, elapsed = run_hook(second, tmpdir)
        failures.extend(assert_within_budget(elapsed, label))
        body = parse_hook_output(result.stdout)
        if body is None or not body.startswith(TIER_A_PREFIX):
            failures.append(
                f"{label}: fresh-session reminder must fire, got {body!r}"
            )
    return failures


def test_07_cross_tier_same_session_sequential() -> List[str]:
    """Cross-tier same-session sequential → each tier fires once."""
    label = "test_07_cross_tier_same_session_sequential"
    failures: List[str] = []
    with tempfile.TemporaryDirectory() as tmpdir:
        # Sequence: Tier-A, then Tier-B, then Tier-A again (silent), then
        # Tier-B again (silent). Confirms the state file accumulates one
        # letter per tier and rate-limits per tier independently.
        seq: List[Tuple[str, str, str, Optional[str]]] = [
            ("Write", "/tmp/a.md", "Tier-A first fire", TIER_A_PREFIX),
            ("Edit", "/tmp/B.java", "Tier-B first fire", TIER_B_PREFIX),
            ("Write", "/tmp/a2.md", "Tier-A repeat (silent)", None),
            ("Edit", "/tmp/Bx.kt", "Tier-B repeat (silent)", None),
        ]
        for tool_name, path, sub_label, expected_prefix in seq:
            payload = make_input(
                session_id="t07",
                tool_name=tool_name,
                file_path=path,
            )
            result, elapsed = run_hook(payload, tmpdir)
            failures.extend(
                assert_within_budget(elapsed, f"{label} [{sub_label}]")
            )
            body = parse_hook_output(result.stdout)
            if expected_prefix is None:
                if body:
                    failures.append(
                        f"{label} [{sub_label}]: expected silent, got "
                        f"{body[:120]!r}"
                    )
            else:
                if body is None or not body.startswith(expected_prefix):
                    failures.append(
                        f"{label} [{sub_label}]: expected reminder "
                        f"prefix {expected_prefix!r}, got {body!r}"
                    )
    return failures


def test_08_mixed_tier_apply_patch() -> List[str]:
    """Mixed-tier apply-patch (.md + .java) → both bodies concatenated."""
    label = "test_08_mixed_tier_apply_patch"
    failures: List[str] = []
    with tempfile.TemporaryDirectory() as tmpdir:
        payload = make_input(
            session_id="t08",
            tool_name="mcp__localhost-6315__steroid_apply_patch",
            hunks=[
                {
                    "file_path": "/tmp/mixed.md",
                    "old_string": "old",
                    "new_string": "new",
                },
                {
                    "file_path": "/tmp/Mixed.java",
                    "old_string": "old",
                    "new_string": "new",
                },
            ],
        )
        result, elapsed = run_hook(payload, tmpdir)
        failures.extend(assert_within_budget(elapsed, label))
        body = parse_hook_output(result.stdout)
        if body is None:
            failures.append(f"{label}: expected concatenated reminder, got None")
            return failures
        if not body.startswith(TIER_A_PREFIX):
            failures.append(
                f"{label}: concatenated reminder must lead with Tier-A; "
                f"got prefix {body[:80]!r}"
            )
        if TIER_B_PREFIX not in body:
            failures.append(
                f"{label}: Tier-B body missing from concatenated reminder"
            )
        a_index = body.find(TIER_A_PREFIX)
        b_index = body.find(TIER_B_PREFIX)
        if a_index >= 0 and b_index >= 0 and not (a_index < b_index):
            failures.append(
                f"{label}: Tier-A must precede Tier-B in concatenation"
            )
    return failures


def test_09_apply_patch_md_plus_silent_extension() -> List[str]:
    """Mixed-tier apply-patch with silent extension (.md + .xml) → Tier A only."""
    label = "test_09_apply_patch_md_plus_silent_extension"
    failures: List[str] = []
    with tempfile.TemporaryDirectory() as tmpdir:
        payload = make_input(
            session_id="t09",
            tool_name="mcp__localhost-6315__steroid_apply_patch",
            hunks=[
                {
                    "file_path": "/tmp/notes.md",
                    "old_string": "x",
                    "new_string": "y",
                },
                {
                    "file_path": "/tmp/build.xml",
                    "old_string": "x",
                    "new_string": "y",
                },
            ],
        )
        result, elapsed = run_hook(payload, tmpdir)
        failures.extend(assert_within_budget(elapsed, label))
        body = parse_hook_output(result.stdout)
        if body is None or not body.startswith(TIER_A_PREFIX):
            failures.append(
                f"{label}: expected Tier-A only, got {body!r}"
            )
        if body and TIER_B_PREFIX in body:
            failures.append(
                f"{label}: Tier-B body leaked despite no Java/Kotlin hunk"
            )
    return failures


# The four hardcoded blacklist entries — see house-style-write-reminder.sh
# stage 5. Each is tested under three input forms (absolute, basename,
# repo-relative) to confirm realpath-normalisation fires before the
# suffix match. The basename form is silenced only when the agent's
# CWD is the file's parent directory (the hook's realpath normalises a
# bare basename against the current working directory); the test sets
# `cwd=` accordingly.
BLACKLISTED_FILES = [
    ".claude/output-styles/house-style.md",
    ".claude/skills/ai-tells/SKILL.md",
    ".claude/scripts/design-mechanical-checks.py",
    ".claude/scripts/tests/test_dsc_ai_tell.py",
]


def test_10_blacklist_three_path_forms() -> List[str]:
    """Each blacklisted file silent under absolute, basename, and repo-relative form."""
    label = "test_10_blacklist_three_path_forms"
    failures: List[str] = []
    for idx, rel in enumerate(BLACKLISTED_FILES):
        abs_path = REPO_ROOT / rel
        basename = abs_path.name
        parent_dir = str(abs_path.parent)
        forms = [
            ("absolute", str(abs_path), None),
            # The basename probe needs cwd == parent dir so realpath -m
            # resolves the basename to the blacklisted absolute path.
            ("basename", basename, parent_dir),
            ("repo-relative", rel, str(REPO_ROOT)),
        ]
        for form_name, form_path, cwd in forms:
            with tempfile.TemporaryDirectory() as tmpdir:
                payload = make_input(
                    session_id=f"t10-{idx}-{form_name}",
                    tool_name="Write",
                    file_path=form_path,
                )
                result, elapsed = run_hook(payload, tmpdir, cwd=cwd)
                sub_label = f"{label} [{rel} / {form_name}]"
                failures.extend(assert_within_budget(elapsed, sub_label))
                failures.extend(assert_silent(result, sub_label))
    return failures


def test_11_empty_hunks_array() -> List[str]:
    """Empty `hunks: []` array → silent (no fabricated reminder)."""
    label = "test_11_empty_hunks_array"
    failures: List[str] = []
    with tempfile.TemporaryDirectory() as tmpdir:
        payload = make_input(
            session_id="t11",
            tool_name="mcp__localhost-6315__steroid_apply_patch",
            hunks=[],
        )
        result, elapsed = run_hook(payload, tmpdir)
        failures.extend(assert_within_budget(elapsed, label))
        failures.extend(assert_silent(result, label))
    return failures


def test_12_malformed_input_shapes() -> List[str]:
    """Malformed inputs (null file_path, missing tool_input, non-JSON) → silent."""
    label = "test_12_malformed_input_shapes"
    failures: List[str] = []
    cases: List[Tuple[str, str]] = [
        # file_path explicitly null
        ("null file_path", json.dumps({
            "session_id": "t12-null",
            "hook_event_name": "PreToolUse",
            "tool_name": "Write",
            "tool_input": {"file_path": None},
        })),
        # tool_input missing entirely
        ("missing tool_input", json.dumps({
            "session_id": "t12-missing",
            "hook_event_name": "PreToolUse",
            "tool_name": "Write",
        })),
        # Non-JSON stdin
        ("non-JSON text", "this is not JSON at all\n"),
        # Empty stdin
        ("empty stdin", ""),
    ]
    for sub_label, stdin in cases:
        with tempfile.TemporaryDirectory() as tmpdir:
            result, elapsed = run_hook(stdin, tmpdir)
            full_label = f"{label} [{sub_label}]"
            failures.extend(assert_within_budget(elapsed, full_label))
            failures.extend(assert_silent(result, full_label))
            # The hook contract also forbids stderr noise on these
            # silent paths — the goal is to never block the underlying
            # tool call with diagnostic output. A `Broken pipe` style
            # message from a Python fallback would still satisfy the
            # contract because it does not affect exit code or stdout,
            # but a genuinely-loud diagnostic would.
            if result.stderr.strip():
                failures.append(
                    f"{full_label}: unexpected stderr noise: "
                    f"{result.stderr[:200]!r}"
                )
    return failures


def test_13_concurrent_same_tier_same_session() -> List[str]:
    """Concurrent same-tier same-session → at most one fires (flock)."""
    label = "test_13_concurrent_same_tier_same_session"
    failures: List[str] = []
    with tempfile.TemporaryDirectory() as tmpdir:
        payload = make_input(
            session_id="t13",
            tool_name="Write",
            file_path="/tmp/concurrent.md",
        )

        # Two worker threads invoke the hook in parallel; the flock-
        # wrapped critical section must funnel them so at most one emits
        # non-empty additionalContext. The other should exit 0 with
        # either empty stdout (lock-acquisition miss) or with the
        # post-state-write silent branch.
        results: List[Tuple[Optional[str], float]] = []
        lock = threading.Lock()

        def worker() -> None:
            r, e = run_hook(payload, tmpdir)
            body = parse_hook_output(r.stdout) if r.stdout.strip() else None
            with lock:
                results.append((body, e))

        threads = [threading.Thread(target=worker) for _ in range(2)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        non_empty = [b for b, _ in results if b]
        for _, elapsed in results:
            failures.extend(assert_within_budget(elapsed, label))
        if len(non_empty) > 1:
            failures.append(
                f"{label}: expected ≤1 non-empty additionalContext under "
                f"concurrent flock, got {len(non_empty)} non-empty bodies"
            )
        # At least one of the two should have fired (the holder of the
        # flock) — if neither fired, the critical section is broken or
        # the state file was inherited.
        if len(non_empty) == 0:
            failures.append(
                f"{label}: both concurrent invocations stayed silent — "
                f"the flock holder must still fire"
            )
    return failures


def test_14_apply_patch_two_server_keys() -> List[str]:
    """Two `mcp__<server>__steroid_apply_patch` server keys exercise the `.+` regex."""
    label = "test_14_apply_patch_two_server_keys"
    failures: List[str] = []
    # The hook script's internal tool-name match anchors as
    # `^mcp__.+__steroid_apply_patch$` and accepts any non-empty middle
    # segment. Two distinct keys confirm the `.+` middle segment is not
    # accidentally hardcoded to one registry name.
    server_keys = ["localhost-6315", "intellij"]
    for idx, key in enumerate(server_keys):
        with tempfile.TemporaryDirectory() as tmpdir:
            payload = make_input(
                session_id=f"t14-{idx}",
                tool_name=f"mcp__{key}__steroid_apply_patch",
                hunks=[
                    {
                        "file_path": "/tmp/x.md",
                        "old_string": "a",
                        "new_string": "b",
                    },
                ],
            )
            result, elapsed = run_hook(payload, tmpdir)
            sub_label = f"{label} [{key}]"
            failures.extend(assert_within_budget(elapsed, sub_label))
            body = parse_hook_output(result.stdout)
            if body is None or not body.startswith(TIER_A_PREFIX):
                failures.append(
                    f"{sub_label}: expected Tier-A reminder on apply-patch "
                    f"via server key {key!r}, got {body!r}"
                )
    return failures


def test_15_captured_fixture_replay() -> List[str]:
    """Replay the three captured smoke fixtures shipped under fixtures/."""
    label = "test_15_captured_fixture_replay"
    failures: List[str] = []
    expectations: List[Tuple[str, str, Optional[str], Optional[str]]] = [
        # (fixture filename, sub-label, expected prefix, must-also-contain)
        ("house-style-smoke-write.json", "Write Markdown",
         TIER_A_PREFIX, None),
        ("house-style-smoke-edit.json", "Edit Markdown",
         TIER_A_PREFIX, None),
        ("house-style-smoke-apply-patch.json", "apply_patch mixed-tier",
         TIER_A_PREFIX, TIER_B_PREFIX),
    ]
    for fname, sub_label, expected_prefix, must_contain in expectations:
        fixture = FIXTURES_DIR / fname
        if not fixture.exists():
            failures.append(
                f"{label} [{sub_label}]: fixture missing at {fixture}"
            )
            continue
        stdin = fixture.read_text(encoding="utf-8")
        with tempfile.TemporaryDirectory() as tmpdir:
            result, elapsed = run_hook(stdin, tmpdir)
            full_label = f"{label} [{sub_label}]"
            failures.extend(assert_within_budget(elapsed, full_label))
            body = parse_hook_output(result.stdout)
            if body is None or not body.startswith(expected_prefix):
                failures.append(
                    f"{full_label}: expected prefix {expected_prefix!r}, "
                    f"got {body!r}"
                )
            if must_contain and (body is None or must_contain not in body):
                failures.append(
                    f"{full_label}: expected concatenated body to also "
                    f"contain {must_contain!r}"
                )
    return failures


# Headings that the hook reminder bodies cite verbatim. Drift in any of
# these slugs silently rots the Tier-B reminder text; the test reads the
# actual file and asserts each line is present as exact heading text.
TIER_B_HEADINGS = [
    "## Orientation",
    "## Banned vocabulary",
    "## Banned sentence patterns",
    "## Banned analysis patterns",
    "### Em-dash discipline",
]


def test_16_section_name_guard() -> List[str]:
    """§1.5 anchor-drift guard: heading slugs cited by the hook still exist."""
    label = "test_16_section_name_guard"
    failures: List[str] = []
    if not HOUSE_STYLE_MD.exists():
        failures.append(
            f"{label}: rule source missing at {HOUSE_STYLE_MD}"
        )
        return failures
    text = HOUSE_STYLE_MD.read_text(encoding="utf-8")
    # Match each heading anchored to start-of-line; trailing whitespace
    # / inline anchors are tolerated. A bare substring search would
    # produce false positives if a body paragraph happens to contain
    # the slug as inline prose.
    for heading in TIER_B_HEADINGS:
        pattern = re.compile(
            r"^" + re.escape(heading) + r"\s*$", re.MULTILINE
        )
        if not pattern.search(text):
            failures.append(
                f"{label}: heading {heading!r} missing from "
                f"{HOUSE_STYLE_MD.relative_to(REPO_ROOT)}. The hook's "
                f"Tier-B reminder cites this slug verbatim; rename "
                f"breaks the pointer text. Update the hook script and "
                f"this test together."
            )
    return failures


def test_17_jq_fallback() -> List[str]:
    """jq missing → hook still emits valid JSON via Python fallback.

    Not on the 16-case roster but a Validation and Acceptance line item.
    Bundled into the runner because the cost is low and the regression
    risk on the fallback path is non-trivial (the Python one-liner is
    invoked from two distinct callsites in the hook).
    """
    label = "test_17_jq_fallback"
    failures: List[str] = []
    # Build a synthetic PATH that has bash/python3/realpath/etc. but
    # NOT jq, so `command -v jq` misses and the hook takes the Python
    # fallback at every callsite.
    needed = [
        "bash", "python3", "cat", "grep", "printf", "realpath",
        "mktemp", "dirname", "rm", "head", "tail", "awk", "sed",
        "flock", "ls", "env", "command", "test",
    ]
    sandbox = tempfile.mkdtemp(prefix="house-style-jq-sandbox-")
    try:
        for tool in needed:
            real = shutil.which(tool)
            if real is None:
                continue
            link = os.path.join(sandbox, tool)
            try:
                os.symlink(real, link)
            except FileExistsError:
                pass
        # Confirm jq is NOT in the sandbox PATH (sanity check — a stray
        # symlink would invalidate the test).
        if shutil.which("jq", path=sandbox) is not None:
            failures.append(
                f"{label}: sandbox PATH unexpectedly contains jq — "
                f"the test cannot validate the fallback path"
            )
            return failures
        with tempfile.TemporaryDirectory() as tmpdir:
            payload = make_input(
                session_id="t17",
                tool_name="Write",
                file_path="/tmp/fallback.md",
            )
            result, elapsed = run_hook(payload, tmpdir, path_env=sandbox)
            failures.extend(assert_within_budget(elapsed, label))
            if result.returncode != 0:
                failures.append(
                    f"{label}: hook exited {result.returncode} on "
                    f"jq-missing PATH; stderr={result.stderr[:200]!r}"
                )
            body = parse_hook_output(result.stdout)
            if body is None or not body.startswith(TIER_A_PREFIX):
                failures.append(
                    f"{label}: Python fallback path failed to emit "
                    f"Tier-A reminder; got {body!r}; "
                    f"stderr={result.stderr[:200]!r}"
                )
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)
    return failures


# Per-body and concatenated character caps the hook documents in its own
# stage-7 comment ("Each ≤500 chars; concatenated ≤1500 chars … validated
# by the test runner"). test_18 reads the bodies from the hook source and
# enforces these so the comment's claim is true and a future body growth
# that overruns the cap fails the runner instead of silently shipping.
PER_BODY_CHAR_CAP = 500
CONCAT_CHAR_CAP = 1500
# Captures the single-quoted RHS of `tier_a_body='…'` / `tier_b_body='…'`.
# The bodies contain no single quote, so a non-greedy match to the next
# single quote is unambiguous; DOTALL is unused because the assignments
# are single-line. Anchored to start-of-line to skip the prefix
# constants (TIER_A_PREFIX / TIER_B_PREFIX) defined in this test file.
_BODY_ASSIGN_RE = re.compile(r"^(tier_[ab]_body)='([^']*)'", re.MULTILINE)


def _read_reminder_bodies() -> Dict[str, str]:
    """Parse the tier_*_body assignments out of the hook source."""
    src = HOOK.read_text(encoding="utf-8")
    return {m.group(1): m.group(2) for m in _BODY_ASSIGN_RE.finditer(src)}


def test_18_reminder_body_length_budget() -> List[str]:
    """Each reminder body ≤500 chars; the two concatenated ≤1500 chars.

    Makes the hook's stage-7 comment ("validated by the test runner")
    true: without this assertion the documented per-body and concatenated
    caps had no guard, and a body could grow past the cap unnoticed.
    """
    label = "test_18_reminder_body_length_budget"
    failures: List[str] = []
    bodies = _read_reminder_bodies()
    expected = {"tier_a_body", "tier_b_body"}
    if set(bodies) != expected:
        failures.append(
            f"{label}: expected to parse {sorted(expected)} from the hook "
            f"source, parsed {sorted(bodies)} — the assignment shape the "
            f"length guard relies on changed"
        )
        return failures
    for name, body in bodies.items():
        if len(body) > PER_BODY_CHAR_CAP:
            failures.append(
                f"{label}: {name} is {len(body)} chars, over the "
                f"{PER_BODY_CHAR_CAP}-char per-body cap the hook comment "
                f"documents; trim the body or revise the documented cap"
            )
    concat_len = sum(len(b) for b in bodies.values())
    if concat_len > CONCAT_CHAR_CAP:
        failures.append(
            f"{label}: concatenated bodies are {concat_len} chars, over "
            f"the {CONCAT_CHAR_CAP}-char cap the hook comment documents"
        )
    return failures


# Registry of test cases. The order is the order they run in `main`.
TESTS = [
    test_01_tier_a_markdown,
    test_02_tier_b_java,
    test_03_tier_b_kotlin,
    test_04_silent_extensions,
    test_05_rate_limit_same_tier_same_session,
    test_06_fresh_session_after_clear,
    test_07_cross_tier_same_session_sequential,
    test_08_mixed_tier_apply_patch,
    test_09_apply_patch_md_plus_silent_extension,
    test_10_blacklist_three_path_forms,
    test_11_empty_hunks_array,
    test_12_malformed_input_shapes,
    test_13_concurrent_same_tier_same_session,
    test_14_apply_patch_two_server_keys,
    test_15_captured_fixture_replay,
    test_16_section_name_guard,
    test_17_jq_fallback,
    test_18_reminder_body_length_budget,
]


def main() -> int:
    if not HOOK.exists():
        print(f"FATAL: hook script missing at {HOOK}", file=sys.stderr)
        return 1
    all_failures: List[str] = []
    for fn in TESTS:
        try:
            failures = fn()
        except AssertionError as exc:
            failures = [f"{fn.__name__}: AssertionError {exc}"]
        except Exception as exc:  # surface every runtime error as a failure
            failures = [
                f"{fn.__name__}: unexpected {type(exc).__name__}: {exc}"
            ]
        if failures:
            all_failures.extend(failures)
    if all_failures:
        print("FAILED — house-style hook validation:", file=sys.stderr)
        for msg in all_failures:
            print(f"  - {msg}", file=sys.stderr)
        print(
            f"\n{len(all_failures)} assertion(s) failed across "
            f"{len(TESTS)} test cases.",
            file=sys.stderr,
        )
        return 1
    print(
        f"PASSED — house-style hook validation: "
        f"{len(TESTS)} test cases, all assertions within "
        f"{TIME_BUDGET_S:.1f}s budget."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
