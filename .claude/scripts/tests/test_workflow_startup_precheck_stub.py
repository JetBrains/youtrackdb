#!/usr/bin/env python3
"""Validation that `workflow-startup-precheck.sh` resumes a plan-less `minimal`
branch from its phase ledger.

The no-track-for-minimal work drops `implementation-plan.md` from the `minimal`
tier: a one-track change has nothing left for a plan to summarize. The resume
state that the plan's checkboxes used to carry now lives in an append-only phase
ledger (`<plan_dir>/_workflow/phase-ledger.md`), and the active track is
`track-1` by construction (single-track tier — no `## Checklist` to walk). This
file pins that the precheck resumes such a branch to its recorded state instead
of restarting it as a fresh State 0.

This replaces the old stub-plan premise. Before this work, the `minimal` tier
shipped a shape-complete stub of `implementation-plan.md` purely so the unchanged
state machine had a plan to parse; this file then pinned "the unchanged script
parses the stub." That premise is gone: there is no `minimal` plan to parse, and
the state machine reads the ledger. The cases below are the no-plan analogues of
the old stub-plan transitions:

  * ledger phase=0 (plan review not passed) -> readable State 0, the
    autonomous-review gate, with NO implementation-plan.md on disk;
  * ledger phase=C with plan/track-1.md present -> State C, the mid-track resume,
    with the active track defaulted to track-1 (no Checklist walk);
  * ledger phase=Done -> Done, the end-of-plan resume.

It stays a SEPARATE file from `test_workflow_startup_precheck.py` (the precheck's
own contract suite) so a regression here flags "the no-plan minimal resume and
the ledger reader drifted apart" distinctly from a regression in the parser's own
suite. The synthesized ledger lines are byte-faithful to the grammar pinned in
`test_workflow_startup_precheck.py` and the script header.

The ledger lines carry the post-`tier=`-removal schema: the dead `tier=minimal`
token these fixtures used to seed is migrated to the live complexity-axis fields
(`design_gate=no` for a no-design change plus `tracks=1` for the single-track /
no-plan shape) so the resume read exercises the live signal rather than a token
the reader no longer consumes.

Invocation (from repo root):

    python3 .claude/scripts/tests/test_workflow_startup_precheck_stub.py

Exit code 0: every test case passed. Exit code 1: one or more failed; each
failure prints a clear message naming the test case.

Runner shape mirrors `test_workflow_startup_precheck.py` (stand-alone, no pytest
collection, exit-code semantics, single file, shells out to the bash script).
Pytest is not installed on the project's CI image; the stand-alone runner keeps
the test executable on any Python 3 host.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import traceback
from pathlib import Path
from typing import Callable, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT_PATH = REPO_ROOT / ".claude" / "scripts" / "workflow-startup-precheck.sh"


# ---------------------------------------------------------------------------
# Process helper — mirrors the existing suite's run_precheck so this file
# invokes the script the same way (bash + the same arg vector + a cwd pointing at
# the fixture repo). Kept local rather than imported: the stand-alone runners in
# this directory do not import one another.
# ---------------------------------------------------------------------------


def run_precheck(*args: str, cwd: Optional[Path] = None) -> subprocess.CompletedProcess:
    """Run the precheck script with the given args inside `cwd`, capturing
    stdout/stderr. `check=False` so a non-zero exit surfaces as a clear per-case
    assertion rather than a raised CalledProcessError. `cwd` runs the script
    inside the fixture git repo so the state walk resolves the fixture's branch
    (and thus its plan dir) rather than the runner's own checkout. `timeout=60` so
    a wedged child (a git operation that escapes the script's own internal
    `timeout 10` guard) fails fast with a clear message instead of hanging the
    runner."""
    try:
        return subprocess.run(
            ["bash", str(SCRIPT_PATH), *args],
            capture_output=True,
            text=True,
            check=False,
            cwd=str(cwd) if cwd is not None else None,
            timeout=60,
        )
    except subprocess.TimeoutExpired as exc:
        raise SystemExit(
            f"workflow-startup-precheck.sh timed out after 60s "
            f"(args={args!r}, cwd={cwd}); possible wedged subprocess."
        ) from exc


# ---------------------------------------------------------------------------
# Minimal git fixture.
#
# The precheck resolves the active plan dir from the current branch name
# (`docs/adr/<branch>` per § 1.6(g)) and `--mode full` also runs divergence
# detection (which `git fetch`es the upstream). So a hermetic run needs a real
# git repo with a pinned branch and an upstream tracking ref — a bare-cwd run
# would fetch the runner's real upstream (network-dependent, slow on CI) and
# resolve real plan artifacts. This builder stands up a throwaway repo with the
# small surface this file needs: a branch named so its plan dir is predictable,
# an in-sync upstream, and writers for the phase ledger and the single track file
# at that dir — but NO implementation-plan.md (the plan-less minimal shape).
#
# It is intentionally a slimmer sibling of the full suite's GitFixture, kept
# local for the no-cross-import reason above.
# ---------------------------------------------------------------------------


GIT_ENV = {
    # Deterministic identity + isolation from the host's global git config so the
    # fixture behaves identically on any developer machine and on CI.
    "GIT_AUTHOR_NAME": "precheck-stub-fixture",
    "GIT_AUTHOR_EMAIL": "precheck-stub@fixture.invalid",
    "GIT_COMMITTER_NAME": "precheck-stub-fixture",
    "GIT_COMMITTER_EMAIL": "precheck-stub@fixture.invalid",
    "GIT_CONFIG_GLOBAL": "/dev/null",
    "GIT_CONFIG_SYSTEM": "/dev/null",
}


class MinimalLedgerFixture:
    """A throwaway git repo whose branch name fixes the resolved plan dir, with
    an in-sync upstream so `--mode full` divergence detection runs hermetically.
    Models the PLAN-LESS `minimal` shape: a phase ledger and an optional
    `plan/track-1.md`, but no `implementation-plan.md`.

    Use as a context manager so the temp dir is always cleaned up:

        with MinimalLedgerFixture() as fx:
            fx.write_ledger("[...] [ctx=safe] phase=C\\n")
            fx.write_track_1(body, stamp=fx.head_sha())
            proc = run_precheck("--mode", "full", cwd=fx.path)
    """

    def __init__(self, branch: str = "main") -> None:
        # The branch name drives the plan dir: docs/adr/<branch>/_workflow/.
        self.branch = branch
        self._tmp: Optional[tempfile.TemporaryDirectory] = None
        self.path: Path = Path()  # set in __enter__
        self.bare_path: Optional[Path] = None

    def __enter__(self) -> "MinimalLedgerFixture":
        # A unique temp prefix keeps concurrent test runs from colliding —
        # satisfies the project's /tmp isolation rule.
        self._tmp = tempfile.TemporaryDirectory(prefix="precheck-minimal-")
        root = Path(self._tmp.name)
        self.path = root / "work"
        self.path.mkdir()
        # `-b <branch>` pins the initial branch so the plan dir resolves to the
        # name this fixture expects regardless of the host's init.defaultBranch.
        self._git("init", "-q", "-b", self.branch)
        # An initial commit + in-sync upstream so divergence detection reports a
        # clean in-sync state rather than skipping or fetching the real remote.
        (self.path / "seed.txt").write_text("seed\n", encoding="utf-8")
        self._git("add", "seed.txt")
        self._git("commit", "-q", "-m", "seed")
        self.bare_path = root / "remote.git"
        self._git("init", "-q", "--bare", "-b", self.branch, str(self.bare_path))
        self._git("remote", "add", "origin", f"file://{self.bare_path}")
        self._git("push", "-q", "-u", "origin", self.branch)
        return self

    def __exit__(self, *exc: object) -> None:
        if self._tmp is not None:
            self._tmp.cleanup()

    def _git(self, *args: str) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env.update(GIT_ENV)
        return subprocess.run(
            ["git", *args],
            cwd=str(self.path),
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=True,
        )

    def head_sha(self) -> str:
        """The current branch HEAD's full 40-hex SHA — stamped into the track
        file so the drift half of the same `full` run is a clean all-stamped
        no-drift read rather than noise (track-1.md is the drift anchor when the
        plan is absent, D13)."""
        env = dict(os.environ)
        env.update(GIT_ENV)
        proc = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=str(self.path),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            check=True,
        )
        return proc.stdout.strip()

    @property
    def plan_dir(self) -> Path:
        """The plan dir the precheck resolves from the branch: docs/adr/<branch>."""
        return self.path / "docs" / "adr" / self.branch

    @property
    def plan_file(self) -> Path:
        """The implementation-plan.md path — used only to ASSERT its absence (the
        plan-less minimal shape never writes it)."""
        return self.plan_dir / "_workflow" / "implementation-plan.md"

    def write_ledger(self, body: str) -> Path:
        """Write the phase ledger verbatim and commit it so the working tree is
        clean for the divergence half of the same `full` run. The ledger is
        unstamped by design (D13) — it carries no `workflow-sha` line and is
        excluded from the drift walk by omission."""
        path = self.plan_dir / "_workflow" / "phase-ledger.md"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
        rel = str(path.relative_to(self.path))
        self._git("add", rel)
        self._git("commit", "-q", "-m", "add phase-ledger.md")
        return path

    def write_track_1(self, body: str, *, stamp: str) -> Path:
        """Write a stamped `plan/track-1.md` (the single track of the minimal
        tier) so a ledger-driven State C resolves its `## Progress` sub-state. The
        stamp keeps the drift half clean. `body`'s sections follow the line-1
        stamp comment this method prepends."""
        path = self.plan_dir / "_workflow" / "plan" / "track-1.md"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f"<!-- workflow-sha: {stamp} -->\n{body}", encoding="utf-8")
        rel = str(path.relative_to(self.path))
        self._git("add", rel)
        self._git("commit", "-q", "-m", "add track-1.md")
        return path


# A timestamp literal reused across the synthesized ledger lines; minute
# precision with a trailing Z, matching the `date -u +%Y-%m-%dT%H:%MZ` the append
# emits (the read is timestamp-agnostic, so a fixed value is fine).
_TS = "2026-06-15T12:00Z"


def _state(proc: subprocess.CompletedProcess) -> dict:
    """Parse the `state` object from a `--mode full` run, asserting the run exited
    0 first so a script error surfaces as a clear message rather than a downstream
    KeyError on the `state` key. The presence of a parseable JSON `state` object
    is itself the 'readable state' the no-plan resume calls for."""
    assert proc.returncode == 0, (
        f"full mode should exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
    )
    obj = json.loads(proc.stdout)
    assert "state" in obj, f"full mode must emit a `state` key, got {sorted(obj.keys())}"
    return obj["state"]


# ---------------------------------------------------------------------------
# Test cases.
# ---------------------------------------------------------------------------


def test_minimal_no_plan_ledger_phase_0_is_readable_state_0() -> None:
    """A plan-less `minimal` branch whose ledger records `phase=0` (plan review
    not yet passed) parses to a readable State 0 — resume routes to the
    autonomous-review gate — with NO implementation-plan.md on disk. This is the
    load-bearing premise of the minimal-drops-the-plan change: the ledger is the
    resume signal, and its absence of a plan does not strand resume on a parse
    failure."""
    with MinimalLedgerFixture() as fx:
        fx.write_ledger(f"[{_TS}] [ctx=safe] phase=0 design_gate=no tracks=1\n")
        assert not fx.plan_file.exists(), (
            "the plan-less minimal fixture must have no implementation-plan.md"
        )
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "0", "substate": None}, (
            "a plan-less minimal branch with a phase=0 ledger must parse to a "
            f"readable State 0, got {state!r}"
        )


def test_minimal_no_plan_ledger_phase_c_resolves_state_c_track_1() -> None:
    """The mid-track resume: a ledger recording `phase=C` (the ledger names no
    track, so the active track defaults to track-1 — there is no `## Checklist` to
    walk) and a `plan/track-1.md` present resolve State C, reading the
    within-track sub-state from the track file's `## Progress`. Decomposition is
    done and the roster has a `[ ]` step, so the sub-state is steps-partial. This
    is the no-plan analogue of the old stub's plan-review-flipped -> State C
    transition."""
    with MinimalLedgerFixture() as fx:
        fx.write_ledger(f"[{_TS}] [ctx=info] phase=C design_gate=no tracks=1\n")
        fx.write_track_1(
            "# Track 1\n\n## Progress\n"
            "- [x] 2026-06-15T00:00Z [ctx=info] Review + decomposition complete\n"
            "- [ ] Step implementation\n\n## Concrete Steps\n\n"
            "1. the single minimal track — risk: high  [ ]\n",
            stamp=fx.head_sha(),
        )
        assert not fx.plan_file.exists(), (
            "the plan-less minimal fixture must have no implementation-plan.md"
        )
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "C", "substate": "steps-partial"}, (
            "a plan-less minimal branch with a phase=C ledger and plan/track-1.md "
            f"must resume State C (active track defaulted to 1), got {state!r}"
        )


def test_minimal_no_plan_ledger_phase_c_no_track_file_is_state_a() -> None:
    """The pre-decomposition resume: a ledger recording `phase=C` but with NO
    track file on disk yet resolves State A — a phase recorded as C cannot resolve
    a within-track sub-state without a track file, so it falls back to the
    pre-Phase-A State A, mirroring the legacy walk's
    first-`[ ]`-track-without-a-file branch."""
    with MinimalLedgerFixture() as fx:
        fx.write_ledger(f"[{_TS}] [ctx=safe] phase=C design_gate=no tracks=1\n")
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "A", "substate": None}, (
            "a phase=C ledger with no plan/track-1.md must resolve State A, "
            f"got {state!r}"
        )


def test_minimal_no_plan_ledger_phase_done_is_done() -> None:
    """The end-of-plan resume: a ledger whose tail records `phase=Done` (Phase 4
    complete) resolves Done. The earlier `phase=C` line is overridden by
    last-value-wins. This is the no-plan analogue of the old stub's
    track-and-final-artifacts-flipped -> Done transition."""
    with MinimalLedgerFixture() as fx:
        fx.write_ledger(
            f"[{_TS}] [ctx=safe] phase=C design_gate=no tracks=1\n"
            f"[{_TS}] [ctx=safe] phase=Done design_gate=no tracks=1\n"
        )
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "Done", "substate": None}, (
            "a plan-less minimal branch whose ledger tail records phase=Done must "
            f"resolve Done, got {state!r}"
        )


def test_minimal_no_plan_ledger_drift_clean_anchor_is_track_1() -> None:
    """Dropping the plan does not weaken drift detection: with no
    implementation-plan.md, `track-1.md` is the drift anchor (D13). A stamped
    track-1.md plus the unstamped-by-design ledger is a clean all-stamped no-drift
    read, and the unstamped ledger does NOT trip the unstamped-drift
    short-circuit — it is excluded from the walk by omission."""
    with MinimalLedgerFixture() as fx:
        fx.write_ledger(f"[{_TS}] [ctx=safe] phase=C design_gate=no tracks=1\n")
        fx.write_track_1(
            "# Track 1\n\n## Progress\n- [ ] Review + decomposition\n",
            stamp=fx.head_sha(),
        )
        proc = run_precheck("--mode", "full", cwd=fx.path)
        assert proc.returncode == 0, (
            f"full should exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
        )
        obj = json.loads(proc.stdout)
        assert obj["drift"]["detected"] is False, (
            "a stamped track-1.md anchor + unstamped ledger must be a clean "
            f"no-drift read, got {obj['drift']!r}"
        )


# ---------------------------------------------------------------------------
# Runner.
# ---------------------------------------------------------------------------


TESTS: List[Tuple[str, Callable[[], None]]] = [
    ("minimal_no_plan_ledger_phase_0_is_readable_state_0", test_minimal_no_plan_ledger_phase_0_is_readable_state_0),
    (
        "minimal_no_plan_ledger_phase_c_resolves_state_c_track_1",
        test_minimal_no_plan_ledger_phase_c_resolves_state_c_track_1,
    ),
    (
        "minimal_no_plan_ledger_phase_c_no_track_file_is_state_a",
        test_minimal_no_plan_ledger_phase_c_no_track_file_is_state_a,
    ),
    ("minimal_no_plan_ledger_phase_done_is_done", test_minimal_no_plan_ledger_phase_done_is_done),
    (
        "minimal_no_plan_ledger_drift_clean_anchor_is_track_1",
        test_minimal_no_plan_ledger_drift_clean_anchor_is_track_1,
    ),
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
