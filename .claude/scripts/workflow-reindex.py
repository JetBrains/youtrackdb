#!/usr/bin/env python3
"""Mechanical reindex + validation tool for workflow-doc TOC regions.

This script is the schema validator and TOC rebuilder for the
per-section role/phase annotation system defined in
`.claude/workflow/conventions.md §1.8`. It runs in two modes:

- `--check` — validate every in-scope workflow doc against the schema
  and exit nonzero on findings. Used by the pre-commit hook and CI.
- `--write` — rebuild every TOC region in place from the current
  annotations and auto-stamp every in-file `§X.Y(z)` reference with
  the target heading's roles/phases suffix.

Both modes share file enumeration, heading/annotation parsing, TOC
region detection, and the CommonMark fence + inline-backtick state
machine that excludes pedagogical refs inside code spans from
validation and rewriting. This module is structured so that the
shared parsing core (file discovery, heading/annotation/TOC parsing,
fence + inline-backtick state machine, bootstrap probe) is fully
exposed without a CLI surface; the `--check` and `--write` modes are
added by follow-up commits on top of the shared core.

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

The `if __name__ == "__main__"` stub prints the bootstrap output so
the smoke-test runner can exercise the probe end-to-end without
importing the module; the full argparse-driven `--check` /
`--write` / `--files` CLI surface is added by follow-up commits.
"""

from __future__ import annotations

import glob
import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple


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
# CLI entry point.
# ---------------------------------------------------------------------------
#
# The current stub exposes only the bootstrap probe through the CLI.
# Follow-up commits add `--check`, `--write`, and `--files` plus the
# full argparse surface. The stub prints the bootstrap output so the
# smoke-test runner can exercise the probe end-to-end without
# importing the module.
# ---------------------------------------------------------------------------


def main(argv: Optional[Sequence[str]] = None) -> int:
    """Bootstrap-only CLI stub. Returns the process exit code.

    Follow-up commits replace this stub with the argparse-driven
    dispatcher that adds `--check`, `--write`, and `--files`.
    """
    del argv  # Bootstrap-only stub takes no arguments.
    try:
        enums = load_bootstrap_enums(REPO_ROOT)
    except AmbiguousBootstrapProbeError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(f"source: {enums.source}")
    print(f"roles ({len(enums.roles)}): {','.join(enums.roles)}")
    print(f"phases ({len(enums.phases)}): {','.join(enums.phases)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
