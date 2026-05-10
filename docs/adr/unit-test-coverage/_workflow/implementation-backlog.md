# Unit Test Coverage — Core Module — Track Details

<!-- DO NOT DELETE THIS FILE. Its presence on disk signals the new
split-file plan format (see .claude/workflow/conventions.md §1.2).
Deleting it flips subsequent workflow operations into legacy mode.
Natural cleanup happens when the branch is deleted after PR merge. -->

## Track 22b: In-Track Dead-Code Deletion Lockstep

> **What** (deletion clusters — Strong candidates for in-track deletion,
> subject to PSI re-confirmation in 22b Phase A's adversarial review
> per the hybrid policy):
> - `sbtree/singlevalue/v1` (legacy V1 single-value B-tree)
> - `sbtree/local/v1` (legacy V1 local B-tree)
> - `DecimalKeyNormalizer` dead helpers
> - Binary Token / JWT cluster — see the Track 17 absorption block
>   for the full enumeration: the binary-token quintet (`BinaryToken`,
>   `BinaryTokenSerializer`, `BinaryTokenPayloadImpl`,
>   `BinaryTokenPayloadDeserializer`, `DistributedBinaryTokenPayload`)
>   plus the JWT trio (`JsonWebToken`, `JwtPayload`,
>   `YouTrackDBJwtHeader`) — already inert historically
> - Kerberos credential / Krb5 login module dead code
> - `ZIPCompressionUtil` (mechanical fix R2 from previous Phase A
>   iter-1: reclassified from "coverage target" to "deletion candidate"
>   — PSI find-usages reports zero callers across `core/`, `server/`,
>   `driver/`, `embedded/`, `tests/`)
> - Narrow singletons: `IndexConfigPropertyDeadCodeTest` →
>   `IndexConfigProperty`; `MockSerializerDeadCodeTest` →
>   `MockSerializer` (lockstep with `BinarySerializerFactory` registration
>   removal for `PropertyTypeInternal.EMBEDDED` id `-10`);
>   `RecordBytesTestOnlyOverloadTest` → test-only overload;
>   `CronExpressionDeadCodeTest` → `CronExpression.getTimeZone()`
>   lazy fallback (NOT the `setTimeZone(TimeZone)` setter — that stays
>   live); `IndexCursorClusterDeadCodeTest`; `EntityLinkSetImplTest`
>   partial dead methods only.
> - Iter-1 T2 reclassifications (previous Phase A iter-1 finding T2
>   reclassified these from "Defer" to "In-track"):
>   `LiveQueryBatchResultListener`,
>   `DatabaseLifecycleListenerAbstract`, `DatabaseRepair`,
>   `BonsaiTreeRepair`, `HookReplacedRecordThreadLocal`.
> - Track-9 forwarded clusters (subset of seven dead/semi-dead command-
>   script regions, deletion-eligible per PSI safe-delete):
>   `CommandExecutorScript` (719 LOC), `CommandScript` (114 LOC),
>   `CommandManager`'s class-based legacy dispatch cluster
>   (`commandReqExecMap` + `configCallbacks` +
>   `registerExecutor(Class,Class,...)` + `unregisterExecutor(Class)`
>   + `getExecutor(CommandRequestInternal)`),
>   `ScriptExecutorRegister` SPI (zero `META-INF/services` entries),
>   deprecated `ScriptManager.bind(...)` / `bindLegacyDatabaseAndUtil`
>   + `ScriptDocumentDatabaseWrapper` + `ScriptYouTrackDbWrapper`,
>   and `SQLScriptEngine.eval(Reader, Bindings)`. Items (e)
>   `ScriptInterceptor` / `ScriptInjection` SPIs are a partial-class-
>   trim case: keep the SPI, delete only the dead register/unregister
>   loops (mechanical fix R4 from previous Phase A iter-1).
> - Track-10 forwarded clusters: entire `core/query/live/` package
>   (`LiveQueryHookV2` listener + public-static surface) preserving
>   `LiveQueryHookV2.unboxRidbags` (live caller in
>   `CopyRecordContentBeforeUpdateStep.java:52`); three orphan listener
>   interfaces in `core/query/`; entire `core/fetch/` package.
> - Track-11 forwarded clusters: `CronExpression.getTimeZone()` lazy
>   fallback only (refined from Track 11's broader scope); deprecated
>   `Scheduler.{load, close, create}` interface methods + their three
>   `SchedulerProxy` overrides.
> - Track-12 forwarded deletion clusters (5 surfaces, `*DeadCodeTest`
>   shape-pinned): `(a)` `RecordSerializerCSVAbstract` instance API
>   (402 lines, 10.4% covered); `(b)` `RecordSerializerStringAbstract`
>   abstract instance API + four unused statics; `(c)` `JSONWriter`
>   (511 LOC, zero callers); `(d)` `Streamable` interface +
>   `StreamableHelper` (176 LOC, zero implementors); `(e)`
>   `SerializationThreadLocal` listener / shutdown path (54 LOC).
> - Track-13 forwarded deletion clusters (4 surfaces): `SerializableWrapper`,
>   `RecordSerializationDebug`, `RecordSerializationDebugProperty`,
>   `MockSerializer` (sentinel — already in narrow-singleton list above).
> - Track-22a-Phase-C forwarded deletion candidate:
>   `BasicCommandContext.copy()` — Track 22a's track-level review
>   (PF-2 / BC-22a-2) confirmed via PSI find-usages that the only
>   production caller is the recursive self-call at line 496; no
>   external production caller exists. The new
>   `BasicCommandContextStandaloneTest.copyWithNonNullChildDeepCopiesChildAndRewritesParent`
>   pin (F9) becomes obsolete when the method is deleted — the
>   atomic per-cluster commit deletes both. Partial-class-trim case
>   (keep `BasicCommandContext`, delete `copy()` + `setChild` /
>   `getChild` and the two pin tests). 22b Phase A re-confirms PSI
>   safe-delete classification before deletion (the method is on the
>   public `CommandContext` interface, so an external implementer
>   could still call it — verify with `mcp-steroid://ide/safe-delete`
>   against `CommandContext.copy`).
> - Track-22a-Phase-C forwarded license-header normalization (folded
>   into per-cluster commits, no separate step): when a 22b cluster's
>   atomic commit touches a directory containing files with the
>   inconsistent license headers surfaced by 22a's CQ7 / TS6 (the
>   7-line truncated stub, the malformed nested-asterisk shape, or
>   no-header) and the file is not deleted by the cluster, normalize
>   the header to the canonical 13-line form during the same commit.
>   Affected files inventory carried in 22a Phase C synthesis (CQ7):
>   `core/src/test/.../cache/AbstractMapCacheTest.java`,
>   `core/src/test/.../cache/RecordCacheWeakRefsTest.java`,
>   `core/src/test/.../collate/DefaultCollateFactoryTest.java`,
>   `core/src/test/.../conflict/RecordConflictStrategyFactoryTest.java`,
>   `core/src/test/.../id/RecordIdTest.java`,
>   `core/src/test/.../type/IdentityWrapperTest.java`,
>   `core/src/test/.../dictionary/DictionaryDeadCodeTest.java`,
>   `core/src/test/.../compression/CompressionInterfaceDeadCodeTest.java`,
>   plus tx/Gremlin tests with no header. Files deleted by 22b skip
>   normalization; the surviving subset is what gets the canonical
>   header.
>
> **What** (Defer to 22c — likely SPI / external-consumer risk per
> hybrid policy; YTDB issue created and marker rewritten, production
> source untouched in this branch):
> - Hooks cluster: `RecordHookAbstract`, `EntityHookAbstract`,
>   `LiveQueryHookStaticApi`, plus any T2-reclassified items where
>   22b Phase A re-confirms SPI risk.
> - Database-pool cluster: `DatabasePoolAbstract`, `DatabasePoolBase`.
> - Database-tool cluster: `DatabaseCompare`, `GraphRepair`,
>   `CheckIndexTool` (the others — `DatabaseRepair`, `BonsaiTreeRepair`
>   — were T2-reclassified to in-track-22b above).
> - Command-script SPI cluster: `ScriptInterceptor` / `ScriptInjection`
>   SPI tops if 22b Phase A's PSI re-confirmation shows external
>   consumers (vs the partial-class-trim approach).
> - Serializer-base cluster (the abstract bases that survive
>   Track 12's instance-API deletion): retain abstract-class shells if
>   external implementors depend on them.
>
> **How**:
> - **PSI safe-delete classification first.** For each cluster, run
>   the `mcp-steroid://ide/safe-delete` recipe against the production
>   class/method/package the cluster pins. Grep is NOT acceptable for
>   this classification — a missed external consumer (especially in
>   abstract base classes or SPIs) would corrupt the deletion claim.
> - **Hybrid policy** (full text under "Dead-code deletion policy"
>   below, ported from previous Phase A iter-1 clarifications):
>   Cluster meets ALL of: zero production callers (PSI), not part of
>   `com.jetbrains.youtrackdb.api`, not an abstract base / SPI service /
>   exception type, deletion does not require coordinated `server`/
>   `tests`/`embedded` changes → **delete in 22b**. Otherwise → **defer
>   to 22c**.
> - **Strong-Candidate `*IT.java` reference check** (mechanical fix A8
>   from previous Phase A iter-1): in addition to `core/src/test/`,
>   PSI find-usages MUST also cover `*IT.java` files in `core/`,
>   `server/`, and the `tests/` module — integration-test references
>   to dead code do exist in this codebase and must be confirmed
>   absent before deletion.
> - **Partial-class-trim tier** (mechanical fix R4): when a class has
>   both live and dead methods (e.g., `EntityLinkSetImpl` partial dead
>   methods, `ScriptInterceptor`/`ScriptInjection` register/unregister
>   loops with zero impls), delete only the dead methods + their pins;
>   keep the class. The atomic commit deletes the dead methods + the
>   `*DeadCodeTest.java` test methods that pinned them; live methods
>   and their tests remain unchanged.
> - **Per-cluster commit shape**: one step = one cluster = one commit.
>   The commit deletes the production class/method(s) + the
>   `*DeadCodeTest.java` (or its method subset for partial-class-trim) +
>   any `META-INF/services` entries the cluster registered (mechanical
>   fix T1 from previous Phase A iter-1: SPI services-file edits MUST
>   be in the per-cluster checklist for every SPI cluster — one was
>   missed in the iter-1 wording). Each commit is independently
>   bisectable.
> - **CHANGELOG / release-note convention** (mechanical fix A5): when
>   a deletion targets `internal/core` code reachable via the public
>   `api/` surface (e.g., the `BinaryToken` cluster), add a one-line
>   note to `CHANGELOG.md` under "Removed" describing the surface
>   removed and the migration path. Pure-internal deletions (no
>   `api/` reachability) skip CHANGELOG.
> - **Coverage-gate recompute.** After each in-track deletion lockstep
>   commit, re-run `coverage-analyzer.py` to refresh per-package
>   baselines — deleted dead lines drop out of the denominator and
>   displayed coverage may rise substantially without any new test
>   work. 22b's verification step (final commit) reconciles
>   pre- and post-deletion baselines.
> - **No-issue convention for in-track deletions** (carried from
>   previous Pre-Flight clarifications): when a cluster is deleted in
>   this branch, no YTDB issue is created — the deletion itself is the
>   resolution. Avoid issue churn for resolved-in-this-PR deletions.
>
> **Constraints**:
> - In-scope: only the production classes/methods + their
>   `*DeadCodeTest.java` shape pins + their `META-INF/services` entries.
>   No new tests added in 22b (test-additive coverage is 22a's scope);
>   the cluster's surviving live methods stay covered by 22a's tests.
> - Out-of-scope: any cluster whose PSI safe-delete classification
>   returns "Defer" — those flow to 22c.
> - **TinkerPop fork-shadowing forbid in new test files** (mechanical
>   fix R7): inherited from 22a — applies to any test additions that
>   slip into 22b (should be zero, but if a partial-class-trim case
>   needs a small test surface adjustment, the constraint applies).
> - **Per-test UUID DB name** (mechanical fix T5): inherited from 22a
>   — applies to any test surface adjustments.
>
> **Interactions**:
> - Depends on Track 1 (coverage measurement infrastructure).
> - Depends on Track 22a (consumes 22a's PSI safe-delete confirmations
>   from Phase A adversarial review and the post-22a coverage baseline
>   that 22b's per-cluster verification compares against).
> - Feeds 22c the final cluster-disposition list (which clusters are
>   in-track-deleted vs deferred).

### Dead-code deletion policy (full text)

> **Hybrid (cluster-by-cluster).** 22b's adversarial review classifies
> each dead-code cluster (the ~63 `*DeadCodeTest.java` files mapping
> to ~15–20 logical clusters) into one of two dispositions, using
> **PSI find-usages via `mcp-steroid` and the
> `mcp-steroid://ide/safe-delete` recipe** against the production
> class/method/package the cluster pins — grep is not acceptable for
> this classification because a missed external consumer (especially
> in abstract base classes or SPIs) would corrupt the deletion claim.
>
> **Deletion-in-22b (cluster meets ALL of these):**
> - PSI find-usages reports zero production callers (across this
>   repo, including `*IT.java` files per A8).
> - The class/method is not part of `com.jetbrains.youtrackdb.api`
>   (the public-API surface) — no `internal/api` package boundary
>   crossed.
> - The class is not an abstract base class designed for
>   subclassing, an SPI service interface (`META-INF/services`
>   registered), or an exception type that may be caught by
>   external code.
> - Deletion does not require coordinated changes in the `server`,
>   `tests`, or `embedded` modules beyond the `core` test source.
>
> **Issue-only-defer-to-22c (cluster fails ANY of the above):**
> - YTDB issue created with full deletion plan and consumer-search
>   notes (in 22c).
> - WHEN-FIXED marker rewritten from `Track 22` to `YTDB-NNNN`
>   (in 22c).
> - Production source untouched in this branch; deletion happens in
>   a dedicated follow-up PR with wider review.
>
> **Partial-class-trim** (mechanical fix R4): when a class has both
> live and dead methods, delete only the dead methods + their pins;
> keep the class. Treated as a deletion-in-22b case for the dead
> subset; the live subset stays covered by 22a's tests.

## Track 22c: WHEN-FIXED Issue Creation & Marker Rewrite

> **What**:
> - Open YTDB tracking issues for **production-fix WHEN-FIXED pins**:
>   the 101 non-dead-code markers across 164 test files, collapsed
>   to one issue per logical fix (per Pre-Flight clarification).
>   Multiple markers in a single test file usually pin the same
>   logical fix; granularity guidance is **one issue per logical
>   fix**, not one per marker.
> - Open YTDB tracking issues for the **SPI-deferred dead-code
>   clusters** that 22b classified as "issue-only-defer" per the
>   hybrid policy (Hooks, database-pool, database-tool subset,
>   command-script SPI tops if deferred, serializer-base survivors).
>   Each cluster gets one tracking issue with the full deletion plan
>   + consumer-search notes from 22b's PSI classification.
> - **Rewrite inline `// WHEN-FIXED: Track 22`** (and `// WHEN-FIXED:
>   deferred-cleanup track`) markers across `core/src/test/` to
>   reference the new YTDB-NNNN IDs. The rewrite filter excludes
>   markers in clusters DELETED by 22b (no-issue convention — the
>   `*DeadCodeTest.java` pins were deleted alongside their production
>   classes in 22b).
>
> **Production-fix pin inventory** (from previous Phase A iter-1
> R3/A6 — symmetric to dead-code policy; classify the ~30 inherited
> bugs from Tracks 7–21):
> - **In-22a-fix candidates** (production fix is local + test-additive,
>   handled in 22a, no YTDB issue needed in 22c): `BasicCommandContext.copy()`
>   null-child NPE; `CronExpression` parse-leniency edge cases;
>   `CompositeKeySerializer` Map-flatten preprocess negative branches;
>   `BinarySerializerFactory.create()` singleton harmonization;
>   `BinaryComparatorV0` DATE × LONG / DATETIME × DATE / DECIMAL × BYTE
>   / BOOLEAN × STRING asymmetries (production-side normalization).
> - **22c-defer (issue + marker rewrite)**: `LiveQueryHookV2.calculateProjections`
>   always-empty bug (DB-engine-wide projection plumbing); V1 `break`
>   vs V2 `continue` `InterruptedException` handling; `ScheduledEvent`
>   ctor swallows `ParseException`; `executeEventFunction` retry-loop
>   bug; `SchedulerImpl.onEventDropped` NPE; `BytesContainer`
>   zero-capacity infinite-loop hang; `RecordSerializerBinaryV1.deserializeEmbeddedAsDocument`
>   insecure deserialization; `EntitySerializerDelta.deserializeValue`
>   insecure deserialization; `EntitySerializerDelta` unbounded
>   item-count loops; `BinaryComparatorV0` DATE × STRING NFE crash;
>   `BytesContainer.deserializeValue` length-prefix validation gap;
>   `RecordSerializerBinary.fromStream(byte[])` AIOOBE + Base64-of-input
>   log-injection; `HelperClasses.readLinkCollection` NULL_RECORD_ID
>   dead branch; `RecordSerializationDebug*` `faildToRead` typo;
>   `CompactedLinkSerializer`/`LinkSerializer` `(short)` cluster-id
>   truncation; the seven Track-17 latent issues (the most recent
>   addition: `TokenSignImpl.readKeyFromConfig` unreachable inner
>   branch — tokens cannot be verified across server restarts because
>   configured `NETWORK_TOKEN_SECRETKEY` is silently ignored);
>   **MemoryAndLocalPaginatedEnginesInitializer.initialize() non-volatile
>   flag race** (surfaced via Track 22a Phase C BC-22a-3) — the
>   `private boolean initialized` field is read+written without volatile
>   or synchronization; two threads racing on `initialize()` can both
>   pass the `if (!initialized)` check and run `configureDefaults()`
>   twice, re-mutating shared `GlobalConfiguration.DISK_CACHE_SIZE` /
>   `WAL_RESTORE_BATCH_SIZE`. Pre-existing race; not introduced by 22a;
>   the 22a `MemoryAndLocalPaginatedEnginesInitializerTest` is marked
>   `@Category(SequentialTest)` to avoid surfacing it. Fix: declare
>   the field `volatile`, or convert to `AtomicBoolean`, or wrap the
>   init in `synchronized`. Issue body should pin the WHEN-FIXED
>   marker location once the test gains a regression-stress assertion.
>   **`RecordAbstract.dirty` public-field encapsulation** (surfaced
>   via Track 22a Phase C CQ19 — RecordCacheWeakRefsTest's F19 fix
>   needed direct field-write because `setDirty(long)` is gated by
>   `checkForBinding` and post-commit unbound entities cannot mutate
>   dirty via the public setter). The field is `public long` — a
>   leaked-implementation seam. Refactor options: (a) demote to
>   package-private + provide a package-internal `markDirty()` test
>   helper; (b) provide an unguarded `RecordAbstract#testOnlyForceDirty()`
>   package-private helper. Either closes the public-field smell
>   without losing the seam. Issue body must reference the
>   RecordCacheWeakRefsTest sites that need the helper migration.
>
> **WHEN-FIXED inventory at start of 22c** (from previous Pre-Flight):
> 164 distinct test files contain WHEN-FIXED markers (440 markers
> total across the core test sources). The 440 markers split into
> three buckets: ~63 `*DeadCodeTest.java` lockstep deletion pins (most
> rewritten away by 22b's deletions — 22c covers only the SPI-deferred
> subset of clusters), 101 non-dead-code production-fix / refactor
> pins (22c's primary scope), and ~276 in-file repeated markers /
> sub-pins / cross-references that fold into the per-issue grouping
> (multiple markers in one file usually pin one logical fix per the
> "one issue per logical fix" rule). 22c Phase A re-validates this
> arithmetic before issue creation begins.
>
> **How**:
> - **Issue field defaults for the YTDB project** (from
>   `get_issue_fields_schema` at previous Pre-Flight time): no required
>   custom fields, but Type=Bug for production-fix pins, Type=Task for
>   dead-code-deletion-deferred pins, Priority=Normal default; Subsystem
>   set per package/area as appropriate; State=Submitted on creation.
> - **Issue body shape** (always): (1) the test class file path and
>   method name(s) carrying the marker, (2) verbatim quote of the
>   WHEN-FIXED comment block from the test source so the issue is
>   self-contained, (3) one-line "what to do when the issue is fixed"
>   pointing at the test assertion that needs flipping, (4)
>   originating track reference (e.g., "Surfaced during Track NN").
> - **One commit per issue** (mechanical fix R8 from previous Phase A
>   iter-1): each issue creation + corresponding marker rewrite is its
>   own commit. The commit message names the YTDB ID (e.g., `WHEN-FIXED
>   marker rewrite for YTDB-1234 — TokenSignImpl readKeyFromConfig`).
>   This keeps the rewrite bisectable and makes per-issue revert
>   trivial if an issue is closed/reclassified post-PR.
> - **Mixed-marker file ordering** (mechanical fix T3 from previous
>   Phase A iter-1): when a test file carries WHEN-FIXED markers for
>   multiple logical fixes (e.g., `ScriptManagerTest`'s eight markers
>   mapping to ~5 distinct production issues), the rewrite step
>   processes them in **issue-order**, not file-order — i.e., one
>   commit per logical fix touches all files carrying that fix's
>   markers. The final cluster-disposition list from 22b is consumed
>   FIRST (so deleted-cluster markers are filtered out), THEN the
>   per-issue rewrites land in dependency order.
> - **No issues created during Pre-Flight or 22a** — the actual issue
>   creation and marker rewrite happen in 22c's steps.
> - **Verification step** (optional): final 22c step greps
>   `core/src/test/` for any unrewritten `// WHEN-FIXED: Track 22`
>   or `// WHEN-FIXED: deferred-cleanup track` markers; zero hits is
>   the gate. Any surviving marker means either the cluster was
>   deleted by 22b (and the marker was removed alongside the test
>   file — expected) or the rewrite missed a file (defect — fix in
>   the same step).
>
> **Constraints**:
> - In-scope: YTDB issue creation + marker rewrites in
>   `core/src/test/`. No production code changes.
> - Out-of-scope: in-22a-fix candidates (those landed in 22a as
>   test-additive production fixes); deleted clusters from 22b
>   (no-issue convention); markers in modules other than `core/`
>   (none expected — the inventory at start of 22c is `core/src/test/`
>   only).
> - **Issue body must include the verbatim WHEN-FIXED comment block**
>   so the issue is self-contained even if the test source later
>   evolves.
> - **No marker may reference a YTDB ID that was not created in this
>   branch** — strict invariant from previous Pre-Flight clarification.
>
> **Interactions**:
> - Depends on Track 1, Track 22a, Track 22b. 22c's marker-rewrite
>   filter consumes 22b's final cluster-disposition list as input.
> - Closes the deferred-cleanup queue's tracker-hygiene tail.
> - No coverage delta expected; this is tracker hygiene.

### WHEN-FIXED clarification (full text — carried from previous Pre-Flight)

> Track 22 must include a dedicated step (and/or sub-step within each
> coverage step) that creates a YouTrack `YTDB`-project issue for each
> WHEN-FIXED test currently pinned in `core/src/test/`, then rewrites
> the inline `// WHEN-FIXED: Track 22` (and `// WHEN-FIXED: deferred-
> cleanup track`) marker to reference the issue ID — e.g.,
> `// WHEN-FIXED: YTDB-NNNN`. The marker rewrite and the issue
> creation must be in lockstep: every issue created must have its ID
> threaded back into the corresponding test source within Track 22c,
> and no rewritten marker may reference a YTDB ID that was not created
> in this branch.
>
> **Cross-step interaction:** the WHEN-FIXED rewrite step must run
> **before** any dead-code lockstep deletion in 22b — except that the
> split has reversed the ordering: 22b runs first (deletes the
> SPI-safe clusters and their `*DeadCodeTest.java` pins), then 22c's
> rewrite consumes 22b's final cluster-disposition list as the
> filter. The deletion-by-22b clusters skip issue creation entirely
> (the deletion itself is the resolution); only the deferred-by-22b
> clusters get YTDB issues. This is option (a) of the previous
> Pre-Flight's two-option choice — chosen because it avoids issue
> churn for resolved-in-this-PR deletions.
