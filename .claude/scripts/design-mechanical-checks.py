#!/usr/bin/env python3
"""Mechanical checks for design.md mutation discipline.

Implements the structural rules listed in
`.claude/workflow/design-document-rules.md § Mutation discipline § Mechanical
checks (always run)`. Invoked by the `edit-design` skill after each edit to
`design.md` or `design-mechanics.md`.

Usage:
    python3 .claude/scripts/design-mechanical-checks.py \
        --design-path docs/adr/<dir>/design.md \
        [--design-mechanics-path docs/adr/<dir>/design-mechanics.md] \
        [--plan-path docs/adr/<dir>/implementation-plan.md] \
        [--backlog-path docs/adr/<dir>/implementation-backlog.md] \
        [--changed-section "Section Title"] \
        [--scope bounded|whole-doc]

Output: JSON to stdout. Exit code 0 on PASS, 1 on NEEDS REVISION (any blocker).
"""

import argparse
import json
import os
import re
import sys
from typing import Dict, List, Optional, Set, Tuple


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


# ---------------------------------------------------------------------------
# Markdown parsing
# ---------------------------------------------------------------------------


def read_lines(path: str) -> List[str]:
    """Read a file as a list of lines (no trailing newline preserved)."""
    with open(path, "r", encoding="utf-8") as f:
        # Keep newlines stripped — heading regexes don't need them.
        return f.read().splitlines()


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
    in_code_fence = False

    for i, line in enumerate(lines, start=1):
        # Fenced code block tracking — toggles on lines beginning with ``` or ~~~.
        if re.match(r"^(```|~~~)", line):
            in_code_fence = not in_code_fence
            continue
        if in_code_fence:
            continue

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
    in_code_fence = False
    for i, line in enumerate(lines, start=1):
        if re.match(r"^(```|~~~)", line):
            in_code_fence = not in_code_fence
            continue
        if in_code_fence:
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
    """First `## ` heading in design.md must be `## Overview`.

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
    if first["title"] != "Overview":
        findings.append(make_finding(
            "blocker",
            "overview-first",
            f"{design_path}:{first['line_start']}",
            (f"First `## ` section is `{first['title']}`, not `Overview`. "
             "Per design-document-rules.md § Required content, every design.md must open with a "
             "`## Overview` section as the first content under the title — concept-first elevator "
             "pitch, no meta-navigation ahead of the concept."),
            "Reorder so `## Overview` is the first `## ` heading; fold any meta-navigation "
            "(audience, journey table, companion-file pointer) into the tail of Overview.",
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
    # Find Core Concepts section (must come after Overview, before any Part).
    # If absent, flag.
    concept_section = next(
        (s for s in sections if s["title"] == "Core Concepts" and s["parent_part"] is None),
        None,
    )
    if concept_section is None:
        # Locate where it should be inserted (right after Overview, conventionally).
        overview = next((s for s in sections if s["title"] == "Overview"), None)
        loc_line = overview["line_end"] + 1 if overview else 1
        findings.append(make_finding(
            "should-fix",
            "core-concepts-when-parts",
            f"{design_path}:{loc_line}",
            (f"design.md has {len(parts)} `# Part N` heading(s) but no `## Core Concepts` section "
             "between Overview and Class Design. Multi-Part docs introduce more domain vocabulary "
             "than Overview's ≤40 lines can absorb; the Parts then dive into mechanics assuming "
             "the reader already has the concepts. Per design-document-rules.md § Core Concepts "
             "(conditional), add a `## Core Concepts` section that names each load-bearing idea "
             "(component op, logical rollback, etc.), defines it in plain language, states the "
             "delta from baseline, and points at the Part(s) that elaborate."),
            "Insert a `## Core Concepts` section after Overview, with one bold-prefix paragraph "
            "per load-bearing concept ending with a `→ Part X §\"…\"` pointer.",
        ))
    return findings


def section_body_lines(lines: List[str], section: Dict) -> List[str]:
    """Return the body lines of a section (excluding its heading), 0-indexed slice."""
    return lines[section["line_start"]:section["line_end"]]


def section_has_tldr(lines: List[str], section: Dict, head_window: int = 15) -> bool:
    """Return True if a TL;DR appears within the first head_window lines of the section body."""
    body = section_body_lines(lines, section)[:head_window]
    text = "\n".join(body)
    if re.search(r"(^|\n)\*\*TL;DR\.\*\*", text):
        return True
    if re.search(r"(^|\n)### TL;DR\b", text):
        return True
    return False


def section_has_references(lines: List[str], section: Dict, tail_window: int = 30) -> bool:
    """Return True if a References block appears in the last tail_window lines of the section."""
    body = section_body_lines(lines, section)
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
    for section in sections:
        if is_shape_exempt(section):
            continue
        # When scope is "bounded" and a changed section is specified, only check that section.
        if scope == "bounded" and changed_section is not None and section["title"] != changed_section:
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

    Rule: ≤ 15 `## ` total when no `# Part N` headings exist; when Parts are
    used (the prescribed escape hatch), apply per-Part caps instead since the
    Parts themselves are the consolidation. The total `## ` count alone over-
    flags well-structured docs that already used Parts.
    """
    findings: List[Dict] = []
    if not parts:
        # No Parts; apply the flat 15 cap.
        if len(sections) > TOP_LEVEL_CAP:
            findings.append(make_finding(
                "should-fix",
                "top-level-cap",
                f"{design_path}:1",
                (f"design.md has {len(sections)} `## ` sections (cap: ~{TOP_LEVEL_CAP}). "
                 "Group related sections into `# Part N — <name>` headings or move long-form "
                 "material to `design-mechanics.md`."),
                "Group ## sections into `# Part N — <name>` parents.",
            ))
        return findings

    # Parts exist; apply per-Part caps.
    by_part: Dict[Optional[str], List[Dict]] = {}
    for s in sections:
        by_part.setdefault(s["parent_part"], []).append(s)
    for part_title, part_sections in by_part.items():
        if part_title is None:
            # The pre-Part region (Reader Orientation, Overview, Class Design, Workflow).
            # No cap — these are the canonical 4 sections.
            continue
        n = len(part_sections)
        # Find the part dict for line number.
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
) -> List[Dict]:
    """Each `## ` section ≤ 300 lines (warn at 200, blocker at 400)."""
    findings: List[Dict] = []
    for section in sections:
        if is_shape_exempt(section):
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
) -> List[Dict]:
    """Reject `(per D27)`, `(see S14)` style parenthetical asides anywhere in prose.

    Allowed: D-codes / S-codes when they are the SUBJECT of a sentence
    ("D27 makes histograms volatile") and inside the `### References` block.

    Operates on whichever file the caller passes — design.md or
    design-mechanics.md. Parenthetical asides are forbidden in both.
    """
    findings: List[Dict] = []
    # Track which lines are inside a References block (which legitimately lists D/S codes).
    in_references = False
    in_code_fence = False
    in_table_row = False

    aside_patterns = [
        re.compile(r"\([Pp]er D\d+(?:\s*[,/]\s*D\d+)*\)"),
        re.compile(r"\([Pp]er S\d+(?:\s*[,/]\s*S\d+)*\)"),
        re.compile(r"\([Ss]ee D\d+(?:\s*[,/]\s*D\d+)*\)"),
        re.compile(r"\([Ss]ee S\d+(?:\s*[,/]\s*S\d+)*\)"),
    ]

    for i, line in enumerate(lines, start=1):
        if re.match(r"^(```|~~~)", line):
            in_code_fence = not in_code_fence
            continue
        if in_code_fence:
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


def check_same_shape_siblings(
    design_path: str,
    sections: List[Dict],
) -> List[Dict]:
    """Detect 3+ sibling `## ` sections with ≥80% sub-heading-name overlap."""
    findings: List[Dict] = []

    # Group sections by parent_part.
    by_part: Dict[Optional[str], List[Dict]] = {}
    for s in sections:
        if is_shape_exempt(s):
            continue
        # Drop mandatory/form sub-headings before computing the shape signature.
        custom_subs = {
            sub.lower()
            for sub in s["sub_headings"]
            if sub.lower() not in MANDATORY_OR_FORM_SUBHEADINGS
        }
        # Sections without any custom sub-heading have no shape to compare;
        # skip them.
        if not custom_subs:
            continue
        s_with_shape = {**s, "_shape": custom_subs}
        by_part.setdefault(s["parent_part"], []).append(s_with_shape)

    def jaccard(a: Set[str], b: Set[str]) -> float:
        if not a and not b:
            return 1.0
        if not a or not b:
            return 0.0
        return len(a & b) / len(a | b)

    # For each part, find clusters of 3+ sections with pairwise Jaccard ≥ 0.8.
    for part_title, group in by_part.items():
        if len(group) < 3:
            continue
        # Greedy clustering — start from each section, find all neighbors with
        # Jaccard ≥ 0.8 (transitively), report when cluster size ≥ 3.
        clustered: Set[int] = set()
        for i, sec_i in enumerate(group):
            if i in clustered:
                continue
            cluster = [i]
            for j, sec_j in enumerate(group):
                if j == i or j in clustered:
                    continue
                # Connected if it shares ≥0.8 with EVERY current cluster member.
                if all(jaccard(sec_i["_shape"], group[k]["_shape"]) >= 0.8 for k in cluster):
                    if jaccard(sec_i["_shape"], sec_j["_shape"]) >= 0.8:
                        cluster.append(j)
            if len(cluster) >= 3:
                titles = [group[k]["title"] for k in cluster]
                first_loc = group[cluster[0]]["line_start"]
                findings.append(make_finding(
                    "should-fix",
                    "same-shape-siblings",
                    f"{design_path}:{first_loc}",
                    (f"{len(cluster)} sibling sections under `{part_title or '(no Part)'}` share the "
                     f"same internal sub-heading shape: {titles}. Per design-document-rules.md "
                     "§ Consolidation form for sibling sections, 3+ siblings with the same shape "
                     "MUST be consolidated under one parent section using the consolidation form "
                     "(TL;DR + Comparison table + Per-instance short bodies)."),
                    f"Merge {titles} into one parent section using the consolidation form.",
                ))
                clustered.update(cluster)
    return findings


def collect_section_ref_targets(text: str) -> List[Tuple[str, str]]:
    """Extract `<filename>.md §"<section name>"` references from a chunk of text.

    Returns a list of (filename, section_name) tuples. Handles the common
    forms `design.md §"X"`, `design.md Part N §"X"`, and the `+ §"X"`
    continuation pattern within one line.
    """
    refs: List[Tuple[str, str]] = []
    # Match `<filename>.md` followed by zero or more `Part N`/whitespace chars,
    # then one or more `§"name"` chunks (separated by `+` or commas).
    pattern = re.compile(
        r"(\bdesign(?:-mechanics)?\.md)"          # filename
        r"(?:\s+Part\s+\d+)?"                     # optional Part qualifier
        r"\s*§"                                   # section sigil
        r"(?:\s*[\+,]\s*§)*"                      # tolerate `+ §` continuations
        r"\s*\"([^\"]+)\""                        # first quoted name
    )
    # Continuation pattern for additional `+ §"name"` chunks attached to the
    # same primary reference.
    continuation = re.compile(r"§\s*\"([^\"]+)\"")

    # We do this in two passes: first match whole `filename.md ... §"name"`,
    # then sweep all `§"name"` quotes that come *after* a recent filename.
    # Simpler: iterate by line and track the active filename.
    return refs  # superseded by line-by-line collector below


def collect_refs_line_by_line(filepath: str, lines: List[str]) -> List[Tuple[int, str, str]]:
    """Walk `lines` and return `(line_number, filename, section_name)` for every
    cross-reference of the form `<filename>.md ... §"name"` (including
    continuations via `+ §"name"`).

    The active filename is bound by the most recent `<filename>.md` token on
    the same line; subsequent `§"name"` quotes on that line attach to it.
    """
    refs: List[Tuple[int, str, str]] = []
    fname_re = re.compile(r"\b(design(?:-mechanics)?\.md)\b")
    quote_re = re.compile(r"§\s*\"([^\"]+)\"")
    for i, line in enumerate(lines, start=1):
        # Find all (offset, filename) on this line.
        fname_matches = list(fname_re.finditer(line))
        if not fname_matches:
            continue
        # Walk all `§"name"` quotes in order; each binds to the most recent
        # filename whose offset is ≤ the quote's offset.
        for q in quote_re.finditer(line):
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
    """Every `Mechanics: design-mechanics.md §"<name>"` in design.md must resolve
    to a heading in design-mechanics.md."""
    findings: List[Dict] = []
    if design_mechanics_path is None or design_mechanics_lines is None:
        return findings

    # All headings in design-mechanics.md, normalized.
    mech_headings = collect_all_headings(design_mechanics_lines)
    norm_targets = {normalize_heading(t): (line_no, t) for line_no, _level, t in mech_headings}

    refs = collect_refs_line_by_line(design_path, design_lines)
    for line_no, fname, ref_name in refs:
        if fname != "design-mechanics.md":
            continue
        if normalize_heading(ref_name) not in norm_targets:
            findings.append(make_finding(
                "blocker",
                "mechanics-link-resolution",
                f"{design_path}:{line_no}",
                (f"`design-mechanics.md §\"{ref_name}\"` does not resolve to any heading in "
                 f"`{design_mechanics_path}`."),
                ("Either rename the design-mechanics.md heading to match (recommended — heading "
                 "names should track design.md) or update the reference."),
            ))
    return findings


def check_full_design_link_resolution(
    design_path: str,
    design_lines: List[str],
    plan_path: Optional[str],
    plan_lines: Optional[List[str]],
    backlog_path: Optional[str],
    backlog_lines: Optional[List[str]],
    design_mechanics_path: Optional[str],
    design_mechanics_lines: Optional[List[str]],
) -> List[Dict]:
    """Every `**Full design**: design.md §"<name>"` in plan and backlog must
    resolve to a heading in design.md (and any chained `design-mechanics.md
    §"<name>"` in the same line must resolve in design-mechanics.md)."""
    findings: List[Dict] = []
    if plan_lines is None and backlog_lines is None:
        return findings

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
            target_table = design_norm if fname == "design.md" else mech_norm
            target_label = "design.md" if fname == "design.md" else "design-mechanics.md"
            if fname == "design-mechanics.md" and design_mechanics_lines is None:
                out.append(make_finding(
                    "blocker",
                    "full-design-link-resolution",
                    f"{path}:{line_no}",
                    (f"`{target_label} §\"{ref_name}\"` referenced from `{path}` but "
                     "`design-mechanics.md` was not provided as a path."),
                    "Pass `--design-mechanics-path` so the reference can be resolved.",
                ))
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
    if backlog_lines is not None and backlog_path is not None:
        findings.extend(check_file(backlog_path, backlog_lines))
    return findings


# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--design-path", required=True, help="Absolute path to design.md")
    p.add_argument("--design-mechanics-path", help="Absolute path to design-mechanics.md (optional)")
    p.add_argument("--plan-path", help="Absolute path to implementation-plan.md (optional)")
    p.add_argument("--backlog-path", help="Absolute path to implementation-backlog.md (optional)")
    p.add_argument("--changed-section", help="Title of the section that changed (for bounded scope)")
    p.add_argument("--scope", choices=("bounded", "whole-doc"), default="whole-doc",
                   help="Scope of checks (default: whole-doc)")
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
                         "variants; omit --plan-path and --backlog-path so the cross-file ref "
                         "check is naturally skipped — Phase 4 produces a new artifact, not a "
                         "modification of the original design.md.)"))
    return p.parse_args()


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

    design_mechanics_lines: Optional[List[str]] = None
    if args.design_mechanics_path and os.path.exists(args.design_mechanics_path):
        design_mechanics_lines = read_lines(args.design_mechanics_path)

    plan_lines: Optional[List[str]] = None
    if args.plan_path and os.path.exists(args.plan_path):
        plan_lines = read_lines(args.plan_path)

    backlog_lines: Optional[List[str]] = None
    if args.backlog_path and os.path.exists(args.backlog_path):
        backlog_lines = read_lines(args.backlog_path)

    findings: List[Dict] = []

    # design.md shape checks fire only when the mutation actually touched
    # design.md. mechanics-edit mutations (working mode) skip them.
    run_design_shape_checks = args.target in ("design", "both")

    if run_design_shape_checks:
        findings.extend(check_overview_first(args.design_path, design_lines, sections))
        findings.extend(check_core_concepts_when_parts(args.design_path, sections, parts))
        findings.extend(check_per_section_shape(
            args.design_path, design_lines, sections, args.changed_section, args.scope))
        findings.extend(check_top_level_cap(args.design_path, sections, parts))
        findings.extend(check_per_section_length(args.design_path, design_lines, sections))
        findings.extend(check_length_trigger_compliance(
            args.design_path, args.design_mechanics_path, len(design_lines)))
        findings.extend(check_same_shape_siblings(args.design_path, sections))

    # Parenthetical-aside scan runs against whichever file(s) were touched.
    # design.md and design-mechanics.md are both human-readable; the rule
    # applies to both.
    if args.target in ("design", "both"):
        findings.extend(check_dsc_parenthetical_asides(args.design_path, design_lines))
    if args.target in ("mechanics", "both") and design_mechanics_lines is not None:
        findings.extend(check_dsc_parenthetical_asides(
            args.design_mechanics_path, design_mechanics_lines))

    # Cross-file link-resolution always fires — these rules guard against
    # broken references that any mutation can introduce.
    findings.extend(check_mechanics_link_resolution(
        args.design_path, design_lines, args.design_mechanics_path, design_mechanics_lines))
    findings.extend(check_full_design_link_resolution(
        args.design_path, design_lines,
        args.plan_path, plan_lines,
        args.backlog_path, backlog_lines,
        args.design_mechanics_path, design_mechanics_lines))

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
