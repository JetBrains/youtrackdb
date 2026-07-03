#!/usr/bin/env python3
"""Validation runner for the `### Summary` rename support in
`.claude/scripts/design-mechanical-checks.py`.

The per-section summary block was renamed from the TL;DR forms to a
`### Summary` sub-heading. Backward compatibility follows the earlier
footer-rename precedent: the legacy spellings stay accepted, so
committed pre-rename designs keep passing without a backfill. Three
constants carry the rename inside the checker: the `section_has_tldr`
regexes (which gain `### Summary`), `SHAPE_EXEMPT_SECTION_NAMES`
(which gains display-case `"Summary"` for the Part-level section
title, compared against raw titles), and
`MANDATORY_OR_FORM_SUBHEADINGS` (which gains lowercase `"summary"`,
compared against lowercased sub-headings).

Running this script IS the validation: it shells out to
`design-mechanical-checks.py` against the seeded fixtures, parses each
JSON output, and asserts the fire/no-fire behavior of the shape checks
on all accepted spellings.

Invocation (from repo root):

    python3 .claude/scripts/tests/test_dsc_summary_shape.py

Exit code 0: every assertion passed. Exit code 1: one or more failed;
each failure prints to stderr.

The four cases:

- `summary-shape-spellings-pass.md` — three sections, one per accepted
  spelling (`**TL;DR.**`, `### TL;DR`, `### Summary`). Expect: zero
  summary-shape findings, zero blockers (pins the both-spellings
  acceptance in `section_has_tldr`).
- `summary-shape-missing-fail.md` — a well-formed `### Summary`
  section beside a section with no summary block. Expect: exactly one
  summary-shape blocker, on the bare section (proves the check is
  live after the rename, and that `### Summary` satisfies it — a
  regression that drops the new spelling would fire on the good
  section too and fail the exactly-one assertion).
- `summary-shape-siblings-pass.md` — four sections whose only
  sub-headings are `### Summary` and the footer. Expect: zero
  same-shape-siblings findings, zero blockers (pins the lowercase
  `"summary"` entry in `MANDATORY_OR_FORM_SUBHEADINGS`; without it the
  shared heading counts toward every pairwise similarity and the
  consolidation finding fires on every well-formed design).
- `summary-shape-part-exempt-pass.md` — a Part-level `## Summary`
  section with no summary block and no footer of its own. Expect:
  zero blockers (pins the display-case `"Summary"` entry in
  `SHAPE_EXEMPT_SECTION_NAMES`, whose compare is against raw section
  titles — a lowercase entry there would leave the Part-level section
  unexempted and fire both per-section shape blockers).

Every input lives under `tests/fixtures/`; the runner deliberately
references no repository document corpus, so it runs identically from
the live tree and from a staged `.claude/` mirror.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Dict, List

REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT = REPO_ROOT / ".claude" / "scripts" / "design-mechanical-checks.py"
FIXTURE_DIR = REPO_ROOT / ".claude" / "scripts" / "tests" / "fixtures"

SPELLINGS_PASS = FIXTURE_DIR / "summary-shape-spellings-pass.md"
MISSING_FAIL = FIXTURE_DIR / "summary-shape-missing-fail.md"
SIBLINGS_PASS = FIXTURE_DIR / "summary-shape-siblings-pass.md"
PART_EXEMPT_PASS = FIXTURE_DIR / "summary-shape-part-exempt-pass.md"

SUMMARY_RULE = "per-section-shape:tldr"
SIBLINGS_RULE = "same-shape-siblings"


def run_script(target_path: Path) -> Dict:
    """Invoke design-mechanical-checks.py whole-doc on `target_path`; return
    the parsed JSON output dict."""
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
            f"{target_path} (possible infinite loop)."
        ) from exc
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise SystemExit(
            f"design-mechanical-checks.py produced unparseable JSON on "
            f"{target_path}: {exc}\nstdout: {result.stdout[:500]}\n"
            f"stderr: {result.stderr[:500]}"
        ) from exc


def findings_for_rule(data: Dict, rule: str) -> List[Dict]:
    return [f for f in data.get("findings", []) if f.get("rule") == rule]


def assert_no_rule(data: Dict, rule: str, label: str) -> List[str]:
    """Zero findings of `rule` expected."""
    hits = findings_for_rule(data, rule)
    if hits:
        locs = "; ".join(
            f"{f.get('location', '?')} {f.get('description', '')[:60]}"
            for f in hits
        )
        return [f"{label}: expected ZERO `{rule}` findings, got "
                f"{len(hits)}: {locs}"]
    return []


def assert_no_blockers(data: Dict, label: str) -> List[str]:
    blockers = [f for f in data.get("findings", [])
                if f.get("severity") == "blocker"]
    if blockers:
        details = "; ".join(
            f"{f.get('rule', '?')} {f.get('location', '?')}"
            for f in blockers
        )
        return [f"{label}: expected ZERO blockers, got "
                f"{len(blockers)}: {details}"]
    return []


def assert_summary_fires_once_on(data: Dict, section_title: str,
                                 label: str) -> List[str]:
    """Exactly one summary-shape blocker, naming `section_title`."""
    hits = findings_for_rule(data, SUMMARY_RULE)
    failures: List[str] = []
    if len(hits) != 1:
        descs = "; ".join(
            f"{f.get('location', '?')} {f.get('description', '')[:60]}"
            for f in hits
        )
        failures.append(
            f"{label}: expected exactly ONE `{SUMMARY_RULE}` finding, got "
            f"{len(hits)}: [{descs}]"
        )
    naming = [f for f in hits if section_title in f.get("description", "")]
    if not naming:
        descs = "; ".join(f.get("description", "")[:60] for f in hits)
        failures.append(
            f"{label}: expected the `{SUMMARY_RULE}` finding to name "
            f"`{section_title}`; descriptions seen: [{descs}]"
        )
    for f in hits:
        if f.get("severity") != "blocker":
            failures.append(
                f"{label}: `{SUMMARY_RULE}` finding has severity "
                f"'{f.get('severity')}', expected 'blocker'."
            )
    return failures


def main() -> int:
    failures: List[str] = []

    for path in (SPELLINGS_PASS, MISSING_FAIL, SIBLINGS_PASS,
                 PART_EXEMPT_PASS):
        if not path.exists():
            print(f"FATAL: required fixture missing at {path}",
                  file=sys.stderr)
            return 1

    # Case 1: all three spellings satisfy the summary-shape check.
    d = run_script(SPELLINGS_PASS)
    failures.extend(assert_no_rule(d, SUMMARY_RULE, "spellings-pass"))
    failures.extend(assert_no_blockers(d, "spellings-pass"))

    # Case 2: the check is live — a section with no summary block fires
    # exactly once, and the `### Summary` control section stays clean.
    d = run_script(MISSING_FAIL)
    failures.extend(
        assert_summary_fires_once_on(d, "Bare section", "missing-fail"))

    # Case 3: the shared `### Summary` heading is excluded from the
    # same-shape sibling similarity computation.
    d = run_script(SIBLINGS_PASS)
    failures.extend(assert_no_rule(d, SIBLINGS_RULE, "siblings-pass"))
    failures.extend(assert_no_blockers(d, "siblings-pass"))

    # Case 4: a Part-level `## Summary` section is shape-exempt by its
    # display-case title.
    d = run_script(PART_EXEMPT_PASS)
    failures.extend(assert_no_blockers(d, "part-exempt-pass"))

    if failures:
        print("FAILED — Summary-rename shape validation:", file=sys.stderr)
        for msg in failures:
            print(f"  - {msg}", file=sys.stderr)
        print(f"\n{len(failures)} assertion(s) failed.", file=sys.stderr)
        return 1

    print(
        "PASSED — Summary-rename shape validation: all three summary "
        "spellings accepted; the check still fires on a missing summary "
        "block; Summary-only siblings raise no consolidation finding; a "
        "Part-level Summary section is shape-exempt."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
