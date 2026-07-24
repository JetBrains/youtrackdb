<!--MANIFEST
dimension: test-structure
iteration: 1
review_target: 8bbe3d2d18011f1ca6b1702a35e3c252ceba20b1..HEAD
scope: Track 3 in-scope Java production + test files (20 files); 4 new test classes
high_water_mark: TS3
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - id: TS1
    sev: suggestion
    anchor: "#ts1-suggestion-misnamed-shared-error-holder-in-twoconcurrentschematransactionsserializewithoutabort"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/MetadataWriteMutexTest.java:2757,2773"
    cert: n/a
    basis: "Read of MetadataWriteMutexTest twoConcurrent... method; both worker catch blocks write into secondError."
  - id: TS2
    sev: suggestion
    anchor: "#ts2-suggestion-engageorderassertfireswhenindexmanagerlockheld-bundles-two-behaviours-in-one-method"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/MetadataWriteMutexTest.java:3046"
    cert: n/a
    basis: "Read of the method: an assert-fires scenario then a separate well-ordered sanity scenario in the same @Test."
  - id: TS3
    sev: suggestion
    anchor: "#ts3-suggestion-test-reaches-into-protected-resolve-resolveforwrite-internals"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaProxyRoutingTest.java:3797,3802"
    cert: n/a
    basis: "Read of proxyWriteSeamSeedsAndResolvesIntoTheCopy; calls protected resolve()/resolveForWrite() via same-package access."
-->

## Findings

### TS1 [suggestion] Misnamed shared error holder in twoConcurrentSchemaTransactionsSerializeWithoutAbort

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/MetadataWriteMutexTest.java`, method `twoConcurrentSchemaTransactionsSerializeWithoutAbort` (diff lines 2757, 2773, 2799)
- **Issue**: The `AtomicReference<Throwable> secondError` is declared as "the second writer's error", but the **first** writer thread (`mutex-first-writer`) also routes its caught throwable into the same reference: `secondError.compareAndSet(null, t)` at line 2773. The second writer's outer catch does the same at line 2799. A failure on the first thread therefore surfaces under a name and assertion message (`"no schema tx should error on contention"`) that does not say which session failed, and if both threads throw, only the first-set wins — the test cannot distinguish first-writer from second-writer failures in the diagnostic.
- **Suggestion**: Use a single neutrally-named holder such as `workerError` (since the assertion already treats any worker failure as a test failure), or give each thread its own reference (`firstError` / `secondError`) and assert both. This is a documentation/clarity nit, not a correctness defect — the test still fails if either thread throws.

### TS2 [suggestion] engageOrderAssertFiresWhenIndexManagerLockHeld bundles two behaviours in one method

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/MetadataWriteMutexTest.java`, method `engageOrderAssertFiresWhenIndexManagerLockHeld` (diff line 3046)
- **Issue**: The method tests two distinct behaviours: (1) the engage-order assertion fires when the index-manager write lock is held, then (2) a trailing "Sanity" block (diff lines 3074-3085) opens a second transaction and asserts that a well-ordered engage seeds the state, engages the mutex, and releases it on outermost-frame close. The second block is a different scenario (the happy path plus the release-on-close property) sharing the method name, so a failure in the sanity block reports under a name that describes the assertion-fires case. The release-on-close property is also already exercised indirectly by `writeViewIsTransactionScopedAndSeededOnce` (proxy test) and the explicit close path.
- **Suggestion**: Split the sanity block into its own `@Test` (e.g. `wellOrderedEngageSeedsStateAndReleasesOnClose`) so each method documents one scenario and a failure points at the right behaviour. Lower priority than TS1 — both halves are individually clear and well-commented.

### TS3 [suggestion] Test reaches into protected resolve / resolveForWrite internals

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaProxyRoutingTest.java`, method `proxyWriteSeamSeedsAndResolvesIntoTheCopy` (diff lines 3797, 3802)
- **Issue**: The test calls `proxy.resolve()` and `proxy.resolveForWrite()` directly. These are `protected final` on `SchemaProxedResource`; the call compiles only because the test lives in the same package (`...metadata.schema`). The test is therefore coupled to a protected routing helper rather than to a public proxy operation, so the test would not catch a regression where a public DDL method stops routing through `resolveForWrite`. The method's own Javadoc acknowledges this ("calling it directly ... without running a still-guarded DDL mutation"), and the coupling is a deliberate consequence of the de-guarding being a later step.
- **Suggestion**: No change required this iteration — the direct-seam test is a reasonable stopgap while the public mutation path is still guarded. Once the de-guard lands (it does, in Step 3 / `SchemaDeguardTest`), consider re-expressing the seeding assertion through a public DDL call so the test pins the public contract, not the protected helper. Tracking note only.

## Evidence base
