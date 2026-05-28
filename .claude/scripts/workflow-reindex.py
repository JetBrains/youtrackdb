#!/usr/bin/env python3
"""Mechanical reindex + validation tool for workflow-doc TOC regions.

This script is the schema validator and TOC rebuilder for the
per-section role/phase annotation system defined in
`.claude/workflow/conventions.md §1.8`. It runs in two modes:

- `--check` — validate every in-scope workflow doc against the schema
  and exit nonzero on findings. Used by the pre-commit hook and CI.
- `--write` — rebuild every TOC region in place from the current
  annotations and auto-stamp every in-file `§X.Y(z)` reference with
  the target heading's roles/phases suffix. The `--write` surface is
  added by the next commit; this commit lands `--check`.

Both modes share file enumeration, heading/annotation parsing, TOC
region detection, and the CommonMark fence + inline-backtick state
machine that excludes pedagogical refs inside code spans from
validation and rewriting.

Validation rules. The eight rules per design.md §"Reindex script" →
§"Validation rules":

1. Stamp present on line 1 (`<!-- workflow-sha: <40-char SHA> -->`).
2. Exactly one TOC region under H1 (empty TOC accepted for files
   without `^## ` headings).
3. TOC matches annotations: every `^## ` and `^### ` heading has a
   TOC row (bootstrap-block heading exempt by literal-text match).
4. Annotation present after every `^## ` and `^### ` heading (same
   bootstrap exemption).
5. Annotation fields well-formed: `roles=`, `phases=`,
   `summary="..."` ≤120 chars, no spaces around commas, every token
   drawn from the bootstrap output (15 roles + 8 phases including
   the `any` wildcard).
6. Cross-file `name.md[§X.Y(z)]:roles:phases` refs carry the suffix
   and the citer's slice is a subset of the target's annotation per
   §1.8(e). `any`-wildcard semantics: `target.roles={any}` matches
   any citer role; `citer.roles={any}` requires `target.roles={any}`.
   Refs inside fenced code blocks and inline backtick spans are
   excluded.
7. Bootstrap block present on the 38 in-scope system prompts (7
   SKILL.md, 11 prompts, 20 agents) — literal heading match
   `## Reading workflow files (TOC protocol)`.
8. In-file `§X.Y` / `§X.Y(z)` refs auto-stamped with the target
   heading's roles/phases suffix; unstamped, stale, and unresolved
   refs all fail. Same fenced + inline-backtick exclusion as rule 6.

Bootstrap path. The role and phase enums are not hard-coded in this
script — they live in `.claude/workflow/conventions.md §1.8`. The
script discovers the §1.8 source at startup:

1. Enumerate `docs/adr/*/_workflow/staged-workflow/.claude/workflow/conventions.md`.
   Multiple matches halt with exit 2 (ambiguous bootstrap probe).
   A single match wins per `conventions.md §1.7(d)` reads-precedence.
2. Otherwise fall back to live `.claude/workflow/conventions.md`.

This staged-aware probe extends the §1.7(d) reads-precedence rule to
script invocations — without it, the script would always read the
live file and miss the schema changes that a workflow-modifying
branch has staged but not yet promoted.

CLI surface. `--check` runs every applicable rule against the
in-scope file set (or a subset via `--files`) and exits with code
0 (clean), 1 (findings), or 2 (script error or ambiguous bootstrap
probe). `--files <paths>` scopes validation to the listed files —
out-of-scope paths are silently skipped per design.md §"Reindex
script" → §"Validation rules" (the pre-commit hook's regex is
broader than the in-scope glob, so this skip is load-bearing for
mixed-content commits). `--write` is added by the next commit.
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, FrozenSet, Iterable, List, Optional, Sequence, Tuple


# ---------------------------------------------------------------------------
# Repo-root resolution.
# ---------------------------------------------------------------------------
#
# The script may be invoked from any cwd. The repo root is two levels up
# from this file (`<repo>/.claude/scripts/workflow-reindex.py`). The tests
# inject a different root via the `repo_root` parameter on the public
# functions, so the constant below is only the convenience default for
# the CLI stub.
REPO_ROOT = Path(__file__).resolve().parents[2]


# ---------------------------------------------------------------------------
# In-scope globs (design.md §"Reindex script" → §"Discovery mechanism").
#
# The list is hard-coded — a manifest file would drift from the actual file
# set. The seven SKILL.md anchors are spelled out individually so adding
# a new in-scope skill is a single-line edit and the script does not
# accidentally pull in skills outside the workflow surface.
# ---------------------------------------------------------------------------
IN_SCOPE_GLOBS: Tuple[str, ...] = (
    ".claude/workflow/**/*.md",
    ".claude/workflow/prompts/**/*.md",
    ".claude/skills/create-plan/SKILL.md",
    ".claude/skills/execute-tracks/SKILL.md",
    ".claude/skills/edit-design/SKILL.md",
    ".claude/skills/migrate-workflow/SKILL.md",
    ".claude/skills/review-plan/SKILL.md",
    ".claude/skills/review-workflow-pr/SKILL.md",
    ".claude/skills/code-review/SKILL.md",
)


# ---------------------------------------------------------------------------
# TOC region delimiters (conventions.md §1.8(d)).
# ---------------------------------------------------------------------------
TOC_START_DELIMITER = "<!--Document index start-->"
TOC_END_DELIMITER = "<!--Document index end-->"


# ---------------------------------------------------------------------------
# Heading and annotation regexes.
#
# `^## ` and `^### ` headings carry an annotation comment on the next
# line. The annotation comment matches the full `<!-- roles=... phases=...
# summary="..." -->` shape; field well-formedness is enforced by the
# validation-rule pass added by a follow-up commit, not by this regex
# (a malformed comment still parses as "annotation present" so the
# field well-formedness check has something to complain about — see
# `conventions.md §1.8(c)`).
# ---------------------------------------------------------------------------
_H2_RE = re.compile(r"^## (.+?)\s*$")
_H3_RE = re.compile(r"^### (.+?)\s*$")
_ANNOTATION_RE = re.compile(r"^<!--\s*(.+?)\s*-->\s*$")


# Bootstrap-block heading exempted from rules 3 and 4
# (conventions.md §1.8(c) literal-text match).
BOOTSTRAP_BLOCK_HEADING = "## Reading workflow files (TOC protocol)"


# ---------------------------------------------------------------------------
# Bootstrap-block in-scope set (rule 7).
#
# The 38 system prompts per design.md §"Bootstrap protocol for agent system
# prompts" → §"Scope and uniformity": 7 workflow-referencing SKILL.md
# files (the same 7 spelled out in IN_SCOPE_GLOBS), every prompt under
# .claude/workflow/prompts/, and every agent file under .claude/agents/.
#
# The skill list is closed by design (the design enumerates the 7 names
# explicitly). The prompt and agent sets are discovered via directory
# walk — a new prompt or agent added to either directory automatically
# becomes in-scope for rule 7.
# ---------------------------------------------------------------------------
BOOTSTRAP_SCOPE_SKILLS: Tuple[str, ...] = (
    ".claude/skills/create-plan/SKILL.md",
    ".claude/skills/execute-tracks/SKILL.md",
    ".claude/skills/edit-design/SKILL.md",
    ".claude/skills/migrate-workflow/SKILL.md",
    ".claude/skills/review-workflow-pr/SKILL.md",
    ".claude/skills/review-plan/SKILL.md",
    ".claude/skills/code-review/SKILL.md",
)


# ---------------------------------------------------------------------------
# CommonMark fence regexes (mirrors `design-mechanical-checks.py:209-263`).
#
# Backtick fence: info string MAY NOT contain a backtick. Tilde fence:
# info string may contain anything except a newline. Closing fence must
# use the same character as the opener and be at least as long.
# ---------------------------------------------------------------------------
_BACKTICK_FENCE_RE = re.compile(r"^[ ]{0,3}(`{3,})([^`]*)$")
_TILDE_FENCE_RE = re.compile(r"^[ ]{0,3}(~{3,})(.*)$")


# ---------------------------------------------------------------------------
# Annotation comment field parser.
#
# Captures roles= and phases= comma-lists plus the double-quoted summary.
# The two list fields use a strict shape: the captured value must end
# at whitespace, end-of-string, or a closing comment marker. A value
# like `roles=orchestrator, implementer` matches the leading word
# `roles=orchestrator` BUT then the next character is `,` followed by
# a space — by anchoring on whitespace or `-->` after the comma-list,
# we reject the space-after-comma shape, which the field
# well-formedness check then surfaces. The inverse case
# `roles=orchestrator,implementer` has the legal whitespace after
# `implementer` and parses cleanly.
# ---------------------------------------------------------------------------
_ANNOTATION_ROLES_RE = re.compile(r"(?:^|\s)roles=([A-Za-z0-9_,\-]+)(?=\s|$|-->)")
_ANNOTATION_PHASES_RE = re.compile(r"(?:^|\s)phases=([A-Za-z0-9_,\-]+)(?=\s|$|-->)")
_ANNOTATION_SUMMARY_RE = re.compile(r'(?:^|\s)summary="((?:[^"\\]|\\.)*)"')


# Workflow-SHA stamp on line 1 (conventions.md §1.6). The reindex script
# re-checks for consistency per design.md §"Reindex script" → rule 1; the
# drift gate is the authoritative enforcer at session start.
_STAMP_LINE_RE = re.compile(r"^<!-- workflow-sha: [0-9a-f]{40} -->\s*$")


# Reference suffix shape: `roles:phases` where each list is comma-
# separated tokens drawn from the role / phase enums in §1.8(a) / §1.8(b).
# The token character class deliberately includes `-` (for `final-designer`
# and similar hyphenated roles); whitespace and commas terminate a token.
_REF_SUFFIX_TOKEN = r"[A-Za-z0-9_\-]+"
_REF_SUFFIX_LIST = rf"{_REF_SUFFIX_TOKEN}(?:,{_REF_SUFFIX_TOKEN})*"

# In-file reference (`§X.Y` or `§X.Y(z)`) with an optional `:roles:phases`
# stamp. The numeric anchor uses `[0-9]+` (one or more digits) and the
# sub-anchor `(z)` carries one or more alphanumeric characters. The
# trailing suffix is the auto-stamped `:roles:phases` form; absence is
# the unstamped shape rule 8 flags.
_IN_FILE_REF_RE = re.compile(
    r"§(?P<major>[0-9]+)\.(?P<minor>[0-9]+)"
    r"(?:\((?P<sub>[A-Za-z0-9]+)\))?"
    rf"(?::(?P<roles>{_REF_SUFFIX_LIST}):(?P<phases>{_REF_SUFFIX_LIST}))?"
)

# Cross-file reference (`name.md` optionally pinning a sub-section with
# `§X.Y(z)`) followed by the mandatory `:roles:phases` suffix. The path
# segment may include a `prompts/` directory prefix per §1.8(e); other
# directories are not in scope (the conventional anchor is `.claude/
# workflow/` or `.claude/skills/`).
#
# The "must-have suffix" half of rule 6 is enforced separately — refs
# matching `name.md` without a suffix are detected by a second pattern
# below so the validator can emit a "missing suffix" finding rather
# than silently skip the ref.
# Path shape for cross-file refs. The conventional anchor (§1.8(e)) is
# `.claude/workflow/` or `.claude/skills/`; refs may optionally carry a
# `prompts/` directory prefix. `CLAUDE.md` is explicitly out of scope
# per §1.8(e) and is filtered after the regex matches (the filter is
# applied in `check_rule_6_cross_file_refs` so the same exclusion
# applies to bare and stamped match shapes).
_CROSS_FILE_REF_FILE = r"(?:[A-Za-z0-9_\-]+/)?[A-Za-z0-9_\-]+\.md"

# Cross-file refs the script does NOT validate per §1.8(e):
# "`CLAUDE.md` is out of scope (general-purpose project guide, not
# workflow-specific)."
_CROSS_FILE_REF_OUT_OF_SCOPE: FrozenSet[str] = frozenset({"CLAUDE.md"})
_CROSS_FILE_REF_RE = re.compile(
    rf"(?P<file>{_CROSS_FILE_REF_FILE})"
    r"(?:§(?P<major>[0-9]+)\.(?P<minor>[0-9]+)"
    r"(?:\((?P<sub>[A-Za-z0-9]+)\))?)?"
    rf":(?P<roles>{_REF_SUFFIX_LIST}):(?P<phases>{_REF_SUFFIX_LIST})"
)
# Bare `name.md` followed by anything that is NOT a `:` — used to detect
# "missing suffix" refs that rule 6 must flag. The lookahead skips
# matches that the suffix-bearing regex above already captures.
_CROSS_FILE_REF_BARE_RE = re.compile(
    rf"(?P<file>{_CROSS_FILE_REF_FILE})"
    r"(?:§(?P<major>[0-9]+)\.(?P<minor>[0-9]+)"
    r"(?:\((?P<sub>[A-Za-z0-9]+)\))?)?"
    r"(?!:)"
)


# Sub-section heading anchor: `### (z) <name>` (the `(z)` literal must
# appear immediately after `### `). The rule 8 resolver uses this to
# find the sub-section under a `## X.Y` parent.
_SUB_SECTION_HEADING_RE = re.compile(
    r"^### \((?P<sub>[A-Za-z0-9]+)\)(?:\s|$)"
)


# Parent-section heading: `## X.Y <name>` (the `X.Y` literal must appear
# immediately after `## `). The rule 8 / rule 6-sub resolver uses this
# to find the parent of a `§X.Y(z)` anchor.
_PARENT_SECTION_HEADING_RE = re.compile(
    r"^## (?P<major>[0-9]+)\.(?P<minor>[0-9]+)(?:\s|$)"
)


# ---------------------------------------------------------------------------
# Public data model.
# ---------------------------------------------------------------------------


@dataclass
class Annotation:
    """Parsed `<!-- roles=... phases=... summary="..." -->` comment."""

    # Raw body of the comment (between `<!--` and `-->`, whitespace stripped).
    raw: str
    # 1-based line number where the annotation comment appears.
    line: int
    # Parsed fields. None when the field was absent or malformed; the rule-5
    # check distinguishes the two cases by re-inspecting `raw`.
    roles: Optional[Tuple[str, ...]] = None
    phases: Optional[Tuple[str, ...]] = None
    summary: Optional[str] = None
    # True iff `roles=`, `phases=`, and `summary=` all parsed cleanly.
    well_formed: bool = False


@dataclass
class Heading:
    """A `^## ` or `^### ` heading and its annotation comment."""

    level: int  # 2 or 3
    text: str  # heading text without the `## `/`### ` markers
    line: int  # 1-based line number of the heading itself
    annotation: Optional[Annotation] = None  # None if next line is not a comment
    # True iff this heading's literal text matches the bootstrap block (rules 3/4 exempt).
    is_bootstrap: bool = False


@dataclass
class TocRow:
    """A row inside the TOC region's Markdown table."""

    line: int  # 1-based line number of the row inside the file
    raw: str  # the raw `|...|` line, whitespace-stripped


@dataclass
class TocRegion:
    """The TOC region between the delimiter comments."""

    start_line: int  # 1-based line of `<!--Document index start-->`
    end_line: int  # 1-based line of `<!--Document index end-->`
    rows: List[TocRow] = field(default_factory=list)


@dataclass
class ParsedFile:
    """Structured representation of one in-scope file."""

    path: str  # repo-relative POSIX path
    abs_path: Path
    lines: List[str]  # newline-stripped
    headings: List[Heading] = field(default_factory=list)
    toc: Optional[TocRegion] = None
    # Per-line "in fenced code" / "in inline backtick span" flags. True iff
    # the line (or position within the line) sits inside a fenced or inline
    # code context — the cross-file and in-file ref validation rules
    # exclude such ref positions; the `--write` auto-stamp pass skips
    # them when rewriting.
    fenced_lines: List[bool] = field(default_factory=list)


@dataclass
class BootstrapEnums:
    """The role and phase enums extracted from `conventions.md §1.8`."""

    roles: Tuple[str, ...]
    phases: Tuple[str, ...]
    # Path the enums were read from (staged copy or live file). Useful for
    # error messages and test assertions.
    source: Path


class AmbiguousBootstrapProbeError(RuntimeError):
    """Raised when the §1.8 probe finds multiple staged copies.

    The CLI converts this to exit code 2; downstream tools (Steps 2/3)
    surface the same exit code through the same exception.
    """


@dataclass
class Finding:
    """One validation failure surfaced by `--check`.

    The output format is `path:line:rule_N: explanation` per design.md
    §"Reindex script" → §"Output format". `rule` is one of `rule_1`
    through `rule_8`; rule 5 sub-rules are reported with the sub-rule
    suffix (`rule_5a`, `rule_5b`, etc.) so downstream tooling can
    distinguish them.
    """

    path: str  # repo-relative POSIX path
    line: int  # 1-based line number (1 when the finding is file-level)
    rule: str  # e.g. "rule_1", "rule_5c", "rule_6", "rule_8"
    explanation: str

    def render(self) -> str:
        """Render the finding as a single `path:line:rule: explanation` line."""
        return f"{self.path}:{self.line}:{self.rule}: {self.explanation}"


# ---------------------------------------------------------------------------
# Fence + inline-backtick state machine.
# ---------------------------------------------------------------------------


def parse_code_fence(line: str) -> Optional[Tuple[str, int]]:
    """Return `(char, length)` if `line` is a fenced-code-block delimiter, else None.

    Modeled on `design-mechanical-checks.py:parse_code_fence`. The
    closing-fence check ("same character and ≥ open length") happens in
    `compute_fenced_lines` below; this helper only recognises the line
    shape.
    """
    m = _BACKTICK_FENCE_RE.match(line)
    if m is not None:
        return "`", len(m.group(1))
    m = _TILDE_FENCE_RE.match(line)
    if m is not None:
        return "~", len(m.group(1))
    return None


def compute_fenced_lines(lines: Sequence[str]) -> List[bool]:
    """Return a parallel list of bools — True iff the line is inside a fence.

    The fence-line itself (opener and closer) is also considered "inside"
    so a `§X.Y` reference written on a fence opener line (rare, but
    technically possible) is also excluded from validation. This matches
    the design's "refs inside fenced code blocks are excluded" contract
    without quibbling over the line that opens or closes the fence.
    """
    fenced: List[bool] = []
    open_fence: Optional[Tuple[str, int]] = None
    for line in lines:
        if open_fence is None:
            parsed = parse_code_fence(line)
            if parsed is not None:
                # Opening fence: mark this line inside and arm the close
                # check for subsequent lines.
                open_fence = parsed
                fenced.append(True)
            else:
                fenced.append(False)
        else:
            # Inside an open fence. Check whether this line closes it.
            fenced.append(True)
            parsed = parse_code_fence(line)
            if parsed is not None:
                char, length = parsed
                open_char, open_len = open_fence
                if char == open_char and length >= open_len:
                    open_fence = None
    return fenced


def inline_backtick_spans(line: str) -> List[Tuple[int, int]]:
    """Return `(start, end)` 0-based index pairs of inline-backtick spans.

    Inline-code rules per CommonMark: a span opens with N backticks
    (where N ≥ 1) and closes with N backticks of the same run length.
    This is the same matching-length rule as fenced blocks but at the
    inline level — `` `code` `` opens with a 1-backtick run and closes
    with a 1-backtick run; ``` ``inner `tick` outer`` ``` opens with a
    2-backtick run and closes with a 2-backtick run, letting the
    inner span contain a single backtick.

    The returned span (start, end) covers from the position of the
    opening backtick run's first character through the position one past
    the closing run's last character — i.e., it includes the delimiter
    backticks themselves. A position `p` in the line is "inside" the
    span iff `start <= p < end`. Refs that sit inside such spans are
    excluded from validation and from `--write` auto-stamping per
    `conventions.md §1.8(e)` (the "refs inside inline backtick spans
    are excluded" clause).

    Unclosed runs (no matching closing run on the same line) do not
    create spans — they are left as literal backticks per CommonMark.
    """
    spans: List[Tuple[int, int]] = []
    i = 0
    n = len(line)
    while i < n:
        if line[i] != "`":
            i += 1
            continue
        # Count the opening run length.
        run_start = i
        while i < n and line[i] == "`":
            i += 1
        run_len = i - run_start
        # Search for the matching closing run on the same line.
        j = i
        while j < n:
            if line[j] != "`":
                j += 1
                continue
            close_start = j
            while j < n and line[j] == "`":
                j += 1
            close_len = j - close_start
            if close_len == run_len:
                # Found a matching closing run. Record the span and
                # resume scanning after the closer.
                spans.append((run_start, j))
                break
            # A different-length closer is just literal backticks —
            # keep scanning for another same-length run.
        else:
            # No matching closer on this line — the opening run is
            # literal. Leave i past the opening run (it is already
            # advanced) and continue.
            pass
        # `i` is either at `n` (no closer) or just past the closer's
        # last backtick (the for-else clause above sets j past the run
        # before assignment to i below).
        i = j
    return spans


def position_in_inline_span(spans: Sequence[Tuple[int, int]], pos: int) -> bool:
    """Return True iff position `pos` is inside any of the inline-backtick spans."""
    for start, end in spans:
        if start <= pos < end:
            return True
    return False


# ---------------------------------------------------------------------------
# Annotation parser.
# ---------------------------------------------------------------------------


def _parse_comma_list(raw: Optional[str]) -> Optional[Tuple[str, ...]]:
    """Parse a comma-separated token list. Returns None on malformed input.

    The regex character class in `_ANNOTATION_ROLES_RE` / `_ANNOTATION_PHASES_RE`
    already excludes whitespace, so a space-padded value `roles=orchestrator, implementer`
    fails to match and `raw` arrives here as None. The split below is
    therefore a clean comma split — empty tokens are still treated as
    malformed (e.g., `roles=orchestrator,,implementer`).
    """
    if raw is None:
        return None
    tokens = tuple(raw.split(","))
    if any(token == "" for token in tokens):
        return None
    return tokens


def parse_annotation(line: str, line_no: int) -> Optional[Annotation]:
    """Parse a `<!-- roles=... phases=... summary="..." -->` comment.

    Returns None when the line is not a well-formed HTML comment at all
    (so the heading is treated as un-annotated for rule 4's purposes).
    Returns an `Annotation` with `well_formed=False` when the line IS an
    HTML comment but the field shapes are malformed — rule 5 then has a
    record to fire on, with the raw body preserved so the error message
    can name the offending substring.
    """
    m = _ANNOTATION_RE.match(line)
    if m is None:
        return None
    raw = m.group(1)
    roles_m = _ANNOTATION_ROLES_RE.search(raw)
    phases_m = _ANNOTATION_PHASES_RE.search(raw)
    summary_m = _ANNOTATION_SUMMARY_RE.search(raw)
    roles = _parse_comma_list(roles_m.group(1) if roles_m else None)
    phases = _parse_comma_list(phases_m.group(1) if phases_m else None)
    summary = summary_m.group(1) if summary_m else None
    well_formed = roles is not None and phases is not None and summary is not None
    return Annotation(
        raw=raw,
        line=line_no,
        roles=roles,
        phases=phases,
        summary=summary,
        well_formed=well_formed,
    )


# ---------------------------------------------------------------------------
# Heading parser.
# ---------------------------------------------------------------------------


def parse_headings(lines: Sequence[str]) -> List[Heading]:
    """Walk the file and collect every `^## ` and `^### ` heading + its annotation.

    Lines are accessed 0-indexed inside the function; the `line` field
    on each `Heading` and `Annotation` is 1-based to match editor /
    error-output convention.
    """
    headings: List[Heading] = []
    for idx, line in enumerate(lines):
        m2 = _H2_RE.match(line)
        m3 = _H3_RE.match(line)
        if m2 is None and m3 is None:
            continue
        if m2 is not None:
            level = 2
            text = m2.group(1)
        else:
            assert m3 is not None  # narrow Optional for the type checker
            level = 3
            text = m3.group(1)
        heading = Heading(
            level=level,
            text=text,
            line=idx + 1,
            is_bootstrap=(f"{'#' * level} {text}" == BOOTSTRAP_BLOCK_HEADING),
        )
        # Annotation is the next line iff it parses as a comment.
        next_idx = idx + 1
        if next_idx < len(lines):
            ann = parse_annotation(lines[next_idx], next_idx + 1)
            if ann is not None:
                heading.annotation = ann
        headings.append(heading)
    return headings


# ---------------------------------------------------------------------------
# TOC region parser.
# ---------------------------------------------------------------------------


def parse_toc_region(lines: Sequence[str]) -> Optional[TocRegion]:
    """Find the TOC region between the delimiter comments.

    A file with no `<!--Document index start-->` line returns None;
    the TOC-presence validation rule distinguishes "missing TOC, file
    has no `^## ` headings" (accepted) from "missing TOC, file has
    headings" (CI failure). This function does not enforce the rule —
    it just reports presence / absence + the row payload.

    Multiple start delimiters in one file are a malformed shape that
    the TOC-presence rule catches; this parser returns the first
    complete region it finds and lets the rule fire on the
    duplicates.
    """
    start_line: Optional[int] = None
    for idx, line in enumerate(lines):
        if line.strip() == TOC_START_DELIMITER:
            start_line = idx + 1
            break
    if start_line is None:
        return None
    end_line: Optional[int] = None
    for idx in range(start_line, len(lines)):
        if lines[idx].strip() == TOC_END_DELIMITER:
            end_line = idx + 1
            break
    if end_line is None:
        # Unterminated TOC region — treat as no region (rule 2 will catch
        # this when it observes the start delimiter without the end).
        return None
    rows: List[TocRow] = []
    for idx in range(start_line, end_line - 1):
        raw = lines[idx].strip()
        if raw.startswith("|"):
            rows.append(TocRow(line=idx + 1, raw=raw))
    return TocRegion(start_line=start_line, end_line=end_line, rows=rows)


# ---------------------------------------------------------------------------
# File parser entry point.
# ---------------------------------------------------------------------------


def parse_file(path: Path, repo_root: Path) -> ParsedFile:
    """Read a file from disk and return its structured representation."""
    with open(path, "r", encoding="utf-8") as f:
        lines = f.read().splitlines()
    rel = path.resolve().relative_to(repo_root.resolve()).as_posix()
    headings = parse_headings(lines)
    toc = parse_toc_region(lines)
    fenced = compute_fenced_lines(lines)
    return ParsedFile(
        path=rel,
        abs_path=path,
        lines=lines,
        headings=headings,
        toc=toc,
        fenced_lines=fenced,
    )


# ---------------------------------------------------------------------------
# File discovery.
# ---------------------------------------------------------------------------


def discover_in_scope_files(repo_root: Path) -> List[Path]:
    """Enumerate every in-scope workflow file under `repo_root`.

    The discovery is deterministic — globs run in the order declared in
    `IN_SCOPE_GLOBS` and the matched paths are sorted within each glob.
    Duplicate matches (a file caught by two globs) are deduplicated
    while preserving the first-occurrence order.
    """
    seen: Dict[str, Path] = {}
    for pattern in IN_SCOPE_GLOBS:
        full_pattern = str(repo_root / pattern)
        for match in sorted(glob.glob(full_pattern, recursive=True)):
            p = Path(match)
            if not p.is_file():
                continue
            key = str(p.resolve())
            if key in seen:
                continue
            seen[key] = p
    return list(seen.values())


def parse_in_scope_files(repo_root: Path) -> List[ParsedFile]:
    """Parse every in-scope file. Returns one `ParsedFile` per match."""
    return [parse_file(p, repo_root) for p in discover_in_scope_files(repo_root)]


# ---------------------------------------------------------------------------
# Bootstrap probe.
# ---------------------------------------------------------------------------


def discover_conventions_path(repo_root: Path) -> Path:
    """Discover the §1.8 source via the staged-aware probe.

    Algorithm:

    1. Enumerate `docs/adr/*/_workflow/staged-workflow/.claude/workflow/conventions.md`.
       Multiple matches → `AmbiguousBootstrapProbeError` (CLI exit 2).
       A single match wins per `conventions.md §1.7(d)` reads-precedence.
    2. Otherwise fall back to live `.claude/workflow/conventions.md`.

    The probe does not require the live file to exist when a staged
    copy is present — the staging convention deliberately removes the
    branch's live §1.8 until Phase 4 promotion. The probe DOES require
    the live file to exist when no staged copy is found, since that is
    the steady-state path for non-workflow-modifying branches.
    """
    staged_glob = str(
        repo_root
        / "docs"
        / "adr"
        / "*"
        / "_workflow"
        / "staged-workflow"
        / ".claude"
        / "workflow"
        / "conventions.md"
    )
    staged_matches = sorted(glob.glob(staged_glob))
    if len(staged_matches) > 1:
        joined = "\n  ".join(staged_matches)
        raise AmbiguousBootstrapProbeError(
            "Multiple staged conventions.md copies found — bootstrap is ambiguous:\n  "
            + joined
        )
    if len(staged_matches) == 1:
        return Path(staged_matches[0])
    live = repo_root / ".claude" / "workflow" / "conventions.md"
    if not live.exists():
        raise AmbiguousBootstrapProbeError(
            f"No staged conventions.md and live {live} is missing — bootstrap impossible."
        )
    return live


def _extract_enum_block(lines: Sequence[str], anchor: str) -> List[str]:
    """Extract the fenced code block immediately following `anchor` heading.

    `anchor` is the literal heading text (without the `### ` markers).
    The role and phase enums in §1.8 sit inside a single ```-fenced block
    each — this helper finds the heading line, walks forward to the next
    backtick fence, and returns the lines between the opener and closer.

    Returns an empty list when the anchor is missing or has no following
    fenced block (the caller raises a bootstrap-probe error).
    """
    # Find the anchor heading line. The conventions.md file uses `### (a) Role enum`
    # and `### (b) Phase enum` per the schema; matching on the literal
    # text after the `### ` marker keeps the probe robust against
    # heading-level changes during schema-authoring mutations.
    anchor_idx: Optional[int] = None
    for idx, line in enumerate(lines):
        if line.strip() == f"### {anchor}":
            anchor_idx = idx
            break
    if anchor_idx is None:
        return []
    # Find the opening fence after the anchor.
    open_idx: Optional[int] = None
    open_fence: Optional[Tuple[str, int]] = None
    for idx in range(anchor_idx + 1, len(lines)):
        parsed = parse_code_fence(lines[idx])
        if parsed is not None:
            open_idx = idx
            open_fence = parsed
            break
    if open_idx is None or open_fence is None:
        return []
    # Find the matching closer.
    close_idx: Optional[int] = None
    for idx in range(open_idx + 1, len(lines)):
        parsed = parse_code_fence(lines[idx])
        if parsed is not None:
            char, length = parsed
            open_char, open_len = open_fence
            if char == open_char and length >= open_len:
                close_idx = idx
                break
    if close_idx is None:
        return []
    return list(lines[open_idx + 1 : close_idx])


def _extract_tokens(block: Sequence[str]) -> Tuple[str, ...]:
    """Extract the first whitespace-delimited token on each non-empty line.

    The role and phase enums in §1.8 use the shape
    `any                                            — description` or
    `0    Research              (/create-plan …)`. The first
    whitespace-delimited token is the enum value; everything else is the
    human-readable description.

    Lines that start with whitespace are continuation lines of the
    previous enum entry's description (e.g., the multi-line "Final
    Artifacts" body in §1.8(b) that wraps onto a second line with
    leading spaces); they are NOT enum tokens. Only lines whose first
    character is non-whitespace contribute a token. Blank lines and
    pure-whitespace lines are skipped.
    """
    tokens: List[str] = []
    for line in block:
        if not line:
            continue
        # Continuation line — leading whitespace means this is wrapped
        # description text for the previous enum value, not a new enum
        # value. Skip.
        if line[0].isspace():
            continue
        # Take the first whitespace-delimited token. Strip an optional
        # trailing comma in case the enum block ever lists tokens
        # comma-separated.
        first = line.split(None, 1)[0].rstrip(",")
        if not first:
            continue
        tokens.append(first)
    return tuple(tokens)


def load_bootstrap_enums(repo_root: Path) -> BootstrapEnums:
    """Load the role and phase enums from `conventions.md §1.8`.

    Discovers the source via `discover_conventions_path`, parses the
    `### (a) Role enum` and `### (b) Phase enum` fenced blocks, and
    returns the token tuples plus the source path.

    Raises `AmbiguousBootstrapProbeError` on ambiguous staged matches or
    when either enum block fails to parse — the latter is converted to
    exit 2 by the CLI for the same reason: the script cannot validate
    annotations without trustworthy enum tokens.
    """
    source = discover_conventions_path(repo_root)
    with open(source, "r", encoding="utf-8") as f:
        lines = f.read().splitlines()
    roles_block = _extract_enum_block(lines, "(a) Role enum")
    phases_block = _extract_enum_block(lines, "(b) Phase enum")
    roles = _extract_tokens(roles_block)
    phases = _extract_tokens(phases_block)
    if not roles or not phases:
        raise AmbiguousBootstrapProbeError(
            f"Failed to extract role/phase enums from §1.8 at {source}: "
            f"roles={len(roles)}, phases={len(phases)}. The script needs both "
            "enums to validate annotations."
        )
    return BootstrapEnums(roles=roles, phases=phases, source=source)


# ---------------------------------------------------------------------------
# Bootstrap-block in-scope set discovery (rule 7).
# ---------------------------------------------------------------------------


def discover_bootstrap_scope(repo_root: Path) -> List[Path]:
    """Enumerate the 38 in-scope paths for the bootstrap-block presence rule.

    Per design.md §"Bootstrap protocol for agent system prompts" → §"Scope
    and uniformity": 7 specific SKILL.md files (the same names spelled out
    in `BOOTSTRAP_SCOPE_SKILLS`), every `.claude/workflow/prompts/*.md`,
    and every `.claude/agents/*.md`. Missing files are silently dropped —
    rule 7 is a presence check against the discovered set, not a check
    that the 38-count matches.
    """
    paths: List[Path] = []
    for rel in BOOTSTRAP_SCOPE_SKILLS:
        p = repo_root / rel
        if p.is_file():
            paths.append(p)
    prompts_dir = repo_root / ".claude" / "workflow" / "prompts"
    if prompts_dir.is_dir():
        for p in sorted(prompts_dir.glob("*.md")):
            if p.is_file():
                paths.append(p)
    agents_dir = repo_root / ".claude" / "agents"
    if agents_dir.is_dir():
        for p in sorted(agents_dir.glob("*.md")):
            if p.is_file():
                paths.append(p)
    return paths


# ---------------------------------------------------------------------------
# Reference resolution helpers.
# ---------------------------------------------------------------------------


def resolve_anchor(
    headings: Sequence[Heading], major: str, minor: str, sub: Optional[str]
) -> Optional[Heading]:
    """Return the heading matched by `§X.Y` or `§X.Y(z)`, or None.

    For `§X.Y`: walks `headings` looking for a `## X.Y ` parent heading.
    For `§X.Y(z)`: finds the parent first, then walks forward through
    `### (z) ` sub-section headings until the next `## ` parent. The
    returned `Heading` is the resolution target: the parent for the
    parent-only ref, the sub-section heading for the `(z)`-form.

    None means the anchor does not resolve — rule 8 reports this as an
    unresolved-ref blocker.
    """
    # Find the parent `## X.Y` heading.
    parent_idx: Optional[int] = None
    for idx, h in enumerate(headings):
        if h.level != 2:
            continue
        m = _PARENT_SECTION_HEADING_RE.match(f"## {h.text}")
        if m is None:
            continue
        if m.group("major") == major and m.group("minor") == minor:
            parent_idx = idx
            break
    if parent_idx is None:
        return None
    if sub is None:
        return headings[parent_idx]
    # Sub-section: walk forward from parent until the next `## ` heading.
    for idx in range(parent_idx + 1, len(headings)):
        h = headings[idx]
        if h.level == 2:
            break  # left the parent's scope
        if h.level != 3:
            continue
        m = _SUB_SECTION_HEADING_RE.match(f"### {h.text}")
        if m is None:
            continue
        if m.group("sub") == sub:
            return h
    return None


def union_annotation_sets(
    headings: Sequence[Heading],
) -> Tuple[FrozenSet[str], FrozenSet[str]]:
    """Return the union of all (roles, phases) across well-formed annotations.

    File-level cross-file refs resolve to the union of every section's
    annotations in the target file per §1.8(e). Headings without a
    well-formed annotation contribute nothing.
    """
    roles_union: set = set()
    phases_union: set = set()
    for h in headings:
        if h.annotation is None or not h.annotation.well_formed:
            continue
        if h.annotation.roles is not None:
            roles_union.update(h.annotation.roles)
        if h.annotation.phases is not None:
            phases_union.update(h.annotation.phases)
    return frozenset(roles_union), frozenset(phases_union)


def subset_with_any_wildcard(
    citer: Iterable[str], target: Iterable[str]
) -> bool:
    """Return True iff `citer` is a valid subset of `target` per §1.8(e).

    `any`-wildcard semantics:
    - `target` containing `any` matches any citer (return True).
    - `citer` containing `any` requires `target` to contain `any` (else
      False — citer cannot claim wildcard authority the target does not
      grant).
    - Otherwise, plain set-subset check.
    """
    citer_set = frozenset(citer)
    target_set = frozenset(target)
    if "any" in target_set:
        return True
    if "any" in citer_set:
        return False
    return citer_set.issubset(target_set)


# ---------------------------------------------------------------------------
# Cross-file resolver: map a `name.md` (optionally `prompts/name.md`)
# reference to the corresponding parsed file in the in-scope set.
# ---------------------------------------------------------------------------


def build_file_lookup(parsed: Sequence[ParsedFile]) -> Dict[str, ParsedFile]:
    """Build a lookup table from cross-file ref `name.md` shapes to parsed files.

    The convention from §1.8(e): "The path is relative to the conventional
    anchor (`.claude/workflow/`, `.claude/skills/`)." Cross-file refs use:

    - `conventions.md` → `.claude/workflow/conventions.md`
    - `prompts/technical-review.md` → `.claude/workflow/prompts/technical-review.md`
    - `step-implementation.md` → `.claude/workflow/step-implementation.md`
    - SKILL.md is never named bare in a cross-file ref (it would be
      ambiguous across 7 anchors); SKILL.md targets are linked by
      directory prefix when needed.

    The lookup returns the first matching file when multiple files share
    a basename; this is fine for the current scope (no cross-directory
    basename collisions in the workflow set).
    """
    lookup: Dict[str, ParsedFile] = {}
    for pf in parsed:
        # Bare filename keys: `step-implementation.md`, `conventions.md`.
        basename = pf.abs_path.name
        if basename not in lookup:
            lookup[basename] = pf
        # `prompts/X.md` keys for files under `.claude/workflow/prompts/`.
        if pf.path.startswith(".claude/workflow/prompts/"):
            sub_key = f"prompts/{basename}"
            if sub_key not in lookup:
                lookup[sub_key] = pf
    return lookup


# ---------------------------------------------------------------------------
# Reference scanner: extract refs from a parsed file's body, skipping
# fenced + inline-backtick spans.
# ---------------------------------------------------------------------------


@dataclass
class _RefMatch:
    """One reference match in a file's body.

    The match position is `(line, col)` 1-based / 0-based respectively;
    `line` is 1-based to align with editor / Finding convention, `col`
    is 0-based to align with Python string indexing for the
    inline-backtick span check.
    """

    line: int
    col: int
    span_end: int  # 0-based end-exclusive column in the line
    text: str


def _iter_non_fenced_lines(parsed: ParsedFile) -> Iterable[Tuple[int, str]]:
    """Yield `(line_no, line_text)` for each line NOT inside a fenced block or the TOC region.

    `line_no` is 1-based to match editor / Finding convention. The TOC
    region's rows carry `§X.Y` anchors as Section-cell content; those
    are TOC anchors, not in-prose refs, and must not trigger rule 6 /
    rule 8 against themselves.
    """
    toc_start = parsed.toc.start_line if parsed.toc is not None else None
    toc_end = parsed.toc.end_line if parsed.toc is not None else None
    for idx, line in enumerate(parsed.lines):
        if parsed.fenced_lines[idx]:
            continue
        line_no = idx + 1
        if toc_start is not None and toc_end is not None:
            if toc_start <= line_no <= toc_end:
                continue
        yield (line_no, line)


def _ref_in_inline_backticks(line: str, col: int, span_end: int) -> bool:
    """Return True iff `[col, span_end)` overlaps any inline-backtick span."""
    spans = inline_backtick_spans(line)
    # A ref is excluded when its starting position OR any character
    # inside it sits in a backtick span. We use position_in_inline_span
    # at the start position — every regex match starts past the opening
    # backtick run of any wrapping span (the regex's own characters
    # cannot include backticks).
    return position_in_inline_span(spans, col)


def scan_in_file_refs(parsed: ParsedFile) -> List[Tuple[int, int, re.Match]]:
    """Return every in-file `§X.Y(z)` ref outside fenced + inline backtick spans.

    The returned tuple is `(line_no, col, match)` where `match` is the
    `re.Match` object with named groups `major`, `minor`, `sub` (optional),
    `roles` (optional), and `phases` (optional). `line_no` is 1-based;
    `col` is the 0-based starting column inside the line.
    """
    results: List[Tuple[int, int, re.Match]] = []
    for line_no, line in _iter_non_fenced_lines(parsed):
        for m in _IN_FILE_REF_RE.finditer(line):
            col = m.start()
            if _ref_in_inline_backticks(line, col, m.end()):
                continue
            results.append((line_no, col, m))
    return results


def scan_cross_file_refs(
    parsed: ParsedFile,
) -> Tuple[List[Tuple[int, int, re.Match]], List[Tuple[int, int, re.Match]]]:
    """Return `(stamped_refs, bare_refs)` outside fenced + inline backtick spans.

    `stamped_refs` matches the suffix-bearing shape (`name.md:roles:phases`).
    `bare_refs` matches `name.md` references without a `:roles:phases`
    suffix — rule 6 reports each bare match as a missing-suffix finding.

    To avoid double-counting (the bare regex would otherwise match every
    `name.md` prefix of a stamped ref), the bare pattern uses a negative
    lookahead `(?!:)`. Refs inside fenced + inline-backtick spans are
    excluded; the scanner's caller does not need to re-check.
    """
    stamped: List[Tuple[int, int, re.Match]] = []
    bare: List[Tuple[int, int, re.Match]] = []
    for line_no, line in _iter_non_fenced_lines(parsed):
        for m in _CROSS_FILE_REF_RE.finditer(line):
            col = m.start()
            if _ref_in_inline_backticks(line, col, m.end()):
                continue
            stamped.append((line_no, col, m))
        for m in _CROSS_FILE_REF_BARE_RE.finditer(line):
            col = m.start()
            if _ref_in_inline_backticks(line, col, m.end()):
                continue
            bare.append((line_no, col, m))
    return stamped, bare


# ---------------------------------------------------------------------------
# Validation rules.
# ---------------------------------------------------------------------------


def check_rule_1_stamp_present(parsed: ParsedFile) -> List[Finding]:
    """Rule 1: line 1 carries the workflow-SHA stamp.

    Note: this validator runs on workflow files (`.claude/workflow/`,
    `.claude/skills/`). The workflow-SHA stamp rule applies to
    `_workflow/**` artifacts per conventions.md §1.6; live workflow
    files do NOT carry a stamp. Rule 1 is enforced only on files that
    look like `_workflow/**` artifacts based on the staged copy probe —
    a live `.claude/workflow/` file with no stamp passes. The design
    text says "Already enforced by drift gate; reindex script re-checks
    for consistency" — i.e., the script does not duplicate the drift
    gate's enforcement; the reindex script's rule 1 is satisfied
    automatically for live workflow files (no stamp expected).

    This implementation therefore treats rule 1 as a presence check for
    staged copies only — files whose repo-relative path begins with
    `docs/adr/`. Live workflow files pass.
    """
    if not parsed.path.startswith("docs/adr/"):
        return []
    if not parsed.lines:
        return [
            Finding(
                path=parsed.path,
                line=1,
                rule="rule_1",
                explanation="file is empty; expected workflow-sha stamp on line 1",
            )
        ]
    if not _STAMP_LINE_RE.match(parsed.lines[0]):
        return [
            Finding(
                path=parsed.path,
                line=1,
                rule="rule_1",
                explanation="line 1 is not a workflow-sha stamp comment",
            )
        ]
    return []


def check_rule_2_toc_region(parsed: ParsedFile) -> List[Finding]:
    """Rule 2: exactly one TOC region under H1; empty TOC accepted for files without `^## ` headings."""
    h2_headings = [h for h in parsed.headings if h.level == 2 and not h.is_bootstrap]
    has_headings = len(h2_headings) > 0
    if parsed.toc is None:
        if has_headings:
            return [
                Finding(
                    path=parsed.path,
                    line=1,
                    rule="rule_2",
                    explanation="file has H2 headings but no TOC region",
                )
            ]
        return []
    # Detect a second `<!--Document index start-->` delimiter that the
    # parser silently dropped — multiple TOC regions are a CI failure.
    start_count = sum(1 for line in parsed.lines if line.strip() == TOC_START_DELIMITER)
    if start_count > 1:
        return [
            Finding(
                path=parsed.path,
                line=parsed.toc.start_line,
                rule="rule_2",
                explanation=f"file has {start_count} TOC regions; exactly one is required",
            )
        ]
    return []


def _toc_section_anchors(parsed: ParsedFile) -> List[Tuple[int, str]]:
    """Return `(line_no, section_text)` for every TOC data row's first cell.

    The TOC table's structure is `| Section | Roles | Phases | Summary |`
    per §1.8(d). Data rows (skipping the header row and the `|---|---|...`
    separator row) carry the section text in the first cell. The
    separator row is detected by its content (only `|`, `-`, and
    whitespace).
    """
    if parsed.toc is None:
        return []
    anchors: List[Tuple[int, str]] = []
    for row in parsed.toc.rows:
        raw = row.raw.strip()
        # Skip the `|---|---|...|` separator row.
        if re.fullmatch(r"\|(\s*-+\s*\|)+", raw):
            continue
        # Skip the header row (contains the literal word "Section").
        cells = [c.strip() for c in raw.strip("|").split("|")]
        if not cells:
            continue
        first = cells[0]
        if first.lower() == "section":
            continue
        anchors.append((row.line, first))
    return anchors


def _heading_to_section_label(heading: Heading) -> str:
    """Return the TOC section label for a heading.

    Per §1.8(d): "Section cell carries the heading text prefixed with `§`,
    with the `## ` / `### ` Markdown markers stripped". Example:
    `## 1.8 Per-section role/phase annotations and TOC region` produces
    Section cell `§1.8 Per-section role/phase annotations and TOC region`.
    """
    return f"§{heading.text}"


def check_rule_3_toc_matches_annotations(parsed: ParsedFile) -> List[Finding]:
    """Rule 3: every `^## ` and `^### ` heading has a TOC row; bootstrap exempt."""
    if parsed.toc is None:
        # Already handled by rule 2.
        return []
    findings: List[Finding] = []
    toc_anchors = _toc_section_anchors(parsed)
    toc_section_texts = {text for _line, text in toc_anchors}
    for h in parsed.headings:
        if h.is_bootstrap:
            continue
        label = _heading_to_section_label(h)
        if label not in toc_section_texts:
            findings.append(
                Finding(
                    path=parsed.path,
                    line=h.line,
                    rule="rule_3",
                    explanation=(
                        f"heading {label!r} has no matching TOC row "
                        "(every `^## ` / `^### ` heading must appear in the TOC)"
                    ),
                )
            )
    # The other half: every TOC row should map to a real heading.
    heading_labels = {
        _heading_to_section_label(h)
        for h in parsed.headings
        if not h.is_bootstrap
    }
    for line, text in toc_anchors:
        if text not in heading_labels:
            findings.append(
                Finding(
                    path=parsed.path,
                    line=line,
                    rule="rule_3",
                    explanation=(
                        f"TOC row {text!r} does not match any `^## ` / `^### ` heading"
                    ),
                )
            )
    return findings


def check_rule_4_annotation_present(parsed: ParsedFile) -> List[Finding]:
    """Rule 4: annotation comment present after every `^## ` / `^### `; bootstrap exempt."""
    findings: List[Finding] = []
    for h in parsed.headings:
        if h.is_bootstrap:
            continue
        if h.annotation is None:
            findings.append(
                Finding(
                    path=parsed.path,
                    line=h.line,
                    rule="rule_4",
                    explanation=(
                        f"heading {h.text!r} has no annotation comment on the next line"
                    ),
                )
            )
    return findings


def check_rule_5_annotation_fields(
    parsed: ParsedFile, enums: BootstrapEnums
) -> List[Finding]:
    """Rule 5 (5a-5e): annotation field well-formedness.

    - 5a: `roles=` present and parsed (the regex already enforces
      no-space-around-commas).
    - 5b: `phases=` same shape.
    - 5c: `summary="..."` ≤120 chars, double-quoted.
    - 5d: every role / phase token drawn from the bootstrap output.
    - 5e: the comment shape itself is well-formed (open `<!--` /
      close `-->`, single-line).
    """
    findings: List[Finding] = []
    roles_set = frozenset(enums.roles)
    phases_set = frozenset(enums.phases)
    for h in parsed.headings:
        if h.is_bootstrap:
            continue
        ann = h.annotation
        if ann is None:
            continue  # rule 4 handles missing annotations
        # 5a — roles field present and parsed.
        if ann.roles is None:
            findings.append(
                Finding(
                    path=parsed.path,
                    line=ann.line,
                    rule="rule_5a",
                    explanation=(
                        "roles= field missing, empty, or has space around a comma"
                    ),
                )
            )
        # 5b — phases field present and parsed.
        if ann.phases is None:
            findings.append(
                Finding(
                    path=parsed.path,
                    line=ann.line,
                    rule="rule_5b",
                    explanation=(
                        "phases= field missing, empty, or has space around a comma"
                    ),
                )
            )
        # 5c — summary present, ≤120 chars.
        if ann.summary is None:
            findings.append(
                Finding(
                    path=parsed.path,
                    line=ann.line,
                    rule="rule_5c",
                    explanation="summary=\"...\" field missing or not double-quoted",
                )
            )
        elif len(ann.summary) > 120:
            findings.append(
                Finding(
                    path=parsed.path,
                    line=ann.line,
                    rule="rule_5c",
                    explanation=(
                        f"summary field is {len(ann.summary)} chars; limit is 120"
                    ),
                )
            )
        # 5d — every role / phase token drawn from the bootstrap enums.
        if ann.roles is not None:
            for token in ann.roles:
                if token not in roles_set:
                    findings.append(
                        Finding(
                            path=parsed.path,
                            line=ann.line,
                            rule="rule_5d",
                            explanation=(
                                f"roles token {token!r} is not in the role enum "
                                f"(loaded from {enums.source})"
                            ),
                        )
                    )
        if ann.phases is not None:
            for token in ann.phases:
                if token not in phases_set:
                    findings.append(
                        Finding(
                            path=parsed.path,
                            line=ann.line,
                            rule="rule_5d",
                            explanation=(
                                f"phases token {token!r} is not in the phase enum "
                                f"(loaded from {enums.source})"
                            ),
                        )
                    )
        # 5e — malformed comment shape.
        if not ann.well_formed and ann.roles is not None and ann.phases is not None and ann.summary is not None:
            # All three fields parsed but well_formed is still False —
            # should not happen. Guard against logic drift.
            findings.append(
                Finding(
                    path=parsed.path,
                    line=ann.line,
                    rule="rule_5e",
                    explanation="annotation comment shape is malformed",
                )
            )
        # Detect multi-line annotation drift: the raw body should not
        # contain unescaped newlines (the line-anchored regex
        # `_ANNOTATION_RE` already enforces single-line shape, so a
        # raw with a newline would never parse — this check is a guard
        # against future regex relaxations).
        if ann.raw is not None and "\n" in ann.raw:
            findings.append(
                Finding(
                    path=parsed.path,
                    line=ann.line,
                    rule="rule_5e",
                    explanation="annotation comment spans multiple lines",
                )
            )
    return findings


def check_rule_6_cross_file_refs(
    parsed: ParsedFile, file_lookup: Dict[str, ParsedFile]
) -> List[Finding]:
    """Rule 6: cross-file refs carry the suffix and subset-validate against the target.

    Implementation follows §1.8(e):
    - Bare `name.md` refs (no `:roles:phases` suffix) fail.
    - Suffix present: resolve the target file. Missing target file is
      a finding.
    - Sub-section ref (`name.md§X.Y(z):roles:phases`): resolve the
      anchor in the target file; missing anchor is a finding.
    - File-level ref (`name.md:roles:phases`): the union of every
      well-formed annotation in the target file is the comparison set.
    - Subset check applies `any`-wildcard semantics (see
      `subset_with_any_wildcard`).
    """
    findings: List[Finding] = []
    stamped, bare = scan_cross_file_refs(parsed)
    # Track positions matched by stamped refs so bare-ref findings do
    # not double-report the same span. The bare regex already uses
    # `(?!:)` to skip suffix-bearing matches, but a defensive check
    # here keeps the two scanners decoupled.
    stamped_positions = {(line, col) for line, col, _ in stamped}
    for line, col, m in bare:
        if (line, col) in stamped_positions:
            continue
        file_ref = m.group("file")
        if file_ref in _CROSS_FILE_REF_OUT_OF_SCOPE:
            continue
        findings.append(
            Finding(
                path=parsed.path,
                line=line,
                rule="rule_6",
                explanation=(
                    f"cross-file ref {file_ref!r} is missing the :roles:phases suffix"
                ),
            )
        )
    for line, col, m in stamped:
        file_ref = m.group("file")
        if file_ref in _CROSS_FILE_REF_OUT_OF_SCOPE:
            continue
        citer_roles = tuple(m.group("roles").split(","))
        citer_phases = tuple(m.group("phases").split(","))
        major = m.group("major")
        minor = m.group("minor")
        sub = m.group("sub")
        target = file_lookup.get(file_ref)
        if target is None:
            findings.append(
                Finding(
                    path=parsed.path,
                    line=line,
                    rule="rule_6",
                    explanation=(
                        f"cross-file ref target {file_ref!r} is not in the in-scope file set"
                    ),
                )
            )
            continue
        # Resolve the comparison target: section vs. file-level union.
        if major is not None and minor is not None:
            heading = resolve_anchor(target.headings, major, minor, sub)
            if heading is None:
                findings.append(
                    Finding(
                        path=parsed.path,
                        line=line,
                        rule="rule_6",
                        explanation=(
                            f"cross-file ref {file_ref}§{major}.{minor}"
                            + (f"({sub})" if sub else "")
                            + f" does not resolve to a heading in {target.path}"
                        ),
                    )
                )
                continue
            if heading.annotation is None or not heading.annotation.well_formed:
                findings.append(
                    Finding(
                        path=parsed.path,
                        line=line,
                        rule="rule_6",
                        explanation=(
                            f"cross-file ref target section {file_ref}§{major}.{minor}"
                            + (f"({sub})" if sub else "")
                            + " has no well-formed annotation to compare against"
                        ),
                    )
                )
                continue
            target_roles = heading.annotation.roles or ()
            target_phases = heading.annotation.phases or ()
        else:
            target_roles, target_phases = union_annotation_sets(target.headings)
        if not subset_with_any_wildcard(citer_roles, target_roles):
            findings.append(
                Finding(
                    path=parsed.path,
                    line=line,
                    rule="rule_6",
                    explanation=(
                        f"cross-file ref roles {sorted(citer_roles)!r} not a subset of "
                        f"target {sorted(target_roles)!r} "
                        "(widen citer or restore target)"
                    ),
                )
            )
        if not subset_with_any_wildcard(citer_phases, target_phases):
            findings.append(
                Finding(
                    path=parsed.path,
                    line=line,
                    rule="rule_6",
                    explanation=(
                        f"cross-file ref phases {sorted(citer_phases)!r} not a subset of "
                        f"target {sorted(target_phases)!r} "
                        "(widen citer or restore target)"
                    ),
                )
            )
    return findings


def check_rule_7_bootstrap_block(
    parsed: ParsedFile, bootstrap_paths: FrozenSet[str]
) -> List[Finding]:
    """Rule 7: bootstrap block present on the 38 in-scope system prompts.

    The check is literal-heading presence — design.md says block content
    is hand-written and not validated. The heading is unique enough
    (literal match `## Reading workflow files (TOC protocol)`) that a
    string scan over `parsed.lines` is sufficient; a forgotten block is
    almost always a missing heading too.
    """
    if parsed.path not in bootstrap_paths:
        return []
    for line in parsed.lines:
        if line.strip() == BOOTSTRAP_BLOCK_HEADING:
            return []
    return [
        Finding(
            path=parsed.path,
            line=1,
            rule="rule_7",
            explanation=(
                "in-scope system prompt is missing the bootstrap block "
                f"(literal heading {BOOTSTRAP_BLOCK_HEADING!r})"
            ),
        )
    ]


def check_rule_8_in_file_refs(parsed: ParsedFile) -> List[Finding]:
    """Rule 8: in-file `§X.Y(z)` refs are auto-stamped with the target's annotation.

    Three failure modes:
    - Unstamped: `§X.Y` with no `:roles:phases` suffix.
    - Stale: stamped suffix differs from the target heading's current
      annotation.
    - Unresolved: `§X.Y(z)` does not resolve to a heading in the file.
    """
    findings: List[Finding] = []
    for line_no, col, m in scan_in_file_refs(parsed):
        major = m.group("major")
        minor = m.group("minor")
        sub = m.group("sub")
        roles_raw = m.group("roles")
        phases_raw = m.group("phases")
        anchor_text = f"§{major}.{minor}" + (f"({sub})" if sub else "")
        target = resolve_anchor(parsed.headings, major, minor, sub)
        if target is None:
            findings.append(
                Finding(
                    path=parsed.path,
                    line=line_no,
                    rule="rule_8",
                    explanation=(
                        f"in-file ref {anchor_text} does not resolve to any heading "
                        "in this file"
                    ),
                )
            )
            continue
        if target.annotation is None or not target.annotation.well_formed:
            findings.append(
                Finding(
                    path=parsed.path,
                    line=line_no,
                    rule="rule_8",
                    explanation=(
                        f"in-file ref {anchor_text} target heading has no well-formed "
                        "annotation to derive the suffix from"
                    ),
                )
            )
            continue
        expected_roles = ",".join(target.annotation.roles or ())
        expected_phases = ",".join(target.annotation.phases or ())
        if roles_raw is None or phases_raw is None:
            findings.append(
                Finding(
                    path=parsed.path,
                    line=line_no,
                    rule="rule_8",
                    explanation=(
                        f"in-file ref {anchor_text} is unstamped — "
                        f"run --write to add `:{expected_roles}:{expected_phases}`"
                    ),
                )
            )
            continue
        if roles_raw != expected_roles or phases_raw != expected_phases:
            findings.append(
                Finding(
                    path=parsed.path,
                    line=line_no,
                    rule="rule_8",
                    explanation=(
                        f"in-file ref {anchor_text} stamped suffix "
                        f"`:{roles_raw}:{phases_raw}` drifted from target's current "
                        f"annotation `:{expected_roles}:{expected_phases}` — run --write"
                    ),
                )
            )
    return findings


# ---------------------------------------------------------------------------
# Validator entry point.
# ---------------------------------------------------------------------------


def validate(
    repo_root: Path,
    files_filter: Optional[Sequence[str]] = None,
) -> List[Finding]:
    """Run every applicable rule against the in-scope file set.

    `files_filter` is the optional `--files` scope. When provided, the
    validator parses every in-scope file (so cross-file refs can resolve
    targets that fall outside the filter) but only emits findings for
    citing files whose repo-relative path appears in the filter set.
    Paths in `files_filter` that are not in scope are silently dropped
    per design.md §"Validation rules" → "Discovery-glob filter".

    Returns a list of findings sorted by (path, line, rule).
    """
    enums = load_bootstrap_enums(repo_root)
    parsed_files = parse_in_scope_files(repo_root)
    parsed_by_path = {pf.path: pf for pf in parsed_files}
    file_lookup = build_file_lookup(parsed_files)
    bootstrap_paths = frozenset(
        p.resolve().relative_to(repo_root.resolve()).as_posix()
        for p in discover_bootstrap_scope(repo_root)
    )
    if files_filter is not None:
        # Normalise to repo-relative POSIX paths and silently drop
        # out-of-scope entries.
        scoped: List[str] = []
        for raw in files_filter:
            normalised = _normalise_file_path(raw, repo_root)
            if normalised is None:
                continue
            if normalised in parsed_by_path:
                scoped.append(normalised)
        target_paths: FrozenSet[str] = frozenset(scoped)
    else:
        target_paths = frozenset(parsed_by_path.keys())
    findings: List[Finding] = []
    for pf in parsed_files:
        if pf.path not in target_paths:
            continue
        findings.extend(check_rule_1_stamp_present(pf))
        findings.extend(check_rule_2_toc_region(pf))
        findings.extend(check_rule_3_toc_matches_annotations(pf))
        findings.extend(check_rule_4_annotation_present(pf))
        findings.extend(check_rule_5_annotation_fields(pf, enums))
        findings.extend(check_rule_6_cross_file_refs(pf, file_lookup))
        findings.extend(check_rule_7_bootstrap_block(pf, bootstrap_paths))
        findings.extend(check_rule_8_in_file_refs(pf))
    findings.sort(key=lambda f: (f.path, f.line, f.rule))
    return findings


def _normalise_file_path(raw: str, repo_root: Path) -> Optional[str]:
    """Convert a CLI `--files` arg to a repo-relative POSIX path.

    Accepts absolute paths, repo-relative paths, and paths relative to
    the current working directory. Returns None when the path cannot
    be resolved to something under `repo_root` (the caller's signal
    to silently skip the entry).
    """
    if not raw:
        return None
    p = Path(raw)
    try:
        if p.is_absolute():
            resolved = p.resolve()
        else:
            # Try repo-root-relative first (the most common shape the
            # pre-commit hook passes), then cwd-relative.
            candidate = (repo_root / raw).resolve()
            if candidate.exists():
                resolved = candidate
            else:
                resolved = Path(raw).resolve()
        return resolved.relative_to(repo_root.resolve()).as_posix()
    except (ValueError, OSError):
        return None


# ---------------------------------------------------------------------------
# CLI entry point.
# ---------------------------------------------------------------------------


def _build_argparser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="workflow-reindex.py",
        description=(
            "Validate and rebuild workflow-doc TOC regions per "
            "conventions.md §1.8."
        ),
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--check",
        action="store_true",
        help="Validate the in-scope file set; exit 0/1/2 per design.md.",
    )
    mode.add_argument(
        "--write",
        action="store_true",
        help="Rebuild TOCs and auto-stamp in-file refs (added by the next commit).",
    )
    parser.add_argument(
        "--files",
        nargs="*",
        default=None,
        help=(
            "Scope validation to the listed files (space-separated). "
            "Out-of-scope paths are silently skipped."
        ),
    )
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    """CLI dispatcher for `--check`, `--write`, and `--files`.

    Exit codes per design.md §"Reindex script" → §"Exit codes":
    - 0 — clean (no findings) or fully-skipped `--files` set.
    - 1 — findings present.
    - 2 — script error (ambiguous bootstrap probe, unparsable enum
      blocks, malformed CLI args, etc.).
    """
    parser = _build_argparser()
    args = parser.parse_args(argv)
    if args.write:
        print(
            "error: --write is not implemented yet; the next commit lands it.",
            file=sys.stderr,
        )
        return 2
    if not args.check:
        # Bootstrap-only smoke output (no `--check` argument). Useful for
        # sanity-checking the probe on a fresh checkout; the runner can
        # invoke `python3 .claude/scripts/workflow-reindex.py` and read
        # the role / phase enum tokens directly. Future revisions may
        # drop this fallback once `--check` is the de-facto default.
        try:
            enums = load_bootstrap_enums(REPO_ROOT)
        except AmbiguousBootstrapProbeError as exc:
            print(f"error: {exc}", file=sys.stderr)
            return 2
        print(f"source: {enums.source}")
        print(f"roles ({len(enums.roles)}): {','.join(enums.roles)}")
        print(f"phases ({len(enums.phases)}): {','.join(enums.phases)}")
        return 0
    # `--check` mode.
    try:
        findings = validate(REPO_ROOT, files_filter=args.files)
    except AmbiguousBootstrapProbeError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    if not findings:
        return 0
    for f in findings:
        print(f.render())
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
