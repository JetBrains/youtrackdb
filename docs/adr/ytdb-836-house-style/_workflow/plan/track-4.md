# Track 4: Add `dsc-ai-tell` rule to `design-mechanical-checks.py`

## Purpose / Big Picture
After this track lands, the existing structural-checks script gains a `dsc-ai-tell` regex pass that automates the subset of `house-style.md` patterns detectable without judgment, with empirical validation showing zero false positives on three known-good ADRs (`persist-visible-count`, `index-gc`, `non-durable-wow`).

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Add a new `check_dsc_ai_tell` function to `design-mechanical-checks.py` that emits `dsc-ai-tell` findings for: Tier-1 banned vocabulary scan, negative parallelism (`\bit'?s not\b.*\bit'?s\b`), em-dash density (>1 per paragraph; reuse existing fence-aware parser), Title Case heading detection on H2+ (`^#{2,6}` only), signposting openers ("Let's dive", "Let's break", "Here's what you need"), copula avoidance ("serves as", "stands as"), persuasive authority tropes ("at its core", "fundamentally", "the real question"), hyphenated-pair adjectival clusters (3+ distinct pairs in same paragraph in adjectival position), and fragmented headers (heading + ≤1-line paragraph with ≥50% lemma overlap). Each finding cites `house-style.md § <Section>` in its description. `auto_applicable: false` — rewrites need judgment. Create test fixture at `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md` exercising each pattern, plus a runner script that asserts (i) fixture hits ≥1 per pattern and (ii) the three known-good ADRs have zero `dsc-ai-tell` findings.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective

## Context and Orientation

Starting state of `.claude/scripts/design-mechanical-checks.py` (1375 lines, surveyed during research):

- **Lines 1-32 — Module header, imports, usage.** Add a brief mention of `dsc-ai-tell` to the docstring after the existing rules are listed.
- **Lines 34-75 — Constants.** Add new constants: `TIER1_BANNED_VOCAB` (list of 26 words from `house-style.md § Tier 1 — hard ban`), `NEGATIVE_PARALLELISM_RE`, `SIGNPOSTING_OPENERS_RE`, `COPULA_AVOIDANCE_RE`, `AUTHORITY_TROPE_RE`, `HYPHENATED_PAIR_CLUSTER_THRESHOLD = 3`, `FRAGMENTED_HEADER_LEMMA_OVERLAP_THRESHOLD = 0.5`.
- **Lines 77-265 — Existing parsing helpers** (`read_lines`, `parse_code_fence`, `is_code_fence_line`, `fence_closes`, `parse_sections`, `collect_all_headings`, `normalize_heading`). Reuse: `parse_code_fence` / `fence_closes` for paragraph detection inside the new check.
- **Lines 297-315 — `make_finding` helper.** Reuse verbatim for all `dsc-ai-tell` findings; the schema fits.
- **Lines 318-1170 — Existing check functions** (`check_overview_first`, `check_per_section_shape`, `check_top_level_cap`, `check_per_section_length`, `check_dsc_parenthetical_asides`, `check_length_trigger_compliance`, `check_same_shape_siblings`, `check_mechanics_link_resolution`, `check_reverse_direction_refs`, `check_full_design_link_resolution`). The new `check_dsc_ai_tell` function sits alongside these.
- **Lines 1177-1218 — `parse_args`.** No new CLI flags needed — `dsc-ai-tell` runs whenever `target` includes `design` (same gating as `check_dsc_parenthetical_asides`).
- **Lines 1221-1372 — `main` driver.** Add one call into the `if run_design_shape_checks:` block (around line 1316) wiring up the new check.

New file to create:
- `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md` — a seeded markdown file containing one paragraph per banned pattern. Roughly 60-80 lines total, exercising every regex with a clear failure trigger.
- Runner script (location TBD during Phase A — candidates include `.claude/scripts/tests/run-dsc-ai-tell-validation.sh` or inline-in-CI) that:
  - Invokes `python3 .claude/scripts/design-mechanical-checks.py --design-path <fixture> --target design --scope whole-doc`, parses JSON, asserts ≥1 finding with `rule == "dsc-ai-tell"` per expected pattern type.
  - Invokes the script against `docs/adr/persist-visible-count/adr.md`, `docs/adr/index-gc/adr.md`, `docs/adr/non-durable-wow/adr.md` and asserts zero `dsc-ai-tell` findings on each.

The four calibration refinements from `design.md § dsc-ai-tell calibration`:
1. **Title Case rule** — `^#{2,6}` only (exempts H1).
2. **Hyphenated-pair rule** — 3+ distinct adjectival pairs in same paragraph (blank-line-bounded, outside fenced code).
3. **Em-dash density** — paragraph detection via existing fence-aware parser.
4. **Fragmented-header rule** — heading lemma overlap with following ≤1-line paragraph at ≥50%.

## Plan of Work

The approach implements the check function with explicit per-refinement compliance, then exercises it against the fixture and the three known-good ADRs. Order:

1. **Implement `check_dsc_ai_tell` function.** Add the constants and the check function below `check_full_design_link_resolution` (around line 1170). The function takes `design_path: str`, `lines: List[str]`, `sections: List[Dict]` as inputs (same signature as `check_dsc_parenthetical_asides`). It walks the file once, tracks code-fence state via the existing `parse_code_fence` / `fence_closes` helpers, and emits findings per pattern. Each finding's `description` cites `house-style.md § <Section>` and the `rule` is `dsc-ai-tell`. `auto_applicable=False`. Wire the call into `main()` inside the `if run_design_shape_checks:` block. Per-pattern implementation notes:
   - **Tier-1 vocab:** word-boundary regex per word, case-insensitive, walked once.
   - **Negative parallelism:** the issue's regex `\bit'?s not\b.*\bit'?s\b`, but constrained to a single paragraph (not greedy across blank lines).
   - **Em-dash density:** segment file into paragraphs (blank-line-bounded, outside fenced code); count `—` per paragraph; fire if count >1.
   - **Title Case heading:** `^#{2,6} ([A-Z][a-z]+ ){2,}[A-Z][a-z]+$` (3+ Title-Case words after H2-H6 marker, exempting acronyms in caps).
   - **Signposting:** word-boundary regex on `Let's dive`, `Let's break`, `Here's what you need`.
   - **Copula avoidance:** word-boundary regex on `serves as`, `stands as`.
   - **Authority tropes:** word-boundary regex on `at its core`, `fundamentally`, `the real question`.
   - **Hyphenated-pair cluster:** segment paragraphs as above; per paragraph extract all `\b[a-zA-Z]+-[a-zA-Z]+\b` matches; deduplicate; check each pair's position context (immediately precedes a noun, or sits in a comma-separated list of modifiers); fire if 3+ unique adjectival pairs.
   - **Fragmented header:** for each heading, take the next 1-line paragraph (no following blank line within 2 lines); split heading and paragraph into content words (lower-case, strip stop words); compute intersection ratio; fire if ≥50%.
2. **Create the fixture file.** Write `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md` with one paragraph per banned pattern. Each paragraph has a `### <Pattern name>` header and 2-3 sentences exercising the regex. Include an Overview section + References footer at the file level so the fixture passes the per-section-shape rules of `design-mechanical-checks.py` (otherwise blockers from other checks drown the `dsc-ai-tell` signal).
3. **Run validation and tune if needed.** Invoke the script against the fixture (expect findings per pattern) and against the three known-good ADRs (expect zero `dsc-ai-tell` findings). Capture the actual finding counts in the track's `## Episodes` section. If a regex over-fires on the ADRs, tune (the fallback for the hyphenated-pair adjectival heuristic and the fragmented-header lemma-overlap heuristic is documented in `design.md § dsc-ai-tell calibration` — demote severity from `should-fix` to `suggestion`).

Invariants to preserve:
- The existing check pipeline (overview-first, per-section-shape, top-level-cap, per-section-length, parenthetical-asides, length-trigger, same-shape-siblings, link-resolution checks) must continue to pass on every existing ADR with no regressions. The new check is *additive*.
- The script's CLI shape stays the same — no new required flags. `--target` and `--scope` already gate the new check naturally.
- The JSON output schema stays the same. `dsc-ai-tell` findings use the existing `make_finding` shape.

## Concrete Steps

## Episodes

## Validation and Acceptance

Track-level acceptance: `dsc-ai-tell` fires ≥1 finding per banned pattern on the fixture file AND fires zero findings on each of the three known-good ADRs (`persist-visible-count`, `index-gc`, `non-durable-wow`) AND the existing check pipeline produces the same output on every existing ADR as before this track (no regressions).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. -->

## Idempotence and Recovery

## Artifacts and Notes

## Interfaces and Dependencies

**In-scope files:**
- `.claude/scripts/design-mechanical-checks.py` (additive: new constants, new check function, one call in `main`).
- `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md` (new file).
- Runner script (path determined during Phase A): either `.claude/scripts/tests/run-dsc-ai-tell-validation.sh` or equivalent inline-in-CI plumbing.

**Out-of-scope files:**
- `house-style.md` — Track 1 owns its content; this track only cites by section name.
- `.claude/skills/edit-design/SKILL.md` — invokes `design-mechanical-checks.py` already; finding pipeline absorbs the new rule without changes to the skill.
- Other ADRs under `docs/adr/**` — only the three named calibration ADRs are touched, and read-only.

**Inter-track dependencies:**
- **Depends on:** Track 1. Each finding's `description` cites `house-style.md § <Section>`. Track 1 must have fixed those section names.
- Independent of Tracks 2 and 3 — Track 4 can run in parallel with them once Track 1 is done.

**Library / function signatures (within the script):**
- `check_dsc_ai_tell(design_path: str, lines: List[str], sections: List[Dict]) -> List[Dict]` — signature matches the existing check functions.
- Reuses `parse_code_fence`, `fence_closes`, `collect_all_headings`, `make_finding` from elsewhere in the file.

**External contracts:**
- The JSON output schema published to callers of `design-mechanical-checks.py` (the `edit-design` skill, CI hooks, manual invocations) does not change. New findings appear with `rule == "dsc-ai-tell"` and `auto_applicable: false`.
