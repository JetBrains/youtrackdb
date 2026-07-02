<!--
MANIFEST
dimension: bugs-concurrency
step: 3
track: 3
iter: 1
commit_range: 99dd4c83e562c8d748f347bfedd3cecb5f7b14b6~1..99dd4c83e562c8d748f347bfedd3cecb5f7b14b6
evidence_base: PSI find-usages / type-hierarchy via mcp-steroid (project transactional-schema, aligned); full-file reads of IndexManagerEmbedded, SchemaEmbedded, SchemaShared, TxSchemaState, SchemaProxy/SchemaProxedResource, IndexAbstract, DDLStatement, SQLCreateIndexStatement.
cert_index: C1, C2, C3
flags: none
index:
  - id: BC1
    sev: should-fix
    anchor: "#bc1-should-fix-de-guarded-createindex-returns-an-engine-less-handle-that-sqlcreateindexstatementexecute-dereferences-via-idxsize"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java:367-381
    cert: C1
    basis: PSI-backed (callers of createIndex; DDLStatement.execute carries no tx guard; IndexAbstract single-arg ctor leaves im null)
  - id: BC2
    sev: suggestion
    anchor: "#bc2-suggestion-dropclassinternal-tx-local-branch-guards-its-only-write-with-a-java-assert-npe-if-assertions-are-off-and-the-invariant-breaks"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaEmbedded.java:457-466
    cert: C2
    basis: PSI-backed (only caller is doDropClass<-dropClass via proxy resolveForWrite which seeds; getTxSchemaState returns null when tx inactive)
  - id: BC3
    sev: suggestion
    anchor: "#bc3-suggestion-membership-ripple-records-nothing-when-the-index-definition-or-class-name-is-null-but-still-returns-true-and-skips-the-eager-apply"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java:185-206
    cert: C3
    basis: PSI-backed (getChangedClasses consumed only by tests today; Track 5 owns the overlay recompute)
-->

## Findings

### BC1 [should-fix] De-guarded `createIndex` returns an engine-less handle that `SQLCreateIndexStatement.execute` dereferences via `idx.size()`

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (line 367-381)
- **Issue**: The de-guarded in-transaction branch of `createIndex` returns `Indexes.createIndexInstance(type, algorithm, storage)`, which routes to the single-arg factory `IndexFactory.createIndex(indexType, storage)` → `new IndexNotUnique(storage)` / `new IndexUnique(storage)`. That constructor (`IndexAbstract(Storage)`) sets only `this.storage`; it leaves the `IndexMetadata im` field **null** and never loads an engine (no `indexId`). The previous code threw a clean `IllegalStateException("Cannot create a new index inside a transaction")` on this path. With the guard removed, a caller that obtains this handle and calls an engine-backed method gets an NPE instead of a clean rejection.
  The reachable caller is `SQLCreateIndexStatement.execute`: it assigns `idx = ...createIndex(...)` (the direct `keyTypes.length > 0` and `LUCENE_CROSS_CLASS` arms, and the `getoIndex` key-typed arm), then unconditionally runs `if (idx != null) return session.computeInTx(transaction -> idx.size(session));`. `IndexMultiValues.size()` / `IndexOneValue.size()` call `getName()` (`im.getName()` → NPE because `im == null`) and `storage.getIndexSize(indexId, ...)` against an unset `indexId`. So `CREATE INDEX` issued while a user transaction is open NPEs partway through, rather than failing cleanly or deferring to commit.
- **Evidence**: Trace — public API `session.command("CREATE INDEX …")` → `DDLStatement.execute` (PSI-confirmed: opens no transaction of its own and has **no** guard against an already-active user tx) → `DDLExecutionPlan.executeInternal` → `SQLCreateIndexStatement.execute` → `IndexManagerEmbedded.createIndex` (tx active → de-guarded branch → engine-less handle) → back in `execute`, `idx.size(session)` → `IndexAbstract.getName()` returns `im.getName()` with `im == null`. The `SchemaClassImpl.createIndex(...)` API overloads are `void` and discard the handle, so only the SQL path is exposed. `getoIndex`'s no-keyType arm re-fetches via `session.getIndex(indexName)`, which returns null on the de-guard path (index not registered), so that sub-arm is safe; the key-typed arm and the two top-level arms are not.
- **Refutation considered**: I checked whether DDL is forced top-level. `DDLStatement.execute` (both `Object[]` and `Map` overloads, PSI-dumped) builds a `BasicCommandContext` and calls `executionPlan.executeInternal` directly — no `begin`/`commit` wrapper and no "tx active" rejection anywhere in the DDL execution path. The historical throw-guard inside `createIndex` was the only thing rejecting in-tx DDL; removing it exposes the dereference. I also confirmed the engine-less state is real (single-arg `IndexAbstract` ctor leaves `im`/engine unset) rather than lazily initialized. The plan's contract ("a tx-created index is not query-usable until commit; planner skips unbuilt indexes", D13) covers later *queries*, not the `idx.size()` call inside the same statement that created it.
- **Suggestion**: On the de-guarded `createIndex` branch, do not hand back a handle that callers will dereference for engine state. Either (a) keep an explicit fail-closed for the SQL `CREATE INDEX`-in-active-tx case until Track 5's overlay makes the handle usable (a clean `CommandExecutionException`/`IllegalStateException` is strictly better than an NPE), or (b) make `SQLCreateIndexStatement.execute` skip the `idx.size()` probe when the index is deferred (e.g., gate on `session.getTxSchemaState() != null` or on the handle being unregistered). If the intent is genuinely "DDL inside an explicit user tx is unsupported in this intermediate state," restore a loud, message-bearing rejection on the SQL path rather than relying on a downstream NPE.

### BC2 [suggestion] `dropClassInternal` tx-local branch guards its only write with a Java `assert` (NPE if assertions are off and the invariant breaks)

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaEmbedded.java` (line 457-466)
- **Issue**: On the `txLocal` branch the code does `var txState = session.getTxSchemaState(); assert txState != null : …; txState.markClassChanged(className);`. `getTxSchemaState()` is the read-only probe that returns `null` when the transaction is not active (or no schema write has seeded yet). If `txLocal == true` but the probe returns null, the `assert` catches it only with `-ea`; in a production JVM (assertions disabled) execution falls straight into `txState.markClassChanged(...)` and NPEs. For this step's reachable path the invariant holds — `dropClass`/`dropClassInternal` on a `txLocal` instance is reached only through `SchemaProxy.dropClass → resolveForWrite()`, which seeds the state via `ensureTxSchemaState()` before delegating (PSI-confirmed: `doDropClass` is the sole caller of `dropClassInternal`, and `dropClass` is reached only through the proxy seam) — so this is not a live bug today. It is a fragile coupling: the `txLocal` flag lives on the copy instance while the state lives in the transaction's custom data, and Track 4's commit-time promotion will operate on a `txLocal` copy while the transaction may no longer be "active" by the probe's definition.
- **Evidence**: `getTxSchemaState()` body (PSI-dumped): `if (!tx.isActive()) return null;`. The `txLocal` branch is the only write in `dropClassInternal` that records the dropped class into the changed-class set — the hook the Track 4 commit consumes — so a silent skip (NPE) here would also lose the drop from the commit delta, not just crash.
- **Refutation considered**: Confirmed the seed precedes the drop on every Track-3 reachable path (proxy `resolveForWrite` seeds; `copyForTx` asserts an active tx). So under `-ea` test runs the assert documents the invariant correctly and never fires. The concern is purely the production-JVM degradation mode and the cross-track fragility, hence suggestion, not should-fix.
- **Suggestion**: Promote the guard to a real check that fails loudly in production too — e.g., `Objects.requireNonNull(session.getTxSchemaState(), "a tx-local drop must run with a seeded tx-local schema state")`, or fetch the state the same way the mutation path does (so the copy and its state cannot desync). Keeping the `assert` as documentation is fine, but the single write should not depend on assertions being enabled.

### BC3 [suggestion] Membership ripple records nothing when the index definition or class name is null, but still returns `true` and skips the eager apply

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (line 185-206)
- **Issue**: `recordMembershipChangeIntoTxLocalView` computes `changedClass` from `index.getDefinition().getClassName()`, and when `index == null`, `getDefinition() == null`, or `getClassName() == null` it records nothing but still returns `true`, so `addCollectionToIndex` / `removeCollectionFromIndex` skip the eager shared apply and record no changed class. For the in-tx overlay model this is intentional (the eager shared apply must never run mid-tx, and Track 5 recomputes membership from the changed-class set), so it is not a correctness bug in this step. The risk is forward-looking: a membership ripple against an index whose owning class is not captured produces no entry in the changed-class set, and the Track 4/5 commit reads exactly that set to reconcile. If a real (non-null-definition) index ever reaches this with a transiently-null class name, the change would be silently dropped from the commit delta with no trace.
- **Evidence**: PSI find-usages confirms `TxSchemaState.getChangedClasses()` has **no production consumer yet** (only `CopyForTxTest` and `SchemaDeguardTest` read it); the commit-time consumer is Track 4. So today the only observable effect is the test assertions, which pass because the test indexes carry non-null class names. The early-return `if (!isActive()) return false;` correctly preserves the legacy path; the concern is only the `changedClass == null && return true` combination.
- **Refutation considered**: For a property/class index created through the normal API the definition always carries a class name (verified the `createIndex` flow rejects manual/class-less index definitions earlier via `manualIndexesAreUsed`). So the null-class case is not reachable for the indexes this step's tests exercise. Hence suggestion.
- **Suggestion**: When `changedClass == null` on the in-tx path, either log a diagnostic (this should be unreachable for a real class index) or assert it, so a future regression that produces a class-less membership ripple surfaces in tests instead of silently vanishing from the commit delta.

## Evidence base

#### C1 — engine-less handle dereferenced by `idx.size()` (CONFIRMED-as-issue)
Survived refutation. PSI: `IndexAbstract(Storage)` single-arg ctor sets only `storage`, leaves `im`/engine unset; `DefaultIndexFactory.createIndex(indexType, storage)` returns `new IndexNotUnique/IndexUnique(storage)`; `DDLStatement.execute` opens no tx and has no active-tx guard; `SQLCreateIndexStatement.execute` calls `idx.size()` after `createIndex`; `IndexMultiValues.size()`/`IndexOneValue.size()` deref `getName()` (`im.getName()`) and `indexId`. Reachable from public `session.command("CREATE INDEX …")` inside an open user tx, where the code previously threw cleanly.

#### C2 — `dropClassInternal` assert-guarded single write
Refutation outcome: NOT a live bug on Track-3 reachable paths (proxy `resolveForWrite` seeds the txState before `dropClass`; `doDropClass` is the sole caller of `dropClassInternal`, PSI-confirmed; `dropClass` reached only through the proxy seam). Reported as suggestion because (a) `getTxSchemaState()` returns null when the tx is inactive, so the `assert`-only guard degrades to an NPE in a production (`-ea`-off) JVM if the invariant ever breaks, and (b) the `txLocal` flag (on the copy) and the state (in tx custom data) are separately sourced, a coupling Track 4 promotion will stress. The single write here also feeds the Track 4 commit delta, so a silent skip loses the drop, not just crashes.

#### C3 — null-class membership ripple records nothing but returns true
Refutation outcome: NOT reachable for the property/class indexes this step exercises (a real class index always carries a non-null class name; the `createIndex` flow rejects class-less definitions up front). `TxSchemaState.getChangedClasses()` has no production consumer yet (PSI: only tests), so the only effect today is in tests, which pass. Reported as suggestion for forward-safety once Track 4/5 consume the set.
