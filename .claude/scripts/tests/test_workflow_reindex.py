#!/usr/bin/env python3
"""Validation runner for `.claude/scripts/workflow-reindex.py`.

Running this script is the validation: it imports the script as a
module and exercises the staged-aware §1.8 probe plus the parsing and
fence/inline-backtick state machine against fixture inputs.

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

Test infrastructure exposed here (helpers + temp-tree builder) is
extended by follow-up commits that add the validation-rule tests and
the `--write` test matrix; the current file covers only the
script-core smoke and the staged-aware §1.8 probe.
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
