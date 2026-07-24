<!--
MANIFEST
dimension: bugs-concurrency
step: 3.2
iteration: 1
commit_range: b5ecae97066703380356aa37e3241218d6cb5b73~1..b5ecae97066703380356aa37e3241218d6cb5b73
verdict: pass-with-suggestions
blocker_count: 0
should_fix_count: 0
suggestion_count: 1
finding_count: 1
high_water_mark: 1
evidence_base: "## Evidence base"
cert_index: "#### C1"
flags: []
index:
  - id: BC1
    sev: suggestion
    anchor: "### BC1"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaClassProxy.java:503-517"
    cert: "#### C1"
    basis: psi
-->

## Findings

### BC1 [suggestion] `isSubClassOf`/`isSuperClassOf` reads can throw `IllegalStateException` on an absent argument class, where they previously returned `false`

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaClassProxy.java` (line 503-517)
- **Issue**: The two argument-taking read overloads now wrap the argument in `reresolveClassImpl(cls.getOwner(), …)` before the comparison:

  ```java
  public boolean isSubClassOf(SchemaClass iClass) {
    var cls = resolve();
    return cls.isSubClassOf(
        reresolveClassImpl(cls.getOwner(), ((SchemaClassInternal) iClass).getImplementation()));
  }
  ```

  `reresolveClassImpl` throws `IllegalStateException` when the argument class name is absent from the resolved schema (`SchemaProxedResource.java:1271-1278`). The underlying `SchemaClassImpl.isSubClassOf(SchemaClassImpl)` and `isSuperClassOf(SchemaClassImpl)` are total predicates: a `null` or non-matching argument returns `false` (verified by PSI). So the proxy now converts a previously-total read into one that can throw. Two argument shapes trigger it:
  - **Tier 3** (a schema write-view is seeded): an argument class that was dropped earlier in the same transaction, or that exists only in committed state, is absent from the tx-local copy → throw.
  - **Tier 2** (no write-view): a cross-schema/cross-database argument whose owner is not the committed instance → `committed.getClass(name)` is null → throw.

  This deviates from the seam's stated contract that reads route transparently (track-3 Step 2 episode: "sees it through every read path"). A read that used to answer `false` for an unrelated class now aborts the caller.
- **Evidence**: PSI confirms `SchemaClassImpl.isSubClassOf(SchemaClassImpl)` returns `false` for a `null`/non-equal argument and `isSuperClassOf(SchemaClassImpl)` short-circuits `clazz != null && …`. `reresolveClassImpl` (diff lines 1262-1278) throws unconditionally when `txLocalSchema.getClass(impl.getName())` is null. The other re-resolving call sites are all writes (`setSuperClasses`, `addSuperClass`, `removeSuperClass`, `createProperty`, `setLinkedClass`) where a loud failure on a missing class is the intended behaviour; `isSubClassOf`/`isSuperClassOf` are the only **reads** that re-resolve an argument.
- **Refutation considered**: I checked production reachability with PSI `ReferencesSearch`. `SchemaClassProxy.isSubClassOf(SchemaClass)` and `isSuperClassOf(SchemaClass)` have **zero** non-test callers in the indexed tree — production subclass checks go through `SchemaImmutableClass` (snapshot), `SchemaClassImpl` directly, or `YTDBSchemaClassImpl` (Gremlin), none of which route through this proxy overload. That is why this is a suggestion, not a should-fix: the regression is latent (no live caller exercises it today) but the code is wrong against the read-transparency contract, the loud-failure of `reresolveClassImpl` is design-intended only for the write paths, and there is no test covering an absent/foreign argument on these reads. A future caller (or a Gremlin/SQL path that starts using the proxy overload) would see an unexpected `IllegalStateException`.
- **Suggestion**: For these two reads, tolerate an absent argument rather than throwing — re-resolve defensively and fall back to `false`. For example, resolve the argument by name with a null-returning lookup and pass `null`/return `false` when it is absent, or add a read-only variant of the re-resolver that returns `null` instead of throwing. Add a tier-3 test that calls `isSubClassOf` with a class dropped earlier in the same transaction and asserts `false` rather than a throw.

## Evidence base

#### C1 — `isSubClassOf`/`isSuperClassOf` argument re-resolution on the read path (PSI-backed)
- `SchemaClassProxy.isSubClassOf(SchemaClass)` / `isSuperClassOf(SchemaClass)` use `resolve()` (read tier) but wrap the argument with `reresolveClassImpl`, which throws when the named class is absent (`SchemaProxedResource.java:1271-1278`, diff lines 1262-1278).
- PSI: `SchemaClassImpl.isSubClassOf(SchemaClassImpl)` returns `false` on `null`/non-equal; `isSuperClassOf(SchemaClassImpl)` is `clazz != null && clazz.isSubClassOf(this)`. Both are total — no throw.
- PSI `ReferencesSearch`: `SchemaClassProxy.isSubClassOf(SchemaClass)` totalRefs=0, prodRefs=0; `isSuperClassOf(SchemaClass)` totalRefs=0, prodRefs=0. Live subclass checks resolve to `SchemaImmutableClass.isSubClassOf(SchemaClass)` (callers: `SQLMatchPathItem.matchesClass`, `EntityImpl.validateEmbedded`, `SQLUpdateItem.convertToType`) and `SchemaClassImpl`, not the proxy overload. Severity held at suggestion on this reachability evidence.

#### Refuted candidates (survived the refutation check → not reported)

- **TxSchemaState surviving past its transaction (stale write-view leak).** CONFIRMED-not-a-bug. `TxSchemaState` is stashed in `FrontendTransactionImpl.userData` (a per-instance `HashMap`). PSI: `clear()` (run on commit/rollback) does not touch `userData`, so the state object persists on the completed tx object — but `getTxSchemaState()` gates on `tx.isActive()`, which returns false once `status` is `COMPLETED`/`ROLLED_BACK`, so a stale state is never observed. A new outer `begin()` builds a fresh `FrontendTransactionImpl` (`newTxInstance` → `new FrontendTransactionImpl(...)`) with a fresh empty `userData`; nested `begin()` reuses the same object (`beginInternal()` increments the counter), so the seed is correctly one-per-outermost-transaction. Test `writeViewIsTransactionScopedAndSeededOnce` pins this.
- **`getCustomData`/`setCustomData` throwing on the no-transaction path.** CONFIRMED-not-a-bug. `FrontendTransactionNoTx.getCustomData`/`setCustomData` throw `UnsupportedOperationException` and its `isActive()` returns `false`. Both seam entry points gate first: `getTxSchemaState()` returns `null` when `!tx.isActive()` before calling `getCustomData`; `resolveForWrite()` returns the captured `delegate` when `!getTransactionInternal().isActive()` before calling `ensureTxSchemaState()`. The NoTx custom-data methods are never reached.
- **NPE on `property.getOwnerClass().getOwner()` in `setLinkedClass`.** CONFIRMED-not-a-bug. PSI: `SchemaPropertyImpl.owner` is `final` and both constructors assign it (the second delegates to the first), so a constructed property's `getOwnerClass()` is non-null. `resolveForWrite()`'s `rebindToTxLocal` for a property throws if `owner == null` and otherwise returns a property fetched from a tx-local owner class, so the owner is non-null at the deref.
- **Cross-thread race on the seam / `TxSchemaState` / `userData`.** CONFIRMED-not-a-bug. PSI: every proxy method and the two session hooks run under `assert session.assertIfNotActive()`, which throws `SessionNotActivatedException` unless the calling thread owns the session's `activeSession` ThreadLocal. A `DatabaseSessionEmbedded` is thread-confined; the `userData` `HashMap`, the `TxSchemaState`, its `changedClasses` set, and the `currentTx` field are all accessed only by the owning thread. The TOCTOU window between `resolveForWrite()`'s `isActive()` check and `ensureTxSchemaState()`'s `assert tx.isActive()` is intra-thread, so no interleaving exists.
- **`SchemaProxy.load`/`reload`/`getVersion`/`toString` still dereference `delegate` (un-routed).** CONFIRMED-not-a-bug-for-this-step. PSI surfaced these as raw-`delegate` sites, but they are untouched by this diff: `makeSnapshot` is the intended tier-1 family; `load`/`reload` are deprecated storage reloads of the committed instance; `getVersion`/`toString` are diagnostic. The index-manager reads (`getIndexes`, `indexExists`, `getIndexDefinition`) deliberately bypass the seam because the tx-local index overlay is Track 5 (plan §Out of scope). No write-routing or read-isolation family is left un-routed by this step.
- **Accidental tx-local copy seeding on the data read path.** CONFIRMED-not-a-bug. `resolve()` only reads `getTxSchemaState()` and never seeds; only `resolveForWrite()` calls `ensureTxSchemaState()`. A pure data transaction that reads schema (e.g. `getCollectionForNewInstance`) never builds a tx-local copy.
