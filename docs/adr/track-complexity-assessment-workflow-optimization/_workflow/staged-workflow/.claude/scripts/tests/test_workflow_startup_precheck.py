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


def _resolve_live_repo_root() -> Path:
    """Walk up from this test file to the first ancestor *outside the staged
    mirror* that holds `.claude/workflow/conventions.md`, returning that ancestor.

    When this suite runs from its normal home (`.claude/scripts/tests/`),
    `REPO_ROOT` (parents[3]) already holds `.claude/workflow/conventions.md`, so
    this returns the same dir. When the suite runs from the branch-local STAGED
    mirror (`docs/adr/<dir>/_workflow/staged-workflow/.claude/scripts/tests/`),
    `REPO_ROOT` is the staged-workflow root. That root may itself carry a staged
    `.claude/workflow/conventions.md` (a branch that staged conventions.md), so a
    naive walk would stop there and resolve live-file anchors inside the staged
    mirror — the conformance byte-source and the `track-review.md` fallback would
    point at the staged subtree, where `track-review.md` need not exist. To avoid
    that, the walk skips any ancestor whose path contains the `staged-workflow`
    segment, so it always passes the staged mirror and reaches the real repo root
    holding the live `.claude/workflow/conventions.md`. The § 1.6(h) conformance
    byte-source then resolves against the live `conventions.md` while
    `SCRIPT_PATH` still points at the co-located (staged) script. The fallback to
    `REPO_ROOT` keeps the suite's behavior unchanged on a checkout that has no
    conventions.md anywhere above (the assertion in the conformance test then
    surfaces the missing byte-source clearly)."""
    for parent in Path(__file__).resolve().parents:
        if "staged-workflow" in parent.parts:
            continue
        if (parent / ".claude" / "workflow" / "conventions.md").is_file():
            return parent
    return REPO_ROOT


# The live repo root, used only to locate the § 1.6(h) conformance byte-source
# (`conventions.md`). `SCRIPT_PATH` deliberately stays anchored at `REPO_ROOT`
# (parents[3]) so a staged copy of this suite exercises the staged script.
LIVE_REPO_ROOT = _resolve_live_repo_root()

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
        # The script shells out to git / mkdir / cat / mv; bound every
        # invocation so a wedged child fails the suite loudly instead of
        # hanging the runner indefinitely. The script is fast and local, so
        # 30s is generous headroom.
        timeout=30,
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
        the drift `git log $BASE_SHA..HEAD -- .claude/workflow/ .claude/skills/
        .claude/agents/` range watches. A run of these between the stamp base and
        HEAD is what makes the Phase 2 range non-empty (the drift-detected case).
        A distinct
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
        range's trailing-slash pathspecs (`.claude/workflow/`, `.claude/skills/`,
        `.claude/agents/`) must NOT match this path — it sits under
        `docs/adr/.../staged-workflow/`, a different prefix, so a commit touching
        only the staged subtree stays out of the `git log` range (the
        staged-subtree-exclusion invariant)."""
        rel = (
            f"docs/adr/{self.default_branch}/_workflow/staged-workflow/"
            f".claude/workflow/staged-{message.replace(' ', '-')}.md"
        )
        path = self.path / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(message + "\n", encoding="utf-8")
        self._git("add", rel)
        self._git("commit", "-q", "-m", message)

    def agents_commit(self, message: str, *, relpath: Optional[str] = None) -> None:
        """Author a commit that touches a path under `.claude/agents/` — the third
        workflow pathspec the drift `git log $BASE_SHA..HEAD -- .claude/workflow/
        .claude/skills/ .claude/agents/` range watches once §1.6(b)/precheck are
        extended to three prefixes. An agent-only commit between the stamp base
        and HEAD must register as drift exactly like a `.claude/workflow/` commit,
        so the property that an agent-only develop commit registers as a
        workflow-format change holds. A distinct relpath per call keeps successive
        commits real."""
        rel = relpath or f".claude/agents/agent-{message.replace(' ', '-')}.md"
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

    # -- no-drift normalization surface --------------------------------------

    def stamped_artifact(self, relpath: str, stamp: str, *, body: str = "# Title\n") -> Path:
        """Author and commit a stamped `_workflow/` plan artifact, returning its
        path. Thin wrapper over plan_artifact for the normalization fixtures,
        where the stamp is always a *real* commit SHA the fold must resolve and a
        distinct stamp per artifact is the multi-SHA precondition the no-drift
        normalization path keys on."""
        return self.plan_artifact(relpath, stamp=stamp, body=body)

    def dirty_workflow_file(self, name: str = "handoff-pending.md") -> Path:
        """Drop an UNTRACKED non-stampable file inside the active plan's
        `_workflow/` and leave it uncommitted. This is the guard-2 trigger: a
        dirty path inside the plan's `_workflow/` that the stamp rewrite did not
        touch must abort the normalization (the gate refuses to swallow an
        unexpected dirty path). The name is a handoff-*.md so it is genuinely
        non-stampable, mirroring the real residue (a mid-phase handoff sitting in
        the worktree) the guard is built to refuse."""
        path = self.plan_dir / "_workflow" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("# pending handoff\n", encoding="utf-8")
        return path

    def dirty_outside_file(self, name: str = "outside-edit.txt") -> Path:
        """Drop an UNTRACKED file OUTSIDE the active plan's `_workflow/` and leave
        it uncommitted. This is the narrow-scope trigger: a dirty file outside the
        plan's `_workflow/` must NOT abort the normalization (the guard-2
        porcelain check is scoped to `$PLAN_DIR/_workflow/`, matching the byte-
        source's narrow dirty-check philosophy)."""
        path = self.path / name
        path.write_text("unrelated working-tree edit\n", encoding="utf-8")
        return path

    def append_body_to_artifact(self, relpath: str, extra: str = "extra body line\n") -> None:
        """Append a line-2+ body edit to an already-committed stamped artifact and
        leave it UNCOMMITTED. This is the guard-1 trigger: with a pre-existing
        body edit below line 1, the post-rewrite `git diff` spans past line 1, so
        guard 1 (every hunk must start `@@ -1`) fires and aborts. The edit
        preserves line 1 (the stamp) so the file still classifies as stamped and
        enters the rewrite; only lines 2.. differ from HEAD."""
        path = self.plan_dir / "_workflow" / relpath
        with path.open("a", encoding="utf-8") as handle:
            handle.write(extra)

    # -- phase-ledger surface ------------------------------------------------

    @property
    def ledger_file(self) -> Path:
        """The phase ledger the precheck resolves from the branch:
        `<plan_dir>/_workflow/phase-ledger.md`."""
        return self.plan_dir / "_workflow" / "phase-ledger.md"

    def write_ledger(self, body: str, *, commit: bool = True) -> Path:
        """Write the phase ledger verbatim (the test supplies the exact event
        lines) and commit it so the working tree is clean for the divergence half
        of the same `full` run. Used by the determine_state ledger-path tests:
        they pin the read contract by supplying hand-authored event lines rather
        than going through `--append-ledger`, so a read regression is isolated
        from an append regression."""
        path = self.ledger_file
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
        if commit:
            rel = str(path.relative_to(self.path))
            self._git("add", rel)
            self._git("commit", "-q", "-m", "add phase-ledger.md")
        return path

    def write_track_only(self, track_num: int, body: str, *, stamp: str) -> Path:
        """Author a stamped `plan/track-<N>.md` for the no-plan `minimal` resume:
        a ledger-driven State C resolves the active track to this file's
        `## Progress` WITHOUT an `implementation-plan.md` on disk. The stamp keeps
        the drift half of the same `full` run a clean all-stamped read (track-1.md
        is the drift anchor when the plan is absent, D13). Line 1 of `body` should
        be a comment so the stamp lands above the sections."""
        return self.plan_artifact(f"plan/track-{track_num}.md", stamp=stamp, body=body)


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


def test_migrate_range_exclude_sha_clears_merge_base_failure() -> None:
    """`--exclude-sha` drops a pruned/unreachable-commit stamp from the fold
    input so a paired `--bootstrap-sha` re-invocation clears the failure. This
    is the recovery the /migrate-workflow skill drives agent-side: a stamp on a
    commit with no reachable common ancestor makes plain `--mode migrate-range`
    report a non-empty `merge_base_failed`; re-invoking with the failing SHA
    excluded and a reachable bootstrap SHA supplied must fold cleanly.

    Fixture: a stamp on an orphan SHA (no merge-base with the main line) is the
    failing stamp; a second stamp sits on a real reachable commit. The sorted
    walk order is design < implementation-plan, so stamping design.md real and
    implementation-plan.md orphan makes the continue fold seed on the real stamp
    then fail merge-base(real, orphan) — one collected failing pair. Excluding
    the orphan SHA leaves only the real stamp in the fold; adding a
    --bootstrap-sha at an earlier ancestor (with a workflow commit between it and
    HEAD) yields a clean base and a non-empty range. The exclusion is
    fold-input-only: stamped_artifacts still reports BOTH artifacts (the raw
    on-disk walk), since the migration re-stamps every artifact during replay."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        bootstrap = fx.head_sha()  # an earlier, reachable ancestor of HEAD
        # A workflow commit lands AFTER the bootstrap commit so bootstrap..HEAD
        # has something to range over once the fold is clean.
        fx.workflow_commit("change after bootstrap")
        real = fx.head_sha()  # reachable from HEAD, shares history with bootstrap
        # An orphan root: no common ancestor with the main line, so a stamp here
        # makes `git merge-base` fail in the fold.
        orphan = fx.orphan_branch("unrelated")
        fx.checkout(fx.default_branch)
        # Sorted walk order design < implementation-plan: stamp design.md real
        # and implementation-plan.md orphan so the continue fold seeds on the
        # real stamp then fails merge-base(real, orphan).
        fx.plan_artifact("design.md", stamp=real)
        fx.plan_artifact("implementation-plan.md", stamp=orphan)
        # Plain migrate-range: the orphan stamp produces a merge-base failure.
        obj_fail = _migrate_range(run_precheck("--mode", "migrate-range", cwd=fx.path))
        assert obj_fail["merge_base_failed"], (
            f"the orphan stamp must produce a non-empty merge_base_failed, got "
            f"{obj_fail['merge_base_failed']!r}"
        )
        assert obj_fail["base_sha"] is None, (
            f"a failed fold reports base_sha null, got {obj_fail['base_sha']!r}"
        )
        # Re-invoke excluding the orphan SHA and supplying the reachable bootstrap
        # SHA: the fold drops the orphan stamp and folds (real, bootstrap) cleanly.
        obj_ok = _migrate_range(
            run_precheck(
                "--mode", "migrate-range",
                "--bootstrap-sha", bootstrap,
                "--exclude-sha", orphan,
                cwd=fx.path,
            )
        )
        assert obj_ok["merge_base_failed"] == [], (
            f"excluding the orphan stamp must clear merge_base_failed, got "
            f"{obj_ok['merge_base_failed']!r}"
        )
        assert obj_ok["base_sha"] is not None, (
            f"a cleared fold must report a non-null base_sha, got "
            f"{obj_ok['base_sha']!r}"
        )
        # bootstrap is the oldest reachable stamp once the orphan is excluded, so
        # the fold base moves back to it and the workflow commit between bootstrap
        # and HEAD enters the range.
        assert obj_ok["base_sha"] == bootstrap, (
            f"the fold base should be the bootstrap ancestor {bootstrap!r}, got "
            f"{obj_ok['base_sha']!r}"
        )
        subjects = [c["subject"] for c in obj_ok["log_range"]]
        assert subjects == ["change after bootstrap"], (
            f"the bootstrap..HEAD range must cover the post-bootstrap workflow "
            f"commit, got {obj_ok['log_range']!r}"
        )
        # The exclusion is fold-input-only: BOTH artifacts still appear in the
        # raw on-disk classification (the migration re-stamps all artifacts).
        stamped_files = [a["file"] for a in obj_ok["stamped_artifacts"]]
        assert any(f.endswith("design.md") for f in stamped_files) and any(
            f.endswith("implementation-plan.md") for f in stamped_files
        ), (
            f"stamped_artifacts must report the raw on-disk walk (both files) "
            f"regardless of --exclude-sha, got {stamped_files!r}"
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
# (`.claude/workflow/`, `.claude/skills/`, `.claude/agents/`). The fixtures here
# stamp artifacts
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
    `git log BASE_SHA..HEAD -- .claude/workflow/ .claude/skills/ .claude/agents/`
    returns the two workflow commits. detected=true, base_sha is the stamp's
    commit, commit_count is 2, and first_commits lists them oldest-first (the
    `--reverse` order) with full subjects. The plan-artifact commit itself
    touches `docs/adr/...`, not a
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


def test_drift_phase4_stamped_folds_to_no_drift() -> None:
    """Skip #2, the headline Phase-4-active fold. The same drift-detected fixture
    as test_drift_phase2_detected_reports_range — an all-stamped plan whose stamp
    points at a real commit, with two `.claude/workflow/` commits sitting between
    that stamp base and HEAD — would normally report detected=true, kind="stamped"
    with commit_count=2. Adding a phase-ledger whose tail records `phase=D` (Phase
    4 pending) flips the outcome: the new skip reads the ledger tail before the
    fold and folds to detected=false. kind stays "stamped" (the skip's emitted
    label), and base_sha is JSON null because the fold never ran — asserted with
    `is None` so the no-fold short-circuit is told apart from a resolved fold whose
    base happened to coincide. This is the regression: before the fix the walk ran
    to the non-empty-range exit and reported drift at Phase 4, which the startup
    dispatch routed to a migration prompt against a subtree the next cleanup commit
    deletes."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        base = fx.head_sha()  # the commit the stamp points at
        # Two workflow-path commits land after the stamp base, so they would fall
        # in the BASE_SHA..HEAD range the drift `git log` watches — the same setup
        # that makes test_drift_phase2_detected_reports_range report drift.
        fx.workflow_commit("first workflow change")
        fx.workflow_commit("second workflow change")
        # The plan artifact is stamped with the real base SHA; its own commit
        # touches docs/adr/... (not a watched path) so it stays out of the range.
        fx.plan_artifact("plan/track-1.md", stamp=base)
        # The Phase-4 ledger: a tail of `phase=D` is what skip #2 keys on. Its
        # own commit touches docs/adr/... so it does not inflate the range either.
        fx.write_ledger("[2026-06-22T16:00Z] [ctx=safe] phase=D\n")
        drift = _drift(run_precheck("--mode", "full", cwd=fx.path))
        assert drift["detected"] is False, (
            f"a phase=D ledger must fold the in-range drift to no-drift: {drift!r}"
        )
        assert drift["kind"] == "stamped", (
            f"skip #2 emits kind='stamped': {drift!r}"
        )
        assert drift["base_sha"] is None, (
            f"skip #2 returns before the fold, so base_sha stays JSON null, got "
            f"{drift['base_sha']!r}"
        )


def test_drift_phase4_empty_input_returns_kind_null_before_skip2() -> None:
    """The skip-ordering invariant: skip #1 (empty-input) must return before skip
    #2 (Phase-4 active). A `_workflow/` holding a phase=D ledger plus only a
    transient handoff-*.md has no stampable artifact, so the empty-input check
    fires first and returns kind=null — it does NOT fall through to skip #2, which
    would emit kind="stamped". detected is false either way (both are silent
    no-drift), so the kind value is the discriminator: a kind of `null` proves the
    empty-input arm ran first, and `is None` tells it apart from the "stamped"
    label skip #2 would have emitted had the ordering been wrong. This pins the
    empty-input-return-before-skip-#2 ordering the skip-#2 placement depends
    on."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        # A phase=D ledger that would trip skip #2 if the empty-input check did
        # not return first. Its commit touches docs/adr/..., not a watched path.
        fx.write_ledger("[2026-06-22T16:00Z] [ctx=safe] phase=D\n")
        # Only a handoff under _workflow/, no stampable artifact: the empty-input
        # arm must fire before skip #2 reads the ledger.
        fx.handoff()
        drift = _drift(run_precheck("--mode", "full", cwd=fx.path))
        assert drift["detected"] is False, (
            f"empty-input Phase-4 branch must not detect drift: {drift!r}"
        )
        assert drift["kind"] is None, (
            f"empty-input must return kind=null BEFORE skip #2 emits 'stamped', "
            f"got {drift['kind']!r}"
        )


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
    `.claude/skills/`, `.claude/agents/`) carry trailing slashes and match those
    top-level directories, not the staged copy under
    `docs/adr/.../staged-workflow/` (a different path prefix). With the stamp at
    the base and only a staged-subtree commit after it, the range is empty:
    detected=false, commit_count=0. This
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


def test_drift_phase2_agents_commit_detected() -> None:
    """An all-stamped plan whose stamp points at a real commit, with a
    `.claude/agents/` commit sitting between that stamp base and HEAD, is drift:
    the third workflow pathspec (`.claude/agents/`, added alongside the §1.6(b)
    stamp base) puts an agent-only commit in the `git log BASE_SHA..HEAD --
    .claude/workflow/ .claude/skills/ .claude/agents/` range exactly like a
    `.claude/workflow/` commit. detected=true, commit_count=1, and the agent
    commit's subject appears in first_commits. Pins the third prefix in
    WORKFLOW_PATHSPECS so a future edit that drops `.claude/agents/` (breaking
    the property that an agent-only develop commit registers as a workflow-format
    change) fails here."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        base = fx.head_sha()  # the commit the stamp points at
        # An agent-path commit lands after the stamp base, so it falls in the
        # BASE_SHA..HEAD range the drift `git log` now watches via the third prefix.
        fx.agents_commit("agent definition change")
        # The plan artifact is stamped with the real base SHA; its own commit
        # touches docs/adr/... (not a watched path) so it stays out of the range.
        fx.plan_artifact("implementation-plan.md", stamp=base)
        drift = _drift(run_precheck("--mode", "full", cwd=fx.path))
        assert drift["detected"] is True, (
            f"an agent-path commit in range must detect drift: {drift!r}"
        )
        assert drift["commit_count"] == 1, (
            f"commit_count should count the one agent commit, got {drift['commit_count']!r}"
        )
        subjects = [c["subject"] for c in drift["first_commits"]]
        assert subjects == ["agent definition change"], (
            f"first_commits should list the agent commit, got {subjects!r}"
        )


# ---------------------------------------------------------------------------
# No-drift normalization — the script's only autonomous mutation.
#
# Byte-source: workflow-drift-check.md § No-drift normalization. The path fires
# on the no-drift read (empty `git log` range) when the stamp set folds to one
# BASE_SHA but carries more than one distinct SHA (stamps on different commits).
# It rewrites every artifact's line-1 stamp to BASE_SHA, verifies two diff-shape
# guards, and lands one commit; on a guard mismatch it restores the tree and
# exits non-zero with no commit.
#
# The fixtures stamp two artifacts at two distinct *real* commits on the same
# linear history (head_sha captured at two points), so the pairwise fold derives
# the older commit as BASE_SHA and the range is empty — the exact multi-SHA
# no-drift precondition. Each `full` run also carries the divergence half, so an
# upstream is added to keep the fetch from reaching the runner's real remote.
#
# Six behaviors, mirroring the track-level acceptance lines:
#   * success         — one commit (subject `Normalize workflow-sha stamps to
#                       <short>`), line-1-only diff, both stamps at BASE_SHA,
#                       valid five-key `full` JSON on stdout, exit 0,
#                       normalization_landed=true.
#   * uniform skip    — a single distinct SHA (both stamps at the same commit)
#                       fires no normalization: no commit, normalization_landed
#                       false.
#   * guard-1 abort   — a pre-existing line-2+ body edit makes the post-rewrite
#                       diff span past line 1: exit non-zero, the off-line-1 hunk
#                       named on stderr, tree at HEAD, no commit, no JSON.
#   * guard-2 abort   — a dirty non-stamped file inside the plan's `_workflow/`:
#                       exit non-zero, the unexpected path named on stderr, tree
#                       at HEAD, no commit, no JSON.
#   * narrow no-abort — a dirty file OUTSIDE `_workflow/` does not abort; the
#                       normalization still lands.
#   * mode no-mutate  — `divergence-only` and `migrate-range` never call the
#                       normalization (detect_drift runs in `full` only), so a
#                       multi-SHA fixture stays unmutated under those modes.
# ---------------------------------------------------------------------------


# The active branch name the normalization fixtures use. Distinct from the
# divergence fixtures' default `main` so the plan dir matches a realistic
# branch-named plan dir; any non-`main` name works since PLAN_DIR is resolved
# from the branch.
NORM_BRANCH = "ytdb-norm-fixture"


def _two_distinct_stamp_commits(fx: "GitFixture") -> Tuple[str, str]:
    """Author two commits on the linear history and return their SHAs (older,
    newer). Stamping one artifact at each gives a stamp set that folds to the
    older SHA (merge-base of two commits on a line is the older) with an empty
    `BASE_SHA..HEAD` range — the multi-SHA no-drift precondition. The caller
    authors an `init` commit and sets the upstream BEFORE calling this (the
    add_bare_remote push needs an existing commit on the branch); these two
    commits then land ahead of upstream, which is purely-ahead and not a
    divergence."""
    fx.commit("base one")
    older = fx.head_sha()
    fx.commit("base two")
    newer = fx.head_sha()
    return older, newer


def _git_top_subject(fx: "GitFixture") -> str:
    """The subject line of the fixture repo's current HEAD commit. Used to assert
    a Normalize commit landed (or did not) on the success / abort / skip paths."""
    return fx._git_out("log", "-1", "--format=%s")


def _git_porcelain(fx: "GitFixture") -> str:
    """The fixture repo's `git status --porcelain` output (working-tree + index).
    Used to assert the tree is clean after a successful normalization and that an
    aborted normalization left the stamped artifacts at HEAD."""
    return fx._git_out("status", "--porcelain")


def test_norm_success_one_commit_line1_only_diff_exit0() -> None:
    """The success path: two artifacts stamped at two distinct real commits fold
    to the older commit as BASE_SHA with an empty workflow-path range, so the
    no-drift normalization fires. It rewrites both line-1 stamps to BASE_SHA,
    verifies the guards, and lands exactly one commit whose subject is
    `Normalize workflow-sha stamps to <short-BASE_SHA>` and whose diff touches
    only line 1 of each artifact. The `full` run still emits the five-key JSON on
    stdout (the success-path `return` into emit_json, not `exit 0`), exits 0, and
    reports drift.normalization_landed=true with base_sha=BASE_SHA. This is the
    headline behavior delta vs today: the silent prose normalization becomes a
    reported, still-silent housekeeping commit."""
    with GitFixture(default_branch=NORM_BRANCH) as fx:
        fx.commit("init")
        fx.add_bare_remote()
        older, newer = _two_distinct_stamp_commits(fx)
        fx.stamped_artifact("implementation-plan.md", older)
        fx.stamped_artifact("plan/track-1.md", newer)
        # Capture the per-artifact line-1 stamps before the run so the rewrite is
        # provable (track-1 starts at `newer`, must end at `older` = BASE_SHA).
        plan_path = fx.plan_dir / "_workflow" / "implementation-plan.md"
        track_path = fx.plan_dir / "_workflow" / "plan" / "track-1.md"
        before_subject = _git_top_subject(fx)

        proc = run_precheck("--mode", "full", cwd=fx.path)
        assert proc.returncode == 0, (
            f"success path should exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
        )
        # The five-key full JSON still emits on stdout (success returns into
        # emit_json rather than exiting).
        obj = json.loads(proc.stdout)
        assert set(obj.keys()) == {
            "divergence", "drift", "handoffs", "state", "actions_taken"
        }, f"full JSON must carry the five-key set, got {sorted(obj.keys())!r}"
        drift = obj["drift"]
        assert drift["normalization_landed"] is True, (
            f"a landed normalization must set normalization_landed=true: {drift!r}"
        )
        assert drift["base_sha"] == older, (
            f"base_sha should be the folded older commit {older!r}, got {drift['base_sha']!r}"
        )
        assert drift["detected"] is False, (
            f"the no-drift read is preserved (detected stays false): {drift!r}"
        )

        # Exactly one Normalize commit landed on top, with the byte-identical
        # subject and the seven-char short BASE_SHA.
        short = older[:7]
        assert _git_top_subject(fx) == f"Normalize workflow-sha stamps to {short}", (
            f"the commit subject must be the byte-source form, got {_git_top_subject(fx)!r}"
        )
        assert before_subject != _git_top_subject(fx), (
            "a new commit must have landed on top of the plan-artifact commit"
        )

        # Both stamps now point at BASE_SHA, and the rewrite touched only line 1.
        assert plan_path.read_text(encoding="utf-8").splitlines()[0] == (
            f"<!-- workflow-sha: {older} -->"
        ), "implementation-plan.md line 1 must be the BASE_SHA stamp"
        assert track_path.read_text(encoding="utf-8").splitlines()[0] == (
            f"<!-- workflow-sha: {older} -->"
        ), "track-1.md line 1 must be rewritten from the newer stamp to BASE_SHA"
        # The committed diff is line-1-only: every hunk header starts `@@ -1`.
        hunks = fx._git_out("show", "--format=", "--unified=0", "HEAD")
        hunk_headers = [ln for ln in hunks.splitlines() if ln.startswith("@@")]
        assert hunk_headers, f"the Normalize commit must carry a diff, got {hunks!r}"
        for header in hunk_headers:
            assert header.startswith("@@ -1"), (
                f"every hunk must start at line 1, got off-line-1 header {header!r}"
            )
        # The working tree is clean after the commit (all-or-nothing landed whole).
        assert _git_porcelain(fx) == "", (
            f"working tree must be clean after a successful normalization, got "
            f"{_git_porcelain(fx)!r}"
        )


def test_norm_uniform_stamps_skip_no_commit() -> None:
    """Stamps already uniform (both artifacts at the SAME commit) make the
    distinct-SHA fire gate count exactly one, so the normalization does not fire:
    no commit lands, normalization_landed stays false, and the run still emits the
    no-drift `full` JSON and exits 0. This pins that the fire gate distinguishes
    an already-uniform stamp set (skip) from a multi-SHA-folds-to-one set
    (normalize) — the new selecting logic the byte-source carries only in prose."""
    with GitFixture(default_branch=NORM_BRANCH) as fx:
        fx.commit("init")
        fx.add_bare_remote()
        older, _newer = _two_distinct_stamp_commits(fx)
        # Both stamps at the SAME commit -> a single distinct SHA -> skip.
        fx.stamped_artifact("implementation-plan.md", older)
        fx.stamped_artifact("plan/track-1.md", older)
        before_subject = _git_top_subject(fx)
        proc = run_precheck("--mode", "full", cwd=fx.path)
        assert proc.returncode == 0, (
            f"uniform-skip should exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
        )
        drift = json.loads(proc.stdout)["drift"]
        assert drift["normalization_landed"] is False, (
            f"already-uniform stamps must not normalize: {drift!r}"
        )
        assert drift["base_sha"] == older, (
            f"the fold still ran and reports base_sha {older!r}, got {drift['base_sha']!r}"
        )
        assert _git_top_subject(fx) == before_subject, (
            f"no commit may land on the uniform-skip path, got new HEAD subject "
            f"{_git_top_subject(fx)!r}"
        )
        assert _git_porcelain(fx) == "", (
            f"the uniform-skip path mutates nothing, got dirty tree {_git_porcelain(fx)!r}"
        )
        # The uniform-skip path performs no mutation, so actions_taken stays the
        # empty array — there is nothing to report.
        assert json.loads(proc.stdout)["actions_taken"] == [], (
            f"the uniform-skip path reports no action, got "
            f"{json.loads(proc.stdout)['actions_taken']!r}"
        )


def test_norm_success_reports_commit_in_actions_taken() -> None:
    """The reporting half of the success path: a landed normalization populates
    `actions_taken` with exactly one entry naming the commit. The entry carries a
    stable `action` label (so a reader branches on the mutation kind without
    parsing prose), the new commit's own short SHA in `commit` (read back from
    HEAD, distinct from the BASE_SHA abbreviation that appears in the subject),
    and the byte-identical `Normalize workflow-sha stamps to <short>` subject.
    This is the one observable behavior delta vs today's fully silent
    normalization: the commit now surfaces in the JSON. The script still never
    prompts — `actions_taken` is a report, not a confirmation gate."""
    with GitFixture(default_branch=NORM_BRANCH) as fx:
        fx.commit("init")
        fx.add_bare_remote()
        older, newer = _two_distinct_stamp_commits(fx)
        fx.stamped_artifact("implementation-plan.md", older)
        fx.stamped_artifact("plan/track-1.md", newer)
        proc = run_precheck("--mode", "full", cwd=fx.path)
        assert proc.returncode == 0, (
            f"success path should exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
        )
        obj = json.loads(proc.stdout)
        # normalization_landed and actions_taken move together on the success
        # path: the flag says a mutation happened, the array names it.
        assert obj["drift"]["normalization_landed"] is True, (
            f"a landed normalization must set normalization_landed=true: {obj['drift']!r}"
        )
        actions = obj["actions_taken"]
        assert isinstance(actions, list) and len(actions) == 1, (
            f"a landed normalization reports exactly one action, got {actions!r}"
        )
        entry = actions[0]
        assert entry["action"] == "normalize-workflow-sha-stamps", (
            f"the entry must carry the stable action label, got {entry!r}"
        )
        # The subject is byte-identical to the byte-source form (BASE_SHA = the
        # folded older commit, abbreviated to seven chars).
        short_base = older[:7]
        assert entry["subject"] == f"Normalize workflow-sha stamps to {short_base}", (
            f"the entry subject must be the byte-source commit subject, got {entry!r}"
        )
        # `commit` is the NEW commit's own short SHA, read back from HEAD — not the
        # BASE_SHA abbreviation in the subject, which the fixture proves are
        # distinct (HEAD is the Normalize commit, BASE_SHA is the older stamp).
        head_short = fx._git_out("rev-parse", "--short", "HEAD")
        assert entry["commit"] == head_short, (
            f"the entry commit must be the Normalize commit's short HEAD SHA "
            f"{head_short!r}, got {entry['commit']!r}"
        )
        assert entry["commit"] != short_base, (
            "the reported commit SHA must be the new commit's own hash, distinct "
            "from the BASE_SHA abbreviation in the subject"
        )


def test_norm_guard1_off_line1_body_edit_aborts() -> None:
    """Guard 1: a stamped artifact carrying a pre-existing line-2+ body edit makes
    the post-rewrite `git diff` span past line 1 (a hunk header naming a start
    line other than 1), so the rewrite is rejected. The gate restores the stamped
    artifacts from HEAD, prints the off-line-1 hunk to stderr, exits non-zero, and
    lands no commit — and emits NO JSON on stdout (the hard `exit 1` halts before
    emit_json). The aborted artifact returns to its HEAD state (the body edit is
    discarded by `git checkout --`, byte-faithful to the byte-source's manual-
    resolution stance)."""
    with GitFixture(default_branch=NORM_BRANCH) as fx:
        fx.commit("init")
        fx.add_bare_remote()
        older, newer = _two_distinct_stamp_commits(fx)
        fx.stamped_artifact("implementation-plan.md", older)
        fx.stamped_artifact("plan/track-1.md", newer)
        before_subject = _git_top_subject(fx)
        # A pre-existing uncommitted body edit below line 1: the post-rewrite diff
        # of this artifact spans line 2+, tripping guard 1.
        fx.append_body_to_artifact("implementation-plan.md")
        proc = run_precheck("--mode", "full", cwd=fx.path)
        assert proc.returncode != 0, (
            f"guard-1 mismatch must exit non-zero, got {proc.returncode}; "
            f"stdout: {proc.stdout!r}"
        )
        assert proc.stdout.strip() == "", (
            f"the abort path emits no JSON on stdout (exit before emit_json), got "
            f"{proc.stdout!r}"
        )
        assert "off-line-1 hunks:" in proc.stderr, (
            f"stderr must name the off-line-1 hunk, got {proc.stderr!r}"
        )
        assert _git_top_subject(fx) == before_subject, (
            f"no commit may land on a guard-1 abort, got new HEAD subject "
            f"{_git_top_subject(fx)!r}"
        )
        # The stamped artifacts are restored to HEAD: the body edit is gone and
        # line 1 is unchanged from the pre-run stamp.
        plan_path = fx.plan_dir / "_workflow" / "implementation-plan.md"
        assert plan_path.read_text(encoding="utf-8").splitlines()[0] == (
            f"<!-- workflow-sha: {older} -->"
        ), "the restored artifact keeps its original line-1 stamp"
        assert "extra body line" not in plan_path.read_text(encoding="utf-8"), (
            "the pre-existing body edit must be discarded by the abort-restore"
        )


def test_norm_guard2_dirty_workflow_file_aborts() -> None:
    """Guard 2: a dirty non-stamped file inside the active plan's `_workflow/`
    (an uncommitted handoff-*.md the rewrite did not touch) makes the porcelain
    status list a path outside the stamped set, so the rewrite is rejected. The
    gate restores the stamped artifacts from HEAD, prints the unexpected path to
    stderr, exits non-zero, lands no commit, and emits NO JSON on stdout. The
    untracked dirty file itself is left in place (the gate refuses to swallow it,
    surfacing it for manual resolution rather than guessing the recovery)."""
    with GitFixture(default_branch=NORM_BRANCH) as fx:
        fx.commit("init")
        fx.add_bare_remote()
        older, newer = _two_distinct_stamp_commits(fx)
        fx.stamped_artifact("implementation-plan.md", older)
        fx.stamped_artifact("plan/track-1.md", newer)
        before_subject = _git_top_subject(fx)
        # A dirty non-stamped file inside the plan's _workflow/ trips guard 2.
        dirty = fx.dirty_workflow_file("handoff-track-1-phaseB.md")
        proc = run_precheck("--mode", "full", cwd=fx.path)
        assert proc.returncode != 0, (
            f"guard-2 mismatch must exit non-zero, got {proc.returncode}; "
            f"stdout: {proc.stdout!r}"
        )
        assert proc.stdout.strip() == "", (
            f"the abort path emits no JSON on stdout, got {proc.stdout!r}"
        )
        assert "unexpected paths:" in proc.stderr, (
            f"stderr must name the unexpected dirty path, got {proc.stderr!r}"
        )
        assert "handoff-track-1-phaseB.md" in proc.stderr, (
            f"stderr must name the specific dirty file, got {proc.stderr!r}"
        )
        assert _git_top_subject(fx) == before_subject, (
            f"no commit may land on a guard-2 abort, got new HEAD subject "
            f"{_git_top_subject(fx)!r}"
        )
        # The stamps are restored to their pre-run state (track-1 still at newer).
        track_path = fx.plan_dir / "_workflow" / "plan" / "track-1.md"
        assert track_path.read_text(encoding="utf-8").splitlines()[0] == (
            f"<!-- workflow-sha: {newer} -->"
        ), "the stamped artifacts return to HEAD on a guard-2 abort"
        # The untracked dirty file is left in place for manual resolution.
        assert dirty.exists(), "the dirty file the gate refused must be left in place"


def test_norm_narrow_scope_dirty_outside_does_not_abort() -> None:
    """Narrow scope: a dirty file OUTSIDE the active plan's `_workflow/` must not
    abort the normalization. Guard 2's porcelain check is scoped to
    `$PLAN_DIR/_workflow/` (the byte-source's narrow dirty-check philosophy: a
    whole-repo clean check is too strict, so unrelated edits outside the plan's
    `_workflow/` have no bearing), so an unrelated working-tree edit elsewhere in
    the repo has no bearing. The normalization still fires and lands its commit;
    normalization_landed is true."""
    with GitFixture(default_branch=NORM_BRANCH) as fx:
        fx.commit("init")
        fx.add_bare_remote()
        older, newer = _two_distinct_stamp_commits(fx)
        fx.stamped_artifact("implementation-plan.md", older)
        fx.stamped_artifact("plan/track-1.md", newer)
        # A dirty file OUTSIDE _workflow/ — must not abort.
        fx.dirty_outside_file("unrelated.txt")
        proc = run_precheck("--mode", "full", cwd=fx.path)
        assert proc.returncode == 0, (
            f"a dirty file outside _workflow/ must not abort; got {proc.returncode}; "
            f"stderr: {proc.stderr!r}"
        )
        drift = json.loads(proc.stdout)["drift"]
        assert drift["normalization_landed"] is True, (
            f"the normalization still fires past an out-of-scope dirty file: {drift!r}"
        )
        assert _git_top_subject(fx) == f"Normalize workflow-sha stamps to {older[:7]}", (
            f"the normalization commit lands despite the out-of-scope dirty file, got "
            f"{_git_top_subject(fx)!r}"
        )
        # A landed commit is reported in actions_taken even when an unrelated
        # out-of-scope dirty file is present (the report follows the mutation).
        actions = json.loads(proc.stdout)["actions_taken"]
        assert len(actions) == 1 and (
            actions[0]["subject"] == f"Normalize workflow-sha stamps to {older[:7]}"
        ), f"the landed commit must be reported in actions_taken, got {actions!r}"


def test_norm_reduced_modes_never_mutate() -> None:
    """`divergence-only` and `migrate-range` never run the no-drift normalization:
    detect_drift (where the normalization lives) is dispatched only for `--mode
    full`, so the mode gate is structural and automatic. Run both reduced modes
    against a multi-SHA fixture that WOULD normalize under `full`, and assert no
    commit lands and the tree stays clean — the path fires only in `full` mode."""
    with GitFixture(default_branch=NORM_BRANCH) as fx:
        fx.commit("init")
        fx.add_bare_remote()
        older, newer = _two_distinct_stamp_commits(fx)
        fx.stamped_artifact("implementation-plan.md", older)
        fx.stamped_artifact("plan/track-1.md", newer)
        before_subject = _git_top_subject(fx)
        for mode in ("divergence-only", "migrate-range"):
            proc = run_precheck("--mode", mode, cwd=fx.path)
            assert proc.returncode == 0, (
                f"{mode} should exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
            )
            assert _git_top_subject(fx) == before_subject, (
                f"{mode} must not land a normalization commit, got new HEAD subject "
                f"{_git_top_subject(fx)!r}"
            )
            assert _git_porcelain(fx) == "", (
                f"{mode} must leave the tree unmutated, got {_git_porcelain(fx)!r}"
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


CONVENTIONS_PATH = LIVE_REPO_ROOT / ".claude" / "workflow" / "conventions.md"

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


def _extract_all_ls_walks() -> List[str]:
    """Extract every `for f in $(ls ...)` loop in the script through its closing
    `done`, in file order. The script carries THREE such loops, all enumerating
    the same § 1.6(h) artifact set:

      * the drift (full-mode) classification walk in detect_drift — builds
        STAMPED_SHAS / UNSTAMPED_FILES;
      * the migrate-range classification walk in detect_migrate_range — builds
        STAMPED_SHAS plus the sanctioned STAMPED_PAIRS `$f=$SHA` extension;
      * the no-drift-normalization RECOMPUTE walk in no_drift_normalization —
        rebuilds the stamped-PATH list (stamped_files) under the same § 1.6(h)
        enumeration, because that walk exports no companion path list. It
        classifies by the full `<!-- workflow-sha: <40-hex> -->` line-1 comment
        rather than the STAMPED_SHAS value-extraction, so it is a third
        § 1.6(h)-enumeration walk, not a fourth classification walk.

    `_extract_script_walks` below scopes to the two CLASSIFICATION walks (the
    STAMPED_SHAS builders); `_extract_normalization_walk` selects the recompute
    walk. This raw extractor is the shared scanner both build on."""
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


def _extract_script_walks() -> List[str]:
    """Extract the two § 1.6(h) CLASSIFICATION walks: the drift (full-mode) walk
    in detect_drift and the migrate-range walk in detect_migrate_range. Both
    build STAMPED_SHAS, so the classification walks are exactly the `ls` loops
    that mention STAMPED_SHAS. This deliberately EXCLUDES the no-drift-
    normalization recompute walk (`_extract_normalization_walk`), which rebuilds
    a stamped-PATH list and never touches STAMPED_SHAS — it is a § 1.6(h)
    enumeration walk but not a classification walk, so the drift / migrate-range
    identification below must not see it.

    The migrate-range walk adds the one sanctioned § 1.6(h) extension — the
    STAMPED_PAIRS `$f=$SHA` pairing — so the two classification walks are
    distinguished by which carries STAMPED_PAIRS, not by position alone."""
    return [w for w in _extract_all_ls_walks() if "STAMPED_SHAS" in w]


def _extract_normalization_walk() -> str:
    """The no-drift-normalization RECOMPUTE walk: the one § 1.6(h)-enumeration
    `ls` loop that does NOT build STAMPED_SHAS (it rebuilds the stamped-PATH list
    `stamped_files` for the in-place stamp rewrite). Identified by the ABSENCE of
    STAMPED_SHAS — the inverse of the classification-walk filter — so a reader
    sees the third walk is accounted for, not silently dropped, and a future edit
    that accidentally folds it back into the classification path is caught."""
    norm = [w for w in _extract_all_ls_walks() if "STAMPED_SHAS" not in w]
    assert len(norm) == 1, (
        f"expected exactly one § 1.6(h)-enumeration walk WITHOUT STAMPED_SHAS "
        f"(the no-drift-normalization recompute walk), found {len(norm)} of "
        f"{len(_extract_all_ls_walks())} total `ls` walks"
    )
    return norm[0]


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


def test_conformance_normalization_recompute_walk_enumerates_canonical_globs() -> None:
    """The no-drift-normalization path adds a THIRD `for f in $(ls ...)` loop: a
    recompute walk that rebuilds the stamped-PATH list (`stamped_files`) under the
    same § 1.6(h) enumeration the classification walks use, because the Phase 1
    walk exports STAMPED_SHAS / UNSTAMPED_FILES but no companion stamped-path
    list. This documents that the third walk EXISTS and is intentionally excluded
    from the drift / migrate-range classification identification — the
    classification selector keys on STAMPED_SHAS, which this recompute walk never
    builds, so it can never be mistaken for the drift or migrate-range walk.

    The walk is still held to the § 1.6(h) enumeration: it must list exactly the
    four canonical artifact globs in order. So a future glob change to § 1.6(h)
    that the recompute walk misses fails here, the same way the classification
    walks are pinned by `test_conformance_glob_set_matches_canonical`. The
    recompute walk classifies by the full `<!-- workflow-sha: <40-hex> -->`
    line-1 comment (the byte-source's `grep -qE` guard), NOT the STAMPED_SHAS
    value-extraction regex, so it is deliberately NOT asserted against the
    anchored § 1.6(a1) value-extraction fragment — that contract is the
    classification walks'."""
    canonical_tails = _glob_tails(_extract_conventions_h_block())
    norm_walk = _extract_normalization_walk()
    assert _glob_tails(norm_walk) == canonical_tails, (
        "the no-drift-normalization recompute walk's glob set drifted from "
        f"§ 1.6(h); walk lists {_glob_tails(norm_walk)!r}, canonical lists "
        f"{canonical_tails!r}"
    )
    # The recompute walk must NOT build STAMPED_SHAS (that is the classification-
    # walk marker the selector keys on) and must NOT carry the migrate-range
    # STAMPED_PAIRS extension — it is a path-recompute walk, distinct from both.
    assert "STAMPED_SHAS" not in norm_walk, (
        "the normalization recompute walk must not build STAMPED_SHAS; building it "
        "would fold the walk into the classification-walk selector and break the "
        "drift / migrate-range identification."
    )
    assert "STAMPED_PAIRS" not in norm_walk, (
        "the normalization recompute walk must not carry STAMPED_PAIRS; that "
        "pairing is the migrate-range walk's sanctioned extension."
    )


# ---------------------------------------------------------------------------
# The phase ledger — the `--append-ledger` mutation and the determine_state
# ledger-path resume.
#
# The ledger (`<plan_dir>/_workflow/phase-ledger.md`) is the append-only event
# log that owns branch-level resume state (D3/D6). `--append-ledger` appends one
# event line atomically (temp-file + rename); `determine_state` reads the ledger
# tail (last-value-wins per key) for the top-level phase and the active track,
# then the track file's `## Progress` for the within-track sub-state — the
# two-level resume. These tests pin:
#
#   * the appended grammar (the contract Track 2 consumes): the fixed
#     `[<ISO>] [ctx=<level>] key=value …` shape with the
#     { phase, track, design_gate, tracks, phase1_complete, reconciled_tag,
#       substate, categories, s17, paused } key set, `categories`
#     quoted, empty fields omitted;
#   * last-value-wins reads (a second append of a changed key is the value read);
#   * the torn (interrupted) append leaving the prior tail intact;
#   * the determine_state ledger path for each phase token (0/A/C/D/Done),
#     including the State C two-level resume into the track file's `## Progress`;
#   * the no-plan `minimal` resume: ledger + `plan/track-1.md` but no
#     `implementation-plan.md` resolves State C with the active track defaulted
#     to `track-1` (no Checklist walk);
#   * the ledger stays excluded by omission from detect_drift's hardcoded
#     artifact list (no drift-walk change; the ledger is never enumerated).
#
# Byte-source: the script header's "The phase ledger" contract block and design
# §"The phase ledger" / §"Resume routing".
# ---------------------------------------------------------------------------


# An ISO-8601-UTC timestamp regex matching the `date -u +%Y-%m-%dT%H:%MZ` the
# append emits (minute precision, trailing Z), used to assert the appended line's
# leading `[<ISO>]` token without pinning the wall-clock value.
_ISO_TS_RE = r"\[\d{4}-\d{2}-\d{2}T\d{2}:\d{2}Z\]"


def _ledger_text(fx: "GitFixture") -> str:
    """Read the on-disk ledger after an `--append-ledger` run."""
    return fx.ledger_file.read_text(encoding="utf-8")


def test_append_ledger_writes_pinned_grammar() -> None:
    """A single `--append-ledger` with every field set writes ONE line in the
    fixed grammar: a leading `[<ISO>]` timestamp, a `[ctx=<level>]` marker, then
    the `phase`/`track`/`design_gate`/`tracks`/`phase1_complete`/`reconciled_tag`/
    `substate`/`categories`/`s17`/`paused` tokens in that order, with `categories`
    the one double-quoted value (it may carry a comma) and the four
    complexity-axis fields (`design_gate`/`tracks`/`phase1_complete`/
    `reconciled_tag`) all bare and emitted in the pre-`categories` block. This
    pins the contract Track 2's readers consume after the `tier=` removal."""
    import re

    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck(
            "--append-ledger",
            "--ctx", "safe",
            "--phase", "A",
            "--track", "1",
            "--design-gate", "yes",
            "--tracks", "3",
            "--phase1-complete", "yes",
            "--reconciled-tag", "high",
            "--categories", "Workflow machinery,Architecture",
            "--s17", "b",
            "--paused", "phase-2",
            cwd=fx.path,
        )
        assert proc.returncode == 0, (
            f"--append-ledger should exit 0, got {proc.returncode}; "
            f"stderr: {proc.stderr!r}"
        )
        assert proc.stdout == "", (
            f"--append-ledger emits no JSON on stdout, got {proc.stdout!r}"
        )
        lines = _ledger_text(fx).splitlines()
        assert len(lines) == 1, f"one append must write exactly one line, got {lines!r}"
        pattern = (
            rf"^{_ISO_TS_RE} \[ctx=safe\] phase=A track=1 "
            r"design_gate=yes tracks=3 phase1_complete=yes reconciled_tag=high "
            r'substate=steps-partial '
            r'categories="Workflow machinery,Architecture" s17=b paused=phase-2$'
        )
        # The substate token in the expected pattern is supplied below so the
        # full pre-`categories` block ordering (every bare reader-consumed field
        # ahead of the one quoted field) is pinned end to end.
        proc2 = run_precheck(
            "--append-ledger",
            "--ctx", "safe",
            "--phase", "A",
            "--track", "1",
            "--design-gate", "yes",
            "--tracks", "3",
            "--phase1-complete", "yes",
            "--reconciled-tag", "high",
            "--substate", "steps-partial",
            "--categories", "Workflow machinery,Architecture",
            "--s17", "b",
            "--paused", "phase-2",
            cwd=fx.path,
        )
        assert proc2.returncode == 0, (
            f"the substate-bearing append should exit 0, got {proc2.returncode}; "
            f"stderr: {proc2.stderr!r}"
        )
        # The second append is the one matched against the full-grammar pattern;
        # it is the last line on disk.
        full_line = _ledger_text(fx).splitlines()[-1]
        assert re.match(pattern, full_line), (
            f"appended line must match the pinned grammar {pattern!r}, got "
            f"{full_line!r}"
        )


def test_append_ledger_omits_empty_fields_and_defaults_ctx() -> None:
    """An append that supplies only `--phase` writes the timestamp, a defaulted
    `[ctx=safe]` marker, and `phase=…` — and NOTHING ELSE. Unsupplied keys are
    omitted entirely (never written as an empty `key=` token), and a missing
    `--ctx` defaults to `safe` so every entry still carries a `[ctx=…]` marker."""
    import re

    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck("--append-ledger", "--phase", "0", cwd=fx.path)
        assert proc.returncode == 0, (
            f"--append-ledger should exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
        )
        line = _ledger_text(fx).splitlines()[0]
        assert re.match(rf"^{_ISO_TS_RE} \[ctx=safe\] phase=0$", line), (
            f"a phase-only append must default ctx and omit empty keys, got {line!r}"
        )
        for absent in (
            "track=", "design_gate=", "tracks=", "phase1_complete=",
            "reconciled_tag=", "substate=", "categories=", "s17=", "paused=",
        ):
            assert absent not in line, (
                f"unsupplied key {absent!r} must be omitted, got {line!r}"
            )


def test_append_ledger_appends_does_not_overwrite() -> None:
    """A second `--append-ledger` APPENDS a new line below the first rather than
    rewriting it — the append-only invariant. Both lines survive on disk; the
    last-value-wins read (asserted separately) is what collapses them to a single
    resolved state."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        run_precheck("--append-ledger", "--phase", "A", cwd=fx.path)
        run_precheck("--append-ledger", "--phase", "C", "--track", "1", cwd=fx.path)
        lines = _ledger_text(fx).splitlines()
        assert len(lines) == 2, f"two appends must leave two lines, got {lines!r}"
        assert "phase=A" in lines[0], f"first line keeps phase=A, got {lines[0]!r}"
        assert "phase=C" in lines[1], f"second line adds phase=C, got {lines[1]!r}"


def test_append_ledger_last_value_wins_on_read() -> None:
    """Two appends of the same key (`design_gate=no` then `design_gate=yes`, a
    mid-flight design-gate change) plus a `phase` change resolve to the LATEST
    value of each key: the ledger-driven state reads `phase=C`/`track=1` from the
    second append, not the first. This is the read half of the append-only /
    last-value-wins contract, exercised on a live complexity-axis field after the
    `tier=` removal."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        run_precheck(
            "--append-ledger", "--phase", "A", "--design-gate", "no", cwd=fx.path
        )
        run_precheck(
            "--append-ledger", "--phase", "C", "--track", "1",
            "--design-gate", "yes",
            cwd=fx.path,
        )
        # A track file so the latest phase=C resolves the two-level State C read.
        fx.write_track_only(
            1,
            "<!-- track fixture -->\n# Track 1\n\n## Progress\n"
            "- [ ] Review + decomposition\n",
            stamp=head,
        )
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "C", "substate": "decomposition-pending"}, (
            "the latest ledger phase (C) and track (1) must win over the earlier "
            f"phase=A entry, got {state!r}"
        )


def test_torn_append_leaves_prior_tail_intact() -> None:
    """An interrupted (torn) append must not corrupt resume state: the atomic
    temp-file-plus-rename means a crash mid-write leaves the PRIOR ledger
    untouched, so `determine_state` resolves the prior state. This simulates the
    torn write by leaving a stray `.phase-ledger.<pid>.tmp` holding a half-written
    line beside a complete prior ledger, then asserting the read still resolves
    the prior tail (the temp file is never read; only the committed ledger is) and
    that the prior ledger bytes are unchanged."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        # A complete prior ledger recording phase=A.
        prior = fx.write_ledger(
            f"[2026-06-15T10:00Z] [ctx=safe] phase=A design_gate=no tracks=1\n"
        )
        prior_bytes = prior.read_bytes()
        # A stray temp file as if a crashed append had written a partial line to
        # the sibling temp without completing the rename. Its name mirrors the
        # script's `.phase-ledger.<pid>.tmp` pattern; a torn body has no newline.
        torn = prior.parent / ".phase-ledger.99999.tmp"
        torn.write_text(
            "[2026-06-15T10:00Z] [ctx=safe] phase=A design_gate=no tracks=1\n"
            "[2026-06-15T11:00Z] [ctx=info] phase=C tra",  # truncated mid-line
            encoding="utf-8",
        )
        # The read must resolve the PRIOR state (phase=A -> State A here, no track
        # file), never the torn temp's partial phase=C.
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "A", "substate": None}, (
            "a torn append (stray temp file) must leave the prior phase=A tail "
            f"authoritative, got {state!r}"
        )
        assert prior.read_bytes() == prior_bytes, (
            "the prior ledger bytes must be untouched by a torn append"
        )


def _ledger_state(phase: str, *, track: str = "", track_body: str = "") -> dict:
    """Run `--mode full` against a branch with a phase ledger (no
    implementation-plan.md) and return the `state` object. The ledger records
    `phase` (and optional `track`); when `track_body` is given a stamped
    `plan/track-<track or 1>.md` is authored so the State C two-level read has a
    `## Progress` to consume. The drift half stays a clean all-stamped read: the
    ledger is unstamped-by-design (excluded from the walk), and the track file,
    when present, carries the HEAD stamp; with no track file the walk is the
    empty-input no-drift path."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        fields = f"phase={phase}"
        if track:
            fields += f" track={track}"
        fx.write_ledger(f"[2026-06-15T12:00Z] [ctx=safe] {fields}\n")
        if track_body:
            fx.write_track_only(int(track or "1"), track_body, stamp=head)
        return _state(run_precheck("--mode", "full", cwd=fx.path))


def test_ledger_path_state_0() -> None:
    """A ledger recording `phase=0` (plan review not yet passed) resolves State 0
    from the ledger, the same conservative pre-gate state the legacy plan-checkbox
    walk gives an unreviewed plan — but now with NO implementation-plan.md on
    disk."""
    state = _ledger_state("0")
    assert state == {"phase": "0", "substate": None}, (
        f"ledger phase=0 must resolve State 0, got {state!r}"
    )


def test_ledger_path_state_A_no_track_file() -> None:
    """A ledger recording `phase=A` resolves State A: the pre-Phase-A state with
    no track file yet. No implementation-plan.md and no track file on disk."""
    state = _ledger_state("A")
    assert state == {"phase": "A", "substate": None}, (
        f"ledger phase=A must resolve State A, got {state!r}"
    )


def test_ledger_path_state_C_resolves_substate_from_track_file() -> None:
    """A ledger recording `phase=C track=2` resolves State C and computes the
    within-track sub-state from `plan/track-2.md`'s `## Progress` — the two-level
    resume: the ledger owns the phase and active track, the track file owns the
    sub-state. Here decomposition is done and the roster has a `[ ]` step, so the
    sub-state is steps-partial."""
    body = (
        "<!-- track fixture -->\n# Track 2\n\n## Progress\n"
        "- [x] 2026-06-15T00:00Z [ctx=info] Review + decomposition complete\n"
        "- [ ] Step implementation\n\n## Concrete Steps\n\n"
        "1. one — risk: high  [ ]\n"
    )
    state = _ledger_state("C", track="2", track_body=body)
    assert state == {"phase": "C", "substate": "steps-partial"}, (
        "ledger phase=C track=2 must resolve State C with the track file's "
        f"sub-state (steps-partial), got {state!r}"
    )


def test_ledger_path_state_C_track_missing_is_state_a() -> None:
    """A ledger recording `phase=C track=1` but with NO `plan/track-1.md` on disk
    resolves State A (pre-decomposition), mirroring the legacy walk's
    first-`[ ]`-track-without-a-file branch — a phase recorded as C cannot resolve
    a sub-state without a track file."""
    state = _ledger_state("C", track="1")
    assert state == {"phase": "A", "substate": None}, (
        f"ledger phase=C with no track file must resolve State A, got {state!r}"
    )


def test_ledger_path_state_D() -> None:
    """A ledger recording `phase=D` resolves State D (Phase 4 pending), with no
    plan file on disk."""
    state = _ledger_state("D")
    assert state == {"phase": "D", "substate": None}, (
        f"ledger phase=D must resolve State D, got {state!r}"
    )


def test_ledger_path_state_done() -> None:
    """A ledger recording `phase=Done` resolves Done (Phase 4 complete)."""
    state = _ledger_state("Done")
    assert state == {"phase": "Done", "substate": None}, (
        f"ledger phase=Done must resolve Done, got {state!r}"
    )


def test_ledger_no_plan_minimal_resume_defaults_track_1() -> None:
    """The headline no-plan `minimal` resume: a ledger recording `phase=C` with
    NO `track` field and a `plan/track-1.md` present but NO
    `implementation-plan.md` resolves State C with the active track defaulted to
    `track-1` — no Checklist walk (there is no plan to walk). This is the case the
    whole `minimal`-drops-the-plan change rests on: such a branch resumes to its
    recorded state instead of restarting as a fresh State 0."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        # Ledger records phase=C but names no track; the active track defaults to 1.
        fx.write_ledger(
            "[2026-06-15T12:00Z] [ctx=info] phase=C design_gate=no tracks=1\n"
        )
        fx.write_track_only(
            1,
            "<!-- track fixture -->\n# Track 1\n\n## Progress\n"
            "- [x] 2026-06-15T00:00Z [ctx=info] Review + decomposition complete\n"
            "- [ ] Step implementation\n\n## Concrete Steps\n\n"
            "1. the single track — risk: high  [ ]\n",
            stamp=head,
        )
        # Guard: there is genuinely no implementation-plan.md on disk.
        assert not (fx.plan_dir / "_workflow" / "implementation-plan.md").exists(), (
            "the no-plan minimal fixture must have no implementation-plan.md"
        )
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "C", "substate": "steps-partial"}, (
            "a plan-less minimal branch with a ledger and plan/track-1.md must "
            f"resume State C (active track defaulted to 1), got {state!r}"
        )


def test_ledger_absent_falls_back_to_plan_checkbox_walk() -> None:
    """Regression guard: a branch with NO ledger and an in-flight `lite`/`full`
    plan still resumes via the legacy plan-checkbox walk. Plan Review passed, the
    single Checklist track is `[ ]` with a track file present, so the walk
    resolves State C — unchanged from before the ledger existed. This is the
    invariant that keeps existing in-flight plans resuming without regression."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        body = _plan_doc(
            PLAN_REVIEW_PASSED,
            "- [ ] Track 1: the active track",
            "- [ ] Phase 4: Final artifacts",
        )
        fx.plan_artifact("implementation-plan.md", stamp=head, body=body)
        fx.plan_artifact(
            "plan/track-1.md",
            stamp=head,
            body="<!-- t -->\n# Track 1\n\n## Progress\n- [ ] Review + decomposition\n",
        )
        # No ledger written.
        assert not fx.ledger_file.exists(), "this regression fixture writes no ledger"
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "C", "substate": "decomposition-pending"}, (
            "with no ledger the legacy plan-checkbox walk must still resolve State "
            f"C, got {state!r}"
        )


def test_ledger_unrecognized_phase_is_parse_error() -> None:
    """A corrupt ledger (an unrecognized `phase=` token) fails loudly via
    parse_error — exit 3, a stderr message naming phase-ledger.md, and NO JSON on
    stdout — rather than silently routing to a wrong state. Mirrors the
    closed-enum parse-error contract the Checklist / section walks apply to
    malformed glyphs."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        fx.write_ledger("[2026-06-15T12:00Z] [ctx=safe] phase=bogus\n")
        proc = run_precheck("--mode", "full", cwd=fx.path)
        assert proc.returncode == 3, (
            f"an unrecognized ledger phase must exit 3, got {proc.returncode}; "
            f"stderr: {proc.stderr!r}"
        )
        assert proc.stdout == "", (
            f"a ledger parse error must emit no JSON, got {proc.stdout!r}"
        )
        assert "phase-ledger.md" in proc.stderr, (
            f"the parse error must name phase-ledger.md, got {proc.stderr!r}"
        )


def test_ledger_categories_value_with_spaces_reads_back_whole() -> None:
    """The quoted `categories` value (which may carry spaces and commas) is
    written and would read back whole. This append-then-inspect pins that the
    grammar's one quoted field survives a round trip: the on-disk line carries the
    full quoted value, and a subsequent `phase` key after it is still its own
    token (the quote does not swallow the rest of the line). The state read on the
    same fixture confirms the trailing `phase` token after the quoted value is
    parsed correctly (last-value-wins on `phase` is unaffected by the quoted
    `categories` before it)."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        run_precheck(
            "--append-ledger",
            "--phase", "A",
            "--categories", "Workflow machinery, Architecture",
            cwd=fx.path,
        )
        line = _ledger_text(fx).splitlines()[0]
        assert 'categories="Workflow machinery, Architecture"' in line, (
            f"the quoted categories value must survive whole, got {line!r}"
        )
        # phase=A precedes categories in the emit order, so it is still its own
        # token; the read resolves State A, proving the quoted value did not
        # corrupt the surrounding tokens.
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
        assert state == {"phase": "A", "substate": None}, (
            f"phase token must read cleanly alongside a quoted categories, got {state!r}"
        )


def test_ledger_excluded_from_drift_walk_by_omission() -> None:
    """The ledger stays excluded from drift detection BY OMISSION from
    detect_drift's hardcoded artifact list (D13) — there is no detect_drift code
    change. A `_workflow/` holding ONLY a phase-ledger.md (no
    implementation-plan.md / design.md / track-*.md) is the empty-input no-drift
    path: the walk enumerates none of its four hardcoded globs, so the ledger is
    never classified stamped or unstamped, and drift reports `detected=false` with
    a null kind. An UNSTAMPED ledger therefore does NOT trip the unstamped-drift
    short-circuit — proving the ledger is not in the walk's set."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        # Only a ledger in _workflow/ — and it is unstamped (no workflow-sha
        # line), which WOULD trip drift if the walk enumerated it.
        fx.write_ledger("[2026-06-15T12:00Z] [ctx=safe] phase=A\n")
        proc = run_precheck("--mode", "full", cwd=fx.path)
        assert proc.returncode == 0, (
            f"full should exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
        )
        obj = json.loads(proc.stdout)
        assert obj["drift"]["detected"] is False, (
            "an unstamped ledger as the only _workflow/ artifact must NOT register "
            f"drift (it is excluded by omission from the walk), got {obj['drift']!r}"
        )
        assert obj["drift"]["kind"] is None, (
            "the ledger-only _workflow/ is the empty-input no-drift path (null "
            f"kind), got {obj['drift']!r}"
        )


def test_ledger_anchor_track_file_keeps_drift_clean_when_plan_absent() -> None:
    """With the plan dropped (no implementation-plan.md), `track-1.md` is the
    drift anchor (D13): a stamped track-1.md plus an unstamped-by-design ledger is
    a clean all-stamped no-drift read. This pins that dropping the plan does not
    weaken drift detection — the walk still finds a stamped anchor and the ledger
    is silently excluded."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        fx.write_ledger("[2026-06-15T12:00Z] [ctx=safe] phase=C track=1\n")
        # track-1.md is the lone stamped artifact (the drift anchor); no plan.
        fx.write_track_only(
            1,
            "<!-- t -->\n# Track 1\n\n## Progress\n- [ ] Review + decomposition\n",
            stamp=head,
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


def test_append_ledger_mutually_exclusive_with_mode() -> None:
    """`--append-ledger` and `--mode` are mutually exclusive: passing both is a
    caller error (exit 2, usage on stderr, no JSON), because one is a mutation and
    the other a JSON reporter."""
    with GitFixture() as fx:
        fx.commit("init")
        proc = run_precheck("--append-ledger", "--mode", "full", "--phase", "A", cwd=fx.path)
        assert proc.returncode == 2, (
            f"--append-ledger + --mode must exit 2, got {proc.returncode}; "
            f"stderr: {proc.stderr!r}"
        )
        assert proc.stdout == "", f"the error path emits no JSON, got {proc.stdout!r}"


# ---------------------------------------------------------------------------
# Append-path hardening — value validation, failure surfacing, and temp cleanup.
#
# The append primitive is the resume state machine and its grammar is the
# contract Track 2 consumes, so a malformed value must fail loudly rather than
# silently corrupt the ledger tail, a failed write must surface a non-zero exit
# rather than masquerade as a recorded boundary, and a failed append must leave
# no orphan temp file. These pin those three properties.
# ---------------------------------------------------------------------------


def test_append_ledger_rejects_newline_in_field() -> None:
    """A newline smuggled into any field is rejected (exit 3 + stderr diagnostic)
    and the ledger is NOT written. Without the guard, the newline would write a
    second physical line whose `phase=Done` tail the last-value-wins reader would
    resolve as the phase — routing resume to the wrong state. Mirrors the read
    path's loud-reject posture for an unrecognized phase."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck(
            "--append-ledger", "--phase", "X\nphase=Done", cwd=fx.path
        )
        assert proc.returncode == 3, (
            f"a newline in a field must exit 3, got {proc.returncode}; "
            f"stderr: {proc.stderr!r}"
        )
        assert "newline" in proc.stderr, (
            f"the reject must name the newline cause, got {proc.stderr!r}"
        )
        assert not fx.ledger_file.exists(), (
            "a rejected append must not create the ledger"
        )


def test_append_ledger_rejects_double_quote_in_categories() -> None:
    """An embedded double quote in `categories` is rejected (exit 3): a `"` would
    close the quoted span early, and the reader's `%%\"*` truncation would drop
    the rest of the value. `categories` is the one quoted field, so a `"` is
    outside its documented spaces-and-commas alphabet."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck(
            "--append-ledger", "--phase", "A", "--categories", 'foo"bar', cwd=fx.path
        )
        assert proc.returncode == 3, (
            f"a double quote in categories must exit 3, got {proc.returncode}; "
            f"stderr: {proc.stderr!r}"
        )
        assert "double quote" in proc.stderr, (
            f"the reject must name the double-quote cause, got {proc.stderr!r}"
        )
        assert not fx.ledger_file.exists(), (
            "a rejected append must not create the ledger"
        )


def test_append_ledger_rejects_space_in_bare_field() -> None:
    """A space in a bare-token field (here `phase`) is rejected (exit 3): the
    reader splits a bare value at the first space, so a space would truncate the
    value or spawn a spurious `key=value` token. Only `categories` may carry
    spaces."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck("--append-ledger", "--phase", "A B", cwd=fx.path)
        assert proc.returncode == 3, (
            f"a space in a bare field must exit 3, got {proc.returncode}; "
            f"stderr: {proc.stderr!r}"
        )
        assert "bare token" in proc.stderr, (
            f"the reject must name the bare-token cause, got {proc.stderr!r}"
        )
        assert not fx.ledger_file.exists(), (
            "a rejected append must not create the ledger"
        )


def test_append_ledger_allows_spaces_in_categories() -> None:
    """The complement to the bare-field space reject: a space in `categories` is
    ALLOWED (it is the one quoted field), so an append carrying a multi-word
    category list exits 0 and writes the quoted value whole. This guards against
    the validation over-rejecting the one field the grammar permits spaces in."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck(
            "--append-ledger",
            "--phase", "A",
            "--categories", "Workflow machinery, Architecture",
            cwd=fx.path,
        )
        assert proc.returncode == 0, (
            f"a space in categories must be allowed (exit 0), got {proc.returncode}; "
            f"stderr: {proc.stderr!r}"
        )
        line = fx.ledger_file.read_text(encoding="utf-8").splitlines()[0]
        assert 'categories="Workflow machinery, Architecture"' in line, (
            f"the multi-word category list must be written whole, got {line!r}"
        )


def test_append_ledger_surfaces_write_failure_nonzero() -> None:
    """A failed append (here an unwritable `_workflow/` dir, simulating a
    permissions / disk-full failure) surfaces a NON-ZERO exit and a stderr
    diagnostic rather than silently exiting 0. The orchestrator treats a 0 exit as
    "the boundary is recorded", so a lost append must not masquerade as success.
    The dir is chmod'd back so the fixture's temp-dir cleanup can remove it."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        # Pre-create the _workflow/ dir, then make it unwritable so the temp-file
        # write inside append_ledger fails.
        workflow_dir = fx.plan_dir / "_workflow"
        workflow_dir.mkdir(parents=True, exist_ok=True)
        os.chmod(workflow_dir, 0o500)
        try:
            proc = run_precheck("--append-ledger", "--phase", "C", cwd=fx.path)
        finally:
            # Restore write permission unconditionally so TemporaryDirectory
            # cleanup (and any later assertion) can touch the dir.
            os.chmod(workflow_dir, 0o700)
        assert proc.returncode != 0, (
            f"a failed write must exit non-zero, got {proc.returncode}; "
            f"stderr: {proc.stderr!r}"
        )
        assert "append-ledger" in proc.stderr, (
            f"a failed append must emit a diagnostic, got {proc.stderr!r}"
        )


def test_append_ledger_no_orphan_temp_on_success() -> None:
    """The success path leaves no `.phase-ledger.<pid>.tmp` orphan: the rename
    consumes the temp, and the RETURN trap's `rm -f` is then a harmless no-op. A
    second append (over an existing ledger) exercises the cat-existing branch and
    must likewise leave no temp behind."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        run_precheck("--append-ledger", "--phase", "A", cwd=fx.path)
        run_precheck("--append-ledger", "--phase", "C", "--track", "1", cwd=fx.path)
        workflow_dir = fx.plan_dir / "_workflow"
        orphans = [p.name for p in workflow_dir.iterdir() if p.name.endswith(".tmp")]
        assert orphans == [], (
            f"no .tmp orphan must remain after a successful append, got {orphans!r}"
        )


def test_append_ledger_no_orphan_temp_on_failure() -> None:
    """The failure path leaves no orphan temp either: the RETURN trap reaps the
    PID-suffixed temp on a failed write. Simulates the failure by making the
    `_workflow/` dir unwritable AFTER pre-seeding it (so mkdir -p succeeds but the
    temp write fails), then asserts no `.tmp` survives once permission is
    restored."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        workflow_dir = fx.plan_dir / "_workflow"
        workflow_dir.mkdir(parents=True, exist_ok=True)
        # A prior committed ledger so the failing branch is the cat-existing one;
        # its bytes also confirm the failed append did not corrupt it.
        ledger = workflow_dir / "phase-ledger.md"
        ledger.write_text("[2026-06-15T10:00Z] [ctx=safe] phase=A\n", encoding="utf-8")
        os.chmod(workflow_dir, 0o500)
        try:
            proc = run_precheck("--append-ledger", "--phase", "C", cwd=fx.path)
        finally:
            os.chmod(workflow_dir, 0o700)
        assert proc.returncode != 0, (
            f"the failure fixture must exit non-zero, got {proc.returncode}"
        )
        orphans = [p.name for p in workflow_dir.iterdir() if p.name.endswith(".tmp")]
        assert orphans == [], (
            f"the RETURN trap must reap the temp on a failed append, got {orphans!r}"
        )


def test_track_review_step6_carries_ac_ledger_append() -> None:
    """The §What You Do step-6 region of `track-review.md` carries the A→C
    phase-ledger append call. Reproduces the YTDB-1140 bug: the absence of any
    A→C `--append-ledger` site let a resumed session re-run Phase A. The bug
    lives at the doc level — the script already supports `--phase C`; the missing
    piece was the instruction to call it. A regression guard the bug fix requires
    per CLAUDE.md.

    Resolution (§ 1.7(d) read precedence): `track-review.md` is read from the
    STAGED copy under the module's `REPO_ROOT` anchor
    (`Path(__file__).resolve().parents[3]`, the same anchor `SCRIPT_PATH` uses)
    when that copy is present, else from the LIVE copy under `LIVE_REPO_ROOT`
    (walked up to the real repo root). A branch that STAGES a fixed
    `track-review.md` is checked against its staged fix during its own Phase B/C;
    a branch that does NOT stage `track-review.md` — because the A→C append
    already lives in the live file, as on this branch — is checked against the
    live copy, which carries the fix. After the Phase 4 promotion the two anchors
    coincide on the fixed file, so the guard stays green during the branch and
    after. This is § 1.7(d)'s "staged copy authoritative when present, otherwise
    the live file."

    Match shape: the three flags `--append-ledger`, `--phase C`, and `--track`
    are matched ORDER-INDEPENDENTLY within the step-6 region — never as a fixed
    contiguous string. The canonical argument order (script Usage, the
    `[--ctx <level>]` slot) puts `--ctx` before `--phase`, so the real call reads
    `--append-ledger --ctx <level> --phase C --track <N>`; a contiguous-string
    guard would false-negative against the correct call. The region is sliced to
    §What You Do step 6 specifically (not the whole file) to avoid the self-match
    trap: §Phase A Completion step 2's verification prose also contains the
    literal `phase=C track=<N>` and a `--phase C` recovery snippet, so a
    whole-file substring match would self-satisfy from that prose even if the
    real step-6 call were deleted, guarding a string's appearance rather than the
    instruction's presence."""
    import re

    # § 1.7(d) read precedence: prefer the STAGED copy (co-located under
    # REPO_ROOT, parents[3]) when present, else fall back to the LIVE copy
    # (LIVE_REPO_ROOT, walked up to the real repo root). A branch that has staged
    # a FIXED track-review.md is checked against the fix during its own Phase B/C;
    # a branch that did NOT stage track-review.md (the A→C append already lives in
    # the live file, as on this branch) checks the live copy, which carries the
    # fix. After the Phase 4 promotion the two anchors coincide on the fixed file.
    staged_track_review = REPO_ROOT / ".claude" / "workflow" / "track-review.md"
    live_track_review = LIVE_REPO_ROOT / ".claude" / "workflow" / "track-review.md"
    track_review = (
        staged_track_review if staged_track_review.is_file() else live_track_review
    )
    assert track_review.is_file(), (
        f"track-review.md must exist at the staged anchor ({staged_track_review}) "
        f"or the live anchor ({live_track_review})"
    )
    text = track_review.read_text(encoding="utf-8")

    # Slice the §What You Do step-6 region: from the step-6 list item to the next
    # `### ` section heading (`### Tier-driven review selection…`). The slice
    # excludes §Phase A Completion step 2, so the self-match trap (step 2's
    # verification prose carrying `phase=C track=<N>` and a `--phase C` recovery
    # snippet) cannot satisfy the guard.
    step6_start = re.search(r"(?m)^6\. \*\*Append the A.C ledger boundary", text)
    assert step6_start is not None, (
        "could not locate the §What You Do step-6 heading "
        "('6. **Append the A→C ledger boundary…') in track-review.md; the "
        "step-6 region anchor changed"
    )
    next_heading = re.search(r"(?m)^### ", text[step6_start.end():])
    region_end = (
        step6_start.end() + next_heading.start()
        if next_heading is not None
        else len(text)
    )
    region = text[step6_start.start():region_end]

    for flag in ("--append-ledger", "--phase C", "--track"):
        assert flag in region, (
            f"the §What You Do step-6 region must carry {flag!r} so the A→C "
            f"ledger append cannot be silently dropped again (YTDB-1140); the "
            f"region read was:\n{region}"
        )


# ---------------------------------------------------------------------------
# Ledger-first sub-state read — the `substate` ledger key (D1).
#
# The State-C sub-state now comes from a track-scoped `substate` ledger key
# read BEFORE the roster: when the ledger carries a `substate` for the active
# track the precheck emits that slug directly, never touching the track-file
# roster; an empty `substate` falls through to the wrap-fixed `roster_scan`
# fallback. These groups pin the four committed slugs, track-scoping, the S1
# pre-`categories` decoy guard, the empty-substate fallback, dual-path parity,
# the wrapped-roster regression (YTDB-1134), and `--substate` append validation.
# Each fixture stamps its artifacts with a real HEAD commit so the drift half of
# the same `full` run stays a clean all-stamped read.
#
# The four committed slugs the ledger path emits 1:1 (workflow.md step 5 routes
# on these byte-for-byte). `failed-step` and `section-discrepancy` are NOT
# committed slugs — they exist only on the roster fallback path, so they never
# appear as a ledger `substate` value.
_COMMITTED_SUBSTATES = (
    "decomposition-pending",
    "steps-partial",
    "steps-done-review-pending",
    "review-done-track-open",
)


def _ledger_substate_state(
    ledger_body: str, *, track_num: int = 2, track_body: Optional[str] = None
) -> dict:
    """Run `--mode full` against a no-plan branch whose phase ledger is
    `ledger_body` (written verbatim) and return the `state` object. When
    `track_body` is given a stamped `plan/track-<track_num>.md` is authored so
    the fallback arm has a roster to read; the ledger-path tests deliberately
    pass a roster that implies a DIFFERENT slug than the ledger, proving the
    ledger value wins and the roster is not consulted. With no `track_body` the
    ledger-only read is exercised (the substate resolves even with no track file
    on disk). The stamp keeps the drift half clean: the ledger is unstamped by
    design (excluded from the walk), and the track file, when present, carries
    the HEAD stamp."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        fx.write_ledger(ledger_body)
        if track_body is not None:
            fx.write_track_only(track_num, track_body, stamp=head)
        return _state(run_precheck("--mode", "full", cwd=fx.path))


# A track-file roster that resolves to steps-partial on the fallback path: one
# step is `[ ]`. Used by the ledger-path tests as the decoy roster — the ledger
# `substate` must win over whatever this roster would imply.
_ROSTER_IMPLIES_PARTIAL = (
    "<!-- track fixture -->\n# Track 2\n\n## Progress\n"
    "- [x] 2026-06-20T00:00Z [ctx=info] Review + decomposition complete\n"
    "- [ ] Step implementation\n\n## Concrete Steps\n\n"
    "1. only step — risk: high  [ ]\n"
)


def test_ledger_substate_four_committed_slugs_resolve() -> None:
    """Ledger path: for each of the four committed slugs, a ledger tail carrying
    `phase=C track=2 substate=<slug>` resolves `state.substate` to that slug,
    regardless of the roster shape. Each case pairs the ledger slug with a roster
    that implies steps-partial, so a roster read would give a different answer;
    resolving the ledger slug proves the roster is never consulted on this path."""
    for slug in _COMMITTED_SUBSTATES:
        ledger = f"[2026-06-20T12:00Z] [ctx=info] phase=C track=2 substate={slug}\n"
        state = _ledger_substate_state(
            ledger, track_num=2, track_body=_ROSTER_IMPLIES_PARTIAL
        )
        assert state == {"phase": "C", "substate": slug}, (
            f"ledger substate={slug} must resolve to that slug over the "
            f"steps-partial roster, got {state!r}"
        )


def test_ledger_substate_resolves_without_track_file() -> None:
    """Ledger path, ledger-only: a `phase=C track=2 substate=review-done-track-open`
    ledger with NO `plan/track-2.md` on disk still resolves the slug. The ledger
    is authoritative for the within-track sub-state, so the read never depends on
    the track file — only the empty-substate fallback does. This is what lets a
    no-plan minimal branch resume its recorded sub-state even before (or without)
    a track file present.

    The fixture writes a stamped `plan/track-1.md` as the drift anchor (so the
    all-stamped drift read stays clean with the plan dropped) but names track 2 on
    the ledger, so track-2.md is genuinely absent."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        head = fx.head_sha()
        fx.write_ledger(
            "[2026-06-20T12:00Z] [ctx=info] phase=C track=2 "
            "substate=review-done-track-open\n"
        )
        # A stamped track-1.md anchors the drift walk; track-2.md (the active
        # track) is deliberately absent so the read is ledger-only.
        fx.write_track_only(
            1,
            "<!-- t -->\n# Track 1\n\n## Progress\n- [ ] Review + decomposition\n",
            stamp=head,
        )
        assert not (fx.plan_dir / "_workflow" / "plan" / "track-2.md").exists(), (
            "the ledger-only fixture must have no track-2.md on disk"
        )
        state = _state(run_precheck("--mode", "full", cwd=fx.path))
    assert state == {"phase": "C", "substate": "review-done-track-open"}, (
        "a ledger substate must resolve even with the active track file absent, "
        f"got {state!r}"
    )


def test_ledger_substate_track_scoped_not_global_last() -> None:
    """Ledger path, track-scoping (S1): a ledger carrying track 1's terminal
    `substate` followed by track 2's `substate` resolves track 2's value, NOT the
    global last value. A global last-value-wins read would wrongly return track
    1's terminal sub-state if track 1's line came last; here track 2's line is the
    active track, so the track-scoped reader keeps track 2's value. This guards
    the leak the track-scoping is built to prevent: a completed prior track's
    terminal sub-state bleeding into the next track's resume."""
    # Two lines: track 1 finished (review-done-track-open), then track 2 partial.
    # The active track is 2 (the last `track=` the phase=C arm resolves), so the
    # reader must return track 2's substate, ignoring track 1's terminal value.
    ledger = (
        "[2026-06-20T10:00Z] [ctx=info] phase=C track=1 "
        "substate=review-done-track-open\n"
        "[2026-06-20T11:00Z] [ctx=info] phase=C track=2 substate=steps-partial\n"
    )
    state = _ledger_substate_state(ledger, track_num=2, track_body=_ROSTER_IMPLIES_PARTIAL)
    assert state == {"phase": "C", "substate": "steps-partial"}, (
        "the track-scoped read must return track 2's substate, not track 1's "
        f"terminal review-done-track-open, got {state!r}"
    )


def test_ledger_substate_track_scoped_keeps_last_for_track() -> None:
    """Ledger path, track-scoping resolves the LAST value FOR THE ACTIVE TRACK:
    two lines for track 2 (an earlier steps-partial, then a later
    steps-done-review-pending) resolve the later one, while an interleaved track 1
    line is ignored entirely. This proves the reader is last-value-wins WITHIN the
    track's lines, not merely first-match."""
    ledger = (
        "[2026-06-20T09:00Z] [ctx=info] phase=C track=2 substate=steps-partial\n"
        "[2026-06-20T10:00Z] [ctx=info] phase=C track=1 "
        "substate=review-done-track-open\n"
        "[2026-06-20T11:00Z] [ctx=info] phase=C track=2 "
        "substate=steps-done-review-pending\n"
    )
    state = _ledger_substate_state(ledger, track_num=2, track_body=_ROSTER_IMPLIES_PARTIAL)
    assert state == {"phase": "C", "substate": "steps-done-review-pending"}, (
        "the track-scoped read must keep track 2's LAST substate "
        f"(steps-done-review-pending), ignoring the interleaved track 1 line, "
        f"got {state!r}"
    )


def test_ledger_substate_categories_decoy_does_not_win() -> None:
    """Ledger path, the pre-`categories` read (S1): a ledger line whose quoted
    `categories="…"` field embeds a decoy ` track=9 substate=decoy-slug` span
    resolves the REAL bare `track=2` / `substate=steps-done-review-pending` tokens
    that precede the quoted field, never the decoy inside it. The append emitter
    writes every bare reader-consumed key BEFORE the one quoted field, and the
    token reader takes the first ` track=` / ` substate=` match and stops, so the
    decoy after the quote can never win. This pins the emit-order safety
    invariant the whole scheme rests on."""
    # The bare track=2 / substate=… precede the quoted categories that embeds a
    # same-named decoy. The reader must resolve the bare tokens.
    ledger = (
        '[2026-06-20T12:00Z] [ctx=info] phase=C track=2 '
        'substate=steps-done-review-pending '
        'categories="Workflow machinery track=9 substate=decoy-slug"\n'
    )
    state = _ledger_substate_state(ledger, track_num=2, track_body=_ROSTER_IMPLIES_PARTIAL)
    assert state == {"phase": "C", "substate": "steps-done-review-pending"}, (
        "the real bare track=/substate= before the quoted categories must win "
        f"over the decoy embedded inside it, got {state!r}"
    )


def test_ledger_empty_substate_falls_through_to_roster() -> None:
    """Fallback path (S2): a `phase=C track=2` ledger with NO `substate` key
    resolves through `determine_c_substate` and the wrap-fixed `roster_scan`,
    emitting the roster-derived slug — the pre-this-change behavior. The roster
    here implies steps-partial, so the absence of a ledger `substate` must surface
    that roster-derived slug, proving the fallback fires when the ledger is
    silent (a ledger written before this key existed)."""
    ledger = "[2026-06-20T12:00Z] [ctx=info] phase=C track=2\n"
    state = _ledger_substate_state(ledger, track_num=2, track_body=_ROSTER_IMPLIES_PARTIAL)
    assert state == {"phase": "C", "substate": "steps-partial"}, (
        "an empty ledger substate must fall through to the roster-derived slug "
        f"(steps-partial here), got {state!r}"
    )


def test_ledger_dual_path_parity_same_substate() -> None:
    """Dual-path parity (S3, the D2 mandate): ONE track-file fixture run through
    TWO ledger variants resolves to the IDENTICAL sub-state on both arms. The
    ledger-path variant carries `substate=steps-done-review-pending`; the fallback
    variant omits the `substate` token, and the track-file roster (all steps `[x]`,
    code review not yet done) independently implies the same
    steps-done-review-pending. Both must resolve identically.

    Non-vacuity is a fixture property: `determine_c_substate` reads no ledger, so
    the fallback arm exercises the roster path ONLY because its ledger omits
    `substate`. Building the fallback fixture's ledger WITHOUT the token (no new
    harness helper needed) is the 'strip'; a fallback fixture that left `substate`
    on its ledger would make both arms read the ledger and the assertion vacuous —
    so the fallback ledger below carries phase/track only."""
    # The shared track-file roster: every step done, code-review entry not yet
    # `[x]` -> the roster independently resolves steps-done-review-pending.
    track_body = (
        "<!-- t -->\n# Track 2\n\n## Progress\n"
        "- [x] 2026-06-20T00:00Z [ctx=info] Review + decomposition complete\n"
        "- [x] 2026-06-20T01:00Z [ctx=safe] Step 1 complete (commit abc123)\n"
        "- [ ] Track-level code review\n\n## Concrete Steps\n\n"
        "1. only step — risk: high  [x] commit: abc123\n"
    )
    expected = "steps-done-review-pending"
    # Ledger-path arm: the ledger carries the substate.
    ledger_path_arm = (
        f"[2026-06-20T12:00Z] [ctx=info] phase=C track=2 substate={expected}\n"
    )
    state_ledger = _ledger_substate_state(
        ledger_path_arm, track_num=2, track_body=track_body
    )
    # Fallback arm: SAME track-file roster, ledger STRIPPED of the substate token.
    fallback_arm = "[2026-06-20T12:00Z] [ctx=info] phase=C track=2\n"
    state_fallback = _ledger_substate_state(
        fallback_arm, track_num=2, track_body=track_body
    )
    assert state_ledger == state_fallback == {"phase": "C", "substate": expected}, (
        "the ledger path and the ledger-stripped fallback path must resolve to "
        f"the identical sub-state ({expected}); got ledger={state_ledger!r}, "
        f"fallback={state_fallback!r}"
    )


def test_wrapped_roster_step_counted_via_fallback() -> None:
    """Wrapped-roster regression (S5, YTDB-1134's literal criterion): a track whose
    single step description wraps onto a continuation line carrying the `risk:`
    tail and `[x]` status. The wrap-fixed `roster_scan` JOINS the column-0 `N. `
    line with its continuation line, reads the `[x]` status from the joined text,
    counts the step, and the fallback resolves steps-done-review-pending. Before
    the wrap fix the scan found no `risk:` on the column-0 line, skipped the entry
    WITHOUT counting it, and the empty roster resolved steps-partial — the bug.

    Exercised through the EMPTY-substate fallback (the ledger omits `substate`) so
    the roster is actually read; a ledger `substate` would bypass the roster and
    not test the wrap fix."""
    # The roster's single step wraps: the column-0 line has NO risk: tail; the
    # continuation line carries `— risk: low  [x]`. Decomposition + code review are
    # both done in Progress, so a correctly-counted all-done roster resolves
    # steps-done-review-pending. (Code-review entry omitted -> review pending.)
    track_body = (
        "<!-- t -->\n# Track 2\n\n## Progress\n"
        "- [x] 2026-06-20T00:00Z [ctx=info] Review + decomposition complete\n"
        "- [x] 2026-06-20T01:00Z [ctx=safe] Step 1 complete (commit abc123)\n"
        "- [ ] Track-level code review\n\n## Concrete Steps\n\n"
        "1. A very long step description that runs past the line width and\n"
        "   wraps onto this indented continuation line — risk: low  [x] commit: abc123\n"
    )
    ledger = "[2026-06-20T12:00Z] [ctx=info] phase=C track=2\n"
    state = _ledger_substate_state(ledger, track_num=2, track_body=track_body)
    assert state == {"phase": "C", "substate": "steps-done-review-pending"}, (
        "the wrap-fixed roster_scan must count the wrapped [x] step so the "
        f"fallback resolves steps-done-review-pending (not steps-partial), got "
        f"{state!r}"
    )


def test_wrapped_roster_two_adjacent_wrapped_steps_both_counted() -> None:
    """Wrapped-roster regression, the join terminator (S5): TWO ADJACENT wrapped
    steps. The join must stop at the next column-0 step line (matched by the glob
    `[0-9]*". "*`) so the two steps never merge into one — both are counted
    separately. Here step 1 wraps and is `[x]`, step 2 wraps and is `[ ]`; the
    correct read is two steps, one done and one todo -> steps-partial. If the two
    wrapped steps merged into one buffer, the single joined entry would read only
    step 2's trailing `[ ]` status and the count would be 1, not 2 — and the
    intervening `[x]` would be lost. This is the case the trailing `*` in the
    terminator glob fixes; `[0-9]*". "` (no trailing `*`) would never match a real
    next-step line and the two would wrongly merge.

    Exercised through the empty-substate fallback so the roster is read."""
    track_body = (
        "<!-- t -->\n# Track 2\n\n## Progress\n"
        "- [x] 2026-06-20T00:00Z [ctx=info] Review + decomposition complete\n"
        "- [x] 2026-06-20T01:00Z [ctx=safe] Step 1 complete (commit abc123)\n"
        "- [ ] Step implementation\n\n## Concrete Steps\n\n"
        "1. First long step whose description runs past the width and wraps\n"
        "   onto this continuation line — risk: medium  [x] commit: abc123\n"
        "2. Second long step whose description also runs past the width and\n"
        "   wraps onto its own continuation line — risk: medium  [ ]\n"
    )
    ledger = "[2026-06-20T12:00Z] [ctx=info] phase=C track=2\n"
    state = _ledger_substate_state(ledger, track_num=2, track_body=track_body)
    assert state == {"phase": "C", "substate": "steps-partial"}, (
        "two adjacent wrapped steps must be counted separately (one [x], one "
        f"[ ]) so the fallback resolves steps-partial, got {state!r}"
    )


def test_append_substate_rejects_space() -> None:
    """`--substate` append validation (S6): a space in the `substate` value is
    rejected (exit 3 + stderr diagnostic), the ledger is NOT written, and the
    diagnostic names the bare-token cause. `substate` is a bare metacharacter-free
    token, so it joins phase/track/design_gate/tracks/phase1_complete/
    reconciled_tag/s17/paused under the existing bare-token
    rejection — a space would let the reader split the value or spawn a spurious
    `key=value` token."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck(
            "--append-ledger", "--phase", "C", "--substate", "steps partial",
            cwd=fx.path,
        )
        assert proc.returncode == 3, (
            f"a space in substate must exit 3, got {proc.returncode}; "
            f"stderr: {proc.stderr!r}"
        )
        assert "bare token" in proc.stderr, (
            f"the reject must name the bare-token cause, got {proc.stderr!r}"
        )
        assert not fx.ledger_file.exists(), (
            "a rejected append must not create the ledger"
        )


def test_append_substate_rejects_newline() -> None:
    """`--substate` append validation (S6): a newline smuggled into the `substate`
    value is rejected (exit 3 + stderr diagnostic naming the newline cause) and the
    ledger is NOT written. Without the guard the newline would write a second
    physical line whose smuggled tail the last-value-wins reader would resolve,
    routing resume to the wrong state — the same hazard the other field guards
    cover."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck(
            "--append-ledger", "--phase", "C",
            "--substate", "steps-partial\nphase=Done",
            cwd=fx.path,
        )
        assert proc.returncode == 3, (
            f"a newline in substate must exit 3, got {proc.returncode}; "
            f"stderr: {proc.stderr!r}"
        )
        assert "newline" in proc.stderr, (
            f"the reject must name the newline cause, got {proc.stderr!r}"
        )
        assert not fx.ledger_file.exists(), (
            "a rejected append must not create the ledger"
        )


def test_append_substate_written_before_categories() -> None:
    """`--substate` append: a valid substate is written as a BARE token in the
    pre-`categories` block — `substate=<slug>` appears before the quoted
    `categories="…"` field on the line. This pins the emit-order invariant the
    track-scoped reader's first-match safety depends on (a substate written after
    categories could lose to a decoy inside the quoted span)."""
    import re

    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck(
            "--append-ledger",
            "--phase", "C", "--track", "2",
            "--substate", "steps-partial",
            "--categories", "Workflow machinery",
            cwd=fx.path,
        )
        assert proc.returncode == 0, (
            f"a valid substate append must exit 0, got {proc.returncode}; "
            f"stderr: {proc.stderr!r}"
        )
        line = _ledger_text(fx).splitlines()[0]
        # substate is a bare token and precedes the quoted categories field.
        assert re.search(r"\bsubstate=steps-partial\b", line), (
            f"the substate token must be written, got {line!r}"
        )
        sub_pos = line.index("substate=")
        cat_pos = line.index('categories="')
        assert sub_pos < cat_pos, (
            "substate must be emitted BEFORE the quoted categories field "
            f"(emit-order invariant), got {line!r}"
        )


# ---------------------------------------------------------------------------
# The four complexity-axis fields that replaced `tier` (D10): design_gate,
# tracks, phase1_complete, and the per-track reconciled_tag.
#
# `tier=` was dropped from the ledger schema and split into these four fields.
# `determine_state` does not yet CONSUME them (Track 2 wires the resume router
# and the Phase-C / Phase-4 readers); this step adds the fields, their
# append-time validation, and a track-scoped read for the per-track tag. So the
# read assertions below pin the SCHEMA contract — the exact on-disk grammar the
# append writes and the last-value-wins / track-scoped semantics the script's
# `ledger_tail_value` / `ledger_tail_value_for_track` header contract defines —
# via a Python reader that replicates those readers' first-`key=`-token scan
# byte-for-byte. Pinning the contract here (not through a not-yet-existing
# consumer) is what lets Track 2 build its readers against a frozen schema. The
# loud-reject and torn-append cases run the real script, since they need no
# consumer.
# ---------------------------------------------------------------------------


def _ledger_tail_value(ledger_text: str, key: str) -> Optional[str]:
    """Replicate the script's `ledger_tail_value` last-value-wins read: scan
    every line, take the FIRST ` <key>=` token on each line (anchored on a
    leading space so `tracks=` never matches inside `xtracks=`), strip a
    surrounding pair of double quotes, and keep the most recent line's value.
    Returns None when the key never appears. This mirrors the bash reader's
    semantics exactly so the test pins the same contract Track 2's readers will
    consume — including the bash reader's requirement of a *leading space*: the
    bash strip `${line#*" $key="}` operates on the bare line, so a key at column
    0 (no preceding space) is not matched. Every reachable ledger line begins
    with `[<ISO>] [ctx=...] `, so the first real ` key=` token is always
    space-preceded; the leading-space anchor below matches the bash reader on
    every reachable input and diverges only on the unreachable column-0 line."""
    import re

    found: Optional[str] = None
    for line in ledger_text.splitlines():
        # Anchor on a leading space, matching the bash `${line#*" $key="}` strip
        # (which requires a literal space before the key on the bare line), and
        # take the FIRST match (the bash strip removes up to the first space-
        # preceded occurrence). A column-0 key is not matched, mirroring the
        # bash reader, which cannot strip a key it never sees a leading space
        # before.
        m = re.search(rf" {re.escape(key)}=(.*)$", line)
        if not m:
            continue
        rest = m.group(1)
        if rest.startswith('"'):
            # Quoted value: everything between this `"` and the next `"`.
            value = rest[1:].split('"', 1)[0]
        else:
            # Bare token: everything up to the next space.
            value = rest.split(" ", 1)[0]
        found = value
    return found


def _ledger_tail_value_for_track(
    ledger_text: str, key: str, track: str
) -> Optional[str]:
    """Replicate the script's `ledger_tail_value_for_track`: the track-scoped
    last-value-wins read. Only lines whose first ` track=` token equals `track`
    contribute; among those, the most recent `key` value wins. A line carrying
    `key` but no matching `track=` is skipped (strict track-scoping), so a prior
    track's value cannot leak into the active track's resolution. Both the
    `track=` and `<key>=` matches anchor on a leading space, mirroring the bash
    reader's `${line#*" track="}` / `${line#*" $key="}` strips (which require a
    literal preceding space); every reachable ledger line is `[<ISO>] [ctx=...] `
    prefixed, so the first real token is always space-preceded."""
    import re

    found: Optional[str] = None
    for line in ledger_text.splitlines():
        tm = re.search(r" track=(\S*)", line)
        if not tm or tm.group(1) != track:
            continue
        km = re.search(rf" {re.escape(key)}=(.*)$", line)
        if not km:
            continue
        rest = km.group(1)
        if rest.startswith('"'):
            value = rest[1:].split('"', 1)[0]
        else:
            value = rest.split(" ", 1)[0]
        found = value
    return found


def test_new_fields_append_round_trip() -> None:
    """A single `--append-ledger` carrying all four new fields writes each as a
    bare `key=value` token, and a last-value-wins read returns each field's value.
    This pins the round-trip half of the schema contract: the fields are written
    in the grammar Track 2's readers expect, and the read resolves them."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck(
            "--append-ledger",
            "--phase", "C", "--track", "2",
            "--design-gate", "yes",
            "--tracks", "4",
            "--phase1-complete", "yes",
            "--reconciled-tag", "medium",
            cwd=fx.path,
        )
        assert proc.returncode == 0, (
            f"a four-field append must exit 0, got {proc.returncode}; "
            f"stderr: {proc.stderr!r}"
        )
        text = _ledger_text(fx)
        assert _ledger_tail_value(text, "design_gate") == "yes", text
        assert _ledger_tail_value(text, "tracks") == "4", text
        assert _ledger_tail_value(text, "phase1_complete") == "yes", text
        # reconciled_tag is per-track: read it track-scoped for track 2.
        assert _ledger_tail_value_for_track(text, "reconciled_tag", "2") == "medium", text


def test_new_fields_emitted_in_pre_categories_block() -> None:
    """All four new fields are emitted as bare tokens in the pre-`categories`
    block — each appears BEFORE the quoted `categories="…"` field on the line.
    This is the emit-order invariant the first-match token reader's safety rests
    on: a reader-consumed key emitted AFTER the quoted field could lose to a
    same-named decoy inside it."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck(
            "--append-ledger",
            "--phase", "C", "--track", "1",
            "--design-gate", "no",
            "--tracks", "1",
            "--phase1-complete", "yes",
            "--reconciled-tag", "low",
            "--categories", "Workflow machinery",
            cwd=fx.path,
        )
        assert proc.returncode == 0, (
            f"the append must exit 0, got {proc.returncode}; stderr: {proc.stderr!r}"
        )
        line = _ledger_text(fx).splitlines()[0]
        cat_pos = line.index('categories="')
        for field in ("design_gate=", "tracks=", "phase1_complete=", "reconciled_tag="):
            pos = line.index(field)
            assert pos < cat_pos, (
                f"{field!r} must be emitted BEFORE the quoted categories field "
                f"(emit-order invariant), got {line!r}"
            )


def test_new_fields_last_value_wins() -> None:
    """Last-value-wins per key for the new fields: a second append of a changed
    `design_gate` / `tracks` resolves the LATEST value. The first append seeds the
    fields; the second changes them; the read returns the second's values. This is
    the read half of the append-only / last-value-wins contract for the new
    fields."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        run_precheck(
            "--append-ledger", "--phase", "0",
            "--design-gate", "no", "--tracks", "1",
            cwd=fx.path,
        )
        run_precheck(
            "--append-ledger", "--phase", "C",
            "--design-gate", "yes", "--tracks", "3",
            cwd=fx.path,
        )
        text = _ledger_text(fx)
        assert _ledger_tail_value(text, "design_gate") == "yes", (
            f"the latest design_gate (yes) must win, got {text!r}"
        )
        assert _ledger_tail_value(text, "tracks") == "3", (
            f"the latest tracks (3) must win, got {text!r}"
        )


def test_reconciled_tag_track_scoped_no_leak() -> None:
    """The per-track `reconciled_tag` is read TRACK-SCOPED, so a completed prior
    track's tag cannot resolve as a later track's value when the two sit on
    different ledger lines. Here track 1 reconciled `high` on an earlier line and
    track 2 reconciled `low` on a later line; the track-scoped read for track 2
    must return `low`, never track 1's `high` (which a global last-value-wins read
    over the wrong scope could wrongly pick up)."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        run_precheck(
            "--append-ledger", "--phase", "C", "--track", "1",
            "--reconciled-tag", "high",
            cwd=fx.path,
        )
        run_precheck(
            "--append-ledger", "--phase", "C", "--track", "2",
            "--reconciled-tag", "low",
            cwd=fx.path,
        )
        text = _ledger_text(fx)
        assert _ledger_tail_value_for_track(text, "reconciled_tag", "2") == "low", (
            f"track 2's reconciled_tag (low) must resolve track-scoped, got {text!r}"
        )
        assert _ledger_tail_value_for_track(text, "reconciled_tag", "1") == "high", (
            f"track 1's reconciled_tag (high) must stay track-scoped to track 1, "
            f"got {text!r}"
        )


def test_new_field_first_match_wins_over_categories_decoy() -> None:
    """First-match-wins / same-named-decoy invariant for a new field: a ledger line
    whose quoted `categories="…"` value embeds a `design_gate=`-shaped decoy still
    reads the REAL bare `design_gate=` token that precedes the quoted field. The
    bare token is emitted in the pre-`categories` block, and the first-`key=` token
    scan stops at it, so the decoy inside the quoted span can never win. This pins
    the load-bearing ordering the step calls out: a bare reader-consumed field
    placed AFTER `categories` would let such a decoy win."""
    # The bare design_gate=no precedes the quoted categories that embeds a decoy
    # design_gate=yes. The first-match reader must resolve the bare `no`.
    ledger = (
        '[2026-06-20T12:00Z] [ctx=info] phase=C track=1 design_gate=no tracks=1 '
        'categories="Workflow machinery design_gate=yes tracks=99"\n'
    )
    assert _ledger_tail_value(ledger, "design_gate") == "no", (
        "the real bare design_gate=no before the quoted categories must win over "
        f"the decoy embedded inside it, got {_ledger_tail_value(ledger, 'design_gate')!r}"
    )
    assert _ledger_tail_value(ledger, "tracks") == "1", (
        "the real bare tracks=1 before the quoted categories must win over the "
        f"decoy tracks=99 inside it, got {_ledger_tail_value(ledger, 'tracks')!r}"
    )


def test_new_field_first_match_decoy_is_real_on_disk_emit() -> None:
    """The decoy guard verified end-to-end against the SCRIPT's emit (not just a
    hand-authored line): an append carrying both the real `design_gate` and a
    `categories` value that embeds a `design_gate=`-shaped decoy writes the bare
    field BEFORE the quoted one, so the first-match reader over the script's own
    output resolves the real bare token. This proves the emit order the script
    produces actually satisfies the first-match invariant, closing the loop on the
    hand-authored decoy test above."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck(
            "--append-ledger",
            "--phase", "C", "--track", "1",
            "--design-gate", "no",
            "--categories", "Workflow machinery design_gate=yes",
            cwd=fx.path,
        )
        assert proc.returncode == 0, (
            f"the decoy-bearing append must exit 0, got {proc.returncode}; "
            f"stderr: {proc.stderr!r}"
        )
        text = _ledger_text(fx)
        assert _ledger_tail_value(text, "design_gate") == "no", (
            "the script's emit must place the bare design_gate before the quoted "
            f"categories so the first-match reader resolves it, got {text!r}"
        )


def test_append_new_field_rejects_space() -> None:
    """Append validation for the new bare fields: a space in `design_gate` is
    rejected (exit 3 + a bare-token diagnostic) and the ledger is NOT written. The
    new fields join phase/track/substate/s17/paused under the existing bare-token
    rejection, so a space — which the reader would split on — fails loudly."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck(
            "--append-ledger", "--phase", "C", "--design-gate", "ye s",
            cwd=fx.path,
        )
        assert proc.returncode == 3, (
            f"a space in design_gate must exit 3, got {proc.returncode}; "
            f"stderr: {proc.stderr!r}"
        )
        assert "bare token" in proc.stderr, (
            f"the reject must name the bare-token cause, got {proc.stderr!r}"
        )
        assert not fx.ledger_file.exists(), (
            "a rejected append must not create the ledger"
        )


def test_append_new_field_rejects_newline() -> None:
    """Append validation for the new bare fields: a newline smuggled into
    `reconciled_tag` is rejected (exit 3 + a newline diagnostic) and the ledger is
    NOT written. Without the guard the newline would write a second physical line
    whose smuggled tail the last-value-wins reader would resolve, routing resume to
    the wrong state — the same hazard the other field guards cover."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        proc = run_precheck(
            "--append-ledger", "--phase", "C", "--track", "1",
            "--reconciled-tag", "high\nphase=Done",
            cwd=fx.path,
        )
        assert proc.returncode == 3, (
            f"a newline in reconciled_tag must exit 3, got {proc.returncode}; "
            f"stderr: {proc.stderr!r}"
        )
        assert "newline" in proc.stderr, (
            f"the reject must name the newline cause, got {proc.stderr!r}"
        )
        assert not fx.ledger_file.exists(), (
            "a rejected append must not create the ledger"
        )


def test_torn_append_leaves_prior_tail_intact_new_fields() -> None:
    """Torn-append safety for the new schema: a stray `.phase-ledger.<pid>.tmp`
    holding a half-written line carrying the new fields, beside a complete prior
    ledger that also carries the new fields, must leave the prior tail
    authoritative — the read resolves the prior `design_gate`/`tracks`, never the
    torn temp's partial values. Mirrors the existing torn-append test but with the
    post-`tier=`-removal fields on both the prior and the torn line."""
    with GitFixture() as fx:
        fx.commit("init")
        fx.add_bare_remote()
        prior = fx.write_ledger(
            "[2026-06-15T10:00Z] [ctx=safe] phase=A design_gate=no tracks=1\n"
        )
        prior_bytes = prior.read_bytes()
        torn = prior.parent / ".phase-ledger.99999.tmp"
        torn.write_text(
            "[2026-06-15T10:00Z] [ctx=safe] phase=A design_gate=no tracks=1\n"
            "[2026-06-15T11:00Z] [ctx=info] phase=C design_gate=yes trac",  # torn
            encoding="utf-8",
        )
        # The committed ledger (never the temp) is what the read resolves.
        text = prior.read_text(encoding="utf-8")
        assert _ledger_tail_value(text, "design_gate") == "no", (
            "a torn append must leave the prior design_gate=no authoritative, "
            f"got {text!r}"
        )
        assert _ledger_tail_value(text, "tracks") == "1", (
            f"the prior tracks=1 must stay authoritative, got {text!r}"
        )
        assert prior.read_bytes() == prior_bytes, (
            "the prior ledger bytes must be untouched by a torn append"
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
    ("migrate_range_exclude_sha_clears_merge_base_failure", test_migrate_range_exclude_sha_clears_merge_base_failure),
    ("divergence_clean_in_sync", test_divergence_clean_in_sync),
    ("divergence_detected_both_nonzero", test_divergence_detected_both_nonzero),
    ("divergence_no_upstream_skips", test_divergence_no_upstream_skips),
    ("divergence_fetch_failed_skips", test_divergence_fetch_failed_skips),
    ("divergence_object_present_in_full_mode", test_divergence_object_present_in_full_mode),
    ("drift_all_stamped_classifies_stamped_then_folds", test_drift_all_stamped_classifies_stamped_then_folds),
    ("drift_unstamped_detects_drift_kind_unstamped", test_drift_unstamped_detects_drift_kind_unstamped),
    ("drift_empty_input_silent_no_drift_kind_null", test_drift_empty_input_silent_no_drift_kind_null),
    ("drift_phase2_detected_reports_range", test_drift_phase2_detected_reports_range),
    ("drift_phase4_stamped_folds_to_no_drift", test_drift_phase4_stamped_folds_to_no_drift),
    ("drift_phase4_empty_input_returns_kind_null_before_skip2", test_drift_phase4_empty_input_returns_kind_null_before_skip2),
    ("drift_phase2_empty_range_no_drift_count_zero", test_drift_phase2_empty_range_no_drift_count_zero),
    ("drift_phase2_merge_base_failed_kind_scalars_null", test_drift_phase2_merge_base_failed_kind_scalars_null),
    ("drift_phase2_staged_subtree_excluded_from_range", test_drift_phase2_staged_subtree_excluded_from_range),
    ("drift_phase2_real_workflow_commit_vs_staged_distinguished", test_drift_phase2_real_workflow_commit_vs_staged_distinguished),
    ("drift_phase2_agents_commit_detected", test_drift_phase2_agents_commit_detected),
    ("norm_success_one_commit_line1_only_diff_exit0", test_norm_success_one_commit_line1_only_diff_exit0),
    ("norm_uniform_stamps_skip_no_commit", test_norm_uniform_stamps_skip_no_commit),
    ("norm_success_reports_commit_in_actions_taken", test_norm_success_reports_commit_in_actions_taken),
    ("norm_guard1_off_line1_body_edit_aborts", test_norm_guard1_off_line1_body_edit_aborts),
    ("norm_guard2_dirty_workflow_file_aborts", test_norm_guard2_dirty_workflow_file_aborts),
    ("norm_narrow_scope_dirty_outside_does_not_abort", test_norm_narrow_scope_dirty_outside_does_not_abort),
    ("norm_reduced_modes_never_mutate", test_norm_reduced_modes_never_mutate),
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
    ("conformance_normalization_recompute_walk_enumerates_canonical_globs", test_conformance_normalization_recompute_walk_enumerates_canonical_globs),
    # -- phase ledger: append primitive + determine_state ledger path ----------
    ("append_ledger_writes_pinned_grammar", test_append_ledger_writes_pinned_grammar),
    ("append_ledger_omits_empty_fields_and_defaults_ctx", test_append_ledger_omits_empty_fields_and_defaults_ctx),
    ("append_ledger_appends_does_not_overwrite", test_append_ledger_appends_does_not_overwrite),
    ("append_ledger_last_value_wins_on_read", test_append_ledger_last_value_wins_on_read),
    ("torn_append_leaves_prior_tail_intact", test_torn_append_leaves_prior_tail_intact),
    ("ledger_path_state_0", test_ledger_path_state_0),
    ("ledger_path_state_A_no_track_file", test_ledger_path_state_A_no_track_file),
    ("ledger_path_state_C_resolves_substate_from_track_file", test_ledger_path_state_C_resolves_substate_from_track_file),
    ("ledger_path_state_C_track_missing_is_state_a", test_ledger_path_state_C_track_missing_is_state_a),
    ("ledger_path_state_D", test_ledger_path_state_D),
    ("ledger_path_state_done", test_ledger_path_state_done),
    ("ledger_no_plan_minimal_resume_defaults_track_1", test_ledger_no_plan_minimal_resume_defaults_track_1),
    ("ledger_absent_falls_back_to_plan_checkbox_walk", test_ledger_absent_falls_back_to_plan_checkbox_walk),
    ("ledger_unrecognized_phase_is_parse_error", test_ledger_unrecognized_phase_is_parse_error),
    ("ledger_categories_value_with_spaces_reads_back_whole", test_ledger_categories_value_with_spaces_reads_back_whole),
    ("ledger_excluded_from_drift_walk_by_omission", test_ledger_excluded_from_drift_walk_by_omission),
    ("ledger_anchor_track_file_keeps_drift_clean_when_plan_absent", test_ledger_anchor_track_file_keeps_drift_clean_when_plan_absent),
    ("append_ledger_mutually_exclusive_with_mode", test_append_ledger_mutually_exclusive_with_mode),
    # -- append-path hardening: value validation, failure surfacing, temp cleanup
    ("append_ledger_rejects_newline_in_field", test_append_ledger_rejects_newline_in_field),
    ("append_ledger_rejects_double_quote_in_categories", test_append_ledger_rejects_double_quote_in_categories),
    ("append_ledger_rejects_space_in_bare_field", test_append_ledger_rejects_space_in_bare_field),
    ("append_ledger_allows_spaces_in_categories", test_append_ledger_allows_spaces_in_categories),
    ("append_ledger_surfaces_write_failure_nonzero", test_append_ledger_surfaces_write_failure_nonzero),
    ("append_ledger_no_orphan_temp_on_success", test_append_ledger_no_orphan_temp_on_success),
    ("append_ledger_no_orphan_temp_on_failure", test_append_ledger_no_orphan_temp_on_failure),
    # -- doc-presence guard: the A->C ledger append call is present in step 6 ---
    ("track_review_step6_carries_ac_ledger_append", test_track_review_step6_carries_ac_ledger_append),
    # -- ledger-first sub-state read: the `substate` key (D1) -------------------
    ("ledger_substate_four_committed_slugs_resolve", test_ledger_substate_four_committed_slugs_resolve),
    ("ledger_substate_resolves_without_track_file", test_ledger_substate_resolves_without_track_file),
    ("ledger_substate_track_scoped_not_global_last", test_ledger_substate_track_scoped_not_global_last),
    ("ledger_substate_track_scoped_keeps_last_for_track", test_ledger_substate_track_scoped_keeps_last_for_track),
    ("ledger_substate_categories_decoy_does_not_win", test_ledger_substate_categories_decoy_does_not_win),
    ("ledger_empty_substate_falls_through_to_roster", test_ledger_empty_substate_falls_through_to_roster),
    ("ledger_dual_path_parity_same_substate", test_ledger_dual_path_parity_same_substate),
    ("wrapped_roster_step_counted_via_fallback", test_wrapped_roster_step_counted_via_fallback),
    ("wrapped_roster_two_adjacent_wrapped_steps_both_counted", test_wrapped_roster_two_adjacent_wrapped_steps_both_counted),
    ("append_substate_rejects_space", test_append_substate_rejects_space),
    ("append_substate_rejects_newline", test_append_substate_rejects_newline),
    ("append_substate_written_before_categories", test_append_substate_written_before_categories),
    # -- the four complexity-axis fields that replaced tier (D10) ---------------
    ("new_fields_append_round_trip", test_new_fields_append_round_trip),
    ("new_fields_emitted_in_pre_categories_block", test_new_fields_emitted_in_pre_categories_block),
    ("new_fields_last_value_wins", test_new_fields_last_value_wins),
    ("reconciled_tag_track_scoped_no_leak", test_reconciled_tag_track_scoped_no_leak),
    ("new_field_first_match_wins_over_categories_decoy", test_new_field_first_match_wins_over_categories_decoy),
    ("new_field_first_match_decoy_is_real_on_disk_emit", test_new_field_first_match_decoy_is_real_on_disk_emit),
    ("append_new_field_rejects_space", test_append_new_field_rejects_space),
    ("append_new_field_rejects_newline", test_append_new_field_rejects_newline),
    ("torn_append_leaves_prior_tail_intact_new_fields", test_torn_append_leaves_prior_tail_intact_new_fields),
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
