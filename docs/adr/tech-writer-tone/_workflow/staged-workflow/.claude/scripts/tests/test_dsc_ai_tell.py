#!/usr/bin/env python3
"""Validation runner for the `dsc-ai-tell` rule in
`.claude/scripts/design-mechanical-checks.py`.

Running this script is the validation: it shells out to
`design-mechanical-checks.py` against the seeded fixture and the three
calibration ADRs, parses each JSON output, and asserts:

1. The fixture exercises every banned pattern — each pattern in
   `PATTERN_SIGNATURES` below has at least one `dsc-ai-tell` finding.
2. The fixture's negative-case paragraph (the concrete named
   mechanisms the inflated-abstraction-label rule must not fire on)
   emits zero `dsc-ai-tell` findings.
3. The Overview enabling-primitive sentence (line 16) emits zero
   inflated-abstraction-label findings — the `## Overview` section is
   skipped because naming "the enabling primitive(s)" is the prescribed
   Overview element there.
4. The three calibration ADRs (`persist-visible-count`, `index-gc`,
   `non-durable-wow`) each emit zero `dsc-ai-tell` findings — the
   zero-false-positive contract the rule was calibrated against.
   Skipped under `--fixture-only` (see below).

Invocation (from repo root):

    python3 .claude/scripts/tests/test_dsc_ai_tell.py

Fixture-only mode:

    python3 .claude/scripts/tests/test_dsc_ai_tell.py --fixture-only

`--fixture-only` runs the fixture assertions (groups 1-3) and skips the
calibration-ADR group entirely. The mode exists because `REPO_ROOT`
resolves through `parents[3]`, and when this file runs from a staged
mirror of `.claude/` (a `§1.7` workflow-staging subtree) that root
contains the `.claude/**` mirror — so `SCRIPT` and `FIXTURE` resolve —
but no `docs/adr/**` corpus, so the calibration assertions cannot run
in place. Run the full suite (no flag) from the live tree, where both
groups resolve.

Exit code 0: every assertion passed. Exit code 1: one or more failed;
each failure prints to stderr.

Manifest — `dsc-ai-tell` baseline counts on existing ADRs at the time
this runner was authored (informational; the runner does not fail on
these). Survey across `docs/adr/*/adr.md` excluding the three
calibration ADRs:

    docs/adr/thin-workflow/adr.md           6
    docs/adr/unit-test-coverage/adr.md      1
    docs/adr/ytdb-817-new-track-format/adr.md  5

Total 12 findings across 7 non-calibration ADRs. The counts are the
pre-shrink baseline (before the four removed patterns — Tier-1
vocabulary, em-dash density, signposting, copula avoidance — were cut),
so the live count on these ADRs may now be lower; the figures are
informational and the runner asserts nothing against them. The three
calibration ADRs (`persist-visible-count`, `index-gc`, `non-durable-wow`)
hold at zero — the rule's zero-false-positive contract on those three
files is the contract this runner enforces.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Tuple

REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT = REPO_ROOT / ".claude" / "scripts" / "design-mechanical-checks.py"
FIXTURE = (REPO_ROOT / ".claude" / "scripts" / "tests" / "fixtures"
           / "dsc-ai-tell-fixture.md")

CALIBRATION_ADRS = [
    REPO_ROOT / "docs" / "adr" / "persist-visible-count" / "adr.md",
    REPO_ROOT / "docs" / "adr" / "index-gc" / "adr.md",
    REPO_ROOT / "docs" / "adr" / "non-durable-wow" / "adr.md",
]

# Pattern identity is keyed on a stable description prefix carried by each
# `dsc-ai-tell` finding (`<Pattern label> per house-style.md § <Section>`).
# Grouping by description rather than raw count handles the Title-Case case
# where the rule fires on a real H3 heading whose line range differs from
# the H3 body lines.
PATTERN_SIGNATURES: List[Tuple[str, str]] = [
    ("authority-trope",
     "Persuasive authority trope per house-style.md § Persuasive"),
    ("fragmented-header",
     "Fragmented header per house-style.md § Fragmented headers"),
    ("inflated-abstraction-label",
     "Inflated-abstraction label per house-style.md § Banned analysis"),
]

# Negative-case line ranges in the fixture. One `### *negative` H3
# block carries a zero-finding body: concrete named mechanisms (51-62,
# which the inflated-abstraction-label rule must leave alone because
# its adjective slot is a curated closed set of inflation words, not an
# open participle wildcard — "The locking mechanism is held …" / "The
# hashing mechanism provides …" do not fire). The Overview
# enabling-primitive sentence at line 16 is a second negative case,
# exercised by its own assertion below (the inflated-abstraction-label
# rule skips the `## Overview` section). The runner enforces zero
# `dsc-ai-tell` findings whose location line falls inside any of these
# ranges. The trailing `## Banned-pattern regressions` section (line
# 64+) is positive-case territory and must not be folded into a
# negative range.
OVERVIEW_INFLATED_LABEL_LINE = 16
NEGATIVE_RANGES: List[Tuple[int, int, str]] = [
    (51, 62, "Concrete named mechanism negative (inflated-label rule must not fire)"),
]

# Anchored regression cases. Each pair `(line, description_prefix)` must
# appear at least once in the fixture findings — these guard specific
# bugfixes that the pattern-coverage assertion above is too coarse to
# catch (a regression could remove one anchored finding while leaving
# the per-pattern count at >= 1 elsewhere in the fixture).
#
# Line 66: fragmented-header continuation regression — a one-line
# paragraph immediately followed by another heading (no blank line)
# was previously misclassified as paragraph continuation, suppressing
# the rule.
# Line 40: inflated-abstraction-label positive anchor — a subject-slot
# "The underlying mechanism is …" outside the `## Overview` section
# must fire.
ANCHORED_REGRESSION_CASES: List[Tuple[int, str, str]] = [
    (66, "Fragmented header",
     "fragmented-header continuation regression: one-liner followed "
     "by a heading with no intervening blank line must fire"),
    (40, "Inflated-abstraction label",
     "inflated-abstraction positive anchor: a subject-slot 'The "
     "underlying mechanism is …' outside ## Overview must fire"),
]


def run_script(target_path: Path) -> List[Dict]:
    """Invoke design-mechanical-checks.py and return only dsc-ai-tell findings."""
    try:
        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--design-path",
                str(target_path),
                "--target",
                "design",
                "--scope",
                "whole-doc",
            ],
            capture_output=True,
            text=True,
            check=False,
            timeout=60,
        )
    except subprocess.TimeoutExpired as exc:
        raise SystemExit(
            f"design-mechanical-checks.py timed out after 60s on "
            f"{target_path} (possible infinite loop in a paragraph walker "
            f"or regex backtracking)."
        ) from exc
    # The child may exit 1 when the fixture trips unrelated blocker-class
    # findings; we only care about parsing stdout here.
    try:
        data = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise SystemExit(
            f"design-mechanical-checks.py produced unparseable JSON on "
            f"{target_path}: {exc}\nstdout: {result.stdout[:500]}\n"
            f"stderr: {result.stderr[:500]}"
        ) from exc
    return [f for f in data.get("findings", []) if f.get("rule") == "dsc-ai-tell"]


def parse_location_line(loc: str) -> int:
    """Extract the line number from a `path:line` finding location string."""
    # Locations are `<path>:<line>` and paths may themselves contain `:` on
    # exotic platforms, but the script emits POSIX paths only — the last
    # colon-separated token is always the line number.
    try:
        return int(loc.rsplit(":", 1)[1])
    except (ValueError, IndexError):
        return -1


def assert_fixture_positive(findings: List[Dict]) -> List[str]:
    """Every pattern in PATTERN_SIGNATURES must appear at least once."""
    failures: List[str] = []
    descs = [f.get("description", "") for f in findings]
    for label, prefix in PATTERN_SIGNATURES:
        if not any(prefix in d for d in descs):
            failures.append(
                f"FIXTURE positive case missing: pattern '{label}' "
                f"(expected description prefix: '{prefix}') had zero "
                f"`dsc-ai-tell` findings in the fixture."
            )
    return failures


def assert_fixture_anchored(findings: List[Dict]) -> List[str]:
    """Each ANCHORED_REGRESSION_CASES entry must be present in findings."""
    failures: List[str] = []
    for line_no, prefix, rationale in ANCHORED_REGRESSION_CASES:
        present = any(
            parse_location_line(f.get("location", "")) == line_no
            and prefix in f.get("description", "")
            for f in findings
        )
        if not present:
            failures.append(
                f"FIXTURE anchored case missing at line {line_no} "
                f"(expected '{prefix}' finding): {rationale}."
            )
    return failures


def assert_fixture_negative(findings: List[Dict]) -> List[str]:
    """No dsc-ai-tell findings inside the negative-case ranges."""
    failures: List[str] = []
    for f in findings:
        line = parse_location_line(f.get("location", ""))
        desc = f.get("description", "")[:120]
        for start, end, name in NEGATIVE_RANGES:
            if start <= line <= end:
                failures.append(
                    f"FIXTURE negative case '{name}' fired at line "
                    f"{line} (range {start}-{end}): {desc}"
                )
                break
    return failures


def assert_overview_inflated_label_skipped(findings: List[Dict]) -> List[str]:
    """The Overview enabling-primitive sentence must emit no inflated-label finding.

    The fixture's `## Overview` section (line 16) carries
    "The enabling primitive is the regex set seeded below." — a
    subject-slot inflated-abstraction label whose shape would fire the
    rule anywhere else. Because `design-document-rules.md § Overview`
    prescribes naming "the enabling primitive(s)" as the Overview's
    element 3, the rule skips the `## Overview` section. This asserts the
    skip holds: zero inflated-abstraction-label findings at line 16.
    """
    failures: List[str] = []
    for f in findings:
        if parse_location_line(f.get("location", "")) != OVERVIEW_INFLATED_LABEL_LINE:
            continue
        if "Inflated-abstraction label" in f.get("description", ""):
            failures.append(
                f"OVERVIEW skip failed: inflated-abstraction-label fired at "
                f"line {OVERVIEW_INFLATED_LABEL_LINE} inside `## Overview`, where "
                f"naming the enabling primitive is the prescribed element: "
                f"{f.get('description', '')[:100]}"
            )
    return failures


def assert_calibration_adrs() -> List[str]:
    """Every calibration ADR must report zero dsc-ai-tell findings.

    The three surviving patterns were calibrated to fire zero times on
    these three ADRs, so the zero-false-positive contract holds verbatim
    with no snapshot allowlist needed. (Every earlier pattern removal —
    the Tier-1 vocabulary, em-dash density, signposting, and copula
    avoidance regexes, and later the disguise-only style regexes — could
    only ever have lowered this count, so a removal cannot introduce a
    new false positive here.)
    """
    failures: List[str] = []
    for adr in CALIBRATION_ADRS:
        if not adr.exists():
            failures.append(f"CALIBRATION ADR missing on disk: {adr}")
            continue
        findings = run_script(adr)
        if findings:
            details = "; ".join(
                f"{f.get('location', '?')} {f.get('description', '')[:80]}"
                for f in findings
            )
            failures.append(
                f"CALIBRATION ADR {adr.relative_to(REPO_ROOT)} should "
                f"have zero `dsc-ai-tell` findings (zero-false-positive "
                f"contract); got {len(findings)}: {details}"
            )
    return failures


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument(
        "--fixture-only",
        action="store_true",
        help=("Run only the fixture assertions and skip the calibration-ADR "
              "group. Use when this file runs from a staged `.claude/` "
              "mirror (a `§1.7` workflow-staging subtree), where REPO_ROOT "
              "resolves inside the mirror and `docs/adr/**` does not exist. "
              "The full suite (no flag) must still be run from the live "
              "tree before the staged checker is promoted."),
    )
    return p.parse_args()


def main() -> int:
    args = parse_args()
    failures: List[str] = []

    # Fixture: positive + negative cases.
    if not FIXTURE.exists():
        print(f"FATAL: fixture missing at {FIXTURE}", file=sys.stderr)
        return 1
    fixture_findings = run_script(FIXTURE)
    failures.extend(assert_fixture_positive(fixture_findings))
    failures.extend(assert_fixture_anchored(fixture_findings))
    failures.extend(assert_fixture_negative(fixture_findings))
    failures.extend(assert_overview_inflated_label_skipped(fixture_findings))

    # Calibration ADRs must hold at zero `dsc-ai-tell` findings each.
    # Skipped in fixture-only mode, where the corpus is unreachable from
    # REPO_ROOT by design (see the module docstring).
    if not args.fixture_only:
        failures.extend(assert_calibration_adrs())

    if failures:
        print("FAILED — dsc-ai-tell validation:", file=sys.stderr)
        for msg in failures:
            print(f"  - {msg}", file=sys.stderr)
        print(f"\n{len(failures)} assertion(s) failed.", file=sys.stderr)
        return 1

    calibration_note = (
        "calibration ADRs skipped (--fixture-only)"
        if args.fixture_only
        else f"zero on {len(CALIBRATION_ADRS)} calibration ADRs"
    )
    print(
        f"PASSED — dsc-ai-tell validation: "
        f"{len(fixture_findings)} fixture findings across "
        f"{len(PATTERN_SIGNATURES)} patterns, zero on negative cases, "
        f"{calibration_note}."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
