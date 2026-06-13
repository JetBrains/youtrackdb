<!-- MANIFEST
review_kind: adversarial
phase: 3A
track: "Track 1: Author the rule and update the canonical homes, core docs, hook, and CLAUDE.md"
iteration: 1
verdict: changes-requested
findings: 3
blockers: 1
index:
  - id: A1
    sev: blocker
    anchor: "A1"
    loc: ".claude/hooks/house-style-write-reminder.sh:262"
    cert: "Violation scenario: tier_b_body 500-char per-body cap"
    basis: "test_18_reminder_body_length_budget PER_BODY_CHAR_CAP=500; live body 441 chars; planned slug+carve = 580 chars"
  - id: A2
    sev: should-fix
    anchor: "A2"
    loc: ".claude/workflow/implementer-rules.md:1102"
    cert: "Violation scenario: every-numeric-count-reads-six invariant"
    basis: "Plan of Work step 4 names only commit-conventions.md:191 and step-implementation.md:1038 as count sites; implementer-rules.md:1102 'five section slugs' omitted and unmatched by the deferred grep"
  - id: A3
    sev: suggestion
    anchor: "A3"
    loc: "docs/adr/mid-level-english/_workflow/plan/track-1.md:144"
    cert: "Assumption test: deferred Phase-A grep enumerates every subset-naming site"
    basis: "Grep pattern 'five AI-tell|five Tier-B|five sections' misses 'five section slugs' (implementer-rules.md:1102) and any future variant-wording count; recipe at conventions.md:572 is a 4-slug rename helper, not a six-slug enumeration (matches #1142 precedent)"
evidence_base:
  - "Violation scenario: tier_b_body 500-char per-body cap — CONSTRUCTIBLE"
  - "Violation scenario: every-numeric-count-reads-six invariant — CONSTRUCTIBLE"
  - "Assumption test: deferred Phase-A grep enumerates every subset-naming site — FRAGILE"
  - "Assumption test: CLAUDE.md:104 de-enumeration has no machine consumer (D6) — HOLDS"
  - "Challenge: Track 1 sizing vs the #1142 three-track precedent — survives YES"
-->

## Findings

### A1 [blocker]
**Certificate**: Violation scenario: tier_b_body 500-char per-body cap
**Target**: D3 (Tier-B code-comment restatement) / Plan of Work step 5 / Validation
**Challenge**: The track plans to add both the `§ Plain language` slug AND "a matching carve note" to the hook's `tier_b_body` string, but does not account for the hard 500-char per-body cap that `test_18_reminder_body_length_budget` enforces. The live `tier_b_body` is already 441 chars (the #1142 review-fix commit `7cd8fbf97c` trimmed it from 545 to 441 specifically because it had overrun this cap once before). Adding the slug (`, § Plain language`) plus a parallel Plain-language carve note of the same shape as the existing Orientation carve note projects to ~580 chars — over the cap — so step 5 as written fails a pinned test and forces a mid-track rework that re-derives exactly the #1142 fix.
**Evidence**: `.claude/scripts/tests/test_house_style_hook.py:798` `PER_BODY_CHAR_CAP = 500`, asserted at `:833` inside `test_18_reminder_body_length_budget` (in the active roster at `:867`). Hook comment at `house-style-write-reminder.sh:254` documents the cap; `tier_b_body` at `:262`. Simulation: current 441 chars; +slug +count-flip alone = 458 (passes); +slug +count-flip +parallel carve note = 580 (fails). #1142 commit `7cd8fbf97c` message: "had grown to 545 chars, past the 500-char per-body cap … Trim … (441 chars now)". The track names only `test_16_section_name_guard` as the slug-keyed test; it never names `test_18` or the 500-char budget anywhere in D2/D3, Plan of Work, or Validation.
**Proposed fix**: Decide the hook carve-note approach against the budget before Phase B and write it into D3 / step 5. Either (a) drop the parallel carve note from `tier_b_body` and add the slug + count-flip only (458 chars, passes) — the full Plain-language carve already lives in `house-style.md` and `conventions.md §1.5`, exactly the trim rationale #1142 used for Orientation; or (b) shorten the existing Orientation carve sentence to fit both carves under 500. Add `test_18_reminder_body_length_budget` to the track's Validation list so the cap is checked, and note the 441→ headroom (51 chars) explicitly so the executor does not blow it.

### A2 [should-fix]
**Certificate**: Violation scenario: every-numeric-count-reads-six invariant
**Target**: Invariant ("every numeric count of the subset reads 'six'") / Plan of Work step 4
**Challenge**: Plan of Work step 4 closes its count-site list to two files — "the count sites are `commit-conventions.md:191`, `step-implementation.md:1038`" — but a third numeric count exists at `implementer-rules.md:1102`: "the **five** section slugs that make up the Tier-B AI-tell subset". The Interfaces block lists `implementer-rules.md` only as "enumeration (`:1100`)", flagging the slug list but not the count two lines above it. An executor who follows step 4 literally flips the slug enumeration at `:1100-1105` to six and the two named counts, then leaves `:1102` reading "five" beside a six-item slug list directly below — an in-file five-vs-six contradiction that violates the stated invariant and is exactly what the cross-file consistency review (the invariant's named test) would flag.
**Evidence**: `.claude/workflow/implementer-rules.md:1102` "the five section slugs that make up" — the count word; the six (post-flip five) slugs follow at `:1103-1105`. Plan of Work step 4 (`track-1.md:91`) lists only `commit-conventions.md:191` and `step-implementation.md:1038`. Confirmed the only other "five" hits in the core docs are unrelated ("five numbered sub-steps", "five verdicts"), so `:1102` is the single missed count.
**Proposed fix**: Add `implementer-rules.md:1102` ("five section slugs" → "six section slugs") to Plan of Work step 4's count-site list and to the Interfaces block's `implementer-rules.md` entry (annotate "enumeration `:1100` + count `:1102`"). Phase-A decomposition should treat the count flip as part of the same step that flips the enumeration in that file.

### A3 [suggestion]
**Certificate**: Assumption test: deferred Phase-A grep enumerates every subset-naming site
**Target**: Assumption (track-1.md:144 — "Exact in-scope set is derived by `grep …` at Phase A")
**Challenge**: The track defers the exact in-scope set to `grep -rln 'Banned analysis patterns\|five AI-tell\|five Tier-B\|five sections' .claude/ CLAUDE.md`. The first alternation term (`Banned analysis patterns`) reliably catches every slug-enumeration file (it is one of the six slugs), so enumeration sites are covered. The weak point is the *count* terms: they match only three exact phrasings. The live count at `implementer-rules.md:1102` reads "five section slugs" — matched by none of the four patterns (it is caught only incidentally, on its `Banned analysis patterns` slug line, which lands the file in the enumeration bucket but never surfaces the `:1102` count). Any future count phrased "five section" / "the subset's five" likewise escapes. This is the FRAGILE assumption behind A2: the grep finds files, not count occurrences, so a count that does not use the three blessed phrasings is invisible to it.
**Evidence**: Running the deferred grep verbatim returns the Track-1/2/3 file set correctly, but `grep -nE 'five AI-tell|five Tier-B|five sections' implementer-rules.md` returns nothing — the `:1102` count hides behind the literal "five section slugs". Separately, the §1.5 rename helper at `conventions.md:572` lists only four slugs (`Banned vocabulary|Banned sentence patterns|Banned analysis patterns|Em-dash discipline`); #1142 (`fa26c0fe26`) deliberately left it at four when it added Orientation, so it is a stable-heading-rename helper, not a six-slug subset enumeration, and `Plain language` being absent matches that precedent — no change needed there.
**Proposed fix**: Broaden the Phase-A count sweep to a count-agnostic pattern, e.g. `grep -rnE '\bfive\b' .claude/ CLAUDE.md` filtered for `section|slug|subset|AI-tell|Tier-B`, then reconcile every hit against the six-slug invariant (this surfaces `:1102`). Keep the existing enumeration grep for the slug-list files. No edit to the `conventions.md:572` rename helper is required; if the executor touches it, leave it at the four rename-sensitive slugs to match #1142.

## Evidence base

#### Violation scenario: tier_b_body 500-char per-body cap
- **Invariant claim**: D2 holds that the flip is "enumeration sync of pre-existing checks, not a new check"; D3 and Plan of Work step 5 add the `§ Plain language` slug plus a matching carve note to the hook's `tier_b_body`, and the track assumes this is mechanically safe.
- **Violation construction**:
  1. Start state: `house-style-write-reminder.sh:262` `tier_b_body` = 441 chars (post-#1142-trim). Hook comment `:254` documents "Each ≤500 chars". `test_house_style_hook.py:798` `PER_BODY_CHAR_CAP = 500`, asserted at `:833` in `test_18_reminder_body_length_budget`, active in the roster at `:867`.
  2. Action sequence: executor applies Plan of Work step 5 — add `, § Plain language` to the slug list (`+18` chars), flip "five sections" → "six sections" (`-1` char), and add a parallel Plain-language carve note shaped like the existing Orientation carve ("§ Orientation bans out-of-file assumptions in code comments, not in-file terseness.", ~82 chars).
  3. Intermediate state: `tier_b_body` ≈ 580 chars.
  4. Violation point: `test_18_reminder_body_length_budget` at `test_house_style_hook.py:833` — `len(body) > PER_BODY_CHAR_CAP` (580 > 500) → failure.
  5. Observable consequence: the test suite fails mid-track; the executor must trim, re-deriving exactly the #1142 review-fix `7cd8fbf97c` (which trimmed 545 → 441). The "validated by the test runner" claim in the hook comment makes this a hard gate, not a soft warning.
- **Feasibility**: CONSTRUCTIBLE — simulated: 441 base; slug+count-only = 458 (passes); slug+count+parallel carve = 580 (fails). The carve note is explicitly required by D3 Risks ("the hook reminder carries the matching carve note") and Plan of Work step 5 ("with a carve note").

#### Violation scenario: every-numeric-count-reads-six invariant
- **Invariant claim**: "After all three tracks land … every numeric count of the subset reads 'six'." (plan Invariants; track Plan of Work line 95.)
- **Violation construction**:
  1. Start state: three numeric count sites in Track-1 scope — `commit-conventions.md:191`, `step-implementation.md:1038`, and `implementer-rules.md:1102` ("the five section slugs that make up the Tier-B AI-tell subset").
  2. Action sequence: executor follows Plan of Work step 4, which names only the first two as "the count sites", and flips them plus the `implementer-rules.md:1100-1105` slug enumeration to six.
  3. Intermediate state: `implementer-rules.md` now lists six slugs at `:1103-1105` but still reads "five section slugs" at `:1102`.
  4. Violation point: `implementer-rules.md:1102` — count reads "five" against a six-item list one line below.
  5. Observable consequence: an in-file five-vs-six contradiction; the cross-file consistency review (the invariant's named verifier) flags it, forcing a fix iteration.
- **Feasibility**: CONSTRUCTIBLE — the count site exists at `:1102`, Plan of Work step 4 omits it from its closed two-item list, and the Interfaces entry for the file flags only the enumeration at `:1100`.

#### Assumption test: deferred Phase-A grep enumerates every subset-naming site
- **Claim**: The deferred grep `'Banned analysis patterns\|five AI-tell\|five Tier-B\|five sections'` derives the exact in-scope set (track-1.md:144), implying it surfaces every site the six-slug invariant binds.
- **Stress scenario**: a count phrased other than the three blessed literals — e.g. "five section slugs" (`implementer-rules.md:1102`) or a future "the subset's five".
- **Code evidence**: `grep -nE 'five AI-tell|five Tier-B|five sections' .claude/workflow/implementer-rules.md` returns nothing; the file is matched only on its `Banned analysis patterns` slug line at `:1104`, so the grep lands the file but never surfaces the `:1102` count. The `Banned analysis patterns` term does reliably catch every slug-enumeration file, so the enumeration half of the assumption holds; only the count half is fragile.
- **Verdict**: FRAGILE — enumeration coverage holds; count coverage misses any non-blessed phrasing. Grounds A2 and motivates the count-agnostic sweep in A3.

#### Assumption test: CLAUDE.md:104 de-enumeration has no machine consumer (D6)
- **Claim**: D6 de-enumerates `CLAUDE.md:104`'s four-item subset parenthetical to a pointer; the only loss is one reader's inline example.
- **Stress scenario**: a test, hook, or script reads `CLAUDE.md:104`'s literal slug list and would break when the list disappears.
- **Code evidence**: `CLAUDE.md:104` carries the four-item parenthetical "(banned vocabulary, banned sentence patterns, banned analysis patterns, em-dash discipline)" — confirming the pre-existing-lag claim (it omits Orientation). No test, hook, or script reads it: `test_house_style_hook.py` has zero `CLAUDE` references; `test_workflow_reindex.py:1780-1808` explicitly treats `CLAUDE.md` as out of scope; the hook references `CLAUDE.md` only as a path string, never the slug list. The `dsc-ai-tell` checker keys on `house-style.md`, not `CLAUDE.md`.
- **Verdict**: HOLDS — de-enumeration is safe; no finding.

#### Challenge: Track 1 sizing vs the #1142 three-track precedent
- **Chosen approach**: a three-track split — canonical homes + 11 core docs + hook + test + CLAUDE.md in Track 1 (~16 files), 11 prompts + 6 skills in Track 2, 20 agents in Track 3.
- **Best rejected alternative**: merge Track 1 into a smaller-scoped split, or fold Tracks 2/3 differently.
- **Counterargument trace**:
  1. The soft sizing bounds (`planning.md` §Track descriptions, restated in the prompt): a track ≤~12 in-scope files is a merge candidate; >~20-25 is a split candidate. Track 1's in-scope list is exactly 16 bullets — inside both bounds, no written justification owed.
  2. The #1142 squash (`f74ef47e94`) is the direct precedent: 57 files across `.claude/workflow` (19), `.claude/agents` (18), `.claude/skills` (6), output-styles (2), hook (1), scripts/test (3). Its episode trail ("Track 1 step 3", "Track 2 Step 2") confirms a three-track decomposition along the same seams — canonical homes + core docs together, prompts/skills, agents.
  3. This produces no sizing concern: Track 1 sits mid-band and matches the precedent's grouping. Track 3 at ~20 files is at the split-candidate edge but carries the agent group as one reviewable unit, mirroring #1142.
- **Codebase evidence**: `git show --stat f74ef47e94` (57 files, dir breakdown above); track-1.md "In scope" = 16 bullets.
- **Survival test**: YES — sizing and the split shape hold; no finding.
