<!--MANIFEST
dimension: test-behavior
track: 3
iteration: 1
commit_range: 8bbe3d2d18011f1ca6b1702a35e3c252ceba20b1..HEAD
verdict: pass-with-findings
evidence_base: "## Evidence base"
cert_index: [C1, C2, C3, C4]
flags: []
index:
  - id: TB1
    sev: should-fix
    anchor: "#tb1-deferred-handle-collectionstoindex-is-exercised-but-unasserted"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java:300 deferredIndexHandleReportsDefinitionAndZeroSize"
    cert: C1
    basis: "PSI: IndexAbstract.getCollections() returns collectionsToIndex; markDeferred populates it from findCollectionsByIds; no test reads it back."
  - id: TB2
    sev: should-fix
    anchor: "#tb2-bare-assertthrows-without-message-on-the-loud-write-path-resolver"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaProxyRoutingTest.java:190 reresolveClassImplThrowsWhenClassAbsentFromCopy"
    cert: C2
    basis: "assertThrows checks IllegalStateException type only; production message 'is not present in the transaction-local schema view' is load-bearing and unverified."
  - id: TB3
    sev: suggestion
    anchor: "#tb3-blocking-tests-do-not-pin-the-park-to-the-mutex-permit"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/MetadataWriteMutexTest.java:94 twoConcurrentSchemaTransactionsSerializeWithoutAbort"
    cert: C3
    basis: "awaitThreadParked observes WAITING/TIMED_WAITING but cannot attribute the park to the semaphore; secondCreatedClass==false is the load-bearing proof and already present."
  - id: TB4
    sev: suggestion
    anchor: "#tb4-membership-ripple-test-does-not-assert-the-tx-local-copys-membership-changed"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java:109 membershipRippleInTransactionLeavesSharedIndexUntouchedOnRollback"
    cert: C4
    basis: "Test asserts the shared Index is untouched (the leak) but not that the ripple was captured in the tx-local view; the sibling test membershipRippleRecordsChangedClassIntoTxLocalState covers the positive half."
-->

## Findings

### TB1 [should-fix] Deferred-handle `collectionsToIndex` is exercised but unasserted

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java`, method `deferredIndexHandleReportsDefinitionAndZeroSize` (line 300); same gap in `createIndexSqlInsideTransactionDoesNotNpeAndDefersToCommit` (line 267).
- **Issue**: `IndexManagerEmbedded.createIndex` on the in-transaction path calls `deferredHandle.markDeferred(new IndexMetadata(iName, indexDefinition, deferredCollections, ...))` where `deferredCollections = findCollectionsByIds(collectionIdsToIndex, session)` (diff `IndexManagerEmbedded.java`, in-tx create arm). `IndexAbstract.markDeferred` then sets `this.collectionsToIndex = deferredCollections != null ? new HashSet<>(deferredCollections) : new HashSet<>()` (diff `IndexAbstract.java`). The test asserts `getName()`, `getDefinition()`, `getDefinition().getClassName()`, `size(session) == 0`, and `existsIndex == false` — but never reads the handle's collection membership back. The `size() == 0` result comes from the `indexId < 0` early return in `IndexOneValue.size` / `IndexMultiValues.size` (diff), not from the collections, so it does not cover the `collectionsToIndex` write.
- **Evidence**: FALSIFIABILITY CHECK (cert C1). Mutation: change `markDeferred` to `this.collectionsToIndex = new HashSet<>()` unconditionally (drop the `deferredCollections` copy). Every assertion in both tests still passes — name/definition/className come from `IndexMetadata`, `size()` from the `indexId < 0` guard, `existsIndex` from the shared registry. The contract "the deferred handle answers collection queries sensibly" (the stated purpose of `markDeferred` per its Javadoc, and the reason `findCollectionsByIds` is called) is unverified. PSI confirms `IndexAbstract.getCollections()` returns `Collections.unmodifiableSet(collectionsToIndex)`, so the populated field is directly observable.
- **Missing behavior**: that the deferred handle reports the collections derived from the index definition's collection ids.
- **Suggested fix**:
  ```java
  // in deferredIndexHandleReportsDefinitionAndZeroSize, after the size() assertion:
  assertEquals(
      "the deferred handle must report the collections from its definition's collection ids",
      Set.copyOf(findExpectedCollectionNames(cls)), // or the concrete expected name set
      Set.copyOf(deferred.getCollections()));
  // At minimum, assert the membership is non-empty for an index over a class that owns collections:
  assertFalse("the deferred handle must carry its collection membership, not an empty set",
      deferred.getCollections().isEmpty());
  ```

### TB2 [should-fix] Bare `assertThrows` without message on the loud write-path resolver

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaProxyRoutingTest.java`, method `reresolveClassImplThrowsWhenClassAbsentFromCopy` (line 190).
- **Issue**: the test asserts only the exception *type*: `assertThrows(IllegalStateException.class, () -> SchemaProxedResource.reresolveClassImpl(copy, freshlyCommittedImpl))`. `reresolveClassImpl` throws `IllegalStateException` with the specific, load-bearing message `"Schema class '<name>' is not present in the transaction-local schema view; a shared schema impl cannot be linked into the transaction's private copy"` (diff `SchemaProxedResource.java`). `IllegalStateException` is a generic type thrown in several places on the schema path (e.g. `rebindToTxLocal`, `reresolvePropertyImpl`, the tx-local-state-absent guards), so type-only matching is the weak-exception pattern the dimension flags.
- **Evidence**: ASSERTION PRECISION CHECK (cert C2). The production value is a typed exception carrying a descriptive message that pins *why* it threw (class absent from the copy, refusing to link a shared impl). PRECISION: WEAK — would pass for any `IllegalStateException` raised anywhere in the call, including a future refactor that throws ISE for an unrelated reason before reaching the intended check. The sibling read-path test (`reresolveClassImplForReadReturnsNullWhenClassAbsentFromCopy`) correctly verifies the *value* (null) rather than just non-throwing, so the asymmetry is visible within the same file.
- **Missing behavior**: that the failure is the absent-from-copy refusal specifically, not an incidental ISE.
- **Suggested fix**:
  ```java
  var ex = assertThrows(
      "re-resolving a class missing from the copy must fail loudly",
      IllegalStateException.class,
      () -> SchemaProxedResource.reresolveClassImpl(copy, freshlyCommittedImpl));
  assertTrue(
      "the reject must name the absent class and the private-copy refusal: " + ex.getMessage(),
      ex.getMessage().contains("AbsentFromCopy")
          && ex.getMessage().contains("transaction-local schema view"));
  ```

### TB3 [suggestion] Blocking tests do not pin the park to the mutex permit

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/MetadataWriteMutexTest.java`, methods `twoConcurrentSchemaTransactionsSerializeWithoutAbort` (line 94) and `differentThreadParksUntilRelease` (line 296).
- **Issue**: `awaitThreadParked` proves the worker reached `WAITING`/`TIMED_WAITING`, which is a genuine improvement over a timing sleep (and the dimension's "prove blocking deterministically" bar is met by the `secondCreatedClass.get() == false` / `foreignEngaged.getCount() != 0` assertions, not by the thread-state poll). The thread-state observation itself, however, cannot attribute the park to the semaphore — a thread blocked on any monitor, any other lock, or an unrelated `await` also reports those states. The load-bearing proof is already the progress flag; the thread-state assertion adds confidence but is not itself falsifiable against "parked on the wrong thing."
- **Evidence**: FALSIFIABILITY CHECK (cert C3). Mutation: if `createClass` parked on some unrelated lock instead of the mutex (a hypothetical regression that moved blocking elsewhere), `awaitThreadParked` would still return a parked state and the thread-state assertion would still pass — only `secondCreatedClass.get() == false` catches it, and only because the park happens to precede the `set(true)`. The test is sound today because the progress flag carries the proof; the suggestion is to make that explicit so a future reader does not over-trust the thread-state poll.
- **Missing behavior**: a direct check that the holder is the mutex at the moment the worker is parked (the first writer / test thread should be observable as `isEngagedBy`).
- **Suggested fix**:
  ```java
  // while the second worker is parked, also assert the permit is genuinely held by the other party:
  // (twoConcurrent...) the first session is the holder — already proven by firstHoldsMutex; add:
  // (differentThreadParks...) the test thread holds it:
  assertTrue("the test thread must still hold the permit while the foreign worker parks",
      mutex.isEngagedBy(session));
  ```

### TB4 [suggestion] Membership-ripple rollback test does not assert the tx-local capture

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java`, method `membershipRippleInTransactionLeavesSharedIndexUntouchedOnRollback` (line 109).
- **Issue**: this test is the headline I-A7 leak test, and its core assertion is precise and falsifiable — PSI confirms `sharedIndex.getCollections()` is an unmodifiable view of `Index.collectionsToIndex`, the exact field an eager shared apply would corrupt, and the test pins it equal to `membershipBefore` both *during* the transaction (line 132) and *after rollback* (line 137). That half is strong. The gap: it verifies the leak did not happen but not that the ripple was *captured* (routed into the tx-local changed-class set rather than silently dropped). A regression that simply made `addCollectionToIndex` a no-op inside a transaction — neither leaking to the shared index nor recording into the tx-local view — would pass this test.
- **Evidence**: BEHAVIOR TRACE (cert C4). The de-guarded `recordMembershipChangeIntoTxLocalView` (diff `IndexManagerEmbedded.java`) both (a) skips the eager shared apply and (b) calls `txState.markClassChanged(changedClass)`. This test covers (a) only. The sibling `membershipRippleRecordsChangedClassIntoTxLocalState` (line 148) covers (b), so the combined coverage is complete — but only because two tests exist. Folding the positive capture assertion into the rollback test (or cross-referencing it) would make the single most important leak test self-contained against the no-op regression.
- **Missing behavior**: that the membership change was recorded into the tx-local changed-class set, not dropped.
- **Suggested fix**:
  ```java
  // before the rollback, after asserting the shared index is untouched:
  var state = session.getTxSchemaState();
  assertNotNull("the in-transaction ripple must have seeded the tx-local state", state);
  assertTrue("the ripple must be captured in the tx-local changed-class set, not dropped",
      state.getChangedClasses().contains("RippleSuper"));
  ```

## Evidence base

### Survived refutation (CONFIRMED-as-issue)

- **C1** (TB1): PSI on `IndexAbstract` shows `getCollections()` returns `Collections.unmodifiableSet(collectionsToIndex)` and `collectionsToIndex` is a `protected Set<String>` populated by `markDeferred`. `grep` on the four test files shows `getCollections()` is read only in `SchemaDeguardTest` lines 121/134/139 (the membership-ripple test), never on a deferred handle. Mutation (empty-set `markDeferred`) survives every deferred-handle assertion because `size()==0` derives from the `indexId < 0` guard. CONFIRMED.
- **C2** (TB2): the lambda calls `reresolveClassImpl(copy, freshlyCommittedImpl)` directly; the production method's only throw on that argument is the typed-and-messaged absent-from-copy ISE. `assertThrows(IllegalStateException.class, ...)` with no message check is type-only matching against a generic exception type used elsewhere on the schema path. The read-path sibling in the same file asserts the value, exposing the asymmetry. CONFIRMED (low impact: the call is direct today, so the only realistic ISE is the intended one — hence should-fix, not blocker).

### Refuted / down-graded claims (full reasoning retained)

- **C3** (TB3): initial candidate was "blocking proven only by thread state, which is not falsifiable." Refuted to suggestion. The load-bearing falsifiable proof is the progress flag (`secondCreatedClass.get() == false` while parked, `foreignEngaged.getCount() != 0` while held) plus the post-release completion, both of which fail closed if a regression lets the writer through. `awaitThreadParked` polls `Thread.State` rather than sleeping, so the prompt's "no timing sleep" bar is met. The residual weakness — thread state cannot attribute the park to the semaphore specifically — is real but the test catches the regression that matters (writer not blocked) via the progress flag. Down-graded to suggestion; the fix only hardens an already-sound test.
- **C4** (TB4): initial candidate was "the I-A7 rollback test is incomplete." Refuted to suggestion. The leak assertion is precise and falsifiable (PSI-confirmed `getCollections()` ⇒ `collectionsToIndex`; asserted during-tx and post-rollback against a captured `membershipBefore`). The only uncovered mutation (ripple becomes a silent no-op) is caught by the sibling `membershipRippleRecordsChangedClassIntoTxLocalState`, so combined track coverage is complete. Down-graded to suggestion: the recommendation is to make the headline test self-contained, not to fix a coverage hole.
- **Refuted entirely — cross-session isolation (NOT a finding)**: candidate "isolation tests only check the writer sees the change." Refuted. `concurrentSessionDoesNotSeeInTransactionCreate` (SchemaDeguardTest:173) opens a second real session and asserts `other.getMetadata().getSchema().existsClass("ConcurrentInvisible")` is `false` while the first session's create is uncommitted — a direct negative cross-session assertion. `readResolvesToCommittedWhenNoWriteViewSeeded` / `readResolvesToTxLocalCopyOnceWriteViewSeeded` (SchemaProxyRoutingTest) assert the tier-2 vs tier-3 resolution by inspecting `getImplementation().getOwner()` identity, which is falsifiable against a routing bug. No finding.
- **Refuted entirely — seed read-only-ness (NOT a finding)**: candidate "the copy tests are object-graph plumbing, not behavior." Refuted. `seedDoesNotEnrolCommittedRecordsIntoTheCallerTransaction` reads `getTransactionInternal().getEntryCount()` inside the same transaction (PSI: returns `recordOperations.size()`), pinning the I-side-effect property that a `toStream`-based seed would violate. `seedDoesNotRebindCommittedClassRecordIds` and `copyCarriesAnImmutableValueCopyOfTheCommittedRootIdentity` assert RID identity/value rather than mere non-null. These are precise behavior assertions. No finding.
- **Refuted entirely — exception precision elsewhere (NOT a finding)**: the two engage-order tests (`engageOrderAssertFiresWhenSchemaLockHeld`, `engageOrderAssertFiresWhenIndexManagerLockHeld`) and `sameThreadSecondSessionEngageThrows` all assert exception type *and* a message substring (`SchemaShared.lock`, `index-manager`, `different session`). Assertions are enabled in core (`core/pom.xml` `<argLine>-ea`), so the `AssertionError` paths are live. TB2 is the only bare-type `assertThrows` in the four files. No further finding.
