<!-- MANIFEST
findings: 8   severity: {blocker: 0, should-fix: 5, suggestion: 3}
index:
  - {id: CQ1, sev: should-fix, loc: AbstractMatchPlanStep.java:466, anchor: "### CQ1 ", cert: n/a, basis: "new comment justifies a no-stale-cursor claim with a premise two of three CLOSED routes falsify; one of those routes was added by this same track"}
  - {id: CQ2, sev: should-fix, loc: AbstractMatchPlanStep.java:167, anchor: "### CQ2 ", cert: n/a, basis: "the NEW-is-pristine invariant the six-state design rests on is stated in four satellite comments and absent from State.NEW's own Javadoc"}
  - {id: CQ3, sev: should-fix, loc: MatchStatementExecutionTest.java:2453, anchor: "### CQ3 ", cert: n/a, basis: "recursive plan-walk helper added in four test classes this diff, two pairs byte-identical, one name carrying two incompatible signatures"}
  - {id: CQ4, sev: should-fix, loc: BoundaryStepTestSupport.java:1139, anchor: "### CQ4 ", cert: n/a, basis: "the shared test-support class names the raw-entity duplication as its reason to exist but does not absorb it; the diff adds call sites to both copies"}
  - {id: CQ5, sev: should-fix, loc: MatchExecutionPlanner.java:5448, anchor: "### CQ5 ", cert: n/a, basis: "two root-scan builders with opposite class-vs-RID precedence now sit as sibling branches; step 5 widened the input set where they disagree"}
  - {id: CQ6, sev: suggestion,  loc: AbstractMatchPlanStep.java:29, anchor: "### CQ6 ", cert: n/a, basis: "47 added Java lines exceed the 100-char limit; Spotless cannot catch them because comment formatting is off"}
  - {id: CQ7, sev: suggestion,  loc: AbstractMatchPlanStep.java:902, anchor: "### CQ7 ", cert: n/a, basis: "the sticky-close-guard rationale is restated in nine places, the exact shape that produced this track's three comment-accuracy findings"}
  - {id: CQ8, sev: suggestion,  loc: AbstractMatchPlanStep.java:880, anchor: "### CQ8 ", cert: n/a, basis: "two counts in surrounding comments went stale when the diff added a fifth plan-seam hook and a second close-gating state"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### CQ1 [should-fix] `openArming()`'s new comment rests on a premise two of the three CLOSED routes falsify

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java` (lines 466-467)

**Issue**: The comment guarding the `REARMED_AFTER_CLOSE` branch reads:

```java
// The stale-cursor block above never fires in this state: openStream is null,
// because every route into CLOSED goes through releaseStreamAndClosePlan(), which nulls it.
```

Three sites write `state = State.CLOSED`. Only one of them goes through `releaseStreamAndClosePlan()`:

| Site | Line | Release call |
|---|---|---|
| `processNextStart()` terminal handler | 325 | `releaseStreamAndClosePlan()` |
| `openArming()` start-failure handler | 504 | `closePlan()` |
| `close()`, stream-already-released arm | 628-635 | `closePlan()` |

The conclusion still holds — `openStream` really is null in `REARMED_AFTER_CLOSE` — but by a different argument on each route: the start-failure handler runs after the stale-cursor block at 444-450 has already nulled the field, and `close()`'s `closePlan()` arm is reached only when `openStream == null` is the branch condition. The stated reason is the one thing that is not true.

This matters more than an ordinary comment slip because the falsifying route is `openArming()`'s own start-failure handler forty lines below, added by this track (step 2's gate-check repair, DR-M2). A future editor auditing "can the stale-cursor block fire here?" verifies the property the comment names, finds it false, and has to redo the whole analysis to discover the claim is still sound.

**Suggestion**: State the invariant that actually holds and how each route establishes it, e.g. "openStream is null in every state that leads here: the terminal handler and `close()` null it through `releaseStreamAndClosePlan()`, and the start-failure handler is reached only after the stale-cursor block above has nulled it." Keep the sentence to the fact a reader can check in one pass.

### CQ2 [should-fix] `State.NEW`'s own Javadoc omits the invariant the six-state design rests on

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java` (lines 167-171)

**Issue**: `NEW` now carries a load-bearing invariant — the plan this step holds has never been started, so it is safe to start rather than copy. Three sites read it that way: the rewind skip in `openArming()` (486), `close()`'s `CLOSED_UNSTARTED` mapping (619-625), and `reset()`'s `CLOSED_UNSTARTED` → `NEW` edge (588-589). Violating it is precisely the regression the step-2 gate check caught.

The invariant is written down in four places — `CLOSED_UNSTARTED`'s Javadoc (203-205), `openArming()`'s start-failure comment (497-503), `close()`'s NEW branch (620-623), and the track file's Surprises entry — and in none of them is it on `NEW`. `NEW`'s Javadoc still says only:

```java
/**
 * Constructed, or {@link #reset()} before the plan ever ran. The next open starts the plan
 * WITHOUT rewinding it — there is no consumed state to rewind.
 */
NEW,
```

That is where an editor adding a sixth route that touches the plan will look, and it tells them nothing about the obligation to record a state. The text is also now incomplete on its own terms: `NEW` is reachable from `CLOSED_UNSTARTED` via `reset()`, which is neither "constructed" nor "`reset()` before the plan ever ran" in the sense a reader takes from that phrasing.

Related, and cheap to fix in the same edit: the six states and their transitions are described in five prose blocks (the class-level `<ul>`, the per-constant Javadoc, `processNextStart()`, `reset()`, `close()`), each covering a slice. There is no single place showing the whole machine, so answering "what happens on `reset()` from `DRAINED`?" means reading three of the five. The machine is followable, but only by assembling it.

**Suggestion**: Move the invariant onto `NEW` as an explicit contract sentence — "**Invariant:** a `NEW` step's plan has never been started. Every route that closes or otherwise consumes the plan must record a state; leaving the state `NEW` after closing a plan makes the next open start a dead chain." — and have `CLOSED_UNSTARTED` and the two handlers `{@link #NEW}`-reference it instead of restating it. While there, add a compact seven-row transition table (from-state, trigger, to-state, effect on the plan) to the class Javadoc's lifecycle section; it replaces most of what the five prose blocks are currently each half-saying.

### CQ3 [should-fix] The recursive plan-walk helper is now defined four times across the test tree

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/MatchStatementExecutionTest.java` (lines 2453, 2470)

**Issue**: This diff adds a recursive `getSubSteps()` walk to four test classes:

| Class | Line | Signature |
|---|---|---|
| `MatchStatementExecutionTest` | 2453, 2470 | `containsStepOfType(ExecutionStep, Class)`, `countStepsOfType(List<ExecutionStep>, Class)` |
| `HashJoinPlannerIntegrationTest` | 377, 393 | same two, **byte-identical bodies** |
| `GremlinToMatchSmokeTest` | 736 | `containsStepOfType(List<ExecutionStep>, Class)` |
| `YTDBQueryMetricsStrategyTest` | 1718 (pre-existing), 1733 (new `findStepOfType`) | `containsStepOfType(List<ExecutionStep>, Class)` |

Two problems compound. The `MatchStatementExecutionTest` / `HashJoinPlannerIntegrationTest` pair is a straight copy — same bodies, only the Javadoc wording differs. And `containsStepOfType` now names two incompatible signatures depending on which test class you are reading: one takes a single step and tests it plus its descendants, the other takes a list and tests the descendants of each. A reader moving between `MatchStatementExecutionTest` and `YTDBQueryMetricsStrategyTest` reasonably assumes the same helper and gets a different inclusion rule at the root.

The track's own episodes flag `getSubSteps()`-walking helpers as the callers that a double-published sub-plan would mislead (`CommandExecutorSQLSelectTest.indexUsages`, `BaseDBJUnit5Test.indexesUsed`). Adding four more copies puts six independent implementations of that walk in the tree, which is exactly the population that has to be re-audited the next time the accessor contract moves.

**Suggestion**: Extract one package-visible helper — `containsStepOfType(List<ExecutionStep>, Class)`, `countStepsOfType(List<ExecutionStep>, Class)`, `findStepOfType(List<ExecutionStep>, Class)` — into a small static class in the test tree (`com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionPlanIntrospection` would reach all four callers; `MatchStatementExecutionTest` and `HashJoinPlannerIntegrationTest` also share `DbTestBase` if a base-class home is preferred). Settle on the list-taking shape, which all four call sites can express, and delete the single-step overload.

### CQ4 [should-fix] `BoundaryStepTestSupport` names the duplication it exists to absorb, then does not absorb it

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/BoundaryStepTestSupport.java` (lines 1139-1144)

**Issue**: On the split question first, because it is the prior one: **the two-class split is principled.** `ReplayablePlanFixture` builds the thing under the step and owns a real `SelectExecutionPlan` plus its source step; `BoundaryStepTestSupport` drives the step and owns no state. They have different lifetimes, different dependencies, and different reasons to change, and the stated boundary ("drives a step" against "builds the plan under a step") predicts correctly where any new helper goes. Merging them would put a 60-line inner `AbstractExecutionStep` subclass in the same file as a pull loop.

The problem is what did not move. `BoundaryStepTestSupport`'s class Javadoc argues for its own existence by citing a duplication it leaves in place:

> A helper duplicated per class has to be hardened twice, and the class pair already carries one such split (the raw-entity reader exists under two names with two bodies), so anything both classes drive belongs here.

The reader is told the rule and shown the counterexample in the same sentence. Concretely, still duplicated across the two boundary test classes:

- `MultiPlanMatchStepTest.rawEntityOf` (1224) and `YTDBMatchPlanStepTest.assertRawEntityOf` (1553) — byte-identical twelve-line reflective bodies under two names, and this diff adds four new call sites across the two copies.
- `MultiPlanMatchStepTest.freshTraversal` (1238) and `YTDBMatchPlanStepTest.freshTraversal` (1567) — identical code, differing only in comment text.
- New in this diff, a third instance of the pattern: `MultiPlanMatchStepTest.stubCopyYielding` (1388 region) and `YTDBMatchPlanStepTest.stubPlanCopyDelivering` (1860 region) do the same job — stub `copy(...)` with a mock whose stream delivers rows, return the copy — under two names with two verbs.

The track file records the fold-in as owed to this pass ("The raw-entity reader is still duplicated across the two boundary test classes under two names with two bodies; the track-level pass owns folding it in"), so leaving it also leaves that entry stale.

**Suggestion**: Move the raw-entity reader into `BoundaryStepTestSupport` under one name (`rawEntityOf`, the shorter and the one that does not promise an assertion in its name while returning a value) and delete both copies. Move `freshTraversal` with it — it is a drive-the-step helper by the class's own rule. For the two copy-stubbing helpers, pick one name (`stubPlanCopyDelivering` reads better on both) and keep them per-class if their fixture types genuinely differ; renaming alone removes the "are these the same thing?" question.

### CQ5 [should-fix] Two root-scan builders, opposite class-vs-RID precedence, now sitting as sibling branches

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java` (lines 5448-5451)

**Issue**: The new prefetch branch in `addStepsFor` (5434-5462) makes the method's structure line up one-for-one with the edge-free branch of `createPlanForPattern` (2138-2151), and the diff's comment says so: "The edge-free branch of `createPlanForPattern` already skips the sub-plan this way." The two `else` arms then build the same scan two different ways, and they disagree on target precedence:

```java
// addStepsFor (5448-5451), hand-rolled — class wins
if (clazz != null) {
  select.getTarget().getItem().setIdentifier(new SQLIdentifier(clazz));
} else if (pinnedRids != null) {
  select.getTarget().getItem().setRids(pinnedRids);
}

// createSelectStatement (5576-5580), the helper createPlanForPattern uses — RIDs win
if (targetRids != null && !targetRids.isEmpty()) {
  fromItem.setRids(targetRids);
} else if (targetClass != null) {
  fromItem.setIdentifier(new SQLIdentifier(targetClass));
}
```

For an alias carrying both a class and pinned RIDs the two builders emit different plans: `createSelectStatement` emits the RID fetch, the hand-rolled block emits a class scan with the `@rid` filter left as a post-filter — the shape step 5 was created to eliminate. Step 5 widened the set of aliases that reach the planner with both set, since `promoteStaticRidsFromFilters` now fires on code-assembled clauses and leaves the filter in `aliasFilters` while adding to `aliasPinnedRids`. The hand-rolled arm is reached when the alias is not prefetched, which for a RID-pinned alias needs either ≥ `THRESHOLD` pinned RIDs or a `dependsOnExecutionContext` filter, so this is narrow rather than hot — but it is the one arm where the divergence is silent, because the rows are correct either way.

The same two arms also disagree on filter handling: the hand-rolled block copies (`where == null ? null : where.copy()`, 5453) while `createPlanForPattern` passes the filter to `createSelectStatement` uncopied (2144). One of the two is wrong about mutable-filter corruption, and the parallel structure now invites a reader to assume they match.

**Suggestion**: Replace the hand-rolled block with `createSelectStatement(clazz, pinnedRids, where == null ? null : where.copy())`, keeping the `subContxt` derivation and the `MatchFirstStep` chain call as they are. That collapses the precedence question to one implementation and makes the sibling branches read as siblings. Two micro-nits in the same block while it is being touched: `subContxt` (5454) is a typo for `subContext`, and `this.aliasClasses` (5442) uses an explicit `this.` the surrounding code does not.

### CQ6 [suggestion] 47 added Java lines exceed the 100-character limit

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java` (line 29, first of 15 in that file)

**Issue**: `CLAUDE.md` § Code Style fixes the line width at 100. Forty-seven added lines are over it, spread across twelve files:

| File | Over-length added lines |
|---|---|
| `AbstractMatchPlanStep.java` | 15 |
| `YTDBMatchPlanStepTest.java` | 9 |
| `MultiPlanMatchStepTest.java` | 5 |
| `SQLSuffixIdentifierTest.java`, `BoundaryStepTestSupport.java`, `StartStepRecogniser.java` | 3 each |
| `ReplayablePlanFixture.java`, `GremlinToMatchSmokeTest.java`, `YTDBQueryMetricsStrategyTest.java` | 2 each |
| `PromoteStaticRidsFromFiltersTest.java`, `YTDBMatchPlanStep.java`, `MultiPlanMatchStep.java` | 1 each |

All but two are Javadoc or comment lines, at 101-104 characters. Spotless does not catch them: `project-config/eclipse-formatter.xml` sets `format_javadoc_comments`, `format_block_comments`, and `format_line_comments` all to `false`, so the 100-column `lineSplit` applies to code only. The convention is documented and unenforced, which is how a diff this comment-heavy accumulates forty-five overruns without a red build.

The two non-comment cases are the `import static …BoundaryStepTestSupport.drainPayloads;` lines in both boundary test classes, at 115 characters. Imports cannot be wrapped, but this one need not exist: `BoundaryStepTestSupport` is in the same package as both importers, so `BoundaryStepTestSupport.drainPayloads(step)` compiles with no import at all and reads better at the call site — it names where the helper lives, which is the thing a reader of a 1500-line test class wants.

**Suggestion**: Reflow the forty-five comment lines to 100 columns — most are one word over. Drop the two static imports and qualify the calls.

### CQ7 [suggestion] The sticky-close-guard rationale is restated in nine places

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java` (lines 902-907)

**Issue**: "A closed plan cannot be rewound, because `AbstractExecutionStep`'s close guard is sticky and `ExecutionStepInternal.reset()` does not clear it, so a re-arm has to copy" is written out, at varying length, in nine places this diff touches:

1. Class Javadoc, Exhaustion bullet (66-68)
2. `State.REARMED_AFTER_CLOSE` (208-213)
3. `openArming()`'s start-failure comment (500-503)
4. `replaceClosedPlanWithCopy()` hook Javadoc (902-907) — the canonical statement
5. `YTDBMatchPlanStep.replaceClosedPlanWithCopy` inline comment
6. `MultiPlanMatchStep.replaceClosedPlanWithCopy` inline comment
7. `ReplayablePlanFixture` class Javadoc
8. `YTDBMatchPlanStepTest`'s "Re-iteration after close()" section comment
9. `MultiPlanMatchStepTest`'s "Re-iteration after close()" section comment

The "copy against the plan's own context, not `clone()`'s isolated child" argument has a parallel spread of six. Both subclass implementations show the right instinct half-applied: they say "the base's hook Javadoc gives the full reasoning" and then restate the short version anyway.

Each restatement is accurate today. The concern is the maintenance shape rather than any current defect: this track produced three separate findings about comments asserting behaviour the code did not have, and a rationale with nine copies is the population where the fourth comes from.

**Suggestion**: Keep the full argument in the hook Javadoc (4) and reduce the others to a one-clause statement plus `{@link #replaceClosedPlanWithCopy()}`. The subclass comments in particular can drop to their first sentence — the sentence that already points at the base.

### CQ8 [suggestion] Two counts in surrounding comments went stale with this diff

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java` (line 880)

**Issue**: Two statements the diff invalidated without updating:

- Line 880, the plan-seam hooks section comment: "The lifecycle primitives above drive one live stream at a time through these **four** hooks." The diff added `replaceClosedPlanWithCopy()` between `rewindPlan` and `startPlanStream`, so there are five: `planContext`, `rewindPlan`, `replaceClosedPlanWithCopy`, `startPlanStream`, `closePlan`. A subclass author reading the section header and counting is off by one.
- Line 598, `close()`'s Javadoc: "Idempotent via the CLOSED state." The gate is now `state == State.CLOSED || state == State.CLOSED_UNSTARTED` (616). As written, the sentence says a `close()` from `CLOSED_UNSTARTED` falls through to the plan close, which is the opposite of what the code does and of what `closeBeforeAnyIteration_thenReset_startsTheOriginalPlanRatherThanACopy` pins.

**Suggestion**: "these five hooks" and "Idempotent via the two closed states". The second could equally read "Idempotent: both closed states return early" if enumerating states in that sentence gets long.

## Evidence base
