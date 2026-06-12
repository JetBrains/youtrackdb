<!-- MANIFEST
findings: 7   severity: {blocker: 0, should-fix: 5, suggestion: 2}
index:
  - {id: A1, sev: should-fix, loc: "docs/adr/explanation-style/_workflow/plan/track-2.md:49", anchor: "### A1 ", cert: C6, basis: "track asserts Track 1 flipped the readability-feedback governance grep; realized Track 1 (R1) kept it four-string and never committed to the file; track contradicts itself at :58"}
  - {id: A2, sev: should-fix, loc: ".claude/workflow/design-document-rules.md:284", anchor: "### A2 ", cert: C2, basis: "dsc-ai-tell row enumerates nine patterns by name; two new regexes make the count and roster stale; file absent from the track's in-scope roster and step-2 sync set"}
  - {id: A3, sev: should-fix, loc: ".claude/workflow/prompts/design-review.md:126", anchor: "### A3 ", cert: C3, basis: "new block planned with applies-to line only; sibling Human-reader block is activated by four explicit pointers the track's three-site sync set omits"}
  - {id: A4, sev: should-fix, loc: "docs/adr/explanation-style/_workflow/plan/track-2.md:97", anchor: "### A4 ", cert: C4, basis: "inflated-abstraction label has no house-style rule text to cite, breaking the dsc-ai-tell citation convention; 'faux-symmetry' collides with house-style.md:341's different defined meaning"}
  - {id: A5, sev: should-fix, loc: "docs/adr/explanation-style/_workflow/plan/track-2.md:76", anchor: "### A5 ", cert: C8, basis: "calibration acceptance cannot fail: dsc-ai-tell emits should-fix at most and design-mechanical-checks.py exits 1 only on blockers"}
  - {id: A6, sev: suggestion, loc: "docs/adr/explanation-style/_workflow/plan/track-2.md:60", anchor: "### A6 ", cert: C5, basis: "'internal order is free' overstates: the test item depends on the regex item; bind them at decomposition"}
  - {id: A7, sev: suggestion, loc: "docs/adr/explanation-style/_workflow/plan/track-2.md:103", anchor: "### A7 ", cert: C1, basis: "under-floor split justification verified against realized Track 1 (~54 files, over ceiling); the split decision holds"}
evidence_base: {section: "## Evidence base", certs: 11, matches: 5}
cert_index:
  - {id: C1, verdict: YES, anchor: "#### C1 "}
  - {id: C2, verdict: NO, anchor: "#### C2 "}
  - {id: C3, verdict: WEAK, anchor: "#### C3 "}
  - {id: C4, verdict: NO, anchor: "#### C4 "}
  - {id: C5, verdict: WEAK, anchor: "#### C5 "}
  - {id: C6, verdict: BREAKS, anchor: "#### C6 "}
  - {id: C7, verdict: HOLDS, anchor: "#### C7 "}
  - {id: C8, verdict: BREAKS, anchor: "#### C8 "}
  - {id: C9, verdict: INFEASIBLE, anchor: "#### C9 "}
  - {id: C10, verdict: INFEASIBLE, anchor: "#### C10 "}
  - {id: C11, verdict: INFEASIBLE, anchor: "#### C11 "}
flags: [CONTRACT_OK]
-->

# Adversarial review — Track 2, iteration 1

Reviewer: reviewer-adversarial (Phase 3A). Scope per D9 narrowing: (1) scope and sizing, (2) cross-track-episode reality against Track 1's realized outputs, (3) plan-invariant violation. D4/D4b decisions themselves are not re-challenged. The plan's `### Constraints` carries the `§1.7(k)` opt-out marker (implementation-plan.md:21), so the workflow-machinery prose criteria applied throughout: every named reference was resolved as a live path or `§`-anchor via grep and Read; no Java symbols appear in the track, so PSI was not needed. All three plan invariants survive (C9-C11). The split from Track 1 survives (C1). The track's footprint and its orientation prose do not survive unamended: one realized-output claim is false (C6), one sync site is missing (C2), the new block's activation wiring is unplanned (C3), one out-of-scope premise is wrong (C4), and one acceptance line is unfalsifiable (C8).

## Findings

### A1 [should-fix]
**Certificate**: C6 (assumption test — "Track 1 flipped the readability-feedback governance grep")
**Target**: Assumption (cross-track-episode reality; track-2.md `## Context and Orientation` :49 and the overlap note :101)
**Challenge**: The track asserts twice that Track 1 flips `readability-feedback/SKILL.md`'s governance grep, and once that the grep was "kept four-string". Only the second claim matches the realized Track 1. `git log` shows zero branch commits to the file (its only commit is `2498508d6c`, from develop); track-1.md:196 records R1 — "this grep is unchanged — it enumerates pointer sites, not subset membership" — and the step-5 episode part F confirms "both governance greps confirmed unchanged and byte-identical (four-string, R1)". The live grep at SKILL.md:54 is four-string, byte-identical to conventions.md:572.
**Evidence**: The hazard is not cosmetic. An implementer who trusts :49 ("flipped by Track 1") over :58 ("kept four-string") sees a four-string grep at line 54, concludes Track 1 left the file half-done, and "completes" the flip — which breaks byte-identity with the conventions.md:572 copy and reverses Track 1's settled R1 decision. The overlap note's planning.md §"Justify any overlap-split" justification also rests on the false premise: as realized, the file is not shared between the tracks — Track 2 is its only editor on this branch.
**Proposed fix**: Amend the track file via the Track Pre-Flight gate: rewrite :49 to state R1's outcome (Track 1 confirmed the grep unchanged and four-string; no Track 1 commit touched the file), and restate the overlap note as resolved history (the planned overlap dissolved when Track 1's grep edit reduced to a no-op confirm; Track 2 is the file's sole editor). Keep step 4's "kept four-string" instruction as is — it is the correct one.

### A2 [should-fix]
**Certificate**: C2 (scope challenge — footprint completeness)
**Target**: Scope (the ~4-file in-scope roster and step 2's sync set)
**Challenge**: `design-document-rules.md:284` is a dsc-ai-tell enumeration site the roster misses. Its `## Mechanical checks` row reads "Detects **nine** `house-style.md` patterns whose textual fingerprint is reliable enough to flag mechanically:" and then names all nine. Landing two new regexes makes the count and the closed roster stale. The grep sweep for `dsc-ai-tell` outside the script and its test returns exactly this one prose site, so the miss is the entire external sync surface.
**Evidence**: The track itself edits the file that mandates this sync: `readability-feedback/SKILL.md`'s Rule sync map (:48) names "`.claude/workflow/design-document-rules.md` — the `## Mechanical checks` table" as a sync target for rule changes. Track 2's own Phase C runs `review-workflow-consistency`, which reads cross-file beyond the diff; a nine-vs-eleven drift between the script and its documented roster is precisely what it flags, forcing a fix iteration that a one-line roster addition now avoids. Neither the track nor the frozen `design.md §"Over-dense prose enforcement"` Edge-cases list names this site, so the gap needs a track-level correction (and a Phase-4 `design-final.md` reconciliation note, the same pattern the plan review used for CR1's ~50→54 count).
**Proposed fix**: Add `.claude/workflow/design-document-rules.md` to the in-scope roster (~4 → ~5 files) and extend step 2's sync set with the :284 row update (count nine→eleven plus the two new pattern entries with their `§` citations). Record the design-side lag for Phase 4 reconciliation.

### A3 [should-fix]
**Certificate**: C3 (scope challenge — step 1's three-site sync set completeness)
**Target**: Scope (step 1's sync enumeration; D4's realization, not D4 itself)
**Challenge**: Step 1 plans the new block "with its own applies-to line" plus three sync sites (TOC row, `§ Tone and depth` count, Rule sync map row). But the sibling `### Human-reader cold-read additions` block is not activated by its applies-to line alone — it is pointed at from four places: the three mutation-kind bullets each carry an explicit "Plus the Human-reader cold-read additions (§ below)" sentence (design-review.md:126, :135, :148), and the output-format parenthetical (:369-375) tells the reviewer where that block's findings go and how to format their evidence. None of these will reference the new block, and for `target=tracks` the situation is worse: the `## Track-scoped cold-read (Step 4b)` section (:182) enumerates exactly which criteria apply to that target ("The seven §Comprehension questions apply, re-pointed…", absorption, fidelity) and a sibling block it never names has no inbound pointer on that path at all.
**Evidence**: The file's own convention is explicit per-path wiring; a block reachable only through its TOC row and an applies-to line buried among design-kind-bound siblings risks running never, and the `target=tracks` direction — the half of D4 that justifies the both-targets decision — is the direction with zero pointers. The output-format gap also leaves the new block's findings without a defined home or evidence-tone instruction.
**Proposed fix**: Extend step 1's sync set: add the new block to the three mutation-kind "Plus …" sentences (or one canonical equivalent), add one sentence to §Track-scoped cold-read naming the block as an applicable criterion for `target=tracks`, and extend the :369-375 output-format parenthetical (and its kind list, which must now also cover `target=tracks`) to cover the new block's findings.

### A4 [should-fix]
**Certificate**: C4 (scope challenge — the out-of-scope boundary's factual premise)
**Target**: Non-Goal (track-2.md:97 — "the over-dense rules the block scans against … already exist; this track adds enforcement, not new rule text")
**Challenge**: The premise holds for only two of the four judgment cases. "Run-on mechanism traces" and "lists spliced into one sentence" resolve to `§ Mechanism traces and inline citations` (house-style.md:384-386 covers the inline (1)(2)(3) splice). "Inflated-abstraction labels" resolves to nothing: no house-style section states the tell — `§ Nominalization and placeholder words` (:166) bans placeholder nouns like "material" and "data", a different move from grandiose definite labels like "the enabling primitive". The "X, not Y" shape is likewise not enumerated under Negative parallelism (:120 lists "not X, but Y" variants only), and the name the track gives it — "faux-symmetry" — already means something else in house-style.md:341 ("Do not invent a third bullet or a fourth section just to balance the structure"), a terminology collision in the very file the rules cite.
**Evidence**: The dsc-ai-tell convention is load-bearing: "Each finding cites `house-style.md § <Section>` in its description so the reader can trace the rule back to its prose" (design-document-rules.md:284). An inflated-abstraction finding has no section to cite, so the implementer must either improvise a citation into a section that does not state the rule (breaking traceability, and teachable as a consistency finding) or add rule text the track declares out of scope. The same gap re-creates the WC1 defect class the track's own step 4 exists to fix: the readability audit classifies passages as "CAUGHT by §" or GAP, and an inflated-abstraction passage stays a GAP after this track because no `§` catches it.
**Proposed fix**: Narrow the out-of-scope claim and add the minimal rule text: one clause extending Negative parallelism with the inverted "X, not Y" shape, and one sentence (in `§ Nominalization and placeholder words` or `§ Banned analysis patterns`) stating the inflated-abstraction-label tell. Rename the pattern away from "faux-symmetry" (e.g. "inverted negative parallelism") to avoid the :341 collision. Record the scope amendment as a live decision in the track's `## Decision Log`. This keeps house-style.md in the footprint for two surgical sentences; the alternative — a recorded decision to cite-nothing for these two patterns — requires amending the :284 citation convention and is the worse trade.

### A5 [should-fix]
**Certificate**: C8 (assumption test — "the demotable calibration acceptance line can fail")
**Target**: Assumption (track-2.md:76, acceptance bullet "Running `edit-design` … produces no blocker-severity false positive from the two new regexes")
**Challenge**: The bullet is unfalsifiable as written. `check_dsc_ai_tell` emits findings at should-fix at most (e.g. design-mechanical-checks.py:1847), with demote-to-suggestion as the documented fallback (:105); `main()` returns 1 only when `blockers > 0` (:2266, :2279). A blocker-severity false positive from these regexes is unconstructible, so the acceptance line passes no matter how badly the regexes misfire. Meanwhile the false-positive base rate is demonstrably high: house-style.md itself uses the contrastive "X, not Y" shape repeatedly in sanctioned prose (:80, :333, :347, :360), as does most workflow prose.
**Evidence**: D4b names the Phase-4 self-application false-positive count as "the calibration point", but the track-level acceptance reduces that to a check that cannot fail, so the calibration has no recorded teeth and no failure threshold — and it is also the only acceptance bullet that cannot be verified at this track's own Phase C (it fires in Phase 4).
**Proposed fix**: Rewrite the bullet with bite that is checkable at track time: the false-positive guards in `test_dsc_ai_tell.py` must include the contrastive shapes house-style.md itself uses (cite 2-3 verbatim), and the two regexes must produce zero findings when run over `house-style.md` and the current `design.md` (the in-repo conforming corpus). Keep the Phase-4 observed-count line as a record-the-number instruction (episode-recorded FP count), not a pass/fail gate.

### A6 [suggestion]
**Certificate**: C5 (scope challenge — internal ordering claim)
**Target**: Plan of Work ordering note (track-2.md:60 — "Internal order is free — the block, regexes, and tests are independent within the track")
**Challenge**: Item 3 (tests for the two regexes) cannot precede item 2 (the regexes), and a step is "a single atomic change = one commit. Fully tested" — landing the regexes untested in their own commit violates that, while landing tests for absent regexes is impossible. Only the block, the WC1 edit, and the regex+test pair are mutually independent.
**Evidence**: track-2.md:56-57 (items 2 and 3); the step definition in the Phase-A workflow context.
**Proposed fix**: At decomposition, bind items 2 and 3 into one step (regexes + tests, one commit) and reword the ordering note to "the block, the regex+test step, and the WC1 edit are independent".

### A7 [suggestion]
**Certificate**: C1 (scope challenge — the split from Track 1)
**Target**: Sizing justification (track-2.md:103)
**Challenge**: Argued for folding Track 2 into Track 1 (the under-floor merge-candidate default) and for re-splitting along a different seam. Both fail against the realized data: Track 1 landed at ~54 in-scope files (track-1.md:223), more than double the ~20-25 ceiling, so folding ~4-6 more files in worsens the documented breach for no review gain; and no alternative seam exists — the subset-enumeration flip had to be atomic (D1), leaving the non-enumerating enforcement surfaces as the only separable unit, which is exactly this track.
**Evidence**: track-1.md:223 (~54 files); planning.md:589 (overlap-split rule), :598 (two-sided bound); the under-floor written justification at track-2.md:103 satisfies the argumentation gate.
**Proposed fix**: None — the decision holds. Footprint grows to ~5-6 files under A2/A4, still comfortably under the ~12 floor; the existing justification's logic is unchanged.

## Evidence base

**Group: SCOPE CHALLENGES (mandated challenge 1 — scope, sizing, decomposition)**

#### C1 Challenge: Track split — fold Track 2 into Track 1, or split differently
- **Chosen approach**: a second ~4-file track holding the two YTDB-1084 moves that do not enumerate the AI-tell subset, split off over-ceiling Track 1.
- **Best rejected alternative**: fold into Track 1 (the default for an under-floor track per planning.md:598's merge-candidate bound).
- **Counterargument trace**:
  1. In the fold scenario, Track 1's realized footprint (~54 in-scope files, track-1.md:223) grows by the 4-6 enforcement files, deepening a breach already double the ~20-25 ceiling.
  2. The split keeps the regex-calibration and cold-read-coverage review surface out of the atomic-flip review, whose focus (no surviving four-of-five enumeration) is orthogonal — Track 1's Phase C confirmed exactly that with 0 blockers.
  3. No third seam exists: D1's atomicity forbids splitting the enumeration flip, so the non-enumerating surfaces are the only separable unit.
- **Codebase evidence**: track-1.md:223; track-2.md:103; planning.md:589, :598.
- **Survival test**: YES — the split justification holds as realized. → A7

#### C2 Challenge: the ~4-file footprint misses a dsc-ai-tell enumeration site
- **Chosen approach**: in-scope roster of 4 files (design-review.md, design-mechanical-checks.py, test_dsc_ai_tell.py, readability-feedback/SKILL.md).
- **Best rejected alternative**: roster of 5 including `design-document-rules.md`.
- **Counterargument trace**:
  1. design-document-rules.md:284 enumerates the dsc-ai-tell patterns as a closed nine-item roster with `§` citations; adding two regexes falsifies both the count word "nine" and the closed list.
  2. `grep -rn 'dsc-ai-tell'` over `.claude/workflow|skills|agents`, excluding the script and its test, returns exactly that one site — it is the whole external sync surface, and the track's own target file (`readability-feedback/SKILL.md:48`, Rule sync map) names that table as a mandatory sync stop.
  3. Track 2's Phase C `review-workflow-consistency` pass reads cross-file and flags the nine-vs-eleven drift, costing a fix iteration.
- **Codebase evidence**: design-document-rules.md:284; readability-feedback/SKILL.md:48; grep sweep (one external site); design.md:229-233 Edge-cases list (site absent there too — frozen-design lag for Phase 4).
- **Survival test**: NO — the roster must grow. → A2

#### C3 Challenge: the applies-to line alone does not activate the new block
- **Chosen approach**: step 1 adds the block with its own applies-to line and syncs three sites (TOC row, `§ Tone and depth` count, Rule sync map row).
- **Best rejected alternative**: also wire the four activation/output points the sibling block uses.
- **Counterargument trace**:
  1. The Human-reader block carries an applies-to line (design-review.md:168) AND is pointed at from each mutation-kind bullet (:126, :135, :148) AND has an output-format home (:369-375). The file's convention is explicit per-path wiring, with the applies-to line as a secondary statement.
  2. For `target=tracks`, the §Track-scoped cold-read section (:182) enumerates which criteria apply (seven re-pointed comprehension questions, absorption, fidelity); a sibling block it never names has no inbound pointer on that path. The tracks direction is the half of D4 that motivated the both-targets decision.
  3. A reviewer following the per-kind or per-target instructions therefore never reaches the block's checks, and any findings it does produce have no defined output slot or evidence-tone instruction.
- **Codebase evidence**: design-review.md:126, :135, :148, :168, :182-201, :369-375.
- **Survival test**: WEAK — the TOC row makes the block discoverable, so "dead text" is not certain, but the wiring sites are real and unplanned. → A3

#### C4 Challenge: enforcement without rule text — the out-of-scope premise fails for two patterns
- **Chosen approach**: out-of-scope declares the house-style rules "already exist; this track adds enforcement, not new rule text" (track-2.md:97).
- **Best rejected alternative**: include 2 surgical house-style sentences in scope (or record a citation-convention amendment).
- **Counterargument trace**:
  1. Grep over house-style.md finds no rule text for inflated-abstraction labels: 'inflated', 'abstraction label' return nothing; § Nominalization (:166) bans placeholder nouns ("material", "data"), a different move from "the enabling primitive" as a grandiose definite label.
  2. The "X, not Y" shape is absent from Negative parallelism's enumerated variants (:120: "It's not X — it's Y", "It's not X, it's Y", "Not just A, but B", "You're not an X, you're a Y"); and "faux-symmetry" already names a different rule at :341 (inventing structure for balance).
  3. The dsc-ai-tell citation convention (design-document-rules.md:284: every finding cites `house-style.md § <Section>`) then has nothing valid to cite for the inflated-label regex, and the readability audit's CAUGHT-vs-GAP classification leaves inflated-label passages as GAPs — the same defect class WC1/step 4 repairs for `## Orientation`.
- **Codebase evidence**: house-style.md:116-128, :166-180, :341; design-document-rules.md:284; track-2.md:97.
- **Survival test**: NO — the boundary needs a scope amendment or a recorded convention change. → A4

#### C5 Challenge: "Internal order is free" is false for the regex/test pair
- **Chosen approach**: ordering note declares the block, regexes, and tests independent (track-2.md:60).
- **Best rejected alternative**: regexes and tests bound into one step.
- **Counterargument trace**:
  1. Tests for the two new regexes (item 3) reference regex constants that exist only after item 2; in the reverse order the test commit fails collection.
  2. In the stated order as separate commits, the regex commit lands untested, violating the step definition (one commit, fully tested).
- **Codebase evidence**: track-2.md:56-57, :60.
- **Survival test**: WEAK — decomposition can trivially repair this, but the note as written invites a broken step split. → A6

**Group: ASSUMPTION CHALLENGES (mandated challenge 2 — cross-track-episode reality, verified on the live tree)**

#### C6 Assumption test: "Track 1 flipped the readability-feedback governance grep"
- **Claim**: track-2.md:49 "The same file's governance grep (line 54) is flipped by Track 1"; :101 "(Track 1 flips its governance grep; …)".
- **Stress scenario**: inspect the live file and Track 1's realized record instead of the Phase-1 plan text.
- **Code evidence**: `git log` for `.claude/skills/readability-feedback/SKILL.md` shows one commit total (`2498508d6c`, develop) — no branch commit; SKILL.md:54 is four-string, byte-identical to conventions.md:572; track-1.md:196 (R1: greps unchanged, pointer-site enumerators) and the step-5 episode part F ("both governance greps confirmed unchanged and byte-identical") record the deliberate keep. Track 2's own step 4 (:58) says "kept four-string", contradicting :49/:101 inside one file.
- **Verdict**: BREAKS — the realized output the track describes does not exist; the track is internally contradictory about it. → A1

#### C7 Assumption test: "the WC1 gap survived into the file Track 2 edits"
- **Claim**: `readability-feedback/SKILL.md`'s audit prompt does not name `## Orientation`, so too-terse passages misroute as GAP; the fix lands at :36 and :70.
- **Stress scenario**: Track 1 (or a develop-side change) might already have closed the gap, making step 4 a no-op.
- **Code evidence**: `grep -n 'Orientation'` over the file returns zero matches; :36 is the GAP-routing target-section guidance (names Banned vocabulary / Banned sentence patterns / Banned analysis patterns / Structural rules / Document-shape rules, no Orientation) and :70 is the STEP 1 "Note especially" list (same omission). Both cited line numbers are exact on the live tree. `house-style.md:54` carries `## Orientation`, so the rule the fix points at exists (Track 1's output is real here).
- **Verdict**: HOLDS — step 4's premise and line anchors are correct as assumed.

#### C8 Assumption test: "the demotable calibration acceptance line can fail"
- **Claim**: acceptance bullet 5 (track-2.md:76) gates on "no blocker-severity false positive" during Phase-4 self-application.
- **Stress scenario**: regexes misfire heavily on conforming prose; does the bullet trip?
- **Code evidence**: dsc-ai-tell findings are emitted at should-fix (design-mechanical-checks.py:1847) with demote-to-suggestion the documented fallback (:105); `main()` exits 1 only when blockers > 0 (:2266, :2279). No dsc-ai-tell code path produces a blocker, so the bullet is true by construction at any false-positive rate. House-style.md's own prose uses the contrastive "X, not Y" shape at :80, :333, :347, :360 — the conforming corpus the regexes will run against is saturated with near-misses.
- **Verdict**: BREAKS — the acceptance line is vacuous; the calibration needs a checkable criterion. → A5

**Group: INVARIANT CHALLENGES (mandated challenge 3 — the three plan invariants)**

#### C9 Violation scenario: "Subset enumeration is uniform after Track 1"
- **Invariant claim**: no site enumerates the AI-tell subset as four-of-five after Track 1's Phase C; Track 2 must not create one.
- **Violation construction**:
  1. Start state: all 54 closed-set enumeration sites name five members (Track 1 Phase C verified, 0 blockers).
  2. Action sequence: Track 2's planned edits — the cold-read block (scans named rule sections, not the closed subset), two regexes (not an enumeration site, per track-1.md:196's three-file exclusion list which names design-mechanical-checks.py), test cases, SKILL.md:36/:70 additions (the STEP 1 list is a six-section house-style overview including Punctuation and Structural rules, not the closed AI-tell set; adding § Orientation cannot make it four-of-five), and the Rule sync map row (names design-review sync stops, not subset members).
  3. Violation point: none reachable from the planned edits. The only adjacent constructible hazard is the C6 side path: an implementer misled by :49 "flips" the SKILL.md:54 grep, breaking byte-identity with conventions.md:572 — a consistency break, though not a four-of-five enumeration, since R1 classified the greps as pointer-site enumerators outside the invariant's domain.
- **Feasibility**: INFEASIBLE as planned (the C6 side path is the A1 fix's concern, not an invariant breach).

#### C10 Violation scenario: "`## Orientation` exists before any enumeration names it"
- **Invariant claim**: the rule text lands with or before any site that points at it.
- **Violation construction**:
  1. Start state: house-style.md:54 carries `## Orientation` on the live tree now, before Track 2's first commit.
  2. Action sequence: Track 2 adds new pointers (the cold-read block's too-terse axis; SKILL.md:36/:70).
  3. Violation point: would require the rule to be absent at pointer-creation time; it is already present, and no Track 2 step touches house-style.md's Orientation section. (If A4's fix adds two house-style sentences, they extend other sections and do not move `## Orientation`.)
- **Feasibility**: INFEASIBLE.

#### C11 Violation scenario: "The opt-out disables staging only"
- **Invariant claim**: reviewer-criteria re-pointing stays on; staged-read precedence and the Phase-4 promotion guard find no staged subtree and skip.
- **Violation construction**:
  1. Start state: no `_workflow/staged-workflow/` directory exists (verified: `ls` fails); conventions.md §1.7(l) is live and the three Phase-3A prompts carry the opt-out trigger (adversarial-review.md:282 fired for this very review — the criteria switch demonstrably works).
  2. Action sequence: all four Track 2 steps write live paths under `.claude/workflow|scripts|skills`; none writes under `_workflow/staged-workflow/`.
  3. Violation point: would require a step to create a staged subtree or to suppress the criteria switch; no step does either.
- **Feasibility**: INFEASIBLE.
