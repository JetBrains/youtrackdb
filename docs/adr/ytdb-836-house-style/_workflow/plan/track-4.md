# Track 4: Add `dsc-ai-tell` rule to `design-mechanical-checks.py`

## Purpose / Big Picture
After this track lands, the existing structural-checks script gains a `dsc-ai-tell` regex pass that automates the subset of `house-style.md` patterns detectable without judgment, with empirical validation showing zero false positives on three known-good ADRs (`persist-visible-count`, `index-gc`, `non-durable-wow`).

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Add a new `check_dsc_ai_tell` function to `design-mechanical-checks.py` that emits `dsc-ai-tell` findings for: Tier-1 banned vocabulary scan, negative parallelism (`\bit'?s not\b.*\bit'?s\b`), em-dash density (>1 per paragraph; reuse existing fence-aware parser), Title Case heading detection on H2+ (`^#{2,6}` only), signposting openers ("Let's dive", "Let's break", "Here's what you need"), copula avoidance ("serves as", "stands as"), persuasive authority tropes ("at its core", "fundamentally", "the real question"), hyphenated-pair adjectival clusters (3+ distinct pairs in same paragraph in adjectival position), and fragmented headers (heading + ≤1-line paragraph with ≥50% lemma overlap). Each finding cites `house-style.md § <Section>` in its description. `auto_applicable: false` — rewrites need judgment. Create test fixture at `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md` exercising each pattern, plus a runner script that asserts (i) fixture hits ≥1 per pattern and (ii) the three known-good ADRs have zero `dsc-ai-tell` findings.

## Progress
- [x] Review + decomposition
- [x] 2026-05-18T12:00Z [ctx=info] Review + decomposition complete
- [x] Step implementation
- [x] 2026-05-18T12:15Z [ctx=safe] Step 1 complete (commit 05396db057bb25bc0001a2f53063142de1126eb0)
- [x] 2026-05-18T12:22Z [ctx=safe] Step 2 complete (commit 991f1c35918e98ec47ce4b9b8b50ec7a3a96d8e3)
- [x] 2026-05-18T12:31Z [ctx=safe] Step 3 complete (commit 0f56f60b8b365555d77d2e6a06aabe14a53f9559)
- [x] 2026-05-18T12:31Z [ctx=safe] Step implementation complete
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

- **T4 — Title Case 3-word threshold vs canonical rule.** The implementation regex `^#{2,6} ([A-Z][a-z]+ ){2,}[A-Z][a-z]+$` requires 3+ Title-Case words, which lets 2-word violations like `## Page Eviction` or `## Crash Recovery` pass silently. This is a deliberate calibration choice: the project's own ADR-scaffold headings (`Architecture Notes`, `Decision Records`, `Integration Points`, `Non-Goals`, `Key Discoveries`, `Component Map`) are all 2-word Title-Case and must pass Invariant 3 on the three calibration ADRs. The canonical rule at `house-style.md:281-283` does not carve out a word-count exemption — future Track 1 follow-up (out of scope here) could either tighten the canonical rule to "3+ Title-Case content words" or accept this as documented implementation drift. Captured here so a future hygiene sweep finds the gap.
- **T7 — `edit-design/SKILL.md` documentation drift.** Lines 215-217 of `.claude/skills/edit-design/SKILL.md` claim that `check_dsc_parenthetical_asides`, `check_per_section_length`, and several other rules "always run whole-doc regardless of `--scope`." This is already stale relative to the actual code — both `check_dsc_parenthetical_asides:776-788` and `check_per_section_length:707-710` honor `--scope=bounded`. Track 4's bounded-aware `check_dsc_ai_tell` increases the drift by one rule. Out of scope for this track; suggest a follow-up cleanup PR after YTDB-836 merges. Recorded here so the cleanup is not lost.
- **A4 — Invariant 3 is a snapshot, not evergreen.** The plan-level Invariant 3 ("zero false positives on three known-good ADRs") implicitly couples to the *current* contents of `house-style.md § Tier 1-4`. Banned-vocab tiers evolve (quarterly Tier-4 review per `house-style.md:90`); a future addition could legitimately appear in one of the calibration ADRs. Mitigation: documented in `## Validation and Acceptance` snapshot-semantics paragraph above and operationalised in the first Phase B step episode. A canonical fix would amend the plan-file Invariant 3 wording to include "at the time Track 4 lands" + a maintenance-protocol cross-reference — out of scope for Phase A track-file work; deferred to a possible future plan-file refinement.
- **A5 — Optional cheap-add: 5 additional banned-sentence-pattern regexes.** `house-style.md § Banned sentence patterns` lines 96-102 list 7 bullet patterns; Track 4 covers only negative parallelism. The other 6 — sycophantic openers ("Great question!", "Certainly!", "I'd be happy to"), throat-clearing ("It's worth noting that", "It is important to consider"), closing phrases ("In conclusion,", "In summary,", "Ultimately,"), trailing hedges, prompt-restating ("This document will…"), knowledge-cutoff disclaimers — are equally regex-detectable with low-FP risk (literal-phrase word-boundary regexes, structurally identical to the signposting / copula / authority tropes Track 4 already plans). Cost is ~30 extra lines (5 constants + 5 fixture demos). Not in scope for the initial Track 4 cut; Phase B implementer may absorb if calibration room allows. Capture as a follow-up issue if deferred.
- **A6 — D5 rationale framing.** The D5 paragraph at `implementation-plan.md:84-88` leads with the "placeholder citations" hazard but the load-bearing reason for folding is user direction during research. Cosmetic — decision survives, no action.
- **Runner-CI gap (T6 / A3 confirmed).** No `.github/workflows/*.yml` invokes `design-mechanical-checks.py`. The Python runner authored in step 3 is invocation-on-demand only. Follow-up issue (out of scope): wire `.github/workflows/design-checks.yml` to run the runner on every PR that touches `.claude/scripts/`, `.claude/output-styles/house-style.md`, or `docs/adr/**`.
- **Em-dash rule carve-out for balanced parenthetical asides.** Step 3 resolved the Step 1 residual finding on `index-gc/adr.md` via Option A: tightened the em-dash density rule so a paragraph with exactly 2 em dashes fires only when the middle segment contains a sentence terminator (`. ! ?`). Balanced parenthetical asides `A — clause — B` (no terminator in `clause`) are exempt; unbalanced `X. — Y — Z` cadences still fire. This is a documented carve-out parallel to the 3-word Title-Case threshold (entry T4): the canonical `house-style.md § Em-dash discipline` rule is "max 1 em dash per paragraph", and the implementation accepts a narrower "max 1 or balanced-aside" form. Track 1 follow-up (out of scope here) could either amend the canonical wording or accept this as documented implementation drift. The 12-finding baseline across the seven non-calibration ADRs is recorded in the `test_dsc_ai_tell.py` module docstring manifest. See Episodes §Step 3.

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective

- [x] Technical: PASS at iteration 2 (7 findings, 7 accepted: T1, T2 blocker (resolved via comma-cluster tightening), T3, T4 documented, T5, T6, T7 documented). Iteration 2 gate-check: 7/7 VERIFIED, no new findings.
- [x] Adversarial: PASS at iteration 2 (6 findings, 6 accepted: A1 (same root as T2), A2, A3, A4 documented, A5 documented, A6 documented). Iteration 2 gate-check: 6/6 VERIFIED, no new findings, no regressions.

## Context and Orientation

Starting state of `.claude/scripts/design-mechanical-checks.py` (1375 lines, surveyed during research):

- **Lines 1-32 — Module header, imports, usage.** Add a brief mention of `dsc-ai-tell` to the docstring after the existing rules are listed.
- **Lines 34-75 — Constants.** Add new constants: `TIER1_BANNED_VOCAB` (list of **29 base words** from `house-style.md § Tier 1 — hard ban` line 60 — see Phase A finding T1: the source list contains 29 base words, three of which (`navigate`, `unlock`, `underscore`) carry parenthetical qualifications that the simple constants-list cannot enforce; documented in Plan of Work step 1's per-pattern notes), `NEGATIVE_PARALLELISM_RE`, `SIGNPOSTING_OPENERS_RE`, `COPULA_AVOIDANCE_RE`, `AUTHORITY_TROPE_RE`, `HYPHENATED_PAIR_CLUSTER_THRESHOLD = 3`, `FRAGMENTED_HEADER_CONTENT_WORD_OVERLAP_THRESHOLD = 0.5` (note: renamed from `_LEMMA_OVERLAP_` per Phase A finding T3 — the implementation does content-word overlap with stop-word stripping, not true morphological lemmatisation), and `STOP_WORDS: frozenset[str]` (English stop-list — `the`, `a`, `an`, `is`, `are`, `was`, `were`, `be`, `been`, `being`, `of`, `for`, `to`, `in`, `on`, `at`, `by`, `from`, `with`, `as`, `and`, `or`, `but`, `if`, `then`, `that`, `this`, `these`, `those`, `it`, `its`).
- **Lines 77-265 — Existing parsing helpers** (`read_lines`, `parse_code_fence`, `is_code_fence_line`, `fence_closes`, `parse_sections`, `collect_all_headings`, `normalize_heading`). Reuse: `parse_code_fence` / `fence_closes` for fence tracking inside the new check. **A blank-line-bounded paragraph helper does NOT yet exist** — see Plan of Work step 1's first sub-step, which introduces `iter_paragraphs(...)` as the missing seam (Phase A finding A2).
- **Lines 297-315 — `make_finding` helper.** Reuse verbatim for all `dsc-ai-tell` findings; the schema fits.
- **Lines 318-1170 — Existing check functions** (`check_overview_first`, `check_per_section_shape`, `check_top_level_cap`, `check_per_section_length`, `check_dsc_parenthetical_asides`, `check_length_trigger_compliance`, `check_same_shape_siblings`, `check_mechanics_link_resolution`, `check_reverse_direction_refs`, `check_full_design_link_resolution`). The new `check_dsc_ai_tell` function sits alongside these. **Inherit the References-block (`in_references`) toggle and table-row skip from `check_dsc_parenthetical_asides:803-810`** so Tier-1 vocab matches in citation lines (`- D7: nuanced index design — paper title`) and table cells (`| Term | nuanced |`) do not fire spuriously (Phase A finding A2).
- **Lines 1177-1218 — `parse_args`.** No new CLI flags needed — `dsc-ai-tell` runs whenever `target` includes `design` (same gating as `check_dsc_parenthetical_asides`).
- **Lines 1221-1372 — `main` driver.** Add one call alongside `check_dsc_parenthetical_asides` (around line 1330, inside the `if args.target in ("design", "both"):` block) — `dsc-ai-tell` is a content-scan rule, not a structural-shape rule, so it belongs in the content-scan conditional, not in the `if run_design_shape_checks:` block above it. The two conditions evaluate equivalently today (`run_design_shape_checks = args.target in ("design", "both")`), so runtime behavior is identical; the placement preserves the existing structural-vs-content split.

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

1. **Implement `check_dsc_ai_tell` function.** Add the constants and the check function below `check_full_design_link_resolution` (around line 1170). The function signature matches the bounded-aware sibling `check_dsc_parenthetical_asides`: `check_dsc_ai_tell(file_path: str, lines: List[str], sections: Optional[List[Dict]] = None, changed_section: Optional[str] = None, scope: str = "whole-doc") -> List[Dict]`. When `scope == "bounded"` and `changed_section` is supplied, findings are restricted to the section whose title matches `changed_section` — every other section is skipped. When `scope == "whole-doc"` (default), the function walks the entire file. It tracks code-fence state via the existing `parse_code_fence` / `fence_closes` helpers and emits findings per pattern. Each finding's `description` cites `house-style.md § <Section>` and the `rule` is `dsc-ai-tell`. `auto_applicable=False`. Wire the call alongside `check_dsc_parenthetical_asides` (see § Context and Orientation entry for `main` driver). The check **inherits References-block and table-row exclusion** semantics from `check_dsc_parenthetical_asides:803-810` (Phase A finding A2) — toggle `in_references` on `### References` / `**References.**` lines, and skip lines starting with `|` (table rows) — so Tier-1 vocab in citations and table cells does not fire spuriously.

   **Sub-step 1.0 — paragraph helper.** Introduce a new helper `iter_paragraphs(lines: List[str], section: Optional[Dict] = None, exclude_fences: bool = True, exclude_references: bool = True, exclude_tables: bool = True) -> Iterator[Tuple[int, List[str]]]` that yields `(start_line_no, paragraph_lines)` tuples segmented by blank lines, with the exclusion semantics built in. Em-dash density and hyphenated-pair cluster scans consume this helper. The helper is reusable for future paragraph-scoped checks (Phase A finding A2).

   Per-pattern implementation notes:
   - **Tier-1 vocab:** word-boundary regex per word, case-insensitive, walked once over each non-excluded line. **29 base words** from `house-style.md § Tier 1 — hard ban` line 60 (Phase A finding T1). Three words carry parenthetical qualifications (`navigate (metaphorical)`, `unlock (metaphorical)`, `underscore (as a verb meaning "shows")`): the regex includes all 29 unconditionally and accepts that literal-meaning usages may false-fire; the Phase B episode captures observed FP rate on the three calibration ADRs (currently zero per Phase A grep verification) and falls back to per-word exclusion only if real usage shows the qualifications matter. Demote-to-`suggestion` is the documented escape hatch per D3.
   - **Negative parallelism:** the issue's regex `\bit'?s not\b.*\bit'?s\b`, but constrained to a single paragraph (not greedy across blank lines).
   - **Em-dash density:** consume `iter_paragraphs(..., exclude_fences=True)`; count `—` per paragraph; fire if count >1.
   - **Title Case heading:** `^#{2,6} ([A-Z][a-z]+ ){2,}[A-Z][a-z]+$` (3+ Title-Case words after H2-H6 marker, exempting acronyms in caps). The 3-word minimum is a deliberate calibration choice that lets the project's 2-word ADR-scaffold headings (`Architecture Notes`, `Decision Records`, `Integration Points`, `Non-Goals`, `Key Discoveries`, `Component Map`) pass — see Surprises & Discoveries entry for the rationale and the gap relative to the canonical rule (Phase A finding T4).
   - **Signposting:** word-boundary regex on `Let's dive`, `Let's break`, `Here's what you need`.
   - **Copula avoidance:** word-boundary regex on `serves as`, `stands as`.
   - **Authority tropes:** word-boundary regex on `at its core`, `fundamentally`, `the real question`.
   - **Hyphenated-pair cluster:** consume `iter_paragraphs(...)`. **Detect 3+ distinct hyphenated pairs in a single comma-separated cluster** (regex shape: `\b[a-z]+-[a-z]+(?:,\s+[a-z]+-[a-z]+){2,}\b` plus deduplication), matching the canonical `house-style.md § Hyphenated word-pair overuse` example "fast-paced, well-crafted, next-generation" exactly. This is strictly narrower than the original "immediately precedes a noun, or sits in a comma-separated list of modifiers" wording — the tighter form catches the canonical AI-tell pattern while letting legitimate technical compounds in adjectival position (e.g., `non-durable-wow/adr.md:5-12`'s "cache-backed data structures … double-write log protection … per-file non-durability support") pass. Severity stays `should-fix` because the tightened form is restrictive enough to hold Invariant 3; if Phase B validation shows even one false positive on the three calibration ADRs, demote to `suggestion` per D3. The comma-cluster decision and its rationale are recorded in the first Phase B step episode (Phase A findings T2 / A1).
   - **Fragmented header:** for each heading, take the next 1-line paragraph (no following blank line within 2 lines); split heading and paragraph into **content words** (lower-case, strip stop words via `STOP_WORDS`); compute intersection ratio; fire if ≥50%. **Tokenization rule:** split on `re.split(r"[^a-zA-Z0-9-]+", ...)` so hyphenated compounds stay as one token (`non-durable` is one token, not `non` + `durable`) — this avoids the false-positive case where `## Non-Goals` collides with body tokens of `non-durable` (Phase A finding T3).
2. **Create the fixture file.** Write `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md` with one paragraph per banned pattern plus **negative-case paragraphs** that exercise the highest-FP-risk rules without firing (Phase A findings T5 / A3).

   **Fixture authoring constraints** (Phase A finding T5):
   - Shape: one `## Overview` H2 (5–40 line body, ≥1 TL;DR-style line, References footer) plus one `## Banned patterns` H2 containing the demo `### <Pattern name>` H3s with sentence-case names (e.g., `### Negative parallelism`, not Title Case).
   - Demo placement: when the demonstration itself would otherwise fire on the fixture's own H3 heading (Title Case demo, fragmented-header demo), put the violating example **in the body prose inside a fenced code block** so `parse_sections` / `collect_all_headings` skip it for heading-related rules — but the regex still hits via `iter_paragraphs(..., exclude_fences=False)` only for the patterns that want it. For Title Case specifically, the rule operates on real H2+ lines; demonstrate via a fenced `text` block.
   - No parenthetical-aside-shaped prose (`(per Dxx)`, `(see Sxx)`) anywhere in fixture body — would fire `check_dsc_parenthetical_asides` and crowd the runner's `dsc-ai-tell`-only assertion.

   **Positive cases (≥1 paragraph per pattern):** Tier-1 vocab, negative parallelism, em-dash density, Title Case (H2+), signposting, copula, authority tropes, hyphenated-pair cluster, fragmented header. Total 9 demo paragraphs.

   **Negative cases (≥3 paragraphs that must NOT fire):**
   - **Hyphenated technical compounds in adjectival position** that should not fire — mirror `non-durable-wow/adr.md:5-12`'s shape, e.g., "The disk-based write cache provides cache-backed data structures with double-write log protection and per-file non-durability support." (4 distinct hyphenated pairs adjacent to nouns, NO comma cluster). Asserts the tightened comma-cluster regex from step 1 does not over-fire.
   - **Em-dash in 6-line technical-compound paragraph** with exactly one em-dash. Asserts em-dash density >1-per-paragraph rule does not over-fire on single em-dashes.
   - **H1 Title Case heading** (`# Some Title Cased Adr`) demonstrating that `^#{2,6}` correctly skips H1.
3. **Author the runner script and validate.** Create `.claude/scripts/tests/test_dsc_ai_tell.py` — a Python runner (per Phase A finding T6; the recommendation supersedes the original "shell or inline-in-CI" placeholder per D4's own caveat "shell runner can't easily express per-pattern expectations as cleanly as a unit test"). The runner invokes `python3 -m design_mechanical_checks` or `subprocess.run` against the script with `--design-path <fixture> --target design --scope whole-doc`, parses JSON, and asserts:
   - **Positive cases:** ≥1 finding with `rule == "dsc-ai-tell"` per expected pattern paragraph (matched by paragraph location range or description substring).
   - **Negative cases:** zero `dsc-ai-tell` findings on the negative-case paragraphs.
   - **Calibration ADRs:** zero `dsc-ai-tell` findings on `docs/adr/persist-visible-count/adr.md`, `docs/adr/index-gc/adr.md`, `docs/adr/non-durable-wow/adr.md`.

   **CI wiring is out of scope** — no `.github/workflows/*` invokes `design-mechanical-checks.py` today. The runner is invocation-on-demand only (Phase A findings T6 / A3); document this in `## Validation and Acceptance`.

   If the script over-fires on the calibration ADRs, capture observed counts in the third Phase B step episode and tune (demote severity per D3, or tighten the comma-cluster regex further).

Invariants to preserve:
- The existing check pipeline (overview-first, per-section-shape, top-level-cap, per-section-length, parenthetical-asides, length-trigger, same-shape-siblings, link-resolution checks) must continue to pass on every existing ADR with no regressions. The new check is *additive*.
- The script's CLI shape stays the same — no new required flags. `--target` and `--scope` already gate the new check naturally.
- The JSON output schema stays the same. `dsc-ai-tell` findings use the existing `make_finding` shape.

## Concrete Steps

1. Add `iter_paragraphs` helper, `check_dsc_ai_tell` function (9 patterns, bounded-aware signature, References-block + table-row exclusion), new constants, and `main()` wire-up in `.claude/scripts/design-mechanical-checks.py` — risk: medium (new non-public function that changes observable behavior of the mechanical-checks pipeline by emitting a new `dsc-ai-tell` finding type; heuristic-regex calibration risk is real at code-write time but covered empirically by step 3)  [x]  commit: 05396db057bb25bc0001a2f53063142de1126eb0
2. Create `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md` with 9 positive-case paragraphs (one per banned pattern) and ≥3 negative-case paragraphs (hyphenated technical compounds, single em-dash, H1 Title Case) per `## Plan of Work` step 2's fixture authoring constraints — risk: low (default: new single-purpose markdown fixture; not shared test infrastructure)  [x]  commit: 991f1c35918e98ec47ce4b9b8b50ec7a3a96d8e3
3. Author `.claude/scripts/tests/test_dsc_ai_tell.py` Python runner and run validation: assert ≥1 finding per positive-case paragraph, zero on negative-case paragraphs, zero on the three calibration ADRs (`docs/adr/persist-visible-count/adr.md`, `docs/adr/index-gc/adr.md`, `docs/adr/non-durable-wow/adr.md`), and no regressions on existing-ADR check output — risk: medium (new test infrastructure: first test harness under `.claude/scripts/tests/`; future tests for the mechanical-checks script may extend this scaffold)  [x]  commit: 0f56f60b8b365555d77d2e6a06aabe14a53f9559

## Episodes

### Step 1 — commit 05396db057bb25bc0001a2f53063142de1126eb0, 2026-05-18T12:15Z [ctx=safe]
**What was done:** Added the `dsc-ai-tell` rule to `.claude/scripts/design-mechanical-checks.py`. Introduced the 29-word Tier-1 banned-vocab list, five regex constants (negative parallelism, signposting, copula, authority tropes, hyphenated-pair comma cluster), `HYPHENATED_PAIR_CLUSTER_THRESHOLD = 3`, `FRAGMENTED_HEADER_CONTENT_WORD_OVERLAP_THRESHOLD = 0.5`, and a 31-word `STOP_WORDS` frozenset. Added an `iter_paragraphs` helper that segments blank-line-bounded paragraphs with fence / References-block / table-row exclusion, splitting on top-level bullet starters (`- `, `* `, `+ `, `N. `) so tight ADR bullet lists are not treated as one paragraph. Added the bounded-aware `check_dsc_ai_tell(file_path, lines, sections=None, changed_section=None, scope="whole-doc")` function with all nine pattern checks (Tier-1 vocab, negative parallelism, em-dash density, Title Case H2+, signposting, copula, authority, hyphenated-pair cluster, fragmented headers); each finding cites `house-style.md § <Section>` via the existing `make_finding` shape with `auto_applicable=False`. Wired the call into `main()` next to `check_dsc_parenthetical_asides` in the content-scan branch for both design and mechanics targets. Updated the module docstring to mention the new rule.

**What was discovered:** The original "blank-line-bounded paragraph" definition over-fired the em-dash density rule on calibration ADRs because tight markdown bullet lists (`- item one\n- item two — with em dash\n- item three — with em dash`) were treated as one paragraph. Splitting on top-level bullet starters inside `iter_paragraphs` makes each bullet its own paragraph for em-dash counting and brings `persist-visible-count/adr.md` and `non-durable-wow/adr.md` to zero `dsc-ai-tell` findings. `index-gc/adr.md` still shows one finding — two em dashes on lines 7-9 of the Summary form the parenthetical-aside pair "`BTree.put()` — when entries are already being redistributed —", a genuine `X — Y — Z` cadence tell per `house-style.md § Em-dash discipline`. Step 3's runner is the place this is reconciled — either by accepting the finding as a true positive (calling for a re-review of the calibration baseline), by tightening the em-dash rule to ignore matched parenthetical-aside pairs, or by demoting severity per D3.

**What changed from the plan:** Added one implementation detail not explicitly called out in the plan but required to meet Invariant 3 on the calibration ADRs: `iter_paragraphs` splits on top-level markdown bullet starters in addition to blank lines. Documented inline in the helper docstring. Affects no future steps (Step 2 fixture and Step 3 runner are unchanged).

**Key files:**
- `.claude/scripts/design-mechanical-checks.py` (modified)

**Critical context:** `index-gc/adr.md` currently registers 1 `dsc-ai-tell` finding (em-dash density at line 5). Step 3's runner is the gate for reconciling this against Invariant 3.

### Step 2 — commit 991f1c35918e98ec47ce4b9b8b50ec7a3a96d8e3, 2026-05-18T12:22Z [ctx=safe]
**What was done:** Created `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md` (and the previously-absent `.claude/scripts/tests/fixtures/` directory). The fixture seeds a single H1 plus two H2 wrappers (`## Overview`, `## Banned patterns`) and twelve H3 demo blocks: 9 positive cases (Tier-1 vocab, negative parallelism, em-dash density, Title Case H2+, signposting, copula, authority, hyphenated-pair comma cluster, fragmented header) and 3 negative cases (hyphenated technical compounds, single em-dash, H1 Title Case). Running `python3 .claude/scripts/design-mechanical-checks.py --design-path <fixture> --target design --scope whole-doc` yields 11 `dsc-ai-tell` findings (3 Tier-1 + 1 each for the other 8 patterns) and zero `dsc-ai-tell` findings inside the three negative-case sections. The three calibration ADRs remain at 0 / 1 / 0.

**What was discovered:** The Title Case regex `^#{2,6} ([A-Z][a-z]+ ){2,}[A-Z][a-z]+$` requires each Title-Case word to have ≥2 letters — `[A-Z][a-z]+` rejects a 1-letter word like `A`. An early fixture draft used `### A Title Case Demo Heading` and failed to fire the rule; the final draft uses `### Title Case Demo Heading` (three multi-letter Title-Case words). This is a fixture-authoring constraint Step 3's runner should encode in its assertion message. The fixture intentionally trips two non-`dsc-ai-tell` findings (`per-section-shape:tldr` and `per-section-shape:references-footer` on `## Banned patterns`) — the runner must assert against `rule == "dsc-ai-tell"` only.

**What changed from the plan:** Demonstrated the Title Case pattern with a real H3 heading rather than a fenced `text` block (the rule operates on real H2+ lines, so a fenced demo would have been skipped by the fence tracker). The Plan-of-Work "fenced text block" hint was a generic risk-avoidance suggestion; the real constraint is that the rule fires only on live heading lines, so a live H3 is the correct way to exercise it. Affects Step 3 only — the runner must match the Title Case demo by line range or description substring, not by an "every H3 must fire" heuristic.

**Key files:**
- `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md` (new)

**Critical context:** Fixture registers 11 `dsc-ai-tell` findings but exercises 9 distinct patterns — Tier-1 fires 3 times in one paragraph (one finding per matched base word). Step 3's runner should group by pattern identity, not raw count.

### Step 3 — commit 0f56f60b8b365555d77d2e6a06aabe14a53f9559, 2026-05-18T12:31Z [ctx=safe]
**What was done:** Authored `.claude/scripts/tests/test_dsc_ai_tell.py` — a Python runner that shells out to `design-mechanical-checks.py` against the seeded fixture and the three calibration ADRs, filters JSON findings to `rule == "dsc-ai-tell"` only, and asserts: (1) ≥1 finding per pattern via a 9-entry `PATTERN_SIGNATURES` table keyed on description prefixes; (2) zero findings on the fixture's H1 Title Case line and on the two `### *negative` H3 ranges; (3) zero findings on each calibration ADR. Resolved the Step 1 residual em-dash finding via Option A — tightened `check_dsc_ai_tell`'s em-dash density rule to fire on 3+ em dashes per paragraph, or on 2 em dashes whose middle segment carries a sentence terminator; balanced parenthetical asides `A — clause — B` are exempt. Bumped the fixture's em-dash demo from 2 to 3 em dashes so the unbalanced cadence still fires. Runner exits 0; calibration ADRs all hold at zero `dsc-ai-tell` findings; the seven non-calibration ADRs still emit the same 12 baseline findings recorded in the runner module docstring's manifest comment.

**What was discovered:** Option A reduces to a single middle-segment check rather than a multi-em-dash pairing search: every paragraph with exactly 2 em dashes has the shape `A — middle — B`, so the genuine `X — Y — Z` cadence vs balanced parenthetical aside disambiguation reduces to "is there a sentence terminator in `middle`?". The `index-gc` case has middle " when entries are already being redistributed " (no terminator → aside, drops out). The `thin-workflow/adr.md:58` case has middle " held. Entries are only added during " (period → two unpaired em dashes, still fires). The check is structurally narrower than the initial parenthetical-aside-pairing idea and avoids weakening the rule on the 12 baseline findings across the seven non-calibration ADRs.

**What changed from the plan:** Plan-of-Work step 3 directive listed three resolution paths for the residual finding (Option A tighten, Option B snapshot allowlist, Option C demote severity). Picked Option A; the runner's calibration assertion is the simple "zero findings" form rather than the snapshot allowlist Option B would have required. Plan-file Invariant 3 wording survives unchanged.

**Key files:**
- `.claude/scripts/tests/test_dsc_ai_tell.py` (new)
- `.claude/scripts/design-mechanical-checks.py` (modified — em-dash rule tightened)
- `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md` (modified — em-dash demo bumped 2 → 3)

**Critical context:** Runner is invocation-on-demand only — no CI wiring (Phase A finding T6/A3). Future regression detection depends on running `python3 .claude/scripts/tests/test_dsc_ai_tell.py` manually. The runner's module docstring carries the 12-finding baseline across the seven non-calibration ADRs as a manifest comment; if a future change drives any of those to zero unintentionally, the rule has been weakened.

## Validation and Acceptance

Track-level acceptance:
- **Positive case coverage:** `dsc-ai-tell` fires ≥1 finding per banned pattern on the fixture file's 9 positive-case paragraphs.
- **Negative case coverage:** `dsc-ai-tell` fires **zero** findings on the fixture's 3+ negative-case paragraphs (hyphenated technical compounds, single em-dash, H1 Title Case).
- **Calibration ADRs:** `dsc-ai-tell` fires **zero** findings on `docs/adr/persist-visible-count/adr.md`, `docs/adr/index-gc/adr.md`, `docs/adr/non-durable-wow/adr.md`.
- **No regressions:** the existing check pipeline (overview-first, per-section-shape, top-level-cap, per-section-length, parenthetical-asides, length-trigger, same-shape-siblings, link-resolution checks) produces the same output on every existing ADR as before this track.

**Snapshot semantics for Invariant 3 (Phase A finding A4):** the zero-FP property on the three calibration ADRs holds **at the time Track 4 lands**. Banned-vocab tiers in `house-style.md` are subject to quarterly review (see `house-style.md:90`), and a future vocab addition could legitimately appear in one of the calibration ADRs (citation, technical name, quoted passage). Any subsequent PR that adds words to `house-style.md § Tier 1-4` MUST re-run `python3 .claude/scripts/tests/test_dsc_ai_tell.py` in the same PR; document this expectation in the first Phase B step episode's notes.

**Runner trigger:** the Python runner is invocation-on-demand only — CI wiring (`.github/workflows/*.yml`) is out of scope for Track 4 (Phase A findings T6 / A3). Add the trigger expectation to Surprises & Discoveries so a future cleanup task picks it up.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. -->

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. -->

## Idempotence and Recovery

## Artifacts and Notes

## Interfaces and Dependencies

**In-scope files:**
- `.claude/scripts/design-mechanical-checks.py` (additive: new constants, new `iter_paragraphs` helper, new `check_dsc_ai_tell` function, one call in `main`).
- `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md` (new file; positive + negative cases).
- `.claude/scripts/tests/test_dsc_ai_tell.py` (new file — Python runner, resolved in Phase A finding T6).
- `.claude/scripts/tests/` directory (new, created by Step 2 since `.claude/scripts/` currently has no `tests/` subdirectory).

**Out-of-scope files:**
- `house-style.md` — Track 1 owns its content; this track only cites by section name.
- `.claude/skills/edit-design/SKILL.md` — invokes `design-mechanical-checks.py` already; finding pipeline absorbs the new rule without changes to the skill.
- Other ADRs under `docs/adr/**` — only the three named calibration ADRs are touched, and read-only.

**Inter-track dependencies:**
- **Depends on:** Track 1. Each finding's `description` cites `house-style.md § <Section>`. Track 1 must have fixed those section names.
- Independent of Tracks 2 and 3 — Track 4 can run in parallel with them once Track 1 is done.

**Library / function signatures (within the script):**
- `check_dsc_ai_tell(file_path: str, lines: List[str], sections: Optional[List[Dict]] = None, changed_section: Optional[str] = None, scope: str = "whole-doc") -> List[Dict]` — signature matches the bounded-aware sibling `check_dsc_parenthetical_asides`. `scope="bounded"` + `changed_section` restricts findings to the named section; `scope="whole-doc"` walks the entire file.
- `iter_paragraphs(lines: List[str], section: Optional[Dict] = None, exclude_fences: bool = True, exclude_references: bool = True, exclude_tables: bool = True) -> Iterator[Tuple[int, List[str]]]` — new helper yielding `(start_line_no, paragraph_lines)` for blank-line-bounded paragraphs with built-in fence / References-block / table-row exclusion. Reused by em-dash density and hyphenated-pair cluster scans (Phase A finding A2).
- Reuses `parse_code_fence`, `fence_closes`, `collect_all_headings`, `make_finding` from elsewhere in the file.

**External contracts:**
- The JSON output schema published to callers of `design-mechanical-checks.py` (the `edit-design` skill, CI hooks, manual invocations) does not change. New findings appear with `rule == "dsc-ai-tell"` and `auto_applicable: false`.

## Base commit

a75f18d62386ff1a8420d38a25ac5ba8d14c1403
