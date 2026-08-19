<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 2, suggestion: 3}
index:
  - {id: T12, sev: should-fix, loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:5, anchor: "### T12 ", cert: C25, basis: "the track title, Purpose, and three plan-file sentences still promise a JMH baseline that 7a and acceptance moved out of track"}
  - {id: T13, sev: should-fix, loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:64, anchor: "### T13 ", cert: C21, basis: "item 2 consumes the 3a seam but is numbered ahead of it; the seam's no-overwrite guarantee stops short of setResultShaping"}
  - {id: T14, sev: suggestion, loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:71, anchor: "### T14 ", cert: C24, basis: "4a's gate is blanket over all four ops while its own rationale limits divergence to fold/tail; no acceptance line pins the choice"}
  - {id: T15, sev: suggestion, loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:111, anchor: "### T15 ", cert: C22, basis: "the T10 javadoc list names AbstractMatchPlanStep.shaping, already current, and omits WalkerContext.shaping, which the 3a seam invalidates"}
  - {id: T16, sev: suggestion, loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:87, anchor: "### T16 ", cert: C26, basis: "tail(n) selects by arrival order while the branch invariant is multiset equality; nothing pins translated row order to native traversal order"}
verdicts:
  - {id: T1, verdict: STILL OPEN}
  - {id: T2, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
  - {id: T4, verdict: VERIFIED}
  - {id: T5, verdict: VERIFIED}
  - {id: T6, verdict: VERIFIED}
  - {id: T7, verdict: VERIFIED}
  - {id: T8, verdict: STILL OPEN}
  - {id: T9, verdict: VERIFIED}
  - {id: T10, verdict: VERIFIED}
  - {id: T11, verdict: VERIFIED}
overall: FAIL
evidence_base: {section: "## Evidence base", certs: 6, matches: 4}
cert_index:
  - {id: C21, verdict: CONFIRMED, anchor: "#### C21 "}
  - {id: C22, verdict: PARTIAL, anchor: "#### C22 "}
  - {id: C23, verdict: CONFIRMED, anchor: "#### C23 "}
  - {id: C24, verdict: CONFIRMED, anchor: "#### C24 "}
  - {id: C25, verdict: CONFIRMED, anchor: "#### C25 "}
  - {id: C26, verdict: PARTIAL, anchor: "#### C26 "}
flags: [CONTRACT_OK]
-->

# Track 9 technical review — gate verification, iteration 2

Nine of eleven fixes landed as proposed and survive the cascade check. T1 is **STILL OPEN**: the prose was rewritten in all three places the finding named, but the mermaid diagram inside `## Context and Orientation` still draws `fold → BoundaryOutputType.LIST → extend projectOrSkip switch + drain stage`, and line 39 still calls the new stage the "`LIST` drain". T8 is **STILL OPEN** on its third clause only — the GQL witness and the `rebindFilters` constraint landed, the file estimate did not. Overall verdict **FAIL** on those two.

The T1 mechanism swap did not break the neighbouring claims. The `ListShapingOp` drain satisfies the `union(...).fold()` acceptance line under the new 4a child gate (C24), `ResultShaping.withListShapingOps` has exactly the shape 3a's append implementation needs (C21), and no other track claim presupposes the dropped enum constant. Five new findings, none of them blockers: two cascade residues from accepted fixes, one under-specified gate scope, one factual error the amendments inherited from my own iteration-1 T10 body, and one order-dependence premise that predates this pass.

**Tooling caveat.** `steroid_execute_code` times out on this repository, so every symbol check below used `find` / `grep` / `sed` over the working tree. Reference accuracy is high for the declaration reads (`ResultShaping`, `ListShapingOp`, the three "seven flags" javadocs, `UnionStepRecogniser`'s agreement loop — all read end-to-end). The one negative that carries weight, "`WalkerContext:121` is the only stale seven-flags site the amendment missed", rests on a repository-wide grep for `seven flags` / `seven row-projection` across `core/src`, which returned exactly three hits.

## Verification certificates

#### Verify T1: `BoundaryOutputType.LIST` is the wrong mechanism
- **Original issue**: item 2 pinned `fold()` to a new `BoundaryOutputType.LIST` constant plus a `projectOrSkip` `LIST` arm, which a per-row projector cannot express.
- **Fix applied**: item 2 rewritten to register a `ListShapingOp` drain; `## Context and Orientation` trap (1) rewritten as the reason not to add the constant; `## Interfaces and Dependencies` "In scope (new)" now says "**no** `BoundaryOutputType` constant and no `projectOrSkip` arm (T1)"; the plan's scope note and Track 9 table row updated.
- **Re-check**:
  - Track-file locations: line 33 (orientation lead), line 35 (trap 1), line 61 (item 2), line 87 (acceptance), lines 110–111, 113 (interfaces), plus `implementation-plan.md:663` and `:693`.
  - Current state: all six prose sites are correct and mutually consistent. Line 87 states the acceptance criterion as "without adding a `BoundaryOutputType` constant". `## Purpose / Big Picture` never mentioned the constant.
  - **Two sites still carry the old mechanism.** The mermaid diagram at line 47 is unchanged: `Fold["fold → BoundaryOutputType.LIST"] --> Switch["extend projectOrSkip switch\n+ drain stage"]`. That diagram sits inside `## Context and Orientation`, one of the three sections the proposed fix named. Line 39 still reads "the base class is exactly where this track's `LIST` drain / flat-map / ring-buffer stages land" — the backticked `LIST` is the enum-constant name, not a description of the output.
  - Criteria met: the mechanism criterion is met in prose and unmet in the diagram, so the track now contradicts itself. A decomposer reading the diagram first would build the arm the rest of the file forbids.
- **Regression check**: checked `## Purpose / Big Picture`, `## Validation and Acceptance`, `## Interfaces and Dependencies`, and both plan-file sites (C23). Clean apart from the two named above.
- **Verdict**: STILL OPEN — delete the `Fold`/`Switch` nodes from the mermaid (or redraw them as `fold → ListShapingOp drain → applyListShaping`), and drop the `LIST` qualifier from line 39.

#### Verify T2: no `RecognitionContext` seam to append a list-shaping op
- **Original issue**: recognisers could only call `setResultShaping`, which rebuilds from `NONE` and has no reader, so `values("name").fold()` wipes the presence flags and `reverse().unfold()` cannot compose two ops.
- **Fix applied**: sub-item 3a added, naming `void appendListShapingOp(@Nonnull ListShapingOp op)` on `RecognitionContext`, a `WalkerContext` append implementation, the `setResultShaping` javadoc update, and an explicit `SubTraversalPredicateAdapter` decline. `## Interfaces and Dependencies` gained the seam under "In scope (new)" and all three classes under "In scope (modified)".
- **Re-check**:
  - Locations: track line 64 (3a), lines 110–111.
  - Current state: the seam is specified with the preferred signature, both motivating shapes, and the adapter decision. `ResultShaping.withListShapingOps(@Nonnull List<ListShapingOp> ops)` exists at `ResultShaping.java:106` and replaces the whole list, so the append implementation is `shaping = shaping.withListShapingOps(<existing + op>)` exactly as the finding assumed (C21). `WalkerContext.shaping()` at line 583 is package-private, as the amendment states; `SubTraversalPredicateAdapter.setResultShaping` at line 397 is the documented no-op the amendment describes.
  - Criteria met: the seam exists in the plan, its implementation target is real, and the three consequence sites are scoped.
- **Regression check**: checked the union path, where `UnionStepRecogniser` still calls `setResultShaping(agreedShaping)` before any suffix op appends (C24) — no conflict. Checked whether the seam creates ordering hazards with the other terminators: none reachable under D3's last-step rule. Two smaller gaps produce T13.
- **Verdict**: VERIFIED

#### Verify T3: union child carrying a list-shaping op
- **Original issue**: `union(__.out().fold(), __.in().fold())` accepts or declines depending on whether the ops are singletons or fresh instances, so correctness rides an implementation detail.
- **Fix applied**: sub-item 4a added with the explicit non-empty-`listShapingOps` decline; acceptance bullet at line 89; two decline cases in item 5; `UnionStepRecogniser` added to "In scope (modified)".
- **Re-check**:
  - Locations: track line 71 (4a), line 74 (item 5), line 89 (acceptance), line 111.
  - Current state: 4a reproduces the mechanism accurately against `UnionStepRecogniser.java:106-111`, which I re-read — `agreedShaping = childResult.shaping()` on the first child and `!agreedShaping.equals(childResult.shaping())` on the rest, with nothing else inspecting `listShapingOps`. The acceptance bullet requires the decline be "witnessed by an explicit non-empty-`listShapingOps` gate rather than inferred from op reference identity", which is the operative half.
  - Criteria met: yes.
- **Regression check**: confirmed the child gate does not collide with the `union(...).fold()` suffix acceptance line (C24). One scope ambiguity produces T14.
- **Verdict**: VERIFIED

#### Verify T4: `FoldStep` also carries the seeded reduce
- **Original issue**: registering `FoldStep` unconditionally would translate `fold(seed, operator)` as a list drain.
- **Fix applied**: item 2 gained "**declines when `!step.isListFold()`**" plus the two-constructor evidence; item 5 gained the decline case; acceptance bullet at line 88.
- **Re-check**: item 2's restatement of the `javap` evidence matches C7 verbatim in substance — two constructors, a `listFold` boolean, `isListFold()` accessor, and D9's concrete-class keying making one registry entry claim both. The acceptance bullet states the observable outcome (native's summed scalar), not just the internal gate.
- **Regression check**: checked that the decline propagates correctly under D3 all-or-nothing — a declining terminator declines the whole traversal, which is what "produces native's summed scalar" requires. Clean.
- **Verdict**: VERIFIED

#### Verify T5: `unfold`'s contract is five-way
- **Original issue**: the track described `unfold` as expanding a list, missing the `Map` → `entrySet()`, array, and scalar arms.
- **Fix applied**: sub-item 3b pins all five arms with the `MAP`-is-live rationale; the `ListShapingOp` javadoc correction is named; item 5 gained the payload-shape group; acceptance line 87 now says "across all five `UnfoldStep.flatMap` arms, `Map` → `entrySet()` and scalar → one-element included".
- **Re-check**: 3b's five arms match C7. `ListShapingOp.java:22` still reads "{@code unfold} expands a list payload into its elements", so the javadoc target is real and still stale. Item 5 lists `groupCount().unfold()`, `valueMap().unfold()`, an array payload, and a scalar payload.
- **Regression check**: line 33's older phrase "`unfold()` flat-maps per emission (needs a cross-call pending-emission buffer, not just a flag)" is still there and is not contradicted by 3b — it describes the buffering requirement, not the dispatch. Clean.
- **Verdict**: VERIFIED

#### Verify T6: `ListShapingOp`'s once-per-child javadoc clause is false
- **Original issue**: the carrier javadoc claims the base rebuilds its shaped iterator once per child plan, which no code path implements and which contradicts item 4.
- **Fix applied**: sub-item 4b added; `ListShapingOp` added to "In scope (modified)".
- **Re-check**: `ListShapingOp.java` lines 32–36 still carry "after a {@code reset()} and reopen, and once per child plan for a multi-plan boundary". 4b names exactly that parenthetical, states the replacement (one arming spans the concatenation; a re-arm rebuilds), and preserves the surrounding buffer advice. Matches the finding.
- **Regression check**: 3b and 4b both edit `ListShapingOp`'s javadoc, in different clauses (the `unfold` bullet at line 22 versus the re-open paragraph at line 32). Not a double-claim, but a decomposer that splits items 3 and 4 into separate steps will touch one file twice. Worth one sentence at decomposition; not a finding.
- **Verdict**: VERIFIED

#### Verify T7: re-arm and clone are unasserted
- **Original issue**: `applyListShaping` re-invokes `op.apply` on every open and `AbstractStep.clone()` shares `shaping` by reference, and item 5 covered neither.
- **Fix applied**: item 5 gained the re-arm group (both the `DRAINED` and `CLOSED` routes, naming `REARMED_AFTER_CLOSE`) and the clone group (naming `MultiPlanMatchStepTest`'s idiom); acceptance bullet at line 90.
- **Re-check**: both groups are present with the mechanism stated, and the acceptance bullet mirrors them. The `REARMED_AFTER_CLOSE` route name matches the seven-constant enum recorded in C1.
- **Regression check**: the clone paragraph's cross-reference was rewritten from "(see T3)" to "(4a)", which points at the right sub-item in the new numbering. Clean.
- **Verdict**: VERIFIED

#### Verify T8: item 1a's blast radius reaches GQL
- **Original issue**: three clauses — (a) add a GQL witness, (b) name the fix site and say how the post-optimization strip survives, (c) revise the file estimate and add the GQL files to `## Interfaces and Dependencies`.
- **Fix applied**: item 1a gained the GQL blast-radius paragraph and the `rebindFilters` constraint; acceptance bullets at lines 96 and 97; `GqlMatchPatternAssembler` and the Track 1 GQL prettyPrint tests added to "In scope (modified)".
- **Re-check**:
  - (a) landed — the paragraph names the shared `MatchExecutionPlanner(pattern, aliasClasses, aliasFilters)` entry and the witness, and line 96 makes it an acceptance criterion.
  - (b) landed — the `MatchExecutionPlanner:2064` citation is accurate; I re-read lines 2055–2064 and the `rebindFilters(aliasFilters)` call carries the `detectNotInAntiJoin()` comment word for word. Line 97 makes the strip survival an acceptance criterion, and item 1a names the pattern-edge-items option.
  - (c) **not applied.** `implementation-plan.md:669` still reads "item 1a is a known member costing roughly 3–5 files (`MatchPatternBuilder`, the filter-binding site the fix lands on, and the equivalence tests that do not currently witness it), which puts the track at ~18–26 files". The GQL witness, `GqlMatchPatternAssembler`, and the Track 1 prettyPrint regression tests are not in that count. The Scope line above it was edited for T1 but its "~15–21 files" figure was left as-is while Phase A added seven work items across five more production files (`RecognitionContext`, `WalkerContext`, `SubTraversalPredicateAdapter`, `UnionStepRecogniser`, `ListShapingOp`) plus a new `jmh-ldbc/src/test` class.
  - The count matters beyond bookkeeping: `implementation-plan.md:640-642` records the forward risk that "Track 9 at ~18–26 files … could be the third" track past the ~4,000-line review-burden threshold, with ESCALATE as the response. That trigger reads the stale number.
- **Regression check**: the two new acceptance bullets do not conflict with the existing item-1a bullet at line 93. Clean.
- **Verdict**: STILL OPEN on clause (c) only — revise both figures in `implementation-plan.md:663-671` to reflect the Phase A additions, so the ESCALATE trigger reads a current count.

#### Verify T9: the JMH baseline is not capturable locally
- **Original issue**: item 7 promised a captured baseline while the track's own clarification says every gate is verified locally, and `jmh-ldbc` has no self-contained fixture graph.
- **Fix applied**: sub-item 7a splits the deliverable into in-track (classes, harness, a JUnit installation check in `jmh-ldbc/src/test`) and Hetzner-scoped (the numbers); the throwing-check requirement is stated; acceptance line 95 says the numbers are not a gate.
- **Re-check**: 7a's facts hold. `jmh-ldbc/src/test` contains `LdbcQueryCorrectnessTest` and `LdbcQueryExplainTest`, so the named home is real. `GremlinToMatchStrategy` reads `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` at lines 336–338, matching the cited `:338`. Acceptance line 95 states which half gates the track.
- **Regression check**: the split left the track title, `## Purpose / Big Picture`, and three plan-file sentences still promising a baseline (C25). That produces T12.
- **Verdict**: VERIFIED

#### Verify T10: the Pre-Flight constant count and three stale javadocs
- **Original issue**: the clarification recorded three `BoundaryOutputType` constants where there are four, and three sibling javadocs are stale.
- **Fix applied**: the clarification bullet now lists all four with `SCALAR` attributed to Track 6 and the correction attributed to T10; the three javadoc touch-ups were added to "In scope (modified)".
- **Re-check**: the constant correction is right — `BoundaryOutputType.java` declares `ELEMENT`, `MAP`, `SINGLE_VALUE`, `SCALAR`, and its class javadoc lists all four while the opening sentence names only `YTDBMatchPlanStep`, exactly as T10 said. `UnionStepRecogniser`'s class javadoc still reads "the list-shaping terminators ({@code fold} and friends) are not translated yet". **The third javadoc member is wrong**: `AbstractMatchPlanStep.java:153-160` already reads "the seven row-projection flags … plus the ordered list-shaping ops applied to the projected payload stream afterward ({@link ResultShaping#listShapingOps()} …)", so it is current and needs no edit. The site that is stale and unlisted is `WalkerContext.java:121` (C22). The error originates in my own iteration-1 T10 body; the amendment carried it faithfully.
- **Regression check**: `ResultShaping`'s own class javadoc is also current ("the seven … flags plus the ordered list-shaping post-process"). No fourth stale site.
- **Verdict**: VERIFIED on the substantive correction; the javadoc list needs the swap described in T15.

#### Verify T11: `getLimit()` pins the GValue before returning
- **Original issue**: reading the limit and then declining on `n<0` leaves the traversal's GValue manager mutated.
- **Fix applied**: sub-item 3c states the pinning behaviour, the Track 6 precedent, the D9 reading, and the two options; item 3's main text now cites `TailGlobalStepContract.CONCRETE_STEPS` as the registration source of truth.
- **Re-check**: 3c reproduces C20's trace faithfully and leaves the choice to the decomposer with a "record why" requirement, which is what a suggestion-severity finding asked for. The `CONCRETE_STEPS` citation matches C5.
- **Regression check**: 3c does not contradict item 3's `n<0 → decline` rule; it constrains how the limit is read before the check. Clean.
- **Verdict**: VERIFIED

## Findings

### T12 [should-fix]
**Certificate**: C25 (JMH-baseline framing across the two files)
**Location**: Track 9 title (line 2), `## Purpose / Big Picture` (line 5); `implementation-plan.md:643`, `:646-650`, `:680`

**Issue**: T9's fix moved the baseline numbers out of track and said so in acceptance line 95 — "the baseline **numbers** are Hetzner-scoped and not a gate on this track" — but five surrounding statements still promise them as a deliverable.

- Track title: "Cucumber green + JMH **baseline**".
- Purpose: "a Gremlin-on-vs-off JMH baseline measures the translator's value with the plan cache enabled".
- Plan checklist heading at `:643`: same title.
- Plan checklist body at `:648-650`: "and a Gremlin-on-vs-off JMH baseline pinned to verified-recognised shapes".
- Implementation-state paragraph at `:680`: "Track 9 still owns list-shaping terminators + Cucumber green + JMH baseline (D3)".

The plan's Track 9 *table row* was updated to "JMH harness" in the same edit, so the plan file now says both things three lines apart. A track's Purpose is what a Phase C completion check reads to decide whether the track delivered what it promised; leaving "baseline" there sets up a completion argument the acceptance section already conceded.

**Proposed fix**: Retitle to "Cucumber green + JMH harness" in both files, and rewrite the Purpose clause as "a Gremlin-on-vs-off JMH harness lands and compiles, with the baseline numbers captured out of track on Hetzner". Update `:648-650` and `:680` to match the table row.

### T13 [should-fix]
**Certificate**: C21 (`withListShapingOps` shape and the append contract)
**Location**: Track 9 Plan of Work item 2 and sub-item 3a (lines 61, 64)

**Issue**: Sub-item 3a opens "Add the append seam to `RecognitionContext` **first**", and it is numbered after item 2 — which cannot be built without it. 3a's own first motivating example is `g.V().values("name").fold()`, which is item 2's `FoldStep` recogniser. As numbered, a decomposer walking the Plan of Work in order builds the fold recogniser, discovers it has no way to preserve `PropertiesStepRecogniser`'s presence keys, and either back-tracks or ships the silent wipe 3a exists to prevent.

Second, narrower point on the same seam. 3a justifies the append implementation as making it so "no recogniser can overwrite a sibling's flags". That holds only for recognisers that use the new method. `setResultShaping` survives unchanged and replaces the whole record including `listShapingOps`, so any recogniser running after a list-shaper still clobbers its ops. Nothing reachable does that today — D3 confines list-shapers to the last step, and `UnionStepRecogniser`'s `setResultShaping(agreedShaping)` runs before any suffix op appends — but the guarantee as stated is broader than the mechanism delivers, and the D3 dependency is the kind of invariant that should be written down where the seam is specified rather than rediscovered.

**Proposed fix**: Promote the seam to its own item ahead of the fold recogniser — renumber 3a to item 1b or 2a, or move it into item 2's opening — so the dependency order is the reading order. State in the same paragraph that `setResultShaping` remains a full replace and that D3's last-step rule is what keeps the two from colliding.

### T14 [suggestion]
**Certificate**: C24 (union child gate versus union suffix path)
**Location**: Track 9 Plan of Work sub-item 4a (line 71) and `## Validation and Acceptance` (line 89)

**Issue**: 4a specifies a blanket gate — "decline when any child's `shaping().listShapingOps()` is non-empty" — and then closes by saying only two of the four ops actually diverge: "`unfold` and `reverse` are unaffected — both are per-payload, so once-over-the-concatenation and once-per-child coincide; only the two window drains, `fold` and `tail`, diverge." Those two sentences point at different gates. The acceptance bullet names only `fold` and `tail` decline cases and says nothing about `union(__.out().unfold(), __.in().unfold())`, so an implementer can satisfy the acceptance line with either reading.

Neither reading is unsafe. The blanket gate over-declines two shapes that would translate correctly, which costs coverage but no correctness, and it is no worse than today (before this track, an unrecognised `UnfoldStep` inside a child declines the fork anyway). A fold/tail-only gate is tighter but needs the op type to be inspectable, which pushes a `sealed` or type-tagged shape onto `ListShapingOp` that the carrier does not have today.

**Proposed fix**: Pick one and say so in 4a. If blanket, add one sentence: "the gate is deliberately blanket rather than fold/tail-only, because `ListShapingOp` carries no op-type discriminator; `union(__.unfold(), __.unfold())` declines as collateral and stays a Phase 2 shape." If fold/tail-only, say what discriminator makes it possible and add the accepting `unfold` case to the acceptance bullet so the narrower gate is witnessed.

### T15 [suggestion]
**Certificate**: C22 (the three "seven flags" javadoc sites)
**Location**: Track 9 `## Interfaces and Dependencies` "In scope (modified)" (line 111)

**Issue**: The T10 doc-sync list names "the 'seven flags' wording on `RecognitionContext.setResultShaping` and `AbstractMatchPlanStep.shaping`". One of the two is already correct and one that is not is missing. A repository-wide grep for `seven flags` / `seven row-projection` across `core/src` returns exactly three hits:

- `RecognitionContext.java:263` — "the seven flags a terminator sets to control how {@link … YTDBMatchPlanStep} projects each MATCH row", no mention of `listShapingOps`. Stale. Correctly listed, and 3a already schedules an edit to this javadoc's "calls this once" clause, so the two land together.
- `WalkerContext.java:121` — "the seven flags a terminator pins so {@link … YTDBMatchPlanStep} knows how to project each row … a terminator replaces it through {@link #setResultShaping}". Stale on both counts, and the second clause becomes actively wrong once 3a adds an append path. **Not listed.**
- `AbstractMatchPlanStep.java:153-160` — "the seven row-projection flags … **plus the ordered list-shaping ops applied to the projected payload stream afterward** ({@link ResultShaping#listShapingOps()} …)". Already current. **Listed, needs no edit.**

The error is mine: iteration-1's T10 body asserted both sites were stale without reading the second. `WalkerContext` is already in "In scope (modified)" for the seam implementation, so the correct edit costs nothing extra.

**Proposed fix**: In line 111, replace `AbstractMatchPlanStep.shaping` with `WalkerContext.shaping` in the T10 javadoc list, and note that the `WalkerContext` wording needs the append path added alongside `setResultShaping`.

### T16 [suggestion]
**Certificate**: C26 (arrival order versus the multiset-equality invariant)
**Location**: Track 9 `## Context and Orientation` (line 33) and `## Validation and Acceptance` (lines 87, 92)

**Issue**: Not a cascade from this iteration's fixes — it was equally present at iteration 1 and I did not raise it. Recording it now because 4a's decline analysis turns on the same order question and a decomposer will meet it.

`tail(n)` selects a subset by position: "keeps the last `n` in arrival order". The branch's equivalence standard is `implementation-plan.md:365` — "Translator-on and translator-off produce equal result multisets for every `RECOGNIZED` shape". Those two only agree if the translated path's arrival order matches native traversal order, because a different order makes `tail(n)` select different elements, so the multisets differ in content and not merely in sequence. Nothing in the track or the plan pins translated row order to native order, and the branch has direct evidence that the two can diverge: `OrderTest` is one of the 21 deferred failures in `plan/track-10/core-compliance-failure-dispositions.md`, recorded as "Ordering divergence, expected `[josh]` got `[marko]`".

`fold()` has the same exposure in a milder form — the list's element order is observable even when the multiset is right.

The exposure is bounded. `OrderGlobalStepRecogniser` exists, so `order().by(…).tail(n)` gets a deterministic arrival order from the MATCH `ORDER BY`, and TinkerPop's own feature scenarios generally order before a positional terminator for the same reason. What is unpinned is unordered `tail(n)` and the element order inside a `fold()` list.

**Proposed fix**: Add one acceptance line stating the scope: translated `tail(n)` and `fold()` are validated against native for ordered inputs (`order().by(…)` preceding the terminator) and as unordered multisets otherwise, and any Cucumber scenario asserting a positional result on an unordered input goes to the triage bucket rather than being treated as a terminator defect. If item 1's first Cucumber run shows the unordered scenarios passing anyway, downgrade the line to a Decision Log note.

## Evidence base

#### C21 Premise: `ResultShaping.withListShapingOps` supports 3a's append implementation
- **Amendment claim**: 3a — "implement it on `WalkerContext` as an append over the existing ops so no recogniser can overwrite a sibling's flags". The iteration-1 proposed fix spelled it `shaping = shaping.withListShapingOps(<existing + op>)`.
- **Search performed**: full read of `ResultShaping.java`; `grep -rn "listShapingOps" --include=*.java core/src/main/java`. PSI unavailable.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/ResultShaping.java:102-107`; `.../strategy/WalkerContext.java:128, 577-584`
- **Actual behavior**: `public ResultShaping withListShapingOps(@Nonnull List<ListShapingOp> ops)` returns a copy with the list replaced; the compact constructor runs `listShapingOps = List.copyOf(listShapingOps)`, so the record defensively copies and an append is `withListShapingOps(concat(listShapingOps(), op))`. `WalkerContext.shaping` is a mutable field defaulting to `ResultShaping.NONE`, written only by `setResultShaping` (line 577) and read by the package-private `shaping()` (line 583).
- **Verdict**: CONFIRMED
- **Detail**: The seam is implementable exactly as 3a describes, with no new builder needed. Two contract gaps produce T13. Reference-accuracy caveat: `withListShapingOps` currently has no production caller (the terminators that would use it are this track's work), so "the builder the append needs" is established by declaration read, not by usage.

#### C22 Premise: the three "seven flags" javadoc sites and which are stale
- **Amendment claim**: line 111 — "the 'seven flags' wording on `RecognitionContext.setResultShaping` and `AbstractMatchPlanStep.shaping`" are stale sibling javadocs to touch up.
- **Search performed**: `grep -rn "seven flags\|seven row-projection" --include=*.java core/src/`, then a read of each hit's full javadoc block; read of `ResultShaping`'s class javadoc for a fourth candidate.
- **Code location**: `.../strategy/RecognitionContext.java:262-270`; `.../strategy/WalkerContext.java:120-128`; `.../step/AbstractMatchPlanStep.java:153-161`
- **Actual behavior**: three hits. `RecognitionContext` and `WalkerContext` both stop at the seven flags and both name only `YTDBMatchPlanStep`; `AbstractMatchPlanStep` continues "plus the ordered list-shaping ops applied to the projected payload stream afterward ({@link ResultShaping#listShapingOps()}, empty for every traversal that has no list-shaping terminator)". `ResultShaping`'s class javadoc is likewise current.
- **Verdict**: PARTIAL
- **Detail**: One listed member needs no edit, one unlisted member does. `WalkerContext:121`'s closing clause — "a terminator replaces it through {@link #setResultShaping}" — is the one the 3a seam directly invalidates. Produces T15.

#### C23 Integration: which track and plan sites still presuppose `BoundaryOutputType.LIST`
- **Amendment claim**: T1's fix dropped the constant "from item 2, from `## Context and Orientation`, and from `## Interfaces and Dependencies`".
- **Search performed**: `grep -n "BoundaryOutputType"` and a bare-`LIST`-token grep over `track-9.md` and `implementation-plan.md`; read of `## Purpose / Big Picture`, the mermaid block, `## Validation and Acceptance`, and both plan-file Track 9 entries.
- **Code location**: `docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:33, 35, 39, 47, 52, 61, 87, 110-111`; `implementation-plan.md:663, 693`
- **Actual behavior**: six prose sites are correct and consistent. Two are not. Line 47, inside the `## Context and Orientation` mermaid block, still draws `Fold["fold → BoundaryOutputType.LIST"] --> Switch["extend projectOrSkip switch\n+ drain stage"]`. Line 39 still reads "this track's `LIST` drain / flat-map / ring-buffer stages". `## Purpose / Big Picture` never named the constant, and `## Validation and Acceptance` line 87 now states its absence as a criterion.
- **Verdict**: CONFIRMED
- **Detail**: Keeps T1 STILL OPEN. The plan file's two sites were both updated correctly.

#### C24 Edge case: does the drain still fold a union suffix once, given 4a's child gate?
- **Trigger**: `g.V().union(__.out(), __.in()).fold()` after items 4 and 4a land.
- **Code path trace**:
  1. `UnionStepRecogniser.recognize` walks each child (`UnionStepRecogniser.java:92-118`). Neither child carries a trailing terminator, so `childResult.shaping().listShapingOps()` is empty on both and 4a's gate does not fire.
  2. `ctx.pinBoundary(canonicalAlias, agreedOutputType, agreedReturnClass)` then `ctx.setResultShaping(agreedShaping)` at line 123-124 — the parent walk is pinned to the children's agreed contract, with an empty op list.
  3. The suffix `FoldStep` dispatches through the walker's post-union gate, which item 4 relaxes to admit it, and calls `appendListShapingOp` (3a). The op appends onto the empty agreed list, yielding `[foldOp]`.
  4. `buildResult`'s multi-plan branch passes `ctx.shaping()` into the multi-plan `TranslationResult` (confirmed at iteration 1, C8), and `MultiPlanMatchStep`'s base applies the ops once over the single `MultipleExecutionStream` concatenation (C9).
- **Outcome**: one list over the concatenated child streams, which is what acceptance line 92 requires. 4a's gate inspects children only and cannot reach the suffix op, so the two rules are disjoint by construction.
- **Track coverage**: yes — acceptance lines 89 and 92 cover the child and suffix forms separately. One scope ambiguity inside 4a produces T14.
- **Verdict**: CONFIRMED

#### C25 Integration: the JMH-baseline framing after 7a's split
- **Amendment claim**: acceptance line 95 — "The baseline **numbers** are Hetzner-scoped and not a gate on this track".
- **Search performed**: `grep -n "Track 9" implementation-plan.md` and reads of `:643-680`; read of `track-9.md` lines 2, 5, 24, 76-78, 95.
- **Code location**: `track-9.md:2, 5`; `implementation-plan.md:643, 646-650, 680, 693`
- **Actual behavior**: the plan's Track 9 table row at `:693` was changed to "JMH harness". The checklist heading at `:643`, its body at `:648-650`, the implementation-state paragraph at `:680`, the track title, and the track Purpose all still say "baseline". The track's Decision Log bullet ("JMH must pin recognised shapes") makes no baseline claim, so it needs nothing.
- **Verdict**: CONFIRMED
- **Detail**: Produces T12. The 7a body itself is internally consistent and its facts check out — `jmh-ldbc/src/test` holds `LdbcQueryCorrectnessTest` and `LdbcQueryExplainTest`, and `GremlinToMatchStrategy` reads the kill-switch at lines 336–338.

#### C26 Premise: translated arrival order equals native traversal order
- **Track claim**: implicit in line 33 and acceptance line 87 — "`tail(n)` keeps the last `n` in arrival order … All match native".
- **Search performed**: `grep -rn "multiset\|arrival order" implementation-plan.md`; directory listing of the `strategy` package for an order recogniser; read of the `OrderTest` row in `plan/track-10/core-compliance-failure-dispositions.md`.
- **Code location**: `implementation-plan.md:365`; `.../strategy/OrderGlobalStepRecogniser.java`; `plan/track-10/core-compliance-failure-dispositions.md:59`
- **Actual behavior**: the branch invariant is multiset equality, not sequence equality. `OrderGlobalStepRecogniser` exists, so an explicit `order()` pins the MATCH `ORDER BY` and makes arrival order deterministic. Without one, nothing in the track, the plan, or the boundary base ties MATCH row order to native traversal order, and `OrderTest` is a recorded, deferred instance of the two diverging ("expected `[josh]` got `[marko]`").
- **Verdict**: PARTIAL
- **Detail**: The premise holds for ordered inputs and is unestablished for unordered ones. Whether it bites depends on how many Cucumber `tail` / `fold` scenarios assert positionally over an unordered input, which item 1's first run will show. Produces T16 at suggestion severity only.

## Summary

**FAIL** — T1 and T8 remain open, both on narrow remainders rather than on the substance of their fixes.

- T1: the prose rewrite is complete and correct; the mermaid diagram in the same section and one phrase at line 39 still carry the dropped `BoundaryOutputType.LIST` mechanism.
- T8: clauses (a) and (b) landed; clause (c), the file estimate, did not, and the stale figure feeds the plan's own ESCALATE trigger.

Nine findings verified. Five new findings, no blockers: T12 and T13 should-fix, T14 through T16 suggestion. The T1 mechanism swap produced no cascade damage to the neighbouring claims — the drain satisfies the union-suffix acceptance line, the append seam matches the real `ResultShaping` API, and the six amended items do not double-claim any edit.
