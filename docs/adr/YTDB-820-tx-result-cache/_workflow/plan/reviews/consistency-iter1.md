<!-- MANIFEST
schema: review-file/v1
review: consistency
iter: 1
role: reviewer-plan
phase: 2
findings: 4
by_sev: {blocker: 0, should-fix: 2, suggestion: 2}
by_class: {mechanical: 3, design-decision: 1}
evidence_base: {refs: 28, flows: 2, invariants: 3, matches: 30, non_matches: 4}
tooling: grep+Read (mcp-steroid PSI execute_code timed out repeatedly on first-call compile; symbol verdicts carry a reference-accuracy caveat — see Evidence base note)
index:
  - {id: CR1, sev: should-fix, class: design-decision, anchor: "cacheCodeDepth re-entrancy guard does not exist; conflicts with inFlightLookup", loc: "design.md §Cache invalidation L641, §Concurrency L742/L744", cert: "Ref: cacheCodeDepth", basis: current-state}
  - {id: CR2, sev: should-fix, class: mechanical, anchor: "STATEMENT_CACHE named symbol does not exist; actual class is YqlStatementCache", loc: "design.md §Cache key (D2/D12), plan D2", cert: "Ref: STATEMENT_CACHE", basis: current-state}
  - {id: CR3, sev: suggestion, class: mechanical, anchor: "matchesFilters(record, ctx) signature imprecise — actual overloads (Identifiable|Result, ctx)", loc: "design.md §TxDeltaCursor L341, plan Integration Points", cert: "Ref: SQLWhereClause.matchesFilters", basis: current-state}
  - {id: CR4, sev: suggestion, class: mechanical, anchor: "beginInternal line cited as both 164 and 165 across design", loc: "design.md §Concurrency table + L165, Integration Points L165", cert: "Ref: FrontendTransactionImpl.beginInternal", basis: current-state}
-->

# Consistency review — YTDB-820 transaction-scoped query result cache (iteration 1)

BLUF: the plan and design cite a large surface of existing code, and almost
all of it checks out — `FrontendTransactionImpl` mutation/lifecycle hooks, the
`addRecordOperation` collapse arms, the tx-end sink topology, the
`DatabaseSessionEmbedded` query path, the parser AST classes and their
`equals`/`noCache`/`skip` shapes, the D22 `SQLInputParameter` no-override claim,
`PropertyTypeInternal.increment`, the aggregate-step splice point, the three
function factories, and the `math_` reflective registration all match the code
at the cited locations. Four findings, none a blocker: one re-entrancy-guard
inconsistency the execution agent would trip on (`cacheCodeDepth` vs
`inFlightLookup`), one shorthand-name mismatch (`STATEMENT_CACHE` →
`YqlStatementCache`), and two cosmetic citation nits.

## Findings

### CR1 [should-fix]
**Certificate**: Ref: cacheCodeDepth
**Location**: `design.md` §Cache invalidation → Edge cases (L641); §Concurrency → "`clear()` is owner-thread-only" (L742, L744). Code: `DatabaseSessionEmbedded.java`, `FrontendTransactionImpl.java`.
**Issue**: The design describes the cache's re-entrancy guard two different ways. The Class Design diagram (L57) and the §Concurrency re-entrancy bullet (L714) introduce a NEW `inFlightLookup: boolean` on `QueryResultCache` as the re-entrancy mechanism, and the plan's Component Map / track-1 echo `inFlightLookup`. But three other passages (L641, L742, L744) describe an `cacheCodeDepth` counter, calling it the "existing concern from SO5 / `cacheCodeDepth`" and "the SO5 re-entrancy guard [that] relies on `cacheCodeDepth`". No symbol named `cacheCodeDepth` exists anywhere in `core/src` — it is neither an existing guard nor the field the plan builds.
**Evidence**: `grep -rn "cacheCodeDepth" core/src` returns zero matches (non-test, non-worktree). The actual re-entrancy field is `inFlightLookup` (design L57/L714, plan Component Map, track-1 step 7). The L641/L742/L744 text presents `cacheCodeDepth` as pre-existing infrastructure the cache "must respect (`cacheCodeDepth > 0` bypass)", which would send the execution agent looking for a counter that does not exist and is not what the design otherwise specifies. Caveat: verified by grep, not PSI (mcp-steroid `execute_code` timed out); a textual search for a non-existent identifier cannot be hidden by polymorphism, so the negative is reliable.
**Proposed fix**: Reconcile to one mechanism. Replace the `cacheCodeDepth` references at L641, L742, L744 with `inFlightLookup` (the boolean re-entrancy flag the rest of the design and the plan use), and drop the "existing concern from SO5" framing since the guard is new. If `cacheCodeDepth` was meant to name a distinct pre-existing session-level guard, the design must cite where it lives — it does not exist today.
**Classification**: design-decision
**Justification**: ambiguous fix — the orchestrator cannot tell whether `cacheCodeDepth` and `inFlightLookup` are two names for one new guard (collapse to `inFlightLookup`) or whether the design intended a second, distinct guard that needs to be created; resolving the re-entrancy model is a design call for the user.

### CR2 [should-fix]
**Certificate**: Ref: STATEMENT_CACHE
**Location**: `design.md` §Cache key composition (D2 L271/L293, D12 L294, TL;DR L271, edge cases L286/L289); plan Architecture Notes D2 (L120–L122). Code: `internal/core/sql/SQLEngine.java`, `internal/core/sql/parser/YqlStatementCache.java`.
**Issue**: The design and plan refer to the existing parser AST cache as `STATEMENT_CACHE` (e.g. "`STATEMENT_CACHE` returns the same instance for identical text", "`STATEMENT_CACHE` keys by text"). There is no class, field, or constant named `STATEMENT_CACHE`. The actual type is `YqlStatementCache`, a Guava `Cache<String, SQLStatement>` keyed by statement text; `SQLEngine.parse()` routes through `YqlStatementCache.get(...)`. The related config knob IS named correctly (`GlobalConfiguration.STATEMENT_CACHE_SIZE` @ L952, key `youtrackdb.statement.cacheSize`), which is likely the source of the shorthand.
**Evidence**: `SQLEngine.java:78` calls `YqlStatementCache.get(query, db)`; `YqlStatementCache.java:22` holds `private final Cache<String, SQLStatement> cache`; `:92` does `cache.get(statement, () -> parse(statement, session))`, which returns the same cached `SQLStatement` instance for identical text. So the load-bearing D12 claim (identity fast-path `stmt == other.stmt` fires for same-text re-issues) is CORRECT against `YqlStatementCache`; only the symbol name is wrong. Caveat: grep+Read, not PSI; the behavioral confirmation is from reading `YqlStatementCache.get`/the Guava `cache.get(key, loader)` contract directly.
**Proposed fix**: Replace `STATEMENT_CACHE` with `YqlStatementCache` throughout §Cache key composition and plan D2, keeping `STATEMENT_CACHE_SIZE` where it names the knob. No semantic change — the instance-identity behavior the D12 fast-path depends on is real.
**Classification**: mechanical
**Justification**: current-state claim, single unambiguous correct rendering (the existing class is `YqlStatementCache`); the fix updates only the name and preserves the plan's intent.

### CR3 [suggestion]
**Certificate**: Ref: SQLWhereClause.matchesFilters
**Location**: `design.md` §TxDeltaCursor step 2 (L341, "`match_after = entry.whereClause.matchesFilters(op.record, ctx)`"), §Edge cases (L563), Integration Points (L316); plan/track-1 L224, track-3 L50/L195. Code: `internal/core/sql/parser/SQLWhereClause.java:50,57`.
**Issue**: The documents cite `SQLWhereClause.matchesFilters(record, ctx)` and call it as `matchesFilters(op.record, ctx)` where `op.record` is a `RecordAbstract`. The class has no `matchesFilters(RecordAbstract, ...)` overload. The two real overloads are `matchesFilters(Identifiable currentRecord, CommandContext ctx)` (L50) and `matchesFilters(Result currentRecord, CommandContext ctx)` (L57). `RecordAbstract` implements `Identifiable`, so the call resolves to the `Identifiable` overload — the call is valid, the cited signature is just imprecise.
**Evidence**: `grep -n matchesFilters SQLWhereClause.java` → two overloads, `(Identifiable, CommandContext)` and `(Result, CommandContext)`; no `RecordAbstract` parameter form. Caveat: grep+Read, not PSI; overload set read directly from the declaration lines.
**Proposed fix**: In the Integration Points line and §TxDeltaCursor, note the real signature is `matchesFilters(Identifiable, CommandContext)` (a `RecordAbstract` binds via `Identifiable`). Cosmetic; no algorithm change.
**Classification**: mechanical
**Justification**: current-state claim, single unambiguous correct rendering (the existing overload signature); preserves intent.

### CR4 [suggestion]
**Certificate**: Ref: FrontendTransactionImpl.beginInternal
**Location**: `design.md` §Concurrency single-thread table ("`beginInternal()` line 164") and §Concurrency TL;DR ("line 165 (`beginInternal`)"); Integration Points (L312, "beginInternal (165)"); plan Integration Points ("beginInternal (165)"); track-1 ("beginInternal (line 165)"). Code: `FrontendTransactionImpl.java:164`.
**Issue**: `beginInternal()` is declared at line 164; line 165 is its first body statement (`assertOnOwningThread()`). The design cites it as 164 in the table and 165 elsewhere; the plan/track use 165. Trivial off-by-one inconsistency.
**Evidence**: `grep -n` shows `public int beginInternal() {` at 164 and `assertOnOwningThread();` at 165. Caveat: grep+Read.
**Proposed fix**: Standardize on `beginInternal` @ line 164 (the declaration) across the design table and the prose/plan citations. No behavioral impact.
**Classification**: mechanical
**Justification**: current-state claim, single unambiguous correct rendering (declaration line 164); preserves intent.

## Evidence base

Note on tooling: mcp-steroid was reachable and the project was open, but every
`steroid_execute_code` PSI snippet (including a one-class smoke test) hit the
~60 s MCP HTTP timeout on the kotlinc compile/first-run path. Per the prompt's
fallback rule, symbol verdicts below were produced with `grep`/`Read` against
the live `core/src` tree. Each verdict's reference-accuracy is robust for the
checks performed: existence + declaration-line + signature reads are exact
(read straight from the source), and the two negative findings (CR1
`cacheCodeDepth`, CR2 `STATEMENT_CACHE`) are searches for literal identifiers,
which grep cannot miss via polymorphism/overrides. No verdict here rests on a
find-usages count that grep could under- or over-report.

### Design ↔ Code

#### Ref: FrontendTransactionImpl core members
- **Document claim**: design Component Map + Integration Points + track-1 — owns `recordOperations` (L83), `addRecordOperation` (510), `beginInternal` (165), `clearUnfinishedChanges` (≈998), `assertOnOwningThread` (133).
- **Search performed**: grep/Read `tx/FrontendTransactionImpl.java`.
- **Code location**: `recordOperations` @ 83 (`HashMap<RecordIdInternal, RecordOperation>`); `addRecordOperation(RecordAbstract, byte)` @ 510; `beginInternal()` @ 164 (assert @165); `clearUnfinishedChanges()` @ 998 (private); `assertOnOwningThread()` @ 133.
- **Actual signature/role**: all present at cited locations; `recordOperations` keyed by `RecordIdInternal` (plan track-1 L46 states this correctly).
- **Verdict**: MATCHES (beginInternal line nit → CR4).

#### Ref: addRecordOperation collapse arms (D5/D21 dispatch foundation)
- **Document claim**: design D5/D21 — "CREATE+UPDATE stays CREATED per `FrontendTransactionImpl.java:604-610`"; collapse folds successive saves on one RID into a single op whose type reflects the FIRST status.
- **Search performed**: Read lines 510–615.
- **Code location**: switch on `txEntry.type` @ 591–612; `case CREATED` arm @ 604–611.
- **Actual signature/role**: CREATED arm handles `status==DELETED` (→ type DELETED) and throws on second CREATED; a subsequent `UPDATED` status falls through with no action, so type stays CREATED. Confirms the dispatch-table premise. `version` field is not present today (new in Track 1, target-state) — the UPDATE-of-CREATED path is currently a no-op, so track-1 step 2's "stamp version on the collapse path" must add a write into a branch that has no body today; the plan flags this.
- **Verdict**: MATCHES.

#### Ref: tx-end sink topology (I1, single sink)
- **Document claim**: design — `clearUnfinishedChanges()` is the single tx-end sink for commit/rollback/close; cache `clear()` hooks there.
- **Search performed**: grep callers + Read.
- **Code location**: `clearUnfinishedChanges()` called only @ 993 from private `clear()` (972); `close()` (948) → `clear()` (949); `rollbackInternal()` (356) → `clear()` (385)/`close()` (400); `doCommit` (632) → `close()` (698).
- **Actual signature/role**: commit, rollback, and close all converge on `clear()` → `clearUnfinishedChanges()`. The existing private `clear()` also calls `session.closeActiveQueries()` first, matching the design's pause/resume ordering claim.
- **Verdict**: MATCHES.

#### Ref: assertOnOwningThread + cross-thread exemption (I2/I6)
- **Document claim**: design §Concurrency — mutation paths guarded by `assertOnOwningThread` @ lines 165/224/250/474/511; `close()`/`rollbackInternal()` exempt per comment at `FrontendTransactionImpl.java:122-132`; `DatabaseSessionEmbeddedPooled.realClose` may call cross-thread.
- **Search performed**: grep `assertOnOwningThread` + Read 118–135 + locate `realClose`.
- **Code location**: assert calls @ 165, 224, 250, 474, 511 (and 432, 452); exemption comment @ 121–132 explicitly naming `close()`, `rollbackInternal()`, `DatabaseSessionEmbeddedPooled.realClose`; `realClose()` @ `DatabaseSessionEmbeddedPooled.java:58`.
- **Verdict**: MATCHES (cited "122-132" ≈ actual 121-132).

#### Ref: DatabaseSessionEmbedded query path
- **Document claim**: `query()` @617, `executeInternal()` @702, `SQLEngine.parse` @632, `activeQueries` weak-valued @256/238, `closeActiveQueries()` @3431.
- **Search performed**: grep `db/DatabaseSessionEmbedded.java`.
- **Code location**: `query(String, Object...)` @617; `executeInternal(...)` @702; `SQLEngine.parse` @632 (and 672); `activeQueries` field @238, assigned `WeakValueHashMap` in embedded mode @256; `closeActiveQueries()` @3431.
- **Verdict**: MATCHES.

#### Ref: AbstractExecutionStep.prev / SelectExecutionPlan.steps / AggregateProjectionCalculationStep
- **Document claim**: design §Aggregate side-tap — splice upstream of `AggregateProjectionCalculationStep` (≈121-137 blocking loop `prev.start(ctx)` → `while hasNext: aggregate(...)`); rewire its `prev` (public field on `AbstractExecutionStep:66`); walk `SelectExecutionPlan.steps`.
- **Search performed**: grep/Read the three files.
- **Code location**: `AbstractExecutionStep.prev` is `public ExecutionStepInternal prev` @ ~66; `SelectExecutionPlan.steps` is `protected List<ExecutionStepInternal> steps` @54 (public `getSteps()` @138 returns `List<ExecutionStep>`); `AggregateProjectionCalculationStep extends ProjectionCalculationStep` (@58) `extends AbstractExecutionStep` (@43); blocking loop in `executeAggregation(ctx)` — `prev.start(ctx)` @129, `while lastRs.hasNext(ctx)` @131, `aggregate(lastRs.next(ctx), ctx, ...)` @136.
- **Actual signature/role**: `prev` inherited and public, splice reachable; `steps` is a protected field (direct walk works only same-package or via the existing executor internals) — implementation detail for Track 2, not a finding.
- **Verdict**: MATCHES.

#### Ref: parser statement classes
- **Document claim**: `SQLStatement.isIdempotent()` @129 (D3); `SQLStatement.execute(...)` overloads @62/66/83/89 carry `Map<Object,Object>`; `createExecutionPlan(ctx, false)` exists; `SQLSelectStatement.noCache` field + `equals` @380 (D2/D6); `SQLMatchStatement.equals` covers SKIP natively; MATCH grammar has no NOCACHE.
- **Search performed**: grep/Read parser files.
- **Code location**: `SQLStatement.isIdempotent()` @129 returns `false` (subclasses override); `execute(... Map<Object,Object> args ...)` @62/66/83/89; `createExecutionPlan(CommandContext, boolean)` @111; base `SQLStatement` has NO `equals` override (subclasses do). `SQLSelectStatement.noCache` (Boolean) @48, `equals` @380 includes `noCache` @426, `skip`/`limit` @36/38. `SQLMatchStatement.equals` @508 compares `skip` @542.
- **Verdict**: MATCHES (D2's "`SQLStatement.equals()` is structural (`SQLSelectStatement:380`)" correctly points at the subclass that `stmt.equals()` dispatches to).

#### Ref: SQLInputParameter (D22 — confirm no equals/hashCode)
- **Document claim**: D22 / plan / track-1 — `SQLInputParameter` extends `SimpleNode` with no `equals`/`hashCode` (inherits `Object` identity), reached via `SQLSkip`/`SQLLimit.equals`, so a re-parsed AST false-misses; D22 adds field-based overrides.
- **Search performed**: grep/Read `SQLInputParameter.java`, `SQLSkip.java`, `SQLLimit.java`.
- **Code location**: `SQLInputParameter extends SimpleNode` @20 — no `equals`/`hashCode` declared. `SQLSkip.inputParam: SQLInputParameter` @17, `SQLSkip.equals` @78 ends with `Objects.equals(inputParam, oSkip.inputParam)` @91; `SQLLimit` symmetric @17/78.
- **Actual signature/role**: confirms the exact failure mode D22 targets — `SQLSkip.equals`/`SQLLimit.equals` compare `inputParam` by identity (no override on `SQLInputParameter`), so two equal-text re-parses with input params would false-miss on the deep-equals path.
- **Verdict**: MATCHES (D22 current-state claim verified true).

#### Ref: PropertyTypeInternal.increment / SQLFunctionSum / Average / Distinct (D19/D20)
- **Document claim**: SUM/AVG fold via `PropertyTypeInternal.increment(current, value): Number`, same primitive storage uses; `SQLFunctionDistinct` uses `LinkedHashSet<Object>` raw-Object semantics.
- **Search performed**: grep/Read.
- **Code location**: `PropertyTypeInternal.increment(Number a, Number b): Number` static @1782; `SQLFunctionSum` calls `PropertyTypeInternal.increment(sum, value)` @73; `SQLFunctionAverage` @80; `SQLFunctionDistinct` field `Set<Object> context = new LinkedHashSet<>()` @37.
- **Verdict**: MATCHES.

#### Ref: three SQLFunctionFactory impls + math_ reflective registration (D6/I5)
- **Document claim**: I5 enumerates `DefaultSQLFunctionFactory`, `CustomSQLFunctionFactory` (reflective `math_*`), `DatabaseFunctionFactory`; `register(prefix, clazz)` exposes static methods as `<prefix>methodName`, so `Math.random()` → `math_random`.
- **Search performed**: find/grep/Read `sql/functions/`, `metadata/function/`.
- **Code location**: `DefaultSQLFunctionFactory`, `CustomSQLFunctionFactory` (@20), `DatabaseFunctionFactory` all implement `SQLFunctionFactory`. `CustomSQLFunctionFactory` static init `register("math_", Math.class)` @25; `register(String prefix, Class<?> clazz)` @28 iterates `clazz.getMethods()` @30 and names them `prefix+methodName`. So `Math.random()` registers as `math_random`.
- **Verdict**: MATCHES.

#### Ref: SchemaClass.getAllSubclasses (D11)
- **Document claim**: subclass closure via `SchemaClass.getAllSubclasses()`, stable per tx (I8).
- **Search performed**: grep `metadata/schema/`.
- **Code location**: `SchemaClassImpl.getAllSubclasses()` @652 (recursive — `set.addAll(c.getAllSubclasses())` @660, so it returns the full transitive closure); `SchemaClassProxy.getAllSubclasses()` @433 delegates.
- **Actual signature/role**: returns `Collection<? extends SchemaClass>` (objects, not names); the design needs a `Set<String>` so the execution agent maps to names — target-state detail, not a finding. The closure is already transitive, matching "subclass closure".
- **Verdict**: MATCHES.

#### Ref: STATEMENT_CACHE / YqlStatementCache (D2/D12)
- **Document claim**: `STATEMENT_CACHE` memoizes `SQLStatement` by text, returns same instance for identical text (D12 identity fast-path).
- **Search performed**: grep `SQLEngine.java`, `GlobalConfiguration.java`, `YqlStatementCache.java`; grep literal `STATEMENT_CACHE`.
- **Code location**: no symbol named `STATEMENT_CACHE` in `SQLEngine`. Actual: `YqlStatementCache` (Guava `Cache<String, SQLStatement>` @22), `SQLEngine.parse` → `YqlStatementCache.get` @78, atomic `cache.get(statement, () -> parse(...))` @92 returns the cached instance. `GlobalConfiguration.STATEMENT_CACHE_SIZE` @952 (knob name, correct).
- **Verdict**: PARTIAL → CR2 (behavior correct; symbol name `STATEMENT_CACHE` does not exist, real class is `YqlStatementCache`).

#### Ref: cacheCodeDepth (re-entrancy guard)
- **Document claim**: design L641/L742/L744 — "existing concern from SO5 / `cacheCodeDepth`"; the SO5 re-entrancy guard "relies on `cacheCodeDepth`"; cache must respect `cacheCodeDepth > 0` bypass.
- **Search performed**: grep `cacheCodeDepth` across `core/src`; grep `inFlightLookup` in design/plan.
- **Code location**: NOT FOUND — zero matches for `cacheCodeDepth`. The re-entrancy field the design otherwise specifies and the plan builds is `inFlightLookup: boolean` on `QueryResultCache` (design L57/L714, plan Component Map, track-1 step 7).
- **Verdict**: NOT FOUND / MISMATCHES → CR1.

#### Ref: SQLTruncateClassStatement / RecordIteratorCollection.nextTxId / loadRecord / LocalResultSet
- **Document claim**: D3 bulk-DML invalidation via `SQLTruncateClassStatement`; D21 tx-CREATED emitted via `RecordIteratorCollection.nextTxId`, tx-UPDATED via `loadRecord`, tx-DELETED throws `RecordNotFoundException`; splice fallback returns a `LocalResultSet`.
- **Search performed**: find/grep.
- **Code location**: `SQLTruncateClassStatement.java` present; `RecordIteratorCollection.nextTxId` field @46 driving the tx-record phase @95–203; `FrontendTransactionImpl.loadRecord(RID)` @451 throws `RecordNotFoundException` @456; `LocalResultSet.java` present (`sql/parser/`).
- **Verdict**: MATCHES.

#### Ref: SQLWhereClause.matchesFilters overloads
- **Document claim**: `matchesFilters(record, ctx)` called as `matchesFilters(op.record, ctx)` (op.record is RecordAbstract).
- **Search performed**: grep `SQLWhereClause.java`.
- **Code location**: `matchesFilters(Identifiable currentRecord, CommandContext ctx)` @50; `matchesFilters(Result currentRecord, CommandContext ctx)` @57. No `RecordAbstract` overload.
- **Actual signature/role**: `RecordAbstract` implements `Identifiable`, so the call binds to the `Identifiable` overload — valid; cited signature imprecise.
- **Verdict**: PARTIAL → CR3.

### Plan ↔ Code

All plan Integration Points (FrontendTransactionImpl 510/165/998, DatabaseSessionEmbedded 617/702, AggregateProjectionCalculationStep ≈121-137, SQLStatement.isIdempotent 129, SQLSelectStatement.noCache, SQLWhereClause.matchesFilters, GlobalConfiguration) resolve to the certificates above. Track-1 / track-2 / track-3 `## Context and Orientation` current-state claims (recordOperations map type + line, addRecordOperation collapse, activeQueries weak-value @238, closeActiveQueries @3431, SQLMatchStatement grammar lacking NOCACHE, SchemaClass.getAllSubclasses, the three factories, increment) all MATCH. The new cache classes named across all three tracks are target-state (`[ ]` tracks) — not findings per the intent-axis pre-screen.

#### Invariant: I1 — cache cleared on every tx-end path
- **Document claim**: `clearUnfinishedChanges()` → cache `clear()` on commit/rollback/close.
- **Code evidence**: `clearUnfinishedChanges()` @998 reached by all three paths (see flow above).
- **Mechanism**: cache `clear()` to be invoked from `clearUnfinishedChanges` (target-state hook, Track 1).
- **Verdict**: ASPIRATIONAL (correctly tagged; sink exists, hook is Track-1 work) — not a finding.

#### Invariant: I2 — mutation paths owner-thread-only
- **Document claim**: all cache mutation paths under `assertOnOwningThread`; tx-end `clear()` the documented cross-thread exception.
- **Code evidence**: `assertOnOwningThread` @133 guards begin/commit/read/write/delete; exemption comment @121-132 names close/rollbackInternal.
- **Verdict**: ENFORCED for the existing guard surface the cache rides on — not a finding.

#### Invariant: I8 — schema immutable per tx (DDL unreachable mid-tx)
- **Document claim**: schema DDL excluded from cache invalidation because I8 makes it unreachable mid-tx; a `Java assert` canary fires if a schema-DDL statement reaches the cache hook.
- **Code evidence**: enforcement cited as `SchemaShared.saveInternal` / `IndexManagerEmbedded` (not independently traced this pass — the design tags I8 "enforced upstream"); `SQLTruncateClassStatement` confirmed present as the one mid-tx bulk op the design hooks.
- **Verdict**: ASPIRATIONAL (the canary assert is Track-1 target-state) — not a finding; deeper I8-enforcement trace deferred (the design itself defers to upstream enforcement).

### Design ↔ Plan

Design Class Design diagram classes (`QueryResultCache`, `CacheKey`, `CachedEntry`, `CacheableShape`, `AggregateState`, `TxDeltaCursor`, `MatchMultiDelta`, `DeltaBuilder`, `CachedResultSetView`) map 1:1 to the plan Component Map and track scopes (Track 1 foundation + RECORD/K0_NONE, Track 2 aggregate, Track 3 MATCH). DRs D1–D11 in the plan correspond to design D-records D1/D2/D4/D5-lazy/D8-lazy/D11/D18/D19/D20/D21/D22. No orphan design class lacks a track; no track references a design construct absent from the design. The `inFlightLookup` field appears in both the design diagram (L57) and the plan Component Map — consistent except for the CR1 `cacheCodeDepth` prose drift inside the design itself.

### Gaps

No plan element lacks design coverage and no design section lacks a covering track. The companion `design-mechanics.md` (view.next() sorted-merge, MATCH two-pass) is all target-state pseudocode for new types — no current-state code claims to verify there. The only cross-document gap is internal to the design: the re-entrancy guard is specified as `inFlightLookup` in the structural sections and as the non-existent `cacheCodeDepth` in three prose passages (CR1).
