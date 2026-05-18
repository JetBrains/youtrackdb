#!/usr/bin/env python3
"""Validation runner for the `dsc-ai-tell` rule in
`.claude/scripts/design-mechanical-checks.py`.

Running this script is the validation: it shells out to
`design-mechanical-checks.py` against the seeded fixture and the three
calibration ADRs, parses each JSON output, and asserts:

1. The fixture exercises every banned pattern — each pattern in
   `PATTERN_SIGNATURES` below has at least one `dsc-ai-tell` finding.
2. The fixture's negative-case paragraphs (hyphenated technical
   compounds, single em-dash, the H1 Title Case heading) emit zero
   `dsc-ai-tell` findings.
3. The three calibration ADRs (`persist-visible-count`, `index-gc`,
   `non-durable-wow`) each emit zero `dsc-ai-tell` findings — the
   zero-false-positive contract the rule was calibrated against.

Invocation (from repo root):

    python3 .claude/scripts/tests/test_dsc_ai_tell.py

Exit code 0: every assertion passed. Exit code 1: one or more failed;
each failure prints to stderr.

Manifest — `dsc-ai-tell` baseline counts on existing ADRs at the time
this runner was authored (informational; the runner does not fail on
these). Survey across `docs/adr/*/adr.md` excluding the three
calibration ADRs:

    docs/adr/thin-workflow/adr.md           6
    docs/adr/unit-test-coverage/adr.md      1
    docs/adr/ytdb-817-new-track-format/adr.md  5

Total 12 findings across 7 non-calibration ADRs. These are real signals
the rule should keep firing on; if a future change drives any of them to
zero unintentionally, the rule has been weakened. The three calibration
ADRs (`persist-visible-count`, `index-gc`, `non-durable-wow`) hold at
zero — the rule's zero-false-positive contract on those three files is
the contract this runner enforces.
"""

from __future__ import annotations

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
# Grouping by description rather than raw count handles the Tier-1 case
# where one paragraph emits one finding per matched base word (3 in the
# fixture), and the Title-Case case where the rule fires on a real H3
# heading whose line range differs from the H3 body lines.
PATTERN_SIGNATURES: List[Tuple[str, str]] = [
    ("tier1-vocab",
     "Tier-1 banned vocabulary per house-style.md § Tier 1"),
    ("negative-parallelism",
     "Negative parallelism per house-style.md § Banned sentence"),
    ("em-dash-density",
     "Em-dash density per house-style.md § Em-dash discipline"),
    ("title-case-heading",
     "Title-Case heading per house-style.md § Title Case headings"),
    ("signposting",
     "Signposting opener per house-style.md § Signposting"),
    ("copula",
     "Copula avoidance per house-style.md § Copula avoidance"),
    ("authority-trope",
     "Persuasive authority trope per house-style.md § Persuasive"),
    ("hyphenated-pair-cluster",
     "Hyphenated-pair cluster per house-style.md § Hyphenated"),
    ("fragmented-header",
     "Fragmented header per house-style.md § Fragmented headers"),
]

# Negative-case line ranges in the fixture. The H1 at line 1 is the
# Title-Case negative case (the rule must skip `# ` lines); the two
# `### *negative` H3 blocks start at lines 94 and 103. The runner
# enforces zero `dsc-ai-tell` findings whose location line falls inside
# either of these ranges or equals 1.
H1_TITLE_CASE_LINE = 1
NEGATIVE_RANGES: List[Tuple[int, int, str]] = [
    (94, 102, "Hyphenated technical compounds negative"),
    (103, 999, "Single em-dash negative"),
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


def assert_fixture_negative(findings: List[Dict]) -> List[str]:
    """No dsc-ai-tell findings inside the negative-case ranges or on H1."""
    failures: List[str] = []
    for f in findings:
        line = parse_location_line(f.get("location", ""))
        desc = f.get("description", "")[:120]
        if line == H1_TITLE_CASE_LINE:
            failures.append(
                f"FIXTURE negative case fired on H1 line "
                f"{H1_TITLE_CASE_LINE} (must skip `# `): {desc}"
            )
            continue
        for start, end, name in NEGATIVE_RANGES:
            if start <= line <= end:
                failures.append(
                    f"FIXTURE negative case '{name}' fired at line "
                    f"{line} (range {start}-{end}): {desc}"
                )
                break
    return failures


def assert_calibration_adrs() -> List[str]:
    """Every calibration ADR must report zero dsc-ai-tell findings.

    The em-dash density rule is tightened to fire only on 3+ em dashes
    per paragraph or on 2 em dashes whose middle segment carries a
    sentence terminator (two unpaired em dashes rather than one balanced
    parenthetical aside). Under this rule the residual finding on
    `index-gc/adr.md` (two em dashes forming a balanced aside
    `BTree.put() — when entries are already being redistributed —`)
    drops out and each of the three calibration ADRs holds at zero —
    the zero-false-positive contract holds verbatim, no snapshot
    allowlist needed.
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


def main() -> int:
    failures: List[str] = []

    # Fixture: positive + negative cases.
    if not FIXTURE.exists():
        print(f"FATAL: fixture missing at {FIXTURE}", file=sys.stderr)
        return 1
    fixture_findings = run_script(FIXTURE)
    failures.extend(assert_fixture_positive(fixture_findings))
    failures.extend(assert_fixture_negative(fixture_findings))

    # Calibration ADRs must hold at zero `dsc-ai-tell` findings each.
    failures.extend(assert_calibration_adrs())

    if failures:
        print("FAILED — dsc-ai-tell validation:", file=sys.stderr)
        for msg in failures:
            print(f"  - {msg}", file=sys.stderr)
        print(f"\n{len(failures)} assertion(s) failed.", file=sys.stderr)
        return 1

    print(
        f"PASSED — dsc-ai-tell validation: "
        f"{len(fixture_findings)} fixture findings across "
        f"{len(PATTERN_SIGNATURES)} patterns, zero on negative cases, "
        f"zero on {len(CALIBRATION_ADRS)} calibration ADRs."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
