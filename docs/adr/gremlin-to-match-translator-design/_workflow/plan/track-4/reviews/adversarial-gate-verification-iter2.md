<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: A9, sev: suggestion, loc: "plan/track-4.md:25", anchor: "### A9 ", cert: "#### Verify A3", basis: "residual hasLabel prose drift across three spots: Decision Log last-resort clause vs A3's by-construction multi-label decline; C&O ~label sentence predates the A3/A4 fixes; explain() narrowed-scan assertion vs the non-narrowing INSTANCEOF fallback"}
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
  - {id: A5, verdict: VERIFIED}
  - {id: A6, verdict: VERIFIED}
  - {id: A7, verdict: VERIFIED}
  - {id: A8, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

Adversarial gate verification, iteration 2 — Track 4: Filtering, predicates only. All eight iteration-1 findings (A1 blocker, A2–A4 should-fix, A5–A8 suggestion) verify against the fixed track file and plan. One new suggestion-level finding (A9): the interrelated hasLabel edits left three spots of residual prose drift. The blocker is resolved; the gate passes.

**Tooling caveat:** mcp-steroid PSI (`steroid_execute_code`) times out this session (per the spawn note). Code-fact re-checks (`MatchWhereBuilder` factories, `MatchPatternBuilder.addNode` overwrite Javadoc) used grep over HEAD source; grep can miss polymorphic call sites, but no verdict here depends on caller enumeration — only on declaration existence and Javadoc text, which grep answers exactly.

## Findings

### A9 [suggestion]
**Certificate**: regression checks under Verify A3 / A4 / A5 below.
**Target**: three spots the interrelated hasLabel edits left in mild mutual drift — Decision Log R1/T6 (`plan/track-4.md:25`), the C&O `~label` orientation sentence (`plan/track-4.md:48`), and Validation line 2 (`plan/track-4.md:70`).
**Challenge**: the normative spots (Plan of Work step 2, Decision Log primary path, Validation) agree on single-label re-typing and multi-label decline, but three secondary sentences read against them. (1) The Decision Log's "Decline-to-native is a last resort (used only if the Phase B pin surfaces a shape neither re-typing nor INSTANCEOF matches)" collides with PoW step 2's by-construction multi-label decline: a multi-label within-container IS a shape OR-of-`SQLInstanceofCondition` could match, yet Track 4 declines it under A3 — a reader applying the last-resort rule literally would translate it. (2) The C&O `~label` sentence predates the A3/A4 fixes: it routes `~label` → `classEquals` when non-polymorphic with no re-typing mention and no single-`eq(L)` restriction, and hedges polymorphic mode as "must decline or emit a subclass-inclusive predicate" where the Decision Log now pins re-typing (a pattern-node change, not a predicate) as the expected path. (3) Validation line 70's first sentence asserts the narrowed scan via `explain()` for `hasLabel(L)` unconditionally, while its second sentence permits the `SQLInstanceofCondition` WHERE fallback — which does not narrow the scan (iter-1 C5: INSTANCEOF full-scans the V root), so if Phase B pins the fallback the assertion as written cannot pass.
**Evidence**: `plan/track-4.md:25` (last-resort clause), `:48` (C&O sentence, quoted stale in this file's Verify A3 regression check), `:56` (PoW step 2 — the corrected normative text), `:70` (the two validation sentences); iter-1 C5 (INSTANCEOF has no scan narrowing).
**Proposed fix**: one alignment pass — scope the Decision Log's last-resort clause to single-label (or cross-reference the A3 multi-label decline); rewrite the C&O `~label` clause to match PoW step 2 (single-`eq(L)` only, re-typing in both modes, multi-label declines); scope the `explain()` narrowed-scan assertion to the re-typing paths.

## Evidence base

#### Verify A1: `eq(null)` translated to bare `IS NULL` (blocker)
- **Original issue**: `eq(null)` → bare `IS NULL` over-matches absent properties ({A,B} vs native {A}); the validation line tested only the null-valued case, so the wrong multiset would ship unseen.
- **Fix applied**: C&O NULL-semantics bullet (`plan/track-4.md:43`) now mandates `field IS DEFINED AND field IS NULL`, "**not** a bare `field IS NULL` (A1)", carrying the over-match mechanism (`MatchWhereBuilder.isNull` conflation vs native empty-iterator false) and the vertex-A/vertex-B counterexample. Validation (`plan/track-4.md:72`) now asserts `eq(null)` / `neq(null)` match native "on both a null-valued property AND an **absent** property (a vertex lacking the key is excluded by both pipelines — A1)".
- **Re-check**:
  - Track-file location: `plan/track-4.md:43` and `:72`.
  - Current state: the guarded form's semantics are correct — absent property: `isDefined` false → excluded (matches native); null-valued: defined AND `execute()==null` → included (matches native). Both factories exist at HEAD: `MatchWhereBuilder.isNull` (`MatchWhereBuilder.java:229`), `isDefined` (`:263`) — grep-confirmed, declaration-existence only so the caveat is immaterial.
  - Criteria met: the equivalence invariant (translator-on = translator-off multisets) is restored for the `eq(null)` shape, and the validation line now exercises the case that would have hidden the bug.
- **Regression check**: checked the A2 rule bullet (`:44`) — it cross-references `eq(null)` as covered by the guard rule, coherent; `neq(null)` → `IS NOT NULL` untouched and still correct (both sides exclude absent). Clean.
- **Verdict**: VERIFIED

#### Verify A2: absent-property audit misses the negated-predicate class
- **Original issue**: the divergence audit enumerated `gt`/`gte`/`lt`/`lte` by name and skipped every negated form (`without`, `not*` TextP, `P.not`), which provably share the true-on-absent over-match.
- **Fix applied**: C&O bullet (`plan/track-4.md:44`) restated as a rule — "any predicate whose translated SQL evaluates *true* on an absent property gets the `IS DEFINED` guard" — naming `Contains.without` → `SQLNotInCondition`, the `TextP` `not*` variants, and `P.not(...)` via `MatchWhereBuilder.not`, plus `neq` (already guarded) and `eq(null)` (A1); the four range comparisons demoted to audit-to-confirm. Validation (`:72`) adds "a negated predicate (`without` / `notContaining` / `P.not(...)`) likewise excludes absent rows, matching native (A2)".
- **Re-check**: rule form covers the class, not the list; the named forms match the C4 mechanism (NOT of false → true on absent).
- **Regression check**: checked `:74` (`not*` variants "translate and match native") — consistent with the guard rule. Clean.
- **Verdict**: VERIFIED

#### Verify A3: multi-label `hasLabel` within-container unspecified
- **Original issue**: `hasLabel(L1,L2)` arrives as one within-container that neither `classEquals` (single name) nor `MatchWhereBuilder.in("@class",…)` (plain-identifier trap) can build; the track neither built nor declined it, while the validation's "(single + multi)" implied coverage.
- **Fix applied**: PoW step 2 (`plan/track-4.md:56`) — "A `~label` container is handled **only when it carries a single `eq(L)`**"; multi-label within-containers **decline** under D3 with both unbuildable constructions named and record-attribute `@class IN` / OR-of-`SQLInstanceofCondition` noted as possible follow-ups. Validation (`:69`) scopes "(single + multi)" to `hasId` and appends "(multi-label `hasLabel` declines — A3)".
- **Re-check**: the decomposer now has a pinned disposition for every `~label` shape; the validation promise matches it.
- **Regression check**: two residual drifts — the Decision Log's last-resort clause (`:25`) reads against the by-construction decline, and the C&O `~label` sentence (`:48`) still lacks the single-`eq(L)` restriction ("`~label` → `MatchWhereBuilder.classEquals` **only when `ctx.polymorphic()` is false** (… polymorphic mode must decline or emit a subclass-inclusive predicate)"). Normative spots outvote them; logged as A9 (suggestion).
- **Verdict**: VERIFIED

#### Verify A4: non-polymorphic `hasLabel` full-V scan
- **Original issue**: attaching `@class = 'L'` as WHERE over the V-rooted node filters correctly but scans all of V; native non-polymorphic `hasLabel` iterates only the target class.
- **Fix applied**: PoW step 2 (`plan/track-4.md:56`) — `~label` narrows "by **re-typing the boundary node's class** via `MatchPatternBuilder.addNode`'s documented className-overwrite (so the scan is narrowed to `L`, not a full `V` scan that rejects rows in a WHERE — A4)"; non-polymorphic re-types to `L` and attaches exact `classEquals(L)` (hierarchy scanned, subclasses filtered out), polymorphic re-types to `{class:L}`. Validation (`:70`) adds the `explain()` scan-shape assertion mirroring the `g.V(id)` direct-RID line.
- **Re-check**: `MatchPatternBuilder.addNode` overwrite Javadoc confirmed at HEAD (`MatchPatternBuilder.java:65`, `:77`: "className overwrites the existing class when non-null/non-blank") — the one-call fix the finding proposed is exactly what the step now specifies, and re-type + exact filter preserves non-polymorphic multiset semantics.
- **Regression check**: the unconditional `explain()` narrowed-scan assertion sits against the non-narrowing INSTANCEOF fallback the same validation line permits — folded into A9. Construction itself clean.
- **Verdict**: VERIFIED

#### Verify A5: R1 branch (b) hedge and the unlisted INSTANCEOF alternative
- **Original issue**: the Decision Log conditioned the polymorphic path on "if the IR cannot re-type (unverifiable now)" with decline as the fallback, though re-typing feasibility was answerable by code read and `SQLInstanceofCondition` was an unlisted middle option.
- **Fix applied**: Decision Log R1/T6 (`plan/track-4.md:25`) now states the MATCH IR **can** re-type ("`MatchPatternBuilder.addNode` overwrites the class on re-registration by documented merge semantics — A5, confirmed by code read, so `{class:L}` needs no new IR capability"), names `SQLInstanceofCondition` (`clazz.isSubClassOf(L)`) as the already-built subclass-inclusive alternative and the only form serving polymorphic multi-label, and demotes decline-to-native to "a last resort …, not the expected polymorphic outcome".
- **Re-check**: no "unverifiable" hedge remains anywhere in the track file (grep clean); the overwrite Javadoc re-confirmed (Verify A4).
- **Regression check**: the strengthened last-resort clause is one of the A9 drift spots (it now reads against A3's by-construction multi-label decline). The decision content itself is correct and complete.
- **Verdict**: VERIFIED

#### Verify A6: putAliasFilter-then-decline under multi-container iteration
- **Original issue**: a per-container contribution loop can mutate `WalkerContext` then decline mid-list, failing the plan's per-recogniser no-mutation-on-decline unit invariant (whose rescope is parked in Track 5, which lands later).
- **Fix applied**: PoW step 2 (`plan/track-4.md:56`, final sentence) — "translates every container to an expression **first** and contributes via a single `putAliasFilter` only after all containers translate, so an untranslatable container declines with zero `WalkerContext` mutation (A6 — satisfies the no-mutation-on-decline invariant at the unit level, independent of the production discard-on-decline path)".
- **Re-check**: collect-then-commit closes the C8 construction exactly; the plan invariant (`implementation-plan.md:306-307`) is satisfiable at unit level without waiting for the Track 5 rescope.
- **Regression check**: checked against the C&O same-alias AND-composition rule (`:52`) — no conflict: AND-composition governs cross-recogniser contributions inside `putAliasFilter`; collect-then-commit governs intra-recogniser container batching before the single call. Clean.
- **Verdict**: VERIFIED

#### Verify A7: GQL-unchanged invariant vs the R2 collate transform
- **Original issue**: the plan's `GqlMatchStatement`-unchanged invariant is literally violated by the planned R2 collate change on the shared `SQLContainsTextCondition`; no recorded carve-out for a Phase C reviewer.
- **Fix applied**: `## Invariants & Constraints` (`plan/track-4.md:102`) now carries the carve-out: the invariant is scoped to the Track 1 builder refactor; Track 4's R2 transform intentionally changes GQL/SQL `CONTAINSTEXT` on `ci` properties (Decision Log R2); the testable assertion still holds (no existing GQL test covers `ci` `CONTAINSTEXT`); the `default`-collated regression guard pins the no-change case.
- **Re-check**: the carve-out quotes the plan invariant accurately (`implementation-plan.md:312-313`: "unchanged after the builder refactor (its existing tests pass with the same assertions)") and states why it was recorded (Phase C reviewer finds the resolution, not a contradiction).
- **Regression check**: coherent with Decision Log R2 (`:26`) and the split default-unchanged / ci-changed validation line (`:78`). Clean.
- **Verdict**: VERIFIED

#### Verify A8: scope-line annotation for the R2 contingency
- **Original issue**: recount landed at 14–19 files around the "~16" claim; the +2 R2 contingency (`QueryOperatorContainsText` / fulltext path edits) was unannotated, risking decomposer surprise at the ceiling check.
- **Fix applied**: plan checklist Track 4 entry (`implementation-plan.md:415-416`) now reads "~16 files (+2 if the R2 collation-consistency checks require editing `QueryOperatorContainsText` / the fulltext path)".
- **Re-check**: matches the proposed annotation; both soft bounds still hold (≥14 > ~12 floor, ≤19 < ~20–25 ceiling).
- **Regression check**: Track 5's scope line (`:436`) untouched, as expected. Clean.
- **Verdict**: VERIFIED

## Summary

PASS — all eight iteration-1 findings VERIFIED, including the A1 blocker. One new suggestion (A9, residual hasLabel prose drift across the Decision Log last-resort clause, the C&O `~label` sentence, and the `explain()` assertion scope); it does not gate.
