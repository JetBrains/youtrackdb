<!--MANIFEST
dimension: workflow-hook-safety
target: "Track 1 (e68f84f760..HEAD) — shrink house-style: drop 4 dsc-ai-tell regex patterns from design-mechanical-checks.py + paired test/fixture (Step 1); drop 2 Tier-B slugs + 'six'->'four' count word from house-style-write-reminder.sh + paired test (Step 2)"
iteration: 1
verdict: PASS
findings_total: 0
blockers: 0
evidence_base:
  certs: 0
flags: []
cert_index: []
index: []
-->

## Findings

No findings. The track's subtractive edits to the deterministic checker (`design-mechanical-checks.py`), its paired test (`test_dsc_ai_tell.py`) and fixture (`dsc-ai-tell-fixture.md`), and the write-reminder hook (`house-style-write-reminder.sh`) with its paired test (`test_house_style_hook.py`) left every operational-safety property intact. The shrink is clean: no orphaned regex/helper, no dangling fixture anchor, no off-by-one in the renumbered ranges, docstring/count constants match the surviving pattern set, and the hook slug-list edit stays consistent with its test. No `/tmp` paths, secrets, network calls, idempotency, or exit-code surfaces were touched.

Verified:

- **Both suites pass green after the shrink.** `python3 .claude/scripts/tests/test_dsc_ai_tell.py` -> "8 fixture findings across 7 patterns, zero on negative cases, zero on 3 calibration ADRs" (exit 0). `python3 .claude/scripts/tests/test_house_style_hook.py` -> "18 test cases, all assertions within 3.0s budget" (exit 0). No environment pytest needed; both are standalone runners.
- **Behavioral claim holds.** A document carrying all four removed triggers (a "Let's dive in" signposting opener, a "serves as" copula, Tier-1 words `delve`/`holistic`/`foster`, and three em dashes in one paragraph) draws zero `check_dsc_ai_tell` findings (driven directly via `check_dsc_ai_tell(path, lines)`). The seven survivor patterns still fire on the fixture: negative parallelism (leading and trailing frames), Title-Case heading, persuasive authority trope, hyphenated-pair cluster, fragmented header, and inflated-abstraction label — seven distinct firing prefixes confirmed.
- **No orphaned identifiers in the checker.** `grep -nE "TIER1_BANNED_VOCAB|SIGNPOSTING_OPENERS_RE|COPULA_AVOIDANCE_RE|em_dash_count|em_dash_fires"` over the live `design-mechanical-checks.py` returns nothing — the four removed pattern constants, their compiled regexes, the per-line scan loops, and the per-paragraph em-dash block were all removed together. The only residual lexical hits for the removed-pattern names in `test_dsc_ai_tell.py` are the three explanatory comment lines (`:42-43`, `:284`) that document *why* the count dropped, which is correct.
- **Docstring/count constant matches the actual pattern set.** The `check_dsc_ai_tell` docstring now enumerates seven patterns (renumbered 1-7) and the prose lead reads "Seven patterns fire" — matching the seven that the direct call fires. The `iter_paragraphs` docstring was updated in lockstep: the em-dash-density justification and the "banned vocabulary" citation-exclusion rationale were rewritten to reference only the surviving hyphenated-pair cluster rule, so no stale comment points at a removed scan.
- **Renumbered fixture anchors resolve to correct content (no silent off-by-one).** Spot-checked every renumbered anchor against the live 139-line fixture: `ANCHORED_REGRESSION_CASES` L130 -> "### Fragmented one-liner regression" (expects Fragmented header), L69 -> "...thorough rethinking, not just a patch" (expects trailing 'X, not Y'), L79 -> "The underlying mechanism is a write-ahead log..." (expects inflated-abstraction-label positive); `OVERVIEW_INFLATED_LABEL_LINE` 24 -> the Overview enabling-primitive sentence; `NEGATIVE_RANGES` headers L90/L99/L115 -> the three negative-case block headers; the regressions section start at L128 sits past the last negative range, correctly kept out of negative territory. The dropped Tier-1 heading-scan regression case and its fixture block were removed together, so no anchor points at a deleted block.
- **Hook slug-list edit stays consistent with its test (re-confirmed at track level).** `house-style-write-reminder.sh:262` (`tier_b_body`) names the four survivors (`§ Orientation`, `§ Plain language`, `§ Banned sentence patterns`, `§ Banned analysis patterns`); the count word changed "six"->"four" at `:256` and `:262` (and the Tier-A body at `:412` dropped the "banned vocabulary" item); `test_house_style_hook.py:693` `TIER_B_HEADINGS` lists the same four `## ` headings. The step-2 hook-safety review already cleared the hook's operational-safety surface (`set -uo pipefail`, `${session_id}`/`$$`-suffixed `/tmp` state + lock files, documented no-trap rationale, `flock`-guarded idempotent critical section, no secrets/network, executable bit, latency budget); this diff does not touch any of it, and `bash -n` on the hook plus the green test re-confirm.

## Evidence base

<!-- Evidence-trail-exempt dimension: no refutation or certificate phase to persist. -->
