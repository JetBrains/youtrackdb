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
        # Validation rules + --check CLI surface.
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
