# Track 8: SQL Executor & Result Sets

## Description

Write tests for SQL execution step classes, the SELECT planner, the
result-collection wrappers, and the metadata-execution helpers. This is
the largest coverage gap in the SQL layer (~2,109 uncov lines) but at
medium testability since most production classes here require a live
`DatabaseSessionEmbedded` to exercise their uncovered branches.

> **What**: Tests for `core/sql/executor/*` (60+ executor steps),
> `core/sql/executor/resultset/*` (ExecutionStream wrappers,
> Link/Embedded collection impls), and `core/sql/executor/metadata/*`
> (metadata execution helpers).
>
> **How**: DbTestBase by default for executor-step tests (per-track
> override of D2; mirrors Track 7 precedent). Direct-step tests
> (stubbed `AbstractExecutionStep` + manual `ResultInternal`
> predecessors) for step-internal branches; SQL round-trip reserved
> for `SelectExecutionPlanner` branch coverage. Falsifiable-regression
> + WHEN-FIXED markers for latent bugs. `@After rollbackIfLeftOpen` +
> `// forwards-to: Track NN` cross-track bug-pin convention.
>
> **Constraints**: In-scope: only the listed executor packages.
> Out-of-scope: `sql/parser` (generated), parser-coupled classes
> (Track 22). Production code changes limited to dead-code removal
> in Step 4 (FetchFromIndexStep).
>
> **Interactions**: Depends on Track 1. Track 22 absorbs CQ/TS/TC
> deferrals (~37 suggestion-tier items) plus 4 dead/semi-dead class
> deletion candidates (`InfoExecutionPlan`, `InfoExecutionStep`,
> `TraverseResult`, `BatchStep`).

## Progress
- [x] Review + decomposition
- [x] Step implementation (10/10 complete)
- [x] Track-level code review (3/3 iterations — PASS)

## Base commit
`c45c99432ca4d66e9766ceaea255c562995db0ff`

## Pre-Phase A rebase
- Pre-rebase HEAD: `a09a1a057aa61d27753bdbe7f42ac61b126f91ec`
- Post-rebase HEAD: `74fa861ab57856c28341c39b1c9fd2ceb7d84eb0`
- Rebased 103 commits from `origin/develop` onto `unit-test-coverage`; no conflicts.
- Post-rebase test suite: `./mvnw -pl core -am clean test -q` — exit 0 (PASS).
- Post-rebase `spotless:apply`: no changes required.

## Reviews completed
- [x] Technical (iter-1: 0 blockers / 4 should-fix / 5 suggestions) → `reviews/track-8-technical.md`
- [x] Risk (iter-1: 0 blockers / 4 should-fix / 4 suggestions) → `reviews/track-8-risk.md`
- [x] Adversarial (iter-1: 3 blockers / 5 should-fix / 3 suggestions) → `reviews/track-8-adversarial.md`

### Iteration-1 review resolution

All 3 adversarial blockers and the convergent should-fix items are
addressed by **plan-level adjustments** (no Decision Records modified —
ADJUST, not ESCALATE):

| Finding | Resolution |
|---|---|
| **A1** scope undersized (~7 → 9-11 steps) | Track 8 scope updated to ~10 steps; concrete decomposition below absorbs SelectExecutionPlanner, ResultInternal, FetchFromIndexStep, dead-code as their own steps. |
| **A2** Track 7 hand-off claim about `SQLScriptEngine` is structurally false | Track 7's strategy refresh corrected in the plan. Track 8 explicitly excludes `SQLScriptEngine` (deferred to Track 9 / Track 22). |
| **A3** D2 inverted by 76% of executor tests | Plan now records a **per-track override**: default to DbTestBase for executor steps; standalone reserved for delegating wrappers + pretty-printers. **D2 itself is unchanged** (still applies to functions/methods/utilities). |
| **T1** SubQueryStep already covered by 706-LOC test | De-scoped from advanced-step grouping; gap-check only in Step 6. |
| **T2/A1** FetchFromIndexStep is 1001 LOC | Dedicated Step 4 (its own decomposition step). |
| **T3** SQLScriptEngine handoff weakly grounded | Same as A2; resolved in Track 7 plan correction. |
| **T4/R3/A9** resultset has two distinct subgroups | Steps 8 and 9 split the resultset package. |
| **R2/A1/A8** SelectExecutionPlanner + ResultInternal need dedicated steps | Steps 7 (Result types) and 10 (SelectExecutionPlanner SQL round-trip). |
| **R4** match/ subpackage in/out of scope | Track-level scope explicitly excludes `match/**` (already 93%/79%). |
| **A4** dead-code pinning needed | Step 1 absorbs dead-code enumeration + WHEN-FIXED markers. |
| **A5** 85%/70% target realism | Acknowledged: aim for 85%/70% on the in-scope packages with planner SQL-round-trip in Step 10; if the planner step lands the package at ~82-84%, document the residual as Track 22 final-sweep candidates rather than chasing asymptotic coverage. |
| **A6** test-strategy ambiguity | Plan now codifies: direct-step tests as default; SQL round-trip reserved for SelectExecutionPlanner. |
| **A7** D1 ordering risk re EntityImpl/schema | Plan now codifies the `// forwards-to: Track NN` convention for cross-track bug pinning. |
| **R1/A1** step count growth | 10 steps decomposed up front (matches Track 6 / Track 7 actual counts). |

**No iteration-2 gate check** spawned: all findings target plan
description and decomposition strategy, not code; the decomposition
artifact below manifests every fix. Findings about test-class internals
(R5 ExecutorStepTestBase, R6 ParallelExecStep golden output, R7
SQLScriptEngine, R8 RetryStep wall-clock acceptance, A10 ParallelExecStep
ordering pin, A11 grep-existing-tests-first) are wired into individual
step descriptions below. Suggestion-grade items (T5–T9) likewise.

## Test-strategy precedent (carry-forward from Tracks 5–7)

- **DbTestBase by default** (per-track override of D2 — see plan).
- **`@After rollbackIfLeftOpen`** safety-net using
  `session.getActiveTransactionOrNull() + tx.isActive()` guard. DbTestBase
  shares one session across test methods in a class.
- **`session.commit()` detaches `Iterable<Vertex>` wrappers** — collect
  RIDs into a local `List` before committing.
- **Direct-step tests** = stub `AbstractExecutionStep` upstream + manually
  built `ResultInternal` predecessors. Pattern lives in
  `SubQueryStepTest.TrackingSourceStep`, `ExpandStepTest`,
  `CartesianProductStepTest`. Track `wasStarted` / `wasStreamClosed` /
  `step.close()` separately — `step.close()` propagates backward to
  `prev.close()` (via `AbstractExecutionStep.alreadyClosed` guard), not
  forward through the returned ExecutionStream.
- **`@Category(SequentialTest) + @FixMethodOrder + UUID-qualified markers`**
  for tests that mutate process-wide static state (none expected in
  Track 8 from current analysis — flag if discovered).
- **Counting CommandContext wrapper** for fallback-branch mutation kills
  when both primary and fallback resolve to identical values.
- **Falsifiable regression + `// WHEN-FIXED:` marker** convention for
  pinning latent bugs (production fixes deferred to Track 22).
- **`// forwards-to: Track NN`** convention for failures attributable to
  `record/impl` (Track 14/15), `metadata/schema` (Track 16), or
  `core/db` (Track 14) — pin and work around in Track 8, do not block.
- **`grep @Test <target-class>` and `grep "<target-class>"` in existing
  test files** is the FIRST action on each step — write only tests that
  exercise branches not already touched.

## Steps

- [x] Step 1: Shared executor test fixture + dead-code pinning
  - [x] Context: warning
  > **What was done:** Extended `TestUtilsFixture` with an `@After
  > rollbackIfLeftOpen()` safety net (Track 7 idiom — guards against
  > intra-class transaction leak in DbTestBase-shared sessions via
  > `session.isTxActive() → session.rollback()`). Created
  > `SqlExecutorDeadCodeTest` (14 tests, all standalone) pinning four
  > production classes confirmed via grep to have zero callers in
  > `core/src/main`: `InfoExecutionPlan` (23 LOC / 100% covered),
  > `InfoExecutionStep` (19 LOC / 100% covered), `TraverseResult`
  > (13/15 line, 8/14 branch — gap is the `assertIfNotActive` active-session
  > branch that needs DbTestBase), `BatchStep` (10/19 line — `mapResult` /
  > `internalStart` left uncovered by design; entire class dead, not worth
  > exhaustive testing since Track 22 deletes it). Each pin carries a
  > `// WHEN-FIXED: Track 22 — delete <class>` marker tied to specific
  > observable behaviors. Commit `77ac8e8b2d`; step-level review iter-1
  > applied via `Review fix: strengthen TraverseResult $depth pins + drop
  > javadoc link` (`7d29db2cd7`) addressing CQ1, CQ2, TB2, TC2 from the
  > dimensional review. All 14 new tests + 125 existing TestUtilsFixture-
  > extending tests pass.
  >
  > **What was discovered:**
  > - `InfoExecutionPlan.toResult` is annotated `@Nonnull` yet returns
  >   hardcoded `null`. The contract violation is itself the dead-code tell
  >   — any production caller would NPE immediately. Pinned.
  > - `InfoExecutionStep.toResult` builds a fresh `ResultInternal(session)`
  >   each call and discards all populated fields (`name`, `cost`, etc.).
  >   Structurally a stub. Pinned.
  > - `TraverseResult.setProperty` silently ignores non-Number `$depth`
  >   values (no exception, no warning) and truncates any Number via
  >   `intValue()` — so `Long.MAX_VALUE` → `-1`, `3.9` → `3`. Silent-
  >   data-loss surface a restored caller would inherit. Pinned as
  >   falsifiable regressions with the number-narrowing edge cases
  >   (Double truncation, BigDecimal, Long overflow).
  > - `BatchStep` public constructor has zero callers AND the private
  >   constructor is reachable only via `copy()` — the entire class is dead.
  >   `SQLBatch.evaluate` on a default-constructed (no `num`, no
  >   `inputParam`) SQLBatch falls through to `return -1`, so the public
  >   ctor is reachable with `batchSize = -1` (modulo by -1 = 0; every
  >   result would trigger a commit).
  > - Adding `@After rollbackIfLeftOpen()` to `TestUtilsFixture` is a
  >   no-op for the 10 existing subclasses (their tests don't leak
  >   transactions) and establishes the default for Track 8 Steps 2-10.
  >
  > **What changed from the plan:** Step 1 intentionally did not include
  > stub-step helpers (`stubPredecessor`, `stubExecutionPlan`) — the step
  > plan flagged these as tentative ("Decide during implementation; lean
  > toward extending TestUtilsFixture"). Deferred to Step 2 where the
  > concrete usage pattern will drive a cleaner extraction. Step file
  > updated with 14 tests instead of 13 after the review-fix
  > TraverseResult number-narrowing addition.
  >
  > **Deferred to Phase C track-level code review** (from iter-1, not
  > critical enough to iterate at context-warning within this session):
  > TB1 non-null-session branch for `InfoExecutionPlan.toResult` (needs
  > DbTestBase); TB3 exact `prettyPrint` indent assertion (vs.
  > `length() >` weak form); TB4 `getClass()==BatchStep.class` vs.
  > `instanceof`; TC1 `TraverseResult(session, Identifiable)` two-arg
  > ctor pin; TC3 `BatchStep.copy` with distinct `ctx` +
  > `profilingEnabled=true`; TC4 `InfoExecutionPlan` null-boundary
  > setters; TC5 direct test of `rollbackIfLeftOpen` branches; CQ3 DRY
  > helper for BatchStep construction; CQ4 Javadoc `--&gt;` HTML-escape
  > polish; CQ5 pattern-match `instanceof BatchStep batchCopy`; BC1
  > swallow rollback exceptions to preserve original assertion; BC2
  > mutation-kill hardening for `$depth` ignore path. 0 blockers.
  >
  > **Key files:**
  > - `core/src/test/java/.../sql/executor/TestUtilsFixture.java` (modified — +39 lines, `@After rollbackIfLeftOpen` + class Javadoc)
  > - `core/src/test/java/.../sql/executor/SqlExecutorDeadCodeTest.java` (new — 362 LOC, 14 tests)
  >
  > **Cross-track impact:** Track 22 queue unchanged (4 dead classes were
  > already on its cleanup list). Track 8 Steps 2-10 inherit the
  > rollbackIfLeftOpen safety net from TestUtilsFixture — no per-class
  > `@After` boilerplate needed. Component Map, Decision Records, and
  > track dependencies are unchanged.

Create the per-track test scaffolding and pin dead-code production classes
(per Track 7 Step 5 precedent). This step unblocks all subsequent steps.

**Tasks:**
1. Create or extend the test fixture base. Options:
   - Extend `TestUtilsFixture` (already in
     `core/src/test/java/.../sql/executor/TestUtilsFixture.java`,
     extends `DbTestBase`) to add `@After rollbackIfLeftOpen()`,
     `stubPredecessor(Result...)` helper, `stubExecutionPlan()` helper.
   - Or introduce `ExecutorStepTestBase` if `TestUtilsFixture`'s scope
     should remain narrow. Decide during implementation; lean toward
     extending `TestUtilsFixture` to avoid a parallel hierarchy.
2. Enumerate zero-caller classes via grep (verified in adversarial review
   certificate S2):
   - `InfoExecutionPlan` (23 uncov, 100% uncov)
   - `InfoExecutionStep` (19 uncov, 100% uncov)
   - `TraverseResult` (15 uncov, 100% uncov) — confirm `new TraverseResult`
     has zero callers
   - `BatchStep` (19 uncov, 100% uncov) — only self-instantiation in `copy()`
   Re-verify each via fresh `grep` before pinning; if any has a non-test
   caller introduced post-rebase, drop it from the pin list and add to
   the appropriate step instead.
3. Write `SqlExecutorDeadCodeTest` mirroring Track 7's
   `SqlQueryDeadCodeTest` shape: one test per dead class asserting
   `instantiate-and-call-each-public-method` with a `// WHEN-FIXED: …
   delete <class> in Track 22` marker that fails if the class becomes
   reachable from production code.
4. Update `coverage-baseline.md` (or write a `track-8-baseline.md`
   sibling) with the recomputed "reachable LoC" target for the executor
   package, excluding pinned dead classes, so the 85% target is honest.

**Coverage target:** No package-level delta expected (dead code stays
"covered" only via WHEN-FIXED test). Establish the realistic baseline.

**Key files:**
- `core/src/test/java/.../sql/executor/TestUtilsFixture.java` (extend)
- `core/src/test/java/.../sql/executor/SqlExecutorDeadCodeTest.java` (new)

---

- [x] Step 2: CRUD / write steps
  - [x] Context: warning
  > **What was done:** Added direct-step tests for all nine CRUD/write
  > execution steps: CreateRecordStepTest (13 tests), DeleteStepTest (8),
  > UpdateSetStepTest (5), UpdateRemoveStepTest (5), UpdateMergeStepTest
  > (5), UpdateContentStepTest (8), UpsertStepTest (9),
  > CopyRecordContentBeforeUpdateStepTest (7), InsertValuesStepTest (9)
  > — 69 tests total. All extend TestUtilsFixture and use direct-step
  > instantiation with BasicCommandContext + parser-backed AST nodes
  > extracted via YouTrackDBSql from representative SQL. Per-class
  > coverage meets target in every target:
  > `CreateRecordStep 100%/100%, DeleteStep 100%/83%, UpdateSetStep
  > 100%/75%, UpdateRemoveStep 100%/75%, UpdateMergeStep 100%/75%,
  > UpdateContentStep 100%/75%, UpsertStep 97.4%/83%,
  > CopyRecordContentBeforeUpdateStep 100%/78.6%, InsertValuesStep
  > 100%/95.5%`. Package-level: `sql/executor 75.1%/65.6% (1703 uncov)
  > → 78.2%/67.3% (1489 uncov)` — Step 2 reduced uncovered lines by 214.
  >
  > Commits: `8cc63ef7b9` (Step 2 tests) + `cac6a39eaa` (review fix
  > iter-1: TB1 singular-branch falsifiability via total=0 cross-check;
  > TB2 DeleteStep RID-precision; TB3 CopyRecord containsExactlyInAnyOrder
  > property-set pin; TB4 UpdateSet copy functional-equivalence;
  > CQ5 parseUpdateItems private-static).
  >
  > **What was discovered:**
  > - **UPDATE CONTENT input-parameter grammar gap.** The UPDATE grammar's
  >   CONTENT production accepts only Json() (no alternation to
  >   InputParameter), so `UPDATE X CONTENT :p` is a parse error. The
  >   SQLInputParameter-ctor path on `UpdateContentStep` is therefore
  >   reachable only through the INSERT grammar (`INSERT INTO X CONTENT
  >   :p`) or via CREATE EDGE CONTENT. Test-side workaround: parse the
  >   INSERT form and reuse the extracted parameter in the
  >   UpdateContentStep direct-step test. Documented in the test helper
  >   Javadoc. Track 22 candidate: either extend the UPDATE grammar to
  >   accept InputParameter in CONTENT, or drop the dead
  >   SQLInputParameter ctor from UpdateContentStep (currently only
  >   called by INSERT/CREATE EDGE planners, not UPDATE).
  > - **TC1 speculative NPE disproven.** Test-completeness reviewer
  >   hypothesized a latent NPE in
  >   `CopyRecordContentBeforeUpdateStep.mapResult` line 47 when the
  >   upstream entity's `getImmutableSchemaClass(session)` returns null.
  >   Empirically, `session.newEntity()` with no class produces an entity
  >   with default class "O" (schemaless but classed) — the null branch
  >   is not reachable via that construction path. Test was written, ran
  >   green (no NPE), and removed. A reachable construction (e.g.
  >   embedded entity from a LET subquery feeding RETURN BEFORE) would
  >   still merit a pin if someone can produce it; kept as Track 22
  >   backlog item with a note to re-investigate the embedded-entity
  >   pathway.
  > - **`UpdatableResult.previousValue` is package-private.** The field
  >   is `protected` in `com.jetbrains.youtrackdb.internal.core.sql.executor`
  >   and the test package matches, so direct field access is legal and
  >   more precise than exercising it through SQL round-trip. Pattern
  >   carry-forward for Step 7 (Result types).
  > - **Parser-as-fixture pattern.** All nine files reuse
  >   `new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes())).parse()`
  >   + cast to extract AST nodes (SQLUpdateItem, SQLUpdateRemoveItem,
  >   SQLJson, SQLFromClause, SQLWhereClause, SQLInputParameter, SQLExpression
  >   lists). This is cleaner than hand-constructing AST nodes via
  >   protected setters AND cleaner than reflection. Carry forward to
  >   Step 5 (IfStep/FilterStep WHERE-clause construction), Step 6
  >   (LetStep SQLStatement construction), and Step 10 (SelectExecutionPlanner
  >   round-trip — that's already its primary mode).
  > - **`DbTestBase.countClass` requires an active tx.** Verification
  >   after a commit must re-open a transaction just for the count
  >   assertion. DeleteStepTest uses `session.begin(); try { assertThat
  >   (session.countClass(...)); } finally { session.rollback(); }` for
  >   this — a pattern likely repeated in Steps 3/5/6.
  > - **InsertValuesStep$1 (inner ResultMapper) 58.3% branch coverage.**
  >   The uncovered branches are the defensive `!(result instanceof
  >   ResultInternal) && !result.isEntity()` guards. Non-ResultInternal
  >   results never appear in the production planner output. Exercising
  >   this would require a custom Result subclass — out of scope for
  >   Step 2's direct-step strategy. Noted in InsertValuesStepTest
  >   Javadoc as "Track 22 candidate".
  >
  > **What changed from the plan:** Step scope grew slightly from 6
  > target classes to 9 test files (one per step class) because
  > UpdateMergeStep and UpdateContentStep are distinct enough from
  > UpdateSetStep/UpdateRemoveStep (different ctor shapes, different
  > mutation semantics) that collapsing them into a single test file
  > would muddy scenario separation. Aligns with Track 7 precedent
  > (one test file per production class). Total 2,478 LOC added — in
  > line with the original scope estimate. No Component Map or
  > Decision Record changes.
  >
  > **Deferred to Phase C track-level code review** (from step-level
  > review iter-1, context at warning level prevented in-session
  > application):
  > - **CQ1/TS1 (DRY helper extraction):** newContext / sourceStep /
  >   drain are copy-pasted verbatim across all 9 new files (and ~20
  >   pre-existing executor tests). ~300 LOC deletion candidate.
  >   Plan explicitly defers this to Track 22 (see Track 7 iter-1
  >   CQ3/TS5). Track 8's Phase C should consider promoting these to
  >   TestUtilsFixture **now** rather than Track 22, since every Track 8
  >   step will use the same helpers — but respecting the plan's
  >   explicit Track 22 scoping is also defensible.
  > - **CQ2/TS5 (EntityImpl fully-qualified cast noise):** 30+ call
  >   sites use `(com.jetbrains.youtrackdb.internal.core.record.impl
  >   .EntityImpl)` inline rather than importing. Plain mechanical
  >   cleanup; ~2-3 LOC saved per call-site.
  > - **TS2 (parser-helper consolidation):** `parseTarget` / `parseWhere`
  >   in UpsertStepTest double-parse the same SQL per test. A single
  >   `parseUpdate(sql) -> SQLUpdateStatement` helper would eliminate
  >   the redundancy. Part of Track 22's SqlAstFixtures work.
  > - **TS4 (createVertexClassInstance / createEdgeClassInstance):**
  >   Two tests use `"Prefix_" + System.nanoTime()` for vertex/edge
  >   class names because TestUtilsFixture has only `createClassInstance()`.
  >   Minor DRY.
  > - **TB5/TB6 (copy-test functional equivalence for UpsertStep and
  >   UpdateContentStep InputParameter-variant):** same pattern as TB4
  >   (applied); remaining two clone-tests only verify isNotSameAs +
  >   prettyPrint header, not behavioral equivalence.
  > - **TB7 (indent width precision):** every `startsWith("    ")`
  >   assertion catches under-indent but not over-indent; tighter form
  >   `startsWith("    +").doesNotStartWith("     +")` would pin the
  >   exact width.
  > - **TB8/TB9/TB10:** UpdateMergeStep prettyPrint tokenization,
  >   InsertValuesStep ellipsis-boundary (size 2 vs 3 vs 4), null-fields
  >   copy behavior.
  > - **TC2-TC10:** completeness gaps (UPSERT non-equality operator
  >   silent-drop; InsertValuesStep typed-property coercion;
  >   InsertValuesStep wraparound cycling with rows > tuples; DeleteStep
  >   vertex+edge cascade; UpdateRemoveStep collection-element remove-
  >   with-value; CreateRecordStep negative-total boundary).
  > - **BC1 (pre-populate try/finally around begin/commit):** defensive
  >   hygiene — TestUtilsFixture's rollbackIfLeftOpen already catches
  >   leaks, so low risk but worth tightening later.
  > - **BC8 (TestUtilsFixture Javadoc on @After ordering):** document
  >   that subclass @After methods must not rely on session state before
  >   rollbackIfLeftOpen runs.
  >
  > **Key files:**
  > - `core/src/test/java/.../sql/executor/CreateRecordStepTest.java` (new — 13 tests, 370 LOC)
  > - `core/src/test/java/.../sql/executor/DeleteStepTest.java` (new — 8 tests, 251 LOC)
  > - `core/src/test/java/.../sql/executor/UpdateSetStepTest.java` (new — 5 tests, 219 LOC)
  > - `core/src/test/java/.../sql/executor/UpdateRemoveStepTest.java` (new — 5 tests, 205 LOC)
  > - `core/src/test/java/.../sql/executor/UpdateMergeStepTest.java` (new — 5 tests, 206 LOC)
  > - `core/src/test/java/.../sql/executor/UpdateContentStepTest.java` (new — 8 tests, 303 LOC)
  > - `core/src/test/java/.../sql/executor/UpsertStepTest.java` (new — 9 tests, 325 LOC)
  > - `core/src/test/java/.../sql/executor/CopyRecordContentBeforeUpdateStepTest.java` (new — 7 tests, 245 LOC)
  > - `core/src/test/java/.../sql/executor/InsertValuesStepTest.java` (new — 9 tests, 321 LOC)
  >
  > **Cross-track impact:** None to Component Map, Decision Records,
  > or track dependencies. Track 22 queue gains the deferred items
  > listed above (~10 cleanup entries). Pattern carry-forward for
  > Steps 3–10: `parseX(sql)` parser-as-fixture helper, UpdatableResult
  > .previousValue direct field access in test package,
  > `begin → count → rollback` verification pattern after a committed
  > seed.

Cover the entity-mutating execution steps. All require DbTestBase per the
per-track default.

**Targets** (~150 uncov lines combined):
- `CreateRecordStep` (90 LOC)
- `DeleteStep` (56 LOC)
- `UpdateSetStep` (60 LOC) — also exercise UpdateMergeStep, UpdateRemoveStep,
  UpdateContentStep, UpdateEdgeStep if uncov in the post-rebase report
- `UpsertStep` (100 LOC) — branch coverage for both
  `upstream.hasNext()=true` (no-op) and `upstream.hasNext()=false`
  (vertex/entity creation)
- `CopyRecordContentBeforeUpdateStep` (35 uncov, 100% uncov today) —
  verify `getImmutableSchemaClass(session)` path
- `InsertValuesStep` (36 uncov / 44 total) — both shape variants
  (single-row, multi-row)

**Test approach:**
- Direct-step instantiation with `BasicCommandContext` +
  `ctx.setDatabaseSession(session)`.
- Use schema fixture: `session.createClass(generateClassName())`.
- For UpdateSet/UpsertStep, manually build `SQLFromClause(-1)`,
  `SQLWhereClause(-1)`, `SQLUpdateItem` AST nodes (pattern lives in
  match/* tests); if scaffolding accumulates, factor a small package-
  private `SqlAstFixtures` helper (Track 22 DRY candidate).
- Iterable-detach pattern: collect RIDs to local List BEFORE
  `session.commit()`.
- Forward-to convention: any failure due to EntityImpl property setting,
  schema snapshot loading, or RID allocation gets `// forwards-to: Track
  14/15/16` and a workaround (commit-before-read, RID snapshot).

**Coverage target:** ~95% line / ~80% branch on the listed step files.

**Key files:**
- `core/src/test/java/.../sql/executor/CreateRecordStepTest.java` (new
  if not present; extend if there's a stub)
- One test class per step where natural; consolidate small steps
  (UpdateRemove/UpdateContent) if they share fixtures
- `core/src/main/java/.../sql/executor/CreateRecordStep.java` etc.

---

- [x] Step 3: Fetch source steps (FetchFromClass / FetchFromCollection / FetchFromRids / FetchFromVariable)
  - [x] Context: warning
  > **What was done:** Added direct-step tests for three fetch source steps
  > (`FetchFromClassExecutionStep`, `FetchFromCollectionExecutionStep`,
  > `FetchFromRidsStep`) and verified `FetchFromVariableStep` is already
  > adequately covered by the pre-existing `FetchFromVariableStepTest`
  > (95.5%/84.6%, above target — no gap-fill needed). All three new test
  > classes extend `TestUtilsFixture` to inherit the `rollbackIfLeftOpen`
  > safety net. Initial commit `ed1715ca6a` (47 tests, ~1,387 LOC); step-
  > review iter-1 applied via `104b25a23b` (added 5 tests + tightened
  > 15+ assertions). Final test counts: Class 18, Collection 18, Rids 16 =
  > **52 tests total**. Per-class coverage:
  > `FetchFromClassExecutionStep 100%/96.2%`, `FetchFromCollectionExecutionStep
  > 100%/100%`, `FetchFromRidsStep 100%/100%`, `FetchFromVariableStep
  > 95.5%/84.6%` — all exceed the 85%/70% target.
  >
  > **What was discovered:**
  > - **Round-robin collection distribution forces population-aware test
  >   design.** `FetchFromCollectionExecutionStep` tests that verify ordering
  >   (ASC/DESC) within a specific collection cannot seed just 3 records —
  >   round-robin distribution across ~4-8 polymorphic collections means each
  >   collection likely receives only 1 record. Seeding 24 records and using
  >   a `pickCollectionWithAtLeast(rids, 3)` helper (or the single-RID
  >   constructor-style tests) is the robust pattern. Carry forward for any
  >   Step 4+ test that asserts within-collection ordering.
  > - **`LoaderExecutionStream.RecordNotFoundException` silently terminates
  >   the stream** (not just skips the missing RID). `FetchFromRidsStep` with
  >   input `[real, missing, real]` yields only the first record — the second
  >   real RID after the missing one is NEVER emitted (Step 3 TC1 pin).
  >   Documented contract, worth pinning because a refactor to `continue`
  >   would silently change cardinality. Forwards-to: if any Track 8+ test
  >   observes this truncation, pin via the same idiom.
  > - **`FetchFromClassExecutionStep.copy()` field-copy omission is masked by
  >   a single-record dataset.** Neither `orderByRidAsc` nor `orderByRidDesc`
  >   is observable via 1-element iteration output (sorting the collection-ID
  >   array is a no-op on 1 collection). Iter-1 fix: use the `serialize()`
  >   probe on the copy to pin both flags (carry forward to any Step 4+ test
  >   asserting copy semantics on a step with multiple boolean fields).
  > - **`FetchFromCollectionExecutionStep`'s `prettyPrint` coalesces `null`
  >   and `ORDER_ASC` into "ASC"** because the formula is
  >   `ORDER_DESC.equals(order) ? "DESC" : "ASC"`. A deserialize round-trip
  >   test that only asserts prettyPrint cannot distinguish "sentinel properly
  >   restored" from "sentinel lost". Re-serializing the restored step and
  >   asserting `getProperty("order")` is the correct pin (Step 3 TB2/BC2).
  > - **`queryPlanning` field is unobservable from outside the step.** The
  >   `copyWithNonNullPlanningInfoInvokesPlanningCopy` test originally claimed
  >   to pin the non-null branch of `queryPlanning == null ? null :
  >   queryPlanning.copy()`, but that ternary's two branches produce no
  >   behavioral difference — the field is private, has no getter, and is
  >   not consulted at execution time (explicit in production Javadoc).
  >   Resolution: documented the test as branch-coverage-only with a
  >   comment pointing to the future-upgrade path if a getter is added.
  >   Similar one-way-field patterns in other Track 8 targets should follow
  >   the same documentation convention (pin the ternary for coverage, name
  >   it honestly).
  > - **`FetchFromRidsStep` honors insertion order even when caller provides
  >   a reversed list.** Step 3's `preservesInsertionOrder` test passes
  >   `[rid3, rid1, rid2]` and confirms the step does not re-sort. Pinned
  >   explicitly because a future planner optimization that dedupes/sorts
  >   the RID list would silently reorder results.
  >
  > **What changed from the plan:** Test count went from scope-indicator
  > ~3-5 (no per-file count given) to 3 new files × 52 tests. `FetchFromVariableStep`
  > gap-fill was not needed (existing coverage already exceeds target). No
  > Component Map or Decision Record changes; `pickCollectionWithAtLeast`
  > helper emerged as a Track 8-local idiom but is small enough to inline
  > per-file rather than extracting to `TestUtilsFixture` (defer to Track 22
  > if the same helper appears in Step 4+).
  >
  > **Deferred to Phase C track-level code review** (iter-1 context-warning
  > prevented further iteration):
  > - **CQ2 / TS-R4 (DRY — stubPredecessorTrackingStartClose):** The 27-line
  >   AbstractExecutionStep stub that tracks `prevStarted`/`prevClosed` is
  >   now copy-pasted in 12 executor test files (9 from Step 2 + 3 from
  >   Step 3). Promoting to `TestUtilsFixture` as
  >   `protected static ExecutionStepInternal stubPredecessorTrackingStartClose
  >   (CommandContext, AtomicBoolean, AtomicBoolean)` would save ~20 LOC per
  >   test file across Steps 2-10. Either apply in Phase C or route to
  >   Track 22 alongside the `newContext`/`drain` consolidation already on
  >   the queue.
  > - **TB1/CQ7 residual:** If `queryPlanning` ever becomes execution-
  >   relevant (or a getter is added to the step), strengthen
  >   `copyWithNonNullPlanningInfoReachesNonNullBranch` to assert the copy
  >   carries a distinct, deep-copied `QueryPlanningInfo` instance. Currently
  >   marked branch-coverage-only in the test Javadoc.
  > - **BC3 / TC completeness:** step-specific property unboxing paths in
  >   `deserialize` (e.g. boolean-unbox NPE when "orderByRidAsc" is missing)
  >   are not exercised directly. Current tests pin only the
  >   `basicDeserialize` / `ClassNotFoundException` wrap. Minor; routes to
  >   Phase C consideration.
  > - **BC5 / TS-M5:** `FetchFromRidsStepTest.predecessorIsStartedAndClosedBefore
  >   Iterating` does not wrap `drain` in a `session.begin/rollback` for
  >   consistency with the other two sibling predecessor-drain tests. The
  >   empty RID list means no storage is touched; `rollbackIfLeftOpen`
  >   catches any leak. Stylistic only.
  > - **TC5/TC6/TC8/TC9:** lower-priority completeness gaps — invalid
  >   collection IDs at start(), filter with non-matching names (distinct
  >   from empty filter), empty filter × ridOrder ASC/DESC, and non-sentinel
  >   order string in deserialize. All can be added in Phase C or Track 22
  >   without blocking Track 8 Steps 4+.
  >
  > **Key files:**
  > - `core/src/test/java/.../sql/executor/FetchFromClassExecutionStepTest.java` (new — 18 tests, 534 LOC)
  > - `core/src/test/java/.../sql/executor/FetchFromCollectionExecutionStepTest.java` (new — 18 tests, 561 LOC)
  > - `core/src/test/java/.../sql/executor/FetchFromRidsStepTest.java` (new — 16 tests, 477 LOC)
  >
  > **Cross-track impact:** None to Component Map, Decision Records, or track
  > dependencies. Track 22 queue gains the `stubPredecessorTrackingStartClose`
  > DRY candidate (alongside the `newContext`/`drain` consolidation already
  > queued from Step 2). Pattern carry-forward for Steps 4-10:
  > seed-enough-records-and-pick-populated-collection idiom for round-robin
  > distributed fixtures; re-serialize restored step to pin sentinel
  > deserialization (not just prettyPrint); `serialize()` probe on the copy
  > to pin field propagation when the step has multiple boolean/nullable
  > fields; document branch-coverage-only tests honestly when the observable
  > surface doesn't distinguish branches.

Cover the smaller fetch source steps. **Excludes FetchFromIndexStep**
(Step 4) and `FetchFromVariableStepTest` already exists — verify
coverage delta and only fill gaps.

**Targets** (~150 uncov lines combined):
- `FetchFromClassExecutionStep` (213 LOC)
- `FetchFromCollectionExecutionStep` (40 uncov / 40 total — currently
  fully uncovered)
- `FetchFromRidsStep` (96 LOC)
- `FetchFromVariableStep` — gap-fill only; existing test is
  `FetchFromVariableStepTest`

**Test approach:**
- Direct-step instantiation with stub upstream where applicable.
- For `FetchFromClassExecutionStep`: vary `cls.isVertexType()` /
  `isEdgeType()` / regular class branch; vary cluster targeting.
- For `FetchFromRidsStep`: cover both materialized RID list and
  parsed-from-AST RID iterator paths; null/empty input branch.
- For `FetchFromCollectionExecutionStep`: cover the variable-resolution
  branches (the class is fully uncov today — likely it covers iteration
  of LET-bound collections).

**Coverage target:** ~90% line / ~80% branch on the listed steps.

**Key files:**
- New tests as needed under `core/src/test/java/.../sql/executor/`
- Source: `FetchFromClassExecutionStep.java`,
  `FetchFromCollectionExecutionStep.java`, `FetchFromRidsStep.java`

---

- [x] Step 4: FetchFromIndexStep (dedicated)
  - [x] Context: warning
  > **What was done:** Added `FetchFromIndexStepTest` (direct-step tests) —
  > 37 tests (36 initial + 1 from iter-1 review) across the full operator
  > matrix: constructor / `canBeCached` / `copy` / `reset` / `isOrderAsc`,
  > `init()` dispatch (null-definition via Mockito, null/AndBlock/other
  > condition shapes including the `UnsupportedOperationException`
  > catch-all), full-scan (asc/desc, with/without null keys, `ignoreNullValues`
  > metadata), the operator matrix (`=`, `>`, `>=`, `<`, `<=`) against a
  > NOTUNIQUE single-property index, the additional-range-condition branch,
  > `SQLInCondition` cartesian expansion, composite-index equality and
  > cartesian-product over a leading IN, predecessor-drain guard,
  > `readResult` key/rid/$current population, the four
  > `SQLContains{,Any,Text,Value}Condition` sub-block arms of
  > `indexKeyFromIncluded`/`indexKeyToIncluded`, `serialize` property shape
  > (indexName / orderAsc / condition / additionalRangeCondition),
  > `prettyPrint` (header / profiling / key-condition / additional-range),
  > and the `deserialize` bug-pin (see below).
  >
  > Also removed **163 lines of dead code** from `FetchFromIndexStep.java`
  > — five `private static` methods (`processInCondition`,
  > `processBetweenCondition`, `processBinaryCondition`, `createCursor`,
  > `toIndexKey`) with zero production callers. The responsibility they
  > once held was already fully subsumed by `multipleRange` + the
  > `indexKeyFromIncluded`/`ToIncluded` inclusivity calculators. Spotless
  > auto-pruned the two imports that became orphaned
  > (`SQLBetweenCondition`, `SQLEqualsOperator`). User explicitly approved
  > this cleanup ("Can we remove dead code then?"), which is why it landed
  > with Step 4 rather than being deferred to Track 22.
  >
  > Step-level code review ran 1 iteration (4 baseline dimensional
  > agents: code quality / bugs & concurrency / test behavior / test
  > completeness). 4 convergent should-fix findings across reviewers:
  > **BC1 = CQ4 = TB1** (identical issue flagged by 3 agents) — the
  > `assertThatReachesProcessAndBlock` helper caught `RuntimeException`
  > wholesale, which swallowed the `UnsupportedOperationException` that
  > mutation-testing would rely on to signal a missing inclusivity-branch
  > arm. Fixed in commit `a61b676292` by separating the
  > `UnsupportedOperationException` catch (which now rethrows as
  > `AssertionError`) from the broader `RuntimeException` catch (downstream
  > value-resolution failures, including the expected NPEs from deliberately
  > incomplete contains-condition AST, stay acceptable). Also applied TB2
  > (prettyPrint cost-marker pattern pinning), TB3 (copy verifies
  > profilingEnabled via prettyPrint equivalence), and TC1 (empty-IN
  > boundary test — zero results, not a full scan via null-key collapse).
  > ~10 suggestion-grade items deferred to Phase C / Track 22 (documented
  > in the review-fix commit body).
  >
  > Coverage: `FetchFromIndexStep.java` went from
  > **71.9% line / 60.8% branch** (pre-dead-code-removal) to
  > **91.1% line / 73.7% branch** after the removal; both thresholds
  > cleared. Package-level: `sql/executor` 79.7%/68.1% (1386 uncov) →
  > **80.7%/68.9% (1302 uncov)** — Step 4 reduced uncovered lines by 84
  > (64 from dead-code deletion, the rest from new test coverage).
  >
  > **What was discovered:**
  > - **Pre-existing production bug in `SQLBooleanExpression.deserializeFromOResult`
  >   (line 316)**: uses `Class.forName(name).getConstructor(Integer.class)`
  >   but every concrete AST class (SQLAndBlock, SQLOrBlock, SQLNotBlock,
  >   SQLInCondition, SQLContainsCondition, etc.) declares its id-only
  >   constructor with **primitive `int`, not boxed `Integer`** —
  >   `getConstructor` does not auto-unbox, so lookup always throws
  >   `NoSuchMethodException`. Practical impact: plan-cache round-trip
  >   (`serialize` → `deserialize`) is broken for every `FetchFromIndexStep`
  >   whose key condition wraps an `SQLAndBlock` (essentially all
  >   non-trivial indexed queries). Pinned as
  >   `deserializingAndBlockConditionHitsIntegerConstructorBug` with
  >   `hasRootCauseInstanceOf(NoSuchMethodException.class) +
  >   hasRootCauseMessage("…SQLAndBlock.<init>(java.lang.Integer)")`.
  >   **WHEN-FIXED: Track 22** — change `deserializeFromOResult` to use
  >   `int.class` (primitive).
  > - **Dead code** (verified by repo-wide grep of all 5 method names):
  >   `processInCondition`, `processBetweenCondition`,
  >   `processBinaryCondition`, `createCursor`, `toIndexKey` had zero
  >   production callers. `init()` dispatches only to
  >   `processFlatIteration` / `processAndBlock`; neither of those
  >   transitively invokes any of the deleted methods. Removing them
  >   shrinks the measured surface so the 85/70 coverage target is
  >   achievable without chasing unreachable branches.
  > - **Parser wraps WHERE expressions in `SQLOrBlock(SQLAndBlock(SQLNotBlock(condition)))`**
  >   even for simple single-condition WHEREs. The `parsedKeyCondition`
  >   helper now peels the outer OR (when single-subblock) plus each
  >   not-negated NotBlock to give the downstream code a clean AND block.
  > - **Mockito + JaCoCo class-load gotcha**: for
  >   `indexWithoutDefinitionProducesEmptyStream` (where
  >   `index.getDefinition()` returns null), the step's `init()` early-
  >   return is reached cleanly without a transaction because the null
  >   check fires before `tx.preProcessRecordsAndExecuteCallCallbacks()`
  >   can touch anything. (Actually in practice, start() calls
  >   `tx.preProcessRecordsAndExecuteCallCallbacks()` FIRST, then
  >   `init()`. So a real transaction is still needed.)
  > - **`session.begin()` must wrap `step.start(ctx)` + stream drain**:
  >   index lookups demand an active transaction for both the
  >   `preProcessRecordsAndExecuteCallCallbacks` prelude AND the storage
  >   read. Pattern is codified as `startAndDrain(step, ctx)`.
  > - **`SQLInCondition.setRightMathExpression` requires `SQLMathExpression`,
  >   not `SQLExpression`**. `SQLValueExpression extends SQLExpression`
  >   so the latter cannot be passed. Added a `valueMathExpr` helper
  >   using Mockito (matching the `IndexSearchDescriptorCostTest` pattern).
  > - **UNIQUE indexes do NOT ignore null values by default**; that
  >   behavior requires explicit `METADATA { ignoreNullValues: true }`
  >   via the multi-arg `createIndex(name, type, null, Map, String[])`
  >   overload. Initial assumption (UNIQUE = null-ignored) was wrong.
  >
  > **What changed from the plan:** Step scope closely matches the
  > original decomposition. Scope-indicator "600-900 LOC" for the test
  > file ended at 1,133 LOC (including helpers); the extra length comes
  > from thorough dead-code-removal coverage, the pinning of the
  > Integer-constructor bug, and iter-1 review-fix additions. No new
  > steps, no plan corrections. Dead-code removal accepted mid-step
  > rather than deferred to Track 22 — user approval was explicit.
  >
  > **Deferred to Phase C track-level code review** (suggestion-grade
  > items from iter-1 that don't disturb the Step 4 contract):
  > - **CQ1** four lines over 100-char in Javadoc prose.
  > - **CQ2** four fully-qualified class references (`java.util.Map`,
  >   `InstanceOfAssertFactories`, `SQLOrBlock`, `SQLNotBlock`) instead of
  >   explicit imports.
  > - **CQ3** duplicate begin/commit seed block across the two composite-
  >   index tests — candidate for a `seedComposite(className, rows[])`
  >   helper alongside `seed` / `seedNull` / `seedAndReturnRid`.
  > - **CQ5** / **CQ7** comment wording and bug-pin message-string
  >   fragility.
  > - **TB4** / **TB5** deserialize-unknown-index test-name/behavior
  >   mismatch, and additional-range round-trip assertion tightening.
  > - **TC2** additional range combined with IN / ContainsAny / Contains
  >   inclusivity sub-branch (reaches `isIncludeOperator(additionalOperator)
  >   && isGreaterOperator(additionalOperator)`).
  > - **TC3** cartesian-product size-mismatch `DatabaseException` (line
  >   462-465) — requires mocked SQLExpression returning collections of
  >   divergent sizes.
  > - **TC4** composite map-index CONTAINSKEY / CONTAINSVALUE wrapping
  >   inside `convertToIndexDefinitionTypes` (lines 629-646).
  > - **TC5** descending-scan combined with key condition (the
  >   `isOrderAsc=false` path through `multipleRange`'s
  >   `streamEntriesBetween` call sites).
  >
  > **Key files:**
  > - `core/src/main/java/.../sql/executor/FetchFromIndexStep.java`
  >   (modified — −163 lines dead code + imports pruned by Spotless).
  > - `core/src/test/java/.../sql/executor/FetchFromIndexStepTest.java`
  >   (new — 1,133 LOC, 37 tests, parser-as-fixture + Mockito-for-
  >   structural-edges pattern).
  >
  > **Cross-track impact:** Track 22 queue **loses** the
  > FetchFromIndexStep dead-code-deletion entry (landed in Step 4). Track
  > 22 queue **gains** the Integer-constructor bug fix for
  > `SQLBooleanExpression.deserializeFromOResult` (affects all concrete
  > AST classes, so plan-cache reload of any non-trivial indexed query is
  > broken). Pattern carry-forward for Steps 5-10: `startAndDrain(step,
  > ctx)` helper shape, `parsedKeyCondition` parser-as-fixture with
  > OrBlock/NotBlock peeling, `valueMathExpr` Mockito helper for
  > `SQLInCondition.setRightMathExpression`, the narrow-catch-on-
  > UnsupportedOperationException idiom when a test asserts "a specific
  > branch ran" via side-effect-free reachability.

Cover `FetchFromIndexStep` (1001 LOC, 136 uncov / 399 counted) — a
combinatorial surface of WHERE-condition × index-definition × single-/
multi-value lookup.

**Strategy:** Direct-step tests using parser-AST construction (`new
SQLBinaryCondition(-1)`, `new SQLBetweenCondition(-1)`, `new
SQLInCondition(-1)`, `new SQLContains*Condition(-1)`), each combined with
a real `Index` created via `session.createClass(...).createProperty(...)
.createIndex(...)`. Group test methods by condition type:

1. Point lookup (`SQLBinaryCondition` with `=`)
2. Range (`SQLBinaryCondition` with `>` / `<` / `>=` / `<=`)
3. Between (`SQLBetweenCondition`)
4. IN (`SQLInCondition`)
5. ContainsKey (map indexes)
6. ContainsValue (map indexes)
7. ContainsAny (collection indexes)
8. ContainsText (fulltext)
9. Composite key (`CompositeKey`) over multi-property index
10. Multi-value index (`IndexDefinitionMultiValue`) per
    `PropertyTypeInternal` variant

**Test approach:** Direct-step. Each test creates a minimal class +
property + index, populates 3-5 records, builds a stub upstream that
yields the search descriptor, asserts the streamed result matches the
expected RID set.

**Coverage target:** ≥85% line / ≥70% branch on `FetchFromIndexStep.java`
alone. Acknowledge any branches reachable only from real planner output
(`IndexSearchDescriptor` paths Filter creates) — those land in Step 10
via `session.query(sql)` round-trip.

**Key files:**
- `core/src/test/java/.../sql/executor/FetchFromIndexStepTest.java` (new)
  — likely 600-900 LOC; do NOT split unless it exceeds 1,000 LOC (Track 22
  cleanup if so).
- Source: `core/src/main/java/.../sql/executor/FetchFromIndexStep.java`

---

- [x] Step 5: Control-flow steps + ParallelExecStep
  - [x] Context: warning
  > **What was done:** Added 83 direct-step tests across 6 test files covering
  > FilterStep, LimitExecutionStep, SkipExecutionStep, IfStep, ForEachStep, and
  > ParallelExecStep (the last is an extension — the existing 1-test trivial
  > construction-only test was replaced with 15 comprehensive tests). All
  > per-class coverage targets exceed 85%/70%:
  >
  > | Class | Line | Branch |
  > |---|---|---|
  > | FilterStep | 91.9% | 90.0% |
  > | LimitExecutionStep | 100% | 87.5% |
  > | SkipExecutionStep | 100% | 87.5% |
  > | IfStep | 98.5% | 95.0% |
  > | ForEachStep | 100% | 86.4% |
  > | ParallelExecStep | 98.6% | 97.5% |
  >
  > Package `sql/executor`: **80.7%/68.9% → 82.3%/70.7%** (the branch metric
  > crosses the 70% threshold for the first time).
  >
  > Commits: `c1a231afc4` (Step 5 tests, 80 tests) + `575b5f2836` (review fix
  > iter-1: 9 should-fix findings applied, 3 new coverage tests added).
  >
  > Step-level code review ran 1 iteration with 5 dimensional agents (CQ, BC,
  > TB, TC, TS baseline + test-structure). Results: 0 blockers, 13 should-fix
  > applied, ~40 suggestion-grade items deferred to Phase C / Track 22.
  > Applied:
  > - CQ1/TS2 rewrote FilterStepTest.serializeStoresWhereClauseProperty
  >   javadoc (was self-contradictory about a reflection path not taken);
  > - CQ4/TB1 replaced 4 vacuous `canBeCached()` "didn't throw" guards in
  >   LimitExecutionStepTest.{sendTimeoutIsNoOp, closeWithoutPrevIsNoOp} and
  >   SkipExecutionStepTest equivalents with `assertThatCode(...)
  >   .doesNotThrowAnyException()`;
  > - TB2 pinned LimitExecutionStep copy null-limit branch via reflection on
  >   the private `limit` field (was coverage-only before);
  > - TB3 replaced `getClass().getSimpleName()` string checks with
  >   `isInstanceOf(ExpireResultSet.class)` in FilterStep timeout tests;
  > - TB4 added element-level `isNotSameAs` to IfStepTest.copyDeepCopies (a
  >   mutation reusing SQLStatement references would now fail);
  > - TB6/BC2 pinned ParallelExecStepTest.copy with list-level AND
  >   element-level `isNotSameAs` (was size-only, missed aliasing mutations);
  > - TB9 tightened ParallelExecStep 5-sub-plan arrow assertion — "contains
  >   -, +, |" is trivially true from block separators, so pin at least one
  >   addArrows-generated junction "+" at a non-zero column instead;
  > - TB10 pinned SkipExecutionStepTest copy via reflection on the private
  >   `skip` field (copy uses `skip.copy()` with no null guard; without the
  >   reflection pin, an alias-vs-copy mutation passed the prettyPrint check);
  > - TC1 added `internalStartReturnsEarlyWhenBodyPlanReturnsNonNull` for
  >   ForEachStep (the early-return branch — RETURN in body — was unpinned);
  > - TC6 added ParallelExecStep empty-sub-plan-list tests for
  >   `internalStart` (empty stream) and `prettyPrint` (header-only render).
  >
  > **What was discovered:**
  > - **SQLBooleanExpression Integer-constructor bug affects every
  >   boolean-expression-serializing step, not just FetchFromIndexStep.**
  >   Confirmed empirically by adding a second falsifiable-regression pin
  >   through `FilterStepTest.serializeDeserializeHitsIntegerConstructorBug`
  >   — a serialize → deserialize round-trip of a FilterStep fails with
  >   `NoSuchMethodException(SQLOrBlock.<init>(java.lang.Integer))` at the
  >   exact same site that broke in Step 4. The bug is in
  >   `SQLBooleanExpression.deserializeFromOResult` (line 316) — uses
  >   `Class.forName(name).getConstructor(Integer.class)` where every
  >   concrete AST subclass declares the primitive-int ctor instead. Track 22
  >   fix is a one-line change (`int.class` instead of `Integer.class`) and
  >   simultaneously unblocks plan-cache round-trip for every indexed query
  >   AND boolean-expression-using step (filter, fetch-from-index, etc.).
  > - **`ExecutionStepInternal.canBeCached()` default is `false`.** Stub
  >   steps used as sub-plan contents MUST override `canBeCached()` to `true`
  >   for `ParallelExecStep.canBeCached` tests to meaningfully distinguish
  >   "all cacheable" from "any non-cacheable". Led to extracting named
  >   `YieldingStubStep` and `NonCacheableStubStep` classes (instead of the
  >   anonymous-subclass pattern elsewhere in Track 8) so `copy()` could be
  >   non-throwing — `ParallelExecStep.copy` recursively copies every
  >   sub-plan's steps.
  > - **`ScriptExecutionPlan.executeFull()` returns null for pure SELECT
  >   bodies** (documented contract, lines 174-199). This makes ForEachStep
  >   with a SELECT body always run its while-loop to completion — the
  >   loop variable ends bound to the LAST iterator element. The early-return
  >   branch requires a RETURN statement in the body (TC1 pin). Without
  >   understanding this contract, the pin for `internalStartBindsLoop
  >   Variable...` would appear wrong.
  > - **SQL grammar accepts negative `LIMIT` as a sentinel for "no limit".**
  >   `LimitExecutionStep.internalStart` has an explicit `if (limitVal == -1)
  >   return prev.start(ctx)` short-circuit. Exact-match, not `< 0` — any
  >   other negative value would fall into `result.limit(negative)` and
  >   presumably fail downstream. Pinned via `limitMinusOneReturnsUpstream
  >   Unchanged`.
  > - **FilterStep's `registerBooleanExpression` side-effect** is the real
  >   mechanism enabling `GlobalLetQueryStep` to decide subquery
  >   materialization. Confirmed by tracing to `CommandContext
  >   .getParentWhereExpressions()` usage in `GlobalLetQueryStep`. Pinned
  >   with a `CapturingContext` subclass that records every registered
  >   expression — mutation-kill: dropping the register call passes the
  >   filtered-stream assertion but fails the context-capture assertion.
  > - **`ParallelExecStep` is named "Parallel" but is strictly sequential.**
  >   The `MultipleExecutionStream` iterates sub-plans one at a time in
  >   declared order. The A10 adversarial pin
  >   (`subPlansExecuteInDeclaredOrder`) fails if a future refactor ever
  >   switches to real concurrency or reorders sub-plans.
  > - **IfStep's condition/statement fields are package-private/public and
  >   written directly by the planner** (not via setters). Tests reach into
  >   these fields directly to pin null-vs-non-null-vs-empty branches — the
  >   only way to reach these branches since the constructor is zero-arg.
  >   Acceptable given the intentionally-direct-access design.
  >
  > **What changed from the plan:** None substantive. Step scope matches the
  > decomposition (6 target classes, ~140 uncov combined → now ~24 uncov).
  > Test count grew from scope-indicator ~20-30 to 83 as every branch got its
  > own mutation-killing pin (the Track 6/7/8 precedent). Dead-code Pin:
  > ParallelExecStepTest was "extended" by near-total rewrite because the
  > existing 1-test construction-only test had no assertions on execution
  > results (recognized in Step 1-like pattern but not marked as a Track 22
  > delete candidate — current structure is retained and the new 15 tests
  > replace its role). No Component Map or Decision Record changes.
  >
  > **Deferred to Phase C track-level code review** (suggestion-grade items
  > from iter-1 that don't disturb the Step 5 contract):
  > - **CQ2/TS6:** Replace fully-qualified `com.jetbrains.youtrackdb.internal
  >   .core.db.DatabaseSessionEmbedded` cast in `ParallelExecStepTest
  >   .YieldingStubStep` with a proper import. Same for `SQLBooleanExpression`
  >   FQN in `FilterStepTest.CapturingContext`.
  > - **CQ5:** Drop line-number comments (e.g. "line 45", "line 95", "line
  >   199") across the six files — they describe implementation details that
  >   go stale.
  > - **CQ6:** Remove unnecessary `(SQLStatement)` casts in `List.of((
  >   SQLStatement) new SQLReturnStatement(-1))` — targeted-type inference
  >   handles this.
  > - **CQ7:** ParallelExecStepTest doesn't extend TestUtilsFixture (verified
  >   safe — no session work); document the rationale with a class-level
  >   comment.
  > - **CQ8/TS1 (DRY):** `newContext()` / `drain(stream, ctx)` /
  >   `sourceStep(ctx, rows)` duplicated across 6 new files (and 12+ existing
  >   executor tests from Steps 2-4). Already on the Track 22 queue from
  >   Track 7 iter-1 (CQ3/TS5). Step 5 adds ~120 LOC of duplication; consider
  >   promoting at least `newContext` and `drain` to TestUtilsFixture in
  >   Track 22 now that the pattern spans 10+ files.
  > - **CQ9:** `copyText.split("\n")[1]` indexing is obscure — replace with
  >   line-based comparison of the full multiline output or a more targeted
  >   contains.
  > - **CQ10:** Redundant `isNotNull()` followed by `isNotEmpty()` in
  >   IfStepTest (the second assertion would NPE on null and produce a clear
  >   message).
  > - **CQ11/TS4:** "Copyright 2018" banner on new files authored in 2026.
  > - **CQ12:** AssertJ's `AtomicBoolean` specialized assertion works
  >   implicitly — some reviewers may prefer `.get().isTrue()` for clarity.
  > - **TB5/TB13:** `initPositivePlanChainsAllStatements` uses
  >   `hasSizeGreaterThanOrEqualTo(2)`; `initNegativePlanChains...` uses
  >   `isNotEmpty()`. Tighten to exact-size or `prettyPrint` fingerprint.
  > - **TB7/TB8:** `FilterStepTest.prettyPrint*` use `contains("μs")` /
  >   `doesNotContain("(0")`; tighter regex or first-line end-pattern would
  >   pin the profiling-suffix branch exactly.
  > - **TB11:** ForEachStep variable-binding test could cite the
  >   `ScriptExecutionPlan.executeFull` contract in the javadoc.
  > - **TB12:** `FilterStepTest.serializeStoresWhereClauseProperty` is
  >   shallow (`isNotNull`); could deserialize the sub-result and pin its
  >   structure.
  > - **TB14:** `IfStepTest.getConditionReturnsWhatSetConditionStored` is a
  >   trivial getter/setter pin; accepted but not behavior-strong.
  > - **TC2-TC5, TC7-TC15:** Various completeness gaps (ForEach scalar/null
  >   source, LIMIT/SKIP non-Number parameter error paths, ParallelExecStep
  >   error propagation, IfStep null-condition NPE, FilterStep order
  >   preservation with `containsExactly`, ParallelExecStep copy with
  >   different ctx instance, SkipExecutionStep upstream-exhausted spy,
  >   deeply-nested IfStep.containsReturn). All gap-fill candidates for
  >   Phase C or Track 22; not blocking.
  > - **BC1:** `internalStartBindsLoopVariableToLastElement...` is coupled
  >   to the `ScriptExecutionPlan.executeFull` return-null contract; could be
  >   strengthened with a counter that pins "loop iterated exactly 3 times".
  > - **BC3-BC7:** Minor structural / clarity concerns — no correctness
  >   issues.
  > - **TS5/TS7/TS8:** Comment/readability polish and direct-field-access
  >   justification notes for IfStepTest.
  >
  > **Key files:**
  > - `core/src/test/java/.../sql/executor/FilterStepTest.java` (new — 15 tests, ~510 LOC)
  > - `core/src/test/java/.../sql/executor/LimitExecutionStepTest.java` (new — 13 tests, ~310 LOC)
  > - `core/src/test/java/.../sql/executor/SkipExecutionStepTest.java` (new — 12 tests, ~290 LOC)
  > - `core/src/test/java/.../sql/executor/IfStepTest.java` (new — 18 tests, ~390 LOC)
  > - `core/src/test/java/.../sql/executor/ForEachStepTest.java` (new — 10 tests, ~295 LOC)
  > - `core/src/test/java/.../sql/executor/ParallelExecStepTest.java` (rewritten — 15 tests, ~390 LOC; replaces a 42-LOC construction-only test)
  >
  > **Cross-track impact:** None to Component Map, Decision Records, or
  > track dependencies. Track 22 queue gains ~15 suggestion-grade items
  > (enumerated in "Deferred to Phase C" above), most of which overlap with
  > items already on the queue from Steps 2-4 (DRY helpers, stale
  > line-number comments, copyright banner year). The
  > SQLBooleanExpression.deserializeFromOResult Integer-constructor bug is
  > now pinned through TWO steps (Step 4 FetchFromIndexStep +
  > Step 5 FilterStep) — the fix in Track 22 will unblock both pins
  > atomically. Pattern carry-forward for Steps 6-10: `assertThatCode(...)
  > .doesNotThrowAnyException()` idiom for no-op methods; reflection-based
  > field-independence pins for `copy()` semantics when prettyPrint alone
  > can't distinguish alias from deep-copy; named-inner-class stubs
  > (`YieldingStubStep`, `NonCacheableStubStep`) when a stub's `copy()` is
  > structurally required to be non-throwing.

### (archived step-5 plan)
- [~archived] Step 5: Control-flow steps + ParallelExecStep
Cover Filter, If, ForEach, Limit, Skip, ParallelExecStep — including
branches that LET steps depend on (e.g., `FilterStep`'s
`ctx.registerBooleanExpression(whereClause.getBaseExpression())`
side-effect at line 61).

**Targets** (~140 uncov lines combined):
- `FilterStep` (123 LOC) — predicate match/mismatch, timeout wrap into
  `ExpireResultSet` (timeoutMillis branch >0 vs ==0), parent-context
  whereExpression registration (mutation-kill via
  `ctx.getParentWhereExpressions().contains(expr)`)
- `LimitExecutionStep` (95 LOC), `SkipExecutionStep` (89 LOC) — boundary
  values (limit=0, limit=Long.MAX, skip=upstream.size())
- `IfStep` (136 LOC) — `producePlan()`, `initPositivePlan()`,
  `initNegativePlan()`, `containsReturn()` via stub SQLStatement OR via
  end-to-end `IF (...)` script
- `ForEachStep` (96 LOC) — empty collection, single, multi
- `ParallelExecStep` (53 uncov / 68 total — 78% uncov) — needs golden-
  output prettyPrint test (parameterized over sub-plan sizes 1, 2, 3, 5)
  + concatenation correctness test using 2 StubExecutionPlan instances.
  **Add ordering-pin test** `parallelExecStepSubPlansExecuteInDeclared
  Order` that fails if planner ever switches to real concurrency
  (Adversarial finding A10).

**Test approach:** Direct-step where possible. For IfStep/ForEachStep
end-to-end coverage of the SQLStatement branches, mix with `session
.command("BEGIN; ...; COMMIT")` script execution inheriting the
existing `IfStatementExecutionTest` pattern.

**Coverage target:** ≥90% line / ≥75% branch per file.

**Key files:**
- `core/src/test/java/.../sql/executor/{Filter,Limit,Skip,If,ForEach,
  ParallelExec}StepTest.java` (new or extended)
- Sources in `core/src/main/java/.../sql/executor/`

---

- [x] Step 6: Advanced steps (Retry, Timeout, Let, Unwind, Batch); SubQueryStep gap-check
  - [x] Context: warning
  > **What was done:** Added direct-step tests across 9 new test files
  > covering every advanced-step target: `RetryStepTest` (16 tests),
  > `TimeoutStepTest` (9), `AccumulatingTimeoutStepTest` (9),
  > `LetExpressionStepTest` (11), `LetQueryStepTest` (9),
  > `GlobalLetExpressionStepTest` (8), `GlobalLetQueryStepTest` (11),
  > `UnwindStepTest` (13), `BatchStepTest` (7) — **93 tests total, ~3,292
  > LOC**. All extend `TestUtilsFixture` (inheriting `rollbackIfLeftOpen`);
  > all pass on first run after fixture fixes. SubQueryStep was verified
  > via JaCoCo to already be at 100%/100% line/branch via the pre-existing
  > 17-test `SubQueryStepTest` — no gap-fill needed. Per-class coverage
  > (measured via the narrow executor-step-only coverage build):
  >
  > | Class | Line | Branch |
  > |---|---|---|
  > | RetryStep | 95.6% | 88.5% |
  > | TimeoutStep | 100% | 75.0%† |
  > | AccumulatingTimeoutStep | 93.8% | 75.0%‡ |
  > | LetExpressionStep | 90.0% | 75.0% |
  > | LetQueryStep | 97.0% | 77.8% |
  > | GlobalLetExpressionStep | 100% | 100% |
  > | GlobalLetQueryStep | 96.6% | 80.8% |
  > | UnwindStep | 97.9% | 85.0% |
  > | BatchStep | 100% | 83.3% |
  > | SubQueryStep | 100% | 100% |
  >
  > †TimeoutStep: 1 uncovered branch is the phantom `assert prev != null`
  > JaCoCo artifact (CLAUDE.md tip 10). ‡AccumulatingTimeoutStep: 1
  > uncovered branch is the same assertion artifact. All production-relevant
  > branches are covered; the 75%/83% numbers reflect JaCoCo's synthetic
  > `$assertionsDisabled` tracking, not real coverage gaps. Every target
  > clears the 85%/70% threshold once assertion-phantom branches are
  > excluded.
  >
  > Commit: `c8f0d0ebfd` (Step 6 tests — no review-fix commit yet; see
  > "Deferred to Phase C" below).
  >
  > **What was discovered:**
  > - **Anonymous-subclass initializer shadowing**. In
  >   `new SQLTimeout(-1) { { this.failureStrategy = failureStrategy; } }`,
  >   the unqualified `failureStrategy` on the RHS resolves to the INHERITED
  >   protected field (which is null at that point), NOT the captured local
  >   parameter. The assignment becomes a silent self-copy of null. Fixed
  >   by renaming the parameter to `failureStrategyArg`. Same trap hit in
  >   `SQLBatch`'s `num` field (test file `BatchStepTest`) — renamed to
  >   `numArg`. Pattern carry-forward: whenever an anonymous AST subclass
  >   writes an inherited protected field from a same-named parameter,
  >   rename the parameter. Applies to all future `SQLTimeout`/`SQLBatch`/
  >   similar-AST scaffolds in Tracks 8-22.
  > - **`executeFull()` always runs the else body when retries exhaust,
  >   then consults `elseFail` — not short-circuited**. My first
  >   `internalStartRunsElseBodyWhenNonEmptyAfterExhaustedRetries` test
  >   assumed `drain(...)` would return empty because the RetryStep would
  >   short-circuit on the else body returning `null` from `executeFull()`.
  >   Actually the method flows: (1) run else body, (2) if its plan returns
  >   a RETURN-carrying result, short-circuit; (3) otherwise fall through
  >   to the `elseFail` gate — which THROWS with `elseFail=true`. Rewrote
  >   the test to use `elseFail=false` for the "else body ran, step
  >   returned empty" assertion, and added a separate test
  >   (`internalStartReturnsElseBodyReturnValueWhenExhausted`) that uses
  >   `new SQLReturnStatement(-1)` in the else body to exercise the
  >   short-circuit branch at RetryStep line 66-68. Together these two
  >   tests pin both arms of the executeFull-result-null ternary.
  > - **SQLBooleanExpression Integer-constructor bug affects every LET
  >   step too**, not just fetch/filter. A third falsifiable-regression
  >   pin lands through `LetExpressionStepTest
  >   .deserializeRoundTripHitsIntegerConstructorBug`. Track 22's
  >   one-line fix (`int.class` instead of `Integer.class` in
  >   `SQLBooleanExpression.deserializeFromOResult` line 316) simultaneously
  >   unblocks plan-cache round-trip for FetchFromIndexStep (Step 4),
  >   FilterStep (Step 5), and LetExpressionStep (Step 6) — all three
  >   falsifiable pins will flip together.
  > - **AccumulatingTimeoutStep timeout firing requires wall-clock time
  >   accumulation via hasNext()**. Its `TimeoutResultSet` tracks elapsed
  >   nanoseconds only during `internal.hasNext()` and `internal.next()`
  >   calls. Creating a `ResultInternal` and iterating an in-memory list
  >   takes microseconds, not milliseconds — so a timeoutMillis=0 test
  >   needs a `Thread.sleep(5)` inside the upstream's hasNext to
  >   deterministically cross the 1ms threshold. Codified as
  >   `slowSendTimeoutPredecessor(ctx, sentTimeout, rows, sleepMillis)`
  >   helper. R8 wall-clock-flakiness concern acknowledged: the 5ms sleep
  >   is well above any CI-sched-jitter tolerance.
  > - **RetryStep rollback-swallow branch is reachable via Mockito**. I
  >   initially wrote a complex `ThrowingRollbackContext` +
  >   `RollbackThrowingSession` wrapper hierarchy; simplified to a
  >   Mockito `mock(DatabaseSessionEmbedded.class) + doThrow(...).when(...)
  >   .rollback()` combined with an anonymous `BasicCommandContext`
  >   override of `getDatabaseSession()`. ~15 LOC vs 50 LOC for the
  >   wrapper approach. Pattern carry-forward: use Mockito for narrow
  >   session-method behavior injection; never hand-roll a
  >   `DatabaseSessionEmbedded` subclass.
  > - **`SQLUnwind.items` must be `ArrayList`, not `List.of()`-mutable**:
  >   the production `items.stream().map(x -> x.copy())` in `SQLUnwind.copy`
  >   works with any List, but our anonymous-subclass initializer uses
  >   `this.items = new ArrayList<>(...)` to remain safe for potential
  >   mutation in the per-test flow. No production bug — just a
  >   test-fixture quirk.
  >
  > **What changed from the plan:** Step 6 scope matches the decomposition
  > exactly; no new steps, no plan corrections. Scope-indicator estimated
  > "~180 uncov lines combined" — actual new test LOC ~3,292 (expected
  > range for thorough direct-step coverage per Track 6/7/8 precedent).
  >
  > **Deferred to Phase C track-level code review** (skipped the
  > step-level dimensional review under context-warning pressure — 36%
  > at commit time; mirrors Steps 2, 3, 4, 5 deferral precedent):
  > - **CQ (expected)**: duplicated `newContext` / `drain` / `sourceStep`
  >   helpers now repeat across 15+ executor test files (Steps 1-6); the
  >   DRY extraction to `TestUtilsFixture` is a Track 22 cleanup candidate
  >   already on the queue from Step 5.
  > - **CQ**: fully-qualified AST class references in a few test files
  >   (e.g. `com.jetbrains.youtrackdb.internal.core.query.Result`) — minor
  >   cleanup.
  > - **TB**: stronger `isInstanceOf` pins instead of `contains("+ LET")`
  >   string-only checks for prettyPrint — current form passes on any
  >   copy-paste-rendered output; exact match would pin the exact header.
  > - **TB**: AccumulatingTimeoutStep RETURN-strategy test currently
  >   asserts `assertThatCode(...).doesNotThrowAnyException()` on the
  >   first next() call AFTER hasNext — tightening to assert the returned
  >   `Result` is an empty `ResultInternal` (per TimeoutResultSet's
  >   fallback at line 46-47) would pin the "timedOut → empty ResultInternal"
  >   branch precisely.
  > - **TC**: UnwindStep's `MultiValue.getMultiValueIterator` invocation
  >   branch is exercised via `Integer[]` but not via `Object[]` (a
  >   mutation that swapped the array iterator for a fixed-type one would
  >   still pass). Minor.
  > - **TC**: UnwindStep empty-collection behavior was tested via
  >   `new ArrayList<>()`; additional coverage for `Set`/`Map` variants
  >   would be thorough but redundant given the test verifies the
  >   iterator-level behavior.
  > - **TC**: RetryStep `ExecutionThreadLocal.isInterruptCurrentOperation()`
  >   true-arm is structurally unreachable from a JUnit test thread
  >   (requires `SoftThread`). Deliberately unpinned; marked R8 in plan.
  > - **TS**: `StubStatement` + `StubInternalPlan` helpers duplicated
  >   across RetryStepTest, LetQueryStepTest, GlobalLetQueryStepTest. DRY
  >   candidate for Track 22 alongside `StubExecutionPlan` already in
  >   SubQueryStepTest.
  > - **BC**: RetryStep's `ctx.getDatabaseSession()` null-return path is
  >   not exercised (the mock test returns a non-null mock); a future
  >   mutation that changed `db.rollback()` to `db == null` guard would
  >   pass. Minor.
  > - **Copyright year**: new files carry "Copyright 2018" header from
  >   template; actual authorship year is 2026. Track 22 banner cleanup
  >   catches this.
  >
  > **Key files:**
  > - `core/src/test/java/.../sql/executor/RetryStepTest.java` (new — 16 tests, 558 LOC)
  > - `core/src/test/java/.../sql/executor/TimeoutStepTest.java` (new — 9 tests, 276 LOC)
  > - `core/src/test/java/.../sql/executor/AccumulatingTimeoutStepTest.java` (new — 9 tests, 310 LOC)
  > - `core/src/test/java/.../sql/executor/LetExpressionStepTest.java` (new — 11 tests, 284 LOC)
  > - `core/src/test/java/.../sql/executor/LetQueryStepTest.java` (new — 9 tests, 365 LOC)
  > - `core/src/test/java/.../sql/executor/GlobalLetExpressionStepTest.java` (new — 8 tests, 247 LOC)
  > - `core/src/test/java/.../sql/executor/GlobalLetQueryStepTest.java` (new — 11 tests, 370 LOC)
  > - `core/src/test/java/.../sql/executor/UnwindStepTest.java` (new — 13 tests, 316 LOC)
  > - `core/src/test/java/.../sql/executor/BatchStepTest.java` (new — 7 tests, 252 LOC)
  >
  > **Cross-track impact:** None to Component Map, Decision Records, or
  > track dependencies. Track 22 queue gains a 3rd SQLBooleanExpression
  > pin site (LetExpressionStep joins FetchFromIndexStep and FilterStep —
  > all three unblock atomically when the Integer/int.class fix lands).
  > Track 22 also gains: StubStatement/StubInternalPlan DRY candidate,
  > anonymous-subclass-shadowing-parameter-renaming documentation for
  > future AST test fixtures, slowSendTimeoutPredecessor helper promotion.
  > Pattern carry-forward for Steps 7-10: Mockito session mock idiom for
  > narrow rollback/tx failure injection; anonymous-subclass field-writer
  > with renamed parameters; RETURN-statement stub for exercising
  > "plan returned a RETURN step" branches without SingleOpExecutionPlan
  > gymnastics; Thread.sleep-in-upstream-hasNext for wall-clock-based
  > timeout testing.

Cover the remaining "advanced" execution steps. SubQueryStep gets a
coverage-delta check only.

**Targets** (~180 uncov lines combined):
- `RetryStep` (105 LOC) — counting-stub SQLStatement that throws
  `NeedRetryException` N times; parametric tests across `retries × elseBody
  × elseFail`: (a) succeed first try, (b) succeed Nth retry, (c)
  exhaust + elseBody=null + elseFail=true (re-throw), (d) exhaust +
  elseBody=empty + elseFail=false, (e) exhaust + elseBody non-empty
  succeeds, (f) `ExecutionThreadLocal.isInterruptCurrentOperation()`
  mid-retry. Real wall-clock concurrent-transaction scenarios pinned with
  WHEN-FIXED + integration-test marker (R8).
- `TimeoutStep` (51 LOC) + `AccumulatingTimeoutStep` (16 uncov / 100%
  uncov) — exercise both branches of the timeout check; flaky wall-clock
  enforcement pinned with WHEN-FIXED.
- `LetExpressionStep` (130 LOC), `LetQueryStep` (206 LOC),
  `GlobalLetExpressionStep` (85 LOC), `GlobalLetQueryStep` (191 LOC) —
  variable resolution: both materialize and non-materialize paths via
  counting CommandContext wrapper (carry-forward from Track 7 iter-2).
- `UnwindStep` (148 LOC) — single value, collection, iterator, null
- **SubQueryStep** — coverage-delta check only. If
  `SubQueryStepTest` keeps it ≥85% line / ≥75% branch in the post-Track-8
  report, no new tests. Otherwise add 1-2 targeted tests.

**Coverage target:** ≥85% line / ≥70% branch on the listed steps.

**Key files:**
- `core/src/test/java/.../sql/executor/{Retry,Timeout,Let*,Unwind,
  Batch}StepTest.java` (new)
- Sources in `core/src/main/java/.../sql/executor/`

---

- [x] Step 7: Result types — ResultInternal, UpdatableResult, TraverseResult
  - [x] Context: warning
  > **What was done:** Added 144 direct tests across 3 new test files
  > covering the three record-wrapper classes used by every executor step.
  > All extend `TestUtilsFixture` (for the `@After rollbackIfLeftOpen`
  > safety net) and all pass alongside the full `core` suite
  > (`./mvnw -pl core clean test` → 1544 tests, 0 failures). JaCoCo
  > coverage delta (Step 7 tests + Step 1 dead-code pins, measured via the
  > narrow surefire run):
  >
  > | Class | Line | Branch |
  > |---|---|---|
  > | ResultInternal | 90.4% (595/658) | 80.4% (393/489) |
  > | UpdatableResult | 95.8% (69/72) | 50.0% (31/62)† |
  > | TraverseResult | 86.7% (13/15) | 85.7% (12/14) |
  >
  > †UpdatableResult's raw branch number looks low because the class has
  > 28 `assert checkSession()` lines. Per CLAUDE.md tip 10, each assert
  > statement contributes 1 phantom uncovered branch (the false / assert-
  > failure side, which never fires in tests). Excluding those phantoms,
  > real branch coverage is ~91% (3 non-assert missed out of 34 non-assert
  > branches — well above the 70% target). Both line target (85%) and
  > adjusted branch target (70%) met on all three classes.
  >
  > Test strategy:
  > - **ResultInternalTest (117 tests)**: constructor variants (null-
  >   session, sized hint, map population, identifiable-holding); all
  >   `convertPropertyValue` type-switch branches (LinkBag, Blob bytes /
  >   RID, persistent / embedded Entity, ContextualRecordId →
  >   metadata + plain RecordId, plain Identifiable, Result with same
  >   session / different session, Object[] / List scalar / RID /
  >   promotion / empty, ResultSet materialization, Set / Map scalar / RID
  >   / mixed-rejection / non-String-key rejection, unsupported-type →
  >   `CommandExecutionException`); temporary properties (including
  >   Result-carrying-entity unwrap); metadata get/set/add with null-key
  >   guard; setIdentifiable (embedded flatten → content, ContextualRecordId
  >   → plain RecordId, plain RID); type checks (isEntity / isBlob /
  >   isEdge / isVertex with real records plus null-identifiable early
  >   returns); asEntity / asRecord / asBlob / asEdge including OrNull
  >   variants; property accessors getEntity / getVertex / getEdge /
  >   getBlob / getResult / getLink with both RID-resolution and missing-
  >   property-returns-null paths; hasProperty / getPropertyNames; toMap
  >   (projection vs entity delegation); toJSON (scalars, `@`-prefix
  >   sorting, Number, Boolean, String, null, RID, nested Result, Map,
  >   Date, byte[], primitive int[] arrays via direct `content.put`,
  >   Iterator via direct `content.put`, unsupported type → UOE, null-key
  >   comparator guard); toString (projection vs identifiable); equals
  >   (reflexive, different-type, by-identity, asymmetric identifiable-vs-
  >   projection, by-property-contents with size mismatch); hashCode;
  >   detach (sessionless projection + entity-backed); refreshNonPersistentRid
  >   (persistent guard + null-identifiable guard); static toResult /
  >   toResultInternal factories (null input, already-a-Result pass-
  >   through, Identifiable with/without alias, Map with/without alias,
  >   Map.Entry with non-String-key rejection, scalar with default "value"
  >   or supplied alias); toMapValue for every type case (Edge → RID, Blob
  >   → bytes, embedded Entity → Map, persistent Entity → RID, nested
  >   Result → Map, EntityLinkListImpl / EntityLinkSetImpl / EntityLinkMapIml
  >   via `session.newLinkList() / newLinkSet() / newLinkMap()`,
  >   List/Set/Map with recursive conversion, unsupported-type → IAE,
  >   scalars pass-through).
  > - **UpdatableResultTest (25 tests)**: invariant tests
  >   (isIdentifiable=true, isEntity=true, isProjection=false, isBlob=false,
  >   asBlob throws DatabaseException); delegation tests for every method
  >   (setProperty / removeProperty, getEntity / getResult / getVertex /
  >   getEdge / getLink / getBlob, hasProperty / getPropertyNames / detach
  >   / toMap / toString / getIdentity / asEntity / asRecord / isEdge /
  >   asEdge + OrNull); toJSON with persisted + unloaded-entity path
  >   (drives the `entity.isUnloaded() && session != null` branch); and
  >   both arms of setIdentifiable (Entity shortcut vs
  >   Identifiable → `transaction.loadEntity`). `@Before createUrEClass`
  >   hoists schema mutations out of the per-test transaction (Track 8
  >   precedent: `createXClass` outside `session.begin()`).
  > - **TraverseResultLiveSessionTest (3 tests)**: covers the
  >   `session != null` half of the `assertIfNotActive` short-circuit —
  >   the one gap Step 1's `SqlExecutorDeadCodeTest` could not reach
  >   (those pins use `new TraverseResult(null)`, which leaves the
  >   non-null-session branch uncovered). Exercises the case-sensitive
  >   `$depth` getter/setter plus delegation-to-super-setProperty with
  >   an active session. Kept as a standalone companion file rather than
  >   merging into `SqlExecutorDeadCodeTest` because the latter uses
  >   standalone tests; the live-session variant needs `DbTestBase`.
  >
  > Commit: `63881dc740` (Step 7 tests — no step-level dimensional review
  > commit; see "Deferred to Phase C" below).
  >
  > **What was discovered:**
  > - **Raw Iterators can't flow through `setProperty`**. `convertPropertyValue`
  >   has explicit switch arms for LinkBag, Blob, Entity, Identifiable,
  >   Result, Object[], List, ResultSet, Set, and Map, then a default arm
  >   that rejects anything `PropertyTypeInternal.getTypeByValue` doesn't
  >   recognize. A bare `Iterator` falls through to the default and throws
  >   `CommandExecutionException`. To test the Iterator branch of
  >   `toJson()` we must stuff the iterator directly into the protected
  >   `content` map (bypassing `convertPropertyValue`). Codified with an
  >   inline comment so a future reader doesn't try `setProperty(name,
  >   myIterator)` expecting it to work.
  > - **`Entity.toJSON()` requires a persistent @class link**. An entity
  >   created via `session.newEntity("Class")` but never committed has a
  >   non-persistent identity (`#N:-M`). When `toJSON()` writes the
  >   `@class` metadata as a link (`JSONSerializerJackson.writeMetadata`),
  >   it throws `SerializationException: Cannot serialize non-persistent
  >   link: #N:-M`. Fix: persist first, re-fetch via `SELECT FROM Class`,
  >   then exercise the `toJSON` path on the re-loaded entity. Pattern
  >   carry-forward: any Step-7..10 test that calls `toJSON()` on an
  >   entity must use a committed entity, not a transient one. Applied
  >   twice in this step (ResultInternalTest.toJsonOnEntityDelegatesToEntityJson
  >   and UpdatableResultTest.toJsonDelegatesToEntityToJson).
  > - **`LINKLIST` / `LINKSET` / `LINKMAP` property types reject plain
  >   `ArrayList` / `HashSet` / `HashMap`**. An attempt like
  >   `owner.setProperty("links", new ArrayList<>(linked), PropertyType.LINKLIST)`
  >   throws `IllegalArgumentException: Data containers have to be created
  >   using appropriate getOrCreateXxx methods`. The fix is to build the
  >   container via `session.newLinkList() / newLinkSet() / newLinkMap()`,
  >   which returns an instance of `EntityLinkListImpl` /
  >   `EntityLinkSetImpl` / `EntityLinkMapIml` wired to the current
  >   transaction. Pattern carry-forward to Tracks 14/15 (record/impl,
  >   EntityImpl property tests): never hand-build `LINK*` containers —
  >   always use the session factory methods.
  > - **Schema mutations must live outside `session.begin()` /
  >   `session.executeInTx()`**. `session.createClass("X")` or
  >   `getSchema().getOrCreateClass("X")` called inside an active
  >   transaction throws `SchemaException: Cannot change the schema
  >   while a transaction is active`. The UpdatableResultTest initially
  >   did this inside a helper called from within `session.begin()` and
  >   18 tests cascade-failed. The fix is a `@Before createUrEClass`
  >   method that runs BEFORE DbTestBase's per-test `session.begin()` in
  >   the individual tests. Matches the precedent from Track 8's
  >   CreateRecordStepTest / DeleteStepTest which create classes outside
  >   `begin()`. Carry-forward: schema setup goes in `@Before` or at the
  >   top of the test method; transactions start after.
  > - **`JaCoCo assert-statement phantom branch arithmetic**: The
  >   CLAUDE.md tip 10 formula applied to UpdatableResult — 28 asserts
  >   contribute 28 phantom uncovered branches, explaining why raw
  >   branch coverage is 50% while real branch coverage (excluding
  >   phantoms) is ~91%. Pattern to carry forward into Steps 8, 9, 10:
  >   when reviewing JaCoCo branch numbers for classes with heavy
  >   `assert` usage, always compute `(covered) / (total - assert_count)`
  >   to get the real picture. Track 22 could optionally extract
  >   `checkSession` into a static helper (Track 14's pattern) to
  >   reclaim the phantoms, but that's a future-session call.
  >
  > **What changed from the plan:** Step 7 scope matches the decomposition
  > almost exactly. Two divergences from the step-description targets:
  > - `UpdatableResult` line coverage (95.8%) is above target; branch
  >   (50% raw, ~91% real) is documented with the phantom-adjustment
  >   rationale rather than chasing the asymptotic 70% raw number with
  >   speculative tests.
  > - `TraverseResult` was mostly pinned in Step 1; Step 7 only added 3
  >   live-session tests rather than a full suite. Given the class is
  >   dead code queued for Track 22 deletion, exhaustive tests would be
  >   wasted effort.
  >
  > **Deferred to Phase C track-level code review** (skipped step-level
  > dimensional review at context-warning — mirrors Steps 2-6 precedent):
  > - **CQ**: The `(Object) r.getProperty(...)` cast-to-disambiguate
  >   AssertJ `assertThat` overloads is repeated across ResultInternalTest
  >   and could be wrapped in a local `getProp(r, name)` helper. Minor.
  > - **CQ**: `r.content.put(...)` direct manipulation (used twice —
  >   toJsonIteratorPath + toJsonSortComparatorHandlesNullPropertyNames +
  >   toJsonThrowsOnUnsupportedValueType + toJsonEncodesJavaArraysAsJsonArrays)
  >   relies on same-package access to `protected Map<String, Object>
  >   content`. Load-bearing for coverage; test maintainability
  >   acceptable given the explanatory inline comments. An alternative
  >   would be a package-private `ResultInternalTestHelper` on the
  >   production side — overkill for these 4 tests.
  > - **CQ**: `toMapValueEntityLink*ImplReturnsListOfRids` tests use
  >   `instanceof EntityLinkListImpl linkList` pattern-match guards —
  >   if the factory methods ever change their return type, the tests
  >   silently pass with the guard failing. Strengthen to a
  >   `.isInstanceOf(EntityLinkListImpl.class)` pre-assertion.
  > - **TB**: `equalsReflexiveAndHandlesDifferentType` uses the boolean
  >   return of `.equals(r)` — could be pinned tighter via
  >   `assertThat(r).isEqualTo(r)` to cover the AssertJ equality
  >   contract, but the raw-boolean form pins the actual method.
  >   Marginal preference.
  > - **TC**: The BlobsPersistentReturnsRid path (blob.save() commit +
  >   test the persistent branch of `convertPropertyValue`) is skipped —
  >   only the non-persistent bytes path is pinned. A future regression
  >   that flipped `isPersistent()` inversion would still be caught by
  >   other tests that round-trip persistent blobs (e.g., Track 14 /
  >   Track 15 EntityImpl tests). Minor gap.
  > - **TC**: `EntityLinkSetImpl` / `EntityLinkMapIml` tests only check
  >   `isInstanceOf(Set.class)` / `isInstanceOf(Map.class)` without
  >   inspecting sizes. A mutation that returned the input collection
  >   unchanged would pass. Tighten with `.hasSize(1).extractingOne(...)`
  >   in Phase C.
  > - **TC**: `getEdgeResolvesRidToEdge` passes an Edge instance to
  >   `setProperty` which goes through `convertPropertyValue` and
  >   becomes a RID. The `instanceof Edge edge` second branch inside
  >   `getEdge(name)` is structurally unreachable because Edge extends
  >   Identifiable (first branch always matches first). Pin as
  >   dead-code via WHEN-FIXED in Phase C or Track 22.
  > - **TS**: `newContext` / `drain` helper duplication continues from
  >   Steps 1-6; DRY to `TestUtilsFixture` is a Track 22 candidate
  >   already on the queue.
  > - **BC**: `basicResultInternalInterfaceExposesSetPropertyAndSetMetadata
  >   AndSetIdentity` dropped the setIdentity portion because the
  >   identifiable-cleared-content path requires a session (for isBlob).
  >   Could add a DbTestBase variant, but the identity path is covered
  >   by `setIdentityDelegatesToSetIdentifiable`. Minor.
  >
  > **Key files:**
  > - `core/src/test/java/.../sql/executor/ResultInternalTest.java`
  >   (new — 117 tests, ~1,380 LOC)
  > - `core/src/test/java/.../sql/executor/UpdatableResultTest.java`
  >   (new — 25 tests, ~420 LOC)
  > - `core/src/test/java/.../sql/executor/TraverseResultLiveSessionTest.java`
  >   (new — 3 tests, ~55 LOC)
  >
  > **Cross-track impact:** None to Component Map, Decision Records, or
  > track dependencies. Track 22 queue gains: (a) potential
  > `ResultInternal.checkSession` extraction for phantom-branch
  > elimination, (b) dead `if (result instanceof Edge edge)` branch in
  > `ResultInternal.getEdge(name)` (unreachable because Edge extends
  > Identifiable), (c) the `getProp(r, name)` AssertJ-disambiguation
  > helper if the pattern recurs across Steps 8-10 + Track 22 tests.
  > Pattern carry-forward for Steps 8, 9, 10: (1) persist-then-re-fetch
  > for any entity-level `toJSON()` test; (2)
  > `session.newLinkList/Set/Map()` for LINK* property types — never
  > plain ArrayList/HashSet/HashMap; (3) `@Before` schema setup outside
  > any `session.begin()`; (4) `r.content.put(...)` direct manipulation
  > for driving post-`convertPropertyValue` branches that convertPropertyValue
  > itself rejects; (5) the CLAUDE.md tip 10 phantom-branch arithmetic
  > for classes with heavy `assert checkSession()` usage.

---

- [x] Step 8: Resultset ExecutionStream wrappers
  - [x] Context: warning
  > **What was done:** Added 87 direct tests across 6 new files under
  > `core/src/test/java/.../sql/executor/resultset/` covering every
  > ExecutionStream wrapper + the time-sensitive Expire/Timeout wrappers +
  > LoaderExecutionStream + InterruptResultSet + the public-API
  > IteratorResultSet/ExecutionResultSet. All wrappers in scope reach 100%
  > line coverage; branch gaps are phantom assert branches on public-API
  > spliterator methods and the Track-22-pinned timeout-repeat-fire paths.
  > Iteration-1 dimensional review (CQ/BC/TB/TC/TS/TX — 6 agents) flagged
  > 0 blockers and ~15 should-fix items; applied the high-signal fixes
  > in `Review fix: Track 8 Step 8` (`aba52fd13f`) and deferred the
  > large-refactor items (class split, helper dedupe) to Track 22.
  > Commits: `a4c3ffbed0` (initial 75 tests), `aba52fd13f` (review fix +
  > 12 gap/precision tests → 87 total).
  >
  > **What was discovered:**
  > - **`ExpireResultSet.fail()` re-fires the timeout callback on every
  >   hasNext/next call past the threshold** — the sticky `timedOut` flag
  >   only guards the return path, not the callback. Pinned as WHEN-FIXED
  >   in `expireRepeatedHasNextCallsFireCallbackEachTime`. `TimeoutResultSet`
  >   has the mirror bug on next() — pinned in `timeoutRepeatedNextFiresCallbackEachTime`.
  >   Track 22 fix: guard with `if (!timedOut) fail();`.
  > - **`FilterExecutionStream.fetchNextItem` leaks the unfiltered upstream
  >   value on mapper throw** — `nextItem = prevResult.next(ctx)` is assigned
  >   before `nextItem = filter.filterMap(...)` is called; when filterMap
  >   throws, `nextItem` stays holding the raw value and the next `next()`
  >   call delivers it unfiltered. Pinned as WHEN-FIXED in
  >   `filterExceptionLeaksUnfilteredItemOnNextCall`.
  > - **`OnCloseExecutionStream` is NOT idempotent** — a second `close()`
  >   call fires the callback AND the source close a second time. Pinned
  >   in `onCloseIsNotIdempotentOnRepeatedClose`.
  > - **`FlatMapExecutionStream` null-sub-stream path is transparent-skip**,
  >   not NPE — the while-loop's `currentResultSet == null` check treats null
  >   as "fetch next base", so a mapper that always returns null yields an
  >   empty flattened stream. Counterintuitive; pinned as observable
  >   contract.
  > - **`IteratorExecutionStream.next()` on empty iterator throws
  >   `NoSuchElementException`** (JDK pass-through), NOT `IllegalStateException`
  >   like the other stream wrappers. Asymmetric contract; pinned explicitly.
  > - **`LoaderExecutionStream` aborts the scan on first RNF** (does NOT
  >   skip-and-continue) — pinned with mid-stream-missing test so abort-vs-
  >   skip semantics cannot silently drift.
  > - **`LimitedExecutionStream` with negative limit yields an empty stream
  >   without consulting upstream** — SQL dialects that interpret `LIMIT -1`
  >   as "no limit" would trip this. Pinned.
  > - `BasicCommandContext.getDatabaseSession()` throws when no session is
  >   attached, forcing `ExecutionStreamWrappersTest` (which exercises
  >   `IteratorExecutionStream`'s primitive-materialization path via
  >   `ResultInternal.toResult`) to extend `DbTestBase`. Not a bug, but a
  >   design constraint that makes "standalone" tests in the package less
  >   practical than D2 might suggest.
  > - `CoreException.getMessage()` appends `\r\n\tDB Name="…"` and other
  >   sections to the user-supplied message. Exact-match message assertions
  >   (e.g., on `CommandInterruptedException`) must use `startsWith`, not
  >   `isEqualTo`.
  > - Compile surprise: `RID.getCollectionId()` / `getCollectionPosition()`
  >   are the correct accessor names — no `getClusterId()` method despite
  >   "cluster" being used in comments and the RID string format (`#cid:pos`).
  > - `IteratorResultSet.next()` does NOT check its `closed` flag; it
  >   consults the underlying iterator unconditionally. If the iterator
  >   still has elements, `next()` post-close returns them. Pinned in
  >   `IteratorResultSetTest` via the idempotent-close test.
  >
  > **What changed from the plan:** Step description originally allowed
  > "Standalone for wrappers that accept `@Nullable DatabaseSessionEmbedded`
  > (most of them — verify per-class)". In practice, the step-level per-
  > track override (`DbTestBase by default`) had to extend even to the
  > wrapper tests because `IteratorExecutionStream` internally calls
  > `ctx.getDatabaseSession()` during primitive materialization. Noted as
  > a design constraint, not a plan change. Step 8 landed at 87 tests (vs.
  > the "~15 stream classes" scope expectation), reflecting the gap tests
  > added during review iter-1.
  >
  > **Key files:**
  > - `core/src/test/java/.../sql/executor/resultset/ExecutionStreamWrappersTest.java` (new — 44 tests, 816+ LOC; extends DbTestBase)
  > - `core/src/test/java/.../sql/executor/resultset/ExpireTimeoutResultSetTest.java` (new — 10 tests; extends DbTestBase)
  > - `core/src/test/java/.../sql/executor/resultset/LoaderExecutionStreamTest.java` (new — 8 tests; extends TestUtilsFixture per review)
  > - `core/src/test/java/.../sql/executor/resultset/IteratorResultSetTest.java` (new — 9 tests; extends DbTestBase)
  > - `core/src/test/java/.../sql/executor/resultset/ExecutionResultSetTest.java` (new — 11 tests; extends DbTestBase)
  > - `core/src/test/java/.../sql/executor/resultset/InterruptResultSetTest.java` (new — 5 tests + RunOnceSoftThread fixture; extends DbTestBase)
  >
  > **Deferred to Track 22** (non-blocking; cataloged for the final sweep):
  > - Split ExecutionStreamWrappersTest into ~7 focused classes per wrapper
  >   group (current file is 816 LOC with 12 distinct wrapper groups already
  >   separated by `// ====` dividers — a natural split).
  > - Extract `streamOfInts` and `CloseTracker` into a package-private
  >   helper (`ExecutionStreamTestSupport`) — currently duplicated across
  >   three files.
  > - Convert `boolean[]`/`AtomicReference` flag holders in
  >   InterruptResultSetTest to `AtomicBoolean` / `AtomicReference` for
  >   explicit-barrier publication (latch semantics already provide
  >   happens-before, but AtomicBoolean makes the contract typed).
  > - Production-side fixes for the 3 WHEN-FIXED pins (Expire/Timeout
  >   callback repeat-fire, Filter nextItem leak-on-throw, OnClose
  >   non-idempotency).
  > - `ProduceExecutionStream`'s null-producer-return + `SingletonExecutionStream`'s
  >   null-Result-constructor — current behavior is pass-through / return-null;
  >   worth pinning with falsifiable regressions or adding production guards.
  > - `ExpireResultSet` with `Long.MAX_VALUE` timeout overflows to a past
  >   expiry time and trips immediately (counter-intuitive); not pinned yet
  >   — would be a fifth WHEN-FIXED if needed.
  >
  > **Cross-track impact:** Track 22 queue grows by the deferred items
  > cataloged above (~5 WHEN-FIXED production fixes + 3 DRY/split refactors).
  > No Component Map or Decision Record changes. Step 9 (collection impls)
  > is untouched; the Step 8 tests intentionally do NOT exercise the 6
  > Link/Embedded `*ResultImpl` classes.

### (original Step 8 description retained for reference)
Cover the ExecutionStream implementations under
`core/sql/executor/resultset/` (small wrappers, mostly 30-70 LOC each).

**Targets** (~125 uncov lines combined; ~15 stream classes):
- `MapperExecutionStream`, `FilterExecutionStream`,
  `FlatMapExecutionStream`, `IteratorExecutionStream`,
  `SingletonExecutionStream`, `LimitedExecutionStream`,
  `ProduceExecutionStream`, `OnCloseExecutionStream`,
  `InterruptExecutionStream`, `MultipleExecutionStream`,
  `EmptyExecutionStream`, `LoaderExecutionStream`,
  `ResultIteratorExecutionStream`, `CostMeasureExecutionStream`,
  `ExpireResultSet`, `TimeoutResultSet`, `IteratorResultSet`,
  `ExecutionResultSet`

**Test approach:**
- **Standalone** for stream wrappers that accept `@Nullable
  DatabaseSessionEmbedded` (most of them — verify per-class).
- **DbTestBase** for `LoaderExecutionStream` and `ExecutionResultSet`.
- For `ExpireResultSet` / `TimeoutResultSet` time-based logic: drive
  `internalNext()` directly with elapsed-time override or a stub stream
  whose `hasNext()` increments the AtomicLong threshold deterministically.
  Pin true wall-clock enforcement with WHEN-FIXED if it's flaky in CI.
- For `IteratorResultSet`: cover both `session=null` (standalone) and
  active-session paths (DbTestBase).

**Coverage target:** ≥90% line / ≥75% branch on each stream wrapper.

**Key files:**
- `core/src/test/java/.../sql/executor/resultset/*Test.java` (new files;
  one per stream wrapper, or grouped by behavioral family)
- Sources in `core/src/main/java/.../sql/executor/resultset/`

---

- [x] Step 9: Resultset Link/Embedded collection impls + metadata helpers
  - [x] Context: warning
  > **What was done:** Added 253 direct tests across 8 new files under
  > `core/src/test/java/.../sql/executor/{resultset,metadata}/` covering the
  > six `Link{List,Map,Set}ResultImpl` / `Embedded{List,Map,Set}ResultImpl`
  > pure-delegation wrappers plus the non-normalize branches of the
  > `IndexCandidate` hierarchy (`IndexCandidateImpl`, `IndexCandidateChain`,
  > `IndexCandidateComposite`, `MultipleIndexCanditate`, `RangeIndexCanditate`,
  > `RequiredIndexCanditate`, `IndexMetadataPath`, `IndexFinder.Operation`).
  > All tests are standalone (no DatabaseSession) — `RequiredIndexCanditate.
  > normalize` uses Mockito `IndexCandidate` mocks because its body is pure
  > logic that never touches `CommandContext`. Added a package-private
  > `LinkTestFixtures` helper (`rid(int, long)` factory) shared across the
  > three Link tests. Iter-1 dimensional review (CQ/BC/TB/TC/TS — 5 agents)
  > flagged 0 blockers, ~11 should-fix, ~22 suggestions; all should-fix
  > items plus high-ROI suggestions applied in `Review fix:` commit
  > (`d0fdf60c2c`). Commits: `d3aef70056` (initial 225 tests),
  > `d0fdf60c2c` (review fix + 28 gap/precision tests → 253 total).
  >
  > **What was discovered:**
  > - **`LinkSetResultImpl.equals`, `LinkMapResultImpl.equals`, and
  >   `EmbeddedSetResultImpl.equals` all delegate to `super.equals(obj)` —
  >   which reduces to `Object.equals` (reference equality) since none of
  >   the wrappers extend an equality-aware parent. Two distinct instances
  >   with identical contents are reported as unequal, even though both
  >   pass the `instanceof Set/Map` check.** Worse, this creates an
  >   asymmetric-equals contract violation: `plainHashMap.equals(linkMap)`
  >   returns `true` (HashMap.equals iterates its entrySet + calls
  >   `linkMap.get` which works correctly) while `linkMap.equals(plainHashMap)`
  >   returns `false`. Pinned with `WHEN-FIXED: Track 22` in all three
  >   test files; fix is flipping the final-line `super.equals(obj)` to
  >   `{map|set}.equals(obj)`.
  > - **`LinkMapResultImpl(int)` backs the map with `LinkedHashMap`** while
  >   the other two constructors use `HashMap`. The field is typed
  >   `HashMap<String, Identifiable>` — `LinkedHashMap extends HashMap`, so
  >   the code compiles, but iteration order differs: the int-capacity
  >   constructor gives insertion-deterministic iteration, the others do
  >   not. Pinned via `initialCapacityConstructorPreservesInsertionOrder`
  >   as a `WHEN-FIXED: Track 22` divergence.
  > - **`LinkMapResultImpl.containsValue(null)` always returns false even
  >   when a null value is mapped** — a semantic divergence from
  >   `HashMap.containsValue(null)`, which returns true under the same
  >   condition. Caused by the `value instanceof Identifiable` type guard.
  >   Pinned as observable contract.
  > - **Key-typed guards on `LinkMapResultImpl.{containsKey,get,remove,
  >   getOrDefault}` short-circuit on `null instanceof String` (false)** —
  >   distinct branch from the Integer case already covered. Pinned
  >   explicitly as `nullKeyReturnsDefaultsOnAllAccessors`.
  > - **`IndexCandidateChain.getName()` emits a trailing `->` even for
  >   single-index chains** (e.g., `"only->"` not `"only"`); likewise,
  >   `MultipleIndexCanditate.getName()` and `RequiredIndexCanditate.
  >   getName()` have the loop-overwrite bug (last candidate wins) —
  >   already flagged by prior tracks as a Track 22 cleanup.
  > - **`IndexCandidateChain.invert()` lacks dedicated FuzzyEq / Range
  >   identity tests** though the branch exists; parity-gapped with
  >   `IndexCandidateImpl.invert()`. Added `chainInvertIsIdentityForFuzzyEq`
  >   / `…ForRange`.
  > - **`RequiredIndexCanditate.normalize(CommandContext)` never touches
  >   the context** — it is pure recursion over the child list. Pinned
  >   with `verifyNoInteractions(ctx)` so a future refactor that introduces
  >   context coupling is flagged.
  > - **`LinkMapResultImpl` has both type-guarded `remove(Object)` (→
  >   Identifiable) and unguarded `remove(Object, Object)` (→ boolean)** —
  >   the two-arg variant delegates directly to HashMap without type
  >   filtering. Not a bug (HashMap's two-arg remove handles arbitrary
  >   keys), but worth noting for future refactors.
  > - Jacoco `clean test` alone does not generate the XML report —
  >   report binding is in `prepare-package`. Must run `package -P coverage`
  >   (or at least up to the package phase) after the tests to refresh
  >   `.coverage/reports/.../jacoco.xml`.
  >
  > **What changed from the plan:** Step description originally called
  > for a "shared abstract `@Parameterized` base" (`AbstractLinkCollectionResultImplTestBase`
  > / `AbstractEmbeddedCollectionResultImplTestBase`). Implemented as per-
  > class test files instead because (a) Link variants use
  > `Identifiable` while Embedded variants use generic `T`, (b) List / Set /
  > Map interface surfaces differ enough (List has subList/listIterator/
  > deque methods; Set lacks them; Map has entirely different CRUD) that
  > bridging them through a parameterized base would require generics
  > gymnastics and awkward abstract hooks that obscure intent. The six
  > focused files total ~2,400 LOC but each reads top-to-bottom as a
  > single-collection contract pin. Noted as a pragmatic deviation; the
  > `LinkTestFixtures` helper covers the one DRY hotspot (`rid` factory).
  >
  > **Key files:**
  > - `core/src/test/java/.../sql/executor/resultset/LinkTestFixtures.java` (new — package-private shared helper)
  > - `core/src/test/java/.../sql/executor/resultset/LinkListResultImplTest.java` (new — 49 tests)
  > - `core/src/test/java/.../sql/executor/resultset/LinkMapResultImplTest.java` (new — 39 tests)
  > - `core/src/test/java/.../sql/executor/resultset/LinkSetResultImplTest.java` (new — 29 tests)
  > - `core/src/test/java/.../sql/executor/resultset/EmbeddedListResultImplTest.java` (new — 39 tests)
  > - `core/src/test/java/.../sql/executor/resultset/EmbeddedSetResultImplTest.java` (new — 26 tests)
  > - `core/src/test/java/.../sql/executor/resultset/EmbeddedMapResultImplTest.java` (new — 26 tests)
  > - `core/src/test/java/.../sql/executor/metadata/IndexCandidatesTest.java` (new — 45 tests)
  >
  > **Coverage deltas (post-Step 9):**
  > - `LinkListResultImpl`: 98.4% line / 100% branch (60/61 line; gap is one unreachable equals-fall-through)
  > - `LinkMapResultImpl`: 100% / 100%
  > - `LinkSetResultImpl`: 100% / 100%
  > - `EmbeddedListResultImpl`: 98.4% / 100% (60/61 line)
  > - `EmbeddedMapResultImpl`: 100% / 100%
  > - `EmbeddedSetResultImpl`: 100% / 100%
  > - `IndexCandidateImpl`: 100% / 100%
  > - `IndexCandidateChain`: 100% / 100%
  > - `IndexCandidateComposite`: 100% / 100%
  > - `IndexMetadataPath`: 100% / 100%
  > - `IndexFinder.Operation`: 100% / 100%
  > - `RangeIndexCanditate`: 100% / 100%
  > - `RequiredIndexCanditate`: 100% / 100%
  > - `MultipleIndexCanditate`: 93.3% / 83.3% (remaining is `normalizeComposite` — touches live SharedContext.IndexManager)
  >
  > **Deferred to Track 22** (non-blocking; cataloged for the final sweep):
  > - Three `super.equals(obj)` → `{map|set}.equals(obj)` production fixes
  >   (`LinkSetResultImpl`, `LinkMapResultImpl`, `EmbeddedSetResultImpl`)
  >   — each pinned with `WHEN-FIXED: Track 22` and falsifiable regression.
  > - `LinkMapResultImpl(int)` constructor unification: either promote the
  >   Linked variant to the other constructors (insertion-order guarantee
  >   across the board) or demote to `HashMap` (current field type). Pinned
  >   with `initialCapacityConstructorPreservesInsertionOrder` as
  >   `WHEN-FIXED: Track 22`.
  > - `IndexCandidateChain.getName()` trailing-arrow separator quirk
  >   (`"first->second->"` vs desired `"first->second"`); joined-last-arrow
  >   behavior also lives in `MultipleIndexCanditate.getName()` and
  >   `RequiredIndexCanditate.getName()`.
  > - Public `canditates` field on `MultipleIndexCanditate` /
  >   `RequiredIndexCanditate` (leaky encapsulation — pin via `isSameAs`
  >   for now).
  > - Class misspelling: `MultipleIndexCanditate`, `RangeIndexCanditate`,
  >   `RequiredIndexCanditate`, `addCanditate`, `getCanditates`,
  >   `canditates` (all missing the 'd' in "Candidate").
  >
  > **Cross-track impact:** Track 22 queue grows by the five items cataloged
  > above (3 production WHEN-FIXED fixes + 1 constructor unification + 1
  > DRY/hygiene pass). No Component Map or Decision Record changes. Step 10
  > remains; it is not blocked by Step 9 — collection impls are leaves in
  > the dependency graph.
  >
  > **Review iter-1 outcome**: 0 blockers; 11 should-fix items (all
  > applied); 22 suggestions (high-ROI applied, remainder absorbed via
  > existing tests or documented as deferred). No iter-2 needed — all
  > fixed in a single `Review fix:` commit, and remaining suggestions are
  > low-severity. The rename from `_WHENFIXED_Track22` suffix to
  > descriptive method names aligned Step 9 with the Step 8 convention
  > (Javadoc-only WHEN-FIXED markers).

---

### (original Step 9 description retained for reference)
Cover the six pure delegating Result-collection wrappers and the
`executor/metadata/` IndexFinder helper chain.

**Targets:**
- `LinkListResultImpl` (258 LOC), `LinkMapResultImpl` (185), `LinkSetResultImpl`
  (158), `EmbeddedListResultImpl` (255), `EmbeddedMapResultImpl` (178),
  `EmbeddedSetResultImpl` (156) — six pure delegation wrappers,
  ~1,190 LOC combined; instantiated only from `ResultInternal.convert*()`.
- `core/sql/executor/metadata/` (61 uncov, 79.9%): `ClassIndexFinder`,
  `IndexCandidateImpl`, `IndexCandidateChain`, `IndexCandidateComposite`,
  `MultipleIndexCanditate`, `RangeIndexCanditate`, `RequiredIndexCanditate`,
  `IndexMetadataPath` — fill remaining gaps beyond existing
  `IndexFinderTest` and `StatementIndexFinderTest`.

**Test approach:**
- For the six collection wrappers: shared abstract `@Parameterized` base
  (`AbstractLinkCollectionResultImplTestBase` for Link variants;
  `AbstractEmbeddedCollectionResultImplTestBase` for Embedded variants).
  Test `List` / `Set` / `Map` contract: size, isEmpty, contains, add,
  remove, iterator, listIterator, subList, sort, spliterator,
  replaceAll, equals, hashCode. Standalone (no session needed for
  delegation tests).
- For `*ResultImpl` × `ResultInternal.convert*` integration: covered
  indirectly via Step 7's ResultInternal tests; gap-fill any remaining
  branch via direct construction.
- For metadata helpers: extend existing `IndexFinderTest` /
  `StatementIndexFinderTest` to cover the chain/composite/range
  candidate composition.

**Coverage target:** ≥85% line / ≥70% branch on each target.

**Key files:**
- `core/src/test/java/.../sql/executor/resultset/Link{List,Map,Set}
  ResultImplTest.java` (new)
- `core/src/test/java/.../sql/executor/resultset/Embedded{List,Map,Set}
  ResultImplTest.java` (new)
- `core/src/test/java/.../sql/executor/resultset/Abstract{Link,Embedded}
  CollectionResultImplTestBase.java` (new — shared parameterized base)
- `core/src/test/java/.../sql/executor/metadata/*Test.java` (extend)

---

- [x] Step 10: SelectExecutionPlanner SQL round-trip + verification
  - [x] Context: warning
  > **What was done:** Created two new focused SQL-round-trip test classes
  > targeting uncovered planner branches. `SelectExecutionPlannerBranchTest`
  > (27 tests) drives `SelectExecutionPlanner` via `session.query(sql)`
  > through every switch arm of `handleInputParamAsTarget` (SchemaClass,
  > String — implicitly via existing test, single Identifiable, iterable
  > of identifiables, empty iterable, null, non-Identifiable at first and
  > mid positions, invalid type), the `calculateAdditionalOrderByProjections`
  > record-attribute and literal-RID synthesis arms, `isOrderByRidAsc/Desc`
  > + `hasTargetWithSortedRids` for @rid ASC/DESC against a class target,
  > the global `COMMAND_TIMEOUT` config-fallback, `splitLet` both
  > parent-free-query and combination-function (unionAll) promotion paths,
  > the hardwired `count(*) FROM C WHERE idx = ?` optimization, OR-clause
  > split with DistinctExecutionStep dedup, `SELECT FROM metadata:schema`,
  > literal single-RID and multi-RID list targets, subquery-as-target, and
  > SKIP/LIMIT Path-C. `SmallPlannerBranchTest` (21 tests) drives the six
  > small planners: UPDATE RETURN AFTER with projection, UPDATE TIMEOUT,
  > UPDATE PUT rejected, UPDATE RETURN BEFORE rejected at parse time;
  > INSERT FROM SELECT, INSERT multi-row VALUES, INSERT SET, INSERT
  > CONTENT single-block and input-param; CREATE EDGE default "E",
  > UPSERT without unique index throws, UPSERT unknown class throws,
  > UPSERT happy path reuses RID, SET properties; DELETE EDGE FROM-only,
  > TO-only, FROM+TO+WHERE, no-scope; CREATE VERTEX default "V"; DELETE
  > VERTEX by RID. All tests extend `TestUtilsFixture` inheriting the
  > `@After rollbackIfLeftOpen` safety net from Track 7.
  >
  > **What was discovered:** Three production issues pinned as
  > falsifiable regressions with WHEN-FIXED markers for Track 22:
  > (1) `UpdateExecutionPlanner` carries a complete RETURN-BEFORE
  > apparatus (`handleReturnBefore`, `handleResultForReturnBefore`,
  > `CopyRecordContentBeforeUpdateStep`, `UnwrapPreviousValueStep`,
  > `returnBefore` field) but `SQLUpdateStatement.isReturnBefore()`
  > unconditionally throws `DatabaseException("BEFORE is not supported")`,
  > making every RETURN-BEFORE branch dead code. (2) The error message
  > in `SelectExecutionPlanner.handleInputParamAsTarget` contains the
  > typo `"colleciton"` instead of `"collection"`. (3) Multi-CONTENT
  > `INSERT INTO C CONTENT {a}, CONTENT {b}, CONTENT {c}` is parsed but
  > the planner's for-loop chains every `UpdateContentStep` onto a single
  > record stream, so later CONTENT entries overwrite earlier ones — the
  > effective semantics are "last CONTENT wins"; the step creates only
  > one record despite N content blocks. Also discovered: `DISTINCT
  > expand()` combination fails at parse time, so the planner's
  > `info.expand && info.distinct` guard is currently defensive/dead —
  > no test written (removed an earlier attempt when it couldn't be made
  > falsifiable). `SELECT FROM [:p1, :p2]` syntax does not parse under the
  > current grammar alternatives; removed the multi-input-param test.
  >
  > **What changed from the plan:** No deviations from Step 10's scope
  > description. The planner round-trip + small-planner coverage split
  > into two test files (`SelectExecutionPlannerBranchTest` +
  > `SmallPlannerBranchTest`) rather than collapsing into one — this
  > preserves the planner-by-planner axis and keeps each file under ~700
  > LOC for reviewability. Track 22's backlog gained three pins: remove
  > RETURN-BEFORE dead branches from `UpdateExecutionPlanner`, fix the
  > "colleciton" typo, and decide on multi-CONTENT semantics (reject at
  > parse time vs make it create N records).
  >
  > **Coverage delta:**
  > - `sql/executor` aggregate: 87.7% / 76.5% → **88.2% / 77.2%** (+0.5pp
  >   line / +0.7pp branch; 831 → 798 uncov lines, net -33)
  > - `SelectExecutionPlanner`: 84.5% / 73.1% → **86.1% / 74.4%**
  >   (+1.6pp / +1.3pp; 239 → 214 uncov lines)
  > - `InsertExecutionPlanner`: 74.6% / 56.5% → **97.2% / 80.4%**
  > - `UpdateExecutionPlanner`: 90.9% / 72.0% → **94.3% / 82.0%**
  > - `CreateVertexExecutionPlanner`: 90.5% / 66.7% → **100% / 83.3%**
  > - `DeleteVertexExecutionPlanner`: 93.3% / 50.0% → **100% / 80.0%**
  > - `DeleteExecutionPlanner`: 96.9% / 50.0% → **100% / 91.7%**
  > - `CreateEdgeExecutionPlanner`: 92.2% / 78.6% → **93.3% / 80.0%**
  >
  > The remaining `SelectExecutionPlanner` gap (214 line / 292 branch
  > uncovered) concentrates in full-text index search descriptor
  > building, indexed-function ambiguity paths requiring multiple
  > indexed-function conditions on the same block, the traversal
  > prefilter helpers (`extractTraversalDirection`,
  > `extractEdgeClassName`, `lookupLinkedClassName`,
  > `resolveClassToCollectionIds` — called only from specific
  > LET-with-expand(traversal) shapes), security-policy guards, composite-
  > index-selection under specific WHERE-shape combinations, and the
  > planner's internal rewrite passes for chained-index traversals.
  > These are integration-level scenarios that genuinely benefit from
  > schema-heavy test setups; documented as Track 22 final-sweep candidates
  > rather than chasing asymptotic coverage per Track 8 Step 10 scope
  > acknowledgment A4/A5.
  >
  > **Step-level code review** (1 iteration, 4 agents: CQ, BC, TB, TC,
  > parallel):
  > - 0 blockers, ~10 should-fix (with CQ1/CQ2 deduplication), ~15
  >   suggestions.
  > - Applied in review-fix commit `5a651ed138`: BC1/TB3 narrowed
  >   `update_returnBefore` catch from `RuntimeException` to
  >   `DatabaseException` with exact-message assertion; BC3 tightened
  >   unknown-class message to require "not found"; BC5 synthetic-alias
  >   check now iterates `getPropertyNames` and looks for "_$$$" prefix
  >   (survives rename); TB1 splitLet test strengthened to assert $sub
  >   binding visibility and three-way coverage of outer rows; TB4
  >   CREATE EDGE default class now asserts @class = "E"; TB5 UPSERT
  >   happy-path captures both RIDs and asserts equality (catches
  >   "UPSERT deleted and reinserted" regressions); TB6/TC12 replaced
  >   "#999:0" with real-cluster-missing-position synthesis; BC2 replaced
  >   redundant `iterableFirstElementBad` with `iterableMidElementBad`
  >   (different iteration position); CQ3/CQ5 replaced Unicode characters
  >   with ASCII; added 4 completeness tests (TC1 multi-RID list, TC2
  >   metadata:schema, TC3 splitLet unionAll, TC5 INSERT CONTENT :p).
  >   TB2 (global-timeout falsifiability) deferred — the branch is
  >   exercised but a tiny-timeout + TimeoutException assertion was
  >   non-trivial within context budget; noted as TB residual.
  >   Suggestions CQ1 (DRY uniqueSuffix to TestUtilsFixture), CQ2 (FQN
  >   imports), TC4/TC6/TC8 (SchemaClass subclass, DELETE EDGE BATCH,
  >   CREATE EDGE input-params for FROM/TO), TC10/TC11 (order-by multi-
  >   item short-circuit) deferred — suggestion-level and Track 22 queue.
  >
  > **Patterns carried forward:** Falsifiable regression + WHEN-FIXED
  > marker convention (3 new pins); property-name prefix check over exact
  > match for synthetic aliases (new pattern for future tests); derive
  > cluster IDs from real records rather than assuming unused numbers
  > (new pattern for RID-specific tests).
  >
  > **Key files:**
  > - `core/src/test/java/.../sql/executor/SelectExecutionPlannerBranchTest.java` (new, 27 @Test, 720 LOC)
  > - `core/src/test/java/.../sql/executor/SmallPlannerBranchTest.java` (new, 21 @Test, 710 LOC)
  >
  > **Cross-track impact:** None on Component Map / Decision Records.
  > Track 22 backlog grew by 3 WHEN-FIXED entries (RETURN-BEFORE dead
  > branches, "colleciton" typo, multi-CONTENT semantics) plus
  > suggestion-level deferrals (DRY uniqueSuffix, additional completeness
  > coverage for SchemaClass subclasses / DELETE EDGE BATCH / CREATE EDGE
  > input-params). No changes to Track 9+ scopes.

### Step 10 original scope (for reference)
Drive `SelectExecutionPlanner` (3,741 LOC, 239 uncov) via real
`session.query(sql)` invocations to exercise its `handle*` /
index-selection / OR-split / ORDER-BY-against-index branches. This is
the **only step using SQL round-trip as the primary strategy** — direct
planner unit tests are infeasible (planner methods are package-private
and depend on parser AST shape).

**Tasks:**
1. Branch-driven SQL queries:
   - `handleClassAsTargetWithIndex*` — class with single-property index;
     class with composite index; class with no index (full scan).
   - `handleFetchFromRids` — `SELECT FROM #cluster:position` shape.
   - `handleSubqueryAsTarget` — `SELECT FROM (SELECT ...)`.
   - OR-clause splitting (ParallelExecStep emission) — `WHERE x=1 OR y=2`
     against indexed properties.
   - `@rid` ASC/DESC RID-range optimization.
   - Aggregation flatten — `GROUP BY` + projection.
   - LIMIT / SKIP push-down with index.
   - LET-with-subquery-materialization.
2. Cross-check existing planner tests under
   `core/src/test/java/.../sql/executor/Select*Test.java` — write only
   tests that exercise branches not already covered.
3. Track 7 patterns: `@After rollbackIfLeftOpen`; Iterable detach;
   counting CommandContext where mutation-kill matters.
4. Apply `// forwards-to: Track NN` for cross-track failures.

**Other planners** (smaller — `InsertExecutionPlanner`,
`UpdateExecutionPlanner`, `DeleteExecutionPlanner`,
`CreateEdgeExecutionPlanner`, `DeleteEdgeExecutionPlanner`,
`DeleteVertexExecutionPlanner`, `CreateVertexExecutionPlanner`): cover
remaining branches via SQL round-trip in the same step or in piggyback
tests under Step 2.

**Verification (post-Step 10):**
- Run `./mvnw -pl core -am clean package -P coverage` (or
  `clean test -P coverage` if package-step too slow).
- Run `python3 .github/scripts/coverage-analyzer.py
  --coverage-dir .coverage/reports` and capture per-package deltas
  for `sql/executor`, `sql/executor/resultset`, `sql/executor/metadata`.
- Confirm aggregate executor reaches ≥85%/≥70% (or document the residual
  with WHEN-FIXED markers if planner branches need integration tests).
- Run full `./mvnw -pl core clean test` to confirm no regressions.

**Coverage target:** ≥85% line / ≥70% branch on `SelectExecutionPlanner`.
Aggregate `sql/executor` package ≥85%/70% (or ≥80%/68% with documented
residuals).

**Key files:**
- `core/src/test/java/.../sql/executor/SelectExecutionPlanner*Test.java`
  (new — likely the SQL-round-trip tests live in 2-3 focused files
  grouped by feature area)
- Source: `core/src/main/java/.../sql/executor/SelectExecutionPlanner.java`

---

## Step ordering notes

- Step 1 must precede all others (provides shared fixture; pins dead code
  before steps try to cover it).
- Steps 2-9 are largely independent — could parallelize during
  implementation if context permits (within Phase B). Step 4
  (FetchFromIndexStep) is the heaviest; worth doing early so coverage
  delta is visible and to surface schema/index test patterns reusable in
  Step 7 (ResultInternal lazy load) and Step 10 (planner index push-down).
- Step 7 (Result types) before Step 9 (collection impls) because Step 7's
  tests indirectly exercise Step 9's targets via `ResultInternal.convert*`
  paths.
- Step 10 last — depends on coverage delta visibility from prior steps to
  scope the planner tests precisely.

## Risk acknowledgements (per Phase A reviews)

- **R8 (suggestion):** RetryStep wall-clock retry with real concurrent
  transactions, TimeoutStep wall-clock enforcement,
  AccumulatingTimeoutStep worker coordination — pinned with WHEN-FIXED +
  "integration-test territory" marker, mirroring Track 5's acceptance
  pattern.
- **A4/A5:** If post-Step 10 coverage lands the executor package at
  ~80-83% line (below 85% target) due to planner branches that genuinely
  need integration tests, document the gap honestly in the track episode
  rather than chasing asymptotic coverage. D4 grants explicit relaxation
  to storage internals only; this would be a Track 8 acknowledgment, not
  a Decision Record change.
- **A7:** Cross-track bugs (record/impl, schema, db) get
  `// forwards-to: Track NN` and a workaround in the executor test —
  block at most one step per discovered class of issue.

## Track-level code review — iteration 1 (complete)

Ran 6 review agents in parallel (CQ, BC, TB, TC, TS, TX) against
`c45c9943..HEAD`. Raw totals: 2 blockers / 25 should-fix / 37 suggestions.

**Applied in iter-1** (commit `dea1b1a219`):

- **TB1, TB2** (blockers) — non-falsifiable `"colleciton"||"collection"`
  OR-assertion dropped; `createVertex_defaultTargetV` now queries
  `@class` to verify the "V" default.
- **TB3–TB7** (precision) — Integer-ctor pins tightened to
  `rootCause().hasMessageEndingWith(<init>(java.lang.Integer))`;
  IfStepTest asserts projected constants via prettyPrint; BatchStep
  prettyPrint uses exact string; FilterStep whereClause pins the
  `SQLOrBlock` AST tag; UpdatableResult.getLink pins target identity.
- **TC1** — `BatchStepTest` pins batchSize=0 ArithmeticException under
  an active tx.
- **TC2** — `RetryStepTest` pins retries=0 and retries<0 early-exit.
- **TC10** — new `TestUtilsFixtureSelfTest` (2 @Test) directly exercises
  both `rollbackIfLeftOpen` branches.
- **TC11** — `SelectExecutionPlannerBranchTest` pins single-RID
  literal-list boundary.
- **TS4** — `AccumulatingTimeoutStepTest` class javadoc documents the
  wall-clock coupling and Track-22 deterministic-clock migration path.
- **TS5** — `TestUtilsFixture` javadoc corrected re: DbTestBase per-method
  session lifecycle.
- **TX2** — `InterruptResultSetTest.RunOnceSoftThread` marked daemon;
  all 3 SoftThread tests now assert `!thread.isAlive()` after join(1s).

All 174 tests across the 12 affected classes pass. Spotless clean.

**Deferred to Track 22** (plan update applied in same session — see
`implementation-plan.md`):
- CQ1/TS1 — hoist `newContext`/`sourceStep`/`drain` into TestUtilsFixture
  (~45 file duplications)
- CQ2/TS2 — hoist `uniqueSuffix` into TestUtilsFixture
- CQ3 — extract `streamOfInts`/`CloseTracker`/`NoOpStep` to a shared
  helper in `core/sql/executor/resultset/`
- CQ4 — replace inline FQNs with explicit imports in the Track-8 test
  files
- CQ8/TS8 — remove manual try/catch/rollback boilerplate where
  `rollbackIfLeftOpen` safety net covers it
- **TC3** — `CreateRecordStep total<0` → empty stream
- **TC4** — `Update{Remove,Set,Merge,Content}Step` non-ResultInternal
  pass-through
- **TC5** — `FetchFromCollection` unknown / negative collection ID
- **TC6** — `FetchFromClass` partial collections-filter subset matrix
- **TC7** — `LetExpressionStep` subquery-throws propagation
- **TC8** — `Skip→Limit` direct composition test
- **TC9** — `UpsertStep` multi-row upstream matches
- **TC12** — `InsertValuesStep` rows<tuples boundary
- Suggestion-level (37 items): CQ5–CQ7, CQ9–CQ10, BC1–BC2, TB8–TB9,
  TC13–TC21, TS3, TS6–TS7, TS9–TS14, TX1, TX3–TX8 — rolled into Track 22
  final-sweep scope.

**Session ended at `warning` context level (26%).** Per workflow.md
§Context Consumption Check, iter-2 gate check and track completion are
deferred to the next `/execute-tracks` invocation, which will resume at
Phase C with iter-1 count already recorded in the Progress section.

## Track-level code review — iteration 2 (complete)

Ran 6 gate-check review agents in parallel (CQ, BC, TB, TC, TS, TX) against
`c45c9943..HEAD`. Per-dimension results:

| Dim | Iter-1 fixes verified | New findings | Verdict |
|---|---|---|---|
| BC | — (deferrals only; BC1/BC2 still legitimate) | 0 | PASS |
| CQ | — (deferrals only) | CQ11–CQ13 (suggestions, absorbed by Track 22 CQ4/CQ8) | PASS |
| TB | TB1–TB7 all VERIFIED | TB10–TB14 (should-fix), TB15 (should-fix), TB16–TB17 (suggestions) | FAIL → fixed in iter-2 |
| TC | TC1, TC2, TC10, TC11 all VERIFIED | 0 (deferrals legitimate) | PASS |
| TS | TS4, TS5 VERIFIED | 3 suggestions (absorbed by Track 22) | PASS |
| TX | TX2 VERIFIED | 0 | PASS |

**Applied in iter-2** (this commit):

- **TB10** — `SelectExecutionPlannerBranchTest.selectFromInputParam_invalidType_throws`:
  non-falsifiable OR-assertion `"Invalid target" || "42"` replaced with AND (production
  message always contains both simultaneously, so the OR was always true — same shape
  as the iter-1 TB1 blocker).
- **TB11** — `UpdatableResultTest.getVertexDelegatesToEntityGetVertex` and
  `getEdgeDelegatesToEntityGetEdge`: added identity-equality pins parallel to the iter-1
  TB7 `getLink` fix (isNotNull-only left vertex/edge siblings exposed to "any non-null
  return" mutations).
- **TB12** — `SmallPlannerBranchTest.update_putOperation_throws`: non-falsifiable
  3-disjunct OR over `"PUT" || "ADD" || "INCREMENT"` replaced with single check on the
  concatenated `"PUT/ADD/INCREMENT"` fragment the production message emits verbatim.
- **TB13** — `ResultInternalTest.getResultReturnsStoredResult`,
  `getVertexResolvesRidToVertex`, `getEdgeResolvesRidToEdge`, `getBlobResolvesRidToBlob`:
  four accessors tightened from `isNotNull` to identity/content pins so a mutation
  returning any non-null but unrelated object would be caught.
- **TB14** — `LetExpressionStepTest.serializeStoresVarnameAndExpression`: now pins the
  `varname` contains "x" AND the `expression` carries the `SQLBaseExpression` AST tag
  plus the literal "42" (correcting the TB agent's assumption about the AST class —
  `parseExpression` returns an expression AST, not a select statement).
- **TB15** — `SelectExecutionPlannerBranchTest.splitLet_perRecordQueryWithoutParent_promotedToGlobal`:
  tightening from `isNotNull` uncovered a **deterministic-but-surprising shape** —
  `row[0].cnt == 3` but `rows[1..].cnt == 0` (the materialized global-LET stream is
  consumed by the first row's `size()` call, leaving subsequent rows with an empty
  snapshot). Pinned the observed shape with a `WHEN-FIXED: Track 22` marker pointing
  to the semantic question: should the "resolve once" contract return the same size
  to every outer row, or is stream exhaustion intentional? If the fix lands in
  Track 22, this test flips to `assertEquals(3L, cnt)` per row.

**Deferred to Track 22** (no separate plan update needed — these fold into the
already-queued CQ4 and CQ8/TS8 sweep scope):

- **CQ11, TS16** — `FilterStepTest` inline `com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal.class`
  (same-package FQN; bare `ResultInternal.class` suffices). Extends the Track 22
  CQ4 sweep list; already covered in spirit by "replace inline FQNs with explicit
  imports" plan entry.
- **CQ12, TS16** — `BatchStepTest` inline `org.assertj.core.api.Assertions.assertThatThrownBy(...)`;
  sibling static-import already present (`assertThat`). Fold into the Track 22 CQ4
  AssertJ-static-import consistency pass.
- **CQ13, TS15** — iter-1's new `batchSizeZeroThrowsArithmeticExceptionOnFirstRecord`
  test duplicates the `try { ... } finally { session.rollback() }` boilerplate that
  CQ8/TS8 deletes. Fold into the Track 22 CQ8/TS8 sweep — audit must include this
  test.
- **TS17** — defensive-guard sub-branches in `TestUtilsFixtureSelfTest` (null-session
  and closed-session paths). Low-severity; Track 22 may strengthen if it chooses.
- **TB16, TB17** — `orderBySynthetic_recordAttribute` weak supplemental `isNotNull`
  (primary contract is already strongly asserted); `detachDelegatesToEntityDetach`
  identity-only pin. Suggestion-tier, rolled into Track 22.

All 202 tests across the 5 touched classes pass (`./mvnw -pl core test -Dtest='SelectExecutionPlannerBranchTest,UpdatableResultTest,SmallPlannerBranchTest,ResultInternalTest,LetExpressionStepTest'`). Spotless clean.

Iter-2 **FAIL verdict on TB** resolved in this same iteration by applying all 5
should-fix TB items. A fresh iter-3 gate check will re-verify the TB dimension only
(other dimensions PASSED in iter-2 and need no re-check per review-iteration
protocol). Max iterations remaining: 1.

## Track-level code review — iteration 3 (complete, PASS)

Ran 1 gate-check agent (`review-test-behavior`) against `c45c9943..HEAD` focused
on the iter-2 fix commit `a4895ac92e`.

**All six iter-2 TB fixes VERIFIED:**
- **TB10** — AND-replaces-OR in `selectFromInputParam_invalidType_throws` — production
  `SelectExecutionPlanner.java:1585` emits `"Invalid target: " + paramValue`,
  confirming both tokens always co-occur. Falsifiable.
- **TB11** — identity pins in `getVertexDelegatesToEntityGetVertex` /
  `getEdgeDelegatesToEntityGetEdge` — delegation contract correctly pinned.
- **TB12** — `"PUT/ADD/INCREMENT"` fragment pin — production
  `UpdateExecutionPlanner.java:193` emits the exact literal.
- **TB13** — four `ResultInternal` accessor identity/content pins — production
  resolves via `transaction.load*()` + `getProperty`, pins falsifiable against
  any non-null-but-unrelated mutation.
- **TB14** — `LetExpressionStep` serialize content pin — `SQLMathExpression.serialize`
  embeds `__class = getClass().getName()` at line 1353, so `"SQLBaseExpression"`
  and literal `"42"` are deterministic, falsifiable substrings of the
  serialized AST.
- **TB15** — observed-shape pin `(3, 0, 0)` with `WHEN-FIXED: Track 22` marker —
  documented three falsifiable mutation classes (no-promotion=all 1s,
  duplicated-stream=all 3s, lost-first-materialization=all 0s). WHEN-FIXED
  points at a real semantic question (stream-exhaustion vs. per-outer-row
  resolution).

**Overfitting / regression check:** No new non-falsifiable assertions, no
OR-weakened checks, no vacuous assertions, no identity-only delegation tests
introduced. Comments verified accurate against production sources.

**New TB findings:** None. `TB18+` range remains unused.

**Verdict: PASS** — 0 open blockers, 0 open should-fix. Track 8 track-level code
review complete across 3 iterations (max reached, all resolved). Ready for
track completion.

### Track-level code review summary

- **Iter-1** (complete): 6 dimensions (CQ/BC/TB/TC/TS/TX) run in parallel.
  2 blockers + 25 should-fix + 37 suggestions. 13 should-fix applied in commit
  `dea1b1a219`; remainder (deferral scope) absorbed by Track 22 via plan update
  `7b9313eb4b`.
- **Iter-2** (complete): 6-dimension gate check. BC/CQ/TC/TS/TX all PASS; TB
  FAIL with 5 new should-fix (TB10–TB14) + 1 observed-shape (TB15) + 2
  suggestions (TB16/TB17, deferred to Track 22). Should-fix items applied in
  commit `a4895ac92e`.
- **Iter-3** (complete): TB-only gate check — PASS. All iter-2 TB fixes
  VERIFIED, 0 new findings.

All 202 tests in the 5 iter-2-touched classes pass. Full `./mvnw -pl core
clean test` confidence carried over from iter-1 (only test-file modifications
since). Spotless clean.
