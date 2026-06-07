#!/usr/bin/env python3
"""Mechanical test for the review-file count-validation contract (S4/S6).

The review-file schema in `.claude/workflow/conventions-execution.md` §2.5
("Review-file schema, count validation, and coverage") defines two
mechanical invariants over every bulk-producing sub-agent's output file:

  S4 (count validation): the manifest `findings` count must equal the
      ID-anchored grep count over the file. On a mismatch the reader
      raises CONTRACT_VIOLATION and falls back to a whole-section read.
  S6 (heading-only validation): the count is taken from heading lines
      only, never a finding body, so a reader validates without ingesting
      any body and the no-bodies invariant holds through validation.

The canonical count-validation regex (the single source of truth lives in
conventions-execution.md §2.5) is:

    grep -cE '^### [A-Z]+[0-9]+ '

`[A-Z]+` (one-or-more uppercase), NOT `[A-Z]{2,}`, so single-letter
strategic prefixes (`T`/`R`/`A`/`S`) match alongside two-letter dimensional
ones (`BC`/`CQ`/…). The trailing space after `[0-9]+` excludes the
four-hash `#### <cert>` evidence entries.

This script is the validation: it implements the §2.5 reader's
count-validation logic (`validate_review_file`) using the canonical regex,
then asserts the four fixtures behave per the per-step acceptance criteria
in the track file's `## Validation and Acceptance`:

  1. A valid two-letter-prefix dimensional file validates (count == manifest).
  2. A valid single-letter-prefix strategic file (`### T1 `) validates —
     the case that motivated `[A-Z]+` over `[A-Z]{2,}`.
  3. A count-mismatch file raises CONTRACT_VIOLATION.
  4. A file with a stray `### CASE1 ` body heading raises
     CONTRACT_VIOLATION (the file-wide reservation: a stray uppercase
     three-hash heading anywhere inflates the count).

It also asserts the regex-source contract: every fixture embeds and
cites the canonical regex literal, and the regex this script runs is
byte-identical to the literal the schema documents.

Invocation (from repo root):

    python3 .claude/scripts/tests/test_review_file_schema.py

Exit code 0: every assertion passed. Exit code 1: one or more failed;
each failure prints to stderr.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import List, Tuple

REPO_ROOT = Path(__file__).resolve().parents[3]
FIXTURES = REPO_ROOT / ".claude" / "scripts" / "tests" / "fixtures"
SCHEMA_DOC = (
    REPO_ROOT
    / ".claude"
    / "workflow"
    / "conventions-execution.md"
)
# When this branch stages its workflow edits, §2.5 lives in the staged
# mirror until the Phase 4 promotion. The reads-precedence rule
# (conventions.md §1.7(d)) makes the staged copy authoritative when
# present; this test follows the same precedence so it validates against
# whichever copy the schema currently lives in.
STAGED_SCHEMA_DOC = (
    REPO_ROOT
    / "docs"
    / "adr"
    / "reroute-tactical-reviews"
    / "_workflow"
    / "staged-workflow"
    / ".claude"
    / "workflow"
    / "conventions-execution.md"
)

# The canonical count-validation regex. This Python string is the
# pattern body of the documented grep `grep -cE '^### [A-Z]+[0-9]+ '`.
# It is the single source the validator runs and the fixtures cite; the
# regex-source assertion below confirms the schema doc carries the same
# literal so this constant never silently drifts from the contract.
CANONICAL_GREP_ERE = r"^### [A-Z]+[0-9]+ "
# The exact `grep -cE '<pattern>'` invocation literal, as it must appear
# verbatim in the schema doc and in every fixture's embedded citation.
CANONICAL_GREP_LITERAL = "grep -cE '^### [A-Z]+[0-9]+ '"

_ANCHOR_RE = re.compile(CANONICAL_GREP_ERE)
# Matches the manifest `findings: <N>` field on the first manifest line.
_FINDINGS_RE = re.compile(r"\bfindings:\s*(\d+)")


def count_anchors(text: str) -> int:
    """Apply the canonical anchor regex to heading lines only (S6).

    This mirrors `grep -cE '^### [A-Z]+[0-9]+ '`: each line is matched
    independently against the anchored pattern, so only heading lines of
    the `### <PREFIX><N> ` shape count. Finding bodies and `#### <cert> `
    evidence entries never contribute — validation reads heading lines
    only and never ingests a body.
    """
    return sum(1 for line in text.splitlines() if _ANCHOR_RE.match(line))


def manifest_findings_count(text: str) -> int:
    """Read the manifest `findings:` count from the MANIFEST comment block.

    The first `findings: <N>` occurrence is the manifest count. Returns -1
    when absent so the caller can flag a malformed manifest.
    """
    m = _FINDINGS_RE.search(text)
    return int(m.group(1)) if m else -1


def validate_review_file(text: str) -> Tuple[bool, int, int]:
    """The §2.5 reader's count-validation step (S4).

    Returns `(ok, manifest_count, grep_count)`. `ok` is True when the
    manifest `findings` count equals the canonical anchor grep count —
    the reader trusts the index. False means the reader raises
    CONTRACT_VIOLATION and falls back to a whole-section read.
    """
    manifest_count = manifest_findings_count(text)
    grep_count = count_anchors(text)
    return (manifest_count == grep_count, manifest_count, grep_count)


# (fixture filename, expect_valid, expected_grep_count, description)
FIXTURE_CASES: List[Tuple[str, bool, int, str]] = [
    (
        "review-file-valid-dimensional.md",
        True,
        2,
        "valid two-letter-prefix (BC) dimensional file: grep count 2 == "
        "manifest findings 2; the `#### detail` body heading and `#### C<N> ` "
        "evidence entries are excluded (S4/S6)",
    ),
    (
        "review-file-valid-strategic.md",
        True,
        3,
        "valid single-letter-prefix (T) strategic file: grep count 3 == "
        "manifest findings 3 — the case the rejected `[A-Z]{2,}` form "
        "would have failed",
    ),
    (
        "review-file-count-mismatch.md",
        False,
        2,
        "count-mismatch CONTRACT_VIOLATION: manifest claims 3, only 2 anchors "
        "in the body",
    ),
    (
        "review-file-stray-heading.md",
        False,
        3,
        "stray `### CASE1 ` body heading CONTRACT_VIOLATION: manifest claims 2, "
        "the stray uppercase three-hash heading inflates the grep count to 3",
    ),
]


def assert_fixture_cases() -> List[str]:
    """Each fixture must validate / violate exactly as the schema dictates."""
    failures: List[str] = []
    for name, expect_valid, expected_grep, desc in FIXTURE_CASES:
        path = FIXTURES / name
        if not path.exists():
            failures.append(f"FIXTURE missing on disk: {path}")
            continue
        text = path.read_text(encoding="utf-8")
        ok, manifest_count, grep_count = validate_review_file(text)
        if grep_count != expected_grep:
            failures.append(
                f"{name}: canonical grep counted {grep_count} anchors, "
                f"expected {expected_grep} ({desc})"
            )
        if ok != expect_valid:
            verdict = "validated" if ok else "raised CONTRACT_VIOLATION"
            wanted = "validate" if expect_valid else "raise CONTRACT_VIOLATION"
            failures.append(
                f"{name}: {verdict} (manifest findings={manifest_count}, "
                f"grep count={grep_count}); expected it to {wanted} ({desc})"
            )
    return failures


def assert_heading_only_invariant() -> List[str]:
    """S6: a finding body's content must not change the count.

    Appending arbitrary body prose to the valid dimensional fixture —
    including lines that contain `###`-like text mid-line — must leave
    the anchor count unchanged, because the canonical regex anchors on
    `^### ` (line start). This proves validation reads heading lines only.
    """
    failures: List[str] = []
    path = FIXTURES / "review-file-valid-dimensional.md"
    if not path.exists():
        return [f"FIXTURE missing on disk: {path}"]
    text = path.read_text(encoding="utf-8")
    base_count = count_anchors(text)
    # Body prose with mid-line `###` and a non-anchored heading-ish line.
    injected = text + (
        "\nA sentence mentioning ### CQ9 mid-line, not at line start.\n"
        "    ### CQ9 indented, so not a line-start anchor either.\n"
        "#### CQ9 four-hash, excluded by the trailing-space anchor.\n"
    )
    after_count = count_anchors(injected)
    if after_count != base_count:
        failures.append(
            "S6 heading-only violated: injecting body prose with mid-line / "
            f"indented / four-hash `###`-like text changed the anchor count "
            f"from {base_count} to {after_count} (the canonical regex must "
            "anchor on `^### ` line-start headings only)"
        )
    return failures


def assert_regex_source_contract() -> List[str]:
    """The regex this test runs matches the schema doc and the fixtures.

    The fixtures embed and cite the canonical regex; the schema doc is the
    single source of truth. This asserts the documented literal appears
    verbatim in the schema doc and in every fixture, so the contract, the
    fixtures, and this validator can never silently diverge.
    """
    failures: List[str] = []

    # Follow §1.7(d) reads-precedence: staged copy authoritative if present.
    schema_path = STAGED_SCHEMA_DOC if STAGED_SCHEMA_DOC.exists() else SCHEMA_DOC
    if not schema_path.exists():
        failures.append(f"SCHEMA doc missing on disk: {schema_path}")
    else:
        schema_text = schema_path.read_text(encoding="utf-8")
        if CANONICAL_GREP_LITERAL not in schema_text:
            failures.append(
                f"SCHEMA doc {schema_path.relative_to(REPO_ROOT)} does not "
                f"carry the canonical grep literal {CANONICAL_GREP_LITERAL!r} "
                "— the validator's regex has drifted from the contract"
            )

    for name, _, _, _ in FIXTURE_CASES:
        path = FIXTURES / name
        if not path.exists():
            continue  # already reported by assert_fixture_cases
        text = path.read_text(encoding="utf-8")
        if CANONICAL_GREP_LITERAL not in text:
            failures.append(
                f"FIXTURE {name} does not embed/cite the canonical grep "
                f"literal {CANONICAL_GREP_LITERAL!r}"
            )

    # Guard against the rejected `[A-Z]{2,}` form reappearing in the schema.
    if schema_path.exists():
        schema_text = schema_path.read_text(encoding="utf-8")
        bad = "grep -cE '^### [A-Z]{2,}[0-9]+ '"
        # The schema may mention `[A-Z]{2,}` to explain why it was rejected;
        # what must not appear is the rejected form as a live grep command.
        if bad in schema_text:
            failures.append(
                f"SCHEMA doc carries the rejected grep form {bad!r} as a live "
                "command; single-letter strategic prefixes would fail it"
            )
    return failures


def main() -> int:
    failures: List[str] = []
    failures.extend(assert_fixture_cases())
    failures.extend(assert_heading_only_invariant())
    failures.extend(assert_regex_source_contract())

    if failures:
        print("FAILED — review-file schema validation (S4/S6):", file=sys.stderr)
        for msg in failures:
            print(f"  - {msg}", file=sys.stderr)
        print(f"\n{len(failures)} assertion(s) failed.", file=sys.stderr)
        return 1

    print(
        f"PASSED — review-file schema validation (S4/S6): "
        f"{len(FIXTURE_CASES)} fixtures (2 valid incl. single-letter prefix, "
        f"2 CONTRACT_VIOLATION), heading-only invariant, regex-source contract."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
