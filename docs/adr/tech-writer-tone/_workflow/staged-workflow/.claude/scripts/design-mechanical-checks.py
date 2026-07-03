#!/usr/bin/env python3
"""Mechanical checks for design.md mutation discipline.

Implements the structural rules listed in
`.claude/workflow/design-document-rules.md § Mutation discipline § Mechanical
checks (always run)` plus the `dsc-ai-tell` rule that detects the
regex-expressible subset of `house-style.md` AI-tell patterns. Invoked by
the `edit-design` skill after each edit to `design.md` or
`design-mechanics.md`.

D11 (YTDB-1083 reconciliation) renamed the per-section trailing footer from
`### References` to `### Decisions & invariants`. Footer detection is
backward-compatible — both spellings are accepted (FOOTER_HEADING_RE), so a
pre-rename design keeps passing. D11 also adds the
`decision-cited-without-rationale` check: a `D<n>` cited bare under
`D-records:` whose full record is introduced nowhere in design.md is a
`should-fix`. Both are design.md-only — track files carry records in
`## Decision Log` and have no footer.

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
    # Display case on purpose: this set compares raw section titles
    # (`section["title"] in SHAPE_EXEMPT_SECTION_NAMES`), so the Part-level
    # `## Summary` analogue of the Part-level TL;DR must be listed exactly as
    # it appears in the heading.
    "Summary",
}

# Sub-heading names that are part of the per-section mandatory shape or the
# consolidation form. Excluded from the same-shape sibling similarity
# computation, since otherwise every well-formed section would look identical.
# `references` and `decisions & invariants` are both listed because the D11
# footer rename keeps both spellings valid (see FOOTER_HEADING_RE below).
MANDATORY_OR_FORM_SUBHEADINGS = {
    "tl;dr",
    # Lowercase on purpose: this set is compared against lowercased
    # sub-heading titles (`sub.lower() not in ...`). Without "summary" here,
    # the shared `### Summary` heading of every well-formed post-rename
    # section would count toward the same-shape sibling similarity score and
    # the check would false-positive on every well-formed design.
    "summary",
    "edge cases / gotchas",
    "edge case / gotchas",
    "gotchas",
    "edge cases",
    "references",
    "decisions & invariants",
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

# Inflated-abstraction label in the SUBJECT slot. Fires on a sentence whose
# subject is an inflated label used AS the thing being described — the
# placeholder-noun / vague-attribution tell from
# `house-style.md § Banned analysis patterns` — e.g. "The enabling primitive
# is …", "The key abstraction here …", "The underlying mechanism …", "The
# driving force is …", "The defining characteristic is …".
#
# The inflation-adjective slot is a CURATED CLOSED SET, not an open
# participle wildcard. A bare `[a-z]+ing` / `[a-z]+ed` arm crossed with the
# concrete label-nouns below ("mechanism", "property", "concept", "factor",
# "force") fires on ordinary concrete-mechanism prose a storage-engine design
# routinely carries — "The locking mechanism is held by the writer.", "The
# hashing mechanism provides O(1)." — none of which is the inflation tell. So
# only the words that actually signal inflation are listed: the participles
# (`enabling`, `driving`, `defining`, `unifying`, `guiding`, `governing`,
# `underpinning`, `animating`) plus the inflated adjectives (`key`, `core`,
# `central`, `underlying`, …). A concrete participle like `locking` / `hashing`
# / `polling` is deliberately absent and therefore passes.
#
# Two discriminators keep it off the design-doc Overview's sanctioned use.
# `design-document-rules.md § Overview` prescribes naming "the enabling
# primitive(s)" as Overview element 3 — so the caller SKIPS the `## Overview`
# section for this pattern (the only on-branch match, design.md:23 "The
# enabling primitives are the `## Orientation` rule text …", is exactly that
# sanctioned enumeration prose). Beyond that, the capital `The` at a
# sentence/clause start spares lowercase quoted mentions of the labels (a
# rule that quotes "the enabling primitive" as an example is not itself
# using it as a subject), and requiring a finite verb after the label spares
# the bolded enumeration form `**The enabling primitive(s)** —` (em dash, no
# verb).
INFLATED_ABSTRACTION_LABEL_RE = re.compile(
    r"(?:^|(?<=[.!?]\s)|(?<=[.!?]))\s*"
    r"The\s+"
    r"(?:enabling|driving|defining|unifying|guiding|governing|"
    r"underpinning|animating|"
    r"key|core|central|underlying|essential|fundamental|crucial|"
    r"critical|decisive|pivotal|main|primary|overarching)\s+"
    r"(?:primitive|abstraction|mechanism|insight|idea|concept|"
    r"principle|notion|force|factor|characteristic|property|"
    r"observation|realization|realisation)s?\s+"
    r"(?:is|are|lies|comes|here|becomes|provides|enables|makes|"
    r"underpins|drives|governs|holds|sits|stems|rests)\b",
)

# Persuasive authority tropes from `house-style.md § Persuasive authority
# tropes`. Track plan names three literal phrases.
AUTHORITY_TROPE_RE = re.compile(
    r"\b(at its core|fundamentally|the real question)\b",
    re.IGNORECASE,
)

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
# Decisions-and-invariants footer detection (D11 footer rename)
#
# The per-section trailing footer was renamed from `### References` to
# `### Decisions & invariants` per design-document-rules.md § Decisions &
# invariants (the D11 rename of the former references footer). The check that
# enforces footer presence, the References-block toggle used by the
# parenthetical-aside / AI-tell / paragraph scans, and the same-shape sibling
# exclusion all accept BOTH spellings so designs authored before the rename —
# this branch's own frozen design.md among them — keep passing without a
# backfill. Both the `### ` heading form and the bold-prefix `**…**` form are
# recognized. `&` is matched literally; `&amp;`-style HTML entities are not
# expected in authored Markdown footers.
#
# Single source of truth: every site that detected the old `### References`
# footer now references these two regexes so the two spellings never drift
# apart across the four toggle sites in this module.
# ---------------------------------------------------------------------------

# Matches a footer HEADING line (line-anchored at the start). Used for the
# section-end footer-presence check and the References-block toggle-on tests.
FOOTER_HEADING_RE = re.compile(
    r"^### (?:References|Decisions & invariants)\b"
    r"|^\*\*(?:References|Decisions & invariants)\.\*\*"
)

# Same two spellings but un-anchored, for the `section_has_references`
# tail-window search that joins body lines with newlines and matches at a
# line boundary (`(^|\n)`).
FOOTER_HEADING_SEARCH_RE = re.compile(
    r"(^|\n)### (?:References|Decisions & invariants)\b"
    r"|(^|\n)\*\*(?:References|Decisions & invariants)\.\*\*"
)


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
    """Return True if a summary block appears within the first head_window
    non-fence lines of the section body.

    Accepts the `### Summary` sub-heading (the current form) plus the two
    legacy TL;DR spellings (`**TL;DR.**` bold-prefix and `### TL;DR`
    sub-heading), mirroring the D11 footer-rename both-spellings precedent so
    designs authored before the rename keep passing without a backfill.
    """
    body = section_body_lines_outside_fences(lines, section)[:head_window]
    text = "\n".join(body)
    if re.search(r"(^|\n)### Summary\b", text):
        return True
    if re.search(r"(^|\n)\*\*TL;DR\.\*\*", text):
        return True
    if re.search(r"(^|\n)### TL;DR\b", text):
        return True
    return False


def section_has_references(lines: List[str], section: Dict, tail_window: int = 30) -> bool:
    """Return True if a decisions-and-invariants footer appears in the last
    tail_window non-fence lines of the section.

    Accepts both the new `### Decisions & invariants` / `**Decisions &
    invariants.**` spelling and the legacy `### References` /
    `**References.**` spelling (D11 footer rename, backward-compatible — see
    FOOTER_HEADING_SEARCH_RE).
    """
    body = section_body_lines_outside_fences(lines, section)
    if len(body) > tail_window:
        body = body[-tail_window:]
    text = "\n".join(body)
    return FOOTER_HEADING_SEARCH_RE.search(text) is not None


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
    """Check that every section (except exempt ones) has a summary block
    (`### Summary`, or a legacy TL;DR spelling) and a decisions-and-invariants
    footer."""
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
                (f"Section `{section['title']}` is missing a `### Summary` block (or a "
                 "legacy `**TL;DR.**` / `### TL;DR` block) in its first ~10 lines."),
                ("Insert a `### Summary` sub-heading (≤5 lines: what the section is about "
                 f"+ why it matters) directly after the heading at line "
                 f"{section['line_start']}. The legacy `**TL;DR.**` and `### TL;DR` "
                 "spellings are still accepted for pre-rename designs."),
            ))
        if not section_has_references(lines, section):
            findings.append(make_finding(
                "blocker",
                "per-section-shape:references-footer",
                location,
                (f"Section `{section['title']}` is missing a decisions-and-invariants footer "
                 "(no `### Decisions & invariants` / `**Decisions & invariants.**` block, and "
                 "no legacy `### References` / `**References.**` block, in its last ~30 lines)."),
                ("Append a `### Decisions & invariants` block at the end of the section listing "
                 "D-records, Invariants, and (when applicable) `Mechanics:` cross-references. "
                 "The legacy `### References` spelling is still accepted for pre-rename designs."),
            ))
    return findings


# ---------------------------------------------------------------------------
# Decision-cited-without-rationale (D11, design.md-only)
# ---------------------------------------------------------------------------

# A full D-record introduction anywhere in design.md. The canonical
# introduce-once markup (used by every other ADR's design-final.md) is a
# bold-prefix paragraph `**D<n>. <title>.** …` or `**D<n>: …**`; a `### D<n>`
# / `#### D<n>` heading is the equally-valid heading form. Either satisfies
# "the decision's full record is introduced once somewhere in design.md".
# The `[.:]` terminator (not `\b`) is what delimits the code in the
# bold-prefix form: `**D1.` ends in `. ` (space follows, no word boundary),
# so a trailing `\b` would never match; `[.:]` already prevents `**D12.` from
# matching as `D1`. The heading form keeps `\b` because `#### D7 ` is followed
# by whitespace.
_DRECORD_FULL_INTRO_RE = re.compile(
    r"^\s*(?:#{2,6}\s+D(\d+)\b"          # heading form: ## … #### D7 …
    r"|\*\*D(\d+)[.:])"                  # bold-prefix form: **D7.** / **D7:**
)

# A footer `D-records:` list entry: a `D<n>` code optionally followed by an
# inline parenthetical rationale `(…)`. The capture groups are the code
# number and the immediate next non-space character after the code, used to
# decide whether the citation carries inline rationale or is bare.
_FOOTER_DCODE_RE = re.compile(r"\bD(\d+)\b")


def collect_full_drecord_intros(lines: List[str]) -> set:
    """Return the set of D-record numbers whose full record is introduced
    somewhere in design.md (bold-prefix `**D<n>.**`/`**D<n>:**` paragraph or
    `### D<n>` / `#### D<n>` heading), ignoring fenced code blocks.

    This is the "introduced once somewhere in design.md" half of the D11
    introduce-once rule. A citation in any section's footer is satisfied when
    its code appears here.
    """
    intros: set = set()
    open_fence: Optional[Tuple[str, int]] = None
    for line in lines:
        if open_fence is None:
            parsed = parse_code_fence(line)
            if parsed is not None:
                open_fence = parsed
                continue
        else:
            if fence_closes(open_fence, line):
                open_fence = None
            continue
        m = _DRECORD_FULL_INTRO_RE.match(line)
        if m is not None:
            num = m.group(1) or m.group(2)
            if num is not None:
                intros.add(int(num))
    return intros


def _footer_block_lines(lines: List[str], section: Dict) -> List[Tuple[int, str]]:
    """Return `(line_no, text)` for every body line inside the section's
    decisions-and-invariants footer (from the footer heading through the end
    of the section / the next heading), excluding the footer-heading line
    itself and fenced-code-block content.

    Both footer spellings (D11) toggle the block on; any subsequent heading
    (including a deeper `###`) closes it.
    """
    out: List[Tuple[int, str]] = []
    in_footer = False
    open_fence: Optional[Tuple[str, int]] = None
    start = section["line_start"]
    end = section["line_end"] or len(lines)
    for i in range(start, end + 1):
        line = lines[i - 1] if 0 <= i - 1 < len(lines) else ""
        if open_fence is None:
            parsed = parse_code_fence(line)
            if parsed is not None:
                open_fence = parsed
                continue
        else:
            if fence_closes(open_fence, line):
                open_fence = None
            continue
        if FOOTER_HEADING_RE.match(line):
            in_footer = True
            continue
        # Any other heading ends the footer block.
        if re.match(r"^#{1,6}\s", line):
            in_footer = False
            continue
        if in_footer:
            out.append((i, line))
    return out


def _iter_footer_dcode_citations(
    footer_lines: List[Tuple[int, str]],
) -> Iterator[Tuple[int, int, bool]]:
    """Yield `(line_no, code_number, has_inline_rationale)` for each `D<n>`
    citation appearing on a `D-records:` entry (and its continuation lines).

    `has_inline_rationale` is True when the code is immediately followed by a
    `(` parenthetical or by descriptive prose on the same logical entry — the
    shape this branch's frozen design.md uses (`D2 (two-gate model …)`). It is
    False only for a genuinely bare code: `D2`, or `D2,` / `D2)` with nothing
    but list punctuation after it, or a code directly followed by the next
    code. A bare code with no full record elsewhere is what the rule fires on.

    Only TOP-LEVEL codes are citations. A `D<n>` nested inside another code's
    parenthetical (`D1 (see D2)`, `D1 (supersedes D2, D3)`) is part of that
    code's rationale, not a citation in its own right. Scanning every token
    would let `_FOOTER_DCODE_RE.finditer` over the whole joined entry surface a
    nested code as its own citation — its `rest` is `)` or `,`, so the
    rationale heuristic judges it bare, a false positive when that nested code
    has no full record elsewhere. The scan therefore tracks parenthetical depth
    across the joined string and skips any code match whose start offset falls
    inside an open `(`; only depth-0 codes are yielded.

    A `D-records:` list wraps freely across continuation lines (indented, no
    leading `- `), and a code's parenthetical rationale can begin on a line
    after the code itself (`… D12` / next line `(mid-flight tier upgrade), …`).
    To evaluate the rationale correctly, the whole `D-records:` entry is folded
    into one joined string before scanning; the finding line number for each
    code is recovered from a per-offset line map so the location still points
    at the line the code physically appears on.
    """
    # Group footer lines into `D-records:` entries. An entry begins on a line
    # containing `D-records:` and continues onto indented non-bullet, non-empty
    # continuation lines until the next `- ` bullet (Invariants:, Mechanics:)
    # or a blank line.
    entries: List[List[Tuple[int, str]]] = []
    current: Optional[List[Tuple[int, str]]] = None
    for line_no, text in footer_lines:
        stripped = text.strip()
        if re.search(r"\bD-records:", text):
            current = [(line_no, text)]
            entries.append(current)
        elif current is not None:
            if (not stripped) or stripped.startswith("- "):
                current = None
            else:
                current.append((line_no, text))

    for entry in entries:
        # Build the joined text plus a parallel list mapping each character
        # offset in the joined string back to its source line number. Lines
        # are joined with a single space (matching how the entry reads when
        # unwrapped), and the joining space inherits the preceding line's
        # number so a code at end-of-line maps to that line.
        joined_parts: List[str] = []
        offset_line: List[int] = []
        for idx, (line_no, text) in enumerate(entry):
            if idx > 0:
                joined_parts.append(" ")
                offset_line.append(entry[idx - 1][0])
            joined_parts.append(text)
            offset_line.extend([line_no] * len(text))
        joined = "".join(joined_parts)

        # Parenthetical depth at each character offset, so a code match can be
        # tested for being inside an open `(`. Depth is the count of unclosed
        # `(` strictly before the offset; a `(` raises the depth of the chars
        # that follow it, a `)` lowers the depth of the chars that follow it.
        # Depth is clamped at 0 so a stray `)` (unbalanced footer prose) cannot
        # drive it negative and re-expose later nested codes as top-level.
        depth_at: List[int] = []
        depth = 0
        for ch in joined:
            depth_at.append(depth)
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth = max(0, depth - 1)

        for m in _FOOTER_DCODE_RE.finditer(joined):
            # Skip codes nested inside another code's parenthetical — they are
            # that code's rationale, not citations in their own right.
            if m.start() < len(depth_at) and depth_at[m.start()] > 0:
                continue
            num = int(m.group(1))
            code_line = offset_line[m.start()] if m.start() < len(offset_line) else entry[0][0]
            rest = joined[m.end():].lstrip()
            # Inline rationale: a `(` parenthetical, or descriptive words that
            # are not just the next code / list punctuation. A bare code is
            # followed by end-of-entry, a comma, a closing paren, or directly
            # by the next `D<n>` code with nothing in between.
            has_rationale = False
            if rest.startswith("("):
                has_rationale = True
            elif rest and rest[0] not in ",)":
                # Something other than list punctuation follows — but it must
                # not be merely the next code. Strip a leading `&`.
                lead = rest.lstrip("&").strip()
                if lead and not _FOOTER_DCODE_RE.match(lead):
                    has_rationale = True
            yield code_line, num, has_rationale


def check_decision_cited_without_rationale(
    design_path: str,
    lines: List[str],
    sections: List[Dict],
) -> List[Dict]:
    """Within each section's decisions-and-invariants footer, every `D<n>`
    cited under `D-records:` must either carry inline rationale in the footer
    OR have its full record introduced once somewhere in design.md.

    A bare `D<n>` citation (no inline rationale) whose full record appears in
    no section is a `should-fix`: the seed references a decision it never
    states. Per design-document-rules.md § Decisions & invariants
    (introduce-once within the seed, D11).

    The check is **design.md-only** by construction — the driver calls it only
    inside `run_design_shape_checks` (target `design`/`both`), never against
    track files (which carry records in `## Decision Log`, have no footer) or
    mechanics.

    Scoping: the legacy bare-`D<n>` `### References` footer of a pre-rename
    design does not trip it whenever the cited record's full intro exists
    elsewhere in the same design.md (regardless of footer spelling). This
    branch's own frozen design.md passes because every footer code carries an
    inline parenthetical rationale.
    """
    findings: List[Dict] = []
    full_intros = collect_full_drecord_intros(lines)
    for section in sections:
        # Shape-exempt sections (Overview, Core Concepts, Class Design,
        # Workflow, Part-level TL;DR) have no decisions-and-invariants footer
        # and are skipped, mirroring the per-section shape check.
        if is_shape_exempt(section):
            continue
        footer_lines = _footer_block_lines(lines, section)
        if not footer_lines:
            continue
        # De-dup per (section, code): one finding per bare-and-unintroduced
        # code in this section even if the code is mentioned more than once.
        flagged: set = set()
        for line_no, num, has_rationale in _iter_footer_dcode_citations(footer_lines):
            if has_rationale or num in full_intros:
                continue
            if num in flagged:
                continue
            flagged.add(num)
            findings.append(make_finding(
                "should-fix",
                "decision-cited-without-rationale",
                f"{design_path}:{line_no}",
                (f"`D{num}` is cited bare in the `{section['title']}` section's "
                 "decisions-and-invariants footer (no inline rationale) and its full record is "
                 "introduced in no section of design.md. Per design-document-rules.md "
                 "§ Decisions & invariants (introduce-once within the seed, D11), the seed must "
                 "introduce each decision once in full or carry its rationale inline in the "
                 "footer; a footer that cites a decision it never states is a dangling reference."),
                (f"Either add an inline rationale to the `D{num}` citation in the footer "
                 f"(e.g. `D{num} (<one-line rationale>)`) or introduce `D{num}`'s full record "
                 "once in the section that owns it."),
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
                 "Move long-form material to `design-mechanics.md` and reference via the "
                 "`### Decisions & invariants` footer."),
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
    ("D27 makes histograms volatile") and inside the
    `### Decisions & invariants` footer block (or the legacy `### References`).

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

        # Footer block toggles on `### Decisions & invariants` /
        # `**Decisions & invariants.**` or the legacy `### References` /
        # `**References.**` (D11 footer rename), ends on the next heading.
        if FOOTER_HEADING_RE.match(line):
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

    Three patterns fire (each finding cites `house-style.md § <Section>`
    in its description):

    1. Persuasive authority tropes (`§ Persuasive authority tropes`).
    2. Fragmented header (`§ Fragmented headers`).
    3. Inflated-abstraction label in the subject slot
        (`§ Banned analysis patterns`).

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

    # Resolve the `## Overview` section's line range, if a section list was
    # supplied. The inflated-abstraction-label scan below skips this range:
    # `design-document-rules.md § Overview` prescribes naming
    # "the enabling primitive(s)" as Overview element 3, so a subject-slot
    # "The enabling primitive is …" there is the sanctioned enumeration prose,
    # not the AI-tell. Track files and the mechanics file pass `sections=None`
    # (no Overview section), so the range stays None and nothing is skipped.
    overview_range: Optional[Tuple[int, int]] = None
    if sections is not None:
        overview_section = next(
            (s for s in sections if normalize_heading(s["title"]) == "overview"),
            None,
        )
        if overview_section is not None:
            overview_range = (
                overview_section["line_start"],
                overview_section["line_end"] or len(lines),
            )
    in_overview = (
        lambda line_no: overview_range is not None
        and overview_range[0] <= line_no <= overview_range[1]
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

        # Footer-block toggle: on at `### Decisions & invariants` /
        # `**Decisions & invariants.**` or the legacy `### References` /
        # `**References.**` (D11), off at the next heading.
        if FOOTER_HEADING_RE.match(line):
            in_references = True
            continue
        is_heading = bool(re.match(r"^#{1,6}\s", line))
        if is_heading:
            in_references = False

        if in_references:
            continue
        if line.lstrip().startswith("|"):
            continue
        if not in_range(i):
            continue

        # Persuasive authority-trope scan runs on heading lines too: an AI
        # tell like "at its core" inside an H2/H3 is the same signal as in
        # body prose and the "every authored prose surface" scope in
        # `house-style.md § What this style governs` does not exempt
        # heading text.
        for m in AUTHORITY_TROPE_RE.finditer(line):
            findings.append(make_finding(
                "should-fix",
                "dsc-ai-tell",
                f"{file_path}:{i}",
                (f"Persuasive authority trope per house-style.md § Persuasive "
                 f"authority tropes: '{m.group(0)}'. State the actual mechanism."),
                f"Cut '{m.group(0)}' at line {i}; name the mechanism directly.",
            ))

        # Inflated-abstraction label in the subject slot. Skipped inside the
        # `## Overview` section, where naming "the enabling primitive(s)" is
        # the prescribed Overview element rather than the AI-tell.
        if not in_overview(i):
            for m in INFLATED_ABSTRACTION_LABEL_RE.finditer(line):
                label = m.group(0).strip()
                findings.append(make_finding(
                    "should-fix",
                    "dsc-ai-tell",
                    f"{file_path}:{i}",
                    (f"Inflated-abstraction label per house-style.md § Banned "
                     f"analysis patterns: '{label[:80]}'. Naming an abstract "
                     "category ('the enabling primitive', 'the key abstraction') "
                     "as the subject hides the concrete thing."),
                    f"Rewrite the sentence at line {i} to lead with the concrete "
                    "thing the label stands for, not the category noun.",
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
        findings.extend(check_decision_cited_without_rationale(
            args.design_path, design_lines, sections))
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
