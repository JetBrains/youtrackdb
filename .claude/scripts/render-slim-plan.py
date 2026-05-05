#!/usr/bin/env python3
"""Slim plan rendering for sub-agent contexts.

Implements the rule defined in
`.claude/workflow/plan-slim-rendering.md`. Reads an implementation plan
and writes a filtered snapshot for sub-agent prompts that need
strategic context but should not see completed-track implementation
detail.

Usage:
    python3 .claude/scripts/render-slim-plan.py \\
        --plan-path docs/adr/<dir>/_workflow/implementation-plan.md \\
        [--out /tmp/claude-code-plan-slim-$PPID.md] \\
        [--quiet]

If --out is omitted the script writes to
`/tmp/claude-code-plan-slim-<ppid>.md` where `<ppid>` is this process's
parent PID — matching the convention in plan-slim-rendering.md so
concurrent sessions do not collide.

Output:
    Stdout: one-line summary of tracks rendered (suppressed by --quiet).
    Stderr: errors only.

Exit codes:
    0 - success
    1 - malformed input (parser refused to render the snapshot)
    2 - I/O error reading the plan or writing the snapshot
"""

import argparse
import os
import re
import sys
from typing import List, Optional, Set, Tuple


# Keywords that mark the start of a named subsection inside a track's
# blockquote body. Per `plan-slim-rendering.md § How to identify the
# intro paragraph` this is the closed set; any other `**Foo:**` text is
# treated as ordinary intro content.
SUBSECTION_KEYWORDS: Set[str] = {
    "What",
    "How",
    "Constraints",
    "Interactions",
    "Scope",
    "Depends on",
    "Track episode",
    "Step file",
    "Skipped",
    "Strategy refresh",
}

# Per-status keep/drop rules. The spec table in plan-slim-rendering.md
# §Rendering rule lists Keep and Drop keywords explicitly; the union
# below covers every keyword in SUBSECTION_KEYWORDS so a keyword on the
# "wrong" side of a status (e.g. Skipped on a [x] track) is silently
# dropped rather than aborting on real-world plans whose [x] entries
# were never collapsed on disk.
COMPLETED_KEEP: Set[str] = {"Track episode", "Strategy refresh"}
COMPLETED_DROP: Set[str] = {
    "Scope",
    "Depends on",
    "Step file",
    "What",
    "How",
    "Constraints",
    "Interactions",
    "Skipped",
}

SKIPPED_KEEP: Set[str] = {"Skipped", "Strategy refresh"}
SKIPPED_DROP: Set[str] = {
    "Scope",
    "Depends on",
    "Step file",
    "What",
    "How",
    "Constraints",
    "Interactions",
    "Track episode",
}

# Track header: `- [STATUS] Title`. Status is space, x, or ~. `[>]` is
# Phase-4-only (per conventions.md §1.2) and never appears on a track
# entry — explicitly rejected below.
_TRACK_HEADER_RE = re.compile(r"^- \[(?P<status>[ x~>])\] (?P<title>.+)$")

# Blockquote prefix on track entries: exactly two leading spaces + `>`.
_BLOCKQUOTE_PREFIX_RE = re.compile(r"^  >")

# Subsection start. Real plans use `**Keyword:**` (colon inside the
# bold); the spec text in plan-slim-rendering.md uses the informal form
# `**Keyword**:` (colon outside). Accept both.
_SUBSECTION_LINE_RE = re.compile(
    r"^  > \*\*(?P<keyword>[^*:]+)(?::\*\*|\*\*:)"
)

# Code fences (CommonMark). Backtick fences forbid backticks in the info
# string; tilde fences accept any info string. Two regexes keep the
# contracts separate so a stray backtick in a markdown sample doesn't
# get misread as a fence opener.
_BACKTICK_FENCE_RE = re.compile(r"^[ ]{0,3}(`{3,})([^`]*)$")
_TILDE_FENCE_RE = re.compile(r"^[ ]{0,3}(~{3,})(.*)$")


class PlanParseError(Exception):
    """Raised when the plan does not match the expected structure."""

    def __init__(self, message: str, line_num: Optional[int] = None) -> None:
        if line_num is not None:
            message = f"line {line_num}: {message}"
        super().__init__(message)


def _parse_code_fence(line: str) -> Optional[Tuple[str, int]]:
    m = _BACKTICK_FENCE_RE.match(line)
    if m is not None:
        return "`", len(m.group(1))
    m = _TILDE_FENCE_RE.match(line)
    if m is not None:
        return "~", len(m.group(1))
    return None


def _fence_state(lines: List[str]) -> List[bool]:
    """For each line, True if the line is inside a fenced code block.

    Used so heading-shaped lines inside a fenced markdown sample don't
    mis-trigger the section parser.
    """
    inside_flags: List[bool] = []
    open_char: Optional[str] = None
    open_len = 0
    for line in lines:
        if open_char is not None:
            inside_flags.append(True)
            fence = _parse_code_fence(line)
            if fence is not None and fence[0] == open_char and fence[1] >= open_len:
                open_char = None
                open_len = 0
        else:
            inside_flags.append(False)
            fence = _parse_code_fence(line)
            if fence is not None:
                open_char, open_len = fence
    return inside_flags


def _is_blank_blockquote(line: str) -> bool:
    """A blank blockquote line: `  >` followed only by whitespace."""
    return line.startswith("  >") and line[3:].strip() == ""


def _split_plan(
    lines: List[str],
) -> Tuple[List[str], List[str], List[str]]:
    """Split plan lines into (pre, checklist_body, post).

    `checklist_body` starts with the `## Checklist` line and runs up to
    (but not including) the next H2 heading. `post` is everything from
    that heading to EOF — empty if no further H2 exists.
    """
    inside = _fence_state(lines)

    checklist_idx: Optional[int] = None
    for i, line in enumerate(lines):
        if inside[i]:
            continue
        if line.rstrip() == "## Checklist":
            checklist_idx = i
            break
    if checklist_idx is None:
        raise PlanParseError("plan has no '## Checklist' section")

    next_h2_idx: Optional[int] = None
    for j in range(checklist_idx + 1, len(lines)):
        if inside[j]:
            continue
        if lines[j].startswith("## "):
            next_h2_idx = j
            break

    if next_h2_idx is None:
        return lines[:checklist_idx], lines[checklist_idx:], []
    return (
        lines[:checklist_idx],
        lines[checklist_idx:next_h2_idx],
        lines[next_h2_idx:],
    )


def _parse_checklist_body(
    body_lines: List[str], body_offset: int
) -> Tuple[str, List[Tuple[str, str, List[str], int]]]:
    """Parse a checklist body into (header_line, entries).

    Each entry is `(status, header_line, blockquote_lines, header_line_num)`.
    `body_offset` is the 1-based line number of `body_lines[0]` in the
    full plan — used for error messages.
    """
    if not body_lines or body_lines[0].rstrip() != "## Checklist":
        raise PlanParseError(
            "checklist body must start with '## Checklist'", body_offset
        )

    header = body_lines[0]
    entries: List[Tuple[str, str, List[str], int]] = []
    i = 1
    n = len(body_lines)

    while i < n:
        line = body_lines[i]
        if line.strip() == "":
            i += 1
            continue
        m = _TRACK_HEADER_RE.match(line)
        if m is None:
            raise PlanParseError(
                f"unexpected line in checklist (not a track header or "
                f"blockquote continuation): {line!r}",
                body_offset + i,
            )
        status = m.group("status")
        if status == ">":
            raise PlanParseError(
                f"'[>]' marker is invalid on a track entry per "
                f"conventions.md §1.2: {line!r}",
                body_offset + i,
            )
        header_line = line
        header_line_num = body_offset + i
        i += 1

        block: List[str] = []
        while i < n and _BLOCKQUOTE_PREFIX_RE.match(body_lines[i]):
            block.append(body_lines[i])
            i += 1

        entries.append((status, header_line, block, header_line_num))

    return header, entries


def _parse_blockquote_body(
    block_lines: List[str], header_line_num: int
) -> Tuple[List[str], List[Tuple[str, List[str]]]]:
    """Split an entry's blockquote body into (intro_lines, subsections).

    Each subsection is `(keyword, lines)` where the first line is the
    `> **Keyword:**` opener. Trailing blank `>` lines within each block
    are stripped so `_render_filtered` can emit a single `>` separator
    between kept blocks without doubling them.
    """
    blocks: List[Tuple[Optional[str], List[str]]] = [(None, [])]
    seen_keywords: Set[str] = set()

    for line in block_lines:
        m = _SUBSECTION_LINE_RE.match(line)
        if m is not None:
            keyword = m.group("keyword").strip()
            if keyword in SUBSECTION_KEYWORDS:
                if keyword in seen_keywords:
                    raise PlanParseError(
                        f"duplicate '**{keyword}:**' subsection in track entry",
                        header_line_num,
                    )
                seen_keywords.add(keyword)
                blocks.append((keyword, [line]))
                continue
        blocks[-1][1].append(line)

    for _, block_lines_inner in blocks:
        while block_lines_inner and _is_blank_blockquote(block_lines_inner[-1]):
            block_lines_inner.pop()

    intro: List[str] = []
    if blocks[0][0] is None and blocks[0][1]:
        intro = blocks[0][1]
    subsections: List[Tuple[str, List[str]]] = [
        (kw, lines) for kw, lines in blocks if kw is not None
    ]

    return intro, subsections


def _render_full(header: str, block_lines: List[str]) -> List[str]:
    """`[ ]` track: emit verbatim — the rule is a no-op for these."""
    return [header] + list(block_lines)


def _render_filtered(
    status: str,
    header: str,
    intro: List[str],
    subsections: List[Tuple[str, List[str]]],
) -> List[str]:
    """`[x]` or `[~]` track: keep intro + the spec's keep list, drop the rest."""
    if status == "x":
        keep = COMPLETED_KEEP
    elif status == "~":
        keep = SKIPPED_KEEP
    else:
        raise AssertionError(f"_render_filtered called with status {status!r}")

    kept_subs = [(kw, lines) for kw, lines in subsections if kw in keep]

    out_lines: List[str] = [header]
    blocks_to_emit: List[List[str]] = []
    if intro:
        blocks_to_emit.append(intro)
    for _, sub_lines in kept_subs:
        blocks_to_emit.append(sub_lines)

    for j, b in enumerate(blocks_to_emit):
        if j > 0:
            out_lines.append("  >")
        out_lines.extend(b)

    return out_lines


def render_slim(plan_text: str) -> Tuple[str, dict]:
    """Render a plan into its slim form.

    Returns `(slim_text, stats)`. `stats` carries totals per status so
    the caller can print a one-line summary.
    """
    lines = plan_text.splitlines()
    has_trailing_newline = plan_text.endswith("\n")

    pre, body, post = _split_plan(lines)
    body_offset = len(pre) + 1
    header, entries = _parse_checklist_body(body, body_offset)

    completed = 0
    skipped = 0
    not_started = 0
    rendered_entries: List[List[str]] = []

    for status, entry_header, block, header_line_num in entries:
        if status == " ":
            rendered_entries.append(_render_full(entry_header, block))
            not_started += 1
        elif status == "x":
            intro, subs = _parse_blockquote_body(block, header_line_num)
            rendered_entries.append(
                _render_filtered("x", entry_header, intro, subs)
            )
            completed += 1
        elif status == "~":
            intro, subs = _parse_blockquote_body(block, header_line_num)
            rendered_entries.append(
                _render_filtered("~", entry_header, intro, subs)
            )
            skipped += 1
        else:
            raise AssertionError(f"unexpected status {status!r}")

    out: List[str] = list(pre)
    out.append(header)
    out.append("")
    for k, entry_lines in enumerate(rendered_entries):
        if k > 0:
            out.append("")
        out.extend(entry_lines)
    if post:
        out.append("")
        out.extend(post)

    slim_text = "\n".join(out)
    if has_trailing_newline:
        slim_text += "\n"

    stats = {
        "total": len(entries),
        "completed": completed,
        "skipped": skipped,
        "not_started": not_started,
    }
    return slim_text, stats


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Render a slim plan snapshot for sub-agent contexts. The "
            "rendering rule is specified in "
            ".claude/workflow/plan-slim-rendering.md."
        ),
    )
    parser.add_argument(
        "--plan-path",
        required=True,
        help="Path to docs/adr/<dir>/_workflow/implementation-plan.md.",
    )
    parser.add_argument(
        "--out",
        default=None,
        help=(
            "Path to write the slim snapshot. Default: "
            "/tmp/claude-code-plan-slim-<ppid>.md (uses this process's "
            "parent PID)."
        ),
    )
    parser.add_argument(
        "--quiet",
        action="store_true",
        help="Suppress the one-line stdout summary on success.",
    )
    args = parser.parse_args()

    out_path = args.out
    if out_path is None:
        out_path = f"/tmp/claude-code-plan-slim-{os.getppid()}.md"

    try:
        with open(args.plan_path, "r", encoding="utf-8") as f:
            plan_text = f.read()
    except OSError as e:
        print(
            f"render-slim-plan: cannot read {args.plan_path}: {e}",
            file=sys.stderr,
        )
        return 2

    try:
        slim_text, stats = render_slim(plan_text)
    except PlanParseError as e:
        print(f"render-slim-plan: malformed plan: {e}", file=sys.stderr)
        return 1

    try:
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(slim_text)
    except OSError as e:
        print(
            f"render-slim-plan: cannot write {out_path}: {e}",
            file=sys.stderr,
        )
        return 2

    if not args.quiet:
        print(
            f"slim plan: {stats['total']} tracks "
            f"({stats['completed']} completed, "
            f"{stats['skipped']} skipped, "
            f"{stats['not_started']} not-started) "
            f"-> {out_path}"
        )

    return 0


if __name__ == "__main__":
    sys.exit(main())
