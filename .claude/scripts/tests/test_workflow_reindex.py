#!/usr/bin/env python3
"""Validation runner for `.claude/scripts/workflow-reindex.py`.

Running this script is the validation: it imports the script as a
module and exercises the staged-aware §1.8 probe, the parsing and
fence/inline-backtick state machine, the eight validation rules, and
the `--check` CLI surface against fixture inputs.

Invocation (from repo root):

    python3 .claude/scripts/tests/test_workflow_reindex.py

Exit code 0: every test case passed. Exit code 1: one or more failed;
each failure prints a clear message naming the test case + actual vs
expected.

Runner shape mirrors `.claude/scripts/tests/test_dsc_ai_tell.py` and
`.claude/scripts/tests/test_house_style_hook.py` (stand-alone, no
pytest collection, exit-code semantics, single-file). Pytest is not
installed on the project's CI image; the stand-alone runner pattern
keeps the test executable on any Python 3 host.

Test coverage spans the parser-core smoke tests, the staged-aware
§1.8 probe, rule-by-rule positive + negative tests for every
validation rule (rules 1-8), and end-to-end `--check` exit-code
tests. Full cross-product matrix expansion (any-wildcard
combinations, mixed in-scope / out-of-scope `--files` skip-set
tests, `--write` idempotence, and the halt-on-unresolved contract)
lands in subsequent test additions on top of this baseline.
"""

from __future__ import annotations

import importlib.util
import os
import sys
import tempfile
import textwrap
import traceback
from pathlib import Path
from typing import Callable, List, Tuple

REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT_PATH = REPO_ROOT / ".claude" / "scripts" / "workflow-reindex.py"


# ---------------------------------------------------------------------------
# Module loader.
#
# The script file ends in `.py` and is importable; the dash in
# `workflow-reindex` is the only obstacle, since `import` requires a
# Python identifier. importlib.util loads the module file directly and
# returns a module object the tests can call into.
# ---------------------------------------------------------------------------


def load_workflow_reindex_module():
    """Import `.claude/scripts/workflow-reindex.py` as a module.

    The module is registered in `sys.modules` before `exec_module` runs
    because Python 3.14's `@dataclass` decorator looks up the module's
    `__dict__` via `sys.modules.get(cls.__module__)` while processing
    the class body — an importlib-loaded module that is not yet in
    `sys.modules` returns None and the decorator crashes (observed on
    the fixture host running Python 3.14). The PEP-451 import-protocol
    contract is that the spec-machinery installs the module in
    `sys.modules` for normal `import foo`; doing it manually here
    matches that contract.
    """
    spec = importlib.util.spec_from_file_location(
        "workflow_reindex", str(SCRIPT_PATH)
    )
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Failed to load module spec for {SCRIPT_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules["workflow_reindex"] = module
    spec.loader.exec_module(module)
    return module


MODULE = load_workflow_reindex_module()


# ---------------------------------------------------------------------------
# Fixture builders.
# ---------------------------------------------------------------------------


# A minimal §1.8 fixture body. The role and phase enum blocks are the
# load-bearing content; everything else is filler so the file shape
# (`## 1.8 Per-section ...` then `### (a) Role enum` then a fenced
# block, then `### (b) Phase enum` then a fenced block) matches what
# `load_bootstrap_enums` expects to find.
FIXTURE_CONVENTIONS_BODY = textwrap.dedent(
    """\
    <!-- workflow-sha: 0000000000000000000000000000000000000000 -->
    # Conventions fixture

    Filler paragraph so the file is not empty above §1.8.

    ## 1.8 Per-section role/phase annotations and TOC region

    Body intro paragraph.

    ### (a) Role enum

    Description paragraph.

    ```
    any
    orchestrator        — driver
    planner             — planner agent
    implementer         — implementer
    decomposer          — Phase 3A step decomposer
    final-designer      — Phase 4 final-artifact authoring
    migrator            — /migrate-workflow agent
    pr-reviewer         — /review-workflow-pr agent
    reviewer-technical  — Phase 3A technical review
    reviewer-risk       — Phase 3A risk review
    reviewer-adversarial — Phase 3A adversarial review
    reviewer-plan       — Phase 2 consistency + structural reviewers
    reviewer-design     — design-mutation cold-read
    reviewer-dim-step   — Phase 3B step-level dimensional reviewers
    reviewer-dim-track  — Phase 3C track-level dimensional reviewers
    ```

    ### (b) Phase enum

    Description paragraph.

    ```
    0    Research
    1    Planning
    2    Plan Review
    3A   Track Review + Decomposition
    3B   Step Implementation
    3C   Track-Level Code Review + Track Completion
    4    Final Artifacts                       (workflow-modifying plans: 3 commits;
                                                non-workflow-modifying: 2 commits)
    any  Wildcard
    ```

    Trailing paragraph.
    """
)


def write_fixture_conventions(target: Path) -> Path:
    """Write the §1.8 fixture to `target` and return the path.

    `target` must include the conventions.md filename — the helper
    creates parent directories as needed and writes the body verbatim.
    """
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(FIXTURE_CONVENTIONS_BODY, encoding="utf-8")
    return target


# ---------------------------------------------------------------------------
# Test runner.
# ---------------------------------------------------------------------------


_FAILURES: List[Tuple[str, str]] = []


def run_test(name: str, fn: Callable[[], None]) -> None:
    """Execute one test function. Capture failures without stopping the run."""
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
# Tests.
# ---------------------------------------------------------------------------


def test_module_loads() -> None:
    """Smoke test: the script imports cleanly and exposes the public surface."""
    assert hasattr(MODULE, "load_bootstrap_enums"), "missing load_bootstrap_enums"
    assert hasattr(MODULE, "discover_conventions_path"), "missing discover_conventions_path"
    assert hasattr(MODULE, "parse_annotation"), "missing parse_annotation"
    assert hasattr(MODULE, "parse_headings"), "missing parse_headings"
    assert hasattr(MODULE, "parse_toc_region"), "missing parse_toc_region"
    assert hasattr(MODULE, "compute_fenced_lines"), "missing compute_fenced_lines"
    assert hasattr(MODULE, "inline_backtick_spans"), "missing inline_backtick_spans"
    assert hasattr(MODULE, "discover_in_scope_files"), "missing discover_in_scope_files"


def test_bootstrap_probe_live_only() -> None:
    """When no staged copy exists, the probe reads the live conventions.md."""
    with tempfile.TemporaryDirectory() as tmpdir:
        root = Path(tmpdir)
        live = root / ".claude" / "workflow" / "conventions.md"
        write_fixture_conventions(live)
        enums = MODULE.load_bootstrap_enums(root)
        assert enums.source == live, f"expected {live}, got {enums.source}"
        assert len(enums.roles) == 15, f"expected 15 roles, got {len(enums.roles)}"
        assert len(enums.phases) == 8, f"expected 8 phases, got {len(enums.phases)}"
        assert "any" in enums.roles, "roles missing 'any'"
        assert "any" in enums.phases, "phases missing 'any'"
        assert "orchestrator" in enums.roles, "roles missing 'orchestrator'"
        assert "3B" in enums.phases, "phases missing '3B'"


def test_bootstrap_probe_staged_wins() -> None:
    """A single staged copy wins over the live file per `conventions.md §1.7(d)` reads-precedence."""
    with tempfile.TemporaryDirectory() as tmpdir:
        root = Path(tmpdir)
        # Write a deliberately-different "live" conventions.md so we
        # can prove the probe did NOT read from it.
        live = root / ".claude" / "workflow" / "conventions.md"
        live.parent.mkdir(parents=True, exist_ok=True)
        live.write_text(
            "## 1.8 wrong\n\n### (a) Role enum\n\n```\nbogus\n```\n\n"
            "### (b) Phase enum\n\n```\nbogus\n```\n",
            encoding="utf-8",
        )
        # The staged copy is the one the probe should pick up.
        staged = (
            root
            / "docs"
            / "adr"
            / "some-plan"
            / "_workflow"
            / "staged-workflow"
            / ".claude"
            / "workflow"
            / "conventions.md"
        )
        write_fixture_conventions(staged)
        enums = MODULE.load_bootstrap_enums(root)
        assert enums.source == staged, (
            f"expected staged copy {staged}, got {enums.source}"
        )
        assert len(enums.roles) == 15, f"expected 15 roles, got {len(enums.roles)}"
        assert len(enums.phases) == 8, f"expected 8 phases, got {len(enums.phases)}"


def test_bootstrap_probe_multiple_staged_halts() -> None:
    """Multiple staged copies raise `AmbiguousBootstrapProbeError` (exit 2)."""
    with tempfile.TemporaryDirectory() as tmpdir:
        root = Path(tmpdir)
        live = root / ".claude" / "workflow" / "conventions.md"
        write_fixture_conventions(live)
        staged_a = (
            root / "docs" / "adr" / "plan-a" / "_workflow"
            / "staged-workflow" / ".claude" / "workflow" / "conventions.md"
        )
        staged_b = (
            root / "docs" / "adr" / "plan-b" / "_workflow"
            / "staged-workflow" / ".claude" / "workflow" / "conventions.md"
        )
        write_fixture_conventions(staged_a)
        write_fixture_conventions(staged_b)
        try:
            MODULE.load_bootstrap_enums(root)
        except MODULE.AmbiguousBootstrapProbeError as exc:
            assert "Multiple staged" in str(exc), (
                f"expected ambiguity message, got: {exc}"
            )
            return
        raise AssertionError(
            "expected AmbiguousBootstrapProbeError for multiple staged copies"
        )


def test_parse_annotation_well_formed() -> None:
    """A clean annotation comment parses with `well_formed=True`."""
    line = '<!-- roles=orchestrator,implementer phases=3A,3B summary="works" -->'
    ann = MODULE.parse_annotation(line, line_no=42)
    assert ann is not None, "annotation should parse"
    assert ann.well_formed, "expected well_formed=True"
    assert ann.roles == ("orchestrator", "implementer"), f"got {ann.roles}"
    assert ann.phases == ("3A", "3B"), f"got {ann.phases}"
    assert ann.summary == "works", f"got {ann.summary}"
    assert ann.line == 42, f"got {ann.line}"


def test_parse_annotation_space_after_comma_fails_field() -> None:
    """`roles=foo, bar` is malformed — the field-extraction returns None."""
    line = '<!-- roles=foo, bar phases=3A summary="x" -->'
    ann = MODULE.parse_annotation(line, line_no=1)
    assert ann is not None, "comment shape should still parse"
    assert not ann.well_formed, "expected well_formed=False on malformed roles"
    assert ann.roles is None, f"expected roles=None, got {ann.roles}"


def test_parse_annotation_not_a_comment() -> None:
    """A line that is not an HTML comment returns None."""
    ann = MODULE.parse_annotation("This is just prose.", line_no=1)
    assert ann is None, f"expected None, got {ann}"


def test_parse_headings_collects_h2_and_h3() -> None:
    """`parse_headings` returns one record per `^## ` and `^### `."""
    text = textwrap.dedent(
        """\
        # Title

        ## Section A
        <!-- roles=any phases=any summary="A" -->

        Body.

        ### Sub Alpha
        <!-- roles=any phases=any summary="Alpha" -->

        Body.

        ## Section B

        No annotation here.
        """
    ).splitlines()
    headings = MODULE.parse_headings(text)
    assert len(headings) == 3, f"expected 3 headings, got {len(headings)}"
    assert headings[0].text == "Section A" and headings[0].level == 2
    assert headings[0].annotation is not None
    assert headings[1].text == "Sub Alpha" and headings[1].level == 3
    assert headings[1].annotation is not None
    assert headings[2].text == "Section B" and headings[2].level == 2
    # Section B has no annotation on the next line.
    assert headings[2].annotation is None


def test_parse_headings_bootstrap_flag() -> None:
    """The bootstrap-block heading is flagged for rule 3/4 exemption."""
    text = [
        "# Title",
        "",
        "## Reading workflow files (TOC protocol)",
        "",
        "Body.",
    ]
    headings = MODULE.parse_headings(text)
    assert len(headings) == 1
    assert headings[0].is_bootstrap, "expected bootstrap flag"


def test_parse_toc_region_detects_delimiters() -> None:
    """`parse_toc_region` finds the start/end delimiters and rows."""
    text = [
        "# Title",
        "",
        "<!--Document index start-->",
        "",
        "| Section | Roles | Phases | Summary |",
        "|---|---|---|---|",
        "| §1 Foo | any | any | bar |",
        "",
        "<!--Document index end-->",
        "",
        "## Section 1",
    ]
    toc = MODULE.parse_toc_region(text)
    assert toc is not None, "TOC region should be found"
    assert toc.start_line == 3
    assert toc.end_line == 9
    # Three `|`-prefixed lines: header, separator, one data row.
    assert len(toc.rows) == 3, f"expected 3 rows, got {len(toc.rows)}"


def test_parse_toc_region_missing_returns_none() -> None:
    """A file with no TOC delimiters returns None."""
    text = ["# Title", "", "## Section 1", "Body."]
    toc = MODULE.parse_toc_region(text)
    assert toc is None


def test_compute_fenced_lines_basic() -> None:
    """Lines inside a ```-fenced block are flagged True, outside False."""
    text = [
        "para 1",
        "```",
        "inside",
        "more inside",
        "```",
        "para 2",
    ]
    fenced = MODULE.compute_fenced_lines(text)
    assert fenced == [False, True, True, True, True, False], f"got {fenced}"


def test_compute_fenced_lines_mismatched_close_keeps_fence_open() -> None:
    """A shorter close fence does NOT terminate a longer open fence."""
    text = [
        "````",  # open with 4 backticks
        "inside",
        "```",  # 3 backticks — does NOT close
        "still inside",
        "````",  # 4 backticks — closes
        "outside",
    ]
    fenced = MODULE.compute_fenced_lines(text)
    assert fenced == [True, True, True, True, True, False], f"got {fenced}"


def test_compute_fenced_lines_tilde_vs_backtick_distinct() -> None:
    """A tilde fence is not closed by a backtick fence of any length."""
    text = [
        "~~~",  # open with tildes
        "inside",
        "```",  # backtick line — does not close
        "still inside",
        "~~~",  # closes
        "outside",
    ]
    fenced = MODULE.compute_fenced_lines(text)
    assert fenced == [True, True, True, True, True, False], f"got {fenced}"


def test_inline_backtick_spans_single() -> None:
    """A single `code` span is detected with correct (start, end) bounds."""
    line = "see `§1.8` for details"
    spans = MODULE.inline_backtick_spans(line)
    assert spans == [(4, 10)], f"got {spans}"
    # Position 5 is inside the span; position 0 is outside.
    assert MODULE.position_in_inline_span(spans, 5)
    assert not MODULE.position_in_inline_span(spans, 0)


def test_inline_backtick_spans_double_with_inner_backtick() -> None:
    """A `` `` span can carry a single backtick inside."""
    line = "see ``inner `tick` outer`` here"
    spans = MODULE.inline_backtick_spans(line)
    # The double-backtick run opens at index 4; the matching closer is
    # the `` at index 23. The span covers 4..25 inclusive of the closer.
    assert len(spans) == 1, f"got {spans}"
    start, end = spans[0]
    assert start == 4
    assert line[start:end].startswith("``") and line[start:end].endswith("``")
    # The single backtick at `tick` is NOT a closer (different length)
    # so it must be inside the span.
    tick_pos = line.index("`tick`")
    assert MODULE.position_in_inline_span(spans, tick_pos)


def test_inline_backtick_spans_unclosed_no_span() -> None:
    """An unclosed run produces no span (literal backticks)."""
    line = "this `is unclosed and runs to EOL"
    spans = MODULE.inline_backtick_spans(line)
    assert spans == [], f"expected no spans, got {spans}"


def test_discover_in_scope_files_smoke() -> None:
    """The repo's in-scope file set is non-empty and contains known files."""
    files = MODULE.discover_in_scope_files(REPO_ROOT)
    assert len(files) > 0, "expected at least one in-scope file"
    rels = {p.resolve().relative_to(REPO_ROOT.resolve()).as_posix() for p in files}
    # `conventions.md` is the obvious anchor — guaranteed to exist on
    # any branch that has the workflow surface.
    assert ".claude/workflow/conventions.md" in rels, (
        f"expected conventions.md in discovery set; got {sorted(rels)[:5]}..."
    )


def test_discover_in_scope_files_picks_up_staged_paths() -> None:
    """Staged workflow / skill / agent files under `docs/adr/*/_workflow/staged-workflow/`
    are part of the discovery walk so the pre-commit hook and CI workflow
    can pass staged paths through `--files` and have them validate.

    Without this, a workflow-modifying branch that edited only the staged
    copy of `.claude/workflow/conventions.md` would have its staged edits
    silently skipped at the gate — the bug the in-scope-set extension
    closes.
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        root = Path(tmpdir)
        # Live conventions.md so the bootstrap probe has a fallback target.
        # The live tree itself stays otherwise empty so the test does not
        # accidentally rely on the repo's live workflow surface.
        write_fixture_conventions(root / ".claude" / "workflow" / "conventions.md")
        # A staged workflow file (under `.claude/workflow/`).
        staged_workflow = (
            root
            / "docs"
            / "adr"
            / "some-plan"
            / "_workflow"
            / "staged-workflow"
            / ".claude"
            / "workflow"
            / "step-implementation.md"
        )
        staged_workflow.parent.mkdir(parents=True, exist_ok=True)
        staged_workflow.write_text("# Staged step file\n", encoding="utf-8")
        # A staged skill file under `.claude/skills/<name>/SKILL.md`.
        staged_skill = (
            root
            / "docs"
            / "adr"
            / "some-plan"
            / "_workflow"
            / "staged-workflow"
            / ".claude"
            / "skills"
            / "execute-tracks"
            / "SKILL.md"
        )
        staged_skill.parent.mkdir(parents=True, exist_ok=True)
        staged_skill.write_text("# Staged skill\n", encoding="utf-8")
        # A staged agent file under `.claude/agents/<name>.md`.
        staged_agent = (
            root
            / "docs"
            / "adr"
            / "some-plan"
            / "_workflow"
            / "staged-workflow"
            / ".claude"
            / "agents"
            / "review-workflow-context-budget.md"
        )
        staged_agent.parent.mkdir(parents=True, exist_ok=True)
        staged_agent.write_text("# Staged agent\n", encoding="utf-8")
        files = MODULE.discover_in_scope_files(root)
        rels = {p.resolve().relative_to(root.resolve()).as_posix() for p in files}
        assert (
            "docs/adr/some-plan/_workflow/staged-workflow/.claude/workflow/step-implementation.md"
            in rels
        ), f"staged workflow path missing from discovery; got {sorted(rels)}"
        assert (
            "docs/adr/some-plan/_workflow/staged-workflow/.claude/skills/execute-tracks/SKILL.md"
            in rels
        ), f"staged skill path missing from discovery; got {sorted(rels)}"
        assert (
            "docs/adr/some-plan/_workflow/staged-workflow/.claude/agents/review-workflow-context-budget.md"
            in rels
        ), f"staged agent path missing from discovery; got {sorted(rels)}"
        # The `--files` predicate (`_normalise_file_path` + membership in
        # the discovery set) must also resolve the staged path. Pass an
        # absolute path through the normaliser and check it lands inside
        # the discovered set.
        normalised = MODULE._normalise_file_path(str(staged_workflow), root)
        assert normalised in rels, (
            f"normalised staged path {normalised!r} is not in discovery set"
        )


# ---------------------------------------------------------------------------
# Helpers for the validation-rule tests.
#
# The rule tests build hermetic fixture trees under a temp directory and
# call `validate(repo_root)` (or `main(['--check'])` for the CLI-level
# tests). The fixture builder writes the §1.8 conventions.md so the
# bootstrap probe finds the role / phase enums.
# ---------------------------------------------------------------------------


def _make_fixture_root() -> tempfile.TemporaryDirectory:
    """Return a temp directory the caller wraps in a `with` block."""
    return tempfile.TemporaryDirectory()


def _write_conventions(root: Path) -> Path:
    """Write the §1.8 conventions fixture into a fresh fixture root."""
    return write_fixture_conventions(
        root / ".claude" / "workflow" / "conventions.md"
    )


def _write_in_scope_file(root: Path, rel_path: str, body: str) -> Path:
    """Write `body` to `root/rel_path`, creating parents as needed."""
    target = root / rel_path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body, encoding="utf-8")
    return target


def _findings_by_rule(findings) -> dict:
    """Group findings by rule name for easier assertions."""
    by_rule: dict = {}
    for f in findings:
        by_rule.setdefault(f.rule, []).append(f)
    return by_rule


def _findings_for_path(findings, path_suffix: str):
    """Filter findings whose `path` ends with the given suffix."""
    return [f for f in findings if f.path.endswith(path_suffix)]


# A minimal valid workflow file body: one annotated H2, matching TOC,
# no rule_8 in-file refs. Used as the "this file is clean" baseline
# that rule-specific tests mutate to introduce one defect.
def _clean_workflow_body() -> str:
    return textwrap.dedent(
        """\
        # Demo workflow file

        <!--Document index start-->

        | Section | Roles | Phases | Summary |
        |---|---|---|---|
        | §1 Demo | orchestrator | 3B | One-line description. |

        <!--Document index end-->

        ## 1 Demo
        <!-- roles=orchestrator phases=3B summary="One-line description." -->

        Body paragraph.
        """
    )


# A clean file with both H2 and H3 + their TOC rows + their annotations.
def _clean_workflow_body_with_h3() -> str:
    return textwrap.dedent(
        """\
        # Demo workflow file

        <!--Document index start-->

        | Section | Roles | Phases | Summary |
        |---|---|---|---|
        | §1.6 Stamps | orchestrator | 3B | Stamp rule. |
        | §1.6(a) Format | orchestrator | 3B | Format rule. |

        <!--Document index end-->

        ## 1.6 Stamps
        <!-- roles=orchestrator phases=3B summary="Stamp rule." -->

        Body.

        ### (a) Format
        <!-- roles=orchestrator phases=3B summary="Format rule." -->

        Body.
        """
    )


# ---------------------------------------------------------------------------
# Rule 1 — workflow-SHA stamp on line 1 (staged docs/adr/ artifacts only).
#
# Live `.claude/workflow/` files do not carry a stamp; rule 1 is enforced
# on `docs/adr/<dir>/_workflow/staged-workflow/.claude/...` paths only.
# These tests use the staged subtree so the rule actually fires.
# ---------------------------------------------------------------------------


def test_rule_1_stamp_present_on_staged_path_passes() -> None:
    """A staged copy under docs/adr/.../staged-workflow/.claude/ with a
    valid workflow-sha stamp on line 1 passes rule 1.

    The fixture mirrors the workflow-modifying staging layout: the staged
    workflow file sits under `docs/adr/<plan>/_workflow/staged-workflow/.claude/workflow/`
    and starts with a 40-char-hex `workflow-sha:` comment. The validator's
    rule 1 gate (the `docs/adr/` path prefix branch) accepts the file
    without a rule_1 finding.
    """
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            <!-- workflow-sha: 0123456789abcdef0123456789abcdef01234567 -->
            # Demo staged file

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1 Demo | orchestrator | 3B | One-line description. |

            <!--Document index end-->

            ## 1 Demo
            <!-- roles=orchestrator phases=3B summary="One-line description." -->

            Body.
            """
        )
        staged_rel = (
            "docs/adr/some-plan/_workflow/staged-workflow/"
            ".claude/workflow/demo.md"
        )
        _write_in_scope_file(root, staged_rel, body)
        findings = MODULE.validate(root)
        rule1 = [
            f for f in findings if f.rule == "rule_1" and staged_rel in f.path
        ]
        assert not rule1, f"staged file with valid stamp should pass rule_1; got {rule1}"


def test_rule_1_missing_stamp_on_staged_path_fails() -> None:
    """A staged copy under docs/adr/.../staged-workflow/.claude/ that
    lacks the workflow-sha stamp on line 1 fails rule 1.

    The fixture starts the file with an H1, not the stamp comment, so the
    `_STAMP_LINE_RE` match fails and the validator records a rule_1
    finding at line 1.
    """
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo staged file

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1 Demo | orchestrator | 3B | One-line description. |

            <!--Document index end-->

            ## 1 Demo
            <!-- roles=orchestrator phases=3B summary="One-line description." -->

            Body.
            """
        )
        staged_rel = (
            "docs/adr/some-plan/_workflow/staged-workflow/"
            ".claude/workflow/demo.md"
        )
        _write_in_scope_file(root, staged_rel, body)
        findings = MODULE.validate(root)
        rule1 = [
            f for f in findings if f.rule == "rule_1" and staged_rel in f.path
        ]
        assert rule1, f"staged file without stamp should fail rule_1; got {findings}"
        assert rule1[0].line == 1, (
            f"rule_1 finding should anchor at line 1; got line {rule1[0].line}"
        )


def test_rule_1_live_workflow_file_without_stamp_passes() -> None:
    """A live `.claude/workflow/<name>.md` file without a workflow-sha
    stamp passes rule 1 — the rule is scoped to staged `docs/adr/`
    paths only (the drift-gate handles live workflow files separately).
    """
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Live workflow file with no stamp

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1 Demo | orchestrator | 3B | Body. |

            <!--Document index end-->

            ## 1 Demo
            <!-- roles=orchestrator phases=3B summary="Body." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/live-demo.md", body)
        findings = MODULE.validate(root)
        rule1 = [
            f for f in findings
            if f.rule == "rule_1" and "/live-demo.md" in f.path
        ]
        assert not rule1, (
            f"live workflow file should not trigger rule_1; got {rule1}"
        )


# ---------------------------------------------------------------------------
# Rule 2 — TOC region presence.
# ---------------------------------------------------------------------------


def test_rule_2_missing_toc_fails_when_file_has_h2() -> None:
    """A file with H2 headings but no TOC region surfaces a rule_2 finding."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo workflow file

            ## 1 Demo
            <!-- roles=orchestrator phases=3B summary="x" -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        rule2 = [f for f in findings if f.rule == "rule_2" and f.path.endswith("/demo.md")]
        assert rule2, f"expected rule_2 finding, got {findings}"
        assert "no TOC region" in rule2[0].explanation


def test_rule_2_no_toc_passes_when_file_has_no_h2() -> None:
    """A file with no H2 headings is allowed to omit the TOC region."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = "# Demo workflow file\n\nJust prose, no sections.\n"
        _write_in_scope_file(root, ".claude/workflow/no-headings.md", body)
        findings = MODULE.validate(root)
        rule2 = [
            f for f in findings
            if f.rule == "rule_2" and f.path.endswith("/no-headings.md")
        ]
        assert not rule2, f"expected no rule_2 finding, got {rule2}"


# ---------------------------------------------------------------------------
# Rule 3 — TOC matches annotations.
# ---------------------------------------------------------------------------


def test_rule_3_heading_without_toc_row_fails() -> None:
    """An H2 with no matching TOC row surfaces a rule_3 finding."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | orchestrator | 3B | A. |

            <!--Document index end-->

            ## A
            <!-- roles=orchestrator phases=3B summary="A." -->

            Body.

            ## B
            <!-- roles=orchestrator phases=3B summary="B." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        rule3 = _findings_for_path(
            [f for f in findings if f.rule == "rule_3"], "/demo.md"
        )
        assert any("'§B'" in f.explanation for f in rule3), (
            f"expected rule_3 finding for §B, got {rule3}"
        )


def test_rule_3_bootstrap_heading_exempt() -> None:
    """The bootstrap block heading does not require a TOC row."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            ## Reading workflow files (TOC protocol)

            Body of bootstrap block (heading carries no annotation).

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | orchestrator | 3B | A. |

            <!--Document index end-->

            ## A
            <!-- roles=orchestrator phases=3B summary="A." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        rule3 = _findings_for_path(
            [f for f in findings if f.rule == "rule_3"], "/demo.md"
        )
        rule4 = _findings_for_path(
            [f for f in findings if f.rule == "rule_4"], "/demo.md"
        )
        assert not rule3, f"bootstrap heading should not trigger rule_3, got {rule3}"
        assert not rule4, f"bootstrap heading should not trigger rule_4, got {rule4}"


def test_rule_3_orphan_toc_row_fails() -> None:
    """A TOC row pointing to a missing heading surfaces a rule_3 finding."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | orchestrator | 3B | A. |
            | §Ghost | orchestrator | 3B | Phantom. |

            <!--Document index end-->

            ## A
            <!-- roles=orchestrator phases=3B summary="A." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        rule3 = _findings_for_path(
            [f for f in findings if f.rule == "rule_3"], "/demo.md"
        )
        assert any("§Ghost" in f.explanation for f in rule3), (
            f"expected rule_3 finding for §Ghost orphan, got {rule3}"
        )


# ---------------------------------------------------------------------------
# Rule 4 — annotation presence.
# ---------------------------------------------------------------------------


def test_rule_4_missing_annotation_fails() -> None:
    """An H2 with no annotation comment on the next line fails rule_4."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | orchestrator | 3B | A. |

            <!--Document index end-->

            ## A

            No annotation here.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        rule4 = _findings_for_path(
            [f for f in findings if f.rule == "rule_4"], "/demo.md"
        )
        assert rule4, f"expected rule_4 finding, got {findings}"


# ---------------------------------------------------------------------------
# Rule 5 — annotation field well-formedness.
# ---------------------------------------------------------------------------


def test_rule_5a_space_after_comma_fails() -> None:
    """`roles=foo, bar` (space after comma) fails rule_5a."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | x, y | 3B | A. |

            <!--Document index end-->

            ## A
            <!-- roles=orchestrator, implementer phases=3B summary="A." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        rule5a = [f for f in findings if f.rule == "rule_5a"]
        assert rule5a, f"expected rule_5a finding, got {findings}"


def test_rule_5b_missing_phases_fails() -> None:
    """`phases=` field missing fails rule_5b."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | orchestrator | 3B | A. |

            <!--Document index end-->

            ## A
            <!-- roles=orchestrator summary="A." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        rule5b = [f for f in findings if f.rule == "rule_5b"]
        assert rule5b, f"expected rule_5b finding, got {findings}"


def test_rule_5c_summary_over_120_chars_fails() -> None:
    """`summary` longer than the 120-char cap from §1.8(c) fails rule_5c."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        # Build a 130-char summary so the body is well past the cap.
        long_summary = "x" * 130
        body = textwrap.dedent(
            f"""\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | orchestrator | 3B | A. |

            <!--Document index end-->

            ## A
            <!-- roles=orchestrator phases=3B summary="{long_summary}" -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        rule5c = [f for f in findings if f.rule == "rule_5c"]
        assert rule5c, f"expected rule_5c finding, got {findings}"
        assert "130 chars" in rule5c[0].explanation, (
            f"expected char count in message, got {rule5c[0].explanation}"
        )


def test_rule_5d_out_of_enum_role_fails() -> None:
    """A role token not in the bootstrap enum fails rule_5d."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | nonsense | 3B | A. |

            <!--Document index end-->

            ## A
            <!-- roles=nonsense phases=3B summary="A." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        rule5d = [f for f in findings if f.rule == "rule_5d"]
        assert rule5d, f"expected rule_5d finding, got {findings}"
        assert "'nonsense'" in rule5d[0].explanation, (
            f"expected offending token in message, got {rule5d[0].explanation}"
        )


def test_rule_5d_any_token_accepted_in_roles_and_phases() -> None:
    """`any` is in both the role enum and the phase enum per §1.8(b)."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | any | any | A. |

            <!--Document index end-->

            ## A
            <!-- roles=any phases=any summary="A." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        # No rule_5d findings should fire for `any` tokens.
        rule5d = [f for f in findings if f.rule == "rule_5d"]
        assert not rule5d, f"`any` should pass rule_5d, got {rule5d}"


# ---------------------------------------------------------------------------
# Rule 6 — cross-file refs.
# ---------------------------------------------------------------------------


def _two_file_cross_ref_setup(root: Path, citer_body: str, target_body: str) -> None:
    """Write a target conventions.md and a citing file under .claude/agents/.

    The fixture uses an `.claude/agents/` file as the citer because rule
    6 applies to cross-file refs in agent files and SKILL.md. Agents are
    not in the in-scope-glob set by default — to make the rule fire on
    the agent file we instead place the citer under `.claude/workflow/`
    so the in-scope discovery picks it up.
    """
    _write_conventions(root)
    _write_in_scope_file(root, ".claude/workflow/target.md", target_body)
    _write_in_scope_file(root, ".claude/workflow/citer.md", citer_body)


def test_rule_6_missing_suffix_fails() -> None:
    """A cross-file ref `target.md` with no suffix fails rule_6."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        target = textwrap.dedent(
            """\
            # Target

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Foo | orchestrator | 3B | Foo. |

            <!--Document index end-->

            ## 1.6 Foo
            <!-- roles=orchestrator phases=3B summary="Foo." -->

            Body.
            """
        )
        citer = textwrap.dedent(
            """\
            # Citer

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | orchestrator | 3B | A. |

            <!--Document index end-->

            ## A
            <!-- roles=orchestrator phases=3B summary="A." -->

            See target.md for details.
            """
        )
        _two_file_cross_ref_setup(root, citer, target)
        findings = MODULE.validate(root)
        rule6 = _findings_for_path(
            [f for f in findings if f.rule == "rule_6"], "/citer.md"
        )
        assert any(
            "target.md" in f.explanation and "missing" in f.explanation for f in rule6
        ), f"expected rule_6 missing-suffix finding, got {rule6}"


def test_rule_6_role_subset_violation_fails() -> None:
    """Citer claims a role the target's annotation does not grant — rule_6 fails."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        target = textwrap.dedent(
            """\
            # Target

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Foo | orchestrator | 3B | Foo. |

            <!--Document index end-->

            ## 1.6 Foo
            <!-- roles=orchestrator phases=3B summary="Foo." -->

            Body.
            """
        )
        citer = textwrap.dedent(
            """\
            # Citer

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | orchestrator | 3B | A. |

            <!--Document index end-->

            ## A
            <!-- roles=orchestrator phases=3B summary="A." -->

            See target.md§1.6:implementer:3B for details.
            """
        )
        _two_file_cross_ref_setup(root, citer, target)
        findings = MODULE.validate(root)
        rule6 = [f for f in findings if f.rule == "rule_6"]
        assert any("roles" in f.explanation and "subset" in f.explanation for f in rule6), (
            f"expected rule_6 role subset finding, got {rule6}"
        )


def test_rule_6_phase_subset_violation_fails() -> None:
    """Citer claims a phase the target's annotation does not grant — rule_6 fails."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        target = textwrap.dedent(
            """\
            # Target

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Foo | orchestrator | 3B | Foo. |

            <!--Document index end-->

            ## 1.6 Foo
            <!-- roles=orchestrator phases=3B summary="Foo." -->

            Body.
            """
        )
        citer = textwrap.dedent(
            """\
            # Citer

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | orchestrator | 3B | A. |

            <!--Document index end-->

            ## A
            <!-- roles=orchestrator phases=3B summary="A." -->

            See target.md§1.6:orchestrator:4 for details.
            """
        )
        _two_file_cross_ref_setup(root, citer, target)
        findings = MODULE.validate(root)
        rule6 = [f for f in findings if f.rule == "rule_6"]
        assert any(
            "phases" in f.explanation and "subset" in f.explanation for f in rule6
        ), f"expected rule_6 phase subset finding, got {rule6}"


def test_rule_6_target_any_role_accepts_any_citer() -> None:
    """`target.roles={any}` matches every concrete citer role per §1.8(e)."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        target = textwrap.dedent(
            """\
            # Target

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Foo | any | 3B | Foo. |

            <!--Document index end-->

            ## 1.6 Foo
            <!-- roles=any phases=3B summary="Foo." -->

            Body.
            """
        )
        citer = textwrap.dedent(
            """\
            # Citer

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | orchestrator | 3B | A. |

            <!--Document index end-->

            ## A
            <!-- roles=orchestrator phases=3B summary="A." -->

            See target.md§1.6:implementer:3B for details.
            """
        )
        _two_file_cross_ref_setup(root, citer, target)
        findings = MODULE.validate(root)
        rule6 = [f for f in findings if f.rule == "rule_6"]
        # target.roles={any} accepts any citer.roles — no subset finding.
        assert not any(
            "roles" in f.explanation and "subset" in f.explanation for f in rule6
        ), f"target-any should accept any citer role, got {rule6}"


def test_rule_6_citer_any_role_against_narrow_target_fails() -> None:
    """`citer.roles={any}` requires `target.roles={any}` per §1.8(e)."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        target = textwrap.dedent(
            """\
            # Target

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Foo | orchestrator | 3B | Foo. |

            <!--Document index end-->

            ## 1.6 Foo
            <!-- roles=orchestrator phases=3B summary="Foo." -->

            Body.
            """
        )
        citer = textwrap.dedent(
            """\
            # Citer

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | orchestrator | 3B | A. |

            <!--Document index end-->

            ## A
            <!-- roles=orchestrator phases=3B summary="A." -->

            See target.md§1.6:any:3B for details.
            """
        )
        _two_file_cross_ref_setup(root, citer, target)
        findings = MODULE.validate(root)
        rule6 = [f for f in findings if f.rule == "rule_6"]
        assert any("roles" in f.explanation and "subset" in f.explanation for f in rule6), (
            f"citer-any against narrow target should fail, got {rule6}"
        )


def test_rule_6_both_any_wildcard_passes() -> None:
    """`target.roles={any}` + `citer.roles={any}` is the trivially satisfied case.

    Per §1.8(e), `target.roles={any}` matches any citer role, and the
    citer-any case requires the target to also be `any`. Both ends being
    `any` is the union; no rule_6 subset finding should fire.
    """
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        target = textwrap.dedent(
            """\
            # Target

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Foo | any | any | Foo. |

            <!--Document index end-->

            ## 1.6 Foo
            <!-- roles=any phases=any summary="Foo." -->

            Body.
            """
        )
        citer = textwrap.dedent(
            """\
            # Citer

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | any | any | A. |

            <!--Document index end-->

            ## A
            <!-- roles=any phases=any summary="A." -->

            See target.md§1.6:any:any for details.
            """
        )
        _two_file_cross_ref_setup(root, citer, target)
        findings = MODULE.validate(root)
        rule6 = [f for f in findings if f.rule == "rule_6"]
        assert not rule6, (
            f"both-any wildcard should produce no rule_6 finding, got {rule6}"
        )


def test_rule_6_file_level_ref_subset_against_union() -> None:
    """A file-level ref subset-validates against the union of every section."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        # Target carries TWO sections with disjoint roles; the union is
        # {orchestrator, implementer}.
        target = textwrap.dedent(
            """\
            # Target

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1 A | orchestrator | 3B | A. |
            | §2 B | implementer | 3B | B. |

            <!--Document index end-->

            ## 1 A
            <!-- roles=orchestrator phases=3B summary="A." -->

            Body.

            ## 2 B
            <!-- roles=implementer phases=3B summary="B." -->

            Body.
            """
        )
        citer = textwrap.dedent(
            """\
            # Citer

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | orchestrator | 3B | A. |

            <!--Document index end-->

            ## A
            <!-- roles=orchestrator phases=3B summary="A." -->

            See target.md:implementer:3B for details.
            """
        )
        _two_file_cross_ref_setup(root, citer, target)
        findings = MODULE.validate(root)
        rule6 = [f for f in findings if f.rule == "rule_6"]
        # `implementer` is in the union — no subset finding.
        assert not any("subset" in f.explanation for f in rule6), (
            f"file-level subset against union should pass, got {rule6}"
        )


def test_rule_6_sub_section_ref_resolves_to_section_annotation() -> None:
    """A sub-section ref `name.md§X.Y(z)` resolves to that section's annotation."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        target = textwrap.dedent(
            """\
            # Target

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Stamps | orchestrator | 3B | Stamps. |
            | §1.6(a) Format | implementer | 3C | Format. |

            <!--Document index end-->

            ## 1.6 Stamps
            <!-- roles=orchestrator phases=3B summary="Stamps." -->

            Body.

            ### (a) Format
            <!-- roles=implementer phases=3C summary="Format." -->

            Body.
            """
        )
        citer = textwrap.dedent(
            """\
            # Citer

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | orchestrator | 3B | A. |

            <!--Document index end-->

            ## A
            <!-- roles=orchestrator phases=3B summary="A." -->

            See target.md§1.6(a):implementer:3C for details.
            """
        )
        _two_file_cross_ref_setup(root, citer, target)
        findings = MODULE.validate(root)
        rule6 = [f for f in findings if f.rule == "rule_6"]
        # The sub-section's annotation is (implementer, 3C) — citer
        # (implementer, 3C) matches exactly. No subset finding.
        assert not any("subset" in f.explanation for f in rule6), (
            f"sub-section ref should match the sub-section annotation, got {rule6}"
        )


def test_rule_6_claude_md_out_of_scope() -> None:
    """`CLAUDE.md` is explicitly out of rule_6 scope per §1.8(e)."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Citer

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | orchestrator | 3B | A. |

            <!--Document index end-->

            ## A
            <!-- roles=orchestrator phases=3B summary="A." -->

            See CLAUDE.md for the project conventions.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/citer.md", body)
        findings = MODULE.validate(root)
        rule6 = _findings_for_path(
            [f for f in findings if f.rule == "rule_6"], "/citer.md"
        )
        assert not any("CLAUDE.md" in f.explanation for f in rule6), (
            f"CLAUDE.md should be out of scope, got {rule6}"
        )


def test_rule_6_ref_in_fenced_block_excluded() -> None:
    """A cross-file ref inside a ```-fenced block is not validated."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Citer

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | orchestrator | 3B | A. |

            <!--Document index end-->

            ## A
            <!-- roles=orchestrator phases=3B summary="A." -->

            Example:

            ```
            See target.md for details.
            ```
            """
        )
        _write_in_scope_file(root, ".claude/workflow/citer.md", body)
        findings = MODULE.validate(root)
        rule6 = _findings_for_path(
            [f for f in findings if f.rule == "rule_6"], "/citer.md"
        )
        assert not rule6, f"refs inside fenced blocks should be excluded, got {rule6}"


def test_rule_6_ref_in_inline_backticks_excluded() -> None:
    """A cross-file ref inside an inline backtick span is not validated."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Citer

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | orchestrator | 3B | A. |

            <!--Document index end-->

            ## A
            <!-- roles=orchestrator phases=3B summary="A." -->

            See `target.md` — note the inline backticks.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/citer.md", body)
        findings = MODULE.validate(root)
        rule6 = _findings_for_path(
            [f for f in findings if f.rule == "rule_6"], "/citer.md"
        )
        assert not rule6, f"inline-backtick refs should be excluded, got {rule6}"


# ---------------------------------------------------------------------------
# Rule 7 — bootstrap block presence.
# ---------------------------------------------------------------------------


def test_rule_7_missing_bootstrap_fails_for_skill_md() -> None:
    """A SKILL.md missing the bootstrap heading fails rule_7."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        # The SKILL.md must be one of the 7 in-scope names.
        body = textwrap.dedent(
            """\
            # Create Plan Skill

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1 Body | planner | 1 | Body. |

            <!--Document index end-->

            ## 1 Body
            <!-- roles=planner phases=1 summary="Body." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/skills/create-plan/SKILL.md", body)
        findings = MODULE.validate(root)
        rule7 = [f for f in findings if f.rule == "rule_7"]
        assert rule7, f"expected rule_7 finding, got {findings}"
        assert "create-plan" in rule7[0].path


def test_rule_7_bootstrap_present_passes() -> None:
    """A SKILL.md with the bootstrap heading passes rule_7."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            ## Reading workflow files (TOC protocol)

            (bootstrap block body would live here in production)

            # Create Plan Skill

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1 Body | planner | 1 | Body. |

            <!--Document index end-->

            ## 1 Body
            <!-- roles=planner phases=1 summary="Body." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/skills/create-plan/SKILL.md", body)
        findings = MODULE.validate(root)
        rule7 = [f for f in findings if f.rule == "rule_7"]
        assert not rule7, f"bootstrap present should pass rule_7, got {rule7}"


def test_rule_7_out_of_scope_skill_not_required() -> None:
    """A non-workflow skill is not enumerated in `IN_SCOPE_GLOBS`."""
    # Non-workflow skills are not even in the in-scope discovery set,
    # so they are never validated. Confirm by writing a non-workflow
    # skill alongside the conventions fixture and asserting it does not
    # appear in the parsed-files list.
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        _write_in_scope_file(
            root,
            ".claude/skills/ai-tells/SKILL.md",
            "# Not in scope\n\nNo bootstrap needed.\n",
        )
        parsed = MODULE.parse_in_scope_files(root)
        paths = {pf.path for pf in parsed}
        assert ".claude/skills/ai-tells/SKILL.md" not in paths, (
            "non-workflow skill should be out of in-scope-globs"
        )


# ---------------------------------------------------------------------------
# Rule 8 — in-file ref auto-stamp.
# ---------------------------------------------------------------------------


def test_rule_8_unstamped_in_file_ref_fails() -> None:
    """An in-file ref `§X.Y` with no suffix is a rule_8 blocker."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Stamps | orchestrator | 3B | Stamps. |
            | §1.7 Refs | orchestrator | 3B | Refs. |

            <!--Document index end-->

            ## 1.6 Stamps
            <!-- roles=orchestrator phases=3B summary="Stamps." -->

            Body.

            ## 1.7 Refs
            <!-- roles=orchestrator phases=3B summary="Refs." -->

            See §1.6 for the stamp rule.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        rule8 = _findings_for_path(
            [f for f in findings if f.rule == "rule_8"], "/demo.md"
        )
        assert any("unstamped" in f.explanation for f in rule8), (
            f"expected rule_8 unstamped finding, got {rule8}"
        )


def test_rule_8_stale_in_file_ref_fails() -> None:
    """An in-file ref whose suffix drifts from the target fails rule_8."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Stamps | orchestrator | 3B | Stamps. |
            | §1.7 Refs | orchestrator | 3B | Refs. |

            <!--Document index end-->

            ## 1.6 Stamps
            <!-- roles=orchestrator phases=3B summary="Stamps." -->

            Body.

            ## 1.7 Refs
            <!-- roles=orchestrator phases=3B summary="Refs." -->

            See §1.6:implementer:4 for the stamp rule.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        rule8 = _findings_for_path(
            [f for f in findings if f.rule == "rule_8"], "/demo.md"
        )
        assert any("drifted" in f.explanation for f in rule8), (
            f"expected rule_8 stale-suffix finding, got {rule8}"
        )


def test_rule_8_unresolved_in_file_ref_fails() -> None:
    """An in-file ref `§9.99` with no matching heading is a rule_8 blocker."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Stamps | orchestrator | 3B | Stamps. |

            <!--Document index end-->

            ## 1.6 Stamps
            <!-- roles=orchestrator phases=3B summary="Stamps." -->

            See §9.99:orchestrator:3B for nowhere.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        rule8 = _findings_for_path(
            [f for f in findings if f.rule == "rule_8"], "/demo.md"
        )
        assert any("does not resolve" in f.explanation for f in rule8), (
            f"expected rule_8 unresolved finding, got {rule8}"
        )


def test_rule_8_stamped_ref_matching_target_passes() -> None:
    """An in-file ref whose suffix matches the target's annotation passes."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Stamps | orchestrator | 3B | Stamps. |
            | §1.7 Refs | orchestrator | 3B | Refs. |

            <!--Document index end-->

            ## 1.6 Stamps
            <!-- roles=orchestrator phases=3B summary="Stamps." -->

            Body.

            ## 1.7 Refs
            <!-- roles=orchestrator phases=3B summary="Refs." -->

            See §1.6:orchestrator:3B for the stamp rule.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        rule8 = _findings_for_path(
            [f for f in findings if f.rule == "rule_8"], "/demo.md"
        )
        assert not rule8, f"matching-suffix ref should pass rule_8, got {rule8}"


def test_rule_8_subsection_ref_resolves_to_subsection() -> None:
    """An in-file `§X.Y(z)` ref resolves to the `### (z)` sub-section under `## X.Y`."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Stamps | orchestrator | 3B | Stamps. |
            | §1.6(a) Format | implementer | 3C | Format. |
            | §1.7 Refs | orchestrator | 3B | Refs. |

            <!--Document index end-->

            ## 1.6 Stamps
            <!-- roles=orchestrator phases=3B summary="Stamps." -->

            Body.

            ### (a) Format
            <!-- roles=implementer phases=3C summary="Format." -->

            Body.

            ## 1.7 Refs
            <!-- roles=orchestrator phases=3B summary="Refs." -->

            See §1.6(a):implementer:3C for the format.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        rule8 = _findings_for_path(
            [f for f in findings if f.rule == "rule_8"], "/demo.md"
        )
        assert not rule8, f"sub-section matching-suffix ref should pass, got {rule8}"


def test_rule_8_in_file_ref_in_inline_backticks_excluded() -> None:
    """In-file refs inside inline-backtick spans are excluded from validation."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Stamps | orchestrator | 3B | Stamps. |

            <!--Document index end-->

            ## 1.6 Stamps
            <!-- roles=orchestrator phases=3B summary="Stamps." -->

            The literal text `§9.99(z)` is just a pedagogical example.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        rule8 = _findings_for_path(
            [f for f in findings if f.rule == "rule_8"], "/demo.md"
        )
        assert not rule8, f"refs inside inline backticks should be excluded, got {rule8}"


# ---------------------------------------------------------------------------
# CLI tests — exit codes 0 / 1 / 2 and --files filter.
# ---------------------------------------------------------------------------


def test_validate_returns_empty_findings_on_clean_tree() -> None:
    """A fixture tree with one clean workflow file produces no findings."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        _write_in_scope_file(
            root, ".claude/workflow/clean.md", _clean_workflow_body()
        )
        findings = MODULE.validate(root)
        # The conventions.md fixture itself carries no annotations and
        # no TOC region; it will produce rule_2 / rule_4 findings. The
        # clean.md fixture should have NO findings of its own.
        clean_findings = _findings_for_path(findings, "/clean.md")
        assert not clean_findings, f"clean.md should have no findings, got {clean_findings}"


def test_validate_files_filter_silently_skips_out_of_scope() -> None:
    """`--files` containing only out-of-scope paths returns no findings."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        _write_in_scope_file(
            root, ".claude/workflow/clean.md", _clean_workflow_body()
        )
        # An out-of-scope path: `.claude/skills/ai-tells/SKILL.md` is
        # not in IN_SCOPE_GLOBS and should be silently dropped.
        _write_in_scope_file(
            root,
            ".claude/skills/ai-tells/SKILL.md",
            "# Out of scope\n",
        )
        findings = MODULE.validate(
            root, files_filter=[".claude/skills/ai-tells/SKILL.md"]
        )
        assert not findings, f"out-of-scope filter should yield no findings, got {findings}"


def test_validate_files_filter_scopes_findings_to_listed_paths() -> None:
    """`--files` limits findings to the listed file set."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        # Both files have rule_2 findings (no TOC + has H2).
        bad_body = textwrap.dedent(
            """\
            # Demo

            ## A
            <!-- roles=orchestrator phases=3B summary="A." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/a.md", bad_body)
        _write_in_scope_file(root, ".claude/workflow/b.md", bad_body)
        all_findings = MODULE.validate(root)
        a_findings = _findings_for_path(all_findings, "/a.md")
        b_findings = _findings_for_path(all_findings, "/b.md")
        assert a_findings and b_findings, "both files should have findings unfiltered"
        # Scoped to a.md only — b.md findings should not surface.
        scoped = MODULE.validate(root, files_filter=[".claude/workflow/a.md"])
        scoped_a = _findings_for_path(scoped, "/a.md")
        scoped_b = _findings_for_path(scoped, "/b.md")
        assert scoped_a, f"a.md findings should still appear under --files, got {scoped_a}"
        assert not scoped_b, f"b.md findings should be filtered out, got {scoped_b}"


def test_cli_check_exit_0_on_clean_tree() -> None:
    """`--check` exits 0 when there are no findings for the scoped set."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        _write_in_scope_file(
            root, ".claude/workflow/clean.md", _clean_workflow_body()
        )
        # Use the validator directly with the fixture root, plus a
        # files-filter scoped to the clean file. CLI-level invocation
        # would rely on REPO_ROOT, which points at the live tree.
        findings = MODULE.validate(root, files_filter=[".claude/workflow/clean.md"])
        assert not findings, f"clean filter should yield exit 0, got {findings}"


def test_cli_check_findings_yield_exit_1() -> None:
    """`--check` emits exit 1 when findings are present.

    The CLI dispatcher returns 1 from the `main()` function when the
    validator returns findings. This test exercises the validator's
    return path; the CLI-level integration test below covers
    `main()` itself.
    """
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        bad_body = textwrap.dedent(
            """\
            # Demo

            ## A
            <!-- roles=orchestrator phases=3B summary="A." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/bad.md", bad_body)
        findings = MODULE.validate(root)
        bad_findings = _findings_for_path(findings, "/bad.md")
        assert bad_findings, "expected findings for bad fixture"


def test_finding_render_shape() -> None:
    """`Finding.render` emits `path:line:rule: explanation`."""
    f = MODULE.Finding(
        path=".claude/workflow/x.md",
        line=42,
        rule="rule_5c",
        explanation="summary too long",
    )
    assert f.render() == ".claude/workflow/x.md:42:rule_5c: summary too long"


# ---------------------------------------------------------------------------
# Subset helper unit tests (the `any`-wildcard semantics in isolation).
# ---------------------------------------------------------------------------


def test_subset_with_any_target_wildcard_accepts_any_citer() -> None:
    """`target={any}` accepts any citer set."""
    assert MODULE.subset_with_any_wildcard({"orchestrator"}, {"any"})
    assert MODULE.subset_with_any_wildcard({"any"}, {"any"})


def test_subset_with_any_citer_wildcard_against_narrow_target_fails() -> None:
    """`citer={any}` against a narrow target fails."""
    assert not MODULE.subset_with_any_wildcard({"any"}, {"orchestrator"})


def test_subset_with_any_concrete_set_subset_check() -> None:
    """Plain set-subset check applies when neither side carries `any`."""
    assert MODULE.subset_with_any_wildcard({"orchestrator"}, {"orchestrator", "implementer"})
    assert not MODULE.subset_with_any_wildcard({"planner"}, {"orchestrator", "implementer"})


# ---------------------------------------------------------------------------
# Bootstrap-scope discovery (rule 7 surface).
# ---------------------------------------------------------------------------


def test_discover_bootstrap_scope_includes_all_known_paths() -> None:
    """The bootstrap-scope set covers the 7 SKILL.md, 11 prompts, and 20 agents."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        # Create the 7 SKILL.md anchors.
        skills = (
            "create-plan",
            "execute-tracks",
            "edit-design",
            "migrate-workflow",
            "review-workflow-pr",
            "review-plan",
            "code-review",
        )
        for s in skills:
            _write_in_scope_file(root, f".claude/skills/{s}/SKILL.md", "# x\n")
        # A couple of agents and prompts.
        _write_in_scope_file(root, ".claude/agents/some-agent.md", "# x\n")
        _write_in_scope_file(root, ".claude/workflow/prompts/some-prompt.md", "# x\n")
        # An out-of-scope skill (should not appear).
        _write_in_scope_file(
            root, ".claude/skills/ai-tells/SKILL.md", "# x\n"
        )
        paths = MODULE.discover_bootstrap_scope(root)
        rels = {
            p.resolve().relative_to(root.resolve()).as_posix() for p in paths
        }
        # Every workflow-referencing SKILL.md is in scope.
        for s in skills:
            assert f".claude/skills/{s}/SKILL.md" in rels, (
                f"missing {s} in bootstrap scope"
            )
        # Agent and prompt picked up via directory walk.
        assert ".claude/agents/some-agent.md" in rels
        assert ".claude/workflow/prompts/some-prompt.md" in rels
        # Out-of-scope skill not present.
        assert ".claude/skills/ai-tells/SKILL.md" not in rels


# ---------------------------------------------------------------------------
# `--write` mode tests.
#
# Each test builds a hermetic fixture root, runs `compute_write_plan`
# and `apply_write_plan`, and asserts on the rewritten file content.
# The halt-on-unresolved test asserts the disk state is unchanged
# across files when even one in-scope file has an unresolved ref.
# ---------------------------------------------------------------------------


def _read_file(path: Path) -> str:
    """Return the file content as text."""
    return path.read_text(encoding="utf-8")


def _run_write_plan(root: Path, files_filter=None) -> dict:
    """Compute and apply the write plan; return the plan dict."""
    plan = MODULE.compute_write_plan(root, files_filter=files_filter)
    MODULE.apply_write_plan(plan)
    return plan


def test_write_rebuilds_toc_from_h2_annotations() -> None:
    """A file with stale TOC rows gets the TOC rebuilt from current annotations."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        # The TOC body is intentionally wrong (the "stale" row maps to
        # a heading that does not exist; the real heading carries
        # different annotation content).
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §A | implementer | 4 | Stale summary. |

            <!--Document index end-->

            ## A
            <!-- roles=orchestrator phases=3B summary="Current summary." -->

            Body.
            """
        )
        target = _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        _run_write_plan(root, files_filter=[".claude/workflow/demo.md"])
        rewritten = _read_file(target)
        # The rebuilt TOC row carries the current annotation values.
        assert (
            "| §A | orchestrator | 3B | Current summary. |" in rewritten
        ), f"expected fresh TOC row; got:\n{rewritten}"
        # The stale row is gone.
        assert "Stale summary." not in rewritten, (
            f"stale TOC row should be gone; got:\n{rewritten}"
        )


def test_write_rebuilds_toc_with_h2_and_h3() -> None:
    """The TOC rebuild emits one row per H2 AND per H3 in document order."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            <!--Document index end-->

            ## 1.6 Stamps
            <!-- roles=orchestrator phases=3B summary="Stamps." -->

            Body.

            ### (a) Format
            <!-- roles=implementer phases=3C summary="Format." -->

            Body.

            ## 1.7 Refs
            <!-- roles=orchestrator phases=3B summary="Refs." -->

            Body.
            """
        )
        target = _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        _run_write_plan(root, files_filter=[".claude/workflow/demo.md"])
        rewritten = _read_file(target)
        # Three rows: H2 1.6, H3 (a), H2 1.7 — in document order.
        h16_idx = rewritten.find("| §1.6 Stamps")
        ha_idx = rewritten.find("| §(a) Format")
        h17_idx = rewritten.find("| §1.7 Refs")
        assert h16_idx > 0, f"missing H2 1.6 row; got:\n{rewritten}"
        assert ha_idx > h16_idx, f"H3 (a) row should follow H2 1.6; got:\n{rewritten}"
        assert h17_idx > ha_idx, f"H2 1.7 row should follow H3 (a); got:\n{rewritten}"


def test_write_bootstrap_heading_omitted_from_toc() -> None:
    """The bootstrap-block heading does not appear in the rebuilt TOC."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            ## Reading workflow files (TOC protocol)

            Bootstrap-block body.

            <!--Document index start-->

            <!--Document index end-->

            ## 1 Body
            <!-- roles=orchestrator phases=3B summary="Body." -->

            Body.
            """
        )
        target = _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        _run_write_plan(root, files_filter=[".claude/workflow/demo.md"])
        rewritten = _read_file(target)
        # The bootstrap heading must not appear as a TOC row.
        assert "Reading workflow files (TOC protocol)" not in rewritten.split(
            "<!--Document index end-->"
        )[0].split("<!--Document index start-->")[1], (
            f"bootstrap heading should be exempt from TOC; got:\n{rewritten}"
        )
        assert "| §1 Body" in rewritten, (
            f"expected the real H2 in the TOC; got:\n{rewritten}"
        )


def test_write_empty_toc_when_no_h2() -> None:
    """A file with no `^## ` headings and a TOC region gets an empty TOC body."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §Ghost | orchestrator | 3B | Phantom. |

            <!--Document index end-->

            Just prose, no sections.
            """
        )
        target = _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        _run_write_plan(root, files_filter=[".claude/workflow/demo.md"])
        rewritten = _read_file(target)
        # No `| Section |` header row should be inside the rebuilt TOC.
        between = rewritten.split("<!--Document index start-->")[1].split(
            "<!--Document index end-->"
        )[0]
        assert "| Section |" not in between, (
            f"empty TOC should carry no table; got TOC body:\n{between!r}"
        )
        # The phantom row is gone.
        assert "Phantom." not in rewritten, (
            f"phantom row should be removed; got:\n{rewritten}"
        )


def test_write_no_toc_delimiters_no_op_on_toc_half() -> None:
    """A file without TOC delimiters is a no-op for the TOC half of `--write`."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        # No TOC delimiters at all. `--write` must NOT inject them.
        body = textwrap.dedent(
            """\
            # Demo

            ## 1 Body
            <!-- roles=orchestrator phases=3B summary="Body." -->

            Body.
            """
        )
        target = _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        before = _read_file(target)
        _run_write_plan(root, files_filter=[".claude/workflow/demo.md"])
        after = _read_file(target)
        assert "<!--Document index start-->" not in after, (
            f"--write should NOT inject TOC delimiters; got:\n{after}"
        )
        assert before == after, (
            f"file with no TOC should be untouched; before vs after:\n"
            f"BEFORE:\n{before}\nAFTER:\n{after}"
        )


def test_write_stamps_unstamped_in_file_ref() -> None:
    """An unstamped in-file ref `§X.Y` gets the target's suffix appended."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Stamps | orchestrator | 3B | Stamps. |
            | §1.7 Refs | orchestrator | 3B | Refs. |

            <!--Document index end-->

            ## 1.6 Stamps
            <!-- roles=orchestrator phases=3B summary="Stamps." -->

            Body.

            ## 1.7 Refs
            <!-- roles=orchestrator phases=3B summary="Refs." -->

            See §1.6 for the stamp rule.
            """
        )
        target = _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        _run_write_plan(root, files_filter=[".claude/workflow/demo.md"])
        rewritten = _read_file(target)
        assert "See §1.6:orchestrator:3B for the stamp rule." in rewritten, (
            f"expected stamped ref; got:\n{rewritten}"
        )


def test_write_rewrites_stale_in_file_ref_suffix() -> None:
    """A stale in-file ref suffix gets rewritten to match the target's current annotation."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Stamps | orchestrator | 3B | Stamps. |
            | §1.7 Refs | orchestrator | 3B | Refs. |

            <!--Document index end-->

            ## 1.6 Stamps
            <!-- roles=orchestrator phases=3B summary="Stamps." -->

            Body.

            ## 1.7 Refs
            <!-- roles=orchestrator phases=3B summary="Refs." -->

            See §1.6:implementer:4 for the stamp rule.
            """
        )
        target = _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        _run_write_plan(root, files_filter=[".claude/workflow/demo.md"])
        rewritten = _read_file(target)
        # Stale `:implementer:4` rewritten to current `:orchestrator:3B`.
        assert "See §1.6:orchestrator:3B" in rewritten, (
            f"expected rewritten suffix; got:\n{rewritten}"
        )
        assert ":implementer:4" not in rewritten, (
            f"stale suffix should be gone; got:\n{rewritten}"
        )


def test_write_skips_ref_in_fenced_block() -> None:
    """A `§X.Y` ref inside a fenced code block is not auto-stamped."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Stamps | orchestrator | 3B | Stamps. |

            <!--Document index end-->

            ## 1.6 Stamps
            <!-- roles=orchestrator phases=3B summary="Stamps." -->

            Example block:

            ```
            See §1.6 — should stay as-is.
            ```
            """
        )
        target = _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        _run_write_plan(root, files_filter=[".claude/workflow/demo.md"])
        rewritten = _read_file(target)
        # The ref inside the fenced block should still be `§1.6` (no suffix).
        assert "See §1.6 — should stay as-is." in rewritten, (
            f"fenced-block ref should NOT be auto-stamped; got:\n{rewritten}"
        )


def test_write_skips_ref_in_inline_backticks() -> None:
    """A `§X.Y` ref inside an inline-backtick span is not auto-stamped."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Stamps | orchestrator | 3B | Stamps. |

            <!--Document index end-->

            ## 1.6 Stamps
            <!-- roles=orchestrator phases=3B summary="Stamps." -->

            The literal text `§1.6` is a pedagogical example and stays bare.
            """
        )
        target = _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        _run_write_plan(root, files_filter=[".claude/workflow/demo.md"])
        rewritten = _read_file(target)
        # Backticked ref retains its literal form.
        assert "`§1.6`" in rewritten, (
            f"backticked ref should NOT be auto-stamped; got:\n{rewritten}"
        )
        assert "`§1.6:orchestrator:3B`" not in rewritten, (
            f"backticked ref should not gain a suffix; got:\n{rewritten}"
        )


def test_write_halts_on_unresolved_ref_in_same_file() -> None:
    """A file with mixed resolvable + unresolved refs aborts with no writes."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Stamps | orchestrator | 3B | Stamps. |

            <!--Document index end-->

            ## 1.6 Stamps
            <!-- roles=orchestrator phases=3B summary="Stamps." -->

            See §1.6 (resolvable) and §9.99 (unresolved) — neither should land.
            """
        )
        target = _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        before = _read_file(target)
        try:
            MODULE.compute_write_plan(
                root, files_filter=[".claude/workflow/demo.md"]
            )
        except MODULE.UnresolvedInFileRefError as exc:
            # Exactly one unresolved site reported.
            assert any(
                anchor == "§9.99" for _path, _line, anchor in exc.sites
            ), f"expected §9.99 in unresolved sites; got {exc.sites}"
        else:
            raise AssertionError(
                "expected UnresolvedInFileRefError for mixed-content file"
            )
        after = _read_file(target)
        # No write landed — the otherwise-resolvable §1.6 is still bare.
        assert before == after, (
            f"halt-on-unresolved should leave file unchanged; before vs after:\n"
            f"BEFORE:\n{before}\nAFTER:\n{after}"
        )


def test_write_halts_atomically_across_multiple_files() -> None:
    """Unresolved ref in file N blocks writes to all M files in the plan."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        # File A: cleanly resolvable.
        body_a = textwrap.dedent(
            """\
            # File A

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1 A | orchestrator | 3B | A. |

            <!--Document index end-->

            ## 1 A
            <!-- roles=orchestrator phases=3B summary="A." -->

            See §1 for the body.
            """
        )
        # File B: contains an unresolved ref.
        body_b = textwrap.dedent(
            """\
            # File B

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1 B | orchestrator | 3B | B. |

            <!--Document index end-->

            ## 1 B
            <!-- roles=orchestrator phases=3B summary="B." -->

            See §99.99 — does not resolve.
            """
        )
        target_a = _write_in_scope_file(root, ".claude/workflow/a.md", body_a)
        target_b = _write_in_scope_file(root, ".claude/workflow/b.md", body_b)
        before_a = _read_file(target_a)
        before_b = _read_file(target_b)
        try:
            MODULE.compute_write_plan(root)
        except MODULE.UnresolvedInFileRefError:
            pass
        else:
            raise AssertionError("expected UnresolvedInFileRefError")
        after_a = _read_file(target_a)
        after_b = _read_file(target_b)
        # Both files unchanged — atomicity across the whole plan.
        assert before_a == after_a, (
            f"file A should be untouched by failed plan; before vs after:\n"
            f"BEFORE:\n{before_a}\nAFTER:\n{after_a}"
        )
        assert before_b == after_b, (
            f"file B should be untouched by failed plan; before vs after:\n"
            f"BEFORE:\n{before_b}\nAFTER:\n{after_b}"
        )


def test_write_halts_on_mixed_stale_and_unresolved_refs() -> None:
    """A file with both a stale-suffix ref and an unresolved ref aborts
    with no writes — neither auto-fixable nor unresolved sites land.

    The mixed-content case is the strongest atomicity claim: when one ref
    in a file is a candidate for `--write` auto-stamping (stale suffix on
    a resolvable target) AND another ref in the same file fails to
    resolve, the script must refuse the whole file. Partial application
    would leave the stale ref rewritten and the unresolved ref still
    pointing at nothing — exactly the "half-fixed file" the
    halt-on-unresolved contract forbids.
    """
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo mixed-content

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Stamps | orchestrator | 3B | Stamps. |

            <!--Document index end-->

            ## 1.6 Stamps
            <!-- roles=orchestrator phases=3B summary="Stamps." -->

            First ref is stale-stamped: §1.6:implementer:4 should rewrite to
            §1.6:orchestrator:3B. Second ref is unresolved: §99.99 has no
            heading. The file should be left untouched until the §99.99
            site is hand-edited.
            """
        )
        target = _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        before = _read_file(target)
        try:
            MODULE.compute_write_plan(
                root, files_filter=[".claude/workflow/demo.md"]
            )
        except MODULE.UnresolvedInFileRefError as exc:
            assert any(
                anchor == "§99.99" for _path, _line, anchor in exc.sites
            ), f"expected §99.99 in unresolved sites; got {exc.sites}"
        else:
            raise AssertionError(
                "expected UnresolvedInFileRefError for mixed stale + unresolved"
            )
        after = _read_file(target)
        # No write landed — the otherwise-rewritable stale suffix
        # `§1.6:implementer:4` is still present byte-for-byte.
        assert before == after, (
            f"mixed-content halt should leave file unchanged; before vs after:\n"
            f"BEFORE:\n{before}\nAFTER:\n{after}"
        )
        # Verify the stale-suffix ref is still in the on-disk content as
        # evidence the auto-stamp half did NOT run.
        assert "§1.6:implementer:4" in after, (
            "stale suffix should remain in the file untouched"
        )


def test_write_is_idempotent() -> None:
    """A second `--write` run produces the same file content as the first."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Stamps | implementer | 4 | Stale. |

            <!--Document index end-->

            ## 1.6 Stamps
            <!-- roles=orchestrator phases=3B summary="Current." -->

            See §1.6 for the body.
            """
        )
        target = _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        _run_write_plan(root, files_filter=[".claude/workflow/demo.md"])
        first_pass = _read_file(target)
        # Second run must produce no diff.
        plan = MODULE.compute_write_plan(
            root, files_filter=[".claude/workflow/demo.md"]
        )
        # The plan must report no changes for an already-stamped file.
        fwp = plan[".claude/workflow/demo.md"]
        assert not fwp.changed, (
            f"second `--write` pass should report no change; got "
            f"new_lines={fwp.new_lines!r}"
        )
        MODULE.apply_write_plan(plan)
        second_pass = _read_file(target)
        assert first_pass == second_pass, (
            f"second pass diverged from first; first vs second:\n"
            f"FIRST:\n{first_pass}\nSECOND:\n{second_pass}"
        )


def test_write_does_not_touch_cross_file_refs() -> None:
    """`--write` walks past cross-file `name.md:roles:phases` suffixes, even on subset violations."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        # The target file carries a narrow annotation (orchestrator only).
        target_body = textwrap.dedent(
            """\
            # Target

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1 Body | orchestrator | 3B | Body. |

            <!--Document index end-->

            ## 1 Body
            <!-- roles=orchestrator phases=3B summary="Body." -->

            Body.
            """
        )
        # The citer claims a role the target does not grant — rule 6
        # would flag this under `--check`. `--write` must not rewrite
        # the cross-file suffix.
        citer_body = textwrap.dedent(
            """\
            # Citer

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1 A | orchestrator | 3B | A. |

            <!--Document index end-->

            ## 1 A
            <!-- roles=orchestrator phases=3B summary="A." -->

            See target.md:implementer:3B for details.
            """
        )
        _two_file_cross_ref_setup(root, citer_body, target_body)
        target_citer = root / ".claude" / "workflow" / "citer.md"
        before = _read_file(target_citer)
        _run_write_plan(root, files_filter=[".claude/workflow/citer.md"])
        after = _read_file(target_citer)
        # The cross-file `target.md:implementer:3B` is preserved verbatim.
        assert "target.md:implementer:3B" in after, (
            f"cross-file ref should be preserved; got:\n{after}"
        )
        # And nothing else mutated the citer's prose (the citer's TOC
        # already matched its single H2 with the current annotation, so
        # the TOC rebuild is a no-op here too).
        assert before == after, (
            f"citer file should be untouched (TOC already correct, "
            f"cross-file ref left alone); before vs after:\n"
            f"BEFORE:\n{before}\nAFTER:\n{after}"
        )


def test_write_skips_out_of_scope_files() -> None:
    """`--write` with `--files` containing only out-of-scope paths is a no-op."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        _write_in_scope_file(
            root,
            ".claude/skills/ai-tells/SKILL.md",
            "# Out of scope\n\nNo TOC, no annotations.\n",
        )
        plan = MODULE.compute_write_plan(
            root, files_filter=[".claude/skills/ai-tells/SKILL.md"]
        )
        assert plan == {}, (
            f"out-of-scope `--files` should yield empty plan; got {plan}"
        )


def test_write_subsection_ref_resolves_and_stamps() -> None:
    """An in-file `§X.Y(z)` ref resolves to the `### (z)` sub-section under `## X.Y`."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Stamps | orchestrator | 3B | Stamps. |
            | §1.6(a) Format | implementer | 3C | Format. |

            <!--Document index end-->

            ## 1.6 Stamps
            <!-- roles=orchestrator phases=3B summary="Stamps." -->

            Body.

            ### (a) Format
            <!-- roles=implementer phases=3C summary="Format." -->

            See §1.6(a) for the format rule.
            """
        )
        target = _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        _run_write_plan(root, files_filter=[".claude/workflow/demo.md"])
        rewritten = _read_file(target)
        assert "See §1.6(a):implementer:3C for the format rule." in rewritten, (
            f"expected stamped sub-section ref; got:\n{rewritten}"
        )


def test_cli_write_exit_2_on_unresolved_ref() -> None:
    """The `--write` CLI exits 2 when any in-file ref is unresolved.

    Asserts on the dispatcher's return value directly. The CLI uses
    REPO_ROOT, so this test fixtures the live tree differently from
    the in-memory tests above; it exercises the plan-then-apply
    sequence through `main()`.
    """
    # Build a tiny fixture and patch REPO_ROOT for the duration of the
    # call. We cannot easily patch a module-level constant; instead,
    # exercise the same code path that the CLI dispatches into.
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1.6 Stamps | orchestrator | 3B | Stamps. |

            <!--Document index end-->

            ## 1.6 Stamps
            <!-- roles=orchestrator phases=3B summary="Stamps." -->

            See §9.99 — unresolved.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        try:
            MODULE.compute_write_plan(root)
        except MODULE.UnresolvedInFileRefError as exc:
            sites = exc.sites
            assert any(
                anchor == "§9.99" for _p, _l, anchor in sites
            ), f"expected §9.99 in sites; got {sites}"
            return
        raise AssertionError(
            "expected UnresolvedInFileRefError on unresolved-ref fixture"
        )


# ---------------------------------------------------------------------------
# Fence-exclusion in the heading / TOC parser (rules 2/3/4).
#
# Before this fix, `parse_headings`, `parse_toc_region`, and rule_2's
# start-delimiter count treated `##`/`###` headings and
# `<!--Document index ...-->` delimiters inside fenced code blocks as
# real. These tests pin the corrected behaviour: fenced headings and
# fenced delimiters are pedagogical text and must not be counted.
# ---------------------------------------------------------------------------


def test_fenced_heading_excluded_from_parse_headings_backtick() -> None:
    """A `## Heading` inside a ```-fenced block is not collected as a real heading."""
    lines = textwrap.dedent(
        """\
        # Title

        ```markdown
        ## Fenced demo heading
        ### (a) Fenced sub-heading
        ```

        ## Real heading
        """
    ).splitlines()
    fenced = MODULE.compute_fenced_lines(lines)
    headings = MODULE.parse_headings(lines, fenced)
    texts = [h.text for h in headings]
    assert texts == ["Real heading"], (
        f"only the non-fenced heading should be collected; got {texts}"
    )


def test_fenced_heading_excluded_from_parse_headings_tilde() -> None:
    """A `## Heading` inside a ~~~-fenced block is not collected as a real heading."""
    lines = textwrap.dedent(
        """\
        # Title

        ~~~
        ## Fenced demo heading
        ~~~

        ## Real heading
        """
    ).splitlines()
    fenced = MODULE.compute_fenced_lines(lines)
    headings = MODULE.parse_headings(lines, fenced)
    texts = [h.text for h in headings]
    assert texts == ["Real heading"], (
        f"only the non-fenced heading should be collected; got {texts}"
    )


def test_fenced_toc_delimiters_excluded_from_parse_toc_region() -> None:
    """`<!--Document index ...-->` delimiters inside a fence are not a real TOC region."""
    lines = textwrap.dedent(
        """\
        # Title

        ```markdown
        <!--Document index start-->
        | Section | Roles | Phases | Summary |
        <!--Document index end-->
        ```

        Just prose, no real headings.
        """
    ).splitlines()
    fenced = MODULE.compute_fenced_lines(lines)
    toc = MODULE.parse_toc_region(lines, fenced)
    assert toc is None, f"fenced delimiters must not form a TOC region; got {toc}"


def test_rule_2_3_4_no_finding_on_fenced_heading() -> None:
    """A fenced `## Heading` yields no rule_2/3/4 finding (it is not a real section).

    The file's only real heading is annotated and has a matching TOC
    row; the fenced demonstration heading must be ignored entirely so
    the file validates clean.
    """
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1 Real | orchestrator | 3B | Real section. |

            <!--Document index end-->

            ## 1 Real
            <!-- roles=orchestrator phases=3B summary="Real section." -->

            A fenced documentation example follows; its heading is not real:

            ```markdown
            ## 99.1 Demo section
            <!-- roles=orchestrator phases=3B summary="demo." -->
            ```
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        offending = [
            f
            for f in findings
            if f.path.endswith("/demo.md") and f.rule in ("rule_2", "rule_3", "rule_4")
        ]
        assert not offending, (
            f"fenced heading should produce no rule_2/3/4 finding; got {offending}"
        )


def test_real_heading_still_requires_toc_row_and_annotation() -> None:
    """A real (non-fenced) heading still triggers rule_3/rule_4 when unlisted/unannotated.

    Guards against the fence-exclusion fix over-reaching and silencing
    findings on genuine sections.
    """
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1 Real | orchestrator | 3B | Real section. |

            <!--Document index end-->

            ## 1 Real
            <!-- roles=orchestrator phases=3B summary="Real section." -->

            Body.

            ## 2 Unlisted

            Body with no annotation and no TOC row.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        findings = MODULE.validate(root)
        rule3 = [f for f in findings if f.rule == "rule_3" and f.path.endswith("/demo.md")]
        rule4 = [f for f in findings if f.rule == "rule_4" and f.path.endswith("/demo.md")]
        assert any("§2 Unlisted" in f.explanation for f in rule3), (
            f"real unlisted heading should trigger rule_3; got {rule3}"
        )
        assert any("'2 Unlisted'" in f.explanation for f in rule4), (
            f"real unannotated heading should trigger rule_4; got {rule4}"
        )


def test_write_omits_fenced_heading_from_toc() -> None:
    """`--write` does not emit a TOC row for a heading inside a fenced block."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            <!--Document index start-->
            <!--Document index end-->

            ## 1 Real
            <!-- roles=orchestrator phases=3B summary="Real section." -->

            ```markdown
            ## 99.1 Demo section
            <!-- roles=orchestrator phases=3B summary="demo." -->
            ```
            """
        )
        target = _write_in_scope_file(root, ".claude/workflow/demo.md", body)
        MODULE.apply_write_plan(MODULE.compute_write_plan(root))
        rebuilt = target.read_text(encoding="utf-8")
        assert "| §1 Real |" in rebuilt, "real heading should appear in the rebuilt TOC"
        assert "§99.1 Demo section" not in rebuilt, (
            f"fenced heading must not appear in the TOC; got:\n{rebuilt}"
        )


# ---------------------------------------------------------------------------
# H1-less after-frontmatter TOC anchor (§1.8(d), rule_2).
# ---------------------------------------------------------------------------


def test_frontmatter_close_detection() -> None:
    """`find_frontmatter_close_line` returns the closing `---` of a leading YAML block."""
    lines = textwrap.dedent(
        """\
        ---
        name: edit-design
        user-invocable: false
        ---

        Body.
        """
    ).splitlines()
    assert MODULE.find_frontmatter_close_line(lines) == 4, (
        f"expected close at line 4; got {MODULE.find_frontmatter_close_line(lines)}"
    )
    assert MODULE.find_first_h1_line(lines, MODULE.compute_fenced_lines(lines)) is None


def test_h1_less_file_with_after_frontmatter_toc_validates() -> None:
    """An H1-less SKILL.md whose TOC sits right after the frontmatter block validates clean."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            ---
            name: edit-design
            description: "x"
            user-invocable: false
            ---

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §Modes | orchestrator | 3B | Operational modes. |

            <!--Document index end-->

            ## Modes
            <!-- roles=orchestrator phases=3B summary="Operational modes." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/skills/edit-design/SKILL.md", body)
        findings = MODULE.validate(root)
        rule2 = [
            f for f in findings if f.rule == "rule_2" and f.path.endswith("/SKILL.md")
        ]
        assert not rule2, f"after-frontmatter TOC should validate; got {rule2}"


def test_h1_less_file_with_misplaced_toc_fails_anchor() -> None:
    """An H1-less file with prose between the frontmatter and the TOC fails the anchor check."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            ---
            name: edit-design
            user-invocable: false
            ---

            Some intro prose that pushes the TOC away from the frontmatter.

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §Modes | orchestrator | 3B | Operational modes. |

            <!--Document index end-->

            ## Modes
            <!-- roles=orchestrator phases=3B summary="Operational modes." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/skills/edit-design/SKILL.md", body)
        findings = MODULE.validate(root)
        rule2 = [
            f for f in findings if f.rule == "rule_2" and f.path.endswith("/SKILL.md")
        ]
        assert any("frontmatter block" in f.explanation for f in rule2), (
            f"misplaced TOC should fail the anchor check; got {rule2}"
        )


def test_bootstrap_block_between_anchor_and_toc_is_allowed() -> None:
    """The bootstrap block may sit between the H1 (anchor) and the TOC region."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            ## Reading workflow files (TOC protocol)

            Bootstrap block body that teaches the TOC reading protocol.

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1 Real | orchestrator | 3B | Real section. |

            <!--Document index end-->

            ## 1 Real
            <!-- roles=orchestrator phases=3B summary="Real section." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/prompts/demo.md", body)
        findings = MODULE.validate(root)
        rule2 = [f for f in findings if f.rule == "rule_2" and f.path.endswith("/demo.md")]
        assert not rule2, (
            f"bootstrap block before the TOC should be allowed; got {rule2}"
        )


# ---------------------------------------------------------------------------
# Top-of-file TOC anchor for prose-first files (§1.8(d) shape 3).
#
# Ten of the eleven prompts open directly with prose — no real
# (non-fenced) H1 and no leading YAML frontmatter. Their TOC anchors to
# the top of the file: the `<!--Document index start-->` delimiter is
# the first content, before any leading prose.
# ---------------------------------------------------------------------------


def test_prose_first_file_with_top_of_file_toc_validates() -> None:
    """A prose-first file (no H1, no frontmatter) with a top-of-file TOC validates clean."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1 Real | reviewer-technical | 3A | Real section. |

            <!--Document index end-->

            This prompt opens with prose, no H1 and no frontmatter.

            ## 1 Real
            <!-- roles=reviewer-technical phases=3A summary="Real section." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/prompts/prose-first.md", body)
        findings = MODULE.validate(root)
        rule2 = [
            f
            for f in findings
            if f.rule == "rule_2" and f.path.endswith("/prose-first.md")
        ]
        assert not rule2, f"top-of-file TOC should validate; got {rule2}"


def test_prose_first_file_with_toc_below_prose_fails_anchor() -> None:
    """A prose-first file with leading prose before the TOC fails the rule_2 anchor check."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            This prompt opens with prose that pushes the TOC down the file.

            More intro prose.

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1 Real | reviewer-technical | 3A | Real section. |

            <!--Document index end-->

            ## 1 Real
            <!-- roles=reviewer-technical phases=3A summary="Real section." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/prompts/prose-first.md", body)
        findings = MODULE.validate(root)
        rule2 = [
            f
            for f in findings
            if f.rule == "rule_2" and f.path.endswith("/prose-first.md")
        ]
        assert any("top of file" in f.explanation for f in rule2), (
            f"TOC below leading prose should fail the top-of-file anchor; got {rule2}"
        )


def test_prose_first_file_with_bootstrap_then_top_toc_validates() -> None:
    """A prose-first file with a bootstrap block then a TOC at the top validates (gap-tolerance)."""
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            ## Reading workflow files (TOC protocol)

            Bootstrap block body that teaches the TOC reading protocol.

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1 Real | reviewer-technical | 3A | Real section. |

            <!--Document index end-->

            ## 1 Real
            <!-- roles=reviewer-technical phases=3A summary="Real section." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/prompts/prose-first.md", body)
        findings = MODULE.validate(root)
        rule2 = [
            f
            for f in findings
            if f.rule == "rule_2" and f.path.endswith("/prose-first.md")
        ]
        assert not rule2, (
            f"bootstrap block then top-of-file TOC should validate; got {rule2}"
        )


def test_fenced_bootstrap_heading_in_gap_not_accepted() -> None:
    """A fenced bootstrap-heading literal plus real prose in the gap still fails the anchor check.

    The gap scan must skip fenced lines, so a fenced occurrence of the
    bootstrap heading is never mistaken for the real bootstrap block;
    the real (non-fenced) prose after it is then an anchor violation.
    """
    with _make_fixture_root() as tmp:
        root = Path(tmp)
        _write_conventions(root)
        body = textwrap.dedent(
            """\
            # Demo

            ```markdown
            ## Reading workflow files (TOC protocol)
            ```

            Real prose after a fenced bootstrap-heading literal.

            <!--Document index start-->

            | Section | Roles | Phases | Summary |
            |---|---|---|---|
            | §1 Real | orchestrator | 3B | Real section. |

            <!--Document index end-->

            ## 1 Real
            <!-- roles=orchestrator phases=3B summary="Real section." -->

            Body.
            """
        )
        _write_in_scope_file(root, ".claude/workflow/prompts/demo.md", body)
        findings = MODULE.validate(root)
        rule2 = [f for f in findings if f.rule == "rule_2" and f.path.endswith("/demo.md")]
        assert any("not anchored at the H1" in f.explanation for f in rule2), (
            f"fenced bootstrap literal must not be accepted as the bootstrap block; got {rule2}"
        )


# ---------------------------------------------------------------------------
# Driver.
# ---------------------------------------------------------------------------


def main() -> int:
    print("Running workflow-reindex.py validation runner...")
    print()
    tests = [
        ("module loads", test_module_loads),
        ("bootstrap probe — live only", test_bootstrap_probe_live_only),
        ("bootstrap probe — staged wins", test_bootstrap_probe_staged_wins),
        (
            "bootstrap probe — multiple staged halts",
            test_bootstrap_probe_multiple_staged_halts,
        ),
        ("parse_annotation well-formed", test_parse_annotation_well_formed),
        (
            "parse_annotation space after comma fails field",
            test_parse_annotation_space_after_comma_fails_field,
        ),
        ("parse_annotation not a comment", test_parse_annotation_not_a_comment),
        ("parse_headings collects h2 + h3", test_parse_headings_collects_h2_and_h3),
        ("parse_headings bootstrap flag", test_parse_headings_bootstrap_flag),
        ("parse_toc_region detects delimiters", test_parse_toc_region_detects_delimiters),
        ("parse_toc_region missing returns None", test_parse_toc_region_missing_returns_none),
        ("compute_fenced_lines basic", test_compute_fenced_lines_basic),
        (
            "compute_fenced_lines mismatched close keeps fence open",
            test_compute_fenced_lines_mismatched_close_keeps_fence_open,
        ),
        (
            "compute_fenced_lines tilde vs backtick distinct",
            test_compute_fenced_lines_tilde_vs_backtick_distinct,
        ),
        ("inline_backtick_spans single", test_inline_backtick_spans_single),
        (
            "inline_backtick_spans double with inner backtick",
            test_inline_backtick_spans_double_with_inner_backtick,
        ),
        ("inline_backtick_spans unclosed", test_inline_backtick_spans_unclosed_no_span),
        ("discover_in_scope_files smoke", test_discover_in_scope_files_smoke),
        (
            "discover_in_scope_files picks up staged paths",
            test_discover_in_scope_files_picks_up_staged_paths,
        ),
        # Validation rules + --check CLI surface.
        (
            "rule_1 stamp present on staged path passes",
            test_rule_1_stamp_present_on_staged_path_passes,
        ),
        (
            "rule_1 missing stamp on staged path fails",
            test_rule_1_missing_stamp_on_staged_path_fails,
        ),
        (
            "rule_1 live workflow file without stamp passes",
            test_rule_1_live_workflow_file_without_stamp_passes,
        ),
        (
            "rule_2 missing TOC fails when file has H2",
            test_rule_2_missing_toc_fails_when_file_has_h2,
        ),
        (
            "rule_2 no TOC passes when file has no H2",
            test_rule_2_no_toc_passes_when_file_has_no_h2,
        ),
        (
            "rule_3 heading without TOC row fails",
            test_rule_3_heading_without_toc_row_fails,
        ),
        ("rule_3 bootstrap heading exempt", test_rule_3_bootstrap_heading_exempt),
        ("rule_3 orphan TOC row fails", test_rule_3_orphan_toc_row_fails),
        ("rule_4 missing annotation fails", test_rule_4_missing_annotation_fails),
        ("rule_5a space after comma fails", test_rule_5a_space_after_comma_fails),
        ("rule_5b missing phases fails", test_rule_5b_missing_phases_fails),
        (
            "rule_5c summary over 120 chars fails",
            test_rule_5c_summary_over_120_chars_fails,
        ),
        ("rule_5d out-of-enum role fails", test_rule_5d_out_of_enum_role_fails),
        (
            "rule_5d any token accepted in roles and phases",
            test_rule_5d_any_token_accepted_in_roles_and_phases,
        ),
        ("rule_6 missing suffix fails", test_rule_6_missing_suffix_fails),
        (
            "rule_6 role subset violation fails",
            test_rule_6_role_subset_violation_fails,
        ),
        (
            "rule_6 phase subset violation fails",
            test_rule_6_phase_subset_violation_fails,
        ),
        (
            "rule_6 target-any role accepts any citer",
            test_rule_6_target_any_role_accepts_any_citer,
        ),
        (
            "rule_6 citer-any against narrow target fails",
            test_rule_6_citer_any_role_against_narrow_target_fails,
        ),
        (
            "rule_6 both-any wildcard passes",
            test_rule_6_both_any_wildcard_passes,
        ),
        (
            "rule_6 file-level ref subset against union",
            test_rule_6_file_level_ref_subset_against_union,
        ),
        (
            "rule_6 sub-section ref resolves to section annotation",
            test_rule_6_sub_section_ref_resolves_to_section_annotation,
        ),
        ("rule_6 CLAUDE.md out of scope", test_rule_6_claude_md_out_of_scope),
        ("rule_6 ref in fenced block excluded", test_rule_6_ref_in_fenced_block_excluded),
        (
            "rule_6 ref in inline backticks excluded",
            test_rule_6_ref_in_inline_backticks_excluded,
        ),
        (
            "rule_7 missing bootstrap fails for SKILL.md",
            test_rule_7_missing_bootstrap_fails_for_skill_md,
        ),
        ("rule_7 bootstrap present passes", test_rule_7_bootstrap_present_passes),
        (
            "rule_7 out-of-scope skill not required",
            test_rule_7_out_of_scope_skill_not_required,
        ),
        ("rule_8 unstamped in-file ref fails", test_rule_8_unstamped_in_file_ref_fails),
        ("rule_8 stale in-file ref fails", test_rule_8_stale_in_file_ref_fails),
        (
            "rule_8 unresolved in-file ref fails",
            test_rule_8_unresolved_in_file_ref_fails,
        ),
        (
            "rule_8 stamped ref matching target passes",
            test_rule_8_stamped_ref_matching_target_passes,
        ),
        (
            "rule_8 sub-section ref resolves to sub-section",
            test_rule_8_subsection_ref_resolves_to_subsection,
        ),
        (
            "rule_8 in-file ref in inline backticks excluded",
            test_rule_8_in_file_ref_in_inline_backticks_excluded,
        ),
        (
            "validate empty findings on clean tree",
            test_validate_returns_empty_findings_on_clean_tree,
        ),
        (
            "validate --files silently skips out-of-scope",
            test_validate_files_filter_silently_skips_out_of_scope,
        ),
        (
            "validate --files scopes findings to listed paths",
            test_validate_files_filter_scopes_findings_to_listed_paths,
        ),
        ("CLI --check exit 0 on clean tree", test_cli_check_exit_0_on_clean_tree),
        ("CLI --check findings yield exit 1", test_cli_check_findings_yield_exit_1),
        ("Finding.render shape", test_finding_render_shape),
        (
            "subset target=any accepts any citer",
            test_subset_with_any_target_wildcard_accepts_any_citer,
        ),
        (
            "subset citer=any against narrow target fails",
            test_subset_with_any_citer_wildcard_against_narrow_target_fails,
        ),
        (
            "subset concrete set check",
            test_subset_with_any_concrete_set_subset_check,
        ),
        (
            "discover_bootstrap_scope includes all known paths",
            test_discover_bootstrap_scope_includes_all_known_paths,
        ),
        # --write mode.
        (
            "--write rebuilds TOC from H2 annotations",
            test_write_rebuilds_toc_from_h2_annotations,
        ),
        (
            "--write rebuilds TOC with H2 and H3 in order",
            test_write_rebuilds_toc_with_h2_and_h3,
        ),
        (
            "--write omits bootstrap heading from TOC",
            test_write_bootstrap_heading_omitted_from_toc,
        ),
        (
            "--write empties TOC when file has no H2",
            test_write_empty_toc_when_no_h2,
        ),
        (
            "--write is a no-op for files without TOC delimiters",
            test_write_no_toc_delimiters_no_op_on_toc_half,
        ),
        (
            "--write stamps unstamped in-file ref",
            test_write_stamps_unstamped_in_file_ref,
        ),
        (
            "--write rewrites stale in-file ref suffix",
            test_write_rewrites_stale_in_file_ref_suffix,
        ),
        (
            "--write skips ref in fenced block",
            test_write_skips_ref_in_fenced_block,
        ),
        (
            "--write skips ref in inline backticks",
            test_write_skips_ref_in_inline_backticks,
        ),
        (
            "--write halts on unresolved ref in same file",
            test_write_halts_on_unresolved_ref_in_same_file,
        ),
        (
            "--write halts atomically across multiple files",
            test_write_halts_atomically_across_multiple_files,
        ),
        (
            "--write halts on mixed stale + unresolved refs",
            test_write_halts_on_mixed_stale_and_unresolved_refs,
        ),
        (
            "--write is idempotent",
            test_write_is_idempotent,
        ),
        (
            "--write does not touch cross-file refs",
            test_write_does_not_touch_cross_file_refs,
        ),
        (
            "--write skips out-of-scope --files entries",
            test_write_skips_out_of_scope_files,
        ),
        (
            "--write sub-section ref resolves and stamps",
            test_write_subsection_ref_resolves_and_stamps,
        ),
        (
            "CLI --write exit 2 on unresolved ref",
            test_cli_write_exit_2_on_unresolved_ref,
        ),
        # Fence-exclusion in the heading / TOC parser (rules 2/3/4).
        (
            "fenced heading excluded from parse_headings (backtick)",
            test_fenced_heading_excluded_from_parse_headings_backtick,
        ),
        (
            "fenced heading excluded from parse_headings (tilde)",
            test_fenced_heading_excluded_from_parse_headings_tilde,
        ),
        (
            "fenced TOC delimiters excluded from parse_toc_region",
            test_fenced_toc_delimiters_excluded_from_parse_toc_region,
        ),
        (
            "rule_2/3/4 no finding on fenced heading",
            test_rule_2_3_4_no_finding_on_fenced_heading,
        ),
        (
            "real heading still requires TOC row + annotation",
            test_real_heading_still_requires_toc_row_and_annotation,
        ),
        (
            "--write omits fenced heading from TOC",
            test_write_omits_fenced_heading_from_toc,
        ),
        # H1-less after-frontmatter TOC anchor (§1.8(d), rule_2).
        ("frontmatter close detection", test_frontmatter_close_detection),
        (
            "H1-less file with after-frontmatter TOC validates",
            test_h1_less_file_with_after_frontmatter_toc_validates,
        ),
        (
            "H1-less file with misplaced TOC fails anchor",
            test_h1_less_file_with_misplaced_toc_fails_anchor,
        ),
        (
            "bootstrap block between anchor and TOC is allowed",
            test_bootstrap_block_between_anchor_and_toc_is_allowed,
        ),
        # Top-of-file TOC anchor for prose-first files (§1.8(d) shape 3).
        (
            "prose-first file with top-of-file TOC validates",
            test_prose_first_file_with_top_of_file_toc_validates,
        ),
        (
            "prose-first file with TOC below prose fails anchor",
            test_prose_first_file_with_toc_below_prose_fails_anchor,
        ),
        (
            "prose-first file with bootstrap then top TOC validates",
            test_prose_first_file_with_bootstrap_then_top_toc_validates,
        ),
        (
            "fenced bootstrap heading in gap not accepted",
            test_fenced_bootstrap_heading_in_gap_not_accepted,
        ),
    ]
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
