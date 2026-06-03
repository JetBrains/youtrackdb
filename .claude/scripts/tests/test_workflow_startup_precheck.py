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
    """`--mode full` emits valid JSON, exits 0, and carries the pinned top-level
    shape: the five top-level keys, `handoffs` an empty array, `actions_taken` an
    empty array (the seam the no-drift normalization wiring fills later), and
    `state` a `{phase, substate}` object (the state walk fills `state`; the
    fixture has no plan file, so the resolved phase is State 0).

    Runs inside a clean GitFixture because `full` performs divergence detection
    — a bare-cwd run would `git fetch` the runner's real upstream
    (network-dependent and slow on CI). The fixture keeps the shape assertion
    hermetic; its divergence content is covered separately above. The state
    content (each phase) is covered by the State 0/A/C/D/Done tests below; here
    only the key set and the state-object shape are pinned."""
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
    # `state` is now the state-walk object, not the scaffold's null seam. With no
    # plan file in the fixture, the phase resolves to State 0 with a null
    # substate (the top-level walk does not populate substate at this step).
    assert obj["state"] == {"phase": "0", "substate": None}, (
        f"full state should be the State 0 object (no plan file), got {obj['state']!r}"
    )


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
    with no `state`, `handoffs`, or `divergence` keys.

    Runs inside a clean GitFixture because migrate-range now walks the active
    plan's artifacts and folds the stamp set (it reads git) — a bare-cwd run
    would read the runner's own checkout and resolve real stamps. The fixture
    holds no plan artifact, so the walk is empty and the fold produces no base;
    only the key set is asserted here, the populated shapes below."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck("--mode", "migrate-range", cwd=fx.path)
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

    `base_sha` is migrate-range's live witness for the empty->null idiom. With
    no stampable artifact on disk and no `--bootstrap-sha`, the fold input is
    empty, the fold produces no base, and the idiom
    `($x | if . == "" then null else . end)` must collapse the empty
    `MR_BASE_SHA` to JSON null. A naive `--arg` binding would emit "" here,
    which every downstream `jq -e '.field == null'` assertion would silently
    fail to catch; this test pins the idiom directly. base_sha is now the fold's
    output (not a direct echo of --bootstrap-sha), so the fixture controls the
    fold input rather than the bare-cwd checkout's stamps."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        # No plan artifact and no --bootstrap-sha -> empty fold input -> no base.
        proc = run_precheck("--mode", "migrate-range", cwd=fx.path)
    assert proc.returncode == 0, (
        f"migrate-range should exit 0, got {proc.returncode}"
    )
    obj = json.loads(proc.stdout)
    assert obj["base_sha"] is None, (
        "an empty fold (no stamps, no --bootstrap-sha) must emit base_sha as "
        f"JSON null, got {obj['base_sha']!r} (an empty string would mean the "
        "empty->null idiom regressed to a naive --arg binding)"
    )
    # And explicitly NOT the empty string — the precise regression the idiom
    # guards against.
    assert obj["base_sha"] != "", (
        "base_sha must be JSON null, not the empty string"
    )


def test_null_vs_empty_present_scalar_is_the_value() -> None:
    """The other half of the idiom: a present fold base emits the SHA verbatim
    as a JSON string, so the empty->null collapse fires only for the empty case.

    A single stamped artifact pointing at a real HEAD commit folds to that SHA
    (no merge-base failure, no other stamp to combine), so `base_sha` is that
    full 40-hex SHA — exercising the present-scalar branch of the same idiom
    through the real fold path."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        fx.plan_artifact("implementation-plan.md", stamp=head)
        proc = run_precheck("--mode", "migrate-range", cwd=fx.path)
    assert proc.returncode == 0, (
        f"migrate-range should exit 0, got {proc.returncode}"
    )
    obj = json.loads(proc.stdout)
    assert obj["base_sha"] == head, (
        f"a single stamp at HEAD must fold to that SHA, got {obj['base_sha']!r}"
    )


# ---------------------------------------------------------------------------
# migrate-range mode — the migration's Step 2 walk, fold, and range.
#
# migrate-range walks the active plan's artifacts (byte-copied from
# conventions.md § 1.6(h), extended with the STAMPED_PAIRS `(file, sha)`
# pairing), folds the stamp set with the CONTINUE-and-collect failure mode
# (distinct from the drift gate's break-on-first), folds in an optional
# --bootstrap-sha, and ranges `git log BASE_SHA..HEAD` over the workflow
# pathspecs. It emits no state / handoffs / divergence. Byte-source:
# migrate-workflow/SKILL.md Step 2. Each fixture stamps with *real* commit SHAs
# (head_sha / orphan_branch) so the fold resolves them.
# ---------------------------------------------------------------------------


def _migrate_range(proc: subprocess.CompletedProcess) -> dict:
    """Parse a migrate-range run's whole object, asserting it exited 0 first so
    a script error surfaces clearly rather than as a downstream KeyError."""
    assert proc.returncode == 0, (
        f"migrate-range should exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
    )
    return json.loads(proc.stdout)


def test_migrate_range_stamped_pairs_and_log_range() -> None:
    """An all-stamped plan whose single stamp points at a real commit, with two
    `.claude/workflow/` commits sitting between that stamp base and HEAD,
    produces: `stamped_artifacts` carrying the one `(file, sha)` pair,
    `unstamped_files` empty, `base_sha` the folded stamp base, and `log_range`
    listing both workflow commits oldest-first with full subjects. Unlike the
    drift range, the migration replays every workflow commit, so the full list
    is emitted (no head cap) and the SHA is the full 40-hex `%H`, not short
    `%h`. `merge_base_failed` is empty (the fold was clean)."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        base = fx.head_sha()
        fx.workflow_commit("first workflow change")
        fx.workflow_commit("second workflow change with spaces")
        fx.plan_artifact("implementation-plan.md", stamp=base)
        obj = _migrate_range(run_precheck("--mode", "migrate-range", cwd=fx.path))
        # One stamped artifact, paired (file, sha).
        assert obj["stamped_artifacts"] == [
            {
                "file": "docs/adr/main/_workflow/implementation-plan.md",
                "sha": base,
            }
        ], f"stamped_artifacts pairing mismatch: {obj['stamped_artifacts']!r}"
        assert obj["unstamped_files"] == [], (
            f"no unstamped artifact expected, got {obj['unstamped_files']!r}"
        )
        assert obj["base_sha"] == base, (
            f"base_sha should be the folded stamp base {base!r}, got {obj['base_sha']!r}"
        )
        assert obj["merge_base_failed"] == [], (
            f"a clean fold must report no merge_base_failed, got {obj['merge_base_failed']!r}"
        )
        # log_range lists both workflow commits oldest-first, full subjects.
        subjects = [c["subject"] for c in obj["log_range"]]
        assert subjects == [
            "first workflow change",
            "second workflow change with spaces",
        ], f"log_range should list both subjects oldest-first: {obj['log_range']!r}"
        for entry in obj["log_range"]:
            assert set(entry.keys()) == {"sha", "subject"}, (
                f"each log_range entry is {{sha, subject}}, got {entry!r}"
            )
            # The migration records full %H SHAs (40 hex), not the drift range's
            # short %h.
            assert len(entry["sha"]) == 40, (
                f"migrate-range log_range sha should be the full 40-hex %H, got "
                f"{entry['sha']!r}"
            )


def test_migrate_range_unstamped_files_reported() -> None:
    """A plan with an unstamped artifact reports it in `unstamped_files` (so the
    skill can drive the bootstrap prompt) while still pairing the stamped
    sibling in `stamped_artifacts`. With one stamp at HEAD and no
    --bootstrap-sha, the fold runs over the single stamp and resolves to it; the
    unstamped file is reported but does not enter the fold (the skill folds it
    in via --bootstrap-sha on re-invocation). This pins that the script never
    prompts — it reports the unstamped set as data."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        fx.plan_artifact("implementation-plan.md", stamp=None)  # unstamped
        fx.plan_artifact("plan/track-1.md", stamp=head)  # stamped sibling
        obj = _migrate_range(run_precheck("--mode", "migrate-range", cwd=fx.path))
        assert obj["unstamped_files"] == [
            "docs/adr/main/_workflow/implementation-plan.md"
        ], f"unstamped_files should name the unstamped artifact: {obj['unstamped_files']!r}"
        assert obj["stamped_artifacts"] == [
            {"file": "docs/adr/main/_workflow/plan/track-1.md", "sha": head}
        ], f"stamped sibling should still be paired: {obj['stamped_artifacts']!r}"
        # The single remaining stamp folds to itself.
        assert obj["base_sha"] == head, (
            f"the single stamp should fold to HEAD {head!r}, got {obj['base_sha']!r}"
        )


def test_migrate_range_bootstrap_sha_folded_into_range() -> None:
    """`--bootstrap-sha` is folded into the stamp set (migrate-workflow/SKILL.md
    Step 2's FOLD_INPUT). With one stamp at HEAD and a --bootstrap-sha pointing
    at an *earlier* commit, the fold's merge-base of (HEAD, earlier) is the
    earlier commit (an ancestor of HEAD), so base_sha moves back to the
    bootstrap commit and the `git log` range widens to include the workflow
    commit that landed between the bootstrap commit and HEAD. This confirms the
    bootstrap SHA actually participates in the fold rather than being echoed."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        bootstrap = fx.head_sha()  # an earlier commit
        # A workflow commit lands AFTER the bootstrap commit, then the stamp is
        # placed at the newer HEAD.
        fx.workflow_commit("change after bootstrap")
        head_with_wf = fx.head_sha()
        fx.plan_artifact("implementation-plan.md", stamp=head_with_wf)
        # Without --bootstrap-sha, the fold base is the stamp (head_with_wf) and
        # the range is empty (no workflow commit after it).
        obj_no_boot = _migrate_range(
            run_precheck("--mode", "migrate-range", cwd=fx.path)
        )
        assert obj_no_boot["base_sha"] == head_with_wf, (
            f"without bootstrap, base is the stamp {head_with_wf!r}, got "
            f"{obj_no_boot['base_sha']!r}"
        )
        assert obj_no_boot["log_range"] == [], (
            f"without bootstrap the range is empty, got {obj_no_boot['log_range']!r}"
        )
        # With --bootstrap-sha at the earlier commit, the fold moves base back to
        # that ancestor and the workflow commit between it and HEAD enters range.
        obj_boot = _migrate_range(
            run_precheck(
                "--mode", "migrate-range", "--bootstrap-sha", bootstrap, cwd=fx.path
            )
        )
        assert obj_boot["base_sha"] == bootstrap, (
            f"bootstrap should fold base back to the ancestor {bootstrap!r}, got "
            f"{obj_boot['base_sha']!r}"
        )
        subjects = [c["subject"] for c in obj_boot["log_range"]]
        assert subjects == ["change after bootstrap"], (
            f"the workflow commit after the bootstrap commit must enter the range, "
            f"got {obj_boot['log_range']!r}"
        )


def test_migrate_range_multi_failure_collects_all_pairs() -> None:
    """The continue-and-collect fold collects MORE than one failing pair, where
    break-on-first (the drift gate's mode) would stop after the first. This is
    the continue-vs-break byte-parity hazard: the migrate-range fold must use
    `continue` (not the drift gate's `break`), so a second merge-base failure
    later in the fold is still captured.

    The fold's reset-then-reseed shape (a failure resets the running base, and
    the next stamp simply re-seeds it without a merge-base call — byte-identical
    to migrate-workflow/SKILL.md Step 2) means two collected failures need the
    walk order [real, orphan, real, orphan]: seed on real#1, fail vs orphan#1,
    re-seed on real#2, fail vs orphan#2. The § 1.6(h) walk sorts its operands
    lexically, so the four artifacts sort
    `design.md < implementation-plan.md < plan/track-1.md < plan/track-2.md`;
    stamping them real/orphan/real/orphan in that sorted order produces the two
    failing pairs. Each failing pair resolves to its owning artifact PATH via
    STAMPED_PAIRS (the whole point of the pairing), so `merge_base_failed` names
    files, not bare SHAs. A failed fold reports base_sha null and an empty
    log_range (the skill re-prompts and restarts the fold)."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        real1 = fx.head_sha()
        fx.commit("second main commit")
        real2 = fx.head_sha()  # shares history with real1, distinct commit
        # Two independent orphan roots, neither sharing history with the main
        # line nor with each other.
        orphan1 = fx.orphan_branch("unrelated-a")
        orphan2 = fx.orphan_branch("unrelated-b")
        fx.checkout(fx.default_branch)
        # Sorted walk order is design < implementation-plan < track-1 < track-2;
        # stamp them real/orphan/real/orphan so the continue fold sees:
        #   seed real1 -> fail (real1, orphan1) -> reseed real2 ->
        #   fail (real2, orphan2).
        fx.plan_artifact("design.md", stamp=real1)
        fx.plan_artifact("implementation-plan.md", stamp=orphan1)
        fx.plan_artifact("plan/track-1.md", stamp=real2)
        fx.plan_artifact("plan/track-2.md", stamp=orphan2)
        obj = _migrate_range(run_precheck("--mode", "migrate-range", cwd=fx.path))
        # TWO failing pairs collected (continue mode); break-on-first would stop
        # after the (real1, orphan1) pair and report only one.
        assert len(obj["merge_base_failed"]) == 2, (
            f"continue mode must collect BOTH failing pairs (break-on-first would "
            f"report one), got {obj['merge_base_failed']!r}"
        )
        # Each failing pair names the owning artifact paths via STAMPED_PAIRS.
        all_files = [
            f for pair in obj["merge_base_failed"] for f in pair["files"]
        ]
        assert any(f.endswith("implementation-plan.md") for f in all_files), (
            f"merge_base_failed must name implementation-plan.md (orphan1's owner) "
            f"via STAMPED_PAIRS, got files {all_files!r}"
        )
        assert any(f.endswith("plan/track-2.md") for f in all_files), (
            f"merge_base_failed must name plan/track-2.md (orphan2's owner), got "
            f"files {all_files!r}"
        )
        # Each pair carries {base, sha, files}.
        for pair in obj["merge_base_failed"]:
            assert set(pair.keys()) == {"base", "sha", "files"}, (
                f"each merge_base_failed entry is {{base, sha, files}}, got {pair!r}"
            )
        # A failed fold reports no clean base and no range.
        assert obj["base_sha"] is None, (
            f"a failed fold must report base_sha null, got {obj['base_sha']!r}"
        )
        assert obj["log_range"] == [], (
            f"a failed fold must report an empty log_range, got {obj['log_range']!r}"
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
    Phase-2 seam (Phase 1 classifies, Phase 2 folds), using a real stamp so the
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


# ---------------------------------------------------------------------------
# Resume-state determination — the top-level State 0/A/C/D/Done precedence walk.
#
# `full` mode now walks the active plan file (and, for State A vs C, probes for
# the active track file) to reproduce the precedence in workflow.md § Startup
# Protocol step 5, reporting `state.phase` (0/A/C/D/Done) with a null substate
# (the State C sub-state map and the section-discrepancy edge land in later
# steps of this track). Each fixture composes a plan body with the relevant
# `## Plan Review` / `## Checklist` / `## Final Artifacts` markers, stamps it at
# a real HEAD commit (so the drift half of the same `full` run is a clean
# all-stamped no-drift read rather than noise), and asserts the `state` object.
# Byte-source: workflow.md § Startup Protocol step 5.
# ---------------------------------------------------------------------------


def _state(proc: subprocess.CompletedProcess) -> dict:
    """Parse the `state` object from a full-mode run, asserting the run itself
    exited 0 first so a script error surfaces as a clear message rather than a
    downstream KeyError on the state key."""
    assert proc.returncode == 0, (
        f"full should exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
    )
    obj = json.loads(proc.stdout)
    return obj["state"]


def _plan_doc(plan_review: str, checklist: str, final_artifacts: str) -> str:
    """Compose a plan-file body from its three state-bearing sections. Each
    argument is the section's content lines (the `## <heading>` line is added
    here); pass the empty string to omit a section entirely. Composing the body
    rather than hardcoding whole-file strings keeps each fixture's intent visible
    in the one section it varies."""
    parts: List[str] = []
    if plan_review:
        parts.append(f"## Plan Review\n{plan_review}")
    if checklist:
        parts.append(f"## Checklist\n{checklist}")
    if final_artifacts:
        parts.append(f"## Final Artifacts\n{final_artifacts}")
    return "\n\n".join(parts) + "\n"


# A passed `## Plan Review` section — the precondition for every non-State-0
# test (State 0 is the only state where plan review has not passed).
PLAN_REVIEW_PASSED = "- [x] Plan review (consistency + structural) — passed"


def test_state_0_absent_plan_file() -> None:
    """State 0, shape 1 of 3: no plan file at the resolved PLAN_DIR. With no
    `implementation-plan.md` to review against, the walk reports State 0 before
    reading any section. The fixture writes no plan artifact at all (only a
    commit + upstream so the divergence/drift halves of `full` run hermetically)
    so PLAN_DIR resolves but the plan file is absent."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "0", "substate": None}, (
            f"absent plan file must be State 0, got {state!r}"
        )


def test_state_0_plan_review_unchecked() -> None:
    """State 0, shape 2 of 3: the plan file exists and `## Plan Review`'s first
    top-level checkbox is `[ ]` (review not yet passed). State 0 is checked
    before any track walk, so the `[ ]` Plan-Review entry forces State 0
    regardless of the track checkboxes below it."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        body = _plan_doc(
            "- [ ] Plan review (not yet passed)",
            "- [x] Track 1: done\n- [ ] Track 2: pending",
            "- [ ] Phase 4: Final artifacts",
        )
        fx.plan_artifact("implementation-plan.md", stamp=fx.head_sha(), body=body)
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "0", "substate": None}, (
            f"unchecked Plan Review must be State 0, got {state!r}"
        )


def test_state_0_plan_review_section_absent() -> None:
    """State 0, shape 3 of 3: the plan file exists but has no `## Plan Review`
    section at all. workflow.md step 5 row 1 treats "section missing entirely"
    identically to an unchecked entry, so an absent section is State 0 even
    when the track checklist below it looks resumable."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        body = _plan_doc(
            "",  # no ## Plan Review section
            "- [ ] Track 1: pending",
            "- [ ] Phase 4: Final artifacts",
        )
        fx.plan_artifact("implementation-plan.md", stamp=fx.head_sha(), body=body)
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "0", "substate": None}, (
            f"absent Plan Review section must be State 0, got {state!r}"
        )


def test_state_A_first_todo_track_no_track_file() -> None:
    """State A: Plan Review passed, the first `[ ]` track has no `plan/track-N.md`
    on disk. State A is the rare path (every track file is written at Phase 1;
    the only track-file-deleting action leaves the track `[~]`, not `[ ]`), but
    the walk implements it for parity and the manual-delete / corruption case.
    Here Track 1 is `[x]` and Track 2 is the first `[ ]`; no track-2.md is
    written, so the walk reports State A."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        body = _plan_doc(
            PLAN_REVIEW_PASSED,
            "- [x] Track 1: done\n- [ ] Track 2: pending",
            "- [ ] Phase 4: Final artifacts",
        )
        fx.plan_artifact("implementation-plan.md", stamp=fx.head_sha(), body=body)
        # No plan/track-2.md authored, so the first [ ] track has no track file.
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "A", "substate": None}, (
            f"first [ ] track with no track file must be State A, got {state!r}"
        )


def test_state_C_first_todo_track_with_track_file() -> None:
    """State C: Plan Review passed, the first `[ ]` track (Track 2) HAS a
    `plan/track-2.md` on disk — the steady-state mid-track resume case. The track
    number is parsed from the `Track 2:` tail, so the probed track file is
    `plan/track-2.md`, not `plan/track-1.md`. This fixture's track body carries no
    `## Progress` section, so the joint read finds no `[x]` "Review +
    decomposition" entry and reports the decomposition-pending sub-state — the
    sub-state map is exercised in depth by the dedicated sub-state tests below;
    here the focus is that the State A/C decision lands on C for the right track
    number."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        body = _plan_doc(
            PLAN_REVIEW_PASSED,
            "- [x] Track 1: done\n- [ ] Track 2: pending",
            "- [ ] Phase 4: Final artifacts",
        )
        fx.plan_artifact("implementation-plan.md", stamp=head, body=body)
        # The first [ ] track is Track 2, so author plan/track-2.md.
        fx.plan_artifact("plan/track-2.md", stamp=head, body="# Track 2\n")
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "C", "substate": "decomposition-pending"}, (
            f"first [ ] track with a track file must be State C, got {state!r}"
        )


def test_state_C_track_number_drives_track_file_probe() -> None:
    """The State A/C decision probes `plan/track-<N>.md` for the *specific* first
    `[ ]` track number, not track-1 by default. Track 1 is `[~]` (skipped), Track
    2 is `[x]`, Track 3 is the first `[ ]`; only `plan/track-3.md` exists. The
    walk must parse N=3 from the `Track 3:` tail and find track-3.md to report
    State C — a default-to-track-1 probe would miss it and wrongly report State
    A."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        body = _plan_doc(
            PLAN_REVIEW_PASSED,
            "- [~] Track 1: skipped\n- [x] Track 2: done\n- [ ] Track 3: pending",
            "- [ ] Phase 4: Final artifacts",
        )
        fx.plan_artifact("implementation-plan.md", stamp=head, body=body)
        # Only track-3.md exists; the walk must probe track-3, not track-1.
        fx.plan_artifact("plan/track-3.md", stamp=head, body="# Track 3\n")
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        # The track body has no ## Progress section -> decomposition-pending
        # sub-state. The point of this test is the track-number probe (N=3), not
        # the sub-state; the sub-state tests below cover the map directly.
        assert state == {"phase": "C", "substate": "decomposition-pending"}, (
            f"the probe must target the first [ ] track's number (3), got {state!r}"
        )


def test_state_D_all_tracks_done_phase4_pending() -> None:
    """State D: every track is `[x]` or `[~]` (no `[ ]` track), and `## Final
    Artifacts`' first checkbox is `[ ]` (Phase 4 not yet done). The walk finds no
    `[ ]` track, falls through to Final Artifacts, and reports State D. A `[~]`
    track counts as done for the all-tracks-done test."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        body = _plan_doc(
            PLAN_REVIEW_PASSED,
            "- [x] Track 1: done\n- [~] Track 2: skipped",
            "- [ ] Phase 4: Final artifacts",
        )
        fx.plan_artifact("implementation-plan.md", stamp=fx.head_sha(), body=body)
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "D", "substate": None}, (
            f"all tracks done + Phase 4 [ ] must be State D, got {state!r}"
        )


def test_state_D_phase4_in_progress() -> None:
    """State D also covers a Phase 4 marked `[>]` (in progress): an interrupted
    Phase 4 session resumes in State D, not Done. The `[>]` glyph is recognized
    (it is the § 1.2 in-progress marker) and resolves to State D, distinct from
    the `[x]` Done case below."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        body = _plan_doc(
            PLAN_REVIEW_PASSED,
            "- [x] Track 1: done",
            "- [>] Phase 4: Final artifacts (in progress)",
        )
        fx.plan_artifact("implementation-plan.md", stamp=fx.head_sha(), body=body)
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "D", "substate": None}, (
            f"all tracks done + Phase 4 [>] must be State D, got {state!r}"
        )


def test_state_done_phase4_complete() -> None:
    """Done: every track is `[x]`/`[~]` and `## Final Artifacts` is `[x]`. The
    walk finds no `[ ]` track and a `[x]` Phase 4 checkbox, so it reports Done —
    the only state distinguished from State D by the Final-Artifacts glyph."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        body = _plan_doc(
            PLAN_REVIEW_PASSED,
            "- [x] Track 1: done\n- [x] Track 2: done",
            "- [x] Phase 4: Final artifacts",
        )
        fx.plan_artifact("implementation-plan.md", stamp=fx.head_sha(), body=body)
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "Done", "substate": None}, (
            f"all tracks done + Phase 4 [x] must be Done, got {state!r}"
        )


def test_state_checklist_anchors_to_top_level_track_lines() -> None:
    """The Checklist walk anchors to column-0 `- [<m>] Track N:` lines. A
    blockquoted episode checkbox (`> - [ ] ...`) under a completed Track 1 must
    NOT be miscounted as the first `[ ]` track. Track 1 is `[x]` with a
    blockquoted `> - [ ]`-shaped episode line beneath it; Track 2 is the real
    first `[ ]`. With track-2.md present the walk must report State C anchored on
    Track 2 — a walk that counted the quoted `> - [ ]` would resolve the wrong
    (or no) track. A fenced code block containing a `- [ ]` template line is
    likewise skipped."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        checklist = (
            "- [x] Track 1: done\n"
            "  > **Track episode:**\n"
            "  > - [ ] a quoted checkbox inside an episode, NOT a track entry\n"
            "  > - [x] Track 99: a quoted line that mimics a track entry\n"
            "\n"
            "```bash\n"
            "- [ ] a checkbox-shaped template line inside a fence\n"
            "```\n"
            "- [ ] Track 2: the real first [ ] track"
        )
        body = _plan_doc(
            PLAN_REVIEW_PASSED, checklist, "- [ ] Phase 4: Final artifacts"
        )
        fx.plan_artifact("implementation-plan.md", stamp=head, body=body)
        fx.plan_artifact("plan/track-2.md", stamp=head, body="# Track 2\n")
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        # The track body has no ## Progress section -> decomposition-pending; the
        # assertion's focus is the Checklist anchoring (Track 2, not the quoted /
        # fenced lines), so phase C on the right track is what matters here.
        assert state == {"phase": "C", "substate": "decomposition-pending"}, (
            "the walk must anchor on the column-0 Track 2 line, not the "
            f"blockquoted or fenced checkbox-shaped lines, got {state!r}"
        )


def _state_parse_error(body: str) -> subprocess.CompletedProcess:
    """Run `--mode full` against a plan body and return the completed process so
    the caller can assert the parse-error contract (non-zero exit, no stdout
    JSON, a stderr diagnostic). Shared by the malformed-marker cases below."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        fx.plan_artifact("implementation-plan.md", stamp=fx.head_sha(), body=body)
        return run_precheck("--mode", "full", cwd=fx.path)


def test_state_malformed_marker_is_parse_error() -> None:
    """An unrecognized checkbox glyph on a line the state parser reads is an
    explicit parse error: non-zero exit, NO JSON on stdout, and a stderr
    diagnostic naming the section and the offending line — never a coerced
    state. The enum stays closed (no sixth `phase` value). Three malformed
    bodies are checked across the two reading sites: `[X]` (single unrecognized
    glyph), `[]` (empty body), and `[ x]` (multi-char body), each in `## Plan
    Review` and once in `## Checklist`. A `[!]` marker is NOT malformed (it is
    the recognized failed-step marker) and is checked separately below."""
    cases = [
        # (description, plan body, expected section in the stderr message)
        (
            "Plan Review [X]",
            _plan_doc("- [X] Plan review", "- [ ] Track 1: x", "- [ ] Phase 4"),
            "## Plan Review",
        ),
        (
            "Plan Review [] (empty body)",
            _plan_doc("- [] Plan review", "- [ ] Track 1: x", "- [ ] Phase 4"),
            "## Plan Review",
        ),
        (
            "Plan Review [ x] (multi-char body)",
            _plan_doc("- [ x] Plan review", "- [ ] Track 1: x", "- [ ] Phase 4"),
            "## Plan Review",
        ),
        (
            "Checklist [X] on a track line",
            _plan_doc(PLAN_REVIEW_PASSED, "- [X] Track 1: x", "- [ ] Phase 4"),
            "## Checklist",
        ),
        (
            "Checklist [] on a track line",
            _plan_doc(PLAN_REVIEW_PASSED, "- [] Track 1: x", "- [ ] Phase 4"),
            "## Checklist",
        ),
        (
            "Final Artifacts [X]",
            _plan_doc(PLAN_REVIEW_PASSED, "- [x] Track 1: done", "- [X] Phase 4"),
            "## Final Artifacts",
        ),
    ]
    for desc, body, section in cases:
        proc = _state_parse_error(body)
        assert proc.returncode != 0, (
            f"{desc}: malformed marker must exit non-zero, got {proc.returncode}"
        )
        assert proc.stdout.strip() == "", (
            f"{desc}: malformed marker must emit NO stdout JSON, got {proc.stdout!r}"
        )
        assert "malformed checkbox marker" in proc.stderr, (
            f"{desc}: stderr must name the malformed marker, got {proc.stderr!r}"
        )
        assert section in proc.stderr, (
            f"{desc}: stderr must name the section {section!r}, got {proc.stderr!r}"
        )


def test_state_bang_marker_is_recognized_not_malformed() -> None:
    """A `[!]` failed-step marker is RECOGNIZED, not a parse error — it is the
    roster/Progress failed-step glyph (step-implementation-recovery.md) that a
    later step's State C failed-step sub-state depends on. Here a Track 1 line
    carrying `[!]` does not abort the parse; the walk treats `[!]` as a
    non-`[ ]` (not-the-first-todo) marker and continues to the first genuine
    `[ ]` track (Track 2), reporting a valid state rather than exiting. This pins
    that `[!]` is inside the closed enum, distinct from the malformed glyphs
    above."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        body = _plan_doc(
            PLAN_REVIEW_PASSED,
            "- [!] Track 1: a failed-step marker, recognized\n- [ ] Track 2: pending",
            "- [ ] Phase 4: Final artifacts",
        )
        fx.plan_artifact("implementation-plan.md", stamp=head, body=body)
        fx.plan_artifact("plan/track-2.md", stamp=head, body="# Track 2\n")
        proc = run_precheck("--mode", "full", cwd=fx.path)
        assert proc.returncode == 0, (
            f"[!] is recognized, the run must exit 0, got {proc.returncode}; "
            f"stderr: {proc.stderr!r}"
        )
        state = json.loads(proc.stdout)["state"]
        # Track 1 is [!] (not the first [ ]); Track 2 is the first [ ] and has a
        # track file -> State C. The point is the run did NOT parse-error on [!].
        # The track body has no ## Progress section, so the sub-state is
        # decomposition-pending; what this test pins is that [!] is recognized
        # (exit 0, a valid state) rather than the specific sub-state.
        assert state == {"phase": "C", "substate": "decomposition-pending"}, (
            f"[!] must be recognized and the walk continue, got {state!r}"
        )


def test_state_checklist_malformed_glyph_after_first_todo_is_parse_error() -> None:
    """The closed-enum parse-error contract is TOTAL over the bounded `## Checklist`
    region, not just the lines at or before the first `[ ]` track. A malformed
    checkbox glyph on a track line AFTER the first `[ ]` track must still parse-error
    rather than being skipped by an early break.

    Regression: the walk previously broke at the first `[ ]` track to record the
    resume target, so a `[zzz]` glyph on a later track line was never validated and
    the run reported `{phase:A}` (exit 0). Here the first track line is the first
    `[ ]` track and the second track line carries the malformed `[zzz]`; the run
    must exit non-zero with no stdout JSON and a stderr diagnostic naming
    `## Checklist`, never a coerced state."""
    body = _plan_doc(
        PLAN_REVIEW_PASSED,
        "- [ ] Track 1: the first todo (resume target)\n"
        "- [zzz] Track 2: a malformed glyph AFTER the first todo",
        "- [ ] Phase 4: Final artifacts",
    )
    proc = _state_parse_error(body)
    assert proc.returncode != 0, (
        "a malformed glyph on a track line after the first [ ] must exit "
        f"non-zero, got {proc.returncode}"
    )
    assert proc.stdout.strip() == "", (
        f"a malformed glyph must emit NO stdout JSON, got {proc.stdout!r}"
    )
    assert "malformed checkbox marker" in proc.stderr, (
        f"stderr must name the malformed marker, got {proc.stderr!r}"
    )
    assert "## Checklist" in proc.stderr, (
        f"stderr must name the ## Checklist section, got {proc.stderr!r}"
    )


def test_state_heading_match_tolerates_trailing_whitespace_plan_review() -> None:
    """Section entry tolerates a trailing-whitespace run on the `## <heading>` line.
    A `## Plan Review ` heading (one trailing space) carrying a passed `[x]` checkbox
    must still be ENTERED and read as passed, not treated as an absent section.

    Regression: heading entry previously used exact-equality
    (`[ "$line" = "## Plan Review" ]`), so a trailing-space heading did not match
    and the section was treated as absent, which coerces to State 0. Here the
    trailing-space Plan Review is passed and the first track line is the first `[ ]`
    track with a track file present, so a correct (heading-entered) read reports
    State C; a regressed read that missed the trailing-space heading would report
    State 0 instead."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        # Build the body directly so the `## Plan Review ` heading carries a literal
        # trailing space (the _plan_doc helper emits headings with no trailing run).
        body = (
            "## Plan Review \n"  # trailing space on the heading line
            f"{PLAN_REVIEW_PASSED}\n\n"
            "## Checklist\n"
            "- [ ] Track 1: the first todo\n\n"
            "## Final Artifacts\n"
            "- [ ] Phase 4: Final artifacts\n"
        )
        fx.plan_artifact("implementation-plan.md", stamp=head, body=body)
        fx.plan_artifact("plan/track-1.md", stamp=head, body="# Track 1\n")
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        # State C (not 0) proves the trailing-space Plan Review heading was entered
        # and read as passed. The track body has no ## Progress section, so the
        # sub-state is decomposition-pending; the discriminating fact is phase != 0.
        assert state == {"phase": "C", "substate": "decomposition-pending"}, (
            "a trailing-space `## Plan Review ` heading must be entered and read "
            f"as passed (State C), not coerced to State 0, got {state!r}"
        )


def test_state_heading_match_tolerates_trailing_whitespace_final_artifacts() -> None:
    """The trailing-whitespace tolerance is applied uniformly at every heading site,
    not just `## Plan Review`. A `## Final Artifacts ` heading (one trailing space)
    carrying `[x]` must still be ENTERED so an all-tracks-done plan resolves Done.

    Regression (second literal-heading site): with all tracks `[x]` the walk
    falls through to Final Artifacts. If the trailing-space `## Final Artifacts `
    heading were not matched, the section would read as absent and collapse to State
    D (the not-yet-Done default). Reporting Done discriminates that the heading was
    entered and its `[x]` checkbox read."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        body = (
            f"## Plan Review\n{PLAN_REVIEW_PASSED}\n\n"
            "## Checklist\n"
            "- [x] Track 1: done\n\n"
            "## Final Artifacts \n"  # trailing space on the heading line
            "- [x] Phase 4: Final artifacts complete\n"
        )
        fx.plan_artifact("implementation-plan.md", stamp=head, body=body)
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        # Done (not D) proves the trailing-space `## Final Artifacts ` heading was
        # entered and its `[x]` read. An unmatched heading reads as absent -> State D.
        assert state == {"phase": "Done", "substate": None}, (
            "a trailing-space `## Final Artifacts ` heading must be entered and its "
            f"[x] read (Done), not read as absent (State D), got {state!r}"
        )


def test_state_real_track_file_fixture() -> None:
    """At least one state fixture is cut from a REAL on-disk track file shape:
    the continuous-log `## Progress` section and a numbered `## Concrete Steps`
    roster, not an idealized four-checkbox block, so coverage tracks the real
    artifact shape this branch's own track files carry. This body has the
    `Review + decomposition complete` `[x]` Progress entry and two `[ ]` roster
    steps, so the joint sub-state read resolves to steps-partial — exercising the
    full State C path (Checklist walk → track-file probe → Progress + roster
    joint read) against the realistic shape, not an idealized block."""
    real_track_body = (
        "<!-- a real track file carries a continuous-log Progress section and a\n"
        "     numbered Concrete Steps roster, not a fixed four-checkbox block -->\n"
        "# Sample track with a realistic on-disk shape\n\n"
        "## Progress\n"
        "- [x] 2026-06-03T04:03Z [ctx=info] Review + decomposition complete\n"
        "- [ ] Step implementation\n"
        "- [ ] Track-level code review\n"
        "- [ ] Track completion\n\n"
        "## Concrete Steps\n\n"
        "1. First roster entry — risk: medium  [ ]\n"
        "2. Second roster entry — risk: medium  [ ]\n"
    )
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        body = _plan_doc(
            PLAN_REVIEW_PASSED,
            "- [x] Track 1: done\n- [ ] Track 2: a track with an on-disk file",
            "- [ ] Phase 4: Final artifacts",
        )
        fx.plan_artifact("implementation-plan.md", stamp=head, body=body)
        # The track file uses the real continuous-log + roster shape (its line 1
        # is a comment, so plan_artifact's stamp goes above it). The joint
        # sub-state read consumes both the Progress log and the roster: decomp is
        # [x] and both roster steps are [ ], so the resume sub-state is
        # steps-partial.
        fx.plan_artifact("plan/track-2.md", stamp=head, body=real_track_body)
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "C", "substate": "steps-partial"}, (
            "a realistically-shaped track file (decomp done, roster all [ ]) must "
            f"drive State C steps-partial, got {state!r}"
        )


# ---------------------------------------------------------------------------
# State C sub-state map — the joint read over `## Progress` and
# `## Concrete Steps`.
#
# Once the top-level walk reaches State C (Plan Review passed, first `[ ]` track
# has a track file), the script computes the sub-state from the active track
# file's `## Progress` continuous log and `## Concrete Steps` roster (plus this
# track's plan-file checkbox, `[ ]` by construction), mirroring workflow.md
# § Startup Protocol step 5's sub-state table. The five sub-states and their
# sources:
#
#   * decomposition-pending     — Progress "Review + decomposition" is `[ ]`
#                                 (short-circuits before the roster is read).
#   * failed-step               — roster carries a `[!]` (checked before partial).
#   * steps-partial             — roster has a `[ ]` step and no `[!]`.
#   * steps-done-review-pending — roster all `[x]`/`[~]`, code-review Progress
#                                 entry not `[x]`.
#   * review-done-track-open    — roster all `[x]`/`[~]`, code-review `[x]`,
#                                 plan-file track checkbox still `[ ]`.
#
# Each fixture composes a plan file (first `[ ]` track = Track 2) plus a Track-2
# track body via `_track_doc`, then asserts the `state.substate` slug. Byte-source:
# workflow.md § Startup Protocol step 5 + conventions-execution.md § 2.1 (the
# four pre-seeded Progress phase-checkpoint entries).
# ---------------------------------------------------------------------------


# A plan-file body whose first `[ ]` track is Track 2 — the precondition for
# every sub-state test (the walk reaches State C on Track 2, then reads
# track-2.md). Shared so each sub-state test varies only the track body.
_PLAN_FIRST_TODO_TRACK_2 = _plan_doc(
    PLAN_REVIEW_PASSED,
    "- [x] Track 1: done\n- [ ] Track 2: the active track",
    "- [ ] Phase 4: Final artifacts",
)


def _track_doc(progress: str, concrete_steps: str = "") -> str:
    """Compose a track-file body from its `## Progress` and `## Concrete Steps`
    sections (the two surfaces the sub-state read consumes). `progress` is the
    section's entry lines (the `## Progress` heading is added here);
    `concrete_steps` is the roster lines (the `## Concrete Steps` heading is added
    when non-empty, omitted entirely when empty to model the placeholder roster
    of a not-yet-decomposed track). Line 1 is a comment so the fixture's
    `plan_artifact` stamp lands above it without disturbing the sections."""
    parts: List[str] = ["<!-- track-file fixture -->", "# Track 2"]
    parts.append(f"## Progress\n{progress}")
    if concrete_steps:
        parts.append(f"## Concrete Steps\n\n{concrete_steps}")
    return "\n\n".join(parts) + "\n"


def _substate(track_body: str) -> dict:
    """Run `--mode full` against a State-C plan whose Track 2 carries `track_body`,
    returning the `state` object. Shared by the sub-state cases below."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        fx.plan_artifact("implementation-plan.md", stamp=head, body=_PLAN_FIRST_TODO_TRACK_2)
        fx.plan_artifact("plan/track-2.md", stamp=head, body=track_body)
        return _state(run_precheck("--mode", "full", cwd=fx.path))


def test_state_C_substate_decomposition_pending() -> None:
    """Sub-state 1, decomposition-pending: the `## Progress` "Review +
    decomposition" entry is `[ ]` (Phase A not yet done). This is the steady-state
    first entry of a freshly-created track. The roster is the placeholder shape
    (no `## Concrete Steps` section at all), and the short-circuit must report
    decomposition-pending WITHOUT quantifying the empty roster — a guard against
    coercing an empty roster to an all-steps-done sub-state."""
    body = _track_doc(
        "- [ ] Review + decomposition\n"
        "- [ ] Step implementation\n"
        "- [ ] Track-level code review\n"
        "- [ ] Track completion",
        # No ## Concrete Steps roster — the placeholder shape before Phase A.
        concrete_steps="",
    )
    state = _substate(body)
    assert state == {"phase": "C", "substate": "decomposition-pending"}, (
        f"Review + decomposition [ ] must be decomposition-pending, got {state!r}"
    )


def test_state_C_substate_decomposition_pending_empty_roster_vacuous_guard() -> None:
    """The decomposition-pending vacuous-truth guard: even when a `## Concrete
    Steps` section exists but is EMPTY (a heading with no roster lines — an
    interrupted decomposition), a Progress "Review + decomposition" `[ ]` must
    still report decomposition-pending, NOT an all-steps-done sub-state. An
    all-`[x]` quantifier over zero roster steps is vacuously true, so a naive
    "every step is `[x]`" check would wrongly route an empty roster to
    review-pending; the short-circuit on the Progress entry prevents that."""
    body = _track_doc(
        "- [ ] Review + decomposition\n"
        "- [ ] Step implementation\n"
        "- [ ] Track-level code review\n"
        "- [ ] Track completion",
        # An empty Concrete Steps section (heading present, no roster lines). The
        # composer adds the heading; the body below it is whitespace only.
        concrete_steps="<!-- roster not yet decomposed -->",
    )
    state = _substate(body)
    assert state == {"phase": "C", "substate": "decomposition-pending"}, (
        "an empty roster with Review + decomposition [ ] must stay "
        f"decomposition-pending (vacuous-truth guard), got {state!r}"
    )


def test_state_C_substate_steps_partial_mixed() -> None:
    """Sub-state 3, steps-partial (the common mixed shape): decomposition is `[x]`
    and the roster mixes `[x]` (done) and `[ ]` (pending) steps. The next `[ ]`
    step is the resume target."""
    body = _track_doc(
        "- [x] 2026-06-03T04:03Z [ctx=info] Review + decomposition complete\n"
        "- [x] 2026-06-03T04:29Z [ctx=safe] Step 1 complete (commit abc123)\n"
        "- [ ] Step implementation",
        "1. First step — risk: medium  [x] commit: abc123\n"
        "2. Second step — risk: medium  [ ]\n",
    )
    state = _substate(body)
    assert state == {"phase": "C", "substate": "steps-partial"}, (
        f"a mix of [x] and [ ] roster steps must be steps-partial, got {state!r}"
    )


def test_state_C_substate_steps_partial_all_todo_after_decomposition() -> None:
    """Sub-state 3, steps-partial (the all-`[ ]` just-decomposed shape):
    decomposition is `[x]` and EVERY roster step is `[ ]` (no step started yet).
    The resume target is step 1, so this is steps-partial — NOT an all-steps-done
    review phase. This pins that the steps-partial predicate is "any `[ ]` step
    remains", which covers the all-`[ ]` shape as well as the mixed shape, rather
    than a strict "[x] and [ ] both present" test that would mis-route an
    all-`[ ]` roster."""
    body = _track_doc(
        "- [x] 2026-06-03T04:03Z [ctx=info] Review + decomposition complete\n"
        "- [ ] Step implementation",
        "1. First step — risk: medium  [ ]\n"
        "2. Second step — risk: medium  [ ]\n"
        "3. Third step — risk: medium  [ ]\n",
    )
    state = _substate(body)
    assert state == {"phase": "C", "substate": "steps-partial"}, (
        "an all-[ ] roster after decomposition must be steps-partial (resume from "
        f"step 1), not an all-done review phase, got {state!r}"
    )


def test_state_C_substate_failed_step() -> None:
    """Sub-state 2, failed-step: decomposition is `[x]` and the roster carries a
    `[!]` (failed) step. The `[!]` glyph is recognized (not a parse error), and a
    failed roster routes to the failed-step resume."""
    body = _track_doc(
        "- [x] 2026-06-03T04:03Z [ctx=info] Review + decomposition complete\n"
        "- [x] 2026-06-03T04:20Z [ctx=safe] Step 1 complete (commit abc123)\n"
        "- [!] 2026-06-03T04:29Z [ctx=safe] Step 2 failed",
        "1. First step — risk: medium  [x] commit: abc123\n"
        "2. Second step — risk: high  [!]\n",
    )
    state = _substate(body)
    assert state == {"phase": "C", "substate": "failed-step"}, (
        f"a roster with a [!] step must be failed-step, got {state!r}"
    )


def test_state_C_substate_failed_step_precedes_partial() -> None:
    """failed-step is checked BEFORE steps-partial: a roster that is BOTH failed
    (`[!]`) and partial (`[ ]`) — a failed step plus a not-yet-retried step —
    routes to failed-step, matching workflow.md's `[!]` row (check for a retry
    step) rather than the steps-partial resume. This pins the precedence order."""
    body = _track_doc(
        "- [x] 2026-06-03T04:03Z [ctx=info] Review + decomposition complete\n"
        "- [x] 2026-06-03T04:20Z [ctx=safe] Step 1 complete (commit abc123)\n"
        "- [!] 2026-06-03T04:29Z [ctx=safe] Step 2 failed",
        "1. First step — risk: medium  [x] commit: abc123\n"
        "2. Second step — risk: high  [!]\n"
        "3. Third step — risk: medium  [ ]\n",
    )
    state = _substate(body)
    assert state == {"phase": "C", "substate": "failed-step"}, (
        "a both-failed-and-partial roster must route to failed-step (failed-step "
        f"precedes steps-partial), got {state!r}"
    )


def test_state_C_substate_steps_done_review_pending() -> None:
    """Sub-state 4, steps-done-review-pending: every roster step is `[x]`/`[~]`
    and the `## Progress` code-review entry is NOT yet `[x]` (review pending).
    The pre-seeded `Track-level code review` Progress entry is still `[ ]`, so the
    sub-state is review-pending, not review-done."""
    body = _track_doc(
        "- [x] 2026-06-03T04:03Z [ctx=info] Review + decomposition complete\n"
        "- [x] 2026-06-03T04:29Z [ctx=safe] Step 1 complete (commit abc123)\n"
        "- [x] 2026-06-03T04:40Z [ctx=safe] Step 2 complete (commit def456)\n"
        "- [x] 2026-06-03T04:40Z [ctx=safe] Step implementation\n"
        "- [ ] Track-level code review\n"
        "- [ ] Track completion",
        "1. First step — risk: medium  [x] commit: abc123\n"
        "2. Second step — risk: medium  [x] commit: def456\n",
    )
    state = _substate(body)
    assert state == {"phase": "C", "substate": "steps-done-review-pending"}, (
        "all roster steps [x] with code review [ ] must be "
        f"steps-done-review-pending, got {state!r}"
    )


def test_state_C_substate_steps_done_review_pending_skip_marker_counts_done() -> None:
    """A `[~]` (skipped) roster step counts as done for the all-steps-done test:
    a roster of `[x]` and `[~]` steps (none `[ ]`, none `[!]`) with code review
    `[ ]` is steps-done-review-pending. This pins that `[~]` is not mistaken for an
    unfinished `[ ]` step."""
    body = _track_doc(
        "- [x] 2026-06-03T04:03Z [ctx=info] Review + decomposition complete\n"
        "- [x] 2026-06-03T04:20Z [ctx=safe] Step 1 complete (commit abc123)\n"
        "- [x] 2026-06-03T04:40Z [ctx=safe] Step implementation\n"
        "- [ ] Track-level code review\n"
        "- [ ] Track completion",
        "1. First step — risk: medium  [x] commit: abc123\n"
        "2. Skipped step — risk: low  [~]\n",
    )
    state = _substate(body)
    assert state == {"phase": "C", "substate": "steps-done-review-pending"}, (
        "a roster of [x] and [~] steps (none [ ]) with code review [ ] must be "
        f"steps-done-review-pending ([~] counts as done), got {state!r}"
    )


def test_state_C_substate_review_done_track_open() -> None:
    """Sub-state 5, review-done-track-open: every roster step is `[x]`, the
    `## Progress` code-review entry is `[x]` (review passed), and the plan-file
    track checkbox is still `[ ]` (track completion pending). The most-recent
    code-review Progress entry is `[x]`, so the joint read distinguishes this from
    review-pending; the plan track checkbox is `[ ]` by construction (the
    Checklist walk reached State C on this first `[ ]` track)."""
    body = _track_doc(
        "- [x] 2026-06-03T04:03Z [ctx=info] Review + decomposition complete\n"
        "- [x] 2026-06-03T04:20Z [ctx=safe] Step 1 complete (commit abc123)\n"
        "- [x] 2026-06-03T04:30Z [ctx=safe] Step 2 complete (commit def456)\n"
        "- [x] 2026-06-03T04:40Z [ctx=safe] Step implementation\n"
        "- [x] 2026-06-03T05:00Z [ctx=safe] Track-level code review iteration 1 complete (1/3 iterations)\n"
        "- [x] 2026-06-03T05:01Z [ctx=safe] Track-level code review (PASS iter 1)\n"
        "- [ ] Track completion",
        "1. First step — risk: medium  [x] commit: abc123\n"
        "2. Second step — risk: medium  [x] commit: def456\n",
    )
    state = _substate(body)
    assert state == {"phase": "C", "substate": "review-done-track-open"}, (
        "all roster steps [x], code review [x], plan track [ ] must be "
        f"review-done-track-open, got {state!r}"
    )


def test_state_C_substate_review_entry_most_recent_wins() -> None:
    """The code-review Progress read takes the MOST-RECENT matching entry, not the
    first. An early iteration-1 entry `[x]` followed by a later in-progress
    entry `[ ]` (iteration 2 underway) must report steps-done-review-pending —
    the latest entry is `[ ]`. This pins the continuous-log "most-recent relevant
    entry = current phase" semantics for the code-review read specifically."""
    body = _track_doc(
        "- [x] 2026-06-03T04:03Z [ctx=info] Review + decomposition complete\n"
        "- [x] 2026-06-03T04:20Z [ctx=safe] Step 1 complete (commit abc123)\n"
        "- [x] 2026-06-03T04:40Z [ctx=safe] Step implementation\n"
        "- [x] 2026-06-03T05:00Z [ctx=safe] Track-level code review iteration 1 complete\n"
        "- [ ] 2026-06-03T05:30Z [ctx=safe] Track-level code review iteration 2 in progress",
        "1. First step — risk: medium  [x] commit: abc123\n",
    )
    state = _substate(body)
    assert state == {"phase": "C", "substate": "steps-done-review-pending"}, (
        "the most-recent code-review entry ([ ], iteration 2 in progress) must "
        f"drive review-pending, not the earlier [x] entry, got {state!r}"
    )


def test_state_C_substate_real_completed_track_file_review_done() -> None:
    """A sub-state fixture cut from a REAL on-disk track file (this branch's own
    completed Track 1 shape): a continuous-log `## Progress` with the pre-seeded
    phase checkpoints flipped, interim per-step and per-iteration entries, and a
    numbered roster with all steps `[x] commit: <sha>`. With the plan-file track
    checkbox `[ ]` (the track not yet marked complete in the plan), this resolves
    to review-done-track-open — exercising the full joint read against the
    realistic artifact shape, not an idealized block. The roster lines carry
    backtick-bracketed tokens in their descriptions (e.g. inline `[ ]`), so this
    also pins that the status checkbox is read from the post-`risk:` tail, not the
    description's inline brackets."""
    real_progress = (
        "- [x] 2026-06-02T14:51Z [ctx=info] Review + decomposition complete\n"
        "- [x] 2026-06-02T15:10Z [ctx=safe] Step 1 complete (commit bf6fca2b3f)\n"
        "- [x] 2026-06-02T15:18Z [ctx=safe] Step 2 complete (commit 9cd797f0fc)\n"
        "- [x] 2026-06-02T15:58Z [ctx=safe] Step implementation\n"
        "- [x] 2026-06-03T03:18Z [ctx=safe] Track-level code review iteration 1 complete (1/3 iterations)\n"
        "- [x] 2026-06-03T03:19Z [ctx=safe] Track-level code review (PASS iter 1)"
    )
    # Roster descriptions carry inline backtick-bracketed tokens like `[ ]` so the
    # status-checkbox read must anchor on the post-`risk:` tail, not the first
    # bracket on the line.
    real_roster = (
        "1. Scaffold `workflow-startup-precheck.sh` — `--mode {full,...}` parsing, "
        "unknown-mode error, the `actions_taken` `[]` seam — risk: medium  "
        "[x] commit: bf6fca2b3f\n"
        "2. Branch divergence + git-fixture builder — populate "
        "`divergence{detected,ahead,behind}` — risk: medium  [x] commit: 9cd797f0fc\n"
    )
    body = _track_doc(real_progress, real_roster)
    state = _substate(body)
    assert state == {"phase": "C", "substate": "review-done-track-open"}, (
        "a real completed-track shape (all roster [x], code review [x], plan track "
        f"[ ]) must be review-done-track-open, got {state!r}"
    )


def test_state_C_substate_roster_malformed_status_is_parse_error() -> None:
    """A malformed status checkbox on a roster step (e.g. `[X]`) is a parse error
    on the `## Concrete Steps` section: non-zero exit, no stdout JSON, a stderr
    diagnostic naming the section. The closed-enum rule the top-level walk applies
    extends to the roster status read — `[!]` is recognized, but `[X]` is not.
    Decomposition is `[x]` so the read reaches the roster scan (the
    decomposition-pending short-circuit does not absorb it)."""
    body = _track_doc(
        "- [x] 2026-06-03T04:03Z [ctx=info] Review + decomposition complete\n"
        "- [ ] Step implementation",
        "1. First step — risk: medium  [X]\n",  # malformed status glyph
    )
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        fx.plan_artifact("implementation-plan.md", stamp=head, body=_PLAN_FIRST_TODO_TRACK_2)
        fx.plan_artifact("plan/track-2.md", stamp=head, body=body)
        proc = run_precheck("--mode", "full", cwd=fx.path)
    assert proc.returncode != 0, (
        f"a malformed roster status glyph must exit non-zero, got {proc.returncode}"
    )
    assert proc.stdout.strip() == "", (
        f"a malformed roster status glyph must emit NO stdout JSON, got {proc.stdout!r}"
    )
    assert "malformed checkbox marker" in proc.stderr and "## Concrete Steps" in proc.stderr, (
        f"stderr must name the malformed roster marker and section, got {proc.stderr!r}"
    )


# ---------------------------------------------------------------------------
# Section-discrepancy edge — the roster-to-Progress join keyed on step number.
#
# A roster step flipped `[x]` (done) and its `Step N complete` `## Progress`
# entry are written in lockstep (step-implementation.md sub-step 7), so a `[x]`
# roster step whose number N has NO word-boundary `Step N` Progress entry means
# the two sections are out of sync. That case reports the literal
# `section-discrepancy` so the agent reconciles from the `## Episodes` block,
# rather than resuming on a sub-state computed from inconsistent inputs. A `[!]`
# failed-step Progress entry counts as present-for-N (a reconciled retry is not a
# discrepancy). The hazard the Phase A review flagged is a false positive: a
# healthy track whose `## Progress` interleaves phase-checkpoint lines (`Step
# implementation`, `Track-level code review`) between per-step lines must NOT
# trip the edge, because those lines carry no digit after `Step `.
# ---------------------------------------------------------------------------


def test_state_C_substate_section_discrepancy_present() -> None:
    """The discrepancy edge fires: a roster step is `[x]` (done) but `## Progress`
    has no `Step 2` entry. Step 1 has its `Step 1 complete` entry, but step 2 was
    flipped `[x]` without the orchestrator appending the matching Progress entry
    (the two sections out of sync). The join keyed on step number 2 finds no
    `Step 2` entry, so the sub-state is the literal `section-discrepancy` —
    overriding the steps-done/review resolution the roster alone would suggest."""
    body = _track_doc(
        "- [x] 2026-06-03T04:03Z [ctx=info] Review + decomposition complete\n"
        "- [x] 2026-06-03T04:20Z [ctx=safe] Step 1 complete (commit abc123)\n"
        "- [ ] Track-level code review\n"
        "- [ ] Track completion",
        # Both roster steps are [x], but only step 1 has a Progress entry; step 2
        # is a [x] roster step with no `Step 2` Progress entry -> discrepancy.
        "1. First step — risk: medium  [x] commit: abc123\n"
        "2. Second step — risk: medium  [x] commit: def456\n",
    )
    state = _substate(body)
    assert state == {"phase": "C", "substate": "section-discrepancy"}, (
        "a [x] roster step (step 2) with no `Step 2` Progress entry must report "
        f"section-discrepancy, got {state!r}"
    )


def test_state_C_substate_no_discrepancy_when_phase_checkpoints_interleave() -> None:
    """The false-positive guard: a HEALTHY track whose `## Progress` interleaves
    the pre-seeded phase-checkpoint lines (`Step implementation`, `Track-level code
    review`) between per-step lines must NOT trip the discrepancy. Both roster
    steps are `[x]` and both have their `Step N complete` entries; the interleaved
    `Step implementation` checkpoint carries no digit after `Step `, so it
    contributes no spurious step number. The sub-state resolves normally to
    steps-done-review-pending, not section-discrepancy."""
    body = _track_doc(
        "- [x] 2026-06-03T04:03Z [ctx=info] Review + decomposition complete\n"
        "- [x] 2026-06-03T04:20Z [ctx=safe] Step 1 complete (commit abc123)\n"
        # A phase-checkpoint line interleaved between the per-step lines: it names
        # `Step ` but with no digit, so it must not register a step number.
        "- [x] 2026-06-03T04:40Z [ctx=safe] Step implementation\n"
        "- [x] 2026-06-03T04:50Z [ctx=safe] Step 2 complete (commit def456)\n"
        "- [ ] Track-level code review\n"
        "- [ ] Track completion",
        "1. First step — risk: medium  [x] commit: abc123\n"
        "2. Second step — risk: medium  [x] commit: def456\n",
    )
    state = _substate(body)
    assert state == {"phase": "C", "substate": "steps-done-review-pending"}, (
        "a healthy track with interleaved phase-checkpoint lines (no digit after "
        f"`Step `) must NOT false-positive to section-discrepancy, got {state!r}"
    )


def test_state_C_substate_bang_progress_entry_counts_as_present() -> None:
    """A `[!]` failed-step Progress entry counts as present-for-N: a roster step 1
    flipped `[x]` (a step that failed then was retried to done) whose only
    `## Progress` mention is a `[!]` `Step 1 failed` entry — NO `Step 1 complete` —
    must NOT report section-discrepancy. `progress_step_numbers` records step 1
    from the `Step 1 failed` entry regardless of its checkbox glyph, so the join
    finds step 1 present and the sub-state resolves normally (steps-partial here,
    since step 2 is still `[ ]`)."""
    body = _track_doc(
        "- [x] 2026-06-03T04:03Z [ctx=info] Review + decomposition complete\n"
        # The only Progress mention of step 1 is a [!] failed entry; there is no
        # `Step 1 complete`. It still counts as present-for-N.
        "- [!] 2026-06-03T04:20Z [ctx=safe] Step 1 failed\n"
        "- [ ] Step implementation",
        "1. First step — risk: medium  [x] commit: abc123\n"
        "2. Second step — risk: medium  [ ]\n",
    )
    state = _substate(body)
    assert state == {"phase": "C", "substate": "steps-partial"}, (
        "a [x] roster step whose only Progress entry is a [!] `Step N failed` must "
        f"count as present (no discrepancy); step 2 [ ] drives steps-partial, got {state!r}"
    )


def test_state_C_substate_discrepancy_join_is_exact_step_number() -> None:
    """The roster-to-Progress join is an EXACT step-number match, never a substring
    test: a `Step 12` Progress entry must not satisfy a roster step `1`. Roster
    step 1 is `[x]` with no `Step 1` Progress entry, but a `Step 12` entry is
    present. A naive substring/prefix join would read `Step 12` as covering step 1
    and miss the discrepancy; the exact numeric-equality join reports
    section-discrepancy. Pins the digit-run-whole read in `progress_step_numbers`
    and the equality test in `step_num_in_progress`."""
    body = _track_doc(
        "- [x] 2026-06-03T04:03Z [ctx=info] Review + decomposition complete\n"
        # A `Step 12` entry is present; `Step 1` is NOT. The whole digit run 12
        # must not be read as covering step 1.
        "- [x] 2026-06-03T04:20Z [ctx=safe] Step 12 complete (commit abc123)\n"
        "- [ ] Track-level code review\n"
        "- [ ] Track completion",
        "1. First step — risk: medium  [x] commit: abc123\n",
    )
    state = _substate(body)
    assert state == {"phase": "C", "substate": "section-discrepancy"}, (
        "a `Step 12` Progress entry must not satisfy roster step 1 (exact "
        f"numeric-equality join); the missing `Step 1` entry is a discrepancy, got {state!r}"
    )


def test_state_C_substate_skip_roster_step_not_joined() -> None:
    """A `[~]` (skipped) roster step is NOT subject to the discrepancy join: only
    `[x]` (done) roster steps are joined to a `Step N` Progress entry. A skipped
    step the orchestrator never logged a `Step N complete` entry for must not
    false-positive to section-discrepancy. Roster step 1 is `[x]` with its `Step 1`
    entry; step 2 is `[~]` with no `Step 2` entry. The sub-state resolves to
    steps-done-review-pending ([~] counts as done for the all-steps-done test)."""
    body = _track_doc(
        "- [x] 2026-06-03T04:03Z [ctx=info] Review + decomposition complete\n"
        "- [x] 2026-06-03T04:20Z [ctx=safe] Step 1 complete (commit abc123)\n"
        "- [ ] Track-level code review\n"
        "- [ ] Track completion",
        "1. First step — risk: medium  [x] commit: abc123\n"
        "2. Skipped step — risk: low  [~]\n",  # [~] step has no `Step 2` entry
    )
    state = _substate(body)
    assert state == {"phase": "C", "substate": "steps-done-review-pending"}, (
        "a [~] (skip) roster step is not joined to a Progress entry, so a missing "
        f"`Step 2` entry must not false-positive to section-discrepancy, got {state!r}"
    )


def test_state_C_substate_discrepancy_real_track_file_shape() -> None:
    """The discrepancy edge against a REAL on-disk track-file shape: a continuous-log
    `## Progress` with the pre-seeded phase checkpoints and per-step entries whose
    roster carries backtick-bracketed tokens in its descriptions. Step 1 has its
    `Step 1 complete` Progress entry; step 2 was flipped `[x]` in the roster but
    its `Step 2 complete` Progress entry is missing — the on-disk discrepancy this
    edge exists to catch. The roster descriptions embed inline `[ ]`/`[x]` tokens,
    so this also pins the post-`risk:`-tail status read against the realistic
    shape while exercising the join."""
    real_progress = (
        "- [x] 2026-06-02T14:51Z [ctx=info] Review + decomposition complete\n"
        "- [x] 2026-06-02T15:10Z [ctx=safe] Step 1 complete (commit bf6fca2b3f)\n"
        # Step 2 is [x] in the roster below but has NO `Step 2` Progress entry.
        "- [ ] Step implementation\n"
        "- [ ] Track-level code review\n"
        "- [ ] Track completion"
    )
    real_roster = (
        "1. Scaffold `workflow-startup-precheck.sh` — `--mode {full,...}` parsing, "
        "the `[]` seam — risk: medium  [x] commit: bf6fca2b3f\n"
        "2. Branch divergence — populate `divergence{detected,ahead,behind}` — "
        "risk: medium  [x] commit: 9cd797f0fc\n"
    )
    body = _track_doc(real_progress, real_roster)
    state = _substate(body)
    assert state == {"phase": "C", "substate": "section-discrepancy"}, (
        "a real-shaped track with a [x] roster step 2 lacking its `Step 2` Progress "
        f"entry must report section-discrepancy, got {state!r}"
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


def _extract_script_walks() -> List[str]:
    """Extract every Phase 1 walk in the script: each `for f in $(ls ...)` loop
    through its closing `done`. Returns the loops in file order. The script now
    carries two walks that both byte-copy § 1.6(h): the drift (full-mode) walk
    in detect_drift, and the migrate-range walk in detect_migrate_range. The
    migrate-range walk adds the one sanctioned § 1.6(h) extension — the
    STAMPED_PAIRS `$f=$SHA` pairing — so the two are distinguished by which
    carries STAMPED_PAIRS, not by position alone."""
    text = SCRIPT_PATH.read_text(encoding="utf-8")
    lines = text.splitlines()
    walks: List[str] = []
    i = 0
    while i < len(lines):
        if "for f in $(ls " in lines[i]:
            end = next(
                (j for j in range(i, len(lines)) if lines[j].strip() == "done"),
                None,
            )
            assert end is not None, (
                "could not find the walk loop's closing `done` in the script"
            )
            walks.append("\n".join(lines[i : end + 1]))
            i = end + 1
            continue
        i += 1
    return walks


def _extract_drift_walk() -> str:
    """The drift (full-mode) walk: the one § 1.6(h) walk that does NOT carry the
    STAMPED_PAIRS pairing. The pairing is the migrate-range walk's sanctioned
    extension, so the drift walk is identified by its absence."""
    walks = _extract_script_walks()
    drift = [w for w in walks if "STAMPED_PAIRS" not in w]
    assert len(drift) == 1, (
        f"expected exactly one § 1.6(h) walk without STAMPED_PAIRS (the drift "
        f"walk), found {len(drift)} of {len(walks)} total walks"
    )
    return drift[0]


def _extract_migrate_range_walk() -> str:
    """The migrate-range walk: the one § 1.6(h) walk that DOES carry the
    STAMPED_PAIRS `$f=$SHA` pairing (the sanctioned § 1.6(h) extension that lets
    a merge-base failure name the owning artifact path)."""
    walks = _extract_script_walks()
    mr = [w for w in walks if "STAMPED_PAIRS" in w]
    assert len(mr) == 1, (
        f"expected exactly one § 1.6(h) walk WITH STAMPED_PAIRS (the "
        f"migrate-range walk), found {len(mr)} of {len(walks)} total walks"
    )
    return mr[0]


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
    """Both § 1.6(h) walks in the script — the drift walk and the migrate-range
    walk — enumerate exactly the four § 1.6(h) artifact globs, in the same order
    as the canonical block. A source comparison that catches a glob a walk
    forgot to copy or an extra one it added. The STAMPED_PAIRS pairing line the
    migrate-range walk adds is not a glob line, so it does not perturb the glob
    set; both walks must still match the canonical glob set exactly."""
    canonical = _extract_conventions_h_block()
    canonical_tails = _glob_tails(canonical)
    assert canonical_tails == EXPECTED_GLOB_TAILS, (
        "canonical § 1.6(h) block no longer enumerates the expected glob set; "
        f"found {canonical_tails!r}. If § 1.6(h) intentionally changed, update "
        "EXPECTED_GLOB_TAILS and the script's walks together."
    )
    for label, walk in (
        ("drift", _extract_drift_walk()),
        ("migrate-range", _extract_migrate_range_walk()),
    ):
        assert _glob_tails(walk) == canonical_tails, (
            f"the script's {label} walk glob set drifted from § 1.6(h); "
            f"walk lists {_glob_tails(walk)!r}, canonical lists {canonical_tails!r}"
        )


def test_conformance_anchored_regex_matches_canonical() -> None:
    """The canonical § 1.6(h) block and both script walks carry the anchored
    § 1.6(a1) value-extraction regex verbatim. Pinning the anchored form catches
    a de-anchoring to the bare `[0-9a-f]{40}` variant § 1.6(a1) explicitly
    rejects (false-positives on H1 titles containing a 40-hex run)."""
    canonical = _extract_conventions_h_block()
    assert ANCHORED_REGEX_FRAGMENT in canonical, (
        "canonical § 1.6(h) block no longer carries the anchored regex "
        f"{ANCHORED_REGEX_FRAGMENT!r}; § 1.6(a1) may have changed."
    )
    for label, walk in (
        ("drift", _extract_drift_walk()),
        ("migrate-range", _extract_migrate_range_walk()),
    ):
        assert ANCHORED_REGEX_FRAGMENT in walk, (
            f"the script's {label} walk does not carry the anchored § 1.6(a1) "
            f"regex {ANCHORED_REGEX_FRAGMENT!r} verbatim — it must byte-copy "
            "§ 1.6(h)."
        )


def test_conformance_drift_walk_carries_no_stamped_pairs() -> None:
    """The drift (full-mode) walk is byte-identical to § 1.6(h) with NO
    `STAMPED_PAIRS` pairing — that `$f=$SHA` line is the one sanctioned § 1.6(h)
    extension and belongs only to the migrate-range walk
    (migrate-workflow/SKILL.md Step 2). This pins the whitelist boundary's drift
    side: the drift walk must stay free of the pairing so the conformance suite
    distinguishes the sanctioned extension from accidental drift in the drift
    walk. (`_extract_drift_walk` selects the walk by the *absence* of
    STAMPED_PAIRS, so this re-asserting it documents the contract for a reader
    and guards against a future edit that adds the pairing to the wrong walk.)"""
    drift_walk = _extract_drift_walk()
    assert "STAMPED_PAIRS" not in drift_walk, (
        "the drift (full-mode) walk must not carry STAMPED_PAIRS; the pairing is "
        "the migrate-range walk's sanctioned § 1.6(h) extension, not the drift "
        "walk's."
    )


def test_conformance_migrate_range_walk_carries_stamped_pairs() -> None:
    """The migrate-range walk DOES carry the `STAMPED_PAIRS` `$f=$SHA` pairing —
    the one sanctioned § 1.6(h) extension (migrate-workflow/SKILL.md Step 2
    declares it). This pins the whitelist boundary's migrate-range side: the
    pairing must be present so a merge-base failure can name the owning artifact
    PATH in the skill's recovery re-prompt, and the conformance suite treats it
    as the sanctioned extension rather than flagging it as drift from § 1.6(h).
    The walk is otherwise byte-faithful to § 1.6(h) (the glob-set and
    anchored-regex tests above check that for both walks)."""
    mr_walk = _extract_migrate_range_walk()
    assert "STAMPED_PAIRS" in mr_walk, (
        "the migrate-range walk must carry the STAMPED_PAIRS `$f=$SHA` pairing "
        "(the sanctioned § 1.6(h) extension) so merge-base-failure recovery can "
        "name the owning artifact path."
    )
    # The pairing line follows the canonical migrate-workflow/SKILL.md Step 2
    # form: STAMPED_PAIRS accumulates `$f=$SHA` inside the stamped branch.
    assert 'STAMPED_PAIRS="$STAMPED_PAIRS $f=$SHA"' in mr_walk, (
        "the migrate-range walk's pairing line must match the byte-source form "
        '`STAMPED_PAIRS="$STAMPED_PAIRS $f=$SHA"`; got walk:\n' + mr_walk
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
    ("migrate_range_stamped_pairs_and_log_range", test_migrate_range_stamped_pairs_and_log_range),
    ("migrate_range_unstamped_files_reported", test_migrate_range_unstamped_files_reported),
    ("migrate_range_bootstrap_sha_folded_into_range", test_migrate_range_bootstrap_sha_folded_into_range),
    ("migrate_range_multi_failure_collects_all_pairs", test_migrate_range_multi_failure_collects_all_pairs),
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
    ("state_0_absent_plan_file", test_state_0_absent_plan_file),
    ("state_0_plan_review_unchecked", test_state_0_plan_review_unchecked),
    ("state_0_plan_review_section_absent", test_state_0_plan_review_section_absent),
    ("state_A_first_todo_track_no_track_file", test_state_A_first_todo_track_no_track_file),
    ("state_C_first_todo_track_with_track_file", test_state_C_first_todo_track_with_track_file),
    ("state_C_track_number_drives_track_file_probe", test_state_C_track_number_drives_track_file_probe),
    ("state_D_all_tracks_done_phase4_pending", test_state_D_all_tracks_done_phase4_pending),
    ("state_D_phase4_in_progress", test_state_D_phase4_in_progress),
    ("state_done_phase4_complete", test_state_done_phase4_complete),
    ("state_checklist_anchors_to_top_level_track_lines", test_state_checklist_anchors_to_top_level_track_lines),
    ("state_malformed_marker_is_parse_error", test_state_malformed_marker_is_parse_error),
    ("state_bang_marker_is_recognized_not_malformed", test_state_bang_marker_is_recognized_not_malformed),
    ("state_checklist_malformed_glyph_after_first_todo_is_parse_error", test_state_checklist_malformed_glyph_after_first_todo_is_parse_error),
    ("state_heading_match_tolerates_trailing_whitespace_plan_review", test_state_heading_match_tolerates_trailing_whitespace_plan_review),
    ("state_heading_match_tolerates_trailing_whitespace_final_artifacts", test_state_heading_match_tolerates_trailing_whitespace_final_artifacts),
    ("state_real_track_file_fixture", test_state_real_track_file_fixture),
    ("state_C_substate_decomposition_pending", test_state_C_substate_decomposition_pending),
    ("state_C_substate_decomposition_pending_empty_roster_vacuous_guard", test_state_C_substate_decomposition_pending_empty_roster_vacuous_guard),
    ("state_C_substate_steps_partial_mixed", test_state_C_substate_steps_partial_mixed),
    ("state_C_substate_steps_partial_all_todo_after_decomposition", test_state_C_substate_steps_partial_all_todo_after_decomposition),
    ("state_C_substate_failed_step", test_state_C_substate_failed_step),
    ("state_C_substate_failed_step_precedes_partial", test_state_C_substate_failed_step_precedes_partial),
    ("state_C_substate_steps_done_review_pending", test_state_C_substate_steps_done_review_pending),
    ("state_C_substate_steps_done_review_pending_skip_marker_counts_done", test_state_C_substate_steps_done_review_pending_skip_marker_counts_done),
    ("state_C_substate_review_done_track_open", test_state_C_substate_review_done_track_open),
    ("state_C_substate_review_entry_most_recent_wins", test_state_C_substate_review_entry_most_recent_wins),
    ("state_C_substate_real_completed_track_file_review_done", test_state_C_substate_real_completed_track_file_review_done),
    ("state_C_substate_roster_malformed_status_is_parse_error", test_state_C_substate_roster_malformed_status_is_parse_error),
    ("state_C_substate_section_discrepancy_present", test_state_C_substate_section_discrepancy_present),
    ("state_C_substate_no_discrepancy_when_phase_checkpoints_interleave", test_state_C_substate_no_discrepancy_when_phase_checkpoints_interleave),
    ("state_C_substate_bang_progress_entry_counts_as_present", test_state_C_substate_bang_progress_entry_counts_as_present),
    ("state_C_substate_discrepancy_join_is_exact_step_number", test_state_C_substate_discrepancy_join_is_exact_step_number),
    ("state_C_substate_skip_roster_step_not_joined", test_state_C_substate_skip_roster_step_not_joined),
    ("state_C_substate_discrepancy_real_track_file_shape", test_state_C_substate_discrepancy_real_track_file_shape),
    ("conformance_glob_set_matches_canonical", test_conformance_glob_set_matches_canonical),
    ("conformance_anchored_regex_matches_canonical", test_conformance_anchored_regex_matches_canonical),
    ("conformance_drift_walk_carries_no_stamped_pairs", test_conformance_drift_walk_carries_no_stamped_pairs),
    ("conformance_migrate_range_walk_carries_stamped_pairs", test_conformance_migrate_range_walk_carries_stamped_pairs),
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
