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
import os
import subprocess
import sys
import tempfile
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


def run_precheck(*args: str, cwd: Optional[Path] = None) -> subprocess.CompletedProcess:
    """Run the precheck script with the given args, capturing stdout/stderr.

    `check=False`: the error-path test deliberately expects a non-zero exit,
    so the caller inspects `returncode` rather than relying on raise-on-error.

    `cwd` runs the script inside a fixture git repo so the divergence and (in
    later steps) drift detection read that repo's state rather than the test
    runner's own checkout. When omitted, the script runs from the runner's cwd
    — fine for the mode-dispatch and emit-idiom cases, which do not read git.
    """
    return subprocess.run(
        ["bash", str(SCRIPT_PATH), *args],
        capture_output=True,
        text=True,
        check=False,
        cwd=str(cwd) if cwd is not None else None,
    )


# ---------------------------------------------------------------------------
# Reusable git-fixture builder.
#
# The existing `.claude/scripts/tests/` suite is fixture-file-only and carries
# no git-fixture infrastructure, so this builder is new scaffolding. It stands
# up a throwaway git repo under a temp dir and exposes the small surface the
# precheck's git-reading detection needs to exercise its branches:
#
#   * commit()             — author a commit on the current branch.
#   * add_bare_remote()    — create a local `file://` bare remote and push the
#                            current branch to it, leaving the working repo
#                            with an upstream tracking ref (clean / in-sync).
#   * advance_remote()     — push a new commit to the bare remote through a
#                            sibling clone, so the working repo falls behind.
#   * commit() after that  — advance local past the fork point, producing the
#                            both-non-zero divergence the precheck must detect.
#   * break_remote()       — repoint the remote URL at a nonexistent path so
#                            `git fetch` fails while `@{u}` still resolves
#                            (the fetch-failed skip path).
#   * orphan_branch()      — start an unrelated history with no common
#                            ancestor (reserved for the later merge-base-failed
#                            drift fixtures; harmless here).
#
# A `file://` bare remote is used (not a bare path) so the precheck's
# `git fetch` (no argument) reaches a real, fetchable remote — the divergence
# byte-source idiom fetches the upstream's remote before counting ahead/behind.
# Every helper routes git's own stdout/stderr to DEVNULL so progress lines
# never confuse a failure trace; only the precheck's JSON is asserted on.
# ---------------------------------------------------------------------------


GIT_ENV = {
    # Deterministic identity + isolation from the host's global git config so
    # the fixtures behave identically on any developer machine and on CI.
    "GIT_AUTHOR_NAME": "precheck-fixture",
    "GIT_AUTHOR_EMAIL": "precheck@fixture.invalid",
    "GIT_COMMITTER_NAME": "precheck-fixture",
    "GIT_COMMITTER_EMAIL": "precheck@fixture.invalid",
    "GIT_CONFIG_GLOBAL": "/dev/null",
    "GIT_CONFIG_SYSTEM": "/dev/null",
}


class GitFixture:
    """A throwaway git repo for exercising the precheck's git-reading paths.

    Use as a context manager so the temp dir is always cleaned up:

        with GitFixture() as fx:
            fx.commit("init")
            ...
            proc = run_precheck("--mode", "divergence-only", cwd=fx.path)
    """

    def __init__(self, default_branch: str = "main") -> None:
        self.default_branch = default_branch
        self._tmp: Optional[tempfile.TemporaryDirectory] = None
        self.path: Path = Path()  # set in __enter__
        self.bare_path: Optional[Path] = None

    def __enter__(self) -> "GitFixture":
        # A unique temp prefix keeps concurrent test runs from colliding.
        self._tmp = tempfile.TemporaryDirectory(prefix="precheck-git-")
        root = Path(self._tmp.name)
        self.path = root / "work"
        self.path.mkdir()
        # `-b <branch>` pins the initial branch so the working repo and the
        # bare remote agree on the default branch name regardless of the host's
        # `init.defaultBranch` setting (a mismatch breaks the divergence push).
        self._git("init", "-q", "-b", self.default_branch)
        return self

    def __exit__(self, *exc: object) -> None:
        if self._tmp is not None:
            self._tmp.cleanup()

    # -- low-level git runner ------------------------------------------------

    def _git(self, *args: str, cwd: Optional[Path] = None) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env.update(GIT_ENV)
        return subprocess.run(
            ["git", *args],
            cwd=str(cwd if cwd is not None else self.path),
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=True,
        )

    # -- builder surface -----------------------------------------------------

    def commit(self, message: str, *, filename: Optional[str] = None) -> None:
        """Author a commit on the current branch. A distinct filename per call
        keeps the working tree growing so successive commits are real."""
        name = filename or f"f-{message.replace(' ', '-')}.txt"
        (self.path / name).write_text(message + "\n", encoding="utf-8")
        self._git("add", name)
        self._git("commit", "-q", "-m", message)

    def add_bare_remote(self, remote: str = "origin") -> None:
        """Create a local `file://` bare remote, push the current branch, and
        set it as upstream — the clean / in-sync state where `@{u}` resolves
        and `git fetch` succeeds against a real remote."""
        assert self._tmp is not None
        self.bare_path = Path(self._tmp.name) / "remote.git"
        # Init the bare with the same default branch so its HEAD matches.
        self._git(
            "init", "-q", "--bare", "-b", self.default_branch, str(self.bare_path),
            cwd=self.path,
        )
        self._git("remote", "add", remote, f"file://{self.bare_path}")
        self._git("push", "-q", "-u", remote, self.default_branch)

    def advance_remote(self, message: str = "remote advance") -> None:
        """Push a new commit to the bare remote through a sibling clone, so the
        working repo's upstream moves ahead. Combined with a subsequent local
        commit (without fetching), this produces the both-non-zero divergence
        the precheck must report as detected=true."""
        assert self.bare_path is not None, "add_bare_remote() must run first"
        assert self._tmp is not None
        clone = Path(self._tmp.name) / "sibling-clone"
        self._git(
            "clone", "-q", "-b", self.default_branch, f"file://{self.bare_path}",
            str(clone), cwd=self.path,
        )
        (clone / "remote-side.txt").write_text(message + "\n", encoding="utf-8")
        self._git("add", "remote-side.txt", cwd=clone)
        self._git("commit", "-q", "-m", message, cwd=clone)
        self._git("push", "-q", "origin", self.default_branch, cwd=clone)

    def break_remote(self, remote: str = "origin") -> None:
        """Repoint the remote URL at a nonexistent bare so `git fetch` fails
        while `@{u}` still resolves — the fetch-failed skip path. The tracking
        ref already exists from add_bare_remote(), so the upstream guard passes
        and only the fetch guard trips."""
        dead = f"file:///nonexistent/precheck-dead-remote-{os.getpid()}.git"
        self._git("remote", "set-url", remote, dead)

    def orphan_branch(self, name: str, message: str = "orphan root") -> None:
        """Start an unrelated history (no common ancestor with the current
        branch). Reserved for the later merge-base-failed drift fixtures; it is
        defined here with the rest of the builder so the git-fixture surface
        lives in one place."""
        self._git("checkout", "-q", "--orphan", name)
        # `--orphan` carries the working tree forward; clear the index so the
        # orphan root is a clean unrelated commit.
        self._git("rm", "-rq", "--cached", ".")
        (self.path / f"orphan-{name}.txt").write_text(message + "\n", encoding="utf-8")
        self._git("add", f"orphan-{name}.txt")
        self._git("commit", "-q", "-m", message)


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
    array (the seam the no-drift normalization wiring fills later).

    Runs inside a clean GitFixture because `full` now performs divergence
    detection — a bare-cwd run would `git fetch` the runner's real upstream
    (network-dependent and slow on CI). The fixture keeps the shape assertion
    hermetic; its divergence content is covered separately above."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck("--mode", "full", cwd=fx.path)
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
    exits 0, and `actions_taken` is an empty array.

    Runs inside a clean GitFixture for the same reason as the full-mode shape
    test: divergence-only now runs detection, and a bare-cwd run would fetch
    the runner's real upstream."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck("--mode", "divergence-only", cwd=fx.path)
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
# Branch-divergence detection.
#
# Each test stands up a GitFixture and runs `--mode divergence-only` inside it
# (the cheapest mode that carries `divergence`; `full` reports the same object
# via the same detection path). The four cases map to the four byte-source
# states: clean / diverged / no-upstream / fetch-failed.
# ---------------------------------------------------------------------------


def _divergence(proc: subprocess.CompletedProcess) -> dict:
    """Parse the `divergence` object from a divergence-only run, asserting the
    run itself succeeded first so a script error surfaces as a clear message
    rather than a confusing KeyError downstream."""
    assert proc.returncode == 0, (
        f"divergence-only should exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
    )
    obj = json.loads(proc.stdout)
    return obj["divergence"]


def test_divergence_clean_in_sync() -> None:
    """A branch tracking a bare remote with no local or remote commits past the
    fork point is in sync: detected=false, ahead=0, behind=0, not skipped, and
    skip_reason null. This pins that the both-non-zero rule does not misfire on
    a clean checkout."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        div = _divergence(run_precheck("--mode", "divergence-only", cwd=fx.path))
        assert div["detected"] is False, f"clean must not be detected: {div!r}"
        assert div["ahead"] == 0, f"clean ahead should be 0, got {div['ahead']!r}"
        assert div["behind"] == 0, f"clean behind should be 0, got {div['behind']!r}"
        assert div["skipped"] is False, f"clean must not be skipped: {div!r}"
        assert div["skip_reason"] is None, (
            f"clean skip_reason should be JSON null, got {div['skip_reason']!r}"
        )


def test_divergence_detected_both_nonzero() -> None:
    """When both the local branch and its upstream advance past the fork point,
    ahead and behind are both non-zero and the precheck reports detected=true.
    The fixture advances the remote first (sibling clone push), then advances
    local without fetching, so the working repo's view of `@{u}` is one commit
    behind while local is one commit ahead."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        fx.advance_remote()  # remote moves +1
        fx.commit("local advance")  # local moves +1 on a different commit
        div = _divergence(run_precheck("--mode", "divergence-only", cwd=fx.path))
        assert div["detected"] is True, f"divergence must be detected: {div!r}"
        assert div["ahead"] == 1, f"ahead should be 1, got {div['ahead']!r}"
        assert div["behind"] == 1, f"behind should be 1, got {div['behind']!r}"
        assert div["skipped"] is False, f"diverged must not be skipped: {div!r}"
        assert div["skip_reason"] is None, (
            f"diverged skip_reason should be null, got {div['skip_reason']!r}"
        )


def test_divergence_no_upstream_skips() -> None:
    """A branch with no upstream tracking ref skips the check cleanly:
    skipped=true, skip_reason="no-upstream", detected=false, and the ahead /
    behind counts are JSON null (no count was computed). This is the upstream
    guard — `@{u}` does not resolve, so the fetch never runs."""
    with GitFixture() as fx:
        fx.commit("init")  # no add_bare_remote(), so no upstream
        div = _divergence(run_precheck("--mode", "divergence-only", cwd=fx.path))
        assert div["skipped"] is True, f"no-upstream must skip: {div!r}"
        assert div["skip_reason"] == "no-upstream", (
            f"skip_reason should be 'no-upstream', got {div['skip_reason']!r}"
        )
        assert div["detected"] is False, f"no-upstream must not be detected: {div!r}"
        assert div["ahead"] is None, (
            f"no-upstream ahead should be JSON null, got {div['ahead']!r}"
        )
        assert div["behind"] is None, (
            f"no-upstream behind should be JSON null, got {div['behind']!r}"
        )


def test_divergence_fetch_failed_skips() -> None:
    """When `@{u}` resolves but `git fetch` fails (remote URL repointed at a
    nonexistent path), the fetch guard trips: skipped=true,
    skip_reason="fetch-failed", detected=false, and counts JSON null. This is
    the offline / removed-remote skip path, distinct from no-upstream."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()  # sets a real upstream so @{u} resolves
        fx.break_remote()  # then make fetch fail
        div = _divergence(run_precheck("--mode", "divergence-only", cwd=fx.path))
        assert div["skipped"] is True, f"fetch-failed must skip: {div!r}"
        assert div["skip_reason"] == "fetch-failed", (
            f"skip_reason should be 'fetch-failed', got {div['skip_reason']!r}"
        )
        assert div["detected"] is False, f"fetch-failed must not be detected: {div!r}"
        assert div["ahead"] is None, (
            f"fetch-failed ahead should be JSON null, got {div['ahead']!r}"
        )
        assert div["behind"] is None, (
            f"fetch-failed behind should be JSON null, got {div['behind']!r}"
        )


def test_divergence_object_present_in_full_mode() -> None:
    """`--mode full` reports the same divergence object via the same detection
    path — a clean fixture shows detected=false / ahead=0 / behind=0 in the
    full blob, confirming divergence is wired into full mode, not just
    divergence-only."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck("--mode", "full", cwd=fx.path)
        assert proc.returncode == 0, (
            f"full should exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
        )
        obj = json.loads(proc.stdout)
        div = obj["divergence"]
        assert div is not None, "full mode must carry a divergence object, not null"
        assert div["detected"] is False and div["ahead"] == 0 and div["behind"] == 0, (
            f"clean full-mode divergence mismatch: {div!r}"
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
    ("divergence_clean_in_sync", test_divergence_clean_in_sync),
    ("divergence_detected_both_nonzero", test_divergence_detected_both_nonzero),
    ("divergence_no_upstream_skips", test_divergence_no_upstream_skips),
    ("divergence_fetch_failed_skips", test_divergence_fetch_failed_skips),
    ("divergence_object_present_in_full_mode", test_divergence_object_present_in_full_mode),
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
