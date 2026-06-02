#!/usr/bin/env python3
"""Validation runner for `.claude/scripts/workflow-startup-precheck.sh`.

Running this script is the validation: it shells out to the bash precheck
script, parses the JSON it prints, and asserts the scaffold contract that
this track's first step establishes:

  * the unknown-/missing-mode error path — exits non-zero, prints usage to
    stderr, and emits NO JSON on stdout;
  * the three valid `--mode` shapes (`full`, `divergence-only`,
    `migrate-range`) each emit valid JSON, exit 0, and carry the pinned key
    set with `actions_taken` an empty array and (for `full`) `state` as JSON
    null;
  * the jq null-vs-empty contract on a synthetic nullable scalar: an absent
    `--bootstrap-sha` must emit `base_sha` as JSON `null` (not the empty
    string ""), while a supplied SHA emits the SHA string — the load-bearing
    emit-surface guarantee, for the behavior-parity contract, that every
    later detection field also relies on.

Later changes (divergence, drift, handoff scan, reduced-mode detail) extend
the script and add their own fixtures and assertions; this file pins the
scaffold so a regression in the mode dispatch or the emit idiom fails the
suite immediately.

Invocation (from repo root):

    python3 .claude/scripts/tests/test_workflow_startup_precheck.py

Exit code 0: every test case passed. Exit code 1: one or more failed; each
failure prints a clear message naming the test case.

Runner shape mirrors `.claude/scripts/tests/test_session_stats.py` and
`.claude/scripts/tests/test_measure_read_share.py` (stand-alone, no pytest
collection, exit-code semantics, single file). Pytest is not installed on the
project's CI image; the stand-alone runner keeps the test executable on any
Python 3 host. The harness shells out to the bash script rather than importing
it.
"""

from __future__ import annotations

import json
import subprocess
import sys
import traceback
from pathlib import Path
from typing import Callable, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT_PATH = REPO_ROOT / ".claude" / "scripts" / "workflow-startup-precheck.sh"

# A throwaway but well-formed 40-hex SHA used to exercise the present-scalar
# branch of the empty->null idiom. Not a real commit; the scaffold echoes it
# back verbatim without resolving it against git.
SYNTHETIC_SHA = "a" * 40


# ---------------------------------------------------------------------------
# Process helper.
# ---------------------------------------------------------------------------


def run_precheck(*args: str) -> subprocess.CompletedProcess:
    """Run the precheck script with the given args, capturing stdout/stderr.

    `check=False`: the error-path test deliberately expects a non-zero exit,
    so the caller inspects `returncode` rather than relying on raise-on-error.
    """
    return subprocess.run(
        ["bash", str(SCRIPT_PATH), *args],
        capture_output=True,
        text=True,
        check=False,
    )


# ---------------------------------------------------------------------------
# Test cases. Each raises AssertionError on failure with a clear message.
# ---------------------------------------------------------------------------


def test_unknown_mode_exits_nonzero_no_json() -> None:
    """An unknown --mode value exits non-zero, prints usage to stderr, and
    emits no JSON on stdout — a caller that mistypes the mode must see a hard
    failure, not a silently valid-but-empty blob."""
    proc = run_precheck("--mode", "bogus")
    assert proc.returncode != 0, (
        f"unknown mode should exit non-zero, got {proc.returncode}"
    )
    assert proc.stdout.strip() == "", (
        f"unknown mode must emit no stdout JSON, got: {proc.stdout!r}"
    )
    assert "Usage:" in proc.stderr, (
        f"unknown mode must print usage to stderr, got: {proc.stderr!r}"
    )


def test_missing_mode_exits_nonzero_no_json() -> None:
    """No --mode at all is the same hard-failure path: non-zero exit, usage on
    stderr, no stdout JSON."""
    proc = run_precheck()
    assert proc.returncode != 0, (
        f"missing mode should exit non-zero, got {proc.returncode}"
    )
    assert proc.stdout.strip() == "", (
        f"missing mode must emit no stdout JSON, got: {proc.stdout!r}"
    )
    assert "Usage:" in proc.stderr, (
        f"missing mode must print usage to stderr, got: {proc.stderr!r}"
    )


def test_unknown_arg_exits_nonzero() -> None:
    """An unrecognized argument (even alongside a valid mode) is rejected
    with a non-zero exit and usage on stderr."""
    proc = run_precheck("--mode", "full", "--nonsense", "x")
    assert proc.returncode != 0, (
        f"unknown arg should exit non-zero, got {proc.returncode}"
    )
    assert proc.stdout.strip() == "", (
        f"unknown arg must emit no stdout JSON, got: {proc.stdout!r}"
    )


def test_full_mode_pinned_shape() -> None:
    """`--mode full` emits valid JSON, exits 0, and carries the pinned scaffold
    shape: the five top-level keys, `handoffs` an empty array, `state` JSON
    null (the seam the state parser fills later), and `actions_taken` an empty
    array (the seam the no-drift normalization wiring fills later)."""
    proc = run_precheck("--mode", "full")
    assert proc.returncode == 0, f"full mode should exit 0, got {proc.returncode}"
    obj = json.loads(proc.stdout)
    assert set(obj.keys()) == {
        "divergence",
        "drift",
        "handoffs",
        "state",
        "actions_taken",
    }, f"full mode key set mismatch: {sorted(obj.keys())}"
    assert obj["handoffs"] == [], f"full handoffs should be [], got {obj['handoffs']!r}"
    assert obj["actions_taken"] == [], (
        f"full actions_taken should be [], got {obj['actions_taken']!r}"
    )
    # `state` must be JSON null, not the string "null" or any object yet.
    assert obj["state"] is None, f"full state should be null, got {obj['state']!r}"


def test_divergence_only_mode_pinned_shape() -> None:
    """`--mode divergence-only` emits only `divergence` and `actions_taken`,
    exits 0, and `actions_taken` is an empty array."""
    proc = run_precheck("--mode", "divergence-only")
    assert proc.returncode == 0, (
        f"divergence-only should exit 0, got {proc.returncode}"
    )
    obj = json.loads(proc.stdout)
    assert set(obj.keys()) == {"divergence", "actions_taken"}, (
        f"divergence-only key set mismatch: {sorted(obj.keys())}"
    )
    assert obj["actions_taken"] == [], (
        f"divergence-only actions_taken should be [], got {obj['actions_taken']!r}"
    )


def test_migrate_range_mode_pinned_shape() -> None:
    """`--mode migrate-range` emits the migration-range key set and exits 0,
    with no `state`, `handoffs`, or `divergence` keys."""
    proc = run_precheck("--mode", "migrate-range")
    assert proc.returncode == 0, (
        f"migrate-range should exit 0, got {proc.returncode}"
    )
    obj = json.loads(proc.stdout)
    assert set(obj.keys()) == {
        "stamped_artifacts",
        "unstamped_files",
        "base_sha",
        "log_range",
        "merge_base_failed",
    }, f"migrate-range key set mismatch: {sorted(obj.keys())}"
    for forbidden in ("state", "handoffs", "divergence"):
        assert forbidden not in obj, (
            f"migrate-range must not emit '{forbidden}', got keys {sorted(obj.keys())}"
        )


def test_null_vs_empty_absent_scalar_is_json_null() -> None:
    """The load-bearing emit-surface contract: an absent nullable scalar emits
    JSON `null`, never the empty string "".

    `base_sha` is the scaffold's live witness for the empty->null idiom — with
    no `--bootstrap-sha`, the shell variable is empty and the idiom
    `($x | if . == "" then null else . end)` must collapse it to JSON null.
    A naive `--arg` binding would emit "" here, which every downstream
    `jq -e '.field == null'` assertion would silently fail to catch; this test
    pins the idiom directly."""
    proc = run_precheck("--mode", "migrate-range")
    assert proc.returncode == 0, (
        f"migrate-range should exit 0, got {proc.returncode}"
    )
    obj = json.loads(proc.stdout)
    assert obj["base_sha"] is None, (
        "absent --bootstrap-sha must emit base_sha as JSON null, got "
        f"{obj['base_sha']!r} (an empty string would mean the empty->null "
        "idiom regressed to a naive --arg binding)"
    )
    # And explicitly NOT the empty string — the precise regression the idiom
    # guards against.
    assert obj["base_sha"] != "", (
        "base_sha must be JSON null, not the empty string"
    )


def test_null_vs_empty_present_scalar_is_the_value() -> None:
    """The other half of the idiom: a supplied scalar emits the value verbatim
    as a JSON string, so the empty->null collapse fires only for the empty
    case."""
    proc = run_precheck("--mode", "migrate-range", "--bootstrap-sha", SYNTHETIC_SHA)
    assert proc.returncode == 0, (
        f"migrate-range --bootstrap-sha should exit 0, got {proc.returncode}"
    )
    obj = json.loads(proc.stdout)
    assert obj["base_sha"] == SYNTHETIC_SHA, (
        f"present --bootstrap-sha must emit the SHA string, got {obj['base_sha']!r}"
    )


# ---------------------------------------------------------------------------
# Runner.
# ---------------------------------------------------------------------------


TESTS: List[Tuple[str, Callable[[], None]]] = [
    ("unknown_mode_exits_nonzero_no_json", test_unknown_mode_exits_nonzero_no_json),
    ("missing_mode_exits_nonzero_no_json", test_missing_mode_exits_nonzero_no_json),
    ("unknown_arg_exits_nonzero", test_unknown_arg_exits_nonzero),
    ("full_mode_pinned_shape", test_full_mode_pinned_shape),
    ("divergence_only_mode_pinned_shape", test_divergence_only_mode_pinned_shape),
    ("migrate_range_mode_pinned_shape", test_migrate_range_mode_pinned_shape),
    ("null_vs_empty_absent_scalar_is_json_null", test_null_vs_empty_absent_scalar_is_json_null),
    ("null_vs_empty_present_scalar_is_the_value", test_null_vs_empty_present_scalar_is_the_value),
]


def main() -> int:
    if not SCRIPT_PATH.exists():
        print(f"FAIL: script not found at {SCRIPT_PATH}", file=sys.stderr)
        return 1

    failures: List[str] = []
    for name, fn in TESTS:
        try:
            fn()
            print(f"PASS: {name}")
        except Exception:  # Report every failure and keep going.
            failures.append(name)
            print(f"FAIL: {name}", file=sys.stderr)
            traceback.print_exc()

    print()
    if failures:
        print(f"{len(failures)} of {len(TESTS)} test(s) FAILED: {', '.join(failures)}")
        return 1
    print(f"All {len(TESTS)} test(s) passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
