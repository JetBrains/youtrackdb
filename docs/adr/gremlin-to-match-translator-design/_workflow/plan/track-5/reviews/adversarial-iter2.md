<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 1, suggestion: 3}
index:
  - {id: N1, sev: should-fix, loc: "plan/track-5.md:75,87 (Concrete Step 5 R6 list / Validation)", anchor: "### N1 ", cert: C1, basis: "the A1-blocker regression test — two NOT-differing shapes occupy distinct cache entries — is mandated only in Decision Log A1 prose (:32) and is absent from Step 5's enumerated R6 test list and from Validation & Acceptance, so the one test that proves the blocker fix is not bound to a Concrete Step and not caught by the Phase-B coverage gate (A1+A4 residual)"}
  - {id: N2, sev: suggestion, loc: "plan/track-5.md:9,68 (Big Picture / Plan of Work item 5)", anchor: "### N2 ", cert: C2, basis: "the stale 'post-walk generic-statement fingerprint (A3)' phrasing — the exact framing the A1 blocker warns against — survives in the Big Picture and Plan of Work item 5 even though Decision Log A1/R1-R2 and Concrete Step 5 correct it to 'synthesised from MatchPlanInputs, NOT toGenericStatement'"}
  - {id: N3, sev: suggestion, loc: "plan/track-5.md:73,85 (Concrete Step 3 / hasNot Validation)", anchor: "### N3 ", cert: C3, basis: "the values->properties PROPERTY-acceptance fix is correctly pinned but tagged '(R2/A2)' and '(R2)'; risk R2 is the fingerprint-source finding — the correct traceability tag is adversarial A2, mirroring the Track 4 rewrite Surprise"}
  - {id: N4, sev: suggestion, loc: "plan/track-5.md:30 (Decision Log A5)", anchor: "### N4 ", cert: C4, basis: "Decision Log A5 (:30) still reads 'the decomposer picks one and records which' — the exact open-choice phrasing iter-1 A3 flagged — while the later A3 pin (:33) supersedes it; the append-only log resolves it but the unpinned line carries no forward pointer"}
evidence_base: {section: "## Evidence base", certs: 4, matches: 4}
cert_index:
  - {id: C1, verdict: CONSTRUCTIBLE, anchor: "#### C1 "}
  - {id: C2, verdict: HOLDS, anchor: "#### C2 "}
  - {id: C3, verdict: HOLDS, anchor: "#### C3 "}
  - {id: C4, verdict: HOLDS, anchor: "#### C4 "}
flags: [CONTRACT_OK]
-->

**GATE VERDICT: PASS** — 0 blockers, 1 should-fix, 3 suggestions. The iteration-1 blocker A1 is genuinely resolved at the design level, and the reconciliation edits introduced no new blocker.

Adversarial re-review, iteration 2 — Track 5: Logical filters + plan cache (D5). This pass independently re-verifies each iteration-1 finding (A1 blocker + A2/A3/A4 should-fixes) against the current `track-5.md` text and against HEAD, and hunts for contradictions or new silent-wrong-result paths introduced by the risk/technical reconciliations (R1–R4, T1). Track 5 code is still not started (`## Progress` unchecked; no `*StepRecogniser` / `GremlinPlanCache` / `bindParam` on HEAD). PSI was not used this session; symbol claims rest on direct file reads and grep against HEAD (reference-accuracy caveat on caller enumeration).

**Iteration-1 finding resolution (all verified against HEAD):**
- **A1 (was blocker) — RESOLVED (design).** Decision Log A1 (`:32`) now pins the fingerprint to the full post-walk `MatchPlanInputs` (positive pattern + `notMatchExpressions` + alias filters + inline structural literals), explicitly "NOT from `SQLMatchStatement.toGenericStatement()`." C&O (`:61`) and Concrete Step 5 (`:75`) repeat the pin. HEAD confirms the omission the blocker rested on: `SQLMatchStatement.toGenericStatement()` iterates only `matchExpressions` (+ RETURN/GROUP/ORDER/UNWIND/SKIP/LIMIT), never `notMatchExpressions` (`SQLMatchStatement.java:451-505`), documented at `MatchStatementTest.java:275-276`. Residual: the one regression *test* is unbound (N1).
- **A2 — RESOLVED.** Step 3 (`:73`) pins acceptance of both `PropertyType.VALUE` and `PropertyType.PROPERTY` on the child `PropertiesStep`, mirroring `TraversalFilterStepRecogniser.presenceKey`; Validation (`:85`) pins the `values→properties` acceptance and Step 3 names `hasNot_propertiesChild_contributesIsNotDefined`. Cross-ref tag is mislabeled (N3).
- **A3 — RESOLVED.** Decision Log (`:33`) pins the edge-bearing NOT to a `MatchPatternBuilder.buildNotExpression(...)` sibling keeping D6 discipline (direct-AST demoted to a scoped fallback); Step 3 (`:73`) and Interfaces (`:106`) echo the pin. The older A5 open-choice line survives (N4).
- **A4 — RESOLVED.** Concrete Steps now bind test ownership: A7 decline-boundary → Step 1 (`:71`); A4 alias isolation → Step 2 (`:72`); A6 NOT shapes (incl. the two decline shapes via the eager-build net) → Step 3 (`:73`); R6 determinism → Step 5 (`:75`). One R6 member is missing from the binding (N1).
- **R1 — RESOLVED.** Structural tokens rendered verbatim: consistent with HEAD, where `SQLBaseExpression.toGenericStatement` collapses the *string* branch to `?` (`:112-113`) while `SQLIdentifier.toGenericStatement` appends `value` inline (`:141-142`). Plan flags the polymorphic pattern-node path as a decomposition confirmation.
- **R2 — RESOLVED.** Fingerprint taken pre-plan off insertion-ordered structures: `WalkerContext.aliasFilters` is a `LinkedHashMap` (`:40`), and `MatchExecutionPlanner` copies it into a plain `HashMap` (`:505`) — so the "before the HashMap copy" pin (Decision Log `:34`) is exactly right.
- **R3 — RESOLVED.** Total positional-param switch + shape-pure slot numbering pinned (Decision Log `:35`, Step 5 `:75`).
- **R4 — RESOLVED.** C&O (`:56`) now states `putAliasFilter` AND-composes; HEAD confirms — an existing same-alias entry is merged via `WHERE.and(...)`, not replaced (`WalkerContext.java:293-305`).
- **T1 — RESOLVED.** The recogniser→registry sub-walk seam is now enumerated in Interfaces (`:106`).

## Findings

### N1 [should-fix]
**Certificate**: C1 (violation scenario — the A1-blocker regression test is unbound)
**Target**: `## Concrete Steps` Step 5 R6 enumeration (`track-5.md:75`); `## Validation and Acceptance` (`:82-90`); Decision Log A1 (`:32`).
**Challenge**: The A1 fix has two halves — the design decision (fingerprint enumerates `notMatchExpressions`) and the regression test that proves it (two shapes with an identical positive pattern but differing `notMatchExpressions` must occupy distinct cache entries, e.g. `g.V().not(out("a"))` vs `g.V().not(out("b"))`, and NOT vs no-NOT). The design half is pinned. The test half is stated only in Decision Log A1 prose ("R6 tests must include a NOT-differing pair that must occupy distinct entries", `:32`) and is **absent** from both the places that actually gate it: Step 5's enumerated R6 list (`:75`) names `eq(null)` vs `eq(v)`, distinct `hasLabel` labels, `hasId` bypass, cross-walk stability, second-value multiset, collection-size classes, and schema invalidation — but no NOT-differing pair; and the Validation & Acceptance section (`:82-90`) covers value forks (`:87`), structural `hasLabel` forks (`:88`), and RID bypass (`:89`) but has no NOT-differing cache-entry line. This is the precise A1+A4 residual: iteration-1 A4's whole point was that a test must be bound to a Concrete Step to reach the Phase-B coverage gate; a test living only in a Decision Log sentence is not bound and not gated. The checklist item A1(c) — "a Validation case that two NOT-differing shapes occupy distinct cache entries" — is therefore not satisfied. Adversarial consequence: an implementer who enumerates `notMatchExpressions` into the fingerprint incorrectly (renders them through a path that placeholder-collapses their class/edge tokens, or forgets the list) reintroduces the exact silent-wrong-plan class the blocker named, and no bound test catches it.
**Evidence**: `track-5.md:75` (Step 5 R6 list — no NOT pair); `track-5.md:82-90` (Validation — no NOT-differing cache line); `track-5.md:32` (Decision Log A1 mandates it in prose only). HEAD: `SQLMatchStatement.toGenericStatement()` omits `notMatchExpressions` (`SQLMatchStatement.java:451-505`; `MatchStatementTest.java:275-276`) — the fingerprint's NOT enumeration is the load-bearing correctness step, so its test is not optional.
**Proposed fix**: Add the NOT-differing pair explicitly to Step 5's R6 enumeration and add a matching Validation & Acceptance line ("two traversals with the same positive pattern but differing `not(...)` sub-patterns — and NOT vs no-NOT — occupy distinct cache entries"), so the blocker's regression test is bound to Step 5 and reaches the coverage gate.

### N2 [suggestion]
**Certificate**: C2 (consistency — stale "generic-statement fingerprint" phrasing in narrative sections)
**Target**: Big Picture (`track-5.md:9`), Plan of Work item 5 (`:68`).
**Challenge**: The A1 blocker is that "the post-walk generic-statement fingerprint" naturally routes to `SQLMatchStatement.toGenericStatement()`, which omits `notMatchExpressions`. The reconciliation corrected the authoritative surfaces — Decision Log A1 (`:32`), Decision Log R1/R2 (`:34`), C&O (`:61`), and Concrete Step 5 (`:75`) all now say "synthesised from `MatchPlanInputs`, NOT `toGenericStatement()`." But the pre-reconciliation phrase "post-walk generic-statement fingerprint (A3)" still survives verbatim in the Big Picture (`:9`) and in Plan of Work item 5 (`:68`), the item that Concrete Step 5 refines. Neither line says "`SQLMatchStatement.toGenericStatement()`", so this is loose phrasing rather than a directive to use the omitting method, and the operative Concrete Step is correct — hence suggestion, not should-fix. But the surviving phrase is the exact framing the blocker warns against, and a reader who stops at item 5 without reaching the Decision Log could re-invite the trap. (The two older 2026-07-15 log entries at `:22` and `:28` carry the same phrase but are append-only Surprise/Decision-Log history superseded by the 07-20 entries, so leave them.)
**Evidence**: `track-5.md:9`, `:68` (surviving "generic-statement fingerprint"); corrected at `:32`, `:34`, `:61`, `:75`.
**Proposed fix**: Rephrase lines 9 and 68 to "the value-independent fingerprint synthesised from the post-walk `MatchPlanInputs` (pattern + `notMatchExpressions` + alias filters; not `SQLMatchStatement.toGenericStatement()`)" so all non-log surfaces agree with the Decision Log.

### N3 [suggestion]
**Certificate**: C3 (traceability — mislabeled cross-reference on the A2 fix)
**Target**: Concrete Step 3 (`track-5.md:73`), hasNot Validation line (`:85`).
**Challenge**: The A2 should-fix (accept `PropertyType.PROPERTY` on the `hasNot` child, mirroring the Track 4 `values→properties` rewrite) is correctly pinned — but tagged `(R2/A2)` in Step 3 and `(R2)` in the Validation line. Risk-iteration-1 R2 is the *fingerprint-source-on-the-additive-path* finding; the values→properties rewrite is adversarial-iteration-1 **A2** (and the Track 4 Surprise), not risk R2. The design content is right; only the traceability tag is wrong, which matters in this workflow because decomposition and later reviews trace requirements by tag. This mirrors iteration-1's own T2 (drifted citations).
**Evidence**: `track-5.md:73` ("— R2/A2"), `:85` ("(R2)"); risk-iter1 R2 basis (fingerprint source); adversarial-iter1 A2 (values→properties acceptance).
**Proposed fix**: Retag both references to `(A2)` (optionally "A2 / Track 4 Surprise 2026-07-16"), dropping the erroneous `R2`.

### N4 [suggestion]
**Certificate**: C4 (consistency — superseded open choice left without a forward pointer)
**Target**: Decision Log A5 (`track-5.md:30`) vs Decision Log A3 pin (`:33`).
**Challenge**: Iteration-1 A3 flagged that A5 read "the decomposer picks one and records which," leaving the edge-bearing-NOT builder unpinned. The fix added the 07-20 A3 entry (`:33`) pinning the `buildNotExpression(...)` sibling and demoting direct-AST to a scoped fallback — a correct resolution. But the original A5 line (`:30`) still ends with "the decomposer picks one and records which," the exact open-choice phrasing A3 was raised against. The append-only Decision Log convention means the later entry supersedes, and A3 is dated later, so this is not a true contradiction — but a reader scanning the log top-down hits the unpinned line first with no forward pointer to the pin.
**Evidence**: `track-5.md:30` ("the decomposer picks one and records which"); `:33` (A3 pin superseding). HEAD: `MatchPatternBuilder.build()` returns positive `PatternIR` only, no detached-NOT emitter today (technical-iter1 P-patternbuilder), so the pin is real work.
**Proposed fix**: Append a short forward pointer to A5 ("— superseded by the 2026-07-20 A3 pin below: `buildNotExpression(...)` sibling") so the log's earlier line does not read as still-open.

## Evidence base

#### C1 CONSTRUCTIBLE — the A1-blocker regression test is not bound to a Concrete Step
- **Invariant claim**: A1(c) — the plan carries a Validation case that two NOT-differing shapes occupy distinct cache entries, gated at Phase B.
- **Violation construction**:
  1. Decision Log A1 (`:32`) mandates "R6 tests must include a NOT-differing pair" — prose only.
  2. Step 5 R6 enumeration (`:75`) lists seven R6 cases; none is a NOT-differing pair. Validation (`:82-90`) has no NOT-differing cache line.
  3. Per iteration-1 A4's rule (tests reach the Phase-B gate only when bound to a Concrete Step), the NOT test is unbound → not gated.
  4. Observable: an implementer can render the fingerprint's `notMatchExpressions` incorrectly (collapse their tokens, or omit the list) and pass every enumerated test — the A1 silent-wrong-plan class reappears with no gate.
- **Feasibility**: CONSTRUCTIBLE — the omission is textual and directly checkable; the HEAD `toGenericStatement()` omission (`SQLMatchStatement.java:451-505`; `MatchStatementTest.java:275-276`) makes the NOT enumeration the load-bearing correctness step whose test is missing.
- **Verdict**: should-fix (design pinned; test binding incomplete — the blocker's *design* is resolved, so not a re-opened blocker, consistent with iteration-1 rating the same test-binding class as A4 should-fix).

#### C2 HOLDS — stale "generic-statement fingerprint" phrasing in Big Picture / Plan of Work
- **Claim**: all narrative surfaces agree the fingerprint is synthesised from `MatchPlanInputs`, not `toGenericStatement()`.
- **Code/text evidence**: corrected at `:32` (Decision Log A1: "NOT from `SQLMatchStatement.toGenericStatement()`"), `:34`, `:61`, `:75`; stale at `:9` and `:68` ("post-walk generic-statement fingerprint (A3)"). HEAD: `SQLMatchStatement.toGenericStatement()` omits `notMatchExpressions` (`:451-505`), so the phrase names the trap.
- **Verdict**: HOLDS — loose-phrasing residue in two non-log surfaces; operative Concrete Step is correct → suggestion.

#### C3 HOLDS — A2 fix tagged with the wrong review-finding id
- **Claim**: the values→properties acceptance is attributable to the finding that discovered it.
- **Text evidence**: `:73` ("accepting both `PropertyType.VALUE` and `PropertyType.PROPERTY` … — R2/A2"), `:85` ("(R2)"). Risk-iter1 R2 = fingerprint source on the additive path; adversarial-iter1 A2 = the `hasNot` values→properties rewrite. HEAD: `TraversalFilterStepRecogniser` accepts both property types for `has(key)` presence (technical-iter1 P-notstep-final / Track 4), the pattern the fix mirrors.
- **Verdict**: HOLDS — content correct, tag wrong → suggestion.

#### C4 HOLDS — superseded builder-choice line retains open-choice wording
- **Claim**: the edge-bearing-NOT builder choice is pinned with no residual "decomposer picks" ambiguity.
- **Text evidence**: `:30` (A5, 2026-07-15: "the decomposer picks one and records which"); `:33` (A3, 2026-07-20: pins `buildNotExpression(...)`, direct-AST demoted to fallback). Append-only Decision Log → later entry governs. HEAD: `MatchPatternBuilder.build()` emits positive `PatternIR` only (technical-iter1 P-patternbuilder), so the pin is genuine new work.
- **Verdict**: HOLDS — resolved by the log convention; earlier line lacks a forward pointer → suggestion.

## HEAD re-verification of the reconciliation anchors

| Reconciliation | Plan claim | HEAD check | Result |
|----------------|-----------|-----------|--------|
| A1 | `toGenericStatement()` omits `notMatchExpressions` | `SQLMatchStatement.java:451-505` iterates `matchExpressions` only; `MatchStatementTest.java:275-276` documents it | CONFIRMED |
| R1 | string tokens collapse to `?`; identifiers inline | `SQLBaseExpression.java:112-113` (string → `PARAMETER_PLACEHOLDER`); `SQLIdentifier.java:141-142` (appends `value`) | CONFIRMED |
| R2 | pre-plan off insertion-ordered map; planner copies to `HashMap` | `WalkerContext.java:40` `LinkedHashMap`; `MatchExecutionPlanner.java:505` `new HashMap<>(inputs.aliasFilters())` | CONFIRMED |
| R4 | `putAliasFilter` AND-composes, not overrides | `WalkerContext.java:293-305` merges via `WHERE.and(...)` | CONFIRMED |
| R2/T4 | additive path leaves `statement==null`, `useCache=false` | `MatchExecutionPlanner.java:488-495` caching-precondition javadoc | CONFIRMED |
| T1 | sub-walk seam enumerated in Interfaces | `track-5.md:106` names the `RecognitionContext` sub-walk entry point / shared driver | CONFIRMED |

**New-blocker sweep:** none. The verbatim-structural-token rule (R1) and the value-`?` rule (R3) are internally consistent (value forms → `?`, structural tokens → verbatim). The RID double-treatment (RIDs listed both as verbatim fingerprint discriminators at `:34`/`:75` and as cache-bypass at `:75`) is defense-in-depth given `SQLRid`'s conditional placeholder rendering (risk-iter1 R6), not a contradiction. The "post-walk" (Decision Log A1) vs "pre-plan" (C&O / Step 5) wording denotes the same moment (after the walk produces `MatchPlanInputs`, before the planner's `HashMap` copy) and is consistent.
