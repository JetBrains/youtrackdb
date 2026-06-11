#!/usr/bin/env python3
"""Validation runner for the D11 changes to
`.claude/scripts/design-mechanical-checks.py`:

1. The per-section footer rename `### References` →
   `### Decisions & invariants` (backward-compatible — both spellings are
   accepted as a valid footer).
2. The new `decision-cited-without-rationale` check: a `D<n>` cited bare
   under `D-records:` whose full record is introduced nowhere in design.md
   is a `should-fix`; a citation carrying inline rationale, or whose record
   exists elsewhere in the same design.md (regardless of footer spelling),
   passes.

Running this script IS the validation: it shells out to
`design-mechanical-checks.py` against the seeded fixtures plus this branch's
own frozen `design.md`, parses each JSON output, and asserts the
fire/no-fire behavior of the new check and the footer-presence check on both
footer spellings.

Invocation (from repo root):

    python3 .claude/scripts/tests/test_design_mechanical_checks_d11.py

Exit code 0: every assertion passed. Exit code 1: one or more failed; each
failure prints to stderr.

The four cases pin both sides of the two-sided backward-compatibility
contract:

- `d11-footer-newname-pass.md` — new `### Decisions & invariants` footer; one
  bare `D1` backed by a full `**D1.**` record, one rationale-bearing `D2`.
  Expect: footer recognized (no missing-footer blocker), zero
  `decision-cited-without-rationale` findings.
- `d11-decision-bare-fail.md` — new footer; bare `D9` with no record anywhere.
  Expect: exactly one `decision-cited-without-rationale` finding on `D9`
  (proves the check is live, not a no-op).
- `d11-footer-legacy-pass.md` — legacy `### References` footer; bare `D3`
  backed by a full `**D3.**` record. Expect: footer recognized, zero
  `decision-cited-without-rationale` findings (proves the legacy bare-code
  footer never trips the new check when the record exists).
- frozen `design.md` (legacy `### References` footers, all codes carrying
  inline parenthetical rationale). Expect: verdict PASS, zero
  `decision-cited-without-rationale` findings (the new check must not regress
  the frozen seed).
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Tuple

REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT = REPO_ROOT / ".claude" / "scripts" / "design-mechanical-checks.py"
FIXTURE_DIR = REPO_ROOT / ".claude" / "scripts" / "tests" / "fixtures"

NEWNAME_PASS = FIXTURE_DIR / "d11-footer-newname-pass.md"
BARE_FAIL = FIXTURE_DIR / "d11-decision-bare-fail.md"
LEGACY_PASS = FIXTURE_DIR / "d11-footer-legacy-pass.md"
FROZEN_DESIGN = (REPO_ROOT / "docs" / "adr" / "plan-slimization"
                 / "_workflow" / "design.md")

DCR_RULE = "decision-cited-without-rationale"
FOOTER_RULE = "per-section-shape:references-footer"


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


def assert_no_dcr(data: Dict, label: str) -> List[str]:
    """No decision-cited-without-rationale findings expected."""
    dcr = findings_for_rule(data, DCR_RULE)
    if dcr:
        locs = "; ".join(f"{f.get('location', '?')}" for f in dcr)
        return [
            f"{label}: expected ZERO `{DCR_RULE}` findings, got "
            f"{len(dcr)}: {locs}"
        ]
    return []


def assert_footer_recognized(data: Dict, label: str) -> List[str]:
    """The missing-footer blocker must NOT fire — the footer spelling used by
    the fixture must be recognized as a valid decisions-and-invariants
    footer."""
    footer_misses = findings_for_rule(data, FOOTER_RULE)
    if footer_misses:
        locs = "; ".join(f"{f.get('location', '?')}" for f in footer_misses)
        return [
            f"{label}: footer spelling was NOT recognized — got "
            f"`{FOOTER_RULE}` finding(s): {locs}"
        ]
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


def assert_dcr_fires_on(data: Dict, code: str, label: str) -> List[str]:
    """Exactly one decision-cited-without-rationale finding, naming `code`."""
    dcr = findings_for_rule(data, DCR_RULE)
    failures: List[str] = []
    if len(dcr) != 1:
        failures.append(
            f"{label}: expected exactly ONE `{DCR_RULE}` finding, got "
            f"{len(dcr)}."
        )
    naming = [f for f in dcr if code in f.get("description", "")]
    if not naming:
        descs = "; ".join(f.get("description", "")[:60] for f in dcr)
        failures.append(
            f"{label}: expected a `{DCR_RULE}` finding naming `{code}`; "
            f"descriptions seen: [{descs}]"
        )
    # The fired finding must be a should-fix per the contract (never a blocker).
    for f in dcr:
        if f.get("severity") != "should-fix":
            failures.append(
                f"{label}: `{DCR_RULE}` finding has severity "
                f"'{f.get('severity')}', expected 'should-fix'."
            )
    return failures


def main() -> int:
    failures: List[str] = []

    for path in (NEWNAME_PASS, BARE_FAIL, LEGACY_PASS, FROZEN_DESIGN):
        if not path.exists():
            print(f"FATAL: required input missing at {path}", file=sys.stderr)
            return 1

    # Case 1: new footer spelling, satisfied citations -> recognized + no DCR.
    d = run_script(NEWNAME_PASS)
    failures.extend(assert_footer_recognized(d, "newname-pass"))
    failures.extend(assert_no_dcr(d, "newname-pass"))
    failures.extend(assert_no_blockers(d, "newname-pass"))

    # Case 2: bare decision under new footer, no record -> DCR fires on D9.
    d = run_script(BARE_FAIL)
    failures.extend(assert_footer_recognized(d, "bare-fail"))
    failures.extend(assert_dcr_fires_on(d, "D9", "bare-fail"))

    # Case 3: legacy footer, bare code with record elsewhere -> recognized + no DCR.
    d = run_script(LEGACY_PASS)
    failures.extend(assert_footer_recognized(d, "legacy-pass"))
    failures.extend(assert_no_dcr(d, "legacy-pass"))
    failures.extend(assert_no_blockers(d, "legacy-pass"))

    # Case 4: frozen design.md (legacy footers, inline-rationale codes) ->
    # verdict PASS and no DCR. This is the no-regression contract on the seed.
    d = run_script(FROZEN_DESIGN)
    failures.extend(assert_no_dcr(d, "frozen-design"))
    if d.get("summary", {}).get("verdict") != "PASS":
        failures.append(
            f"frozen-design: expected verdict PASS, got "
            f"{d.get('summary', {}).get('verdict')}."
        )

    if failures:
        print("FAILED — D11 mechanical-check validation:", file=sys.stderr)
        for msg in failures:
            print(f"  - {msg}", file=sys.stderr)
        print(f"\n{len(failures)} assertion(s) failed.", file=sys.stderr)
        return 1

    print(
        "PASSED — D11 mechanical-check validation: footer rename accepts both "
        "spellings; decision-cited-without-rationale fires on a bare "
        "unintroduced code and stays silent on rationale-bearing citations, "
        "records-elsewhere citations (both footer spellings), and the frozen "
        "design.md seed."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
