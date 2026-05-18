#!/usr/bin/env python3
"""Mechanical checks for design.md mutation discipline.

Implements the structural rules listed in
`.claude/workflow/design-document-rules.md § Mutation discipline § Mechanical
checks (always run)` plus the `dsc-ai-tell` rule that detects the
regex-expressible subset of `house-style.md` AI-tell patterns. Invoked by
the `edit-design` skill after each edit to `design.md` or
`design-mechanics.md`.

Usage:
    python3 .claude/scripts/design-mechanical-checks.py \
        --design-path docs/adr/<dir>/_workflow/design.md \
        [--design-mechanics-path docs/adr/<dir>/_workflow/design-mechanics.md] \
        [--plan-path docs/adr/<dir>/_workflow/implementation-plan.md] \
        [--plan-dir docs/adr/<dir>/_workflow/plan/] \
        [--changed-section "Section Title"] \
        [--target design|mechanics|both] \
        [--scope bounded|whole-doc]

For Phase 4 the design path is `docs/adr/<dir>/design-final.md`
(top-level, outside `_workflow/`); see edit-design/SKILL.md for the
phase4-creation kind.

Output: JSON to stdout. Exit code 0 on PASS, 1 on NEEDS REVISION (any blocker).
"""

import argparse
import json
import os
import re
import sys
from typing import Dict, Iterator, List, Optional, Tuple


# ---------------------------------------------------------------------------
# Section names exempt from the per-section TL;DR + References shape rules.
# These sections have their own format defined elsewhere in the rules.
# ---------------------------------------------------------------------------
SHAPE_EXEMPT_SECTION_NAMES = {
    "Overview",
    "Core Concepts",
    "Class Design",
    "Workflow",
    "TL;DR",  # When used as a Part-level TL;DR section under a `# Part N` heading.
}

# Sub-heading names that are part of the per-section mandatory shape or the
# consolidation form. Excluded from the same-shape sibling similarity
# computation, since otherwise every well-formed section would look identical.
MANDATORY_OR_FORM_SUBHEADINGS = {
    "tl;dr",
    "edge cases / gotchas",
    "edge case / gotchas",
    "gotchas",
    "edge cases",
    "references",
    "comparison",
    "per-instance short bodies",
    "per-stat short bodies",
}

# Severity thresholds.
WARN_SECTION_LINES = 200
BLOCKER_SECTION_LINES = 400
SUGGESTED_SECTION_CAP = 300

LENGTH_TRIGGER_LINES = 2000

TOP_LEVEL_CAP = 15
PER_PART_CAP = 8
PER_PART_WARN = 6

# Overview is the concept-first elevator pitch; past 40 lines it has stopped
# being a pitch.
OVERVIEW_LINE_CAP = 40


# ---------------------------------------------------------------------------
# dsc-ai-tell constants
#
# The `check_dsc_ai_tell` function below implements the subset of
# `house-style.md` patterns detectable by regex. Constants live up here so
# their shape is visible without scrolling through the check body.
# ---------------------------------------------------------------------------

# Tier 1 hard-ban vocabulary lifted verbatim from
# `.claude/output-styles/house-style.md § Tier 1 — hard ban` (29 base words).
# Three entries (`navigate`, `unlock`, `underscore`) carry parenthetical
# qualifications in the style file ("metaphorical", "as a verb meaning shows")
# that a flat regex cannot enforce; the rule fires on all 29 unconditionally
# and the Phase B step episode records the observed false-positive count on
# the calibration ADRs. Demote-to-`suggestion` is the documented fallback if
# real usage shows the qualifications matter.
TIER1_BANNED_VOCAB = [
    "delve",
    "tapestry",
    "pivotal",
    "testament",
    "realm",
    "beacon",
    "vibrant",
    "commendable",
    "paramount",
    "multifaceted",
    "holistic",
    "meticulous",
    "intricate",
    "embark",
    "navigate",
    "unlock",
    "foster",
    "showcase",
    "commence",
    "garner",
    "bolster",
    "enduring",
    "elevate",
    "unwavering",
    "journey",
    "ecosystem",
    "paradigm",
    "underscore",
    "nuanced",
]

# One alternation regex for every Tier-1 base word, case-insensitive, word
# boundaries on both sides. Compiled once at module load.
TIER1_BANNED_VOCAB_RE = re.compile(
    r"\b(" + "|".join(re.escape(w) for w in TIER1_BANNED_VOCAB) + r")\b",
    re.IGNORECASE,
)

# Negative parallelism: "It's not X, it's Y." / "It's not X — it's Y."
# The match is constrained to a single paragraph by the caller (the regex
# itself is not greedy across blank lines because paragraphs are joined
# with a single space before matching).
NEGATIVE_PARALLELISM_RE = re.compile(
    r"\bit'?s not\b.*\bit'?s\b",
    re.IGNORECASE,
)

# Signposting openers from `house-style.md § Signposting`.
SIGNPOSTING_OPENERS_RE = re.compile(
    r"\b(let'?s dive|let'?s break|here'?s what you need)\b",
    re.IGNORECASE,
)

# Copula avoidance from `house-style.md § Copula avoidance`. The style file
# lists more verbs in prose ("acts as", "functions as", "represents"), but
# only "serves as" and "stands as" are listed for the regex pass per the
# track plan; the others are judgment calls left to the cold-read prompt.
COPULA_AVOIDANCE_RE = re.compile(
    r"\b(serves as|stands as)\b",
    re.IGNORECASE,
)

# Persuasive authority tropes from `house-style.md § Persuasive authority
# tropes`. Track plan names three literal phrases.
AUTHORITY_TROPE_RE = re.compile(
    r"\b(at its core|fundamentally|the real question)\b",
    re.IGNORECASE,
)

# Hyphenated-pair cluster: three or more distinct lowercase hyphenated pairs
# in a single comma-separated list. Matches the canonical AI-tell shape
# "fast-paced, well-crafted, next-generation" exactly. Strictly narrower than
# the prose rule in house-style.md "immediately precedes a noun, or sits in a
# comma-separated list of modifiers" — the comma-cluster form catches the
# adjectival-ornament tell while letting legitimate technical compounds in
# adjectival position (e.g., "cache-backed data structures with double-write
# log protection") pass.
HYPHENATED_PAIR_CLUSTER_RE = re.compile(
    r"\b[a-z]+-[a-z]+(?:,\s+[a-z]+-[a-z]+){2,}\b",
    re.IGNORECASE,
)
HYPHENATED_PAIR_CLUSTER_THRESHOLD = 3

# Fragmented-header rule fires when the heading and the immediately
# following one-line paragraph share >=50% content words after stop-word
# stripping. Renamed from `_LEMMA_OVERLAP_` because the implementation
# computes content-word overlap, not morphological lemmatisation.
FRAGMENTED_HEADER_CONTENT_WORD_OVERLAP_THRESHOLD = 0.5

# English stop-word list used by the fragmented-header check. Kept small
# and project-local; pulling in a real NLP dependency would buy nothing
# the overlap heuristic actually uses.
STOP_WORDS: frozenset = frozenset({
    "the", "a", "an",
    "is", "are", "was", "were", "be", "been", "being",
    "of", "for", "to", "in", "on", "at", "by", "from", "with", "as",
    "and", "or", "but", "if", "then",
    "that", "this", "these", "those",
    "it", "its",
})


# ---------------------------------------------------------------------------
# Markdown parsing
# ---------------------------------------------------------------------------


def read_lines(path: str) -> List[str]:
    """Read a file as a list of lines (no trailing newline preserved)."""
    with open(path, "r", encoding="utf-8") as f:
        # Keep newlines stripped — heading regexes don't need them.
        return f.read().splitlines()


# Per CommonMark, a fenced code block opens/closes on a line whose only
# content is a run of 3+ backticks (or tildes) optionally followed by an
# info string:
#   - Backtick fence: info string MAY NOT contain a backtick. So a line
#     beginning with ``` followed by text containing more backticks (e.g.
#     `` ```inline``` more text ``) is NOT a fence opener.
#   - Tilde fence: info string can contain anything except a newline,
#     including backticks, spaces, braces, dotted forms — anything an
#     authoring tool may emit (e.g. ``~~~python {cmd=true}``).
#
# CommonMark allows 0-3 leading spaces of indentation on the fence line
# (4+ spaces would make it an indented code block). Keeping leading-space
# tolerance avoids missing fences in lists or quoted blocks.
#
# Two regexes per fence type so the contracts don't bleed into each other.
_BACKTICK_FENCE_RE = re.compile(r"^[ ]{0,3}(`{3,})([^`]*)$")
_TILDE_FENCE_RE = re.compile(r"^[ ]{0,3}(~{3,})(.*)$")


def parse_code_fence(line: str) -> Optional[Tuple[str, int]]:
    """Return `(char, length)` if `line` is a fenced-code-block delimiter, else None.

    `char` is `` ` `` or `~`; `length` is the run length. The caller uses this to
    track an *open* fence and decide whether a subsequent fence-shaped line
    actually closes it: per CommonMark, a closing fence must use the same
    character as the opener and be at least as long.
    """
    m = _BACKTICK_FENCE_RE.match(line)
    if m is not None:
        return "`", len(m.group(1))
    m = _TILDE_FENCE_RE.match(line)
    if m is not None:
        return "~", len(m.group(1))
    return None


def is_code_fence_line(line: str) -> bool:
    """Return True iff `line` is a fenced-code-block delimiter (opener or closer)."""
    return parse_code_fence(line) is not None


def fence_closes(open_fence: Tuple[str, int], line: str) -> bool:
    """Return True iff `line` is a fence delimiter that closes `open_fence`.

    Per CommonMark, the closing fence must use the same character as the opener
    and be at least as long. A backtick fence is NOT closed by a tilde fence
    of any length, and a 4-char ```` ```` fence is NOT closed by a 3-char ``` fence.
    """
    parsed = parse_code_fence(line)
    if parsed is None:
        return False
    char, length = parsed
    open_char, open_len = open_fence
    return char == open_char and length >= open_len


def parse_sections(lines: List[str]) -> Tuple[List[Dict], List[Dict]]:
    """Parse a markdown file into a list of `## ` sections and `# Part N` parts.

    Returns (sections, parts) where:
        sections: list of dicts with keys
            title, line_start (1-based), line_end (1-based, inclusive),
            parent_part (str or None), sub_headings (list of str)
        parts: list of dicts with keys
            title, line_start, line_end (set to last line covered)

    Headings inside fenced code blocks (```...```) are ignored.
    """
    sections: List[Dict] = []
    parts: List[Dict] = []
    current_part: Optional[Dict] = None
    current_section: Optional[Dict] = None
    open_fence: Optional[Tuple[str, int]] = None
    # The first heading-shaped line (any level) outside fences is the document's
    # entry point. If it's H1, it's the document title — consume it so a feature
    # title that happens to match the `Part \d+` shape (e.g. `# Part 5: Memory`)
    # is not misclassified as a Part grouping heading. If the first heading is
    # H2+ (designs without a level-1 title), fall through to normal processing.
    first_heading_seen = False

    for i, line in enumerate(lines, start=1):
        # Fenced code block tracking — per CommonMark, the closing fence must
        # use the same character as the opener and be at least as long.
        # Tracking just an `in_fence` bool would mis-handle nested fences
        # (e.g. ```` ``` `` example outer, ``` `` ``` inner) and treat the
        # inner fence as a close, exposing the inner content's `##` lines as
        # phantom sections.
        if open_fence is None:
            parsed = parse_code_fence(line)
            if parsed is not None:
                open_fence = parsed
                continue
        else:
            if fence_closes(open_fence, line):
                open_fence = None
            continue

        if not first_heading_seen and re.match(r"^#{1,6}\s", line):
            first_heading_seen = True
            if re.match(r"^# ", line):
                # Document title — skip so the Part regex below doesn't fire.
                continue
            # First heading is H2+ — fall through; ## handler picks it up.

        # `# Part N — name` (or any other top-level `# ` heading after the title).
        m_part = re.match(r"^# (Part \d+\b.*)$", line)
        if m_part:
            # Close any open section.
            if current_section is not None:
                current_section["line_end"] = i - 1
                sections.append(current_section)
                current_section = None
            # Close the previous part.
            if current_part is not None:
                current_part["line_end"] = i - 1
            current_part = {
                "title": m_part.group(1).strip(),
                "line_start": i,
                "line_end": None,
            }
            parts.append(current_part)
            continue

        # `## ` section heading.
        m_section = re.match(r"^## (.+?)\s*$", line)
        if m_section:
            if current_section is not None:
                current_section["line_end"] = i - 1
                sections.append(current_section)
            current_section = {
                "title": m_section.group(1).strip(),
                "line_start": i,
                "line_end": None,
                "parent_part": current_part["title"] if current_part else None,
                "sub_headings": [],
            }
            continue

        # `### ` sub-heading inside the current section.
        m_sub = re.match(r"^### (.+?)\s*$", line)
        if m_sub and current_section is not None:
            current_section["sub_headings"].append(m_sub.group(1).strip())

    # Close trailing structures.
    if current_section is not None:
        current_section["line_end"] = len(lines)
        sections.append(current_section)
    if current_part is not None and current_part["line_end"] is None:
        current_part["line_end"] = len(lines)

    return sections, parts


def collect_all_headings(lines: List[str]) -> List[Tuple[int, int, str]]:
    """Return (line_number, level, title) for every heading, ignoring code fences.

    Level is 1 for `# `, 2 for `## `, 3 for `### `, etc.
    """
    headings: List[Tuple[int, int, str]] = []
    open_fence: Optional[Tuple[str, int]] = None
    for i, line in enumerate(lines, start=1):
        if open_fence is None:
            parsed = parse_code_fence(line)
            if parsed is not None:
                open_fence = parsed
                continue
        else:
            if fence_closes(open_fence, line):
                open_fence = None
            continue
        m = re.match(r"^(#{1,6})\s+(.+?)\s*$", line)
        if m:
            level = len(m.group(1))
            title = m.group(2).strip()
            headings.append((i, level, title))
    return headings


# ---------------------------------------------------------------------------
# Heading title normalization for fuzzy reference matching
# ---------------------------------------------------------------------------


def normalize_heading(title: str) -> str:
    """Normalize a heading title for fuzzy comparison.

    Strips backticks, smart/curly quotes, trailing punctuation; lowercases;
    collapses whitespace. Keeps internal punctuation that distinguishes
    sections (parens, dashes, colons).
    """
    s = title
    # Strip backticks.
    s = s.replace("`", "")
    # Replace smart quotes with ASCII.
    s = (s.replace("“", '"').replace("”", '"')
           .replace("‘", "'").replace("’", "'"))
    # Replace various dashes with `-`.
    s = s.replace("—", "-").replace("–", "-")
    # Collapse whitespace.
    s = re.sub(r"\s+", " ", s).strip()
    # Strip trailing punctuation that's not load-bearing.
    s = s.rstrip(".,:;")
    return s.lower()


# ---------------------------------------------------------------------------
# Findings
# ---------------------------------------------------------------------------


def make_finding(
    severity: str,
    rule: str,
    location: str,
    description: str,
    suggested_fix: str = "",
    auto_applicable: bool = False,
) -> Dict:
    """Construct a finding dict in the canonical schema."""
    return {
        "severity": severity,
        "rule": rule,
        "location": location,
        "description": description,
        "suggested_fix": suggested_fix,
        "auto_applicable": auto_applicable,
    }


# ---------------------------------------------------------------------------
# Individual checks
# ---------------------------------------------------------------------------


def check_overview_first(design_path: str, lines: List[str], sections: List[Dict]) -> List[Dict]:
    """First `## ` heading in design.md must be `## Overview` and have a body.

    The concept-first elevator pitch is the cold reader's entry point and
    must come first; meta-navigation (audience, journey table) is folded
    into the tail of Overview, not given its own header. Per
    design-document-rules.md § Required content.
    """
    findings: List[Dict] = []
    if not sections:
        findings.append(make_finding(
            "blocker",
            "overview-first",
            f"{design_path}:1",
            "design.md has no `## ` sections at all — Overview missing.",
            "Add a `## Overview` section as the first `## ` heading after the title.",
        ))
        return findings
    first = sections[0]
    # Truncate the offending title in the message so a 10,000-char title
    # cannot balloon the JSON output.
    first_title_truncated = first["title"][:80]
    if first["title"] != "Overview":
        findings.append(make_finding(
            "blocker",
            "overview-first",
            f"{design_path}:{first['line_start']}",
            (f"First `## ` section is `{first_title_truncated}`, not `Overview`. "
             "Per design-document-rules.md § Required content, every design.md must open with a "
             "`## Overview` section as the first content under the title — concept-first elevator "
             "pitch, no meta-navigation ahead of the concept."),
            "Reorder so `## Overview` is the first `## ` heading; fold any meta-navigation "
            "(audience, journey table, companion-file pointer) into the tail of Overview.",
        ))
        return findings

    # Overview is first — verify it has substantive body content. An empty or
    # near-empty Overview silently passes the per-section shape rules (Overview
    # is shape-exempt) but defeats the concept-first purpose entirely.
    body_non_empty = [
        line for line in section_body_lines_outside_fences(lines, first)
        if line.strip()
    ]
    if len(body_non_empty) < 5:
        findings.append(make_finding(
            "should-fix",
            "overview-body",
            f"{design_path}:{first['line_start']}",
            (f"`## Overview` has only {len(body_non_empty)} non-empty body line(s) (excluding "
             "fences). Overview must carry, in order, the five required elements: baseline "
             "being replaced, the change, the enabling primitive(s), what else is restructured "
             "to fit, and a one-sentence document-structure roadmap. A body shorter than 5 "
             "non-empty lines cannot meaningfully cover all five."),
            "Flesh out the Overview section per design-document-rules.md § Overview "
            "(mandatory, first content).",
        ))

    # Overview ≤40 lines (per design-document-rules.md § Overview). Past 40
    # lines, Overview has stopped being the elevator pitch and has started
    # becoming the design itself — detail belongs in Core Concepts, Class
    # Design, Workflow, or the Parts. Length is total inclusive line span,
    # matching how the agent reads the section.
    overview_length = (first["line_end"] or first["line_start"]) - first["line_start"] + 1
    if overview_length > OVERVIEW_LINE_CAP:
        findings.append(make_finding(
            "should-fix",
            "overview-length",
            f"{design_path}:{first['line_start']}",
            (f"`## Overview` is {overview_length} lines (cap: {OVERVIEW_LINE_CAP}). The Overview "
             "is the concept-first elevator pitch; past the cap it has stopped being a pitch and "
             "started becoming the design itself. Per design-document-rules.md § Overview, move "
             "detail into Core Concepts (vocabulary), Class Design (types), Workflow (sequence), "
             "or the Parts (deep dives)."),
            f"Trim `## Overview` to ≤{OVERVIEW_LINE_CAP} lines; relocate detail to the appropriate "
            "downstream section.",
        ))
    return findings


def check_core_concepts_when_parts(
    design_path: str,
    sections: List[Dict],
    parts: List[Dict],
) -> List[Dict]:
    """If the design has `# Part N` headings, recommend a `## Core Concepts`
    section between Overview and Class Design.

    Severity: `should-fix` — the design is comprehensible without Core
    Concepts, but multi-Part docs introduce more vocabulary than Overview's
    ≤40 lines can absorb, and Parts dive into mechanics assuming the reader
    already has the concepts. The discipline says: fold the new vocabulary
    into a Core Concepts primer between Overview and the deep dives.
    """
    findings: List[Dict] = []
    if not parts:
        return findings
    # Locate the canonical scaffold sections (Overview, Core Concepts, Class
    # Design) in document order. `parent_part is None` filters out homonyms
    # nested inside a Part.
    overview_idx = next(
        (i for i, s in enumerate(sections)
         if s["title"] == "Overview" and s["parent_part"] is None),
        None,
    )
    cc_idx = next(
        (i for i, s in enumerate(sections)
         if s["title"] == "Core Concepts" and s["parent_part"] is None),
        None,
    )
    class_design_idx = next(
        (i for i, s in enumerate(sections)
         if s["title"] == "Class Design" and s["parent_part"] is None),
        None,
    )

    if cc_idx is None:
        # Missing entirely — including any nested-inside-a-Part placement,
        # which the `parent_part is None` filter already rejects.
        overview_section = sections[overview_idx] if overview_idx is not None else None
        loc_line = (overview_section["line_end"] + 1) if overview_section else 1
        findings.append(make_finding(
            "should-fix",
            "core-concepts-when-parts",
            f"{design_path}:{loc_line}",
            (f"design.md has {len(parts)} `# Part N` heading(s) but no top-level `## Core "
             "Concepts` section between Overview and Class Design. Multi-Part docs introduce "
             "more domain vocabulary than Overview's ≤40 lines can absorb; the Parts then "
             "dive into mechanics assuming the reader already has the concepts. Per "
             "design-document-rules.md § Core Concepts (conditional), add a `## Core "
             "Concepts` section that names each load-bearing idea (component op, logical "
             "rollback, etc.), defines it in plain language, states the delta from baseline, "
             "and points at the Part(s) that elaborate. (A `## Core Concepts` nested inside "
             "a `# Part N` is not the canonical placement — it must sit at the top level.)"),
            "Insert a `## Core Concepts` section after Overview, with one bold-prefix paragraph "
            "per load-bearing concept ending with a `→ Part X §\"…\"` pointer.",
        ))
        return findings

    # Core Concepts exists at the top level — verify position. Per
    # design-document-rules.md § Core Concepts, the section must sit between
    # Overview and Class Design (and necessarily before any `# Part N`,
    # since concepts must be defined before the deep dives that use them).
    cc_section = sections[cc_idx]
    cc_loc = f"{design_path}:{cc_section['line_start']}"
    if overview_idx is not None and cc_idx < overview_idx:
        findings.append(make_finding(
            "should-fix",
            "core-concepts-when-parts",
            cc_loc,
            ("`## Core Concepts` appears before `## Overview`. Per design-document-rules.md "
             "§ Core Concepts (conditional), Core Concepts must sit between Overview and "
             "Class Design — it builds on Overview's concept-first pitch, so it cannot "
             "precede it."),
            "Move `## Core Concepts` to immediately follow `## Overview`.",
        ))
    if class_design_idx is not None and cc_idx > class_design_idx:
        findings.append(make_finding(
            "should-fix",
            "core-concepts-when-parts",
            cc_loc,
            ("`## Core Concepts` appears after `## Class Design`. Per design-document-rules.md "
             "§ Core Concepts (conditional), Core Concepts must sit between Overview and "
             "Class Design — concepts must be defined before the types and the Parts that "
             "use them."),
            "Move `## Core Concepts` to sit between `## Overview` and `## Class Design`.",
        ))
    # If `# Part N` exists, Core Concepts must come before the first Part.
    first_part_line = parts[0]["line_start"]
    if cc_section["line_start"] > first_part_line:
        findings.append(make_finding(
            "should-fix",
            "core-concepts-when-parts",
            cc_loc,
            (f"`## Core Concepts` appears after `# {parts[0]['title']}`. Per design-document-"
             "rules.md § Core Concepts (conditional), the section must precede the Parts so "
             "the cold reader has vocabulary in hand before the first deep dive."),
            "Move `## Core Concepts` to before the first `# Part N` heading.",
        ))
    return findings


def section_body_lines(lines: List[str], section: Dict) -> List[str]:
    """Return the body lines of a section (excluding its heading), 0-indexed slice."""
    return lines[section["line_start"]:section["line_end"]]


def section_body_lines_outside_fences(lines: List[str], section: Dict) -> List[str]:
    """Return body lines that are NOT inside a fenced code block.

    Section heading lines are guaranteed by `parse_sections` to sit outside
    any fence (headings inside fences are skipped), so a body always starts
    with no open fence. Fence opener/closer lines are themselves dropped.
    Uses CommonMark same-char-and-length matching so a 3-char ``` doesn't
    falsely close a 4-char ```` outer fence.
    """
    out: List[str] = []
    open_fence: Optional[Tuple[str, int]] = None
    for line in section_body_lines(lines, section):
        if open_fence is None:
            parsed = parse_code_fence(line)
            if parsed is not None:
                open_fence = parsed
                continue
            out.append(line)
        else:
            if fence_closes(open_fence, line):
                open_fence = None
            # Lines inside an open fence are dropped, including the closer.
    return out


def section_has_tldr(lines: List[str], section: Dict, head_window: int = 10) -> bool:
    """Return True if a TL;DR appears within the first head_window non-fence lines of the section body."""
    body = section_body_lines_outside_fences(lines, section)[:head_window]
    text = "\n".join(body)
    if re.search(r"(^|\n)\*\*TL;DR\.\*\*", text):
        return True
    if re.search(r"(^|\n)### TL;DR\b", text):
        return True
    return False


def section_has_references(lines: List[str], section: Dict, tail_window: int = 30) -> bool:
    """Return True if a References block appears in the last tail_window non-fence lines of the section."""
    body = section_body_lines_outside_fences(lines, section)
    if len(body) > tail_window:
        body = body[-tail_window:]
    text = "\n".join(body)
    if re.search(r"(^|\n)### References\b", text):
        return True
    if re.search(r"(^|\n)\*\*References\.\*\*", text):
        return True
    return False


def is_shape_exempt(section: Dict) -> bool:
    """A section is exempt from the per-section shape rules if its title is in SHAPE_EXEMPT_SECTION_NAMES."""
    return section["title"] in SHAPE_EXEMPT_SECTION_NAMES


def check_per_section_shape(
    design_path: str,
    lines: List[str],
    sections: List[Dict],
    changed_section: Optional[str],
    scope: str,
) -> List[Dict]:
    """Check that every section (except exempt ones) has TL;DR and References blocks."""
    findings: List[Dict] = []
    # Validate `changed_section` against the section list before iterating. A
    # typo'd section name (or a stale name passed during a section-rename)
    # would otherwise skip every section in the loop and silently return zero
    # findings — the script would emit verdict=PASS without ever running the
    # per-section shape check on the bounded scope's actual target.
    if scope == "bounded" and changed_section is not None:
        normalized_target = normalize_heading(changed_section)
        section_titles = [s["title"] for s in sections]
        if not any(normalize_heading(s["title"]) == normalized_target for s in sections):
            findings.append(make_finding(
                "blocker",
                "changed-section-not-found",
                f"{design_path}:1",
                (f"--changed-section \"{changed_section}\" does not match any `## ` heading in "
                 f"{design_path}. The bounded per-section shape check would skip every section "
                 "and silently return PASS. Section titles in this file: "
                 f"{section_titles[:20]}{'...' if len(section_titles) > 20 else ''}."),
                ("Pass the exact section title (case- and punctuation-sensitive after fuzzy "
                 "normalization). For section-rename mutations, pass the NEW title."),
            ))
            return findings
    for section in sections:
        if is_shape_exempt(section):
            continue
        # When scope is "bounded" and a changed section is specified, only check that section.
        if (scope == "bounded" and changed_section is not None
                and normalize_heading(section["title"]) != normalize_heading(changed_section)):
            continue
        location = f"{design_path}:{section['line_start']}"
        if not section_has_tldr(lines, section):
            findings.append(make_finding(
                "blocker",
                "per-section-shape:tldr",
                location,
                f"Section `{section['title']}` is missing a TL;DR block in its first ~10 lines.",
                ("Insert `**TL;DR.** <≤5 lines: what the section is about + why it matters>` "
                 f"as the first paragraph after the heading at line {section['line_start']}."),
            ))
        if not section_has_references(lines, section):
            findings.append(make_finding(
                "blocker",
                "per-section-shape:references-footer",
                location,
                f"Section `{section['title']}` is missing a References footer (no `### References` "
                "or `**References.**` block in its last ~30 lines).",
                ("Append a `### References` block at the end of the section listing D-records, "
                 "Invariants, and (when applicable) `Mechanics:` cross-references."),
            ))
    return findings


def check_top_level_cap(
    design_path: str,
    sections: List[Dict],
    parts: List[Dict],
) -> List[Dict]:
    """Top-level `## ` cap.

    Rule (per design-document-rules.md § Mechanical checks):
    - The total flat count of `## ` sections must be ≤ 15. This bound holds
      regardless of whether `# Part N` headings exist — Parts group sections
      visually but each `##` still counts.
    - When Parts exist, an additional per-Part cap (≤ 8 sections, warn at > 6)
      catches Parts that have absorbed too much detail, even when the flat
      total is still below 15.
    """
    findings: List[Dict] = []

    # Always apply the flat 15 cap — Parts are a grouping layer, not an
    # escape hatch from the top-level count.
    if len(sections) > TOP_LEVEL_CAP:
        findings.append(make_finding(
            "should-fix",
            "top-level-cap",
            f"{design_path}:1",
            (f"design.md has {len(sections)} `## ` sections (cap: ~{TOP_LEVEL_CAP}). "
             "Consolidate same-shape siblings or move long-form material to "
             "`design-mechanics.md`. `# Part N` headings group sections visually "
             "but every `##` still counts toward the total."),
            "Consolidate `##` sections via the consolidation form, or move "
            "long-form mechanism content to `design-mechanics.md`.",
        ))

    if not parts:
        return findings

    # Per-Part caps when Parts exist — the pre-Part region (Overview,
    # Core Concepts, Class Design, Workflow) is the canonical scaffold and
    # does not get its own additional cap beyond the flat 15 above.
    by_part: Dict[Optional[str], List[Dict]] = {}
    for s in sections:
        by_part.setdefault(s["parent_part"], []).append(s)
    for part_title, part_sections in by_part.items():
        if part_title is None:
            continue
        n = len(part_sections)
        line_no = next((p["line_start"] for p in parts if p["title"] == part_title), 1)
        if n > PER_PART_CAP:
            findings.append(make_finding(
                "should-fix",
                "top-level-cap",
                f"{design_path}:{line_no}",
                (f"`{part_title}` contains {n} `## ` sections (per-Part cap: ~{PER_PART_CAP}). "
                 "Consolidate same-shape siblings or move long-form material to `design-mechanics.md`."),
                f"Consolidate sibling sections in {part_title} per the consolidation form.",
            ))
        elif n > PER_PART_WARN:
            findings.append(make_finding(
                "suggestion",
                "top-level-cap",
                f"{design_path}:{line_no}",
                f"`{part_title}` has {n} `## ` sections (warn at >{PER_PART_WARN}, cap at {PER_PART_CAP}).",
            ))
    return findings


def check_per_section_length(
    design_path: str,
    lines: List[str],
    sections: List[Dict],
    changed_section: Optional[str],
    scope: str,
) -> List[Dict]:
    """Each `## ` section ≤ 300 lines (warn at 200, blocker at 400).

    Honors `--scope=bounded`: when `scope == "bounded"` and `changed_section`
    is set, the check fires only on that section. Per design-document-rules.md
    § Mutation discipline (scope: new mutations only), the discipline does not
    backfill length-cap violations on sections the current mutation didn't
    touch — pre-existing legacy oversize sections live until they're modified.
    """
    findings: List[Dict] = []
    for section in sections:
        if is_shape_exempt(section):
            continue
        # Bounded scope: only flag length on the section the mutation changed.
        if (scope == "bounded" and changed_section is not None
                and normalize_heading(section["title"]) != normalize_heading(changed_section)):
            continue
        # Section length = lines from heading through end (inclusive).
        length = (section["line_end"] or section["line_start"]) - section["line_start"] + 1
        location = f"{design_path}:{section['line_start']}"
        if length > BLOCKER_SECTION_LINES:
            findings.append(make_finding(
                "blocker",
                "per-section-length",
                location,
                (f"Section `{section['title']}` is {length} lines (blocker at >{BLOCKER_SECTION_LINES}). "
                 "Move long-form material to `design-mechanics.md` and reference via the `### References` footer."),
                f"Move worked examples / derivations from `{section['title']}` to design-mechanics.md.",
            ))
        elif length > SUGGESTED_SECTION_CAP:
            findings.append(make_finding(
                "should-fix",
                "per-section-length",
                location,
                (f"Section `{section['title']}` is {length} lines (cap: ~{SUGGESTED_SECTION_CAP}). "
                 "Move long-form material to `design-mechanics.md`."),
                f"Move long-form content from `{section['title']}` to design-mechanics.md.",
            ))
        elif length > WARN_SECTION_LINES:
            findings.append(make_finding(
                "suggestion",
                "per-section-length",
                location,
                f"Section `{section['title']}` is {length} lines (warn at >{WARN_SECTION_LINES}, cap at {SUGGESTED_SECTION_CAP}).",
            ))
    return findings


def check_dsc_parenthetical_asides(
    file_path: str,
    lines: List[str],
    sections: Optional[List[Dict]] = None,
    changed_section: Optional[str] = None,
    scope: str = "whole-doc",
) -> List[Dict]:
    """Reject `(per D27)`, `(see S14)` style parenthetical asides anywhere in prose.

    Allowed: D-codes / S-codes when they are the SUBJECT of a sentence
    ("D27 makes histograms volatile") and inside the `### References` block.

    Operates on whichever file the caller passes — design.md or
    design-mechanics.md. Parenthetical asides are forbidden in both.

    Honors `--scope=bounded`: when `scope == "bounded"`, `changed_section`
    is set, and `sections` is supplied, only asides whose line falls within
    the changed section's `[line_start, line_end]` range are flagged. Per
    design-document-rules.md § Mutation discipline (scope: new mutations only),
    pre-existing asides outside the changed section are left for the mutation
    that next touches them.
    """
    findings: List[Dict] = []
    # Track which lines are inside a References block (which legitimately lists D/S codes).
    in_references = False
    open_fence: Optional[Tuple[str, int]] = None

    aside_patterns = [
        re.compile(r"\([Pp]er D\d+(?:\s*[,/]\s*D\d+)*\)"),
        re.compile(r"\([Pp]er S\d+(?:\s*[,/]\s*S\d+)*\)"),
        re.compile(r"\([Ss]ee D\d+(?:\s*[,/]\s*D\d+)*\)"),
        re.compile(r"\([Ss]ee S\d+(?:\s*[,/]\s*S\d+)*\)"),
    ]

    # Resolve the changed section's line range when bounded scope is active.
    bounded_range: Optional[Tuple[int, int]] = None
    if scope == "bounded" and changed_section is not None and sections is not None:
        target_norm = normalize_heading(changed_section)
        target = next(
            (s for s in sections if normalize_heading(s["title"]) == target_norm),
            None,
        )
        if target is not None:
            bounded_range = (
                target["line_start"],
                target["line_end"] or len(lines),
            )

    for i, line in enumerate(lines, start=1):
        if open_fence is None:
            parsed = parse_code_fence(line)
            if parsed is not None:
                open_fence = parsed
                continue
        else:
            if fence_closes(open_fence, line):
                open_fence = None
            continue

        # References block toggles on `### References` or `**References.**`,
        # ends on the next heading.
        if re.match(r"^### References\b|^\*\*References\.\*\*", line):
            in_references = True
            continue
        if re.match(r"^#{1,6}\s", line):
            in_references = False
        # Table rows (lines starting with `|`) sometimes legitimately use the
        # `(per Dxx)` form to encode source attribution; skip them.
        is_table_row = line.lstrip().startswith("|")

        if in_references or is_table_row:
            continue

        # Bounded scope: skip lines outside the changed section's range.
        if bounded_range is not None and not (bounded_range[0] <= i <= bounded_range[1]):
            continue

        for pat in aside_patterns:
            for m in pat.finditer(line):
                findings.append(make_finding(
                    "should-fix",
                    "dsc-parenthetical-aside",
                    f"{file_path}:{i}",
                    (f"D/S parenthetical aside `{m.group(0)}` in prose. "
                     "Per design-document-rules.md § D/S code discipline, D/S codes are forbidden as "
                     "parenthetical asides; allowed when the code IS the subject of the sentence, "
                     "and listed in the References footer."),
                    (f"Remove `{m.group(0)}` from line {i}; if the D/S code is load-bearing, "
                     "rephrase so it is the subject of the sentence, or move the citation to the "
                     "References footer."),
                    auto_applicable=True,
                ))
    return findings


def check_length_trigger_compliance(
    design_path: str,
    design_mechanics_path: Optional[str],
    line_count: int,
) -> List[Dict]:
    """If design.md > 2,000 lines and design-mechanics.md doesn't exist, blocker."""
    findings: List[Dict] = []
    if line_count <= LENGTH_TRIGGER_LINES:
        return findings
    if design_mechanics_path is None or not os.path.exists(design_mechanics_path):
        findings.append(make_finding(
            "blocker",
            "length-trigger-compliance",
            f"{design_path}:1",
            (f"design.md is {line_count} lines (trigger: >{LENGTH_TRIGGER_LINES}) but no "
             "`design-mechanics.md` companion exists. Long-form mechanism content must move "
             "to `design-mechanics.md` per design-document-rules.md § Length-triggered split."),
            "Create design-mechanics.md and move long-form content from design.md.",
        ))
    return findings


SAME_SHAPE_JACCARD_THRESHOLD = 0.8


def _jaccard(a: frozenset, b: frozenset) -> float:
    """Jaccard similarity over two sub-heading sets: |a ∩ b| / |a ∪ b|.

    Empty sets are filtered out by the caller, so the union is always non-empty.
    """
    union = a | b
    return len(a & b) / len(union)


def check_same_shape_siblings(
    design_path: str,
    sections: List[Dict],
) -> List[Dict]:
    """Detect 3+ sibling `## ` sections whose custom sub-heading sets overlap
    by ≥ SAME_SHAPE_JACCARD_THRESHOLD (Jaccard similarity).

    Per design-document-rules.md § Consolidation form for sibling sections,
    3+ siblings sharing the same internal shape must be consolidated. The
    threshold is fuzzy on purpose: a cluster where two sections have
    `### Inputs / ### Algorithm / ### Outputs` and a third adds
    `### Edge Cases` is still the consolidation-form anti-pattern — exact-set
    matching would silently miss it. Pairs with similarity ≥ threshold are
    unioned (transitive closure), and clusters of 3+ are flagged.
    """
    findings: List[Dict] = []

    # Per-Part candidates: section + custom sub-heading set (lowercased).
    by_part: Dict[Optional[str], List[Tuple[Dict, frozenset]]] = {}
    for s in sections:
        if is_shape_exempt(s):
            continue
        custom_subs = frozenset(
            sub.lower()
            for sub in s["sub_headings"]
            if sub.lower() not in MANDATORY_OR_FORM_SUBHEADINGS
        )
        if not custom_subs:
            continue
        by_part.setdefault(s["parent_part"], []).append((s, custom_subs))

    for part_title, items in by_part.items():
        n = len(items)
        if n < 3:
            continue

        # Union-find: merge any pair whose custom-subhead sets overlap by
        # ≥ threshold. Path compression keeps the find amortized near-O(1).
        parent = list(range(n))

        def find(x: int) -> int:
            while parent[x] != x:
                parent[x] = parent[parent[x]]
                x = parent[x]
            return x

        def union(a: int, b: int) -> None:
            ra, rb = find(a), find(b)
            if ra != rb:
                parent[ra] = rb

        for i in range(n):
            for j in range(i + 1, n):
                if _jaccard(items[i][1], items[j][1]) >= SAME_SHAPE_JACCARD_THRESHOLD:
                    union(i, j)

        clusters: Dict[int, List[int]] = {}
        for i in range(n):
            clusters.setdefault(find(i), []).append(i)

        for cluster_indices in clusters.values():
            if len(cluster_indices) < 3:
                continue
            cluster_indices.sort(key=lambda idx: items[idx][0]["line_start"])
            cluster_sections = [items[idx][0] for idx in cluster_indices]
            titles = [s["title"] for s in cluster_sections]
            first_loc = cluster_sections[0]["line_start"]
            findings.append(make_finding(
                "should-fix",
                "same-shape-siblings",
                f"{design_path}:{first_loc}",
                (f"{len(cluster_sections)} sibling sections under "
                 f"`{part_title or '(no Part)'}` share the same internal sub-heading shape "
                 f"(Jaccard ≥ {SAME_SHAPE_JACCARD_THRESHOLD:.0%} pairwise overlap on custom "
                 f"sub-headings): {titles}. Per design-document-rules.md § Consolidation "
                 "form for sibling sections, 3+ siblings with the same shape MUST be "
                 "consolidated under one parent section using the consolidation form "
                 "(TL;DR + Comparison table + Per-instance short bodies)."),
                f"Merge {titles} into one parent section using the consolidation form.",
            ))
    return findings


# Filename regex covers the four canonical companions:
#   design.md / design-final.md / design-mechanics.md / design-mechanics-final.md
# Phase 1 mutations target the first two; Phase 4 targets the `-final.md`
# variants. The regex must catch all four so refs are validated regardless of
# which mutation kind is running.
#
# The leading negative lookbehind rejects matches preceded by a word char or
# hyphen, so prefixed variants like `test-design.md`, `pre-design.md`, or
# `not-design-final.md` don't sneak through (a plain `\b` boundary sits at the
# `-d` transition and would silently strip the prefix). The closing `\b` is
# fine — `.md` is followed by end-of-line or punctuation in valid refs.
_FILENAME_RE = re.compile(r"(?<![\w-])(design(?:-final|-mechanics(?:-final)?)?\.md)\b")
_QUOTE_RE = re.compile(r"§\s*\"([^\"]+)\"")


def collect_refs_line_by_line(filepath: str, lines: List[str]) -> List[Tuple[int, str, str]]:
    """Walk `lines` and return `(line_number, filename, section_name)` for every
    cross-reference of the form `<filename>.md ... §"name"` (including
    continuations via `+ §"name"`).

    The active filename is bound by the most recent `<filename>.md` token on
    the same line; subsequent `§"name"` quotes on that line attach to it.

    Lines inside fenced code blocks are skipped — `design.md §"Foo"` appearing
    inside a worked example would otherwise be reported as a broken cross-ref
    even though it's just illustrative code.
    """
    refs: List[Tuple[int, str, str]] = []
    open_fence: Optional[Tuple[str, int]] = None
    for i, line in enumerate(lines, start=1):
        if open_fence is None:
            parsed = parse_code_fence(line)
            if parsed is not None:
                open_fence = parsed
                continue
        else:
            if fence_closes(open_fence, line):
                open_fence = None
            continue

        # Find all (offset, filename) on this line.
        fname_matches = list(_FILENAME_RE.finditer(line))
        if not fname_matches:
            continue
        # Walk all `§"name"` quotes in order; each binds to the most recent
        # filename whose offset is ≤ the quote's offset.
        for q in _QUOTE_RE.finditer(line):
            quote_offset = q.start()
            active_fname: Optional[str] = None
            for f in fname_matches:
                if f.start() <= quote_offset:
                    active_fname = f.group(1)
                else:
                    break
            if active_fname is None:
                continue
            refs.append((i, active_fname, q.group(1).strip()))
    return refs


def check_mechanics_link_resolution(
    design_path: str,
    design_lines: List[str],
    design_mechanics_path: Optional[str],
    design_mechanics_lines: Optional[List[str]],
) -> List[Dict]:
    """Every `<mechanics-basename> §"<name>"` reference in design.md must
    resolve to a heading in the supplied mechanics file.

    The mechanics basename is derived from `--design-mechanics-path` so the
    check works for both `design-mechanics.md` (Phase 1) and
    `design-mechanics-final.md` (Phase 4).
    """
    findings: List[Dict] = []
    if design_mechanics_path is None or design_mechanics_lines is None:
        return findings

    mech_basename = os.path.basename(design_mechanics_path)

    # All headings in mechanics, normalized.
    mech_headings = collect_all_headings(design_mechanics_lines)
    norm_targets = {normalize_heading(t): (line_no, t) for line_no, _level, t in mech_headings}

    refs = collect_refs_line_by_line(design_path, design_lines)
    for line_no, fname, ref_name in refs:
        if fname != mech_basename:
            continue
        if normalize_heading(ref_name) not in norm_targets:
            findings.append(make_finding(
                "blocker",
                "mechanics-link-resolution",
                f"{design_path}:{line_no}",
                (f"`{mech_basename} §\"{ref_name}\"` does not resolve to any heading in "
                 f"`{design_mechanics_path}`."),
                (f"Either rename the {mech_basename} heading to match (recommended — heading "
                 "names should track design.md) or update the reference."),
            ))
    return findings


def check_reverse_direction_refs(
    design_mechanics_path: str,
    design_mechanics_lines: List[str],
    design_basename: str,
) -> List[Dict]:
    """Flag any `<design-basename> §"<name>"` reference appearing inside the
    mechanics file.

    Per design-document-rules.md § Length-triggered split, cross-references go
    one direction only: design.md → design-mechanics.md, never the reverse.
    Mechanics is the agent-targeted long-form companion and must remain
    self-contained — a reverse ref is a should-fix.
    """
    findings: List[Dict] = []
    refs = collect_refs_line_by_line(design_mechanics_path, design_mechanics_lines)
    for line_no, fname, ref_name in refs:
        if fname != design_basename:
            continue
        findings.append(make_finding(
            "should-fix",
            "reverse-direction-ref",
            f"{design_mechanics_path}:{line_no}",
            (f"`{design_basename} §\"{ref_name}\"` referenced from {design_mechanics_path}. "
             "Per design-document-rules.md § Length-triggered split, cross-references go one "
             "direction only: design.md → design-mechanics.md, never the reverse. The mechanics "
             "file must be self-contained for the agent reader."),
            "Remove the reverse reference or inline the relevant content into the mechanics file.",
        ))
    return findings


def check_full_design_link_resolution(
    design_path: str,
    design_lines: List[str],
    plan_path: Optional[str],
    plan_lines: Optional[List[str]],
    track_files: Optional[List[Tuple[str, List[str]]]],
    design_mechanics_path: Optional[str],
    design_mechanics_lines: Optional[List[str]],
) -> List[Dict]:
    """Every `**Full design**: <design-basename> §"<name>"` in the plan and
    in any track file (`plan/track-N.md`) must resolve to a heading in
    design.md (and any chained `<mechanics-basename> §"<name>"` must
    resolve in mechanics).

    Basenames are derived from `--design-path` / `--design-mechanics-path` so
    the check works whether the supplied paths point at the original `design.md`
    pair or at the Phase 4 `*-final.md` variants.
    """
    findings: List[Dict] = []
    if plan_lines is None and not track_files:
        return findings

    design_basename = os.path.basename(design_path)
    mech_basename = (
        os.path.basename(design_mechanics_path)
        if design_mechanics_path is not None else None
    )

    # Targets in design.md and design-mechanics.md.
    design_norm = {
        normalize_heading(t): (line_no, t)
        for line_no, _level, t in collect_all_headings(design_lines)
    }
    mech_norm: Dict[str, Tuple[int, str]] = {}
    if design_mechanics_lines is not None:
        mech_norm = {
            normalize_heading(t): (line_no, t)
            for line_no, _level, t in collect_all_headings(design_mechanics_lines)
        }

    def check_file(path: str, lines_: List[str]) -> List[Dict]:
        out: List[Dict] = []
        for line_no, fname, ref_name in collect_refs_line_by_line(path, lines_):
            if fname == design_basename:
                target_table = design_norm
                target_label = design_basename
            elif mech_basename is not None and fname == mech_basename:
                target_table = mech_norm
                target_label = mech_basename
            elif fname in {"design-mechanics.md", "design-mechanics-final.md"}:
                # Plan / track-file refs to a mechanics file but no mechanics path supplied.
                out.append(make_finding(
                    "blocker",
                    "full-design-link-resolution",
                    f"{path}:{line_no}",
                    (f"`{fname} §\"{ref_name}\"` referenced from `{path}` but "
                     "no mechanics path was provided."),
                    "Pass `--design-mechanics-path` so the reference can be resolved.",
                ))
                continue
            else:
                # Reference to a design-family file we weren't told about
                # (e.g., the plan references `design-final.md` but the script
                # was run with `--design-path design.md`). Skip silently —
                # this is the agent's contract: the script resolves only the
                # files it was given.
                continue
            if normalize_heading(ref_name) not in target_table:
                out.append(make_finding(
                    "blocker",
                    "full-design-link-resolution",
                    f"{path}:{line_no}",
                    f"`{target_label} §\"{ref_name}\"` does not resolve to any heading in `{target_label}`.",
                    ("Update the reference to a real section, or rename the section it should "
                     "point at. Section renames must update every `**Full design**` and "
                     "`Mechanics:` cross-reference in the same mutation."),
                ))
        return out

    if plan_lines is not None and plan_path is not None:
        findings.extend(check_file(plan_path, plan_lines))
    if track_files:
        for track_path, track_lines in track_files:
            findings.extend(check_file(track_path, track_lines))
    return findings


# ---------------------------------------------------------------------------
# dsc-ai-tell helpers and check
# ---------------------------------------------------------------------------


_BULLET_START_RE = re.compile(r"^\s*(?:[-*+]\s+|\d+\.\s+)")


def iter_paragraphs(
    lines: List[str],
    section: Optional[Dict] = None,
    exclude_fences: bool = True,
    exclude_references: bool = True,
    exclude_tables: bool = True,
) -> Iterator[Tuple[int, List[str]]]:
    """Yield `(start_line_no, paragraph_lines)` for blank-line-bounded paragraphs.

    `start_line_no` is 1-based and points at the first non-blank line of the
    paragraph. `paragraph_lines` is the list of consecutive non-blank lines
    that make up the paragraph (no trailing blank).

    Each top-level bullet item (a line starting with `- `, `* `, `+ `, or
    `N. `) opens a new paragraph even without a preceding blank line, so a
    tightly-packed markdown list is treated as one paragraph per item rather
    than one paragraph for the whole list. Without this split the em-dash
    density rule (and the hyphenated-pair cluster rule) would over-fire on
    legitimate ADR prose where authors stack short bullets with one em dash
    each.

    Exclusion semantics borrow from `check_dsc_parenthetical_asides`:

    - `exclude_fences=True` (default) drops every line inside a fenced code
      block, including the opener and closer. CommonMark same-char-and-length
      matching is used so a 3-char ``` does not falsely close a 4-char ```` outer
      fence.
    - `exclude_references=True` (default) drops every line from a
      `### References` / `**References.**` toggle through the next heading.
      Citations there legitimately mention banned vocabulary or hyphenated
      compounds without being a violation.
    - `exclude_tables=True` (default) drops every line whose first
      non-whitespace character is `|`. Table rows often encode terms,
      definitions, and citations that would false-fire the vocabulary scan.

    When `section` is supplied, only lines whose 1-based number falls in
    `[section["line_start"], section["line_end"]]` are considered.
    """
    if section is not None:
        start_idx = section["line_start"]
        end_idx = section["line_end"] or len(lines)
    else:
        start_idx = 1
        end_idx = len(lines)

    open_fence: Optional[Tuple[str, int]] = None
    in_references = False
    paragraph: List[str] = []
    paragraph_start: Optional[int] = None

    def flush() -> Iterator[Tuple[int, List[str]]]:
        nonlocal paragraph, paragraph_start
        if paragraph_start is not None and paragraph:
            yield paragraph_start, paragraph
        paragraph = []
        paragraph_start = None

    for i in range(start_idx, end_idx + 1):
        line = lines[i - 1] if 0 <= i - 1 < len(lines) else ""

        # Fence tracking. Fence-delimiter lines and the body inside are
        # excluded entirely when `exclude_fences` is set; the paragraph in
        # progress is also flushed so a paragraph cannot straddle a fence.
        if exclude_fences:
            if open_fence is None:
                parsed = parse_code_fence(line)
                if parsed is not None:
                    yield from flush()
                    open_fence = parsed
                    continue
            else:
                if fence_closes(open_fence, line):
                    open_fence = None
                # Inside-fence lines never contribute to a paragraph.
                continue

        # References-block toggle. Switches on at the `### References` /
        # `**References.**` line and back off at the next heading.
        if exclude_references:
            if re.match(r"^### References\b|^\*\*References\.\*\*", line):
                yield from flush()
                in_references = True
                continue
            if re.match(r"^#{1,6}\s", line):
                in_references = False
            if in_references:
                yield from flush()
                continue

        # Table rows.
        if exclude_tables and line.lstrip().startswith("|"):
            yield from flush()
            continue

        # Headings are paragraph separators — flush, do not include.
        if re.match(r"^#{1,6}\s", line):
            yield from flush()
            continue

        if line.strip() == "":
            yield from flush()
        else:
            # A top-level bullet starter opens a new paragraph even when a
            # prior bullet is still in progress (no blank line between
            # bullets in a tight list).
            if _BULLET_START_RE.match(line) and paragraph_start is not None:
                yield from flush()
            if paragraph_start is None:
                paragraph_start = i
            paragraph.append(line)

    yield from flush()


def _title_case_violation(line: str) -> bool:
    """Return True if `line` is an H2-H6 heading whose words are Title Case.

    A "Title Case" heading is three or more whitespace-separated words after
    the heading marker, each beginning with an upper-case letter followed by
    one or more lower-case letters. The 3-word minimum is a deliberate
    calibration choice: it lets the project's 2-word ADR-scaffold headings
    (Architecture Notes, Decision Records, Integration Points, Non-Goals,
    Key Discoveries, Component Map) pass without violating the canonical
    `house-style.md § Title Case headings forbidden` rule, which does not
    itself carve out a word-count exemption.
    """
    return bool(re.match(r"^#{2,6} ([A-Z][a-z]+ ){2,}[A-Z][a-z]+$", line))


def _heading_content_words(title: str) -> List[str]:
    """Return lower-case content words from a heading title, stop-words stripped.

    Tokenises on any non-alphanumeric-or-hyphen character so hyphenated
    compounds stay as one token (`non-durable` is one token, not `non` +
    `durable`). This avoids the false-positive case where `## Non-Goals`
    collides with body tokens of `non-durable`.
    """
    tokens = re.split(r"[^a-zA-Z0-9-]+", title.lower())
    return [t for t in tokens if t and t not in STOP_WORDS]


def _paragraph_content_words(paragraph_lines: List[str]) -> List[str]:
    """Tokenise paragraph body the same way as `_heading_content_words`."""
    text = " ".join(paragraph_lines).lower()
    tokens = re.split(r"[^a-zA-Z0-9-]+", text)
    return [t for t in tokens if t and t not in STOP_WORDS]


def check_dsc_ai_tell(
    file_path: str,
    lines: List[str],
    sections: Optional[List[Dict]] = None,
    changed_section: Optional[str] = None,
    scope: str = "whole-doc",
) -> List[Dict]:
    """Detect the subset of `house-style.md` AI-tell patterns expressible as regex.

    Nine patterns fire (each finding cites `house-style.md § <Section>` in
    its description):

    1. Tier-1 banned vocabulary scan (`§ Tier 1 — hard ban`).
    2. Negative parallelism (`§ Banned sentence patterns`).
    3. Em-dash density >1 per paragraph (`§ Em-dash discipline`).
    4. Title Case heading on H2+ (`§ Title Case headings forbidden`).
    5. Signposting openers (`§ Signposting`).
    6. Copula avoidance (`§ Copula avoidance`).
    7. Persuasive authority tropes (`§ Persuasive authority tropes`).
    8. Hyphenated-pair comma cluster (`§ Hyphenated word-pair overuse`).
    9. Fragmented header (`§ Fragmented headers`).

    Signature matches the bounded-aware sibling `check_dsc_parenthetical_asides`:
    when `scope == "bounded"` and `changed_section` is supplied, findings are
    restricted to the named section. When `scope == "whole-doc"` (the default),
    the entire file is walked.

    References-block lines (`### References`, `**References.**` through the
    next heading), table rows (lines starting with `|`), and fenced code
    blocks are excluded for every pattern; findings inside legitimate
    citations or example blocks would otherwise be noise.
    """
    findings: List[Dict] = []

    # Resolve the bounded section range, if any.
    target_section: Optional[Dict] = None
    bounded_range: Optional[Tuple[int, int]] = None
    if scope == "bounded" and changed_section is not None and sections is not None:
        target_norm = normalize_heading(changed_section)
        target_section = next(
            (s for s in sections if normalize_heading(s["title"]) == target_norm),
            None,
        )
        if target_section is not None:
            bounded_range = (
                target_section["line_start"],
                target_section["line_end"] or len(lines),
            )

    in_range = (
        lambda line_no: bounded_range is None
        or bounded_range[0] <= line_no <= bounded_range[1]
    )

    # --- Per-line scans (fence/References/table exclusion replicated here so
    #     the patterns that need character-level matches can run without
    #     joining paragraphs).
    open_fence: Optional[Tuple[str, int]] = None
    in_references = False

    for i, line in enumerate(lines, start=1):
        if open_fence is None:
            parsed = parse_code_fence(line)
            if parsed is not None:
                open_fence = parsed
                continue
        else:
            if fence_closes(open_fence, line):
                open_fence = None
            continue

        # References-block toggle: on at `### References` / `**References.**`,
        # off at the next heading.
        if re.match(r"^### References\b|^\*\*References\.\*\*", line):
            in_references = True
            continue
        is_heading = bool(re.match(r"^#{1,6}\s", line))
        if is_heading:
            in_references = False

        # Title Case heading check fires on headings only; runs before the
        # table-row / References skip because a heading is never a table row.
        if is_heading and in_range(i) and _title_case_violation(line):
            heading_text = line.lstrip("#").strip()
            findings.append(make_finding(
                "should-fix",
                "dsc-ai-tell",
                f"{file_path}:{i}",
                (f"Title-Case heading per house-style.md § Title Case headings "
                 f"forbidden: '{heading_text}'. H2 and below use sentence case."),
                f"Rewrite the heading at line {i} in sentence case "
                "(only the first word and proper nouns capitalised).",
            ))

        if in_references:
            continue
        if line.lstrip().startswith("|"):
            continue
        if not in_range(i):
            continue

        # Tier-1 banned vocabulary, signposting, copula, and authority-trope
        # scans run on heading lines too: an AI tell like "delve" or
        # "at its core" inside an H2/H3 is the same signal as in body prose
        # and the "every authored prose surface" scope in
        # `house-style.md § What this style governs` does not exempt
        # heading text.
        for m in TIER1_BANNED_VOCAB_RE.finditer(line):
            findings.append(make_finding(
                "should-fix",
                "dsc-ai-tell",
                f"{file_path}:{i}",
                (f"Tier-1 banned vocabulary per house-style.md § Tier 1 — hard ban: "
                 f"'{m.group(0)}'."),
                f"Replace '{m.group(0)}' with a plainer alternative.",
            ))

        # Signposting openers.
        for m in SIGNPOSTING_OPENERS_RE.finditer(line):
            findings.append(make_finding(
                "should-fix",
                "dsc-ai-tell",
                f"{file_path}:{i}",
                (f"Signposting opener per house-style.md § Signposting: "
                 f"'{m.group(0)}'. Just say the thing; the reader knows they "
                 "are reading the document."),
                f"Cut the signposting opener at line {i} and lead with the claim.",
            ))

        # Copula avoidance.
        for m in COPULA_AVOIDANCE_RE.finditer(line):
            findings.append(make_finding(
                "should-fix",
                "dsc-ai-tell",
                f"{file_path}:{i}",
                (f"Copula avoidance per house-style.md § Copula avoidance: "
                 f"'{m.group(0)}'. Prefer 'is' unless the action is genuinely "
                 "active."),
                f"Rewrite '{m.group(0)}' as 'is' at line {i}.",
            ))

        # Persuasive authority tropes.
        for m in AUTHORITY_TROPE_RE.finditer(line):
            findings.append(make_finding(
                "should-fix",
                "dsc-ai-tell",
                f"{file_path}:{i}",
                (f"Persuasive authority trope per house-style.md § Persuasive "
                 f"authority tropes: '{m.group(0)}'. State the actual mechanism."),
                f"Cut '{m.group(0)}' at line {i}; name the mechanism directly.",
            ))

    # --- Per-paragraph scans (em-dash density, hyphenated-pair cluster,
    #     negative parallelism).
    for start_line, para_lines in iter_paragraphs(
        lines,
        section=target_section,
        exclude_fences=True,
        exclude_references=True,
        exclude_tables=True,
    ):
        if not in_range(start_line):
            continue
        para_text = " ".join(para_lines)

        # Em-dash density: fire on 3+ em dashes per paragraph (unbalanced
        # `X — Y — Z — W` cadence, the canonical AI-tell shape), or on 2 em
        # dashes whose middle segment carries a sentence terminator (which
        # means the two em dashes are not a single balanced parenthetical
        # aside but two unpaired uses). A 2-em-dash balanced parenthetical
        # aside `A — clause — B` (no `.`/`!`/`?` in the middle segment) is
        # treated as one aside, not as a cadence, and passes the rule. The
        # canonical `X — Y — Z` cadence the style file flags always has
        # 3 segments separated by 2 em dashes; with 2 em dashes the cadence
        # only exists when the middle segment is structurally a sentence
        # continuation rather than an aside, which is exactly what the
        # sentence-terminator check captures.
        em_dash_count = para_text.count("—")
        em_dash_fires = False
        if em_dash_count > 2:
            em_dash_fires = True
        elif em_dash_count == 2:
            # Split on em dashes; the middle segment is parts[1].
            middle = para_text.split("—", 2)[1]
            if any(ch in middle for ch in ".!?"):
                em_dash_fires = True
        if em_dash_fires:
            findings.append(make_finding(
                "should-fix",
                "dsc-ai-tell",
                f"{file_path}:{start_line}",
                (f"Em-dash density per house-style.md § Em-dash discipline: "
                 f"{em_dash_count} em dashes in this paragraph form an "
                 "unbalanced cadence (balanced parenthetical asides "
                 "`A — clause — B` are exempt; 3+ em dashes or 2 unpaired "
                 "em dashes fire)."),
                f"Replace em dashes at line {start_line} with periods, commas, "
                "or colons; keep at most one balanced parenthetical aside per "
                "paragraph.",
            ))

        # Hyphenated-pair comma cluster: dedupe pairs inside the cluster so a
        # match like "fast-paced, fast-paced, fast-paced" does not fire on
        # one distinct pair repeated; the canonical AI-tell shape uses
        # genuinely different pairs.
        for m in HYPHENATED_PAIR_CLUSTER_RE.finditer(para_text):
            cluster = m.group(0)
            pairs = re.findall(r"[a-z]+-[a-z]+", cluster, flags=re.IGNORECASE)
            distinct = {p.lower() for p in pairs}
            if len(distinct) >= HYPHENATED_PAIR_CLUSTER_THRESHOLD:
                findings.append(make_finding(
                    "should-fix",
                    "dsc-ai-tell",
                    f"{file_path}:{start_line}",
                    (f"Hyphenated-pair cluster per house-style.md § Hyphenated "
                     f"word-pair overuse: {len(distinct)} distinct adjectival "
                     f"hyphenated pairs in one comma-separated cluster "
                     f"('{cluster}')."),
                    f"Rewrite the cluster at line {start_line} so the adjectival "
                    "ornament is gone; legitimate technical compounds outside a "
                    "comma cluster do not trigger.",
                ))

        # Negative parallelism: match against the joined paragraph text so the
        # ".*" between "it's not" and "it's" cannot cross a blank line.
        m_neg = NEGATIVE_PARALLELISM_RE.search(para_text)
        if m_neg is not None:
            findings.append(make_finding(
                "should-fix",
                "dsc-ai-tell",
                f"{file_path}:{start_line}",
                (f"Negative parallelism per house-style.md § Banned sentence "
                 f"patterns: '{m_neg.group(0)[:80]}'. The pattern adds no "
                 "information; rewrite as a positive statement."),
                f"Rewrite the 'it's not X, it's Y' construct at line {start_line} "
                "as a positive statement.",
            ))

    # --- Fragmented-header check: heading followed by a one-line paragraph
    #     whose content-word overlap with the heading is at or above the
    #     threshold. Walked by heading position rather than by paragraph so
    #     the "no following blank line within 2 lines" rule is enforceable.
    headings = collect_all_headings(lines)
    for heading_line_no, _level, title in headings:
        if not in_range(heading_line_no):
            continue
        # Find the next non-blank paragraph within 2 lines of the heading.
        # The "within 2 lines" rule means the paragraph must start on
        # heading_line_no+1 or heading_line_no+2 (one blank line allowed).
        para_start: Optional[int] = None
        for offset in (1, 2):
            candidate_idx = heading_line_no + offset
            if candidate_idx > len(lines):
                break
            if lines[candidate_idx - 1].strip() != "":
                para_start = candidate_idx
                break
        if para_start is None:
            continue
        # Paragraph length must be exactly 1 line — if a non-blank prose
        # line follows immediately at para_start+1 the paragraph is
        # multi-line and the rule does not apply. A heading or code-fence
        # delimiter on the next line does *not* extend the paragraph
        # (Markdown treats them as new blocks even without an intervening
        # blank line), so a fragmented one-liner sandwiched between a
        # heading and the next structural marker still fires the rule.
        if (para_start < len(lines)
                and lines[para_start].strip() != ""
                and not re.match(r"^#{1,6}\s", lines[para_start])
                and not is_code_fence_line(lines[para_start])):
            continue
        para_line = lines[para_start - 1]
        # Skip if the "paragraph" line is itself a heading, table row, or
        # fence delimiter — those are not prose continuations.
        if (re.match(r"^#{1,6}\s", para_line)
                or para_line.lstrip().startswith("|")
                or is_code_fence_line(para_line)):
            continue

        heading_words = set(_heading_content_words(title))
        if not heading_words:
            continue
        para_words = set(_paragraph_content_words([para_line]))
        if not para_words:
            continue
        overlap_count = len(heading_words & para_words)
        overlap_ratio = overlap_count / len(heading_words)
        if overlap_ratio >= FRAGMENTED_HEADER_CONTENT_WORD_OVERLAP_THRESHOLD:
            findings.append(make_finding(
                "should-fix",
                "dsc-ai-tell",
                f"{file_path}:{heading_line_no}",
                (f"Fragmented header per house-style.md § Fragmented headers: "
                 f"the one-line paragraph at line {para_start} shares "
                 f"{overlap_count}/{len(heading_words)} content words with the "
                 f"heading '{title}' "
                 f"({overlap_ratio:.0%} overlap, threshold "
                 f"{FRAGMENTED_HEADER_CONTENT_WORD_OVERLAP_THRESHOLD:.0%})."),
                f"Either expand the paragraph at line {para_start} with new "
                f"content, or cut it and let the heading at line "
                f"{heading_line_no} stand alone.",
            ))

    return findings


# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--design-path", required=True, help="Absolute path to design.md")
    p.add_argument("--design-mechanics-path", help="Absolute path to design-mechanics.md (optional)")
    p.add_argument("--plan-path", help="Absolute path to implementation-plan.md (optional)")
    p.add_argument("--plan-dir", help="Absolute path to the plan/ directory containing plan/track-N.md track files (optional). Every *.md file in this directory is scanned for `**Full design**` refs.")
    p.add_argument("--changed-section", help="Title of the section that changed (for bounded scope)")
    p.add_argument("--scope", choices=("bounded", "whole-doc"), default="whole-doc",
                   help=("Scope of the section-bounded checks (default: whole-doc). When "
                         "`bounded`, --changed-section is required and the following checks "
                         "fire only against that section: per-section TL;DR/References shape, "
                         "per-section length cap, and parenthetical-aside scan over design.md. "
                         "Per design-document-rules.md § Mutation discipline (scope: new "
                         "mutations only), bounded scope keeps the discipline from forcing "
                         "the agent to backfill pre-existing legacy violations on sections "
                         "the current mutation didn't touch. Whole-doc-only checks (top-level "
                         "cap, mechanics-link resolution, length-trigger compliance, "
                         "same-shape siblings, full-design link resolution, reverse-direction "
                         "refs, mechanics-side parenthetical asides) always run whole-doc."))
    p.add_argument("--target", choices=("design", "mechanics", "both"), default="design",
                   help=("Which file the mutation actually touched. `design` runs the full "
                         "design.md shape rules + cross-file checks (default — current behavior). "
                         "`mechanics` skips the design.md shape rules (since they don't apply to "
                         "design-mechanics.md) and scans the mechanics file for parenthetical "
                         "asides instead; cross-file link-resolution still runs. `both` runs the "
                         "design.md shape rules AND scans both files for parenthetical asides — "
                         "use for phase1-creation, design-sync, length-trigger-crossing, and "
                         "phase4-creation mutations that touch both files. (For phase4-creation, "
                         "the --design-path and --design-mechanics-path point at the *-final.md "
                         "variants; omit --plan-path and --plan-dir so the cross-file ref "
                         "check is naturally skipped — Phase 4 produces a new artifact, not a "
                         "modification of the original design.md.)"))
    args = p.parse_args()
    # Fail-fast validation: target=mechanics or target=both requires
    # --design-mechanics-path; otherwise the parenthetical-aside scan over
    # mechanics is silently skipped, which makes those targets a no-op.
    if args.target in ("mechanics", "both") and not args.design_mechanics_path:
        p.error(
            f"--target={args.target} requires --design-mechanics-path "
            "(the mechanics file the mutation supposedly touched)."
        )
    return args


def main() -> int:
    args = parse_args()

    if not os.path.exists(args.design_path):
        print(json.dumps({
            "findings": [{
                "severity": "blocker",
                "rule": "design-path-missing",
                "location": args.design_path,
                "description": f"design.md does not exist at {args.design_path}",
                "suggested_fix": "",
                "auto_applicable": False,
            }],
            "summary": {"blockers": 1, "should_fix": 0, "suggestions": 0, "verdict": "NEEDS REVISION"},
        }))
        return 1

    design_lines = read_lines(args.design_path)
    sections, parts = parse_sections(design_lines)

    findings: List[Dict] = []

    # Optional companion paths: when supplied, the file must exist. A supplied
    # path is the agent's explicit assertion that the file is part of the
    # mutation; silently skipping when missing would mask a regression in the
    # cross-file ref check.
    # A supplied-but-missing companion path is a blocker, not a should-fix:
    # the agent's flag is an explicit assertion that the file is part of the
    # mutation, and silently skipping the cross-file checks would mask broken
    # references (the exact failure mode the cross-file checks exist to catch)
    # while still emitting a misleading PASS verdict on the rest of the doc.
    design_mechanics_lines: Optional[List[str]] = None
    if args.design_mechanics_path:
        if os.path.exists(args.design_mechanics_path):
            design_mechanics_lines = read_lines(args.design_mechanics_path)
        else:
            findings.append(make_finding(
                "blocker",
                "companion-path-missing",
                args.design_mechanics_path,
                (f"--design-mechanics-path was supplied but the file does not exist at "
                 f"{args.design_mechanics_path}. Cross-file link-resolution against this "
                 "file would be silently skipped, masking any broken `Mechanics:` refs."),
                "Pass an existing path or omit the flag.",
            ))

    plan_lines: Optional[List[str]] = None
    if args.plan_path:
        if os.path.exists(args.plan_path):
            plan_lines = read_lines(args.plan_path)
        else:
            findings.append(make_finding(
                "blocker",
                "companion-path-missing",
                args.plan_path,
                (f"--plan-path was supplied but the file does not exist at {args.plan_path}. "
                 "`**Full design**` ref resolution against the plan would be silently "
                 "skipped, masking any broken refs."),
                "Pass an existing path or omit the flag.",
            ))

    track_files: Optional[List[Tuple[str, List[str]]]] = None
    if args.plan_dir:
        if os.path.isdir(args.plan_dir):
            track_paths = sorted(
                os.path.join(args.plan_dir, name)
                for name in os.listdir(args.plan_dir)
                if name.endswith(".md")
            )
            track_files = [(p, read_lines(p)) for p in track_paths]
        else:
            findings.append(make_finding(
                "blocker",
                "companion-path-missing",
                args.plan_dir,
                (f"--plan-dir was supplied but the directory does not exist at "
                 f"{args.plan_dir}. `**Full design**` ref resolution against the "
                 "track files would be silently skipped, masking any broken refs."),
                "Pass an existing directory or omit the flag.",
            ))

    # design.md shape checks fire only when the mutation actually touched
    # design.md. mechanics-edit mutations (working mode) skip them.
    run_design_shape_checks = args.target in ("design", "both")

    if run_design_shape_checks:
        findings.extend(check_overview_first(args.design_path, design_lines, sections))
        findings.extend(check_core_concepts_when_parts(args.design_path, sections, parts))
        findings.extend(check_per_section_shape(
            args.design_path, design_lines, sections, args.changed_section, args.scope))
        findings.extend(check_top_level_cap(args.design_path, sections, parts))
        findings.extend(check_per_section_length(
            args.design_path, design_lines, sections, args.changed_section, args.scope))
        findings.extend(check_length_trigger_compliance(
            args.design_path, args.design_mechanics_path, len(design_lines)))
        findings.extend(check_same_shape_siblings(args.design_path, sections))

    # Parenthetical-aside scan runs against whichever file(s) were touched.
    # design.md and design-mechanics.md are both human-readable; the rule
    # applies to both. Bounded scope only flags asides inside the changed
    # section on the design.md side; for mechanics-edit (working mode) the
    # scan still runs whole-doc since `--changed-section` describes a
    # design.md section, not a mechanics section.
    if args.target in ("design", "both"):
        findings.extend(check_dsc_parenthetical_asides(
            args.design_path, design_lines,
            sections=sections,
            changed_section=args.changed_section,
            scope=args.scope,
        ))
        findings.extend(check_dsc_ai_tell(
            args.design_path, design_lines,
            sections=sections,
            changed_section=args.changed_section,
            scope=args.scope,
        ))
    if args.target in ("mechanics", "both") and design_mechanics_lines is not None:
        findings.extend(check_dsc_parenthetical_asides(
            args.design_mechanics_path, design_mechanics_lines))
        findings.extend(check_dsc_ai_tell(
            args.design_mechanics_path, design_mechanics_lines))

    # Cross-file link-resolution always fires — these rules guard against
    # broken references that any mutation can introduce.
    findings.extend(check_mechanics_link_resolution(
        args.design_path, design_lines, args.design_mechanics_path, design_mechanics_lines))
    findings.extend(check_full_design_link_resolution(
        args.design_path, design_lines,
        args.plan_path, plan_lines,
        track_files,
        args.design_mechanics_path, design_mechanics_lines))

    # Reverse-direction ref check: per design-document-rules.md § Length-
    # triggered split, mechanics must be self-contained — no `<design-basename>
    # §"X"` refs may appear inside mechanics. Fires whenever the mutation
    # touches mechanics (i.e., target includes mechanics).
    if args.target in ("mechanics", "both") and design_mechanics_lines is not None:
        findings.extend(check_reverse_direction_refs(
            args.design_mechanics_path,
            design_mechanics_lines,
            os.path.basename(args.design_path),
        ))

    blockers = sum(1 for f in findings if f["severity"] == "blocker")
    should_fix = sum(1 for f in findings if f["severity"] == "should-fix")
    suggestions = sum(1 for f in findings if f["severity"] == "suggestion")
    verdict = "PASS" if blockers == 0 else "NEEDS REVISION"

    output = {
        "findings": findings,
        "summary": {
            "blockers": blockers,
            "should_fix": should_fix,
            "suggestions": suggestions,
            "verdict": verdict,
        },
    }
    print(json.dumps(output, indent=2))
    return 0 if verdict == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
