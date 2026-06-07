#!/usr/bin/env python3
"""Mechanical reindex + validation tool for workflow-doc TOC regions.

This script is the schema validator and TOC rebuilder for the
per-section role/phase annotation system defined in
`.claude/workflow/conventions.md §1.8`. It runs in two modes:

- `--check` — validate every in-scope workflow doc against the schema
  and exit nonzero on findings. Used by the pre-commit hook and CI.
- `--write` — rebuild every TOC region in place from the current
  annotations and auto-stamp every in-file `§X.Y(z)` reference with
  the target heading's roles/phases suffix. Halts atomically with
  exit 2 if any in-file ref is unresolved (write nothing, anywhere);
  idempotent (second invocation produces no diff). Does NOT touch
  cross-file `name.md:roles:phases` suffixes (hand-written per D9).

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
mixed-content commits). `--write` computes the full plan in memory
across the in-scope set, halts with exit 2 on the first unresolved
in-file ref (writing nothing), and applies the mutations to disk
only when every file resolves cleanly.
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
#
# Two glob sets land here side by side. The first set walks the live
# `.claude/...` tree — the steady-state path that every non-workflow-
# modifying branch validates against. The second set walks the staged
# subtree under `docs/adr/*/_workflow/staged-workflow/.claude/...`. The
# pre-commit hook widens its file-name filter to include staged paths
# so workflow-modifying branches catch staged edits before they land
# in history; the script's discovery walk has to enumerate the same
# staged surface so `--files <staged-path>` finds the file on disk and
# `--check` (no `--files`) sweeps it during a CI walk.
# ---------------------------------------------------------------------------
IN_SCOPE_GLOBS: Tuple[str, ...] = (
    # Live paths — the canonical workflow surface.
    ".claude/workflow/**/*.md",
    ".claude/workflow/prompts/**/*.md",
    ".claude/skills/create-plan/SKILL.md",
    ".claude/skills/execute-tracks/SKILL.md",
    ".claude/skills/edit-design/SKILL.md",
    ".claude/skills/migrate-workflow/SKILL.md",
    ".claude/skills/review-plan/SKILL.md",
    ".claude/skills/review-workflow-pr/SKILL.md",
    ".claude/skills/code-review/SKILL.md",
    # Staged paths — present only on workflow-modifying branches per
    # `conventions.md §1.7(e)`. The pre-commit hook and the CI workflow
    # pass these paths through `--files`; the discovery walk picks them
    # up via the globs below so the script can read the staged copy
    # directly.
    #
    # Skills asymmetry is intentional: the staged glob accepts any
    # SKILL.md so a workflow-modifying plan that stages a non-workflow
    # skill (rare) still gets validated, while the live glob above
    # restricts to the 7 workflow-referencing anchors. Live `.claude/
    # skills/**` carries ~20 skills today and only the 7 enumerated
    # above interact with the workflow tree; the staged subtree is
    # plan-scoped and only contains files the author chose to stage.
    "docs/adr/*/_workflow/staged-workflow/.claude/workflow/**/*.md",
    "docs/adr/*/_workflow/staged-workflow/.claude/skills/**/SKILL.md",
    # Staged agents are the third stageable prefix (`conventions.md §1.7(e)`):
    # a workflow-modifying branch that edits a `.claude/agents/` file routes
    # the write to this staged subtree, so this glob matches a real file once
    # such a branch stages an agent. The match feeds the staged agent into
    # `discover_in_scope_files` / `parse_in_scope_files`, but `validate` then
    # partitions it OUT of the eight-rule loop (via `_is_staged_agent`) and
    # into the rules-6/7-only agent citing scope alongside the live agents, so
    # it validates exactly like its live namesake. Without the partition the
    # eight-rule loop would over-fire rules 2/4/8 on a staged agent
    # (un-annotated `##`/`###` headings, no TOC region, bare `§X.Y` in-file
    # refs); rules 1/3/5 are already structurally unreachable on a staged agent
    # (rule 1's staged-mirror exemption, no TOC region for rule 3, no
    # well-formed annotation for rule 5 to validate), so the partition
    # suppresses 2/4/8 and future-proofs the rest. Live agent files are NOT in `IN_SCOPE_GLOBS` — they enter rules 6
    # and 7 through the separate `discover_agent_citing_files` scope (see that
    # function's docstring); only the *staged* copy passes through this glob.
    "docs/adr/*/_workflow/staged-workflow/.claude/agents/**/*.md",
)


# Staged-subtree path prefix (conventions.md §1.7(a)). A staged copy of a
# workflow file lives under `docs/adr/<dir-name>/_workflow/staged-workflow/`
# and mirrors its live `.claude/...` relative path byte-for-byte beneath
# that prefix. Stripping the prefix collapses a staged copy and its live
# namesake onto the same logical `.claude/...` path, which is how the
# cross-file resolver matches a `name.md` reference to either copy and the
# staged-copy probe detects when both are present.
_STAGED_SUBTREE_PREFIX_RE = re.compile(
    r"^docs/adr/[^/]+/_workflow/staged-workflow/"
)


# ---------------------------------------------------------------------------
# TOC region delimiters (conventions.md §1.8(d)).
# ---------------------------------------------------------------------------
TOC_START_DELIMITER = "<!--Document index start-->"
TOC_END_DELIMITER = "<!--Document index end-->"


# H1 heading (`# Title`). The TOC anchor for files that carry an H1
# (workflow docs and prompts) sits directly under it per §1.8(d).
_H1_RE = re.compile(r"^# (.+?)\s*$")

# YAML frontmatter fence (`---` on its own line). H1-less skill files
# (SKILL.md with a YAML metadata block but no document title) anchor
# their TOC immediately after the closing frontmatter `---` per the
# §1.8(d) after-frontmatter rule.
_FRONTMATTER_FENCE = "---"


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
        # last backtick (the while-else clause above sets j past the run
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


def parse_headings(
    lines: Sequence[str], fenced: Optional[Sequence[bool]] = None
) -> List[Heading]:
    """Walk the file and collect every `^## ` and `^### ` heading + its annotation.

    Lines are accessed 0-indexed inside the function; the `line` field
    on each `Heading` and `Annotation` is 1-based to match editor /
    error-output convention.

    `fenced` is the per-line "inside a fenced code block" mask from
    `compute_fenced_lines`. When supplied, `^## ` / `^### ` lines inside
    a fence are skipped — a heading written inside a fenced code block
    (a documentation example such as §1.8(g)'s `## 99.1 Demo section`
    or `create-final-design.md`'s fenced `adr.md`-template headings) is
    pedagogical text, not a real section, so it carries no annotation
    and no TOC row (rules 3/4 must not fire on it). When `fenced` is
    None the function falls back to treating every line as non-fenced,
    which preserves the pre-fence-exclusion behaviour for callers that
    do not yet compute the mask.
    """
    headings: List[Heading] = []
    for idx, line in enumerate(lines):
        if fenced is not None and idx < len(fenced) and fenced[idx]:
            # Heading inside a fenced code block — pedagogical, not a
            # real section. Skip per §1.8(e).
            continue
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


def parse_toc_region(
    lines: Sequence[str], fenced: Optional[Sequence[bool]] = None
) -> Optional[TocRegion]:
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

    `fenced` is the per-line "inside a fenced code block" mask from
    `compute_fenced_lines`. When supplied, delimiter lines inside a
    fence are ignored — the `<!--Document index start-->` /
    `<!--Document index end-->` literals inside a fenced documentation
    example (such as §1.8(g)'s worked example) are pedagogical text,
    not a real TOC region, so the parser must not latch onto them. When
    `fenced` is None every line counts, preserving the pre-fence-
    exclusion behaviour.
    """
    def _is_fenced(idx: int) -> bool:
        return fenced is not None and idx < len(fenced) and fenced[idx]

    start_line: Optional[int] = None
    for idx, line in enumerate(lines):
        if _is_fenced(idx):
            continue
        if line.strip() == TOC_START_DELIMITER:
            start_line = idx + 1
            break
    if start_line is None:
        return None
    end_line: Optional[int] = None
    for idx in range(start_line, len(lines)):
        if _is_fenced(idx):
            continue
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
# TOC-anchor helpers (§1.8(d)).
#
# Two anchor shapes are valid for a TOC region:
#   - Directly under the document H1 (`# Title`) — workflow docs and
#     prompts, which all carry an H1.
#   - Immediately after the YAML frontmatter block (`---` ... `---`) —
#     H1-less SKILL.md files that carry a metadata block but no document
#     title. 5 of the 7 in-scope skill files (edit-design, migrate-
#     workflow, code-review, review-workflow-pr, review-plan) are this
#     shape; §1.8(d)'s original "directly under H1" rule did not
#     anticipate them.
# ---------------------------------------------------------------------------


def find_first_h1_line(
    lines: Sequence[str], fenced: Optional[Sequence[bool]] = None
) -> Optional[int]:
    """Return the 1-based line number of the first non-fenced `# ` H1, or None.

    Fenced H1 lines (a `# Title` inside a documentation example) are
    skipped via the same fence mask the heading parser uses.
    """
    for idx, line in enumerate(lines):
        if fenced is not None and idx < len(fenced) and fenced[idx]:
            continue
        if _H1_RE.match(line):
            return idx + 1
    return None


def find_frontmatter_close_line(lines: Sequence[str]) -> Optional[int]:
    """Return the 1-based line number of a leading YAML frontmatter block's closing `---`.

    A frontmatter block is recognised only when the file's literal first
    line (line 1, index 0) is exactly `---`; the block then runs to the
    next line that is exactly `---`. Returns the 1-based line number of
    that closing delimiter, or None when the file has no leading
    frontmatter block (line 1 is not `---`, or the opener is
    unterminated).

    Requiring the opener at index 0 (rather than the first non-blank
    line) makes the `---`-as-thematic-break ambiguity unreachable: a
    YAML frontmatter block is a document-level construct that, by
    definition, occupies line 1, whereas a thematic break appears mid-
    document after other content. In-scope files satisfy this cleanly —
    H1-less SKILL.md files open with genuine `---` frontmatter on line 1,
    and `_workflow/**` staged artifacts open with the line-1 workflow-sha
    stamp comment (never `---`), so neither is misread. The frontmatter
    fence is not a CommonMark code fence, so this scan is independent of
    the `compute_fenced_lines` mask.
    """
    if not lines or lines[0].strip() != _FRONTMATTER_FENCE:
        return None
    for idx in range(1, len(lines)):
        if lines[idx].strip() == _FRONTMATTER_FENCE:
            return idx + 1
    return None


# ---------------------------------------------------------------------------
# File parser entry point.
# ---------------------------------------------------------------------------


def parse_file(path: Path, repo_root: Path) -> ParsedFile:
    """Read a file from disk and return its structured representation."""
    with open(path, "r", encoding="utf-8") as f:
        lines = f.read().splitlines()
    rel = path.resolve().relative_to(repo_root.resolve()).as_posix()
    # Compute the fence mask first so heading and TOC-region parsing can
    # exclude `##`/`###` headings and `<!--Document index ...-->`
    # delimiters that sit inside fenced code blocks (§1.8(e)). Rules
    # 2/3/4 inherit the exclusion through the parsed structures; rules
    # 6/8 consult `fenced_lines` directly.
    fenced = compute_fenced_lines(lines)
    headings = parse_headings(lines, fenced)
    toc = parse_toc_region(lines, fenced)
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
# Live agent-file citing scope — rules 6 and 7 only.
#
# Agent files (`.claude/agents/*.md`) are loaded as sub-agent system prompts;
# the Read tool never opens them, so per-section annotations would save no
# Read-tool tokens — the schema therefore keeps agents refs-only. They are
# deliberately NOT in `IN_SCOPE_GLOBS`: routing them through the full
# `validate` loop would fire rules 1/2/3/4/5/8 — demanding a workflow-sha
# stamp (rule 1), a TOC region (rule 2), TOC/annotation parity (rules 3/4),
# enum-token wellformedness (rule 5), and in-file ref auto-stamps (rule 8)
# on files the schema exempts. Rule 4 alone would emit ~360 false findings
# across the 20 agents (every un-annotated `##`/`###` heading) versus rule
# 2's 20.
#
# Instead agents enter a SEPARATE citing scope that runs only rule 6
# (cross-file ref suffix subset) and rule 7 (bootstrap presence) — the two
# rules that stay live for agents (outgoing workflow-doc refs carry the
# suffix; each agent carries the bootstrap block).
#
# `.claude/agents/` is one of §1.7(e)'s three stageable prefixes
# (`.claude/workflow/**`, `.claude/skills/**`, `.claude/agents/**`), so on a
# workflow-modifying branch an agent edit routes to the staged subtree while
# the live agent stays at develop-state until the Phase 4 promotion (the
# live workflow tree never changes mid-branch; see `conventions.md §1.7`).
# This function discovers only the LIVE agents; the *staged* copies are
# matched by the staged-agents glob in `IN_SCOPE_GLOBS` and partitioned into
# the same rules-6/7-only scope by `validate` (via `_is_staged_agent`), so a
# staged agent validates exactly like its live namesake without entering the
# eight-rule loop.
#
# This walk mirrors `discover_bootstrap_scope`'s agent half (rule 7 already
# consults that set) so the two never drift: the live-agent citing scope and
# the bootstrap scope's live-agent slice are the same files.
# ---------------------------------------------------------------------------


def discover_agent_citing_files(repo_root: Path) -> List[Path]:
    """Enumerate live `.claude/agents/*.md` files for the rules-6/7-only scope.

    Mirrors the agent slice of `discover_bootstrap_scope`. Returns the live
    agent files in sorted order; missing directory yields an empty list.
    These files are run through rules 6 and 7 only — never rules 1/2/3/4/5/8.
    This function walks only the live `.claude/agents/` directory. On a
    workflow-modifying branch a *staged* agent copy lives under the §1.7(a)
    subtree and is discovered through the staged-agents glob in
    `IN_SCOPE_GLOBS` instead; `validate` partitions it into the same
    rules-6/7-only scope (see `_is_staged_agent`). The two copies are
    distinct paths — the live develop-state copy here, the staged copy via
    the glob — so both are validated when both exist.
    """
    paths: List[Path] = []
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


def _logical_workflow_path(pf: ParsedFile) -> str:
    """Return the file's logical `.claude/...` path with any staged prefix stripped.

    A staged copy lives at
    `docs/adr/<dir>/_workflow/staged-workflow/.claude/...` and mirrors its
    live `.claude/...` relative path beneath that prefix (§1.7(a)).
    Stripping the prefix collapses a staged copy and its live namesake
    onto the same logical path so the cross-file resolver can match a
    `name.md` reference to either copy and prefer the staged one.
    """
    return _STAGED_SUBTREE_PREFIX_RE.sub("", pf.path)


def _is_staged(pf: ParsedFile) -> bool:
    """Return True iff the file is a staged copy under the §1.7(a) subtree."""
    return _STAGED_SUBTREE_PREFIX_RE.match(pf.path) is not None


def _is_staged_agent(pf: ParsedFile) -> bool:
    """Return True iff `pf` is a staged copy of a `.claude/agents/` file.

    Once `conventions.md §1.7(e)` stages agents (the third stageable prefix),
    the staged-agents glob in `IN_SCOPE_GLOBS` starts matching real files and
    `discover_in_scope_files` returns the staged agent into `parsed_files`. A
    staged agent must validate like a live agent — rules 6 and 7 only — not
    through the eight-rule `parsed_files` loop, which would over-fire rules
    2/4/8 on it (un-annotated `##`/`###` headings, no TOC region, bare `§X.Y`
    in-file refs); rules 1/3/5 are already structurally unreachable on a staged
    agent (rule 1's staged-mirror exemption, no TOC region for rule 3, no
    well-formed annotation for rule 5 to validate), so the partition suppresses
    2/4/8 and future-proofs the rest. The validator and the `--write` planner
    use this predicate to pull
    a staged agent out of the eight-rule / TOC-rewrite treatment and into the
    rules-6/7-only citing scope. The logical path (staged prefix stripped)
    collapses the staged copy onto its live `.claude/agents/<name>.md`
    namesake, so the test is `staged AND logical path under .claude/agents/`.
    """
    if not _is_staged(pf):
        return False
    logical = _logical_workflow_path(pf)
    return logical.startswith(".claude/agents/")


def _is_workflow_root_doc(logical: str) -> bool:
    """Return True iff `logical` is a `.claude/workflow/` root doc (not a prompt).

    A workflow-root doc sits directly under `.claude/workflow/` with no
    further directory component (`.claude/workflow/conventions.md`,
    `.claude/workflow/structural-review.md`). A prompt under
    `.claude/workflow/prompts/` is NOT a root doc. The bare-basename
    cross-file ref form resolves to the workflow-root doc when both share a
    basename (§1.8(e) basename-collision rule).
    """
    return (
        logical.startswith(".claude/workflow/")
        and "/" not in logical[len(".claude/workflow/") :]
    )


def _is_workflow_prompt(logical: str) -> bool:
    """Return True iff `logical` is a `.claude/workflow/prompts/` prompt."""
    return logical.startswith(".claude/workflow/prompts/")


# A skill file's logical path is `.claude/skills/<dir>/SKILL.md`. The
# directory-prefixed cross-file key is `<dir>/SKILL.md` (the ref shape
# §1.8(e) prescribes — the path relative to the `.claude/skills/` anchor).
_SKILL_FILE_RE = re.compile(r"^\.claude/skills/(?P<dir>[^/]+)/SKILL\.md$")


def _skill_dir_key(logical: str) -> Optional[str]:
    """Return the `<skill-dir>/SKILL.md` cross-file key for a skill file, else None.

    A skill file lives at `.claude/skills/<dir>/SKILL.md`; its cross-file
    ref target form is `<dir>/SKILL.md` (the path relative to the
    `.claude/skills/` anchor, per §1.8(e)). Keying on the logical
    `.claude/...` path (staged prefix already stripped by the caller) means
    a staged skill copy and its live namesake collapse onto the same key,
    so staged precedence applies exactly as it does for workflow docs and
    prompts. Bare `SKILL.md` is never a valid cross-file target (ambiguous
    across the skill anchors), so this directory-prefixed key is the only
    way a SKILL.md ref resolves.
    """
    match = _SKILL_FILE_RE.match(logical)
    if match is None:
        return None
    return f"{match.group('dir')}/SKILL.md"


def build_file_lookup(parsed: Sequence[ParsedFile]) -> Dict[str, ParsedFile]:
    """Build a lookup table from cross-file ref `name.md` shapes to parsed files.

    The convention from §1.8(e): "The path is relative to the conventional
    anchor (`.claude/workflow/`, `.claude/skills/`)." Cross-file refs use:

    - `conventions.md` → `.claude/workflow/conventions.md`
    - `prompts/technical-review.md` → `.claude/workflow/prompts/technical-review.md`
    - `step-implementation.md` → `.claude/workflow/step-implementation.md`
    - `edit-design/SKILL.md` → `.claude/skills/edit-design/SKILL.md`.
      SKILL.md is never named bare in a cross-file ref (it would be
      ambiguous across the skill anchors); a SKILL.md target is reached
      through its `<skill-dir>/SKILL.md` directory-prefixed key (§1.8(e):
      the path is relative to the `.claude/skills/` anchor). The bare
      `SKILL.md` key the loop still records (all skill files collide on
      it) stays first-match-wins and is never a valid cross-file target.

    Staged-copy precedence. The reads-precedence rule in `conventions.md`
    §1.7(d) — staged copy authoritative when present, established for the
    §1.8 enum bootstrap probe — extends here to cross-file-ref target
    resolution. On a workflow-modifying branch the in-scope
    set contains both the un-annotated live copy of a target and its
    annotated staged copy under
    `docs/adr/<dir>/_workflow/staged-workflow/.claude/...`. A converted
    cross-file ref must subset-validate against the branch's authored
    annotation, which is the staged copy — so when a live copy and a
    staged copy of the SAME logical target resolve to a key, the staged
    copy wins. On a non-workflow-modifying branch (develop, or a branch
    with no staged subtree) no staged copy exists and the lookup stays
    pure-live, so behaviour is unchanged.

    Keys are derived from each file's logical `.claude/...` path (staged
    prefix stripped), so a staged prompt and its live namesake both
    contribute the `prompts/<basename>` key — keying on the raw staged
    path would have hidden the `prompts/` form for staged prompts.

    Multi-staged ambiguity guard. The guard fires only on a genuine
    duplicate: two staged copies of ONE logical target (two plan dirs each
    carrying a staged-workflow subtree of the same `.claude/...` path). In
    that case the script cannot tell which annotation a converted ref
    should validate against, so it halts with
    `AmbiguousBootstrapProbeError` (CLI exit 2) — the same guard the §1.8
    enum bootstrap probe uses for a duplicated staged `conventions.md`.

    Distinct targets that happen to share a basename are NOT a duplicate.
    Several in-scope files collide on a bare-basename key: all seven
    `SKILL.md` anchors key to bare `SKILL.md`, and the workflow-root
    `structural-review.md` and its `prompts/structural-review.md` namesake
    both key to bare `structural-review.md`. These collisions occur on the
    live side too, where the live globs yield more than one file per such
    key. They are resolved by first-match-wins (the first candidate
    recorded for the key keeps it), not rejected. The guard distinguishes
    the two cases by the recorded winner's logical `.claude/...` path: a
    second staged copy halts only when its logical path equals the recorded
    winner's logical path (the genuine "one target, two plan dirs" case);
    otherwise it is a distinct target sharing a key and the first-match-wins
    entry stands. For every basename that is a valid bare cross-file ref
    target (`conventions.md`, `step-implementation.md`, and the like), the
    globs yield at most one file per key, so the first-match-wins clause is
    a no-op there.

    Workflow-root precedence on the bare key (§1.8(e) basename
    collision). One basename collision has a defined winner rather than
    first-match-wins: when a `.claude/workflow/` root doc and a
    `.claude/workflow/prompts/` prompt share a basename
    (`structural-review.md` today), the bare key resolves to the
    workflow-root doc and the prompt is reachable only through its
    `prompts/<name>` key. A prompt therefore claims the bare key only when
    no workflow-root namesake exists (so a prompts-only basename keeps its
    bare key and existing bare prompt refs still resolve), while a
    workflow-root doc claims the bare key unconditionally — regardless of
    parse order, since the glob sorts the prompts path first. SKILL.md
    collisions are untouched: SKILL.md is never a bare cross-file ref
    target and no workflow-root SKILL.md exists, so the override never
    fires for them and they stay first-match-wins. The override is scoped
    to the bare-basename key; the `prompts/<name>` key is unaffected.
    """
    # Resolve per key with staged precedence. The first candidate seeds each
    # key; a staged copy of the SAME logical target overrides a recorded
    # live copy; a second staged copy of that SAME logical target is the
    # ambiguous case the guard rejects. Candidates sharing only a basename
    # (distinct logical targets) keep the first-match-wins entry, except the
    # bare key's workflow-root-over-prompt override below (§1.8(e)).
    lookup: Dict[str, ParsedFile] = {}
    # Per key, the recorded winner's logical `.claude/...` path and whether
    # it is the staged copy. Tracking the logical path tells a genuine "one
    # target, two plan dirs" duplicate apart from two distinct targets that
    # merely share a basename key, on both the live and the staged side.
    winner_logical: Dict[str, str] = {}
    winner_staged: Dict[str, bool] = {}

    def _record(key: str, pf: ParsedFile, is_bare_key: bool) -> None:
        staged = _is_staged(pf)
        logical = _logical_workflow_path(pf)
        if key not in lookup:
            lookup[key] = pf
            winner_logical[key] = logical
            winner_staged[key] = staged
            return
        if logical != winner_logical[key]:
            # A different logical target sharing this basename key (the
            # SKILL.md / structural-review.md collisions, live or staged).
            # Default is first-match-wins, but the bare key gives the
            # workflow-root doc precedence over a colliding prompt
            # (§1.8(e)): the bare form means the workflow-root doc, the prompt is
            # reached via its `prompts/<name>` key. The override is
            # order-independent — it displaces a recorded prompt when the
            # new candidate is the root doc, and refuses to let a prompt
            # displace a recorded root doc.
            if is_bare_key:
                cand_root = _is_workflow_root_doc(logical)
                winner_root = _is_workflow_root_doc(winner_logical[key])
                cand_prompt = _is_workflow_prompt(logical)
                winner_prompt = _is_workflow_prompt(winner_logical[key])
                if cand_root and winner_prompt:
                    lookup[key] = pf
                    winner_logical[key] = logical
                    winner_staged[key] = staged
                    return
                if cand_prompt and winner_root:
                    # The workflow-root doc keeps the bare key.
                    return
            # Every other distinct-target collision: first-match-wins.
            return
        # Same logical target as the recorded winner.
        if not staged:
            # A live copy never displaces the recorded entry; a second live
            # copy of one logical target cannot occur (the globs and the
            # filesystem yield one live file per logical path).
            return
        if winner_staged[key]:
            # Two staged copies of ONE logical target (two plan dirs each
            # carrying a staged-workflow subtree) — halt rather than
            # silently picking one (§1.8 enum-probe guard reuse).
            raise AmbiguousBootstrapProbeError(
                f"Multiple staged copies resolve to cross-file ref key {key!r} — "
                f"target resolution is ambiguous:\n  {lookup[key].path}\n  {pf.path}"
            )
        # The recorded entry is the live copy of this logical target; the
        # staged copy overrides it (staged precedence per §1.7(d)).
        lookup[key] = pf
        winner_staged[key] = True

    for pf in parsed:
        logical = _logical_workflow_path(pf)
        # Bare filename keys: `step-implementation.md`, `conventions.md`.
        _record(Path(logical).name, pf, is_bare_key=True)
        # `prompts/X.md` keys for files under `.claude/workflow/prompts/`,
        # matched on the logical path so staged prompt copies also key here.
        if logical.startswith(".claude/workflow/prompts/"):
            _record(f"prompts/{Path(logical).name}", pf, is_bare_key=False)
        # `<skill-dir>/SKILL.md` key for files under `.claude/skills/`,
        # matched on the logical path so staged skill copies also key here.
        # The bare `SKILL.md` key (recorded above) stays first-match-wins;
        # this directory-prefixed key is the only resolvable SKILL.md target
        # form (§1.8(e)).
        skill_key = _skill_dir_key(logical)
        if skill_key is not None:
            _record(skill_key, pf, is_bare_key=False)
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

    After the staged-mirror exemption below, rule 1 has no reachable
    in-scope target. This is not obvious from `IN_SCOPE_GLOBS` alone:

    - This script's `IN_SCOPE_GLOBS` mix live-path globs
      (`.claude/workflow/**`, `.claude/skills/**`) with staged-workflow-
      mirror globs (`docs/adr/*/_workflow/staged-workflow/.claude/...`).
    - The `docs/adr/` early-return below discards every live path before
      the stamp check, so the only in-scope paths that could reach the
      check are the staged-workflow mirror.
    - The staged mirror is a byte-verbatim copy of the unstamped live
      file per conventions.md §1.7(e), and §1.6(f) excludes staged copies
      from the stamped artifact set. So those copies correctly carry no
      stamp, and the exemption below skips them.

    Nothing else in `IN_SCOPE_GLOBS` is `docs/adr/`-rooted, so after the
    exemption no in-scope path reaches the stamp check via
    `validate` / `--check`. Rule 1 is kept as a harmless guard: if a
    future change re-introduces a non-exempt `docs/adr/`-rooted glob into
    `IN_SCOPE_GLOBS`, this check would resume firing on it.

    The §1.6(f) stamped artifact set (the `_workflow/**` plan
    artifacts) is enforced elsewhere, by the
    `workflow-startup-precheck.sh` drift gate. That set is DISJOINT from
    this script's `IN_SCOPE_GLOBS` (the drift gate does not re-check
    rule 1's staged-mirror target, and this script does not re-check the
    drift gate's stamped set), so rule 1 neither duplicates nor depends
    on the drift gate's enforcement.

    The empty-file and malformed-stamp branches below stay covered by a
    direct-call regression test on a synthetic non-exempt
    `docs/adr/`-rooted `ParsedFile`; no `IN_SCOPE_GLOBS` path reaches
    them after the exemption.
    """
    # Exempt the staged-workflow mirror (conventions.md §1.7(e)): those
    # copies are byte-verbatim duplicates of the unstamped live files and
    # are intentionally absent from the §1.6(f) stamped set, so a missing
    # line-1 stamp is correct, not a defect. Checked before the
    # `docs/adr/` gate below, which would otherwise demand a stamp.
    if _STAGED_SUBTREE_PREFIX_RE.match(parsed.path):
        return []
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
    """Rule 2: exactly one TOC region at the correct anchor; empty TOC accepted for files without `^## ` headings.

    The anchor is one of three shapes per §1.8(d), tried in precedence
    order (H1 > frontmatter > top-of-file):
    - directly under the document H1 (`# Title`) for files that carry a
      real (non-fenced) H1 (workflow docs, plus the one prompt that
      opens with an H1);
    - immediately after the leading YAML frontmatter block for H1-less
      files that carry frontmatter (the frontmatter-bearing skill files);
    - at the top of the file (the TOC delimiter is the first content)
      for prose-first files with neither a real H1 nor leading
      frontmatter (the H1-less prompts).

    "Directly under" / "immediately after" / "at the top" is checked
    loosely: the only content permitted between the anchor and the TOC
    start delimiter is blank lines (or the bootstrap block, when
    present). Prose or another heading between the anchor and the TOC is
    an anchor violation.
    """
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
    # Count only non-fenced delimiters: a `<!--Document index start-->`
    # literal inside a fenced documentation example (such as §1.8(g)'s
    # worked example) is pedagogical text, not a real region.
    start_count = sum(
        1
        for idx, line in enumerate(parsed.lines)
        if line.strip() == TOC_START_DELIMITER
        and not (idx < len(parsed.fenced_lines) and parsed.fenced_lines[idx])
    )
    if start_count > 1:
        return [
            Finding(
                path=parsed.path,
                line=parsed.toc.start_line,
                rule="rule_2",
                explanation=f"file has {start_count} TOC regions; exactly one is required",
            )
        ]
    # Anchor check (§1.8(d)). Three anchor shapes, tried in precedence
    # order: a real (non-fenced) document H1 wins; else the leading YAML
    # frontmatter close for H1-less-but-frontmatter files; else the top
    # of the file for prose-first files with neither. `gap_start_idx` is
    # the 0-based index of the first line that may sit in the
    # anchor→TOC gap (the line after the anchor, or line 0 for the
    # top-of-file shape).
    h1_line = find_first_h1_line(parsed.lines, parsed.fenced_lines)
    if h1_line is not None:
        # H1 takes precedence over frontmatter when a file carries both
        # (no in-scope file does today; §1.8(d) is the durable contract).
        anchor_kind = "H1"
        anchor_line = h1_line  # 1-based
        gap_start_idx = h1_line  # gap starts on the line after the H1
    else:
        fm_close = find_frontmatter_close_line(parsed.lines)
        if fm_close is not None:
            anchor_kind = "frontmatter block"
            anchor_line = fm_close
            gap_start_idx = fm_close  # gap starts after the closing `---`
        else:
            # Prose-first file: neither a real H1 nor leading frontmatter.
            # The anchor is the top of the file — the TOC delimiter must
            # be the first content, before any leading prose.
            anchor_kind = "top of file"
            anchor_line = 0
            gap_start_idx = 0
    # The TOC must come after the anchor, never before it. (Trivially
    # satisfied for the top-of-file shape where gap_start_idx == 0.)
    if parsed.toc.start_line <= anchor_line:
        return [
            Finding(
                path=parsed.path,
                line=parsed.toc.start_line,
                rule="rule_2",
                explanation=(
                    f"TOC region precedes the {anchor_kind} (line {anchor_line}); "
                    "it must be anchored immediately after it"
                ),
            )
        ]
    # Between the anchor and the TOC start, the only permitted content is
    # blank lines and the bootstrap block (`## Reading workflow files
    # (TOC protocol)` heading plus its body). The bootstrap block, when
    # present, sits above the TOC region per §"Bootstrap protocol" →
    # §"Block placement and stability". Once a non-fenced bootstrap
    # heading appears, the remainder of the gap up to the delimiter is
    # treated as bootstrap-block body and not re-validated (§1.8(d)). Any
    # other non-blank content before the bootstrap heading is an anchor
    # violation. Fenced lines are skipped so a fenced `##`/heading or a
    # fenced bootstrap-heading literal in the gap is never mistaken for
    # real content (consistent with the fence exclusion every other
    # heading/delimiter consumer in this script applies).
    #
    # `gap_start_idx` and `parsed.toc.start_line` map to the 0-based
    # slice [gap_start_idx, start_line - 1).
    for idx in range(gap_start_idx, parsed.toc.start_line - 1):
        if idx < len(parsed.fenced_lines) and parsed.fenced_lines[idx]:
            continue
        stripped = parsed.lines[idx].strip()
        if stripped == "":
            continue
        if stripped == BOOTSTRAP_BLOCK_HEADING:
            # Everything from the bootstrap heading onward in the gap is
            # bootstrap-block body — accept the remainder and stop scanning.
            break
        # Non-blank, non-bootstrap content before any bootstrap heading.
        return [
            Finding(
                path=parsed.path,
                line=parsed.toc.start_line,
                rule="rule_2",
                explanation=(
                    f"TOC region is not anchored at the {anchor_kind} "
                    f"(line {anchor_line}); only blank lines and the bootstrap block "
                    "may separate them"
                ),
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

    Two citing scopes are validated:

    - **In-scope workflow files** (`IN_SCOPE_GLOBS`): all eight rules
      apply. These are workflow docs, prompts, the 7 workflow-referencing
      SKILL.md files, and their staged copies. Staged *agent* copies are
      partitioned out of this scope below (see the next bullet).
    - **Agent files** — both live `.claude/agents/*.md` and staged
      `.claude/agents/` copies under the §1.7(a) subtree: only rules 6 and
      7 apply. Agent files carry the bootstrap block and suffix-annotated
      outgoing refs but no TOC, per-section annotations, or workflow-sha
      stamp (§1.6(f): only `_workflow/**` artifacts are stamped) — so
      rules 1/2/3/4/5/8 are gated off for them. Live agents are not in
      `IN_SCOPE_GLOBS` at all; a staged agent IS matched by the
      staged-agents glob in `IN_SCOPE_GLOBS` once §1.7(e) stages agents, so
      it is pulled out of `parsed_files` here and routed into the same
      rules-6/7-only scope, validating exactly like its live namesake.

    `files_filter` is the optional `--files` scope. When provided, the
    validator parses every in-scope file AND every agent file (so
    cross-file refs can resolve targets that fall outside the filter) but
    only emits findings for citing files whose repo-relative path appears
    in the filter set. Paths in `files_filter` that are not in either
    scope are silently dropped per design.md §"Validation rules" →
    "Discovery-glob filter".

    Returns a list of findings sorted by (path, line, rule).
    """
    enums = load_bootstrap_enums(repo_root)
    in_scope_files = parse_in_scope_files(repo_root)
    # Partition the in-scope set: a staged agent (matched by the
    # `IN_SCOPE_GLOBS` staged-agents glob once §1.7(e) stages agents) must
    # validate like a live agent — rules 6/7 only — not through the eight-rule
    # loop below, which would over-fire rules 2/4/8 on it (un-annotated
    # `##`/`###` headings, no TOC region, bare `§X.Y` in-file refs); rules
    # 1/3/5 are already structurally unreachable on a staged agent (rule 1's
    # staged-mirror exemption, no TOC region for rule 3, no well-formed
    # annotation for rule 5 to validate), so the partition suppresses 2/4/8 and
    # future-proofs the rest. Pull staged agents out
    # of `parsed_files` and into the agent citing scope alongside the live
    # agents. On a non-workflow-modifying tree (no staged subtree) this
    # partition is a no-op and `parsed_files` is the full in-scope set.
    parsed_files = [pf for pf in in_scope_files if not _is_staged_agent(pf)]
    staged_agent_files = [pf for pf in in_scope_files if _is_staged_agent(pf)]
    parsed_by_path = {pf.path: pf for pf in parsed_files}
    # Live + staged agent files form a separate citing scope (rules 6/7 only).
    # They are parsed for cross-file ref scanning and bootstrap presence but
    # are NOT added to the file_lookup keyspace: an agent file is never a
    # valid cross-file ref TARGET (an agent-file-as-target is backtick-
    # wrapped, not suffixed), so building the lookup from the in-scope set
    # alone keeps agent basenames out of the target keyspace. The lookup
    # the agents' rule-6 scan consults is the in-scope workflow lookup, which
    # is exactly the set of valid suffix targets.
    parsed_agent_files = [
        parse_file(p, repo_root) for p in discover_agent_citing_files(repo_root)
    ] + staged_agent_files
    parsed_agent_by_path = {pf.path: pf for pf in parsed_agent_files}
    file_lookup = build_file_lookup(parsed_files)
    # A staged agent's own path joins the bootstrap-presence scope so rule 7
    # (`## Reading workflow files (TOC protocol)` presence) fires on it exactly
    # as it does on its live namesake. `discover_bootstrap_scope` walks only the
    # live `.claude/agents/` directory, so the staged copy's path would
    # otherwise be absent and rule 7 would silently skip it.
    bootstrap_paths = frozenset(
        p.resolve().relative_to(repo_root.resolve()).as_posix()
        for p in discover_bootstrap_scope(repo_root)
    ) | frozenset(pf.path for pf in staged_agent_files)
    if files_filter is not None:
        # Normalise to repo-relative POSIX paths and silently drop
        # out-of-scope entries (neither in-scope nor an agent file).
        scoped: List[str] = []
        for raw in files_filter:
            normalised = _normalise_file_path(raw, repo_root)
            if normalised is None:
                continue
            if normalised in parsed_by_path or normalised in parsed_agent_by_path:
                scoped.append(normalised)
        target_paths: FrozenSet[str] = frozenset(scoped)
    else:
        target_paths = frozenset(parsed_by_path.keys()) | frozenset(
            parsed_agent_by_path.keys()
        )
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
    # Agent files: rules 6 and 7 only (the per-rule applicability gate).
    # Rules 1/2/3/4/5/8 are deliberately omitted — they do not apply to
    # agent files (the schema keeps agents refs-only: no stamp, no TOC, no
    # per-section annotations, no in-file auto-stamping).
    for pf in parsed_agent_files:
        if pf.path not in target_paths:
            continue
        findings.extend(check_rule_6_cross_file_refs(pf, file_lookup))
        findings.extend(check_rule_7_bootstrap_block(pf, bootstrap_paths))
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
# `--write` mode: TOC region rebuild + in-file ref auto-stamp.
#
# Two mutations land per in-scope file:
#
# 1. The TOC region between the delimiter comments is regenerated from
#    the file's current annotations. One row per `^## ` / `^### `
#    heading in document order; the bootstrap-block heading is exempt
#    per rule 3 / §1.8(c). A file without delimiter comments is a
#    no-op for the TOC half — `--write` does NOT inject a TOC into a
#    file that never had one (the universal annotation rollout owns
#    first-touch delimiter placement).
# 2. Every in-file `§X.Y[(z)][:roles:phases]` ref is rewritten to
#    carry the target heading's current annotation as its suffix. The
#    rewrite skips fenced code blocks and inline backtick spans per
#    §1.8(e). It also skips refs inside the TOC region (those are
#    TOC anchors, not in-prose refs — they get rebuilt by the TOC
#    half above).
#
# Atomicity. `compute_write_plan` runs in two passes: it parses every
# in-scope file and computes the proposed mutations, raising
# `UnresolvedInFileRefError` on the first unresolved in-file ref. The
# CLI converts that exception to exit 2 with no disk writes anywhere.
# Only when every file resolves cleanly does `apply_write_plan` push
# the new content to disk. This matches the "halt-on-unresolved"
# contract from design.md §"Reindex script" → §"--write mode".
#
# Idempotence. The TOC rebuild is deterministic from the headings +
# annotations; running `--write` twice produces the same TOC body
# both times. In-file ref rewrites land the target's current
# annotation; a second pass observes the freshly-stamped ref already
# matching the target and emits no change. The CLI exits 0 in both
# cases (clean run; mutations applied OR no mutations needed).
# ---------------------------------------------------------------------------


class UnresolvedInFileRefError(RuntimeError):
    """Raised when an in-file ref does not resolve to a heading in the same file.

    Carries `(path, line, anchor)` tuples for every unresolved ref the
    scanner found so the CLI can render a useful exit-2 message. The
    halt is atomic: a single unresolved ref in any in-scope file blocks
    every other file's writes.
    """

    def __init__(self, sites: Sequence[Tuple[str, int, str]]):
        self.sites = tuple(sites)
        message = "; ".join(
            f"{path}:{line}: unresolved {anchor} — no matching heading in this file"
            for path, line, anchor in self.sites
        )
        super().__init__(message)


@dataclass
class FileWritePlan:
    """Per-file mutation plan for a single `--write` pass.

    `new_lines` is the rewritten file content (no trailing newline
    enforcement — the writer preserves the original file's trailing-
    newline shape). `changed` is True iff `new_lines` differs from
    the file's current content; the writer skips no-diff files.
    """

    parsed: ParsedFile
    new_lines: List[str]
    changed: bool


def _build_toc_rows(parsed: ParsedFile) -> List[str]:
    """Return the rebuilt TOC table lines for `parsed` (no delimiter comments).

    Includes the header row, the separator row, and one data row per
    `^## ` / `^### ` heading in document order. The bootstrap-block
    heading is exempt per §1.8(c). Headings with no well-formed
    annotation contribute `(missing)` placeholders in the role / phase
    cells and an empty summary cell — rules 4 / 5 catch those, so the
    `--write` pass does not refuse to run on a file that has other
    findings.

    A file with no non-bootstrap H2/H3 headings yields an empty table
    (just the delimiter pair with no rows between them — the caller
    is responsible for emitting the surrounding `<!--Document index
    start-->` / `<!--Document index end-->` comments).
    """
    data_rows: List[str] = []
    for h in parsed.headings:
        if h.is_bootstrap:
            continue
        section = _heading_to_section_label(h)
        if h.annotation is not None and h.annotation.well_formed:
            roles = ",".join(h.annotation.roles or ())
            phases = ",".join(h.annotation.phases or ())
            summary = h.annotation.summary or ""
        else:
            roles = "(missing)"
            phases = "(missing)"
            summary = ""
        data_rows.append(f"| {section} | {roles} | {phases} | {summary} |")
    if not data_rows:
        return []
    header = "| Section | Roles | Phases | Summary |"
    separator = "|---|---|---|---|"
    return [header, separator, *data_rows]


def _rebuild_toc_region(parsed: ParsedFile) -> Optional[List[str]]:
    """Return the file's new line list with the TOC region rebuilt, or None.

    Returns `None` when the file has no TOC region (no delimiter
    comments) — `--write` does not inject a TOC into a file that
    never had one (the universal annotation rollout owns first-touch
    delimiter placement).

    The rebuilt region preserves the start / end delimiter lines and
    flanking blank lines: the new content between the delimiters is
    a blank line, the table body, and a blank line (matching the
    canonical shape per `conventions.md §1.8(d)`). When the file has
    no non-bootstrap H2/H3 headings, the rebuilt region carries only
    the blank lines and no table — rule 2 accepts an empty TOC for
    such files.
    """
    if parsed.toc is None:
        return None
    rows = _build_toc_rows(parsed)
    # `parsed.toc.start_line` and `parsed.toc.end_line` are 1-based;
    # convert to 0-based indices for slicing.
    start_idx = parsed.toc.start_line - 1
    end_idx = parsed.toc.end_line - 1
    # Keep the start delimiter line and everything before it; replace
    # the body between the delimiters; keep the end delimiter line and
    # everything after it. Sandwich the body with blank lines to match
    # the §1.8(d) shape.
    body: List[str] = [""]
    body.extend(rows)
    body.append("")
    new_lines = list(parsed.lines[: start_idx + 1]) + body + list(parsed.lines[end_idx:])
    return new_lines


def _compute_in_file_ref_rewrites(
    parsed: ParsedFile,
) -> Tuple[List[Tuple[int, int, int, str]], List[Tuple[int, str]]]:
    """Return `(rewrites, unresolved)` for in-file refs in `parsed`.

    `rewrites` is a list of `(line_idx_0based, col_start, col_end,
    new_text)` tuples — one per ref that needs a suffix change. The
    tuples are sorted by `(line_idx, col_start)` so the caller can
    apply them in descending-position order without later edits
    shifting earlier-edit offsets.

    `unresolved` is a list of `(line_no_1based, anchor)` tuples for
    refs whose target does not resolve in this file. A non-empty
    `unresolved` triggers the halt-on-unresolved contract; the
    caller wraps these into `UnresolvedInFileRefError` and aborts.

    Refs already carrying the target's current annotation as a suffix
    contribute nothing to `rewrites` — idempotence falls out of
    "no diff" → "no rewrite".
    """
    rewrites: List[Tuple[int, int, int, str]] = []
    unresolved: List[Tuple[int, str]] = []
    for line_no, col, m in scan_in_file_refs(parsed):
        major = m.group("major")
        minor = m.group("minor")
        sub = m.group("sub")
        anchor_text = f"§{major}.{minor}" + (f"({sub})" if sub else "")
        target = resolve_anchor(parsed.headings, major, minor, sub)
        if target is None:
            unresolved.append((line_no, anchor_text))
            continue
        if target.annotation is None or not target.annotation.well_formed:
            # No well-formed annotation on the target — rule 8 reports
            # this as a finding under `--check`, but `--write` cannot
            # derive a suffix to stamp. Skip the rewrite; the author
            # fixes the target's annotation (rule 4 / rule 5) and
            # re-runs `--write`.
            continue
        expected_roles = ",".join(target.annotation.roles or ())
        expected_phases = ",".join(target.annotation.phases or ())
        new_text = f"{anchor_text}:{expected_roles}:{expected_phases}"
        # `m.end()` is the 0-based end-exclusive column of the existing
        # ref (with whatever stale suffix it already carried, if any).
        # The replacement text replaces the entire `§X.Y[(z)][:r:p]`
        # span — both the anchor and any prior suffix.
        if m.group(0) == new_text:
            continue  # already correct — idempotence
        rewrites.append((line_no - 1, col, m.end(), new_text))
    rewrites.sort(key=lambda x: (x[0], x[1]))
    return rewrites, unresolved


def _apply_line_rewrites(
    lines: List[str], rewrites: Sequence[Tuple[int, int, int, str]]
) -> List[str]:
    """Apply column-range rewrites to lines, descending-position order per line.

    `rewrites` carries `(line_idx, col_start, col_end, new_text)` tuples
    sorted by `(line_idx, col_start)`. Within each line we apply edits
    in descending `col_start` order so an earlier edit cannot shift a
    later edit's offsets.
    """
    out = list(lines)
    # Group rewrites by line, then apply descending-col within each line.
    by_line: Dict[int, List[Tuple[int, int, str]]] = {}
    for line_idx, col_start, col_end, new_text in rewrites:
        by_line.setdefault(line_idx, []).append((col_start, col_end, new_text))
    for line_idx, edits in by_line.items():
        edits.sort(key=lambda x: x[0], reverse=True)
        line = out[line_idx]
        for col_start, col_end, new_text in edits:
            line = line[:col_start] + new_text + line[col_end:]
        out[line_idx] = line
    return out


def compute_write_plan(
    repo_root: Path,
    files_filter: Optional[Sequence[str]] = None,
) -> Dict[str, FileWritePlan]:
    """Compute the full `--write` plan across the in-scope set.

    Runs the TOC rebuild and in-file ref rewrite passes in memory for
    every in-scope file (or every file in `files_filter` when that
    argument is supplied). Raises `UnresolvedInFileRefError` on the
    first file with one or more unresolved in-file refs — the caller
    must NOT proceed to disk writes. The error carries every
    unresolved site discovered across the whole pass, not just the
    first; the author then sees the full list rather than playing
    whack-a-mole.

    Returns a dict keyed by repo-relative POSIX path. Each
    `FileWritePlan` carries the proposed new line list plus a
    `changed` flag (True iff the new content differs from the file's
    current content). The caller filters by `changed` before writing.
    """
    # Exclude staged agents from the write plan exactly as `validate`
    # excludes them from the eight-rule loop: agents are refs-only (no TOC,
    # no in-file refs to auto-stamp), so a staged agent must stay TOC-inert
    # under `--write` like its live namesake. Without this filter the
    # staged-agents glob (live once §1.7(e) stages agents) would route the
    # staged agent through the TOC rebuild + rule-8 stamp passes below.
    parsed_files = [
        pf for pf in parse_in_scope_files(repo_root) if not _is_staged_agent(pf)
    ]
    parsed_by_path = {pf.path: pf for pf in parsed_files}
    if files_filter is not None:
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
    plan: Dict[str, FileWritePlan] = {}
    unresolved_sites: List[Tuple[str, int, str]] = []
    for pf in parsed_files:
        if pf.path not in target_paths:
            continue
        # Compute in-file ref rewrites first — unresolved refs short-
        # circuit the whole pass via the exception below.
        rewrites, unresolved = _compute_in_file_ref_rewrites(pf)
        for line_no, anchor in unresolved:
            unresolved_sites.append((pf.path, line_no, anchor))
        # The TOC rebuild happens on the post-ref-rewrite line list so
        # in-file ref edits inside the TOC region (there shouldn't be
        # any — `scan_in_file_refs` skips the TOC region — but defence-
        # in-depth) compose cleanly with the TOC rewrite.
        new_lines = _apply_line_rewrites(pf.lines, rewrites)
        # Apply TOC rebuild against the rewritten line list. Build a
        # transient ParsedFile-shaped view so `_rebuild_toc_region`
        # can read the start/end delimiter positions and TOC rows.
        # The fence/heading positions are byte-for-byte stable across
        # the in-file ref rewrites — the ref edits only change the
        # column count past `§X.Y`, not heading lines or fence lines —
        # so reusing the original `parsed.toc` line numbers is safe.
        toc_rebuilt = _rebuild_toc_region(
            ParsedFile(
                path=pf.path,
                abs_path=pf.abs_path,
                lines=new_lines,
                headings=pf.headings,
                toc=pf.toc,
                fenced_lines=pf.fenced_lines,
            )
        )
        if toc_rebuilt is not None:
            new_lines = toc_rebuilt
        plan[pf.path] = FileWritePlan(
            parsed=pf,
            new_lines=new_lines,
            changed=(new_lines != pf.lines),
        )
    if unresolved_sites:
        raise UnresolvedInFileRefError(unresolved_sites)
    return plan


def _read_trailing_newline(path: Path) -> bool:
    """Return True iff the file on disk ends with a `\\n`.

    `splitlines()` drops the trailing terminator, so we re-read the
    raw bytes to preserve the original file's "ends-with-newline" or
    "does-not" shape across the write. New files (no on-disk content
    yet) default to True — every workflow file in the project ends
    with a newline.
    """
    try:
        data = path.read_bytes()
    except OSError:
        return True
    return data.endswith(b"\n")


def apply_write_plan(plan: Dict[str, FileWritePlan]) -> List[str]:
    """Write the planned mutations to disk.

    Returns the list of repo-relative paths whose content actually
    changed. Files with `changed=False` are skipped — idempotence
    relies on the no-op-write here, otherwise the file's mtime would
    flap on every `--write` run.
    """
    written: List[str] = []
    for path, fwp in plan.items():
        if not fwp.changed:
            continue
        trailing_newline = _read_trailing_newline(fwp.parsed.abs_path)
        body = "\n".join(fwp.new_lines)
        if trailing_newline:
            body += "\n"
        fwp.parsed.abs_path.write_text(body, encoding="utf-8")
        written.append(path)
    return written


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
        help=(
            "Rebuild TOC regions and auto-stamp in-file `§X.Y(z)` "
            "refs. Halts atomically with exit 2 if any in-file ref "
            "is unresolved."
        ),
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
        try:
            plan = compute_write_plan(REPO_ROOT, files_filter=args.files)
        except AmbiguousBootstrapProbeError as exc:
            print(f"error: {exc}", file=sys.stderr)
            return 2
        except UnresolvedInFileRefError as exc:
            # Halt-on-unresolved contract: no writes anywhere, exit 2,
            # message lists every site so the author can fix in one pass.
            for path, line, anchor in exc.sites:
                print(
                    f"{path}:{line}:rule_8: unresolved {anchor} — "
                    "no matching heading in this file",
                    file=sys.stderr,
                )
            print(
                "error: --write halted; fix the unresolved refs and re-run.",
                file=sys.stderr,
            )
            return 2
        written = apply_write_plan(plan)
        for path in written:
            print(path)
        return 0
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
