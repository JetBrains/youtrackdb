# Track 1: Coverage Measurement Infrastructure

## Description

Create a Python script (`coverage-analyzer.py`) that parses JaCoCo XML
reports and produces per-package overall coverage summaries. Unlike the
existing `coverage-gate.py` (which checks only changed lines in PRs),
this script computes totals across all lines in each package and
generates a sorted table of packages by uncovered line count.

> **What**: `.github/scripts/coverage-analyzer.py` (new); baseline
> `coverage-baseline.md` document.
>
> **How**: Read JaCoCo XML, aggregate `<counter>` elements at the
> `<package>` level, output sorted markdown table.
>
> **Constraints**: Read-only tool — no CI integration beyond optional
> manual run; respects the same JaCoCo exclusions as the coverage
> profile.
>
> **Interactions**: Required infrastructure for all later tracks; no
> downstream code changes.

## Progress
- [x] Review + decomposition
- [x] Step implementation (2/2 complete)
- [x] Track-level code review (1/1 iterations — PASS, 0 blockers, 0 should-fix, 2 suggestions)

## Base commit
`7e19d84b51`

## Reviews completed
- [x] Technical

## Steps

- [x] Step 1: Create coverage-analyzer.py script
  - [x] Context: safe
  > **What was done:** Created `.github/scripts/coverage-analyzer.py` (185
  > lines) that parses JaCoCo XML reports and produces per-package overall
  > coverage summaries. Uses `argparse` with `--coverage-dir` argument,
  > `xml.etree.ElementTree` for XML parsing, and outputs a markdown table
  > sorted by uncovered lines descending plus aggregate totals. Handles
  > missing LINE/BRANCH counters (defaults to 0), skips zero-line packages,
  > and includes multi-report merging logic. Verified against existing
  > JaCoCo report: 63.6% line / 53.3% branch / 177 packages — matches
  > plan baseline.
  >
  > **Key files:** `.github/scripts/coverage-analyzer.py` (new)

- [x] Step 2: Record baseline coverage
  - [x] Context: safe
  > **What was done:** Generated baseline coverage document using the
  > analyzer against the existing JaCoCo report (generated same day, no
  > Java changes since develop). Created
  > `docs/adr/unit-test-coverage/_workflow/coverage-baseline.md` with commit SHA,
  > date, aggregate totals (63.6% line / 53.3% branch), target gap
  > analysis (+21.4pp line / +16.7pp branch needed), and full 177-package
  > table sorted by uncovered lines. Review skipped — documentation-only
  > change (no code, no logic).
  >
  > **Key files:** `docs/adr/unit-test-coverage/_workflow/coverage-baseline.md` (new)
