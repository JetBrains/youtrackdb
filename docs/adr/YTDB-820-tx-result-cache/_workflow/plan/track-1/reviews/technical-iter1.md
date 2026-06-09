<!-- MANIFEST
findings: 4   severity: {blocker: 1, should-fix: 2, suggestion: 1}
index:
  - {id: T1, sev: blocker,    loc: "core/.../internal/core/tx/FrontendTransactionImpl.java:29,83", anchor: "### T1 ", cert: P3, basis: "Track names RecordOperation in internal/core/tx/, but the tx mutation log uses internal/core/db/record/RecordOperation; the tx one is an immutable record of a different shape"}
  - {id: T2, sev: should-fix, loc: "core/.../internal/core/sql/parser/SQLInputParameter.java:20", anchor: "### T2 ", cert: P4, basis: "D22 premise is imprecise: parameter AST leaves are always SQLNamedParameter/SQLPositionalParameter, which already have field-based equals/hashCode; base-class fix is redundant for hit correctness"}
  - {id: T3, sev: should-fix, loc: "core/.../internal/core/tx/FrontendTransactionImpl.java:590-613", anchor: "### T3 ", cert: P3, basis: "Track claims collapse type reflects FIRST status; code mutates type in place so CREATE->DELETE and UPDATE->DELETE become DELETED (latest), only CREATE->UPDATE keeps the first"}
  - {id: T4, sev: suggestion, loc: "core/.../internal/core/sql/parser/SQLStatement.java", anchor: "### T4 ", cert: P5, basis: "D2 says SQLStatement.equals() is already structural; base SQLStatement has no equals/hashCode, structurality lives only on SQLSelectStatement; note for decomposer"}
evidence_base: {section: "## Evidence base", certs: 12, matches: 9}
cert_index:
  - {id: P1, verdict: CONFIRMED, anchor: "#### P1 "}
  - {id: P2, verdict: CONFIRMED, anchor: "#### P2 "}
  - {id: P3, verdict: PARTIAL,   anchor: "#### P3 "}
  - {id: P4, verdict: PARTIAL,   anchor: "#### P4 "}
  - {id: P5, verdict: PARTIAL,   anchor: "#### P5 "}
  - {id: P6, verdict: CONFIRMED, anchor: "#### P6 "}
  - {id: P7, verdict: CONFIRMED, anchor: "#### P7 "}
  - {id: P8, verdict: CONFIRMED, anchor: "#### P8 "}
  - {id: P9, verdict: CONFIRMED, anchor: "#### P9 "}
  - {id: P10, verdict: CONFIRMED, anchor: "#### P10 "}
  - {id: E1, verdict: NOTED,     anchor: "#### E1 "}
  - {id: I1, verdict: MATCHES,   anchor: "#### I1 "}
flags: [CONTRACT_OK]
-->

# Technical review — Track 1 (iteration 1)

Reference-accuracy caveat for the whole file: `steroid_execute_code` (PSI) was
unreachable this session — every call, including a trivial `"warmup ok"`,
exceeded the MCP HTTP timeout, while `steroid_list_projects` and
`steroid_list_windows` returned normally. All symbol facts below were gathered
by exact-line `grep`/`sed`/`find` against the single non-worktree main tree
(`core/src/main/java/...`). Existence and signature claims are therefore
high-confidence; this review did not need an exhaustive caller/override
enumeration to drive any finding, so the usual grep caveat (missed polymorphic
or generic call sites) does not affect the conclusions. The `find` for each
named class returned exactly one main-tree match (the rest were `.claude/worktrees/`
copies), so the named-reference existence checks are unambiguous.

## Findings

### T1 [blocker]
**Certificate**: Premise P3 (RecordOperation identity / package)
**Location**: Track file `## Context and Orientation` ("`RecordOperation`
(`internal/core/tx/`) gains a `version: long` field …") and `## Interfaces and
Dependencies` ("`RecordOperation` (`version` field)"); plan Component Map
("`RecordOperation` gains a `version: long`"). Source:
`internal/core/tx/FrontendTransactionImpl.java:29,83`,
`internal/core/db/record/RecordOperation.java`, `internal/core/tx/RecordOperation.java`.
**Issue**: Two distinct `RecordOperation` classes exist, and the track names the
wrong one.
- `internal/core/tx/RecordOperation` is a Java **record**:
  `public record RecordOperation(@Nonnull DBRecord record, @Nonnull RecordOperationType type) {}`.
  It is immutable and is **not** the transaction mutation-log entry.
- `internal/core/db/record/RecordOperation` is the mutable class actually used:
  `FrontendTransactionImpl` imports `internal.core.db.record.RecordOperation`
  (line 29); the canonical log is
  `HashMap<RecordIdInternal, RecordOperation> recordOperations` (line 83) typed
  with that class; `addRecordOperation` writes `new RecordOperation(record, status)`
  (≈line 568) and reads `RecordOperation.DELETED/UPDATED/CREATED` byte constants.
  This class has `public byte type`, implements `Comparable<RecordOperation>`, and
  is where the `version: long` field (D5) must land.

Adding `version` to the `tx/` record would compile but have zero effect on the
mutation log the delta builder reads — the populate-version filter
(`op.version > populateMutationVersion`, D5/D21) would never see a stamped
version. Step 2 ("`mutationVersion` + `RecordOperation.version`") and step 8
(`DeltaBuilder.buildForRecord` reading `op.version`/`op.type`) both target the
wrong type.
**Proposed fix**: Correct every reference in the track file (`## Context and
Orientation`, `## Interfaces and Dependencies`) and the plan Component Map from
`internal/core/tx/RecordOperation` to
`internal/core/db/record/RecordOperation`. Add the `version: long` field, the
`op.type` reads, and the collapse re-stamp to the `db.record` class. The
decomposer should pin the FQN in step 2's roster entry to avoid the ambiguity
recurring.

### T2 [should-fix]
**Certificate**: Premise P4 (D22 SQLInputParameter equals/hashCode)
**Location**: Track `## Context and Orientation` (D22 paragraph), `## Plan of
Work` step 3, plan D2 Risks/Caveats. Source:
`internal/core/sql/parser/SQLInputParameter.java:20`,
`SQLNamedParameter.java:85-105`, `SQLPositionalParameter.java:74-90`,
`YouTrackDBSql.jj:3163-3185`.
**Issue**: D22's premise is imprecise and the planned fix is largely redundant
for hit correctness. The track says `SQLInputParameter` "inherits `Object`
identity, so a re-parsed AST after `YqlStatementCache` eviction would
false-miss," and step 3 adds field-based equals/hashCode to `SQLInputParameter`.
Two facts undercut that:
1. The base `SQLInputParameter` has no instance state of its own (only a static
   `dateFormatString` constant). There is nothing field-based to compare on the
   base class.
2. The parameter leaf nodes that actually appear in a parsed AST are always the
   concrete subclasses. The grammar production `InputParameter()` returns
   `PositionalParameter()` or `NamedParameter()` — the bare
   `new SQLInputParameter(JJTINPUTPARAMETER)` is only the JJTree node-scope
   bookkeeping object and is never returned into the tree. Both subclasses
   **already** override `equals`/`hashCode` with field comparison
   (`SQLNamedParameter`: `paramNumber` + `paramName`; `SQLPositionalParameter`:
   `paramNumber`), and both guard with `getClass() != o.getClass()`.

So a re-parsed AST's parameter nodes already compare by value; the cache hit
after `YqlStatementCache` eviction does not depend on a base-class equals. The
D22 change is harmless but does not buy the correctness it claims, and the
"false-miss" risk it is meant to close does not exist at the parameter level.
**Proposed fix**: Either (a) drop the base-class equals/hashCode work and the
D22 risk note as already satisfied by the subclasses (the regression test in
step 3 that forces eviction + re-parse should still be written — it proves the
existing subclass equals carries the hit), or (b) keep a defensive base-class
override but re-state the rationale: it guards any future AST node that holds a
bare `SQLInputParameter` field and compares it, not the parameter leaves. Update
the track `## Context and Orientation` D22 paragraph and plan D2 Risks/Caveats to
state that the concrete parameter subclasses already implement field equality.

### T3 [should-fix]
**Certificate**: Premise P3 (addRecordOperation collapse semantics)
**Location**: Track `## Context and Orientation` ("collapse logic in
`addRecordOperation` (line 510) that folds successive saves on one RID into a
single op whose `type` reflects the FIRST status"). Source:
`internal/core/tx/FrontendTransactionImpl.java:590-613`.
**Issue**: The collapse does not keep the FIRST status in general; it mutates
`txEntry.type` in place per a transition table:
- `CREATED` then `DELETED` → `type = DELETED` (latest wins)
- `CREATED` then `UPDATED` → stays `CREATED` (first wins)
- `UPDATED` then `DELETED` → `type = DELETED` (latest wins)
- `UPDATED` then `CREATED` → `IllegalStateException`
- `DELETED` then anything → `IllegalStateException`

So `type` reflects the FIRST status only on the CREATE→UPDATE path; on the two
DELETE paths it reflects the LATEST. The delta-build dispatch table (D5/D21,
step 8) keys on `op.type` plus the `cached_at_build` column, so the inaccurate
"reflects the FIRST status" framing in the track could mislead the decomposer
when it writes the `(op.type, cached_at_build, match_after)` cases — in
particular the CREATE-then-DELETE collapse (entry created post-populate then
deleted) presents as a single `DELETED` op, not a CREATE+DELETE pair.
**Proposed fix**: Reword the `## Context and Orientation` sentence to describe
the actual transition table (CREATE→DELETE and UPDATE→DELETE collapse to
DELETED; CREATE→UPDATE stays CREATED; the illegal transitions throw). Ensure
step 8's dispatch-table decomposition enumerates the collapsed `DELETED`-after-
`CREATED` case explicitly, since that is the case D5's `cached_at_build` column
is called out as load-bearing for.

### T4 [suggestion]
**Certificate**: Premise P5 (SQLStatement.equals structurality)
**Location**: Plan D2 Rationale ("`SQLStatement.equals()` is already
structural"). Source: `internal/core/sql/parser/SQLStatement.java` (no
equals/hashCode), `SQLSelectStatement.java:420-468`.
**Issue**: The base `SQLStatement` has no `equals`/`hashCode`. The structural
equality D2 relies on lives on `SQLSelectStatement` (overrides both: target,
projection, whereClause, groupBy, orderBy, unwind, skip, limit, fetchPlan,
letClause, timeout, parallel, noCache). This is fine for Track 1 because
`CacheKey` is built only for `SQLSelectStatement` (RECORD/K0_NONE) here, but the
plan's general phrasing "`SQLStatement.equals()` is already structural" is
inaccurate for the abstract base and for `SQLMatchStatement` (Track 3 must
verify `SQLMatchStatement.equals()` independently before keying on it).
**Proposed fix**: No Track 1 change required. Note for the decomposer / Track 3:
`CacheKey.equals` must confirm structural equals exists on the concrete
statement type it keys (verified for `SQLSelectStatement`; `SQLMatchStatement`
to be re-verified in Track 3). Optionally tighten the plan D2 wording to
"`SQLSelectStatement.equals()` is structural."

## Evidence base

#### P1 FrontendTransactionImpl exists with cited members
- **Track claim**: `FrontendTransactionImpl` (`internal/core/tx/`) holds
  `recordOperations` (line 83), `addRecordOperation` (line 510), `beginInternal`
  (164), `assertOnOwningThread` (133), `clearUnfinishedChanges` as the tx-end sink.
- **Search performed**: `find` (single main-tree match) + `grep -n` for each
  member (PSI unreachable — see file-level caveat).
- **Code location**: `core/src/main/java/.../internal/core/tx/FrontendTransactionImpl.java`
  — class @76, `recordOperations` @83, `assertOnOwningThread` @133,
  `beginInternal` @164, `addRecordOperation` @510, `clearUnfinishedChanges` @998.
- **Actual behavior**: `protected final HashMap<RecordIdInternal, RecordOperation>
  recordOperations` (83); `private void assertOnOwningThread()` (133);
  `public int beginInternal()` calls `assertOnOwningThread()` first (164-165);
  `public void addRecordOperation(RecordAbstract record, byte status)` calls
  `assertOnOwningThread()` first (510-511); `private void clearUnfinishedChanges()`
  clears `recordOperations`, `recordsInTransaction`, `indexEntries`,
  `recordIndexOperations` (998) and is called from the close/rollback path (993).
- **Verdict**: CONFIRMED
- **Detail**: All five line citations land within ±1 of the actual lines.
  `clearUnfinishedChanges` is `private`, so the `clear()` hooks the track adds in
  `beginInternal` and `clearUnfinishedChanges` are intra-class (no access issue).

#### P2 DatabaseSessionEmbedded entry points
- **Track claim**: `DatabaseSessionEmbedded.query()` (617) → `SQLEngine.parse()`
  → `executeInternal` (702); `activeQueries` (238) is a `WeakValueHashMap` in
  embedded mode; `closeActiveQueries()` (3431) closes live `ResultSet`s on tx end.
- **Search performed**: `grep -n` for each symbol + surrounding `sed`.
- **Code location**: `.../internal/core/db/DatabaseSessionEmbedded.java` — class
  @174, `activeQueries` field @238, ctor `serverMode ? new HashMap<>() : new
  WeakValueHashMap<>()` @256, `public ResultSet query(String, Object...)` @617,
  `private ResultSet executeInternal(...)` @702, `closeActiveQueries()` @3431,
  called from `internalClose` @2218.
- **Actual behavior**: `executeInternal` is `private` (same-class wiring is fine).
  `activeQueries` is a `WeakValueHashMap` only in embedded (non-server) mode;
  server mode uses `HashMap`. `closeActiveQueries()` iterates a defensive copy
  `new ArrayList<>(activeQueries.values())` (3432).
- **Verdict**: CONFIRMED
- **Detail**: Line citations exact. The track's ordering claim
  ("closeActiveQueries ordered before clearUnfinishedChanges") was not fully
  traced through `internalClose`; `closeActiveQueries()` is the first body
  statement of `internalClose` (2218) — see I1.

#### P3 RecordOperation — two classes; collapse semantics
- **Track claim**: `RecordOperation` (`internal/core/tx/`) is the mutation-log
  entry that gains `version: long`; collapse in `addRecordOperation` "folds
  successive saves … into a single op whose `type` reflects the FIRST status."
- **Search performed**: `find RecordOperation.java` (two main-tree matches),
  import grep in `FrontendTransactionImpl`, `sed` of both class heads and the
  collapse else-branch.
- **Code location**: `internal/core/tx/RecordOperation.java` (a `record`);
  `internal/core/db/record/RecordOperation.java` (the mutable class);
  `FrontendTransactionImpl.java:29` import; collapse switch @590-613.
- **Actual behavior**: `tx/RecordOperation` =
  `record RecordOperation(DBRecord record, RecordOperationType type)`.
  `db/record/RecordOperation` = mutable `class` with `public byte type`,
  `CREATED/UPDATED/DELETED` byte constants, `Comparable`. `FrontendTransactionImpl`
  imports the `db.record` one (line 29) and the log is typed with it (line 83).
  Collapse mutates `txEntry.type` in place: CREATED→DELETED ⇒ DELETED,
  CREATED→UPDATED ⇒ stays CREATED, UPDATED→DELETED ⇒ DELETED; UPDATED→CREATED and
  any-after-DELETED throw `IllegalStateException`.
- **Verdict**: PARTIAL
- **Detail**: Wrong package named (the `tx/` class is not the log entry) → T1
  blocker. Collapse "reflects the FIRST status" is true only for CREATE→UPDATE →
  T3 should-fix.

#### P4 SQLInputParameter equals/hashCode and AST leaf types
- **Track claim**: `SQLInputParameter` extends `SimpleNode` with no
  equals/hashCode (confirmed) — inherits `Object` identity, so a re-parsed AST
  would false-miss; D22 adds field-based equals/hashCode.
- **Search performed**: `grep` of base + both subclasses; `sed` of equals bodies;
  `grep` of `new SQLInputParameter(` in `sql/`; `sed` of the `InputParameter()`
  grammar production in `YouTrackDBSql.jj` and the generated `.java`.
- **Code location**: `SQLInputParameter.java:20` (extends `SimpleNode`, no
  equals/hashCode, only a static `dateFormatString`); `SQLNamedParameter.java:85-105`
  and `SQLPositionalParameter.java:74-90` (both override equals/hashCode with
  field comparison and `getClass()` guard); `YouTrackDBSql.jj:3163-3185` /
  `YouTrackDBSql.java:4451-4495` (`InputParameter()` returns
  `PositionalParameter()` | `NamedParameter()`).
- **Actual behavior**: Base has no equals/hashCode — true. But the AST parameter
  leaves are always the concrete subclasses, which already compare by value; the
  bare `SQLInputParameter` instance in the production is JJTree bookkeeping, not
  returned.
- **Verdict**: PARTIAL
- **Detail**: The "no override on base" half is confirmed, but the "would
  false-miss" conclusion does not hold at the parameter level because the actual
  nodes already implement field equality → T2 should-fix.

#### P5 SQLStatement / SQLSelectStatement structural equals
- **Track claim** (plan D2): "`SQLStatement.equals()` is already structural,
  giving whitespace/alias-invariant keys for free."
- **Search performed**: `grep` for equals/hashCode in `SQLStatement.java`; `sed`
  of `SQLSelectStatement` equals/hashCode.
- **Code location**: `SQLStatement.java` (no equals/hashCode);
  `SQLSelectStatement.java:420-468` (structural equals over target, projection,
  whereClause, groupBy, orderBy, unwind, skip, limit, fetchPlan, letClause,
  timeout, parallel, noCache; matching hashCode).
- **Actual behavior**: Structurality lives on `SQLSelectStatement`, not on the
  abstract base. Correct for Track 1's RECORD/K0_NONE keys (all
  `SQLSelectStatement`).
- **Verdict**: PARTIAL
- **Detail**: Accurate for the concrete type Track 1 keys; the base-class
  phrasing is loose and `SQLMatchStatement.equals()` is unverified for Track 3 →
  T4 suggestion.

#### P6 SQLSelectStatement.noCache + isIdempotent (D3, D6)
- **Track claim**: gate on `isIdempotent()` + type check; `SQLSelectStatement.noCache`
  already parses and the cache becomes its first consumer.
- **Search performed**: `grep -n` for `noCache` and `isIdempotent` in
  `SQLSelectStatement.java`, `SQLStatement.java`, `SQLMatchStatement.java`.
- **Code location**: `SQLSelectStatement.java:48` `protected Boolean noCache;`
  with getter `isNoCache`/setter `setNoCache` (495), used at 189/253;
  `isIdempotent()` @475; base `SQLStatement.isIdempotent()` @129;
  `SQLMatchStatement.isIdempotent()` @389.
- **Actual behavior**: `noCache` is a parsed, get/set field; `isIdempotent()`
  exists on base + select + match.
- **Verdict**: CONFIRMED

#### P7 SQLWhereClause.matchesFilters signature (CR3)
- **Track claim**: `SQLWhereClause#matchesFilters(Identifiable, CommandContext): boolean`
  (existing, reused; `RecordAbstract` binds via `Identifiable`).
- **Search performed**: `grep -n matchesFilters` in `SQLWhereClause.java`.
- **Code location**: `SQLWhereClause.java:50`
  `public boolean matchesFilters(Identifiable currentRecord, CommandContext ctx)`;
  overload @57 `matchesFilters(Result, CommandContext)`.
- **Actual behavior**: Exactly the CR3-corrected signature exists; a `Result`
  overload also exists.
- **Verdict**: CONFIRMED

#### P8 GlobalConfiguration location (config knobs)
- **Track claim**: add four `youtrackdb.query.txResultCache.*` knobs to
  `GlobalConfiguration`.
- **Search performed**: `find GlobalConfiguration.java`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/api/config/GlobalConfiguration.java`
  (single main-tree match).
- **Actual behavior**: Class exists at `api.config` (a public-API enum-style
  config registry). Knob addition is the standard new-enum-constant pattern.
- **Verdict**: CONFIRMED
- **Detail**: Note the FQN is `api.config`, not an `internal` package — adding
  knobs touches a public-API file. This is the established place for all
  `youtrackdb.*` knobs, so it is expected, not a compat concern.

#### P9 CoreMetrics location
- **Track claim**: add `QueryCacheMetrics`/`CoreMetrics` counters (hits, misses,
  spliceFailures, k0Invalidations, overflows).
- **Search performed**: `find CoreMetrics.java`.
- **Code location**: `core/.../internal/common/profiler/metrics/CoreMetrics.java`
  (single main-tree match).
- **Actual behavior**: Exists at `internal.common.profiler.metrics` (not the
  `internal/core/metric` or `internal/core/metadata` the FQN might suggest). The
  track does not assert an FQN, so this is consistent.
- **Verdict**: CONFIRMED

#### P10 New-package classes are planned (not existing)
- **Track claim**: new package with `QueryResultCache`, `CacheKey`, `CachedEntry`,
  `CacheableShape`, `ShapeClassifier`, `NonDeterministicQueryDetector`,
  `TxDeltaCursor`, `DeltaBuilder`, `CachedResultSetView`,
  `IdempotentExecutionStream`, `QueryCacheMetrics`.
- **Search performed**: track `## Interfaces and Dependencies` "In scope (new)"
  list read; these names are explicitly created by this track.
- **Code location**: N/A — planned-class case.
- **Actual behavior**: Per the prompt's planned-class rule, a non-resolving name
  for a class the track explicitly creates is CONFIRMED-as-planned.
- **Verdict**: CONFIRMED
- **Detail**: planned by this track.

#### E1 RecordOperation collapse + no record-content snapshot (D5/D21 dependency)
- **Trigger**: A RID created or updated pre-populate, then collapsed by a later
  save, then read through a cached entry's delta.
- **Code path trace**:
  1. `addRecordOperation(record, status)` @510 — on an existing `txEntry`, the
     collapse switch @590 mutates `txEntry.type` in place; the same `record`
     reference is retained, no content snapshot is taken.
  2. The collapse does not currently stamp any version (none exists yet); step 2
     adds the `mutationVersion` stamp on both paths.
  3. `DeltaBuilder.buildForRecord` (step 8) will read `op.type` + `op.version`
     from the collapsed entry.
- **Outcome**: Because collapse retains the latest `type` on DELETE paths and
  folds onto the live `record`, the D21 `cached_at_build` column is load-bearing
  exactly for the CREATE-then-DELETE-post-populate case (presents as a single
  `DELETED`). The plan D5 already calls this column "load-bearing"; the track
  prose's "FIRST status" wording obscures it.
- **Track coverage**: partial — the collapse is mentioned but its actual
  transition semantics are mis-stated (T3).

#### I1 closeActiveQueries ordered before clearUnfinishedChanges (lifecycle)
- **Plan claim**: `closeActiveQueries()` closes every live `ResultSet` on tx end,
  ordered before `clearUnfinishedChanges`; `clear()` hooks go in `beginInternal`
  and `clearUnfinishedChanges`.
- **Actual entry point**: `DatabaseSessionEmbedded.internalClose` @2218 calls
  `closeActiveQueries()` as its first body statement;
  `FrontendTransactionImpl.clearUnfinishedChanges` @998 is the tx-end record sink.
- **Caller analysis**: not exhaustively enumerated (PSI down); the documented
  single tx-end sink (`clearUnfinishedChanges`) and the session close path are
  the two the track wires `clear()` into. The cross-thread `clear()` exception
  (pool shutdown via `close()`/`rollbackInternal()`) matches the existing
  `assertOnOwningThread` exception set.
- **Breaking change risk**: low — `clear()` additions are new behavior behind the
  enabled flag; when disabled the cache field is null and the hooks no-op.
- **Verdict**: MATCHES
- **Detail**: Ordering of `closeActiveQueries` before record clearing is
  consistent with the D9 idempotent-close requirement (the `IdempotentExecutionStream`
  wrapper must already be in both close slots by then).
