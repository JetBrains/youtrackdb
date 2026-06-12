#!/usr/bin/env python3
"""Validation that the UNCHANGED `workflow-startup-precheck.sh` parses the
shape-complete `minimal` stub plan.

The complexity-adaptive tiering work adds a `minimal` tier whose plan is a
~10-line stub rather than the full aggregator. The whole premise of that tier
is that the resume state machine is left untouched: the existing precheck
script must read the stub's three state-bearing sections and resolve a valid
resume state without any script change. This file pins that premise as an
executable assertion.

It is deliberately a SEPARATE file from `test_workflow_startup_precheck.py`:
that file is the precheck's own contract suite and must stay byte-identical
(no script/test edits is the load-bearing invariant the stub work rests on).
This file adds NEW coverage on the LIVE script without touching either, so a
regression here flags "the stub shape and the unchanged parser drifted apart"
distinctly from a regression in the parser's own suite.

The synthesized stub is byte-faithful to the authoritative `minimal` stub
template in `create-plan/SKILL.md` (the plan-derivation block): a
`## High-level plan` carrying the tier line, a `## Checklist` with exactly one
track entry, a `## Plan Review` section with its decision checkbox, and a
`## Final Artifacts` section with its decision checkbox. The state walk reads
each section's first top-level checkbox, so the stub's checkboxes (not bare
headings) are what keep resume out of a stranded State 0.

Three assertions, matching the three states a stub passes through over its
lifetime:

  * As written (`## Plan Review` `[ ]`), the precheck reports a readable state
    and it is State 0 — plan review has not passed, so the stub resumes at the
    autonomous-review gate exactly like a full plan.
  * With `## Plan Review` flipped to `[x]` (review passed), the precheck walks
    the single `## Checklist` track and reports State A (no track file) or
    State C (track file present) — the post-review, mid-execution resume.
  * With the single `## Checklist` track flipped to `[x]` AND `## Final
    Artifacts` flipped to `[x]`, the precheck finds no `[ ]` track and reports
    State D (Phase 4 pending) or Done (Phase 4 complete) — the end-of-plan
    resume.

Invocation (from repo root):

    python3 .claude/scripts/tests/test_workflow_startup_precheck_stub.py

Exit code 0: every test case passed. Exit code 1: one or more failed; each
failure prints a clear message naming the test case.

Runner shape mirrors `test_workflow_startup_precheck.py` (stand-alone, no
pytest collection, exit-code semantics, single file, shells out to the bash
script). Pytest is not installed on the project's CI image; the stand-alone
runner keeps the test executable on any Python 3 host.
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
# invokes the LIVE script the same way (bash + the same arg vector + a cwd
# pointing at the fixture repo). Kept local rather than imported: the
# stand-alone runners in this directory do not import one another.
# ---------------------------------------------------------------------------


def run_precheck(*args: str, cwd: Optional[Path] = None) -> subprocess.CompletedProcess:
    """Run the precheck script with the given args inside `cwd`, capturing
    stdout/stderr. `check=False` so a non-zero exit surfaces as a clear
    per-case assertion rather than a raised CalledProcessError. `cwd` runs the
    script inside the fixture git repo so the state walk resolves the fixture's
    branch (and thus its plan dir) rather than the runner's own checkout.
    `timeout=60` so a wedged child (a git operation that escapes the script's
    own internal `timeout 10` guard) fails fast with a clear message instead of
    hanging the runner; mirrors the D11 test's `run_script` bound."""
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
# an in-sync upstream, and a writer for the stub plan file at that dir.
#
# It is intentionally a slimmer sibling of the full suite's GitFixture (which
# also models divergence/drift/migrate fixtures this file does not exercise),
# kept local for the no-cross-import reason above.
# ---------------------------------------------------------------------------


GIT_ENV = {
    # Deterministic identity + isolation from the host's global git config so
    # the fixture behaves identically on any developer machine and on CI.
    "GIT_AUTHOR_NAME": "precheck-stub-fixture",
    "GIT_AUTHOR_EMAIL": "precheck-stub@fixture.invalid",
    "GIT_COMMITTER_NAME": "precheck-stub-fixture",
    "GIT_COMMITTER_EMAIL": "precheck-stub@fixture.invalid",
    "GIT_CONFIG_GLOBAL": "/dev/null",
    "GIT_CONFIG_SYSTEM": "/dev/null",
}


class StubPlanFixture:
    """A throwaway git repo whose branch name fixes the resolved plan dir, with
    an in-sync upstream so `--mode full` divergence detection runs hermetically.

    Use as a context manager so the temp dir is always cleaned up:

        with StubPlanFixture() as fx:
            fx.write_plan(stub_body)
            proc = run_precheck("--mode", "full", cwd=fx.path)
    """

    def __init__(self, branch: str = "main") -> None:
        # The branch name drives the plan dir: docs/adr/<branch>/_workflow/.
        self.branch = branch
        self._tmp: Optional[tempfile.TemporaryDirectory] = None
        self.path: Path = Path()  # set in __enter__
        self.bare_path: Optional[Path] = None

    def __enter__(self) -> "StubPlanFixture":
        # A unique temp prefix (mktemp -d under the hood) keeps concurrent test
        # runs from colliding — satisfies the project's /tmp isolation rule.
        self._tmp = tempfile.TemporaryDirectory(prefix="precheck-stub-")
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
        """The current branch HEAD's full 40-hex SHA — stamped into the stub's
        line-1 `workflow-sha` comment so the drift half of the same `full` run
        is a clean all-stamped no-drift read rather than noise."""
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

    def write_plan(self, body: str, *, commit: bool = True) -> Path:
        """Write the stub plan to `<plan_dir>/_workflow/implementation-plan.md`
        (the file the state walk reads) and commit it so the working tree is
        clean for the divergence half of the same `full` run."""
        path = self.plan_dir / "_workflow" / "implementation-plan.md"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
        if commit:
            rel = str(path.relative_to(self.path))
            self._git("add", rel)
            self._git("commit", "-q", "-m", "add implementation-plan.md")
        return path

    def write_track_file(self, track_num: int, body: str) -> Path:
        """Write a `plan/track-<N>.md` so the post-review walk resolves State C
        (track file present) rather than State A (track file absent)."""
        path = self.plan_dir / "_workflow" / "plan" / f"track-{track_num}.md"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
        rel = str(path.relative_to(self.path))
        self._git("add", rel)
        self._git("commit", "-q", "-m", f"add track-{track_num}.md")
        return path


# ---------------------------------------------------------------------------
# The shape-complete `minimal` stub, byte-faithful to create-plan/SKILL.md's
# `minimal` stub template (the plan-derivation block). The three state-bearing
# sections each carry their first top-level checkbox; `_stub` lets each test
# vary exactly the glyphs the transition under test flips, so the rest of the
# template stays the spec the state machine reads.
# ---------------------------------------------------------------------------


def _stub(
    *,
    plan_review_glyph: str,
    checklist_glyph: str,
    final_artifacts_glyph: str,
    stamp: str,
) -> str:
    """Render the `minimal` stub plan. Each `*_glyph` is the single checkbox
    body for that section's decision checkbox (`" "` for `[ ]`, `"x"` for
    `[x]`, etc.). `stamp` is the line-1 workflow-sha value (a real HEAD SHA so
    the drift half of `full` reads clean). The section text outside the glyphs
    matches the SKILL template verbatim, so this fixture pins the very template
    the resume state machine depends on."""
    return (
        f"<!-- workflow-sha: {stamp} -->\n"
        "# Adaptive tiering demo feature\n"
        "\n"
        "## High-level plan\n"
        "\n"
        "**Change tier:** minimal — matched categories: none\n"
        "\n"
        "## Checklist\n"
        f"- [{checklist_glyph}] Track 1: the single track that carries the whole change\n"
        "  > intro paragraph — high-level context; detailed description in plan/track-1.md\n"
        "  > **Scope:** ~1 files covering the demo change\n"
        "\n"
        "## Plan Review\n"
        f"- [{plan_review_glyph}] Plan review (consistency + structural) — autonomous;"
        " runs as the first phase of `/execute-tracks`\n"
        "\n"
        "## Final Artifacts\n"
        f"- [{final_artifacts_glyph}] Phase 4: Final artifacts (PR-description verdict"
        " summary; no `docs/adr/` entry — Gate 2 is the durable-ADR boundary)\n"
    )


def _state(proc: subprocess.CompletedProcess) -> dict:
    """Parse the `state` object from a `--mode full` run, asserting the run
    exited 0 first so a script error surfaces as a clear message rather than a
    downstream KeyError on the `state` key. The presence of a parseable JSON
    `state` object is itself the 'readable state' the step calls for."""
    assert proc.returncode == 0, (
        f"full mode should exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
    )
    obj = json.loads(proc.stdout)
    assert "state" in obj, f"full mode must emit a `state` key, got {sorted(obj.keys())}"
    return obj["state"]


# ---------------------------------------------------------------------------
# Test cases.
# ---------------------------------------------------------------------------


def test_minimal_stub_as_written_is_readable_state_0() -> None:
    """The stub exactly as `/create-plan` writes it for the `minimal` tier
    (`## Plan Review` `[ ]`, the single `## Checklist` track `[ ]`, `## Final
    Artifacts` `[ ]`) parses to a readable state, and that state is State 0:
    plan review has not passed, so resume routes to the autonomous-review gate.
    This is the load-bearing premise — the unchanged precheck reads the stub's
    decision checkboxes (not bare headings) and does not strand resume on a
    parse failure."""
    with StubPlanFixture() as fx:
        body = _stub(
            plan_review_glyph=" ",
            checklist_glyph=" ",
            final_artifacts_glyph=" ",
            stamp=fx.head_sha(),
        )
        fx.write_plan(body)
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "0", "substate": None}, (
            "an as-written minimal stub (Plan Review `[ ]`) must parse to a "
            f"readable State 0, got {state!r}"
        )


def test_minimal_stub_plan_review_flipped_is_state_a_or_c() -> None:
    """First post-review transition: with `## Plan Review` flipped to `[x]`
    (review passed) and no track file on disk, the precheck walks the single
    `## Checklist` track — the first `[ ]` track — and reports State A (track
    file absent). With a `plan/track-1.md` present it would report State C; the
    step's contract is State A OR C, so this asserts membership while pinning
    the no-track-file case to A and exercising the track-file case to C in the
    sibling test below."""
    with StubPlanFixture() as fx:
        body = _stub(
            plan_review_glyph="x",  # review passed
            checklist_glyph=" ",  # the single track is still the first [ ] track
            final_artifacts_glyph=" ",
            stamp=fx.head_sha(),
        )
        fx.write_plan(body)
        # No track file written, so the first [ ] track resolves to State A.
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state["phase"] in ("A", "C"), (
            "a passed Plan Review over the single open `## Checklist` track must "
            f"resolve to State A or C, got {state!r}"
        )
        assert state == {"phase": "A", "substate": None}, (
            "with no plan/track-1.md on disk the post-review walk pins State A, "
            f"got {state!r}"
        )


def test_minimal_stub_plan_review_flipped_with_track_file_is_state_c() -> None:
    """The State C half of the post-review transition: `## Plan Review` `[x]`,
    the single `## Checklist` track still `[ ]`, AND a `plan/track-1.md` present
    on disk. The walk parses the track number from the checklist entry's
    `Track <N>:` tail, finds the track file, and reports State C — the mid-track
    resume. The track body carries no `## Progress` section, so the sub-state is
    `decomposition-pending`; this test's focus is that the stub's single-track
    `## Checklist` drives the State A/C decision to C when the track file is
    present, completing the A-or-C contract from the sibling test."""
    with StubPlanFixture() as fx:
        body = _stub(
            plan_review_glyph="x",
            checklist_glyph=" ",
            final_artifacts_glyph=" ",
            stamp=fx.head_sha(),
        )
        fx.write_plan(body)
        # The single checklist entry is track number 1, so author its
        # plan/track-1.md to reach State C.
        fx.write_track_file(1, "# track one\n")
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state["phase"] == "C", (
            "the single open track with a track file present must resolve to "
            f"State C, got {state!r}"
        )


def test_minimal_stub_track_and_final_artifacts_flipped_is_state_d_or_done() -> None:
    """End-of-plan transition: with the single `## Checklist` track flipped to
    `[x]` (no `[ ]` track left) AND `## Final Artifacts` flipped to `[x]`, the
    precheck finds no open track, falls through to the Final-Artifacts decision,
    and reports Done. A `[ ]`/`[>]` Final-Artifacts checkbox would report State
    D (Phase 4 pending); the step's contract is State D OR Done, so this asserts
    membership while pinning the `[x]` case to Done and the still-open Phase 4
    case to D in the sibling test below. `## Plan Review` stays `[x]` because a
    completed plan kept it passed."""
    with StubPlanFixture() as fx:
        body = _stub(
            plan_review_glyph="x",
            checklist_glyph="x",  # the single track is now done
            final_artifacts_glyph="x",  # Phase 4 complete
            stamp=fx.head_sha(),
        )
        fx.write_plan(body)
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state["phase"] in ("D", "Done"), (
            "a done single track with Final Artifacts flipped must resolve to "
            f"State D or Done, got {state!r}"
        )
        assert state == {"phase": "Done", "substate": None}, (
            "with Final Artifacts `[x]` the end-of-plan walk pins Done, "
            f"got {state!r}"
        )


def test_minimal_stub_track_done_final_artifacts_open_is_state_d() -> None:
    """The State D half of the end-of-plan transition: the single `## Checklist`
    track is `[x]` (no open track) but `## Final Artifacts` is still `[ ]`
    (Phase 4 not yet done). The walk finds no open track and a not-`[x]`
    Final-Artifacts checkbox, so it reports State D — the Phase-4-pending resume,
    completing the D-or-Done contract from the sibling test."""
    with StubPlanFixture() as fx:
        body = _stub(
            plan_review_glyph="x",
            checklist_glyph="x",
            final_artifacts_glyph=" ",  # Phase 4 still pending
            stamp=fx.head_sha(),
        )
        fx.write_plan(body)
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "D", "substate": None}, (
            "a done single track with Final Artifacts still `[ ]` must resolve to "
            f"State D, got {state!r}"
        )


# ---------------------------------------------------------------------------
# Runner.
# ---------------------------------------------------------------------------


TESTS: List[Tuple[str, Callable[[], None]]] = [
    ("minimal_stub_as_written_is_readable_state_0", test_minimal_stub_as_written_is_readable_state_0),
    ("minimal_stub_plan_review_flipped_is_state_a_or_c", test_minimal_stub_plan_review_flipped_is_state_a_or_c),
    (
        "minimal_stub_plan_review_flipped_with_track_file_is_state_c",
        test_minimal_stub_plan_review_flipped_with_track_file_is_state_c,
    ),
    (
        "minimal_stub_track_and_final_artifacts_flipped_is_state_d_or_done",
        test_minimal_stub_track_and_final_artifacts_flipped_is_state_d_or_done,
    ),
    ("minimal_stub_track_done_final_artifacts_open_is_state_d", test_minimal_stub_track_done_final_artifacts_open_is_state_d),
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
