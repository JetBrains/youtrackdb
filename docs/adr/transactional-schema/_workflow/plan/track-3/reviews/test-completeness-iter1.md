<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 4, suggestion: 2}
index:
  - {id: TC1, sev: should-fix, loc: SchemaDeguardTest.java:196, anchor: "### TC1 ", cert: C1, basis: "in-tx createIndex with a duplicate name has no guard and silently mints a 2nd deferred handle; legacy path throws — divergence untested"}
  - {id: TC2, sev: should-fix, loc: SchemaDeguardTest.java:229, anchor: "### TC2 ", cert: C2, basis: "in-tx dropIndex on an unknown index silently no-ops; legacy path throws — divergence + silent-drop untested"}
  - {id: TC3, sev: should-fix, loc: SchemaProxyRoutingTest.java:162, anchor: "### TC3 ", cert: C3, basis: "addSuperClass linking a parent created in the SAME tx (rebind already-tx-local branch) never exercised end-to-end; the dangerous polymorphic-membership path"}
  - {id: TC4, sev: should-fix, loc: SchemaProxyRoutingTest.java:189, anchor: "### TC4 ", cert: C4, basis: "drop-then-reference within a tx: rebindToTxLocal dropped-earlier throw branch (Step-2 cross-track contract) has no test"}
  - {id: TC5, sev: suggestion, loc: SchemaProxyRoutingTest.java:280, anchor: "### TC5 ", cert: C5, basis: "reresolvePropertyImpl has zero production callers (PSI); its owner-null / property-absent throw branches are exercised only as static-helper inputs"}
  - {id: TC6, sev: suggestion, loc: CopyForTxTest.java:30, anchor: "### TC6 ", cert: C6, basis: "copyForTx seed never tested on an empty schema (zero user classes) — the minimal-collection boundary"}
evidence_base: {section: "## Evidence base", certs: 6, matches: 6}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6, verdict: CONFIRMED, anchor: "#### C6 "}
flags: [CONTRACT_OK]
-->

## Findings

### TC1 [should-fix] In-tx createIndex with a duplicate name is unguarded and untested

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java`
**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (lines 389-411 vs 415-459)

**Missing scenario**: Creating an index inside a transaction whose name already exists in the shared index registry (or was already created earlier in the same transaction).

**Why it matters**: The legacy top-level path checks `indexes.containsKey(iName)` and throws `IndexException("Index with name ... already exists")` (line 420-423). The de-guarded in-tx path at lines 389-411 has **no duplicate-name check at all** — it unconditionally calls `Indexes.createIndexInstance` and `markDeferred`, returning a fresh deferred handle. So a transaction that re-creates an existing index name gets a silent second handle with no error, where the same operation outside a transaction fails loudly. That divergence either masks a user error until commit (where Track 4/5 must then detect the collision, with no test pinning the expected behavior) or, worse, lets two deferred definitions for one name race to commit. The existing tests (`createIndexInsideTransactionDefersToCommitAndDoesNotThrow`, `deferredIndexHandleReportsDefinitionAndZeroSize`) only ever create a *new, unique* index name, so this boundary is entirely uncovered.

**Evidence**: Input-domain row `createIndex(iName, ...)` / boundary "iName already registered": tested YES on the legacy path (existing index-manager tests), NO on the de-guarded tx path. The branch at line 389 has no analogue of the line-420 collision check.

**Refutation considered**: Could caller validation make the duplicate unreachable? No — `SchemaClassProxy.createIndex` → `resolveForWrite().createIndex(...)` → `IndexManagerEmbedded.createIndex` reaches this branch directly for any user-supplied name; the SQL `CREATE INDEX` path does too. Could the deferred-overlay (Track 5) be relied on to dedupe? The Track-3 contract is "enablement half only," and the throw is a Track-3-visible behavioral difference that should be pinned now (either as the intended deferred-detect-at-commit behavior or as a Track-3 loud reject). At minimum the current silent acceptance must be asserted so a Track-4/5 change to it is a deliberate, test-visible decision.

**Suggested test**:
```java
@Test
public void createIndexWithDuplicateNameInsideTransactionBehavesPredictably() {
  var schema = session.getMetadata().getSchema();
  var cls = schema.createClass("DupIdx");
  cls.createProperty("name", PropertyType.STRING);
  var indexName = "DupIdx.name";
  cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name"); // committed, top-level

  session.begin();
  // Re-create the same index name inside a tx. Pin whatever the de-guard should do:
  // either it rejects (mirroring the legacy duplicate throw) or it records once and
  // defers collision detection to commit. Today it silently mints a 2nd deferred handle —
  // assert the chosen contract so a later track cannot change it unnoticed.
  // e.g. assertThrows(IndexException.class, () -> cls.createIndex(indexName, ...));
  session.rollback();
}
```

### TC2 [should-fix] In-tx dropIndex on a nonexistent index silently no-ops, untested

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java`
**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (lines 548-569)

**Missing scenario**: Dropping an index by a name that does not exist (typo, or already dropped earlier in the same transaction) while a transaction is active.

**Why it matters**: The de-guarded `dropIndex` tx branch (line 557-568) seeds the tx-local state, then only marks a changed class when `idx != null && idx.getDefinition() != null && idx.getDefinition().getClassName() != null` (line 561). When the index name is unknown, the branch falls through and `return`s — a **silent no-op**, having already seeded the tx-local state and engaged the mutex. The legacy path (line 571+) loads the index inside `executeInTxInternal` and behaves differently for an unknown name. Two untested consequences: (1) a `dropIndex` of a bogus name inside a tx silently engages the metadata-write mutex and seeds a tx-local copy for a no-op write, and (2) the behavioral divergence from the non-tx path (loud vs silent) is unpinned. The existing `dropIndexInsideTransactionDefersToCommitAndDoesNotThrow` only drops an index that *does* exist.

**Evidence**: Input-domain row `dropIndex(iIndexName)` / boundary "iIndexName absent": tested NO on both the "no changed-class recorded" sub-case and the "mutex engaged for a no-op" sub-case. The `idx != null` guard at line 561 is the untested branch.

**Refutation considered**: Is the boundary reachable? Yes — `SchemaProxy`/SQL callers pass user-supplied names straight through. Is the no-op harmless? Engaging the mutex and seeding a full `SchemaShared` copy for a name-typo drop is wasteful but not corrupting; still, the divergence from the legacy throw is exactly the kind of silent contract change a completeness test should freeze. Confirmed as a meaningful gap, should-fix rather than blocker because no data corruption results.

**Suggested test**:
```java
@Test
public void dropUnknownIndexInsideTransactionDoesNotThrowAndRecordsNoChangedClass() {
  session.begin();
  var indexManager = session.getSharedContext().getIndexManager();
  indexManager.dropIndex(session, "DoesNotExist.nope"); // must not throw, must no-op
  var state = session.getTxSchemaState();
  // It still seeds + engages today; assert the chosen contract explicitly:
  assertNotNull("a dropIndex seeds the tx-local state even for an unknown name", state);
  assertTrue("an unknown-index drop records no changed class",
      state.getChangedClasses().isEmpty());
  session.rollback();
}
```

### TC3 [should-fix] Linking a same-tx-created superclass (rebind already-tx-local branch) not exercised end-to-end

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaProxyRoutingTest.java`
**Production code**: `SchemaProxedResource.java` (reresolveClassImpl, lines 1979-1980 of the diff: `if (impl.getOwner() == txLocalSchema) return impl;`) and `SchemaClassProxy.addSuperClass` / `setSuperClasses` (diff lines 1019-1036, 1001-1016)

**Missing scenario**: Inside one transaction, create class `Parent`, then `Child.addSuperClass(Parent)` (or `createClass("Child", parent)`) where `parent` is the proxy returned by the *same* transaction's create. The superclass impl handed to `reresolveClassImpl` is then already owned by the tx-local copy, so the "already a tx-local object" short-circuit fires instead of the by-name lookup.

**Why it matters**: This is the central D8 isolation invariant ("no shared impl entering the tx-local graph") under its hardest input — an impl argument that is *already* tx-local. The acceptance line "impl-typed arguments re-resolved by name before linking (no shared impl entering the tx-local graph)" is only tested via the static helper `reresolveClassImplMapsCommittedClassIntoTheCopy`, which feeds it a *committed* impl and the "already owned by copy" line as a unit input (`reresolveClassImpl(copy, copyImpl)`). No test drives a real `addSuperClass`/`createClass(name, super)` write through the proxy where the linked superclass is a same-tx-created class. If the short-circuit or the proxy's `resolveForWrite()`-then-`reresolveClassImpl(cls.getOwner(), ...)` wiring regressed (e.g. resolved against the wrong owner, or rebuilt the parent by stale committed name), a same-tx inheritance chain would silently link a shared or wrong-owner parent — exactly the leak D8 forbids — and every current test would still pass. This is also the `addSuperClass`-half of the track's "polymorphic membership ripple" acceptance line, distinct from the create-subclass-of-committed-indexed-super case `membershipRippleInTransactionLeavesSharedIndexUntouchedOnRollback` already covers.

**Evidence**: Input-domain row `reresolveClassImpl(owner, impl)` / boundary "impl.getOwner() == txLocalSchema (same-tx-created)": tested only as a direct static call (`SchemaProxyRoutingTest:177-178`), NO via any proxy write path. The de-guard tests create subclasses of *committed* superclasses only.

**Refutation considered**: Is it covered indirectly by `membershipRipple...`? No — that test creates `RippleSub` as a subclass of `RippleSuper`, where `RippleSuper` is committed (top-level) before the tx, so the linked superclass impl is resolved by *name* (the lookup branch), not the same-tx short-circuit. The same-tx-created-parent case takes the other branch and binds the object-graph edge between two private copies; it is genuinely uncovered and is the more error-prone path.

**Suggested test**:
```java
@Test
public void addSuperClassToSameTxCreatedParentLinksWithinTheCopy() {
  session.begin();
  var schema = (SchemaInternal) session.getMetadata().getSchema();
  var parent = schema.createClass("SameTxParent");          // tx-local copy
  var child  = schema.createClass("SameTxChild");           // tx-local copy
  child.addSuperClass(parent);                              // exercises the already-tx-local branch
  var resolvedChild = (SchemaClassInternal) schema.getClass("SameTxChild");
  var resolvedParent = (SchemaClassInternal) schema.getClass("SameTxParent");
  // Superclass edge must bind to the COPY's parent object, never a shared/committed one.
  assertSame(resolvedParent.getImplementation(),
      resolvedChild.getImplementation().getSuperClasses().get(0));
  assertSame("the linked parent must be owned by the tx-local copy",
      session.getTxSchemaState().getTxLocalSchema(),
      resolvedChild.getImplementation().getSuperClasses().get(0).getOwner());
  session.rollback();
}
```

### TC4 [should-fix] Drop-then-reference within a tx (rebind dropped-earlier throw branch) untested

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaProxyRoutingTest.java`
**Production code**: `SchemaClassProxy.rebindToTxLocal` (diff lines 714-727) and `SchemaPropertyProxy.rebindToTxLocal` (diff lines 1474-1500)

**Missing scenario**: Inside a transaction, capture a class proxy (created in or before the tx), drop that class in the same tx, then call any read/write method on the still-held proxy. `rebindToTxLocal` then finds the class absent from the tx-local copy and throws `IllegalStateException("... it may have been dropped earlier in this transaction")`.

**Why it matters**: Step 2's recorded cross-track contract (track file, Episodes §Step 2 "Critical context") states the write-path `reresolve*` / rebind helpers "throw `IllegalStateException` on a missing class/property in the copy, so Step 3 / Track 4 drop-then-reference tests should expect that loud failure." This is a deliberately designed loud-failure boundary that Track 4 builds on, yet no Track-3 test exercises a proxy whose class/property was dropped earlier in the same transaction. `SchemaProxyRoutingTest.reresolveClassImplThrowsWhenClassAbsentFromCopy` tests absence by creating a class in committed *after* the copy was built — a different shape (never-in-copy), not the drop-then-reference shape. The `rebindToTxLocal` throw on `SchemaClassProxy`/`SchemaPropertyProxy` (the instance method the proxies actually route through, distinct from the static `reresolveClassImpl`) has zero coverage.

**Evidence**: Input-domain row `rebindToTxLocal(txLocalSchema)` / boundary "delegate's class dropped earlier in this tx": tested NO. The throw at diff lines 721-725 (class) and 1493-1498 (property) is uncovered; the analogous static-helper throw is covered but is not the same method.

**Refutation considered**: Is it the same code as `reresolveClassImplThrowsWhenClassAbsentFromCopy`? No — that test calls the *static* `reresolveClassImpl`; the drop-then-reference path goes through the *instance* `rebindToTxLocal` reached from `resolve()`/`resolveForWrite()`. Different method, different call site, and the contract Step 2 explicitly flags for a test. Confirmed gap.

**Suggested test**:
```java
@Test
public void usingAProxyAfterItsClassWasDroppedInTheSameTxFailsLoudly() {
  session.getMetadata().getSchema().createClass("DropThenUse");
  session.begin();
  var schema = session.getMetadata().getSchema();
  var proxy = schema.getClass("DropThenUse");   // captured proxy, routes via rebindToTxLocal
  schema.dropClass("DropThenUse");              // removed from the tx-local copy
  assertThrows("a read through a proxy for a class dropped earlier in the tx must fail loudly",
      IllegalStateException.class, proxy::getName);
  session.rollback();
}
```

### TC5 [suggestion] reresolvePropertyImpl has no production caller; its throw branches are tested only as static inputs

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaProxyRoutingTest.java`
**Production code**: `SchemaProxedResource.reresolvePropertyImpl` (diff lines 2021-2048)

**Missing scenario**: A production write path that passes a `SchemaPropertyImpl` argument through `reresolvePropertyImpl`, exercising its owner-null and property-absent throw branches in situ.

**Why it matters**: PSI find-usages shows `reresolvePropertyImpl` has **zero production callers** — its only references are `SchemaProxyRoutingTest.reresolvePropertyImplMapsCommittedPropertyIntoTheCopy` and the Javadoc `{@link}` in the same file. Every proxy write that links an impl (`setLinkedClass`, `addSuperClass`, `createProperty(..., linkedClass)`) re-resolves a *class* impl via `reresolveClassImpl`, never a property impl. So the method's two `IllegalStateException` branches (owner class null; property absent from copy) and the "already tx-local" short-circuit are exercised purely as direct static-method inputs — useful, but they validate a helper that nothing in the track actually calls. Either a write path should route a property argument through it (then the test should drive that path), or the method is currently dead weight and the test is testing scaffolding rather than behavior. Worth surfacing so Track 5 (which the helper is presumably reserved for) wires it to a real caller with a real-path test.

**Evidence**: PSI `Callers of SchemaProxedResource#reresolvePropertyImpl` → only `SchemaProxyRoutingTest` + the internal `{@link}`. No production write path. Contrast `reresolveClassImpl`, which is called from `SchemaClassProxy`, `SchemaPropertyProxy`, and `SchemaProxy` write methods.

**Refutation considered**: Is it reachable through a yet-unseen overload? PSI is authoritative here (IDE reachable, project aligned) and reports no production caller. Low severity because this is a coverage-of-scaffolding observation, not a behavioral gap in a live path — the live path (`reresolveClassImpl`) is well covered.

### TC6 [suggestion] copyForTx seed never tested against an empty (zero-user-class) schema

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/CopyForTxTest.java`
**Production code**: `SchemaShared.copyForTx` (diff lines 2479-2512), specifically the `fromStream` re-parse over the `"classes"` link set.

**Missing scenario**: Seeding the tx-local copy when the committed schema carries only the bootstrap internal classes and no user classes — the minimal-collection boundary for the re-parse loop.

**Why it matters**: Every `CopyForTxTest` creates at least one user class before calling `copyForTx` (`Isolated`, `Parent`/`Child`, `Untouched`/`AlsoUntouched`, `Stable`, `WithProp`). The re-parse iterates the `"classes"` link set and rebinds per-class RIDs; the zero-user-class case (the state right after database bootstrap, before any DDL) exercises the loop's empty/near-empty boundary and the `assert committedRoot.getProperty("globalProperties") != null` guard with the smallest possible class graph. A re-parse that mishandled an empty or internal-only class set (off-by-one, an NPE on an empty link set, or a derived-state recompute that assumes at least one user class) would be invisible to the current suite. This is the standard "empty collection" boundary applied to the seed's primary input.

**Evidence**: Input-domain row `copyForTx` / committed-schema state "no user classes (bootstrap only)": tested NO. All seven existing tests pre-create user classes, so the minimal-graph re-parse path is never hit.

**Refutation considered**: Is the empty case unreachable? No — the first schema write in a freshly bootstrapped database (before any user class exists) seeds the copy over exactly the internal-class-only graph. Is it trivially correct? The re-parse + derived-state recompute is non-trivial code; an empty user-class set is a legitimate boundary worth one cheap assertion. Low severity because the internal bootstrap classes mean the graph is never literally empty, so a true zero-element NPE is unlikely — but the "smallest real input" assertion still has value.

**Suggested test**:
```java
@Test
public void copyForTxSeedsCleanlyOnABootstrapSchemaWithNoUserClasses() {
  // No user createClass before the copy — only the bootstrap internal classes exist.
  var committed = committedSchema();
  var copy = session.computeInTx(tx -> committed.copyForTx(session));
  assertNotSame(committed, copy);
  assertEquals("the copy carries the committed root identity even with no user classes",
      committed.getIdentity(), copy.getIdentity());
  // Every committed class (internal-only here) must round-trip with its owner rebound to the copy.
  for (var c : committed.getClasses(session)) {
    var copied = copy.getClass(c.getName());
    assertNotNull("internal class " + c.getName() + " must survive the empty-user-schema re-parse",
        copied);
    assertSame(copy, copied.getOwner());
  }
}
```

## Evidence base

#### C1 — createIndex in-tx duplicate-name divergence — CONFIRMED
`IndexManagerEmbedded.createIndex` legacy branch checks `indexes.containsKey(iName)` and throws (lines 420-423); the de-guarded tx branch (lines 389-411) has no such check and unconditionally `markDeferred`s a new handle. PSI confirms `markDeferred` is overridden only in `IndexAbstract` and called only from `IndexManagerEmbedded.createIndex`, so the deferred handle creation is the single reachable site. `SchemaDeguardTest` create-index tests use only fresh unique names. Survived refutation: the boundary is reachable from both proxy and SQL callers; the silent-vs-loud divergence is a Track-3-visible contract that no test pins.

#### C2 — dropIndex in-tx unknown-name silent no-op — CONFIRMED
`IndexManagerEmbedded.dropIndex` tx branch (lines 550-568) seeds + engages, then guards `markClassChanged` on `idx != null && getDefinition() != null && getClassName() != null` and returns; an unknown name records nothing yet still engaged the mutex and built a copy. Legacy path (571+) loads the index and differs. `dropIndexInsideTransactionDefersToCommitAndDoesNotThrow` drops only an existing index. Survived refutation: reachable via user-supplied names; the divergence from the legacy throw and the wasted engage/seed are both unpinned.

#### C3 — same-tx-created superclass link branch — CONFIRMED
`reresolveClassImpl` short-circuits when `impl.getOwner() == txLocalSchema` (diff 1979-1980); proxy `addSuperClass`/`setSuperClasses`/`createClass(name, super)` route through it after `resolveForWrite()`. `membershipRippleInTransactionLeavesSharedIndexUntouchedOnRollback` links a *committed* superclass (by-name branch), and `reresolveClassImplMapsCommittedClassIntoTheCopy` only calls the static helper directly with `copyImpl`. No proxy write links a same-tx-created parent. Survived refutation: the membership-ripple test takes the other branch; this is the more error-prone same-tx object-graph edge and is the `addSuperClass` half of the polymorphic-ripple acceptance line.

#### C4 — drop-then-reference rebind throw — CONFIRMED
`SchemaClassProxy.rebindToTxLocal` (diff 714-727) and `SchemaPropertyProxy.rebindToTxLocal` (diff 1474-1500) throw when the delegate's class/property is absent from the copy; track-file Episodes §Step 2 Critical context explicitly designates drop-then-reference as an expected loud failure for Step 3 / Track 4. `reresolveClassImplThrowsWhenClassAbsentFromCopy` tests the *static* helper with a never-in-copy class, not the instance `rebindToTxLocal` after an in-tx drop. Survived refutation: different method and call site from the covered static-helper throw; explicitly flagged contract with no test.

#### C5 — reresolvePropertyImpl dead in production — CONFIRMED
PSI find-usages: `reresolvePropertyImpl` callers = `SchemaProxyRoutingTest.reresolvePropertyImplMapsCommittedPropertyIntoTheCopy` plus the internal `{@link}` Javadoc reference only; zero production callers. Production property-linking writes (`setLinkedClass`) re-resolve a *class* impl via `reresolveClassImpl`. Survived refutation: PSI authoritative (IDE reachable, project aligned). Suggestion severity — coverage of scaffolding, not a live-path behavioral gap.

#### C6 — copyForTx empty-user-schema boundary — CONFIRMED
All seven `CopyForTxTest` methods pre-create user classes before `copyForTx`; the bootstrap-only (no-user-class) re-parse boundary of the `"classes"` link-set loop and derived-state recompute is never hit. Survived refutation: the empty case is the first-DDL-after-bootstrap reality; non-trivial re-parse code; cheap to assert. Suggestion severity — bootstrap internal classes keep the graph non-literally-empty, so a hard NPE is unlikely, but the smallest-real-input assertion has value.
