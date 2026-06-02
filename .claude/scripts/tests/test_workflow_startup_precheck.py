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
import shutil
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

    def _git_out(self, *args: str, cwd: Optional[Path] = None) -> str:
        """Run git and capture stdout (stripped). Used where the fixture needs
        the command's output — e.g. resolving HEAD's SHA to stamp an artifact
        with a *real* commit so the Phase 2 merge-base fold resolves it."""
        env = dict(os.environ)
        env.update(GIT_ENV)
        proc = subprocess.run(
            ["git", *args],
            cwd=str(cwd if cwd is not None else self.path),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            check=True,
        )
        return proc.stdout.strip()

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

    def orphan_branch(self, name: str, message: str = "orphan root") -> str:
        """Start an unrelated history (no common ancestor with the current
        branch) and return the orphan root's SHA. Used by the merge-base-failed
        drift fixture: a stamp pointing at this orphan SHA and another stamp on
        the main line share no reachable common ancestor, so the Phase 2 fold's
        `git merge-base` fails.

        `--orphan` carries the prior branch's working tree forward as untracked
        files; this would block a later `git checkout` back to the default
        branch (git refuses to overwrite untracked files). So after clearing the
        index, wipe the working tree (`git checkout .` cannot help on an orphan
        with no tracked content, so files are removed directly) before authoring
        the orphan root. The default branch's files are restored when the
        fixture checks it back out."""
        self._git("checkout", "-q", "--orphan", name)
        # Clear the index so the orphan root carries no tracked content from the
        # prior branch, then physically remove the carried-forward working-tree
        # files so a later `checkout(default_branch)` is not blocked by them.
        self._git("rm", "-rfq", "--cached", ".")
        for child in self.path.iterdir():
            if child.name == ".git":
                continue
            if child.is_dir():
                shutil.rmtree(child)
            else:
                child.unlink()
        (self.path / f"orphan-{name}.txt").write_text(message + "\n", encoding="utf-8")
        self._git("add", f"orphan-{name}.txt")
        self._git("commit", "-q", "-m", message)
        return self.head_sha()

    # -- Phase 2 fold + `git log` surface ------------------------------------

    def head_sha(self) -> str:
        """The current branch HEAD's full 40-hex SHA. Used to stamp a plan
        artifact with a *real* commit so the Phase 2 merge-base fold (which runs
        `git merge-base` over the stamp set) resolves it instead of failing on a
        synthetic SHA."""
        return self._git_out("rev-parse", "HEAD")

    def checkout(self, branch: str) -> None:
        """Switch back to an existing branch (e.g. from an orphan branch to the
        default branch). The drift walk resolves PLAN_DIR from the current
        branch, so a fixture that briefly visited an orphan branch must return
        to the default branch before the precheck runs."""
        self._git("checkout", "-q", branch)

    def workflow_commit(self, message: str, *, relpath: Optional[str] = None) -> None:
        """Author a commit that touches a path under `.claude/workflow/` — a path
        the drift `git log $BASE_SHA..HEAD -- .claude/workflow/ .claude/skills/`
        range watches. A run of these between the stamp base and HEAD is what
        makes the Phase 2 range non-empty (the drift-detected case). A distinct
        relpath per call keeps successive commits real."""
        rel = relpath or f".claude/workflow/wf-{message.replace(' ', '-')}.md"
        path = self.path / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(message + "\n", encoding="utf-8")
        self._git("add", rel)
        self._git("commit", "-q", "-m", message)

    def staged_workflow_commit(self, message: str = "staged wf edit") -> None:
        """Author a commit that touches ONLY the staged subtree under
        `<plan_dir>/_workflow/staged-workflow/.claude/workflow/`. The drift
        range's trailing-slash pathspecs (`.claude/workflow/`, `.claude/skills/`)
        must NOT match this path — it sits under `docs/adr/.../staged-workflow/`,
        a different prefix — so a commit touching only the staged subtree stays
        out of the `git log` range (the staged-subtree-exclusion invariant)."""
        rel = (
            f"docs/adr/{self.default_branch}/_workflow/staged-workflow/"
            f".claude/workflow/staged-{message.replace(' ', '-')}.md"
        )
        path = self.path / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(message + "\n", encoding="utf-8")
        self._git("add", rel)
        self._git("commit", "-q", "-m", message)

    # -- drift-walk plan-artifact surface ------------------------------------

    @property
    def plan_dir(self) -> Path:
        """The active plan dir the precheck's drift walk resolves: the precheck
        builds `docs/adr/<branch>` from the current branch name (§ 1.6(g)), so a
        fixture's plan artifacts must live under the matching branch dir."""
        return self.path / "docs" / "adr" / self.default_branch

    def plan_artifact(
        self, relpath: str, *, stamp: Optional[str], body: str = "# Title\n"
    ) -> Path:
        """Author a `_workflow/` plan artifact for the drift walk to classify.

        `relpath` is relative to `<plan_dir>/_workflow/` (e.g.
        `implementation-plan.md` or `plan/track-1.md`). `stamp` is a 40-hex SHA
        written as the line-1 `<!-- workflow-sha: <sha> -->` comment (the
        stamped case) or None (the unstamped case — the file starts with the
        `body` and the walk classifies it unstamped). The file is committed so
        the working tree is clean for the divergence half of the same run.
        """
        path = self.plan_dir / "_workflow" / relpath
        path.parent.mkdir(parents=True, exist_ok=True)
        if stamp is not None:
            content = f"<!-- workflow-sha: {stamp} -->\n{body}"
        else:
            content = body
        path.write_text(content, encoding="utf-8")
        rel = path.relative_to(self.path)
        self._git("add", str(rel))
        self._git("commit", "-q", "-m", f"add {relpath}")
        return path

    def handoff(self, name: str = "handoff-test.md") -> Path:
        """Author a transient `handoff-*.md` under the plan's `_workflow/` — a
        non-stampable artifact. Used by the empty-input drift fixture: a
        `_workflow/` holding only a handoff has no stampable artifact, so both
        the stamped and unstamped sets stay empty (the silent no-drift path)."""
        path = self.plan_dir / "_workflow" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("# Handoff\n", encoding="utf-8")
        rel = path.relative_to(self.path)
        self._git("add", str(rel))
        self._git("commit", "-q", "-m", f"add {name}")
        return path


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
# Drift Phase 1 — artifact walk + classification.
#
# The precheck's `full` mode walks the active plan's `_workflow/**` artifacts
# (byte-copied from conventions.md § 1.6(h)) and classifies each as stamped or
# unstamped. Phase 1 resolves three outcomes: empty-input (no stampable
# artifact -> silent no-drift), any unstamped (-> drift detected,
# kind="unstamped"), and all-stamped (-> kind="stamped", with the fold scalars
# left for the Phase 2 merge-base fold + `git log` in a later step). Each
# fixture sets up the matching plan-artifact state inside a GitFixture and
# asserts the `drift` object. A clean upstream is added so the divergence half
# of the same `full` run does not fetch the runner's real remote.
# ---------------------------------------------------------------------------


# A throwaway but well-formed 40-hex SHA distinct from SYNTHETIC_SHA, used to
# stamp the Phase 1 unstamped fixture's stamped sibling. The Phase 1 walk
# classifies on the stamp's presence and anchored shape, not on whether the SHA
# resolves to a real commit; the Phase 2 fold fixtures use real commit SHAs
# (head_sha / orphan_branch) instead, since the fold runs `git merge-base`.
STAMP_SHA = "b" * 40


def _drift(proc: subprocess.CompletedProcess) -> dict:
    """Parse the `drift` object from a full-mode run, asserting the run itself
    succeeded first so a script error surfaces clearly rather than as a
    downstream KeyError."""
    assert proc.returncode == 0, (
        f"full should exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
    )
    obj = json.loads(proc.stdout)
    return obj["drift"]


def test_drift_all_stamped_classifies_stamped_then_folds() -> None:
    """An all-stamped plan (two artifacts, both carrying a line-1 workflow-sha
    pointing at the same real commit) classifies as kind="stamped" and the
    Phase 2 fold runs end to end. With both stamps at the same HEAD commit and
    no workflow-path commit after it, the fold derives that commit as BASE_SHA
    and `git log BASE_SHA..HEAD` is empty: detected=false, base_sha filled (the
    fold ran), commit_count=0, first_commits=[]. This is the closed Phase-1/
    Phase-2 seam — Phase 1 classifies, Phase 2 folds — using a real stamp so the
    fold resolves it. normalization_landed stays hard-false in this track."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        # Both stamps point at the same real commit so the pairwise fold resolves
        # to that commit (merge-base of a SHA with itself is the SHA), exercising
        # the multi-stamp fold without a merge-base failure.
        fx.plan_artifact("implementation-plan.md", stamp=head)
        fx.plan_artifact("plan/track-1.md", stamp=head)
        drift = _drift(run_precheck("--mode", "full", cwd=fx.path))
        assert drift["kind"] == "stamped", f"all-stamped kind should be 'stamped': {drift!r}"
        assert drift["detected"] is False, (
            f"all stamps at HEAD with no later workflow commit is no drift: {drift!r}"
        )
        # The fold ran: base_sha is the folded commit (not null), commit_count is
        # 0, and first_commits is empty.
        assert drift["base_sha"] == head, (
            f"base_sha should be the folded stamp base {head!r}, got {drift['base_sha']!r}"
        )
        assert drift["commit_count"] == 0, (
            f"empty-range commit_count should be 0, got {drift['commit_count']!r}"
        )
        assert drift["first_commits"] == [], (
            f"empty-range first_commits should be [], got {drift['first_commits']!r}"
        )
        assert drift["normalization_landed"] is False, (
            f"normalization_landed is hard-false in this track: {drift!r}"
        )


def test_drift_unstamped_detects_drift_kind_unstamped() -> None:
    """A plan with any unstamped artifact short-circuits to drift detected with
    kind="unstamped": no fold runs, so the fold scalars are JSON null. Asserted
    via `is None` (a strict null check), the regression the empty->null idiom
    guards against. The unstamped file here is implementation-plan.md with no
    line-1 stamp comment; a co-present stamped track file does not suppress the
    unstamped signal (any unstamped artifact is sufficient)."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        fx.plan_artifact("implementation-plan.md", stamp=None)  # unstamped
        fx.plan_artifact("plan/track-1.md", stamp=STAMP_SHA)  # stamped sibling
        drift = _drift(run_precheck("--mode", "full", cwd=fx.path))
        assert drift["detected"] is True, f"unstamped must detect drift: {drift!r}"
        assert drift["kind"] == "unstamped", f"kind should be 'unstamped': {drift!r}"
        assert drift["base_sha"] is None, (
            f"unstamped base_sha should be JSON null, got {drift['base_sha']!r}"
        )
        assert drift["commit_count"] is None, (
            f"unstamped commit_count should be JSON null, got {drift['commit_count']!r}"
        )
        assert drift["first_commits"] == [], (
            f"unstamped first_commits should be [], got {drift['first_commits']!r}"
        )


def test_drift_empty_input_silent_no_drift_kind_null() -> None:
    """A `_workflow/` holding only a transient handoff-*.md has no stampable
    artifact, so both the stamped and unstamped sets stay empty: the silent
    no-drift path. detected=false and kind is JSON null — distinct from the
    all-stamped clean case (kind="stamped") and from the unstamped case
    (kind="unstamped"). The null kind is asserted with `is None` so the
    empty-input case is told apart from a literal "stamped"/"unstamped"
    string."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        fx.handoff()  # only a handoff under _workflow/, no stampable artifact
        drift = _drift(run_precheck("--mode", "full", cwd=fx.path))
        assert drift["detected"] is False, f"empty-input must not detect drift: {drift!r}"
        assert drift["kind"] is None, (
            f"empty-input kind should be JSON null (no classification), got {drift['kind']!r}"
        )
        assert drift["base_sha"] is None, f"empty-input base_sha should be null: {drift!r}"
        assert drift["commit_count"] is None, (
            f"empty-input commit_count should be null: {drift!r}"
        )


# ---------------------------------------------------------------------------
# Drift Phase 2 — pairwise merge-base fold + `git log`.
#
# When every artifact is stamped, the precheck folds the stamp set pairwise
# through `git merge-base` to derive BASE_SHA (the oldest stamp reachable from
# HEAD), then ranges `git log $BASE_SHA..HEAD` against the workflow pathspecs
# (`.claude/workflow/`, `.claude/skills/`). The fixtures here stamp artifacts
# with *real* commit SHAs (head_sha / orphan_branch) so the fold resolves them,
# unlike the Phase 1 fixtures' synthetic SHAs. Four cases:
#
#   * drift detected   — workflow-path commits sit between the stamp base and
#                        HEAD: detected=true, base_sha filled, commit_count and
#                        first_commits (oldest first) reflect those commits.
#   * no-drift empty   — the fold resolves but no workflow-path commit sits in
#                        the range: detected=false, base_sha filled,
#                        commit_count=0, first_commits=[].
#   * merge-base fail  — two stamps with no reachable common ancestor:
#                        kind="merge-base-failed", detected=true, fold scalars
#                        null.
#   * staged exclusion — a commit touching only the staged subtree under
#                        `docs/adr/.../staged-workflow/.claude/workflow/` stays
#                        out of the range (the trailing-slash pathspec excludes
#                        it by prefix difference).
# ---------------------------------------------------------------------------


def test_drift_phase2_detected_reports_range() -> None:
    """An all-stamped plan whose single stamp points at a real commit, with two
    `.claude/workflow/` commits sitting between that stamp base and HEAD, is
    drift: the Phase 2 fold derives BASE_SHA from the stamp, and
    `git log BASE_SHA..HEAD -- .claude/workflow/ .claude/skills/` returns the two
    workflow commits. detected=true, base_sha is the stamp's commit, commit_count
    is 2, and first_commits lists them oldest-first (the `--reverse` order) with
    full subjects. The plan-artifact commit itself touches `docs/adr/...`, not a
    watched path, so it does not inflate the count."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        base = fx.head_sha()  # the commit the stamp points at
        # Two workflow-path commits land *after* the stamp base, so they fall in
        # the BASE_SHA..HEAD range the drift `git log` watches.
        fx.workflow_commit("first workflow change")
        fx.workflow_commit("second workflow change with spaces")
        # The plan artifact is stamped with the real base SHA; its own commit
        # touches docs/adr/... (not a watched path) so it stays out of the range.
        fx.plan_artifact("implementation-plan.md", stamp=base)
        drift = _drift(run_precheck("--mode", "full", cwd=fx.path))
        assert drift["detected"] is True, f"workflow commits in range must detect drift: {drift!r}"
        assert drift["kind"] == "stamped", f"kind should be 'stamped': {drift!r}"
        assert drift["base_sha"] == base, (
            f"base_sha should be the folded stamp base {base!r}, got {drift['base_sha']!r}"
        )
        assert drift["commit_count"] == 2, (
            f"commit_count should count the two workflow commits, got {drift['commit_count']!r}"
        )
        subjects = [c["subject"] for c in drift["first_commits"]]
        assert subjects == ["first workflow change", "second workflow change with spaces"], (
            f"first_commits should list both subjects oldest-first, whole: {drift['first_commits']!r}"
        )
        # Each entry carries a short sha and the verbatim subject (subjects with
        # spaces stay in the one field, not split across array elements).
        for entry in drift["first_commits"]:
            assert set(entry.keys()) == {"sha", "subject"}, (
                f"each first_commits entry is {{sha, subject}}, got {entry!r}"
            )
            assert entry["sha"], f"first_commits sha should be non-empty: {entry!r}"


def test_drift_phase2_empty_range_no_drift_count_zero() -> None:
    """An all-stamped plan whose stamp points at HEAD itself (no workflow commit
    sits after it) folds to a BASE_SHA equal to HEAD, so
    `git log BASE_SHA..HEAD` is empty: no drift in the strict sense. detected is
    false, but base_sha is still reported (the fold ran), commit_count is 0, and
    first_commits is the empty array. This is the no-drift read distinct from the
    Phase 1 all-stamped seam (where the fold had not yet run and base_sha was
    null) — here the fold ran and base_sha is filled."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        # Stamp at HEAD, then commit only the plan artifact (a docs/adr path,
        # not watched). No workflow-path commit lands after the stamp, so the
        # range is empty.
        head = fx.head_sha()
        fx.plan_artifact("implementation-plan.md", stamp=head)
        drift = _drift(run_precheck("--mode", "full", cwd=fx.path))
        assert drift["detected"] is False, f"empty range must not detect drift: {drift!r}"
        assert drift["kind"] == "stamped", f"kind should be 'stamped': {drift!r}"
        assert drift["base_sha"] == head, (
            f"base_sha should be the folded stamp base {head!r} (fold ran), got "
            f"{drift['base_sha']!r}"
        )
        assert drift["commit_count"] == 0, (
            f"empty-range commit_count should be 0, got {drift['commit_count']!r}"
        )
        assert drift["first_commits"] == [], (
            f"empty-range first_commits should be [], got {drift['first_commits']!r}"
        )


def test_drift_phase2_merge_base_failed_kind_scalars_null() -> None:
    """Two stamped artifacts whose stamps point at commits with no reachable
    common ancestor (the default branch's HEAD and an orphan branch's root) make
    the Phase 2 pairwise `git merge-base` fail. The drift gate uses the `break`
    failure mode: it short-circuits to kind="merge-base-failed", signals drift
    (detected=true), and leaves the fold scalars JSON null (base_sha,
    commit_count) — the §1.6(c) recovery prompt that resolves the failing pair
    stays agent-side. The null scalars are asserted with `is None` so the
    merge-base-failed short-circuit is told apart from a resolved fold."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        main_sha = fx.head_sha()
        # An orphan branch root shares no history with the default branch.
        orphan_sha = fx.orphan_branch("unrelated")
        # The drift walk resolves PLAN_DIR from the current branch, so return to
        # the default branch (where the plan artifacts must live) before running.
        fx.checkout(fx.default_branch)
        # Two stamps with no common ancestor: the fold's `git merge-base
        # main_sha orphan_sha` fails, short-circuiting to merge-base-failed.
        fx.plan_artifact("implementation-plan.md", stamp=main_sha)
        fx.plan_artifact("plan/track-1.md", stamp=orphan_sha)
        drift = _drift(run_precheck("--mode", "full", cwd=fx.path))
        assert drift["detected"] is True, f"merge-base failure must detect drift: {drift!r}"
        assert drift["kind"] == "merge-base-failed", (
            f"kind should be 'merge-base-failed', got {drift['kind']!r}"
        )
        assert drift["base_sha"] is None, (
            f"merge-base-failed base_sha should be JSON null, got {drift['base_sha']!r}"
        )
        assert drift["commit_count"] is None, (
            f"merge-base-failed commit_count should be JSON null, got {drift['commit_count']!r}"
        )
        assert drift["first_commits"] == [], (
            f"merge-base-failed first_commits should be [], got {drift['first_commits']!r}"
        )


def test_drift_phase2_staged_subtree_excluded_from_range() -> None:
    """A commit touching ONLY the staged subtree under
    `docs/adr/<branch>/_workflow/staged-workflow/.claude/workflow/` must not
    appear in the drift `git log` range. The range's pathspecs (`.claude/workflow/`,
    `.claude/skills/`) carry trailing slashes and match those top-level
    directories, not the staged copy under `docs/adr/.../staged-workflow/` (a
    different path prefix). With the stamp at the base and only a staged-subtree
    commit after it, the range is empty: detected=false, commit_count=0. This
    pins the staged-subtree-exclusion invariant the byte-source relies on so a
    workflow-modifying branch's staged edits do not self-report as drift."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        base = fx.head_sha()
        # A commit touching only the staged subtree — NOT a watched path.
        fx.staged_workflow_commit("edit dispatch rule")
        fx.plan_artifact("implementation-plan.md", stamp=base)
        drift = _drift(run_precheck("--mode", "full", cwd=fx.path))
        assert drift["detected"] is False, (
            f"a staged-subtree-only commit must not register as drift: {drift!r}"
        )
        assert drift["kind"] == "stamped", f"kind should be 'stamped': {drift!r}"
        assert drift["commit_count"] == 0, (
            f"staged-subtree commit must be excluded; commit_count should be 0, got "
            f"{drift['commit_count']!r}"
        )
        assert drift["first_commits"] == [], (
            f"staged-subtree commit must be excluded; first_commits should be [], got "
            f"{drift['first_commits']!r}"
        )


def test_drift_phase2_real_workflow_commit_vs_staged_distinguished() -> None:
    """Sanity pairing for the exclusion: a real `.claude/workflow/` commit AND a
    staged-subtree commit both land after the stamp base, but only the real one
    enters the range. This confirms the exclusion is selective (it drops the
    staged copy) rather than dropping everything — guarding against a pathspec
    typo that would silently match nothing."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        base = fx.head_sha()
        fx.workflow_commit("real workflow edit")  # watched -> in range
        fx.staged_workflow_commit("staged copy edit")  # excluded -> out of range
        fx.plan_artifact("implementation-plan.md", stamp=base)
        drift = _drift(run_precheck("--mode", "full", cwd=fx.path))
        assert drift["detected"] is True, f"the real workflow commit must detect drift: {drift!r}"
        assert drift["commit_count"] == 1, (
            f"only the real workflow commit counts (staged excluded), got "
            f"{drift['commit_count']!r}"
        )
        subjects = [c["subject"] for c in drift["first_commits"]]
        assert subjects == ["real workflow edit"], (
            f"only the real workflow commit appears in first_commits, got {subjects!r}"
        )


# ---------------------------------------------------------------------------
# Pending mid-phase handoff scan.
#
# `full` mode runs `ls -t <plan_dir>/_workflow/handoff-*.md` (byte-source:
# workflow.md § Startup Protocol step 4) and reports the file BASENAMES in
# `handoffs`, most-recent-first by mtime so the agent resumes the newest pause
# first. Two cases: a plan dir holding multiple handoffs (the basenames appear
# in mtime order) and a plan dir holding none (`handoffs` is the empty array —
# the empty-safe path, the common session-start case). Each runs inside a clean
# GitFixture so the divergence half of the same `full` run does not fetch the
# runner's real remote.
# ---------------------------------------------------------------------------


def _set_mtime(path: Path, when: float) -> None:
    """Force a file's mtime so `ls -t` ordering is deterministic regardless of
    how fast the fixture authored the files. Without this, two handoffs written
    in the same wall-clock second could sort either way and the order assertion
    would flake."""
    os.utime(path, (when, when))


def test_handoffs_reported_in_mtime_order_newest_first() -> None:
    """Three handoff files under the active plan's `_workflow/` are reported in
    `handoffs` as basenames, most-recent-first by mtime. The fixture forces
    distinct, ascending mtimes (oldest -> newest), so the expected `ls -t` order
    is the reverse: newest basename first. This pins that the script preserves
    `ls -t`'s mtime ordering into the JSON array (the resume protocol depends on
    processing the newest pause first) and strips the path to the basename."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        # Author three handoffs, then stamp distinct ascending mtimes so the
        # ordering is unambiguous. base is an arbitrary fixed epoch; +10s steps
        # keep the three well apart even on coarse-mtime filesystems.
        oldest = fx.handoff("handoff-oldest.md")
        middle = fx.handoff("handoff-middle.md")
        newest = fx.handoff("handoff-newest.md")
        base = 1_700_000_000.0
        _set_mtime(oldest, base)
        _set_mtime(middle, base + 10)
        _set_mtime(newest, base + 20)
        proc = run_precheck("--mode", "full", cwd=fx.path)
        assert proc.returncode == 0, (
            f"full should exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
        )
        obj = json.loads(proc.stdout)
        # ls -t is newest-first, so the array reverses the authored mtime order.
        assert obj["handoffs"] == [
            "handoff-newest.md",
            "handoff-middle.md",
            "handoff-oldest.md",
        ], f"handoffs must be ls -t (newest-first) basenames, got {obj['handoffs']!r}"


def test_handoffs_empty_when_none_present() -> None:
    """A plan dir that exists (carries a stamped artifact) but holds no
    `handoff-*.md` reports `handoffs` as the empty array — the empty-safe scan
    path. Distinct from `test_full_mode_pinned_shape`, which asserts `[]` on a
    fixture with no plan dir at all: here the `_workflow/` dir is present and
    non-empty, so the glob genuinely matches nothing rather than the parent dir
    being absent. This pins that `ls` matching no file collapses to `[]`, not a
    one-element array of the unexpanded glob or a script abort."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        # A stamped plan artifact makes the _workflow/ dir exist and be
        # non-empty, but it is not a handoff-*.md, so the handoff glob matches
        # nothing.
        fx.plan_artifact("implementation-plan.md", stamp=fx.head_sha())
        proc = run_precheck("--mode", "full", cwd=fx.path)
        assert proc.returncode == 0, (
            f"full should exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
        )
        obj = json.loads(proc.stdout)
        assert obj["handoffs"] == [], (
            f"no handoff present must yield handoffs=[], got {obj['handoffs']!r}"
        )


def test_state_stub_is_json_null_in_full_mode() -> None:
    """`full` mode emits `state` as JSON `null` — the seam the state parser
    fills later. Asserted with `is None` (a strict null check, not a falsy
    check) so a regression to the string "null" or an empty object is caught.
    Pinning the stub as exactly JSON null keeps the later state-parser change a
    clean null -> object diff: the stub shape must be JSON null here, not an
    empty object or the string "null"."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck("--mode", "full", cwd=fx.path)
        assert proc.returncode == 0, (
            f"full should exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
        )
        obj = json.loads(proc.stdout)
        assert obj["state"] is None, (
            f"full mode state must be JSON null (the state-parser seam), got "
            f"{obj['state']!r}"
        )


# ---------------------------------------------------------------------------
# § 1.6(h) source-extraction conformance.
#
# The drift walk in the script is byte-copied from conventions.md § 1.6(h)
# "Phase 1 walk bash block" (with the anchored § 1.6(a1) value-extraction
# regex). This test is a SOURCE comparison, not a behavior smoke test: it
# extracts the canonical walk block from conventions.md and the script's walk
# from the script, then asserts they share the same `ls`-glob set and the same
# anchored regex. A future § 1.6(h) edit the script misses (a new artifact kind
# added to the glob, the regex de-anchored) fails the suite even when every
# behavior fixture still passes against the stale walk.
#
# The one sanctioned divergence is the `migrate-range` walk's `STAMPED_PAIRS`
# `$f=$SHA` pairing line (migrate-workflow/SKILL.md Step 2 names it explicitly):
# that line lands in a later step and is whitelisted here so the conformance
# check does not flag it as drift from § 1.6(h).
# ---------------------------------------------------------------------------


CONVENTIONS_PATH = REPO_ROOT / ".claude" / "workflow" / "conventions.md"

# The four artifact globs the § 1.6(h) walk enumerates, in order. The
# conformance check confirms both the canonical block and the script's walk
# enumerate exactly this set (modulo the `$PLAN_DIR` / `<dir-name>` prefix the
# byte-source declares as substituted at invocation time).
EXPECTED_GLOB_TAILS = [
    "_workflow/implementation-plan.md",
    "_workflow/design.md",
    "_workflow/design-mechanics.md",
    "_workflow/plan/track-*.md",
]

# The anchored § 1.6(a1) value-extraction regex. Both the script and the
# canonical block must carry it verbatim; the unanchored `[0-9a-f]{40}` variant
# (no `workflow-sha:` anchor) is explicitly rejected by § 1.6(a1).
ANCHORED_REGEX_FRAGMENT = "grep -oE 'workflow-sha: [0-9a-f]{40}' | grep -oE '[0-9a-f]{40}$'"


def _extract_conventions_h_block() -> str:
    """Extract the bash fenced block under `### (h) Phase 1 walk bash block`
    in conventions.md — the canonical byte-source the script's walk copies."""
    text = CONVENTIONS_PATH.read_text(encoding="utf-8")
    lines = text.splitlines()
    # Locate the (h) heading, then the first ```bash fence after it.
    h_idx = next(
        (i for i, ln in enumerate(lines) if ln.startswith("### (h) Phase 1 walk")),
        None,
    )
    assert h_idx is not None, "could not find '### (h) Phase 1 walk' heading in conventions.md"
    fence_start = next(
        (i for i in range(h_idx, len(lines)) if lines[i].strip() == "```bash"),
        None,
    )
    assert fence_start is not None, "could not find ```bash fence under § 1.6(h)"
    fence_end = next(
        (i for i in range(fence_start + 1, len(lines)) if lines[i].strip() == "```"),
        None,
    )
    assert fence_end is not None, "unterminated ```bash fence under § 1.6(h)"
    return "\n".join(lines[fence_start + 1 : fence_end])


def _extract_script_walk() -> str:
    """Extract the script's Phase 1 walk: the `for f in $(ls ...)` loop through
    its closing `done`. This is the region the byte-copy contract governs."""
    text = SCRIPT_PATH.read_text(encoding="utf-8")
    lines = text.splitlines()
    start = next(
        (i for i, ln in enumerate(lines) if "for f in $(ls " in ln),
        None,
    )
    assert start is not None, "could not find the `for f in $(ls ...)` walk loop in the script"
    end = next(
        (i for i in range(start, len(lines)) if lines[i].strip() == "done"),
        None,
    )
    assert end is not None, "could not find the walk loop's closing `done` in the script"
    return "\n".join(lines[start : end + 1])


def _glob_tails(block: str) -> List[str]:
    """The artifact-glob tails an enumerate-and-classify block lists, in order,
    normalized so the comparison ignores byte-source incidentals that are not
    drift:

      * the `$PLAN_DIR` / `docs/adr/<dir-name>` prefix — the byte-source
        declares it is substituted at invocation time, so the script
        legitimately resolves it from the branch while conventions.md carries
        the literal placeholder;
      * shell quotes — the canonical idiom closes the quote mid-path
        (`"$PLAN_DIR/_workflow/plan/"track-*.md`), so a raw substring match on
        the un-quoted tail would spuriously miss it;
      * the trailing `2>/dev/null); do` and line-continuation backslashes.

    The walk's `ls` lines are the only lines mentioning `_workflow/`; each is
    reduced to the `_workflow/...` suffix after stripping quotes and the prefix.
    """
    tails: List[str] = []
    for raw in block.splitlines():
        if "_workflow/" not in raw:
            continue
        # Drop quotes, the line continuation, and the trailing ls tail so the
        # remaining token is a clean glob path.
        cleaned = raw.replace('"', "").replace("\\", "").strip()
        cleaned = cleaned.split("2>/dev/null")[0].strip()
        # Keep only the `_workflow/...` suffix (drops the $PLAN_DIR / docs/adr
        # prefix that differs between the placeholder and the resolved form).
        idx = cleaned.find("_workflow/")
        if idx == -1:
            continue
        tail = cleaned[idx:].strip()
        # The continuation line that opens the loop (`for f in $(ls ...`) and
        # the closing `); do` fragment can leave trailing tokens; the four
        # artifact globs are exactly the lines whose cleaned tail is one of the
        # expected entries, so filtering to EXPECTED_GLOB_TAILS membership here
        # would mask drift. Instead keep every `_workflow/` tail verbatim and
        # let the caller compare the full ordered list against the expected set.
        tails.append(tail)
    return tails


def test_conformance_glob_set_matches_canonical() -> None:
    """The script's walk enumerates exactly the four § 1.6(h) artifact globs,
    in the same order as the canonical block — a source comparison that catches
    a glob the script forgot to copy or an extra glob it added."""
    canonical = _extract_conventions_h_block()
    script_walk = _extract_script_walk()
    canonical_tails = _glob_tails(canonical)
    script_tails = _glob_tails(script_walk)
    assert canonical_tails == EXPECTED_GLOB_TAILS, (
        "canonical § 1.6(h) block no longer enumerates the expected glob set; "
        f"found {canonical_tails!r}. If § 1.6(h) intentionally changed, update "
        "EXPECTED_GLOB_TAILS and the script's walk together."
    )
    assert script_tails == canonical_tails, (
        "the script's Phase 1 walk glob set drifted from § 1.6(h); "
        f"script lists {script_tails!r}, canonical lists {canonical_tails!r}"
    )


def test_conformance_anchored_regex_matches_canonical() -> None:
    """Both the canonical § 1.6(h) block and the script's walk carry the
    anchored § 1.6(a1) value-extraction regex verbatim. Pinning the anchored
    form catches a de-anchoring to the bare `[0-9a-f]{40}` variant § 1.6(a1)
    explicitly rejects (false-positives on H1 titles containing a 40-hex run)."""
    canonical = _extract_conventions_h_block()
    script_walk = _extract_script_walk()
    assert ANCHORED_REGEX_FRAGMENT in canonical, (
        "canonical § 1.6(h) block no longer carries the anchored regex "
        f"{ANCHORED_REGEX_FRAGMENT!r}; § 1.6(a1) may have changed."
    )
    assert ANCHORED_REGEX_FRAGMENT in script_walk, (
        "the script's Phase 1 walk does not carry the anchored § 1.6(a1) regex "
        f"{ANCHORED_REGEX_FRAGMENT!r} verbatim — it must byte-copy § 1.6(h)."
    )


def test_conformance_script_walk_carries_no_stamped_pairs_yet() -> None:
    """In this track the script's drift (full-mode) walk is byte-identical to
    § 1.6(h) with no `STAMPED_PAIRS` pairing — that `$f=$SHA` line is the one
    sanctioned § 1.6(h) extension and belongs only to the later migrate-range
    walk (migrate-workflow/SKILL.md Step 2). This test documents the whitelist
    boundary: it pins that the drift walk does NOT carry the pairing line, so
    when the migrate-range walk adds it in a later step the conformance suite
    distinguishes the sanctioned extension from accidental drift in the drift
    walk."""
    script_walk = _extract_script_walk()
    assert "STAMPED_PAIRS" not in script_walk, (
        "the drift (full-mode) walk must not carry STAMPED_PAIRS; the pairing is "
        "the migrate-range walk's sanctioned § 1.6(h) extension, added in a "
        "later step, not the drift walk's."
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
    ("drift_all_stamped_classifies_stamped_then_folds", test_drift_all_stamped_classifies_stamped_then_folds),
    ("drift_unstamped_detects_drift_kind_unstamped", test_drift_unstamped_detects_drift_kind_unstamped),
    ("drift_empty_input_silent_no_drift_kind_null", test_drift_empty_input_silent_no_drift_kind_null),
    ("drift_phase2_detected_reports_range", test_drift_phase2_detected_reports_range),
    ("drift_phase2_empty_range_no_drift_count_zero", test_drift_phase2_empty_range_no_drift_count_zero),
    ("drift_phase2_merge_base_failed_kind_scalars_null", test_drift_phase2_merge_base_failed_kind_scalars_null),
    ("drift_phase2_staged_subtree_excluded_from_range", test_drift_phase2_staged_subtree_excluded_from_range),
    ("drift_phase2_real_workflow_commit_vs_staged_distinguished", test_drift_phase2_real_workflow_commit_vs_staged_distinguished),
    ("handoffs_reported_in_mtime_order_newest_first", test_handoffs_reported_in_mtime_order_newest_first),
    ("handoffs_empty_when_none_present", test_handoffs_empty_when_none_present),
    ("state_stub_is_json_null_in_full_mode", test_state_stub_is_json_null_in_full_mode),
    ("conformance_glob_set_matches_canonical", test_conformance_glob_set_matches_canonical),
    ("conformance_anchored_regex_matches_canonical", test_conformance_anchored_regex_matches_canonical),
    ("conformance_script_walk_carries_no_stamped_pairs_yet", test_conformance_script_walk_carries_no_stamped_pairs_yet),
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
