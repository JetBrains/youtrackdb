<!-- MANIFEST
findings: 8   severity: {blocker: 1, should-fix: 3, suggestion: 4}
index:
  - {id: A1, sev: blocker,    loc: "plan/track-4.md:43", anchor: "### A1 ", cert: C3, basis: "eq(null) -> bare IS NULL over-matches absent properties; planned validation line tests only the null-valued case, so the wrong multiset ships unseen"}
  - {id: A2, sev: should-fix, loc: "plan/track-4.md:44", anchor: "### A2 ", cert: C4, basis: "absent-property divergence audit names only gt/gte/lt/lte; every negated form (without, not* TextP, P.not) shares the true-on-absent over-match"}
  - {id: A3, sev: should-fix, loc: "plan/track-4.md:56", anchor: "### A3 ", cert: C7, basis: "multi-label hasLabel(L1,L2) arrives as one within-container; classEquals takes one name and MatchWhereBuilder.in has the plain-identifier @class trap — behavior unspecified"}
  - {id: A4, sev: should-fix, loc: "plan/track-4.md:56", anchor: "### A4 ", cert: C6, basis: "non-polymorphic hasLabel as classEquals WHERE over the V-rooted node = full V scan; re-typing the node via addNode's documented class overwrite narrows the scan"}
  - {id: A5, sev: suggestion, loc: "plan/track-4.md:25", anchor: "### A5 ", cert: C5, basis: "R1's 'IR may not re-type' branch is moot — addNode overwrites className by documented merge semantics; SQLInstanceofCondition is an unlisted subclass-inclusive alternative"}
  - {id: A6, sev: suggestion, loc: "plan/track-4.md:52", anchor: "### A6 ", cert: C8, basis: "multi-container iteration invites putAliasFilter-then-decline; the invariant rescope (pre-split A7) is parked in Track 5, which lands after this track"}
  - {id: A7, sev: suggestion, loc: "plan/track-4.md:97", anchor: "### A7 ", cert: C9, basis: "R2 collate transform changes GQL CONTAINSTEXT on ci properties; the plan's GqlMatchStatement-unchanged invariant needs a recorded carve-out"}
  - {id: A8, sev: suggestion, loc: "plan/track-4.md:91", anchor: "### A8 ", cert: C1, basis: "footprint recount lands at 14-19 files around the ~16 claim; inside both soft bounds, no Track-5 leakage; +2 contingency if R2 consistency checks become edits"}
evidence_base: {section: "## Evidence base", certs: 9, matches: 3}
cert_index:
  - {id: C1, verdict: SURVIVES, anchor: "#### C1 "}
  - {id: C2, verdict: HOLDS, anchor: "#### C2 "}
  - {id: C3, verdict: CONSTRUCTIBLE, anchor: "#### C3 "}
  - {id: C4, verdict: FRAGILE, anchor: "#### C4 "}
  - {id: C5, verdict: WEAK, anchor: "#### C5 "}
  - {id: C6, verdict: CONSTRUCTIBLE, anchor: "#### C6 "}
  - {id: C7, verdict: BREAKS, anchor: "#### C7 "}
  - {id: C8, verdict: CONSTRUCTIBLE, anchor: "#### C8 "}
  - {id: C9, verdict: SURVIVES, anchor: "#### C9 "}
flags: [CONTRACT_OK]
-->

Adversarial review, iteration 1 (regenerated post-A1-split) — Track 4: Filtering, predicates only. Narrowed to track realization (D9): scope/sizing of the split-down track, cross-track-episode reality of Tracks 1–3 outputs, invariant violation in the planned steps. Inline design decisions are not re-challenged; the split (pre-split A1) is not re-raised. This file supersedes the pre-split adversarial review (preserved in git history).

**Tooling caveat:** mcp-steroid PSI (`steroid_execute_code`) times out this session. All symbol claims rest on direct file reads at HEAD, grep, and `javap` disassembly of the fork jar (`io.youtrackdb:gremlin-core:3.8.1-af9db90-SNAPSHOT`). Grep can miss polymorphic call sites; each certificate that depends on caller enumeration carries the caveat inline.

## Findings

### A1 [blocker]
**Certificate**: C3 (violation scenario — `eq(null)` over-match)
**Target**: Track construction — `## Context and Orientation` NULL-semantics bullet ("`P.eq(null)` … rewrite to `field IS NULL`") and the matching `## Validation and Acceptance` line.
**Challenge**: `has(k, P.eq(null))` translated to a bare `k IS NULL` returns a strictly larger multiset than native. Native `HasContainer.test(Element)` iterates `element.properties(key)` and returns `false` when the iterator is empty — an absent property fails *every* predicate, including `eq(null)` (fork-jar bytecode: the plain-key branch returns 0 on empty iterator). YTDB's `IS NULL` evaluator conflates absent and literal-null (`MatchWhereBuilder.isNull` Javadoc: "expression.execute() == null, so document stores conflate 'property absent' and 'property set to literal null'"). Vertex A with `nickname = null` and vertex B without `nickname`: native returns {A}; the planned translation returns {A, B}. The validation line as written ("`eq(null)` / `neq(null)` against a null-valued property match native") exercises only the null-valued case, so the planned tests would not catch the wrong multiset. `neq(null)` → `IS NOT NULL` is fine (both sides exclude absent).
**Evidence**: `HasContainer.test` disassembly (fork jar); `MatchWhereBuilder.isNull` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchWhereBuilder.java:221-238`; the adapter's own `neq` precedent already applies the guard for exactly this native-excludes-absent reason (`GremlinPredicateAdapter` comment: "HasContainer.test iterates element.properties(key) and returns false for an absent …").
**Proposed fix**: Translate `eq(null)` as `k IS DEFINED AND k IS NULL` (both factories shipped in Track 1). Extend the validation line to assert the absent-property case: a vertex lacking the key is excluded by both pipelines.

### A2 [should-fix]
**Certificate**: C4 (assumption test — divergence-audit completeness)
**Target**: `## Context and Orientation` absent-property bullet — "audits the other comparisons (`gt` / `gte` / `lt` / `lte`) for the same divergence".
**Challenge**: The audit list is enumerated by operator name and misses the class of predicates that provably shares `neq`'s defect: every *negated* form evaluates true on an absent left operand. `Contains.without` → `SQLNotInCondition` (NOT of a false IN → true on absent); `TextP.notContaining` / `notStartingWith` / `notEndingWith` / `notRegex` built as `MatchWhereBuilder.not(<positive form>)` (NOT of false → true); `P.not(<pred>)` likewise. Native excludes absent rows for all of them (same empty-iterator return in `HasContainer.test`). The four range operators, by contrast, likely agree already (comparison on null → false), so the enumerated audit inspects where the divergence probably isn't and skips where it is.
**Evidence**: `HasContainer.test` disassembly (absent → false, predicate-independent); `MatchWhereBuilder.not` exists (`MatchWhereBuilder.java:288`); Plan of Work step 4 names the `not*` text variants with no guard mention.
**Proposed fix**: Restate the audit as a rule, not a list: any predicate whose translated SQL evaluates true when the property is absent gets the `IS DEFINED` guard — at minimum every `not`-composed form plus `neq` (done) and `eq(null)` (A1). Add one negated-predicate-on-absent-property case to the validation lines.

### A3 [should-fix]
**Certificate**: C7 (assumption test — multi-label `hasLabel`)
**Target**: Plan of Work step 2 (`~label` branch) + Validation line "`hasLabel` / `hasId` (single + multi)".
**Challenge**: `hasLabel(L1, L2)` arrives as one `~label` container carrying `P.within([L1, L2])`, and the planned branch routes `~label` to `MatchWhereBuilder.classEquals`, which takes a single class name and throws on a blank one. Nothing in the track says what happens to the within-shaped container: `classEquals` cannot express it, `MatchWhereBuilder.in("@class", …)` is the exact plain-`SQLIdentifier` trap the T1 fix documented for `~id` (a plain identifier `@class` is read as an absent property — `classEquals` deliberately builds a `SQLRecordAttribute` left side instead), and polymorphic mode has no single class to re-type to. Meanwhile the validation line's "(single + multi)" reads as promising multi-label coverage. A decomposer can neither build it nor cleanly decline it from the current text.
**Evidence**: `classEquals` single-name contract and `SQLRecordAttribute` left side (`MatchWhereBuilder.java:65-76`); `in(...)` plain-identifier left side (`MatchWhereBuilder.java:99` via `fieldExpression`); TinkerPop folds multi-label into a single within-container (reference-accuracy caveat: asserted from TinkerPop semantics plus T1's single-`HasStep` finding, not PSI).
**Proposed fix**: Pin the multi-label behavior before decomposition. Cheapest: within-label containers decline in Track 4 (D3 whole-traversal fallback) and the validation line's "(single + multi)" is scoped to `hasId`. If translation is wanted: non-polymorphic → a record-attribute `@class IN [...]` (the `buildRidInExpression` pattern with `@class`); polymorphic → OR of `SQLInstanceofCondition` (see A5).

### A4 [should-fix]
**Certificate**: C6 (violation scenario — non-polymorphic `hasLabel` scan width)
**Target**: Plan of Work step 2 — non-polymorphic `~label` → `MatchWhereBuilder.classEquals` on the alias filter.
**Challenge**: The boundary node is registered at the generic root (`ctx.addNode(alias, VERTEX_ROOT_CLASS)` — class `V`). Attaching `@class = 'L'` as a WHERE clause filters correctly but does not narrow the *scan*: the planner iterates all of `V` and rejects rows, while native non-polymorphic `hasLabel` (the `YTDBGraphStepStrategy` fold) iterates only the target class. On a large multi-class graph the translated query reads every vertex to return a leaf class — a regression exactly on the track's headline predicate. `MatchPatternBuilder.addNode` already documents merge-on-re-register ("className overwrites the existing class when non-null/non-blank"), so re-typing the node to `L` alongside the exact `@class = 'L'` filter (hierarchy scanned, subclasses filtered out) is a one-call fix.
**Evidence**: `WalkerContext.addNode` → `patternBuilder.addNode(alias, className, null, false)` (`WalkerContext.java:208-211`); `MatchPatternBuilder.addNode` overwrite semantics (`MatchPatternBuilder.java:62-99`); grep found no `@class`-equality-to-scan-class inference in `MatchExecutionPlanner` (reference-accuracy caveat: grep, PSI down — if such inference exists, this finding drops to suggestion).
**Proposed fix**: The non-polymorphic branch re-types the node's class to `L` *and* attaches `classEquals(L)`; the equivalence test gains a scan-shape assertion (`explain()` shows the narrowed class, mirroring the existing `g.V(id)` direct-RID acceptance line).

### A5 [suggestion]
**Certificate**: C5 (decision challenge — R1's re-typing conditional and an unlisted alternative)
**Target**: Decision Log entry R1/T6 (mode-gated `hasLabel`), branch (b) "if the IR cannot re-type (unverifiable now — PSI down this session), fall back to decline-to-native".
**Challenge**: Both halves of the conditional are resolvable now. (1) The IR re-types by documented design: `MatchPatternBuilder.addNode` merge semantics overwrite the class on re-registration, so the polymorphic `{class:L}` path needs no new IR capability. (2) The rejected-alternative search turned up infrastructure the decision never lists: `SQLInstanceofCondition` evaluates `clazz.isSubClassOf(name)` — subclass-inclusive narrowing as a WHERE predicate, no re-typing needed, and the only construction that serves polymorphic *multi*-label (A3). The decline fallback is therefore harsher than the code requires: between "re-type" and "decline whole traversal" sits a third, already-built option.
**Evidence**: `MatchPatternBuilder.java:62-99`; `SQLInstanceofCondition` (`core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLInstanceofCondition.java:39-96`, `isSubClassOf` in both eval paths, `copy`/`equals`/`hashCode` complete).
**Proposed fix**: Strengthen the Decision Log: the re-typing branch is the expected path (capability confirmed by code read, pending only the Phase B native-membership pin); name `SQLInstanceofCondition` as the fallback before decline-to-native. The chosen re-typing approach survives — it narrows the scan where INSTANCEOF cannot — but the recorded rationale under-sells the option space.

### A6 [suggestion]
**Certificate**: C8 (invariant challenge — no-mutation-on-decline under multi-container iteration)
**Target**: Plan invariant "No-mutation-on-decline: a recognizer that returns `false` leaves `WalkerContext` unmutated (per-recognizer unit invariant)".
**Challenge**: `HasStepRecogniser` iterates several containers and AND-composes contributions; the natural loop calls `putAliasFilter` per container and can hit an untranslatable container mid-list → mutate-then-DECLINE. Production survives (Track 3's rework discards the whole context on decline — `WalkerContext` class Javadoc), but the plan invariant, immutable during execution, demands per-recogniser unit-level non-mutation and would fail its unit test. The rescoping finding that reconciles the invariant with the discard model (pre-split A7) is parked in Track 5 — which lands *after* this track, so Track 4's decomposer faces the stale wording first.
**Evidence**: `WalkerContext.java:22-27` (discard-on-decline Javadoc) vs `implementation-plan.md` §Invariants (still phrased against the retired `boolean recognize` contract); track `## Outcomes & Retrospective` (pre-split A7 distributed to Track 5).
**Proposed fix**: One sentence in Plan of Work step 2: translate all containers to expressions first, contribute via a single `putAliasFilter` only after every container translates (a decline then costs zero mutations). Cheap, satisfies the invariant's letter, leaves the Track 5 rescope untouched.

### A7 [suggestion]
**Certificate**: C9 (violation scenario — plan invariant vs the R2 collate transform)
**Target**: Plan invariant "`GqlMatchStatement` observable behavior is unchanged …" vs Decision Log R2 (intentional CONTAINSTEXT behavior change on `ci` properties).
**Challenge**: GQL text-contains routes through the same shared `SQLContainsTextCondition` node, so the Track 4 collate transform changes GQL observable behavior on `ci`-collated properties — a literal reading of the invariant is violated by a planned, user-approved step. The invariant's *testable assertion* ("its existing tests pass with the same assertions") survives — had existing GQL tests covered `ci` CONTAINSTEXT, R2's blast-radius analysis would have flagged them — so this is a prose-coherence gap, not a blocker: a Phase C reviewer reading the plan invariant against the diff sees a contradiction with no recorded resolution.
**Evidence**: `SQLContainsTextCondition.java:32-98` (four raw-`indexOf` eval paths, shared node); plan §Invariants; track Decision Log R2 (change recorded as intentional, flagged for Phase C).
**Proposed fix**: Add the carve-out to the track's currently-empty `## Invariants & Constraints`: the GQL-unchanged invariant is scoped to the Track 1 builder refactor; Track 4's R2 collate transform intentionally changes shared-node semantics on `ci` properties (Decision Log R2), covered by the default-unchanged regression guard.

### A8 [suggestion]
**Certificate**: C1 (scope challenge — footprint recount)
**Target**: `> **Scope:** ~16 files` (plan checklist Track 4 entry) and the split's sizing outcome.
**Challenge**: Recount from `## Interfaces and Dependencies` against the real tree: 3 new production files (`HasStepRecogniser`, `TraversalFilterStepRecogniser`, `SQLEndsWithCondition`), 8 modified production files (`GremlinPredicateAdapter`, `GremlinStepWalker` registry, `StartStepRecogniser` helper extraction, `WalkerContext`, `RecognitionContext`, `MatchWhereBuilder`, `SQLMatchesCondition`, `SQLContainsTextCondition`), 3–6 test classes (predicate-equivalence, NULL/collection, string-predicate, AST round-trip, CONTAINSTEXT regression, polymorphic-hierarchy equivalence) = 14–17. The R2 blast-radius language ("check the legacy `QueryOperatorContainsText` and the fulltext-index path stay collation-consistent") converts to +2 files if the checks become edits — the legacy operator is raw case-sensitive `indexOf` today, so an edit is the likely outcome → 16–19. Both soft bounds hold: no fold-back (≥14 > ~12 floor) and no re-split (≤19 < ~20–25 ceiling). No moved-out work smuggled back: Plan of Work's "composite `P.and/or/not`" is predicate algebra (`ConnectiveP` values inside one `has`), not the step-level logical filters Track 5 owns; no `hasNot` / sub-walker / D5 reference remains.
**Evidence**: File-by-file existence checks in C2; `implementation-plan.md:415-419` (scope line); the A3/A4 fixes stay inside already-counted files.
**Proposed fix**: None required for sizing. Optionally annotate the scope line "~16 (+2 if R2 consistency requires editing `QueryOperatorContainsText` / the fulltext path)" so the decomposer isn't surprised at the ceiling check.

## Evidence base

#### C1 SURVIVES — Scope challenge: post-split footprint sizing
- **Chosen approach**: ~16-file predicate-only track after the A1 split.
- **Best rejected alternative**: fold back into a neighbor (if under the ~12 floor) or re-split (if the realized footprint again overshoots the ~20–25 ceiling).
- **Counterargument trace**: enumerated every named in-scope artifact against the tree (A8): 14–17 nominal, 16–19 with the R2 consistency contingency. Neither bound is threatened. The seam held — grep over `plan/track-4.md` finds logical-filter / `hasNot` / sub-walker / D5 tokens only inside explicit "moved to Track 5" statements.
- **Codebase evidence**: existence checks in C2; `QueryOperatorContainsText` raw `indexOf` (grep; reference-accuracy caveat: grep).
- **Survival test**: YES — sizing and seam both hold.

#### C2 HOLDS — Assumption test: Tracks 1–3 realized outputs exist as the track assumes
- **Claim**: every cross-track artifact the track builds on exists in the shape described.
- **Stress scenario**: the track was reconciled to a 14-commit unreviewed rework; any assumed symbol could have drifted.
- **Code evidence** (direct file reads at HEAD unless noted):
  - `GremlinPredicateAdapter` skeleton: exists; `@Nullable SQLBooleanExpression toFilter(HasContainer)`; declines blank/reserved keys and non-`Compare` predicates; already emits the `IS DEFINED AND <>` guard for `neq`.
  - Contract `Outcome recognize(StepCursor, RecognitionContext)`: implemented by `StartStepRecogniser.java:87`; registry is `Map.of(GraphStep.class → StartStepRecogniser, VertexStep.class → VertexStepRecogniser)` at `GremlinStepWalker.java:90-93` — Track 4's two registrations are additive edits there.
  - `MatchWhereBuilder`: `classEquals` (65), `in` (99), `between` (121), `containsText` (133), `startsWith` (152, throws on empty prefix), `and`/`or`/`andOptional` (172–219), `isNull` (229), `isDefined` (263), `isNotDefined` (276), `not` (288), `wrap` (299). `endsWith` / `matchesRegex` absent — this track's work, matching the plan's Track-1 deferral note.
  - `classEquals` production callers: none (grep — Javadoc mentions and `MatchWhereBuilderTest` only; reference-accuracy caveat: grep, PSI down). Matches the episode claim.
  - `StartStepRecogniser.buildRidInExpression` (228, `SQLRecordAttribute` left + literal list) and `normaliseIds` (166, `toRecordId` loop + duplicate-decline at 179–183) — the two R5 seams are real, currently private statics.
  - `WalkerContext.putAliasFilter` overwrites (`aliasFilters.put`, 236–238) — the AND-composition change is genuinely pending, as the track states; `isReservedHasKey` (150–154) confirms `~`/`@` keys decline in the adapter, so the `~label`/`~id` interception must indeed run first.
  - `promoteStaticRidsFromFilters` (`MatchExecutionPlanner.java:4758`) promotes via `findRidInList` → `findRidInListInExpression`, which recurses into nested AND blocks (`SQLWhereClause.java:953-975`, `1128-1130`) and is non-destructive — the track's own AND-composition rule does **not** break the `g.V(id).has(k,v)` direct-RID fast path. Checked because it was the likeliest self-inflicted invariant break; it holds. (OR blocks are descended only when single-child, so future OR composition blocks promotion — a Track 5 concern.)
  - Fork jar (javap): `GraphStep` implements `GraphStepContract`, which extends `Step`/`GraphComputing`/`AutoCloseable` — **not** `HasContainerHolder`; no `HasIdStep` / `HasLabelStep` class exists in the jar; `TraversalFilterStep` is `final`; `GraphTraversal.has(String)` bytecode constructs `TraversalFilterStep(__.values(key))` exactly as the track states. `YTDBHasLabelStep.java` exists in-tree (class-exists / instance-absent wording is accurate).
  - `QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT` defaults `true` (`GlobalConfiguration.java:945-950`) — the R1 premise is accurate.
  - `EdgeTraversalEquivalenceTest` and `GremlinPredicateAdapterTest` exist (the fixtures the Interfaces section depends on).
- **Verdict**: HOLDS — no stale cross-track assumption found.

#### C3 CONSTRUCTIBLE — Violation scenario: `eq(null)` translated to bare `IS NULL`
- **Invariant claim**: translator-on and translator-off produce equal result multisets for every RECOGNIZED shape.
- **Violation construction**:
  1. Start state: class `Person`; vertex A `nickname = null` (literal), vertex B with no `nickname` property.
  2. Action sequence: `g.V().has("nickname", P.eq(null))` with the translator on, translated per the track's NULL-semantics bullet to `nickname IS NULL`.
  3. Intermediate state: native path — `HasContainer.test(B)` → `properties("nickname")` empty → `false` (fork-jar bytecode: plain-key branch returns 0 on empty iterator); A → `testValue` → `eq(null).test(null)` → true. Native multiset {A}.
  4. Violation point: translated `nickname IS NULL` — evaluator `expression.execute() == null` is true for both A and B (`MatchWhereBuilder.java:221-227` Javadoc). Translated multiset {A, B}.
  5. Observable consequence: wrong (over-large) result multiset; the planned validation line (null-valued property only) never exercises B, so tests pass.
- **Feasibility**: CONSTRUCTIBLE — ordinary data, default config.

#### C4 FRAGILE — Assumption test: the gt/gte/lt/lte audit list covers the absent-property divergence
- **Claim**: auditing the four range comparisons closes the absent-property gap beyond the handled `neq`.
- **Stress scenario**: any negated predicate on a vertex lacking the key — `has(k, P.without(a))`, `has(k, TextP.notContaining(s))`, `has(k, P.not(P.eq(a)))`.
- **Code evidence**: native excludes absent rows for all predicates (C3's bytecode trace is predicate-independent). Translated: `SQLNotInCondition` / `MatchWhereBuilder.not(positive)` evaluate NOT(false) = true on a null left operand → over-match, the same mechanism the adapter already guards for `neq`. The range operators on null likely return false (agreeing with native), so the enumerated audit spends effort where divergence probably isn't and skips the family where it provably is.
- **Verdict**: FRAGILE — the intent (audit divergence) is right; the enumeration misses the negated class.

#### C5 WEAK — Challenge: Decision R1 branch (b) — "IR may not re-type; fall back to decline"
- **Chosen approach**: polymorphic `hasLabel` re-types the boundary node to `{class:L}` if the IR permits, else declines to native; feasibility marked unverifiable (PSI down).
- **Best rejected alternative (unlisted)**: `SQLInstanceofCondition` WHERE predicate — subclass-inclusive via `isSubClassOf`, no IR change, composable under OR for multi-label.
- **Counterargument trace**:
  1. Re-typing feasibility is answerable by code read: `MatchPatternBuilder.addNode` documents "className overwrites the existing class when non-null/non-blank" on re-registration (`MatchPatternBuilder.java:62-67, 97-99`), and `WalkerContext.addNode` passes className straight through — `ctx.addNode(boundaryAlias, "L")` re-types today.
  2. Where re-typing cannot express the shape (two labels, one node), `SQLInstanceofCondition` evaluates `clazz.isSubClassOf(right)` in both eval paths (`SQLInstanceofCondition.java:39-96`) — the decline fallback skips an already-built middle option.
  3. Outcome: the decision's conditional protects against a risk that doesn't exist, and its worst-case branch (whole-traversal decline in the default config) is harsher than the code requires.
- **Codebase evidence**: as above. INSTANCEOF full-scans over the V root (no scan narrowing), so re-typing stays the right primary — the challenge targets the fallback, not the choice.
- **Survival test**: WEAK — decision survives; rationale needs the two facts recorded.

#### C6 CONSTRUCTIBLE — Violation scenario: non-polymorphic `hasLabel` full-V scan
- **Invariant claim**: performance conformance implied by the track's own `g.V(id)` no-class-scan acceptance line — translated shapes take comparably-narrow access paths to native.
- **Violation construction**:
  1. Start state: 1M vertices across many classes; class `L` has 100 instances; session non-polymorphic.
  2. Action sequence: `g.V().hasLabel("L")` per Plan of Work step 2 — node stays at root class `V` (`StartStepRecogniser` → `ctx.addNode(alias, VERTEX_ROOT_CLASS)`), filter `@class = 'L'` attached via `classEquals`.
  3. Intermediate state: MATCH plan scans class `V` polymorphically — the node's class drives the scan; grep finds no `@class`-filter-to-scan-class promotion in `MatchExecutionPlanner` (caveat: grep, PSI down).
  4. Violation point: 1M rows read, 100 returned; native `YTDBGraphStepStrategy` fold iterates only `L`.
  5. Observable consequence: correct results, orders-of-magnitude slowdown on the track's headline predicate.
- **Feasibility**: CONSTRUCTIBLE — default schema shapes; capped at should-fix because results stay correct and the fix (re-type + filter, C5 fact 1) is one call.

#### C7 BREAKS — Assumption test: the `~label` branch handles what `hasLabel` actually produces
- **Claim**: routing `~label` containers to `classEquals` covers the `hasLabel` surface the validation lines promise.
- **Stress scenario**: `g.V().hasLabel("Person", "Company")` — one container, key `~label`, predicate `P.within([Person, Company])` (TinkerPop folds multi-label into a single within-container; caveat: asserted from TinkerPop semantics plus T1's single-`HasStep` finding, not PSI).
- **Code evidence**: `classEquals(String)` accepts exactly one concrete name (`MatchWhereBuilder.java:65-70`); `MatchWhereBuilder.in` builds a plain-`SQLIdentifier` left side (`fieldExpression`, `MatchWhereBuilder.java:99/364`) — for `@class` the identical absent-property trap T1 fixed for `~id`; polymorphic mode has no single re-type target. The track specifies no within-label path and no decline.
- **Verdict**: BREAKS — the planned branch cannot dispatch a legal, common input; pin decline-or-construct before decomposition.

#### C8 CONSTRUCTIBLE — Violation scenario: per-container contribution then decline
- **Invariant claim**: plan §Invariants — "a recognizer that returns `false` leaves `WalkerContext` unmutated (per-recognizer unit invariant)".
- **Violation construction**:
  1. Start state: `g.V().has("age", 30).has("x", customP)` — after `InlineFilterStrategy` folding (an Optimization-category strategy, so it runs before this ProviderOptimization translator), one `HasStep` with two containers.
  2. Action sequence: a naive `HasStepRecogniser` loop translates container 1 → `putAliasFilter(alias, ageFilter)`; container 2 → adapter declines (custom bi-predicate).
  3. Intermediate state: context holds the age filter; recogniser returns DECLINE.
  4. Violation point: the unit invariant's assertion (context unmutated after decline) fails — though production is safe: the walker discards the whole context on decline (`WalkerContext.java:22-27`, "a partial contribution never leaks").
  5. Observable consequence: unit-invariant test failure or a silently-skipped invariant; the reconciling rescope (pre-split A7) lives in Track 5, which executes after this track.
- **Feasibility**: CONSTRUCTIBLE at unit level; zero production impact — hence suggestion. Collect-then-commit closes it for free.

#### C9 SURVIVES — Violation scenario: GQL-unchanged invariant vs the R2 collate transform
- **Invariant claim**: plan §Invariants — "`GqlMatchStatement` observable behavior is unchanged after the builder refactor (its existing tests pass with the same assertions)".
- **Violation construction**: GQL text-contains on a `ci`-collated property compiles to the shared `SQLContainsTextCondition`; Track 4's collate transform flips its evaluation from raw case-sensitive `indexOf` (`SQLContainsTextCondition.java:42/63/78/97`) to collation-aware — observable GQL behavior changes on `ci` properties by design (Decision Log R2).
- **Feasibility**: the *prose* violation is constructible; the *testable assertion* survives — existing GQL tests cannot be covering `ci` CONTAINSTEXT case behavior (they would fail post-transform, and R2's blast-radius analysis found none), and the "after the builder refactor" clause reads the invariant as Track-1-scoped. SURVIVES on the assertion, with a recorded-carve-out fix (A7) to keep Phase C coherent.
