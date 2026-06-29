<!--MANIFEST
dimension: bugs-concurrency
step: 6
iteration: 1
commit_range: 2bf7d95305f8b14ba01430dde79e109752e9e8d0~1..2bf7d95305f8b14ba01430dde79e109752e9e8d0
verdict: APPROVED
counts: {blocker: 0, should-fix: 0, suggestion: 2}
evidence_base: 4
cert_index: [C1, C2, C3, C4]
flags: []
index:
  - id: BC1
    sev: suggestion
    anchor: "#bc1-suggestion--both-converted-sites-dereference-getimmutableschemasnapshot-without-a-null-check"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBGraphImplAbstract.java:129-130"
    cert: C1
    basis: "code-trace + PSI find-usages"
  - id: BC2
    sev: suggestion
    anchor: "#bc2-suggestion--entityimplgetschemaclass-remains-a-lock-based-tx-aware-read-not-noted-in-the-enumeration"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/record/impl/EntityImpl.java:3863"
    cert: C4
    basis: "PSI find-usages"
-->

## Findings

### BC1 [suggestion] — Both converted sites dereference `getImmutableSchemaSnapshot()` without a null check

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBGraphImplAbstract.java` (line 129-130);
  `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMatchStatement.java` (line 371-372)
- **Issue**: `MetadataDefault.getImmutableSchemaSnapshot()` is annotated `@Nullable` and returns `null`
  when both `immutableSchema` and `schema` are null (MetadataDefault.java:105-114). Both converted sites
  chain `.getClass(...)` straight onto the result with no intervening null check:
  `snapshot.getClass(label)` (YTDBGraphImplAbstract:130) and `schema.getClass(className1)`
  (SQLMatchStatement:372). If the snapshot were ever null, the call would NPE instead of the prior code's
  behavior. The pre-change reads could not NPE on a null schema container: `createVertexWithClass` read
  `session.getSharedContext().getSchema()`, which returns the `SchemaShared` instance directly
  (SharedContext.java:236, never null on an open session), and `getLowerSubclass` read
  `session.getMetadata().getSchema()`, which returns the non-null `SchemaProxy`. So the swap introduces a
  new (theoretical) null-deref surface that did not exist before.
- **Evidence**: The null branch in `getImmutableSchemaSnapshot()` fires only when `metadata.schema` is
  itself null, which on an open, initialized session does not happen — both call sites run on an open
  session (`createVertexWithClass` inside `executeSchemaCode`, which acquires a pooled open session;
  `getLowerSubclass` during MATCH execution on the active session, guarded by `assertIfNotActive()` up the
  stack). PSI confirms no production path reaches either site with a metadata whose `schema` is null. The
  concern is latent, not live.
- **Refutation considered**: (1) *Could the snapshot legitimately be null here?* Only on a closed or
  uninitialized session; the surrounding `executeSchemaCode` / MATCH-execution contexts both require an
  open session, so it cannot happen in practice. (2) *Did the old code already risk this?* No — both old
  read targets (`SharedContext.getSchema()` returning `SchemaShared`, `MetadataDefault.getSchema()`
  returning `SchemaProxy`) are non-null on an open session, so this is a small new surface, not a
  pre-existing one. Reported as a suggestion because it is unreachable today.
- **Suggestion**: Optional. If defensive symmetry with the rest of the planner is wanted, mirror the
  pattern at `FetchFromClassExecutionStep.loadClassFromSchema` and other snapshot consumers (which also
  deref the snapshot directly and rely on the open-session invariant) — so no change is strictly needed.
  If you prefer to fail loudly, add `Objects.requireNonNull(snapshot, "schema snapshot")` at each site so
  a future closed-session regression surfaces with a clear message rather than a bare NPE.

### BC2 [suggestion] — `EntityImpl.getSchemaClass()` remains a lock-based tx-aware read, not noted in the enumeration

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/record/impl/EntityImpl.java` (line 3863)
- **Issue**: The step's stated job (track-4.md "Read-site enumeration (D19, T3/R3)") is to confirm only
  the two converted sites remain non-snapshot among the *hot* `SchemaShared.lock`-based reads. The PSI
  sweep behind this review surfaced one more production reader of the lock-based tx-aware proxy on a
  potentially per-record path: `EntityImpl.getSchemaClass()` calls
  `session.getMetadata().getSchema().getClass(className)` (the proxy, which `acquireSchemaReadLock()`s via
  `SchemaShared.getClass` and is tx-aware via `resolve()`). This is not a defect — it is by design — but
  it is the kind of site the enumeration claim invites a reviewer to check, and the diff carries no note
  recording why it is exempt.
- **Evidence**: PSI find-usages of `EntityImpl.getSchemaClass()` returns exactly three production callers
  (`EntityImpl.copy` helper, `EdgeEntityImpl.getSchemaClass` delegate, and
  `JSONSerializerJackson.createRecordFromJsonAfterMetadata`) — none on the commit-contended per-vertex or
  per-MATCH hot path. The genuinely hot per-record reads on `EntityImpl` (`fetchClassName`,
  `setClassNameIfExists`, `getImmutableSchemaClass`, `validate`, `checkClass`) already route through
  `getImmutableSchemaSnapshot()` (confirmed by the 174-caller PSI sweep of `getImmutableSchemaSnapshot`).
  `getSchemaClass()` returns the *tx-aware* class deliberately: an entity bound inside a transaction may
  need its tx-local class, which the committed snapshot would not show. It therefore falls under the
  enumeration's "off the commit-contended hot path / tx-semantic-required" exemption, not a missed
  conversion.
- **Refutation considered**: (1) *Is this a hot read that would stall behind the commit write lock,
  contradicting I-U5?* No — its three callers are a copy helper, an edge delegate, and JSON
  deserialization, none of which is the per-vertex-create or per-MATCH-step hot path the step targets.
  (2) *Should it have been converted?* No — converting it to the snapshot would change semantics (it
  intentionally returns the tx-local class), so leaving it lock-based and tx-aware is correct. The only
  gap is documentation. Confirmed not a bug.
- **Suggestion**: Optional. The conversions themselves are complete and correct. Consider a one-line note
  in the episode (or the D19 enumeration breadcrumb) recording that `EntityImpl.getSchemaClass()` stays
  lock-based and tx-aware on purpose (tx-local class semantics, cold callers), so a later reader auditing
  the I-U5 claim does not re-flag it.

## Evidence base

#### C1 — New null-deref surface on the converted reads (BC1)
- **Method**: Read `MetadataDefault.getImmutableSchemaSnapshot` (the `@Nullable` body), `SharedContext.getSchema`
  (returns `SchemaShared`), `MetadataDefault.getSchema` (returns the `SchemaProxy`), and both converted
  call sites; PSI to confirm the open-session invariant on both contexts.
- **Findings**: `getImmutableSchemaSnapshot()` returns null only when `metadata.schema` is null; both old
  read targets are non-null on an open session; both call sites run on an open session and deref directly.
- **Verdict**: REFUTED as a live bug; reported as a defensive suggestion only — unreachable on an open session.

#### C2 — `createVertexWithClass` snapshot-vs-shared split is sound (TOCTOU / idempotency)
- **Method**: Read `createVertexWithClass` (both old and new via the diff), `executeSchemaCode` (acquires a
  pooled open session), `SharedContext.getSchema` (committed `SchemaShared`, not the tx-aware proxy),
  `SchemaShared.getClass` (`acquireSchemaReadLock`), and `SchemaEmbedded.getOrCreateClass`.
- **Findings**: The old existence check read `getSharedContext().getSchema()` = the committed `SchemaShared`
  — already committed-only, not tx-aware — so the conversion to `getImmutableSchemaSnapshot()` does not
  change tx-visibility, only committed-live → committed-snapshot (lock-free). The create fallback re-reads
  `getSharedContext().getSchema()` and calls `getOrCreateClass`, which double-checks `classes.get(name)`
  under `acquireSchemaWriteLock` and returns the existing class if a concurrent create already added it, so
  a momentarily-stale snapshot that falls through to create resolves correctly. `isVertexType()` resolves
  the same committed class data on the snapshot as on the live shared schema; a class becoming a vertex
  type requires a committed superclass change, which invalidates the snapshot via the single trailing
  `forceSnapshot` (D8) under the same lock, so a reader sees either the consistent pre-commit snapshot or
  the rebuilt post-commit one — read-committed semantics, matching the old committed-live read.
- **Verdict**: CONFIRMED CORRECT — no TOCTOU, idempotency preserved by the write-lock recheck, no
  behavior change in the existence/vertex-type observable.

#### C3 — `getLowerSubclass` tx-visibility change is benign (consistent with the rest of MATCH/SQL) (review focus)
- **Method**: Read `getLowerSubclass` (old: `getMetadata().getSchema()` = tx-aware proxy; new:
  `getImmutableSchemaSnapshot()` = committed-only) and its sole production caller `addAliases`; PSI
  find-usages of `getImmutableSchemaSnapshot` across the MATCH/SQL planner and executor; read
  `MatchExecutionPlanner.resolveTargetClass` and `FetchFromClassExecutionStep.loadClassFromSchema`.
- **Findings**: The old path resolved through `SchemaProxy.getClass` → `resolve()`, which returns the
  tx-local schema when `getTxSchemaState() != null`, so it *could* see a class created in the same
  uncommitted tx. The new path is committed-only (`SchemaProxy.makeSnapshot()` comment: "the immutable
  snapshot is taken from the committed instance, not the tx-local copy"). This is a real behavior change,
  but benign: the rest of the MATCH/SQL pipeline already resolves classes via the committed snapshot —
  `MatchExecutionPlanner.resolveTargetClass` (line 2754), `lookupLinkedVertexClass`, the cost estimators,
  `FetchFromClassExecutionStep.loadClassFromSchema`, and `SQLFromItem.getSchemaClass` all call
  `getImmutableSchemaSnapshot()`. An in-tx-only class would already throw "Class … not found" at
  `loadClassFromSchema` / fetch, so a MATCH against it could never succeed regardless of `getLowerSubclass`.
  The change removes a pre-existing inconsistency (one method on the tx-aware proxy while the rest used the
  snapshot) rather than introducing one.
- **Verdict**: CONFIRMED CORRECT — the visibility change matches the surrounding executor; no reachable
  query depends on the old tx-aware resolution.

#### C4 — Read-site enumeration (I-U5) is sound; one by-design lock-based reader remains (BC2)
- **Method**: PSI find-usages of `SchemaProxy.getClass(String)` (23 production callers) and
  `EntityImpl.getSchemaClass()` (3 production callers); cross-check the hot per-record `EntityImpl` reads
  against the `getImmutableSchemaSnapshot` caller set.
- **Findings**: The 23 remaining lock-based `SchemaProxy.getClass` production callers are DDL statements
  (themselves schema-write paths), import/export/compare tools, security init, the scheduler, and
  `SchemaClassEmbedded.setName` (a write path) — none on the per-vertex-create or per-MATCH-step hot path.
  `EntityImpl.getSchemaClass()` is the one borderline reader still on the tx-aware proxy, but its three
  callers are cold (copy helper, edge delegate, JSON deserialization) and it returns the tx-local class by
  design; the hot per-record `EntityImpl` reads already route through the snapshot. The two named
  conversions are the only hot lock-based reads, so the I-U5 enumeration claim holds.
- **Verdict**: CONFIRMED — the two converted sites are the complete set of hot lock-based reads that would
  stall behind the commit write lock; `EntityImpl.getSchemaClass()` is a documented-exempt by-design case,
  not a missed conversion.
