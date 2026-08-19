<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 2, suggestion: 1}
index:
  - {id: BG8, sev: should-fix, loc: SQLWhereClause.java:1128, anchor: "### BG8 ", cert: C1, basis: "static-RID promotion replaces the alias's class target with the pinned RID list, so a translated hasId over a non-vertex RID stops being class-scoped and diverges from native"}
  - {id: BG9, sev: should-fix, loc: MatchExecutionPlanner.java:5798, anchor: "### BG9 ", cert: C2, basis: "the duplicate-RID dedupe key folds a 96-bit RID into 64 bits, so two distinct RIDs can collide and the second is silently dropped from the promoted list"}
  - {id: BG10, sev: suggestion, loc: MatchPrefetchStep.java:130, anchor: "### BG10 ", cert: C3, basis: "the new getSubSteps() dereferences prefetchExecutionPlan while canBeCached() and copy() in the same class treat it as nullable"}
evidence_base: {section: "## Evidence base", certs: 10, matches: 3}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
  - {id: C8, verdict: REFUTED, anchor: "#### C8 "}
  - {id: C9, verdict: REFUTED, anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED, anchor: "#### C10 "}
flags: [CONTRACT_OK, DIFF_STALE, PSI_UNAVAILABLE]
-->

## Findings

### BG8 [should-fix] Static-RID promotion drops the alias's class, so a translated `hasId` over a non-vertex RID stops being vertex-scoped

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLWhereClause.java` (line 1128); fix site `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java` (lines 5576-5580)

**Issue.** The leaf branch added at `SQLWhereClause:1128` makes a code-assembled one-condition clause visible to `findRidInList()`. For the Gremlin translator that turns on `promoteStaticRidsFromFilters`, which writes the RID list into `aliasPinnedRids`. The alias's fetch is then built by `createSelectStatement` (`MatchExecutionPlanner:5570-5584`), and that method treats RIDs and class as mutually exclusive: a non-empty `targetRids` sets `fromItem.setRids(...)` and the `else if (targetClass != null)` arm never runs. The class is dropped from the target.

For a translated `g.V()`-rooted traversal the class is the only vertex constraint there is. `StartStepRecogniser:121` registers the boundary node under `WalkerContext.VERTEX_ROOT_CLASS` (`"V"`), and `WalkerContext:210-215` states outright that both registration sites do so "with no `@class` filter". So before this diff the alias fetched through `SELECT FROM V WHERE @rid IN [...]`, where the class scan enforced vertex-ness and the `@rid` post-filter picked the row; after it, the alias fetches through `SELECT FROM [#X:Y]`, which resolves whatever record that RID names.

`g.V().hasId(...)` is the shape that reaches this with a single bare condition. `HasStepRecogniser:169` hands the accumulated filters to `putAliasFilter` through `MatchWhereBuilder.and`, which returns a lone operand unwrapped, so a traversal whose only filter is `hasId` installs a bare `SQLInCondition` — invisible to the detector before this diff, promotable after it.

**Failure scenario.** `g.V().hasId(edge.id())`, or any `hasId` given a RID that names an edge, a plain document, or a record in a non-graph class.

- Native (translator off): `YTDBGraphStepStrategy:123-131` folds the `HasStep` onto `YTDBGraphStep` as a HasContainer, not into `ids`, so `YTDBGraphStep.elements()` takes the `ids.length == 0` arm and runs a `YTDBGraphQueryBuilder` query scoped to the vertex class. The non-vertex record is not in that class. Result: `[]`.
- Translated before this diff: `SELECT FROM V WHERE @rid IN [...]` — same class scan, same `[]`.
- Translated after this diff: `SELECT FROM [#X:Y]` fetches the record, the retained `@rid IN` post-filter passes it, and the boundary emits it. On the `ELEMENT` path `AbstractMatchPlanStep.projectVertex:868-874` calls `Result.getVertex(alias)`, which reaches `transaction.loadVertex(id)` (`ResultInternal:536-547`) and raises on a non-vertex record. On a path that never materializes the element — `g.V().hasId(edgeRid).count()`, which the count short-circuit answers from the plan — the row is simply counted, so the traversal returns `1` where native returns `0`.

The count variant is the worse half: no exception, a wrong number, and nothing in the suite watching. Both violate the branch-wide multiset-equality invariant this track's `## Invariants & Constraints` restates.

**Refutation considered.** I checked whether the class survives somewhere else in the plan. It does not: the retained WHERE clause holds only `@rid IN [...]` (`HasStepRecogniser:216-222` builds nothing else), and no `@class` condition is emitted for the root class — the `hasLabel` narrowing that Track 3 added goes through `MatchWhereBuilder.classEquals` and only exists when the traversal actually calls `hasLabel`. I checked whether the parsed SQL MATCH path or GQL widen the same way, and they do not (see C6), so the exposure is the translator's. I checked whether the pre-diff plan really returned empty rather than throwing: `SELECT FROM V` iterates the V hierarchy's collections, so a record outside it is never visited. I also checked the `g.V(rid)` start-step form and left it out of the scenario deliberately — native takes the `ids` arm there and calls `entity.asVertex()`, which raises on a non-vertex record, so that form diverged before this diff too and the diff does not clearly worsen it.

**Suggestion.** Keep both constraints when both are known. `createSelectStatement` can set the RID target and still carry the class, either by emitting the class as a WHERE conjunct alongside the pinned RIDs or by having `promoteStaticRidsFromFilters` skip the promotion when `aliasClasses` holds a class the RIDs are not proven to belong to. A regression test on the equivalence suite — `g.V().hasId(<edge rid>)` and `g.V().hasId(<edge rid>).count()` against the native path — pins it either way.

### BG9 [should-fix] The duplicate-RID dedupe key folds a 96-bit RID into 64 bits, so a distinct RID can be silently dropped

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java` (line 5798)

**Issue.** The dedupe added in `a4fad97c1b` keys the `seen` set on `(collection << 32) ^ position`. Collection id is an `int` and position is a `long` (`sqlRidFromRuntimeValue:5813-5852` reads them from `RecordIdInternal.getCollectionId()` / `getCollectionPosition()`), so the key packs 96 bits of input into 64 bits of output. Two distinct RIDs collide whenever `(c1 ^ c2) << 32 == p1 ^ p2`, and on a collision the second RID is not added to `rids` — it disappears from the promoted list while remaining in the WHERE clause that nothing else enforces at fetch time.

**Failure scenario.** `g.V().hasId(a, b)` where `a = #0:4294967296` and `b = #1:0`. Both key to `0x1_0000_0000`: `(0L << 32) ^ 4294967296L` and `(1L << 32) ^ 0L`. `b` is dropped, the promoted list is `[#0:4294967296]`, `createSelectStatement` fetches one record, and the traversal returns one vertex where native returns two. Silent, with no exception and no log line — the debug line at `:5735` reports the post-dedupe size as if it were the whole list.

The general condition is that the two positions agree in their low 32 bits and differ in their high 32 bits by exactly the cluster-id delta, which needs `|position| >= 2^32` in at least one of them. Small positive positions cannot collide, and neither can the small negative positions temporary RIDs carry, so the trigger is a collection that has allocated more than 4.29 billion positions. That is remote for most databases and reachable for a long-lived high-churn one, since positions count allocations rather than live records.

**Refutation considered.** I checked whether an upstream guard bounds the position range. `RecordIdInternal.fromString` rejects an out-of-range *collection* id (`sqlRidFromRuntimeValue:5830-5837` catches exactly that), but nothing caps the position, and `SQLInteger.setValue` takes the `long` unchanged. I checked whether the values could already be deduped before reaching here: `HasStepRecogniser` calls `StartStepRecogniser.toRecordIds` deliberately without the duplicate decline (`StartStepRecogniser:159-161`), which is why this dedupe exists at all. I checked whether the collision could be benign — it cannot, because the promotion replaces the class scan, so a RID missing from the pinned list is never fetched.

**Suggestion.** Key on the pair rather than a fold. `StartStepRecogniser:295-298` already declares exactly the right shape two files over — `private record RidKey(int collectionId, long position)` in a `HashSet<RidKey>` — and reusing that shape here makes the two dedupe sites read the same and removes the failure mode at no cost. The same edit lets the unreachable `collection == null || position == null` guard at `:5795-5797` go: `sqlRidFromRuntimeValue` is the only producer and it never leaves either field null.

### BG10 [suggestion] `MatchPrefetchStep.getSubSteps()` dereferences a field its siblings treat as nullable

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchPrefetchStep.java` (lines 130-132)

**Issue.** The new override returns `List.copyOf(prefetchExecutionPlan.getSteps())` with no guard. Two methods in the same class say the field can be null — `canBeCached():107-109` short-circuits on `prefetchExecutionPlan == null`, and `copy(ctx):145-155` builds `prefetchExecutionPlanCopy = null` when the source is null and hands that null to the constructor. The constructor's own non-null check is an `assert` (`:70`), so with assertions off the null propagates. The sibling override this diff adds to `MatchFirstStep` (`:166-167`) does guard, which makes the pair inconsistent as well as unsafe.

**Failure scenario.** A `MatchPrefetchStep` copied from one whose sub-plan is null — the path `copy()` explicitly writes for — then walked by `ExecutionStep.toResult` for an `EXPLAIN` document or by the index-counting test helpers throws `NullPointerException` out of plan introspection rather than reporting an empty child list. Plan copying is on a live path now: `AbstractMatchPlanStep.replaceClosedPlanWithCopy()` copies the whole plan on every re-arm after close, and `MatchPrefetchStep.copy` is what that walk calls for this step.

**Refutation considered.** I looked for a production route that constructs the step with a null plan. `addPrefetchSteps:5548-5562` always passes `prefetchStm.createExecutionPlan(...)`, so today the field is non-null on every construction except a copy-of-a-null, which itself requires a null to already exist. That is why this sits at suggestion. I also checked whether the pre-existing methods already carry the same exposure, and three do — `reset():77-79`, `internalStart():86-101`, and `prettyPrint():135` all dereference unguarded — so the diff widens a pattern rather than creating one. The inconsistency is still worth closing while the file is open, because the two accessors added in this diff disagree with each other on the same question.

**Suggestion.** Mirror `MatchFirstStep`: `return prefetchExecutionPlan == null ? List.of() : List.copyOf(prefetchExecutionPlan.getSteps());`. If the field is genuinely non-null by construction, the better fix is to make it so — drop the null arms from `canBeCached()` and `copy()` and promote the constructor `assert` to an `Objects.requireNonNull` — so the class states one answer instead of two.

## Evidence base

#### C1 CONFIRMED — promotion replaces the class target and the translator's root class has no WHERE backstop (BG8)

`createSelectStatement:5576-5580` sets `fromItem.setRids(targetRids)` and reaches the `targetClass` arm only when the RID list is null or empty; `StartStepRecogniser:121` registers the boundary node under `WalkerContext.VERTEX_ROOT_CLASS` and `WalkerContext:210-215` records that the registration carries no `@class` filter; `HasStepRecogniser:169` installs the `hasId` filter through `MatchWhereBuilder.and`, which leaves a single operand unwrapped and therefore promotable under the new leaf branch at `SQLWhereClause:1128`; native `g.V().hasId(...)` stays class-scoped because `YTDBGraphStepStrategy:123-131` folds the container onto the step rather than into `ids`, so `YTDBGraphStep.elements():85-160` takes the class-query arm.

#### C2 CONFIRMED — the dedupe key is not injective over the RID domain (BG9)

`MatchExecutionPlanner:5798` folds a 32-bit collection id and a 64-bit position into one `long`; `#0:4294967296` and `#1:0` both produce `0x1_0000_0000`, and the `seen.add` guard at the same line drops the second, so `rids` returned at `:5802` is short by one entry that `createSelectStatement` would otherwise have fetched.

#### C3 CONFIRMED — the two `getSubSteps()` overrides disagree on the nullability of their sub-plan (BG10)

`MatchPrefetchStep:130-132` dereferences `prefetchExecutionPlan` unguarded while `:107-109` and `:145-155` in the same class branch on it being null; `MatchFirstStep:166-167` guards; the constructor's non-null check at `MatchPrefetchStep:70` is an `assert` and so is absent in production.

#### C4 REFUTED — the `$`-prefixed projection probe can dispatch to the backing record or shadow a LET variable

**Claim.** `SQLSuffixIdentifier:191` gates the new probe on `iCurrentRecord.isProjection()`, and the comment above it claims that gate stops "a record-backed Result" from dispatching to the record. `ResultInternal.hasProperty:620-631` falls through to `identifiable` when `content` lacks the key, and its cold path `loadLazyAndHasProperty:639-643` loads the record from storage. If a `Result` can be both a projection and record-backed, the probe triggers exactly the storage read the gate was written to avoid, and can raise `RecordNotFoundException` on a dangling RID where the old code returned null. Separately, moving the probe ahead of the metadata and temporary-property lookups reorders resolution for any `$`-name that is both a projection column and a LET binding.

**Check.** `ResultInternal` cannot hold both. `setProperty:191-203` throws when `content` is null, and `setIdentifiable:912-935` assigns `this.content = null` on every non-embedded path (the embedded path builds `content` and nulls `identifiable`), so `isProjection()` — `content != null`, `:757-761` — implies `identifiable == null`. `UpdatableResult.isProjection():126-129` returns `false` unconditionally. `MatchResultRow.isProjection():187-190` returns `true`, but the class holds no `identifiable` and its `hasProperty:103-113` delegates up the parent chain, whose root is the content-only `ResultInternal` that `MatchFirstStep.internalStart:117-120` builds. On the LET question, `execute(Result, CommandContext):163-172` resolves a non-null `ctx.getVariable(varName)` before the record block is reached, so a live LET binding still wins; the only reordered case is a `$`-name that is simultaneously a projection column and a `ResultInternal` metadata key or temporary property, and the two writers of `$`-prefixed names — `MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX` aliases and the translator's `$g2m_` namespace — write columns, while `AggregateProjectionCalculationStep:180-196` writes an alias as either a regular property or a temporary property but never both.

**Verdict.** REFUTED. No `Result` implementation reaches the probe as both a projection and a record, and no writer produces a `$`-name in two stores at once.

#### C5 REFUTED — the AND-loop rewrite loses a shape the old extract-then-recurse found

**Claim.** `findRidConditionInExpression` changed from applying `termExtractor` to each sub-block and then recursing, to recursing only (`SQLWhereClause:1119-1126`). A sub-block that the extractor recognised but the recursion does not would stop being found, silently disabling promotion for a shape that worked before.

**Check.** Enumerated the sub-block shapes against both versions. A leaf reaches `termExtractor` in both (old: directly; new: through the recursion's leaf branch at `:1128`). A single-element `OrBlock` or `AndBlock` wrapper reaches it in both — old through `unwrapSingleElementTerm:1297-1320` inside the extractor, new through the recursion's own unwrap plus the leaf branch. A multi-element `OrBlock` yields null in both. A multi-element `AndBlock` yields null from the old extractor and is walked by the new recursion, which is strictly wider. A non-negated `SQLNotBlock` is unwrapped by the extractor in both, since the recursion treats it as a leaf and hands it straight over. No shape narrows.

**Verdict.** REFUTED. The rewrite is a superset of the old behaviour on every sub-block shape.

#### C6 REFUTED — the widening reaches the parsed SQL MATCH path and the GQL front-end

**Claim.** The track file's step-5 critical-context note says the leaf branch makes "every code-assembled single-condition WHERE clause" promotable, "not just the Gremlin translator's". If parsed SQL MATCH or GQL also reach it, BG8's class-drop consequence lands on those front-ends too and the blast radius is much larger than the translator.

**Check.** The parsed path cannot produce a leaf `baseExpression`: the grammar's `WhereClause()` production always assigns an `SQLOrBlock`, `addAliases` merges per-alias filters into an `SQLAndBlock`, and the one code-built clause in the file — `buildWhereWith:1055-1071` — wraps its terms in `OrBlock(AndBlock(...))`. GQL does not reach the promotion at all: `buildPatterns:5652-5670` gates the additive branch on `promoteFilterRidsOnBuild`, which the comment there records as set only on the `MatchPlanInputs` path, with "the GQL 3-arg ctor keeps its plans". Grep over `core/src/main` for `setBaseExpression` returns eight sites; the only two that assign a bare condition are `MatchWhereBuilder:434` and `StartStepRecogniser:302`, both on the translator path.

**Verdict.** REFUTED. The widening's production reach today is the Gremlin translator only, which is where BG8 places it. (grep-only — see the reference-accuracy caveat in `## Reviewer notes`.)

#### C7 REFUTED — the sub-plan-free `MatchFirstStep` can be reached without its `MatchPrefetchStep` having run

**Claim.** `addStepsFor` now chains `new MatchFirstStep(context, patternNode, profilingEnabled)` — no sub-plan — whenever the root alias is in `prefetchedAliases` (`MatchExecutionPlanner:5434-5440`). If any caller reaches that step in a plan where the matching `MatchPrefetchStep` has not run, `internalStart:100-110` finds no cache and dereferences a null `executionPlan`; the new `assert` on the line above only fires under `-ea`. The Cartesian branch is the obvious suspect, since it runs each sub-plan through `CartesianProductStep` rather than inline.

**Check.** Both call sites (`:2113`, `:2125`) receive the same `aliasesToPrefetch` set that `addPrefetchSteps(result, aliasesToPrefetch, …)` walked at `:657`, and that call precedes both the Cartesian branch at `:661-668` and the inline branch at `:670-676`, so every alias in the set has a prefetch step ahead of the pattern steps in `result`. The Cartesian sub-plans are built with the same `context` object (`:665-666`), and `CartesianProductStep.internalStart:50-54` drains `prev` — which runs the prefetch steps — before calling `ep.start()`, so the sub-plan reads the same context variable the prefetch wrote. The two builders that genuinely run ahead of the prefetch, `buildNotPatternPlan` and `buildHashJoinBranchPlan`, do not call `addStepsFor`: grep for the method name returns only the definition and the two call sites above, and both builders keep their own `createSelectStatement` scan, which is what the two new comments at `:1855-1861` and `:1907-1912` record.

**Verdict.** REFUTED. Every reachable sub-plan-free root has its prefetch step ahead of it in the same chain and the same context.

#### C8 REFUTED — a dangling RID in the promoted list truncates the fetch

**Claim.** `FetchFromRidsStep` documents a "legacy terminate-on-first-missing contract" and defaults `skipMissing` to false (`FetchFromRidsStep:33-40`). Now that `g.V(id1, id2, id3)` promotes to `SELECT FROM [#a, #b, #c]`, a deleted middle RID would end the stream and drop every RID after it, where the pre-diff class scan simply skipped the hole.

**Check.** The promoted list reaches the plan through `SQLFromItem.setRids`, which `SelectExecutionPlanner.handleRidsAsTarget:1632-1644` turns into `new FetchFromRidsStep(actualRids, ctx, profilingEnabled, /* skipMissing= */ true)`. The comment there names the MATCH `@rid` promotion explicitly as one of the callers the flag exists for. `LoaderExecutionStream.fetchNext:61-90` then `continue`s past a `RecordNotFoundException` instead of returning.

**Verdict.** REFUTED. The promoted-RID path opts into skip-missing, matching class-scan parity.

#### C9 REFUTED — the sub-plan-free root makes a previously uncacheable plan cacheable

**Claim.** `MatchFirstStep.canBeCached():126-128` returns `executionPlan == null || executionPlan.canBeCached()`. Dropping the sub-plan for a prefetched root turns a `false` from a non-cacheable scan — `FetchFromRidsStep.canBeCached()` is unconditionally `false` — into a `true`, so a plan that was correctly excluded from the plan cache could now be cached and replayed with stale RIDs.

**Check.** `addPrefetchSteps:5548-5556` builds the prefetch sub-plan from the identical `createSelectStatement(targetClass, pinnedRids, filter)` call, and `MatchPrefetchStep.canBeCached():107-109` forwards to it. The prefetch step is chained into the same `result` plan, so the plan's aggregate cacheability still sees the same `false`. Removing the root's duplicate of that sub-plan removes a duplicate answer, not the answer.

**Verdict.** REFUTED. The prefetch step reports the same cacheability the dropped sub-plan did.

#### C10 REFUTED — a re-armed `MultiPlanMatchStep` resumes from a stale child cursor

**Claim.** `replaceClosedPlanWithCopy():266-305` swaps `plans` for a list of copies but touches no other field. If the child cursor that `startPlanStream()` advances is step state rather than per-arming state, a re-arm after a fully drained pass would start with the cursor past the last child and emit nothing.

**Check.** `startPlanStream():308-341` allocates the cursor inside the arming: `final var childPlans = plans;` followed by an anonymous `ExecutionStreamProducer` whose `iter` field is initialised from `childPlans.iterator()` at construction. Each call to `startPlanStream()` builds a fresh producer over the current list, so the cursor cannot survive a pass. The capture into a local also pins the swapped-in list for the arming rather than re-reading the field mid-stream.

**Verdict.** REFUTED. The child cursor is per-arming by construction.

## Reviewer notes

**Diff staleness.** The supplied patch at `/tmp/claude-code-track-10-diff-305004.patch` is one commit behind the range it names: it stops at `32b2cd27ac` and omits `a4fad97c1b` ("Drop duplicate RIDs when promoting a static IN list"), which is HEAD. `git diff f007749249..HEAD` is 3,864 lines against the patch's 3,810, and `toPromotedSqlRidList` — the dedupe the dispatch asked me to check — appears only in the former. I reviewed against `f007749249..HEAD`, so BG9 covers the dedupe as shipped.

**Reference-accuracy caveat.** `steroid_list_projects` confirmed the open project matches the working tree, and one `steroid_execute_code` PSI find-usages attempt timed out, as `reference_mcp_steroid_psi_timeout` predicts for this repository. Every symbol-search claim above is grep-derived. The three that carry weight are: the callers of `findRidInList` / `findRidEquality` (BG8, C6), the `setBaseExpression` producers of a bare-condition clause (C6), and the `addStepsFor` call sites (C7). All three key on unambiguous literals with no polymorphic dispatch — a private static method, a setter with one declaration, and a private method with no overrides — which is the case grep handles most reliably, but none was cross-checked against PSI.

**Concurrency triage gap here.** The diff writes the non-final `plan` and `plans` fields on the iteration path (`YTDBMatchPlanStep:153`, `MultiPlanMatchStep:304`) with plain writes, and both are read through public accessors that `YTDBQueryMetricsStep.capturedExecutionPlan()` calls from a listener callback. Both new comments reason explicitly about JMM publication. That is publication-and-interleaving territory, which `review-concurrency` owns. If this track pass was not triaged onto the `concurrency` category, it may need to be. I did no interleaving analysis. (The step-2 pass raised the same note; repeating it here because the track-level dispatch lists no concurrency category.)

**Carried forward, not a finding.** Step 2 fixed re-arm from `CLOSED` by copying the plan, and left the `REARMED` path rewinding in place through `rewindPlan(ctx)` → `plan.reset(ctx)`. One level down, `MatchPrefetchStep.internalStart:86-101` closes its own `prefetchExecutionPlan` at the end of every pass, and `MatchPrefetchStep.reset():77-79` forwards to `prefetchExecutionPlan.reset(ctx)` — the exact "reset cannot revive a closed chain" hazard DR-M2 documents for the outer plan. A `next(); reset(); toList()` sequence therefore re-runs the outer plan against a sub-plan the previous pass closed. Whether that yields empty depends on which steps sit under the prefetch: `FetchFromClassExecutionStep.internalStart:155-174` builds a fresh iterator per call and overrides no `close()`, so it may well restart cleanly, and `SelectExecutionPlan` carries no closed flag of its own (`:76-78`). I could not settle it without running the suite, which the dispatch forbids, so I am recording it rather than claiming it. It is pre-existing either way — neither the close-in-`internalStart` nor the `REARMED` path is new in this diff — but it sits directly under the contract step 2 was written to establish, and a single unit test driving `next(); reset(); toList()` over a plan with a prefetch step would resolve it.

**Scope note.** Steps 2 and 3 were re-read rather than re-derived, per the dispatch; C7, C9 and C10 are the cross-step checks that came out of reading them against steps 5 and 6. No blocker survived. The two should-fix findings are both silent-wrong-multiset defects on the promotion path step 5 opened, which is where the dispatch pointed, and both have small local fixes.
